package scalacv

import scalacv.graphs.*

/** The Picture graphics layer and Color, verified at the pixel level on rendered canvases. */
class GraphicsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** BGR pixel at (x, y). */
  private def px(img: Image, x: Int, y: Int): Array[Double] = img.mat.get(y, x)

  test("a filled shape paints its colour; the background stays"):
    val img = Picture.circle(Point(50, 50), 20).fillColor(Color.Red).noStroke.render(100, 100, Color.Black)
    try
      val centre = px(img, 50, 50) // Color.Red → BGR (40, 40, 220)
      assert(centre(2) > 180 && centre(0) < 90, s"centre should be red, got ${centre.toList}")
      val corner = px(img, 4, 4)
      assert(
        corner(0) < 25 && corner(1) < 25 && corner(2) < 25,
        s"corner should be black, got ${corner.toList}"
      )
    finally img.close()

  test("a dashed stroke leaves gaps a solid one would not"):
    val dashed =
      Picture.line(Point(10, 50), Point(90, 50)).strokeColor(Color.White).strokeDash(Dash(6, 6)).smooth(false)
    val img = dashed.render(100, 100, Color.Black)
    try
      val along = (10 to 90).map(x => px(img, x, 50)(0))
      assert(along.count(_ > 128) > 5, "a dashed line must paint some pixels")
      assert(along.count(_ < 128) > 5, "a dashed line must leave gaps")
    finally img.close()

  test("a solid stroke of the same line has no gaps"):
    val img = Picture
      .line(Point(10, 50), Point(90, 50))
      .strokeColor(Color.White)
      .smooth(false)
      .render(100, 100, Color.Black)
    try
      val along = (12 to 88).map(x => px(img, x, 50)(0))
      assert(along.forall(_ > 128), "a solid line should be painted end to end")
    finally img.close()

  test("composition draws top over bottom"):
    val pic = Picture
      .circle(Point(50, 50), 14)
      .fillColor(Color.Blue)
      .noStroke
      .on(Picture.rectangle(Rect(20, 20, 60, 60)).fillColor(Color.Red).noStroke)
    val img = pic.render(100, 100, Color.Black)
    try
      val centre = px(img, 50, 50) // circle (blue, on top) → BGR blue high
      assert(centre(0) > 180, s"centre should be the top circle (blue), got ${centre.toList}")
      val rectOnly = px(img, 24, 24) // inside the rect, outside the circle → red
      assert(rectOnly(2) > 180, s"rect area should be red, got ${rectOnly.toList}")
    finally img.close()

  test("alpha gives a real blend over the background"):
    val img = Picture
      .rectangle(Rect(20, 20, 60, 60))
      .fillColor(Color.White.withAlpha(128))
      .noStroke
      .render(100, 100, Color.Black)
    try
      val blended = px(img, 50, 50) // white at 50% over black ≈ mid grey
      assert(blended.forall(c => c > 100 && c < 160), s"expected ~grey, got ${blended.toList}")
    finally img.close()

  test("draw overlays a picture on an existing image and consumes it"):
    val base = Image.blank(100, 100, Scalar.Black)
    val out = base.draw(Picture.marker(Point(50, 50), Color.Green, radius = 6))
    intercept[IllegalStateException](base.width) // base was consumed
    try assert(px(out, 50, 50)(1) > 150, s"marker should be green, got ${px(out, 50, 50).toList}")
    finally out.close()

  test("translate moves a shape"):
    val img =
      Picture.marker(Point(0, 0), Color.White, radius = 5).at(Point(70, 30)).render(100, 100, Color.Black)
    try
      assert(px(img, 70, 30)(0) > 150, "the marker should be at the translated position")
      assert(px(img, 5, 5)(0) < 30, "and not at the origin")
    finally img.close()

  test("Color helpers: alpha, lighten, fadeOut, hsl"):
    assertEquals(Color.Red.withAlpha(128).alpha, 128)
    assertEquals(Color.Black.lighten(1.0), Color.White)
    assertEquals(Color.White.fadeOut(0.5).alpha, 128)
    val red = Color.hsl(0, 1.0, 0.5)
    assert(red.red > 200 && red.green < 60 && red.blue < 60, s"hsl(0,1,0.5) should be red, got $red")

  test("Color.toBgrScalar bridges RGBA to the BGR the drawing verbs take"):
    // A red Color must land in the BGR Scalar's third slot, not its first.
    assertEquals(Color(200, 40, 40).toBgrScalar, Scalar(40, 40, 200))
    // Scalar.Red (BGR) round-trips back to an opaque red Color.
    assertEquals(Scalar.Red.toColor, Color(255, 0, 0))
    // Round-trip through the bridge preserves an opaque colour.
    val c = Color(17, 128, 240)
    assertEquals(c.toBgrScalar.toColor, c)

  /** The rendered pixels of a chart picture, so two series can be compared as drawn, not as trees. */
  private def rendered(p: Picture, w: Int, h: Int): Array[Byte] =
    val img = p.render(w, h, Color.Black)
    try
      val data = new Array[Byte]((img.mat.total() * img.mat.channels()).toInt)
      img.mat.get(0, 0, data)
      data
    finally img.close()

  test("a series with negatives charts by magnitude — no throw, inside the box, identical to its abs"):
    val signed = Seq(-6.0, 3.0, -9.0, 1.5, -4.0)
    val magnitude = signed.map(math.abs)
    val (w, h) = (120, 60)
    // Each builder accepts the signed series without throwing, and its bounds stay inside the box.
    Seq(
      "bars" -> Chart.bars(signed, w, h),
      "line" -> Chart.line(signed, w, h),
      "area" -> Chart.area(signed, w, h),
      "pie" -> Chart.pie(signed, w, h)
    ).foreach: (name, chart) =>
      val b = chart.bounds.getOrElse(fail(s"$name: a non-empty signed series should have bounds"))
      assert(
        b.minX >= -1e-9 && b.minY >= -1e-9 && b.maxX <= w + 1e-9 && b.maxY <= h + 1e-9,
        s"$name: bounds $b escaped the ${w}x$h box"
      )
    // The documented magnitude semantics, pinned as drawn: a signed series renders pixel-for-pixel
    // identical to its absolute values — the sign is dropped, not plotted downward.
    Seq(
      "bars" -> (Chart.bars(_, w, h)),
      "line" -> (Chart.line(_, w, h)),
      "area" -> (Chart.area(_, w, h)),
      "pie" -> (Chart.pie(_, w, h))
    ).foreach: (name, build) =>
      assert(
        rendered(build(signed), w, h).sameElements(rendered(build(magnitude), w, h)),
        s"$name: a series with negatives must render exactly as its magnitudes do"
      )

  test("Scalar.toColor clamps and rounds out-of-gamut channels"):
    assertEquals(Scalar(-10.0, 127.6, 300.0).toColor, Color(red = 255, green = 128, blue = 0))

  test("Animation.frames renders every canvas before returning, rather than lazily"):
    // Pins the return type against a regression to LazyList. A lazy sequence would have rendered
    // nothing by the time `frames` returns (so `rendered` would still be 0), and would then memoise
    // every canvas it yielded — the shape Video documents as forbidden, because a caller who obeys
    // "each image is yours to close" gets spent handles back on the second traversal.
    var rendered = 0
    val images = Animation.frames(3, 32, 32) { i =>
      rendered += 1
      Picture.marker(Point(16, 16), Color.White, radius = 2 + i)
    }
    try
      assertEquals(rendered, 3, "every canvas must exist before the Seq reaches the caller")
      assertEquals(images.size, 3)
      assertEquals(images.map(_.width).toList, List(32, 32, 32))
      // Reference equality: Image is a final class, so this is identity, not pixel comparison.
      assertEquals(images.distinct.size, 3, "each frame must be its own canvas")
    finally images.foreach(_.close())

  test("Animation.frames rejects a negative count"):
    intercept[IllegalArgumentException](Animation.frames(-1, 16, 16)(_ => Picture.empty))

  test("Animation.foreach hands over one live canvas at a time and closes each one"):
    // The images are captured here only so the test can prove they were released afterwards. Letting
    // an Image escape the scope that owns it is exactly what this method exists to spare callers.
    val handedOut = scala.collection.mutable.ArrayBuffer.empty[Image]
    Animation.foreach(4, 24, 24)(i => Picture.marker(Point(12, 12), Color.White, radius = 2 + i)) { img =>
      handedOut += img
      assertEquals(img.width, 24)
      assertEquals(
        handedOut.count(_.toString.contains("<closed>")),
        handedOut.size - 1,
        "only the canvas currently being handled may be alive"
      )
    }
    assertEquals(handedOut.size, 4)
    handedOut.foreach(img => intercept[IllegalStateException](img.width))

  test("Animation.foreach rejects a negative count and draws nothing for zero"):
    intercept[IllegalArgumentException](Animation.foreach(-1, 8, 8)(_ => Picture.empty)(_ => ()))
    var calls = 0
    Animation.foreach(0, 8, 8)(_ => Picture.empty)(_ => calls += 1)
    assertEquals(calls, 0)
