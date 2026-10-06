package scalacv

import scala.jdk.CollectionConverters.SeqHasAsJava

import org.opencv.core.{Mat, MatOfPoint}
import org.opencv.imgproc.Imgproc

/* Drawing primitives, as extension methods on the Mat they draw into.
 *
 * **These mutate the receiver.** That is the exact opposite of the rest of the library: every other
 * operation leaves its input alone and hands back a new, caller-owned Mat. OpenCV's drawing functions have
 * no out-of-place form — they rasterise straight into the image you give them — so pretending otherwise
 * would mean cloning a full frame per annotation, which is precisely what a per-frame overlay cannot afford.
 * Instead the mutation is made obvious in the names: everything here is `drawSomething` or `fillSomething`
 * and returns `Unit`, so no call site can mistake one for a pure transform.
 *
 * These live in `core`, not in a GUI module, and reference nothing from highgui or JavaFX. Drawing is
 * ordinary raster work: annotating detections before `imwrite`, rendering a mask, burning a timestamp into a
 * recorded frame — all of which happen on machines with no display. A `draw` module would only have split
 * `core` in two along a line that does not exist.
 *
 * They also give [[LineType]] and [[Font]] their only consumers, and are how the typed results of the Hough
 * transforms ([[Segment]]) and of `findContours` ([[Contour]]) get rendered.
 */

/** How wide a stroke is, or that a shape is filled instead.
  *
  * OpenCV encodes "filled" as a thickness of `-1`, a sentinel that ordinary arithmetic on a thickness will
  * happily produce by accident. Worse, it is only meaningful for closed shapes: `cv::line` asserts
  * `0 < thickness`, so passing the sentinel to a line or to text aborts in native code. Splitting the two
  * cases into distinct types lets the shapes that *can* be filled accept [[Thickness]] while lines and text
  * accept [[Thickness.Stroke]] only — the mistake stops compiling rather than crashing.
  */
sealed trait Thickness:
  /** The `int` OpenCV expects. */
  def cvValue: Int

object Thickness:

  /** An outline `pixels` wide. */
  final case class Stroke(pixels: Int) extends Thickness:
    require(pixels >= 1, s"a stroke must be at least one pixel wide, got $pixels")
    def cvValue: Int = pixels

  /** A solid shape. Only closed shapes can be filled — see [[Thickness]]. */
  case object Filled extends Thickness:
    def cvValue: Int = Imgproc.FILLED

  /** The one-pixel outline used as the default everywhere here. */
  val Default: Stroke = Stroke(1)

/** What a string will occupy once drawn, from `Imgproc.getTextSize`.
  *
  * @param size
  *   the bounding box of the glyphs.
  * @param baseline
  *   how far the descenders reach below the baseline, in pixels. It is returned separately because
  *   `drawText`'s anchor is the baseline's left end, not the top-left corner: a background box has to be
  *   `size.height + baseline` tall to enclose the text, and forgetting it clips every `g` and `y`.
  */
final case class TextMetrics(size: Size, baseline: Int)

/** Text measurement — the one part of drawing that answers a question instead of changing an image. */
object Draw:

  /** Measures `text` without drawing it, so a caller can place or box it.
    *
    * The arguments are the same ones [[drawText]] takes, and must match for the answer to be meaningful.
    */
  def textSize(
      text: String,
      font: Font = Font.Simplex,
      scale: Double = 1.0,
      thickness: Thickness.Stroke = Thickness.Default
  ): TextMetrics =
    val baseline = Array(0)
    val s = Cv.orThrow("getTextSize")(
      Imgproc.getTextSize(text, font.cvValue, scale, thickness.cvValue, baseline)
    )
    TextMetrics(Size.from(s), baseline(0))

