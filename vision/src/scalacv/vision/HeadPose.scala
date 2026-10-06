package scalacv.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat

import scalacv.*

/* Head orientation (yaw / pitch / roll) from a detected face's five landmarks, via `solvePnP` against a
 * canonical 3D face model — no extra model file, reusing what [[FaceDetect]] already gives you.
 */

/** Head orientation in degrees — the classic yaw / pitch / roll. */
final case class HeadPose(yaw: Double, pitch: Double, roll: Double)

/** Head-pose estimation from a detected [[Face]]'s five landmarks, via `solvePnP` against a canonical 3D face
  * model. No extra model file — it reuses what [[FaceDetect]] already gives you.
  *
  * The 3D reference is an approximate generic head, so the angles are indicative rather than metric: good for
  * "looking left / up / tilted", not for a calibrated measurement. For that, a dedicated head-pose network
  * (run through [[Dnn]]) and a calibrated camera matrix are the way.
  */
object HeadPose:

  // Canonical 3D landmark positions (arbitrary units), in the same order as Face's landmarks:
  // right eye, left eye, nose tip, right mouth corner, left mouth corner. X→image-right, Y→image-down,
  // Z→away from the camera; the nose tip is the origin and protrudes toward the viewer.
  private val model = Seq(
    Point3(-45, -34, 27), // right eye  (subject's right -> image left)
    Point3(45, -34, 27), // left eye
    Point3(0, 0, 0), // nose tip
    Point3(-30, 35, 22), // right mouth corner
    Point3(30, 35, 22) // left mouth corner
  )

  /** Estimates the head orientation for `face` under the given camera [[Intrinsics]], or `None` if `solvePnP`
    * fails to converge (degenerate landmarks). Pass a calibrated model for a sharper result; for a quick
    * uncalibrated guess use the [[Size]] overload.
    */
  def estimate(face: Face, intrinsics: Intrinsics): Option[HeadPose] =
    // Pnp.solve owns and frees every Mat the solve needs; the decode registers its two extras with the same
    // scope. The Left is folded to None too: degenerate landmarks can make OpenCV *throw* rather than return
    // `ok = false`, and the documented contract here is `None` on failure, not a raw CvException. EPnP is the
    // solver this five-point, real-time-shaped problem is for — see PnpSolver.EPnP.
    Pnp
      .solve(model, face.landmarks, intrinsics, PnpSolver.EPnP) { (own, rvec, _) =>
        val rotation = own(Mat())
        Calib3d.Rodrigues(rvec, rotation)
        // RQDecomp3x3 returns the Euler angles (degrees) about x, y, z. mtxR and mtxQ are the
        // decomposition's factors, which this only needs as somewhere for OpenCV to write.
        val euler = Calib3d.RQDecomp3x3(rotation, own(Mat()), own(Mat()))
        HeadPose(yaw = euler(1), pitch = euler(0), roll = euler(2))
      }
      .getOrElse(None)

  /** Estimates the head orientation for `face` in an image of `imageSize`, using [[Intrinsics.approx]]'s
    * uncalibrated pinhole guess — focal length from a 60° horizontal field of view, principal point at the
    * centre, no lens distortion. That is the library's one uncalibrated guess, shared with `Ar` and
    * `Localizer`, so the angles agree with anything else built on `approx`. Enough for "looking left / up /
    * tilted"; pass real [[Intrinsics]] when you have them.
    */
  def estimate(face: Face, imageSize: Size): Option[HeadPose] =
    estimate(face, Intrinsics.approx(imageSize))
