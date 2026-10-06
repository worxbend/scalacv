package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import scala.util.Using

import org.opencv.core.{CvType, Mat, Scalar as CvScalar}
import org.opencv.core.Point as CvPoint
import org.opencv.imgproc.Imgproc

/** Fixtures are drawn, not loaded. There is no test image in this repo by design, and a synthetic one is also
  * the only way to assert an *exact* bounding rect.
  */
class ContoursTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val canvasSize = (120, 200) // rows x cols

  /** A black single-channel canvas. `CV_8UC1` because findContours accepts nothing wider. */
  private def blackCanvas(): Mat =
    Mat.zeros(canvasSize._1, canvasSize._2, CvType.CV_8UC1)

  /** Fills `rect` with `value`. Drawn corner-to-corner inclusive, so the shape really is w x h pixels. */
  private def fill(m: Mat, rect: Rect, value: Double = 255): Unit =
    Imgproc.rectangle(
      m,
      CvPoint(rect.x.toDouble, rect.y.toDouble),
      CvPoint((rect.x + rect.width - 1).toDouble, (rect.y + rect.height - 1).toDouble),
      CvScalar(value),
      -1 // FILLED
    )

  private val boxA = Rect(10, 10, 40, 30)
  private val boxB = Rect(100, 60, 50, 20)

  /** OpenCV's contour order is a scan-order artefact, not a promise; sort so assertions are stable. */
  private def sorted(cs: Seq[Contour]): Seq[Contour] = cs.sortBy(_.boundingRect.x)

  test("two filled rectangles yield exactly two contours with the right bounding rects"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)
      fill(canvas.get, boxB)

      val contours = sorted(canvas.get.findContours())
      assertEquals(contours.size, 2)
      assertEquals(contours.head.boundingRect, boxA)
      assertEquals(contours(1).boundingRect, boxB)

  test("area and perimeter match the shoelace values for a known rectangle"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)

      val c = canvas.get.findContours().head
      // contourArea measures between the centres of the boundary pixels, so a 40x30 block of
      // pixels encloses 39 x 29 — this is OpenCV's definition, not an off-by-one.
      assertEqualsDouble(c.area, 39.0 * 29.0, 0.001)
      assertEqualsDouble(c.perimeter, 2 * (39.0 + 29.0), 0.001)

  test("CHAIN_APPROX_SIMPLE collapses an axis-aligned rectangle to its four corners"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)

      val simple = canvas.get.findContours().head
      assertEquals(simple.points.size, 4)

      val full = canvas.get.findContours(approximation = ContourApproximation.None).head
      assertEquals(full.points.size, 2 * (40 + 30) - 4) // the whole pixel chain, corners once each

  test("a uniform image has no contours"):
    Using.resource(Managed(blackCanvas())): canvas =>
      assertEquals(canvas.get.findContours(), Seq.empty[Contour])

  test("External returns only the outer outline; Tree also returns the hole"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)
      fill(canvas.get, Rect(boxA.x + 10, boxA.y + 10, 10, 10), value = 0) // punch a hole

      assertEquals(canvas.get.findContours(ContourRetrieval.External).size, 1)
      assertEquals(canvas.get.findContours(ContourRetrieval.Tree).size, 2)

  test("contours outlive the Mat they came from"):
    val canvas = Managed(blackCanvas())
    fill(canvas.get, boxA)
    val contours = canvas.get.findContours()
    canvas.release()

    // If Contour held a native handle rather than copied data, this would segfault.
    assertEquals(contours.head.boundingRect, boxA)
    assertEqualsDouble(contours.head.area, 39.0 * 29.0, 0.001)

  test("an empty Mat is a programmer error, not a native crash"):
    Using.resource(Managed(Mat())): m =>
      val e = intercept[IllegalArgumentException](m.get.findContours())
      assert(e.getMessage.contains("non-empty"), e.getMessage)
      // The shared Preconditions check names the operation — pin that, since the message is the
      // only thing a caller has to grep for. (require prefixes it with "requirement failed: ".)
      assert(e.getMessage.contains("findContours"), e.getMessage)

  test("a multi-channel input surfaces as a named CvError rather than a raw CvException"):
    Using.resource(Managed(Mat.zeros(16, 16, CvType.CV_8UC3))): m =>
      val e = intercept[CvError.NativeCall](m.get.findContours())
      assertEquals(e.operation, "findContours")

  test("a hand-built empty Contour measures zero without touching native code"):
    val c = Contour(Seq.empty)
    assert(c.isEmpty)
    assertEquals(c.boundingRect, Rect(0, 0, 0, 0))
    assertEqualsDouble(c.area, 0.0, 0.0)
    assertEqualsDouble(c.perimeter, 0.0, 0.0)
    assertEquals(c.centroid, None)
    assert(c.convexHull.isEmpty)
    assert(c.approx(1.0).isEmpty)

  test("a Contour built from a mutable buffer is insulated from later edits to the buffer"):
    // Construction copies the points into an immutable Vector: without the copy the lazy metrics would
    // observe whatever the caller's buffer holds at measurement time, not at construction time.
    val buf = scala.collection.mutable.ArrayBuffer(Point(0, 0), Point(4, 0), Point(4, 3))
    val c = Contour(buf.toSeq)
    val before = c.points
    buf(0) = Point(-50, -50)
    buf += Point(100, 100)
    assertEquals(c.points, before)
    assertEquals(c.points, Seq(Point(0, 0), Point(4, 0), Point(4, 3)))
    assertEqualsDouble(c.area, 6.0, 0.001) // the original 3-4-5-right-triangle half, not the mutated buffer

  test("centroid of a rectangle is its geometric centre"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA) // corners at pixel centres (10,10)..(49,39)
      val c = canvas.get.findContours().head
      val Some(centre) = c.centroid: @unchecked
      assertEqualsDouble(centre.x, (10 + 49) / 2.0, 0.5)
      assertEqualsDouble(centre.y, (10 + 39) / 2.0, 0.5)

  test("centroid is None for a degenerate (collinear) contour where m00 is zero"):
    // Three collinear points enclose no area, so the moment m00 is 0 and the centroid is undefined.
    val line = Contour(Seq(Point(0, 0), Point(5, 0), Point(10, 0)))
    assertEquals(line.centroid, None)

  test("convex hull of a rectangle is its four corners and encloses the original"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)
      val hull = canvas.get.findContours().head.convexHull
      assertEquals(hull.points.size, 4)
      assertEquals(hull.boundingRect, boxA)

  test("convex hull of a concave (L-shaped) contour drops the reflex vertex"):
    // An L has six corners, one of them the reflex vertex (10,10) that pokes inward. The hull drops exactly
    // that vertex and fills the notch back in, so it has fewer points and a larger area.
    val l = Contour(Seq(Point(0, 0), Point(40, 0), Point(40, 10), Point(10, 10), Point(10, 40), Point(0, 40)))
    val hull = l.convexHull
    assert(!hull.points.contains(Point(10, 10)), s"the reflex vertex should be dropped, got ${hull.points}")
    assert(
      hull.points.size < l.points.size,
      s"hull ${hull.points.size} should be smaller than the L's ${l.points.size}"
    )
    assert(hull.area > l.area, s"hull area ${hull.area} should exceed L area ${l.area}")

  test("approx simplifies a full pixel chain back to four corners"):
    Using.resource(Managed(blackCanvas())): canvas =>
      fill(canvas.get, boxA)
      val full = canvas.get.findContours(approximation = ContourApproximation.None).head
      assert(full.points.size > 4, s"expected the full chain, got ${full.points.size}")
      assertEquals(full.approx(epsilon = 2.0).points.size, 4)
      intercept[IllegalArgumentException](full.approx(epsilon = -1))
