package scalacv.vision

import org.opencv.core.{CvType, Mat}

import scalacv.*

/** A 2D occupancy grid — a top-down map of free vs. occupied space, accumulated from range/obstacle
  * observations over time.
  *
  * This is the map [[Navigator]]'s reflex lacks and a planner needs. Each cell holds a **log-odds** estimate
  * that it is occupied: an obstacle reading nudges a cell toward occupied, seeing through empty space nudges
  * the cells along the way toward free, and repeated evidence accumulates and clamps. World coordinates (in
  * metres, say) are quantised to cells by `resolution`, with the grid centred on the origin.
  *
  * Feed it from stereo/obstacle readings — a robot at a known pose turns each [[Obstacle]] into a ray via
  * [[observe]]. It is a plain in-memory structure (no native memory); [[toImage]] renders it for viewing.
  */
final class OccupancyGrid private (val cols: Int, val rows: Int, val resolution: Double):

  import OccupancyGrid.{Clamp, LogHit, LogMiss}

  private val logOdds = Array.fill(cols * rows)(0.0)

  /** The `(column, row)` cell containing world point `(x, y)`, rounded to nearest (ties toward +infinity).
    * The grid is centred on the origin. Nonfinite or unrepresentable Int coordinates are rejected.
    */
  def cellOf(x: Double, y: Double): (Int, Int) =
    def checked(value: Double, offset: Int): Int =
      require(value.isFinite && (value / resolution).isFinite, "grid coordinates must be finite")
      val rounded = math.round(value / resolution).toDouble + offset
      require(rounded >= Int.MinValue && rounded <= Int.MaxValue, "grid coordinate exceeds Int range")
      rounded.toInt
    (checked(x, cols / 2), checked(y, rows / 2))

  /** Records an obstacle (a "hit") at world `(x, y)`. */
  def hit(x: Double, y: Double): Unit =
    val (cx, cy) = cellOf(x, y)
    bump(cx, cy, LogHit)

  /** Records free space (a "miss") at world `(x, y)`. */
  def miss(x: Double, y: Double): Unit =
    val (cx, cy) = cellOf(x, y)
    bump(cx, cy, -LogMiss)

  /** Integrates one range reading: the cells along the ray from the sensor at `(fromX, fromY)` to the
    * obstacle at `(obstacleX, obstacleY)` are marked free, and an in-grid obstacle cell is occupied. Clips
    * before incremental traversal, so work is bounded by grid size. An outside obstacle never turns the
    * clipped boundary into a hit. Nonfinite inputs or division overflow are rejected.
    */
  def observe(fromX: Double, fromY: Double, obstacleX: Double, obstacleY: Double): Unit =
    require(Seq(fromX, fromY, obstacleX, obstacleY).forall(_.isFinite), "ray coordinates must be finite")
    import java.math.{BigDecimal as Decimal, MathContext}
    def coordinate(value: Double, offset: Int): Decimal =
      val scaled = value / resolution
      require(scaled.isFinite, "ray exceeds finite grid coordinate range")
      val rounded =
        if scaled >= Long.MinValue.toDouble && scaled < Long.MaxValue.toDouble then
          Decimal.valueOf(math.round(scaled))
        else Decimal.valueOf(scaled) // beyond Long range a finite Double has no fractional component
      rounded.add(Decimal.valueOf(offset.toLong))
    val x0 = coordinate(fromX, cols / 2)
    val y0 = coordinate(fromY, rows / 2)
    val x1 = coordinate(obstacleX, cols / 2)
    val y1 = coordinate(obstacleY, rows / 2)
    val dx = x1.subtract(x0)
    val dy = y1.subtract(y0)
    // Keep clipping parameters as exact fractions. Double interpolation collapses both endpoints
    // onto the same cell for very distant rays (e.g. +/-1e100); only bounded endpoints are rounded.
    type Fraction = (Decimal, Decimal)
    var enter: Fraction = (Decimal.ZERO, Decimal.ONE)
    var leave: Fraction = (Decimal.ONE, Decimal.ONE)
    def compare(a: Fraction, b: Fraction): Int = a._1.multiply(b._2).compareTo(b._1.multiply(a._2))
    def clip(p: Decimal, q: Decimal): Boolean =
      if p.signum() == 0 then q.signum() >= 0
      else
        val t = if p.signum() < 0 then (q.negate(), p.negate()) else (q, p)
        if p.signum() < 0 then
          if compare(t, enter) > 0 then enter = t
        else if compare(t, leave) < 0 then leave = t
        compare(enter, leave) <= 0
    val maxX = Decimal.valueOf(cols.toLong - 1)
    val maxY = Decimal.valueOf(rows.toLong - 1)
    if clip(dx.negate(), x0) && clip(dx, maxX.subtract(x0)) &&
      clip(dy.negate(), y0) && clip(dy, maxY.subtract(y0))
    then
      def endpoint(origin: Decimal, delta: Decimal, t: Fraction, max: Int): Int =
        val v =
          origin.multiply(t._2).add(delta.multiply(t._1)).divide(t._2, MathContext.DECIMAL128).doubleValue()
        math.max(0L, math.min(max.toLong, math.round(v))).toInt
      val ax = endpoint(x0, dx, enter, cols - 1)
      val ay = endpoint(y0, dy, enter, rows - 1)
      val bx = endpoint(x0, dx, leave, cols - 1)
      val by = endpoint(y0, dy, leave, rows - 1)
      val hitInside =
        x1.signum() >= 0 && x1.compareTo(maxX) <= 0 && y1.signum() >= 0 && y1.compareTo(maxY) <= 0
      bresenham(ax, ay, bx, by): (cx, cy) =>
        bump(cx, cy, if hitInside && cx == bx && cy == by then LogHit else -LogMiss)

  /** Occupancy probability in `[0, 1]` at world `(x, y)` — `0.5` for an unobserved or out-of-bounds cell. */
  def probability(x: Double, y: Double): Double =
    val (cx, cy) = cellOf(x, y)
    if !inBounds(cx, cy) then 0.5 else sigmoid(logOdds(cy * cols + cx))

  /** Whether world `(x, y)` is believed occupied at or above `threshold`. */
  def isOccupied(x: Double, y: Double, threshold: Double = 0.5): Boolean = probability(x, y) >= threshold

  /** Renders the grid as a grayscale [[Image]]: occupied → white, free → black, unknown → mid-grey. */
  def toImage: Image =
    // The pixels are built before the Mat, not after. Between a raw `Mat(...)` and the `Managed` that
    // adopts it there is no owner, so anything thrown in that window strands a native buffer — and
    // allocating a rows×cols JVM array is exactly the kind of thing that throws (OutOfMemoryError on a
    // large grid). Filling first leaves no window at all, which is cheaper than the try/catch that
    // `Interop.toMat` needs for the same hazard where the order cannot be swapped.
    val bytes = new Array[Byte](rows * cols)
    var i = 0
    while i < bytes.length do
      bytes(i) = (sigmoid(logOdds(i)) * 255).toByte
      i += 1
    val mat = Mat(rows, cols, CvType.CV_8UC1)
    val handle = Managed(mat)
    try
      mat.put(0, 0, bytes): Unit
      Image.wrap(handle)
    catch
      case e: Throwable =>
        handle.release()
        throw e

  private def sigmoid(l: Double): Double = 1.0 - 1.0 / (1.0 + math.exp(l))

  private def inBounds(cx: Int, cy: Int): Boolean = cx >= 0 && cx < cols && cy >= 0 && cy < rows

  private def bump(cx: Int, cy: Int, delta: Double): Unit =
    if inBounds(cx, cy) then
      val i = cy * cols + cx
      logOdds(i) = math.max(-Clamp, math.min(Clamp, logOdds(i) + delta))

  /** Integer Bresenham line — the cells a ray passes through, endpoints included. */
  private def bresenham(x0: Int, y0: Int, x1: Int, y1: Int)(visit: (Int, Int) => Unit): Unit =
    var x = x0
    var y = y0
    val dx = math.abs(x1 - x0)
    val dy = -math.abs(y1 - y0)
    val sx = if x0 < x1 then 1 else -1
    val sy = if y0 < y1 then 1 else -1
    var err = dx.toLong + dy
    var going = true
    while going do
      visit(x, y)
      if x == x1 && y == y1 then going = false
      else
        val e2 = 2 * err
        if e2 >= dy then
          err += dy
          x += sx
        if e2 <= dx then
          err += dx
          y += sy

