package scalacv.graphs

import scalacv.*

/** The resolved style a renderer receives. Stroke widths remain in pixels under scene transforms; only circle
  * radii and text scales scale with the geometry.
  */
final case class PictureStyle(
    strokeColor: Option[Color] = Some(Color.White),
    strokeWidth: Int = 1,
    dash: Option[Dash] = None,
    fill: Option[Color] = None,
    font: Font = Font.Simplex,
    fontScale: Double = 0.5,
    antialias: Boolean = true
)

/** Immutable contextual overrides. None inherits; Some(None) explicitly clears an optional property. */
private[scalacv] final case class StylePatch(
    strokeColor: Option[Option[Color]] = None,
    strokeWidth: Option[Int] = None,
    dash: Option[Option[Dash]] = None,
    fill: Option[Option[Color]] = None,
    font: Option[Font] = None,
    fontScale: Option[Double] = None,
    antialias: Option[Boolean] = None
):
  def apply(style: PictureStyle): PictureStyle = PictureStyle(
    strokeColor.getOrElse(style.strokeColor),
    strokeWidth.getOrElse(style.strokeWidth),
    dash.getOrElse(style.dash),
    fill.getOrElse(style.fill),
    font.getOrElse(style.font),
    fontScale.getOrElse(style.fontScale),
    antialias.getOrElse(style.antialias)
  )

/** A stroke dash pattern — alternating on/off run lengths in pixels. OpenCV has no dashed line, so
  * [[Picture]] draws them by hand from this.
  */
final case class Dash(on: Int, off: Int):
  require(on > 0 && off > 0, s"dash lengths must be positive, got on=$on off=$off")

object Dash:
  val dashed: Dash = Dash(10, 8)
  val dotted: Dash = Dash(1, 6)
  val dense: Dash = Dash(4, 4)

