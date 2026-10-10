package scalacv.vision

import org.opencv.core.{CvType, Mat}

import scalacv.*

/** Owned stereo disparity in pixels, independent of display normalization. Close after use. Nonfinite and
  * nonpositive samples are invalid, not evidence of free space. Not thread-safe.
  */
final class DisparityMeasurement private (
    private val values: Managed[Mat],
    private val mask: Managed[Mat]
) extends AutoCloseable:
  /** Width in pixels; access after close is rejected. */
  def width: Int = values.get.cols

  /** Height in pixels; access after close is rejected. */
  def height: Int = values.get.rows

  /** A valid disparity in pixels, or None for an invalid correspondence. */
  def at(x: Int, y: Int): Option[Double] =
    require(x >= 0 && x < width && y >= 0 && y < height, "sample outside disparity map")
    Option.when(mask.get.get(y, x)(0) != 0)(values.get.get(y, x)(0))

  /** Caller-owned copy of the CV_32FC1 pixel disparities (invalid samples are zero). */
  def pixelsCopy: Managed[Mat] = Managed(values.get.clone())

  /** Caller-owned CV_8UC1 validity mask: 255 valid, zero unknown. */
  def validityCopy: Managed[Mat] = Managed(mask.get.clone())

  /** Display-only image using an explicit fixed pixel scale; invalid pixels are black. This is not metric
    * depth: depth additionally requires calibrated focal length and baseline.
    */
  def visualize(maxDisparityPixels: Double): Image =
    require(
      maxDisparityPixels.isFinite && maxDisparityPixels > 0,
      "display scale must be finite and positive"
    )
    val out = Managed(Mat())
    try
      values.get.convertTo(out.get, CvType.CV_8U, 255.0 / maxDisparityPixels)
      Image.wrap(out)
    catch
      case e: Throwable =>
        out.release()
        throw e

  /** Releases both native buffers. Idempotent. */
  def close(): Unit =
    try values.release()
    finally mask.release()

object DisparityMeasurement:
  /** Copies a borrowed CV_32FC1 pixel-disparity matrix, deriving an independent validity mask. Positive
    * finite values are valid. Zero, negative and nonfinite values are unknown.
    */
  def fromPixels(pixels: Mat): DisparityMeasurement =
    require(
      pixels.dims() == 2 && !pixels.empty() && pixels.`type`() == CvType.CV_32FC1,
      "disparity must be nonempty 2-D CV_32FC1 in pixels"
    )
    val values = Managed(pixels.clone())
    try
      val mask = Managed(Mat.zeros(pixels.rows, pixels.cols, CvType.CV_8UC1))
      try
        val row = new Array[Float](pixels.cols)
        val valid = new Array[Byte](pixels.cols)
        for y <- 0 until pixels.rows do
          values.get.get(y, 0, row)
          for x <- row.indices do
            valid(x) = (if row(x).isFinite && row(x) > 0 then 255 else 0).toByte
            if valid(x) == 0 then row(x) = 0
          values.get.put(y, 0, row)
          mask.get.put(y, 0, valid)
        new DisparityMeasurement(values, mask)
      catch
        case e: Throwable =>
          mask.release()
          throw e
    catch
      case e: Throwable =>
        values.release()
        throw e
