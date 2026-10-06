package scalacv

import org.opencv.core.{Core, Mat}
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/* Typed replacements for OpenCV's raw int constants.
 *
 * Most of these are genuine enumerations — a value is exactly one case and `cvValue` is a single named
 * constant. Two are not. `Threshold` is a real bitmask: `THRESH_BINARY | THRESH_OTSU` is the ordinary way to
 * ask for Otsu's method, a combination rather than a choice, so it carries a mode plus an optional modifier.
 * `ImreadFlags` looks like one but is not — OpenCV's IMREAD constants are not orthogonal bits (each
 * reduced-size flag bakes in its colour bit, and `IMREAD_UNCHANGED` is `-1`, which ORs every other bit away),
 * so it is a structured value whose (colour, scale) pair maps totally onto exactly one named constant, with
 * orientation the sole genuinely-independent flag.
 */

/** Colour space conversions. A true enumeration. */
enum ColorConversion(val cvValue: Int):
  case BgrToGray extends ColorConversion(Imgproc.COLOR_BGR2GRAY)
  case GrayToBgr extends ColorConversion(Imgproc.COLOR_GRAY2BGR)
  case BgrToRgb extends ColorConversion(Imgproc.COLOR_BGR2RGB)
  case RgbToBgr extends ColorConversion(Imgproc.COLOR_RGB2BGR)
  case BgrToHsv extends ColorConversion(Imgproc.COLOR_BGR2HSV)
  case HsvToBgr extends ColorConversion(Imgproc.COLOR_HSV2BGR)
  case BgrToLab extends ColorConversion(Imgproc.COLOR_BGR2Lab)
  case LabToBgr extends ColorConversion(Imgproc.COLOR_Lab2BGR)
  case BgrToBgra extends ColorConversion(Imgproc.COLOR_BGR2BGRA)
  case BgraToBgr extends ColorConversion(Imgproc.COLOR_BGRA2BGR)

/** Interpolation for resize and warps. A true enumeration. */
enum Interpolation(val cvValue: Int):
  case Nearest extends Interpolation(Imgproc.INTER_NEAREST)
  case Linear extends Interpolation(Imgproc.INTER_LINEAR)
  case Cubic extends Interpolation(Imgproc.INTER_CUBIC)
  case Area extends Interpolation(Imgproc.INTER_AREA)
  case Lanczos4 extends Interpolation(Imgproc.INTER_LANCZOS4)

/** Line rasterisation. A true enumeration. */
enum LineType(val cvValue: Int):
  case Connected4 extends LineType(Imgproc.LINE_4)
  case Connected8 extends LineType(Imgproc.LINE_8)
  case AntiAliased extends LineType(Imgproc.LINE_AA)

/** Hershey fonts for putText. A true enumeration. */
enum Font(val cvValue: Int):
  case Simplex extends Font(Imgproc.FONT_HERSHEY_SIMPLEX)
  case Plain extends Font(Imgproc.FONT_HERSHEY_PLAIN)
  case Duplex extends Font(Imgproc.FONT_HERSHEY_DUPLEX)
  case Complex extends Font(Imgproc.FONT_HERSHEY_COMPLEX)
  case Triplex extends Font(Imgproc.FONT_HERSHEY_TRIPLEX)
  case Script extends Font(Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX)

/** Which contours findContours reports. A true enumeration. */
enum ContourRetrieval(val cvValue: Int):
  case External extends ContourRetrieval(Imgproc.RETR_EXTERNAL)
  case List extends ContourRetrieval(Imgproc.RETR_LIST)
  case CComp extends ContourRetrieval(Imgproc.RETR_CCOMP)
  case Tree extends ContourRetrieval(Imgproc.RETR_TREE)

/** How findContours compresses each contour. A true enumeration. */
enum ContourApproximation(val cvValue: Int):
  case None extends ContourApproximation(Imgproc.CHAIN_APPROX_NONE)
  case Simple extends ContourApproximation(Imgproc.CHAIN_APPROX_SIMPLE)
  case Tc89L1 extends ContourApproximation(Imgproc.CHAIN_APPROX_TC89_L1)
  case Tc89Kcos extends ContourApproximation(Imgproc.CHAIN_APPROX_TC89_KCOS)