extension (mat: Mat)

  /** Draws a straight line from `from` to `to`. **Mutates the receiver.**
    *
    * Coordinates outside the image are clipped, not rejected — that is OpenCV's behaviour and it is what
    * makes drawing a detection that runs off the edge of a frame safe.
    */
  def drawLine(
      from: Point,
      to: Point,
      color: Scalar = Scalar.White,
      thickness: Thickness.Stroke = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawLine", mat)
    Cv.orThrow("line")(
      Imgproc.line(mat, from.toCv, to.toCv, color.toCv, thickness.cvValue, lineType.cvValue)
    )

  /** Draws a line with an arrowhead at `to`. **Mutates the receiver.**
    *
    * @param tipLength
    *   the arrowhead's length as a fraction of the whole line, so the head stays in proportion however long
    *   the line is. OpenCV's own default is `0.1`.
    */
  def drawArrow(
      from: Point,
      to: Point,
      color: Scalar = Scalar.White,
      thickness: Thickness.Stroke = Thickness.Default,
      lineType: LineType = LineType.Connected8,
      tipLength: Double = 0.1
  ): Unit =
    Preconditions.requireNonEmpty("drawArrow", mat)
    Cv.orThrow("arrowedLine")(
      // The 8-argument overload is the only one that reaches tipLength; `0` is the shift,
      // which this API does not expose.
      Imgproc.arrowedLine(
        mat,
        from.toCv,
        to.toCv,
        color.toCv,
        thickness.cvValue,
        lineType.cvValue,
        0,
        tipLength
      )
    )

  /** Draws an axis-aligned rectangle. **Mutates the receiver.**
    *
    * Pass [[Thickness.Filled]] for a solid block — useful as a label background or for building a mask.
    */
  def drawRect(
      rect: Rect,
      color: Scalar = Scalar.White,
      thickness: Thickness = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawRect", mat)
    Cv.orThrow("rectangle")(
      Imgproc.rectangle(mat, rect.toCv, color.toCv, thickness.cvValue, lineType.cvValue)
    )

  /** Draws a circle of `radius` pixels about `center`. **Mutates the receiver.** */
  def drawCircle(
      center: Point,
      radius: Int,
      color: Scalar = Scalar.White,
      thickness: Thickness = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawCircle", mat)
    require(radius >= 0, s"a circle cannot have a negative radius, got $radius")
    Cv.orThrow("circle")(
      Imgproc.circle(mat, center.toCv, radius, color.toCv, thickness.cvValue, lineType.cvValue)
    )

  /** Draws `text` with its baseline's left end at `at`. **Mutates the receiver.**
    *
    * `at` is *not* the top-left corner: OpenCV anchors text on the baseline, so a `y` of 0 puts almost the
    * whole string above the image and draws nothing visible. [[Draw.textSize]] gives the box to place it by.
    *
    * Only the Hershey vector fonts exist — OpenCV cannot render a system font, and non-ASCII characters are
    * drawn as `?`.
    */
  def drawText(
      text: String,
      at: Point,
      color: Scalar = Scalar.White,
      font: Font = Font.Simplex,
      scale: Double = 1.0,
      thickness: Thickness.Stroke = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawText", mat)
    Cv.orThrow("putText")(
      Imgproc.putText(
        mat,
        text,
        at.toCv,
        font.cvValue,
        scale,
        color.toCv,
        thickness.cvValue,
        lineType.cvValue
      )
    )

  /** Draws a connected run of line segments through `points`. **Mutates the receiver.**
    *
    * @param closed
    *   whether to draw the closing edge from the last point back to the first. Defaults to `true`, matching
    *   what [[Contour]] and polygon data mean.
    *
    * An empty `points` draws nothing rather than failing: a polyline is frequently the result of a filter,
    * and filtering everything out is a legitimate outcome, not a programming error.
    */
  def drawPolyline(
      points: Seq[Point],
      closed: Boolean = true,
      color: Scalar = Scalar.White,
      thickness: Thickness.Stroke = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawPolyline", mat)
    if points.nonEmpty then
      DrawOps.withPolygons(Seq(points)): polys =>
        Cv.orThrow("polylines")(
          Imgproc.polylines(mat, polys, closed, color.toCv, thickness.cvValue, lineType.cvValue)
        )

  /** Fills the polygon described by `points` with `color`. **Mutates the receiver.**
    *
    * The outline is implicitly closed. Self-intersecting outlines are filled by OpenCV's even-odd rule.
    */
  def fillPolygon(
      points: Seq[Point],
      color: Scalar = Scalar.White,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("fillPolygon", mat)
    if points.nonEmpty then
      DrawOps.withPolygons(Seq(points)): polys =>
        Cv.orThrow("fillPoly")(Imgproc.fillPoly(mat, polys, color.toCv, lineType.cvValue))

  /** Draws every contour in `contours`. **Mutates the receiver.**
    *
    * This is the renderer for what `findContours` returns. [[Thickness.Filled]] fills them, which is the
    * usual way to turn a set of contours back into a mask.
    */
  def drawContours(
      contours: Seq[Contour],
      color: Scalar = Scalar.White,
      thickness: Thickness = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawContours", mat)
    val outlines = contours.map(_.points).filter(_.nonEmpty)
    if outlines.nonEmpty then
      DrawOps.withPolygons(outlines): polys =>
        // -1 draws all of them; there is no per-contour call to make in a loop.
        Cv.orThrow("drawContours")(
          Imgproc.drawContours(mat, polys, -1, color.toCv, thickness.cvValue, lineType.cvValue)
        )

  /** Draws every segment in `segments`. **Mutates the receiver.**
    *
    * The renderer for `houghLinesP`, whose results are otherwise invisible.
    */
  def drawSegments(
      segments: Seq[Segment],
      color: Scalar = Scalar.White,
      thickness: Thickness.Stroke = Thickness.Default,
      lineType: LineType = LineType.Connected8
  ): Unit =
    Preconditions.requireNonEmpty("drawSegments", mat)
    // One JNI call per segment, deliberately — there is no batched primitive to prefer. The batch entry
    // points (`polylines`, `fillPoly`, `drawContours`) all funnel their input through the generated
    // `Converters.vector_vector_Point_to_Mat`, which leaks one Mat per polygon (see `withPolygons` below),
    // and a segment cloud is exactly the input shape that would multiply that residue. `Imgproc.line`
    // allocates nothing native beyond its scalar arguments, so this loop is the leak-free form; the
    // per-call JNI overhead is the accepted price.
    segments.foreach(s => mat.drawLine(s.start, s.end, color, thickness, lineType))

/** File-private helpers. Wrapped in an object rather than left top-level so their names cannot collide with
  * another file's package-private definitions.
  */
private object DrawOps:

  /** Materialises Scala point lists as the `java.util.List[MatOfPoint]` OpenCV's polygon calls demand, and
    * frees the ones it allocated.
    *
    * Each `MatOfPoint` is a native allocation the drawing call does not take ownership of, so releasing them
    * is ours to do. Scoped through [[Managed.scope]] rather than a hand-rolled `try`/`finally`: every
    * `MatOfPoint` is registered with the scope the moment it is created, so a constructor throwing partway
    * through the list — or a `CvException` from the draw, which is exactly when cleanup is most likely to be
    * skipped — still releases everything already allocated, in reverse order.
    *
    * **This does not make the call leak-free, and it would be dishonest to claim otherwise.** The generated
    * Java binding for `polylines`, `fillPoly` and `drawContours` runs the input through
    * `Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus one more for the
    * outer vector and releases none of them. That residue is upstream in the official Java API and cannot be
    * fixed from here without reimplementing the converter. It is bounded per call but, like everything else
    * in this class of bug, unbounded across a video loop.
    */
  def withPolygons[A](polygons: Seq[Seq[Point]])(f: java.util.List[MatOfPoint] => A): A =
    Managed.scope: own =>
      val mats = polygons.map(points => own(MatOfPoint(points.map(_.toCv)*)))
      f(mats.asJava)
