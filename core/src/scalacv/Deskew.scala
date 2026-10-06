package scalacv

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/* The deskew document pipeline: detect the dominant text skew and rotate the page upright, the classic OCR
 * pre-step. Lives apart from Ops.scala because it is a *pipeline* — grayscale, Otsu, findNonZero,
 * minAreaRect, warp — rather than a single imgproc verb, while sharing the same ownership contract: the
 * receiver is only read from and the result is a fresh `Managed[Mat]` the caller owns.
 *
 * The final warp goes through `warpAboutCenter` in Ops.scala rather than a private copy here: `rotated`
 * (a core geometric verb, which stays in Ops) already assembles exactly the getRotationMatrix2D + warpAffine
 * rotation this pipeline ends with, and the two differ only in canvas policy. Duplicating the geometry to
 * keep this file self-contained would be two places to fix the same rotation maths, so the helper is
 * `private[scalacv]` there and called from here.
 */
extension (self: Mat)

  /** Detects the dominant text skew and rotates the image upright — the classic OCR pre-step. Works on any
    * image: it binarises internally to find the text pixels, fits a minimum-area rectangle to them, and
    * rotates by that tilt. The exposed corners are filled white, and a detected skew beyond `maxAngle` is
    * treated as a misread and left alone (a page of large graphics can fool the estimate).
    */
  def deskew(maxAngle: Double = 45.0): Managed[Mat] =
    require(maxAngle > 0 && maxAngle <= 90, s"maxAngle must be in (0, 90], got $maxAngle")
    // The scope owns the working Mats; the rotated result this returns is allocated outside it and escapes.
    Managed.scope: own =>
      // Otsu-inverted, so the text becomes white and `findNonZero` reads the ink rather than the page.
      val bin = own.adopt(
        Mats.grayscale(self).pipe(_.threshold(0, 255, Threshold.otsu(Threshold.Mode.BinaryInv)).image)
      )
      val coords = own(Mat())
      Cv.orThrow("deskew.findNonZero")(Core.findNonZero(bin, coords))
      if coords.rows == 0 then Managed(self.clone()) // a blank page — nothing to straighten
      else
        val pts = own(org.opencv.core.MatOfPoint2f())
        Cv.orThrow("deskew.convertTo")(coords.convertTo(pts, CvType.CV_32F))
        val skew = normalizeSkew(Cv.orThrow("deskew.minAreaRect")(Imgproc.minAreaRect(pts)).angle)
        if math.abs(skew) < 0.1 || math.abs(skew) > maxAngle then Managed(self.clone())
        else
          // A deskew corrects a degree or two and must not change the frame — hence expandCanvas = false,
          // white outside, where `rotated` grows the canvas to keep every corner.
          warpAboutCenter(
            self,
            skew,
            scale = 1.0,
            expandCanvas = false,
            Interpolation.Linear,
            BorderType.Constant,
            Scalar.White,
            "deskew"
          )

  /** Folds a `minAreaRect` angle into the equivalent tilt in `(-45, 45]`. */
  private def normalizeSkew(angle: Double): Double =
    val a = angle % 90.0
    if a > 45.0 then a - 90.0 else if a <= -45.0 then a + 90.0 else a
