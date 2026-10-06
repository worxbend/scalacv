#import "../lib/book.typ": *

#chapter("Typed Constants and Colour Spaces", subtitle: [Six is greyscale. Seven is also greyscale. Only one of them is the one you meant.])

OpenCV expresses almost every option as an `int`. Which colour space to convert into, how to
extrapolate pixels past the edge of an image, which contours to report, how to interpolate during a
resize, which Hershey font to draw with --- all of it arrives at the native layer as a bare integer,
and the Java binding faithfully passes it through. `Imgproc.cvtColor(src, dst, 6)` is a complete,
compiling, working call. There are more than a hundred colour-conversion codes alone, and the binding
gives every one of them the same type.

That design is defensible in C++, where the constants come from an `enum` and the compiler still
knows something about them. In the Java API they are `public static final int` fields, and the type
information is gone. `Imgproc.COLOR_BGR2GRAY` is 6. `Imgproc.COLOR_RGB2GRAY` is 7. Both are legal
third arguments to `cvtColor`, both produce a single-channel image of exactly the expected
dimensions, and both compile. The difference is that greyscale luminance is a weighted sum ---
roughly 30% red, 59% green, 11% blue --- and the two codes disagree about which stored channel is
which. Hand a BGR image to code 7 and the blue channel is weighted as though it were red. Nothing
throws. You get a grey image that is subtly, consistently wrong, and you find out three stages later
when a contour count drifts or a threshold that was tuned on Tuesday stops holding on Wednesday.

Larger mistakes are louder. Pass a `BORDER_` constant where a `RETR_` was expected and you are still
passing an `int`; the compiler has no opinion. OpenCV usually notices --- an assertion fires natively
and the binding rethrows it as a `CvException` --- but "usually" is carrying weight there: some codes
are in range for the wrong parameter and mean something else there.

The remedy is not clever, and scalacv does not pretend otherwise: give each family of constants a
Scala 3 `enum`, and let the compiler do the one thing it is good at. What is worth reading closely is
where that mapping is _not_ one-to-one --- two of OpenCV's constant families are not enumerations at
all, and a third is one enum standing over two domains that accept different values. And one family,
colour conversion, deserves more than a lookup table, because choosing the right colour
space is the difference between a segmentation that survives a cloud passing over the window and one
that does not.

#sect("The mechanism")

Every typed constant in scalacv is a Scala 3 `enum` with a single parameter, `cvValue`, carrying the
OpenCV integer. `Enums.scala` is the file, and `ColorConversion` is the pattern.

#example("The enum declaration, in full for the first two cases.")[
```scala
enum ColorConversion(val cvValue: Int):
  case BgrToGray extends ColorConversion(Imgproc.COLOR_BGR2GRAY)
  case GrayToBgr extends ColorConversion(Imgproc.COLOR_GRAY2BGR)
  // ... BgrToRgb, RgbToBgr, BgrToHsv, HsvToBgr,
  //     BgrToLab, LabToBgr, BgrToBgra, BgraToBgr
```
]

Three things follow from that one line, and they are the whole contract.

The case names are OpenCV's own names transliterated, not reinvented. `COLOR_BGR2GRAY` becomes
`BgrToGray`: the family prefix goes, because the enum type already carries it; the screaming-snake
spelling becomes `UpperCamelCase`; and the `2` is spelled `To`, because `Bgr2Gray` reads like a
version number. A reader who knows the OpenCV constant can guess the scalacv case, and the reverse.

The integer is not hidden. `cvValue` is a public `val`, so `ColorConversion.BgrToGray.cvValue` is 6
and you can print it, log it, or hand it to a raw call. The wrapper's job is to stop you passing the
wrong constant, not to stop you seeing the right one.

And the whole set is enumerable: `ColorConversion.values` is an `Array` of every case, which is what
makes autocompletion useful the moment you type `ColorConversion.` and what makes the table below
something you consult twice and then stop needing.

#sect("The families")

Thirteen typed-constant families stand on their own in `Enums.scala`, and one more --- `OutputDepth`, the
destination depth for the derivative operators --- lives in `Ops.scala`, beside the operations that
take it. Four further enums in that same file exist to serve the two structured types described in
the next section rather than to be passed on their own: `Threshold.Mode` and `Threshold.Auto`,
`ImreadColor` and `ImreadScale`.