object OccupancyGrid:

  /** The log-odds a single obstacle reading adds to a cell. */
  private val LogHit = 0.85

  /** The log-odds a single see-through reading subtracts from a cell. Smaller than [[LogHit]] on purpose:
    * seeing nothing is weaker evidence of free space than a return is of an obstacle.
    */
  private val LogMiss = 0.4

  /** The bound each cell's log-odds is clamped to, so a long run of identical readings cannot saturate a cell
    * beyond what a few contrary ones can undo.
    */
  private val Clamp = 4.0

  /** A `cols`×`rows` grid, each cell `resolution` world-units square, centred on the origin. */
  def apply(cols: Int, rows: Int, resolution: Double = 0.05): OccupancyGrid =
    require(cols > 0 && rows > 0, s"a grid needs positive dimensions, got ${cols}x$rows")
    // cols*rows sizes the backing arrays; check it fits an Int before it silently overflows to a
    // negative size and throws a bare NegativeArraySizeException instead of this named error.
    require(
      cols.toLong * rows <= Int.MaxValue,
      s"grid ${cols}x$rows has too many cells (${cols.toLong * rows}) to address"
    )
    require(resolution.isFinite && resolution > 0, s"resolution must be positive, got $resolution")
    new OccupancyGrid(cols, rows, resolution)
