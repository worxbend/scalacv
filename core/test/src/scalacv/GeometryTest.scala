package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core as cv

/** The value types are plain data, but their `toCv`/`from` conversions are the binary boundary every detector
  * result crosses — `PublicApiTest` calls them out as surface — so the round-trips get a direct assertion
  * rather than only incidental exercise. No natives: `org.opencv.core.{Point, Size, Rect, Scalar}` are pure
  * Java data classes, so this suite needs no `OpenCv.load()`.
  *
  * `Face.clippedBox` is exercised here too. It lives on `Face` because that is where an unclipped box is
  * produced, but what it computes is `Rect` arithmetic, and testing it needs neither a native Mat nor the
  * downloaded YuNet model — so it belongs with the rectangles rather than in `FaceDetectTest`.
  */
class GeometryTest extends munit.FunSuite:

  test("Point round-trips through org.opencv.core.Point"):
    val p = Point(3.5, -2.25)
    val back = Point.from(p.toCv)
    assertEquals(back, p)
    assertEquals(p.toCv.x, 3.5)
    assertEquals(p.toCv.y, -2.25)

  test("Point3 round-trips"):
    val p = Point3(1.0, 2.0, 3.0)
    assertEquals(Point3.from(p.toCv), p)

  test("Size round-trips and rejects a negative extent"):
    val s = Size(640, 480)
    assertEquals(Size.from(s.toCv), s)
    val e = intercept[IllegalArgumentException](Size(-1, 10))
    assert(e.getMessage.contains("negative"), e.getMessage)

  test("Size rejects non-finite extents while retaining fractional geometry"):
    for invalid <- Seq(Double.PositiveInfinity, Double.NaN) do
      intercept[IllegalArgumentException](Size(invalid, 10))
      intercept[IllegalArgumentException](Size(10, invalid))
    val fractional = Size(1.25, 2.75)
    assertEquals(Size.from(fractional.toCv), fractional)
    assertEquals(Size(0, 0), Size.from(Size(0, 0).toCv))

  test("Rect round-trips, computes area, and rejects a negative extent"):
    val r = Rect(10, 20, 40, 30)
    assertEquals(Rect.from(r.toCv), r)
    assertEquals(r.area, 40L * 30)
    assertEquals(r.topLeft, Point(10, 20))
    assertEquals(r.bottomRight, Point(50, 50))

  test("Point algebra: +, -, and scale are component-wise and mutually consistent"):
    val a = Point(3.0, -2.0)
    val b = Point(1.5, 4.0)
    assertEquals(a + b, Point(4.5, 2.0))
    assertEquals(a - b, Point(1.5, -6.0))
    assertEquals(b.scale(2.0), Point(3.0, 8.0))
    assertEquals(a - b + b, a)
    assertEqualsDouble((a - b).distanceTo(Point(0, 0)), a.distanceTo(b), 1e-12)

  test("Rect.contains is half-open: left/top edges in, right/bottom edges out"):
    val r = Rect(10, 20, 40, 30)
    assert(r.contains(Point(10, 20)), "the top-left corner is enclosed")
    assert(r.contains(Point(49.999, 49.999)))
    assert(!r.contains(Point(50, 25)), "the right edge is one past the last enclosed pixel")
    assert(!r.contains(Point(25, 50)), "the bottom edge is one past the last enclosed pixel")
    assert(!r.contains(Point(9.999, 25)))
    assert(!r.contains(Point(25, 100)))
    assert(!Rect(0, 0, 0, 0).contains(Point(0, 0)), "an empty rect contains nothing")

  test("Rect.intersect computes the overlap"):
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(5, 5, 10, 10)), Some(Rect(5, 5, 5, 5)))
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(2, 3, 4, 5)), Some(Rect(2, 3, 4, 5)))
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(0, 0, 10, 10)), Some(Rect(0, 0, 10, 10)))
    // Negative origins intersect too: a ROI hanging off the top-left of an image.
    assertEquals(Rect(-5, -5, 10, 10).intersect(Rect(0, 0, 8, 8)), Some(Rect(0, 0, 5, 5)))

  test("Rect.intersect is None when disjoint, including when the rects only touch"):
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(20, 0, 5, 5)), None)
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(0, 20, 5, 5)), None)
    // Abutting edges share no pixel under the half-open convention; a zero-extent "overlap" would be a
    // rectangle Mat.submat throws on, so it is None rather than Some(Rect(..., 0, 10)).
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(10, 0, 5, 5)), None)
    assertEquals(Rect(0, 0, 10, 10).intersect(Rect(0, 10, 5, 5)), None)

  test("Rect.intersect does not overflow on edges past Int.MaxValue"):
    // In Int arithmetic `100 + Int.MaxValue` wraps to a negative right edge and the rects look disjoint;
    // in the widened arithmetic both overlap exactly in columns 100..199.
    assertEquals(
      Rect(100, 0, Int.MaxValue, 10).intersect(Rect(0, 0, 200, 10)),
      Some(Rect(100, 0, 100, 10))
    )

  test("Rect.area does not overflow on a large full-frame rectangle"):
    // width * height as Int wraps negative past a ~46340 side; as Long it is exact.
    val big = Rect(0, 0, 50_000, 50_000)
    assertEquals(big.area, 2_500_000_000L)
    val e = intercept[IllegalArgumentException](Rect(0, 0, -5, 5))
    assert(e.getMessage.contains("negative"), e.getMessage)

  test("Scalar round-trips all four channels, defaulting the unset ones to zero"):
    val full = Scalar(1, 2, 3, 4)
    assertEquals(Scalar.from(full.toCv), full)
    assertEquals(Scalar(7).toCv.`val`.toSeq, Seq(7.0, 0.0, 0.0, 0.0))

  test("Scalar's named colours are BGR-ordered, matching OpenCV's default Mat layout"):
    // Red is (0, 0, 255) not (255, 0, 0): the last channel is R because OpenCV Mats are BGR.
    assertEquals(Scalar.Red, Scalar(0, 0, 255))
    assertEquals(Scalar.Blue, Scalar(255, 0, 0))
    assertEquals(cv.Scalar(Scalar.Green.toCv.`val`).`val`(1), 255.0)

  /** A face whose landmarks are irrelevant — `Face` insists on exactly five, and the clip reads none of them.
    */
  private def faceAt(box: Rect): Face = Face(box, Seq.fill(5)(Point(0, 0)), 0.9f)

  // `Face.clippedBox` is the library's only rectangle clip, and what it computes is pure `Rect` geometry, so
  // its cases sit here with the rest of the `Rect` assertions rather than in `FaceDetectTest`: they want
  // neither the natives nor the downloaded YuNet model, and the half-open case below is exactly the
  // off-by-one this file exists to pin.

  test("Face.clippedBox trims a box that hangs off each of the four edges"):
    assertEquals(faceAt(Rect(-7, 12, 96, 96)).clippedBox(640, 480), Some(Rect(0, 12, 89, 96)))
    assertEquals(faceAt(Rect(12, -7, 96, 96)).clippedBox(640, 480), Some(Rect(12, 0, 96, 89)))
    assertEquals(faceAt(Rect(600, 12, 96, 96)).clippedBox(640, 480), Some(Rect(600, 12, 40, 96)))
    assertEquals(faceAt(Rect(12, 400, 96, 96)).clippedBox(640, 480), Some(Rect(12, 400, 96, 80)))

  test("Face.clippedBox leaves a box that already fits alone, and clamps one that swallows the frame"):
    val inside = Rect(10, 20, 40, 30)
    assertEquals(faceAt(inside).clippedBox(640, 480), Some(inside))
    assertEquals(faceAt(Rect(0, 0, 640, 480)).clippedBox(640, 480), Some(Rect(0, 0, 640, 480)))
    assertEquals(faceAt(Rect(-100, -100, 1000, 1000)).clippedBox(640, 480), Some(Rect(0, 0, 640, 480)))

  test("Face.clippedBox is half-open, so a box that only touches an edge does not overlap"):
    // A 10-wide frame encloses columns 0..9, so a box starting at x = 10 shares no pixel with it. An
    // inclusive comparison would answer Some(Rect(10, 0, 0, 10)) — which `Rect` accepts and `Mat.submat`
    // then throws on, which is why an empty overlap has to be None rather than a zero-extent rectangle.
    assertEquals(faceAt(Rect(10, 0, 10, 10)).clippedBox(10, 10), None)
    assertEquals(faceAt(Rect(0, 10, 10, 10)).clippedBox(10, 10), None)
    assertEquals(faceAt(Rect(-10, 0, 10, 10)).clippedBox(10, 10), None)
    assertEquals(faceAt(Rect(-50, -50, 10, 10)).clippedBox(640, 480), None)

  test("Face.clippedBox always answers a rectangle Image.crop accepts"):
    val (fw, fh) = (640, 480)
    val offFrame = Seq(
      Rect(-7, 12, 96, 96),
      Rect(600, 470, 96, 96),
      Rect(-100, -100, 1000, 1000),
      Rect(0, 0, 640, 480)
    )
    offFrame.foreach: box =>
      faceAt(box)
        .clippedBox(fw, fh)
        .foreach: r =>
          // Image.crop's precondition, restated here so it can be checked without allocating an image.
          assert(
            r.x >= 0 && r.y >= 0 && r.x + r.width <= fw && r.y + r.height <= fh,
            s"$r is not inside ${fw}x$fh"
          )
          assert(r.width > 0 && r.height > 0, s"$r is empty, and submat throws on a zero-extent ROI")

  test("Face.clippedBox does not wrap on a box whose right edge overflows Int"):
    // The decode rounds the model's floats into Ints, so a degenerate detection can land Int.MaxValue in x
    // or width. In Int arithmetic `x + width` wraps negative, and a box far off the right edge then looks
    // like it overlaps the frame at column 0.
    assertEquals(faceAt(Rect(Int.MaxValue - 10, 0, 96, 96)).clippedBox(640, 480), None)
    assertEquals(
      faceAt(Rect(100, 100, Int.MaxValue, Int.MaxValue)).clippedBox(640, 480),
      Some(Rect(100, 100, 540, 380))
    )

  test("Face.clippedBox rejects a frame with a negative extent"):
    val e = intercept[IllegalArgumentException](faceAt(Rect(0, 0, 10, 10)).clippedBox(-1, 10))
    assert(e.getMessage.contains("negative"), e.getMessage)
