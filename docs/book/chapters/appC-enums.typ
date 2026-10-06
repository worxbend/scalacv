#import "../lib/book.typ": *

#appendix("Enums and Types Reference", subtitle: [Every typed constant and value type in the library, with the OpenCV constant underneath it.])

Chapter 8 argued the case for typing OpenCV's integer constants and showed the mechanism: a Scala 3
`enum` with one parameter, `cvValue`, carrying the number the native call actually receives. This
appendix is the other half --- the complete list, so that a question of the form "what are the
cases?" or "which OpenCV constant is that?" has one place to be answered without an IDE open.

Two things are worth knowing before the tables start. The first is that nothing here is a wrapper
around a pointer. Every type in this appendix is either a Scala 3 `enum` whose runtime
representation is a singleton object, or a `final case class` holding primitives and other case
classes. None of them owns native memory, none of them needs releasing, and all of them stay valid
after the `Mat` they were computed from has been freed. That is a deliberate property and it is what
makes the detector results in the second half of this appendix safe to store, serialise and return
from a scope.

The second is that the OpenCV constant is never hidden. `cvValue` is a public `val` on every enum
that wraps one, so `Interpolation.Area.cvValue` is `Imgproc.INTER_AREA` and you can log it, compare
it, or pass it to a raw call. Three enums carry a different payload, because a single `int` is not
what they name: `ImreadScale` carries `denom` (`1`, `2`, `4`, `8`), `Codec` a `fourcc: Int`, and
`CascadeName` a `fileName: String`. Five carry nothing at all --- `ImreadColor`, `TrackerKind`,
`KeypointLayout`, `HandGesture` and `Steering` --- because each stands for a decision made in Scala
rather than for a constant OpenCV defines. Every enum also has `.values`, an `Array` of its cases,
which is the fastest way to answer a "what are the options" question from a REPL rather than from a
book.

#sect("The core enums")

Thirteen true enumerations live in `core/src/scalacv/Enums.scala`, together with the two structured
value types --- `Threshold` and `ImreadFlags` --- that are deliberately not enums, the four small
enums that make up their axes, and `ThresholdResult`, which the mid-level `Mat.threshold` returns
beside its Mat.

#subsect("Colour conversion")

`ColorConversion` steers `Image.convert` and the mid-level `Mat.cvtColor`. The `gray` and `toHsv`
shortcuts cover the two commonest choices; chapter 8 covers when each colour space earns its keep,
and chapter 11 uses HSV in anger for segmentation.

#figure-table("ColorConversion — the colour spaces scalacv converts between.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`BgrToGray`], [`COLOR_BGR2GRAY`], [Three channels to one luminance channel.],
  [`GrayToBgr`], [`COLOR_GRAY2BGR`], [One channel to three identical ones --- how you draw colour on a grey image.],
  [`BgrToRgb`], [`COLOR_BGR2RGB`], [Channel order swap, for handing pixels to a library that expects RGB.],
  [`RgbToBgr`], [`COLOR_RGB2BGR`], [The same swap the other way; the operation is its own inverse.],
  [`BgrToHsv`], [`COLOR_BGR2HSV`], [Hue, saturation, value --- the space colour thresholding belongs in.],
  [`HsvToBgr`], [`COLOR_HSV2BGR`], [Back to displayable pixels.],
  [`BgrToLab`], [`COLOR_BGR2Lab`], [CIE L\*a\*b\*, where Euclidean distance approximates perceived difference.],
  [`LabToBgr`], [`COLOR_Lab2BGR`], [Back from L\*a\*b\*.],
  [`BgrToBgra`], [`COLOR_BGR2BGRA`], [Adds an opaque alpha channel.],
  [`BgraToBgr`], [`COLOR_BGRA2BGR`], [Drops the alpha channel.],
)
]

#subsect("Resizing, borders and mirroring")

`Interpolation` is the resampling kernel for `resize`, `scale` and the warps of chapter 10. `Area` is
the one worth remembering: it is the only case that averages rather than samples, which is why it is
the right choice when shrinking an image and a poor one when enlarging it.

#figure-table("Interpolation — how a resampled pixel is computed.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Nearest`], [`INTER_NEAREST`], [Take the closest source pixel. Blocky, exact, and the only correct choice for a label mask.],
  [`Linear`], [`INTER_LINEAR`], [Bilinear over the 2×2 neighbourhood. OpenCV's default and scalacv's.],
  [`Cubic`], [`INTER_CUBIC`], [Bicubic over 4×4. Sharper enlargement, slower.],
  [`Area`], [`INTER_AREA`], [Pixel-area resampling --- averages the source region, so downscaling does not alias.],
  [`Lanczos4`], [`INTER_LANCZOS4`], [Windowed sinc over 8×8. The most expensive, and the sharpest.],
)
]

