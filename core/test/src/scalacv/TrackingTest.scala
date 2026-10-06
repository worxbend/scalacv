package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Object tracking: IoU, the Kalman smoother, single-object CSRT tracking, and SORT-lite identity. */
class TrackingTest extends munit.ScalaCheckSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A frame with a white square whose centre is at `cx`, on black. */
  private def frame(cx: Int): Image =
    val m = Mat(200, 200, CvType.CV_8UC3, cv.Scalar(0, 0, 0))
    Imgproc.rectangle(m, cv.Point(cx - 15, 85), cv.Point(cx + 15, 115), cv.Scalar(255, 255, 255), -1)
    Image.wrap(Managed(m))

  test("IoU is 1 for identical boxes, 0 for disjoint, and partial for overlap"):
    assertEquals(ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(0, 0, 10, 10)), 1.0)
    assertEquals(ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(50, 50, 10, 10)), 0.0)
    val half = ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(5, 0, 10, 10))
    assert(half > 0.3 && half < 0.4, s"half-overlap IoU should be ~1/3, got $half")

  test("a Kalman filter learns velocity and extrapolates past the last measurement"):
    val k = Kalman.point(Point(0, 0))
    try
      var last = Point(0, 0)
      for i <- 1 to 6 do
        k.predict()
        last = k.correct(Point(i * 10.0, 0))
      val predicted = k.predict()
      assert(predicted.x > last.x, s"should extrapolate forward past ${last.x}, got ${predicted.x}")
      assert(predicted.x > 40, s"velocity should have built up, got ${predicted.x}")
      assert(math.abs(predicted.y) < 5, s"y should stay near 0, got ${predicted.y}")
    finally k.close()

  test("Kalman survives its setup releasing the filter's own internal matrices (refcount assumption)"):
    // Kalman.point wraps kf.get_transitionMatrix()/get_measurementMatrix()/... in Managed.use and
    // release()s them. That is safe ONLY because the OpenCV Java binding returns a refcount-sharing
    // header copy, so release drops the extra refcount and the filter's member survives. This pins
    // that assumption: had a release actually freed the transition matrix, a long run would diverge.
    val k = Kalman.point(Point(0, 0))
    try
      for i <- 1 to 500 do
        k.predict()
        k.correct(Point(i.toDouble, i * 0.5)) // a steady diagonal track
      val p = k.predict() // one step past the last measurement (i = 500)
      assert(math.abs(p.x - 501.0) < 15, s"x should still track the line after 500 steps, got ${p.x}")
      assert(math.abs(p.y - 250.5) < 15, s"y should still track the line after 500 steps, got ${p.y}")
    finally k.close()

  test("a CSRT tracker follows an object across frames"):
    val tracker = Tracker.create(TrackerKind.Csrt).fold(throw _, identity)
    val f0 = frame(50)
    try
      tracker.init(f0, Rect(35, 85, 30, 30))
      val f1 = frame(90)
      try
        val box = tracker.update(f1)
        assert(box.isDefined, "the tracker should still have the object")
        val centreX = box.get.x + box.get.width / 2
        assert(centreX > 65, s"the box should have followed the square rightward, centre x=$centreX")
      finally f1.close()
    finally
      tracker.close()
      f0.close()

  test("ObjectTracker keeps a stable id for each object across frames"):
    val t = ObjectTracker.create()
    try
      val a = Rect(10, 10, 20, 20)
      val b = Rect(120, 120, 20, 20)
      val first = t.update(Seq(a, b))
      assertEquals(first.map(_.id).toSet, Set(0, 1))
      assertEquals(t.count, 2)
      // Both objects shift a little; identities must carry over, no new tracks.
      val second = t.update(Seq(Rect(14, 10, 20, 20), Rect(124, 120, 20, 20)))
      assertEquals(second.map(_.id).toSet, Set(0, 1))
      assertEquals(t.count, 2, "no new objects appeared")
    finally t.close()

  test("ObjectTracker retires a lost track and counts a genuinely new one"):
    val t = ObjectTracker.create(maxAge = 0)
    try
      t.update(Seq(Rect(10, 10, 20, 20), Rect(120, 120, 20, 20))) // ids 0, 1
      assertEquals(t.count, 2)
      // Only the first object remains; the second is unseen and, with maxAge 0, retired at once.
      val kept = t.update(Seq(Rect(12, 10, 20, 20)))
      assertEquals(kept.map(_.id).toSet, Set(0))
      // A brand-new object far from anything gets a fresh id, bumping the running count.
      val withNew = t.update(Seq(Rect(12, 10, 20, 20), Rect(180, 20, 15, 15)))
      assert(withNew.exists(_.id == 2), s"a new object should get id 2, got ${withNew.map(_.id)}")
      assertEquals(t.count, 3)
    finally t.close()

  test("drawTracks annotates without changing the frame size"):
    val annotated = frame(50).drawTracks(Seq(ObjectTrack(3, Rect(35, 85, 30, 30), hits = 5, age = 5)))
    try assertEquals((annotated.width, annotated.height), (200, 200))
    finally annotated.close()

  private val genRect: Gen[Rect] =
    for
      x <- Gen.choose(-20, 20)
      y <- Gen.choose(-20, 20)
      w <- Gen.choose(0, 30)
      h <- Gen.choose(0, 30)
    yield Rect(x, y, w, h)

  /** The set of pixel cells a box covers — the brute-force oracle the closed-form IoU must agree with. */
  private def pixels(r: Rect): Set[(Int, Int)] =
    (for
      px <- r.x until r.x + r.width
      py <- r.y until r.y + r.height
    yield (px, py)).toSet

  property(
    "iou is symmetric, within [0, 1], 1 for a non-empty box against itself, and matches a pixel-count oracle"
  ):
    forAll(genRect, genRect): (a, b) =>
      val i = ObjectTracker.iou(a, b)
      val inter = (pixels(a) intersect pixels(b)).size.toDouble
      val union = (pixels(a) union pixels(b)).size.toDouble
      val expected = if union == 0 then 0.0 else inter / union
      i == ObjectTracker.iou(b, a)
      && i >= 0 && i <= 1
      && math.abs(i - expected) < 1e-12
      && (a.area == 0 || ObjectTracker.iou(a, a) == 1.0)

  test("a fresh Kalman predicts its initial point and a matching correction leaves it there"):
    val k = Kalman.point(Point(10, 20))
    try
      // Zero initial velocity, so the first prediction is the seed itself. A statePost laid out as
      // (x, 0, y, 0) instead of (x, y, vx, vy) would read back as (30, 0) here, not (10, 20).
      val p = k.predict()
      assertEqualsDouble(p.x, 10.0, 1e-4)
      assertEqualsDouble(p.y, 20.0, 1e-4)
      val c = k.correct(Point(10, 20))
      assertEqualsDouble(c.x, 10.0, 1e-4)
      assertEqualsDouble(c.y, 20.0, 1e-4)
    finally k.close()

  test(
    "a closed Kalman throws IllegalStateException instead of touching freed memory, and close is idempotent"
  ):
    val k = Kalman.point(Point(10, 20))
    k.close()
    k.close()
    intercept[IllegalStateException](k.predict()): Unit
    intercept[IllegalStateException](k.correct(Point(10, 20))): Unit

  test("ObjectTracker withholds a track until minHits and then reports the raw detection with hits and age"):
    val t = ObjectTracker.create(minHits = 2)
    try
      val a = Rect(10, 10, 20, 20)
      val a2 = Rect(12, 10, 20, 20)
      assertEquals(t.update(Seq(a)), Seq.empty)
      assertEquals(t.count, 1, "the track exists even while unconfirmed")
      // The box is a2 itself, not the Kalman-smoothed one: the report is the detection, the filter only
      // steers association.
      assertEquals(t.update(Seq(a2)), Seq(ObjectTrack(0, a2, hits = 2, age = 1)))
    finally t.close()

  test(
    "ObjectTracker coasts a track through an unseen frame under maxAge and re-associates without a new id"
  ):
    val t = ObjectTracker.create(maxAge = 1)
    try
      val a = Rect(10, 10, 20, 20)
      t.update(Seq(a)): Unit
      assertEquals(t.update(Seq.empty), Seq.empty, "an unseen track is not reported")
      assertEquals(t.update(Seq(a)).map(_.id), Seq(0))
      assertEquals(t.count, 1, "the coasted track was re-used, not replaced")
    finally t.close()

  test("ObjectTracker.create rejects an IoU threshold outside [0, 1] and a negative maxAge"):
    intercept[IllegalArgumentException](ObjectTracker.create(iouThreshold = 1.5)): Unit
    intercept[IllegalArgumentException](ObjectTracker.create(maxAge = -1)): Unit

  test("ObjectTracker.create rejects a minHits below 1 — a track must be hit before it confirms"):
    intercept[IllegalArgumentException](ObjectTracker.create(minHits = 0)): Unit
    intercept[IllegalArgumentException](ObjectTracker.create(minHits = -1)): Unit

  test("ObjectTracker.update after close is an IllegalStateException, not a native leak"):
    val t = ObjectTracker.create()
    t.update(Seq(Rect(10, 10, 20, 20))): Unit
    t.close()
    val e = intercept[IllegalStateException](t.update(Seq(Rect(10, 10, 20, 20))))
    assert(e.getMessage.contains("already been closed"), e.getMessage)
    assertEquals(t.count, 1, "a closed tracker must not spawn new tracks")

  test("ObjectTracker.close is idempotent"):
    val t = ObjectTracker.create()
    t.update(Seq(Rect(10, 10, 20, 20))): Unit
    t.close()
    t.close() // second close must not throw

  test("Tracker.update before init is an IllegalArgumentException, not a native call"):
    val fresh = Tracker.create(TrackerKind.Csrt).fold(throw _, identity)
    val f0 = frame(50)
    try intercept[IllegalArgumentException](fresh.update(f0)): Unit
    finally
      fresh.close()
      f0.close()

  test("Tracker.update after close is an IllegalStateException"):
    val t = Tracker.create(TrackerKind.Csrt).fold(throw _, identity)
    val f0 = frame(50)
    try
      t.init(f0, Rect(35, 85, 30, 30))
      t.close()
      intercept[IllegalStateException](t.update(f0)): Unit
    finally
      // Idempotent, so this only matters if init throws before the close above.
      t.close()
      f0.close()

  test("every TrackerKind constructs and keeps an unchanged object on a still frame"):
    val box = Rect(35, 85, 30, 30)
    for kind <- TrackerKind.values do
      val tr = Tracker.create(kind).fold(throw _, identity)
      val f0 = frame(50)
      val f1 = frame(50)
      try
        tr.init(f0, box)
        val out = tr.update(f1)
        assert(out.isDefined, s"$kind lost an unchanged object")
        assert(ObjectTracker.iou(out.get, box) > 0.5, s"$kind drifted to ${out.get}")
      finally
        tr.close()
        f0.close()
        f1.close()
