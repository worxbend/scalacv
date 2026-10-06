package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}

/** [[Pose]] estimation: the topologies, the two output-tensor decoders (fed synthetic tensors, so no model
  * file is needed), head pose from face landmarks, and skeleton drawing.
  */
class PoseTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("the built-in topologies have the expected sizes and valid edges"):
    assertEquals(PoseTopology.CocoBody17.size, 17)
    assertEquals(PoseTopology.Hand21.size, 21)
    assertEquals(PoseTopology.Hand21.edges.size, 20) // 5 fingers x (1 base link + 3 joints)
    // the require in PoseTopology already guarantees edge indices are in range for both

  test("regression decode maps (y, x, score) rows to scaled keypoints"):
    val k = PoseTopology.CocoBody17.size
    val tensor = Mat(k, 3, CvType.CV_32F) // reshape(1, k) is a no-op on a k x 3 CV_32FC1
    val row = Array(0.1f, 0.2f, 0.7f) // y, x, score — same for every keypoint
    for i <- 0 until k do tensor.put(i, 0, row)
    try
      val pose = PoseEstimator.decode(tensor, Size(200, 100), KeypointLayout.Regression)
      assertEquals(pose.keypoints.size, k)
      val nose = pose("nose").get
      assertEqualsDouble(nose.point.x, 0.2 * 200, 1e-4) // x from column 1
      assertEqualsDouble(nose.point.y, 0.1 * 100, 1e-4) // y from column 0
      assertEqualsDouble(nose.score.toDouble, 0.7, 1e-6)
    finally tensor.release()

  test("heatmap decode picks the arg-max of each keypoint plane"):
    val k = PoseTopology.CocoBody17.size
    val h = 4
    val w = 5
    val tensor = Mat(Array(1, k, h, w), CvType.CV_32F, cv.Scalar.all(0))
    val flat = tensor.reshape(1, k) // k rows x (h*w), shares the tensor's data
    try
      val plane = Array.ofDim[Float](h * w)
      for c <- 0 until k do
        java.util.Arrays.fill(plane, 0f)
        val py = c % h
        val px = (c * 2) % w
        plane(py * w + px) = 1.0f // a single peak per channel
        flat.put(c, 0, plane)
      val pose = PoseEstimator.decode(tensor, Size(100, 80), KeypointLayout.Heatmap)
      // channel 0: peak at (py=0, px=0) -> (0, 0)
      assertEqualsDouble(pose.keypoints(0).point.x, 0.0, 1e-4)
      assertEqualsDouble(pose.keypoints(0).point.y, 0.0, 1e-4)
      // channel 1: peak at (py=1, px=2) -> (2/5*100, 1/4*80)
      assertEqualsDouble(pose.keypoints(1).point.x, 2.0 / 5 * 100, 1e-4)
      assertEqualsDouble(pose.keypoints(1).point.y, 1.0 / 4 * 80, 1e-4)
      assertEqualsDouble(pose.keypoints(0).score.toDouble, 1.0, 1e-6)
    finally
      flat.release()
      tensor.release()

  test("Pose helpers: lookup, confidence filter, and bone filtering"):
    val topo = PoseTopology(Seq("a", "b", "c"), Seq((0, 1), (1, 2)))
    val pose = Pose(
      Seq(
        Keypoint("a", Point(0, 0), 0.9f),
        Keypoint("b", Point(10, 0), 0.9f),
        Keypoint("c", Point(20, 0), 0.1f) // below threshold
      ),
      topo
    )
    assertEquals(pose("b").map(_.point.x), Some(10.0))
    assertEquals(pose.confident(0.3f).map(_.name), Seq("a", "b"))
    // edge (0,1) survives (both confident); edge (1,2) drops (c is not confident)
    assertEquals(pose.bones(0.3f), Seq((Point(0, 0), Point(10, 0))))

  test("head pose converges on a frontal face and reports it as roughly centred"):
    val w = 640
    val h = 480
    val cx = w / 2.0
    val cy = h / 2.0
    // Symmetric, frontal landmarks in Face order: right eye, left eye, nose, right mouth, left mouth.
    val face = Face(
      box = Rect((cx - 40).toInt, (cy - 40).toInt, 80, 90),
      landmarks = Seq(
        Point(cx - 30, cy - 20),
        Point(cx + 30, cy - 20),
        Point(cx, cy),
        Point(cx - 20, cy + 25),
        Point(cx + 20, cy + 25)
      ),
      score = 0.99f
    )
    HeadPose.estimate(face, Size(w.toDouble, h.toDouble)) match
      case None => fail("solvePnP should converge on a valid frontal face")
      case Some(hp) =>
        assert(hp.yaw.isFinite && hp.pitch.isFinite && hp.roll.isFinite, s"angles must be finite: $hp")
        assert(math.abs(hp.roll) < 20, s"a symmetric frontal face should have little roll, got $hp")
        assert(math.abs(hp.yaw) < 20, s"a symmetric frontal face should have little yaw, got $hp")

  test("HeadPose.estimate(face, imageSize) uses the one uncalibrated guess — Intrinsics.approx's focal"):
    val w = 640
    val h = 480
    val cx = w / 2.0
    val cy = h / 2.0
    // Asymmetric (turned head) so the focal length materially moves the reported angles.
    val face = Face(
      box = Rect((cx - 40).toInt, (cy - 40).toInt, 80, 90),
      landmarks = Seq(
        Point(cx - 25, cy - 22),
        Point(cx + 30, cy - 20),
        Point(cx - 5, cy),
        Point(cx - 18, cy + 25),
        Point(cx + 20, cy + 25)
      ),
      score = 0.99f
    )
    val size = Size(w.toDouble, h.toDouble)
    (HeadPose.estimate(face, size), HeadPose.estimate(face, Intrinsics.approx(size))) match
      case (Some(viaSize), Some(viaApprox)) =>
        assertEqualsDouble(viaSize.yaw, viaApprox.yaw, 1e-9)
        assertEqualsDouble(viaSize.pitch, viaApprox.pitch, 1e-9)
        assertEqualsDouble(viaSize.roll, viaApprox.roll, 1e-9)
      case other => fail(s"both estimates should converge on a valid face, got $other")

  test("a pose cannot be built with fewer keypoints than its topology names"):
    // The failure this prevents is not "a confusing value" but an IndexOutOfBoundsException thrown later,
    // from `bones` or from GestureRecognizer, both of which index keypoints by a topology-derived index.
    val topo = PoseTopology(Seq("a", "b", "c"), Seq((0, 1), (1, 2)))
    val tooFew = intercept[IllegalArgumentException](Pose(Seq(Keypoint("a", Point(0, 0), 0.9f)), topo))
    assert(tooFew.getMessage.contains("one keypoint per topology entry"), tooFew.getMessage)
    val tooMany = intercept[IllegalArgumentException]:
      Pose(Seq.tabulate(4)(i => Keypoint(s"k$i", Point(0, 0), 0.9f)), topo)
    assert(tooMany.getMessage.contains("one keypoint per topology entry"), tooMany.getMessage)

  test("drawSkeleton renders a pose without error"):
    val pose = Pose(
      Seq(Keypoint("a", Point(10, 10), 0.9f), Keypoint("b", Point(40, 40), 0.9f)),
      PoseTopology(Seq("a", "b"), Seq((0, 1)))
    )
    val out = Image.blank(80, 80).drawSkeleton(pose).bytes(".png")
    assert(out.isRight, s"expected an encoded skeleton, got $out")
