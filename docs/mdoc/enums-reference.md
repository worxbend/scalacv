# Enums & constants reference

scalacv replaces OpenCV's raw `int` constants with **typed enums** — so `image.convert(ColorConversion.BgrToGray)` instead of `Imgproc.cvtColor(..., 6)`, checked by the compiler. This page lists the values you can pass. Every case carries the OpenCV constant underneath (its `cvValue`), so nothing is lost — you just can't pass the wrong one by accident.

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*
```

```scala mdoc
// Each typed case maps to the OpenCV int — visible, but you never write the int yourself.
ColorConversion.BgrToGray.cvValue
```

## Colour & conversion

**`ColorConversion`** — for `convert` / `cvtColor`:
`BgrToGray`, `GrayToBgr`, `BgrToRgb`, `RgbToBgr`, `BgrToHsv`, `HsvToBgr`, `BgrToLab`, `LabToBgr`, `BgrToBgra`, `BgraToBgr`.
(`gray` and `toHsv` are shortcuts for the common ones.)

**`Colormap`** — false-colour a grey image with `colorMap`:
`Autumn`, `Bone`, `Jet`, `Ocean`, `Hot`, `Magma`, `Inferno`, `Plasma`, `Viridis`, `Turbo`.
(Perceptually-uniform picks — `Viridis`, `Inferno`, `Magma`, `Plasma`, `Turbo` — are the honest choices for data.)

## Resizing & borders

**`Interpolation`** — for `resize` / `scale`: `Nearest`, `Linear` (default), `Cubic`, `Area` (best for downscaling), `Lanczos4`.

**`BorderType`** — how pixels *beyond* the edge are invented, since any operation that reads a neighbourhood needs values out there: `Constant` (fill with one colour), `Replicate` (repeat the edge pixel outward), `Reflect` (mirror, *including* the edge pixel), `Reflect101` (mirror, *excluding* it).

**The default depends on the operation** — there is no single default across the library:

| Operation | Its default border |
|---|---|
| `pad`, `border`, `rotate(degrees, scale)` | `Constant`, filled with `Scalar.Black` |
| the filters — `gaussianBlur`, `boxBlur`, `sobel`, `laplacian` | `Reflect101`, which is OpenCV's own filter-border convention |

The split is deliberate. Mirroring is right for a filter, because a constant black edge would bleed inward and darken the border of a blurred image; a constant colour is right for padding, because a visible margin is usually the whole point. The `Image`-level `blur` and `gaussianBlur` do not expose the parameter at all and take the `Reflect101` default; to choose a different one, drop to the mid-level call on a borrowed `Mat` (see [Working with the raw OpenCV API](/low-level)).

`BORDER_ISOLATED` is not offered at all. It is a modifier that only means anything for the region-of-interest calls scalacv does not expose, so it is left out rather than accepted and silently ignored.

`Wrap` (pixels taken from the opposite edge) is a fifth value, but it is accepted **only** by `pad` / `border` (OpenCV's `copyMakeBorder`) and `rotated` (`warpAffine`). The filters — `gaussianBlur`, `boxBlur`, `sobel`, `laplacian` — cannot honour it: OpenCV's filter engine asserts `columnBorderType != BORDER_WRAP` and aborts. Worse, it is inconsistent about *when*: `gaussianBlur` on an 8-bit image quietly ignores the mode and only fails once the same call meets a 32-bit float image. Do not pass `Wrap` to a filter.

**`Flip`** — for `flip`: `Horizontal`, `Vertical`, `Both`.

**`Rotation`** — lossless quarter-turns for `rotate(rotation)`: exact pixels, no interpolation, nothing resampled. `Clockwise` is a quarter turn clockwise, `CounterClockwise` a quarter turn anti-clockwise, `Half` a 180° turn. These are the `Rotation` *enum* cases; the separate `rotate(degrees, scale)` overload measures **degrees counter-clockwise** and does resample, so `rotate(90.0)` turns the image the same way `rotate(Rotation.CounterClockwise)` does.

:::warning[Two rotation conventions live in this library]
`Image.rotate(degrees)` is **counter-clockwise** — the convention of OpenCV's `getRotationMatrix2D` / `warpAffine`, and the one the `Rotation` enum's `CounterClockwise` case matches.

`Picture.rotate(degrees, about)` in the `scalacv-graphs` module is **clockwise**. The scene graph works in screen coordinates, where y points *down* the image rather than up, and in that frame the same positive angle turns the other way.

Different layers, different conventions. Check which one you are holding before reaching for a minus sign.
:::

## Thresholding

**`Threshold`** — for `threshold`. A `Mode` plus an optional automatic method:

| | Values |
|---|---|
| `Threshold.Mode` | `Binary`, `BinaryInv`, `Truncate`, `ToZero`, `ToZeroInv` |
| `Threshold.Auto` | `Otsu`, `Triangle` (pick the cutoff for you) |
| shortcuts | `Threshold.Binary`, `Threshold.otsu(mode)`, `Threshold.triangle(mode)` |

**`AdaptiveMethod`** — for `adaptiveThreshold`: `Mean`, `Gaussian` (default).

## Morphology

**`MorphShape`** — the structuring element for `erode` / `dilate` / `morphology`: `Rect`, `Ellipse`, `Cross`.

**`MorphOp`** — the compound operation for `morphology`: `Open`, `Close`, `Gradient`, `TopHat`, `BlackHat`.

## Derivatives

**`OutputDepth`** — result depth for Sobel/Laplacian (mid-level): `SameAsSource`, `Unsigned8`, `Signed16`, `Float32`, `Float64`.
(Beware `SameAsSource` on an 8-bit image — it clips negative derivatives; use `Signed16` then `convertScaleAbs`.)

## Drawing

**`Font`** — the Hershey vector fonts: `Simplex`, `Plain`, `Duplex`, `Complex`, `Triplex`, `Script`. These are the only fonts there are: OpenCV cannot render a system font, and non-ASCII characters come out as `?`.

**`LineType`** — how a line or an outline is rasterised: `Connected4`, `Connected8` (default), `AntiAliased` (smooth edges, slower).

:::note[Neither reaches the `Image` draw verbs]
`Image.drawText(text, at, color, scale)` takes no `font`, and no `Image` draw verb — `drawRect`, `drawCircle`, `drawText`, `drawContours`, `drawRects` — takes a `lineType`. The chainable `Image` layer fixes the font at `Simplex` and the line type at `Connected8` on purpose: those are the knobs almost nobody varies mid-chain, and leaving them out keeps the chain readable.

You have two ways to reach them.

**Borrow the `Mat`** and call the mid-level `drawText`, which takes `font`, `thickness` and `lineType`. `img.mat` is a *borrow* — drawing mutates the pixels in place and does **not** consume the `Image` — so the same value comes back out and the chain carries on:

```scala mdoc:compile-only
def caption(img: Image, text: String): Image =
  img.mat.drawText(
    text,
    Point(10, 30),
    Scalar.White,
    font = Font.Duplex,
    thickness = Thickness.Stroke(2),
    lineType = LineType.AntiAliased
  )
  img // unchanged handle: the pixels moved, the ownership did not
