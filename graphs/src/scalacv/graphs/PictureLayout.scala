package scalacv.graphs

import scalacv.*

/** Backend-controlled text measurement. The result includes the descender baseline, as in [[Draw.textSize]].
  * Measurement deliberately uses the historical one-pixel glyph metrics, independently of stroke width.
  */
trait TextMeasurer:
  def measure(text: String, font: Font, scale: Double): TextMetrics

object TextMeasurer:
  /** The existing Hershey metrics. Native loading occurs only when text is actually measured. */
  def openCv: TextMeasurer = OpenCvRenderer.textMeasurer

/** Geometry and layout with explicitly chosen text metrics. Shape-only measurement never calls the measurer;
  * text remains axis-aligned at its transformed anchor. Bounds describe geometry, not ink.
  */
final class PictureLayout(val textMeasurer: TextMeasurer):
  def bounds(picture: Picture): Option[Bounds] =
    var result: Option[Bounds] = None
    PictureTraversal.foreach(picture) { (prim, style) =>
      result = union(result, primBounds(prim, style))
    }
    result

  /** Places `that` to the right of `first`, with centres aligned vertically. */
  def beside(first: Picture, that: Picture, gap: Double = 8): Picture =
    (bounds(first), bounds(that)) match
      case (Some(a), Some(b)) =>
        Picture.all(Seq(first, that.translate(a.maxX + gap - b.minX, a.centerY - b.centerY)))
      case (Some(_), None) => first
      case (None, _) => that

  /** Places `that` below `first`, with centres aligned horizontally. */
  def above(first: Picture, that: Picture, gap: Double = 8): Picture =
    (bounds(first), bounds(that)) match
      case (Some(a), Some(b)) =>
        Picture.all(Seq(first, that.translate(a.centerX - b.centerX, a.maxY + gap - b.minY)))
      case (Some(_), None) => first
      case (None, _) => that

  /** Tight horizontal packing with the same alignment and painter's order as a left fold of [[beside]]. Each
    * input is measured at most twice, without rescanning the placed prefix. Empty inputs take no space; the
    * first non-empty input keeps its position. Metrics must remain stable during this call. No bounds are
    * retained after layout.
    */
  def row(pictures: Seq[Picture], gap: Double = 8): Picture = pack(pictures, gap, horizontal = true)

  /** Tight vertical packing, like a left fold of [[above]], measuring each input at most twice without
    * rescanning the placed prefix. Empty inputs take no space; the first non-empty input keeps its position.
    * Metrics must remain stable during this call. No bounds are retained after layout.
    */
  def column(pictures: Seq[Picture], gap: Double = 8): Picture = pack(pictures, gap, horizontal = false)

  private def pack(pictures: Seq[Picture], gap: Double, horizontal: Boolean): Picture =
    val placed = Vector.newBuilder[Picture]
    var accumulated: Option[Bounds] = None
    pictures.foreach { picture =>
      bounds(picture).foreach { b =>
        accumulated match
          case None =>
            placed += picture
            accumulated = Some(b)
          case Some(a) =>
            val dx = if horizontal then a.maxX + gap - b.minX else a.centerX - b.centerX
            val dy = if horizontal then a.centerY - b.centerY else a.maxY + gap - b.minY
            val translated = picture.translate(dx, dy)
            placed += translated
            // Measure in the actual transform context: translating an existing box changes floating-point
            // association (e.g. (center + dx) + radius versus (center + radius) + dx).
            accumulated = union(accumulated, bounds(translated))
      }
    }
    Picture.all(placed.result())

  /** Uniform cells based on the largest measured picture. Empty pictures retain their cell. */
  def grid(pictures: Seq[Picture], columns: Int, gap: Double = 8): Picture =
    require(columns >= 1, s"a grid needs at least one column, got $columns")
    val measured = pictures.map(bounds)
    val cellW = measured.flatten.map(_.width).maxOption.getOrElse(0.0) + gap
    val cellH = measured.flatten.map(_.height).maxOption.getOrElse(0.0) + gap
    Picture.all(pictures.zip(measured).zipWithIndex.map { case ((p, measuredBounds), i) =>
      measuredBounds match
        case Some(b) => p.translate((i % columns) * cellW - b.minX, (i / columns) * cellH - b.minY)
        case None => p
    })

  /** A text label boxed using this backend's metrics, including descenders and padding. */
  def label(
      text: String,
      at: Point,
      textColor: Color = Color.White,
      background: Color = Color.Black,
      padding: Int = 4,
      fontScale: Double = 0.5,
      font: Font = Font.Simplex
  ): Picture =
    val m = textMeasurer.measure(text, font, fontScale)
    val w = m.size.width.round.toInt + 2 * padding
    val h = m.size.height.round.toInt + m.baseline + 2 * padding
    val box = Rect(at.x.round.toInt, at.y.round.toInt, w, h)
    val baseline = Point(at.x + padding, at.y + padding + m.size.height)
    Picture
      .text(text, baseline)
      .strokeColor(textColor)
      .fontScale(fontScale)
      .font(font)
      .on(Picture.rectangle(box).fillColor(background).noStroke)

  private def union(a: Option[Bounds], b: Option[Bounds]): Option[Bounds] = (a, b) match
    case (Some(x), Some(y)) => Some(x.union(y))
    case (some, None) => some
    case (None, some) => some

  /** Geometric extent: empty paths have no bounds; stroke and antialiasing padding belongs to the backend. */
  private def primBounds(prim: RenderPrimitive, style: PictureStyle): Option[Bounds] =
    import RenderPrimitive.*
    prim match
      case Circle(c, r) =>
        extentOf(Seq(Point(c.x - r, c.y - r), Point(c.x + r, c.y + r)))
      case Path(points, _) => extentOf(points)
      case Text(txt, p) =>
        val m = textMeasurer.measure(txt, style.font, style.fontScale)
        extentOf(Seq(Point(p.x, p.y - m.size.height), Point(p.x + m.size.width, p.y + m.baseline)))

  private def extentOf(points: Seq[Point]): Option[Bounds] =
    if points.isEmpty then None
    else
      require(points.forall(p => p.x.isFinite && p.y.isFinite), "bounds must be finite")
      val xs = points.map(_.x)
      val ys = points.map(_.y)
      Some(Bounds(xs.min, ys.min, xs.max, ys.max))

object PictureLayout:
  /** Convenience layout using the existing OpenCV metrics, only for text-bearing scenes. */
  val openCv: PictureLayout = new PictureLayout(TextMeasurer.openCv)
