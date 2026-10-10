package scalacv

import scalacv.graphs.*
import java.util.concurrent.TimeUnit
import org.opencv.core.Core

class GraphicsReviewGeometryTest extends munit.FunSuite:
  private class Recorder extends Renderer:
    val commands = scala.collection.mutable.ArrayBuffer.empty[(RenderPrimitive, PictureStyle)]
    val textMeasurer: TextMeasurer = new TextMeasurer:
      def measure(text: String, font: Font, scale: Double): TextMetrics =
        TextMetrics(Size(text.length * 8 * scale, 10 * scale), 2)
    protected def draw(p: RenderPrimitive, s: PictureStyle): Unit =
      commands += ((p, s))
      ()

  test("labels resolve background before glyphs"):
    val r = new Recorder
    r.layout.label("hi", Point(0, 0)).renderWith(r)
    assert(r.commands.head._1.isInstanceOf[RenderPrimitive.Path])
    assert(r.commands.last._1.isInstanceOf[RenderPrimitive.Text])

  test("large groups and arbitrary deep transforms/styles are stack safe with painter order intact"):
    val r = new Recorder
    val group = Picture.all((0 until 20000).map(i => Picture.circle(Point(i, 0), 1)))
    group.renderWith(r)
    assertEquals(r.commands.size, 20000)
    assertEquals(r.commands.head._1, RenderPrimitive.Circle(Point(0, 0), 1))
    assertEquals(r.commands.last._1, RenderPrimitive.Circle(Point(19999, 0), 1))
    assertEquals(group.bounds, Some(Bounds(-1, -1, 20000, 1)))
    val deep = (0 until 20000).foldLeft(Picture.circle(Point(0, 0), 1).strokeColor(Color.Red)) { (p, _) =>
      p.translate(1, 0).strokeColor(Color.Blue).on(Picture.empty)
    }
    r.commands.clear()
    deep.renderWith(r)
    assertEquals(r.commands.head._1, RenderPrimitive.Circle(Point(20000, 0), 1))
    assertEquals(r.commands.head._2.strokeColor, Some(Color.Red))
    assertEquals(deep.bounds, Some(Bounds(19999, -1, 20001, 1)))

  test("dense and tiny charts stay geometrically inside their requested box"):
    val charts = Seq(
      Chart.bars(Seq.fill(10)(1.0), 20, 1, gap = Int.MaxValue),
      Chart.line(Seq(0.0, 1.0), 20, 1),
      Chart.area(Seq(0.0, 1.0), 20, 1),
      Chart.scatter(Seq((-Double.MaxValue, 0.0), (Double.MaxValue, 1.0)), 20, 1),
      Chart.pie(Seq(Double.MaxValue, Double.MaxValue), 20, 1)
    )
    charts.foreach { p =>
      val b = p.bounds.get
      assert(b.minX >= 0 && b.maxX <= 20 && b.minY >= 0 && b.maxY <= 1, b.toString)
    }
    intercept[IllegalArgumentException](Chart.bars(Seq.fill(21)(1.0), 20, 20))
    intercept[IllegalArgumentException](Chart.bars(Seq(1.0), 20, 20, gap = -1))

  test("invalid radii sampling and nonfinite chart values fail at construction"):
    val p = Point(0, 0)
    for r <- Seq(-1.0, Double.NaN, Double.PositiveInfinity) do
      intercept[IllegalArgumentException](Picture.circle(p, r))
      intercept[IllegalArgumentException](Picture.ellipse(p, r, 1))
      intercept[IllegalArgumentException](Picture.roundedRectangle(Rect(0, 0, 4, 4), r))
      intercept[IllegalArgumentException](Chart.scatter(Seq.empty, 20, 20, radius = r))
    for n <- Seq(0, -1) do
      intercept[IllegalArgumentException](Picture.curve(p, p, p, p, segments = n))
      intercept[IllegalArgumentException](Picture.quadraticCurve(p, p, p, segments = n))
      intercept[IllegalArgumentException](Picture.ellipse(p, 1, 1, segments = n))
      intercept[IllegalArgumentException](Picture.arc(p, 1, 1, 0, 90, segments = n))
    intercept[IllegalArgumentException](Chart.line(Seq(Double.NaN), 20, 20))
    intercept[IllegalArgumentException](Chart.pie(Seq(Double.PositiveInfinity), 20, 20))
    intercept[IllegalArgumentException](Picture.line(p, Point(Double.NaN, 0)))