`BorderType` says what pixels exist beyond the edge of an image, for every operation that reads a
neighbourhood. Chapters 9 and 10 both take it, and they do not share a default: the filters default
to `Reflect101`, while `pad`, `border` and `rotated` default to `Constant` filled with
`Scalar.Black`.

#figure-table("BorderType — how pixels past the edge are invented.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Constant`], [`BORDER_CONSTANT`], [One fixed colour outside. The padding default.],
  [`Replicate`], [`BORDER_REPLICATE`], [The edge pixel repeated outward: `aaaaaa|abcdefgh`.],
  [`Reflect`], [`BORDER_REFLECT`], [Mirrored, including the edge pixel: `fedcba|abcdefgh`.],
  [`Reflect101`], [`BORDER_REFLECT_101`], [Mirrored, excluding it: `gfedcb|abcdefgh`. The filter default.],
  [`Wrap`], [`BORDER_WRAP`], [The opposite edge, as if the image tiled. #strong[Padding and `rotated` only.]],
)
]

#warning[
`Wrap` is the one case in this appendix that is not accepted everywhere its type is. OpenCV's
`imgproc` filter engine asserts `columnBorderType != BORDER_WRAP` and aborts the process. Worse, it
is inconsistent about when: measured against OpenCV 4.13, `GaussianBlur` with `BORDER_WRAP` silently
ignores the mode on `CV_8U` input and aborts on `CV_32F`, so the same call works against the 8-bit
frames it was developed on and kills the process the first time it meets a float image.
`BorderType.requireFilterSupport(op, border)` is the guard `gaussianBlur`, `boxBlur`, `sobel` and
`laplacian` each run, turning that into an `IllegalArgumentException` before any native call.
`BORDER_ISOLATED` and `BORDER_TRANSPARENT` are not in the enum at all --- the first is meaningless
without the region-of-interest calls scalacv does not expose, and the second can only produce a
throw or uninitialised pixels.
]

`Flip` and `Rotation` are chapter 10's lossless pair. `Flip` is named for the visible effect rather
than for OpenCV's axis-centric flip code, which is the usual source of a mirrored-the-wrong-way bug.

#figure-table("Flip and Rotation — the operations that move pixels without resampling them.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Flip.Horizontal`], [flip code `1`], [Mirror left--right, about the vertical axis.],
  [`Flip.Vertical`], [flip code `0`], [Mirror top--bottom, about the horizontal axis.],
  [`Flip.Both`], [flip code `-1`], [Both at once --- a 180° point reflection.],
  [`Rotation.Clockwise`], [`ROTATE_90_CLOCKWISE`], [A quarter turn clockwise.],
  [`Rotation.CounterClockwise`], [`ROTATE_90_COUNTERCLOCKWISE`], [A quarter turn anti-clockwise.],
  [`Rotation.Half`], [`ROTATE_180`], [A half turn. Exact pixels, no interpolation.],
)
]

`Rotation` covers the quarter turns only. The separate `rotate(degrees, scale)` overload resamples
through `warpAffine`, expanding the canvas so no corner is clipped, and it measures its angle
#emph[counter-clockwise] --- so `rotate(90.0)` turns the image the same way
`rotate(Rotation.CounterClockwise)` does.

#warning[
Two rotation conventions live in this library. `Image.rotate(degrees)` is counter-clockwise, matching
`getRotationMatrix2D` and the `Rotation` enum's `CounterClockwise` case.
`Picture.rotate(degrees, about)` in `scalacv-graphs` is clockwise: the scene graph works in screen
coordinates, where `y` points down the image rather than up, and in that frame the same positive
angle turns the other way. Check which layer you are holding before reaching for a minus sign.
]

#subsect("Morphology")

`MorphShape` builds the structuring element for `erode`, `dilate` and `morphology` --- all three take
a `radius` and a `shape`, defaulting to `MorphShape.Rect`. `MorphOp` names the compound operation
that `morphology` performs; erosion and dilation have their own methods and so are not cases here.
Chapter 9 is the chapter.

