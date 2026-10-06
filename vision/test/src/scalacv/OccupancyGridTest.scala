package scalacv

import scalacv.vision.*

/** Unit tests for [[OccupancyGrid]] — the log-odds mapping and the Bresenham ray integration, which are pure
  * in-memory arithmetic. `toImage` is the only part that touches native memory, so `OpenCv.load()` runs once.
  */
class OccupancyGridTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("construction rejects non-positive dimensions and resolution"):
    intercept[IllegalArgumentException](OccupancyGrid(0, 10))
    intercept[IllegalArgumentException](OccupancyGrid(10, -1))
    intercept[IllegalArgumentException](OccupancyGrid(10, 10, resolution = 0.0))

  test("construction rejects a cell count that overflows Int, with a typed error not a raw crash"):
    // cols*rows here is ~3.4e9, past Int.MaxValue: without the Long-checked require it would wrap
    // negative and throw a bare NegativeArraySizeException from Array.fill.
    intercept[IllegalArgumentException](OccupancyGrid(60000, 60000))

  test("the grid is centred on the origin and quantises by resolution"):
    val g = OccupancyGrid(21, 21, resolution = 1.0)
    assertEquals(g.cellOf(0.0, 0.0), (10, 10))
    assertEquals(g.cellOf(1.0, 0.0), (11, 10))
    assertEquals(g.cellOf(-2.0, 3.0), (8, 13))
    // Sub-resolution offsets round to the nearest cell.
    assertEquals(g.cellOf(0.4, -0.4), (10, 10))

  test("an unobserved or out-of-bounds cell reads 0.5"):
    val g = OccupancyGrid(11, 11, resolution = 1.0)
    assertEqualsDouble(g.probability(0.0, 0.0), 0.5, 1e-9)
    // Far outside the 11x11 grid (which spans roughly [-5, 5] cells).
    assertEqualsDouble(g.probability(1000.0, 0.0), 0.5, 1e-9)

  test("hit pushes a cell toward occupied, miss toward free"):
    val g = OccupancyGrid(11, 11, resolution = 1.0)
    g.hit(0.0, 0.0)
    assert(g.probability(0.0, 0.0) > 0.5, "a hit should raise occupancy above 0.5")
    val h = OccupancyGrid(11, 11, resolution = 1.0)
    h.miss(0.0, 0.0)
    assert(h.probability(0.0, 0.0) < 0.5, "a miss should lower occupancy below 0.5")

  test("isOccupied honours the threshold"):
    val g = OccupancyGrid(11, 11, resolution = 1.0)
    g.hit(0.0, 0.0)
    val p = g.probability(0.0, 0.0)
    assert(g.isOccupied(0.0, 0.0)) // default threshold 0.5
    assert(g.isOccupied(0.0, 0.0, threshold = p - 0.01))
    assert(!g.isOccupied(0.0, 0.0, threshold = p + 0.01))

  test("observe marks the obstacle cell occupied and the ray to it free"):
    val g = OccupancyGrid(21, 21, resolution = 1.0)
    // Sensor at the origin sees an obstacle 3 cells to the right along y=0.
    g.observe(0.0, 0.0, 3.0, 0.0)
    assert(g.probability(3.0, 0.0) > 0.5, "the obstacle cell should be occupied")
    assert(g.probability(1.0, 0.0) < 0.5, "a cell along the ray should be free")
    assert(g.probability(0.0, 0.0) < 0.5, "the sensor cell is on the free part of the ray")

  test("repeated hits accumulate but clamp below certainty"):
    val g = OccupancyGrid(11, 11, resolution = 1.0)
    val once =
      g.hit(0.0, 0.0)
      g.probability(0.0, 0.0)
    for _ <- 0 until 50 do g.hit(0.0, 0.0)
    val saturated = g.probability(0.0, 0.0)
    assert(saturated > once, "more evidence should raise the estimate")
    assert(saturated > 0.9, s"it should approach occupied, got $saturated")
    assert(saturated < 1.0, s"log-odds clamping must keep it short of certainty, got $saturated")

  test("toImage renders a cols x rows single-channel map, bright where occupied"):
    val g = OccupancyGrid(9, 7, resolution = 1.0)
    for _ <- 0 until 20 do g.hit(0.0, 0.0) // saturate the centre cell
    val img = g.toImage
    try
      assertEquals((img.width, img.height, img.channels), (9, 7, 1))
      // The saturated centre cell should render near white, an untouched corner near mid-grey.
      val centre = img.mat.get(3, 4)(0) // (row 3, col 4) is the centre of a 9x7 grid
      assert(centre > 200, s"occupied centre should be bright, was $centre")
    finally img.close()