Outside core the pattern travels, but not unchanged. `CaptureBackend` in `Video.scala` and
`ArucoDictionary` in `Detectors.scala` carry a `cvValue` exactly as above. Two others deliberately do
not: `Codec` in `Codec.scala` carries `fourcc: Int`, because a codec identifier is four packed
characters rather than an OpenCV flag, and `CascadeName` in `Cascades.scala` carries
`fileName: String`, because what it names is a bundled resource. `TrackerKind` in `Tracker.scala`
carries nothing at all --- there is no integer behind `Csrt`, `Kcf` and `Mil`, only three different
Java factory classes that `Tracker.create` matches on. The accessor is named for what it holds, so
seeing `cvValue` on a case is itself the signal that a raw OpenCV constant is underneath.

#figure-table("The typed constant families, and what each one steers.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Enum*], [*Steers*], [*Cases*],
  [`ColorConversion`], [`convert`, `cvtColor`], [`BgrToGray`, `GrayToBgr`, `BgrToRgb`, `RgbToBgr`, `BgrToHsv`, `HsvToBgr`, `BgrToLab`, `LabToBgr`, `BgrToBgra`, `BgraToBgr`],
  [`Interpolation`], [`resizeTo`, `scale`, `rotate(degrees)`], [`Nearest`, `Linear`, `Cubic`, `Area`, `Lanczos4`],
  [`BorderType`], [pixels past the edge], [`Constant`, `Replicate`, `Reflect`, `Reflect101`, `Wrap`],
  [`Flip`], [`flip`], [`Horizontal`, `Vertical`, `Both`],
  [`Rotation`], [lossless quarter-turns], [`Clockwise`, `CounterClockwise`, `Half`],
  [`MorphShape`], [the structuring element], [`Rect`, `Ellipse`, `Cross`],
  [`MorphOp`], [`morphology`], [`Open`, `Close`, `Gradient`, `TopHat`, `BlackHat`],
  [`AdaptiveMethod`], [`adaptiveThreshold`], [`Mean`, `Gaussian`],
  [`ContourRetrieval`], [`findContours`], [`External`, `List`, `CComp`, `Tree`],
  [`ContourApproximation`], [`findContours`], [`None`, `Simple`, `Tc89L1`, `Tc89Kcos`],
  [`Colormap`], [`colorMap`], [`Autumn`, `Bone`, `Jet`, `Ocean`, `Hot`, `Magma`, `Inferno`, `Plasma`, `Viridis`, `Turbo`],
  [`Font`], [mid-level `drawText`], [`Simplex`, `Plain`, `Duplex`, `Complex`, `Triplex`, `Script`],
  [`LineType`], [rasterisation], [`Connected4`, `Connected8`, `AntiAliased`],
  [`OutputDepth`], [`sobel`, `laplacian`, `normalize`], [`SameAsSource`, `Unsigned8`, `Signed16`, `Float32`, `Float64`],
)
]

Two case names collide with things already in scope, deliberately. `ContourApproximation.None` and
`ContourRetrieval.List` are named after `CHAIN_APPROX_NONE` and `RETR_LIST`; renaming them to dodge
`scala.None` and `scala.List` would break the one property that makes the scheme worth having, and
qualified there is no ambiguity. `Enums.scala` writes the cost back out where a reader will meet it:
`Threshold`'s `auto` parameter defaults to `scala.None`, spelled in full, so nobody scanning a file
that also defines a case called `None` has to stop and work out which one is meant.

#sect("Where the enum is the wrong shape")

An `enum` says: a value is exactly one of these cases, and every case is as good as any other
wherever the type is accepted. Two of OpenCV's constant families break the first half of that, and a
third breaks the second. All three would have produced an API that could not express the common call,
or one that quietly accepted a value the native layer refuses.

#subsect("Threshold is a bitmask")

`Imgproc.threshold` takes a mode --- binary, inverted binary, truncate, to-zero, inverted to-zero ---
optionally OR-ed with an automatic method, `THRESH_OTSU` or `THRESH_TRIANGLE`. Asking for Otsu's
method is not choosing a sixth mode; it is modifying one of the five. So `Threshold` is a case class
carrying both parts, and `cvValue` does the OR:

#example("A mode, plus at most one automatic modifier.")[
```scala
final case class Threshold(mode: Threshold.Mode, auto: Option[Threshold.Auto] = scala.None):
  def cvValue: Int = mode.cvValue | auto.fold(0)(_.cvValue)
  def computesThreshold: Boolean = auto.isDefined
```
]

The two nested enums are real enumerations --- `Mode` has `Binary`, `BinaryInv`, `Truncate`,
`ToZero`, `ToZeroInv`; `Auto` has `Otsu` and `Triangle` --- and the `Option` encodes the fact that
the two automatic methods are mutually exclusive with each other but not with any mode. Three
companion shortcuts cover the calls people actually write: the `Threshold.Binary` value for a fixed
cutoff, and the `Threshold.otsu(mode)` and `Threshold.triangle(mode)` methods for the automatic ones,
both of which default `mode` to `Mode.Binary` so that `Threshold.otsu()` is the whole call.

`computesThreshold` exists because of what `threshold` returns. OpenCV's function hands back a
`double`, which most wrappers discard; for Otsu and Triangle that number is the cutoff OpenCV chose,
and it is frequently the reason you called the function at all. scalacv keeps it, as
`ThresholdResult(value: Double)`, and `computesThreshold` tells you whether the number means
anything.

#subsect("ImreadFlags only looks like a bitmask")

The `IMREAD_*` constants have the shape of OR-able bits and are not. Each `IMREAD_REDUCED_*` value
already bakes its colour choice in, so OR-ing a colour flag onto a reduction quietly decodes
something other than what you asked for, and `IMREAD_UNCHANGED` is `-1`, whose bits swallow every
other flag entirely.

So `ImreadFlags` models the truth: a `(color, scale)` pair that maps _totally_ onto exactly one named
constant, plus `ignoreOrientation` as the only genuinely independent flag. Combinations OpenCV has no
constant for are rejected by `require` rather than OR-ed into something plausible-looking --- there is
no reduced-size decode for `ColorRgb`, `Unchanged` or `AnyDepth`, and `Unchanged` cannot carry a
reduction or an orientation bit at all.

#subsect("BorderType is one type over two domains")

`BorderType` is the most interesting of the three, because the value is legal in one half of the
library and fatal in the other. OpenCV packs two different sets of accepted values into the same
`int`. `copyMakeBorder` --- behind `pad` and `border` --- and `warpAffine` --- behind `rotated` ---
honour all five modes including `Wrap`. The `imgproc` filter family does not: `gaussianBlur`,
`boxBlur`, `sobel` and `laplacian` all reach `cv::FilterEngine::init`, which asserts
`columnBorderType != BORDER_WRAP` and aborts.

Left to native code, that is not even a consistent failure. Verified against OpenCV 4.13,
`GaussianBlur` with `BORDER_WRAP` on `CV_8U` input _silently ignores_ the mode --- the SIMD path
never reaches the assertion --- and aborts on `CV_32F`. So this is the bug that ships:

```scala
// Raw OpenCV: fine all through development on 8-bit frames,
// aborts the first time it meets a float image.
Imgproc.GaussianBlur(src, dst, org.opencv.core.Size(5, 5), 0, 0, Core.BORDER_WRAP)
```

scalacv rejects it at the Scala boundary instead. `gaussianBlur`, `boxBlur`, `sobel` and `laplacian`
each open with `BorderType.requireFilterSupport(op, border)`, a `require` in `BorderType`'s companion
that takes the scalacv operation name and the caller's mode. Both depths then fail identically,
before any native call, with an `IllegalArgumentException` that names the operation, the assertion it
would have hit, and where the mode _is_ legal:

```text
requirement failed: gaussianBlur does not support BorderType.Wrap: OpenCV's imgproc
filters assert columnBorderType != BORDER_WRAP. Wrap is valid only for pad/border
(copyMakeBorder) and rotated (warpAffine).
```

The library is explicit that this is a stopgap. Splitting the type --- a `FilterBorder` without
`Wrap`, widening into a `TransformBorder` with it --- would make the mistake unrepresentable rather
than merely detected, and that is the stated end state; it is a breaking change to four public
signatures, so it waits. Two related values stay out of the enum entirely: `BORDER_ISOLATED`, a
modifier that means nothing for any call scalacv exposes, and `BORDER_TRANSPARENT`, which
`copyMakeBorder` throws on and which leaves `warpAffine`'s freshly-allocated destination
uninitialised.