#figure-table("MorphShape and MorphOp — the kernel, and what to do with it.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`MorphShape.Rect`], [`MORPH_RECT`], [A filled rectangle. The cheapest, the library default, and it squares off round shapes.],
  [`MorphShape.Ellipse`], [`MORPH_ELLIPSE`], [A filled ellipse. Worth passing by hand for organic shapes.],
  [`MorphShape.Cross`], [`MORPH_CROSS`], [A plus sign. Touches only the 4-connected neighbours.],
  [`MorphOp.Open`], [`MORPH_OPEN`], [Erode then dilate --- removes small bright specks.],
  [`MorphOp.Close`], [`MORPH_CLOSE`], [Dilate then erode --- fills small dark holes.],
  [`MorphOp.Gradient`], [`MORPH_GRADIENT`], [Dilation minus erosion --- an outline of the shapes.],
  [`MorphOp.TopHat`], [`MORPH_TOPHAT`], [Source minus its opening --- bright detail smaller than the kernel.],
  [`MorphOp.BlackHat`], [`MORPH_BLACKHAT`], [Closing minus the source --- dark detail smaller than the kernel.],
)
]

#subsect("Thresholding")

`Threshold` is not an enum, and chapter 11 explains why: `Imgproc.threshold` takes a mode OR-ed with
at most one automatic-threshold modifier, so the useful combinations would be unrepresentable as a
flat enumeration. It is a case class of a `Threshold.Mode` and an `Option[Threshold.Auto]`, with
`cvValue` doing the OR and `computesThreshold` reporting whether the `ThresholdResult` the mid-level
call returns will carry a number worth reading.

#figure-table("Threshold.Mode and Threshold.Auto — the two axes of a threshold.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Mode.Binary`], [`THRESH_BINARY`], [Above the cutoff becomes `maxValue`, below becomes `0`.],
  [`Mode.BinaryInv`], [`THRESH_BINARY_INV`], [The same, inverted.],
  [`Mode.Truncate`], [`THRESH_TRUNC`], [Above the cutoff is clamped to it; below is untouched.],
  [`Mode.ToZero`], [`THRESH_TOZERO`], [Below the cutoff becomes `0`; above is untouched.],
  [`Mode.ToZeroInv`], [`THRESH_TOZERO_INV`], [Above the cutoff becomes `0`; below is untouched.],
  [`Auto.Otsu`], [`THRESH_OTSU`], [OpenCV picks the cutoff by minimising intra-class variance.],
  [`Auto.Triangle`], [`THRESH_TRIANGLE`], [OpenCV picks it by the triangle method --- better for one dominant peak.],
)
]

Three shorthands sit in the companion. `Threshold.Binary` is the plain fixed-cutoff value and the
default of the `kind` parameter both `threshold` methods take --- note that it is a `Threshold`, not
a `Mode`, and shadows `Threshold.Mode.Binary` in prose if not in code. `Threshold.otsu(mode)` and
`Threshold.triangle(mode)` both default `mode` to `Mode.Binary`. `AdaptiveMethod` is the separate
enum that `adaptiveThreshold` takes: `Mean` (`ADAPTIVE_THRESH_MEAN_C`, the unweighted neighbourhood
average) and `Gaussian` (`ADAPTIVE_THRESH_GAUSSIAN_C`, weighted by distance, and the default).

#subsect("Contours")

Both of chapter 12's enums have a case whose name shadows something in `scala.Predef`.
`ContourRetrieval.List` and `ContourApproximation.None` are the source's spellings and are correct;
inside `Enums.scala` itself the compiler needs `scala.None` written out, which is why `Threshold`'s
default argument reads `auto: Option[Threshold.Auto] = scala.None`.

#figure-table("ContourRetrieval and ContourApproximation — which outlines, and how compressed.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`ContourRetrieval.External`], [`RETR_EXTERNAL`], [Outermost contours only. The default, and usually what you want.],
  [`ContourRetrieval.List`], [`RETR_LIST`], [Every contour, flat, with no hierarchy.],
  [`ContourRetrieval.CComp`], [`RETR_CCOMP`], [Two levels: outer boundaries and the holes inside them.],
  [`ContourRetrieval.Tree`], [`RETR_TREE`], [The full nesting hierarchy.],
  [`ContourApproximation.None`], [`CHAIN_APPROX_NONE`], [Every boundary pixel, uncompressed.],
  [`ContourApproximation.Simple`], [`CHAIN_APPROX_SIMPLE`], [Straight runs collapsed to their endpoints. The default.],
  [`ContourApproximation.Tc89L1`], [`CHAIN_APPROX_TC89_L1`], [Teh--Chin chain approximation, L1 variant.],
  [`ContourApproximation.Tc89Kcos`], [`CHAIN_APPROX_TC89_KCOS`], [Teh--Chin, k-cosine variant.],
)
]

