package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/* Imgproc and Core operations as extension methods on `Mat`.
 *
 * ==The ownership contract==
 *
 * Every operation in this file is **pure with respect to its receiver**: it allocates a fresh destination
 * Mat, writes the result there, and hands that back as a `Managed[Mat]` that the **caller now owns and must
 * release**. The receiver is never written to, never released, and never aliased into the result — the two
 * Mats have different `dataAddr()`s, so releasing one cannot invalidate the other. Nothing here takes
 * ownership of the receiver either, which is why an op can be applied to a borrowed Mat (a video frame, a
 * detector's input) without any transfer-of-ownership ceremony at the call site. There are no in-place
 * variants; if one is ever added it will say so in its name and return `Unit`, so that a mutating call can
 * never be mistaken for a pure one at a glance.
 *
 * The corollary is that a two-step pipeline written naively strands a Mat:
 *
 * {{{
 * val edges = src.gaussianBlur(Size(5, 5), 1.5).use(_.canny(50, 150)) // the blur output is freed,
 *                                                                     // but `use` returns a Mat that
 *                                                                     // outlives its own Managed
 * }}}
 *
 * [[pipe]] exists for exactly that shape: it feeds the intermediate to the next stage and releases it once
 * that stage has produced its own output, so the intermediate cannot be leaked and cannot be used after the
 * chain moves on. [[Mats.chain]] is the n-stage form.
 *
 * ==Errors==
 *
 * Preconditions that are programmer errors (an even Gaussian kernel, a zero target size) are `require`d and
 * throw [[IllegalArgumentException]]. Everything OpenCV itself rejects arrives as a `CvException` from
 * native code and is rethrown as [[CvError.NativeCall]] naming the operation — see [[Cv]]. In that case the
 * half-built destination Mat is released before the throw propagates, so a failed op leaks nothing.
 *
 * ==What lives elsewhere==
 *
 * This file keeps the core imgproc verbs. The social-media effects (sepia, stylize and friends) are in
 * Effects.scala, the deskew document pipeline is in Deskew.scala, and the `Mats` helpers that do not belong
 * on a Mat are in Mats.scala — all four share this contract and the `scalacv` package, so nothing at a call
 * site changes.
 */

/** The destination depth for the operators that can change it — the derivative operators, and [[normalize]].
  *
  * Worth a type of its own rather than a bare `int` because [[OutputDepth.SameAsSource]] is a trap on the
  * commonest input: `Sobel` on an 8-bit unsigned image with `ddepth = -1` clips every negative derivative to
  * zero, so half of each edge silently disappears. [[Signed16]] then [[convertScaleAbs]] is the standard fix.
  */
enum OutputDepth(val cvValue: Int):

  /** `ddepth = -1` — the destination gets the source's depth. */
  case SameAsSource extends OutputDepth(-1)
  case Unsigned8 extends OutputDepth(CvType.CV_8U)
  case Signed16 extends OutputDepth(CvType.CV_16S)
  case Float32 extends OutputDepth(CvType.CV_32F)
  case Float64 extends OutputDepth(CvType.CV_64F)

