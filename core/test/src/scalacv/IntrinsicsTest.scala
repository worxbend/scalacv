package scalacv

/** The pinhole model's value semantics: the native matrices `cameraMatrix`/`distCoeffs` hand out must carry
  * exactly the constructor's numbers — a regression net for the guarded allocate-then-fill both now use (a
  * throwing `put` must release the fresh Mat, which only a values test can keep honest on the happy path).
  */
class IntrinsicsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("cameraMatrix lays out fx, cx, fy, cy in OpenCV's K positions"):
    val intr = Intrinsics(fx = 610.5, fy = 612.25, cx = 320.5, cy = 240.75)
    Managed.use(intr.cameraMatrix): k =>
      assertEquals((k.rows, k.cols), (3, 3))
      def at(r: Int, c: Int): Double = k.get(r, c)(0)
      assertEqualsDouble(at(0, 0), 610.5, 1e-9, "fx at (0,0)")
      assertEqualsDouble(at(0, 2), 320.5, 1e-9, "cx at (0,2)")
      assertEqualsDouble(at(1, 1), 612.25, 1e-9, "fy at (1,1)")
      assertEqualsDouble(at(1, 2), 240.75, 1e-9, "cy at (1,2)")
      assertEqualsDouble(at(2, 2), 1.0, 1e-9, "1 at (2,2)")
      assertEqualsDouble(at(0, 1) + at(1, 0) + at(2, 0) + at(2, 1), 0.0, 1e-9, "zeros elsewhere")

  test("distCoeffs carries the distortion vector unchanged; empty means ideal lens"):
    val intr = Intrinsics(600, 600, 320, 240, distortion = Seq(0.1, -0.05, 0.001, 0.002, 0.01))
    Managed.use(intr.distCoeffs): d =>
      assertEquals(d.total().toInt, 5)
      assertEquals(d.toArray.toSeq, Seq(0.1, -0.05, 0.001, 0.002, 0.01))
    Managed.use(Intrinsics(600, 600, 320, 240).distCoeffs): d =>
      assertEquals(d.total().toInt, 0)

  test("Intrinsics.approx is the library's one uncalibrated guess, at a 60° horizontal FoV"):
    val size = Size(640.0, 480.0)
    val a = Intrinsics.approx(size)
    val expectedF = 320.0 / math.tan(math.toRadians(30.0))
    assertEqualsDouble(a.fx, expectedF, 1e-9, "fx from (w/2)/tan(fov/2)")
    assertEqualsDouble(a.fy, a.fx, 1e-9, "square pixels")
    assertEquals((a.cx, a.cy), (320.0, 240.0))
    assertEquals(a.distortion, Seq.empty)
    intercept[IllegalArgumentException](Intrinsics.approx(size, horizontalFovDegrees = 0))
    intercept[IllegalArgumentException](Intrinsics.approx(size, horizontalFovDegrees = 180))