#subsect("Drawing")

`Font` and `LineType` reach the mid-level `Mat.drawText` and the `Picture` layer of chapter 16; the
chainable `Image` verbs of chapter 14 fix the font at `Simplex` and the line type at `Connected8`
rather than carry two more parameters through every signature.

#figure-table("Font and LineType — the Hershey vector fonts, and rasterisation.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Font.Simplex`], [`FONT_HERSHEY_SIMPLEX`], [Sans-serif, single stroke. The default.],
  [`Font.Plain`], [`FONT_HERSHEY_PLAIN`], [Smaller sans-serif.],
  [`Font.Duplex`], [`FONT_HERSHEY_DUPLEX`], [Sans-serif, double stroke --- heavier than `Simplex`.],
  [`Font.Complex`], [`FONT_HERSHEY_COMPLEX`], [Serif, single stroke.],
  [`Font.Triplex`], [`FONT_HERSHEY_TRIPLEX`], [Serif, triple stroke.],
  [`Font.Script`], [`FONT_HERSHEY_SCRIPT_SIMPLEX`], [Handwriting-style.],
  [`LineType.Connected4`], [`LINE_4`], [4-connected Bresenham.],
  [`LineType.Connected8`], [`LINE_8`], [8-connected Bresenham. The default.],
  [`LineType.AntiAliased`], [`LINE_AA`], [Gaussian-filtered edges. Smoother, and slower.],
)
]

These are the only fonts there are: OpenCV cannot render a system font, and a non-ASCII character
comes out as `?`.

#subsect("False colour")

`Colormap` turns a single-channel image --- a depth map, a motion field, any scalar field --- into a
colour heatmap. One argument, two entry points: `Image.colorMap(map)` in the chain, and the
mid-level `Mat.colorMap(map)` on a borrowed handle. Chapter 15 uses it, and `Filter.heatmap` is the
prepackaged `gray` then `colorMap(Colormap.Inferno)` pair; a disparity map in chapter 32 is far
easier to read through one.

#figure-table("Colormap — the false-colour ramps.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`Autumn`], [`COLORMAP_AUTUMN`], [Red through yellow.],
  [`Bone`], [`COLORMAP_BONE`], [Grey with a blue cast --- the medical-imaging ramp.],
  [`Jet`], [`COLORMAP_JET`], [The classic rainbow. Not perceptually uniform; it invents bands that are not in the data.],
  [`Ocean`], [`COLORMAP_OCEAN`], [Green through blue to white.],
  [`Hot`], [`COLORMAP_HOT`], [Black through red and yellow to white.],
  [`Magma`], [`COLORMAP_MAGMA`], [Perceptually uniform, black to pale yellow.],
  [`Inferno`], [`COLORMAP_INFERNO`], [Perceptually uniform, higher-contrast than `Magma`.],
  [`Plasma`], [`COLORMAP_PLASMA`], [Perceptually uniform, blue through magenta to yellow.],
  [`Viridis`], [`COLORMAP_VIRIDIS`], [Perceptually uniform, and legible in greyscale print.],
  [`Turbo`], [`COLORMAP_TURBO`], [A rainbow rebuilt to be perceptually uniform --- `Jet`'s range without its lies.],
)
]

#subsect("Reading an image")

`ImreadFlags` is the second structured value type, and chapter 7 explains the trap it exists to
close: OpenCV's `IMREAD_*` constants look like OR-able bits and are not. Each `IMREAD_REDUCED_*`
value already carries its own colour bit, and `IMREAD_UNCHANGED` is `-1`, whose bits swamp every
other flag. So the `(colour, scale)` pair maps #emph[totally] onto exactly one named constant rather
than composing, and only `ignoreOrientation` (bit 128) is genuinely independent.

