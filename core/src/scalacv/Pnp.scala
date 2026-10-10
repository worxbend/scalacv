package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f}

/** Which method OpenCV's `solvePnP` should use — the typed replacement for its `SOLVEPNP_*` int flags.
  *
  * The choice is not cosmetic: each solver has its own degenerate-input behaviour (some answer `ok = false`,
  * some abort with a native `CV_Assert` — see [[Pnp.solve]]), so the flag is part of a call site's error
  * story and deserves a name, not a number. This is a subset of OpenCV's solvers, not its full catalog. See
  * https://docs.opencv.org/4.x/d5/d1f/calib3d_solvePnP.html for each solver's point-count and geometry
  * requirements; in particular, DLS and UPnP are fallback aliases in OpenCV, not distinct robust estimators.
  */
enum PnpSolver(val cvValue: Int):

  /** Levenberg-Marquardt refinement of a DLT estimate — the general default. Aborts natively on four or five
    * non-coplanar points ("needs at least 6 points"), so pair it with a `Left`-tolerant caller.
    */
  case Iterative extends PnpSolver(Calib3d.SOLVEPNP_ITERATIVE)

  /** Efficient PnP — O(n) EPnP with no refinement pass. The usual choice for real-time pose from four or more
    * points (head pose from five landmarks).
    */
  case EPnP extends PnpSolver(Calib3d.SOLVEPNP_EPNP)

  /** OpenCV's DLS flag currently falls back to EPnP because the DLS implementation is broken. Prefer
    * [[EPnP]]; this flag does not add refinement or reject mismatched correspondences.
    */
  case DLS extends PnpSolver(Calib3d.SOLVEPNP_DLS)

  /** OpenCV's UPnP flag currently falls back to EPnP because its implementation is broken. It does not
    * estimate focal length in that fallback; prefer [[EPnP]] with calibrated [[Intrinsics]].
    */
  case UPnP extends PnpSolver(Calib3d.SOLVEPNP_UPNP)

  /** Infinitesimal plane-based pose — at least four coplanar points, not necessarily a square. */
  case IPPE extends PnpSolver(Calib3d.SOLVEPNP_IPPE)

  /** IPPE specialised to a square — faster and steadier on a flat tag; the marker-AR solver. */
  case IppeSquare extends PnpSolver(Calib3d.SOLVEPNP_IPPE_SQUARE)

  /** Globally optimal SQPnP, with at least three point correspondences. */
  case SQPnP extends PnpSolver(Calib3d.SOLVEPNP_SQPNP)

/** The shared `solvePnP` ceremony.
  *
  * Every absolute-pose recovery — a marker's pose, a head's orientation, the camera's own localization — runs
  * the same six-Mat ritual: wrap the object and image points, own the camera matrix and the distortion
  * coefficients, own the rvec/tvec outputs, solve, and decode the outputs *before* the scope releases them.
  * Written once here so the ownership story and the `Cv.attempt` guard live in one place; each caller keeps
  * only what actually differs — the solver and the decode.
  */
