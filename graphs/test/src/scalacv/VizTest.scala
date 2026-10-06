package scalacv

import scalacv.graphs.*

import java.nio.file.Files

/** Data-visualisation charts and animation, on top of the Picture layer. */
class VizTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def paintedPixels(img: Image, channel: Int, min: Double): Int =
    var count = 0
    var y = 0
    while y < img.height do
      var x = 0
      while x < img.width do
        if img.mat.get(y, x)(channel) > min then count += 1
        x += 1
      y += 1
    count

  test("a bar chart paints bars"):
    val img = Chart.bars(Seq(1.0, 3.0, 2.0, 5.0), 120, 80, Color.Blue).render(120, 80, Color.Black)
    try assert(paintedPixels(img, channel = 0, min = 100) > 50, "the bars should paint blue pixels")
    finally img.close()

  test("a line chart paints a connected line"):
    val img = Chart
      .line(Seq(1.0, 4.0, 2.0, 5.0, 3.0), 160, 80, Color.Green, strokeWidth = 2)
      .render(160, 80, Color.Black)
    try assert(paintedPixels(img, channel = 1, min = 100) > 30, "the line should paint green pixels")
    finally img.close()

  test("a scatter plot paints markers"):
    val data = Seq((0.0, 0.0), (1.0, 2.0), (2.0, 1.0), (3.0, 3.0))
    val img = Chart.scatter(data, 100, 100, Color.Red, radius = 4).render(100, 100, Color.Black)
    try assert(paintedPixels(img, channel = 2, min = 100) > 20, "the scatter should paint red markers")
    finally img.close()

  test("empty data yields the empty picture"):
    val img = Chart.bars(Seq.empty, 100, 100).render(100, 100, Color.Black)
    try assertEquals(paintedPixels(img, channel = 0, min = 100), 0)
    finally img.close()

  test("a non-positive chart box is a precondition violation, not a degenerate picture"):
    intercept[IllegalArgumentException](Chart.bars(Seq(1.0, 2.0), 0, 80))
    intercept[IllegalArgumentException](Chart.line(Seq(1.0, 2.0), 100, -5))
    intercept[IllegalArgumentException](Chart.scatter(Seq((1.0, 2.0)), -1, 100))
    intercept[IllegalArgumentException](Chart.area(Seq(1.0, 2.0), 100, 0))
    intercept[IllegalArgumentException](Chart.pie(Seq(1.0, 2.0), 100, -1))
    intercept[IllegalArgumentException](Chart.histogram(Seq(1.0, 2.0), bins = 4, width = 0, height = 50))

  test("animation records a frame per step to a video"):
    val out = Files.createTempFile("scalacv-anim-", ".avi")
    try
      val written =
        Animation.record(out.toString, frames = 5, width = 64, height = 48, fps = 10, codec = Codec.Mjpg) { i =>
          Picture.circle(Point(32, 24), 5 + i.toDouble).fillColor(Color.White).noStroke
        }
      assertEquals(written, Right(5L))
      assert(Files.size(out) > 0, "the animation file should not be empty")
    finally Files.deleteIfExists(out)

  test("animation frames yields owned images"):
    val images =
      Animation.frames(3, 40, 40)(i => Picture.marker(Point(20, 20), Color.White, radius = 2 + i)).toList
    try assertEquals(images.size, 3)
    finally images.foreach(_.close())