#figure-table("ImreadColor and ImreadScale — the two axes of ImreadFlags.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Case*], [*OpenCV constant*], [*Meaning*],
  [`ImreadColor.Grayscale`], [`IMREAD_GRAYSCALE`], [One channel, whatever the file holds.],
  [`ImreadColor.Color`], [`IMREAD_COLOR`], [Three channels, BGR. The default.],
  [`ImreadColor.ColorRgb`], [`IMREAD_COLOR_RGB`], [Three channels, RGB order --- no `cvtColor` afterwards.],
  [`ImreadColor.Unchanged`], [`IMREAD_UNCHANGED`], [Keep alpha and bit depth exactly as stored. Cannot carry any other flag.],
  [`ImreadColor.AnyDepth`], [`IMREAD_ANYDEPTH`], [Keep 16- or 32-bit depth rather than truncating to 8.],
  [`ImreadScale.Full`], [(no reduction)], [Decode at full resolution. `denom` is `1`.],
  [`ImreadScale.Half`], [`IMREAD_REDUCED_*_2`], [Half each side. `denom` is `2`.],
  [`ImreadScale.Quarter`], [`IMREAD_REDUCED_*_4`], [A quarter each side. `denom` is `4`.],
  [`ImreadScale.Eighth`], [`IMREAD_REDUCED_*_8`], [An eighth each side. `denom` is `8`.],
)
]

Reduced-size decode exists only for `Grayscale` and `Color`, so `ImreadFlags` carries two `require`s
that reject the combinations OpenCV has no constant for --- a reduction on `ColorRgb`, `Unchanged` or
`AnyDepth`, and any extra bit on `Unchanged`. Three constants cover the common cases:
`ImreadFlags.Color`, `ImreadFlags.Grayscale` and `ImreadFlags.Unchanged`.

#sect("Enums that live beside their operations")

Not every typed constant is in `Enums.scala`. Where an enum is used by exactly one operation or one
module, it sits with that code instead, which keeps the scaladoc and the signature on the same
screen.

#figure-table("The typed constants defined outside Enums.scala.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Type*], [*Defined in*], [*Cases, and where the book covers them*],
  [`OutputDepth`], [`core` --- `Ops.scala`], [`SameAsSource` (`-1`), `Unsigned8` (`CV_8U`), `Signed16` (`CV_16S`), `Float32` (`CV_32F`), `Float64` (`CV_64F`). Chapter 9.],
  [`Thickness`], [`core` --- `Draw.scala`], [A sealed trait, not an enum: `Thickness.Stroke(pixels)` and `Thickness.Filled` (`Imgproc.FILLED`), plus the `Thickness.Default` one-pixel stroke. Chapter 14.],
  [`Codec`], [`core` --- `Codec.scala`], [`Mp4v`, `Avc1`, `Mjpg`, `Xvid`, each carrying a `fourcc: Int`. Chapter 19.],
  [`CaptureBackend`], [`core` --- `Video.scala`], [`Any`, `FFmpeg`, `GStreamer`, `V4L2`, `AVFoundation`, `MediaFoundation`, `DirectShow`, `ImageSequence`, `BuiltinMjpeg`. Chapter 20.],
  [`CascadeName`], [`vision` --- `Cascades.scala`], [Thirteen bundled Haar cascades, each carrying a `fileName`. Chapter 24.],
  [`ArucoDictionary`], [`vision` --- `Detectors.scala`], [Twenty-two marker families. Chapter 28.],
  [`TrackerKind`], [`vision` --- `Tracker.scala`], [`Csrt`, `Kcf`, `Mil`. No `cvValue` --- each case builds a different OpenCV class. Chapter 30.],
  [`KeypointLayout`], [`vision` --- `PoseEstimator.scala`], [`Regression`, `Heatmap` --- how a pose network encodes its output tensor. Chapter 29.],
  [`HandGesture`], [`vision` --- `Gesture.scala`], [`Fist`, `OpenPalm`, `ThumbsUp`, `Pointing`, `Victory`, `Unknown`. Chapter 29.],
  [`Steering`], [`vision` --- `Navigator.scala`], [`Straight`, `Left`, `Right`, `Stop`. Chapter 32.],
)
]

`Thickness` is the one entry above that is a sealed trait rather than an enum, and the reason is
worth a line. OpenCV encodes "filled" as a thickness of `-1`, a sentinel ordinary arithmetic will
produce by accident, and it is meaningful only for closed shapes --- `cv::line` asserts
`0 < thickness` and aborts on it. Splitting the two cases into distinct types lets `drawRect` and
`drawCircle` accept a `Thickness` while lines and text accept `Thickness.Stroke` only, so the
mistake stops compiling instead of crashing.

`OutputDepth` earns its type for the opposite reason: `SameAsSource` is the trap. `Sobel` on an
8-bit unsigned image with `ddepth = -1` clips every negative derivative to zero, so half of each edge
silently disappears; `Signed16` followed by `convertScaleAbs` is the fix, and chapter 9 works through
it.

