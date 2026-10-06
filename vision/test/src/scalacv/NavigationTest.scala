package scalacv

import scalacv.vision.*

/** The SLAM/navigation front end: optical flow, ORB features, stereo obstacles, and visual odometry — all on
  * synthetic scenes, so no dataset is needed.
  */
class NavigationTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A textured scene (white blocks on dark) whose blocks are offset by (ox, oy) — a controllable "camera
    * shift" for tracking.
    */
  private def scene(ox: Int, oy: Int): Image =
    Image
      .blank(220, 180, Scalar(30, 30, 30))
      .drawRects(
        Seq(
          Rect(30 + ox, 30 + oy, 26, 26),
          Rect(130 + ox, 40 + oy, 30, 22),
          Rect(70 + ox, 110 + oy, 22, 34),
          Rect(150 + ox, 120 + oy, 26, 26)
        ),
        Scalar.White,
        Thickness.Filled
      )

  test("optical flow follows a rigid shift"):
    val base = scene(0, 0)
    val shifted = scene(6, 4)
    try
      val features = OpticalFlow.goodFeatures(base)
      assert(features.nonEmpty, "the textured scene should yield trackable corners")
      val kept = OpticalFlow.track(base, shifted, features).filter(_.found)
      assert(kept.nonEmpty, "some points should survive the track")
      val dx = kept.map(_.displacement.x).sum / kept.size
      val dy = kept.map(_.displacement.y).sum / kept.size
      assert(math.abs(dx - 6) < 2.0, s"mean dx should be ~6, was $dx")
      assert(math.abs(dy - 4) < 2.0, s"mean dy should be ~4, was $dy")
    finally
      base.close()
      shifted.close()

  test("ORB features match an image to itself"):
    val img = scene(0, 0)
    val a = Features.detect(img)
    try
      assert(a.size > 0, "the scene should have ORB features")
      val ms = Features.matches(a, a)
      assert(ms.nonEmpty, "an image should match itself")
      // With cross-check, each descriptor's mutual-best is itself, at distance 0.
      assert(ms.forall(_.distance == 0f), "self-matches should be exact")
      assert(ms.exists(m => m.queryIndex == m.trainIndex), "indices should line up for a self-match")
    finally
      a.close()
      img.close()

  test("obstacle detection reads a near blob off a disparity map"):
    // A disparity map: dark (far) with one bright (near) block.
    val disparity = Image
      .blank(200, 150, Scalar.Black, channels = 1)
      .drawRect(Rect(60, 50, 44, 40), Scalar(210), Thickness.Filled)
    try
      val obstacles = Obstacles.fromDisparity(disparity, minNearness = 0.5, minArea = 200)
      assert(obstacles.nonEmpty, "the near block should be an obstacle")
      val o = obstacles.head
      assert(o.nearness > 0.7, s"the block (210/255) should read as very near, got ${o.nearness}")
      assert(
        o.region.x <= 65 && o.region.x + o.region.width >= 100,
        s"region should cover the block, got ${o.region}"
      )
    finally disparity.close()

  test("stereo disparity returns an 8-bit single-channel map of the pair's size"):
    val left = scene(0, 0)
    val right = scene(3, 0) // a horizontal shift, as a stereo baseline would give
    try
      val disp = StereoDepth.disparity(left, right, numDisparities = 32, blockSize = 7)
      try assertEquals((disp.width, disp.height, disp.channels), (220, 180, 1))
      finally disp.close()
    finally
      left.close()
      right.close()

  test("visual odometry needs at least five correspondences"):
    val four = Seq.fill(4)(Point(1, 1))
    assertEquals(
      VisualOdometry.estimate(four, four, Intrinsics(fx = 500, fy = 500, cx = 100, cy = 100)),
      None
    )

  test("visual odometry recovers a sideways camera translation"):
    val focal = 500.0
    val cx = 320.0
    val cy = 240.0
    // World points in front of the camera; project them before and after moving the camera +0.4 along X.
    val world = Seq(
      (-1.0, -1.0, 5.0),
      (1.0, -1.0, 6.0),
      (-1.0, 1.0, 7.0),
      (1.0, 1.0, 5.5),
      (0.0, 0.0, 6.0),
      (0.5, -0.7, 5.2),
      (-0.6, 0.4, 6.5),
      (0.2, 0.8, 5.8)
    )
    def project(p: (Double, Double, Double), camX: Double): Point =
      val (x, y, z) = p
      Point(focal * (x - camX) / z + cx, focal * y / z + cy)
    val from = world.map(project(_, 0.0))
    val to = world.map(project(_, 0.4))
    VisualOdometry.estimate(from, to, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("odometry should recover a motion from eight good correspondences")
      case Some(motion) =>
        // recoverPose returns a UNIT translation direction (monocular scale is unobservable)...
        val mag = math.sqrt(motion.translation.map(v => v * v).sum)
        assert(math.abs(mag - 1.0) < 0.05, s"translation should be a unit direction, magnitude was $mag")
        // ...and, since the synthetic motion was a pure translation with no turn, a near-identity rotation.
        val rot = motion.rotation
        assert(
          rot(0)(0) > 0.9 && rot(1)(1) > 0.9 && rot(2)(2) > 0.9,
          s"expected ~no rotation (identity), got $rot"
        )
        assert(motion.inliers > 0, "recoverPose should find inliers")

  test("steer rejects an empty or too-narrow disparity map"):
    val empty = Image.wrap(Managed(org.opencv.core.Mat()))
    try intercept[IllegalArgumentException](Navigator.steer(empty))
    finally empty.close()

    val narrow = Image.blank(2, 12) // width 2 < 3 collapses the thirds into a negative-width band
    try
      val e = intercept[IllegalArgumentException](Navigator.steer(narrow))
      assert(e.getMessage.contains("3 px"), e.getMessage)
    finally narrow.close()

  test("a clear (dark) disparity map suggests going straight"):
    val clear = Image.blank(30, 12)
    try
      val g = Navigator.steer(clear)
      assertEquals(g.steering, Steering.Straight)
      assertEqualsDouble(g.centreNearness, 0.0, 0.001)
    finally clear.close()
