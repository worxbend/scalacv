package scalacv.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f}

import scalacv.*

/** The camera's motion between two frames: a 3×3 rotation and a translation direction, with the inlier count.
  *
  * From a single camera the translation is only known **up to scale** (you cannot tell a small nearby motion
  * from a large distant one), so `translation` is a unit direction, not metres. Fuse it with wheel odometry,
  * IMU, or a known baseline to recover scale.
  */
final case class CameraMotion(rotation: Seq[Seq[Double]], translation: Seq[Double], inliers: Int):

  /** The first-camera-frame to second-camera-frame mapping: `x_2 = R * x_1 + t`.
    *
    * Translation from [[VisualOdometry.estimate]] is a unit direction, NOT a metric displacement. This view
    * does not recover scale: choose a common scale before transforming metric landmarks or composing with
    * metric [[CameraPose]]/[[Pose3D]] transforms. The original direction is not normalised or otherwise
    * changed by this wrapper.
    */
  val transform: RigidTransform = RigidTransform(rotation, translation)

/** Monocular visual odometry — estimating how the camera moved between two frames from matched point
  * correspondences, via the essential matrix and `recoverPose`.
  *
  * Pair it with [[OpticalFlow]] (track points frame to frame) or [[Features]] (detect and match): those give
  * the correspondences, this turns them into motion. Chaining the per-frame motions is dead-reckoning
  * odometry; making it drift-free SLAM needs a back end (keyframes, loop closure, bundle adjustment) that is
  * beyond OpenCV — see the navigation guide.
  */
object VisualOdometry:

  /** Estimates the camera motion that carries the `from` points to the `to` points (same length, matched
    * order), given the camera [[Intrinsics]]. `None` when there are too few correspondences (< 5) or the
    * geometry is degenerate, translation unobservable, or supported essential candidates are ambiguous. See
    * [[estimateCandidates]] for the support/parallax policy and minimal-input alternatives.
    *
    * @param intrinsics
    *   the pinhole camera model — use [[Intrinsics.approx]] when the camera is uncalibrated.
    */
  def estimate(from: Seq[Point], to: Seq[Point], intrinsics: Intrinsics): Option[CameraMotion] =
    val ranked = estimateCandidates(from, to, intrinsics)
    ranked.headOption.filter: best =>
      !ranked
        .drop(1)
        .exists(other =>
          other.inliers == best.inliers &&
            (other.translation.zip(best.translation).map((x, y) => (x - y) * (x - y)).sum > 1e-4 ||
              other.rotation.flatten.zip(best.rotation.flatten).map((x, y) => (x - y) * (x - y)).sum > 1e-4)
        )

  /** Supported pose hypotheses, strongest support first. Five-point input often has several equally supported
    * solutions: these are alternatives, NOT independent measured motions. [[estimate]] returns None on
    * materially different equal-support alternatives; a caller may disambiguate with more points or external
    * motion priors. Requires five cheirality inliers and median rotation-compensated normalized parallax >
    * 0.001 (a heuristic observability threshold). Distortion and the RANSAC mask are respected.
    */
  def estimateCandidates(from: Seq[Point], to: Seq[Point], intrinsics: Intrinsics): Seq[CameraMotion] =
    require(from.size == to.size, s"from and to must be the same length, got ${from.size} and ${to.size}")
    if from.size < 5 || !(from ++ to).forall(p => p.x.isFinite && p.y.isFinite) ||
      from.distinct.size < 5 || to.distinct.size < 5 ||
      from.zip(to).forall((a, b) => math.hypot(a.x - b.x, a.y - b.y) < 1e-6)
    then Seq.empty
    else
      Cv.attempt("visual odometry") {
        Managed.scope: own =>
          val pts1 = own(MatOfPoint2f(from.map(_.toCv)*))
          val pts2 = own(MatOfPoint2f(to.map(_.toCv)*))
          val camera = own(intrinsics.cameraMatrix)
          val distortion = own(intrinsics.distCoeffs)
          val a = own(MatOfPoint2f())
          val b = own(MatOfPoint2f())
          Calib3d.undistortPoints(pts1, a, camera, distortion)
          Calib3d.undistortPoints(pts2, b, camera, distortion)
          val identity = own(Mat.eye(3, 3, org.opencv.core.CvType.CV_64F))
          val robustMask = own(Mat())
          val essential = own(
            Calib3d.findEssentialMat(
              a,
              b,
              identity,
              Calib3d.RANSAC,
              0.999,
              1.0 / math.min(intrinsics.fx, intrinsics.fy),
              1000,
              robustMask
            )
          )
          if essential.empty || essential.cols != 3 || essential.rows % 3 != 0 then Seq.empty
          else
            val candidates = (0 until essential.rows by 3).flatMap: row =>
              Managed.scope: candidateOwn =>
                val e = candidateOwn(essential.rowRange(row, row + 3))
                val mask = candidateOwn(robustMask.clone())
                val rotation = candidateOwn(Mat())
                val translation = candidateOwn(Mat())
                val support = Calib3d.recoverPose(e, a, b, identity, rotation, translation, mask)
                val r = Mats.readMatrix(rotation, 3, 3)
                val t = Mats.readColumn(translation, 3)
                val parallax = a.toArray
                  .zip(b.toArray)
                  .zipWithIndex
                  .collect:
                    case ((p, q), i) if mask.get(i, 0)(0) != 0 =>
                      val ray = Vector(p.x, p.y, 1.0)
                      val rotated = r.map(v => v.zip(ray).map(_ * _).sum)
                      math.hypot(rotated(0) / rotated(2) - q.x, rotated(1) / rotated(2) - q.y)
                // Rotation-compensated normalized displacement: 0.001 radians is a heuristic
                // observability gate, not a confidence interval or metric translation accuracy.
                val usable = support >= 5 && r.flatten.forall(_.isFinite) && t.forall(_.isFinite) &&
                  parallax.nonEmpty && parallax.sorted.apply(parallax.length / 2) > 0.001
                Option.when(usable)(CameraMotion(r, t, support))
            candidates.sortBy(-_.inliers)
      }.getOrElse(Seq.empty)
