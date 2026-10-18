package scalacv

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scalacv.graphs.*
import scalacv.vision.*

/** Property/invariant laws over generated Mat sizes and channel counts — the roundtrip and identity checks a
  * regression is most likely to break, complementing the example-based suites. Generators stay small and case
  * counts modest so the suite is fast under the native calls.
  */
class PropertyTest extends munit.ScalaCheckSuite:

  override def beforeAll(): Unit = OpenCv.load()

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  /** Mostly ordinary sizes, but a 1×1 and the tiny odd shapes are drawn often enough that every law sees the
    * edges a fixed example never tries — the sizes where OpenCV's C++ has historically thrown.
    */
  private val genDims: Gen[(Int, Int)] =
    val tiny =
      for
        w <- Gen.choose(1, 3)
        h <- Gen.choose(1, 3)
      yield (w, h)
    val ordinary =
      for
        w <- Gen.choose(4, 40)
        h <- Gen.choose(4, 40)
      yield (w, h)
    Gen.frequency((1, Gen.const((1, 1))), (2, tiny), (7, ordinary))

  private val genChannels: Gen[Int] = Gen.oneOf(1, 3, 4)

  /** A deterministic, non-uniform image of the given shape — non-uniform so a roundtrip/identity law is
    * actually exercised (a flat fill round-trips trivially). Caller owns it.
    */
  private def build(w: Int, h: Int, channels: Int, seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    var img = Image.blank(w, h, Scalar(20, 40, 60), channels)
    for _ <- 0 until 4 do
      val x = rnd.nextInt(w)
      val y = rnd.nextInt(h)
      val c = Scalar(rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble)
      img = img.drawRect(Rect(x, y, 1 + rnd.nextInt(w - x), 1 + rnd.nextInt(h - y)), c, Thickness.Filled)
    img

  /** The module's one pixel-exact content hash — see [[PixelHash]] for why every bit-exactness gate here
    * folds pixels the same way. Borrows the image rather than consuming it.
    */
  private def hash(img: Image): Long = PixelHash.of(img.mat)

  /** dims/channels equal and pixels byte-identical. Consumes neither. */
  private def samePixels(a: Image, b: Image): Boolean =
    a.width == b.width && a.height == b.height && a.channels == b.channels && hash(a) == hash(b)

  property("imencode/imdecode PNG roundtrip is lossless"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      try
        val bytes = src.copy.bytes(".png").fold(throw _, identity)
        val decoded = Image.decode(bytes, ImreadFlags.Unchanged).fold(throw _, identity)
        try samePixels(src, decoded)
        finally decoded.close()
      finally src.close()

  property("double horizontal flip is the identity"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val once = src.copy.flip(Flip.Horizontal)
      val twice = once.flip(Flip.Horizontal)
      try samePixels(src, twice)
      finally
        src.close()
        twice.close()

  property("rotate 90° clockwise then 90° counter-clockwise is the identity"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val back = src.copy.rotate(Rotation.Clockwise).rotate(Rotation.CounterClockwise)
      try samePixels(src, back)
      finally
        src.close()
        back.close()

  property("cvtColor BGR→RGB→BGR is the identity (3-channel)"):
    forAll(genDims, Gen.choose(0, 1_000_000)): (dims, seed) =>
      val (w, h) = dims
      val src = build(w, h, 3, seed)
      val back = src.copy.convert(ColorConversion.BgrToRgb).convert(ColorConversion.RgbToBgr)
      try samePixels(src, back)
      finally
        src.close()
        back.close()

  property("resize up then back down preserves the original dimensions"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val out = src.resize(w * 2, h * 2).resize(w, h)
      try out.width == w && out.height == h
      finally out.close()

  property("canny always yields a single-channel 8-bit image"):
    // .gray converts from BGR, so feed it a 3-channel image; the invariant under test is canny's output.
    forAll(genDims, Gen.choose(0, 1_000_000)): (dims, seed) =>
      val (w, h) = dims
      val out = build(w, h, 3, seed).gray.canny(50, 150)
      try out.channels == 1
      finally out.close()

  property("an integer Nearest upscale followed by an Area downscale is pixel-exact"):
    // Exact, not merely close: Nearest at 2× copies every byte into a 2×2 block, and Area at 1/2 averages
    // each 2×2 block — four identical bytes average to themselves, on every SIMD path.
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val back = src.copy
        .resizeTo(Size(2.0 * w, 2.0 * h), Interpolation.Nearest)
        .resizeTo(Size(w.toDouble, h.toDouble), Interpolation.Area)
      try samePixels(src, back)
      finally
        src.close()
        back.close()

  property("Rect.bottomRight and topLeft never wrap: bottomRight.x == x.toDouble + width"):
    assertEquals(
      Rect(Int.MaxValue - 1, Int.MaxValue - 1, 5, 5).bottomRight,
      Point(2147483651.0, 2147483651.0)
    )
    val genNonNegative = Gen.choose(0, Int.MaxValue)
    forAll(genNonNegative, genNonNegative, genNonNegative, genNonNegative): (x, y, w, h) =>
      val r = Rect(x, y, w, h)
      r.bottomRight == Point(x.toDouble + w, y.toDouble + h) && r.topLeft == Point(x.toDouble, y.toDouble)

  private val genPoint: Gen[Point] =
    for
      x <- Gen.choose(-1e4, 1e4)
      y <- Gen.choose(-1e4, 1e4)
    yield Point(x, y)

  property("Point.distanceTo is the Euclidean length: symmetric, zero to itself, and triangular"):
    assertEquals(Point(0, 0).distanceTo(Point(3, 4)), 5.0)
    forAll(genPoint, genPoint, genPoint): (a, b, c) =>
      a.distanceTo(b) == b.distanceTo(a)
        && a.distanceTo(a) == 0.0
        && a.distanceTo(c) <= a.distanceTo(b) + b.distanceTo(c) + 1e-9

  test("Size and Rect accept a zero extent, and a Rect may start at a negative origin"):
    assertEquals(Size(0, 0).width, 0.0)
    assertEquals(Rect(5, 5, 0, 0).area, 0L)
    assertEquals(Rect(-5, -5, 10, 10).topLeft, Point(-5, -5))
    assertEquals(Rect(-5, -5, 10, 10).bottomRight, Point(5, 5))

  property("Intrinsics accepts a distortion vector exactly when its length is one OpenCV's solvers accept"):
    forAll(Gen.choose(0, 20)): n =>
      scala.util.Try(Intrinsics(500, 500, 10, 10, Seq.fill(n)(0.01))).isSuccess
        == Intrinsics.ValidDistortionSizes.contains(n)

  test("Intrinsics rejects a non-positive focal length and names the offending distortion count"):
    intercept[IllegalArgumentException](Intrinsics(0, 500, 1, 1))
    intercept[IllegalArgumentException](Intrinsics(500, -1, 1, 1))
    val e = intercept[IllegalArgumentException](Intrinsics(500, 500, 1, 1, Seq.fill(3)(0.0)))
    assert(e.getMessage.contains("got 3"), e.getMessage)
    assert(e.getMessage.contains("Leave it empty"), e.getMessage)

  test(
    "Intrinsics.approx at a 90° field of view puts the focal length at half the width, centred, undistorted"
  ):
    // tan(45°) = 1, so f = width / 2 exactly up to floating-point rounding.
    val i = Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 90)
    assertEqualsDouble(i.fx, 320.0, 1e-9)
    assertEqualsDouble(i.fy, 320.0, 1e-9)
    assertEquals((i.cx, i.cy), (320.0, 240.0))
    assert(i.distortion.isEmpty)

  test("Intrinsics.approx rejects a field of view outside (0°, 180°) and an empty image"):
    intercept[IllegalArgumentException](Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 0))
    intercept[IllegalArgumentException](Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 180))
    intercept[IllegalArgumentException](Intrinsics.approx(Size(0, 480)))

  /** A face whose landmarks are irrelevant — `Face` insists on exactly five, and the clip reads none of them.
    */
  private def faceAt(box: Rect): Face = Face(box, Seq.fill(5)(Point(0, 0)), 0.9f)

  /** The pixel set a rectangle encloses — the oracle a clip is checked against. Small boxes only. */
  private def pixels(r: Rect): Set[(Int, Int)] =
    (for
      x <- r.x until r.x + r.width
      y <- r.y until r.y + r.height
    yield (x, y)).toSet

  private val genBox: Gen[Rect] =
    for
      x <- Gen.choose(-15, 15)
      y <- Gen.choose(-15, 15)
      w <- Gen.choose(0, 20)
      h <- Gen.choose(0, 20)
    yield Rect(x, y, w, h)

  // `clippedBox` admits a zero-sized frame, so the generator does too.
  private val genFrame: Gen[(Int, Int)] =
    for
      w <- Gen.choose(0, 12)
      h <- Gen.choose(0, 12)
    yield (w, h)

  property("Face.clippedBox is the pixel intersection of box and frame, or None when that set is empty"):
    forAll(genBox, genFrame): (box, frame) =>
      val (w, h) = frame
      val expected = pixels(box).intersect(pixels(Rect(0, 0, w, h)))
      faceAt(box).clippedBox(w, h) match
        case None => expected.isEmpty
        case Some(r) => r.width > 0 && r.height > 0 && pixels(r) == expected

  /** Whether every point of `p`'s bounding box lies within the `w`×`h` chart box (an empty picture does). */
  private def inside(p: Picture, w: Int, h: Int): Boolean =
    p.bounds.forall(b => b.minX >= -1e-9 && b.minY >= -1e-9 && b.maxX <= w + 1e-9 && b.maxY <= h + 1e-9)

  property("every chart's bounds lie inside its width×height box for any data"):
    forAll(Gen.nonEmptyListOf(Gen.choose(-100.0, 100.0)), Gen.choose(10, 200), Gen.choose(10, 200)):
      (values, w, h) =>
        // Bars only fit while gap·(n+1) + n·barWidth ≤ width, i.e. n ≤ (width − gap) / (gap + 1) with
        // the default gap of 4; past that Chart.bars runs off the right edge, so the series is bounded.
        val bounded = values.take(math.max(1, (w - 4) / 5))
        inside(Chart.line(values, w, h), w, h)
        && inside(Chart.area(values, w, h), w, h)
        && inside(Chart.scatter(values.zipWithIndex.map((v, i) => (i.toDouble, v)), w, h, radius = 3), w, h)
        && inside(Chart.pie(values, w, h), w, h)
        && inside(Chart.bars(bounded, w, h), w, h)
