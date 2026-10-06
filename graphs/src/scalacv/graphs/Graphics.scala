package scalacv.graphs

import org.opencv.core.{Core, Mat, MatOfPoint}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** A stroke dash pattern — alternating on/off run lengths in pixels. OpenCV has no dashed line, so
  * [[Picture]] draws them by hand from this.
  */
final case class Dash(on: Int, off: Int):
  require(on > 0 && off > 0, s"dash lengths must be positive, got on=$on off=$off")

object Dash:
  val dashed: Dash = Dash(10, 8)
  val dotted: Dash = Dash(1, 6)
  val dense: Dash = Dash(4, 4)

private[scalacv] final case class Style(
    strokeColor: Option[Color] = Some(Color.White),
    strokeWidth: Int = 1,
    dash: Option[Dash] = None,
    fill: Option[Color] = None,
    font: Font = Font.Simplex,
    fontScale: Double = 0.5,
    antialias: Boolean = true
)

/** A 2×3 affine, mapping `(x, y)` → `(a·x + b·y + c, d·x + e·y + f)`. */
private[scalacv] final case class Affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double):
  def apply(p: Point): Point = Point(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

  /** This transform applied after `inner`: `p ↦ this(inner(p))`. */
  def compose(inner: Affine): Affine =
    Affine(
      a * inner.a + b * inner.d,
      a * inner.b + b * inner.e,
      a * inner.c + b * inner.f + c,
      d * inner.a + e * inner.d,
      d * inner.b + e * inner.e,
      d * inner.c + e * inner.f + f
    )

  /** The uniform scale this transform applies — used to scale circle radii and text. */
  def scaleFactor: Double = math.sqrt(math.abs(a * e - b * d))

private[scalacv] object Affine:
  val identity: Affine = Affine(1, 0, 0, 0, 1, 0)
  def translate(dx: Double, dy: Double): Affine = Affine(1, 0, dx, 0, 1, dy)
  def scale(s: Double, about: Point): Affine =
    translate(about.x, about.y).compose(Affine(s, 0, 0, 0, s, 0)).compose(translate(-about.x, -about.y))
  def rotate(degrees: Double, about: Point): Affine =
    val r = math.toRadians(degrees)
    val rot = Affine(math.cos(r), -math.sin(r), 0, math.sin(r), math.cos(r), 0)
    translate(about.x, about.y).compose(rot).compose(translate(-about.x, -about.y))

/** The axis-aligned bounding box of a [[Picture]] — what the layout combinators ([[Picture.beside]],
  * [[Picture.above]]) measure to place pictures next to each other.
  */
final case class Bounds(minX: Double, minY: Double, maxX: Double, maxY: Double):
  def width: Double = maxX - minX
  def height: Double = maxY - minY
  def centerX: Double = (minX + maxX) / 2
  def centerY: Double = (minY + maxY) / 2

  /** The smallest box enclosing both this and `other`. */
  def union(other: Bounds): Bounds =
    Bounds(
      math.min(minX, other.minX),
      math.min(minY, other.minY),
      math.max(maxX, other.maxX),
      math.max(maxY, other.maxY)
    )

/** An immutable, composable 2D drawing — the graphics layer, inspired by Doodle and adapted to image space.
  *
  * A `Picture` is a *value*: build primitives, style them, compose them, and only then render onto an image.
  * Because it composes, the same vocabulary annotates a detection, draws a chart, or makes generative art.
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * // A dashed green box with a label — an overlay for a detected face:
  * val overlay =
  *   Picture.rectangle(face.box).strokeColor(Color.Green).strokeWidth(2).dashed
  *     .on(Picture.text("face", Point(face.box.x, face.box.y - 6)).strokeColor(Color.Green))
  *
  * image.draw(overlay) // draw it on
  * }}}
  *
  * Coordinates are image pixels (origin top-left, y down). Styling is contextual: a style set on a group is
  * the default its members inherit unless they set their own. Alpha in a [[Color]] gives real transparency
  * when drawn over an image.
  */
sealed trait Picture:

  /** This picture drawn on top of `under`. */
  def on(under: Picture): Picture = Picture.Over(this, under)

  /** This picture drawn underneath `over`. */
  def under(over: Picture): Picture = Picture.Over(over, this)

  /** The axis-aligned bounding box of this picture, or `None` if it draws nothing. Text is measured with its
    * font metrics, so labels lay out correctly too.
    */
  def bounds: Option[Bounds] = Graphics.boundsOf(this, Affine.identity, Style())

  /** Places `that` immediately to the right of this picture (centres aligned vertically), leaving `gap`
    * pixels between their bounding boxes. The Doodle-style horizontal layout.
    */
  def beside(that: Picture, gap: Double = 8): Picture =
    (bounds, that.bounds) match
      case (Some(a), Some(b)) =>
        Picture.all(Seq(this, that.translate(a.maxX + gap - b.minX, a.centerY - b.centerY)))
      case (Some(_), None) => this
      case (None, _) => that

  /** Places `that` immediately below this picture (centres aligned horizontally), leaving `gap` pixels
    * between their bounding boxes. The Doodle-style vertical layout.
    */
  def above(that: Picture, gap: Double = 8): Picture =
    (bounds, that.bounds) match
      case (Some(a), Some(b)) =>
        Picture.all(Seq(this, that.translate(a.centerX - b.centerX, a.maxY + gap - b.minY)))
      case (Some(_), None) => this
      case (None, _) => that

  /** Translates by `(dx, dy)` pixels. */
  def translate(dx: Double, dy: Double): Picture = Picture.Transformed(this, Affine.translate(dx, dy))

  /** Translates so the picture's origin lands on `point`. */
  def at(point: Point): Picture = translate(point.x, point.y)

  /** Rotates `degrees` (clockwise, since y is down) about `about`. */
  def rotate(degrees: Double, about: Point = Point(0, 0)): Picture =
    Picture.Transformed(this, Affine.rotate(degrees, about))

  /** Scales by `factor` about `about`. */
  def scale(factor: Double, about: Point = Point(0, 0)): Picture =
    Picture.Transformed(this, Affine.scale(factor, about))

  /** Sets the outline colour (a group default its members may override). */
  def strokeColor(color: Color): Picture = styled(_.copy(strokeColor = Some(color)))
  def strokeWidth(width: Int): Picture = styled(_.copy(strokeWidth = math.max(1, width)))
  def stroke(color: Color, width: Int = 1): Picture =
    styled(_.copy(strokeColor = Some(color), strokeWidth = math.max(1, width)))
  def noStroke: Picture = styled(_.copy(strokeColor = None))

  /** Sets the fill colour (for closed shapes). */
  def fillColor(color: Color): Picture = styled(_.copy(fill = Some(color)))
  def noFill: Picture = styled(_.copy(fill = None))

  /** Makes the outline dashed/dotted — the thing OpenCV cannot do on its own. */
  def strokeDash(dash: Dash): Picture = styled(_.copy(dash = Some(dash)))
  def dashed: Picture = strokeDash(Dash.dashed)
  def dotted: Picture = strokeDash(Dash.dotted)
  def solidStroke: Picture = styled(_.copy(dash = None))

  def font(f: Font): Picture = styled(_.copy(font = f))
  def fontScale(s: Double): Picture = styled(_.copy(fontScale = s))
  def smooth(on: Boolean = true): Picture = styled(_.copy(antialias = on))

  private def styled(f: Style => Style): Picture = Picture.Styled(this, f)

  /** Draws this picture onto `image` (consumed) and returns the annotated image. */
  def renderOn(image: Image): Image = image.draw(this)

  /** Renders this picture onto a fresh `width`×`height` canvas filled with `background`. */
  def render(width: Int, height: Int, background: Color = Color.Black): Image =
    Image.blank(width, height, background.toBgr).draw(this)

object Picture:

  private[scalacv] case object Empty extends Picture
  private[scalacv] final case class Leaf(prim: Prim) extends Picture
  private[scalacv] final case class Over(top: Picture, bottom: Picture) extends Picture
  private[scalacv] final case class Styled(child: Picture, f: Style => Style) extends Picture
  private[scalacv] final case class Transformed(child: Picture, affine: Affine) extends Picture

  private[scalacv] enum Prim:
    case Circle(center: Point, radius: Double)
    case Quad(rect: Rect)
    case Path(points: Seq[Point], closed: Boolean)
    case Text(text: String, at: Point)

  /** The empty picture — the identity for [[Picture.on]]. */
  val empty: Picture = Empty

  def circle(center: Point, radius: Double): Picture = Leaf(Prim.Circle(center, radius))
  def rectangle(rect: Rect): Picture = Leaf(Prim.Quad(rect))
  def line(from: Point, to: Point): Picture = Leaf(Prim.Path(Seq(from, to), closed = false))
  def polyline(points: Seq[Point], closed: Boolean = false): Picture = Leaf(Prim.Path(points, closed))
  def polygon(points: Seq[Point]): Picture = polyline(points, closed = true)
  def text(text: String, at: Point): Picture = Leaf(Prim.Text(text, at))

  /** An ellipse with semi-axes `rx`/`ry`, `rotation` degrees turned. A closed shape, so it fills and dashes
    * like any polygon (it is drawn as a fine polyline, which keeps the styling uniform).
    */
  def ellipse(center: Point, rx: Double, ry: Double, rotation: Double = 0, segments: Int = 64): Picture =
    polygon(ellipsePoints(center, rx, ry, rotation, 0, 360, segments))

  /** An open elliptical arc from `startDegrees` to `endDegrees` (clockwise, since y is down). */
  def arc(
      center: Point,
      rx: Double,
      ry: Double,
      startDegrees: Double,
      endDegrees: Double,
      rotation: Double = 0,
      segments: Int = 48
  ): Picture =
    polyline(ellipsePoints(center, rx, ry, rotation, startDegrees, endDegrees, segments), closed = false)

  /** A filled pie slice: the arc from `startDegrees` to `endDegrees` closed back through the centre. */
  def sector(
      center: Point,
      rx: Double,
      ry: Double,
      startDegrees: Double,
      endDegrees: Double,
      rotation: Double = 0,
      segments: Int = 48
  ): Picture =
    polygon(center +: ellipsePoints(center, rx, ry, rotation, startDegrees, endDegrees, segments))

  /** A rectangle with rounded corners of the given `radius` (clamped to half the shorter side). */
  def roundedRectangle(rect: Rect, radius: Double): Picture =
    val r = math.min(radius, math.min(rect.width, rect.height) / 2.0)
    val l = rect.x.toDouble
    val t = rect.y.toDouble
    val rt = (rect.x + rect.width).toDouble
    val b = (rect.y + rect.height).toDouble
    val corners = Seq(
      (Point(rt - r, t + r), 270.0, 360.0), // top-right
      (Point(rt - r, b - r), 0.0, 90.0), // bottom-right
      (Point(l + r, b - r), 90.0, 180.0), // bottom-left
      (Point(l + r, t + r), 180.0, 270.0) // top-left
    )
    polygon(corners.flatMap((c, s, e) => ellipsePoints(c, r, r, 0, s, e, 12)))

  /** A cubic Bézier curve through the two endpoints, pulled toward the two control points. */
  def curve(p0: Point, c0: Point, c1: Point, p1: Point, segments: Int = 32): Picture =
    polyline((0 to segments).map { i =>
      val t = i.toDouble / segments
      val u = 1 - t
      Point(
        u * u * u * p0.x + 3 * u * u * t * c0.x + 3 * u * t * t * c1.x + t * t * t * p1.x,
        u * u * u * p0.y + 3 * u * u * t * c0.y + 3 * u * t * t * c1.y + t * t * t * p1.y
      )
    })

  /** A quadratic Bézier curve from `p0` to `p1`, bent toward `control`. */
  def quadraticCurve(p0: Point, control: Point, p1: Point, segments: Int = 24): Picture =
    polyline((0 to segments).map { i =>
      val t = i.toDouble / segments
      val u = 1 - t
      Point(
        u * u * p0.x + 2 * u * t * control.x + t * t * p1.x,
        u * u * p0.y + 2 * u * t * control.y + t * t * p1.y
      )
    })

  /** A text label on a filled background box — the readable way to tag a detection. `at` is the box's
    * top-left; the box is sized to the text plus `padding` on every side.
    */
  def label(
      text: String,
      at: Point,
      textColor: Color = Color.White,
      background: Color = Color.Black,
      padding: Int = 4,
      fontScale: Double = 0.5,
      font: Font = Font.Simplex
  ): Picture =
    val m = Draw.textSize(text, font, fontScale)
    val w = m.size.width.round.toInt + 2 * padding
    val h = m.size.height.round.toInt + m.baseline + 2 * padding
    val box = Rect(at.x.round.toInt, at.y.round.toInt, w, h)
    val baseline = Point(at.x + padding, at.y + padding + m.size.height)
    Picture
      .text(text, baseline)
      .strokeColor(textColor)
      .fontScale(fontScale)
      .font(font)
      .under(rectangle(box).fillColor(background).noStroke)

  /** Points along an elliptical arc from `startDegrees` to `endDegrees`, rotated `rotation` degrees. */
  private def ellipsePoints(
      center: Point,
      rx: Double,
      ry: Double,
      rotation: Double,
      startDegrees: Double,
      endDegrees: Double,
      segments: Int
  ): Seq[Point] =
    val rot = math.toRadians(rotation)
    val cos = math.cos(rot)
    val sin = math.sin(rot)
    val a0 = math.toRadians(startDegrees)
    val a1 = math.toRadians(endDegrees)
    (0 to segments).map { i =>
      val a = a0 + (a1 - a0) * i / segments
      val ex = rx * math.cos(a)
      val ey = ry * math.sin(a)
      Point(center.x + ex * cos - ey * sin, center.y + ex * sin + ey * cos)
    }

  /** A filled dot (fill it with a colour; the default is white). */
  def dot(at: Point, radius: Double = 3): Picture = circle(at, radius).fillColor(Color.White).noStroke

  /** A keypoint marker — a filled dot in `color`. */
  def marker(at: Point, color: Color, radius: Double = 3): Picture =
    circle(at, radius).fillColor(color).noStroke

  /** An X cross marker. */
  def cross(at: Point, size: Double = 5): Picture =
    line(Point(at.x - size, at.y - size), Point(at.x + size, at.y + size))
      .on(line(Point(at.x - size, at.y + size), Point(at.x + size, at.y - size)))

  /** A line with an arrowhead at `to`. */
  def arrow(from: Point, to: Point, headLength: Double = 12, headAngle: Double = 28): Picture =
    val angle = math.atan2(to.y - from.y, to.x - from.x)
    def wing(sign: Double): Point =
      val a = angle + math.Pi + sign * math.toRadians(headAngle)
      Point(to.x + headLength * math.cos(a), to.y + headLength * math.sin(a))
    line(from, to).on(line(to, wing(1))).on(line(to, wing(-1)))

  /** A regular `sides`-gon inscribed in a circle of `radius`, `rotation` degrees turned. */
  def regularPolygon(center: Point, sides: Int, radius: Double, rotation: Double = 0): Picture =
    require(sides >= 3, s"a polygon needs at least 3 sides, got $sides")
    polygon((0 until sides).map { i =>
      val a = math.toRadians(rotation) + 2 * math.Pi * i / sides
      Point(center.x + radius * math.cos(a), center.y + radius * math.sin(a))
    })

  /** A star with `points` points between `outer` and `inner` radii. */
  def star(center: Point, points: Int, outer: Double, inner: Double, rotation: Double = 0): Picture =
    require(points >= 2, s"a star needs at least 2 points, got $points")
    polygon((0 until points * 2).map { i =>
      val r = if i % 2 == 0 then outer else inner
      val a = math.toRadians(rotation) + math.Pi * i / points
      Point(center.x + r * math.cos(a), center.y + r * math.sin(a))
    })

  /** Overlays a group of pictures, the first at the bottom. */
  def all(pictures: Seq[Picture]): Picture = pictures.foldLeft(empty)((acc, p) => Over(p, acc))

  /** Lays `pictures` out in a grid of `columns` columns, row by row, with `gap` pixels between cells. Each
    * cell is the size of the largest picture, so ragged content still aligns.
    */
  def grid(pictures: Seq[Picture], columns: Int, gap: Double = 8): Picture =
    require(columns >= 1, s"a grid needs at least one column, got $columns")
    if pictures.isEmpty then empty
    else
      val bounds = pictures.map(_.bounds)
      val cellW = bounds.flatten.map(_.width).maxOption.getOrElse(0.0) + gap
      val cellH = bounds.flatten.map(_.height).maxOption.getOrElse(0.0) + gap
      all(pictures.zipWithIndex.map { (p, i) =>
        val col = i % columns
        val row = i / columns
        p.bounds match
          case Some(b) => p.translate(col * cellW - b.minX, row * cellH - b.minY)
          case None => p
      })

/** Renders a [[Picture]] onto a Mat. Package-private — the entry point is `image.draw`. */
private[scalacv] object Graphics:

  def renderTo(picture: Picture, mat: Mat): Unit = draw(picture, mat, Style(), Affine.identity)

  /** The bounding box of `picture` under `tf` and `style` (text needs the style's font to be measured). */
  private[scalacv] def boundsOf(picture: Picture, tf: Affine, style: Style): Option[Bounds] = picture match
    case Picture.Empty => None
    case Picture.Over(top, bottom) => union(boundsOf(top, tf, style), boundsOf(bottom, tf, style))
    case Picture.Styled(child, fn) => boundsOf(child, tf, fn(style))
    case Picture.Transformed(child, affn) => boundsOf(child, tf.compose(affn), style)
    case Picture.Leaf(prim) => primBounds(prim, tf, style)

  private def union(a: Option[Bounds], b: Option[Bounds]): Option[Bounds] = (a, b) match
    case (Some(x), Some(y)) => Some(x.union(y))
    case (some, None) => some
    case (None, some) => some

  /** The box a primitive occupies, or `None` when it occupies nothing.
    *
    * Only a [[Picture.Prim.Path]] can be empty, and an empty one is not a mistake: a polyline is routinely
    * the result of filtering detections, and filtering everything out is a legitimate outcome —
    * `drawPolyline` says so and draws nothing. Measuring has to agree, because the layout combinators
    * ([[Picture.beside]], [[Picture.above]], [[Picture.grid]]) all ask for bounds before placing anything,
    * and they are already written to handle a `None`. Returning a `Bounds` unconditionally meant taking `min`
    * of no points, which threw `UnsupportedOperationException` out of `bounds` — from a signature that
    * already says "or nothing".
    *
    * The box describes the *geometry*, not the ink: a thick stroke and antialiasing spread paint a few pixels
    * outside it (the renderer's `roiOf` pads for exactly that). Keeping bounds geometric is what lets `Chart`
    * promise that a chart's bounds fit its width×height box.
    */
  private def primBounds(prim: Picture.Prim, tf: Affine, style: Style): Option[Bounds] =
    import Picture.Prim.*
    prim match
      case Circle(center, radius) =>
        // A circle is rotation-invariant: only its centre transforms, and its radius scales. Transforming
        // the two bbox corners instead collapses the box under rotation — at 45° the diagonal corners map
        // onto a single line and an axis of the bounds vanishes, while drawPrim still paints the whole
        // circle with r = radius * tf.scaleFactor, the same radius computed here.
        val c = tf(center)
        val r = radius * tf.scaleFactor
        extentOf(Seq(Point(c.x - r, c.y - r), Point(c.x + r, c.y + r)))
      case Quad(rect) =>
        extentOf(
          Seq(
            Point(rect.x.toDouble, rect.y.toDouble),
            Point((rect.x + rect.width).toDouble, (rect.y + rect.height).toDouble)
          ).map(tf.apply)
        )
      case Path(points, _) => extentOf(points.map(tf.apply))
      case Text(txt, at) =>
        // putText cannot rotate: drawPrim renders the glyphs axis-aligned at the *transformed anchor* and
        // at the transform-scaled font. The bounds are therefore the axis-aligned glyph box at tf(at) —
        // rotating the box would match neither the drawn pixels nor a true rotated glyph quad.
        val p = tf(at)
        val m = Draw.textSize(txt, style.font, style.fontScale * tf.scaleFactor)
        extentOf(Seq(Point(p.x, p.y - m.size.height), Point(p.x + m.size.width, p.y + m.baseline)))

  private def extentOf(points: Seq[Point]): Option[Bounds] =
    if points.isEmpty then None
    else
      val xs = points.map(_.x)
      val ys = points.map(_.y)
      Some(Bounds(xs.min, ys.min, xs.max, ys.max))

  private def draw(picture: Picture, mat: Mat, style: Style, tf: Affine): Unit = picture match
    case Picture.Empty => ()
    case Picture.Over(top, bottom) => draw(bottom, mat, style, tf); draw(top, mat, style, tf)
    case Picture.Styled(child, fn) => draw(child, mat, fn(style), tf)
    case Picture.Transformed(child, affn) => draw(child, mat, style, tf.compose(affn))
    case Picture.Leaf(prim) => drawPrim(prim, mat, style, tf)

  private def drawPrim(prim: Picture.Prim, mat: Mat, style: Style, tf: Affine): Unit =
    import Picture.Prim.*
    prim match
      case Circle(center, radius) =>
        val c = tf(center)
        val r = math.max(0, (radius * tf.scaleFactor).round.toInt)
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
      case Quad(rect) =>
        val corners = Seq(
          Point(rect.x.toDouble, rect.y.toDouble),
          Point((rect.x + rect.width).toDouble, rect.y.toDouble),
          Point((rect.x + rect.width).toDouble, (rect.y + rect.height).toDouble),
          Point(rect.x.toDouble, (rect.y + rect.height).toDouble)
        ).map(tf.apply)
        style.fill.foreach(col => fillPoly(mat, corners, col, style))
        style.strokeColor.foreach(col => strokePath(mat, corners, closed = true, col, style))
      case Path(points, closed) =>
        val pts = points.map(tf.apply)
        if closed then style.fill.foreach(col => fillPoly(mat, pts, col, style))
        style.strokeColor.foreach(col => strokePath(mat, pts, closed, col, style))
      case Text(txt, at) =>
        val p = tf(at)
        style.strokeColor.foreach: col =>
          val scale = style.fontScale * tf.scaleFactor
          val m = Draw.textSize(txt, style.font, scale)
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

  private def fillPoly(mat: Mat, points: Seq[Point], col: Color, style: Style): Unit =
    if points.sizeIs >= 3 then
      alpha(mat, col.alpha, roiOf(points, style.strokeWidth, mat)): m =>
        Managed.use(MatOfPoint(points.map(_.toCv)*)): poly =>
          Imgproc.fillPoly(m, java.util.List.of(poly), col.toBgr.toCv, lineType(style))

  private def strokePath(mat: Mat, points: Seq[Point], closed: Boolean, col: Color, style: Style): Unit =
    if points.sizeIs >= 2 then
      val segments = if closed then points.zip(points.drop(1) :+ points.head) else points.zip(points.drop(1))
      alpha(mat, col.alpha, roiOf(points, style.strokeWidth, mat)): m =>
        style.dash match
          case None =>
            segments.foreach((s, e) =>
              Imgproc.line(m, s.toCv, e.toCv, col.toBgr.toCv, style.strokeWidth, lineType(style))
            )
          case Some(dash) => segments.foreach((s, e) => dashSegment(m, s, e, col, style, dash))

  private def dashSegment(mat: Mat, from: Point, to: Point, col: Color, style: Style, dash: Dash): Unit =
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

  private def lineType(style: Style): Int = if style.antialias then Imgproc.LINE_AA else Imgproc.LINE_8

/** The high-level graphics verb on [[Image]] — an extension method so the [[Picture]] layer stays in this
  * file rather than in the image class. `import scalacv.*` gives `image.draw(picture)`.
  */
extension (img: Image)

  /** Draws a composable [[Picture]] onto the image — the high-level graphics layer (shapes, dashed strokes,
    * text, transforms, transparency). Consumes this image and returns the annotated one.
    */
  def draw(picture: Picture): Image = img.paint(mat => Graphics.renderTo(picture, mat))