/** Border extrapolation.
  *
  * Plain rather than an enum-with-modifiers: `BORDER_ISOLATED` is a modifier, but it only means anything for
  * ROI-based calls that scalacv does not expose yet, so it is deliberately omitted rather than offered and
  * ignored.
  *
  * ==One type, two domains==
  *
  * OpenCV packs two different sets of accepted values into this one `int`, and this enum is the union of
  * them. `copyMakeBorder` (behind `pad` and `border`) and `warpAffine` (behind `rotated`) honour all five
  * modes. The `imgproc` filter family — `gaussianBlur`, `boxBlur`, `sobel`, `laplacian` — does not: it
  * rejects [[BorderType.Wrap]], see that case and [[BorderType.requireFilterSupport]].
  *
  * Splitting the type (a `FilterBorder` without `Wrap`, widening into a `TransformBorder` with it) is the end
  * state that would make the mistake unrepresentable, but it is a breaking change to four public signatures.
  * Until then [[BorderType.requireFilterSupport]] is the check every filter operation must run on its
  * `border` parameter, so the rejection lands at the Scala boundary instead of as an assertion failure inside
  * OpenCV. `BORDER_TRANSPARENT` stays out of the enum entirely for a related reason: `copyMakeBorder` throws
  * on it, and `warpAffine` with it leaves the freshly-allocated destination uninitialised, so the value can
  * only ever produce a crash or garbage pixels.
  */
enum BorderType(val cvValue: Int):
  case Constant extends BorderType(Core.BORDER_CONSTANT)
  case Replicate extends BorderType(Core.BORDER_REPLICATE)
  case Reflect extends BorderType(Core.BORDER_REFLECT)
  case Reflect101 extends BorderType(Core.BORDER_REFLECT_101)

  /** Takes the pixels from the opposite edge, as if the image tiled.
    *
    * **Valid only for `pad`/`border` (`copyMakeBorder`) and `rotated` (`warpAffine`).** The `imgproc` filters
    * reject it — see [[BorderType.requireFilterSupport]] for what happens if it reaches them.
    */
  case Wrap extends BorderType(Core.BORDER_WRAP)

object BorderType:

  /** Rejects the one mode OpenCV's `imgproc` filters cannot honour, naming the operation that was asked for.
    *
    * `gaussianBlur`, `boxBlur`, `sobel` and `laplacian` all end up in `cv::FilterEngine::init`, which asserts
    * `columnBorderType != BORDER_WRAP` (`imgproc/src/filter.dispatch.cpp`) and aborts. Leaving that to native
    * code would be tolerable if it were consistent, but it is not: verified against OpenCV 4.13,
    * `GaussianBlur` with `BORDER_WRAP` *silently ignores* the mode on `CV_8U` input — the SIMD path never
    * reaches the assertion — and aborts on `CV_32F`. So the same call succeeds against the 8-bit frame it was
    * developed on and crashes the first time it meets a float image. Checking here makes both depths fail
    * identically, before any native call, with the parameter the caller actually passed.
    *
    * Must **not** be used by `pad`/`border` or `rotated`: `copyMakeBorder` and `warpAffine` do honour
    * [[Wrap]], and rejecting it there would remove the only place the mode is useful.
    *
    * @param op
    *   the scalacv operation name, quoted in the failure message
    * @param border
    *   the caller-supplied mode
    * @throws IllegalArgumentException
    *   if `border` is [[Wrap]]
    */
  def requireFilterSupport(op: String, border: BorderType): Unit =
    require(
      border != Wrap,
      s"$op does not support BorderType.Wrap: OpenCV's imgproc filters assert " +
        "columnBorderType != BORDER_WRAP. Wrap is valid only for pad/border (copyMakeBorder) and " +
        "rotated (warpAffine)."
    )

/** How to mirror an image. Named by the visible effect, not OpenCV's axis-centric flip code. */
enum Flip(val cvValue: Int):

  /** Mirror left↔right (flip about the vertical axis) — OpenCV flip code `1`. */
  case Horizontal extends Flip(1)

  /** Mirror top↔bottom (flip about the horizontal axis) — OpenCV flip code `0`. */
  case Vertical extends Flip(0)

  /** Both at once (a 180° point reflection) — OpenCV flip code `-1`. */
  case Both extends Flip(-1)

/** Lossless quarter-turn rotations — no interpolation, exact pixels. A true enumeration. */
enum Rotation(val cvValue: Int):
  case Clockwise extends Rotation(Core.ROTATE_90_CLOCKWISE)
  case CounterClockwise extends Rotation(Core.ROTATE_90_COUNTERCLOCKWISE)
  case Half extends Rotation(Core.ROTATE_180)

/** The structuring-element shape for morphology. A true enumeration. */
enum MorphShape(val cvValue: Int):
  case Rect extends MorphShape(Imgproc.MORPH_RECT)
  case Ellipse extends MorphShape(Imgproc.MORPH_ELLIPSE)
  case Cross extends MorphShape(Imgproc.MORPH_CROSS)

