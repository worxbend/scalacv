package scalacv

import org.opencv.core.{CvType, Mat}

/** The high-level, fluent face of scalacv — an owned image you transform by chaining.
  *
  * `Image` is the layer to reach for first. It wraps a single native `Mat` and lets you express the common
  * OpenCV shape — read, transform, detect, annotate, write — as one readable chain:
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * for _ <- Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
  * yield ()
  * }}}
  *
  * ==Move semantics: a transform consumes the image==
  *
  * Every **transform** (`gray`, `blur`, `canny`, `resize`, `crop`, a `draw*`) returns a *new* `Image` and
  * **spends the one it was called on** — using the old handle afterwards throws `IllegalStateException`
  * rather than reading freed memory. That is what makes the chain leak-free without a scope: each step frees
  * (or hands on) the previous Mat, so a long pipeline holds exactly one live Mat at a time, never a pile of
  * intermediates. It is [[Mats.chain]]'s guarantee, surfaced as a type.
  *
  * The trade is that you cannot use one `Image` twice. To branch, take a [[copy]] first, or drop to the
  * mid-level API on a borrowed [[mat]].
  *
  * ==Queries borrow, terminals consume==
  *
  * A **query** ([[width]], [[faces]], [[qrCodes]], [[contours]]) only reads, so it leaves the image alive. A
  * **terminal** ([[write]], [[bytes]], [[close]]) consumes it and releases the Mat. If a value escapes the
  * chain without ever reaching a terminal it leaks, exactly as a stray [[Managed]] would — so prefer
  * [[Image.reading]], which closes for you even when the body already consumed the image (release is
  * idempotent).
  *
  * ==Not a wall==
  *
  * `Image` never hides the library underneath it. [[mat]] borrows the raw `org.opencv.core.Mat` for any
  * `org.opencv.*` call this type does not wrap; [[managed]] hands the whole [[Managed]] over. The high-level
  * API is the pleasant default, not a ceiling. Domain verbs that only *happen* to start from an image — face
  * and marker detection, pose and track overlays, OCR preparation, background replacement — are **extension
  * methods** brought in with `import scalacv.*`, not members of this class, which is why they are absent
  * below; they read `image.faces(detector)` all the same.
  *
  * ==Failures==
  *
  * A transform does not return an `Either`: when OpenCV itself rejects the pixels (a data-dependent failure
  * this library cannot foresee), the op throws [[CvError.NativeCall]], naming the operation — an unchecked
  * throw, so it is invisible at the call site. Argument mistakes this library *can* see are rejected up front
  * with `IllegalArgumentException`. Only the `Either`-returning boundary methods ([[Image.read]], [[write]],
  * [[bytes]], [[Image.decode]]) turn failure into a value. To fold a transform's throw into an `Either` too,
  * wrap it with [[Cv.attempt]]. Reusing an already-consumed image throws `IllegalStateException`; see
  * [[Managed]] for `-Dscalacv.trackOwnership=true`, which points the error at the consuming call.
  *
  * `Image` is `AutoCloseable`, so `scala.util.Using` manages it too.
  */
