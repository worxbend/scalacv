package scalacv

import scalacv.graphs.*

/** These contracts deliberately never load OpenCV or allocate an Image. */
class RendererContractTest extends munit.FunSuite:

  override def beforeAll(): Unit = assert(!OpenCv.isLoaded)
  override def afterAll(): Unit = assert(!OpenCv.isLoaded)

  private class RecordingRenderer extends Renderer:
    val textMeasurer: TextMeasurer = new TextMeasurer:
      def measure(text: String, font: Font, scale: Double): TextMetrics =
        TextMetrics(Size(text.length * 10 * scale, 8 * scale), 2)

    val commands = scala.collection.mutable.ArrayBuffer.empty[(RenderPrimitive, PictureStyle)]

    protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit =
      commands += ((primitive, style))
      ()

  test("an independent renderer receives ordered, transformed primitives and inherited styles"):
    val renderer = new RecordingRenderer
    val picture = Picture
      .circle(Point(2, 3), 4)
      .fillColor(Color.Red)
      .noStroke
      .on(Picture.line(Point(0, 0), Point(4, 0)).strokeColor(Color.Green))
      .strokeWidth(3)
      .scale(2)
      .translate(10, 20)

    picture.renderWith(renderer)

    assertEquals(
      renderer.commands.map(_._1).toList,
      List(
        RenderPrimitive.Path(Seq(Point(10, 20), Point(18, 20)), closed = false),
        RenderPrimitive.Circle(Point(14, 26), 8)
      )
    )
    assertEquals(renderer.commands.head._2.strokeColor, Some(Color.Green))
    assertEquals(renderer.commands.head._2.strokeWidth, 3)
    assertEquals(renderer.commands.last._2.fill, Some(Color.Red))
    assertEquals(renderer.commands.last._2.strokeColor, None)
    assertEquals(renderer.commands.last._2.strokeWidth, 3)

  test("styled scenes are immutable values that replay identical resolved commands"):
    def scene = Picture
      .line(Point(1, 2), Point(5, 2))
      .stroke(Color.Red, 3)
      .strokeDash(Dash(4, 2))
      .smooth(false)
      .on(Picture.circle(Point(8, 9), 2).noFill.solidStroke)
      .fillColor(Color.Blue)
      .dashed
    val first = scene
    val second = scene
    assertEquals(first, second)
    val a = new RecordingRenderer
    val b = new RecordingRenderer
    first.renderWith(a)
    second.renderWith(b)
    assertEquals(a.commands.toList, b.commands.toList)
    assertEquals(a.commands.head._2.fill, None)
    assertEquals(a.commands.head._2.dash, None)
    assertEquals(a.commands.last._2.strokeColor, Some(Color.Red))
    assertEquals(a.commands.last._2.dash, Some(Dash(4, 2)))
    assertEquals(a.commands.last._2.antialias, false)

  test("text bounds use the renderer's metrics at the transformed anchor and scale"):
    val renderer = new RecordingRenderer
    val picture = Picture
      .text("ab", Point(10, 20))
      .font(Font.Duplex)
      .fontScale(1.5)
      .scale(2)
      .rotate(90)
      .translate(100, 0)
    val bounds = renderer.layout.bounds(picture).get
    assertEqualsDouble(bounds.minX, 60, 1e-9)
    assertEqualsDouble(bounds.minY, -4, 1e-9)
    assertEqualsDouble(bounds.maxX, 120, 1e-9)
    assertEqualsDouble(bounds.maxY, 22, 1e-9)
    picture.renderWith(renderer)
    val (RenderPrimitive.Text(text, at), style) = renderer.commands.head: @unchecked
    assertEquals(text, "ab")
    assertEqualsDouble(at.x, 60, 1e-9)
    assertEqualsDouble(at.y, 20, 1e-9)
    assertEquals(style.font, Font.Duplex)
    assertEqualsDouble(style.fontScale, 3, 1e-9)

  test("layout combinators use one explicitly supplied measurer, including labels and grids"):
    val layout = new RecordingRenderer().layout
    val square = Picture.rectangle(Rect(0, 0, 20, 10))
    val text = Picture.text("ab", Point(0, 0))
    assertEquals(layout.bounds(layout.beside(square, text, gap = 5)), Some(Bounds(0, 0, 35, 10)))
    assertEquals(layout.bounds(layout.above(square, text, gap = 5)), Some(Bounds(0, 0, 20, 21)))
    assertEquals(
      layout.bounds(layout.grid(Seq(text, square), columns = 2, gap = 3)),
      Some(Bounds(0, 0, 43, 10))
    )
    val label = layout.label("ab", Point(4, 5), padding = 3, fontScale = 1)
    assertEquals(layout.bounds(label), Some(Bounds(4, 5, 30, 21)))

  test("rotated rectangle bounds enclose every rendered corner"):
    val renderer = new RecordingRenderer
    val picture = Picture.rectangle(Rect(0, 0, 20, 10)).rotate(45)
    picture.renderWith(renderer)
    val (RenderPrimitive.Path(corners, true), _) = renderer.commands.head: @unchecked
    val bounds = renderer.layout.bounds(picture).get
    assertEqualsDouble(bounds.minX, corners.map(_.x).min, 1e-9)
    assertEqualsDouble(bounds.minY, corners.map(_.y).min, 1e-9)
    assertEqualsDouble(bounds.maxX, corners.map(_.x).max, 1e-9)
    assertEqualsDouble(bounds.maxY, corners.map(_.y).max, 1e-9)

  test("rectangle geometry does not wrap at Int.MaxValue"):
    val rect = Rect(Int.MaxValue - 2, Int.MaxValue - 2, 10, 20)
    for picture <- Seq(Picture.rectangle(rect), Picture.roundedRectangle(rect, radius = 2)) do
      val renderer = new RecordingRenderer
      picture.renderWith(renderer)
      val (RenderPrimitive.Path(corners, true), _) = renderer.commands.head: @unchecked
      val bounds = renderer.layout.bounds(picture).get
      assertEqualsDouble(bounds.minX, rect.x.toDouble, 1e-6)
      assertEqualsDouble(bounds.minY, rect.y.toDouble, 1e-6)
      assertEqualsDouble(bounds.maxX, rect.bottomRight.x, 1e-6)
      assertEqualsDouble(bounds.maxY, rect.bottomRight.y, 1e-6)
      assertEqualsDouble(corners.map(_.x).max, rect.bottomRight.x, 1e-6)
      assertEqualsDouble(corners.map(_.y).max, rect.bottomRight.y, 1e-6)

  test("shape-only bounds and layout do not need native loading or text metrics"):
    val failOnText = new TextMeasurer:
      def measure(text: String, font: Font, scale: Double): TextMetrics =
        fail("a shape asked for text metrics")
    val layout = new PictureLayout(failOnText)
    val circle = Picture.circle(Point(10, 20), 4).rotate(45, Point(10, 20)).scale(2).strokeWidth(50)
    val bounds = circle.bounds.get
    assertEqualsDouble(bounds.minX, 12, 1e-9)
    assertEqualsDouble(bounds.minY, 32, 1e-9)
    assertEqualsDouble(bounds.maxX, 28, 1e-9)
    assertEqualsDouble(bounds.maxY, 48, 1e-9)
    assertEquals(layout.bounds(circle), circle.bounds)
    assertEquals(layout.bounds(Picture.empty), None)
    assertEquals(layout.bounds(Picture.polyline(Seq.empty)), None)
    assertEquals(layout.bounds(layout.beside(Picture.empty, circle)), circle.bounds)
    assertEquals(layout.bounds(layout.above(circle, Picture.polygon(Seq.empty))), circle.bounds)
    assertEquals(layout.bounds(layout.grid(Seq.empty, columns = 2)), None)
    intercept[IllegalArgumentException](layout.grid(Seq(circle), columns = 0))