/** Compound morphological operations (`morphologyEx`). Erosion and dilation have their own methods. */
enum MorphOp(val cvValue: Int):

  /** Erode then dilate — removes small bright specks. */
  case Open extends MorphOp(Imgproc.MORPH_OPEN)

  /** Dilate then erode — fills small dark holes. */
  case Close extends MorphOp(Imgproc.MORPH_CLOSE)

  /** Dilation minus erosion — an outline of the shapes. */
  case Gradient extends MorphOp(Imgproc.MORPH_GRADIENT)

  /** Source minus its opening — the bright detail smaller than the kernel. */
  case TopHat extends MorphOp(Imgproc.MORPH_TOPHAT)

  /** Closing minus the source — the dark detail smaller than the kernel. */
  case BlackHat extends MorphOp(Imgproc.MORPH_BLACKHAT)

/** How adaptive thresholding weights each pixel's neighbourhood. A true enumeration. */
enum AdaptiveMethod(val cvValue: Int):
  case Mean extends AdaptiveMethod(Imgproc.ADAPTIVE_THRESH_MEAN_C)
  case Gaussian extends AdaptiveMethod(Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C)

/** A false-colour map — turns a single-channel image (a depth map, a motion field, any data) into a colour
  * heatmap. The perceptually-uniform ones ([[Colormap.Viridis]], [[Colormap.Magma]], [[Colormap.Inferno]],
  * [[Colormap.Plasma]], [[Colormap.Turbo]]) are the honest choice for data; [[Colormap.Jet]] is the
  * classic-but-misleading rainbow.
  */
enum Colormap(val cvValue: Int):
  case Autumn extends Colormap(Imgproc.COLORMAP_AUTUMN)
  case Bone extends Colormap(Imgproc.COLORMAP_BONE)
  case Jet extends Colormap(Imgproc.COLORMAP_JET)
  case Ocean extends Colormap(Imgproc.COLORMAP_OCEAN)
  case Hot extends Colormap(Imgproc.COLORMAP_HOT)
  case Magma extends Colormap(Imgproc.COLORMAP_MAGMA)
  case Inferno extends Colormap(Imgproc.COLORMAP_INFERNO)
  case Plasma extends Colormap(Imgproc.COLORMAP_PLASMA)
  case Viridis extends Colormap(Imgproc.COLORMAP_VIRIDIS)
  case Turbo extends Colormap(Imgproc.COLORMAP_TURBO)

/** Thresholding — **a bitmask, not an enumeration.**
  *
  * `Imgproc.threshold` takes a mode OR-ed with at most one automatic-threshold modifier. Modelled as `enum`
  * the useful combinations would be unrepresentable, and `THRESH_MASK` would leak into a public API where it
  * means nothing.
  */
final case class Threshold(mode: Threshold.Mode, auto: Option[Threshold.Auto] = scala.None):
  def cvValue: Int = mode.cvValue | auto.fold(0)(_.cvValue)

  /** True when OpenCV computes the threshold itself, making the returned value meaningful. */
  def computesThreshold: Boolean = auto.isDefined

object Threshold:
  enum Mode(val cvValue: Int):
    case Binary extends Mode(Imgproc.THRESH_BINARY)
    case BinaryInv extends Mode(Imgproc.THRESH_BINARY_INV)
    case Truncate extends Mode(Imgproc.THRESH_TRUNC)
    case ToZero extends Mode(Imgproc.THRESH_TOZERO)
    case ToZeroInv extends Mode(Imgproc.THRESH_TOZERO_INV)

  /** Automatic threshold selection. Mutually exclusive with each other, hence an Option. */
  enum Auto(val cvValue: Int):
    case Otsu extends Auto(Imgproc.THRESH_OTSU)
    case Triangle extends Auto(Imgproc.THRESH_TRIANGLE)

  /** Plain fixed binary thresholding — the common "binarise at this value" case, and the default the
    * `threshold` methods use. The companion counterpart to [[otsu]]/[[triangle]].
    */
  val Binary: Threshold = Threshold(Mode.Binary)

  def otsu(mode: Mode = Mode.Binary): Threshold = Threshold(mode, Some(Auto.Otsu))
  def triangle(mode: Mode = Mode.Binary): Threshold = Threshold(mode, Some(Auto.Triangle))

/** What threshold actually returns.
  *
  * `Imgproc.threshold` returns a `double` that most wrappers discard. For Otsu and Triangle it is the
  * threshold OpenCV chose — frequently the reason you called it at all.
  */
final case class ThresholdResult(value: Double)

