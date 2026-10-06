package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}

/** Invariant/property-style checks for the algorithm-heavy SLAM stack, complementing the example-based
  * [[MappingTest]] and [[NavigationTest]]. Where those assert one worked case, these hammer the invariants a
  * regression is most likely to break: the occupancy grid's probability bounds, monotonicity and ray
  * integration, the reactive [[Navigator]]'s steering logic (otherwise untested), and the stateful
  * [[Odometry]] and [[LoopDetector]] pipelines' bookkeeping.
  */
class SlamPropertiesTest extends munit.ScalaCheckSuite:

  override def beforeAll(): Unit = OpenCv.load()

  // -- OccupancyGrid: probability is a bounded, monotone log-odds ----------------------------------

  test("probability stays in [0, 1] under any interleaving of hits and misses"):
    val grid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
    val rnd = new scala.util.Random(7)
    for _ <- 0 until 5000 do
      val x = rnd.between(-2.5, 2.5)
      val y = rnd.between(-2.5, 2.5)
      if rnd.nextBoolean() then grid.hit(x, y) else grid.miss(x, y)
      val p = grid.probability(x, y)
      assert(p >= 0.0 && p <= 1.0, s"probability escaped [0,1]: $p at ($x, $y)")

  test("log-odds clamping bounds probability strictly inside (0, 1) no matter how much evidence piles up"):
    val grid = OccupancyGrid(cols = 20, rows = 20, resolution = 0.1)
    for _ <- 0 until 10_000 do grid.hit(0.5, 0.5)
    val hi = grid.probability(0.5, 0.5)
    assert(hi > 0.5 && hi < 1.0, s"saturated-hit probability must be < 1 (clamped), got $hi")
    for _ <- 0 until 10_000 do grid.miss(-0.5, -0.5)
    val lo = grid.probability(-0.5, -0.5)
    assert(lo < 0.5 && lo > 0.0, s"saturated-miss probability must be > 0 (clamped), got $lo")
    // Symmetric clamp: the two extremes are mirror images about 0.5.
    assertEqualsDouble(hi - 0.5, 0.5 - lo, 1e-9)

  test("a hit never lowers a cell and a miss never raises it (monotonicity)"):
    val grid = OccupancyGrid(cols = 30, rows = 30, resolution = 0.1)
    var prev = grid.probability(1.0, 1.0)
    for _ <- 0 until 20 do
      grid.hit(1.0, 1.0)
      val now = grid.probability(1.0, 1.0)
      assert(now >= prev - 1e-12, s"a hit lowered probability: $prev -> $now")
      prev = now
    for _ <- 0 until 40 do
      grid.miss(1.0, 1.0)
      val now = grid.probability(1.0, 1.0)
      assert(now <= prev + 1e-12, s"a miss raised probability: $prev -> $now")
      prev = now

  test("out-of-bounds and unobserved cells read exactly 0.5"):
    val grid = OccupancyGrid(cols = 40, rows = 40, resolution = 0.1)
    assertEqualsDouble(grid.probability(0.0, 0.0), 0.5, 0.0) // unobserved centre
    assertEqualsDouble(grid.probability(1000.0, 0.0), 0.5, 0.0) // far outside the grid
    assertEqualsDouble(grid.probability(0.0, -1000.0), 0.5, 0.0)
    // "Unknown" reads exactly 0.5, so at any threshold strictly above 0.5 it is not occupied. (At the
    // default 0.5 threshold `>=` treats unknown as occupied — the conservative "assume an obstacle" default.)
    assert(
      !grid.isOccupied(1000.0, 0.0, threshold = 0.6),
      "unknown space is not occupied above the 0.5 prior"
    )

  // -- OccupancyGrid: ray integration as a law over every direction and slope ----------------------

  /** A 21×21 unit-resolution grid: integer world coordinates in -10..10 land exactly on cells 0..20, so a
    * test can address every cell by its world coordinate without rounding.
    */
  private def unitGrid(): OccupancyGrid = OccupancyGrid(21, 21, resolution = 1.0)

  private val genCoord: Gen[Int] = Gen.choose(-10, 10)

  property(
    "one observation frees exactly max(|dx|, |dy|) 8-connected ray cells and occupies only the obstacle"
  ):
    forAll(genCoord, genCoord, genCoord, genCoord): (x0, y0, x1, y1) =>
      val grid = unitGrid()
      grid.observe(x0, y0, x1, y1)
      val cells = for x <- -10 to 10; y <- -10 to 10 yield (x, y, grid.probability(x, y))
      val free = cells.collect { case (x, y, p) if p < 0.5 => (x, y) }
      val occupied = cells.collect { case (x, y, p) if p > 0.5 => (x, y) }
      val unknown = cells.count(_._3 == 0.5)
      val span = math.max(math.abs(x1 - x0), math.abs(y1 - y0))
      // Bresenham advances the major axis by exactly one cell per step, so ordering the ray by distance
      // along that axis recovers the order it was walked in.
      val xMajor = math.abs(x1 - x0) >= math.abs(y1 - y0)
      def along(cell: (Int, Int)): Int =
        if xMajor then math.abs(cell._1 - x0) else math.abs(cell._2 - y0)
      val ray = (free :+ ((x1, y1))).sortBy(along)
      val steps = ray.zip(ray.drop(1))
      (free.size == span) :| s"${free.size} free cells, expected $span" &&
      (occupied == Seq((x1, y1))) :| s"occupied cells $occupied, expected only ($x1, $y1)" &&
      (unknown == 21 * 21 - span - 1) :| "every cell off the ray still reads exactly 0.5" &&
      (ray.head == (x0, y0)) :| s"the ray starts at the sensor, got ${ray.head}" &&
      steps.forall((a, b) => along(b) - along(a) == 1) :| "the major axis advances one cell per step" &&
      steps.forall((a, b) => math.abs(a._1 - b._1) <= 1 && math.abs(a._2 - b._2) <= 1) :| "8-connected"

  test("an obstacle in the sensor's own cell records one hit and no miss"):
    val grid = OccupancyGrid(11, 11, resolution = 1.0)
    grid.observe(0.0, 0.0, 0.0, 0.0)
    // A one-cell ray has no free part to mark, so the cell carries exactly one LogHit (0.85).
    assertEqualsDouble(grid.probability(0.0, 0.0), 1.0 - 1.0 / (1.0 + math.exp(0.85)), 1e-6)

  test("one hit outweighs one miss"):
    val grid = OccupancyGrid(11, 11, resolution = 1.0)
    grid.hit(0.0, 0.0)
    grid.miss(0.0, 0.0)
    // A return is stronger evidence than seeing nothing: LogHit 0.85 - LogMiss 0.4 leaves +0.45.
    assertEqualsDouble(grid.probability(0.0, 0.0), 1.0 - 1.0 / (1.0 + math.exp(0.45)), 1e-6)
    assert(grid.probability(0.0, 0.0) > 0.5)

  test("out-of-bounds readings are ignored but the in-bounds part of a ray is still integrated"):
    val grid = unitGrid()
    grid.hit(1000.0, 0.0) // must not throw
    assertEqualsDouble(grid.probability(1000.0, 0.0), 0.5, 0.0)
    grid.observe(0.0, 0.0, 1000.0, 0.0)
    assert(grid.probability(5.0, 0.0) < 0.5, "a cell inside the grid on the way to the obstacle is free")
    assert(grid.probability(10.0, 0.0) < 0.5, "the last cell before the edge is free")

  test("a 1x1 grid maps the origin to its only cell and renders as a 1x1 image"):
    val one = OccupancyGrid(1, 1)
    assertEquals(one.cellOf(0.0, 0.0), (0, 0))
    one.hit(0.0, 0.0)
    assert(one.probability(0.0, 0.0) > 0.5)
    val img = one.toImage
    try assertEquals((img.width, img.height), (1, 1))
    finally img.close()

  test("cell boundaries round half-up, so +0.5 and -0.5 land in different cells"):
    // Documents Java's Math.round: half rounds toward positive infinity on both sides of zero, so the
    // cells are not mirror images. If symmetric rounding is ever preferred, this is where it shows.
    val grid = unitGrid()
    assertEquals(grid.cellOf(0.5, 0.5), (11, 11))
    assertEquals(grid.cellOf(-0.5, -0.5), (10, 10))

  test("toImage places an off-centre hit at (row = cell y, col = cell x), unknown as 127, free near black"):
    val grid = OccupancyGrid(9, 7, resolution = 1.0)
    for _ <- 0 until 20 do grid.hit(3.0, -2.0) // cell (col 7, row 1)
    for _ <- 0 until 20 do grid.miss(-4.0, 3.0) // cell (col 0, row 6)
    val img = grid.toImage
    try
      // Clamped log-odds ±4.0 render as sigmoid(4) * 255 = 250.4 -> 250 and sigmoid(-4) * 255 = 4.6 -> 4;
      // the 0.5 prior renders as 127.5 -> 127. Under a transposed index the bright pixel lands elsewhere.
      assertEquals(img.mat.get(1, 7)(0), 250.0)
      assert(
        img.mat.get(6, 0)(0) < 10,
        s"a saturated-free cell should be near black, was ${img.mat.get(6, 0)(0)}"
      )
      assertEquals(img.mat.get(0, 0)(0), 127.0)
    finally img.close()

  // -- Navigator: the reactive steering logic (otherwise untested) ---------------------------------

  /** A single-channel disparity image with three flat vertical bands (brighter = nearer), each 0…255. */
  private def disparity(left: Int, centre: Int, right: Int, w: Int = 300, h: Int = 120): Image =
    val third = w / 3
    Image
      .blank(w, h, Scalar(0), channels = 1)
      .drawRects(Seq(Rect(0, 0, third, h)), Scalar(left.toDouble), Thickness.Filled)
      .drawRects(Seq(Rect(third, 0, third, h)), Scalar(centre.toDouble), Thickness.Filled)
      .drawRects(Seq(Rect(third * 2, 0, w - third * 2, h)), Scalar(right.toDouble), Thickness.Filled)

  test("steer reports every band nearness in [0, 1] and clearanceAhead as its complement"):
    val rnd = new scala.util.Random(11)
    for _ <- 0 until 200 do
      val g = disparity(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
      try
        val guide = Navigator.steer(g)
        for n <- Seq(guide.leftNearness, guide.centreNearness, guide.rightNearness, guide.clearanceAhead) do
          assert(n >= 0.0 && n <= 1.0, s"nearness escaped [0,1]: $n")
        assertEqualsDouble(guide.clearanceAhead, 1.0 - guide.centreNearness, 1e-9)
      finally g.close()

  test("a clear path ahead is Straight; a wall dead ahead with clear sides turns"):
    val clear = disparity(0, 0, 0)
    try assertEquals(Navigator.steer(clear).steering, Steering.Straight)
    finally clear.close()
    val wallAhead = disparity(0, 255, 0)
    try assertNotEquals(Navigator.steer(wallAhead).steering, Steering.Straight)
    finally wallAhead.close()

  test("steer turns toward the clearer side"):
    // Obstacle centre, and the right band is nearer than the left → turn Left (toward the clear left).
    val clearLeft = disparity(left = 0, centre = 255, right = 160)
    try assertEquals(Navigator.steer(clearLeft).steering, Steering.Left)
    finally clearLeft.close()
    val clearRight = disparity(left = 160, centre = 255, right = 0)
    try assertEquals(Navigator.steer(clearRight).steering, Steering.Right)
    finally clearRight.close()

  test("boxed in on all three thirds is Stop"):
    val boxed = disparity(255, 255, 255)
    try assertEquals(Navigator.steer(boxed).steering, Steering.Stop)
    finally boxed.close()

  test("steer rejects out-of-range thresholds"):
    val g = disparity(0, 0, 0)
    try
      intercept[IllegalArgumentException](Navigator.steer(g, dangerNearness = 1.5))
      intercept[IllegalArgumentException](Navigator.steer(g, blockedNearness = -0.1))
    finally g.close()

  test("steer breaks a left/right tie to the Right"):
    val tied = disparity(left = 0, centre = 255, right = 0)
    try assertEquals(Navigator.steer(tied).steering, Steering.Right)
    finally tied.close()

  test("steer treats centre nearness exactly at dangerNearness as a threat, not as clear"):
    val atThreshold = disparity(0, 255, 0) // centre nearness is exactly 1.0
    try assertNotEquals(Navigator.steer(atThreshold, dangerNearness = 1.0).steering, Steering.Straight)
    finally atThreshold.close()
    val justUnder = disparity(0, 254, 0) // 254/255 is strictly below 1.0
    try assertEquals(Navigator.steer(justUnder, dangerNearness = 1.0).steering, Steering.Straight)
    finally justUnder.close()

  test("steer gives the columns left over from the thirds to the right band"):
    // Width 4 splits as third = 1: left is column 0, centre column 1, and the right band takes columns 2..3.
    // Only column 3 is bright, so the right band averages 0 and 255 while the other two read nothing.
    val map =
      Image.blank(4, 6, Scalar(0), channels = 1).drawRect(Rect(3, 0, 1, 6), Scalar(255), Thickness.Filled)
    try
      val g = Navigator.steer(map)
      assertEqualsDouble(g.leftNearness, 0.0, 1e-9)
      assertEqualsDouble(g.centreNearness, 0.0, 1e-9)
      assertEqualsDouble(g.rightNearness, 0.5, 1e-9)
      assertEquals(g.steering, Steering.Straight)
    finally map.close()

  // -- Odometry: the stateful pipeline's bookkeeping -----------------------------------------------

  /** A textured scene (seeded, so it is reproducible) whose blocks are shifted by `dx` pixels. */
  private def scene(dx: Int): Image =
    val rnd = new scala.util.Random(3)
    val blocks = Seq.fill(14)(
      Rect(20 + rnd.nextInt(150) + dx, 15 + rnd.nextInt(120), 16 + rnd.nextInt(14), 16 + rnd.nextInt(14))
    )
    Image.blank(220, 180, Scalar(25, 25, 25)).drawRects(blocks, Scalar.White, Thickness.Filled)

  test("the first frame is a reference (None) and framesProcessed counts every update"):
    val odo = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 110, cy = 90))
    try
      val f0 = scene(0)
      try
        assertEquals(odo.update(f0), None, "the first frame sets the reference and yields no motion")
        assertEquals(odo.framesProcessed, 1)
      finally f0.close()
      for i <- 1 to 3 do
        val f = scene(i * 3)
        try
          val motion = odo.update(f) // may or may not converge; must not throw
          motion.foreach(m => assert(m.translation.forall(_.isFinite), s"non-finite translation: $m"))
        finally f.close()
      assertEquals(odo.framesProcessed, 4)
    finally odo.close()

  test("close is idempotent"):
    val odo = Odometry.monocular(Intrinsics(fx = 400, fy = 400, cx = 50, cy = 50))
    val f = scene(0)
    try odo.update(f)
    finally f.close()
    odo.close()
    odo.close() // a second close must be a no-op, not a crash

  test("framesProcessed does not count a failed update"):
    // The second update is handed an already-consumed frame: the track/snapshot step touches its Mat and
    // throws before any work completes. A counter bumped up front would count the frame anyway.
    val odo = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 110, cy = 90))
    try
      val f0 = scene(0)
      try
        odo.update(f0)
        assertEquals(odo.framesProcessed, 1)
      finally f0.close()
      val spent = scene(3)
      spent.close()
      intercept[IllegalStateException](odo.update(spent))
      assertEquals(odo.framesProcessed, 1, "a throwing update is not a processed frame")
      // The pipeline itself must survive the failed frame: the next good update works and counts.
      val f1 = scene(6)
      try odo.update(f1)
      finally f1.close()
      assertEquals(odo.framesProcessed, 2)
    finally odo.close()

  // -- LoopDetector: detect vs. add, and the exclusion window --------------------------------------

  private def place(seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    val blocks = Seq.fill(8)(
      Rect(10 + rnd.nextInt(170), 10 + rnd.nextInt(130), 18 + rnd.nextInt(16), 18 + rnd.nextInt(16))
    )
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(blocks, Scalar.White, Thickness.Filled)

  test("detect never mutates the keyframe store; process always appends exactly one"):
    val d = LoopDetector(minMatches = 25, recentExclusion = 2)
    try
      val probe = place(1)
      try
        assert(d.detect(probe).isEmpty, "detect on an empty store is None")
        assertEquals(d.keyframeCount, 0, "detect must not add a keyframe")
      finally probe.close()
      for s <- 1 to 5 do
        val img = place(s)
        try d.process(img)
        finally img.close()
      assertEquals(d.keyframeCount, 5, "process appends exactly one keyframe per call")
    finally d.close()

  test("no loop is reported while the store is inside the exclusion window"):
    val d = LoopDetector(minMatches = 20, recentExclusion = 5)
    try
      for s <- 1 to 4 do // fewer keyframes than recentExclusion → nothing is searchable
        val img = place(s)
        try
          assert(d.process(img).isEmpty, "with keyframeCount ≤ recentExclusion, detect is always None")
        finally img.close()
    finally d.close()

  test(
    "a revisit of the oldest place is reported, and its score is a fraction in (0, 1] that clears minMatches"
  ):
    val d = LoopDetector(minMatches = 20, recentExclusion = 1)
    try
      for s <- 1 to 5 do
        val img = place(s)
        try d.process(img)
        finally img.close()
      val revisit = place(1) // old enough to be searchable
      try
        d.detect(revisit) match
          case None =>
            fail(
              "place 1 at index 0 is byte-identical and searchable (5 keyframes, exclusion 1), so a loop must be reported"
            )
          case Some(loop) =>
            assertEquals(loop.keyframe, 0, s"the loop must close to the identical keyframe, got $loop")
            assert(loop.matches >= 20, s"a reported loop must clear minMatches, got ${loop.matches}")
            assert(loop.score > 0.0 && loop.score <= 1.0, s"score must be in (0,1], got ${loop.score}")
      finally revisit.close()
    finally d.close()

  test("maxKeyframes bounds the live count by evicting the oldest, keeping later indices valid"):
    val d = LoopDetector(minMatches = 25, recentExclusion = 1, maxKeyframes = 3)
    try
      val indices =
        for s <- 1 to 8 yield
          val img = place(s)
          try d.addKeyframe(img)
          finally img.close()
      // Eight appended, only three kept live — the rest were evicted and their descriptors freed.
      assertEquals(d.keyframeCount, 3, "live keyframes must be capped at maxKeyframes")
      // Indices stayed stable and monotonic (no renumbering of survivors on eviction).
      assertEquals(indices.toList, (0 to 7).toList, "append indices must remain absolute and stable")
      // The store still works: detecting a recent place must not crash on the evicted (tombstoned) slots.
      val probe = place(8)
      try d.detect(probe): Unit // no NoSuchElement / null deref over tombstones
      finally probe.close()
    finally d.close()

  test("a keyframe that survived eviction is still searchable, and an evicted one is never reported"):
    // The eviction boundary is remembered rather than rediscovered, so the way to get this wrong is to
    // advance it past a keyframe that is still live: the store would silently stop matching its oldest
    // survivors, and every existing test would still pass because none of them re-shows a survivor.
    val d = LoopDetector(minMatches = 20, recentExclusion = 1, maxKeyframes = 3)
    try
      for s <- 1 to 8 do
        val img = place(s)
        try d.addKeyframe(img): Unit
        finally img.close()
      // Eight appended, three kept: indices 0-4 are tombstones, 5-7 are live. recentExclusion = 1 makes
      // 0-6 searchable, so index 5 is both live and searchable — the oldest survivor.
      assertEquals(d.keyframeCount, 3)
      val survivor = place(6) // the place stored at index 5
      try
        d.detect(survivor) match
          case None => fail("re-showing an identical surviving place must still report a loop")
          case Some(loop) =>
            assert(loop.keyframe >= 5, s"a reported keyframe must be a live one (>= 5), got ${loop.keyframe}")
            assert(loop.keyframe <= 6, s"a reported keyframe must be searchable (<= 6), got ${loop.keyframe}")
      finally survivor.close()
    finally d.close()

  test("close is idempotent and clears the store"):
    val d = LoopDetector()
    val img = place(1)
    try d.addKeyframe(img)
    finally img.close()
    d.close()
    assertEquals(d.keyframeCount, 0)
    d.close() // no crash on a second close
