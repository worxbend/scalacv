package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import scala.util.Using

import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** Fixtures are drawn here rather than loaded, so the geometry the assertions check is the geometry the test
  * itself put there. No bitmap image ships with this repository.
  *
  * The interesting assertions are the decoding ones. `HoughLinesP` returns `CV_32SC4` — int32 — while
  * `HoughLines` returns `CV_32FC2`; reading either with the other's element type does not fail cleanly, so a
  * test that only checked "some lines came back" would pass against a wrong decoder.
  */
class HoughTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 200
  private val Height = 200
  private val HorizontalY = 50
  private val VerticalX = 130
  private val Margin = 20

  /** A black image with one horizontal and one vertical white line, run through Canny.
    *
    * The lines are drawn 1px thin: Canny on a thick bar reports both of its sides, which would make "the
    * recovered y is 50" ambiguous by the bar's own width.
    */
  private def edges(): Mat =
    val canvas = Mat.zeros(Height, Width, CvType.CV_8UC1)
    try
      Imgproc.line(
        canvas,
        Point(Margin.toDouble, HorizontalY.toDouble).toCv,
        Point((Width - Margin).toDouble, HorizontalY.toDouble).toCv,
        Scalar.White.toCv,
        1
      )
      Imgproc.line(
        canvas,
        Point(VerticalX.toDouble, Margin.toDouble).toCv,
        Point(VerticalX.toDouble, (Height - Margin).toDouble).toCv,
        Scalar.White.toCv,
        1
      )
      val out = Mat()
      Imgproc.Canny(canvas, out, 50, 150)
      out
    finally canvas.release()

  private def blankEdges(): Mat =
    val canvas = Mat.zeros(Height, Width, CvType.CV_8UC1)
    try
      val out = Mat()
      Imgproc.Canny(canvas, out, 50, 150)
      out
    finally canvas.release()

  private def deg(theta: Double): Double = math.toDegrees(theta)

  test("houghLinesP recovers both drawn segments with approximately the right endpoints"):
    Using.resource(Managed(edges())): m =>
      val segments = m.get.houghLinesP(threshold = 40, minLineLength = 100, maxLineGap = 5)
      assert(segments.nonEmpty, "expected at least the two drawn lines")

      val horizontal = segments.filter(s => math.abs(s.y1 - s.y2) <= 2)
      val vertical = segments.filter(s => math.abs(s.x1 - s.x2) <= 2)

      assert(
        horizontal.exists: s =>
          math.abs(s.y1 - HorizontalY) <= 3 &&
            math.min(s.x1, s.x2) <= Margin + 10 &&
            math.max(s.x1, s.x2) >= Width - Margin - 10,
        s"no horizontal segment near y=$HorizontalY spanning the drawn extent; got $horizontal"
      )
      assert(
        vertical.exists: s =>
          math.abs(s.x1 - VerticalX) <= 3 &&
            math.min(s.y1, s.y2) <= Margin + 10 &&
            math.max(s.y1, s.y2) >= Height - Margin - 10,
        s"no vertical segment near x=$VerticalX spanning the drawn extent; got $vertical"
      )

  test("houghLinesP endpoints stay inside the image — the CV_32SC4 decode is not misaligned"):
    // A float-typed read of this int32 Mat yields denormals near zero or absurd magnitudes.
    // Bounds-checking every endpoint is the cheapest assertion that catches it.
    Using.resource(Managed(edges())): m =>
      val segments = m.get.houghLinesP(threshold = 40, minLineLength = 100, maxLineGap = 5)
      segments.foreach: s =>
        assert(
          s.x1 >= 0 && s.x1 < Width && s.x2 >= 0 && s.x2 < Width &&
            s.y1 >= 0 && s.y1 < Height && s.y2 >= 0 && s.y2 < Height,
          s"endpoint outside a ${Width}x$Height image: $s"
        )
        assert(s.length >= 100.0, s"minLineLength=100 was not honoured by $s")

  test("Segment exposes its endpoints as Points"):
    val s = Segment(1, 2, 3, 4)
    assertEquals(s.start, Point(1.0, 2.0))
    assertEquals(s.end, Point(3.0, 4.0))

  test("houghLines recovers both thetas — theta is the angle of the normal, not of the line"):
    Using.resource(Managed(edges())): m =>
      val lines = m.get.houghLines(threshold = 100)
      assert(lines.nonEmpty, "expected at least the two drawn lines")

      // Horizontal line y=50  -> normal points down  -> theta = 90 deg, rho =  50
      // Vertical   line x=130 -> normal points right -> theta =  0 deg, rho = 130
      assert(
        lines.exists(l => math.abs(deg(l.theta) - 90.0) <= 3.0 && math.abs(l.rho - HorizontalY) <= 3.0),
        s"no line at theta~90deg, rho~$HorizontalY; got ${lines.map(l => (l.rho, deg(l.theta)))}"
      )
      assert(
        lines.exists(l => deg(l.theta) <= 3.0 && math.abs(l.rho - VerticalX) <= 3.0),
        s"no line at theta~0deg, rho~$VerticalX; got ${lines.map(l => (l.rho, deg(l.theta)))}"
      )

  test("houghLinesWithAccumulator reports votes, ranked, and agrees with houghLines"):
    Using.resource(Managed(edges())): m =>
      val plain = m.get.houghLines(threshold = 100)
      val scored = m.get.houghLinesWithAccumulator(threshold = 100)
      assertEquals(scored.size, plain.size)
      assertEquals(scored.map(_.line), plain)
      assert(
        scored.forall(_.votes >= 100),
        s"every vote must clear the threshold; got ${scored.map(_.votes)}"
      )
      assert(
        scored.map(_.votes) == scored.map(_.votes).sortBy(-_),
        s"OpenCV returns lines strongest-first; got ${scored.map(_.votes)}"
      )

  test("an image with no edges yields an empty Seq rather than throwing"):
    // The result Mat is rows=0 with cols=1 and its element type still set, so `empty()` alone
    // is not the discriminator — this is exactly the case a naive decoder walks off the end of.
    Using.resource(Managed(blankEdges())): m =>
      assertEquals(m.get.houghLines(threshold = 50), Seq.empty[PolarLine])
      assertEquals(m.get.houghLinesP(threshold = 50), Seq.empty[Segment])
      assertEquals(m.get.houghLinesWithAccumulator(threshold = 50), Seq.empty[PolarLineWithVotes])

  test("a threshold nothing reaches yields an empty Seq, not a partial one"):
    Using.resource(Managed(edges())): m =>
      assertEquals(m.get.houghLines(threshold = 100000), Seq.empty[PolarLine])
      assertEquals(m.get.houghLinesP(threshold = 100000), Seq.empty[Segment])

  test("a non-8UC1 receiver is a precondition failure, not a native abort"):
    Using.resource(Managed(Mat.zeros(16, 16, CvType.CV_8UC3))): m =>
      val e = intercept[IllegalArgumentException](m.get.houghLinesP(threshold = 10))
      assert(e.getMessage.contains("8-bit single-channel"), e.getMessage)

  test("an empty receiver is a precondition failure"):
    Using.resource(Managed(Mat())): m =>
      intercept[IllegalArgumentException](m.get.houghLines(threshold = 10))

  test("the receiver survives — no transform releases or mutates the image it was given"):
    Using.resource(Managed(edges())): m =>
      val before = m.get.dataAddr()
      val rows = m.get.rows()
      m.get.houghLines(threshold = 100)
      m.get.houghLinesP(threshold = 40)
      m.get.houghLinesWithAccumulator(threshold = 100)
      assertEquals(m.get.dataAddr(), before, "the receiver's buffer must not have been freed or reallocated")
      assertEquals(m.get.rows(), rows)
