package scalacv

import scalacv.vision.*

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** Stereo depth and obstacle detection. `Obstacles.fromDisparity` is exercised on a hand-built disparity map
  * (brightness = nearness), which is deterministic; `StereoDepth.disparity` is checked for its output shape
  * and its preconditions, since SGBM's exact values on a synthetic pair are not a stable thing to assert.
  * Fixtures are drawn, not loaded — there is no test image in this repo by design.
  */
class DepthTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val (rows, cols) = (120, 160)

  private def canvas(channels: Int): Mat =
    Mat.zeros(rows, cols, if channels == 1 then CvType.CV_8UC1 else CvType.CV_8UC3)

  private def fillRect(m: Mat, r: Rect, value: Double): Unit =
    Imgproc.rectangle(
      m,
      CvPoint(r.x.toDouble, r.y.toDouble),
      CvPoint((r.x + r.width - 1).toDouble, (r.y + r.height - 1).toDouble),
      CvScalar(value, value, value),
      -1 // FILLED
    )

  test("a bright near-field block is reported as an obstacle, a dark map has none"):
    val disparity = Image.wrap(Managed(canvas(1)))
    try
      fillRect(disparity.mat, Rect(40, 30, 60, 50), 255) // maximally near
      val obstacles = Obstacles.fromDisparity(disparity, minNearness = 0.5, minArea = 100)
      assert(obstacles.nonEmpty, "a full-brightness block should exceed minNearness")
      assertEqualsDouble(obstacles.head.nearness, 1.0, 0.05)
      assert(obstacles.head.region.area >= 100, s"region too small: ${obstacles.head.region}")
    finally disparity.close()

    val dark = Image.wrap(Managed(canvas(1)))
    try assertEquals(Obstacles.fromDisparity(dark), Seq.empty[Obstacle])
    finally dark.close()

  test("obstacles come back sorted nearest-first"):
    val disparity = Image.wrap(Managed(canvas(1)))
    try
      fillRect(disparity.mat, Rect(10, 10, 40, 40), 140) // just over the 0.5 cutoff
      fillRect(disparity.mat, Rect(100, 60, 40, 40), 255) // nearer
      val obstacles = Obstacles.fromDisparity(disparity, minNearness = 0.5, minArea = 50)
      assertEquals(obstacles.size, 2)
      assert(obstacles(0).nearness >= obstacles(1).nearness, "not sorted nearest-first")
    finally disparity.close()

  test("fromDisparity validates its ranges"):
    val disparity = Image.wrap(Managed(canvas(1)))
    try
      intercept[IllegalArgumentException](Obstacles.fromDisparity(disparity, minNearness = 1.5))
      intercept[IllegalArgumentException](Obstacles.fromDisparity(disparity, minArea = -1))
    finally disparity.close()

  test("disparity of a rectified pair is an 8-bit single-channel map of the same size"):
    val left = Image.wrap(Managed(canvas(3)))
    val right = Image.wrap(Managed(canvas(3)))
    fillRect(left.mat, Rect(30, 30, 60, 40), 200)
    fillRect(right.mat, Rect(22, 30, 60, 40), 200) // shifted left by 8 px — a positive disparity
    val disp = StereoDepth.disparity(left, right, numDisparities = 16, blockSize = 5)
    try
      assertEquals((disp.width, disp.height), (cols, rows))
      assertEquals(disp.mat.channels, 1)
      assertEquals(disp.mat.depth, CvType.CV_8U)
    finally
      disp.close()
      left.close()
      right.close()

  test("disparity rejects a bad range, an even block, and a mismatched pair"):
    val l = Image.wrap(Managed(canvas(3)))
    val r = Image.wrap(Managed(canvas(3)))
    val wide = Image.wrap(Managed(Mat.zeros(rows, cols + 40, CvType.CV_8UC3)))
    try
      intercept[IllegalArgumentException](StereoDepth.disparity(l, r, numDisparities = 63))
      intercept[IllegalArgumentException](StereoDepth.disparity(l, r, blockSize = 4))
      intercept[IllegalArgumentException](StereoDepth.disparity(l, wide))
    finally
      l.close()
      r.close()
      wide.close()