/** A 2×3 affine, mapping `(x, y)` → `(a·x + b·y + c, d·x + e·y + f)`. */
private[scalacv] final case class Affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double):
  def apply(p: Point): Point =
    val result = Point(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
    require(result.x.isFinite && result.y.isFinite, "transformed coordinates must be finite")
    result

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
  def scaleFactor: Double =
    val result = math.hypot(a, d)
    require(result.isFinite, "transform scale must be finite")
    result

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
  * import scalacv.graphs.*
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

  /** The geometric bounds, or `None` for an empty scene. Shape-only measurement needs no native loading. Text
    * uses OpenCV metrics; choose [[Renderer.layout]] or [[PictureLayout]] for another backend.
    */
  def bounds: Option[Bounds] = PictureLayout.openCv.bounds(this)

  /** Places `that` immediately to the right of this picture (centres aligned vertically), leaving `gap`
    * pixels between their bounding boxes. The Doodle-style horizontal layout.
    */
  def beside(that: Picture, gap: Double = 8): Picture = PictureLayout.openCv.beside(this, that, gap)

  /** Places `that` immediately below this picture (centres aligned horizontally), leaving `gap` pixels
    * between their bounding boxes. The Doodle-style vertical layout.
    */
  def above(that: Picture, gap: Double = 8): Picture = PictureLayout.openCv.above(this, that, gap)

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
  def strokeColor(color: Color): Picture = styled(StylePatch(strokeColor = Some(Some(color))))
  def strokeWidth(width: Int): Picture = styled(StylePatch(strokeWidth = Some(math.max(1, width))))
  def stroke(color: Color, width: Int = 1): Picture =
    styled(StylePatch(strokeColor = Some(Some(color)), strokeWidth = Some(math.max(1, width))))
  def noStroke: Picture = styled(StylePatch(strokeColor = Some(None)))

  /** Sets the fill colour (for closed shapes). */
  def fillColor(color: Color): Picture = styled(StylePatch(fill = Some(Some(color))))
  def noFill: Picture = styled(StylePatch(fill = Some(None)))

  /** Makes the outline dashed/dotted — the thing OpenCV cannot do on its own. */
  def strokeDash(dash: Dash): Picture = styled(StylePatch(dash = Some(Some(dash))))
  def dashed: Picture = strokeDash(Dash.dashed)
  def dotted: Picture = strokeDash(Dash.dotted)
  def solidStroke: Picture = styled(StylePatch(dash = Some(None)))

  def font(f: Font): Picture = styled(StylePatch(font = Some(f)))
  def fontScale(s: Double): Picture = styled(StylePatch(fontScale = Some(s)))
  def smooth(on: Boolean = true): Picture = styled(StylePatch(antialias = Some(on)))

  private def styled(patch: StylePatch): Picture = Picture.Styled(this, patch)

  /** Sends this scene to an independently implemented, destination-bound renderer. */
  def renderWith(renderer: Renderer): Unit = renderer.render(this)

  /** Draws this picture onto `image` (consumed) and returns the annotated image. */
  def renderOn(image: Image): Image = OpenCvRenderer.renderOn(this, image)

  /** Renders this picture onto a fresh `width`×`height` canvas filled with `background`. */
  def render(width: Int, height: Int, background: Color = Color.Black): Image =
    OpenCvRenderer.render(this, width, height, background)

object Picture:

  private[scalacv] case object Empty extends Picture
  private[scalacv] final case class Leaf(prim: Prim) extends Picture
  private[scalacv] final case class Over(top: Picture, bottom: Picture) extends Picture
  private[scalacv] final case class Styled(child: Picture, patch: StylePatch) extends Picture
  private[scalacv] final case class Transformed(child: Picture, affine: Affine) extends Picture

  private[scalacv] enum Prim:
    case Circle(center: Point, radius: Double)
    case Quad(rect: Rect)
    case Path(points: Seq[Point], closed: Boolean)
    case Text(text: String, at: Point)

  /** The empty picture — the identity for [[Picture.on]]. */
  val empty: Picture = Empty

  def circle(center: Point, radius: Double): Picture =
    require(radius.isFinite && radius >= 0, "radius must be finite and non-negative")
    require(center.x.isFinite && center.y.isFinite, "circle center must be finite")
    Leaf(Prim.Circle(center, radius))
  def rectangle(rect: Rect): Picture = Leaf(Prim.Quad(rect))
  def line(from: Point, to: Point): Picture = polyline(Seq(from, to))
  def polyline(points: Seq[Point], closed: Boolean = false): Picture =
    require(points.forall(p => p.x.isFinite && p.y.isFinite), "path coordinates must be finite")
    Leaf(Prim.Path(points, closed))
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
    require(radius.isFinite && radius >= 0, "radius must be finite and non-negative")
    val r = math.min(radius, math.min(rect.width, rect.height) / 2.0)
    val l = rect.x.toDouble
    val t = rect.y.toDouble
    val rt = rect.x.toDouble + rect.width
    val b = rect.y.toDouble + rect.height
    val corners = Seq(
      (Point(rt - r, t + r), 270.0, 360.0), // top-right
      (Point(rt - r, b - r), 0.0, 90.0), // bottom-right
      (Point(l + r, b - r), 90.0, 180.0), // bottom-left
      (Point(l + r, t + r), 180.0, 270.0) // top-left
    )
    polygon(corners.flatMap((c, s, e) => ellipsePoints(c, r, r, 0, s, e, 12)))

  /** A cubic Bézier curve through the two endpoints, pulled toward the two control points. */
  def curve(p0: Point, c0: Point, c1: Point, p1: Point, segments: Int = 32): Picture =
    require(segments > 0, "segments must be positive")
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
    require(segments > 0, "segments must be positive")
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
  ): Picture = PictureLayout.openCv.label(text, at, textColor, background, padding, fontScale, font)

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
    require(segments > 0, "segments must be positive")
    require(rx.isFinite && ry.isFinite && rx >= 0 && ry >= 0, "radii must be finite and non-negative")
    require(rotation.isFinite && startDegrees.isFinite && endDegrees.isFinite, "angles must be finite")
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
    require(radius.isFinite && radius >= 0 && rotation.isFinite, "invalid polygon geometry")
    require(sides >= 3, s"a polygon needs at least 3 sides, got $sides")
    polygon((0 until sides).map { i =>
      val a = math.toRadians(rotation) + 2 * math.Pi * i / sides
      Point(center.x + radius * math.cos(a), center.y + radius * math.sin(a))
    })

  /** A star with `points` points between `outer` and `inner` radii. */
  def star(center: Point, points: Int, outer: Double, inner: Double, rotation: Double = 0): Picture =
    require(
      outer.isFinite && inner.isFinite && outer >= 0 && inner >= 0 && rotation.isFinite,
      "invalid star geometry"
    )
    require(points <= Int.MaxValue / 2, "too many star points")
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
    PictureLayout.openCv.grid(pictures, columns, gap)
