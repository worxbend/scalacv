# Operations reference

Every high-level [`Image`](/image-api) operation on one page, grouped by what it does, with the ownership behaviour of each. This is the lookup table; the narrative pages ([Image processing](/image-processing), [Filters](/filters), [Drawing](/drawing), …) explain the *why* and the parameters, and the [API docs](/api/core/index.html) have every signature and default.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## How to read the ownership column

Every operation falls into one of four kinds — knowing which is how you reason about memory (see [Mat lifecycle](/mat-lifecycle)):

| Kind | Effect on the image it's called on | Returns |
|---|---|---|
| **query** | borrows — the image stays alive | plain data |
| **transform** | **consumes** — the image is spent | a new `Image` |
| **draw** | **consumes**, mutating in place (no copy) | a new `Image` |
| **terminal** | consumes and **releases** — except `managed`, which consumes but hands ownership on instead of freeing | a result / nothing |

A transform on a consumed image throws — take `.copy` first to branch. Extension verbs from `vision`/`graphs` (faces, AR, OCR, `Picture` drawing) require both the corresponding dependency and its import: `scalacv.vision.*` or `scalacv.graphs.*`, alongside `scalacv.*`. If `image.faces(...)` does not resolve, check both the build file and the vision import. See [Getting started](/getting-started).

## Queries — read without consuming

