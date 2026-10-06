---
title: Cookbook
description: Seventy task-first recipes — clean a mask, count shapes, write a clip, track an object, plot a chart — each one compiled against the real library, each one runnable with no files to download.
---

# Cookbook

Task-first recipes you can copy, paste, and adapt. If you know *what* you want to do ("blur the background",
"count the shapes", "raise an alarm when something moves") but not yet *which* method does it, start here and
follow the cross-links into the reference pages for the details.

Every snippet on this page is compiled by **mdoc** against the real library, so it cannot drift out of date:
if a recipe here stopped compiling, the docs build would fail. Almost every recipe also *draws its own input*,
so you can run it with nothing on disk — the repository ships no image or video fixtures, and
[Sample inputs](/sample-inputs) explains the technique. The recipes lead with the high-level
[`Image`](/image-api) API — the friendly, chainable layer most code should use — and the
[lower-level recipes](#lower-level-recipes) at the end show the same kind of work on a raw `Mat`, for when you
want the extra control the mid-level [ownership contract](/image-processing) gives you.

scalacv is published as four artifacts, and a recipe that reaches outside the core one says so under its
heading — **Needs `scalacv-vision`** or **Needs `scalacv-graphs`**. Those symbols do not exist on your
classpath until the dependency line is in your build file; `import scalacv.*` cannot conjure a module you have
not added. [Getting started](/getting-started) has every line.

:::note[New to scalacv?]
Read [Getting started](/getting-started) first for install and your very first program, then skim
[The Image API](/image-api) for how the chain works. This page assumes you have `OpenCv.load()` behind you.
:::

## Recipe index

Jump to an area: [Everyday image work](#everyday-image-work) ·
[Masks, colour and compositing](#masks-colour-and-compositing) ·
[Finding and measuring things](#finding-and-measuring-things) ·
[Annotating and drawing](#annotating-and-drawing) · [Video and live capture](#video-and-live-capture) ·
[Detection, tracking and depth](#detection-tracking-and-depth) ·
[Documents, models and interop](#documents-models-and-interop) · [Lower-level recipes](#lower-level-recipes)

The **Needs** column is the artifact the recipe requires beyond `scalacv` itself; `core` means the core
artifact alone is enough.

| I want to…                                | Recipe                                                                                                | Needs  | Reference                                     |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------ | --------------------------------------------- |
| Find edges in a photo                     | [Edge-detect a photo](#edge-detect-a-photo)                                                             | core   | [Image processing](/image-processing)          |
| Shrink an image                           | [Make a thumbnail](#make-a-thumbnail)                                                                   | core   | [Transforms](/transforms)                      |
| Rotate / mirror / crop                    | [Rotate, flip, crop](#rotate-flip-crop)                                                                 | core   | [Transforms](/transforms)                      |
| Fit a picture into a fixed box            | [Letterbox without stretching](#letterbox-to-a-fixed-size-without-stretching)                           | core   | [Transforms](/transforms)                      |
| Shrink without losing thin detail         | [Downscale a frame properly](#downscale-a-frame-properly)                                               | core   | [Transforms](/transforms)                      |
| Look at one colour channel                | [Split into blue, green and red planes](#split-an-image-into-its-blue-green-and-red-planes)             | core   | [Enums & constants](/enums-reference)          |
| Use a transparent PNG                     | [Read a PNG with transparency](#read-a-png-with-transparency-and-composite-it)                          | core   | [Image I/O](/image-io)                         |
| Apply a photo "look"                      | [Apply a named filter](#apply-a-named-filter)                                                           | core   | [Filters](/filters)                            |
| Bottle up your own look                   | [Define and reuse your own filter](#define-and-reuse-your-own-filter)                                   | core   | [Filters](/filters)                            |
| Adjust brightness / contrast / sharpness  | [Tune tone and sharpness](#tune-tone-and-sharpness)                                                     | core   | [Filters](/filters)                            |
| Fix a dark or washed-out photo            | [Rescue a backlit photo](#rescue-a-backlit-or-washed-out-photo)                                         | core   | [Filters](/filters)                            |
| Read, process and write one file          | [Read → process → write, scoped](#read-process-write-scoped)                                            | core   | [The Image API](/image-api)                    |
| Keep only one colour                      | [Segment by colour](#segment-by-colour-hsv-keying)                                                      | core   | [Colour masking](/color-masking)               |
| Clean a noisy mask                        | [Clean up a speckled colour mask](#clean-up-a-speckled-colour-mask)                                     | core   | [Colour masking](/color-masking)               |
| Kill speckle noise                        | [Remove salt-and-pepper speckle](#remove-salt-and-pepper-speckle-from-a-scan)                           | core   | [Image processing](/image-processing)          |
| Binarise a page of text                   | [Binarise dark text on a light page](#binarise-dark-text-on-a-light-page)                               | core   | [Image processing](/image-processing)          |
| Choose a threshold automatically          | [Let OpenCV pick the threshold](#let-opencv-pick-the-threshold-and-find-out-what-it-picked)             | core   | [Image processing](/image-processing)          |
| Turn shapes back into a mask              | [Turn detections back into a mask](#turn-detections-back-into-a-mask)                                   | core   | [Contours](/contours)                          |
| Blend / composite two images              | [Blend two images](#blend-two-images)                                                                   | core   | [Image processing](/image-processing)          |
| Remove an object                          | [Erase an object](#erase-an-object-inpaint)                                                             | core   | [Image processing](/image-processing)          |
| Paste a patch invisibly                   | [Seamlessly paste a patch](#seamlessly-paste-a-patch)                                                   | core   | [Conferencing](/conferencing)                  |
| Blur behind a person                      | [Blur the background](#blur-the-background-behind-a-person)                                             | vision | [Conferencing](/conferencing)                  |
| False-colour data as a heatmap            | [Colour a heatmap](#colour-a-heatmap)                                                                   | core   | [Image processing](/image-processing)          |
| Count / measure shapes                    | [Count the shapes](#count-the-shapes-in-a-frame)                                                        | core   | [Contours](/contours)                          |
| Split blobs that touch                    | [Separate two touching blobs](#separate-two-touching-blobs)                                             | core   | [Contours](/contours)                          |
| Find the biggest blob                     | [Find the biggest blob](#find-the-biggest-blob)                                                         | core   | [Contours](/contours)                          |
| Throw away bad detections                 | [Reject detections by size and shape](#reject-detections-by-size-and-aspect-ratio)                      | core   | [Contours](/contours)                          |
| Name the shape you found                  | [Triangle, square or circle?](#tell-a-triangle-from-a-square-from-a-circle)                             | core   | [Contours](/contours)                          |
| Wrap a dented shape                       | [Draw the convex hull](#draw-the-convex-hull-around-a-blob)                                             | core   | [Contours](/contours)                          |
| Number the objects on screen              | [Label each object at its centre](#label-each-object-at-its-centre)                                     | core   | [Contours](/contours)                          |
| Find straight lines                       | [Find straight lines](#find-straight-lines-hough)                                                       | core   | [Hough](/hough)                                |
| Measure the gradient, not just the edge   | [Keep both sides of every edge with Sobel](#keep-both-sides-of-every-edge-with-sobel)                   | core   | [Image processing](/image-processing)          |
| Label an image                            | [Draw a text badge](#draw-a-text-badge)                                                                 | core   | [Drawing](/drawing)                            |
| Annotate a frame and encode it            | [Annotate a scene and encode it](#annotate-a-scene-and-encode-it)                                       | core   | [Drawing](/drawing)                            |
| Colour each detection differently         | [Give every detection its own colour](#give-every-detection-its-own-colour)                             | graphs | [2D graphics](/graphics)                       |
| Draw a diagram from shapes and labels     | [Draw a labelled diagram](#draw-a-labelled-diagram)                                                     | graphs | [2D graphics](/graphics)                       |
| Plot data as an image                     | [Plot a bar chart straight to a PNG](#plot-a-bar-chart-straight-to-a-png)                               | graphs | [2D graphics](/graphics)                       |
| Make a short animation                    | [Make an animated GIF](#make-an-animated-gif)                                                           | graphs | [2D graphics](/graphics)                       |
| Make a test clip with no footage          | [Write a video from frames you drew](#write-a-video-from-frames-you-drew)                               | core   | [Video](/video)                                |
| Ask a clip what it is                     | [Check a clip before you process it](#check-a-clip-before-you-process-it)                               | core   | [Video](/video)                                |
| Process a whole video                     | [Re-encode a video](#re-encode-a-video)                                                                 | core   | [Video](/video)                                |
| Grab a webcam frame                       | [Snapshot the webcam](#snapshot-the-webcam)                                                             | core   | [Video](/video)                                |
| Clean up a noisy still                    | [Average several frames](#average-several-frames-to-kill-sensor-noise)                                  | core   | [Video](/video)                                |
| Stop a dead stream hanging                | [Open an RTSP stream that might hang](#open-an-rtsp-stream-that-might-hang)                             | core   | [Video](/video)                                |
| Survive a missing codec                   | [Fall back through codecs](#fall-back-through-codecs-until-one-opens)                                   | core   | [Degradation](/degradation-and-error-budgets)  |
| Stop analysis falling behind              | [Analyse only the newest frame](#analyse-only-the-newest-frame)                                         | core   | [Streaming](/streaming-and-backpressure)       |
| Alarm on motion                           | [Raise a motion alarm](#raise-a-motion-alarm-on-a-video)                                                | vision | [Motion detection](/motion-detection)          |
| Read a QR code                            | [Read a QR code](#read-a-qr-code)                                                                       | vision | [Object detection](/object-detection)          |
| Find faces                                | [Find faces](#find-faces)                                                                               | vision | [Object detection](/object-detection)          |
| Make and read a printable tag             | [Generate and detect an ArUco marker](#generate-and-detect-an-aruco-marker)                             | vision | [Markers & AR](/marker-ar)                     |
| Find a control on a screenshot            | [Find a button on a screenshot](#find-a-button-on-a-screenshot)                                         | vision | [Screen analysis](/screen-analysis)            |
| Find every copy of an icon                | [Find every copy of an icon](#find-every-copy-of-an-icon)                                               | vision | [Screen analysis](/screen-analysis)            |
| See what changed on screen                | [Spot what changed](#spot-what-changed-between-two-screenshots)                                         | vision | [Screen analysis](/screen-analysis)            |
| Follow one object                         | [Follow one object without re-detecting it](#follow-one-object-without-re-detecting-it)                 | vision | [Tracking](/tracking)                          |
| Keep an identity across frames            | [Give every detection a stable ID](#give-every-detection-a-stable-id)                                   | vision | [Tracking](/tracking)                          |
| Smooth a jittery point                    | [Smooth a jittery tracked point](#smooth-a-jittery-tracked-point)                                       | vision | [Tracking](/tracking)                          |
| Measure how the scene shifted             | [Work out which way the scene moved](#work-out-which-way-the-scene-moved)                               | vision | [Navigation](/navigation)                      |
| Turn feature matches into motion          | [Turn ORB matches into camera motion](#turn-orb-matches-into-camera-motion)                             | vision | [Navigation](/navigation)                      |
| Estimate depth from two cameras           | [Stereo disparity end to end](#stereo-disparity-end-to-end)                                             | vision | [Navigation](/navigation)                      |
| Remember where obstacles were             | [Build an occupancy map](#build-an-occupancy-map-from-obstacles)                                        | vision | [Navigation](/navigation)                      |
| Clean a scan for OCR                      | [Clean a scanned page](#straighten-and-clean-a-scanned-page-ocr-prep)                                   | core   | [OCR](/ocr)                                    |
| Download a model safely                   | [Fetch and verify your own model](#fetch-and-verify-your-own-model)                                     | core   | [Models](/models)                              |
| Go to/from `BufferedImage`                | [Swing / AWT interop](#swing--awt-interop)                                                              | core   | [Image I/O](/image-io)                         |
| Detect edges on a raw `Mat`               | [Detect edges](#detect-edges)                                                                           | core   | [Low-level](/low-level)                        |
| Run several `Mat` stages in a row         | [Chain many stages](#chain-many-stages)                                                                 | core   | [Low-level](/low-level)                        |
| Add an operation scalacv does not wrap    | [Write your own pipe-able operator](#write-your-own-pipe-able-operator)                                 | core   | [Low-level](/low-level)                        |
| Read a file without exceptions            | [Read an image safely](#read-an-image-safely)                                                           | core   | [Image I/O](/image-io)                         |
| Get the number Otsu chose                 | [Let Otsu pick the threshold](#let-otsu-pick-the-threshold)                                             | core   | [Image processing](/image-processing)          |
| Decode a QR code from a `Mat`             | [Decode a QR code](#decode-a-qr-code)                                                                   | vision | [Object detection](/object-detection)          |
| Detect faces with a Haar cascade          | [Find faces with a Haar cascade](#find-faces-with-a-haar-cascade)                                       | vision | [Object detection](/object-detection)          |

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*
OpenCv.load()
```

## How the chains work

Everything below leans on one rule worth internalising before you copy a recipe: an [`Image`](/image-api) has
**move semantics**. A transform (`gray`, `blur`, `crop`, a `draw*`, …) *consumes* the image it was called on
and returns a new one, and a terminal (`write`, `bytes`, `close`) consumes it for good. So a chain is
leak-free — exactly one live Mat flows through it — but you can never use one `Image` twice:

```scala mdoc:crash
val once = Image.blank(10, 10)
val edges = once.gray         // `once` is now spent
val blurred = once.blur(1)    // reusing it throws IllegalStateException
```

To branch — to feed the *same* source into two pipelines — take a [`copy`](/image-api) first. That is the
single most common shape in this cookbook:

```scala mdoc:silent
val source = Image.blank(120, 80, Scalar(80, 120, 160))
val forEdges = source.copy.gray.canny(80, 160) // works on a copy
val forThumb = source.scale(0.5)               // consumes the original
forEdges.close()
forThumb.close()
```

:::tip
Running under `-Dscalacv.trackOwnership=true` makes a use-after-move error point at the *consuming* call, not
just the reuse. See [Mat lifecycle](/mat-lifecycle) for the full ownership story.
:::

## Everyday image work

Reading, resizing, straightening and re-toning a picture — the operations you reach for before any analysis
starts.

### Edge-detect a photo

The canonical pipeline: greyscale, a light blur to quiet the noise, then [Canny](/image-processing).

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

`canny`'s two thresholds are both `Double` and silently swappable — name them (`canny(threshold1 = 80,
threshold2 = 160)`) when the values are not obviously ordered.

### Make a thumbnail

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.scale(0.25).write("thumb.jpg"))
```

For a *large* source, decoding at reduced size is cheaper than a full read followed by a resize — the codec
skips the detail it is about to throw away. Ask [`imread`](/image-io) for it with an [`ImreadFlags`](/image-io):

```scala mdoc:compile-only
Image.read("huge.jpg", ImreadFlags(ImreadColor.Color, ImreadScale.Quarter)).flatMap(_.write("thumb.jpg"))
```

### Rotate, flip, crop

Quarter-turns are lossless (no interpolation); arbitrary angles expand the canvas so no corner is clipped.
See [Transforms](/transforms) for the full set.

```scala mdoc:silent
val portrait = Image.blank(60, 100, Scalar(30, 30, 30)).drawRect(Rect(10, 10, 40, 30), Scalar.Green, Thickness.Filled)
val landscape = portrait.rotate(Rotation.Clockwise) // a 60x100 image becomes 100x60
val landscapeWidth = landscape.width
landscape.close()
```

```scala mdoc
landscapeWidth
```

`crop` returns an independent copy (not an aliasing view), and rejects a rectangle that does not fit:

```scala mdoc:silent
val scene = Image.blank(200, 200, Scalar.Black).drawCircle(Point(100, 100), 40, Scalar.Red, Thickness.Filled)
val centre = scene.crop(Rect(60, 60, 80, 80))
val centreWidth = centre.width
centre.close()
```

```scala mdoc
centreWidth
```

Mirroring is named by the visible effect, not OpenCV's axis code:

```scala mdoc:silent
val mirrored: Either[CvError, Array[Byte]] =
  Image.blank(64, 48, Scalar.Blue).drawRect(Rect(4, 4, 20, 10), Scalar.White).flip(Flip.Horizontal).bytes(".png")
```

### Letterbox to a fixed size without stretching

Models, thumbnail grids and video encoders all want a fixed frame size, and `resize` to that size squashes
anything whose aspect ratio does not already match. Letterboxing scales by the *smaller* of the two ratios so
the whole picture fits, then pads the leftover with a solid colour.

```scala mdoc:silent
def ciLetterbox(img: Image, targetW: Int, targetH: Int): Image =
  val factor = math.min(targetW.toDouble / img.width, targetH.toDouble / img.height)
  val w = math.max(1, math.round(img.width * factor).toInt)
  val h = math.max(1, math.round(img.height * factor).toInt)
  val left = (targetW - w) / 2
  val top = (targetH - h) / 2
  img
    .resizeTo(Size(w.toDouble, h.toDouble), Interpolation.Area)
    .border(top, targetH - h - top, left, targetW - w - left, BorderType.Constant, Scalar.Black)

val ciWide =
  Image.blank(300, 120, Scalar(40, 90, 160)).drawCircle(Point(150, 60), 40, Scalar.White, Thickness.Filled)
val ciBoxed = ciLetterbox(ciWide, 200, 200)
val ciBoxedSize = (ciBoxed.width, ciBoxed.height)
ciBoxed.close()
```

```scala mdoc
ciBoxedSize
```

`Interpolation.Area` is the right choice going *down*: it averages every source pixel that falls inside the
destination pixel, where `Linear` and `Cubic` sample a handful of them and alias the rest away. Going *up*,
`Area` degenerates to nearest-neighbour, so use `Linear` or `Cubic` there.

:::warning[Compute the far side by subtraction, and read the size before you transform]
`(targetH - h) / 2` on both the top and the bottom loses a row whenever the difference is odd — a 200-pixel
target holding an 81-pixel image would get 59 + 81 + 59 = 199 rows, and every downstream size assertion would
fail by one. Deriving the second pad as `targetH - h - top` makes the total exact by construction.

The other trap is ownership: `resizeTo` **consumes** `img`, so `img.width` must be read *before* the resize
runs. In the code above `factor`, `w` and `h` are all computed first, which is why it works. Reading
`img.width` after the chain would throw `IllegalStateException`.
:::

### Downscale a frame properly

Shrinking an image throws pixels away, and *how* they are thrown away is a choice. The default,
`Interpolation.Linear`, **samples** — it reads a few source pixels near each output position and mixes them.
`Interpolation.Area` **averages** every source pixel that falls inside the output pixel's footprint, so
nothing is silently ignored. For downscaling, `Area` is the one you want.

The difference shows up on fine detail. Here are thirty one-pixel-wide vertical lines, shrunk from 320×240
to 100×75 both ways, counted by [contours](/contours):

```scala mdoc:silent
val vvLines: Image =
  (0 until 30).foldLeft(Image.blank(320, 240, Scalar.Black, channels = 1)) { (img, i) =>
    img.drawRect(Rect(4 + i * 8, 0, 1, 240), Scalar(255), Thickness.Filled)
  }

val vvAreaShrunk = vvLines.copy.resizeTo(Size(100, 75), Interpolation.Area).threshold(1)
val vvNearestShrunk = vvLines.copy.resizeTo(Size(100, 75), Interpolation.Nearest).threshold(1)

val vvAreaLines = vvAreaShrunk.contours().size
val vvNearestLines = vvNearestShrunk.contours().size

vvAreaShrunk.close()
vvNearestShrunk.close()
vvLines.close()
```

```scala mdoc
vvAreaLines
vvNearestLines
```

`Area` accounts for every line — each one survives as a dimmer grey column, because its single bright pixel
was averaged with its dark neighbours. `Nearest` reports only the lines its sampling grid happened to land
on; the rest are not dimmed, they are *gone*. That is **aliasing**: detail finer than the output grid does
not shrink, it disappears or reappears somewhere it never was. On a photograph it looks like shimmering
moiré patterns on brickwork, fabric and text.

:::warning[`Area` is for shrinking only]
Ask OpenCV to *enlarge* with `Interpolation.Area` and it behaves like `Nearest` — blocky, not smooth.
Going up, use `Linear` (fast) or `Cubic` / `Lanczos4` (slower, sharper). `scale` takes the same
`interpolation` argument as `resizeTo`, so `img.scale(0.25, Interpolation.Area)` is the thumbnail form.
:::

If the source is a large file on disk rather than a frame already in memory, decoding at reduced size is
cheaper still — see [Make a thumbnail](#make-a-thumbnail) for the `ImreadScale` version. The full
interpolation table is in [Enums & constants](/enums-reference).

### Split an image into its blue, green and red planes

Looking at one colour channel on its own is how you find out *why* a colour threshold is misbehaving, and
some tasks want a single plane outright — the red channel is often the cleanest place to find skin, and the
blue plane frequently carries the most sensor noise.

```scala mdoc:silent
val ciColour = Image.blank(40, 30, Scalar(200, 120, 60)) // BGR: blue 200, green 120, red 60
val ciBlue = ciColour.copy.channel(0)
val ciGreen = ciColour.copy.channel(1)
val ciRed = ciColour.copy.channel(2)

val ciPlaneValues = Seq(ciBlue, ciGreen, ciRed).map(_.mat.get(0, 0)(0))
Seq(ciColour, ciBlue, ciGreen, ciRed).foreach(_.close())
```

```scala mdoc
ciPlaneValues
```

Each plane comes back as its own **single-channel** image, so it is ready for `threshold`, `contours` or
`colorMap` without any further conversion.

:::warning[OpenCV is BGR, so channel 2 is red — and each `channel` call consumes the image]
`channel(0)` is **blue**, not red: OpenCV's memory order is blue, green, red, which is also why
`Scalar.Red` is `Scalar(0, 0, 255)`. Getting this backwards produces a mask that looks plausible and keeps
the wrong objects.

`channel` is an ordinary transform, so it *consumes* the image it is called on. Pulling out all three planes
therefore needs a `.copy` for each of the first two — three extractions from one source means three copies,
or two copies plus the original as above. On a four-channel BGRA image, channel 3 is the alpha; see
[Read a PNG with transparency](#read-a-png-with-transparency-and-composite-it).
:::

### Read a PNG with transparency and composite it

A logo or a sprite arrives as a PNG with an alpha channel, and the default read throws that alpha away. Ask
for `ImreadFlags.Unchanged`, split the alpha off as a mask, convert the colour down to three channels, and
you can composite it over anything.

The example makes its own transparent PNG in memory so no file is needed; with a real asset the first line
is `Image.read("logo.png", ImreadFlags.Unchanged)`:

```scala mdoc:compile-only
Image.read("logo.png", ImreadFlags.Unchanged) // Either[CvError, Image], 4 channels
```

```scala mdoc:silent
// A four-channel canvas: fully transparent, with an opaque green disc drawn into it.
val ciLogoPng: Either[CvError, Array[Byte]] =
  Image
    .blank(60, 60, Scalar(0, 0, 0, 0), channels = 4)
    .drawCircle(Point(30, 30), 25, Scalar(60, 200, 60, 255), Thickness.Filled)
    .bytes(".png")

val ciLogo: Either[CvError, Image] = ciLogoPng.flatMap(Image.decode(_, ImreadFlags.Unchanged))
val ciLogoChannels: Int = ciLogo.map(_.channels).getOrElse(0)
```

```scala mdoc
ciLogoChannels
```

```scala mdoc:silent
val ciFlattened: Either[CvError, Array[Byte]] =
  ciLogo.flatMap { logo =>
    val alpha = logo.copy.channel(3) // channel 3 of BGRA is the alpha, already a CV_8UC1 mask
    val out = logo.convert(ColorConversion.BgraToBgr).applyMask(alpha).bytes(".png")
    alpha.close() // applyMask borrows the mask
    out
  }
```

`ciFlattened` is the logo's colour with every transparent pixel forced to black — the form you can hand to
`blend`, `seamlessCloneInto`, or a mid-level paste.

:::warning[The default read silently drops the alpha, and `Unchanged` cannot be combined with anything]
`Image.read(path)` uses `ImreadFlags.Color`, which converts to three channels and discards transparency
without telling you. Checking `channels == 4` after the read is the cheap guard.

`ImreadFlags.Unchanged` is OpenCV's `IMREAD_UNCHANGED`, whose value is `-1`; its bits swamp every other flag,
so it cannot carry a reduced-size decode or an orientation flag. scalacv rejects those combinations with an
`IllegalArgumentException` at construction rather than quietly decoding the wrong image. If you need a
smaller transparent PNG, decode it unchanged and `scale` afterwards.

A binary mask made from alpha is all-or-nothing: it keeps a pixel that is 1% opaque exactly as hard as one
that is 100% opaque, so anti-aliased edges come out jagged. For a soft composite, blend with the alpha as
*weights* rather than masking with it.
:::

### Apply a named filter

The built-in [filters](/filters) are composable `Image => Image` looks — apply one with `filter`, or compose
your own with `andThen`:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.filter(Filter.vintage).write("vintage.jpg"))
```

`Filter.all` is the whole catalogue, handy for a contact sheet or a picker:

```scala mdoc
Filter.all.map(_.name)
```

A running example with a filter built from pure tone ops (no photo needed):

```scala mdoc:silent
val warmed: Either[CvError, Array[Byte]] =
  Image.blank(80, 80, Scalar(120, 90, 60)).filter(Filter.warm.andThen(Filter.sharpen)).bytes(".png")
```

### Define and reuse your own filter

A [`Filter`](/filters) is a named `Image => Image` — nothing more. Once your house look is a `Filter` you can
pass it around, compose it, and drop it into the same picker as the built-ins.

```scala mdoc:silent
val ciMyLook: Filter = Filter("myLook")(_.gamma(1.2).saturate(1.3).sharpen(0.6))

val ciLooked: Either[CvError, Array[Byte]] =
  Image.blank(80, 80, Scalar(90, 110, 140)).filter(ciMyLook).bytes(".png")

val ciCombined: Filter = ciMyLook.andThen(Filter.vintage) // yours first, then the built-in
val ciPicker: Seq[Filter] = Filter.all :+ ciMyLook // a contact sheet including your own
```

```scala mdoc
(ciCombined.name, ciPicker.size)
```

`andThen` derives the composed name from its parts, so a filter picker built from `ciPicker.map(_.name)`
stays readable without you naming every combination by hand.

:::warning[A filter consumes the image, so a contact sheet needs one copy per filter]
`image.filter(f)` is the same move as any other transform: it spends the image. Running every filter in
`ciPicker` over one source therefore means `source.copy.filter(f)` per entry, with a single `source.close()`
at the end — reusing the spent original throws `IllegalStateException`.

Two smaller notes. `Filter.all` is an immutable `Seq`, so `:+` returns a new sequence rather than modifying
the catalogue; there is no registry to register with. And order matters inside a filter body:
`_.gamma(1.2).saturate(1.3)` is not the same picture as `_.saturate(1.3).gamma(1.2)`, because gamma is
non-linear and saturation is computed from the channel values it is handed.
:::

### Tune tone and sharpness

`adjust` does brightness and contrast in one step; `sharpen` is an unsharp mask; `saturate`, `gamma` and
`temperature` are the individual tone knobs the named filters are built from.

```scala mdoc:silent
val punchier: Either[CvError, Array[Byte]] =
  Image
    .blank(100, 100, Scalar(60, 90, 120))
    .adjust(brightness = 10, contrast = 1.2)
    .saturate(1.3)
    .sharpen(0.8)
    .bytes(".png")
```

### Rescue a backlit or washed-out photo

Three different tools, three different jobs. `equalizeHist` redistributes the histogram so the full 0–255
range is used — the strongest fix for a flat, low-contrast image, and the least controllable. `adjust` is a
straight brightness shift plus a contrast multiply. `gamma` bends the mid-tones while leaving black and white
where they are, which is the gentlest of the three.

```scala mdoc:silent
val ciFlat =
  Image
    .blank(120, 90, Scalar(150, 150, 150)) // everything crammed into 150..170: no contrast
    .drawCircle(Point(60, 45), 30, Scalar(170, 170, 170), Thickness.Filled)

val ciEqualised: Either[CvError, Array[Byte]] = ciFlat.copy.gray.equalizeHist.bytes(".png")
val ciBrightened: Either[CvError, Array[Byte]] =
  ciFlat.copy.adjust(brightness = 20, contrast = 1.3).bytes(".png")
val ciLifted: Either[CvError, Array[Byte]] = ciFlat.gamma(1.4).bytes(".png")
```

```scala mdoc
(ciEqualised.isRight, ciBrightened.isRight, ciLifted.isRight)
```

Reach for `gamma` first on a backlit subject: `gamma(1.4)` lifts the dark subject without blowing out the
bright window behind it. Reach for `equalizeHist` when the image is uniformly flat — hazy, foggy, or shot
through glass.

:::warning[`equalizeHist` is greyscale-only, and it throws away colour]
`equalizeHist` takes a `CV_8UC1` image, so `gray` has to come first — and that means the result is grey, with
the colour gone for good. Equalising a colour photo properly means equalising only its *luminance*, leaving
hue and saturation alone; running the operation on each BGR channel independently instead shifts the colours.

`adjust` saturates rather than wrapping (it is `convertScaleAbs` underneath), so pixels pushed past 255 clamp
to 255 and stay there. That is the safe behaviour, but it is one-way: detail that clips is gone, and lowering
the brightness afterwards will not bring it back. And be honest about the ceiling — none of these three
invents information that the sensor never recorded.
:::

### Read → process → write, scoped {#read-process-write-scoped}

`Image.reading` closes the source for you even if the body already consumed it (release is idempotent), and
folds any transform failure in the chain into the `Either`:

```scala mdoc:compile-only
Image.reading("photo.jpg")(_.gray.equalizeHist.threshold(128).write("mask.png"))
```

## Masks, colour and compositing

A **mask** is a single-channel image where non-zero means "this pixel counts". Building one, tidying it up,
and using it to keep, erase or blur part of a picture is most of practical computer vision.

### Segment by colour (HSV keying)

Threshold in HSV — where "greenish" is a hue *range*, not a fragile RGB box — then keep only those pixels.
See [Colour masking](/color-masking) for choosing the bounds.

```scala mdoc:silent
val photo =
  Image.blank(120, 90, Scalar(0, 0, 255)).drawCircle(Point(60, 45), 22, Scalar.Green, Thickness.Filled)
val hueMask = photo.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255)) // green hues
val greenOnly: Either[CvError, Array[Byte]] = photo.applyMask(hueMask).bytes(".png")
hueMask.close()
```

:::warning
`applyMask` **borrows** the mask — it does not consume it, so you must `.close()` the mask yourself. The
receiver (`photo`) *is* consumed. The same borrowing rule holds for `inpaint`, `blend`, `blurBackground` and
`seamlessCloneInto`.
:::

### Clean up a speckled colour mask

A mask straight out of `inRange` is never tidy: a few stray pixels match the colour by accident, and a
shadow or a highlight can cut a real object in two. Two morphological passes fix both problems — an
**opening** (erode, then dilate) deletes anything smaller than the brush, and a **closing** (dilate, then
erode) seals gaps narrower than the brush.

Here is a green disc with a two-pixel scratch through it, on a blue background, plus six one-pixel specks of
green noise:

```scala mdoc:silent
val ciSpeckled =
  Image
    .blank(160, 120, Scalar(200, 60, 60)) // BGR: a blue background
    .drawCircle(Point(70, 60), 30, Scalar.Green, Thickness.Filled)
    .drawRect(Rect(69, 25, 2, 70), Scalar(200, 60, 60), Thickness.Filled) // a 2 px scratch across the disc
    .drawCircle(Point(15, 15), 1, Scalar.Green, Thickness.Filled)
    .drawCircle(Point(120, 15), 1, Scalar.Green, Thickness.Filled)
    .drawCircle(Point(140, 20), 1, Scalar.Green, Thickness.Filled)
    .drawCircle(Point(20, 60), 1, Scalar.Green, Thickness.Filled)
    .drawCircle(Point(25, 100), 1, Scalar.Green, Thickness.Filled)
    .drawCircle(Point(150, 100), 1, Scalar.Green, Thickness.Filled)

val ciSpeckledMask = ciSpeckled.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255)) // green hues
val ciRawBlobs = ciSpeckledMask.contours().size

val ciOpened = ciSpeckledMask.morphology(MorphOp.Open, radius = 2) // specks smaller than the brush vanish
val ciOpenedBlobs = ciOpened.contours().size

val ciCleanMask = ciOpened.morphology(MorphOp.Close, radius = 3) // the scratch is bridged
val ciCleanBlobs = ciCleanMask.contours().size
ciCleanMask.close()
```

The three counts are the outline count after each stage — the noise goes first, then the scratch heals:

```scala mdoc
(ciRawBlobs, ciOpenedBlobs, ciCleanBlobs)
```

:::warning[Open first, then close — and `radius` is a radius]
Reverse the order and the closing welds every speck onto whatever is near it, so the opening can no longer
tell them apart. And `radius` is the *half*-width of the brush, not its side: `radius = 2` builds a 5×5
structuring element (`side = radius * 2 + 1`) and erases blobs up to about four pixels across. The floor is
`radius = 1`; `radius = 0` throws `IllegalArgumentException` rather than acting as a no-op. Pick the closing
radius from the widest gap you need to bridge, and the opening radius from the largest speck you want gone.
:::

See [Colour masking](/color-masking) for choosing the `inRange` bounds in the first place, and
[Enums & constants](/enums-reference) for the rest of [`MorphOp`](/enums-reference) — `Gradient`, `TopHat`
and `BlackHat` live on the same method.

### Remove salt-and-pepper speckle from a scan

Scanner dust, sensor hot pixels and lossy compression all leave the same signature: isolated pixels that are
far brighter or darker than everything around them. A **median** blur replaces each pixel with the median of
its neighbourhood, and a single outlier can never be the median — so the speck is deleted outright. A
Gaussian blur only *averages* it, which spreads the speck out instead of removing it.

Fifty single-pixel specks on a flat grey card, cleaned two ways and counted after thresholding:

```scala mdoc:silent
val ciSpeckPoints: Seq[Point] =
  for
    x <- 5 until 160 by 17
    y <- 7 until 120 by 23
  yield Point(x.toDouble, y.toDouble)

val ciNoisyScan: Image =
  ciSpeckPoints.foldLeft(Image.blank(160, 120, Scalar(40), channels = 1)) { (img, p) =>
    img.drawRect(Rect(p.x.toInt, p.y.toInt, 1, 1), Scalar(255), Thickness.Filled)
  }

val ciRawSpeckMask = ciNoisyScan.copy.threshold(80)
val ciNoisySpecks = ciRawSpeckMask.contours().size
ciRawSpeckMask.close()

val ciMedianMask = ciNoisyScan.copy.medianBlur(1).threshold(80) // 3x3 median
val ciMedianSpecks = ciMedianMask.contours().size
ciMedianMask.close()

val ciGaussMask = ciNoisyScan.blur(1).threshold(80) // 3x3 Gaussian
val ciGaussSpecks = ciGaussMask.contours().size
ciGaussMask.close()
```

```scala mdoc
(ciNoisySpecks, ciMedianSpecks, ciGaussSpecks)
```

The median count drops to zero. The Gaussian count does not: the speck is dimmer afterwards, but it is still
the brightest thing in its neighbourhood, so it survives the threshold — and now its neighbours are polluted
too.

:::note[`medianBlur` takes a radius, and its floor is 1]
Both `blur` and `medianBlur` take a **radius**, from which the kernel side is `radius * 2 + 1`. `blur(0)` is
a deliberate identity, but OpenCV's median requires an odd kernel of at least 3, so `medianBlur(0)` is
rejected up front rather than silently doing nothing:

```scala mdoc:crash
Image.blank(20, 20, Scalar(40), channels = 1).medianBlur(0)
```

A median blur is also markedly slower than a Gaussian of the same size, and it rounds off sharp corners. Use
it where the noise is impulsive; use `blur` where the noise is smooth. `bilateralFilter` is the third option
when you need smoothing that leaves edges crisp.
:::

### Binarise dark text on a light page

The default `threshold` keeps what is *brighter* than the cutoff, which is backwards for ink on paper. Two
ways to flip it: `Threshold.Mode.BinaryInv` for a page that is evenly lit, and `adaptiveThreshold(inverse =
true)` for one that is not — a phone photo of a page, or a scan with a shadow down one side.

```scala mdoc:silent
val ciPage =
  Image
    .blank(220, 90, Scalar.White)
    .drawRect(Rect(20, 20, 120, 8), Scalar.Black, Thickness.Filled) // three lines of "text"
    .drawRect(Rect(20, 40, 160, 8), Scalar.Black, Thickness.Filled)
    .drawRect(Rect(20, 60, 90, 8), Scalar.Black, Thickness.Filled)

// Evenly lit: one global cutoff, inverted so ink becomes white.
val ciGlobalInk = ciPage.copy.gray.threshold(128, kind = Threshold(Threshold.Mode.BinaryInv))
val ciGlobalStrokes = ciGlobalInk.contours().size
ciGlobalInk.close()

// Unevenly lit: a cutoff computed per 25x25 neighbourhood instead.
val ciAdaptiveInk = ciPage.gray.adaptiveThreshold(blockSize = 25, c = 10, mode = Threshold.Mode.BinaryInv)
val ciAdaptiveStrokes = ciAdaptiveInk.contours().size
ciAdaptiveInk.close()
```

```scala mdoc
(ciGlobalStrokes, ciAdaptiveStrokes)
```

Both give you a mask where ink is white (non-zero) and paper is black — the shape `contours()`, OCR and
`Image.write` all expect. Feed the adaptive version into
[Clean a scanned page](#straighten-and-clean-a-scanned-page-ocr-prep) before handing it to [Tesseract](/ocr).

:::warning[It is `Threshold(Threshold.Mode.BinaryInv)`, not `Threshold.BinaryInv`]
`Threshold` is a small case class wrapping a *mode* plus an optional automatic method, because OpenCV's
threshold flags are a bitmask rather than an enumeration. The modes live in `Threshold.Mode`; the companion
exposes only the ready-made `Threshold.Binary` value and the `Threshold.otsu()` / `Threshold.triangle()`
constructors. `Threshold.BinaryInv` does not exist and will not compile.

`adaptiveThreshold` has its own rules: it takes a **single-channel** image (put `gray` in front of it), and
`blockSize` must be odd and at least 3 — the neighbourhood needs a centre pixel. Raise `c` to keep less ink;
lower it to keep more.
:::

### Let OpenCV pick the threshold, and find out what it picked

When the lighting changes between shots, a hard-coded cutoff stops working. Otsu's method reads the image's
histogram and chooses the cutoff that best splits it into two groups. The catch is that the high-level
`Image.threshold` returns the *mask* and drops the number, and the number is often the reason you called it.
The mid-level `threshold` on a `Mat` returns both.

```scala mdoc:silent
val ciBimodal =
  Image
    .blank(80, 80, Scalar(40), channels = 1) // a dark background...
    .drawRect(Rect(20, 20, 40, 40), Scalar(200), Thickness.Filled) // ...and a bright block

// `.mat` borrows the Mat inside the Image. The mid-level `threshold` hands back a `Thresholded`:
// the Managed[Mat] holding the mask (ours to release) as `image`, and the computed value as
// `computed` — the number Otsu or Triangle chose, which is often the reason you called it.
val ciOtsuPick: Double =
  val Thresholded(mask, result) = ciBimodal.mat.threshold(0, 255, Threshold.otsu())
  mask.release()
  result.value

val ciTrianglePick: Double =
  val Thresholded(mask, result) = ciBimodal.mat.threshold(0, 255, Threshold.triangle())
  mask.release()
  result.value

ciBimodal.close()
```

```scala mdoc
(ciOtsuPick, ciTrianglePick)
```

Otsu assumes the histogram has **two** peaks and puts the cutoff in the valley between them. `Threshold`'s
other automatic method, `Threshold.triangle()`, assumes **one** dominant peak with a tail — the right choice
for faint objects on a large uniform background, such as fluorescence microscopy or stars on sky.

:::warning[The `value` you pass in is ignored, and the answer is only meaningful if the image is bimodal]
With `Threshold.otsu()` or `Threshold.triangle()` set, OpenCV overwrites the `value` argument with its own
choice — which is why the call above passes `0`. With plain `Threshold.Binary` the returned
`ThresholdResult.value` is the number you handed in and nothing more (`Threshold.computesThreshold`
tells the two cases apart).

Both methods take a **single-channel 8-bit** image. And both always return *something*: run Otsu on a flat,
single-peaked image and you get a meaningless cutoff somewhere in the middle of the noise, with a mask to
match. Check that the split is real before trusting it.
:::

There is a `Managed`-scoped version of the same call in the [lower-level recipes](#let-otsu-pick-the-threshold)
at the end of this page.

### Turn detections back into a mask

You have contours — from a threshold, a colour key, or a filter on size — and you now want to act on
*those regions only*. `drawContours` with `Thickness.Filled` on a blank single-channel canvas rebuilds them
as a mask, which is exactly what `applyMask`, `inpaint` and `blurBackground` all take.

```scala mdoc:silent
val ciObjects =
  Image
    .blank(200, 120, Scalar(20, 20, 20))
    .drawCircle(Point(55, 60), 26, Scalar(180, 180, 180), Thickness.Filled)
    .drawRect(Rect(120, 35, 55, 50), Scalar(180, 180, 180), Thickness.Filled)

val ciObjectMaskSrc = ciObjects.copy.gray.threshold(128)
val ciObjectShapes = ciObjectMaskSrc.contours()
ciObjectMaskSrc.close()

val ciBigShapes = ciObjectShapes.filter(_.area > 500) // whatever your keep-rule is

val ciRebuiltMask =
  Image
    .blank(200, 120, Scalar.Black, channels = 1) // single channel, same size as the image
    .drawContours(ciBigShapes, Scalar.White, Thickness.Filled)

val ciKeptOnly: Either[CvError, Array[Byte]] = ciObjects.applyMask(ciRebuiltMask).bytes(".png")
ciRebuiltMask.close()
```

```scala mdoc
ciBigShapes.size
```

Hand the *same* mask to `inpaint` instead of `applyMask` and you erase those objects rather than keeping
them — see [Erase an object](#erase-an-object-inpaint).

:::warning[Filled, single-channel, same size — and you close the mask]
Three things go wrong here. Leave off `Thickness.Filled` and you draw a one-pixel *outline*, so the mask
keeps a hairline and throws away the object. Build the canvas with the default `channels = 3` and OpenCV
rejects it, because a mask must be `CV_8UC1`. And the mask must match the image's width and height exactly.
`Scalar.White` is `Scalar(255, 255, 255)`; on a single-channel Mat only the first component is used, so it
means 255, which is what you want. Finally, `applyMask` **borrows** the mask — the receiver is consumed, the
mask is not — so `ciRebuiltMask.close()` is yours to call.
:::

### Blend two images

`blend` is an alpha composite — `weight` is how much of the receiver survives. Both images must match in size
and type.

```scala mdoc:silent
val base = Image.blank(100, 100, Scalar(255, 0, 0)) // blue
val over = Image.blank(100, 100, Scalar(0, 0, 255)) // red
val blended: Either[CvError, Array[Byte]] = base.blend(over, weight = 0.5).bytes(".png")
over.close() // `over` is borrowed by blend; `base` was consumed by it
```

### Erase an object (inpaint)

Mark the region to remove with a non-zero (single-channel) mask; inpaint fills it from its surroundings.

```scala mdoc:silent
val scratched =
  Image.blank(100, 100, Scalar(80, 120, 160)).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repairMask =
  Image.blank(100, 100, Scalar.Black, channels = 1).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repaired: Either[CvError, Array[Byte]] = scratched.inpaint(repairMask).bytes(".png")
repairMask.close()
```

### Seamlessly paste a patch

Poisson blending (`seamlessCloneInto`) pastes an object into a background so the seam disappears — it matches
gradients, not just pixels. The receiver (the patch) is consumed; `background` and `mask` are borrowed and the
result is `background`-sized.

```scala mdoc:silent
val patch = Image.blank(60, 60, Scalar(60, 160, 60))
val canvas = Image.blank(200, 200, Scalar(160, 160, 160))
val patchMask =
  Image.blank(60, 60, Scalar.Black, channels = 1).drawCircle(Point(30, 30), 24, Scalar.White, Thickness.Filled)
val cloned: Either[CvError, Array[Byte]] = patch.seamlessCloneInto(canvas, patchMask, Point(100, 100)).bytes(".png")
canvas.close()
patchMask.close()
```

### Blur the background behind a person

**Needs `scalacv-vision`.** `blurBackground` and `replaceBackground` are the background-effect verbs from the
vision module.

Given a foreground mask (from a segmentation model, or any keying), keep the person sharp and blur the rest —
the compositing behind a virtual background.

```scala mdoc:silent
val frame =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawCircle(Point(80, 60), 30, Scalar(200, 180, 160), Thickness.Filled)
val personMask =
  Image.blank(160, 120, Scalar.Black, channels = 1).drawCircle(Point(80, 60), 30, Scalar.White, Thickness.Filled)
val composited: Either[CvError, Array[Byte]] = frame.blurBackground(personMask, strength = 15).bytes(".png")
personMask.close()
```

See [Video conferencing](/conferencing) for producing the mask with a segmentation network, and
`replaceBackground` for swapping in a different scene entirely.

### Colour a heatmap

A single-channel image (a depth map, a motion field, any measurement) is invisible until you false-colour it.
`colorMap` turns `CV_8UC1` into a colour heatmap — the perceptually-uniform maps (`Viridis`, `Magma`,
`Inferno`, `Plasma`, `Turbo`) are the honest choice for data.

```scala mdoc:silent
val field =
  Image.blank(120, 120, Scalar(40), channels = 1).drawCircle(Point(60, 60), 40, Scalar(200), Thickness.Filled)
val heat: Either[CvError, Array[Byte]] = field.colorMap(Colormap.Inferno).bytes(".png")
```

## Finding and measuring things

A [`Contour`](/contours) is the outline of one blob, returned as plain immutable Scala data that outlives the
Mat it came from. Everything in this section is "binarise, take contours, then do ordinary Scala to them".

### Count the shapes in a frame

Binarise, then count [contours](/contours) — the outermost outline of each blob:

```scala mdoc:silent
val shapes = Image
  .blank(200, 120, Scalar.Black)
  .drawCircle(Point(40, 60), 18, Scalar.White, Thickness.Filled)
  .drawCircle(Point(100, 60), 18, Scalar.White, Thickness.Filled)
  .drawRect(Rect(150, 40, 40, 40), Scalar.White, Thickness.Filled)
val binary = shapes.gray.threshold(128)
val shapeCount = binary.contours().size
binary.close()
```

```scala mdoc
shapeCount
```

A [`Contour`](/contours) is plain immutable data (it survives the Mat it came from), so you can `approx` it to
count corners — a filled rectangle simplifies back to four:

```scala mdoc:silent
val poly = Image.blank(120, 120, Scalar.Black).drawRect(Rect(20, 20, 80, 80), Scalar.White, Thickness.Filled)
val polyBin = poly.gray.threshold(128)
val corners =
  polyBin.contours().headOption match
    case Some(c) => c.approx(0.02 * c.perimeter).points.size
    case None    => 0
polyBin.close()
```

```scala mdoc
corners
```

### Separate two touching blobs

Two objects that touch are one connected region, so `contours()` reports **one** outline, and your count is
wrong. Eroding the mask shrinks every blob inwards; once the erosion is wider than the neck joining them, the
neck disappears and the blobs come apart.

```scala mdoc:silent
val ciTouching =
  Image
    .blank(160, 120, Scalar.Black)
    .drawCircle(Point(55, 60), 25, Scalar.White, Thickness.Filled)
    .drawCircle(Point(100, 60), 25, Scalar.White, Thickness.Filled) // overlaps the first

val ciTouchingMask = ciTouching.gray.threshold(128)
val ciTouchingCount = ciTouchingMask.contours().size // one blob, not two

val ciSeparated = ciTouchingMask.erode(radius = 12, shape = MorphShape.Ellipse)
val ciSeparatedCount = ciSeparated.contours().size
val ciSeedCentres = ciSeparated.contours().flatMap(_.centroid)
ciSeparated.close()
```

```scala mdoc
(ciTouchingCount, ciSeparatedCount)
```

```scala mdoc
ciSeedCentres
```

The erosion radius is not a magic number: the two discs here have radius 25 and their centres are 45 pixels
apart, so the neck between them is `2 * sqrt(25² - 22.5²)` ≈ 22 pixels wide. The brush has to be wider than
*half* of that to bite through, hence `radius = 12`. `MorphShape.Ellipse` is the right brush for round
objects — a `Rect` brush eats the corners of the shape at a different rate on the diagonals.

:::warning[Erosion is a counting trick, not a restoration]
The eroded blobs are smaller than the real objects, so their `area` and `boundingRect` are wrong. Dilating by
the same radius does grow them back — and re-merges them, because the neck comes back too. So use the eroded
mask for the *count* and for the seed positions (the centroids above), and go back to the original mask when
you need each object's true extent. When the objects overlap heavily rather than merely touch, no erosion
radius works and you need the distance transform plus a watershed, which lives at the
[lower level](/low-level).
:::

### Find the biggest blob

`contours()` returns plain data, so ordinary Scala — `sortBy`, `maxByOption`, `filter` — does the rest. Here,
the largest blob's bounding box, drawn back over a copy of the source:

```scala mdoc:silent
val src2 = Image
  .blank(200, 120, Scalar.Black)
  .drawCircle(Point(50, 60), 12, Scalar.White, Thickness.Filled)
  .drawRect(Rect(120, 40, 50, 50), Scalar.White, Thickness.Filled)
val bin2 = src2.copy.gray.threshold(128)
val boxes = bin2.contours().sortBy(-_.area).map(_.boundingRect)
val biggestArea = boxes.headOption.map(_.area).getOrElse(0L)
bin2.close()
val annotated2: Either[CvError, Array[Byte]] = src2.drawRects(boxes.take(1), Scalar.Green).bytes(".png")
```

```scala mdoc
biggestArea
```

### Reject detections by size and aspect ratio

Most of what a threshold finds is not what you are looking for. Three measurements throw away the junk before
you do any real work: **area** (too small is noise), **aspect ratio** (`width / height` of the bounding box —
a sliver is a scratch, not an object), and **fill ratio** (`area / boundingRect.area` — a low value means a
thin diagonal streak rather than a solid body).

```scala mdoc:silent
val ciSizeScene =
  Image
    .blank(240, 140, Scalar.Black)
    .drawRect(Rect(20, 20, 100, 50), Scalar.White, Thickness.Filled) // a solid bar — keep
    .drawRect(Rect(150, 20, 12, 100), Scalar.White, Thickness.Filled) // a thin sliver — reject
    .drawCircle(Point(60, 110), 5, Scalar.White, Thickness.Filled) // a tiny dot — reject

val ciSizeMask = ciSizeScene.gray.threshold(128)

val ciMeasured =
  ciSizeMask.contours().map { c =>
    val box = c.boundingRect
    (box, c.area, box.width.toDouble / box.height, c.area / box.area.toDouble)
  }
ciSizeMask.close()

val ciKept =
  ciMeasured.filter { case (_, area, aspect, fill) =>
    area >= 200 && aspect >= 0.4 && aspect <= 3.0 && fill >= 0.5
  }
```

```scala mdoc
ciMeasured.sortBy(-_._2)
```

```scala mdoc
ciKept.map(_._1)
```

:::warning[A solid rectangle does not score a fill ratio of 1.0]
`Contour.area` uses the shoelace formula, which measures the polygon through the **centres** of the boundary
pixels. A filled 100 × 50 rectangle therefore reports 99 × 49 = 4851, not 5000 — while `boundingRect`
*is* inclusive of the extreme pixels and reports `Rect(20, 20, 100, 50)`, area 5000. So the two numbers
disagree by design, a perfectly solid rectangle scores a fill ratio of about 0.97, and 1.0 is unreachable.
Set your threshold at 0.5 or 0.8, never at 1.0, and expect the discrepancy to be proportionally larger on
small objects (a 4 × 4 square scores 3 × 3 / 16 = 0.56).
:::

### Tell a triangle from a square from a circle

Two cheap numbers separate the common shapes. **Corner count** comes from simplifying the outline with
`approx`, which drops vertices that lie within `epsilon` pixels of the simplified line. **Circularity**,
`4π · area / perimeter²`, is 1.0 for a perfect circle and falls away as a shape gets more angular — a square
scores about 0.79, a triangle about 0.6.

```scala mdoc:silent
val ciTriangleOutline = Contour(Seq(Point(50, 15), Point(85, 75), Point(15, 75)))

val ciShapeScene =
  Image
    .blank(280, 100, Scalar.Black)
    .drawContours(Seq(ciTriangleOutline), Scalar.White, Thickness.Filled)
    .drawRect(Rect(110, 20, 60, 60), Scalar.White, Thickness.Filled)
    .drawCircle(Point(230, 50), 30, Scalar.White, Thickness.Filled)

def ciClassify(c: Contour): String =
  val circularity = 4 * math.Pi * c.area / (c.perimeter * c.perimeter)
  if circularity > 0.85 then "circle"
  else
    c.approx(0.03 * c.perimeter).points.size match
      case 3 => "triangle"
      case 4 => "quadrilateral"
      case n => s"$n-sided"

val ciShapeMask = ciShapeScene.gray.threshold(128)
val ciShapeNames = ciShapeMask.contours().map(ciClassify).sorted
ciShapeMask.close()
```

```scala mdoc
ciShapeNames
```

Note that `Contour` is plain immutable Scala data — `ciTriangleOutline` above is one you wrote by hand, and
`drawContours` renders it exactly as it renders one that came back from `contours()`.

:::warning[Test circularity *before* corner count, and scale `epsilon` to the shape]
A rasterised circle does not simplify to "no corners" — it simplifies to a polygon with roughly eight of
them, so a corner-count-first classifier calls every circle an octagon. Check circularity first.

`epsilon` must be a *fraction of the perimeter*, not a fixed number of pixels: an epsilon tuned on a
300-pixel shape will merge the corners of a 30-pixel one into nothing. `0.03` is a tuning knob — too large
and a square collapses to a triangle, too small and every rasterised staircase step counts as a corner.
:::

### Draw the convex hull around a blob

The convex hull is the tightest outline with no dents — the shape a rubber band would take around the object.
It is how you get a clean grip outline for a jagged silhouette, and the ratio of the shape's area to its
hull's area (*solidity*) is a good single number for "how dented is this?".

```scala mdoc:silent
val ciConcave = Contour(
  Seq(Point(10, 10), Point(70, 10), Point(70, 30), Point(30, 30), Point(30, 70), Point(10, 70))
) // an L-bracket: the notch at (30, 30) is the dent

val ciHull = ciConcave.convexHull
val ciSolidity = ciConcave.area / ciHull.area

val ciHullPng: Either[CvError, Array[Byte]] =
  Image
    .blank(90, 90, Scalar.Black)
    .drawContours(Seq(ciConcave), Scalar(90, 90, 90), Thickness.Filled) // the shape, in grey
    .drawContours(Seq(ciHull), Scalar.Green, Thickness.Stroke(1)) // the hull, outlined
    .bytes(".png")
```

```scala mdoc
(ciConcave.points.size, ciHull.points.size, ciSolidity)
```

In real code `ciConcave` comes from `contours()` and the hull is one call on it —
`shape.convexHull` — with no round trip through pixels.

:::note[The hull reuses the original points, and only ever grows]
`convexHull` asks OpenCV for *indices* into the contour and maps them back, so every hull vertex is
literally one of the input points rather than a recomputed approximation. That means the hull always has
fewer points than (or the same number as) the contour, and always encloses at least as much area — so
solidity is in `(0, 1]` and can never exceed 1. A hull is *not* a smoothing operation: it will happily
swallow a genuine concavity you cared about, such as the gap between two fingers.
:::

### Label each object at its centre

To number the things you found, or to print a measurement next to each one, you need a point per object.
`Contour.centroid` is the centre of mass computed from image moments, which is a much better anchor than the
bounding box corner.

```scala mdoc:silent
val ciLabelScene =
  Image
    .blank(240, 120, Scalar.Black)
    .drawCircle(Point(50, 60), 25, Scalar.White, Thickness.Filled)
    .drawRect(Rect(110, 35, 50, 50), Scalar.White, Thickness.Filled)
    .drawCircle(Point(200, 60), 20, Scalar.White, Thickness.Filled)

val ciLabelMask = ciLabelScene.copy.gray.threshold(128)
val ciCentroids = ciLabelMask.contours().flatMap(_.centroid) // Option per contour, flattened
ciLabelMask.close()

val ciLabelled: Either[CvError, Array[Byte]] =
  ciCentroids.zipWithIndex
    .foldLeft(ciLabelScene) { case (img, (p, i)) =>
      img.drawText((i + 1).toString, Point(p.x - 6, p.y + 6), Scalar.Red, scale = 0.6)
    }
    .bytes(".png")
```

```scala mdoc
ciCentroids
```

The `foldLeft` is not decoration: every `drawText` **consumes** the image it is called on and returns a new
one, so an ordinary `foreach` would try to reuse a spent `Image`. Folding threads the single live image
through the loop.

:::warning[`centroid` is an `Option`, and it can land outside the shape]
The moments-based centroid divides by `m00`, the zero-order moment. For a degenerate outline — a single
point, or a run of collinear points — `m00` is 0 and the division is undefined, so `centroid` returns `None`
rather than a `NaN` point. `flatMap` above drops those.

Even when it exists, the centroid is the centre of *mass* of the enclosed region. For a concave shape — a
crescent, an L-bracket, a horseshoe — that point can lie in the hollow, outside the object entirely. If you
need a point guaranteed to be *on* the object, use a point from `contour.points` instead.

The offset `Point(p.x - 6, p.y + 6)` is there because `drawText` anchors on the glyph **baseline's left
end**, not on the centre of the string. See [Drawing](/drawing) and `Draw.textSize` for measuring properly.
:::

### Find straight lines (Hough)

Canny produces the edge image the [Hough transform](/hough) needs; `houghLinesP` returns finite
[`Segment`](/hough)s you can filter by length or draw with `drawSegments`.

```scala mdoc:silent
val boxed = Image.blank(120, 120, Scalar.Black).drawRect(Rect(20, 20, 80, 80), Scalar.White, Thickness.Stroke(2))
val boxEdges = boxed.gray.canny(50, 150)
val segments = boxEdges.mat.houghLinesP(threshold = 20, minLineLength = 15, maxLineGap = 5)
boxEdges.close()
```

```scala mdoc
segments.nonEmpty
```

### Keep both sides of every edge with Sobel

A **Sobel derivative** measures how fast brightness changes across the image — large where an edge is, near
zero on flat paint. Reach for it when you want the gradient itself (to measure edge strength, to feed a
corner or texture measure) rather than the yes/no answer [`canny`](/image-processing) gives you.

The derivative is **signed**: going dark → bright is positive, bright → dark is negative. An 8-bit image
cannot hold a negative number, so asking for the default output depth throws half of every edge away. The
two counts below are the same scene measured both ways:

```scala mdoc:silent
val advEdgeScene =
  Image.blank(80, 60, Scalar.Black).drawRect(Rect(20, 15, 40, 30), Scalar.White, Thickness.Filled)

val (advNaiveEdgePixels, advSignedEdgePixels) =
  advEdgeScene.gray.managed.use { g =>
    // The trap: OutputDepth.SameAsSource on an 8-bit image clips every negative derivative to 0.
    val naive = g.sobel(dx = 1, dy = 0).use(m => org.opencv.core.Core.countNonZero(m))
    // The fix: compute at 16-bit signed, then fold the sign away with convertScaleAbs.
    val signed = g
      .sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16)
      .pipe(_.convertScaleAbs())
      .use(m => org.opencv.core.Core.countNonZero(m))
    (naive, signed)
  }
```

```scala mdoc
(advNaiveEdgePixels, advSignedEdgePixels)
```

The rectangle has a left edge (dark → bright, positive) and a right edge (bright → dark, negative). The
naive call sees only the left one; `Signed16` plus [`convertScaleAbs`](/image-processing) — which scales,
takes the absolute value and saturating-casts back to 8-bit — sees both.

:::warning[A wider depth is not displayable, and not recordable]
`Signed16` and `Float32` results are not 8-bit pixel data. `Recorder.write` rejects them outright (it
`require`s an 8-bit frame), `colorMap` aborts in native code, and `imwrite` only survives them by silently
coercing behind your back. Bring the result back with `convertScaleAbs` (absolute value) or `normalize`
(min–max stretch, 8-bit by default) before you write, display or false-colour it.
:::

## Annotating and drawing

Turning a result back into pixels: boxes and text with the core `draw*` verbs, and whole diagrams, charts and
animations with the [`Picture`](/graphics) layer in `scalacv-graphs`.

### Draw a text badge

OpenCV anchors text on the *baseline*, so measure first with `Draw.textSize`, then size a filled background
rectangle to enclose the glyphs (including descenders). See [Drawing](/drawing) for the baseline caveat.

```scala mdoc:silent
val label = "scalacv"
val metrics = Draw.textSize(label, scale = 0.8)
val boxW = metrics.size.width.toInt + 12
val boxH = metrics.size.height.toInt + metrics.baseline + 12
val badge: Either[CvError, Array[Byte]] =
  Image
    .blank(boxW + 8, boxH + 8, Scalar.Black)
    .drawRect(Rect(4, 4, boxW, boxH), Scalar(50, 50, 50), Thickness.Filled)
    .drawText(label, Point(10, 4 + metrics.size.height.toInt + 6), Scalar.White, scale = 0.8)
    .bytes(".png")
```

### Annotate a scene and encode it

Chaining `draw*` calls builds an annotated frame; `bytes` encodes it to an in-memory PNG/JPEG without touching
the filesystem — the shape you want when the result is going into an HTTP response or a notebook.

```scala mdoc:silent
val annotated: Either[CvError, Array[Byte]] =
  Image
    .blank(220, 140, Scalar.White)
    .drawRect(Rect(20, 20, 90, 70), Scalar.Green)
    .drawCircle(Point(150, 70), 30, Scalar.Red)
    .drawText("scalacv", Point(20, 125), Scalar.Black)
    .bytes(".png")
```

### Give every detection its own colour

**Needs `scalacv-graphs`.** The `Color` palette, the `Picture` scene graph, `Chart` and `Animation` all live
in that module; `import scalacv.*` brings them in once the jar is on the classpath.

When a frame has several detections, one green box around all of them tells you nothing about which is which.
`Color.wheel(n)` returns `n` colours spaced evenly around the hue wheel, so zipping it against your
detections gives each one a visibly different colour.

Here the "detections" are contours found in a scene the snippet draws itself:

```scala mdoc:silent
val gfxScene = Image
  .blank(240, 120, Scalar.Black)
  .drawCircle(Point(40, 60), 25, Scalar.White, Thickness.Filled)
  .drawRect(Rect(100, 30, 50, 60), Scalar.White, Thickness.Filled)
  .drawCircle(Point(200, 60), 20, Scalar.White, Thickness.Filled)

val gfxBinary = gfxScene.copy.gray.threshold(128)
val gfxBoxes: Seq[Rect] = gfxBinary.contours().map(_.boundingRect)
gfxBinary.close()

val gfxPalette: Seq[Color] = Color.wheel(gfxBoxes.size)
```

```scala mdoc
gfxBoxes.size
```

`Color` is the RGBA palette the `Picture` layer uses; the core drawing verbs take an OpenCV `Scalar`, and
`toBgrScalar` is the bridge between the two (named for the channel order it produces):

```scala mdoc:silent
val gfxTagged: Either[CvError, Array[Byte]] =
  gfxBoxes
    .zip(gfxPalette)
    .foldLeft(gfxScene) { case (img, (box, colour)) =>
      img.drawRect(box, colour.toBgrScalar, Thickness.Stroke(2))
    }
    .bytes(".png")
```

```scala mdoc
gfxTagged.fold(_.getMessage, bytes => s"${bytes.length} bytes")
```

Three things worth knowing:

- **`toBgrScalar` drops the alpha.** A `Color` carries transparency and the `Picture` renderer honours it, but a
  `Scalar`'s fourth channel is not an alpha the `draw*` verbs respect. If you want a washed-out colour in a
  core drawing call, bake it in first — `colour.fadeOut(0.5).toBgrScalar` is *not* how (that only lowers an alpha
  nobody reads); blend toward the background instead, with `colour.blend(Color.Black, 0.5).toBgrScalar`.
- **`Color.categorical` is exactly eight colours.** It is `wheel(8)`, a good default for a legend, but with
  nine or more detections two of them share a colour. `Color.wheel(n)` sized to the actual count is the fix.
  `wheel(0)` is a legal empty palette, and `zip` against it yields nothing — so a frame with no detections
  draws no boxes rather than failing.
- **The colour comes from the *position* in the sequence.** Contour order is not stable from frame to frame,
  so an object can change colour as it moves. If you want a colour that sticks to an object, key the palette
  on a track id — `Color.wheel(n)(track.id % n)` — from the [tracker](/tracking) rather than on the index.

### Draw a labelled diagram

**Needs `scalacv-graphs`.**

A [`Picture`](/graphics) is a *drawing described as a value*: you build shapes, style them, and stack or lay
them out, and nothing touches a pixel until you render. Reach for it when the output is a picture rather than
a photograph — a diagram, a legend, an annotated overlay — and especially when you want the boxes to line
themselves up instead of you counting pixels.

A node is a filled rounded rectangle with a caption drawn on top of it. `a.on(b)` means "`a` on top of `b`",
so the text goes on the left of the expression and the box on the right:

```scala mdoc:silent
def gfxNode(caption: String): Picture =
  Picture
    .text(caption, Point(14, 27))
    .strokeColor(Color.White)
    .on(Picture.roundedRectangle(Rect(0, 0, 110, 44), radius = 10).fillColor(Color.Blue).noStroke)
```

`beside(that, gap)` measures both pictures' bounding boxes and translates the second one to sit `gap` pixels
to the right of the first, centres aligned — no coordinates to work out by hand. `bounds` is that same
measurement exposed to you (`Option[Bounds]`, empty when the picture draws nothing), which is how the arrow
below finds the middle of the gap:

```scala mdoc:silent
val gfxRow = gfxNode("capture").beside(gfxNode("detect"), gap = 40)

val gfxMidX = gfxRow.bounds.map(_.centerX).getOrElse(0.0)
val gfxMidY = gfxRow.bounds.map(_.centerY).getOrElse(0.0)

val gfxDiagram = Picture
  .arrow(Point(gfxMidX - 20, gfxMidY), Point(gfxMidX + 20, gfxMidY))
  .strokeColor(Color.DarkGray)
  .strokeWidth(2)
  .on(gfxRow)

val gfxDiagramPng: Either[CvError, Array[Byte]] =
  gfxDiagram.at(Point(20, 26)).render(320, 96, Color.White).bytes(".png")
```

```scala mdoc
gfxDiagramPng.fold(_.getMessage, bytes => s"${bytes.length} bytes")
```

Two things to know before you build a bigger one:

- **`render` hands you an owned [`Image`](/image-api).** The picture itself is inert data you may reuse as
  often as you like, but the canvas `render` returns holds a live native Mat and follows the ordinary move
  rules — end the chain in a terminal (`bytes`, `write`) or call `close()` on it. `picture.renderOn(image)`
  and `image.draw(picture)` are the same drawing onto an image you already have, and consume that image.
- **An unstyled shape is a one-pixel *white* outline with no fill.** That is the default style, so a shape you
  forgot to colour disappears against a white background. Styling is inherited — a style set on a group is the
  default its members may override — which for a single shape means the **first** styling call in a chain is
  the one that shows: `.fillColor(Color.Red).fillColor(Color.Blue)` draws red.

:::note[`Picture.rotate` turns the other way]
`picture.rotate(degrees)` is **clockwise**, because in image space y points down, while
`image.rotate(degrees)` on a whole image is counter-clockwise. The two are not typos of each other — see
[2D graphics](/graphics).
:::

### Plot a bar chart straight to a PNG

**Needs `scalacv-graphs`.**

[`Chart`](/graphics) builds a `Picture` out of your numbers, so a plot is just another drawing: render it to
its own PNG, or drop it into the corner of a video frame with `image.draw`. Useful when you want a quick plot
in a headless job — a batch report, a Slack attachment, a debug overlay — without adding a plotting library or
a browser.

Every chart is sized to a `width`×`height` box whose origin is its top-left corner, so render onto a canvas of
at least that size:

```scala mdoc:silent
val gfxCounts = Seq(3.0, 7.0, 5.0, 9.0)
val gfxBarsPng: Either[CvError, Array[Byte]] =
  Chart.bars(gfxCounts, 320, 200, Color.Blue).render(320, 200, Color.White).bytes(".png")
```

```scala mdoc
gfxBarsPng.fold(_.getMessage, bytes => s"bar chart, ${bytes.length} bytes")
```

The rest of the family takes the same box: `Chart.line(values, w, h)` for a trend, `Chart.area(values, w, h)`
for the same line closed down to the baseline, `Chart.scatter(pairs, w, h)` for `(x, y)` points,
`Chart.pie(values, w, h)` for proportions, and `Chart.histogram(data, bins, w, h)` for "what does this
distribution look like" — it buckets the raw data for you and then draws the counts as bars:

```scala mdoc:silent
val gfxSamples = Seq(1.0, 2, 2, 3, 3, 3, 4, 4, 5, 5, 5, 6)
val gfxHistogramPng: Either[CvError, Array[Byte]] =
  Chart.histogram(gfxSamples, bins = 6, width = 240, height = 90).render(240, 90, Color.White).bytes(".png")
```

```scala mdoc
gfxHistogramPng.fold(_.getMessage, bytes => s"histogram, ${bytes.length} bytes")
```

The gotcha, and it is a quiet one: **`bars`, `line` and `area` plot the absolute value of every datum.** Each
bar or vertex is `|v|` measured up from a baseline at the bottom of the box, scaled to the largest magnitude
in the series. A series that crosses zero — a day-on-day delta, a profit-and-loss line, an audio waveform — is
folded upwards, and the result still looks like a perfectly reasonable chart. `pie` rectifies too. If your
data is signed, shift it into positive territory yourself (`v - min`) and draw your own zero line, or use
`scatter`, which maps the true `(x, y)` range into the box. `histogram` is unaffected, because it bins the raw
values first and counts are never negative. [2D graphics](/graphics) shows the fold happening, byte for byte.

Two smaller edges: a chart box must be positive (`Chart.bars(values, 0, 200, ...)` throws
`IllegalArgumentException` rather than returning an empty picture — a zero-width box is a bug in the caller,
not data), and `histogram` needs `bins >= 1`. An *empty* series is fine and yields `Picture.empty`, which
draws nothing.

### Make an animated GIF

**Needs `scalacv-graphs`.**

An animation is a picture that is a function of the frame number: you write `frame(i)`, and
[`Animation`](/graphics) renders each frame onto a fresh canvas and encodes the lot. Good for a README demo, a
rendered data animation, or a synthetic test clip you can check into a repository.

Describe one frame first — here a circle whose height follows a sine wave, so the loop joins up seamlessly:

```scala mdoc:silent
def gfxBounce(i: Int): Picture =
  val y = 100 + 60 * math.sin(2 * math.Pi * i / 24.0)
  Picture.circle(Point(100, y), 18).fillColor(Color.Cyan).noStroke
```

`Animation.gif` writes the file and returns the number of frames written, or a `Left` if the encode failed:

```scala mdoc:compile-only
Animation.gif("bounce.gif", frames = 24, width = 200, height = 200, fps = 12)(gfxBounce)
```

That one writes to disk, so it is shown but not run here. The same frame function drives
`Animation.foreach`, which renders each frame into memory, hands it to you as an owned `Image`, and closes it
again before drawing the next — that part *is* runnable:

```scala mdoc:silent
var gfxFrameCount = 0
Animation.foreach(count = 24, width = 200, height = 200)(gfxBounce) { canvas =>
  if canvas.width == 200 then gfxFrameCount += 1
}
```

```scala mdoc
gfxFrameCount
```

The gotchas are about size, and they bite in production rather than in a demo:

- **A GIF is 256 colours per frame.** OpenCV dithers your full-colour render down to fit, so gradients band
  and photographic frames look muddy. Flat fills and line art survive it well; anything else wants a video.
- **`gif` holds every frame in memory at once.** It renders all `frames` canvases into a buffer before handing
  them to the encoder, so the peak native cost is `frames × width × height × 3` bytes — 300 frames at 1280×720
  is about 830 MB. `Animation.record(path, frames, width, height)` writes a video through a
  [`Recorder`](/video) instead, rendering, writing and closing one canvas at a time, so its peak cost is a
  single frame however long the clip. Keep GIFs short and small; use `record` for anything else. (`record`
  defaults to the MJPG codec, which opens only in an `.avi` container — name the file `spin.avi`, not
  `spin.mp4`.)
- **`fps` must be greater than zero and `frames` cannot be negative** — both are `require`s, so they throw
  rather than returning a `Left`. So does a `frame` function that throws, and a `Picture` that fails its own
  precondition (`Picture.star` with one point, say): only genuine OpenCV encode failures come back as `Left`.
  Either way a half-written GIF is deleted, because a truncated file still opens in a viewer and would read as
  success.

## Video and live capture

A [`Camera`](/video) reads frames from a file, a device index or a network URL; a [`Recorder`](/video) writes
them back out. The recipes below start by *making* a clip, so everything after it runs with nothing on disk.

### Write a video from frames you drew

You need a test clip and you have no footage — for a unit test, for a demo, or to have something for the
[video recipes](#re-encode-a-video) further down to open. A [`Recorder`](/video) is a video *writer* that
takes [`Image`](/image-api)s, and the images can be drawn ones, so a clip is a loop over a drawing.

```scala mdoc:silent
val vvDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("scalacv-cookbook-video")
vvDir.toFile.deleteOnExit()

val vvClip: java.nio.file.Path = vvDir.resolve("bounce.avi")
val vvSize = Size(320, 240)

val vvWritten: Either[CvError, Unit] =
  Recorder.using(vvClip.toString, vvSize, fps = 25.0, codec = Codec.Mjpg) { rec =>
    (0 until 50).foreach { i =>
      // A green disc sliding left to right: 50 frames of honest, detectable motion.
      val frame = Image
        .blank(320, 240, Scalar(20, 20, 20))
        .drawCircle(Point(20 + i * 5, 120), 18, Scalar.Green, Thickness.Filled)
      try rec.write(frame).fold(e => throw e, identity)
      finally frame.close()
    }
  }
```

```scala mdoc
vvWritten.isRight
java.nio.file.Files.size(vvClip) > 0
```

:::warning[`rec.write` borrows the frame — you close it]
This is the exception to the rule the rest of the cookbook runs on. Every `Image` *transform* consumes its
receiver, so a chain frees itself; `Recorder.write` is neither a transform nor a terminal — it reads the
frame's pixels and hands the `Image` straight back to you, still alive. Without the `try`/`finally` above,
a 50-frame clip leaks 50 frames and a 50,000-frame clip leaks 50,000. The same `try`/`finally` also means
this loop uses the same amount of native memory however long the clip is.
:::

Two more rules a `Recorder` will not let you break: every frame must match the size the recorder was opened
with, and must be 8-bit (a mismatch throws `IllegalArgumentException` rather than writing a file of noise).
And `Codec.Mjpg` needs an `.avi` container — it is the default because Motion-JPEG is served by OpenCV's
*built-in* writer, so it needs no FFmpeg and no system codec, but the codec and the file extension have to
agree or `Recorder.open` returns a `Left`. See [Sample inputs](/sample-inputs) for the rest of the
generate-your-own-fixtures technique and [Video](/video) for the codec ladder.

### Check a clip before you process it

Before you size a `Recorder`, allocate a buffer or draw a progress bar, ask the source what it is.
`Camera.usingFile` opens the file, runs your function and closes the capture; `info` returns a
[`CaptureInfo`](/video) — five fields the backend claims about the source.

```scala mdoc:silent
val vvInfo: Either[CvError, CaptureInfo] = Camera.usingFile(vvClip.toString)(_.info)
```

```scala mdoc
vvInfo.map(i => (i.width, i.height, i.fps, i.frameCount, i.backendName))
vvInfo.map(_.size)
```

:::warning[Every field is advisory — never use `frameCount` as a loop bound]
`info` asks the backend, and backends guess. A live camera usually reports `frameCount == 0` (the question
is meaningless for a stream) and an `fps` of `0` until it has delivered a frame; some containers report a
`frameCount` that is off by a frame or two from what actually decodes. Use these numbers to *size* a
recorder or to show progress, never to decide how many times to loop.
:::

The frame count that is true is the one you get by reading the frames:

```scala mdoc:silent
val vvRealFrames: Either[CvError, Int] =
  Camera.usingFile(vvClip.toString) { cam =>
    var n = 0
    cam.foreach(attemptsPerFrame = 1)(_ => n += 1)
    n
  }
```

```scala mdoc
vvRealFrames
```

`attemptsPerFrame = 1` is the right setting for a file: the first failed read is end-of-file. The default of
`3` exists so a flaky live camera can drop a frame without the stream being declared over, and on a finite
file it only costs two extra blocking reads at the end.

### Re-encode a video

`Camera.recordTo` reads every frame, applies your transform, and writes the result — the transform must
preserve the frame size (colour-convert, filter, annotate: yes; resize: size a [`Recorder`](/video) yourself).

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  cam.recordTo("edges.mp4")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
}
```

### Snapshot the webcam

`Camera.using(0)` opens device 0 and closes it afterwards; `snapshot` retries a few reads before giving up, so
a single dropped frame does not look like a dead camera.

```scala mdoc:compile-only
Camera.using(0)(_.snapshot().flatMap(_.write("shot.png")))
```

### Average several frames to kill sensor noise

Sensor noise is different in every frame; the scene is not. Averaging a handful of frames from a **static**
camera therefore cancels the noise and keeps the picture — the cheapest possible way to clean up a dim
webcam still before you measure anything on it.

`taking` grabs the next *n* frames as owned [`Image`](/image-api)s and closes every one of them when your
block returns, so the averaging has to happen inside:

```scala mdoc:compile-only
/** The running mean of `frames`, or None if there were none. The frames are borrowed, never consumed. */
def advAverage(frames: Seq[Image]): Option[Image] =
  frames.zipWithIndex.foldLeft(Option.empty[Image]) {
    case (None, (frame, _))      => Some(frame.copy)
    // `acc` already holds the mean of `i` frames, so the new frame is worth 1/(i+1) of the result.
    case (Some(acc), (frame, i)) => Some(acc.blend(frame, weight = i.toDouble / (i + 1)))
  }

Camera.using(0) { cam =>
  cam.taking(8) { frames => advAverage(frames).map(_.write("averaged.png")) }
}
```

Two ownership facts make this work. `blend` **borrows** its argument and **consumes** its receiver, so each
frame survives the fold untouched and each intermediate mean is spent by the next step — nothing leaks.
And because `taking` closes every frame the moment the block returns, the seed is a `.copy`: without it the
helper would hand back an image whose pixels are about to be freed.

:::warning[A fixed 0.5 weight is not an average]
`reduce((a, b) => a.blend(b, 0.5))` looks like averaging and is not: the first frame ends up weighted
`1/2^(n-1)`, so eight frames are really "the last frame, slightly smudged". The running weight
`i / (i + 1)` above is what gives every frame an equal share. Averaging also assumes a still camera and a
still subject — anything that moves comes out as a ghost.
:::

### Open an RTSP stream that might hang

A network camera can accept your connection and then deliver nothing. `VideoCapture.read` blocks in native
code with no timeout of its own, so the honest response is to ask the backend to give up for you:

```scala mdoc:compile-only
import scala.concurrent.duration.DurationInt

Camera.usingFile(
  "rtsp://camera.local:554/stream1",
  CaptureOptions.withTimeout(5.seconds, CaptureBackend.FFmpeg)
)(_.snapshot().flatMap(_.write("still.png")))
```

`CaptureOptions.withTimeout(d, backend)` sets both `openTimeout` and `readTimeout` to `d`. They are off by
default, and deliberately so — see [`CaptureOptions`](/video) for the measurements behind that choice.

:::warning[The timeout is best-effort, and nothing reports whether you got one]
Only some backends implement it: **FFmpeg and GStreamer honour it for network sources; V4L2, AVFoundation
and the built-in MJPEG reader ignore it entirely**, and there is no API that tells you which you got. The
values can only be set at open time, and a backend that does not understand them refuses the open outright —
which is why scalacv retries without them rather than reporting a failure that really means "your backend
has no timeout support". Naming a backend is itself a portability decision: one that is not compiled into
the OpenCV build on your classpath turns a working `open` into a failing one.
:::

### Fall back through codecs until one opens

A **codec** compresses video frames; a **container** (`.mp4`, `.avi`) is the file that holds them. Which
codecs exist is a property of the OpenCV build on your classpath, not of your code — the `linux-x86_64` and
`windows-x86_64` payloads this project builds against ship **no FFmpeg plugin at all**. `Recorder.open`
reports that as a `Left` rather than a silently black file, which is exactly what you need to try the next
rung:

```scala mdoc:silent
/** Best compression first, most portable last. The extension travels with the codec, not separately. */
val advCodecLadder: Seq[(Codec, String)] =
  Seq(Codec.Avc1 -> ".mp4", Codec.Mp4v -> ".mp4", Codec.Mjpg -> ".avi")

def advOpenBestRecorder(base: String, size: Size, fps: Double): Either[CvError, (Codec, Recorder)] =
  advCodecLadder.iterator
    .map { case (codec, extension) =>
      Recorder.open(base + extension, size, fps, codec).map(recorder => (codec, recorder))
    }
    .find(_.isRight) // lazy: it stops at the first rung that opens
    .getOrElse(Left(CvError.LoadFailed(base, "no codec in the ladder could open a recorder")))

val advLadderDir = java.nio.file.Files.createTempDirectory("scalacv-cookbook-codec")
```

```scala mdoc
advOpenBestRecorder(advLadderDir.resolve("clip").toString, Size(320.0, 240.0), 25.0)
  .map { case (codec, recorder) =>
    recorder.close() // a probe, not a recording — this one goes straight in the bin
    codec
  }
```

That is the codec the machine building this page actually landed on. `Codec.Mjpg` in an `.avi` is the bottom
rung because videoio's MJPEG writer is built in — no FFmpeg, no GStreamer, no system codec — which is also
why it is the default for `Recorder.open`, `Recorder.using` and `Camera.recordTo`.

:::warning[The container is part of the bargain, and the probe belongs at start-up]
MJPG opens **only** in an `.avi`: a path ending `.mp4` fails even where the codec itself is present, which is
why each rung above carries its own extension. Run the ladder once during warm-up against a throwaway path,
keep the winning `Codec`, and pass it explicitly from then on — each failed rung costs an open attempt and
prints an OpenCV warning to stderr, which you do not want once per clip. Record the winner as a metric label:
a deployment that silently drops from `Avc1` to `Mjpg` grows its output files by an order of magnitude, and
that label is the only place it shows up before the disk does.
:::

See [Degradation & error budgets](/degradation-and-error-budgets) for the same ladder shape applied to
capture sources and models.

### Analyse only the newest frame

When analysis is slower than capture, a queue turns into a growing backlog and your "live" view drifts
minutes behind reality. For a live source the right policy is usually **latest-frame-wins**: keep exactly one
frame, the newest, and throw the rest away on purpose. The whole mechanism is a one-slot mailbox:

```scala mdoc:silent
import java.util.concurrent.atomic.AtomicReference
import org.opencv.core.{CvType, Mat}

/** A one-frame mailbox. Ownership travels with the frame; whatever is displaced is freed on the spot. */
final class AdvLatestFrame:
  private val slot = AtomicReference[Managed[Mat]](null)

  /** Publishes `frame` and releases whatever it displaced. The caller must not touch `frame` again. */
  def offer(frame: Managed[Mat]): Unit = Option(slot.getAndSet(frame)).foreach(_.release())

  /** Takes the newest frame. **The caller now owns it and must release it.** */
  def take(): Option[Managed[Mat]] = Option(slot.getAndSet(null))

  /** Releases anything left in the slot. Idempotent. */
  def drain(): Unit = take().foreach(_.release())
```

`getAndSet` is a single atomic operation, which is what makes this correct with no lock: exactly one caller
ever sees a given frame, so exactly one caller is responsible for freeing it. Watch the displaced frame die:

```scala mdoc:silent
val advSlot = AdvLatestFrame()
val advOldFrame = Managed(Mat(4, 4, CvType.CV_8UC3))
val advNewFrame = Managed(Mat(4, 4, CvType.CV_8UC3))
advSlot.offer(advOldFrame)
advSlot.offer(advNewFrame) // `advOldFrame` is displaced — and released — inside this call
```

```scala mdoc
(advOldFrame.isReleased, advNewFrame.isReleased)
```

```scala mdoc:invisible
advSlot.drain()
```

Wiring it to a camera is a capture thread that only ever offers, and a worker that only ever takes:

```scala mdoc:compile-only
import java.util.concurrent.atomic.AtomicBoolean

val advRunning = AtomicBoolean(true)
val advMailbox = AdvLatestFrame()

val advCaptureThread = Thread { () =>
  Camera.using(0) { cam =>
    // framesCopied, not frames: a frame that crosses a thread boundary needs its own pixel buffer.
    Video.framesCopied(cam.capture, attemptsPerFrame = 3) { frames =>
      frames.takeWhile(_ => advRunning.get()).foreach(advMailbox.offer)
    }
  }
  ()
}
advCaptureThread.start()

// The worker. `Image.wrap` adopts the frame, so the chain that follows frees it.
while advRunning.get() do
  advMailbox.take() match
    case Some(frame) => Image.wrap(frame).gray.canny(60, 160).write("latest.png")
    case None        => Thread.sleep(2) // the slot was empty; wait for the next frame

// Shutdown: whatever flipped this — a signal handler, a health check — ends both loops.
advRunning.set(false)
advCaptureThread.join()
advMailbox.drain() // free the frame in flight when you stopped
```

:::danger[`Video.frames` cannot be used here]
`frames` decodes into **one reused Mat** and overwrites it in place, so a frame handed across a thread
boundary would be rewritten under the reader's feet. `framesCopied` clones each frame into a caller-owned
`Managed[Mat]` — that clone is the reason this is safe, and the reason someone must free it. Drop the
`release` in `offer` and you leak a full frame every time analysis falls behind. `drain()` at shutdown, after
nothing can `offer` again.
:::

Full treatment — measuring the lag before you tune it, the other three strategies, and the shutdown
sequence — is in [Streaming & backpressure](/streaming-and-backpressure).

### Raise a motion alarm on a video

**Needs `scalacv-vision`.** `MotionDetector` is the vision module's stateful motion detector.

Feed frames in order to a stateful [detector](/motion-detection); it reports whether — and where — something
moved. `Camera.foreach` hands you an owned [`Image`](/image-api) per frame and closes it for you.

A detector holds native memory — a retained previous frame, or a background model — so it has to be closed,
and closed even when the loop throws. That is what the `try`/`finally` is for; wrapping the whole thing in a
function is how you make the detector's lifetime exactly as long as the job:

```scala mdoc:compile-only
/** Prints a line for every frame of `path` that contains motion. */
def alertOnClip(path: String): Either[CvError, Unit] =
  val detector = MotionDetector.frameDifference()
  try
    Camera.usingFile(path) { cam =>
      cam.foreach() { frame =>
        val motion = detector.detect(frame)
        if motion.moving then println(s"motion in ${motion.regionCount} region(s)")
      }
    }
  finally detector.close()
```

The same detector drives an MJPEG stream (an ESP32-CAM, a trail cam) directly from the encoded bytes —
`detect(Array[Byte])` decodes each JPEG for you. `frames` here is *your* source: an HTTP multipart reader, a
queue drained by a worker, a poll loop against a camera's snapshot URL.

```scala mdoc:compile-only
def alertOnMotion(frames: Iterator[Array[Byte]]): Unit =
  val detector = MotionDetector.backgroundSubtraction()
  try frames.foreach(jpeg => detector.detect(jpeg).foreach(m => if m.moving then println("alert")))
  finally detector.close()
```

`detect(image)` **borrows** the frame — it reads the pixels and does not consume the `Image`, so the frame
`Camera.foreach` lent you is still the camera's to close. `detect(Array[Byte])` decodes the bytes into an
image it owns and closes that copy itself, and returns a `Left` only when the bytes are not a decodable
image. Neither overload needs a `.copy`.

## Detection, tracking and depth

Everything in this section lives in `scalacv-vision`, and every recipe repeats that under its own heading —
recipes get copied one at a time, and a missing dependency line is the commonest reason one of them will not
compile for you. The detectors here are all *queries*: they read an image and hand back plain data without
consuming it, so the image stays yours to close.

### Read a QR code

**Needs `scalacv-vision`.**

At the high level, `qrCodes` is a query on an [`Image`](/image-api) (see [Object detection](/object-detection));
it borrows the image and returns plain data:

```scala mdoc:compile-only
Image.reading("qr.png")(_.qrCodes.map(_.text))
```

The [lower-level version](#decode-a-qr-code) below builds a code to decode, so it needs no fixture file.

### Find faces

**Needs `scalacv-vision`.**

See the [lower-level recipe](#find-faces-with-a-haar-cascade) for a Haar cascade, and
[Object detection](/object-detection) for the more accurate DNN face detector (`faces`).

### Generate and detect an ArUco marker

**Needs `scalacv-vision`.**

An ArUco marker is a printable black-and-white square tag carrying an integer id. Unlike a photograph you
can *make* one, so the whole round trip runs with no assets: render the tag, then detect it back.

```scala mdoc:silent
val vvMarker: Image =
  Image
    .wrap(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 200))
    .pad(60, color = Scalar.White) // the quiet zone — see below

val vvTags: Seq[ArucoMarker] = vvMarker.arucoMarkers()
val vvMarkerPng: Either[CvError, Array[Byte]] = vvMarker.bytes(".png") // consumes and releases vvMarker
```

```scala mdoc
vvTags.map(_.id)
vvTags.flatMap(_.corners).size
```

The id round-trips, and each marker reports four corners. An `ArucoMarker` is plain immutable data, so
`vvTags` stays valid after `bytes` released the image it came from.

:::warning[The generator leaves no quiet zone, and the detector needs one]
`Aruco.generateMarker` produces the tag with its own black border and *nothing else* — the black runs to
the very edge of the image. The detector finds candidates by hunting for a dark quadrilateral **on a light
background**, so a tag with no white margin has no background to be dark against and is never found. Drop
the `.pad(60, color = Scalar.White)` above and the result is an empty `Seq`, not an error. When you print a
marker, leave the same white margin around it on the paper.
:::

Two more ways to get a silent empty `Seq` rather than a wrong answer: detecting with a different dictionary
from the one you generated with (`arucoMarkers()` defaults to `Dict4x4_50`, the same dictionary used above),
and a tag that is too small on screen for its bit grid to resolve. Asking `generateMarker` for fewer pixels
than the tag has modules throws instead. Pick the *smallest* dictionary that has enough ids for your job —
fewer markers in a dictionary means a bigger difference between any two of them, and so more robust
detection. [Markers & AR](/marker-ar) covers recovering the tag's 3-D pose from those four corners.

### Find a button on a screenshot

**Needs `scalacv-vision`.**

"Is this control on screen, and where?" is *template matching*: slide a small picture of the thing you are
looking for over a bigger picture and report where it correlates best. No model, no training.
`Screen.locate` returns `Some(TemplateMatch)` — a `Rect` and a score — or `None` when nothing reached
`minScore`.

This helper stands in for a screen grab; the next two recipes reuse it.

```scala mdoc:silent
/** A stand-in screenshot: a dark desktop carrying a "button" (a white block with a dark bar across it) at
  * each of `buttons`. The bar matters — see the warning below.
  */
def vvDesktop(buttons: Seq[(Int, Int)]): Image =
  buttons.foldLeft(Image.blank(240, 160, Scalar(45, 45, 45))) { (img, at) =>
    val (x, y) = at
    img
      .drawRect(Rect(x, y, 40, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(x + 6, y + 8, 28, 4), Scalar(40, 40, 40), Thickness.Filled)
  }
```

```scala mdoc:silent
val vvShot = vvDesktop(Seq((30, 24)))
val vvButton = vvShot.copy.crop(Rect(28, 22, 44, 24)) // cut the template out of a *copy*
val vvHit: Option[TemplateMatch] = Screen.locate(vvShot, vvButton, minScore = 0.85)
val vvOutlined: Either[CvError, Array[Byte]] =
  vvShot.copy.drawRects(vvHit.map(_.location).toSeq, Scalar.Green).bytes(".png")
vvShot.close()
vvButton.close()
```

```scala mdoc
vvHit.map(m => (m.location, m.score))
```

:::warning[`Screen` borrows both images, so both are still yours to close]
`locate`, `findAll` and `diff` are *queries*, not transforms: they read the images and return plain data,
and they take ownership of neither. Nothing in the chain closes `vvShot` or `vvButton` for you, which is
why the two `close()` calls are written out. The `.copy` before `crop` and before `drawRects` is the other
half of the same story — those two *are* transforms, and would otherwise consume the screenshot we still
need.
:::

Two things will make a match fail that look like bugs and are not. The template must be **no larger** than
the image — `locate` throws `IllegalArgumentException` rather than returning `None`, because a template
bigger than the haystack is a programmer error. And the template must have **contrast**: the score is a
normalised correlation, which is undefined for a patch of one flat colour, so a solid rectangle matches
nothing. That is why the stand-in button above has a bar across it. `minScore` runs from `-1` to `1`;
`0.95`+ for a byte-for-byte crop, `0.8` (the default) for the same widget in the same theme, `0.6`–`0.75`
when compression or anti-aliasing differ. Template matching is not scale- or rotation-invariant: a template
grabbed at one display scaling will not match at another. [Screen analysis](/screen-analysis) has the full
tuning table and a "wait until this button appears" loop.

### Find every copy of an icon

**Needs `scalacv-vision`.**

When the same thing can appear more than once — a row of identical buttons, every red status light —
`Screen.findAll` returns them best-first, with each hit's footprint suppressed so the next one is a
*different* location rather than the same peak reported twice.

```scala mdoc:silent
val vvGrid = vvDesktop(Seq((20, 20), (120, 20), (70, 100)))
val vvIcon = vvGrid.copy.crop(Rect(18, 18, 44, 24))
val vvAll: Seq[TemplateMatch] = Screen.findAll(vvGrid, vvIcon, minScore = 0.8, maxMatches = 10)
vvGrid.close()
vvIcon.close()
```

```scala mdoc
vvAll.size
vvAll.map(_.location)
```

:::note[Why you do not get twenty hits on one icon]
A good match does not produce a single bright peak in the correlation map — it produces a small hill, and
every pixel near the summit is nearly as good as the summit itself. Left alone, the search would return the
same icon twenty times, one pixel apart. After each hit `findAll` paints out a region roughly half a
template wide and half a template tall around that peak, so the next round has to find a different one.
Two consequences worth knowing: two *genuine* copies overlapping by less than half a template will be
reported as one, and the search stops at the first peak below `minScore` rather than scanning the whole map
— results are always the best ones, never a random subset.
:::

`maxMatches` defaults to `20` and must be at least `1`. `Screen.locate` is exactly this call with
`maxMatches = 1` and `.headOption`, so use `locate` whenever one hit is all you want.

### Spot what changed between two screenshots

**Needs `scalacv-vision`.**

`Screen.diff` compares two captures and returns the regions that differ — plain `Rect`s, largest first. It
is the assertion a visual test makes ("only the dialog changed") and the cheap poll a screen watcher makes
("has anything happened?"), with no model and no state kept between calls.

```scala mdoc:silent
val vvBefore = vvDesktop(Seq((20, 20)))
val vvAfter = vvDesktop(Seq((20, 20), (140, 90))) // one new button appears
val vvChanged: Seq[Rect] = Screen.diff(vvBefore, vvAfter, threshold = 25, minArea = 100)
val vvDiffPng: Either[CvError, Array[Byte]] = vvAfter.copy.drawRects(vvChanged, Scalar.Red).bytes(".png")
vvBefore.close()
vvAfter.close()
```

```scala mdoc
vvChanged
```

One region: the button that appeared. The one both captures share is identical in both, so it is not
reported.

:::warning[The two captures must be the same size, and the box is bigger than the change]
Hand `diff` two captures of different dimensions and it throws `IllegalArgumentException` rather than
guessing an alignment — resize or crop them to match first. And the reported rectangle is a few pixels
larger on every side than the thing that changed: `diff` grows the changed-pixel mask by 2 pixels before
measuring it, so that a change broken into specks by compression noise comes back as one region instead of
thirty. A 40×20 button that appears is reported as a 44×24 rectangle. Use the rectangle to know *where* to
look, not to measure the change.
:::

The two knobs work in opposite directions. `threshold` (default `25`) is the per-pixel intensity difference
that counts as a change — raise it when compression and sub-pixel jitter are producing phantom regions,
lower it to catch subtler change. `minArea` (default `100`) drops blobs smaller than that many pixels, so a
one-pixel text caret is invisible at the default; lower it if the change you care about is tiny.

`diff` remembers nothing between calls, which is exactly right for a before/after assertion or a poll every
few seconds. For a continuous feed where the background itself drifts — a camera, a screen recording — use
the stateful [`MotionDetector`](/motion-detection) instead, as in
[Raise a motion alarm](#raise-a-motion-alarm-on-a-video). [Screen analysis](/screen-analysis) compares the
two side by side.

### Follow one object without re-detecting it

**Needs `scalacv-vision`.**

A [`Tracker`](/tracking) is *model-free*: you show it a box in one frame and it finds that same patch in the
next, learning the appearance as it goes. Reach for it when you have one thing to follow and no detector for
it — or when running a detector on every frame is too slow, and you want to detect occasionally and track in
between.

```scala mdoc:silent
import scala.util.Using

def advTrackFrame(centreX: Int): Image =
  Image
    .blank(200, 200, Scalar.Black)
    .drawRect(Rect(centreX - 15, 85, 30, 30), Scalar.White, Thickness.Filled)

val advTrackedBox: Option[Rect] =
  Tracker.create(TrackerKind.Csrt).toOption.flatMap { created =>
    Using.resource(created) { tracker =>
      val seed = advTrackFrame(50)
      try tracker.init(seed, Rect(35, 85, 30, 30)) // where the object is in the first frame
      finally seed.close()

      val moved = advTrackFrame(90) // the square has slid 40 px to the right
      try tracker.update(moved)
      finally moved.close()
    }
  }
```

```scala mdoc
advTrackedBox.map(b => b.x + b.width / 2 > 65) // the box followed the square rightward
```

The lifecycle is two verbs: `init(image, box)` once, then `update(image)` per frame. `update` returns
`Option[Rect]`, and both the image and the box are borrowed — `init` and `update` do **not** consume the
frame, unlike every `Image` transform.

:::warning[`None` means lost — except with `Mil`]
CSRT and KCF report a lost object by returning `None`; `TrackerKind.Mil` has no failure detection and
**always** hands back a box, so it can drift onto the background and keep reporting confidently. Calling
`update` before `init` throws. When `update` returns `None`, re-seed with a fresh detection rather than
carrying on: `init` may be called again at any time.
:::

### Give every detection a stable ID

**Needs `scalacv-vision`.**

A detector answers "where are the objects in *this* frame" and nothing more — frame to frame, its boxes are
anonymous. [`ObjectTracker`](/tracking) stitches them into tracks with identities that persist, which is what
lets you say "person #3" or count how many distinct objects have passed.

```scala mdoc:silent
def advIdFrame(step: Int): Image =
  Image
    .blank(320, 200, Scalar.Black)
    .drawRect(Rect(20 + step * 8, 60, 24, 30), Scalar.White, Thickness.Filled)
    .drawRect(Rect(250 - step * 7, 120, 24, 30), Scalar.White, Thickness.Filled)

val advIdTracker = ObjectTracker.create(iouThreshold = 0.3, maxAge = 5, minHits = 2)

val advIdsPerFrame: Seq[Seq[Int]] =
  (0 until 4).map { step =>
    val frame = advIdFrame(step)
    // Any detector would do here — faces, motion regions, a DNN. These boxes come from contours.
    val binary = frame.copy.gray.threshold(128)
    val detections = binary.contours().map(_.boundingRect)
    binary.close()

    val tracks = advIdTracker.update(detections)
    frame.drawTracks(tracks).close() // in real code: `.write(s"tracked$step.png")`
    tracks.map(_.id).sorted
  }

advIdTracker.close()
```

```scala mdoc
(advIdsPerFrame, advIdTracker.count)
```

The first frame reports nothing and the rest report the same two ids: with `minHits = 2` a track must be
matched twice before it is confirmed, which costs one frame of latency and buys you immunity to a
single-frame false positive. **The constructor's own default is `minHits = 1`** — every blip becomes a
confirmed track with an id of its own — so pass it explicitly when that matters. `count` is the running
number of *distinct* objects ever seen, the number you show as "3 people entered".

:::warning[It is stateful, single-threaded, and owns native memory]
Each live track carries its own [`Kalman`](#smooth-a-jittery-tracked-point) filter, so `close()` the tracker
when you are done, keep one instance per stream, and keep `update` on one thread. `update` never looks at the
image — only at the boxes — which is exactly why it composes with any detector.
:::

### Smooth a jittery tracked point

**Needs `scalacv-vision`.**

A detected centroid wobbles by a pixel or two even when the object is gliding smoothly, and it disappears
entirely on the frame where the detector blinks. [`Kalman.point`](/tracking) models position *and* velocity,
so it can both smooth the wobble and keep the point moving through a frame with no measurement at all.

```scala mdoc:silent
// One entry per frame: the measured centroid, or None where the detector found nothing.
// In a real loop this is `binary.contours().maxByOption(_.area).flatMap(_.centroid)`.
val advMeasurements: Seq[Option[Point]] = Seq(
  Some(Point(10, 50)),
  Some(Point(21, 49)),
  Some(Point(29, 51)),
  None, // occluded
  None, // still occluded
  Some(Point(62, 50)),
  Some(Point(69, 49))
)

val advSmoothedPath: Seq[Point] =
  val filter = Kalman.point(Point(10, 50))
  try
    advMeasurements.map { measured =>
      val predicted = filter.predict()            // always, exactly once per frame
      measured.fold(predicted)(filter.correct)    // no reading? coast on the prediction
    }
  finally filter.close()
```

```scala mdoc
advSmoothedPath.map(p => f"${p.x}%.1f").mkString(" → ")
```

The x values keep advancing through the two blank frames because the filter learned a velocity of roughly
+10 px per frame and kept applying it.

:::warning[`predict` once per frame, whether or not you have a measurement]
The model only advances when `predict()` is called. Skipping it on frames with no reading is the classic
bug: the filter stops moving, so it cannot coast — and calling it twice on one frame doubles the apparent
speed. Two knobs shape the personality: raise `measurementNoise` for a smoother, laggier track; raise
`processNoise` to chase fast movement more closely. `Kalman` holds a native filter, so `close()` it.
:::

### Work out which way the scene moved

**Needs `scalacv-vision`.**

[Sparse optical flow](/navigation) follows individual points from one frame to the next. Averaging their
motion gives you a single answer to "which way did everything shift" — the basis of camera-shake estimation,
pan detection, and the front end of visual odometry.

```scala mdoc:silent
def advFlowScene(ox: Int, oy: Int): Image =
  Image
    .blank(220, 180, Scalar(30, 30, 30))
    .drawRects(
      Seq(
        Rect(30 + ox, 30 + oy, 26, 26),
        Rect(130 + ox, 40 + oy, 30, 22),
        Rect(70 + ox, 110 + oy, 22, 34),
        Rect(150 + ox, 120 + oy, 26, 26)
      ),
      Scalar.White,
      Thickness.Filled
    )

val advFlowBefore = advFlowScene(0, 0)
val advFlowAfter = advFlowScene(6, 4) // the whole scene slid right 6 px and down 4 px

val advCorners = OpticalFlow.goodFeatures(advFlowBefore, maxPoints = 120)
val advKeptTracks =
  OpticalFlow
    .track(advFlowBefore, advFlowAfter, advCorners)
    .filter(_.found)          // a lost point still carries a `to`; ignore it
    .filter(_.distance > 0.5) // gate out sub-pixel noise from stationary texture

val advMeanShift =
  if advKeptTracks.isEmpty then Point(0, 0)
  else
    Point(
      advKeptTracks.map(_.displacement.x).sum / advKeptTracks.size,
      advKeptTracks.map(_.displacement.y).sum / advKeptTracks.size
    )

advFlowBefore.close()
advFlowAfter.close()
```

```scala mdoc
f"${advKeptTracks.size} points, mean shift (${advMeanShift.x}%.1f, ${advMeanShift.y}%.1f)"
```

:::warning[`found` is not optional, and the answer is the scene's motion, not the camera's]
`track` returns one `Track` per input point, in the same order, **including the ones it lost** — and a lost
track's `to` is meaningless leftover data. Averaging without `.filter(_.found)` mixes that garbage into the
result. Remember also that a camera panning right makes the scene appear to move left: the sign is opposite
to the camera motion. Both images are borrowed here, so you close them yourself.
:::

### Turn ORB matches into camera motion

**Needs `scalacv-vision`.**

[`Features`](/navigation) gives you keypoints and matches; [`VisualOdometry`](/navigation) wants two
*ordered, parallel* lists of points. The join between them is the one step nothing else documents: a
`FeatureMatch` carries indices, not coordinates, and you look those indices up in the two descriptor sets.

```scala mdoc:silent
val advOdoA = advFlowScene(0, 0)
val advOdoB = advFlowScene(6, 4)

val advDescA = Features.detect(advOdoA)
val advDescB = Features.detect(advOdoB)

// queryIndex indexes the FIRST argument's points; trainIndex the SECOND. Swap them and the
// estimated motion is silently backwards.
val (advFromPoints, advToPoints) =
  Features
    .matches(advDescA, advDescB) // best-first, and tighten `maxDistance` to keep only close ones
    .map(m => (advDescA.points(m.queryIndex), advDescB.points(m.trainIndex)))
    .unzip

val advMotion: Option[CameraMotion] =
  VisualOdometry.estimate(advFromPoints, advToPoints, Intrinsics.approx(advOdoA.size))

advDescA.close()
advDescB.close()
advOdoA.close()
advOdoB.close()
```

```scala mdoc
advFromPoints.size // matched correspondences fed to the estimator
```

`Features.detect` hands back a `Descriptors` that owns a native descriptor Mat — `close()` it (or take it
into a `Using.resource`) or you leak one per frame. Matching is brute-force Hamming with cross-check, so
every match is *mutually* best, and `maxDistance` (64 by default; smaller is a closer descriptor) trims the
tail of weak ones.

The estimate is deliberately not printed: this synthetic pair is a flat scene under a pure sideways shift,
which is a **degenerate** case for the essential matrix — every point is at the same depth, so there is no
parallax to recover motion from. A real pair has objects at different distances. Feed it fewer than five
correspondences and you get `None` by construction.

:::note[Translation has no scale]
`CameraMotion.translation` is a **unit direction**, not metres: one camera cannot tell a small nearby motion
from a large distant one. Recover the scale from wheel odometry, an IMU, a known object size, or a stereo
baseline. `Intrinsics.approx(size)` is a guess good enough to see motion track, not to measure with — use a
real [chessboard calibration](/calibration) when the numbers matter.
:::

### Stereo disparity end to end

**Needs `scalacv-vision`.**

Two cameras a known distance apart see near things shift more than far ones. That shift is the **disparity**,
and it is the raw material for obstacle detection on a robot or drone: disparity map → obstacles →
a steering suggestion.

`StereoDepth.disparity` takes a **rectified** pair — row-aligned, which is a one-time calibration step — and
returns an 8-bit single-channel [`Image`](/image-api) where brighter means nearer:

```scala mdoc:silent
val advStereoLeft = advFlowScene(0, 0)
val advStereoRight = advFlowScene(-3, 0) // the right camera sees everything shifted left
val advDisparityMap = StereoDepth.disparity(advStereoLeft, advStereoRight, numDisparities = 32, blockSize = 7)
val advDisparityShape = (advDisparityMap.width, advDisparityMap.height, advDisparityMap.channels)
advDisparityMap.close()
advStereoLeft.close()
advStereoRight.close()
```

```scala mdoc
advDisparityShape
```

`numDisparities` is the depth range searched and must be a positive multiple of 16; `blockSize` is the odd
matching window. What the block matcher makes of a *synthetic* pair is not worth asserting on, so the second
half of the pipeline is shown on a hand-drawn map — exactly the shape `disparity` produces, with a big near
block ahead and a smaller one to the left:

```scala mdoc:silent
val advNearMap = Image
  .blank(240, 120, Scalar.Black, channels = 1)
  .drawRect(Rect(80, 20, 80, 90), Scalar(255), Thickness.Filled) // dead ahead, very near
  .drawRect(Rect(20, 30, 50, 60), Scalar(200), Thickness.Filled) // off to the left
val advObstacles = Obstacles.fromDisparity(advNearMap, minNearness = 0.5, minArea = 200)
val advGuidance = Navigator.steer(advNearMap)
advNearMap.close()
```

```scala mdoc
(advObstacles.map(o => (o.region, f"${o.nearness}%.2f")), advGuidance.steering)
```

`Obstacles.fromDisparity` returns the near-field blobs nearest-first; `Navigator.steer` splits the view into
left / centre / right thirds and turns toward the clearer side when the centre looms. Both **borrow** the
map — neither consumes it — so you close it yourself. `steer` needs a map at least 3 px wide, or the thirds
collapse.

:::danger[Nearness is relative to the frame it came from]
`disparity` normalises each map to 0…255 on its own min and max, so `nearness` is "how near compared with
everything else in *this* frame", not a distance and not comparable across frames. A frame containing
nothing but far-away wall still has bright pixels, and a fixed `minNearness` will report the wall as an
obstacle. Threshold on a calibrated range instead when the decision matters, and treat `nearness` as a
ranking, not a measurement.
:::

### Build an occupancy map from obstacles

**Needs `scalacv-vision`.**

An [`OccupancyGrid`](/navigation) is a top-down map that remembers: each cell accumulates evidence that it is
occupied, so a blob seen once is a maybe and a blob seen ten times is a wall. It is the memory a reactive
`Navigator` lacks and a planner needs.

```scala mdoc:silent
val advGridMap = Image
  .blank(240, 120, Scalar.Black, channels = 1)
  .drawRect(Rect(140, 30, 40, 50), Scalar(255), Thickness.Filled)
val advGridObstacles = Obstacles.fromDisparity(advGridMap, minNearness = 0.5, minArea = 200)
advGridMap.close()

// The rig's calibration in three numbers — replace these and everything below is unchanged.
val advFocalPx = 207.8      // Intrinsics.approx(Size(240, 120)) at a 60° horizontal field of view
val advImageCentreX = 120.0 // the principal point, in pixels
val advMetresAtFullNearness = 1.0 // nearness 1.0 means "one metre away" on this rig

// The robot sits at the world origin facing +x; each obstacle becomes one world point.
val advWorldHits: Seq[(Double, Double)] =
  advGridObstacles.map { o =>
    val centreX = o.region.x + o.region.width / 2.0
    val bearing = math.atan((centreX - advImageCentreX) / advFocalPx)
    val range = advMetresAtFullNearness / o.nearness // nearness is never 0 — it passed minNearness
    (range * math.cos(bearing), range * math.sin(bearing))
  }

val advGrid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1) // 6 m × 6 m, 10 cm cells
advWorldHits.foreach { case (x, y) => advGrid.hit(x, y) }
```

```scala mdoc
advWorldHits.map { case (x, y) =>
  f"($x%.2f, $y%.2f) occupied=${advGrid.isOccupied(x, y)} p=${advGrid.probability(x, y)}%.2f"
}
```

`hit` marks one cell and says nothing about the space in front of it. `observe` traces the whole ray from
the sensor to the obstacle, marking everything along the way *free* and the endpoint *occupied* — which is
what makes a map of free space accumulate rather than staying unknown forever:

```scala mdoc:silent
val advRayGrid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
advWorldHits.foreach { case (x, y) => advRayGrid.observe(0.0, 0.0, x, y) } // sensor at the origin
```

```scala mdoc
f"halfway along the ray: p=${advRayGrid.probability(0.5, 0.1)}%.2f, at the obstacle: p=${advRayGrid.probability(0.98, 0.19)}%.2f"
```

Below `0.5` is believed free, above it believed occupied, and exactly `0.5` means "never observed".

:::warning[Off the grid is silently ignored]
The grid is centred on the origin and covers `cols × resolution` by `rows × resolution` world units — 6 m by
6 m here, so ±3 m. An update outside that is **dropped without error**, and `probability` answers `0.5` for
it. A units bug (centimetres where the grid expects metres) therefore looks exactly like "the robot never
saw anything", not like a crash. `observe` needs a real sensor origin; passing the obstacle's own position as
the origin marks nothing free.
:::

## Documents, models and interop

Getting a page ready for text recognition, fetching the files a network needs, and moving images in and out of
the rest of the JVM.

### Straighten and clean a scanned page (OCR prep)

Deskew the text, then adaptive-threshold so uneven lighting doesn't swallow the letters:

```scala mdoc:compile-only
Image.reading("scan.jpg")(_.gray.deskew().adaptiveThreshold(blockSize = 25, c = 10).write("clean.png"))
```

See [OCR](/ocr) to hand the cleaned page to Tesseract.

### Fetch and verify your own model

[`Models.fetch`](/models) downloads a file, checks it against a pinned SHA-256, and only then moves it into
place — so an interrupted run never leaves a truncated model for the next load to trip over. It is
idempotent: a file that is already there and still matches is returned without touching the network.

```scala mdoc:compile-only
val advModelSpec = ModelSpec(
  fileName = "my-detector-v3.onnx",
  urls = Seq(
    "https://models.example.com/my-detector-v3.onnx", // primary mirror
    "https://backup.example.com/my-detector-v3.onnx", // tried only if the first fails
    "file:///opt/models/my-detector-v3.onnx"          // an on-disk copy is another source
  ),
  sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
)

val advModelPath: Either[CvError, java.nio.file.Path] =
  Models.fetch(advModelSpec, java.nio.file.Path.of("models"))
```

Mirrors are tried in order and the first that downloads *and verifies* wins; if none do, the `Left` lists
every failure. Get the hash with `sha256sum my-detector-v3.onnx` (or `shasum -a 256` on macOS) against the
exact file you intend to ship.

:::warning[`unverified` disables cache invalidation, not only integrity]
`ModelSpec.unverified(name, urls)` is the named opt-out for a model with no published checksum. It costs you
more than the tamper check: with no hash, *any* existing file with that name is accepted as the cached model,
so a stale or half-swapped download is returned forever. If you must use it, put the version in the
`fileName` (`my-detector-v3.onnx`, never `model.onnx`) so a new version cannot be shadowed by an old file.
:::

### Swing / AWT interop

`toBufferedImage` copies out to a `java.awt.image.BufferedImage` (which is also what a notebook renders), and
`Image.fromBufferedImage` brings one back in — always as a 3-channel BGR image. See [Image I/O](/image-io) and
[Notebooks](/notebooks).

```scala mdoc:silent
val awt: java.awt.image.BufferedImage = Image.blank(48, 24, Scalar.Blue).toBufferedImage
val back: Image = Image.fromBufferedImage(awt)
val backChannels = back.channels
back.close()
```

```scala mdoc
backChannels
```

You can round-trip through encoded bytes the same way — encode an image, hand the bytes off (an HTTP body, a
BLOB), and `decode` them back:

```scala mdoc:silent
val encodedRed: Either[CvError, Array[Byte]] = Image.blank(32, 32, Scalar.Red).bytes(".png")
val decodedWidth: Either[CvError, Int] =
  encodedRed.flatMap(Image.decode(_)).map { img =>
    try img.width
    finally img.close()
  }
```

```scala mdoc
decodedWidth.getOrElse(-1)
```

## Lower-level recipes

The same tasks on a raw `Mat`, for when you want the mid-level [ownership contract](/image-processing) and the
`Managed[Mat]` chain directly. Every mid-level op is *pure with respect to its receiver* — it allocates a fresh
result you own and must release — so `pipe` (release the intermediate once the next stage consumes it) and
`Managed.use` (release when the block returns) are how you keep a chain leak-free. See [Low-level](/low-level).

### Detect edges

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

val png: Either[CvError, Array[Byte]] =
  Managed.use(Mat(120, 120, CvType.CV_8UC3)) { image =>
    image
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(60, 160))
      .use(Images.encode(_, ".png"))
  }
```

### Chain many stages

`Mats.chain` reads as a list of stages rather than nested `pipe` lambdas — it borrows the source and releases
every intermediate:

```scala mdoc:silent
val chained: Either[CvError, Array[Byte]] =
  Managed.use(Mat(120, 120, CvType.CV_8UC3)) { image =>
    Mats
      .chain(image)(
        _.cvtColor(ColorConversion.BgrToGray),
        _.gaussianBlur(Size(5, 5), 1.5),
        _.canny(50, 150)
      )
      .use(Images.encode(_, ".png"))
  }
```

### Write your own pipe-able operator

OpenCV has more operations than any wrapper exposes. Anything you write with the shape
`Mat => Managed[Mat]` drops straight into [`pipe` and `Mats.chain`](/low-level) beside the built-ins — that
shape *is* the extension point. Here is Harris corner detection, which scalacv does not wrap:

```scala mdoc:silent
import org.opencv.imgproc.Imgproc

extension (self: Mat)
  /** Harris corner response. The receiver is borrowed; the CV_32F result is yours to release. */
  def harrisCorners(blockSize: Int = 2, kernelSize: Int = 3, k: Double = 0.04): Managed[Mat] =
    require(kernelSize > 0 && kernelSize % 2 == 1, s"kernelSize must be odd and positive, got $kernelSize")
    val dst = Mat()
    try
      Cv.orThrow("cornerHarris")(Imgproc.cornerHarris(self, dst, blockSize, kernelSize, k))
      Managed(dst)
    catch
      case e: Throwable =>
        dst.release() // nothing was handed to a caller, so nothing else can free it
        throw e
```

Four rules, and they are the whole contract:

1. **Borrow the receiver.** Never write to it, never free it, never let it alias the result.
2. **Allocate your own destination**, and hand it back wrapped in a `Managed` — the caller now owns it.
3. **Release that destination if the native call throws**, before the exception propagates. Otherwise every
   failure leaks a Mat that no caller ever saw and therefore cannot free.
4. **Validate arguments with `require`.** OpenCV's own checks live in native code and abort quoting a C++
   expression; yours names the parameter the caller actually passed.

Obey them and it composes exactly like a built-in:

```scala mdoc:silent
val advHarrisPng: Either[CvError, Array[Byte]] =
  Image
    .blank(120, 120, Scalar.Black)
    .drawRect(Rect(30, 30, 50, 40), Scalar.White, Thickness.Filled)
    .managed
    .use { src =>
      Mats
        .chain(src)(
          _.cvtColor(ColorConversion.BgrToGray),
          _.harrisCorners(blockSize = 2, kernelSize = 3, k = 0.04),
          _.normalize(0, 255) // CV_32F response → viewable 8-bit
        )
        .use(Images.encode(_, ".png"))
    }
```

```scala mdoc
advHarrisPng.isRight
```

The `normalize` stage is not decoration. `cornerHarris` answers in `CV_32F`, and its values are tiny
fractions — encode that directly and you get a black image. `normalize` stretches the range and, by default,
brings it down to 8 bits.

:::note[Wrapping a detector, not an operation]
For a native *type* rather than a native operation — a detector class OpenCV ships and scalacv does not wrap
— the equivalent is `given Releasable[YourDetector] = Releasable.handle(_.getNativeObjAddr)`. Of the 188
`org.opencv.*` types holding a native pointer, only `Mat`, `VideoCapture` and `VideoWriter` have a public
`release()`; `Releasable.handle` reaches the private `delete(long)` of the other 185. It throws rather than
degrading quietly, because the alternative is an unbounded leak that looks like success. See
[Mat lifecycle](/mat-lifecycle).
:::

### Read an image safely

`imread` never throws — it returns an empty Mat for a missing or unreadable file. scalacv turns that into an
`Either` so you cannot forget to check:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
```

### Let Otsu pick the threshold

The high-level [`Image.threshold`](/image-api) drops the value OpenCV computes; the mid-level `threshold`
returns it in a [`ThresholdResult`](/image-processing), which for `Threshold.otsu()` is the threshold chosen —
often the reason you called it.

```scala mdoc:silent
import org.opencv.core.Scalar as CvScalar

val otsuValue: Double =
  Managed.use(Mat(50, 50, CvType.CV_8UC1, CvScalar(0.0))) { m =>
    m.drawRect(Rect(10, 10, 30, 30), Scalar(200), Thickness.Filled) // two populations for Otsu to split
    val Thresholded(out, result) = m.threshold(0, 255, Threshold.otsu())
    out.release()
    result.value
  }
```

```scala mdoc
otsuValue > 0
```

### Decode a QR code

**Needs `scalacv-vision`.** `Qr` is the vision module's QR detector.

```scala mdoc:silent
import org.opencv.objdetect.QRCodeEncoder
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Mat, Size => CvSize}

// Encode one to decode it back — no fixture file needed.
val qr = Mat()
QRCodeEncoder.create().encode("https://github.com/w0rxbend/scalacv", qr)
val big = Mat()
Imgproc.resize(qr, big, CvSize(qr.cols * 10, qr.rows * 10), 0, 0, Imgproc.INTER_NEAREST)
val bgr = Mat()
Imgproc.cvtColor(big, bgr, Imgproc.COLOR_GRAY2BGR)
```

```scala mdoc
Qr.detectAndDecode(bgr).map(_.text)
```

```scala mdoc:invisible
qr.release(); big.release(); bgr.release()
```

### Find faces with a Haar cascade

**Needs `scalacv-vision`.** `Cascades` and `CascadeName` are vision-module types.

The cascade is resolved from the platform payload — nothing to download or vendor. The classifier is one of
the types with no public `release()`, so it is freed through the safe bridge:

```scala mdoc:silent
given Releasable[org.opencv.objdetect.CascadeClassifier] =
  Releasable.handle(_.getNativeObjAddr)

val faces: Either[CvError, Seq[Rect]] =
  Cascades.load(CascadeName.FrontalFaceAlt).map { c =>
    c.use { classifier =>
      Managed.use(org.opencv.core.Mat(200, 200, org.opencv.core.CvType.CV_8UC1)) { img =>
        img.detect(classifier)
      }
    }
  }
```

## Next

- [The Image API](/image-api) — the chainable high-level layer these recipes lead with.
- [Mat lifecycle](/mat-lifecycle) — move semantics, borrowing vs. consuming, and diagnosing use-after-move.
- [Image processing](/image-processing) — the mid-level `Mat` operations and the ownership contract behind them.
- [Sample inputs](/sample-inputs) — how to generate the images, clips and markers these recipes feed on.
