package scalacv

import org.opencv.core.{Core, CvType, Mat}

/** Every fixture here is drawn from scratch: a black Mat and the operation under test. There is no image file
  * in this repo and none may be added, and for drawing that is no loss — the assertion that matters is "did
  * these pixels change and those not", which needs a known-blank canvas rather than a photograph.
  */
class DrawTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A black canvas, released after `f`. Single-channel unless `channels` says otherwise, because
    * `countNonZero` — the cheapest "did anything get drawn" assertion — only accepts one channel.
    */
  private def canvas[A](width: Int = 100, height: Int = 100, channels: Int = 1)(f: Mat => A): A =
    val depth = if channels == 1 then CvType.CV_8UC1 else CvType.CV_8UC3
    Managed.use(Mat.zeros(height, width, depth))(f)

  private def pixel(m: Mat, x: Int, y: Int): Seq[Double] = m.get(y, x).toIndexedSeq

  test("a filled rectangle whitens its interior and leaves the outside black"):
    canvas(channels = 3): m =>
      val r = Rect(20, 20, 40, 30)
      m.drawRect(r, Scalar.White, Thickness.Filled)

      assertEquals(pixel(m, 40, 35), Seq(255.0, 255.0, 255.0), "the centre of the rectangle")
      assertEquals(pixel(m, 5, 5), Seq(0.0, 0.0, 0.0), "a pixel well outside it")
      // The far corner is outside too: a Rect is x/y plus extent, not two corners.
      assertEquals(pixel(m, 90, 90), Seq(0.0, 0.0, 0.0), "the far corner")

  test("an outlined rectangle draws its border and not its interior"):
    canvas(): m =>
      m.drawRect(Rect(20, 20, 40, 30), Scalar.White, Thickness.Stroke(1))

      assertEquals(pixel(m, 40, 20), Seq(255.0), "a pixel on the top edge")
      assertEquals(pixel(m, 40, 35), Seq(0.0), "the untouched centre")

  test("colour reaches the right channel — Scalar is BGR, not RGB"):
    canvas(channels = 3): m =>
      m.drawRect(Rect(10, 10, 20, 20), Scalar.Red, Thickness.Filled)
      assertEquals(pixel(m, 20, 20), Seq(0.0, 0.0, 255.0))

  test("drawText changes pixels, and textSize predicts roughly where"):
    canvas(width = 240, height = 80): m =>
      assertEquals(Core.countNonZero(m), 0)

      val metrics = Draw.textSize("scalacv", scale = 1.0)
      assert(metrics.size.width > 0 && metrics.size.height > 0, s"empty text metrics: $metrics")

      m.drawText("scalacv", Point(10, 50), Scalar.White)
      val drawn = Core.countNonZero(m)
      assert(drawn > 0, "putText drew nothing")

      // The glyphs must land inside the box getTextSize promised, allowing a pixel of stroke
      // overhang on each side. Both slices must be non-degenerate for the check to mean anything.
      val right = math.ceil(10 + metrics.size.width + 2).toInt
      val top = math.floor(50 - metrics.size.height - 2).toInt
      assert(right < 240 && top > 0, s"the canvas is too small to test the spill of $metrics")

      Managed.use(m.submat(0, 80, right, 240)): outside =>
        assertEquals(Core.countNonZero(outside), 0, "glyphs spilled past the measured width")
      Managed.use(m.submat(0, top, 0, 240)): above =>
        assertEquals(Core.countNonZero(above), 0, "glyphs spilled above the measured height")

  test("an anti-aliased line does not throw, and softens the edges a plain one leaves hard"):
    canvas(): hard =>
      canvas(): soft =>
        val (from, to) = (Point(10, 12), Point(90, 78))
        hard.drawLine(from, to, Scalar.White, Thickness.Stroke(1), LineType.Connected8)
        soft.drawLine(from, to, Scalar.White, Thickness.Stroke(1), LineType.AntiAliased)

        assert(Core.countNonZero(hard) > 0, "the plain line drew nothing")
        // Anti-aliasing spreads the same line over more, partially lit, pixels.
        assert(
          Core.countNonZero(soft) > Core.countNonZero(hard),
          s"anti-aliased line lit ${Core.countNonZero(soft)} pixels, plain lit ${Core.countNonZero(hard)}"
        )

  test("anti-aliasing draws the right colour in the right place, for every shape"):
    // Previously this test had no assertions at all: it passed as long as nothing threw, so every
    // one of these five calls could have drawn nothing, or the wrong colour, or in the wrong
    // place. It is also the only coverage drawArrow and Font.Duplex get.
    canvas(channels = 3): m =>
      m.drawRect(Rect(5, 5, 20, 20), Scalar.Green, Thickness.Stroke(2), LineType.AntiAliased)
      m.drawCircle(Point(50, 50), 15, Scalar.Blue, Thickness.Filled, LineType.AntiAliased)
      m.drawArrow(Point(10, 90), Point(90, 90), Scalar.Red, lineType = LineType.AntiAliased)
      m.drawText("ok", Point(60, 20), Scalar.White, Font.Duplex, 0.5, lineType = LineType.AntiAliased)
      m.drawPolyline(Seq(Point(70, 60), Point(90, 60), Point(80, 75)), lineType = LineType.AntiAliased)

      // Mats are BGR, so Scalar.Green is (0, 255, 0) and Scalar.Red is (0, 0, 255).
      def px(x: Int, y: Int): Seq[Double] = m.get(y, x).toSeq
      assertEquals(px(50, 50), Seq(255.0, 0.0, 0.0), "the filled circle's centre should be blue")
      assertEquals(px(5, 15), Seq(0.0, 255.0, 0.0), "the rectangle's left edge should be green")
      // The shaft is anti-aliased, so it lands near 255 rather than on it (measured: 232).
      // Assert the channel is dominant instead of pinning a blend value the renderer may tune.
      val shaft = px(50, 90)
      assert(shaft(2) > 200, s"the arrow shaft should be strongly red, got $shaft")
      assertEquals(shaft(0), 0.0, s"the arrow shaft should have no blue, got $shaft")
      assertEquals(shaft(1), 0.0, s"the arrow shaft should have no green, got $shaft")
      assert(px(80, 40).forall(_ == 0.0), "a point in no shape should still be background")

  test("a filled circle whitens its centre and not its corner"):
    canvas(): m =>
      m.drawCircle(Point(50, 50), 20, Scalar.White, Thickness.Filled)
      assertEquals(pixel(m, 50, 50), Seq(255.0))
      assertEquals(pixel(m, 5, 5), Seq(0.0))

  test("a closed polyline draws its closing edge; an open one does not"):
    val triangle = Seq(Point(20, 20), Point(80, 20), Point(50, 70))
    canvas(): closed =>
      canvas(): open =>
        closed.drawPolyline(triangle, closed = true)
        open.drawPolyline(triangle, closed = false)
        assert(
          Core.countNonZero(closed) > Core.countNonZero(open),
          "the closing edge drew nothing"
        )

  test("fillPolygon fills, and drawContours renders the same outline"):
    val triangle = Seq(Point(20, 20), Point(80, 20), Point(50, 70))
    canvas(): filled =>
      canvas(): contoured =>
        filled.fillPolygon(triangle)
        contoured.drawContours(Seq(Contour(triangle)), Scalar.White, Thickness.Filled)

        assert(Core.countNonZero(filled) > 500, "fillPolygon barely drew anything")
        assertEquals(
          Core.countNonZero(contoured),
          Core.countNonZero(filled),
          "a filled contour and a filled polygon should cover the same pixels"
        )
        assertEquals(pixel(filled, 50, 30), Seq(255.0), "a point inside the triangle")
        assertEquals(pixel(filled, 5, 60), Seq(0.0), "a point outside it")

  test("drawSegments renders what houghLinesP returns"):
    canvas(): m =>
      m.drawSegments(Seq(Segment(10, 10, 90, 10), Segment(10, 20, 90, 20)))
      assertEquals(pixel(m, 50, 10), Seq(255.0))
      assertEquals(pixel(m, 50, 20), Seq(255.0))
      assertEquals(pixel(m, 50, 15), Seq(0.0))

  test("empty geometry draws nothing rather than failing"):
    canvas(): m =>
      m.drawPolyline(Seq.empty)
      m.fillPolygon(Seq.empty)
      m.drawContours(Seq(Contour(Seq.empty)))
      m.drawSegments(Seq.empty)
      assertEquals(Core.countNonZero(m), 0)

  test("a Mat with no data is a programmer error, not a CvError"):
    Managed.use(Mat()): m =>
      intercept[IllegalArgumentException](m.drawRect(Rect(0, 0, 1, 1)))
      intercept[IllegalArgumentException](m.drawLine(Point(0, 0), Point(1, 1)))
      intercept[IllegalArgumentException](m.drawText("x", Point(0, 0)))

  test("a stroke narrower than a pixel is rejected before it reaches native code"):
    intercept[IllegalArgumentException](Thickness.Stroke(0))
    intercept[IllegalArgumentException](Thickness.Stroke(-1))
    canvas(): m =>
      intercept[IllegalArgumentException](m.drawCircle(Point(5, 5), -3))

  test("Thickness carries OpenCV's own sentinel for filled"):
    assertEquals(Thickness.Filled.cvValue, org.opencv.imgproc.Imgproc.FILLED)
    assertEquals(Thickness.Default.cvValue, 1)
