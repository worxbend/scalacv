package scalacv.graphs

import org.opencv.core.{Core, Mat, MatOfPoint}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** The OpenCV image backend. Rendering consumes the input Image exactly as `image.draw` does; the returned
  * Image is owned by the caller. Shape construction and geometry need no native loading.
  */
object OpenCvRenderer:
  val textMeasurer: TextMeasurer = new TextMeasurer:
    def measure(text: String, font: Font, scale: Double): TextMetrics = Draw.textSize(text, font, scale)

  def layout: PictureLayout = PictureLayout.openCv

  /** Consumes `image` and returns the annotated image, including for an empty picture. */
  def renderOn(picture: Picture, image: Image): Image = image.paint(mat => Graphics.renderTo(picture, mat))

  /** Renders onto a fresh BGR canvas owned by the caller. */
  def render(picture: Picture, width: Int, height: Int, background: Color = Color.Black): Image =
    renderOn(picture, Image.blank(width, height, background.toBgr))

/** Native raster operations and ROI blending, isolated from scene data and geometric layout. */
private[scalacv] object Graphics:

  def renderTo(picture: Picture, mat: Mat): Unit = new OpenCvDrawing(mat).render(picture)

  private final class OpenCvDrawing(mat: Mat) extends Renderer:
    def textMeasurer: TextMeasurer = TextMeasurer.openCv
    protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit =
      drawPrim(primitive, mat, style)

  private def drawPrim(prim: RenderPrimitive, mat: Mat, style: PictureStyle): Unit =
    import RenderPrimitive.*
    prim match
      case Circle(center, radius) =>
        val c = center
        val r = math.max(0, radius.round.toInt)
        val roi = roiOf(Seq(Point(c.x - r, c.y - r), Point(c.x + r, c.y + r)), style.strokeWidth, mat)
        style.fill.foreach(col =>
          alpha(mat, col.alpha, roi)(m => Imgproc.circle(m, c.toCv, r, col.toBgr.toCv, -1, lineType(style)))
        )
        style.strokeColor.foreach: col =>
          style.dash match
            case None =>
              alpha(mat, col.alpha, roi)(m =>
                Imgproc.circle(m, c.toCv, r, col.toBgr.toCv, style.strokeWidth, lineType(style))
              )
            case Some(_) => strokePath(mat, circlePolygon(c, r), closed = true, col, style)
      case Path(points, closed) =>
        val pts = points
        if closed then style.fill.foreach(col => fillPoly(mat, pts, col, style))
        style.strokeColor.foreach(col => strokePath(mat, pts, closed, col, style))
      case Text(txt, at) =>
        val p = at
        style.strokeColor.foreach: col =>
          val scale = style.fontScale
          val m = OpenCvRenderer.textMeasurer.measure(txt, style.font, scale)
          // putText anchors on the baseline: glyphs rise `size.height` above `p.y` and descend `baseline` below.
          val roi = roiOf(
            Seq(Point(p.x, p.y - m.size.height), Point(p.x + m.size.width, p.y + m.baseline)),
            style.strokeWidth,
            mat
          )
          alpha(mat, col.alpha, roi): dst =>
            Imgproc.putText(
              dst,
              txt,
              p.toCv,
              style.font.cvValue,
              scale,
              col.toBgr.toCv,
              style.strokeWidth,
              lineType(style)
            )

  /** Runs `paint` with real per-shape transparency: opaque draws straight to `mat`; a translucent one paints
    * the shape opaquely onto `mat` and blends only `roi` — the region the paint can touch — back toward the
    * pixels it covered, so a small shape on a large canvas no longer clones and blends the whole image.
    *
    * This is bit-identical to blending the whole image: the arithmetic per pixel is unchanged (`covered·t +
    * original·(1-t)` where painted, `original` elsewhere), and `roi` is a conservative superset of the
    * painted pixels (see [[roiOf]]). Outside `roi` nothing is painted and nothing is blended, exactly as
    * before. When `roi` is the whole image (a shape that fills the canvas) this degenerates to the old
    * clone-and-blend with one extra submat header.
    */
  private def alpha(mat: Mat, a: Int, roi: Rect)(paint: Mat => Unit): Unit =
    if a >= 255 then paint(mat)
    else if roi.width <= 0 || roi.height <= 0 then () // clipped entirely off-canvas — nothing visible
    else
      val t = a / 255.0
      Managed.use(mat.submat(roi.toCv)): view =>
        // `backup` holds the original pixels of the ROI; `paint` then draws the shape opaquely onto `mat`
        // (through `view`'s shared data), and addWeighted blends the painted ROI back toward the backup.
        Managed.use(view.clone()): backup =>
          paint(mat)
          Core.addWeighted(view, t, backup, 1 - t, 0, view)

  /** A pixel-space bounding box that conservatively contains every pixel a draw of `points` with the given
    * stroke width can touch, clipped to the image. The margin covers the stroke half-width, round caps/joins
    * and antialiasing spread; over-covering only costs a little speed, under-covering would clip, so it errs
    * generous. An empty result (fully off-canvas) tells [[alpha]] there is nothing to blend.
    */
  private def roiOf(points: Seq[Point], strokeWidth: Int, mat: Mat): Rect =
    // No points means nothing to blend. Without this the sentinels below survive untouched and the clamped
    // corners come out crossed (x0 = cols, x1 = 0), i.e. a negative-extent Rect, whose `require` would throw
    // an IllegalArgumentException from inside a draw. Today every caller filters short paths out first, so
    // this is a guard on the type rather than on a live bug — but it is one line, and the alternative
    // failure mode is an exception nobody would connect to an empty input.
    if points.isEmpty then Rect(0, 0, 0, 0)
    else roiAround(points, strokeWidth, mat)

  private def roiAround(points: Seq[Point], strokeWidth: Int, mat: Mat): Rect =
    val margin = strokeWidth + 3.0
    var minX = Double.MaxValue
    var minY = Double.MaxValue
    var maxX = -Double.MaxValue
    var maxY = -Double.MaxValue
    points.foreach: p =>
      minX = math.min(minX, p.x)
      minY = math.min(minY, p.y)
      maxX = math.max(maxX, p.x)
      maxY = math.max(maxY, p.y)
    val x0 = math.max(0, math.min(mat.cols, math.floor(minX - margin).toInt))
    val y0 = math.max(0, math.min(mat.rows, math.floor(minY - margin).toInt))
    val x1 = math.max(0, math.min(mat.cols, math.ceil(maxX + margin).toInt))
    val y1 = math.max(0, math.min(mat.rows, math.ceil(maxY + margin).toInt))
    Rect(x0, y0, x1 - x0, y1 - y0)

  private def fillPoly(mat: Mat, points: Seq[Point], col: Color, style: PictureStyle): Unit =
    if points.sizeIs >= 3 then
      alpha(mat, col.alpha, roiOf(points, style.strokeWidth, mat)): m =>
        Managed.use(MatOfPoint(points.map(_.toCv)*)): poly =>
          Imgproc.fillPoly(m, java.util.List.of(poly), col.toBgr.toCv, lineType(style))

  private def strokePath(
      mat: Mat,
      points: Seq[Point],
      closed: Boolean,
      col: Color,
      style: PictureStyle
  ): Unit =
    if points.sizeIs >= 2 then
      val segments = if closed then points.zip(points.drop(1) :+ points.head) else points.zip(points.drop(1))
      alpha(mat, col.alpha, roiOf(points, style.strokeWidth, mat)): m =>
        style.dash match
          case None =>
            segments.foreach((s, e) =>
              Imgproc.line(m, s.toCv, e.toCv, col.toBgr.toCv, style.strokeWidth, lineType(style))
            )
          case Some(dash) => segments.foreach((s, e) => dashSegment(m, s, e, col, style, dash))

  private def dashSegment(
      mat: Mat,
      from: Point,
      to: Point,
      col: Color,
      style: PictureStyle,
      dash: Dash
  ): Unit =
    val length = from.distanceTo(to)
    if length > 0 then
      val ux = (to.x - from.x) / length
      val uy = (to.y - from.y) / length
      val period = dash.on + dash.off
      var pos = 0.0
      while pos < length do
        val on = math.min(pos + dash.on, length)
        Imgproc.line(
          mat,
          Point(from.x + ux * pos, from.y + uy * pos).toCv,
          Point(from.x + ux * on, from.y + uy * on).toCv,
          col.toBgr.toCv,
          style.strokeWidth,
          lineType(style)
        )
        pos += period

  private def circlePolygon(center: Point, radius: Int): Seq[Point] =
    (0 until 48).map { i =>
      val a = 2 * math.Pi * i / 48
      Point(center.x + radius * math.cos(a), center.y + radius * math.sin(a))
    }

  private def lineType(style: PictureStyle): Int = if style.antialias then Imgproc.LINE_AA else Imgproc.LINE_8

/** The graphics extension on [[Image]]. `import scalacv.graphs.*` gives `image.draw(picture)` without making
  * the core image type depend on scene data or the graphics backend.
  */
extension (img: Image)

  /** Draws a composable [[Picture]] onto the image — the high-level graphics layer (shapes, dashed strokes,
    * text, transforms, transparency). Consumes this image and returns the annotated one.
    */
  def draw(picture: Picture): Image = OpenCvRenderer.renderOn(picture, img)