`CascadeName`'s cases are `FrontalFaceAlt`, `FrontalFaceAlt2`, `FrontalFaceDefault`, `ProfileFace`,
`Eye`, `EyeTreeEyeglasses`, `LeftEye2Splits`, `RightEye2Splits`, `Smile`, `FullBody`, `UpperBody`,
`LowerBody` and `RussianPlateNumber`. `ArucoDictionary`'s are `Dict4x4_50` through `Dict7x7_1000`
--- four grid sizes crossed with 50, 100, 250 and 1000 markers --- plus `ArucoOriginal`,
`AprilTag16h5`, `AprilTag25h9`, `AprilTag36h10`, `AprilTag36h11` and `ArucoMip36h12`. Prefer the
smallest dictionary with enough ids for the job: fewer markers means a larger Hamming distance
between them and a more robust detection.

#sect("The geometry value types")

`core/src/scalacv/Geometry.scala` holds five case classes. They exist because
`org.opencv.core.Point`, `Size`, `Rect` and `Scalar` are mutable Java objects with public fields, and
a `Seq` of them handed back from a detector is a set of live handles whose contents can change
underneath you. Copying four numbers across the boundary is cheap, and it turns the result into
ordinary immutable Scala data.

#figure-table("The geometry value types, and their org.opencv.core counterparts.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Type*], [*OpenCV equivalent*], [*Constructor and members*],
  [`Point`], [`org.opencv.core.Point`], [`Point(x: Double, y: Double)`. Origin top-left, `x` right, `y` down. `distanceTo(other)` via `math.hypot`.],
  [`Point3`], [`org.opencv.core.Point3`], [`Point3(x: Double, y: Double, z: Double)`. A model coordinate for pose work; `z` points out of the marker plane toward the camera.],
  [`Size`], [`org.opencv.core.Size`], [`Size(width: Double, height: Double)`. Neither side may be negative; zero is allowed.],
  [`Rect`], [`org.opencv.core.Rect`], [`Rect(x: Int, y: Int, width: Int, height: Int)`. `area: Long`, `topLeft: Point`, `bottomRight: Point`.],
  [`Scalar`], [`org.opencv.core.Scalar`], [`Scalar(v0, v1 = 0, v2 = 0, v3 = 0)`, all `Double`. Up to four channel components in the Mat's own channel order.],
)
]

Three details in that table pay for themselves. `Rect.area` is a `Long` because `width * height`
overflows a signed `Int` past roughly a 46 340-pixel side, which a full-frame region of interest on a
large image reaches. `Rect.bottomRight` is `(x + width, y + height)` --- one past the last enclosed
pixel, the half-open convention OpenCV itself uses. And `Scalar` is in the Mat's channel order, which
for OpenCV means #strong[BGR, not RGB]: `Scalar.Red` is `Scalar(0, 0, 255)`. The companion also
offers `Black`, `White`, `Green` and `Blue`, in the same order.

#memory[
Nothing in this section holds a native pointer, and that is the point of it. A `Seq[Rect]` returned
by a detector, a `Contour`'s `Seq[Point]`, a `Face`'s landmarks --- all of them remain valid after
the `Image` or `Managed[Mat]` they were computed from has been released, and none of them needs a
`Managed` wrapper, a scope, or a `close`. The rule from chapter 5 still holds for everything that
does own memory; these types are outside it by construction. The one thing to keep straight is that
holding a `Rect` does not keep its pixels alive: crop before you leave the scope, not after.
]

#sidebar("Why toCv and from are private")[
Each of these types has a `toCv` producing the `org.opencv.core` object, and each companion has a
`from` reading one back. Both are `private[scalacv]`, and that is deliberate rather than an
oversight.

The conversion allocates. `cv.Point` and `cv.Scalar` are plain Java objects with no native handle, so
those are harmless --- but a public bridge invites the shape where a caller converts, hands the
result to a raw OpenCV call, and keeps it. Keeping the bridge internal means every `org.opencv`
object scalacv creates is one scalacv also frees, and the public surface stays one of pure values.

When you genuinely need the OpenCV form --- because you are calling a method the library does not
wrap --- construct it yourself from the fields: `org.opencv.core.Rect(r.x, r.y, r.width, r.height)`.
It is one line rather than one method call, and it puts the ownership where you can see it.
Appendix A covers that descent in full.
]

