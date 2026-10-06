package scalacv

import scalacv.vision.*

/** The SLAM mapping helpers: loop-closure detection and the occupancy grid. */
class MappingTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A distinct textured "place" per seed — a deterministic random block layout, so the same seed renders the
    * same scene (a revisit) and different seeds render different ones.
    */
  private def place(seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    val blocks = Seq.fill(7)(
      Rect(10 + rnd.nextInt(170), 10 + rnd.nextInt(130), 18 + rnd.nextInt(16), 18 + rnd.nextInt(16))
    )
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(blocks, Scalar.White, Thickness.Filled)

  test("loop detector recognises a revisited place and ignores a novel one"):
    val detector = LoopDetector(minMatches = 25, recentExclusion = 2)
    try
      for s <- 1 to 6 do
        val img = place(s)
        try detector.process(img)
        finally img.close()
      assertEquals(detector.keyframeCount, 6)

      // Revisit place 1 (keyframe 0, old enough not to be excluded).
      val revisit = place(1)
      try
        detector.detect(revisit) match
          case Some(loop) => assertEquals(loop.keyframe, 0, s"should close the loop to place 1, got $loop")
          case None => fail("a revisit of place 1 should be detected")
      finally revisit.close()

      // A place never seen before is not a loop.
      val novel = place(999)
      try assert(detector.detect(novel).isEmpty, "a novel place must not register a loop")
      finally novel.close()
    finally detector.close()

  test("loop detector excludes the most recent keyframes"):
    val detector = LoopDetector(minMatches = 25, recentExclusion = 5)
    try
      val img = place(1)
      try detector.process(img) // one keyframe, which is "recent" and excluded
      finally img.close()
      val again = place(1)
      try assert(detector.detect(again).isEmpty, "the just-added keyframe is excluded from loop search")
      finally again.close()
    finally detector.close()

  // -- Occupancy grid ------------------------------------------------------------------------------

  test("cellOf centres the grid on the origin"):
    val grid = OccupancyGrid(cols = 100, rows = 100, resolution = 0.1)
    assertEquals(grid.cellOf(0.0, 0.0), (50, 50))
    assertEquals(grid.cellOf(1.0, 0.0), (60, 50)) // 1.0m / 0.1 = 10 cells right of centre

  test("an unobserved cell is 0.5; hits raise it, misses lower it"):
    val grid = OccupancyGrid(cols = 100, rows = 100, resolution = 0.1)
    assertEqualsDouble(grid.probability(3.0, 3.0), 0.5, 1e-9)
    for _ <- 0 until 5 do grid.hit(2.0, 1.0)
    assert(
      grid.probability(2.0, 1.0) > 0.5 && grid.isOccupied(2.0, 1.0),
      s"hit cell prob ${grid.probability(2.0, 1.0)}"
    )
    for _ <- 0 until 5 do grid.miss(-2.0, -1.0)
    assert(
      grid.probability(-2.0, -1.0) < 0.5 && !grid.isOccupied(-2.0, -1.0),
      s"miss cell prob ${grid.probability(-2.0, -1.0)}"
    )

  test("observe marks the ray free and the endpoint occupied"):
    val grid = OccupancyGrid(cols = 100, rows = 100, resolution = 0.1)
    grid.observe(0.0, 0.0, 3.0, 0.0) // sensor at origin, obstacle 3m ahead
    assert(grid.isOccupied(3.0, 0.0), "the obstacle endpoint should be occupied")
    assert(!grid.isOccupied(1.5, 0.0), "the space along the ray should be free")

  test("toImage renders a grid-sized grayscale map"):
    val grid = OccupancyGrid(cols = 80, rows = 60, resolution = 0.1)
    grid.hit(1.0, 1.0)
    val img = grid.toImage
    try assertEquals((img.width, img.height, img.channels), (80, 60, 1))
    finally img.close()