/** Native contracts run separately so the pure renderer suite cannot inherit a loaded OpenCV library. */
class OpenCvRendererContractTest extends munit.FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  test("the explicit OpenCV backend retains image consumption and the convenience pixels"):
    val picture = Picture
      .circle(Point(24, 20), 10)
      .fillColor(Color.Red.withAlpha(128))
      .noStroke
      .on(Picture.rectangle(Rect(4, 4, 40, 30)).stroke(Color.Green, 4).dashed)
      .on(Picture.text("Ag", Point(10, 38)).fontScale(0.7).strokeColor(Color.White))
    val reference = picture.render(64, 48)
    val base = Image.blank(64, 48, Scalar.Black)
    val explicit = OpenCvRenderer.renderOn(picture, base)
    try
      intercept[IllegalStateException](base.width)
      assertEquals(org.opencv.core.Core.norm(reference.mat, explicit.mat, org.opencv.core.Core.NORM_INF), 0.0)
      val fresh = OpenCvRenderer.render(picture, 64, 48)
      try
        assertEquals(org.opencv.core.Core.norm(reference.mat, fresh.mat, org.opencv.core.Core.NORM_INF), 0.0)
      finally fresh.close()
    finally
      reference.close()
      explicit.close()

  test("the OpenCV backend exposes the same one-pixel Hershey text metrics as Draw"):
    assertEquals(
      OpenCvRenderer.textMeasurer.measure("Ag", Font.Duplex, 1.5),
      Draw.textSize("Ag", Font.Duplex, 1.5)
    )
