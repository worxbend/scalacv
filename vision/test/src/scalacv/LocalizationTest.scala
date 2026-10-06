package scalacv

import scalacv.vision.*

/** The higher-level navigation components: absolute localization (solvePnP), reactive steering, and the
  * running odometry pipeline.
  */
class LocalizationTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val focal = 600.0
  private val cx = 320.0
  private val cy = 240.0

  // Non-coplanar world points (varying depth) so solvePnP is well posed.
  private val world = Seq(
    (-1.0, -1.0, 6.0),
    (1.0, -1.0, 6.5),
    (-1.0, 1.0, 7.0),
    (1.0, 1.0, 5.5),
    (0.0, 0.0, 8.0),
    (0.6, -0.4, 7.2)
  )

  private def project(x: Double, y: Double, z: Double): Point =
    Point(focal * x / z + cx, focal * y / z + cy)

  test("localizer recovers the identity pose when world and camera frames coincide"):
    val image = world.map((x, y, z) => project(x, y, z))
    Localizer.locate(world, image, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("solvePnP should converge on six good correspondences")
      case Some(pose) =>
        assert(
          pose.rotation(0)(0) > 0.99 && pose.rotation(1)(1) > 0.99 && pose.rotation(2)(2) > 0.99,
          s"expected identity rotation, got ${pose.rotation}"
        )
        assert(
          pose.translation.forall(v => math.abs(v) < 0.05),
          s"expected zero translation, got ${pose.translation}"
        )

  test("localizer recovers a translated camera's position"):
    // Camera moved to world (2,0,0), still looking +Z: a world point (X,Y,Z) sits at camera (X-2,Y,Z).
    val image = world.map((x, y, z) => project(x - 2.0, y, z))
    Localizer.locate(world, image, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("solvePnP should converge")
      case Some(pose) =>
        val pos = pose.position
        assert(math.abs(pos(0) - 2.0) < 0.1, s"camera X position should be ~2, got ${pos(0)}")
        assert(math.abs(pos(1)) < 0.1 && math.abs(pos(2)) < 0.1, s"camera should be on the X axis, got $pos")

  test("localizer needs at least four correspondences"):
    val three = world.take(3)
    assertEquals(
      Localizer.locate(three, three.map((x, y, z) => project(x, y, z)), Intrinsics(focal, focal, cx, cy)),
      None
    )

  /** Runs `locate` on an exact, noise-free projection of `points`, so any `None` is the solver refusing the
    * configuration rather than a fit that failed to converge.
    */
  private def locateExact(points: Seq[(Double, Double, Double)]): Option[CameraPose] =
    Localizer.locate(points, points.map((x, y, z) => project(x, y, z)), Intrinsics(focal, focal, cx, cy))

  test("localizer answers None, not a native exception, on four or five non-coplanar correspondences"):
    // The default iterative solver initialises with a direct linear transform that aborts in native code
    // ("DLT algorithm needs at least 6 points") rather than reporting failure, so before the Cv.attempt
    // wrapper these two calls threw org.opencv.core.CvException straight through the Option return type.
    assertEquals(locateExact(world.take(4)), None)
    assertEquals(locateExact(world.take(5)), None)

  test("localizer still solves four coplanar correspondences"):
    // A single flat surface (constant Z) is the planar case OpenCV solves from four points, so the fix must
    // not have turned the whole 4-point band into None.
    val planar = Seq((-1.0, -1.0, 6.0), (1.0, -1.0, 6.0), (-1.0, 1.0, 6.0), (1.0, 1.0, 6.0))
    locateExact(planar) match
      case None => fail("solvePnP should solve four coplanar correspondences")
      case Some(pose) =>
        assert(
          pose.rotation(0)(0) > 0.99 && pose.rotation(1)(1) > 0.99 && pose.rotation(2)(2) > 0.99,
          s"expected identity rotation, got ${pose.rotation}"
        )
        assert(
          pose.translation.forall(v => math.abs(v) < 0.05),
          s"expected zero translation, got ${pose.translation}"
        )

  /** A rotation of `degrees` about the camera's Y axis (a yaw), as the row-major 3×3 `CameraPose` carries. */
  private def yaw(degrees: Double): Seq[Seq[Double]] =
    val c = math.cos(math.toRadians(degrees))
    val s = math.sin(math.toRadians(degrees))
    Seq(Seq(c, 0.0, s), Seq(0.0, 1.0, 0.0), Seq(-s, 0.0, c))

  private def rigid(
      r: Seq[Seq[Double]],
      t: Seq[Double]
  )(p: (Double, Double, Double)): (Double, Double, Double) =
    val (x, y, z) = p
    (
      r(0)(0) * x + r(0)(1) * y + r(0)(2) * z + t(0),
      r(1)(0) * x + r(1)(1) * y + r(1)(2) * z + t(1),
      r(2)(0) * x + r(2)(1) * y + r(2)(2) * z + t(2)
    )

  test("CameraPose.position is -Rᵀ·t, not -R·t, for a rotated camera"):
    // A quarter turn about Z with t along X: the two formulas disagree in the sign of Y.
    val pose = CameraPose(
      rotation = Seq(Seq(0.0, -1.0, 0.0), Seq(1.0, 0.0, 0.0), Seq(0.0, 0.0, 1.0)),
      translation = Seq(1.0, 0.0, 0.0)
    )
    val pos = pose.position
    assertEqualsDouble(pos(0), 0.0, 1e-12)
    assertEqualsDouble(pos(1), 1.0, 1e-12)
    assertEqualsDouble(pos(2), 0.0, 1e-12)

  test("localizer recovers a yawed and translated camera in the x_cam = R·x_world + t convention"):
    val r = yaw(15.0)
    val t = Seq(0.3, -0.2, 0.5)
    val image = world.map(p => project.tupled(rigid(r, t)(p)))
    Localizer.locate(world, image, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("solvePnP should converge on an exact projection")
      case Some(pose) =>
        // The off-diagonal (0)(2) ≈ +0.2588 is what separates R from its transpose.
        for i <- 0 until 3; j <- 0 until 3 do
          assertEqualsDouble(pose.rotation(i)(j), r(i)(j), 1e-3, s"rotation($i)($j)")
        for i <- 0 until 3 do assertEqualsDouble(pose.translation(i), t(i), 1e-3, s"translation($i)")

  test("localizer rejects mismatched correspondence counts as a programmer error"):
    intercept[IllegalArgumentException](
      Localizer.locate(
        world.take(4),
        world.take(3).map((x, y, z) => project(x, y, z)),
        Intrinsics(focal, focal, cx, cy)
      )
    )

  // -- Visual odometry -----------------------------------------------------------------------------

  // Twelve points spread wide and deep (z from 3.5 to 12). On exact data RANSAC keeps the first 5-point
  // hypothesis that reprojects every point within 1px and stops, so the motion is only as accurate as that
  // one sample's conditioning: reordering these points moves the recovered R by up to ~0.014 per entry. The
  // spread keeps every such hypothesis close to the truth; a narrow cluster at one depth lets a wrong yaw
  // through by a degree or two.
  private val odometryWorld = Seq(
    (-2.0, -1.5, 4.0),
    (2.0, -1.5, 9.0),
    (-2.0, 1.5, 12.0),
    (2.0, 1.5, 5.0),
    (0.0, 0.0, 7.0),
    (1.0, -0.8, 3.5),
    (-1.2, 0.6, 10.0),
    (0.4, 1.2, 6.0),
    (-0.5, -1.0, 8.5),
    (1.5, 0.3, 4.5),
    (-1.8, -0.2, 6.5),
    (0.8, 0.9, 11.0)
  )

  test("visual odometry recovers a known yaw and the unit translation direction (x_2 = R·x_1 + t)"):
    val r = yaw(10.0)
    val t = Seq(0.3, 0.0, 0.1)
    val from = odometryWorld.map(project.tupled)
    val to = odometryWorld.map(p => project.tupled(rigid(r, t)(p)))
    VisualOdometry.estimate(from, to, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("recoverPose should converge on an exact projection")
      case Some(motion) =>
        // A few hundredths absorbs the sample-dependent RANSAC error above; the transpose is 0.34 off at
        // (0)(2) and a flipped translation has alignment -1, so both stay far outside.
        for i <- 0 until 3; j <- 0 until 3 do
          assert(math.abs(motion.rotation(i)(j) - r(i)(j)) < 0.05, s"rotation($i)($j): ${motion.rotation}")
        val norm = math.sqrt(t.map(v => v * v).sum)
        val alignment = motion.translation.zip(t.map(_ / norm)).map(_ * _).sum
        assert(alignment > 0.9, s"translation should point along $t, got ${motion.translation}")
        assert(
          motion.inliers >= odometryWorld.size / 2,
          s"most exact correspondences should pass cheirality, got ${motion.inliers} of ${odometryWorld.size}"
        )

  test("visual odometry reports a sideways camera move as points sliding the other way"):
    // Camera moved +0.4 along X, so every point sits at x - 0.4 in the second frame: t ∝ (-1, 0, 0), and the
    // cheirality check fixes that sign rather than leaving it to the essential matrix's ambiguity.
    val from = odometryWorld.map(project.tupled)
    val to = odometryWorld.map((x, y, z) => project(x - 0.4, y, z))
    VisualOdometry.estimate(from, to, Intrinsics(focal, focal, cx, cy)) match
      case None => fail("recoverPose should converge on an exact projection")
      case Some(motion) =>
        val Seq(tx, ty, tz) = motion.translation
        assert(
          tx < -0.95 && math.abs(ty) < 0.2 && math.abs(tz) < 0.2,
          s"expected ~(-1, 0, 0), got ${motion.translation}"
        )

  test("visual odometry rejects mismatched correspondence counts as a programmer error"):
    intercept[IllegalArgumentException](
      VisualOdometry.estimate(
        Seq.fill(6)(Point(1, 1)),
        Seq.fill(5)(Point(1, 1)),
        Intrinsics(focal, focal, cx, cy)
      )
    )

  test("visual odometry never surfaces a raw CvException or a pose for five coincident correspondences"):
    // What findEssentialMat does on coincident points is solver-dependent (an empty E, a NaN E, a stack of
    // candidate Es, or a native assertion), so the promise pinned here is the wrapper's: a None or a named
    // CvError, never a pose and never an unwrapped org.opencv.core.CvException.
    val same = Seq.fill(5)(Point(100, 100))
    scala.util.Try(VisualOdometry.estimate(same, same, Intrinsics(focal, focal, cx, cy))) match
      case scala.util.Success(None) => ()
      case scala.util.Failure(_: CvError.NativeCall) => ()
      case scala.util.Success(Some(motion)) => fail(s"degenerate geometry must not yield a pose: $motion")
      case scala.util.Failure(other) => fail(s"expected None or a named CvError, got $other")

  // -- Navigator -----------------------------------------------------------------------------------

  /** A disparity map whose left/centre/right thirds have the given near-ness (0..255). */
  private def disparity(left: Int, centre: Int, right: Int): Image =
    Image
      .blank(300, 150, Scalar.Black, channels = 1)
      .drawRect(Rect(0, 0, 100, 150), Scalar(left.toDouble), Thickness.Filled)
      .drawRect(Rect(100, 0, 100, 150), Scalar(centre.toDouble), Thickness.Filled)
      .drawRect(Rect(200, 0, 100, 150), Scalar(right.toDouble), Thickness.Filled)

  private def steer(l: Int, c: Int, r: Int): Steering =
    val d = disparity(l, c, r)
    try Navigator.steer(d).steering
    finally d.close()

  test("navigator goes straight when the way ahead is clear"):
    assertEquals(steer(0, 0, 0), Steering.Straight)

  test("navigator turns toward the clearer side of a central obstacle"):
    assertEquals(steer(0, 220, 220), Steering.Left) // right also blocked → go left
    assertEquals(steer(220, 220, 0), Steering.Right) // left also blocked → go right

  test("navigator stops when boxed in"):
    assertEquals(steer(220, 220, 220), Steering.Stop)

  // -- Odometry pipeline ---------------------------------------------------------------------------

  private def scene(ox: Int, oy: Int): Image =
    Image
      .blank(200, 160, Scalar(30, 30, 30))
      .drawRects(
        Seq(Rect(30 + ox, 30 + oy, 24, 24), Rect(120 + ox, 40 + oy, 28, 20), Rect(70 + ox, 100 + oy, 20, 30)),
        Scalar.White,
        Thickness.Filled
      )

  test("the odometry pipeline reports None on the first frame and then runs frame by frame"):
    val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 100, cy = 80))
    try
      val frame0 = scene(0, 0)
      val frame1 = scene(4, 3)
      val frame2 = scene(8, 6)
      try
        assertEquals(odometry.update(frame0), None) // first frame is the reference
        odometry.update(frame1) // runs the track + estimate loop (Option either way)
        odometry.update(frame2)
        assertEquals(odometry.framesProcessed, 3)
      finally
        frame0.close(); frame1.close(); frame2.close()
    finally odometry.close()

  test("the odometry pipeline answers None on featureless frames instead of throwing"):
    // A uniform frame seeds no corners, and a textured frame after it has nothing to track from: both are
    // "too few points", not errors.
    val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 100, cy = 80))
    try
      val flat0 = Image.blank(200, 160, Scalar(30, 30, 30))
      val flat1 = Image.blank(200, 160, Scalar(30, 30, 30))
      val textured = scene(0, 0)
      try
        assertEquals(odometry.update(flat0), None)
        assertEquals(odometry.update(flat1), None)
        assertEquals(odometry.update(textured), None)
        assertEquals(odometry.framesProcessed, 3)
      finally
        flat0.close(); flat1.close(); textured.close()
    finally odometry.close()

  test("the odometry pipeline re-baselines after close rather than touching the released frame"):
    // The current contract: close releases the retained frame and the next update starts over as a fresh
    // reference, while the frame count keeps running. If a closed pipeline should throw instead, this is the
    // test to change.
    val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 100, cy = 80))
    val frame0 = scene(0, 0)
    val frame1 = scene(4, 3)
    val frame2 = scene(8, 6)
    try
      odometry.update(frame0)
      odometry.close()
      assertEquals(odometry.update(frame1), None)
      assertEquals(odometry.framesProcessed, 2)
      odometry.update(frame2)
      assertEquals(odometry.framesProcessed, 3)
    finally
      odometry.close()
      frame0.close(); frame1.close(); frame2.close()