There is also no single border default across the library, and the split is on purpose.

#figure-table("The border default depends on what you are doing.")[
#tbl(
  columns: (1fr, auto),
  [*Operation*], [*Its default*],
  [`pad`, `border`, mid-level `rotated`], [`Constant`, filled with `Scalar.Black`],
  [`gaussianBlur`, `boxBlur`, `sobel`, `laplacian`], [`Reflect101`],
)
]

Mirroring is right for a filter, because a constant black edge bleeds inward and darkens the border
of a blurred image; a constant colour is right for padding, because a visible margin is usually the
entire point. The parameter names differ with the domain, too: `pad` and `border` take
`borderType`, while the filters and `rotated` take `border` --- and `rotated` splits the mode from
the colour, taking `border` alongside a separate `color`.

#sect("Colour, and the space you reason in")

Everything above is machinery. Colour conversion is where the typed constants stop being a
type-safety story and start being a modelling decision, so it is worth working one problem all the
way through.

The problem: a photograph of a shelf, and you want the bottles with green labels --- how many, and
where.

The instinct is to threshold on the pixel values directly. OpenCV Mats are BGR by default, not RGB
--- a trap worth internalising, and the reason `Scalar.Red` is `Scalar(0, 0, 255)` --- so "green" is a
box around high channel-1 values with low channel 0 and 2. It works on the photograph you tuned it
against and collapses on the next one, taken half an hour later with the blind half-drawn, because
BGR encodes brightness and colour together in all three channels at once. A green label in bright
light is `(40, 200, 60)`; the same label in shadow is `(14, 70, 21)`. Same colour, same object, and
no fixed box in BGR contains both without also containing half the shelf.

#subsect("HSV separates the question")

HSV splits a pixel into hue --- which colour it is, as an angle around a wheel --- saturation --- how
vivid, from grey to pure --- and value --- how bright. The green label's hue barely moves between
sunlight and shadow; it is value that collapses, and saturation that softens. So "green, at any
brightness" becomes a wide range on one channel and a generous floor on the other two, which is a
condition that survives the blind being drawn.

That is why `toHsv` gets a name of its own on `Image`, as only `gray` otherwise does: eight of the
ten conversions are spelled `convert(ColorConversion.X)`, and these two are reached for often enough
that the long form was noise.

#example("BGR in, a binary mask out.")[
```scala
val written: Either[CvError, Unit] =
  Image.reading("shelf.jpg") { shelf =>
    val mask = shelf.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
    try shelf.applyMask(mask).write("green-labels.png")
    finally mask.close() // applyMask only borrowed it
  }.flatten
```
]

`inRange` yields a single-channel `CV_8UC1` mask --- 255 where every channel falls inside the bounds,
0 elsewhere --- whatever the source's channel count was. `applyMask` then keeps the original pixels
only where the mask is white. Both `reading` and `write` return an `Either[CvError, ?]`, so the block
produces a nested pair that `flatten` collapses into the single `Left` a caller can act on --- the
decode failing and the encode failing are both failures to produce the file.

#memory[
`applyMask` and `blend` take a second image that is *borrowed*, not consumed: the mask stays alive
after the call and you close it yourself. Every other verb in that chain spends its receiver, which
is why the mask is built from `shelf.copy` --- without the copy, `toHsv` would consume the only
`Image` you have and `applyMask` would be called on a spent handle. Inside `Image.reading` the
original is released for you on the way out, on success and on exception alike; the mask is not,
because `reading` never owned it.
]

#subsect("Hue is 0 to 179, not 0 to 359")

The bounds in that listing are the part that catches everyone. A hue wheel has 360 degrees. An 8-bit
channel holds 0 to 255. OpenCV's answer, for 8-bit images, is to halve the angle: *hue runs 0--179*,
while saturation and value run the full 0--255. Green sits near 120° on the wheel, which is 60 here.

So the mistake readers actually make is to write the degrees they know:

```scala
// Wrong: 100--140 on a 0--179 scale is blue. This mask comes back almost entirely black.
hsv.inRange(Scalar(100, 80, 80), Scalar(140, 255, 255))
```

