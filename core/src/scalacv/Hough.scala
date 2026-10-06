package scalacv

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/* Typed results for the Hough line transforms.
 *
 * OpenCV returns lines as an `Nx1` multi-channel `Mat` whose element type differs per
 * transform, and whose channels have no names. Reading one correctly means knowing three separate facts that
 * nothing in the Java signature tells you — and getting any of them wrong either throws from JNI or, worse,
 * silently reinterprets bit patterns. Verified against 4.13.0 and 4.14.0 by execution:
 *
 *   - `HoughLines` → `Nx1 CV_32FC2`, `(rho, theta)`. **Vec2f, never Vec3f**, and invariant under `srn`,
 *     `stn`, `min_theta` and `max_theta`.
 *   - `HoughLinesWithAccumulator` → `Nx1 CV_32FC3`, `(rho, theta, votes)`.
 *   - `HoughLinesP` → `Nx1 CV_32SC4`, `(x1, y1, x2, y2)` — **int32**, not float. A float-typed read of this
 *     Mat throws.
 *
 * Everything here decodes through `Mat.get(row, 0): Array[Double]`, which is depth-generic on the Java side
 * and so is the one accessor that is correct for all three shapes. The intermediate Mat never escapes: it is
 * released before the call returns, and what comes back is ordinary immutable Scala data that outlives the
 * image it was computed from.
 */

/** A line in Hesse normal form, as `HoughLines` reports it: infinite, with no endpoints.
  *
  * The underlying Mat is `CV_32FC2`, but the fields are widened to `Double` at the decode boundary: every
  * other measurement the library surfaces ([[Point]], [[Contour]]) is a `Double`, and the float channels
  * carry no precision past 24 bits for the widening to lose.
  *
  * @param rho
  *   distance in pixels from the image origin (top-left) to the line, along the normal.
  * @param theta
  *   angle of that normal in radians. 0 is a vertical line, `Pi/2` a horizontal one — the angle describes the
  *   *normal*, not the line, which is the usual source of confusion.
  */
final case class PolarLine(rho: Double, theta: Double)

/** A line segment with real endpoints, as `HoughLinesP` reports it.
  *
  * Integer, because the underlying Mat is `CV_32SC4`. Rounding here would invent precision OpenCV never
  * produced.
  */
final case class Segment(x1: Int, y1: Int, x2: Int, y2: Int):
  def start: Point = Point(x1.toDouble, y1.toDouble)
  def end: Point = Point(x2.toDouble, y2.toDouble)

  /** Euclidean length, useful for the near-universal "keep the long ones" filter. */
  def length: Double = math.hypot((x2 - x1).toDouble, (y2 - y1).toDouble)

/** A [[PolarLine]] plus its accumulator score.
  *
  * `HoughLinesWithAccumulator` exists precisely so the votes are visible; they are the only way to rank
  * results, since the plain transform already returns them sorted but discards the magnitudes. Votes are
  * whole numbers stored in a float channel, hence the narrowing to `Int`; `rho` and `theta` are widened to
  * `Double` like [[PolarLine]].
  */
final case class PolarLineWithVotes(rho: Double, theta: Double, votes: Int):
  def line: PolarLine = PolarLine(rho, theta)

/** The Hough transforms, as extension methods on a binary edge image.
  *
  * The receiver is read-only: none of these release or alias it, and none allocate a Mat the caller has to
  * account for. Parameter order deliberately departs from the C++ one — `threshold` is the only argument with
  * no sensible default, so it comes first and everything else is optional, making `edges.houghLinesP(50)` the
  * common case.
  */