| Operation | Returns | Notes |
|---|---|---|
| `width` `height` `size` | `Int` / `Size` | pixel dimensions |
| `channels` | `Int` | 1 grey · 3 BGR · 4 BGRA |
| `isEmpty` | `Boolean` | true for a 0×0 image |
| `mat` | `Mat` | **borrow** the raw OpenCV Mat (don't release it) |
| `toBufferedImage` | `BufferedImage` | a copy, for AWT/notebooks |
| `contours(retrieval, approximation)` | `Seq[Contour]` | blob outlines — see [Contours](/contours) |

## Colour & tone — *transforms*

| Operation | Does |
|---|---|
| `gray` | BGR → single-channel greyscale |
| `convert(conversion)` | any colour-space conversion (see `ColorConversion`) |
| `toHsv` | BGR → HSV (the space to [threshold colour](/color-masking) in) |
| `invert` | `255 − v` per channel |
| `adjust(brightness, contrast)` | linear brightness/contrast |
| `normalize(min, max, depth)` | min-max contrast stretch; `depth` defaults to `OutputDepth.Unsigned8`, so a float or 16-bit source comes back displayable — pass `OutputDepth.SameAsSource` to keep its precision |
| `gamma(g)` | gamma correction (`<1` darkens, `>1` lifts) |
| `saturate(factor)` | saturation (`0` grey, `>1` vivid) |
| `temperature(shift)` | colour temperature (`>0` warm, `<0` cool) |
| `channel(index)` | extract one channel as a grey image |
| `colorMap(map)` | false-colour a grey image (heatmap) |
| `equalizeHist` | histogram equalisation (grey only) |
| `sepia` `posterize(levels)` | tone styles |

## Blur & smooth — *transforms*

| Operation | Does |
|---|---|
| `blur(radius)` | quick radius-based Gaussian; the kernel side is `2 * radius + 1`, so `blur(2)` is a 5×5. `radius = 0` is the identity **but still consumes the receiver** — it moves the Mat into a fresh `Image` rather than copying it. A negative radius throws `IllegalArgumentException` |
| `gaussianBlur(kernel, sigmaX, sigmaY)` | full-control Gaussian |
| `medianBlur(radius)` | median — kills salt-and-pepper noise, keeps edges. `radius` starts at `1` (a 3×3), so unlike `blur` there is no "do nothing" value: to make the step optional, branch around the call — `if denoise then img.medianBlur(1) else img` |
| `bilateralFilter(diameter, sigmaColor, sigmaSpace)` | edge-preserving smooth (slower) |
| `edgePreserving(strength, detail)` | flatten texture, keep edges |

## Edges & thresholds — *transforms*

| Operation | Does |
|---|---|
| `canny(threshold1, threshold2, apertureSize, l2Gradient)` | Canny edges → always `CV_8UC1`. `threshold1` is the weak (edge-linking) level and `threshold2` the strong one — both `Double`, so name them at the call site; `apertureSize` is the internal Sobel window and must be `3`, `5` or `7`; `l2Gradient = true` uses the exact gradient magnitude instead of the cheaper approximation. Parameter by parameter in [Image processing](/image-processing) |
| `threshold(value, maxValue, kind)` | fixed or automatic binarise. **Discards** the level OpenCV computed — with `Threshold.otsu()` or `Threshold.triangle()` that number *is* usually the point, so reach for the mid-level `mat.threshold(...)`, which returns `(Managed[Mat], ThresholdResult)` |
| `adaptiveThreshold(blockSize, c, method, inverse)` | per-neighbourhood threshold (uneven light) |

## Geometry — *transforms*

| Operation | Does |
|---|---|
| `resize(width, height)` | absolute resize in pixels. **No interpolation parameter** — it always uses `Interpolation.Linear` |
| `resizeTo(size, interpolation)` | absolute resize to a `Size`, with the interpolation of your choice |
| `scale(factor, interpolation)` | scale both axes by a factor, with the interpolation of your choice |
| `crop(rect)` | crop to an **independent copy** (not a view) |
| `flip(how)` | mirror — see `Flip` |
| `rotate(rotation)` | lossless 90°/180° quarter-turn — see `Rotation` |
| `rotate(degrees, scale)` | arbitrary angle, measured **counter-clockwise**, canvas expanded to fit. `rotate(90.0)` equals `rotate(Rotation.CounterClockwise)` |
| `pad(size, …)` / `border(top, bottom, left, right, …)` | add a border |
| `undistort(intrinsics)` | remove lens distortion — see [Calibration](/calibration) |
| `deskew(maxAngle)` | straighten text skew (OCR prep) |

## Morphology — *transforms*

| Operation | Does |
|---|---|
| `erode(radius, shape)` | shrink bright regions |
| `dilate(radius, shape)` | grow bright regions |
| `morphology(op, radius, shape)` | open/close/gradient/top-hat/black-hat (see `MorphOp`) |

## Stylise — *transforms*

| Operation | Does |
|---|---|
| `sharpen(amount)` | unsharp-mask sharpen |
| `stylize` `sketch` `enhance` `emboss` | painterly / pencil / detail / emboss looks |
| `filter(f)` | apply a named composable [`Filter`](/filters) (`Filter.vintage`, …) |

## Masking & compositing — *transforms* (masks are **borrowed**)

| Operation | Does |
|---|---|
| `inRange(lo, hi)` | binary mask of pixels within a colour range |
| `applyMask(mask)` | keep pixels where `mask` is non-zero |
| `blend(other, weight)` | convex mix: `this * weight + other * (1 - weight)`. `weight` is **this image's** share, not `other`'s — it ranges over `[0, 1]` and defaults to `0.5`; anything outside that range throws `IllegalArgumentException`. `other` is borrowed and must match this image in size and type |
| `inpaint(mask, radius)` | fill the masked region from its surroundings |
| `seamlessCloneInto(background, mask, center)` | Poisson clone into a background |
| `blurBackground(mask, …)` / `replaceBackground(mask, bg, …)` | [virtual background](/conferencing) |

:::note[Masks and other-image args are borrowed]
`applyMask`, `inpaint`, `blend`, `blurBackground`, … **consume the receiver** but **borrow** the mask/other image — you close those yourself.
:::

## Drawing — *draw* (mutate in place, consume the receiver)

| Operation | Draws | From |
|---|---|---|
| `drawRect(rect, color, thickness)` | a rectangle (filled with `Thickness.Filled`) | `scalacv` |
| `drawRects(rects, …)` | many rectangles in one pass | `scalacv` |
| `drawCircle(center, radius, …)` | a circle | `scalacv` |
| `drawText(text, at, color, scale)` | text (baseline-anchored — see [Drawing](/drawing)) | `scalacv` |
| `drawContours(contours, …)` | contour outlines / filled masks | `scalacv` |
| `draw(picture)` | a [`Picture`](/graphics) scene graph | `scalacv-graphs` |
| `markFaces(faces, color)` | a box per face and a dot per landmark | `scalacv-vision` |
| `drawSkeleton(pose, minScore, …)` | a line per bone, a dot per confident keypoint — see [Pose estimation](/pose-estimation) | `scalacv-vision` |
| `drawTracks(tracks, color)` | a box and a `#id` label per [tracked object](/tracking) | `scalacv-vision` |
| `drawMarkerAxes(intrinsics, markerLength, …)` | a 3-D X/Y/Z frame on every ArUco marker | `scalacv-vision` |
| `drawMarkerCube(intrinsics, markerLength, …)` | a wireframe cube standing on every marker — see [Marker AR](/marker-ar) | `scalacv-vision` |

## Detection & analysis — extension *queries* (return plain data)

| Operation | Returns | Page |
|---|---|---|
| `faces(detector)` | `Seq[Face]` (YuNet) | [Face recognition](/face-recognition) |
| `detectHaar(classifier, …)` | `Seq[Rect]` (Haar cascade) | [Object detection](/object-detection) |
| `qrCodes` / `arucoMarkers(dict, …)` | decoded codes / markers | [Object detection](/object-detection) · [Marker AR](/marker-ar) |
| `estimatePose(net, inputSize, layout, …)` | a `Pose` — **human** keypoints | [Pose estimation](/pose-estimation) |
| `arMarkers(intrinsics, markerLength, dictionary)` | `Seq[MarkerPose]` — markers with 3-D pose | [Marker AR](/marker-ar) |
| `segment(net, …)` | a person mask | [Conferencing](/conferencing) |
| `forOcr(denoise, blockSize, c)` | an OCR-prepped `Image` — **a transform**, not a query: it consumes the receiver | [OCR](/ocr) |

Two symbols in this area are *not* methods on `Image`, and looking for them there is a common wrong turn:

- **Recognising text** is `Ocr.read(image, engine)`, an object method that borrows the image. `recognize`
  is the one method **you implement** on the `OcrEngine` trait, because scalacv does the OpenCV
  preparation and leaves the recognition engine for you to supply.
- **Marker pose from a marker you have already detected** is `Ar.estimatePose(marker, markerLength, intrinsics)`,
  also an object method. The `estimatePose` in the table above is a different operation entirely — human
  body keypoints from a neural network.

## Terminals & lifecycle

| Operation | Does |
|---|---|
| `write(path)` | encode to a file, then **release** → `Either[CvError, Unit]` |
| `bytes(format)` | encode to in-memory bytes, then **release** → `Either[CvError, Array[Byte]]` |
| `close()` | release now (idempotent) |
| `copy` | **query** — an independent deep copy (to branch a chain) |
| `managed` | hand the underlying `Managed[Mat]` over → `Managed[Mat]`. Consumes the `Image` but does **not** free the Mat: ownership moves to you, so `use` it, `release()` it, or hand it to `Image.wrap` |

## Constructors

| Operation | Makes |
|---|---|
| `Image.blank(width, height, color, channels)` | a filled canvas |
| `Image.read(path, flags)` | read a file → `Either[CvError, Image]` |
| `Image.decode(bytes, flags)` | decode in-memory bytes → `Either` |
| `Image.fromBufferedImage(bi)` | from AWT |
| `Image.wrap(managed)` | adopt a `Managed[Mat]` |
| `Image.reading(path, flags)(use)` | read **and** scope-close — the safest entry point. `flags` is the same `ImreadFlags` `read` takes, so a scoped greyscale or reduced-size decode is one argument away |

A quick taste — a chain drawn from several categories at once:

```scala mdoc:silent
val out: Either[CvError, Array[Byte]] =
  Image.blank(160, 120, Scalar.White)   // constructor
    .drawCircle(Point(80, 60), 30, Scalar.Black, Thickness.Filled) // draw
    .gray                                 // colour
    .blur(2)                              // smooth
    .threshold(128)                       // edges & thresholds
    .bytes(".png")                        // terminal
```

## Next

- [The Image API](/image-api) — the narrative walkthrough.
- [Image processing](/image-processing) — the operation families in depth.
- [Working with the raw OpenCV API](/low-level) — the mid-level `Mat` twins of these.
