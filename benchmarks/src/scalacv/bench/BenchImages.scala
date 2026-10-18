package scalacv.bench

import org.opencv.core.{CvType, Mat, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** Deterministic fixtures for the benchmarks — generated from a fixed seed so a run on any machine sees the
  * same pixels, and so the pixel-exact differ has something stable to hash. No image assets ship in this repo
  * (see `examples/Fixtures`), so everything is drawn or filled programmatically.
  */
object BenchImages:

  /** A deterministic BGR scene of the given size with hard edges and gradients — realistic input for
    * colour/edge/resize pipelines. Caller owns the returned Mat.
    */
  def scene(width: Int, height: Int): Mat =
    val m = Mat(height, width, CvType.CV_8UC3, CvScalar(30, 40, 50))
    // A handful of filled shapes so the image is neither flat nor random noise.
    var i = 0
    while i < 12 do
      val x = (i * 6151) % width
      val y = (i * 2749) % height
      val w = 20 + (i * 37) % (width / 3 + 1)
      val h = 20 + (i * 53) % (height / 3 + 1)
      Imgproc.rectangle(
        m,
        org.opencv.core.Point(x, y),
        org.opencv.core.Point(x + w, y + h),
        CvScalar((i * 40) % 256, (i * 90) % 256, (i * 150) % 256),
        -1
      )
      i += 1
    m

  /** Fill a Mat of arbitrary type deterministically with a per-pixel ramp (touches every byte). */
  def filled(width: Int, height: Int, cvType: Int): Mat =
    val m = Mat(height, width, cvType)
    val ch = CvType.channels(cvType)
    m.setTo(CvScalar(11, 22, 33, 44))
    // A diagonal band so the content is not uniform (matters for depth conversions).
    Imgproc.line(
      m,
      org.opencv.core.Point(0, 0),
      org.opencv.core.Point(width, height),
      CvScalar(200, 150, 100, 250),
      math.max(1, height / 20)
    )
    val _ = ch
    m

  /** A single-channel 8-bit gradient (for LUT/gamma/threshold benchmarks). */
  def gray(width: Int, height: Int): Mat =
    val bgr = scene(width, height)
    try
      val g = Mat()
      Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
      g
    finally bgr.release()
