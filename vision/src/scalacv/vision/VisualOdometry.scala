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
    * geometry is degenerate.
    *
    * @param intrinsics
    *   the pinhole camera model — use [[Intrinsics.approx]] when the camera is uncalibrated.
    */
  def estimate(from: Seq[Point], to: Seq[Point], intrinsics: Intrinsics): Option[CameraMotion] =
    require(from.size == to.size, s"from and to must be the same length, got ${from.size} and ${to.size}")
    if from.size < 5 then None
    else
      // `Managed.scope` owns every Mat below: each is registered as it is built, so a throw from a later
      // constructor — or from findEssentialMat, which does throw on degenerate input — frees the earlier
      // ones.
      Managed.scope: own =>
        val pts1 = own(MatOfPoint2f(from.map(_.toCv)*))
        val pts2 = own(MatOfPoint2f(to.map(_.toCv)*))
        val camera = own(intrinsics.cameraMatrix)
        val essential = own(Cv.orThrow("findEssentialMat"):
          Calib3d.findEssentialMat(pts1, pts2, camera, Calib3d.RANSAC, 0.999, 1.0))
        if essential.empty || essential.rows < 3 || essential.cols < 3 then None
        else
          val rotation = own(Mat())
          val translation = own(Mat())
          val inliers = Cv.orThrow("recoverPose"):
            Calib3d.recoverPose(essential, pts1, pts2, camera, rotation, translation)
          Some(CameraMotion(Mats.readMatrix(rotation, 3, 3), Mats.readColumn(translation, 3), inliers))
