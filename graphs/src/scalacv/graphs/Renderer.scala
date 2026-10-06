package scalacv.graphs

import scalacv.*

/** Backend-neutral drawing commands in image coordinates. Transforms have already been applied; text stays
  * axis-aligned at its transformed baseline anchor, as in the original Picture API.
  */
enum RenderPrimitive:
  case Circle(center: Point, radius: Double)
  case Path(points: Seq[Point], closed: Boolean)
  case Text(text: String, at: Point)

/** A destination-bound renderer. Implement [[draw]] for a raster, an SVG sink, or a command recorder; the
  * library supplies scene traversal, transform composition and contextual style resolution. The
  * implementation owns its destination's lifetime. No native type or flag is required by this contract.
  */
trait Renderer:
  def textMeasurer: TextMeasurer

  /** Layout with this backend's own text metrics. */
  final def layout: PictureLayout = new PictureLayout(textMeasurer)

  /** Draw in painter's order: bottom before top. */
  final def render(picture: Picture): Unit = PictureTraversal.foreach(picture)(draw)

  protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit

private[scalacv] object PictureTraversal:
  def foreach(picture: Picture)(draw: (RenderPrimitive, PictureStyle) => Unit): Unit =
    visit(picture, Affine.identity, PictureStyle())(draw)

  private def visit(picture: Picture, tf: Affine, style: PictureStyle)(
      draw: (RenderPrimitive, PictureStyle) => Unit
  ): Unit = picture match
    case Picture.Empty => ()
    case Picture.Over(top, bottom) =>
      visit(bottom, tf, style)(draw)
      visit(top, tf, style)(draw)
    case Picture.Styled(child, patch) => visit(child, tf, patch(style))(draw)
    case Picture.Transformed(child, affine) => visit(child, tf.compose(affine), style)(draw)
    case Picture.Leaf(prim) =>
      import Picture.Prim.*
      prim match
        case Circle(center, radius) =>
          draw(RenderPrimitive.Circle(tf(center), radius * tf.scaleFactor), style)
        case Quad(rect) =>
          val corners = Seq(
            Point(rect.x.toDouble, rect.y.toDouble),
            Point(rect.x.toDouble + rect.width, rect.y.toDouble),
            Point(rect.x.toDouble + rect.width, rect.y.toDouble + rect.height),
            Point(rect.x.toDouble, rect.y.toDouble + rect.height)
          ).map(tf.apply)
          draw(RenderPrimitive.Path(corners, closed = true), style)
        case Path(points, closed) => draw(RenderPrimitive.Path(points.map(tf.apply), closed), style)
        case Text(text, at) =>
          draw(RenderPrimitive.Text(text, tf(at)), style.copy(fontScale = style.fontScale * tf.scaleFactor))