extension (self: Mat)

  /** Converts between colour spaces. The channel count of the result follows the conversion, not the source.
    */
  def cvtColor(conversion: ColorConversion): Managed[Mat] =
    Mats.produce("cvtColor")(Imgproc.cvtColor(self, _, conversion.cvValue))

  /** Gaussian blur.
    *
    * `kernel` may be `Size(0, 0)`, in which case OpenCV derives the kernel from the sigmas; otherwise both
    * extents must be positive and odd. A `sigmaY` of 0 means "same as `sigmaX`", which is OpenCV's own
    * default and not a degenerate value.
    *
    * `border` may not be [[BorderType.Wrap]] — see [[BorderType.requireFilterSupport]].
    */
  def gaussianBlur(
      kernel: Size,
      // 0 means "derive the deviation from the kernel size", which is OpenCV's own default and
      // the overwhelmingly common call. Requiring it explicitly made the commonest use of the
      // commonest filter needlessly verbose; the require below still rejects the one combination
      // that is meaningless, a zero kernel with no sigma to derive from.
      sigmaX: Double = 0,
      sigmaY: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    Mats.requireKernel("gaussianBlur", kernel, allowZero = true)
    require(sigmaX > 0 || kernel.width > 0, "gaussianBlur needs either a positive sigmaX or a real kernel")
    BorderType.requireFilterSupport("gaussianBlur", border)
    Mats.produce("gaussianBlur"):
      Imgproc.GaussianBlur(self, _, kernel.toCv, sigmaX, sigmaY, border.cvValue)

  /** Normalised box filter. `anchor` defaults to `Point(-1, -1)`, OpenCV's spelling of "the kernel centre".
    *
    * Named `boxBlur`, not `blur`, on purpose: the high-level [[Image.blur]] is a radius-based *Gaussian*, and
    * a mid-level method sharing that name would silently switch filter families (and output hash) the moment
    * a caller drops from `image.blur(2)` to `image.mat.blur(...)`. The two are different algorithms; the
    * names say so.
    *
    * `border` may not be [[BorderType.Wrap]] — see [[BorderType.requireFilterSupport]].
    */
  def boxBlur(
      kernel: Size,
      anchor: Point = Point(-1, -1),
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    Mats.requireKernel("boxBlur", kernel, allowZero = false)
    BorderType.requireFilterSupport("boxBlur", border)
    Mats.produce("boxBlur")(Imgproc.blur(self, _, kernel.toCv, anchor.toCv, border.cvValue))

  /** Canny edge detection. The result is always `CV_8UC1` regardless of the source type.
    *
    * OpenCV accepts only 3, 5 and 7 for `apertureSize` — the Sobel aperture used internally — and aborts in
    * native code for anything else, so it is checked here instead.
    */
  def canny(
      threshold1: Double,
      threshold2: Double,
      apertureSize: Int = 3,
      l2Gradient: Boolean = false
  ): Managed[Mat] =
    require(
      apertureSize == 3 || apertureSize == 5 || apertureSize == 7,
      s"canny apertureSize must be 3, 5 or 7, not $apertureSize"
    )
    Mats.produce("canny"):
      Imgproc.Canny(self, _, threshold1, threshold2, apertureSize, l2Gradient)

  /** Sobel derivative.
    *
    * See [[OutputDepth]] before leaving `depth` at its default on an 8-bit image. `border` may not be
    * [[BorderType.Wrap]] — see [[BorderType.requireFilterSupport]].
    */
  def sobel(
      dx: Int,
      dy: Int,
      kernelSize: Int = 3,
      depth: OutputDepth = OutputDepth.SameAsSource,
      scale: Double = 1,
      delta: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    require(dx >= 0 && dy >= 0 && (dx + dy) > 0, s"sobel needs a derivative order: dx=$dx dy=$dy")
    require(
      kernelSize == -1 || (kernelSize > 0 && kernelSize % 2 == 1),
      s"sobel kernelSize must be odd and positive, or -1 for the 3x3 Scharr kernel, not $kernelSize"
    )
    BorderType.requireFilterSupport("sobel", border)
    Mats.produce("sobel"):
      Imgproc.Sobel(self, _, depth.cvValue, dx, dy, kernelSize, scale, delta, border.cvValue)

  /** Laplacian. `kernelSize` of 1 is the 3x3 aperture OpenCV special-cases, and is its default.
    *
    * `border` may not be [[BorderType.Wrap]] — see [[BorderType.requireFilterSupport]].
    */
  def laplacian(
      kernelSize: Int = 1,
      depth: OutputDepth = OutputDepth.SameAsSource,
      scale: Double = 1,
      delta: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    require(
      kernelSize > 0 && kernelSize % 2 == 1,
      s"laplacian kernelSize must be odd and positive, not $kernelSize"
    )
    BorderType.requireFilterSupport("laplacian", border)
    Mats.produce("laplacian"):
      Imgproc.Laplacian(self, _, depth.cvValue, kernelSize, scale, delta, border.cvValue)

  /** Histogram equalisation. OpenCV accepts `CV_8UC1` only; anything else fails in native code. */
  def equalizeHist(): Managed[Mat] =
    Mats.produce("equalizeHist")(Imgproc.equalizeHist(self, _))

  /** Thresholding.
    *
    * Returns a [[Thresholded]] — the thresholded image **and** the `double` OpenCV computed. Most wrappers
    * drop that number; for [[Threshold.Auto.Otsu]] and [[Threshold.Auto.Triangle]] it is the threshold OpenCV
    * chose, which is frequently the reason the call was made. For a fixed threshold it is just `value` handed
    * back. A named pair, not a tuple, so `.image` chains with [[pipe]] like every other op.
    *
    * `Imgproc.threshold` has a single 5-argument overload with no defaults, so every argument is spelled out
    * here rather than being layered over Java defaults that do not exist.
    */
  def threshold(
      value: Double,
      maxValue: Double = 255,
      kind: Threshold = Threshold.Binary
  ): Thresholded =
    // `Mats.produce` fills a destination and returns only that, so the `double` OpenCV computes has to be
    // carried out of the callback by hand. It is written exactly once, before `produce` returns, so the var
    // never outlives this expression.
    var computed = 0.0
    val out = Mats.produce("threshold"): dst =>
      computed = Imgproc.threshold(self, dst, value, maxValue, kind.cvValue)
    Thresholded(out, ThresholdResult(computed))

  /** Resizes to an absolute size, given here as a [[Size]] whose two `Double` extents are **truncated toward
    * zero** on the way into native code: `Size(1.9, 1.9)` asks for a 1×1 image.
    *
    * That truncation is why the check below is on the truncated integers and not on the doubles. A computed
    * target such as `Size(width * factor, height * factor)` with a small factor lands between 0 and 1, which
    * is positive as a `Double` but empty as a `cv::Size`, and OpenCV then aborts with
    * `CV_Assert(inv_scale_x > 0)` — a `CvError.NativeCall` quoting a C++ expression, in place of the
    * [[IllegalArgumentException]] naming the caller's own argument that this file promises for a zero target
    * size. Checking after truncation is what `Mats.requireKernel` already does for kernels.
    */
  def resize(size: Size, interpolation: Interpolation = Interpolation.Linear): Managed[Mat] =
    val w = size.width.toInt
    val h = size.height.toInt
    require(w > 0 && h > 0, s"resize needs a target of at least 1x1 pixel; $size truncates to ${w}x$h")
    Mats.produce("resize"):
      Imgproc.resize(self, _, size.toCv, 0, 0, interpolation.cvValue)

  /** Resizes by independent x and y scale factors. Rejects a pair of factors that would round this Mat's own
    * size down to an empty one.
    *
    * A separate method rather than an overload because OpenCV distinguishes the two modes by passing
    * `Size(0, 0)` — a sentinel that has no business in a typed API.
    *
    * Positive factors are not on their own enough to know the call is legal: OpenCV derives the destination
    * from the receiver as `cvRound(cols * fx)` × `cvRound(rows * fy)` and then asserts `!dsize.empty()`, so
    * shrinking a small image hard enough (a 100-pixel sprite at `fx = 0.005`) dies in native code. The check
    * therefore has to be against the receiver's extent, not against the factors.
    *
    * `math.rint` and not `.toInt` or `math.round`, because `cvRound` rounds half **to even**: on a 100-wide
    * source `fx = 0.006` legitimately yields a 1-pixel result that truncation would reject, and `fx = 0.025`
    * yields 2 where `math.round` says 3. Note the asymmetry with [[resize]], where the destination arrives as
    * a `cv::Size` and is truncated instead — the two native paths genuinely round differently, so one shared
    * rule would be wrong for one of them.
    */
  def scaled(fx: Double, fy: Double, interpolation: Interpolation = Interpolation.Linear): Managed[Mat] =
    require(fx > 0 && fy > 0, s"scaled needs positive factors, got fx=$fx fy=$fy")
    val w = math.rint(self.cols * fx).toInt
    val h = math.rint(self.rows * fy).toInt
    require(
      w > 0 && h > 0,
      s"scaled by fx=$fx fy=$fy shrinks ${self.cols}x${self.rows} to ${w}x$h, which is empty"
    )
    Mats.produce("scaled"):
      Imgproc.resize(self, _, Size(0, 0).toCv, fx, fy, interpolation.cvValue)

  /** Scales, takes the absolute value, and saturating-casts to 8-bit unsigned.
    *
    * The companion to a [[OutputDepth.Signed16]] [[sobel]]: it is what turns a signed derivative back into
    * something displayable without losing the negative lobe.
    */
  def convertScaleAbs(alpha: Double = 1, beta: Double = 0): Managed[Mat] =
    Mats.produce("convertScaleAbs")(Core.convertScaleAbs(self, _, alpha, beta))

  /** Weighted sum: `self * alpha + other * beta + gamma`.
    *
    * `other` is borrowed, exactly like the receiver — it is neither released nor aliased.
    */
  def addWeighted(alpha: Double, other: Mat, beta: Double, gamma: Double = 0): Managed[Mat] =
    Mats.produce("addWeighted")(Core.addWeighted(self, alpha, other, beta, gamma, _))

  /** Median blur — each pixel becomes the median of its `ksize`×`ksize` neighbourhood. The standard cure for
    * salt-and-pepper noise, and unlike a Gaussian it does not smear edges. `ksize` must be odd and ≥ 3.
    */
  def medianBlur(ksize: Int): Managed[Mat] =
    require(ksize >= 3 && ksize % 2 == 1, s"medianBlur ksize must be odd and ≥ 3, got $ksize")
    Mats.produce("medianBlur")(Imgproc.medianBlur(self, _, ksize))

  /** Edge-preserving bilateral filter: smooths flat regions while keeping edges crisp. Markedly slower than a
    * Gaussian. `diameter` ≤ 0 lets OpenCV derive it from `sigmaSpace`.
    */
  def bilateralFilter(diameter: Int, sigmaColor: Double, sigmaSpace: Double): Managed[Mat] =
    Mats.produce("bilateralFilter")(Imgproc.bilateralFilter(self, _, diameter, sigmaColor, sigmaSpace))

  /** Adaptive threshold — a threshold computed per neighbourhood rather than once for the whole image, which
    * is what makes it hold up under uneven lighting (document scans, OCR pre-processing). `CV_8UC1` only.
    *
    * `mode` is restricted to [[Threshold.Mode.Binary]] / [[Threshold.Mode.BinaryInv]] because OpenCV's
    * `adaptiveThreshold` accepts exactly those two; it replaces the `inverse: Boolean` this parameter used to
    * be — a boolean trap at the call site (`inverse = true` says nothing about *what* is inverted) when
    * `Threshold.Mode` already models the distinction by name.
    *
    * @param blockSize
    *   the neighbourhood side; must be odd and ≥ 3.
    * @param c
    *   a constant subtracted from the local mean/Gaussian — raise it to keep less.
    */
  def adaptiveThreshold(
      maxValue: Double = 255,
      method: AdaptiveMethod = AdaptiveMethod.Gaussian,
      blockSize: Int = 11,
      c: Double = 2.0,
      mode: Threshold.Mode = Threshold.Mode.Binary
  ): Managed[Mat] =
    require(
      blockSize >= 3 && blockSize % 2 == 1,
      s"adaptiveThreshold blockSize must be odd and ≥ 3, got $blockSize"
    )
    require(
      mode == Threshold.Mode.Binary || mode == Threshold.Mode.BinaryInv,
      s"adaptiveThreshold accepts only Binary or BinaryInv, not $mode — OpenCV has no adaptive " +
        "Truncate/ToZero"
    )
    Mats.produce("adaptiveThreshold"):
      Imgproc.adaptiveThreshold(self, _, maxValue, method.cvValue, mode.cvValue, blockSize, c)

  /** Mirrors the image across an axis — see [[Flip]]. */
  def flip(flip: Flip): Managed[Mat] =
    Mats.produce("flip")(Core.flip(self, _, flip.cvValue))

  /** A lossless quarter-turn rotation — exact pixels, no interpolation. See [[Rotation]]. */
  def rotate(rotation: Rotation): Managed[Mat] =
    Mats.produce("rotate")(Core.rotate(self, _, rotation.cvValue))

  /** Rotates by an arbitrary angle (degrees, counter-clockwise) about the centre, **expanding the canvas so
    * no corner is clipped**. `scale` zooms at the same time. The exposed border is filled per `border`.
    *
    * The fill is named `color` here just as in [[border]], and the `Constant` default — where the filters
    * default to [[BorderType.Reflect101]] — is deliberate: a filter reflects so the kernel's support region
    * stays *inside* the image content, which avoids edge ringing; a geometric transform exposes pixels that
    * were never in the frame at all, and there a reflected or replicated edge would smear the image's own
    * content into the border. A constant outside colour (black, or white for a scanned page) reads as
    * background instead.
    */
  def rotated(
      degrees: Double,
      scale: Double = 1.0,
      interpolation: Interpolation = Interpolation.Linear,
      border: BorderType = BorderType.Constant,
      color: Scalar = Scalar.Black
  ): Managed[Mat] =
    require(scale > 0, s"rotated scale must be positive, got $scale")
    warpAboutCenter(self, degrees, scale, expandCanvas = true, interpolation, border, color, "warpAffine")

  /** Removes lens distortion using calibrated camera [[Intrinsics]] — the barrel/pincushion bend a real lens
    * adds is mapped back out, so straight edges in the world come back straight. A no-op (a plain copy) when
    * `intrinsics.distortion` is empty. See [[Calibration]].
    */
  def undistorted(intrinsics: Intrinsics): Managed[Mat] =
    Managed.scope: own =>
      val camera = own(intrinsics.cameraMatrix)
      val dist = own(intrinsics.distCoeffs)
      // The destination is deliberately NOT owned by the scope — it is what this method hands back.
      Mats.produce("undistort")(dst => Calib3d.undistort(self, dst, camera, dist))

  /** Adds a border (padding) of the given pixel widths on each side. */
  def border(
      top: Int,
      bottom: Int,
      left: Int,
      right: Int,
      borderType: BorderType = BorderType.Constant,
      color: Scalar = Scalar.Black
  ): Managed[Mat] =
    require(
      top >= 0 && bottom >= 0 && left >= 0 && right >= 0,
      s"border widths cannot be negative: top=$top bottom=$bottom left=$left right=$right"
    )
    Mats.produce("copyMakeBorder"):
      Core.copyMakeBorder(self, _, top, bottom, left, right, borderType.cvValue, color.toCv)

  /** Morphological erosion with a `radius`-derived structuring element — shrinks bright regions, removes
    * small bright specks. `iterations` applies it repeatedly.
    */
  def erode(radius: Int = 1, shape: MorphShape = MorphShape.Rect, iterations: Int = 1): Managed[Mat] =
    withStructuringElement("erode", radius, shape, iterations): (kernel, iters) =>
      Mats.produce("erode")(Imgproc.erode(self, _, kernel, Point(-1, -1).toCv, iters))

  /** Morphological dilation — grows bright regions, fills small dark gaps. */
  def dilate(radius: Int = 1, shape: MorphShape = MorphShape.Rect, iterations: Int = 1): Managed[Mat] =
    withStructuringElement("dilate", radius, shape, iterations): (kernel, iters) =>
      Mats.produce("dilate")(Imgproc.dilate(self, _, kernel, Point(-1, -1).toCv, iters))

  /** A compound morphological operation (open/close/gradient/top-hat/black-hat) — see [[MorphOp]]. */
  def morphology(
      op: MorphOp,
      radius: Int = 1,
      shape: MorphShape = MorphShape.Rect,
      iterations: Int = 1
  ): Managed[Mat] =
    withStructuringElement("morphologyEx", radius, shape, iterations): (kernel, iters) =>
      Mats.produce("morphologyEx"):
        Imgproc.morphologyEx(self, _, op.cvValue, kernel, Point(-1, -1).toCv, iters)

  /** Bitwise NOT — inverts every pixel (`255 - v` for 8-bit). */
  def bitwiseNot(): Managed[Mat] =
    Mats.produce("bitwiseNot")(Core.bitwise_not(self, _))

  /** Absolute per-element difference `|self - other|`. `other` is borrowed. The basis of frame-difference
    * motion detection — see [[MotionDetector]].
    */
  def absdiff(other: Mat): Managed[Mat] =
    Mats.produce("absdiff")(Core.absdiff(self, other, _))

  /** A binary mask (`CV_8UC1`, 0 or 255) of the pixels whose every channel lies within `[lo, hi]`. The core
    * of colour segmentation — usually run on an HSV image. `lo` must not exceed `hi` in any channel; checked
    * here, per [[extractChannel]]'s index precheck, because OpenCV reports an inverted range only as an empty
    * mask — a plausible-looking result that is silently wrong.
    */
  def inRange(lo: Scalar, hi: Scalar): Managed[Mat] =
    require(
      lo.v0 <= hi.v0 && lo.v1 <= hi.v1 && lo.v2 <= hi.v2 && lo.v3 <= hi.v3,
      s"inRange needs lo <= hi channel by channel, got lo=$lo hi=$hi"
    )
    Mats.produce("inRange")(Core.inRange(self, lo.toCv, hi.toCv, _))

  /** Keeps this image only where `mask` (`CV_8UC1`) is non-zero; the rest becomes black. `mask` is borrowed.
    */
  def masked(mask: Mat): Managed[Mat] =
    Mats.produce("masked")(Core.bitwise_and(self, self, _, mask))

  /** Linearly rescales values into `[alpha, beta]` (min-max normalisation) and hands the result back at
    * `depth`. Useful for stretching contrast, and the standard way of bringing a non-8-bit result — a
    * disparity map, a distance transform, a float Sobel response — into a displayable range.
    *
    * `depth` defaults to [[OutputDepth.Unsigned8]] rather than to OpenCV's own `dtype = -1`, which means
    * "same depth as the source". With `-1` a `CV_32F` input rescaled to `[0, 255]` comes back as a `CV_32F`
    * holding the values 0..255, so the second half of the job — making it displayable — never happened:
    * [[Image.toBufferedImage]] rejects it, `applyColorMap` ([[colorMap]]) aborts in native code because it
    * takes `CV_8UC1`/`CV_8UC3` only, and `imwrite` only survives it by silently coercing behind our back. For
    * an already-8-bit source `Unsigned8` and `-1` are the same conversion, so a plain contrast stretch is
    * unaffected by the default.
    *
    * Pass [[OutputDepth.SameAsSource]] for a stretch that must keep the source's precision — rescaling a
    * float image into `[0, 1]` for a model's input, for instance, where 8-bit would collapse the range onto
    * 256 levels.
    */
  def normalize(
      alpha: Double = 0,
      beta: Double = 255,
      depth: OutputDepth = OutputDepth.Unsigned8
  ): Managed[Mat] =
    Mats.produce("normalize")(Core.normalize(self, _, alpha, beta, Core.NORM_MINMAX, depth.cvValue))

  /** Extracts a single channel as its own image. */
  def extractChannel(index: Int): Managed[Mat] =
    require(
      index >= 0 && index < self.channels,
      s"channel $index is out of range for a ${self.channels}-channel image"
    )
    Mats.produce("extractChannel")(Core.extractChannel(self, _, index))

  /** Unsharp-mask sharpening: adds back `amount` × (image − its blur). `amount` 0 is a no-op; ~1 is a firm
    * sharpen. Overdo it and haloes appear at edges.
    */
  def sharpen(amount: Double = 1.0): Managed[Mat] =
    require(amount >= 0, s"sharpen amount cannot be negative, got $amount")
    gaussianBlur(Size(0, 0), sigmaX = 3.0).use(blurred => addWeighted(1 + amount, blurred, -amount))

  /** Applies a false-colour map — turns a single-channel image (a depth map, a motion field, any data) into a
    * colour heatmap. See [[Colormap]].
    */
  def colorMap(map: Colormap): Managed[Mat] =
    Mats.produce("applyColorMap")(Imgproc.applyColorMap(self, _, map.cvValue))

  /** Inpaints the region under `mask` (`CV_8UC1`, non-zero = repair) from its surroundings — remove a
    * scratch, an object, or a watermark. `mask` is borrowed.
    */
  def inpaint(mask: Mat, radius: Double = 3.0): Managed[Mat] =
    Mats.produce("inpaint")(Photo.inpaint(self, mask, _, radius, Photo.INPAINT_TELEA))

  /** Seamlessly clones this image (the foreground object) into `background` at `center`, blending gradients
    * so the paste is invisible (Poisson editing). `mask` (`CV_8UC1`) marks the object; `background` and
    * `mask` are borrowed. The result is `background`-sized.
    */
  def seamlessCloneInto(background: Mat, mask: Mat, center: Point): Managed[Mat] =
    Mats.produce("seamlessClone")(
      Photo.seamlessClone(self, background, mask, center.toCv, _, Photo.NORMAL_CLONE)
    )

  /** Builds a `radius`-derived structuring element, runs `f` with it, and frees it — the one place morphology
    * allocates a kernel.
    */
  private def withStructuringElement(op: String, radius: Int, shape: MorphShape, iterations: Int)(
      f: (Mat, Int) => Managed[Mat]
  ): Managed[Mat] =
    require(radius >= 1, s"$op radius must be ≥ 1, got $radius")
    require(iterations >= 1, s"$op iterations must be ≥ 1, got $iterations")
    val side = radius * 2 + 1
    Managed.use(Imgproc.getStructuringElement(shape.cvValue, Size(side.toDouble, side.toDouble).toCv)):
      kernel => f(kernel, iterations)

extension (self: Managed[Mat])

  /** Hands the wrapped Mat to `f` and releases it once `f` has produced its own result.
    *
    * This is the whole reason the ownership contract above is safe to write down. Each op returns a Mat the
    * caller owns, so a chain of them produces one owned Mat per stage, and every stage but the last is
    * garbage the moment the next one returns. `pipe` makes that the default rather than something the caller
    * has to remember: `self` is consumed, and using it afterwards throws `IllegalStateException` instead of
    * reading freed memory.
    *
    * {{{
    * val edges = src.gaussianBlur(Size(5, 5), 1.5).pipe(_.canny(50, 150))
    * }}}
    *
    * The release happens in a `finally`, so a stage that throws does not leak its input either. For a
    * terminal stage that produces something other than a Mat — a count, a `Seq[Rect]` — use `Managed.use`,
    * which has the same shape and the same guarantee.
    */
  def pipe(f: Mat => Managed[Mat]): Managed[Mat] =
    try f(self.get)
    finally self.release()

/** The one place a `getRotationMatrix2D` + `warpAffine` rotation about the image centre is assembled — shared
  * by [[rotated]] here and `deskew` in Deskew.scala, which differ only in canvas policy:
  * `expandCanvas = true` widens the destination to the rotated bounding box so no corner is clipped; `false`
  * keeps the source's frame size (a deskew must not resize the page it is correcting).
  *
  * getRotationMatrix2D gives a 2x3 affine about the centre; with an expanded canvas the translation column is
  * shifted so the whole rotated image lands inside it. The rotation matrix is borrowed from `Managed.use` and
  * freed before this returns; only the produced destination escapes.
  *
  * A plain function rather than an extension member because it never touches the receiver — the source Mat
  * arrives as `src` — and `private[scalacv]` rather than `private` so Deskew.scala can call it; the cohesion
  * call (recorded in Deskew.scala's header) is that one shared rotation beats two copies of the same geometry
  * drifting apart.
  */
private[scalacv] def warpAboutCenter(
    src: Mat,
    degrees: Double,
    scale: Double,
    expandCanvas: Boolean,
    interpolation: Interpolation,
    border: BorderType,
    color: Scalar,
    op: String
): Managed[Mat] =
  val w = src.cols
  val h = src.rows
  Managed.use(Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0).toCv, degrees, scale)): m =>
    val (newW, newH) =
      if expandCanvas then
        val cos = math.abs(m.get(0, 0)(0))
        val sin = math.abs(m.get(0, 1)(0))
        (math.round(h * sin + w * cos).toInt, math.round(h * cos + w * sin).toInt)
      else (w, h)
    m.put(0, 2, m.get(0, 2)(0) + (newW - w) / 2.0)
    m.put(1, 2, m.get(1, 2)(0) + (newH - h) / 2.0)
    Mats.produce(op): dst =>
      Imgproc.warpAffine(
        src,
        dst,
        m,
        Size(newW.toDouble, newH.toDouble).toCv,
        interpolation.cvValue,
        border.cvValue,
        color.toCv
      )