and get an empty result with no error, because 100 to 140 is a perfectly valid range that happens to
select blue. The halving applies only to 8-bit images; convert to `CV_32F` first and OpenCV keeps the
full 0--360. Nothing in the type system distinguishes the two, so the rule is: on the 8-bit images
you will spend almost all of your time with, halve the degrees.

#figure-table("Hue centres on OpenCV's 8-bit 0--179 scale. Widen the saturation and value floors to admit washed-out or shadowed pixels.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Colour*], [*Hue*], [*Note*],
  [Red], [0--10 #emph[and] 170--179], [Wraps the seam --- needs two ranges],
  [Orange], [≈10--20], [],
  [Yellow], [≈25--35], [],
  [Green], [≈35--85], [The labels above],
  [Cyan], [≈85--95], [],
  [Blue], [≈100--130], [],
  [Magenta], [≈140--160], [],
)
]

Red is the awkward one, and not because of the scale. Hue is circular, and red sits exactly on the
0/179 seam, so no single `inRange` captures it: you build two masks, one at each end, and OR them
together. `inRange` is wrapped and a raw bitwise OR is not, so that one step drops to
`org.opencv.core.Core.bitwise_or` --- which is exactly what the escape hatch below is for.

#subsect("The other spaces")

Greyscale is the input format for most of the library: edges, thresholds, contours and `colorMap`
all expect a single channel, and `adaptiveThreshold` and `equalizeHist` accept `CV_8UC1` and nothing
else. `gray` is the shortcut, and it is exactly `convert(ColorConversion.BgrToGray)`.

Lab is the one to reach for when you need perceptual colour _distance_ --- how different two colours
look to a person. Euclidean distance in BGR is close to meaningless for that; in Lab it is roughly
proportional to perceived difference, which is what makes it the space for white balance and for
matching a swatch against a sample.

`BgrToRgb` and `RgbToBgr` only reorder channels, for the boundary with libraries that assume RGB;
`BgrToBgra` and `BgraToBgr` add and drop alpha. Those two and the two greyscale cases are the only
four that touch the channel count --- the rest change what each channel _means_ and leave the count
alone, so a 3-channel BGR image becomes a 3-channel HSV one and `channel(0)` on the result is the hue
plane rather than the blue one.

#sidebar("Why blue comes first")[
Nothing about BGR is principled; it is a fossil. When OpenCV's first releases appeared around the
turn of the century, the ordering that Windows device-independent bitmaps and the frame-grabber cards
of the day used for 24-bit colour was blue-green-red, and reading a frame straight into a buffer
without permuting it was free. Every later decision inherited that: `imread` returns BGR, `imwrite`
expects BGR, the drawing functions take BGR scalars, and a quarter-century of tutorials assume it.

The cost lands downstream. A frame handed to a library that assumes RGB --- a neural-network
preprocessor, an image viewer, a plotting library --- comes back with the reds and blues swapped,
which is the most recognisable bug in computer vision: a person with blue skin against an orange sky.
scalacv does not try to fix this: reordering channels silently would put the library permanently out
of step with the OpenCV documentation its users read. It names the ordering instead --- in `Scalar`'s
scaladoc, in the constants, and in `BgrToRgb` being spelled out rather than called `toRgb`.
]

#sect("When the enum you need is missing")

`ColorConversion` has ten cases. OpenCV has more than a hundred conversion codes. That gap is not an
oversight to be apologised for; it is the point. The ten are the ones the library uses, tests and
documents, and adding `COLOR_BGR2YCrCb` to the enum on the grounds that it exists would trade a set
you can read for a set you have to search.

When you need one of the others, you drop a level and pass the raw `int`. This is a supported move
with a documented shape, not a workaround:

#example("A conversion the enum does not name, done at the raw level and lifted straight back up.")[
```scala
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

val luma: Either[CvError, Unit] =
  Image.reading("shelf.jpg") { shelf =>
    val raw = Mat()
    Imgproc.cvtColor(shelf.mat, raw, Imgproc.COLOR_BGR2YCrCb) // borrowed in, fresh Mat out
    Image.wrap(Managed(raw)).channel(0).write("luma.png")     // adopted; released on write()
  }.flatten
```
]

