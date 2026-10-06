package scalacv

import scalacv.graphs.*

import java.nio.file.{Files, Path}

import org.opencv.core.Core
import org.opencv.imgcodecs.{Animation as CvAnimation, Imgcodecs}

/** The deepened graphics layer: new primitives, layout, colour palettes, richer charts, and GIF export. */
class GraphicsDeepTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def px(img: Image, x: Int, y: Int): Array[Double] = img.mat.get(y, x)

  private given Releasable[CvAnimation] = Releasable.nativeHandle

  /** What a GIF round trip has to preserve, read back through OpenCV's own animation decoder. */
  private case class DecodedGif(frames: Int, frameSize: (Int, Int), durationsMs: Seq[Int], loopCount: Int)

  private def decodeGif(path: Path): DecodedGif =
    Managed(CvAnimation()).use: anim =>
      assert(Imgcodecs.imreadanimation(path.toString, anim), s"could not decode the GIF at $path")
      val frames = anim.get_frames
      try
        Managed.use(anim.get_durations): durations =>
          DecodedGif(
            frames.size,
            (frames.get(0).cols, frames.get(0).rows),
            durations.toArray.toSeq,
            anim.get_loop_count
          )
      finally frames.forEach(_.release())

  private def deleteRecursively(dir: Path): Unit =
    val paths = Files.walk(dir)
    try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p): Unit)
    finally paths.close()

  private def assertBounds(actual: Bounds, minX: Double, minY: Double, maxX: Double, maxY: Double)(using
      munit.Location
  ): Unit =
    assertEqualsDouble(actual.minX, minX, 1e-9, "minX")
    assertEqualsDouble(actual.minY, minY, 1e-9, "minY")
    assertEqualsDouble(actual.maxX, maxX, 1e-9, "maxX")
    assertEqualsDouble(actual.maxY, maxY, 1e-9, "maxY")

  test("an ellipse fills its interior and leaves the far corners"):
    val img =
      Picture.ellipse(Point(60, 40), 50, 25).fillColor(Color.Green).noStroke.render(120, 80, Color.Black)
    try
      assert(px(img, 60, 40)(1) > 150, "centre should be green")
      assert(px(img, 4, 4).sum < 40, "a corner outside the ellipse stays black")
    finally img.close()

  test("a filled sector paints a wedge but not the opposite side"):
    // A quarter slice from 0° (east) to 90° (south) about the centre.
    val img =
      Picture.sector(Point(50, 50), 40, 40, 0, 90).fillColor(Color.Red).noStroke.render(100, 100, Color.Black)
    try
      assert(px(img, 65, 65)(2) > 150, "inside the wedge is red")
      assert(px(img, 35, 35).sum < 40, "the opposite quadrant stays black")
    finally img.close()

  test("a label paints a filled background box"):
    val img = Picture.label("hi", Point(10, 10), Color.White, Color.Blue).render(120, 40, Color.Black)
    try
      val p = px(img, 20, 20) // inside the box → blue-ish (Color.Blue BGR ~ (230,110,50))
      assert(p(0) > 120 && p(2) < 120, s"the label box should be blue, got ${p.toList}")
    finally img.close()

  test("bounds enclose a shape's extent"):
    val b = Picture.rectangle(Rect(10, 20, 30, 40)).bounds.get
    assertEquals((b.minX, b.minY, b.maxX, b.maxY), (10.0, 20.0, 40.0, 60.0))
    assertEquals((b.width, b.height), (30.0, 40.0))

  /* A path with no points is what a filter that matched nothing produces, and `Draw.drawPolyline` already
   * treats it as a legitimate outcome rather than a programming error ("filtering everything out is a
   * legitimate outcome"). The Picture layer has to agree: it draws such a path as nothing, so measuring it
   * must answer "nothing" too, not throw. `bounds` returning None is what the layout combinators below are
   * already written to handle. */
  test("a path with no points has no bounds rather than throwing"):
    assertEquals(Picture.polyline(Seq.empty).bounds, None)
    assertEquals(Picture.polygon(Seq.empty).bounds, None)

  test("laying out an empty path beside a real shape keeps the real shape"):
    val square = Picture.rectangle(Rect(0, 0, 20, 20))
    assertEquals(Picture.polyline(Seq.empty).beside(square).bounds, square.bounds)
    assertEquals(square.above(Picture.polyline(Seq.empty)).bounds, square.bounds)

  test("an empty path in a grid still occupies its cell"):
    // Not the same claim as `beside`/`above`, which drop a measureless picture entirely: `grid` places by
    // index, so the empty cell keeps its slot and the square lands in the second column. The point of the
    // assertion is that laying it out neither throws nor resizes the real shape.
    val square = Picture.rectangle(Rect(0, 0, 20, 20))
    val laid = Picture.grid(Seq(Picture.polyline(Seq.empty), square), columns = 2).bounds
    assertEquals(laid.map(b => (b.width, b.height)), Some((20.0, 20.0)))

  test("beside places the second shape to the right of the first"):
    val a = Picture.rectangle(Rect(0, 0, 20, 20))
    val b = Picture.rectangle(Rect(0, 0, 20, 20))
    val laid = a.beside(b, gap = 10)
    val bounds = laid.bounds.get
    assertEquals(bounds.width, 50.0, "20 + 10 gap + 20")

  test("Color.spin rotates the hue; complement is 180° away"):
    val red = Color.Red
    val (h0, _, _) = red.hsl
    val (h1, _, _) = red.spin(120).hsl
    val delta = ((h1 - h0) % 360 + 360) % 360
    assert(math.abs(delta - 120) < 2, s"hue should advance ~120°, got $delta")
    val (hc, _, _) = red.complement.hsl
    val cdelta = ((hc - h0) % 360 + 360) % 360
    assert(math.abs(cdelta - 180) < 2, s"complement should be ~180° away, got $cdelta")

  test("Color.wheel gives distinct evenly-spaced hues; ramp interpolates endpoints"):
    val wheel = Color.wheel(4)
    assertEquals(wheel.size, 4)
    assertEquals(wheel.map(_.hsl._1.round).toSet.size, 4, "four distinct hues")
    val ramp = Color.ramp(Color.Black, Color.White, 3)
    assertEquals(ramp.head, Color.Black)
    assertEquals(ramp.last, Color.White)
    assert(ramp(1).red > 100 && ramp(1).red < 160, "the middle is mid-grey")

  test("Chart.pie and histogram render without error and size to their box"):
    val pie = Picture.all(Seq(Chart.pie(Seq(3, 1, 1), 80, 80))).render(80, 80, Color.Black)
    try assertEquals((pie.width, pie.height), (80, 80))
    finally pie.close()
    val hist = Chart.histogram(Seq(1.0, 2, 2, 3, 3, 3, 4), bins = 4, width = 100, height = 50).render(100, 50)
    try assertEquals((hist.width, hist.height), (100, 50))
    finally hist.close()

  test("Animation.gif writes a multi-frame animated GIF"):
    val out = Files.createTempFile("scalacv-anim-", ".gif")
    try
      val written = Animation.gif(out.toString, frames = 6, width = 60, height = 40, fps = 10) { i =>
        Picture.circle(Point(10 + i * 8, 20), 6).fillColor(Color.wheel(6)(i)).noStroke
      }
      assertEquals(written, Right(6L))
      assert(Files.size(out) > 0, "the GIF file should have content")
    finally Files.deleteIfExists(out)

  test("Animation.gif closes already-rendered frames when a later frame throws"):
    // The frame lambda throws at index 3, after frames 0-2 have been rendered (each owns a Mat). The
    // fix renders inside gif's try, so its finally closes those three; the eager tabulate it replaced
    // ran before the try and leaked them. The throw must propagate (proving the finally path ran), and
    // a subsequent valid gif must still succeed — a regression that corrupted state would fail here.
    val out = Files.createTempFile("scalacv-gif-throw-", ".gif")
    try
      intercept[RuntimeException] {
        Animation.gif(out.toString, frames = 6, width = 40, height = 40) { i =>
          if i == 3 then throw new RuntimeException("boom")
          else Picture.circle(Point(20, 20), 6).fillColor(Color.White).noStroke
        }
      }
      val written =
        Animation.gif(out.toString, frames = 4, width = 40, height = 40)(i =>
          Picture.circle(Point(10 + i * 5, 20), 4).fillColor(Color.White).noStroke
        )
      assertEquals(written, Right(4L))
    finally Files.deleteIfExists(out): Unit

  // -- transforms and layout ---------------------------------------------------------------------------

  test("transforms apply innermost first: rotate(90) then translate(100, 0) lands where the maths says"):
    // With y down, a 90° turn about the origin maps (x, y) to (-y, x), so the corners (10, 10) and (30, 20)
    // land at (-10, 10) and (-20, 30) before the shift. Composed the other way round the box would sit at
    // (-20, 110)..(-10, 130).
    val b = Picture.rectangle(Rect(10, 10, 20, 10)).rotate(90).translate(100, 0).bounds.get
    assertBounds(b, 80, 10, 90, 30)

  test("scale doubles the extent about its anchor; rotating a square about its centre keeps its bounds"):
    assertEquals(
      Picture.rectangle(Rect(0, 0, 20, 10)).scale(2, Point(0, 0)).bounds,
      Some(Bounds(0, 0, 40, 20))
    )
    val turned = Picture.rectangle(Rect(0, 0, 20, 20)).rotate(90, about = Point(10, 10)).bounds.get
    assertBounds(turned, 0, 0, 20, 20)

  test("a rotated circle's bounds keep both axes — the corners must not collapse onto the rotation line"):
    // A circle is rotation-invariant: rotating about its own centre changes nothing, so the bounds are the
    // centre ± the radius. The previous implementation transformed the two bbox *corners*, which at 45° map
    // onto a single line — the width collapsed to zero while the full circle was still drawn.
    val b = Picture.circle(Point(50, 50), 20).rotate(45, about = Point(50, 50)).bounds.get
    assertBounds(b, 30, 30, 70, 70)

  test("a rotated circle's bounds still cover every painted pixel"):
    // Rotated about a point that is not the centre, so the centre genuinely moves. The geometry bounds
    // describe the circle, so the stroke (1 px) and antialiasing may paint a couple of pixels outside —
    // the tolerance is exactly that, no more.
    val pic = Picture
      .circle(Point(50, 50), 20)
      .strokeColor(Color.White)
      .noFill
      .rotate(30, about = Point(20, 20))
    val b = pic.bounds.get
    val img = pic.render(100, 100, Color.Black)
    try
      val outside =
        for
          y <- 0 until 100
          x <- 0 until 100
          if px(img, x, y).sum > 128
          if x < b.minX - 2 || x > b.maxX + 2 || y < b.minY - 2 || y > b.maxY + 2
        yield (x, y)
      assert(outside.isEmpty, s"painted pixels escaped the bounds (±2px ink): ${outside.take(5)}")
    finally img.close()

  test("rotated text's bounds are the axis-aligned glyph box at the rotated anchor"):
    // putText cannot rotate: the glyphs are drawn axis-aligned wherever the anchor lands, so the bounds are
    // that box — not a rotated glyph quad. 90° about the origin maps (x, y) to (-y, x) (y is down).
    val m = Draw.textSize("hi", Font.Simplex, 1.0)
    val b = Picture.text("hi", Point(100, 60)).fontScale(1.0).rotate(90, about = Point(0, 0)).bounds.get
    assertBounds(b, -60.0, 100.0 - m.size.height, -60.0 + m.size.width, 100.0 + m.baseline)

  test("above centres the lower picture under the upper one, a gap below it"):
    val wide = Picture.rectangle(Rect(0, 0, 40, 10))
    val narrow = Picture.rectangle(Rect(0, 0, 10, 10))
    val laid = wide.above(narrow, gap = 5)
    assertEquals(laid.bounds, Some(Bounds(0, 0, 40, 25)))
    val img = laid.smooth(false).render(50, 30, Color.Black)
    try
      // The narrow box is centred under x = 20, so its 1 px outline stands at x = 15 and x = 25.
      assert(px(img, 15, 20)(0) > 128, "the lower box's left edge")
      assert(px(img, 25, 20)(0) > 128, "the lower box's right edge")
      assert(px(img, 5, 20)(0) < 30, "nothing is drawn left of the lower box")
    finally img.close()

  test("grid wraps into rows of uniform cells; an empty grid has no bounds; zero columns is rejected"):
    val square = Picture.rectangle(Rect(0, 0, 20, 20))
    // A 20 px cell plus the 8 px gap is 28, so the third square starts the second row at y = 28.
    assertEquals(
      Picture.grid(Seq(square, square, square), columns = 2, gap = 8).bounds,
      Some(Bounds(0, 0, 48, 48))
    )
    assertEquals(Picture.grid(Seq.empty, columns = 2).bounds, None)
    intercept[IllegalArgumentException](Picture.grid(Seq(Picture.empty), columns = 0))

  // -- strokes and preconditions -----------------------------------------------------------------------

  test("a dashed stroke repeats with period on + off, starting with an 'on' run"):
    val img = Picture.line(Point(10, 50), Point(90, 50)).strokeDash(Dash(6, 6)).smooth(false).render(100, 100)
    try
      // LINE_8 with integer endpoints paints both ends, so run k covers x in [10 + 12k, 16 + 12k].
      def lit(x: Int): Double = px(img, x, 50)(0)
      assert(lit(13) > 128, "inside the first dash")
      assert(lit(19) < 128, "inside the first gap")
      assert(lit(25) > 128, "inside the second dash")
      assert(lit(31) < 128, "inside the second gap")
      assert(lit(37) > 128, "inside the third dash")
    finally img.close()

  test("a dash longer than the segment is clamped to the segment"):
    val img = Picture.line(Point(10, 50), Point(15, 50)).strokeDash(Dash(10, 8)).smooth(false).render(30, 60)
    try
      val along = (10 to 15).map(x => px(img, x, 50)(0))
      assert(along.forall(_ > 128), s"the whole 5 px segment should be painted, got $along")
    finally img.close()

  test("drawing the empty picture still spends the receiver and leaves the pixels untouched"):
    val base =
      Image.blank(40, 40, Scalar(20, 40, 60)).drawRect(Rect(5, 5, 10, 10), Scalar.Red, Thickness.Filled)
    val reference = base.copy
    try
      val out = base.draw(Picture.empty)
      try
        intercept[IllegalStateException](base.width)
        assertEquals(Core.norm(out.mat, reference.mat, Core.NORM_INF), 0.0)
      finally out.close()
    finally reference.close()

  test("graphics value types reject their degenerate arguments"):
    intercept[IllegalArgumentException](Dash(0, 4))
    intercept[IllegalArgumentException](Dash(4, 0))
    intercept[IllegalArgumentException](Picture.regularPolygon(Point(0, 0), sides = 2, radius = 10))
    intercept[IllegalArgumentException](Picture.star(Point(0, 0), points = 1, outer = 10, inner = 5))

  test("a zero stroke width clamps to one pixel rather than drawing nothing"):
    val img = Picture.line(Point(2, 5), Point(18, 5)).strokeWidth(0).smooth(false).render(20, 10)
    try assert(px(img, 10, 5)(0) > 128, "the line should still be painted")
    finally img.close()

  // -- canvas types ------------------------------------------------------------------------------------

  test("a picture paints a single-channel canvas with its intensity"):
    val square = Picture.rectangle(Rect(10, 10, 20, 20)).fillColor(Color.White).noStroke
    val out = Image.blank(40, 40, Scalar.Black, channels = 1).draw(square)
    try
      assertEquals(out.channels, 1)
      assertEquals(px(out, 20, 20)(0), 255.0)
      assertEquals(px(out, 2, 2)(0), 0.0)
    finally out.close()

  test("a picture paints a BGRA canvas, and a translucent fill blends on it too"):
    val square = Picture.rectangle(Rect(10, 10, 20, 20))
    val opaque = Image.blank(40, 40, Scalar.Black, channels = 4).draw(square.fillColor(Color.White).noStroke)
    try
      assertEquals(opaque.channels, 4)
      // The alpha channel is deliberately not asserted: Color.toBgrScalar carries no alpha, so what lands
      // there is not part of the contract.
      val p = px(opaque, 20, 20)
      assert(p.take(3).forall(_ == 255.0), s"the colour channels should be white, got ${p.toList}")
    finally opaque.close()
    val blended = Image
      .blank(40, 40, Scalar.Black, channels = 4)
      .draw(square.fillColor(Color.White.withAlpha(128)).noStroke)
    try
      val v = px(blended, 20, 20)(0)
      assert(v > 120 && v < 136, s"white at alpha 128 over black should be ~128, got $v")
    finally blended.close()

  // -- charts ------------------------------------------------------------------------------------------

  test("bars scale to the tallest, sit on the baseline, and use magnitudes"):
    // gap 4 and two values: barWidth = (60 - 12) / 2 = 24, so the bars are Rect(4, 2, 24, 48) and
    // Rect(32, 26, 24, 24). LINE_8 keeps the gap probe, 2 px from the first bar's edge, clear of any spread.
    val img = Chart.bars(Seq(2.0, 1.0), 60, 50, Color.White, gap = 4).smooth(false).render(60, 50)
    try
      assert(px(img, 16, 10)(0) > 200, "inside the tall bar")
      assert(px(img, 44, 10)(0) < 30, "above the short bar")
      assert(px(img, 44, 40)(0) > 200, "inside the short bar")
      assert(px(img, 2, 25)(0) < 30, "the gap before the first bar")
    finally img.close()
    assertEquals(Chart.bars(Seq(-2.0, 1.0), 60, 50).bounds, Chart.bars(Seq(2.0, 1.0), 60, 50).bounds)

  test("a series chart needs two samples, puts the peak at the top, and centres a lone scatter point"):
    assertEquals(Chart.line(Seq(1.0), 100, 50).bounds, None)
    assertEquals(Chart.area(Seq(1.0), 100, 50).bounds, None)
    assertEquals(Chart.line(Seq(0.0, 4.0), 100, 50).bounds, Some(Bounds(0, 2, 100, 50)))
    assertEquals(Chart.scatter(Seq((3.0, 3.0)), 100, 60, radius = 3).bounds, Some(Bounds(47, 27, 53, 33)))

  test("scatter maps the data range into the box with y up; area's fill is a real blend of its colour"):
    val scatter =
      Chart.scatter(Seq((0.0, 0.0), (1.0, 1.0)), 100, 100, Color.White, radius = 3).render(100, 100)
    try
      assert(px(scatter, 3, 97)(0) > 200, "the minimum sits bottom-left")
      assert(px(scatter, 97, 3)(0) > 200, "the maximum sits top-right")
    finally scatter.close()
    // The default Color.Blue is BGR (230, 110, 50) and the fill is fadeOut(0.7), alpha 77, so the blue
    // channel over black blends to about 69: neither the background nor the opaque stroke.
    val area = Chart.area(Seq(4.0, 4.0), 100, 50).render(100, 50)
    try
      val v = px(area, 50, 45)(0)
      assert(v > 55 && v < 85, s"the area fill should be a 30% blend of blue over black, got $v")
    finally area.close()

  test("a two-slice pie paints the first colour on the right half and cycles a short palette"):
    // Slices start at 12 o'clock and run clockwise, so the first of two equal slices is the east half.
    val two = Chart.pie(Seq(1.0, 1.0), 80, 80, palette = Seq(Color.White, Color.Red)).render(80, 80)
    try
      assert(px(two, 60, 40).forall(_ > 240), s"the first slice is white, got ${px(two, 60, 40).toList}")
      val left = px(two, 20, 40)
      assert(left(2) > 200 && left(0) < 60, s"the second slice is red, got ${left.toList}")
    finally two.close()
    // Three slices over two colours: the third must wrap back to white, which a palette that merely clamps
    // at its last entry would paint red.
    val cycled = Chart.pie(Seq(1.0, 1.0, 1.0), 80, 80, palette = Seq(Color.White, Color.Red)).render(80, 80)
    try
      val second = px(cycled, 40, 55)
      assert(second(2) > 200 && second(0) < 60, s"the second slice is red, got ${second.toList}")
      assert(
        px(cycled, 25, 40).forall(_ > 240),
        s"the third slice wraps to white, got ${px(cycled, 25, 40).toList}"
      )
    finally cycled.close()
    assertEquals(Chart.pie(Seq(0.0, 0.0), 80, 80).bounds, None)
    assertEquals(Chart.pie(Seq(1.0), 80, 80, palette = Seq.empty).bounds, None)

  test("a histogram bins its maximum into the last bin and constant data into bin 0"):
    // Four bins over 1..4 with gap 1: barWidth = (100 - 5) / 4 = 23, so bin i spans x = 1 + 24i to 24 + 24i,
    // and the counts (1, 2, 3, 1) put the bar tops at y = 34, 18, 2 and 34. Color.Purple is BGR (200, 80, 150).
    val img = Chart.histogram(Seq(1, 2, 2, 3, 3, 3, 4), bins = 4, 100, 50).render(100, 50)
    try
      assert(px(img, 84, 42)(0) > 150, "the value 4 counts in the last bin")
      assert(px(img, 84, 25)(0) < 30, "with a count of one, not more")
      assert(px(img, 60, 10)(0) > 150, "the three 3s make the third bin the tallest")
      assert(px(img, 36, 10)(0) < 30, "the second bin stops at two")
    finally img.close()
    val flat = Chart.histogram(Seq(5, 5, 5), bins = 4, 100, 50).render(100, 50)
    try
      assert(px(flat, 12, 25)(0) > 150, "constant data all lands in bin 0")
      assert(px(flat, 60, 25)(0) < 30, "and leaves the other bins empty")
    finally flat.close()

  // -- Animation.record and gif ------------------------------------------------------------------------

  // Output files get a fixed name inside a per-test temp directory, as VideoTest and CameraTest do, so one
  // recursive delete removes whatever a run left behind, including a file a failing assertion never reached.

  test("record deletes the partial file and rethrows when a frame throws"):
    val dir = Files.createTempDirectory("scalacv-record-throw")
    try
      // The file exists before recording starts, so only deletePartial can make it vanish.
      val out = Files.createFile(dir.resolve("partial.avi"))
      val thrown = intercept[RuntimeException] {
        Animation.record(out.toString, frames = 5, width = 64, height = 48) { i =>
          if i == 2 then throw RuntimeException("boom")
          else Picture.circle(Point(32, 24), 5).fillColor(Color.White).noStroke
        }
      }
      assertEquals(thrown.getMessage, "boom")
      assert(!Files.exists(out), "two frames had been written, so the truncated file must be deleted")
    finally deleteRecursively(dir)

  test(
    "record answers Left for an unopenable target, Right(0) for zero frames, and rejects a negative count"
  ):
    val dir = Files.createTempDirectory("scalacv-record-")
    try
      val missing = dir.resolve("missing").resolve("x.avi")
      assert(Animation.record(missing.toString, 3, 64, 48)(_ => Picture.empty).isLeft)
      assert(!Files.exists(missing))
      val empty = dir.resolve("empty.avi")
      assertEquals(Animation.record(empty.toString, frames = 0, 64, 48)(_ => Picture.empty), Right(0L))
      intercept[IllegalArgumentException] {
        Animation.record(dir.resolve("negative.avi").toString, -1, 8, 8)(_ => Picture.empty)
      }
    finally deleteRecursively(dir)

  test("a recorded video decodes back to exactly the frames written"):
    val dir = Files.createTempDirectory("scalacv-record-roundtrip")
    try
      val out = dir.resolve("clip.avi")
      val written = Animation.record(out.toString, frames = 5, width = 64, height = 48, fps = 10)(i =>
        Picture.circle(Point(32, 24), 5 + i.toDouble).fillColor(Color.White).noStroke
      )
      assertEquals(written, Right(5L))
      val sizes = Video
        .open(out.toString)
        .fold(throw _, _.use(capture => Video.frames(capture)(_.map(m => (m.cols, m.rows)).toList)))
      assertEquals(sizes, List.fill(5)((64, 48)))
    finally deleteRecursively(dir)

  test("a GIF carries one frame per step at 1000/fps ms, and its loop flag changes the encoded loop count"):
    val dir = Files.createTempDirectory("scalacv-gif-")
    try
      val once = dir.resolve("once.gif")
      val forever = dir.resolve("forever.gif")
      def dot(i: Int): Picture = Picture.circle(Point(10 + i * 8, 20), 6).fillColor(Color.White).noStroke
      assertEquals(Animation.gif(once.toString, 6, 60, 40, fps = 10, loop = false)(dot), Right(6L))
      assertEquals(Animation.gif(forever.toString, 6, 60, 40, fps = 10, loop = true)(dot), Right(6L))
      val decoded = decodeGif(once)
      assertEquals(decoded.frames, 6)
      assertEquals(decoded.frameSize, (60, 40))
      assertEquals(decoded.durationsMs, Seq.fill(6)(100))
      // The literal loop counts belong to the encoder; what scalacv owns is that the flag reaches the file.
      assertNotEquals(decoded.loopCount, decodeGif(forever).loopCount)
    finally deleteRecursively(dir)

  test(
    "gif answers Left with no file for an unwritable target, Right(0) with no file for zero frames, and rejects fps 0"
  ):
    val dir = Files.createTempDirectory("scalacv-gif-edge-")
    try
      val missing = dir.resolve("missing").resolve("x.gif")
      assert(Animation.gif(missing.toString, 3, 8, 8)(_ => Picture.empty).isLeft)
      assert(!Files.exists(missing))
      val none = dir.resolve("zero.gif")
      assertEquals(Animation.gif(none.toString, 0, 8, 8)(_ => Picture.empty), Right(0L))
      assert(!Files.exists(none), "zero frames should not touch the filesystem")
      intercept[IllegalArgumentException] {
        Animation.gif(dir.resolve("fps.gif").toString, 3, 8, 8, fps = 0)(_ => Picture.empty)
      }
    finally deleteRecursively(dir)
