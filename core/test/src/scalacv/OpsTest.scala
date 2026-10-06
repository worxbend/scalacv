package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import scala.util.Using

import org.opencv.core as cv
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/** B6's ops, and — more importantly — B6's ownership contract.
  *
  * The dimension and type assertions are the cheap half. The half that matters is that every op leaves its
  * receiver alive (`dataAddr() != 0`), returns something that is not an alias of it, and that the chaining
  * combinators really do free what they claim to. A stranded intermediate is invisible to the collector so
  * nothing but an explicit assertion catches it.
  *
  * Fixtures are drawn programmatically: the repository ships no test image and none may be added (D12).
  */
class OpsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 160
  private val Height = 120

  /** A deliberately bimodal scene: a dark ground with two bright, hard-edged shapes. Bimodal so Otsu has a
    * real valley to find, hard-edged so Canny and Sobel have something to report.
    */
  private def fixture(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(30, 30, 30))
    Imgproc.rectangle(m, cv.Point(20, 20), cv.Point(70, 90), cv.Scalar(200, 200, 200), -1)
    Imgproc.circle(m, cv.Point(115, 60), 30, cv.Scalar(255, 255, 255), -1)
    m

  /** `Using.Manager`, unwrapped. Every Mat registered inside is released on the way out, including on a
    * failed assertion — a leak here would be a leak in the test of the leak guard.
    */
  private def scoped[A](f: Using.Manager => A): A = Using.Manager(f).get

  /** Runs `f` with a fresh colour fixture and a grey copy of it, releasing both afterwards. */
  private def withImages[A](f: (Mat, Mat) => A): A =
    scoped: use =>
      val colour = use(Managed(fixture()))
      val grey = use(colour.get.cvtColor(ColorConversion.BgrToGray))
      f(colour.get, grey.get)

  /** The two halves of the contract that every single op owes. */
  private def assertOwnership(receiver: Mat, out: Managed[Mat]): Unit =
    assertNotEquals(receiver.dataAddr(), 0L, "the receiver must not have been released")
    assertNotEquals(out.get.dataAddr(), 0L, "the result must own live native memory")
    assertNotEquals(out.get.dataAddr(), receiver.dataAddr(), "the result must not alias the receiver")

  test("cvtColor produces a new single-channel Mat and leaves the receiver alone"):
    scoped: use =>
      val src = use(Managed(fixture()))
      val grey = use(src.get.cvtColor(ColorConversion.BgrToGray))
      assertEquals(grey.get.channels(), 1)
      assertEquals(grey.get.`type`(), CvType.CV_8UC1)
      assertEquals(grey.get.size(), src.get.size())
      assertEquals(src.get.channels(), 3, "the receiver must still be a 3-channel image")
      assertOwnership(src.get, grey)

  test("gaussianBlur keeps size and type and actually changes the pixels"):
    withImages: (colour, _) =>
      scoped: use =>
        val blurred = use(colour.gaussianBlur(Size(5, 5), 1.5))
        assertEquals(blurred.get.size(), colour.size())
        assertEquals(blurred.get.`type`(), colour.`type`())
        assertOwnership(colour, blurred)
        // If the op had written nothing, every assertion above would still pass.
        assert(differs(colour, blurred.get), "gaussianBlur must not return an untouched copy")

  test("gaussianBlur accepts Size(0, 0) and derives the kernel from sigma"):
    withImages: (colour, _) =>
      scoped: use =>
        val derived = use(colour.gaussianBlur(Size(0, 0), 2.0))
        assertEquals(derived.get.size(), colour.size())

  test("gaussianBlur rejects an even kernel before it reaches native code"):
    withImages: (colour, _) =>
      val e = intercept[IllegalArgumentException](colour.gaussianBlur(Size(4, 4), 1.0))
      assert(e.getMessage.contains("odd"), e.getMessage)

  test("every imgproc filter rejects the border mode OpenCV's filter engine aborts on"):
    withImages: (colour, grey) =>
      // BorderType.Wrap reaches cv::FilterEngine::init, which asserts columnBorderType != BORDER_WRAP.
      // The native behaviour is depth-dependent — GaussianBlur on CV_8U takes a SIMD path that skips the
      // assertion and silently ignores the mode, CV_32F aborts — so a call developed against an 8-bit
      // frame crashes the first time it meets a float one. All four filters must fail here instead, and
      // identically, whatever the source depth. `border` and `rotated` are deliberately not in this list:
      // copyMakeBorder and warpAffine do honour Wrap.
      val calls: Seq[(String, () => Managed[Mat])] = Seq(
        "gaussianBlur" -> (() => colour.gaussianBlur(Size(5, 5), 1.5, border = BorderType.Wrap)),
        "boxBlur" -> (() => colour.boxBlur(Size(3, 3), border = BorderType.Wrap)),
        "sobel" -> (() => grey.sobel(dx = 1, dy = 0, border = BorderType.Wrap)),
        "laplacian" -> (() => grey.laplacian(border = BorderType.Wrap))
      )
      calls.foreach: (op, call) =>
        val e = intercept[IllegalArgumentException](call())
        assert(e.getMessage.contains(op), s"the failure must name $op, got: ${e.getMessage}")

  test("boxBlur keeps size and type"):
    withImages: (colour, _) =>
      scoped: use =>
        val b = use(colour.boxBlur(Size(3, 3)))
        assertEquals(b.get.size(), colour.size())
        assertEquals(b.get.`type`(), colour.`type`())
        assertOwnership(colour, b)

  test("canny returns an 8-bit single-channel edge map with edges in it"):
    withImages: (_, grey) =>
      scoped: use =>
        val edges = use(grey.canny(50, 150))
        assertEquals(edges.get.`type`(), CvType.CV_8UC1)
        assertEquals(edges.get.size(), grey.size())
        assert(Core.countNonZero(edges.get) > 0, "the fixture has hard edges; Canny must find some")
        assertOwnership(grey, edges)

  test("canny rejects an aperture OpenCV would abort on"):
    withImages: (_, grey) =>
      intercept[IllegalArgumentException](grey.canny(50, 150, apertureSize = 4))

  test("sobel honours the requested output depth"):
    withImages: (_, grey) =>
      scoped: use =>
        val dx = use(grey.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16))
        assertEquals(dx.get.`type`(), CvType.CV_16SC1, "Signed16 must widen the destination")
        assertEquals(dx.get.size(), grey.size())
        assertOwnership(grey, dx)

        val same = use(grey.sobel(dx = 1, dy = 0))
        assertEquals(same.get.`type`(), CvType.CV_8UC1, "SameAsSource must keep the source depth")

  test("sobel requires a derivative order"):
    withImages: (_, grey) =>
      intercept[IllegalArgumentException](grey.sobel(dx = 0, dy = 0))

  test("laplacian honours the requested output depth"):
    withImages: (_, grey) =>
      scoped: use =>
        val l = use(grey.laplacian(kernelSize = 3, depth = OutputDepth.Float32))
        assertEquals(l.get.`type`(), CvType.CV_32FC1)
        assertEquals(l.get.size(), grey.size())
        assertOwnership(grey, l)

  test("equalizeHist returns an 8-bit single-channel image"):
    withImages: (_, grey) =>
      scoped: use =>
        val eq = use(grey.equalizeHist())
        assertEquals(eq.get.`type`(), CvType.CV_8UC1)
        assertEquals(eq.get.size(), grey.size())
        assertOwnership(grey, eq)

  test("a fixed threshold hands back the value it was given"):
    withImages: (_, grey) =>
      val Thresholded(out, result) = grey.threshold(128)
      Using.resource(out): binary =>
        assertEquals(result.value, 128.0)
        assertEquals(binary.get.`type`(), CvType.CV_8UC1)
        assert(onlyValues(binary.get, 0, 255), "THRESH_BINARY must produce a two-valued image")
        assertOwnership(grey, binary)

  test("Otsu computes a plausible threshold instead of dropping it"):
    withImages: (_, grey) =>
      // `value` is ignored for Otsu — OpenCV computes its own and returns it. The fixture's modes are
      // 30 (ground) and 200/255 (shapes); every threshold in [30, 199] induces the same partition and
      // Otsu returns the lowest maximiser, so the bound below is `>= 30`, not `> 30`.
      val Thresholded(out, result) = grey.threshold(0, kind = Threshold.otsu())
      Using.resource(out): binary =>
        assert(result.value > 0.0, s"Otsu returned ${result.value}; the computed value was dropped")
        assert(
          result.value >= 30.0 && result.value < 200.0,
          s"Otsu picked ${result.value}, outside the fixture's two modes (30 and 200/255)"
        )
        // And it has to be the *right* split: the shapes are a 51x71 rectangle plus a radius-30
        // disc, about 6450 of the 19200 px. A merely non-zero threshold would land nowhere near.
        val lit = Core.countNonZero(binary.get)
        assert(lit > 5500 && lit < 7500, s"Otsu's split kept $lit px; the shapes are about 6450")

  test("Triangle also computes a threshold"):
    withImages: (_, grey) =>
      val Thresholded(out, result) = grey.threshold(0, kind = Threshold.triangle())
      Using.resource(out)(_ => assert(result.value > 0.0, s"Triangle returned ${result.value}"))

  test("threshold's named pair pipes like every other op"):
    withImages: (colour, _) =>
      // The point of `Thresholded`: `.image` is a bare Managed[Mat], so threshold composes in a chain
      // where the tuple it replaced forced a `._1` outside `pipe`.
      val edges = colour
        .cvtColor(ColorConversion.BgrToGray)
        .pipe(m => m.threshold(128).image)
        .pipe(_.bitwiseNot())
      try
        assertEquals(edges.get.`type`(), CvType.CV_8UC1)
        assertNotEquals(colour.dataAddr(), 0L, "the chain's source is borrowed, not consumed")
      finally edges.release()

  test("adaptiveThreshold's mode parameter inverts the output exactly"):
    withImages: (_, grey) =>
      scoped: use =>
        val binary = use(grey.adaptiveThreshold(blockSize = 11, c = 2, mode = Threshold.Mode.Binary))
        val inverted =
          use(grey.adaptiveThreshold(blockSize = 11, c = 2, mode = Threshold.Mode.BinaryInv))
        val total = grey.rows() * grey.cols()
        assertEquals(
          Core.countNonZero(binary.get) + Core.countNonZero(inverted.get),
          total,
          "BinaryInv must be the exact complement of Binary, pixel for pixel"
        )

  test("adaptiveThreshold rejects a mode OpenCV has no adaptive form of"):
    withImages: (_, grey) =>
      val e = intercept[IllegalArgumentException](
        grey.adaptiveThreshold(mode = Threshold.Mode.Truncate)
      )
      assert(e.getMessage.contains("adaptiveThreshold"), e.getMessage)
      assert(e.getMessage.contains("Truncate"), e.getMessage)

  test("inRange rejects a lo above hi, channel by channel"):
    withImages: (colour, _) =>
      // Only the second channel is inverted; a whole-Scalar comparison would miss it.
      val e = intercept[IllegalArgumentException](
        colour.inRange(Scalar(0, 200, 0), Scalar(255, 100, 255))
      )
      assert(e.getMessage.contains("inRange"), e.getMessage)

  test("Mats.grayscale rejects a 2-channel Mat by name instead of passing it off as already grey"):
    val twoChannel = Mat(10, 10, CvType.CV_8UC2)
    try
      val e = intercept[IllegalArgumentException](Mats.grayscale(twoChannel))
      assert(e.getMessage.contains("2 channels"), e.getMessage)
    finally twoChannel.release()

  test("resize hits the exact requested size"):
    withImages: (colour, _) =>
      scoped: use =>
        val small = use(colour.resize(Size(40, 30), Interpolation.Area))
        assertEquals(small.get.cols(), 40)
        assertEquals(small.get.rows(), 30)
        assertEquals(small.get.`type`(), colour.`type`())
        assertEquals(colour.cols(), Width, "the receiver must not have been resized in place")
        assertOwnership(colour, small)

  test("resize rejects an empty target"):
    withImages: (colour, _) =>
      intercept[IllegalArgumentException](colour.resize(Size(0, 0)))

  test("resize rejects a target that is positive as a Double but empty once truncated"):
    withImages: (colour, _) =>
      // `Size(w * 0.005, h * 0.005)` is the shape of every fit-to-box computation. Both extents are
      // above zero, so the old guard passed them straight to OpenCV, which truncates them to 0x0 and
      // aborts with `inv_scale_x > 0` — a native error where this file promises IllegalArgumentException.
      val e = intercept[IllegalArgumentException](colour.resize(Size(0.5, 0.5)))
      assert(e.getMessage.contains("1x1"), e.getMessage)

  test("scaled applies independent x and y factors"):
    withImages: (colour, _) =>
      scoped: use =>
        val s = use(colour.scaled(0.5, 0.25))
        assertEquals(s.get.cols(), Width / 2)
        assertEquals(s.get.rows(), Height / 4)

  test("scaled rejects factors that round this image away entirely"):
    withImages: (colour, _) =>
      // 120 * 0.004 = 0.48, which OpenCV rounds to 0 rows and then rejects with `!dsize.empty()`.
      // The factors themselves are positive, so only a check against the receiver's own size catches it.
      val e = intercept[IllegalArgumentException](colour.scaled(0.004, 0.004))
      assert(e.getMessage.contains("empty"), e.getMessage)

  test("scaled still accepts the smallest factors OpenCV itself accepts"):
    withImages: (colour, _) =>
      // The guard must mirror OpenCV's round-half-to-even, not truncate: 160 * 0.006 = 0.96 and
      // 120 * 0.006 = 0.72 both round up to 1, so this is a legal 1x1 result that `.toInt` would reject.
      scoped: use =>
        val tiny = use(colour.scaled(0.006, 0.006))
        assertEquals(tiny.get.cols(), 1)
        assertEquals(tiny.get.rows(), 1)

  test("convertScaleAbs turns a signed derivative back into 8-bit unsigned"):
    withImages: (_, grey) =>
      scoped: use =>
        val dx = use(grey.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16))
        val abs = use(dx.get.convertScaleAbs())
        assertEquals(abs.get.`type`(), CvType.CV_8UC1)
        assertEquals(abs.get.size(), grey.size())
        assertOwnership(dx.get, abs)

  /** A float image with a single hot pixel — a stand-in for a disparity map or a raw filter response, the
    * inputs `normalize` exists to make displayable. Its range is exactly [0, 1000], so where each value has
    * to land after a rescale is arithmetic rather than a guess.
    */
  private def floatFixture(): Mat =
    val m = Mat(Height, Width, CvType.CV_32FC1, cv.Scalar(0))
    val _ = m.put(0, 0, 1000.0)
    m

  test("normalize brings a float image down to 8-bit, which is what makes it displayable"):
    scoped: use =>
      val raw = use(Managed(floatFixture()))
      val stretched = use(raw.get.normalize())
      assertEquals(stretched.get.`type`(), CvType.CV_8UC1, "a CV_32F source must come back as CV_8U")
      assertEquals(stretched.get.get(0, 0)(0), 255.0, "the maximum must land at the top of the range")
      assertEquals(stretched.get.get(1, 1)(0), 0.0, "the minimum must land at the bottom")
      // The remedy `toBufferedImage`'s own error message prescribes has to work afterwards: applyColorMap
      // takes CV_8UC1/CV_8UC3 only and aborted in native code on the CV_32F this used to hand back.
      val heat = use(stretched.get.colorMap(Colormap.Jet))
      assertEquals(heat.get.`type`(), CvType.CV_8UC3)
      assertOwnership(raw.get, stretched)

  test("normalize keeps the source depth when explicitly asked to"):
    scoped: use =>
      val raw = use(Managed(floatFixture()))
      val unitRange = use(raw.get.normalize(0, 1, OutputDepth.SameAsSource))
      assertEquals(unitRange.get.`type`(), CvType.CV_32FC1, "SameAsSource keeps a float stretch in float")
      assertEquals(unitRange.get.get(0, 0)(0), 1.0, "[0, 1] must survive in full precision, not as 0 and 1")

  test("normalize leaves an already-8-bit image at its own type"):
    withImages: (colour, _) =>
      scoped: use =>
        val stretched = use(colour.normalize())
        assertEquals(stretched.get.`type`(), colour.`type`(), "the 8-bit default must not change the type")
        assertEquals(stretched.get.size(), colour.size())
        assertOwnership(colour, stretched)

  test("addWeighted borrows both operands"):
    withImages: (colour, _) =>
      scoped: use =>
        val blurred = use(colour.gaussianBlur(Size(9, 9), 3.0))
        val sharp = use(colour.addWeighted(1.5, blurred.get, -0.5))
        assertEquals(sharp.get.size(), colour.size())
        assertEquals(sharp.get.`type`(), colour.`type`())
        assertNotEquals(colour.dataAddr(), 0L, "the receiver must survive addWeighted")
        assertNotEquals(blurred.get.dataAddr(), 0L, "the second operand must survive addWeighted")
        assertNotEquals(sharp.get.dataAddr(), colour.dataAddr())
        assertNotEquals(sharp.get.dataAddr(), blurred.get.dataAddr())

  test("a native failure is named and does not leak the destination"):
    withImages: (colour, _) =>
      // equalizeHist accepts CV_8UC1 only, so the 3-channel fixture makes it throw inside OpenCV.
      val e = intercept[CvError.NativeCall](colour.equalizeHist())
      assert(e.getMessage.contains("equalizeHist"), e.getMessage)
      assertNotEquals(colour.dataAddr(), 0L, "a failed op must not release the receiver")

  test("pipe releases the intermediate and keeps the result"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      val intermediate = grey.get // captured before the chain consumes it
      val edges = grey.pipe(_.canny(50, 150))
      try
        assert(grey.isReleased, "pipe must release its receiver")
        assertEquals(intermediate.dataAddr(), 0L, "the intermediate's native buffer must be freed")
        assert(!edges.isReleased)
        assertEquals(edges.get.size(), colour.size())
        assertEquals(edges.get.`type`(), CvType.CV_8UC1)
        assertNotEquals(colour.dataAddr(), 0L, "the source of the chain is borrowed, not consumed")
      finally edges.release()

  test("pipe makes the consumed intermediate unusable rather than dangling"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      Using.resource(grey.pipe(_.canny(50, 150))): _ =>
        intercept[IllegalStateException](grey.get)

  test("pipe still releases the intermediate when the next stage throws"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      val intermediate = grey.get
      intercept[RuntimeException](grey.pipe(_ => throw RuntimeException("boom")))
      assert(grey.isReleased, "a throwing stage must not strand its input")
      assertEquals(intermediate.dataAddr(), 0L)

  test("chain releases every intermediate but never the source"):
    withImages: (colour, _) =>
      val seen = scala.collection.mutable.ArrayBuffer.empty[Mat]
      def spy(m: Managed[Mat]): Managed[Mat] =
        seen += m.get
        m

      val out = Mats.chain(colour)(
        m => spy(m.cvtColor(ColorConversion.BgrToGray)),
        m => spy(m.gaussianBlur(Size(5, 5), 1.5)),
        m => m.canny(50, 150)
      )
      try
        assertEquals(seen.size, 2)
        seen.zipWithIndex.foreach: (m, i) =>
          assertEquals(m.dataAddr(), 0L, s"chain stranded intermediate $i")
        assertNotEquals(out.get.dataAddr(), 0L, "the final result must survive")
        assertEquals(out.get.`type`(), CvType.CV_8UC1)
        assertNotEquals(colour.dataAddr(), 0L, "chain must not release the source it was handed")
      finally out.release()

  test("chain releases what it holds when a stage throws"):
    withImages: (colour, _) =>
      var intermediate: Mat | Null = null
      def capture(m: Mat): Managed[Mat] =
        val g = m.cvtColor(ColorConversion.BgrToGray)
        intermediate = g.get
        g

      intercept[RuntimeException](Mats.chain(colour)(capture, _ => throw RuntimeException("boom")))
      assertEquals(intermediate.nn.dataAddr(), 0L, "a throwing stage must not strand the previous output")
      assertNotEquals(colour.dataAddr(), 0L)

  test("chain with no stages is a programmer error, not a silently borrowed Mat"):
    withImages: (colour, _) =>
      intercept[IllegalArgumentException](Mats.chain(colour)())

  /** True if any pixel differs — cheap, and enough to prove an op wrote something. */
  private def differs(a: Mat, b: Mat): Boolean =
    scoped: use =>
      val diff = use(Managed(Mat()))
      Core.absdiff(a, b, diff.get)
      val flat = use(Managed(diff.get.reshape(1)))
      Core.countNonZero(flat.get) > 0

  private def onlyValues(m: Mat, low: Int, high: Int): Boolean =
    val buf = new Array[Byte](m.rows() * m.cols() * m.channels())
    m.get(0, 0, buf)
    buf.forall(b => (b & 0xff) == low || (b & 0xff) == high)