/** The pair `Mat.threshold` returns: the thresholded [[image]] plus the value OpenCV [[computed]].
  *
  * Why a pair exists at all: for [[Threshold.Auto.Otsu]] and [[Threshold.Auto.Triangle]] the whole point of
  * the call is the threshold OpenCV chose, so dropping the `double` the native function returns would throw
  * away the answer. And why a named case class rather than the `(Managed[Mat], ThresholdResult)` tuple this
  * used to be: a tuple made `threshold` the one op that did not return a bare `Managed[Mat]`, which broke
  * `Managed.pipe` and left every call site spelling `._1`. A named pair destructures the same (`val
  * Thresholded(out, result) = ...`) but reads as `.image` / `.computed` everywhere else.
  */
final case class Thresholded(image: Managed[Mat], computed: ThresholdResult)

/** How `imread`/`imdecode` should decode a pixel's colour. */
enum ImreadColor:
  case Grayscale, Color, ColorRgb, Unchanged, AnyDepth

/** The fraction of full resolution to decode at. OpenCV's reduced-size decode is cheaper than a full read
  * followed by a resize, because the codec skips the discarded detail rather than producing it first.
  */
enum ImreadScale(val denom: Int):
  case Full extends ImreadScale(1)
  case Half extends ImreadScale(2)
  case Quarter extends ImreadScale(4)
  case Eighth extends ImreadScale(8)

/** Image reading flags — **a total model, not a bitmask.**
  *
  * OpenCV's `IMREAD_*` constants look like OR-able bits but are not: each `IMREAD_REDUCED_*` value already
  * bakes in its colour bit, and `IMREAD_UNCHANGED` is `-1`, whose bits swamp everything else. OR-ing a colour
  * with a reduced-size flag therefore silently decodes the wrong image. So the (colour, scale) pair maps
  * *totally* onto exactly one named constant instead of composing, and only [[ignoreOrientation]] (bit 128)
  * is a genuinely independent flag that may be OR-ed on top.
  *
  * Reduced-size decode exists only for [[ImreadColor.Grayscale]] and [[ImreadColor.Color]], and
  * [[ImreadColor.Unchanged]] (`-1`) can carry no extra bit at all; the two `require`s reject the combinations
  * OpenCV has no constant for, rather than quietly OR-ing them into something else.
  */
final case class ImreadFlags(
    color: ImreadColor,
    scale: ImreadScale = ImreadScale.Full,
    ignoreOrientation: Boolean = false
):
  require(
    scale == ImreadScale.Full || color == ImreadColor.Grayscale || color == ImreadColor.Color,
    s"$color has no reduced-size decode; only Grayscale and Color support $scale"
  )
  require(
    !(color == ImreadColor.Unchanged && (scale != ImreadScale.Full || ignoreOrientation)),
    "IMREAD_UNCHANGED (-1) cannot carry a reduction or an orientation flag"
  )

  def cvValue: Int =
    val base = (color, scale) match
      case (ImreadColor.Grayscale, ImreadScale.Full) => Imgcodecs.IMREAD_GRAYSCALE
      case (ImreadColor.Grayscale, ImreadScale.Half) => Imgcodecs.IMREAD_REDUCED_GRAYSCALE_2
      case (ImreadColor.Grayscale, ImreadScale.Quarter) => Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4
      case (ImreadColor.Grayscale, ImreadScale.Eighth) => Imgcodecs.IMREAD_REDUCED_GRAYSCALE_8
      case (ImreadColor.Color, ImreadScale.Full) => Imgcodecs.IMREAD_COLOR
      case (ImreadColor.Color, ImreadScale.Half) => Imgcodecs.IMREAD_REDUCED_COLOR_2
      case (ImreadColor.Color, ImreadScale.Quarter) => Imgcodecs.IMREAD_REDUCED_COLOR_4
      case (ImreadColor.Color, ImreadScale.Eighth) => Imgcodecs.IMREAD_REDUCED_COLOR_8
      case (ImreadColor.ColorRgb, ImreadScale.Full) => Imgcodecs.IMREAD_COLOR_RGB
      case (ImreadColor.Unchanged, ImreadScale.Full) => Imgcodecs.IMREAD_UNCHANGED
      case (ImreadColor.AnyDepth, ImreadScale.Full) => Imgcodecs.IMREAD_ANYDEPTH
      case _ => throw IllegalStateException(s"unreachable: require() guards ($color,$scale)")
    if ignoreOrientation then base | Imgcodecs.IMREAD_IGNORE_ORIENTATION else base

object ImreadFlags:
  val Color: ImreadFlags = ImreadFlags(ImreadColor.Color)
  val Grayscale: ImreadFlags = ImreadFlags(ImreadColor.Grayscale)
  val Unchanged: ImreadFlags = ImreadFlags(ImreadColor.Unchanged)
