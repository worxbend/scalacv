package scalacv.graphs

import scalacv.*

/** Minimal data visualisation, built from [[Picture]] — proof that the graphics layer composes into charts,
  * and a handy way to overlay a plot on an image (a histogram beside a detection, a signal on a frame).
  *
  * Each returns a `Picture` sized to a `width`×`height` box with its origin at the top-left, so it composes
  * and transforms like any other picture: `Chart.bars(counts, 200, 80).at(Point(10, 10))` drops a chart into
  * a corner.
  */
object Chart:

  /** The drawing box every chart needs. A non-positive box yields degenerate `Rect`s and divide-by-span
    * artifacts rather than a picture, so it is a precondition violation — thrown, like the other graphics
    * value types validate their invariants — not a silently empty chart.
    */
  private def requireBox(width: Int, height: Int): Unit =
    require(width > 0 && height > 0, s"a chart needs a positive box, got ${width}x$height")

  /** A bottom-aligned bar chart of `values` (scaled to the tallest).
    *
    * **Magnitude semantics:** a value is plotted as `math.abs(v)` — `-5` draws the very bar `5` draws, rising
    * from the bottom edge. There is no zero line and no downward bar; if the sign of your data carries
    * meaning, split the series or re-base it yourself before charting.
    */
  def bars(values: Seq[Double], width: Int, height: Int, color: Color = Color.Blue, gap: Int = 4): Picture =
    requireBox(width, height)
    if values.isEmpty then Picture.empty
    else
      val peak = values.map(math.abs).max.max(1e-9)
      val n = values.size
      val barWidth = math.max(1, (width - gap * (n + 1)) / n)
      Picture.all(values.zipWithIndex.map { (v, i) =>
        val h = (math.abs(v) / peak * (height - 2)).round.toInt
        val x = gap + i * (barWidth + gap)
        Picture.rectangle(Rect(x, height - h, barWidth, h)).fillColor(color).noStroke
      })

  /** The polyline every series chart is drawn from: `values` spread evenly across the width, their magnitudes
    * scaled against the largest one so the tallest point sits at the top of the box (the 2px inset keeps that
    * point's stroke inside the box rather than clipped by its edge).
    *
    * [[line]] and [[area]] must agree on this shape down to the pixel — `area` strokes the very same polyline
    * on top of its own fill — so they share one definition. Were each to derive it, a later change to the
    * scaling on one side would let a fill drift away from its own outline, with nothing to catch it.
    *
    * Callers are responsible for the `values.sizeIs < 2` guard: with fewer than two points the
    * `values.size - 1` divisor is zero, and there is no line to draw anyway.
    */
  private def seriesPoints(values: Seq[Double], width: Int, height: Int): Seq[Point] =
    val peak = values.map(math.abs).max.max(1e-9)
    values.zipWithIndex.map { (v, i) =>
      Point(i.toDouble / (values.size - 1) * width, height - math.abs(v) / peak * (height - 2))
    }

  /** A line chart of `values` across the width (scaled to the largest magnitude).
    *
    * **Magnitude semantics:** points sit at `math.abs(v)` — a signed series traces the same polyline as its
    * absolute values, measured up from the bottom edge. The sign is not plotted; chart a re-based series if
    * you need it.
    */
  def line(
      values: Seq[Double],
      width: Int,
      height: Int,
      color: Color = Color.Green,
      strokeWidth: Int = 2
  ): Picture =
    requireBox(width, height)
    if values.sizeIs < 2 then Picture.empty
    else
      val points = seriesPoints(values, width, height)
      Picture.polyline(points).strokeColor(color).strokeWidth(strokeWidth)

  /** A scatter plot of `(x, y)` data, its range mapped into the box. */
  def scatter(
      points: Seq[(Double, Double)],
      width: Int,
      height: Int,
      color: Color = Color.Red,
      radius: Double = 3
  ): Picture =
    requireBox(width, height)
    if points.isEmpty then Picture.empty
    else
      val xs = points.map(_._1)
      val ys = points.map(_._2)
      val (minX, maxX) = (xs.min, xs.max)
      val (minY, maxY) = (ys.min, ys.max)
      def sx(x: Double): Double =
        if maxX == minX then width / 2.0 else (x - minX) / (maxX - minX) * (width - 2 * radius) + radius
      def sy(y: Double): Double = if maxY == minY then height / 2.0
      else height - ((y - minY) / (maxY - minY) * (height - 2 * radius) + radius)
      Picture.all(points.map((x, y) => Picture.marker(Point(sx(x), sy(y)), color, radius)))

  /** A filled area chart of `values` across the width — a [[line]] closed down to the baseline. Shares
    * [[line]]'s **magnitude semantics**: negative values fill as their absolute values, up from the baseline.
    */
  def area(
      values: Seq[Double],
      width: Int,
      height: Int,
      color: Color = Color.Blue,
      strokeWidth: Int = 2
  ): Picture =
    requireBox(width, height)
    if values.sizeIs < 2 then Picture.empty
    else
      val top = seriesPoints(values, width, height)
      val filled = (Point(0, height.toDouble) +: top) :+ Point(width.toDouble, height.toDouble)
      Picture
        .polyline(top)
        .strokeColor(color)
        .strokeWidth(strokeWidth)
        .under(Picture.polygon(filled).fillColor(color.fadeOut(0.7)).noStroke)

  /** A pie chart of `values` (their proportions), coloured from `palette` and cycling it if short.
    *
    * **Magnitude semantics:** each slice's share is `math.abs(v)` over the summed magnitudes — a negative
    * value buys the same wedge as its absolute value, never a negative or missing slice.
    */
  def pie(
      values: Seq[Double],
      width: Int,
      height: Int,
      palette: Seq[Color] = Color.categorical
  ): Picture =
    requireBox(width, height)
    val positive = values.map(math.abs)
    val total = positive.sum
    if total <= 0 || palette.isEmpty then Picture.empty
    else
      val center = Point(width / 2.0, height / 2.0)
      val radius = math.min(width, height) / 2.0 - 2
      // Each slice's start angle is the running total of the ones before it, so `scanLeft` states it
      // directly. The previous version carried a `var angle` that the mapping function advanced as a side
      // effect — correct only as long as `values` is a strict, singly-traversed, in-order collection, which
      // the signature (`Seq`) does not promise: hand it a `LazyList` and the angles come out wrong or
      // duplicated, silently, as a wrong-looking chart rather than an error.
      val starts = positive.scanLeft(-90.0)((angle, v) => angle + v / total * 360) // -90 = 12 o'clock
      Picture.all(positive.zip(starts).zipWithIndex.map { case ((v, start), i) =>
        Picture
          .sector(center, radius, radius, start, start + v / total * 360)
          .fillColor(palette(i % palette.size))
          .noStroke
      })

  /** A histogram: bins `data` into `bins` equal-width buckets across its range, then draws the counts as
    * [[bars]]. The one call for "what does this distribution look like".
    */
  def histogram(
      data: Seq[Double],
      bins: Int,
      width: Int,
      height: Int,
      color: Color = Color.Purple
  ): Picture =
    require(bins >= 1, s"a histogram needs at least one bin, got $bins")
    requireBox(width, height)
    if data.isEmpty then Picture.empty
    else
      val lo = data.min
      val hi = data.max
      val span = hi - lo
      val counts = Array.fill(bins)(0.0)
      data.foreach { v =>
        val idx = if span <= 0 then 0 else math.min(bins - 1, ((v - lo) / span * bins).toInt)
        counts(idx) += 1
      }
      bars(counts.toSeq, width, height, color, gap = 1)
