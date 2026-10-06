# Image processing

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

This is the operation catalogue — the verbs you apply to pixels: colour conversion, blurring, edges,
thresholding, morphology, geometric transforms, colour grading, restoration. If you have pixels in an
[`Image`](/image-api) (see [reading & writing](/image-io) for how they get there) and you want to *do*
something to them, this is the page.

Almost everything is offered at **two levels**, and a lot of the page is about knowing which to reach for:

- The **high-level** [`Image`](/image-api) API — verbs that chain (`gray`, `blur`, `canny`, …). Reach
  for it first; it frees every intermediate for you.
- The **mid-level** `Mat` extension ops in `Ops` — the same operations one step down, returning a
  caller-owned [`Managed[Mat]`](/mat-lifecycle). Reach for it when you need a knob `Image` does not
  surface (the threshold value Otsu chose, a signed-depth Sobel), or when you are already working with
  raw `Mat`s from a detector or a video frame.

:::tip[Beginner path]
If you are just starting, stay on the high-level side: `Image.read(...).map(_.gray.blur(2).canny(80,
160).write("edges.png"))`. Every verb consumes the image and returns a new one, so a chain never leaks.
Come back to the mid-level ops when you hit something `Image` does not expose.
:::

Everything below runs against a synthetic scene so no image file is needed:

```scala mdoc:invisible
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Scalar => CvScalar, Point => CvPoint}

// A raw BGR Mat with a filled rectangle and circle — the caller owns it and must release it.
def bgr(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(40, 40, 40))
  Imgproc.rectangle(m, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220, 220, 220), -1)
  Imgproc.circle(m, CvPoint(125, 60), 24, CvScalar(255, 255, 255), -1)
  m

// The same scene as an Image (a transform consumes it, so build a fresh one each call).
def scene(): Image = Image.wrap(Managed(bgr()))
```

## The ownership contract, in one paragraph