final class Image private (private val handle: Managed[Mat]) extends AutoCloseable:

  // -- Queries: borrow the Mat, leave this Image alive ---------------------------------------------

  /** Width in pixels. */
  def width: Int = handle.get.cols

  /** Height in pixels. */
  def height: Int = handle.get.rows

  /** `Size(width, height)`. */
  def size: Size = Size(width.toDouble, height.toDouble)

  /** Channel count — 3 for a BGR image, 1 for greyscale, 4 with alpha. */
  def channels: Int = handle.get.channels

  /** True for a 0×0 image with no pixels. */
  def isEmpty: Boolean = handle.get.empty()

  /** The underlying Mat, **borrowed** — for any `org.opencv.*` or mid-level extension call `Image` does not
    * wrap. It stays owned by this `Image`: read from it, pass it to a detector, but do not release it. This
    * is the escape hatch that keeps the low-level API one method away.
    */
  def mat: Mat = handle.get

  /** A copy of this image as a `java.awt.image.BufferedImage` — for AWT/Swing interop and, notably, automatic
    * display in a notebook (Almond renders a `BufferedImage`). Borrows this image, which stays alive. The
    * image must be 8-bit; a 4-channel image is flattened to BGR.
    */
  def toBufferedImage: java.awt.image.BufferedImage = Interop.toBufferedImage(handle.get)

  // -- Detection: borrow, return plain immutable data ----------------------------------------------
  //
  // Domain detectors that only start from an image live as extension methods in their own files — `qrCodes`
  // and `arucoMarkers` in Detectors.scala, `arMarkers` in Ar.scala, `faces` in FaceDetect.scala, `detectHaar`
  // in Cascades.scala — so this class stays about the image, not about every vision application. The generic
  // `contours` stays because it is core image processing, not a domain.

  /** Contours of a binary image — see the mid-level `findContours` for the retrieval/approximation knobs. */
  def contours(
      retrieval: ContourRetrieval = ContourRetrieval.External,
      approximation: ContourApproximation = ContourApproximation.Simple
  ): Seq[Contour] = handle.get.findContours(retrieval, approximation)

  // -- Transforms: consume this Image, return a fresh one ------------------------------------------

  /** Converts to single-channel greyscale (from BGR). */
  def gray: Image = convert(ColorConversion.BgrToGray)

  /** Converts between colour spaces; the channel count follows the conversion. */
  def convert(conversion: ColorConversion): Image = transform(_.cvtColor(conversion))

  /** A quick, radius-based Gaussian blur: `radius` 2 is a 5×5 kernel. `radius` 0 is the identity. For full
    * control over kernel and sigma, use [[gaussianBlur]].
    */
  def blur(radius: Int): Image =
    require(radius >= 0, s"blur radius cannot be negative, got $radius")
    // radius 0 is the identity, but it must still spend the receiver like every other branch: move the Mat
    // out of this handle into a fresh Image (no pixel copy) so the source is left spent, not aliased alive.
    if radius == 0 then Image(Managed(handle.take()))
    else
      val side = radius * 2 + 1
      transform(_.gaussianBlur(Size(side.toDouble, side.toDouble)))

  /** Gaussian blur with an explicit kernel and sigmas — the mid-level `Ops` signature. */
  def gaussianBlur(kernel: Size, sigmaX: Double = 0, sigmaY: Double = 0): Image =
    transform(_.gaussianBlur(kernel, sigmaX, sigmaY))

  /** Canny edge detection. The result is always `CV_8UC1`. `threshold1` is the weak (linking) edge and
    * `threshold2` the strong one; they are both `Double` and silently swappable, so name them at the call
    * site — `canny(threshold1 = 80, threshold2 = 160)` — when the values are not obviously ordered.
    */
  def canny(
      threshold1: Double,
      threshold2: Double,
      apertureSize: Int = 3,
      l2Gradient: Boolean = false
  ): Image =
    transform(_.canny(threshold1, threshold2, apertureSize, l2Gradient))

  /** Histogram equalisation — `CV_8UC1` only, so usually preceded by [[gray]]. */
  def equalizeHist: Image = transform(_.equalizeHist())

  /** Fixed or automatic thresholding. Drops the computed value ([[Threshold.Auto]] users who need it should
    * use the mid-level `threshold`, which returns it in a [[Thresholded]]); this is the common "binarise"
    * case.
    */
  def threshold(
      value: Double,
      maxValue: Double = 255,
      kind: Threshold = Threshold.Binary
  ): Image =
    transform(_.threshold(value, maxValue, kind).image)

  /** Resizes to an absolute pixel size. */
  def resizeTo(size: Size, interpolation: Interpolation = Interpolation.Linear): Image =
    transform(_.resize(size, interpolation))

  /** Resizes to absolute `width`×`height`. */
  def resize(width: Int, height: Int): Image = resizeTo(Size(width.toDouble, height.toDouble))

  /** Scales by a single factor on both axes — `0.5` halves each side. */
  def scale(factor: Double, interpolation: Interpolation = Interpolation.Linear): Image =
    require(factor > 0, s"scale factor must be positive, got $factor")
    transform(_.scaled(factor, factor, interpolation))

  /** Crops to `rect`, returning an independent copy (not an aliasing view). The rectangle must lie within the
    * image.
    */
  def crop(rect: Rect): Image =
    require(
      rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height,
      s"crop $rect does not fit inside ${width}x$height"
    )
    // submat is an aliasing view of the parent's data; clone makes it independent, and the view Mat is
    // released before the parent so no header is stranded. Going through `transform` is what releases the
    // parent even when submat or clone throws.
    transform(m => Managed.use(m.submat(rect.toCv))(view => Managed(view.clone())))

  /** Mirrors the image — see [[Flip]]. */
  def flip(how: Flip): Image = transform(_.flip(how))

  /** A lossless quarter-turn rotation — see [[Rotation]]. */
  def rotate(rotation: Rotation): Image = transform(_.rotate(rotation))

  /** Rotates by an arbitrary angle (degrees, counter-clockwise), expanding the canvas so no corner is
    * clipped. `scale` zooms at the same time.
    */
  def rotate(degrees: Double, scale: Double = 1.0): Image = transform(_.rotated(degrees, scale))

  /** Removes lens distortion using calibrated camera [[Intrinsics]], straightening the lines a real lens
    * bends. With an uncalibrated [[Intrinsics.approx]] guess (no distortion) this is a plain copy. Given a
    * [[Calibration]] value, the `undistort(calibration)` extension overload (in `Calibration.scala`) unwraps
    * its intrinsics for you.
    */
  def undistort(intrinsics: Intrinsics): Image = transform(_.undistorted(intrinsics))

  /** Adds a uniform border of `size` pixels on every side. */
  def pad(size: Int, borderType: BorderType = BorderType.Constant, color: Scalar = Scalar.Black): Image =
    transform(_.border(size, size, size, size, borderType, color))

  /** Adds a border of independent widths per side. */
  def border(
      top: Int,
      bottom: Int,
      left: Int,
      right: Int,
      borderType: BorderType = BorderType.Constant,
      color: Scalar = Scalar.Black
  ): Image = transform(_.border(top, bottom, left, right, borderType, color))

  /** Median blur (`radius` 1 = 3×3) — the standard cure for salt-and-pepper noise. */
  def medianBlur(radius: Int): Image =
    require(radius >= 1, s"medianBlur radius must be ≥ 1, got $radius")
    transform(_.medianBlur(radius * 2 + 1))

  /** Edge-preserving bilateral filter — smooths while keeping edges crisp (slower than a Gaussian). */
  def bilateralFilter(diameter: Int = 9, sigmaColor: Double = 75, sigmaSpace: Double = 75): Image =
    transform(_.bilateralFilter(diameter, sigmaColor, sigmaSpace))

  /** Adaptive threshold — a per-neighbourhood threshold that holds up under uneven lighting (document scans,
    * OCR prep). `CV_8UC1` only, so usually after [[gray]].
    *
    * The parameter order here leads with the two you actually tune (`blockSize`, `c`); the mid-level
    * `Ops.adaptiveThreshold` instead mirrors OpenCV's own `(maxValue, method, blockSize, c)` order. The
    * divergence is deliberate, and it cannot bite silently: the leading parameters have different types
    * across the tiers (`blockSize: Int` here vs `maxValue: Double` there), so a positional call meant for one
    * tier does not compile against the other. Use named arguments —
    * `adaptiveThreshold(blockSize = 15, c = 4)` — and the order stops mattering entirely.
    */
  def adaptiveThreshold(
      blockSize: Int = 11,
      c: Double = 2.0,
      method: AdaptiveMethod = AdaptiveMethod.Gaussian,
      mode: Threshold.Mode = Threshold.Mode.Binary
  ): Image = transform(_.adaptiveThreshold(255, method, blockSize, c, mode))

  /** Morphological erosion — shrinks bright regions, clears small bright specks. */
  def erode(radius: Int = 1, shape: MorphShape = MorphShape.Rect): Image = transform(_.erode(radius, shape))

  /** Morphological dilation — grows bright regions, fills small dark gaps. */
  def dilate(radius: Int = 1, shape: MorphShape = MorphShape.Rect): Image = transform(_.dilate(radius, shape))

  /** A compound morphological operation — opening, closing, gradient, top-hat, black-hat. See [[MorphOp]].
    * (There is no bare `close` method because `close()` already releases the image.)
    */
  def morphology(op: MorphOp, radius: Int = 1, shape: MorphShape = MorphShape.Rect): Image =
    transform(_.morphology(op, radius, shape))

  /** Inverts the image (`255 - v`). */
  def invert: Image = transform(_.bitwiseNot())

  /** Brightness/contrast in one step: `contrast` scales (1.0 = unchanged), `brightness` shifts (0 =
    * unchanged).
    */
  def adjust(brightness: Double = 0, contrast: Double = 1.0): Image =
    transform(_.convertScaleAbs(contrast, brightness))

  /** Converts BGR → HSV — the space to threshold in for colour segmentation (see [[inRange]]). */
  def toHsv: Image = convert(ColorConversion.BgrToHsv)

  /** Unsharp-mask sharpening; `amount` ~1 is a firm sharpen, higher haloes the edges. */
  def sharpen(amount: Double = 1.0): Image = transform(_.sharpen(amount))

  /** Min-max normalises values into `[min, max]` — a quick contrast stretch, and the standard way of making a
    * non-8-bit result (a disparity map, a distance transform, a float Sobel response) displayable.
    *
    * `depth` defaults to [[OutputDepth.Unsigned8]], so a `CV_32F` image comes back as `CV_8U` and is actually
    * viewable; for an already-8-bit image that is a no-op conversion, so a plain contrast stretch is
    * unaffected. Pass [[OutputDepth.SameAsSource]] to rescale without giving up the source's precision — a
    * float image stretched into `[0, 1]` for a model's input, say. See `Mat.normalize` for the full argument.
    */
  def normalize(
      min: Double = 0,
      max: Double = 255,
      depth: OutputDepth = OutputDepth.Unsigned8
  ): Image = transform(_.normalize(min, max, depth))

  /** Extracts a single channel as its own single-channel image. */
  def channel(index: Int): Image = transform(_.extractChannel(index))

  /** False-colours a single-channel image (depth, motion, data) into a heatmap — see [[Colormap]]. Makes the
    * output of [[StereoDepth]], [[MotionDetector]] and friends actually visible.
    */
  def colorMap(map: Colormap): Image = transform(_.colorMap(map))

  /** A smooth, painterly cartoon stylisation. */
  def stylize(strength: Float = 60, detail: Float = 0.45f): Image = transform(_.stylize(strength, detail))

  /** A colour pencil-sketch rendering. */
  def sketch(strength: Float = 60, detail: Float = 0.07f, shade: Float = 0.02f): Image =
    transform(_.pencilSketch(strength, detail, shade))

  /** Detail enhancement — boosts local contrast and texture. */
  def enhance(strength: Float = 10, detail: Float = 0.15f): Image = transform(
    _.detailEnhance(strength, detail)
  )

  /** Edge-preserving smoothing — flattens texture while keeping edges. */
  def edgePreserving(strength: Float = 60, detail: Float = 0.4f): Image = transform(
    _.edgePreserving(strength, detail)
  )

  /** Inpaints the region under `mask` (non-zero = repair) from its surroundings — remove a scratch, object,
    * or watermark. `mask` is borrowed.
    */
  def inpaint(mask: Image, radius: Double = 3.0): Image = transform(_.inpaint(mask.mat, radius))

  /** Seamlessly clones this image (the object, under `mask`) into `background` at `center` via Poisson
    * blending — an invisible paste, the compositing behind a good virtual background. `background` and `mask`
    * are borrowed; the result is `background`-sized.
    */
  def seamlessCloneInto(background: Image, mask: Image, center: Point): Image =
    transform(_.seamlessCloneInto(background.mat, mask.mat, center))

  /** Sepia tone. */
  def sepia: Image = transform(_.sepia)

  /** Gamma correction (`< 1` darkens, `> 1` lifts the mid-tones). */
  def gamma(g: Double): Image = transform(_.gamma(g))

  /** Posterises to `levels` tones per channel. */
  def posterize(levels: Int): Image = transform(_.posterize(levels))

  /** Emboss. */
  def emboss: Image = transform(_.emboss)

  /** Saturation (`> 1` vivid, `< 1` muted, `0` grey). */
  def saturate(factor: Double): Image = transform(_.saturate(factor))

  /** Colour temperature (`> 0` warm, `< 0` cool). */
  def temperature(shift: Double): Image = transform(_.temperature(shift))

  /** Applies a named, composable [[Filter]] — `image.filter(Filter.vintage)`. */
  def filter(f: Filter): Image = f(this)

  /** Detects the text skew and rotates the image upright — the OCR straightening step. See `Ops.deskew`. */
  def deskew(maxAngle: Double = 45.0): Image = transform(_.deskew(maxAngle))

  /** A binary mask of the pixels whose channels all fall within `[lo, hi]` — the core of colour segmentation.
    * Consumes this image and returns the mask; usually run after [[toHsv]].
    */
  def inRange(lo: Scalar, hi: Scalar): Image = transform(_.inRange(lo, hi))

  /** Keeps this image only where `mask` is non-zero (elsewhere black). `mask` is borrowed, not consumed. */
  def applyMask(mask: Image): Image = transform(_.masked(mask.mat))

  /** Alpha-blends `other` over this image: `this * weight + other * (1 - weight)`. Both must match in size
    * and type; `other` is borrowed.
    */
  def blend(other: Image, weight: Double = 0.5): Image =
    require(weight >= 0 && weight <= 1, s"blend weight must be in [0, 1], got $weight")
    transform(_.addWeighted(weight, other.mat, 1 - weight))

  // -- Drawing: mutate in place (we own the Mat), consume this Image -------------------------------

  /** Draws an axis-aligned rectangle. Pass [[Thickness.Filled]] for a solid block. */
  def drawRect(rect: Rect, color: Scalar = Scalar.White, thickness: Thickness = Thickness.Default): Image =
    paint(_.drawRect(rect, color, thickness))

  /** Draws a circle. */
  def drawCircle(
      center: Point,
      radius: Int,
      color: Scalar = Scalar.White,
      thickness: Thickness = Thickness.Default
  ): Image =
    paint(_.drawCircle(center, radius, color, thickness))

  /** Draws text with its baseline's left end at `at` (see [[Draw]] for the baseline caveat). */
  def drawText(text: String, at: Point, color: Scalar = Scalar.White, scale: Double = 1.0): Image =
    paint(_.drawText(text, at, color, scale = scale))

  /** Draws every contour — [[Thickness.Filled]] turns them back into a mask. */
  def drawContours(
      contours: Seq[Contour],
      color: Scalar = Scalar.White,
      thickness: Thickness = Thickness.Default
  ): Image =
    paint(_.drawContours(contours, color, thickness))

  /** Draws every rectangle in one pass — detector bounding boxes, motion regions, ROIs. */
  def drawRects(
      rects: Seq[Rect],
      color: Scalar = Scalar.Green,
      thickness: Thickness = Thickness.Default
  ): Image = paint(m => rects.foreach(r => m.drawRect(r, color, thickness)))

  // Domain overlays that turn a domain result into pixels — `markFaces` (FaceDetect.scala), `drawSkeleton`
  // (Pose.scala), `drawMarkerAxes`/`drawMarkerCube` (Ar.scala), `drawTracks` (ObjectTracker.scala), and
  // `draw` for the Picture layer (Graphics.scala) — are extension methods in their own files, built on
  // `paint`.

  // -- Terminals: consume this Image and release ---------------------------------------------------

  /** Writes to `path`, choosing the encoder from its extension, then releases. */
  def write(path: String): Either[CvError, Unit] =
    try Images.write(path, handle.get)
    finally close()

  /** Encodes to an in-memory image file (`".png"`, `".jpg"`, …), then releases. */
  def bytes(format: String = ".png"): Either[CvError, Array[Byte]] =
    try Images.encode(handle.get, format)
    finally close()

  /** Hands the underlying [[Managed]] over and spends this `Image` — for when you want to keep managing the
    * Mat directly. Ownership transfers to the returned `Managed`.
    */
  def managed: Managed[Mat] = Managed(handle.take())

  /** Releases the native memory. Idempotent, and called for you by [[Image.reading]] and `Using`. */
  def close(): Unit = handle.release()

  /** An independent deep copy, so the original can be used again (move semantics otherwise forbid it). */
  def copy: Image = Image(Managed(handle.get.clone()))

  override def toString: String =
    if handle.isReleased then "Image(<closed>)" else s"Image(${width}x$height, ${channels}ch)"

  // -- internals -----------------------------------------------------------------------------------

  /** A pure transform: run `op` on the borrowed Mat (it returns a fresh owned Mat via [[Mats.produce]]), then
    * release the source. Identical to [[Ops.pipe]]; a failure in `op` still releases the source and `op`'s
    * own half-built Mat, so nothing leaks.
    */
  private def transform(op: Mat => Managed[Mat]): Image =
    try Image(op(handle.get))
    finally handle.release()

  /** An in-place draw: take the Mat (spending this handle without freeing), mutate it, rewrap it. No copy.
    * `private[scalacv]` rather than `private` so a domain's drawing verbs can live as extension methods in
    * their own file (e.g. `drawSkeleton` in `Pose.scala`) instead of swelling this class.
    */
  private[scalacv] def paint(draw: Mat => Unit): Image =
    val m = handle.take()
    try
      draw(m)
      Image(Managed(m))
    catch
      case e: Throwable =>
        m.release()
        throw e

