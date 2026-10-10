package scalacv

import org.opencv.core.{Mat, MatOfDouble}

/** A pinhole camera's intrinsics — what turns a pixel measurement into a metric one.
  *
  * `fx`/`fy` are the focal length in pixels, `cx`/`cy` the principal point (usually near the image centre).
  * All calibration values must be finite; focal lengths must be positive. `distortion` is OpenCV's
  * radial/tangential coefficients (`k1, k2, p1, p2[, k3 …]`); leave it empty for an ideal lens. Only the
  * counts OpenCV itself accepts are allowed — see [[Intrinsics.ValidDistortionSizes]]. A real camera's
  * numbers come from a chessboard calibration; when you have not calibrated, [[Intrinsics.approx]] gives a
  * serviceable guess from the image size and a field-of-view estimate — good enough to *see* an augmented
  * overlay track, not good enough to *measure* with.
  *
  * This is the core camera model the vision layer builds on: `scalacv.vision.Ar`, `HeadPose` and `Localizer`
  * all take an `Intrinsics`, `scalacv.vision.Calibration` produces one, and `Image.undistort` consumes one.
  */
final case class Intrinsics(
    fx: Double,
    fy: Double,
    cx: Double,
    cy: Double,
    distortion: Seq[Double] = Seq.empty
):
  require((Seq(fx, fy, cx, cy) ++ distortion).forall(_.isFinite), "camera calibration must be finite")
  require(fx > 0 && fy > 0, s"focal lengths must be positive, got fx=$fx fy=$fy")
  require(
    Intrinsics.ValidDistortionSizes.contains(distortion.size),
    // The sizes are a Set (membership is the only operation), so the message sorts them itself.
    s"distortion must have ${Intrinsics.ValidDistortionSizes.toSeq.sorted.mkString(", ")} coefficients " +
      s"(k1, k2, p1, p2[, k3[, k4, k5, k6[, s1, s2, s3, s4[, taux, tauy]]]]), got ${distortion.size}. " +
      "Leave it empty for an ideal lens."
  )

  /** The 3×3 camera matrix as a caller-owned `CV_64F` Mat.
    *
    * The write is guarded for the reason `Mats.column`'s is: between the bare `Mat.zeros` and the caller
    * taking ownership there is nobody to free it, so a throwing `put` would strand a native buffer no one
    * ever saw.
    */
  private[scalacv] def cameraMatrix: Mat =
    val m = Mat.zeros(3, 3, org.opencv.core.CvType.CV_64F)
    try
      m.put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0): Unit
      m
    catch
      case e: Throwable =>
        m.release()
        throw e

  /** The distortion coefficients as a caller-owned `MatOfDouble` (empty ⇒ no distortion).
    *
    * Guarded on the same grounds as [[cameraMatrix]]: the varargs constructor allocates natively first and
    * can still throw while copying the coefficients in.
    */
  private[scalacv] def distCoeffs: MatOfDouble =
    if distortion.isEmpty then MatOfDouble()
    else
      val m = MatOfDouble()
      try
        m.fromArray(distortion*)
        m
      catch
        case e: Throwable =>
          m.release()
          throw e

object Intrinsics:

  /** The coefficient counts OpenCV's `undistort`, `solvePnP` and `projectPoints` accept: the four
    * radial/tangential terms `k1, k2, p1, p2`, optionally extended with `k3`, then the rational model's
    * `k4, k5, k6`, then the thin-prism `s1..s4`, then the tilted-sensor `taux, tauy`. Zero means an ideal
    * lens and is accepted too.
    *
    * Checked in the constructor because OpenCV does not fail usefully on a wrong count. A five-element vector
    * with a coefficient dropped is still a legal length, so the native call runs and returns a silently wrong
    * undistortion or pose -- a number that looks plausible and is not, which is far more expensive to track
    * down than a rejected constructor. This mirrors the focal-length check directly above it: reject what
    * cannot be a camera, at the point the value is made rather than at the point it is used, which may be
    * several layers away.
    */
  val ValidDistortionSizes: Set[Int] = Set(0, 4, 5, 8, 12, 14)

  /** A rough camera model from the image size and horizontal field of view. Assumes a centred principal
    * point, square pixels and no lens distortion — fine for a live AR overlay, not for metrology.
    */
  def approx(imageSize: Size, horizontalFovDegrees: Double = 60.0): Intrinsics =
    require(imageSize.width > 0 && imageSize.height > 0, "imageSize must be positive")
    require(horizontalFovDegrees > 0 && horizontalFovDegrees < 180, "field of view must be in (0, 180)")
    val f = (imageSize.width / 2.0) / math.tan(math.toRadians(horizontalFovDegrees / 2.0))
    Intrinsics(f, f, imageSize.width / 2.0, imageSize.height / 2.0)