The `scalacv-graphs` module adds three value types of its own on the same model: `Color(red, green,
blue, alpha = 255)` with `lighten`, `darken`, `fadeOut`, `withAlpha` and `blend`; `Dash(on, off)`
with the `Dash.dashed`, `Dash.dotted` and `Dash.dense` presets; and `Bounds(minX, minY, maxX, maxY)`
with `width`, `height`, `centerX`, `centerY` and `union`. Chapter 16 covers all three.

#sect("Result types")

Every detector, estimator and analyser in the library returns plain immutable data rather than a
handle. This is the full list, with the chapter that puts each to work.

#figure-table("The result types, and where the book covers them.")[
#tbl(
  columns: (auto, auto, auto),
  [*Type*], [*What it carries*], [*Chapter*],
  [`ThresholdResult`], [`value: Double` --- the cutoff Otsu or Triangle chose. Only the mid-level `Mat.threshold` returns it; `Image.threshold` drops it.], [11],
  [`Contour`], [`points: Seq[Point]`, plus lazy `boundingRect`, `area`, `perimeter`, `centroid`, `convexHull`.], [12],
  [`PolarLine`], [`rho: Float`, `theta: Float` --- an infinite line in Hough space.], [13],
  [`PolarLineWithVotes`], [The same, plus `votes: Int`.], [13],
  [`Segment`], [`x1`, `y1`, `x2`, `y2`, all `Int` --- a finite line segment. Plus `start`, `end`, `length`.], [13],
  [`TextMetrics`], [`size: Size`, `baseline: Int` --- what a string will occupy once drawn.], [14],
  [`CaptureInfo`], [`width`, `height`, `fps`, `frameCount`, `backendName`, plus `size`. Every field advisory.], [19],
  [`CaptureOptions`], [How a capture should be opened. An input, but the same shape.], [19],
  [`Motion`], [`moving: Boolean`, `ratio: Double`, `regions: Seq[Rect]`, plus `regionCount` and `largest`.], [21],
  [`ModelSpec`], [`fileName`, `urls`, `sha256: Option[String]`, `sizeBytes: Option[Long]`. The constructor is private; `ModelSpec(...)` pins a hash, `ModelSpec.unverified` opts out.], [23],
  [`Face`], [`box: Rect`, five `landmarks: Seq[Point]`, `score: Float`, plus the named accessors.], [24],
  [`FaceEmbedding`], [`values: Vector[Float]` --- a face as a comparable vector.], [25],
  [`FaceMatch`], [`name: String`, `similarity: Double`.], [25],
  [`QrCode`], [`text: String` (empty when located but undecodable), `corners: Seq[Point]`.], [28],
  [`ArucoMarker`], [`id: Int`, `corners: Seq[Point]`, clockwise from the marker's top left.], [28],
  [`Pose3D`], [`rvec: Seq[Double]`, `tvec: Seq[Double]` --- a marker's rotation and translation.], [28],
  [`MarkerPose`], [`marker: ArucoMarker`, `pose: Pose3D`.], [28],
  [`Keypoint`], [`name: String`, `point: Point`, `score: Float`.], [29],
  [`PoseTopology`], [`names: Seq[String]`, `edges: Seq[(Int, Int)]` --- the skeleton's wiring.], [29],
  [`Pose`], [`keypoints: Seq[Keypoint]`, `topology: PoseTopology`.], [29],
  [`HeadPose`], [`yaw`, `pitch`, `roll`, all `Double`.], [29],
  [`ObjectTrack`], [`id: Int`, `box: Rect`, `hits: Int`, `age: Int`.], [30],
  [`ChessboardPattern`], [`columns`, `rows`, `squareSize` (default `1.0`), plus `corners`, the inner-corner count `columns × rows`. An input, not a result.], [31],
  [`Intrinsics`], [`fx`, `fy`, `cx`, `cy`, `distortion: Seq[Double]` --- the camera model.], [31],
  [`Calibration`], [`intrinsics`, `imageSize`, `reprojectionError`.], [31],
  [`Track`], [`from: Point`, `to: Point`, `found: Boolean` --- one optical-flow track. Plus `displacement` and `distance`.], [32],
  [`FeatureMatch`], [`queryIndex`, `trainIndex`, `distance: Float`.], [32],
  [`Obstacle`], [`region: Rect`, `nearness: Double`.], [32],
  [`Guidance`], [`steering: Steering`, `clearanceAhead`, and `leftNearness`, `centreNearness`, `rightNearness`.], [32],
  [`CameraMotion`], [`rotation: Seq[Seq[Double]]`, `translation: Seq[Double]`, `inliers: Int`.], [32],
  [`CameraPose`], [`rotation`, `translation` --- an absolute pose rather than a delta.], [32],
  [`LoopClosure`], [`keyframe: Int`, `matches: Int`, `score: Double`.], [32],
  [`OcrWord`], [`text: String`, `confidence: Float`, `box: Rect`.], [33],
  [`OcrResult`], [`text: String`, `words: Seq[OcrWord]`.], [33],
  [`TemplateMatch`], [`location: Rect`, `score: Double`.], [34],
)
]

`CvError`, the sealed error hierarchy of chapter 6, belongs to the same family: it is plain data,
returned as the left of an `Either`, and it survives the scope that produced it.

Eight types deliberately sit outside that table, because they are handles rather than results and the
distinction is the whole point of listing the rest. `Descriptors` (chapter 32) holds a `Managed[Mat]`
of ORB descriptors; `Tracker` and `Kalman` (chapter 30) each own one native object; `ObjectTracker`
(chapter 30) owns a `Kalman` per live track; `MotionDetector` (chapter 21) retains either the
previous frame or an MOG2 background model; `FaceRecognizer` (chapter 25) wraps a
`Managed[FaceRecognizerSF]`; `Odometry` (chapter 32) retains the previous frame and its tracked
points; and `LoopDetector` (chapter 32) owns a `Descriptors` per keyframe. All eight are
`AutoCloseable` and caller-owned: close them, or hand them to `Using.resource`. Everything they hand
back --- a `Seq[Point]`, a `FeatureMatch`, an `ObjectTrack`, a `Motion`, a `CameraMotion`, a
`LoopClosure` --- is in the table above and needs nothing.

#sect("When the case you need is missing")

The enums here are curated, not exhaustive. OpenCV has more than a hundred colour conversions and
`ColorConversion` types ten of them; `Videoio` defines many more capture backends than the nine
`CaptureBackend` names, most of them for hardware no supported platform exposes; two border modes are
left out on purpose. Sooner or later you will want one that is not a case.

The escape hatch is the same one Appendix A describes at length, and it is not a defeat: the raw
`org.opencv.*` surface is never walled off, so pass the integer directly and own the result. `mat`
borrows the `Image`'s handle without consuming it, `Managed.scope` takes ownership of whatever you
allocate inside it, and `Cv.attempt` turns the native throw into a `Left[CvError]`.

The wrong version allocates the destination and then hopes:

#example("The destination leaks on every failing path.")[
```scala
val dst = org.opencv.core.Mat()
Imgproc.cvtColor(img.mat, dst, Imgproc.COLOR_BGR2YUV)  // throws? dst is lost
Images.encode(dst, ".png")                             // returns Left? dst is lost
```
]

