package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Model-free contracts and laws of the vision layer — embedding metrics, [[Gallery]] lookup, gesture rules,
  * pinhole projection, the pose decoders' guards and ArUco corner order. Everything runs on hand-built data:
  * no model file, no asset, and the only native work is `projectPoints`, a few synthetic tensors and one
  * marker round-trip. Property laws sit here rather than in the per-feature example suites so only this one
  * file needs to be a ScalaCheck suite.
  */
class VisionContractsTest extends munit.ScalaCheckSuite:

  override def beforeAll(): Unit = OpenCv.load()

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(50)

  private def emb(xs: Float*): FaceEmbedding = FaceEmbedding(xs.toVector)

  /** Three same-length embeddings, so every pairwise law and the triangle inequality can be checked at once.
    */
  private val genTriple: Gen[(FaceEmbedding, FaceEmbedding, FaceEmbedding)] =
    Gen
      .choose(1, 16)
      .flatMap: n =>
        val vec = Gen.listOfN(n, Gen.choose(-10f, 10f)).map(l => FaceEmbedding(l.toVector))
        Gen.zip(vec, vec, vec)

  /** Builds a Hand21 pose. `fingers` = (thumb, index, middle, ring, pinky) extended flags; every tip carries
    * `tipScore`. An extended finger's tip sits far from the wrist (up the frame); a curled one's tip sits
    * close.
    */
  private def hand(fingers: (Boolean, Boolean, Boolean, Boolean, Boolean), tipScore: Float = 0.9f): Pose =
    val (t, i, m, r, p) = fingers
    val wrist = Point(50, 100)
    val pts = Array.fill(21)(wrist)
    def finger(tip: Int, extended: Boolean, x: Double): Unit =
      pts(tip - 2) = Point(x, if extended then 60 else 55)
      pts(tip) = Point(x, if extended then 20 else 82)
    finger(4, t, 30)
    finger(8, i, 42)
    finger(12, m, 50)
    finger(16, r, 58)
    finger(20, p, 66)
    val names = PoseTopology.Hand21.names
    Pose(names.indices.map(idx => Keypoint(names(idx), pts(idx), tipScore)).toSeq, PoseTopology.Hand21)

  /** Every landmark rotated by `angle` about the origin, scaled by `s` and shifted by `(dx, dy)` — a
    * similarity transform, which preserves every distance ratio the gesture rules compare.
    */
  private def transform(pose: Pose, angle: Double, dx: Double, dy: Double, s: Double): Pose =
    val (cos, sin) = (math.cos(angle), math.sin(angle))
    pose.copy(keypoints = pose.keypoints.map: kp =>
      val Point(x, y) = kp.point
      kp.copy(point = Point(s * (x * cos - y * sin) + dx, s * (x * sin + y * cos) + dy)))

  private val genFlags: Gen[(Boolean, Boolean, Boolean, Boolean, Boolean)] =
    Gen.listOfN(5, Gen.oneOf(true, false)).map(l => (l(0), l(1), l(2), l(3), l(4)))

  /** Arm after finite constructor validation to detect any later coefficient read. */
  private final class ExplodingDistortion extends scala.collection.immutable.Seq[Double]:
    var armed = false
    def length: Int = 4
    override def isEmpty: Boolean = false
    def apply(i: Int): Double =
      if !armed then 0.0 else throw IllegalStateException(s"distortion($i) was read")
    def iterator: Iterator[Double] = Iterator.range(0, length).map(i => apply(i))

  private val quad = Seq(Point(0, 0), Point(1, 0), Point(1, 1), Point(0, 1))

  // -- FaceEmbedding / Gallery -------------------------------------------------------------------------

  property("embedding metrics obey their laws for any three vectors of one length"):
    forAll(genTriple, Gen.choose(0.1, 10.0)): (triple, k) =>
      val (a, b, c) = triple
      val scaled = FaceEmbedding(b.values.map(_ * k.toFloat))
      val cosAB = a.cosineSimilarity(b)
      // Scaling b rounds each element to Float32 (~6e-8 relative), so 1e-9 would flap here.
      math.abs(cosAB - b.cosineSimilarity(a)) < 1e-12 &&
      cosAB >= -1 - 1e-9 && cosAB <= 1 + 1e-9 &&
      math.abs(a.cosineSimilarity(scaled) - cosAB) < 1e-6 &&
      a.l2Distance(b) == b.l2Distance(a) &&
      a.l2Distance(a) == 0.0 &&
      a.l2Distance(c) <= a.l2Distance(b) + b.l2Distance(c) + 1e-9

  test("an empty embedding is rejected, a zero vector has no direction, and l2Distance needs equal lengths"):
    assertEquals(emb(0, 0, 0).cosineSimilarity(emb(1, 2, 3)), 0.0)
    intercept[IllegalArgumentException](FaceEmbedding(Vector.empty))
    intercept[IllegalArgumentException](emb(1, 2).l2Distance(emb(1, 2, 3)))

  test(
    "Gallery.identify picks the best of duplicate enrolments, honours an explicit inclusive threshold, " +
      "and answers None on an empty gallery"
  ):
    assertEquals(Gallery.empty.identify(emb(1, 0)), None)
    val g = Gallery.empty.enroll("ada", emb(1, 0, 0)).enroll("ada", emb(0, 1, 0))
    assertEquals(g.names, Seq("ada", "ada"))
    assertEquals(g.size, 2)
    g.identify(emb(0, 0.9f, 0.1f)) match
      case Some(FaceMatch("ada", s)) => assertEqualsDouble(s, 0.9 / math.sqrt(0.82), 1e-6)
      case other => fail(s"expected ada via the (0, 1, 0) enrolment, got $other")
    assertEquals(g.identify(emb(0, 0, 1), threshold = 0.0), Some(FaceMatch("ada", 0.0)))
    assertEquals(g.identify(emb(0, 0, 1)), None)

  // -- GestureRecognizer -------------------------------------------------------------------------------

  test("gesture rules cover Unknown and four-finger palms, and gate on tip score"):
    assertEquals(GestureRecognizer.recognize(hand((true, true, false, false, false))), HandGesture.Unknown)
    assertEquals(GestureRecognizer.recognize(hand((false, true, true, true, true))), HandGesture.OpenPalm)
    assertEquals(
      GestureRecognizer.recognize(hand((true, true, true, true, true), tipScore = 0.1f), minScore = 0.3f),
      HandGesture.Fist
    )

  property("gesture recognition is invariant under rotation, translation and scale"):
    forAll(
      genFlags,
      Gen.choose(0.0, 2 * math.Pi),
      Gen.choose(-500.0, 500.0),
      Gen.choose(-500.0, 500.0),
      Gen.choose(0.5, 3.0)
    ): (flags, angle, dx, dy, s) =>
      val expected = GestureRecognizer.recognize(hand(flags))
      GestureRecognizer.recognize(transform(hand(flags), angle, dx, dy, s)) == expected

  // -- Ar / Pose3D / Intrinsics ------------------------------------------------------------------------

  test("project with no rotation maps a model point through the pinhole formula exactly"):
    // fx != fy and cx != cy, so a transposed camera matrix or swapped principal point cannot pass.
    val intr = Intrinsics(100, 200, 50, 60)
    val pose = Pose3D(Seq(0, 0, 0), Seq(0, 0, 2))
    val Seq(p) = Ar.project(Seq(Point3(0.5, 0.25, 0)), pose, intr): @unchecked
    assertEqualsDouble(p.x, 75.0, 1e-6)
    assertEqualsDouble(p.y, 85.0, 1e-6)

  test("project of an empty point list needs no natives and never reads the intrinsics"):
    val coefficients = ExplodingDistortion()
    val intr = Intrinsics(100, 200, 50, 60).copy(distortion = coefficients)
    coefficients.armed = true
    assertEquals(Ar.project(Seq.empty, Pose3D(Seq(0, 0, 0), Seq(0, 0, 2)), intr), Seq.empty)

  test("Pose3D and MarkerPose are 3-vectors with a Euclidean distance, and markerLength must be positive"):
    intercept[IllegalArgumentException](Pose3D(Seq(1, 2), Seq(0, 0, 0)))
    intercept[IllegalArgumentException](Pose3D(Seq(0, 0, 0), Seq(1, 2, 3, 4)))
    assertEquals(Pose3D(Seq(0, 0, 0), Seq(3, 4, 0)).distance, 5.0)
    val mp = MarkerPose(ArucoMarker(9, quad), Pose3D(Seq(0, 0, 0), Seq(3, 4, 0)))
    assertEquals(mp.id, 9)
    assertEquals(mp.distance, 5.0)
    intercept[IllegalArgumentException](
      Ar.estimatePose(ArucoMarker(1, quad), 0.0, Intrinsics.approx(Size(100, 100)))
    )

  // -- PoseEstimator / Pose ----------------------------------------------------------------------------

  test("PoseEstimator.decode names a wrong-shaped tensor and borrows it"):
    val reg = Mat(10, 3, CvType.CV_32F)
    val twoD = Mat(17, 20, CvType.CV_32F)
    val five = Mat(Array(1, 5, 4, 5), CvType.CV_32F)
    try
      val e1 =
        intercept[CvError.NativeCall](PoseEstimator.decode(reg, Size(100, 80), KeypointLayout.Regression))
      assert(e1.getMessage.contains("expected 51 values"), e1.getMessage)
      assert(e1.getMessage.contains("produced 30"), e1.getMessage)
      assertEquals(reg.total(), 30L)
      val e2 =
        intercept[CvError.NativeCall](PoseEstimator.decode(twoD, Size(100, 80), KeypointLayout.Heatmap))
      assert(e2.getMessage.contains("4-D"), e2.getMessage)
      assert(e2.getMessage.contains("2-D"), e2.getMessage)
      val e3 =
        intercept[IllegalArgumentException](PoseEstimator.decode(five, Size(100, 80), KeypointLayout.Heatmap))
      assert(e3.getMessage.contains("5 keypoints"), e3.getMessage)
      assertEquals(five.total(), 100L)
    finally
      reg.release()
      twoD.release()
      five.release()

  test("a flat heatmap resolves to the first cell with score 0"):
    val k = PoseTopology.CocoBody17.size
    val flat = Mat(Array(1, k, 4, 5), CvType.CV_32F, cv.Scalar.all(0))
    try
      val pose = PoseEstimator.decode(flat, Size(100, 80), KeypointLayout.Heatmap)
      assertEquals(pose.keypoints.size, k)
      // The strict `>` in the arg-max keeps the first cell on a tie, so a plane of zeros is (0, 0).
      pose.keypoints.foreach: kp =>
        assertEquals(kp.point, Point(0, 0), kp.name)
        assertEquals(kp.score, 0f, kp.name)
    finally flat.release()

  test("invalid topology edges are rejected, and meanScore averages every keypoint"):
    intercept[IllegalArgumentException](PoseTopology(Seq("a"), Seq((0, 1))))
    val topo = PoseTopology(Seq("a", "b", "c"), Seq.empty)
    val pose = Pose(
      Seq(
        Keypoint("a", Point(0, 0), 0.2f),
        Keypoint("b", Point(0, 0), 0.4f),
        Keypoint("c", Point(0, 0), 0.6f)
      ),
      topo
    )
    assertEqualsFloat(pose.meanScore, 0.4f, 1e-6f)
    assertEquals(Pose(Seq.empty, PoseTopology(Seq.empty, Seq.empty)).meanScore, 0f)

  // -- Aruco -------------------------------------------------------------------------------------------

  test(
    "detected ArUco corners come back clockwise from the marker's top-left, in image (x, y) not (row, col)"
  ):
    // Asymmetric margins (top 90, bottom 30, left 30, right 90) around a 200 px tag, so each corner has a
    // distinct expected position: an x/y swap reports (90, 30) first, and a reversed or rotated order puts a
    // corner 200 px from where it belongs.
    Aruco
      .generateMarker(ArucoDictionary.Dict4x4_50, 5, 200)
      .use(_.border(90, 30, 30, 90, color = Scalar.White))
      .use: scene =>
        val found = Aruco.detect(scene)
        assertEquals(found.map(_.id), Seq(5))
        val expected = Seq(Point(30, 90), Point(230, 90), Point(230, 290), Point(30, 290))
        assertEquals(found.head.corners.size, 4)
        // The default detector does no corner refinement: each corner is a contour vertex on the tag's
        // outermost dark pixel, about 1 px inside the nominal edge on every platform. 4 px covers that with
        // margin while staying two orders of magnitude below an order mistake.
        found.head.corners
          .zip(expected)
          .foreach: (got, want) =>
            assert(got.distanceTo(want) < 4.0, s"$got vs $want in ${found.head.corners}")
