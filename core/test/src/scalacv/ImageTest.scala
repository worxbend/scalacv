package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.nio.file.Files

import org.opencv.core as cv
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/** The high-level [[Image]] API: the fluent chain, and — the part that matters — its ownership discipline.
  *
  * The dimension checks are the easy half. The half worth testing is that a transform really spends the image
  * it was called on (so a stale handle throws rather than reading freed memory), that a query leaves it
  * alive, and that a terminal releases it. Fixtures are drawn programmatically; the repo ships no image.
  */
class ImageTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 160
  private val Height = 120

  /** A bimodal scene with hard edges, so gray/canny/threshold have something real to work on. */
  private def scene(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(30, 30, 30))
    Imgproc.rectangle(m, cv.Point(20, 20), cv.Point(70, 90), cv.Scalar(220, 220, 220), -1)
    Imgproc.circle(m, cv.Point(115, 60), 30, cv.Scalar(255, 255, 255), -1)
    m

  /** A fresh decodable Image from the scene, round-tripped through PNG bytes so no test shares a Mat. */
  private def sample(): Image =
    val bytes = Managed.use(scene())(Images.encode(_, ".png")).fold(e => fail(e.getMessage), identity)
    Image.decode(bytes).fold(e => fail(e.getMessage), identity)

  test("decode → gray → blur → canny → bytes produces a PNG and consumes the image"):
    val img = sample()
    val out = img.gray.blur(2).canny(80, 160).bytes(".png")
    assert(out.isRight, s"expected encoded bytes, got $out")
    assert(out.toOption.get.nonEmpty)

  test("a transform spends the image it was called on"):
    val img = sample()
    val g = img.gray
    intercept[IllegalStateException](img.width) // the source is spent
    assertEquals(g.channels, 1)
    g.close()

  test("blur spends the image for every non-negative radius, including the 0 identity"):
    for r <- Seq(0, 2) do
      val img = sample()
      val blurred = img.blur(r)
      intercept[IllegalStateException](img.width) // the source is spent even when r == 0
      assertEquals((blurred.width, blurred.height), (Width, Height)) // the handle moved, not the pixels
      blurred.close()

  test("a query borrows: the image is still usable afterwards"):
    val img = sample()
    assertEquals(img.qrCodes, Seq.empty) // there is no QR code in the scene
    assertEquals(img.width, Width) // still alive after the query
    img.close()

  test("a terminal releases: using the image after write throws"):
    val img = sample()
    val tmp = Files.createTempFile("scalacv-image-", ".png")
    try
      assert(img.write(tmp.toString).isRight)
      intercept[IllegalStateException](img.height)
      assert(Files.size(tmp) > 0)
    finally Files.deleteIfExists(tmp)

  test("gray reduces to one channel; the scene starts at three"):
    val img = sample()
    assertEquals(img.channels, 3)
    val g = img.gray
    assertEquals(g.channels, 1)
    g.close()

  test("resize and scale change the dimensions"):
    val resized = sample().resize(80, 60)
    assertEquals((resized.width, resized.height), (80, 60))
    resized.close()
    val scaled = sample().scale(0.5)
    assertEquals((scaled.width, scaled.height), (Width / 2, Height / 2))
    scaled.close()

  test("crop returns an independent copy of the requested size"):
    val cropped = sample().crop(Rect(10, 10, 40, 30))
    assertEquals((cropped.width, cropped.height), (40, 30))
    cropped.close()

  test("crop rejects a rectangle that runs off the image"):
    val img = sample()
    intercept[IllegalArgumentException](img.crop(Rect(0, 0, Width + 10, Height)))
    img.close()

  test("blank makes a canvas of the requested size and channel count"):
    val one = Image.blank(50, 40, channels = 1)
    assertEquals((one.width, one.height, one.channels), (50, 40, 1))
    one.close()
    val four = Image.blank(10, 10, channels = 4)
    assertEquals(four.channels, 4)
    four.close()
    intercept[IllegalArgumentException](Image.blank(10, 10, channels = 2))

  test("copy lets one image feed two independent chains"):
    val img = sample()
    val branch = img.copy // independent deep copy
    val a = img.gray.bytes(".png")
    val b = branch.canny(80, 160).bytes(".png")
    assert(a.isRight && b.isRight)

  test("mat borrows the underlying handle without consuming the image"):
    val img = sample()
    assertEquals(img.mat.rows, Height) // low-level escape hatch
    assertEquals(img.width, Width) // still owned afterwards
    img.close()

  test("markFaces on no faces is a no-op that still yields a writable image"):
    val annotated = sample().markFaces(Seq.empty)
    assertEquals(annotated.width, Width)
    assert(annotated.bytes(".png").isRight)

  test("Image.reading closes the image and returns a query result"):
    val tmp = Files.createTempFile("scalacv-reading-", ".png")
    try
      Managed.use(scene())(Images.write(tmp.toString, _)).fold(e => fail(e.getMessage), identity)
      val result = Image.reading(tmp.toString)(_.size)
      assertEquals(result, Right(Size(Width.toDouble, Height.toDouble)))
    finally Files.deleteIfExists(tmp)

  test("Image.read on a missing path is a Left, not a throw"):
    val missing = "/does/not/exist/scalacv-image.png"
    Image.read(missing) match
      case Left(e) => assert(e.getMessage.contains(missing), e.getMessage)
      case Right(img) => img.close(); fail("a missing image must not read")

  test("managed hands the Mat over and spends the image"):
    val img = sample()
    val handed = img.managed
    intercept[IllegalStateException](img.width) // spent
    handed.use(m => assertEquals(m.rows, Height)) // still a live Mat

  // -- the expanded operation set ------------------------------------------------------------------

  private def dims(img: Image): (Int, Int, Int) =
    try (img.width, img.height, img.channels)
    finally img.close()

  test("flip preserves the dimensions"):
    assertEquals(dims(sample().flip(Flip.Horizontal)), (Width, Height, 3))

  test("a quarter-turn rotation swaps width and height"):
    assertEquals(dims(sample().rotate(Rotation.Clockwise)), (Height, Width, 3))

  test("an arbitrary rotation expands the canvas so nothing is clipped"):
    val r = sample().rotate(45.0)
    try
      assert(r.width > Width && r.height > Height, s"expected an expanded canvas, got ${r.width}x${r.height}")
    finally r.close()

  test("pad and border grow the image by the requested widths"):
    assertEquals(dims(sample().pad(10)), (Width + 20, Height + 20, 3))
    assertEquals(
      dims(sample().border(top = 5, bottom = 10, left = 0, right = 20)),
      (Width + 20, Height + 15, 3)
    )

  test("median and bilateral blur preserve shape"):
    assertEquals(dims(sample().medianBlur(1)), (Width, Height, 3))
    assertEquals(dims(sample().bilateralFilter()), (Width, Height, 3))

  test("adaptive threshold yields a single-channel binary image"):
    assertEquals(dims(sample().gray.adaptiveThreshold()), (Width, Height, 1))

  test("morphology preserves shape"):
    assertEquals(dims(sample().erode()), (Width, Height, 3))
    assertEquals(dims(sample().dilate(radius = 2)), (Width, Height, 3))
    assertEquals(dims(sample().morphology(MorphOp.Open)), (Width, Height, 3))
    assertEquals(
      dims(sample().morphology(MorphOp.Close, radius = 2, shape = MorphShape.Ellipse)),
      (Width, Height, 3)
    )

  test("invert, adjust, sharpen and normalize preserve shape"):
    assertEquals(dims(sample().invert), (Width, Height, 3))
    assertEquals(dims(sample().adjust(brightness = 40, contrast = 1.2)), (Width, Height, 3))
    assertEquals(dims(sample().sharpen()), (Width, Height, 3))
    assertEquals(dims(sample().normalize()), (Width, Height, 3))

  test("toHsv keeps three channels; channel extracts one"):
    assertEquals(dims(sample().toHsv), (Width, Height, 3))
    assertEquals(dims(sample().channel(0)), (Width, Height, 1))

  test("inRange produces a single-channel mask of the same size"):
    assertEquals(dims(sample().toHsv.inRange(Scalar(0, 0, 0), Scalar(180, 255, 255))), (Width, Height, 1))

  test("applyMask keeps the shape; blend combines two same-size images"):
    val mask = sample().toHsv.inRange(Scalar(0, 0, 0), Scalar(180, 255, 255)) // all-pass mask
    try assertEquals(dims(sample().applyMask(mask)), (Width, Height, 3))
    finally mask.close()
    val other = sample() // borrowed by blend, so this test owns and closes it
    try assertEquals(dims(sample().blend(other, 0.5)), (Width, Height, 3))
    finally other.close()

  test("a full expanded pipeline chains and writes without leaking"):
    val out =
      sample().gray.medianBlur(1).adaptiveThreshold().morphology(MorphOp.Open).invert.bytes(".png")
    assert(out.isRight, s"expected encoded bytes, got $out")

  // -- pixel-level effects: where a marked pixel lands, what the documented formulas produce -------
  //
  // The dimension checks above cannot tell a flip from its transpose or `adjust` from its argument swap.
  // These fixtures are a single marked pixel or a flat integral colour, so every expected value is exact by
  // definition of the op — lossless moves, integer arithmetic, binary morphology — on every build.

  /** The channels at (x, y). `Mat.get` is row-major, so the arguments swap on the way in. */
  private def pixel(img: Image, x: Int, y: Int): Seq[Double] = img.mat.get(y, x).toIndexedSeq

  /** The count of non-zero pixels of a single-channel image, which it then releases. */
  private def lit(img: Image): Int =
    try Core.countNonZero(img.mat)
    finally img.close()

  private val On = Seq(255.0)
  private val Off = Seq(0.0)

  /** A 4×3 single-channel canvas whose only white pixel is (x = 0, y = 0). */
  private def marked(): Image =
    Image.blank(4, 3, Scalar.Black, channels = 1).drawRect(Rect(0, 0, 1, 1), Scalar.White, Thickness.Filled)

  test("flip mirrors the marked pixel across the axis its name promises"):
    for (how, x, y) <- Seq((Flip.Horizontal, 3, 0), (Flip.Vertical, 0, 2), (Flip.Both, 3, 2)) do
      val flipped = marked().flip(how)
      assertEquals(pixel(flipped, x, y), On, s"$how")
      assertEquals(lit(flipped), 1, s"$how must move the pixel, not copy it")

  test("a quarter-turn carries the marked corner clockwise to top-right, counter-clockwise to bottom-left"):
    val cases = Seq(
      (Rotation.Clockwise, (3, 4), 2, 0),
      (Rotation.CounterClockwise, (3, 4), 0, 3),
      (Rotation.Half, (4, 3), 3, 2)
    )
    for (rotation, size, x, y) <- cases do
      val turned = marked().rotate(rotation)
      assertEquals((turned.width, turned.height), size, s"$rotation")
      assertEquals(pixel(turned, x, y), On, s"$rotation")
      assertEquals(lit(turned), 1, s"$rotation must move the pixel, not copy it")

  test("blend weights this image by `weight` and the borrowed other by its complement"):
    val a = Image.blank(8, 8, Scalar(100, 100, 100))
    val b = Image.blank(8, 8, Scalar(200, 200, 200))
    try
      for (weight, expected) <- Seq((0.25, 175.0), (1.0, 100.0), (0.0, 200.0)) do
        val mixed = a.copy.blend(b, weight)
        try assertEquals(pixel(mixed, 4, 4), Seq(expected, expected, expected), s"weight $weight")
        finally mixed.close()
      assertEquals(b.width, 8) // borrowed by every blend, never consumed
    finally
      a.close(); b.close()

  test("adjust scales by contrast and offsets by brightness; invert is 255 - v"):
    val flat = Image.blank(8, 8, Scalar(100, 100, 100))
    val adjusted = flat.copy.adjust(brightness = 10, contrast = 2)
    // The transpose, 100 · 10 + 2, would saturate to 255 — so 210 proves the arguments reached OpenCV in order.
    try assertEquals(pixel(adjusted, 4, 4), Seq(210.0, 210.0, 210.0))
    finally adjusted.close()
    val inverted = flat.invert
    try assertEquals(pixel(inverted, 4, 4), Seq(155.0, 155.0, 155.0))
    finally inverted.close()

  private val Backdrop = Seq(1.0, 2.0, 3.0)
  private val RedPixel = Seq(0.0, 0.0, 255.0)

  /** A 20×10 BGR scene on a `Backdrop` background with a red block over x 12..16, y 4..6. */
  private def blockScene(): Image =
    Image.blank(20, 10, Scalar(1, 2, 3)).drawRect(Rect(12, 4, 5, 3), Scalar.Red, Thickness.Filled)

  test("crop copies exactly the requested window, and the copy outlives the spent parent"):
    val parent = blockScene()
    val window = parent.crop(Rect(11, 3, 7, 5)) // the red block with a one-pixel backdrop margin
    try
      intercept[IllegalStateException](parent.width)
      assertEquals((window.width, window.height), (7, 5))
      assertEquals(pixel(window, 0, 0), Backdrop)
      assertEquals(pixel(window, 1, 1), RedPixel)
      assertEquals(pixel(window, 5, 3), RedPixel)
      assertEquals(pixel(window, 6, 4), Backdrop)
    finally window.close()

  test("crop accepts a rectangle flush with every edge and rejects one a single pixel past it"):
    val whole = blockScene().crop(Rect(0, 0, 20, 10))
    try
      assertEquals((whole.width, whole.height), (20, 10))
      assertEquals(pixel(whole, 12, 4), RedPixel)
      assertEquals(pixel(whole, 11, 4), Backdrop)
    finally whole.close()
    val corner = blockScene().crop(Rect(19, 9, 1, 1))
    try assertEquals((corner.width, corner.height, pixel(corner, 0, 0)), (1, 1, Backdrop))
    finally corner.close()
    val img = blockScene()
    intercept[IllegalArgumentException](img.crop(Rect(16, 0, 5, 10))) // x + width = 21
    intercept[IllegalArgumentException](img.crop(Rect(0, 8, 20, 3))) // y + height = 11
    assertEquals(img.width, 20) // rejected up front, so the image is still usable
    img.close()

  test("pad honours BorderType.Wrap, tiling the opposite edge in"):
    val stripe = Image
      .blank(4, 2, Scalar.Black, channels = 1)
      .drawRect(Rect(3, 0, 1, 2), Scalar.White, Thickness.Filled) // only the right column is white
    val padded = stripe.pad(1, BorderType.Wrap)
    try
      assertEquals((padded.width, padded.height), (6, 4))
      assertEquals(pixel(padded, 0, 1), On) // left pad: the source's white right column
      assertEquals(pixel(padded, 4, 1), On) // that source column, shifted by the pad
      assertEquals(pixel(padded, 5, 1), Off) // right pad: the source's black left column
      assertEquals(pixel(padded, 4, 0), On) // top pad: the source's bottom row, same column
    finally padded.close()

  test("rotated honours BorderType.Wrap, the one mode the filters reject, by tiling the corners"):
    val flat = Image.blank(8, 8, Scalar(10, 20, 30))
    try
      flat.mat
        .rotated(30.0, border = BorderType.Wrap, color = Scalar.Red)
        .use: m =>
          // Every tile of a flat source is the same colour, so the exposed corner is that colour if Wrap reached
          // warpAffine — and red if it fell back to the constant fill.
          assertEquals(m.get(0, 0).toSeq, Seq(10.0, 20.0, 30.0))
    finally flat.close()

  test("border rejects a negative width by name"):
    val img = Image.blank(4, 4)
    val e = intercept[IllegalArgumentException](img.border(-1, 0, 0, 0))
    assert(e.getMessage.contains("negative"), e.getMessage)
    img.close()

  /** A 40×20 BGR scene on a flat colour with a white block, for the arbitrary-angle rotations. */
  private def wide(): Image =
    Image.blank(40, 20, Scalar(10, 20, 30)).drawRect(Rect(5, 5, 10, 8), Scalar.White, Thickness.Filled)

  test("an arbitrary-angle rotate sizes its canvas by the rotated bounding box"):
    assertEquals(dims(wide().rotate(90.0)), (20, 40, 3))
    assertEquals(dims(wide().rotate(0.0)), (40, 20, 3))
    assertEquals(dims(wide().rotate(0.0, scale = 2.0)), (80, 40, 3))

  test("rotate by 0° leaves every pixel where it was"):
    val src = wide()
    val same = src.copy.rotate(0.0)
    try assert(Core.norm(src.mat, same.mat, Core.NORM_INF) <= 1.0)
    finally
      src.close(); same.close()

  test("an arbitrary rotation centres the source on the canvas and fills the exposed corners"):
    val src = wide()
    val turned = src.mat.rotated(45.0, color = Scalar.Red)
    try
      turned.use: m =>
        assertEquals(m.get(0, 0).toSeq, RedPixel) // the canvas corner lies outside the rotated source
        assertEquals(m.get(m.rows / 2, m.cols / 2).toSeq, Seq(10.0, 20.0, 30.0)) // the source's flat centre
    finally src.close()

  test("rotate rejects a non-positive scale"):
    val img = wide()
    intercept[IllegalArgumentException](img.rotate(10.0, scale = 0))
    img.close()

  /** An 11×11 single-channel canvas with a lone white pixel at (5, 5). */
  private def dot(): Image =
    Image.blank(11, 11, Scalar.Black, channels = 1).drawRect(Rect(5, 5, 1, 1), Scalar.White, Thickness.Filled)

  test("dilate grows a lone pixel into the (2r + 1)-square its radius names"):
    val grown = dot().dilate(radius = 1)
    assertEquals(pixel(grown, 4, 4), On) // the block's top-left corner
    assertEquals(pixel(grown, 3, 3), Off) // just outside it
    assertEquals(lit(grown), 9)
    assertEquals(lit(dot().dilate(radius = 2)), 25)

  test("MorphShape.Cross reaches only the four neighbours"):
    assertEquals(lit(dot().dilate(1, MorphShape.Cross)), 5)

  test("erode and Open remove a lone pixel; Gradient leaves its dilation"):
    assertEquals(lit(dot().erode(radius = 1)), 0)
    assertEquals(lit(dot().morphology(MorphOp.Open, 1)), 0)
    assertEquals(lit(dot().morphology(MorphOp.Gradient, 1)), 9)

  /** An 8×8 single-channel canvas at 100 everywhere: its local mean is 100, so `c` alone decides the sign. */
  private def flat(): Image = Image.blank(8, 8, Scalar(100), channels = 1)

  test("adaptiveThreshold's reordered parameters reach OpenCV intact: c is subtracted and mode flips"):
    for method <- AdaptiveMethod.values do
      assertEquals(lit(flat().adaptiveThreshold(blockSize = 3, c = 2, method = method)), 64, s"$method")
      assertEquals(lit(flat().adaptiveThreshold(blockSize = 3, c = -2, method = method)), 0, s"$method")
      assertEquals(
        lit(
          flat().adaptiveThreshold(
            blockSize = 3,
            c = 2,
            method = method,
            mode = Threshold.Mode.BinaryInv
          )
        ),
        0,
        s"$method"
      )

  test("adaptiveThreshold rejects an even or sub-3 blockSize by name"):
    for blockSize <- Seq(4, 1) do
      val img = flat()
      val e = intercept[IllegalArgumentException](img.adaptiveThreshold(blockSize = blockSize))
      assert(e.getMessage.contains("blockSize"), e.getMessage)
      img.close()