class GraphicsReviewRasterTest extends munit.FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  test("labels contain visible white glyphs on their background"):
    val img = Picture.label("HELLO", Point(4, 4), Color.White, Color.Blue).render(120, 50)
    try
      assert((for y <- 0 until 50; x <- 0 until 120 yield img.mat.get(y, x).forall(_ > 240)).contains(true))
      assert(img.mat.get(5, 5)(0) > 120)
    finally img.close()

  test("dash phase is invariant to straight edge tessellation including closing edges"):
    val sparse = Seq(Point(10, 10), Point(90, 10), Point(90, 50), Point(10, 50))
    val dense = sparse.zip(sparse.tail :+ sparse.head).flatMap { (a, b) =>
      (0 until 10).map(i => Point(a.x + (b.x - a.x) * i / 10, a.y + (b.y - a.y) * i / 10))
    }
    val a = Picture.polygon(sparse).strokeDash(Dash(7, 9)).smooth(false).render(100, 60)
    val b = Picture.polygon(dense).strokeDash(Dash(7, 9)).smooth(false).render(100, 60)
    try assertEquals(Core.norm(a.mat, b.mat, Core.NORM_INF), 0.0)
    finally
      a.close()
      b.close()

  test("clipping skips invisible work without resetting path phase"):
    val img = Picture
      .polyline(Seq(Point(-1000, 10), Point(-995, 10), Point(40, 10)))
      .strokeDash(Dash(6, 6))
      .smooth(false)
      .render(32, 24)
    try
      // Global path distance 1000 has phase four; x=0 is on, x=5 is off, x=10 is on.
      assert(img.mat.get(10, 0)(0) > 200)
      assertEquals(img.mat.get(10, 5)(0), 0.0)
      assert(img.mat.get(10, 10)(0) > 200)
    finally img.close()

  for distance <- Seq(1e18, 1e100); reverse <- Seq(false, true) do
    test(s"unresolvable intersecting dash coordinates fail explicitly: $distance reverse=$reverse"):
      val ends = Seq(Point(-distance, 10), Point(40, 10))
      val points = if reverse then ends.reverse else ends
      val error = intercept[IllegalArgumentException]:
        val img = Picture.polyline(points).strokeDash(Dash(6, 6)).smooth(false).render(32, 24)
        img.close()
      assert(error.getMessage.contains("floating-point resolution"))

  test("supported distant dash coordinates preserve phase in both directions"):
    for reverse <- Seq(false, true) do
      val ends = Seq(Point(-1e12, 10), Point(40, 10))
      val reference = Seq(Point(-1000, 10), Point(40, 10))
      def render(points: Seq[Point]): Image =
        Picture
          .polyline(if reverse then points.reverse else points)
          .strokeDash(Dash(6, 6))
          .smooth(false)
          .render(32, 24)
      val img = render(ends)
      val expected = render(reference)
      try
        assert(Core.sumElems(img.mat).`val`(0) > 0)
        assertEquals(Core.norm(img.mat, expected.mat, Core.NORM_INF), 0.0)
      finally
        img.close()
        expected.close()

  test("entirely offscreen distant dashed edges remain harmless"):
    for distance <- Seq(1e18, 1e100); reverse <- Seq(false, true) do
      for ends <- Seq(
          Seq(Point(-distance, -10), Point(40, -10)),
          Seq(Point(-distance, 10), Point(-distance / 2, 10))
        )
      do
        val points = if reverse then ends.reverse else ends
        val img = Picture.polyline(points).strokeDash(Dash(6, 6)).smooth(false).render(32, 24)
        try assertEquals(Core.sumElems(img.mat).`val`(0), 0.0)
        finally img.close()

  test("tessellated curves have real dash gaps"):
    val p = Picture.ellipse(Point(60, 60), 40, 30, segments = 256).smooth(false)
    val solid = p.render(120, 120)
    val dashed = p.strokeDash(Dash(8, 12)).render(120, 120)
    try
      val s = Core.sumElems(solid.mat).`val`(0)
      val d = Core.sumElems(dashed.mat).`val`(0)
      assert(d > 0 && d < s * 0.8)
    finally
      solid.close()
      dashed.close()

  test("overflowing dash periods and distant off-canvas edges finish in a bounded subprocess"):
    // Include the isolated test loader's URLs as well as the launcher classpath (Mill uses both).
    def urls(loader: ClassLoader): List[String] =
      if loader == null then Nil
      else
        val own = loader match
          case u: java.net.URLClassLoader =>
            u.getURLs.toList.map(u => java.nio.file.Path.of(u.toURI).toString)
          case _ => Nil
        own ::: urls(loader.getParent)
    val cp = (System.getProperty("java.class.path").split(java.io.File.pathSeparator).toList :::
      urls(getClass.getClassLoader)).distinct.mkString(java.io.File.pathSeparator)
    val javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString
    val process =
      new ProcessBuilder(javaBin, "-cp", cp, "scalacv.GraphicsDashTimeoutProbe").inheritIO().start()
    try
      assert(process.waitFor(15, TimeUnit.SECONDS), "dash rendering timed out")
      assertEquals(process.exitValue(), 0)
    finally
      if process.isAlive then
        process.destroyForcibly()
        process.waitFor()

object GraphicsDashTimeoutProbe:
  def main(args: Array[String]): Unit =
    OpenCv.load()
    for dash <- Seq(Dash(Int.MaxValue, 1), Dash(Int.MaxValue, Int.MaxValue), Dash(1, 1)) do
      val p = Picture.polyline(Seq(Point(-1e12, 10), Point(10, 10), Point(20, 10)))
      val img = p.strokeDash(dash).smooth(false).render(32, 32)
      try assert(img.width == 32)
      finally img.close()
    val short = Picture.line(Point(0, 10), Point(20, 10)).strokeDash(Dash(Int.MaxValue, 1)).render(32, 32)
    try assert(short.mat.get(10, 10)(0) > 200)
    finally short.close()