```

**Or build a `Picture`** with the `scalacv-graphs` module, which carries both as style: `Picture.text("hi", Point(10, 30)).font(Font.Duplex)`, and `.smooth(true)` for anti-aliasing — already the default there.
:::

**`Thickness`** — `Thickness.Stroke(pixels)`, `Thickness.Filled` (solid shapes), `Thickness.Default` (1-px). Lines and text take `Thickness.Stroke` only — `Filled` doesn't type-check there.

## Contours

**`ContourRetrieval`** — which contours `findContours` returns: `External` (outermost only, default), `List`, `CComp`, `Tree` (with nesting).

**`ContourApproximation`** — how outlines are compressed: `None`, `Simple` (default — straight runs → endpoints), `Tc89L1`, `Tc89Kcos`.

## Reading images

**`ImreadFlags`** — how `Image.read` / `decode` interpret a file. Built from `ImreadColor` (`Grayscale`, `Color`, `ColorRgb`, `Unchanged`, `AnyDepth`) and `ImreadScale` (`Full`, `Half`, `Quarter`, `Eighth`). Common constants: `ImreadFlags.Color` (default), `ImreadFlags.Grayscale`, `ImreadFlags.Unchanged` (keep alpha/depth).

## Video

**`Codec`** — output codec for `Recorder` / `recordTo` / `Animation.record`: `Mjpg` (the default — always available, but only inside an `.avi`), `Mp4v` (MPEG-4 in an `.mp4`, needs FFmpeg or a platform encoder), `Avc1` (H.264, same caveat), `Xvid`. The codec and the file extension have to agree, or the writer will not open.

**`CaptureBackend`** — which videoio backend to request: `Any` (default), `FFmpeg`, `GStreamer`, `V4L2`, `AVFoundation`, `MediaFoundation`, `DirectShow`, `ImageSequence`, `BuiltinMjpeg`.

## Detection (vision module)

**`CascadeName`** — bundled Haar cascades for `detectHaar`:
`FrontalFaceAlt`, `FrontalFaceAlt2`, `FrontalFaceDefault`, `ProfileFace`, `Eye`, `EyeTreeEyeglasses`, `LeftEye2Splits`, `RightEye2Splits`, `Smile`, `FullBody`, `UpperBody`, `LowerBody`, `RussianPlateNumber`.

**`ArucoDictionary`** — marker families: `Dict4x4*`, `Dict5x5*`, `Dict6x6*`, `Dict7x7*` (by count), `ArucoOriginal`, `AprilTag16h5`, and more — see the [API docs](/api/core/scalacv/vision/ArucoDictionary.html).

**`TrackerKind`** — single-object trackers: `Csrt` (accurate), `Kcf` (fast), `Mil`.

:::tip[Discover values in your editor]
Every enum has `.values` — `CascadeName.values` lists them all — and autocomplete shows the cases as you type `ColorConversion.`. You rarely need this page once your IDE is set up.
:::

```scala mdoc
CascadeName.values.length // how many bundled cascades there are
```

## Next

- [Operations reference](/operations-reference) — the operations these values feed.
- [The API docs](/api/core/index.html) — every case with its documentation.