object Image:

  private[scalacv] def apply(handle: Managed[Mat]): Image = new Image(handle)

  /** Reads an image from the filesystem. `Left` if the path is missing, is a directory, is empty, or does not
    * decode — four cases the error message tells apart, because the read goes through the JVM's own file I/O
    * rather than through `imread`, which reports all of them as one bare `false` (see [[Images.read]]).
    */
  def read(path: String, flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Image] =
    Images.read(path, flags).map(apply)

  /** Decodes an image from bytes already in memory — an HTTP body, a BLOB, a fixture. */
  def decode(bytes: Array[Byte], flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Image] =
    Images.decode(bytes, flags).map(apply)

  /** Adopts an existing [[Managed]]`[Mat]` as an `Image`. Ownership transfers: do not also release the
    * `Managed` yourself.
    */
  def wrap(handle: Managed[Mat]): Image = apply(handle)

  /** Builds an `Image` from a `java.awt.image.BufferedImage` — the reverse of [[Image.toBufferedImage]], for
    * pulling a frame in from AWT/Swing, `ImageIO`, or a notebook. Always produces a 3-channel BGR image.
    */
  def fromBufferedImage(image: java.awt.image.BufferedImage): Image = wrap(Managed(Interop.toMat(image)))

  /** A blank canvas, filled with `color`. `channels` is 1, 3 or 4. */
  def blank(width: Int, height: Int, color: Scalar = Scalar.Black, channels: Int = 3): Image =
    require(width > 0 && height > 0, s"a blank Image needs a positive size, got ${width}x$height")
    require(channels == 1 || channels == 3 || channels == 4, s"channels must be 1, 3 or 4, got $channels")
    // The require is the single channel check; the match is total over what it admits, so no dead fallback.
    val cvType = channels match
      case 1 => CvType.CV_8UC1
      case 3 => CvType.CV_8UC3
      case _ => CvType.CV_8UC4
    apply(Managed(Mat(height, width, cvType, color.toCv)))

  /** Reads `path` and runs `use` on the resulting `Image`, closing it afterwards — even if `use` already
    * consumed it (close is idempotent) and even on an exception. The scoped, forget-nothing entry point.
    *
    * {{{
    * Image.reading("photo.jpg")(_.gray.canny(80, 160).write("edges.png"))
    * }}}
    *
    * Do not let the `Image` (or one derived from it) escape `use`; it is closed when the block returns.
    *
    * The whole `use` body runs inside [[Cv.attempt]], so a [[CvError.NativeCall]] thrown by a transform in
    * the chain (`gray`/`canny`/…) comes back as a `Left` rather than escaping past the `Either` — the return
    * type is honest about failure, not just about the read. Programmer errors (`IllegalArgumentException`,
    * use-after-move) still throw, as everywhere else.
    */
  def reading[A](path: String, flags: ImreadFlags = ImreadFlags.Color)(use: Image => A): Either[CvError, A] =
    Scoped.using(read(path, flags), "reading")(use)