Three doorways make that work, and knowing which is which is the whole skill. `img.mat` *borrows* the
underlying `org.opencv.core.Mat`: the `Image` still owns it, so a raw call may read from it and write
into a destination of its own, but must never release it. `img.managed` *hands ownership over*,
leaving the `Image` spent and the returned `Managed[Mat]` yours to release. `Image.wrap(managed)`
hands ownership back the other way.

#memory[
`Imgproc.cvtColor` fills a bare `Mat` that has no owner: if the wrapping line is never reached, it
leaks a full-size pixel buffer that the collector will not reclaim in any useful timeframe. Wrap a
raw result in `Managed` --- or adopt it as an `Image` --- in the block that created it, so something
is on the hook for the release whatever happens next. And never call `release()` on `img.mat`: that
frees a pointer the `Image` still believes it owns, which is a double free rather than an
exception.
]

The mid level is the same move with the ownership already sorted out: every pixel-producing
extension op on a `Mat` --- `cvtColor`, `gaussianBlur`, `inRange` --- hands back an owned
`Managed[Mat]` allocated by `Mats.produce`, which releases the half-built destination itself if the
native call throws. `pipe` threads one op into the next and releases each intermediate in a
`finally`; `Mats.chain` is the same fold written as a list of stages. A typed `cvtColor` and a raw
one compose in the same chain, and the only difference is who wraps the output.

#sect("The value types")

Constants are half of the vocabulary. The other half is geometry, and `Geometry.scala` defines five
value types: `Point`, `Point3`, `Size`, `Rect` and `Scalar`.

#figure-table("The geometry value types. All are immutable Scala case classes, copied across the native boundary.")[
#tbl(
  columns: (auto, 1fr, auto, 1fr),
  [*Type*], [*Fields*], [*Element*], [*Invariant*],
  [`Point`], [`x`, `y`], [`Double`], [none],
  [`Point3`], [`x`, `y`, `z`], [`Double`], [none],
  [`Size`], [`width`, `height`], [`Double`], [neither side negative],
  [`Rect`], [`x`, `y`, `width`, `height`], [`Int`], [non-negative extent],
  [`Scalar`], [`v0`--`v3`], [`Double`], [none],
)
]

They are copies, not wrappers, which is the design decision worth understanding.
`org.opencv.core.Rect` is a mutable Java object with public fields, and a `Seq` of them handed back
by a detector is a set of live handles whose contents can change underneath you. Copying four ints at
the boundary is cheap, and it turns detector output into ordinary immutable Scala data --- something
you can pattern-match on, key a `Map` by, or send to another thread.

#memory[
Because they are copies, geometry survives the image. A `Seq[Rect]` from a detector is still valid
long after the `Mat` it was measured from has been released --- which is exactly what lets
`Image.reading` free the image at the end of the block while you keep the boxes. Nothing in these
five types points at native memory, so nothing in them can dangle.
]

The invariants are enforced where they are cheap. `Size` and `Rect` both `require` a non-negative
extent, though `Rect`'s origin may be negative, since a region of interest can legitimately extend
past the top-left of an image. `Rect.area` returns a `Long`, because `width * height` overflows a
signed `Int` past roughly a 46340-pixel side. `Point.distanceTo` uses `math.hypot`, which will not
overflow while squaring.

`Scalar` is the pixel value: up to four channel components, in whatever order the `Mat` uses, unset
channels defaulting to 0. Its constants are ordered for BGR, which is the last time this chapter will
say so: `Scalar.Red` is `Scalar(0, 0, 255)`, `Scalar.Blue` is `Scalar(255, 0, 0)`, and `Scalar.Black`
and `Scalar.White` sidestep the question entirely.

#sect("Where this goes next")

The mask cut from the shelf photograph is not finished work. A freshly thresholded mask is grainy at
the edges and speckled in the middle: stray pixels that passed the hue test, small holes where a
highlight blew the saturation out, and counting contours on it now would count the speckle. Cleaning
that up is morphology --- `MorphOp.Open` to erase the specks, `MorphOp.Close` to fill the holes, both
shaped by a `MorphShape` --- and it is the subject of the next chapter, #emph[Filtering and
Morphology], which picks the mask up where this one put it down.
