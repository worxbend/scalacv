package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f}

/** Which method OpenCV's `solvePnP` should use — the typed replacement for its `SOLVEPNP_*` int flags.
  *
  * The choice is not cosmetic: each solver has its own degenerate-input behaviour (some answer `ok = false`,
  * some abort with a native `CV_Assert` — see [[Pnp.solve]]), so the flag is part of a call site's error
  * story and deserves a name, not a number. Only the solvers this library has a use for (or a caller is
  * plausibly choosing between) are listed; the rest of OpenCV's dozen are experimental or deprecated
  * upstream.
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

  /** Direct least squares with a refinement pass; robust when the points may be mismatched. */
  case DLS extends PnpSolver(Calib3d.SOLVEPNP_DLS)

  /** Exhaustive PnP — samples and keeps the best; slower, steadier on noisy correspondences. */
  case UPnP extends PnpSolver(Calib3d.SOLVEPNP_UPNP)

  /** Infinitesimal plane-based pose — exactly four coplanar points. */
  case IPPE extends PnpSolver(Calib3d.SOLVEPNP_IPPE)

  /** IPPE specialised to a square — faster and steadier on a flat tag; the marker-AR solver. */
  case IppeSquare extends PnpSolver(Calib3d.SOLVEPNP_IPPE_SQUARE)

  /** SQPnP — the most accurate general solver OpenCV ships, at a small extra cost over [[Iterative]]. */
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
    Managed.scope: own =>
      val obj = own(MatOfPoint3f(objectPoints.map(_.toCv)*))
      val img = own(MatOfPoint2f(imagePoints.map(_.toCv)*))
      val camera = own(intrinsics.cameraMatrix)
      val distortion = own(intrinsics.distCoeffs)
      val rvec = own(Mat())
      val tvec = own(Mat())
      Cv.attempt(s"solvePnP($solver)"):
        val ok = Calib3d.solvePnP(obj, img, camera, distortion, rvec, tvec, false, solver.cvValue)
        Option.when(ok)(decode(own, rvec, tvec))