Every mid-level op is **pure with respect to its receiver**: it allocates a fresh destination `Mat`,
writes the result there, and hands that back as a `Managed[Mat]` that **you now own and must
release**. The receiver is never written to, never freed, never aliased into the result — so an op is
safe on a borrowed `Mat` (a video frame, a detector's input) with no transfer-of-ownership ceremony.
There are no in-place variants. Because each stage returns its own `Managed`, a naive two-step
pipeline strands the intermediate; the [combinators below](#chaining-without-leaks) exist to stop that.

The high-level `Image` gives you the same guarantee through *move semantics* instead: each transform
consumes the image it was called on, so a pipeline holds one live Mat at a time. See the
[Image API](/image-api) for the full contract.

## The operation map

A quick index of the two tiers, so you know a verb exists before you go looking. Each row is expanded in
its own section below.

| Category | High-level (`Image`) | Mid-level (`Mat` op) |
|---|---|---|
| Colour space | `gray`, `toHsv`, `convert` | `cvtColor` |
| Blur | `blur`, `gaussianBlur`, `medianBlur`, `bilateralFilter` | `gaussianBlur`, `boxBlur`, `medianBlur`, `bilateralFilter` |
| Sharpen | `sharpen` | `sharpen` |
| Edges | `canny` | `canny`, `sobel`, `laplacian` |
| Threshold | `threshold`, `adaptiveThreshold`, `equalizeHist`, `normalize` | `threshold`, `adaptiveThreshold`, `equalizeHist`, `normalize` |
| Resize | `resize`, `resizeTo`, `scale`, `crop` | `resize`, `scaled` |
| Geometry | `flip`, `rotate`, `pad`, `border`, `undistort`, `deskew` | `flip`, `rotate`, `rotated`, `border`, `undistorted`, `deskew` |
| Morphology | `erode`, `dilate`, `morphology` | `erode`, `dilate`, `morphology` |
| Masking | `inRange`, `applyMask`, `invert`, `channel` | `inRange`, `masked`, `bitwiseNot`, `absdiff`, `extractChannel` |
| Tone / grade | `adjust`, `gamma`, `posterize`, `saturate`, `temperature`, `sepia`, `emboss`, `colorMap` | `convertScaleAbs`, `gamma`, `posterize`, `saturate`, `temperature`, `sepia`, `emboss`, `colorMap` |
| Blend | `blend` | `addWeighted` |
| Photo | `stylize`, `sketch`, `enhance`, `edgePreserving`, `inpaint`, `seamlessCloneInto` | same names |
| Compose | `filter`, chained transforms | `pipe`, `Mats.chain` |

## Colour conversion

Mid-level `cvtColor` takes a typed [`ColorConversion`](/geometry) — no raw `int` constants — and the
result's channel count follows the conversion, not the source:

```scala mdoc:silent
val srcCol = bgr()
val grayBytes: Either[CvError, Array[Byte]] =
  srcCol.cvtColor(ColorConversion.BgrToGray).use(Images.encode(_, ".png"))
srcCol.release()
```

High-level, `convert` is the same thing, and `gray` is the common `BgrToGray` case named:

```scala mdoc:silent
scene().convert(ColorConversion.BgrToHsv).close()
scene().gray.close()
```

The available conversions, and when each matters:

| `ColorConversion` | Goes to | Why you'd want it |
|---|---|---|
| `BgrToGray` / `GrayToBgr` | 1 ch ↔ 3 ch | almost every detector wants grey; go back to draw colour |
| `BgrToHsv` / `HsvToBgr` | HSV | threshold by hue for [colour masking](/color-masking) |
| `BgrToLab` / `LabToBgr` | L\*a\*b\* | perceptually-uniform colour work |
| `BgrToRgb` / `RgbToBgr` | swap R/B | hand pixels to a library that expects RGB |
| `BgrToBgra` / `BgraToBgr` | add / drop alpha | gain or discard a transparency channel |

:::warning[OpenCV is BGR, not RGB]
Channels are **blue, green, red** — the reverse of what most other imaging code assumes. `Scalar.Red` is
`Scalar(0, 0, 255)`. If colours come out swapped after talking to another library, you need a
`BgrToRgb`.
:::

## Blurring

Blurs come in a family, each with a different trade of speed against what it preserves:

| Op | What it does | Keeps edges? | Typical use |
|---|---|---|---|
| `boxBlur` / `blur` | average of a window | no | cheapest smoothing |
| `gaussianBlur` | weighted (bell) average | no | general-purpose denoise, pre-edge |
| `medianBlur` | median of a window | somewhat | salt-and-pepper noise |
| `bilateralFilter` | edge-aware weighted average | yes | denoise while keeping edges crisp (slow) |

`gaussianBlur` takes an odd, positive [`Size`](/geometry) kernel (or `Size(0, 0)` to derive it from
sigma); `boxBlur` is the normalised box filter. Both return an owned `Managed[Mat]`:

```scala mdoc:silent
val srcBlur = bgr()
val soft: Either[CvError, Array[Byte]] =
  srcBlur.gaussianBlur(Size(5, 5), sigmaX = 1.5).use(Images.encode(_, ".png"))
val boxed: Either[CvError, Array[Byte]] =
  srcBlur.boxBlur(Size(3, 3)).use(Images.encode(_, ".png"))
srcBlur.release()
```

:::note[Why `boxBlur`, not `blur`, at the mid level]
The high-level [`Image.blur`](/image-api) is a radius-based *Gaussian*. A mid-level method sharing that
name would silently switch filter families — and change the output — the moment you dropped from
`image.blur(2)` to `image.mat.blur(...)`. They are different algorithms, so the names differ.
:::

Median and bilateral are the noise-specific tools. `medianBlur` wants an odd `ksize` ≥ 3; `bilateralFilter`
smooths flat regions while leaving edges alone (markedly slower than a Gaussian):

```scala mdoc:silent
val srcNoise = bgr()
val median: Either[CvError, Array[Byte]] =
  srcNoise.medianBlur(3).use(Images.encode(_, ".png"))
val bilateral: Either[CvError, Array[Byte]] =
  srcNoise.bilateralFilter(diameter = 9, sigmaColor = 75, sigmaSpace = 75).use(Images.encode(_, ".png"))
srcNoise.release()
```

High-level `blur(radius)` is the quick radius form — `radius` 2 is a 5×5 kernel, `radius` 0 is the
identity — with `gaussianBlur(kernel, sigmaX, sigmaY)`, `medianBlur(radius)` and `bilateralFilter(...)`
available for the rest:

```scala mdoc:silent
scene().blur(2).close()
scene().gaussianBlur(Size(5, 5), sigmaX = 1.5).close()
scene().medianBlur(1).close()
scene().bilateralFilter().close()
```

## Sharpening

`sharpen` is unsharp masking: it adds back `amount` × (image − its blur). `amount` 0 is a no-op, ~1 is a
firm sharpen, higher haloes the edges:

```scala mdoc:silent
val srcSharp = bgr()
val crisp: Either[CvError, Array[Byte]] =
  srcSharp.sharpen(1.0).use(Images.encode(_, ".png"))
srcSharp.release()

scene().sharpen().close()
```

## Edges

`canny` always produces a `CV_8UC1` result regardless of the source type:

```scala mdoc:silent
val srcEdge = bgr()
val edges: Either[CvError, Array[Byte]] =
  srcEdge.cvtColor(ColorConversion.BgrToGray)
     .pipe(_.canny(60, 180))
     .use(Images.encode(_, ".png"))
srcEdge.release()
```

`scene().gray.canny(60, 180)` is the high-level equivalent.

:::tip[Name Canny's thresholds]
`canny(threshold1, threshold2)` — the weak (linking) and strong edge levels — are both `Double` and
silently swappable. Name them at the call site (`canny(threshold1 = 80, threshold2 = 160)`) when the
ordering is not obvious. A rough starting point is a 1:2 or 1:3 ratio.
:::

### The Sobel depth trap

`sobel` takes a derivative order (`dx`, `dy`) and an [`OutputDepth`](/geometry). The default,
`SameAsSource`, is a trap on the commonest input: on an 8-bit unsigned image it clips every *negative*
derivative to zero, so half of each edge silently disappears. The fix is to compute into
`Signed16`, then bring it back to a displayable 8-bit image with `convertScaleAbs` (which scales,
takes the absolute value, and saturating-casts). `Mats.chain` threads that through cleanly:

```scala mdoc:silent
val srcSobel = bgr()
val gradientX: Either[CvError, Array[Byte]] =
  Mats.chain(srcSobel)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16),
    _.convertScaleAbs()
  ).use(Images.encode(_, ".png"))
srcSobel.release()
```

`laplacian` has the same depth consideration and the same `Signed16` → `convertScaleAbs` remedy:

```scala mdoc:silent
val srcLap = bgr()
val lap: Either[CvError, Array[Byte]] =
  Mats.chain(srcLap)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.laplacian(depth = OutputDepth.Signed16),
    _.convertScaleAbs()
  ).use(Images.encode(_, ".png"))
srcLap.release()
```

The `OutputDepth` cases:

| `OutputDepth` | Meaning |
|---|---|
| `SameAsSource` | `ddepth = -1` — the trap on 8-bit input |
| `Unsigned8` | 8-bit, clips negatives |
| `Signed16` | 16-bit signed — keeps the negative lobe of a derivative |
| `Float32` / `Float64` | floating point, for further numeric work |

## Histogram equalisation

`equalizeHist` redistributes intensities to use the full range — it lifts a flat, low-contrast image. It
accepts `CV_8UC1` only, so it is normally preceded by a `gray` step:

```scala mdoc:silent
val flat = Mat(120, 160, CvType.CV_8UC1, CvScalar(90))
val stretched: Either[CvError, Array[Byte]] =
  flat.equalizeHist().use(Images.encode(_, ".png"))
flat.release()
```

High-level: `scene().gray.equalizeHist`. For a gentler linear stretch instead, `normalize(min, max)`
rescales into a range without redistributing. It also brings the result down to 8-bit by default,
which is what makes a float or 16-bit intermediate — a Sobel response, a distance transform, a
disparity map — actually displayable; pass `OutputDepth.SameAsSource` when you need to keep the
source's precision:

```scala mdoc:silent
scene().gray.equalizeHist.close()
scene().gray.normalize(0, 255).close()
```

## Thresholding

Mid-level `threshold` returns a `Thresholded` carrying **both** the mask (`image`) and the value OpenCV
computed (`computed`, a `ThresholdResult`). For a fixed threshold that number is just the value you
passed back; for the automatic methods it is the threshold OpenCV *chose* — often the reason you called
it. Select a method with [`Threshold`](/geometry): a plain `Threshold.Mode`, or `Threshold.otsu(...)` /
`Threshold.triangle(...)`:

```scala mdoc:silent
val gray = Mat(120, 160, CvType.CV_8UC1, CvScalar(90))
Imgproc.rectangle(gray, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220), -1)
```

```scala mdoc
val Thresholded(otsuMask, otsuResult) = gray.threshold(0, 255, Threshold.otsu())
otsuResult.value // the level Otsu picked
```

A fixed threshold is the same shape with the default `Binary` mode:

```scala mdoc:silent
val Thresholded(mask, _) = gray.threshold(127, 255, Threshold(Threshold.Mode.BinaryInv))
mask.release()
```

```scala mdoc:invisible
otsuMask.release(); gray.release()
```

The `Threshold.Mode` cases decide what happens on each side of the cut:

| `Threshold.Mode` | Above threshold → | Below → |
|---|---|---|
| `Binary` | `maxValue` | 0 |
| `BinaryInv` | 0 | `maxValue` |
| `Truncate` | threshold | unchanged |
| `ToZero` | unchanged | 0 |
| `ToZeroInv` | 0 | unchanged |

High-level `threshold` drops the computed value (the common "binarise" case). Reach for the mid-level
op above when you need the number an `Auto` method chose:

```scala mdoc:silent
scene().gray.threshold(127).close()
```

### Adaptive thresholding

A single global cut fails under uneven lighting — one side of a document scan comes out solid black.
`adaptiveThreshold` computes a threshold *per neighbourhood* instead, which is why it holds up on scans
and is the standard OCR pre-step. `CV_8UC1` only; `blockSize` is the odd neighbourhood side, `c` a
constant subtracted from the local mean (raise it to keep less):

```scala mdoc:silent
val srcAdapt = Mat(120, 160, CvType.CV_8UC1, CvScalar(90))
Imgproc.rectangle(srcAdapt, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220), -1)
val docMask: Either[CvError, Array[Byte]] =
  srcAdapt.adaptiveThreshold(blockSize = 15, c = 4).use(Images.encode(_, ".png"))
srcAdapt.release()

scene().gray.adaptiveThreshold(blockSize = 15, c = 4).close()
```

:::note[Parameter order differs by tier — on purpose]
High-level `adaptiveThreshold` leads with `(blockSize, c)`, the two you actually tune; mid-level mirrors
OpenCV's own `(maxValue, method, blockSize, c)`. The leading params have different types across tiers, so
a positional call meant for one will not compile against the other — use named arguments and the order
stops mattering.
:::

## Resize and scale

Mid-level distinguishes an absolute target `Size` (`resize`) from independent x/y factors (`scaled`),
each with a typed [`Interpolation`](/geometry):

```scala mdoc:silent
val srcResize = bgr()
val small: Either[CvError, Array[Byte]] =
  srcResize.resize(Size(80, 60)).use(Images.encode(_, ".png"))
val half: Either[CvError, Array[Byte]] =
  srcResize.scaled(0.5, 0.5, Interpolation.Area).use(Images.encode(_, ".png"))
srcResize.release()
```

High-level offers `resize(width, height)`, `resizeTo(size)`, `scale(factor)`, and `crop(rect)` (which
returns an independent copy, not an aliasing view):

```scala mdoc:silent
scene().resize(80, 60).close()
scene().scale(0.5).close()
scene().crop(Rect(10, 10, 60, 60)).close()
```

Which [`Interpolation`](/geometry) to pass:

| `Interpolation` | Best for |
|---|---|
| `Area` | **downscaling** — avoids the moiré `Linear` leaves |
| `Linear` | the fast general default |
| `Cubic` | smoother **upscaling** than `Linear` |
| `Lanczos4` | highest-quality upscaling (slowest) |
| `Nearest` | label maps / masks you must not interpolate |

## Geometric transforms

Flips and quarter-turns are exact — no interpolation, no data loss. `flip` mirrors across an axis,
`rotate(Rotation)` turns in 90° steps:

```scala mdoc:silent
val srcGeo = bgr()
val mirrored: Either[CvError, Array[Byte]] =
  srcGeo.flip(Flip.Horizontal).use(Images.encode(_, ".png"))
val turned: Either[CvError, Array[Byte]] =
  srcGeo.rotate(Rotation.Clockwise).use(Images.encode(_, ".png"))
srcGeo.release()

scene().flip(Flip.Vertical).close()
scene().rotate(Rotation.Half).close()
```

| Enum | Cases |
|---|---|
| `Flip` | `Horizontal` (left↔right), `Vertical` (top↔bottom), `Both` (180° point reflection) |
| `Rotation` | `Clockwise`, `CounterClockwise`, `Half` |

`rotated(degrees)` (mid) / `rotate(degrees, scale)` (high) turns by an arbitrary angle — counter-clockwise
— **expanding the canvas so no corner is clipped**, filling the exposed border:

```scala mdoc:silent
val srcRot = bgr()
val tilted: Either[CvError, Array[Byte]] =
  srcRot.rotated(degrees = 15, scale = 1.0).use(Images.encode(_, ".png"))
srcRot.release()

scene().rotate(15.0).close()
```

`border` / `pad` adds padding; `undistort` removes lens distortion given calibrated
[`Intrinsics`](/calibration); `deskew` finds the dominant text tilt and straightens it (the OCR step):

```scala mdoc:silent
val srcBorder = bgr()
val padded: Either[CvError, Array[Byte]] =
  srcBorder.border(top = 10, bottom = 10, left = 10, right = 10, color = Scalar.White)
    .use(Images.encode(_, ".png"))
srcBorder.release()

scene().pad(10, color = Scalar.White).close()
scene().deskew().close()
```

## Morphology

Morphology reshapes *binary* regions with a structuring element — it is what you run after thresholding
to clean up a mask (see [colour masking](/color-masking)). `erode` shrinks bright regions and clears
specks; `dilate` grows them and fills gaps. `morphology` runs the compound operations:

```scala mdoc:silent
val srcMorph = bgr()
val opened: Either[CvError, Array[Byte]] =
  srcMorph.morphology(MorphOp.Open, radius = 2).use(Images.encode(_, ".png"))
val eroded: Either[CvError, Array[Byte]] =
  srcMorph.erode(radius = 1).use(Images.encode(_, ".png"))
srcMorph.release()

scene().dilate(radius = 2).close()
scene().morphology(MorphOp.Close, radius = 2).close()
```

| `MorphOp` | Effect |
|---|---|
| `Open` | erode then dilate — removes small bright specks |
| `Close` | dilate then erode — fills small dark holes |
| `Gradient` | dilation minus erosion — an outline of the shapes |
| `TopHat` | source minus its opening — bright detail smaller than the kernel |
| `BlackHat` | closing minus the source — dark detail smaller than the kernel |

The kernel `shape` is a [`MorphShape`](/geometry): `Rect`, `Ellipse`, or `Cross`.

## Masking and channels

These are the building blocks of colour segmentation. `inRange` produces a binary mask (`CV_8UC1`, 0 or
255) of the pixels whose every channel lies within `[lo, hi]` — usually run on an HSV image; `masked`
keeps the source only where a borrowed mask is non-zero; `bitwiseNot` inverts; `extractChannel` pulls one
channel out; `absdiff` is the per-pixel `|a - b|` behind frame-difference [motion detection](/motion-detection):

```scala mdoc:silent
val srcSeg = bgr()
val redMask: Either[CvError, Array[Byte]] =
  srcSeg.cvtColor(ColorConversion.BgrToHsv)
    .pipe(_.inRange(Scalar(0, 100, 100), Scalar(10, 255, 255)))
    .use(Images.encode(_, ".png"))
srcSeg.release()
```

`masked` and `inpaint` take a **borrowed** mask — it is not consumed, so you close it yourself:

```scala mdoc:silent
val photo = bgr()
val maskMat = Mat(120, 160, CvType.CV_8UC1, CvScalar(0))
Imgproc.circle(maskMat, CvPoint(80, 60), 30, CvScalar(255), -1)
val cutout: Either[CvError, Array[Byte]] =
  photo.masked(maskMat).use(Images.encode(_, ".png"))
maskMat.release() // the mask is borrowed — release it yourself
photo.release()
```

High-level equivalents — `inRange` and `applyMask` consume the receiver; the mask passed to `applyMask`
is borrowed:

```scala mdoc:silent
scene().toHsv.inRange(Scalar(0, 100, 100), Scalar(10, 255, 255)).close()
scene().invert.close()
scene().channel(0).close() // the blue channel
```

:::tip
For the whole colour-segmentation workflow — HSV ranges, cleaning the mask with morphology, compositing —
see the dedicated [colour masking](/color-masking) page.
:::

## Tone and colour grading

These reshape intensity or colour without changing geometry. `convertScaleAbs` (also the Sobel companion)
is `self * alpha + beta` saturating to 8-bit — the high-level `adjust(brightness, contrast)` wraps it:

```scala mdoc:silent
val srcTone = bgr()
val brighter: Either[CvError, Array[Byte]] =
  srcTone.convertScaleAbs(alpha = 1.2, beta = 20).use(Images.encode(_, ".png"))
srcTone.release()

scene().adjust(brightness = 20, contrast = 1.2).close()
```

The colour-grade family, all mid-level (each with an identically-named high-level verb):

| Op | Effect |
|---|---|
| `gamma(g)` | `< 1` darkens mid-tones, `> 1` lifts them |
| `posterize(levels)` | quantises to `levels` tones per channel |
| `saturate(factor)` | `> 1` vivid, `< 1` muted, `0` grey (still 3-channel) |
| `temperature(shift)` | `> 0` warm (more red), `< 0` cool (more blue), in `[-1, 1]` |
| `sepia` | classic sepia tone |
| `emboss` | directional relief |
| `colorMap(map)` | false-colour a 1-channel image into a heatmap |

```scala mdoc:silent
scene().gamma(0.8).close()
scene().posterize(6).close()
scene().saturate(1.4).close()
scene().temperature(0.4).close()
scene().sepia.close()
scene().emboss.close()
```

`colorMap` turns a single-channel image (a depth map, a motion field, any data) into a colour heatmap —
the perceptually-uniform maps are the honest choice for data; `Jet` is the classic-but-misleading rainbow:

```scala mdoc:silent
scene().gray.colorMap(Colormap.Inferno).close()
```

| Honest (perceptually uniform) | Misleading |
|---|---|
| `Viridis`, `Magma`, `Inferno`, `Plasma`, `Turbo` | `Jet` (rainbow) |

## Blending two images

`addWeighted` is the weighted sum of two images — `self * alpha + other * beta + gamma` — with `other`
borrowed, exactly like the receiver. The high-level `blend(other, weight)` is the convex-combination
case:

```scala mdoc:silent
val a = bgr()
val b = Mat(120, 160, CvType.CV_8UC3, CvScalar(10, 60, 10))
val blended: Either[CvError, Array[Byte]] =
  a.addWeighted(0.7, b, 0.3).use(Images.encode(_, ".png"))
a.release(); b.release()
```

`absdiff` is the difference twin — `|a - b|` — the basis of frame-difference motion detection:

```scala mdoc:silent
val f1 = bgr()
val f2 = Mat(120, 160, CvType.CV_8UC3, CvScalar(50, 50, 50))
val diff: Either[CvError, Array[Byte]] =
  f1.absdiff(f2).use(Images.encode(_, ".png"))
f1.release(); f2.release()
```

## Photo filters and restoration

The `Photo`-backed ops are the heavyweights — painterly stylisation, texture-preserving smoothing, and
content-aware repair. All want 8-bit 3-channel input:

```scala mdoc:silent
scene().stylize().close()          // painterly cartoon
scene().sketch().close()           // pencil sketch
scene().enhance().close()          // detail / local contrast boost
scene().edgePreserving().close()   // flatten texture, keep edges
```

`inpaint` repairs the region under a mask from its surroundings — a scratch, an object, a watermark. The
mask (`CV_8UC1`, non-zero = repair) is **borrowed**:

```scala mdoc:silent
val toFix = bgr()
val repairMask = Mat(120, 160, CvType.CV_8UC1, CvScalar(0))
Imgproc.line(repairMask, CvPoint(30, 30), CvPoint(120, 90), CvScalar(255), 3)
val repaired: Either[CvError, Array[Byte]] =
  toFix.inpaint(repairMask, radius = 3.0).use(Images.encode(_, ".png"))
repairMask.release() // borrowed — release yourself
toFix.release()
```

`seamlessCloneInto` pastes an object into a background via Poisson blending, so the seam disappears — the
compositing behind a good virtual background. `background` and `mask` are borrowed; the result is
`background`-sized:

```scala mdoc:compile-only
// foreground.seamlessCloneInto(background, mask, center) — result is background-sized
scene().seamlessCloneInto(scene(), scene().gray, Point(80, 60))
```

### Named filter presets

`Filter` bundles common recipes so you can apply one by name with `image.filter(Filter.vintage)`. Each is
just a chain of the verbs above:

| `Filter` | Roughly |
|---|---|
| `noir` | grey + contrast + gamma |
| `vintage` | sepia + muted + gamma |
| `vivid` / `dramatic` | boosted saturation + detail |
| `warm` / `cool` | temperature shift |
| `cartoon` / `sketch` | stylise / pencil |
| `heatmap` | grey → `Inferno` colormap |

```scala mdoc:silent
scene().filter(Filter.vintage).close()
scene().filter(Filter.noir).close()
```

Filters compose with `andThen`:

```scala mdoc:silent
scene().filter(Filter.grayscale.andThen(Filter.sharpen)).close()
```

## Chaining without leaks

The single most important thing about the mid-level ops: because each returns its own owned
`Managed[Mat]`, writing a chain naively strands every intermediate. Two combinators make the
intermediates free themselves.

`pipe` is the two-stage form — it feeds the intermediate to the next stage and releases it once that
stage has produced its own output, so it can neither leak nor be used after the chain moves on
(touching it afterwards throws, rather than reading freed memory):

```scala mdoc:silent
val srcPipe = bgr()
val cannyBytes: Either[CvError, Array[Byte]] =
  srcPipe.gaussianBlur(Size(5, 5), 1.5)
     .pipe(_.canny(50, 150))
     .use(Images.encode(_, ".png"))
srcPipe.release()
```

`Mats.chain` is the n-stage form — a list of stages reads better than nested `pipe`s, and it releases
each intermediate as soon as the next stage consumes it (even if a stage throws). The source is
borrowed and never released; it belongs to whoever created it:

```scala mdoc:silent
val frame = bgr()
val result: Either[CvError, Array[Byte]] =
  Mats.chain(frame)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.gaussianBlur(Size(5, 5), 1.5),
    _.canny(50, 150)
  ).use(Images.encode(_, ".png"))
frame.release()
```

`use` (or `Managed.use`) is the terminal when the last stage yields something other than a `Mat` — a
count, a `Seq`, an encoded byte array — with the same release guarantee.

The high-level chain gives you the identical property as move semantics rather than a combinator: each
transform consumes the `Image` it was called on, so a pipeline holds exactly one live `Mat` at a time
and never a pile of intermediates:

```scala mdoc:silent
scene().gray.equalizeHist.canny(80, 160).close()
```

That is the same guarantee as `Mats.chain`, surfaced as a type.

:::danger[Move semantics: no reuse]
An `Image` transform *consumes* its receiver. Reusing a consumed handle throws `IllegalStateException` —
it does not read freed memory. To branch a pipeline, take a `.copy` first.
:::

```scala mdoc:crash
val once = scene()
once.gray.close()  // `once` is now spent
once.blur(2)       // throws IllegalStateException — use-after-move
```

## Next

- [Image API](/image-api) — the full high-level surface and the move-semantics contract in depth.
- [Colour masking](/color-masking) — HSV segmentation end to end, built on `inRange` + morphology.
- [Cookbook](/cookbook) — worked, copy-pasteable recipes that combine these operations.
