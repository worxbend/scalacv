package scalacv

import org.opencv.core as cv

/** Value types for the geometry OpenCV passes around.
  *
  * These are Scala case classes, copied across the native boundary rather than wrapping a pointer.
  * `org.opencv.core.Rect` and friends are mutable Java objects with public fields; a `Seq[cv.Rect]` handed
  * back from a detector is a set of live handles whose contents can change underneath you. Copying is cheap
  * here — four ints — and it makes the results ordinary immutable Scala data that is safe to keep after the
  * Mat it came from is released.
  */
/** A 2D point in pixel coordinates, origin top-left, `x` right and `y` down — the sub-pixel form OpenCV uses
  * for feature and contour work, so both fields are `Double`.
  */
final case class Point(x: Double, y: Double):
  private[scalacv] def toCv: cv.Point = cv.Point(x, y)

  /** Component-wise sum — this point translated by `other` read as an offset vector. */
  def +(other: Point): Point = Point(x + other.x, y + other.y)

  /** Component-wise difference — the offset vector from `other` to this point, so `a - b + b == a` and
    * `(a - b).distanceTo(Point(0, 0)) == a.distanceTo(b)`.
    */
  def -(other: Point): Point = Point(x - other.x, y - other.y)

  /** Both components scaled by the same factor, i.e. scaled as a vector from the origin — distances from
    * `(0, 0)` multiply by `by`.
    */
  def scale(by: Double): Point = Point(x * by, y * by)

  /** The straight-line distance to `other`, in pixels.
    *
    * Uses `math.hypot` rather than `math.sqrt(dx * dx + dy * dy)`. The two agree on ordinary pixel
    * coordinates, but `hypot` is written to avoid overflowing or underflowing while squaring, and it is the
    * form every call site in this library had already converged on independently.
    */
  def distanceTo(other: Point): Double = math.hypot(x - other.x, y - other.y)

/** A point in 3D space — a model coordinate for [[Ar]] pose work, in the same units you give a marker's side
  * length (metres is the usual choice). `z` points out of the marker plane toward the camera.
  */
final case class Point3(x: Double, y: Double, z: Double):
  private[scalacv] def toCv: cv.Point3 = cv.Point3(x, y, z)

/** A width/height extent in pixels. Neither side may be negative — a zero extent is allowed (an empty size),
  * a negative one throws.
  */
final case class Size(width: Double, height: Double):
  require(width >= 0 && height >= 0, s"a Size cannot be negative: ${width}x$height")
  private[scalacv] def toCv: cv.Size = cv.Size(width, height)

/** An axis-aligned integer rectangle: top-left corner `(x, y)` and non-negative `width`/`height`. The origin
  * may be negative (a region of interest can extend past the top-left of the image); the extent may not.
  */
final case class Rect(x: Int, y: Int, width: Int, height: Int):
  require(width >= 0 && height >= 0, s"a Rect cannot have negative extent: ${width}x$height")

  /** The enclosed area in pixels. `Long`, because `width * height` overflows a signed `Int` past roughly a
    * 46340-pixel side — an easy limit to hit on a full-frame ROI of a large image.
    */
  def area: Long = width.toLong * height

  /** The top-left corner as a [[Point]]. */
  def topLeft: Point = Point(x.toDouble, y.toDouble)

  /** The bottom-right corner as a [[Point]] — `(x + width, y + height)`, one past the last enclosed pixel.
    *
    * Each sum widens *before* it is taken, not after: `(x + width).toDouble` wraps to a negative corner once
    * the sum passes `Int.MaxValue`, the same overflow [[area]] widens to `Long` to avoid.
    */
  def bottomRight: Point = Point(x.toDouble + width, y.toDouble + height)

  /** Whether `p` lies inside this rectangle. Half-open, like the rectangle itself: the left and top edges
    * count, the right and bottom — one past the last enclosed pixel — do not.
    *
    * The far edges are compared in widened arithmetic, the same widening [[bottomRight]] does: `x + width` as
    * an `Int` wraps negative once it passes `Int.MaxValue`, and a wrapped edge would report points on the far
    * side of the image as contained.
    */
  def contains(p: Point): Boolean =
    p.x >= x.toDouble && p.x < x.toDouble + width &&
      p.y >= y.toDouble && p.y < y.toDouble + height

  /** The overlap of the two rectangles, or `None` when they share no pixel — including when they merely touch
    * along an edge, which would produce a zero-extent rectangle that consumers like `Mat.submat` reject. That
    * is the half-open convention the rest of the library already follows (see `Face.clippedBox`): abutting
    * rects do not overlap.
    *
    * Edges are computed in `Long` because `x + width` can overflow an `Int` before the `min`/`max` is
    * applied. The result always narrows back safely: its left edge is one of the inputs' left edges, and its
    * width is bounded by that input's own `width`, already a valid `Int`.
    */
  def intersect(other: Rect): Option[Rect] =
    val left = math.max(x.toLong, other.x.toLong)
    val top = math.max(y.toLong, other.y.toLong)
    val right = math.min(x.toLong + width, other.x.toLong + other.width)
    val bottom = math.min(y.toLong + height, other.y.toLong + other.height)
    if right <= left || bottom <= top then None
    else Some(Rect(left.toInt, top.toInt, (right - left).toInt, (bottom - top).toInt))

  private[scalacv] def toCv: cv.Rect = cv.Rect(x, y, width, height)

/** A pixel value: up to four channel components, in whatever channel order the Mat uses. OpenCV's default is
  * **BGR, not RGB**, so [[Scalar.Red]] is `Scalar(0, 0, 255)`. Unset channels default to `0`.
  */
final case class Scalar(v0: Double, v1: Double = 0, v2: Double = 0, v3: Double = 0):
  private[scalacv] def toCv: cv.Scalar = cv.Scalar(v0, v1, v2, v3)

object Point:
  private[scalacv] def from(p: cv.Point): Point = Point(p.x, p.y)

object Point3:
  private[scalacv] def from(p: cv.Point3): Point3 = Point3(p.x, p.y, p.z)

object Size:
  private[scalacv] def from(s: cv.Size): Size = Size(s.width, s.height)

object Rect:
  private[scalacv] def from(r: cv.Rect): Rect = Rect(r.x, r.y, r.width, r.height)

object Scalar:
  val Black: Scalar = Scalar(0, 0, 0)
  val White: Scalar = Scalar(255, 255, 255)

  /** OpenCV Mats are BGR by default, so these are ordered accordingly. */
  val Red: Scalar = Scalar(0, 0, 255)
  val Green: Scalar = Scalar(0, 255, 0)
  val Blue: Scalar = Scalar(255, 0, 0)
  private[scalacv] def from(s: cv.Scalar): Scalar =
    val a = s.`val`
    Scalar(a(0), a(1), a(2), a(3))