extension (mat: Mat)

  /** The standard Hough transform: every line is infinite and expressed in polar form.
    *
    * @param threshold
    *   minimum accumulator votes for a line to be reported.
    * @param rho
    *   accumulator distance resolution, in pixels.
    * @param theta
    *   accumulator angle resolution, in radians.
    * @param srn
    *   divisor for a coarse-to-fine `rho`; `0` (with `stn`) selects the classic transform.
    * @param stn
    *   divisor for a coarse-to-fine `theta`.
    * @param minTheta
    *   lower bound on the reported angle, in radians.
    * @param maxTheta
    *   upper bound on the reported angle, in radians.
    * @throws IllegalArgumentException
    *   if the receiver is not a non-empty 8-bit single-channel image.
    */
  def houghLines(
      threshold: Int,
      rho: Double = 1.0,
      theta: Double = math.Pi / 180,
      srn: Double = 0.0,
      stn: Double = 0.0,
      minTheta: Double = 0.0,
      maxTheta: Double = math.Pi
  ): Seq[PolarLine] =
    Preconditions.requireGray8("houghLines", mat)
    Hough.decoding("houghLines"): out =>
      Imgproc.HoughLines(mat, out, rho, theta, threshold, srn, stn, minTheta, maxTheta)
      Hough.rows(out)(v => PolarLine(v(0), v(1)))

  /** The probabilistic Hough transform: finite segments with endpoints in image coordinates.
    *
    * @param threshold
    *   minimum accumulator votes for a line to be reported.
    * @param rho
    *   accumulator distance resolution, in pixels.
    * @param theta
    *   accumulator angle resolution, in radians.
    * @param minLineLength
    *   segments shorter than this are discarded. `0` keeps everything.
    * @param maxLineGap
    *   largest gap, in pixels, that will still be bridged into one segment.
    * @throws IllegalArgumentException
    *   if the receiver is not a non-empty 8-bit single-channel image.
    */
  def houghLinesP(
      threshold: Int,
      rho: Double = 1.0,
      theta: Double = math.Pi / 180,
      minLineLength: Double = 0.0,
      maxLineGap: Double = 0.0
  ): Seq[Segment] =
    Preconditions.requireGray8("houghLinesP", mat)
    Hough.decoding("houghLinesP"): out =>
      Imgproc.HoughLinesP(mat, out, rho, theta, threshold, minLineLength, maxLineGap)
      // CV_32SC4: these doubles are widened int32s, so toInt is exact rather than a rounding choice.
      Hough.rows(out)(v => Segment(v(0).toInt, v(1).toInt, v(2).toInt, v(3).toInt))

  /** As [[houghLines]], but keeps each line's accumulator score.
    *
    * @throws IllegalArgumentException
    *   if the receiver is not a non-empty 8-bit single-channel image.
    */
  def houghLinesWithAccumulator(
      threshold: Int,
      rho: Double = 1.0,
      theta: Double = math.Pi / 180,
      srn: Double = 0.0,
      stn: Double = 0.0,
      minTheta: Double = 0.0,
      maxTheta: Double = math.Pi
  ): Seq[PolarLineWithVotes] =
    Preconditions.requireGray8("houghLinesWithAccumulator", mat)
    Hough.decoding("houghLinesWithAccumulator"): out =>
      Imgproc.HoughLinesWithAccumulator(mat, out, rho, theta, threshold, srn, stn, minTheta, maxTheta)
      Hough.rows(out)(v => PolarLineWithVotes(v(0), v(1), v(2).toInt))

/** Shared plumbing for the three transforms. Not part of the public API. */
private object Hough:

  /** Runs `f` against a fresh output Mat and guarantees the Mat is freed.
    *
    * The Mat is allocated here rather than by the caller so there is exactly one release site, and it is
    * scoped through `Managed` like the library's other native temporaries. What the "nothing escapes"
    * argument constrains is the *return*, not the mechanism: `f` decodes to plain Scala data, so callers
    * receive a result they never have to release — the Mat itself dies inside the scope.
    */
  def decoding[A](operation: String)(f: Mat => Seq[A]): Seq[A] =
    Managed(Mat()).use(out => Cv.orThrow(operation)(f(out)))

  /** Decodes an `Nx1` multi-channel result row by row.
    *
    * `Mat.get(row, col)` is the only accessor that is depth-generic, which matters because the three
    * transforms disagree on element type. An empty result is `rows == 0` — with `cols == 1` and the element
    * type still set, so it is not detectable via `empty()` alone — and falls out of the loop bound rather
    * than needing a special case.
    */
  def rows[A](out: Mat)(decode: Array[Double] => A): Seq[A] =
    Vector.tabulate(out.rows())(i => decode(out.get(i, 0)))