private[scalacv] object Pnp:

  /** Runs `solvePnP` for `objectPoints`/`imagePoints` under `intrinsics` with `solver`, decoding with
    * `decode` while the output Mats (and the scope owning them) are still alive. `decode` receives the scope
    * so it can own any further Mats the decode itself needs (a rotation matrix, the RQ decomposition's factor
    * sinks).
    *
    * The result is two-level on purpose: `Left` when the solver *throws* — some solvers abort with a native
    * `CV_Assert` on degenerate input instead of returning `ok = false`, and that arrives as a CvException,
    * not even a [[CvError]] — `Right(None)` when the solver declines (`ok = false`), and `Right(Some(…))`
    * with the decoded pose otherwise. The caller picks the policy: fold the `Left` into `None` where the
    * contract is "`None` on failure" (`HeadPose.estimate`, `Localizer.locate`), or rethrow it where a native
    * failure is a bug worth naming (`Ar.estimatePose`).
    */
  def solve[A](
      objectPoints: Seq[Point3],
      imagePoints: Seq[Point],
      intrinsics: Intrinsics,
      solver: PnpSolver = PnpSolver.Iterative
  )(decode: (Managed.Scope, Mat, Mat) => A): Either[CvError, Option[A]] =
    require(objectPoints.size == imagePoints.size, "need one image point per object point")
    val world = objectPoints.map(p => Vector(p.x, p.y, p.z))
    val pixels = imagePoints.map(p => Vector(p.x, p.y, 0.0))
    val minimum = if solver == PnpSolver.SQPnP then 3 else 4
    if world.distinct.size < minimum || pixels.distinct.size < minimum || !hasPlane(world) || !hasPlane(
        pixels
      )
    then return Right(None)
    Managed.scope: own =>
      val obj = own(MatOfPoint3f(objectPoints.map(_.toCv)*))
      val img = own(MatOfPoint2f(imagePoints.map(_.toCv)*))
      val camera = own(intrinsics.cameraMatrix)
      val distortion = own(intrinsics.distCoeffs)
      val rvec = own(Mat())
      val tvec = own(Mat())
      Cv.attempt(s"solvePnP($solver)"):
        val ok = Calib3d.solvePnP(obj, img, camera, distortion, rvec, tvec, false, solver.cvValue)
        val accepted = ok && Mats.readColumn(rvec, 3).forall(_.isFinite) &&
          Mats.readColumn(tvec, 3).forall(_.isFinite) &&
          acceptable(own, obj, imagePoints, camera, distortion, rvec, tvec)
        Option.when(accepted)(decode(own, rvec, tvec))

  // Require rank >= 2, not rank 3: planar markers are a supported and important case.
  private def hasPlane(points: Seq[Vector[Double]]): Boolean =
    if points.size < 3 || !points.flatten.forall(_.isFinite) then false
    else
      val origin = points.head
      val offsets = points.map(p => p.zip(origin).map(_ - _))
      val scale = offsets.flatten.map(math.abs).max
      if !scale.isFinite || scale == 0 then false
      else
        val normalized = offsets.map(_.map(_ / scale))
        val axis = normalized.maxBy(v => v.map(x => x * x).sum)
        normalized.exists: v =>
          val cross = Vector(
            axis(1) * v(2) - axis(2) * v(1),
            axis(2) * v(0) - axis(0) * v(2),
            axis(0) * v(1) - axis(1) * v(0)
          )
          cross.map(x => x * x).sum > 1e-12

  private def acceptable(
      own: Managed.Scope,
      obj: MatOfPoint3f,
      pixels: Seq[Point],
      camera: Mat,
      distortion: org.opencv.core.MatOfDouble,
      rvec: Mat,
      tvec: Mat
  ): Boolean =
    val rotation = own(Mat())
    Calib3d.Rodrigues(rvec, rotation)
    val r = Mats.readMatrix(rotation, 3, 3)
    val t = Mats.readColumn(tvec, 3)
    val inFront = obj.toArray.forall(p => r(2)(0) * p.x + r(2)(1) * p.y + r(2)(2) * p.z + t(2) > 1e-9)
    val projected = own(MatOfPoint2f())
    Calib3d.projectPoints(obj, rvec, tvec, camera, distortion, projected)
    val errors = projected.toArray.toSeq.zip(pixels).map((a, b) => math.hypot(a.x - b.x, a.y - b.y))
    // A generous generic sanity gate, not a metrology confidence interval. Callers needing tighter
    // accuracy must evaluate residuals under their own sensor/noise model.
    val span =
      math.hypot(pixels.map(_.x).max - pixels.map(_.x).min, pixels.map(_.y).max - pixels.map(_.y).min)
    inFront && errors.forall(_.isFinite) && math
      .sqrt(errors.map(e => e * e).sum / errors.size) <= math.max(8.0, span * 0.05)