The right version puts the destination under a scope before anything can go wrong, and returns plain
data rather than the handle:

#example("Reaching a conversion the enum does not name.")[
```scala
import org.opencv.imgproc.Imgproc
import scalacv.*

// COLOR_BGR2YUV has no ColorConversion case. Pass the constant; let the scope own the Mat.
def yuvBytes(img: Image): Either[CvError, Array[Byte]] =
  Managed.scope: own =>
    val dst = own(org.opencv.core.Mat())
    Cv.attempt("cvtColor")(Imgproc.cvtColor(img.mat, dst, Imgproc.COLOR_BGR2YUV))
      .flatMap(_ => Images.encode(dst, ".png"))
```
]

Two rules make that safe rather than merely possible. Name the OpenCV constant, never the number:
`Imgproc.COLOR_BGR2YUV` survives a version bump and a hard-coded integer does not. And let the
`Array[Byte]` escape the scope, never the `Mat` --- the rule chapter 5 sets for `Managed.scope`
applies here unchanged. Dropping to a raw constant changes the argument you pass, not who owns the
result.

Then open a pull request. A case is a two-line change plus a test, `CONTRIBUTING.md` has the build
commands (`./mill __.compile` and `./mill __.test`, with `./mill __.fix` for scalafix and
`./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll` for the formatter before you push), and
an enum that grows by the cases people actually reached for is a better enum than one designed in
advance. A constant that is genuinely dangerous will be argued about rather than merged --- `BORDER_TRANSPARENT`
and `BORDER_ISOLATED` are both absent on purpose, and the reasoning is in the scaladoc so the
argument does not have to be had twice.

#sect("Next")

Appendix D turns from types to material: the notebooks, sample images and video clips the examples
throughout this book were written against, where they live, and how to fetch them.
