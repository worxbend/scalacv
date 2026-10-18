# The Image API

`Image` is the high-level face of scalacv and the layer to reach for first. It wraps a single native
image and lets you express the common OpenCV shape — **read → transform → detect → annotate → write** — as
one readable chain, with every intermediate freed for you.

If you are new here, the whole promise is this: you never touch a raw `Mat`, you never call `release`, and
you never leak native memory — as long as you follow one simple rule about *move semantics* that this page
explains from the ground up. Everything else is verbs.

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*
import org.opencv.core.{CvType, Mat, Scalar => CvScalar, Point => CvPoint}
import org.opencv.imgproc.Imgproc
OpenCv.load()

// A synthetic scene, so every example below runs against real pixels without shipping an image file.
// Returns a fresh Image each call because a transform *consumes* the one it is given.
def scene(): Image =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(40, 40, 40))
  Imgproc.rectangle(m, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220, 220, 220), -1)
  Imgproc.circle(m, CvPoint(125, 60), 24, CvScalar(255, 255, 255), -1)
  Image.wrap(Managed(m))

// Placeholders for the compile-only snippets below (those type-check but do not run):
val bytesFromSomewhere: Array[Byte] = Array.emptyByteArray
lazy val detector: org.opencv.objdetect.FaceDetectorYN = ??? // built with FaceDetect.create(model, size)
```

:::tip[New to scalacv? Start here.]
Every runnable example on this page begins from a helper called `scene()` — a small synthetic image of a
rectangle and a circle. It exists only so the docs can run without shipping a photo. In your own code you
would start from [`Image.read("photo.jpg")`](#getting-an-image) instead. Everything else is identical.
:::

## The whole idea, in one line

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

`read` gives you an `Either[CvError, Image]`; the chain transforms it; `write` encodes it and releases the
native memory. No `Mat`, no manual `release`, no leaked intermediates. Read the chain left to right and it
says exactly what it does: load the photo, drop to grey, blur it a little, find the edges, save the result.

### Two tiers, and when to leave this one

scalacv is a two-tier library on purpose. `Image` is the **high-level** tier: it manages Mats for you,
hides raw OpenCV int constants behind typed enums, and turns boundary failures into a `CvError`. Underneath
it is the **mid-level** tier — the same operations as extension methods on a borrowed `org.opencv.core.Mat`
— for the moments `Image` does not wrap what you need. `Image` is the pleasant default, never a wall; see
[Dropping to the low level](#dropping-to-the-low-level) and [/low-level](/low-level).

## Three kinds of method

Everything on `Image` is one of three shapes, and knowing which is which is the whole mental model:

| Kind | Examples | Effect on the image | Returns |
|---|---|---|---|
| **Transform** | `gray`, `blur`, `canny`, `resize`, `crop`, every `draw*` | **consumes** it, returns a new `Image` | `Image` |
| **Query** | `width`, `size`, `channels`, `isEmpty`, `contours`, `qrCodes` | **borrows** it, image stays alive | plain immutable data |
| **Terminal (releasing)** | `write`, `bytes`, `close` | **consumes** it and **frees** the Mat | `Either[CvError, Unit]` / `Either[CvError, Array[Byte]]` / `Unit` |
| **Terminal (handover)** | `managed` | **consumes** it but does **not** free — ownership moves out | `Managed[Mat]`, now yours |

`managed` is the only terminal that leaves live native memory behind. It spends the `Image` — the handle
you called it on is dead afterwards, exactly as with `write` — but it hands the Mat on instead of freeing
it. So the `Managed[Mat]` you get back has to be `use`d, `release()`d, or passed to `Image.wrap`. Drop it
on the floor and it leaks, precisely as a stray `Managed` would; see
[Dropping to the low level](#dropping-to-the-low-level) for the shape that gets it right.

A transform is not an accessor even when it looks like a field: `img.gray` spends `img`, and a discarded
`img.gray` statement leaks the result, because nobody holds the new `Image` to close it.

Keep that table in your head and the ownership rules below stop being rules and become obvious.

### A transform consumes the image

Each transform hands the underlying image on to the next step and spends the handle it was called on. That
is what makes a chain leak-free without a scope: it holds exactly one live image at a time.

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  scene().gray.blur(1).canny(60, 180).bytes(".png")
```

Using a handle after a transform has spent it throws — in Scala, before anything can reach native code:

```scala mdoc:crash
val img = scene()
val g = img.gray   // consumes img
img.width          // img is spent: this throws IllegalStateException, it does not read freed memory
```

:::note[Why it throws instead of crashing]
Calling into a freed OpenCV object segfaults the JVM from native code — no stack trace, no `catch`. So
`Image` (via [`Managed`](/mat-lifecycle)) flips that into an ordinary `IllegalStateException` on the Scala
side. If the error fires somewhere far from the real mistake, start the JVM with
`-Dscalacv.trackOwnership=true` and it will point at the line that actually consumed the handle.
:::

To feed one image into two chains, take a [`copy`](#branching-with-copy) first.

### A query borrows

Queries only read, so the image is still yours afterwards:

```scala mdoc
val img = scene()
(img.width, img.height, img.channels)
```

```scala mdoc:invisible
img.close()
```

The full set of queries:

| Query | Type | Meaning |
|---|---|---|
| `width` / `height` | `Int` | dimensions in pixels |
| `size` | `Size` | `Size(width, height)` |
| `channels` | `Int` | 3 for BGR, 1 for grey, 4 with alpha |
| `isEmpty` | `Boolean` | true for a 0×0 image with no pixels |
| `mat` | `org.opencv.core.Mat` | the borrowed raw Mat — see [below](#dropping-to-the-low-level) |
| `toBufferedImage` | `java.awt.image.BufferedImage` | an AWT copy, for Swing/notebooks |
| `contours(...)` | `Seq[Contour]` | contours of a binary image |

Because a query returns plain immutable Scala data (a copied `Int`, a `Seq[Contour]` of value types), the
result is safe to keep long after the image it came from is closed.

### A terminal releases

`write` and `bytes` encode and then release; `close` just releases. After any of them the handle is spent,
the same as after a transform.

```scala mdoc
scene().gray.bytes(".png").map(_.length)
```

:::warning[A failed terminal still releases]
`write` and `bytes` release the image in a `finally`, which means they release **whether or not the encode
succeeded**. Two things follow, and both bite the first time you write error-handling code:

- you cannot retry a failed `write` on the same `Image`, and
- you cannot fall back from a failed `write` to `bytes`.

Nor are those failures exotic. `CvError.EncodeFailed` is what comes back when the parent directory does not
exist, or when the file extension is one OpenCV has no encoder registered for — exactly the cases the
[error model](/error-model) tells you to branch on.

The fix is to decide *before* the terminal runs: check the destination up front, or spend a `.copy` and keep
the original alive for the second attempt.

```scala mdoc:compile-only
val source = scene()

// Spend a *copy* on the write, so that a failure still leaves an image to fall back on.
val written: Either[CvError, Unit] = source.copy.write("out/edges.png")

val fallbackBytes: Option[Array[Byte]] =
  if written.isRight then
    source.close()                     // the file is on disk; release the spare we were holding
    None
  else source.bytes(".png").toOption   // the write failed — `source` is untouched, so encode in memory
```
:::

:::warning[A value that never reaches a terminal leaks]
An `Image` you build but never `write`, `bytes`, `close`, or hand off via `managed` holds a native Mat that
the garbage collector will not free promptly. If the body of your work does not end in a terminal, wrap it
in [`Image.reading`](#scoping-with-reading), which closes for you.
:::

## Getting an Image

There are several ways in, depending on where the pixels live:

| Constructor | Signature | Use it for |
|---|---|---|
| `Image.read` | `(path, flags): Either[CvError, Image]` | a file on disk |
| `Image.decode` | `(bytes, flags): Either[CvError, Image]` | an in-memory image file (HTTP body, BLOB) |
| `Image.blank` | `(width, height, color, channels): Image` | a fresh canvas to draw on |
| `Image.wrap` | `(managed): Image` | adopt a `Managed[Mat]` you already hold |
| `Image.fromBufferedImage` | `(bufferedImage): Image` | a frame from AWT/Swing/`ImageIO` |

```scala mdoc:compile-only
Image.read("photo.jpg")            // Either[CvError, Image] from a file
Image.decode(bytesFromSomewhere)   // from an in-memory image file (HTTP body, BLOB)
```

```scala mdoc:silent
Image.blank(width = 320, height = 240)                  // a black canvas
Image.blank(64, 64, color = Scalar.White, channels = 1) // a white 1-channel canvas
```

Both `read` and `decode` return `Either` because the outside world is where things go wrong — a missing
path, a directory, bytes that are not an image. OpenCV reports all three the same unhelpful way (an empty
Mat, plus a warning to stderr); scalacv flattens that into a single `CvError.DecodeFailed`. See
[/image-io](/image-io) for the full story.

### Reading options — `ImreadFlags`

`read` and `decode` take an `ImreadFlags`, which is a **total model, not a bitmask** — the (colour, scale)
pair maps onto exactly one OpenCV constant, so you cannot accidentally OR two flags into a third meaning.

| You want | Pass |
|---|---|
| Colour (the default) | `ImreadFlags.Color` |
| Greyscale, decoded straight to 1 channel | `ImreadFlags.Grayscale` |
| Original channels, alpha and all | `ImreadFlags.Unchanged` |
| A cheap half/quarter/eighth-size decode | `ImreadFlags(ImreadColor.Color, ImreadScale.Half)` |
| Ignore the EXIF rotation tag | `ImreadFlags(ImreadColor.Color, ignoreOrientation = true)` |

```scala mdoc:compile-only
Image.read("scan.jpg", ImreadFlags.Grayscale)                              // 1-channel on load
Image.read("huge.png", ImreadFlags(ImreadColor.Color, ImreadScale.Half))   // decode at 50%
Image.read("photo.jpg", ImreadFlags(ImreadColor.Color, ignoreOrientation = true))
```

:::tip[Reduced-size decode beats read-then-resize]
A reduced-size decode (`ImreadScale.Half` and friends) is cheaper than a full read followed by
`resize`, because the codec skips the discarded detail rather than producing every pixel and throwing most
away. Reach for it when you only need a thumbnail. Only `Grayscale` and `Color` support it — the type
enforces that.
:::

## Transforming

The common image-processing steps read as verbs. Each is an ordinary transform: it consumes the receiver
and returns a new `Image`.

```scala mdoc:silent
scene()
  .gray                         // BGR -> single-channel grey
  .equalizeHist                 // stretch the histogram
  .blur(2)                      // quick radius-based Gaussian blur (radius 2 = 5x5)
  .canny(80, 160)               // edges, always CV_8UC1
  .close()
```

:::note[Even a no-op transform spends the image]
A few transforms have a parameter value that means "change nothing": `blur(0)`, `scale(1.0)`, `adjust()` left
at its defaults. They are no-ops on the *pixels* — and they **still consume the receiver**. `blur(0)`, for
instance, moves the Mat straight into a fresh `Image` instead of copying it, so not a pixel is touched, yet
the handle you called it on is spent exactly as if you had blurred.

That makes "pass a neutral value to skip the step" a trap: it reads as if nothing happened, and it does not
generalise — `medianBlur` requires `radius >= 1`, so it has no neutral value to pass at all. Branch *around*
the step instead. Both paths then have the same shape, one live `Image` in and one live `Image` out:

```scala mdoc:compile-only
def maybeBlur(img: Image, radius: Int): Image =
  if radius > 0 then img.blur(radius) else img
```
:::

Resizing and cropping:

```scala mdoc:silent
scene().resize(80, 60).close()                 // absolute size
scene().scale(0.5).close()                     // half on both axes
scene().crop(Rect(10, 10, 60, 60)).close()     // an independent copy of a region
```

:::note[`crop` is a copy, not a view]
`crop` returns an independent image, not an aliasing window into the parent's pixels. That means the crop
outlives the parent safely, and writing to one never disturbs the other. The rectangle must lie fully
inside the image, or the call throws `IllegalArgumentException` up front.
:::

### The full verb set

Beyond the basics above, `Image` covers the everyday OpenCV toolkit — each an ordinary transform that
consumes the image and hands on a new one:

| Group | Verbs |
|---|---|
| **Geometric** | `flip`, `rotate` (quarter-turns and arbitrary angle, auto-expanding), `pad`, `border`, `crop`, `resize`, `resizeTo`, `scale`, `undistort` |
| **Smoothing** | `blur`, `gaussianBlur`, `medianBlur`, `bilateralFilter` |
| **Edges & threshold** | `canny`, `threshold`, `adaptiveThreshold`, `equalizeHist` |
| **Morphology** | `erode`, `dilate`, `morphology(MorphOp.Open / Close / Gradient / TopHat / BlackHat)` |
| **Intensity & colour** | `adjust` (brightness/contrast), `invert`, `normalize`, `sharpen`, `gamma`, `convert`, `gray`, `toHsv`, `channel`, `colorMap`, `saturate`, `temperature` |
| **Stylisation** | `stylize`, `sketch`, `enhance`, `edgePreserving`, `sepia`, `emboss`, `posterize`, `filter` |
| **Masking & compositing** | `inRange` (→ mask), `applyMask`, `blend`, `inpaint`, `seamlessCloneInto` |
| **OCR prep** | `deskew`, `adaptiveThreshold` |

```scala mdoc:silent
scene()
  .rotate(Rotation.Clockwise)     // lossless quarter-turn
  .medianBlur(1)                  // de-noise
  .adjust(brightness = 20)        // a touch brighter
  .morphology(MorphOp.Open)       // clean up small specks
  .bytes(".png")
```

An arbitrary-angle rotation expands the canvas so no corner is clipped. The angle is measured
**counter-clockwise**, which is OpenCV's own `warpAffine` convention — so `rotate(90.0)` and
`rotate(Rotation.CounterClockwise)` produce the same image:

```scala mdoc:silent
scene().rotate(degrees = 30, scale = 1.0).close()   // 30° anti-clockwise; canvas grows to fit
```

:::tip[Name your thresholds]
`canny(threshold1, threshold2)` takes two doubles in a fixed order, and swapping them silently changes the
result. When the numbers are not obviously ordered, name them — `canny(threshold1 = 80, threshold2 = 160)`.
The same advice applies to `adaptiveThreshold(blockSize = 15, c = 4)`.
:::

The dedicated guides go deeper: [Geometric transforms & morphology](/transforms),
[Colour, masking & compositing](/color-masking), and [Image processing](/image-processing) for the
mid-level `Managed[Mat]` equivalents.

### Named filters

A [`Filter`](/color-masking) is a named, composable `Image => Image` transform — the ready-made "looks"
built from the tone and stylisation verbs above. Apply one with `filter`, compose with `andThen`, or name
your own:

```scala mdoc:silent
scene().filter(Filter.vintage).close()                       // a built-in look
scene().filter(Filter.warm.andThen(Filter.sharpen)).close()  // composed
scene().filter(Filter("mine")(_.gamma(1.2).saturate(1.3))).close() // your own
```

The catalog — `Filter.all` enumerates every one, handy for a contact sheet:

| | | | |
|---|---|---|---|
| `grayscale` | `sepia` | `invert` | `warm` |
| `cool` | `vivid` | `muted` | `noir` |
| `vintage` | `cartoon` | `sketch` | `posterize` |
| `emboss` | `softBlur` | `sharpen` | `heatmap` |
| `dramatic` | | | |

## Detecting

The self-contained detectors need nothing from you — they build and free their own machinery:

```scala mdoc
scene().qrCodes.size          // Seq[QrCode]
```

`arucoMarkers(dictionary)` and `contours(...)` work the same way. `contours` is a *query*, so the image
stays alive and you can draw the contours straight back onto it:

```scala mdoc:silent
val binary = scene().gray.threshold(128)   // a binary image
val found  = binary.contours()             // query: borrows, `binary` still alive
val drawn  = binary.drawContours(found, Scalar.Red, Thickness.Filled).bytes(".png")
```

```scala mdoc
found.size
```

Faces need a model you supply, because YuNet is a downloaded network — see
[Object detection](/object-detection):

```scala mdoc:compile-only
Image.reading("crowd.jpg") { img =>
  img.faces(detector) // detector: FaceDetectorYN, from FaceDetect.create(...)
}
```

## Annotating

Draw methods are transforms — they mutate the image you own and hand it on. Coordinates that run off the
edge are clipped, not rejected, so drawing a detection near the border is always safe.

```scala mdoc:silent
val annotated: Either[CvError, Array[Byte]] =
  scene()
    .drawRect(Rect(20, 20, 70, 80), Scalar.Green)
    .drawCircle(Point(125, 60), 24, Scalar.Red)
    .drawText("scene", Point(8, 16), Scalar.White)
    .bytes(".png")
```

Two common patterns — a batch of boxes in one pass, and filling a shape as a solid block:

```scala mdoc:silent
val boxes = Seq(Rect(20, 20, 70, 80), Rect(101, 36, 48, 48))
scene()
  .drawRects(boxes, Scalar.Green)                        // one call, many rectangles
  .drawRect(Rect(0, 0, 40, 18), Scalar.Black, Thickness.Filled) // a solid label background
  .drawText("2 objects", Point(2, 14), Scalar.White)
  .close()
```

:::note[Text is anchored on its baseline]
`drawText`'s point is the *left end of the baseline*, not the top-left corner — a `y` of `0` draws the
whole string above the image and shows nothing. Use `Draw.textSize(...)` to measure a string first when you
need to place or box it. Only the built-in Hershey vector fonts exist; non-ASCII characters render as `?`.
:::

| Draw verb | Shape | Fillable? | Knobs on `Image` | Knobs only on `img.mat` |
|---|---|---|---|---|
| `drawRect`, `drawRects` | rectangle(s) | yes (`Thickness.Filled`) | `color`, `thickness` | `lineType` |
| `drawCircle` | circle | yes | `color`, `thickness` | `lineType` |
| `drawContours` | contours from `findContours` | yes — the usual way back to a mask | `color`, `thickness` | `lineType` |
| `drawText` | Hershey text | no (stroke only) | `color`, `scale` | `font`, `thickness`, `lineType` |

The last column is not a gap to apologise for: `Image` deliberately carries the knobs you chain every day and
leaves the rest to the mid-level tier. When you do want an anti-aliased edge, a heavier stroke on text, or a
font other than `Simplex`, borrow the Mat and draw through it — `img.mat.drawText(...)` mutates in place and
does **not** consume the `Image`, so the chain carries on afterwards. See [Drawing](/drawing).

`markFaces(faces)` is the one-call "show me what YuNet found" — a box per face and a dot per landmark.

### Writing your own overlay

Domain overlays like `drawSkeleton`, `drawTracks`, and `drawMarkerAxes` are extension methods in their own
modules (`scalacv-vision`), built on an internal helper called `paint`. `paint` is `private[scalacv]`, so it
is not available to you — the visibility exists so the library's own domain files can live outside the
`Image` class, not as a public extension point.

**To write your own overlay, borrow the Mat instead.** The mid-level draw ops mutate the Mat in place, and
`img.mat` is a *borrow*, so the `Image` is never consumed: return it unchanged and let the caller's next verb
consume it, exactly as a built-in draw verb would.

```scala mdoc:silent
extension (img: Image)
  def drawCrosshair(at: Point, color: Scalar = Scalar.Red): Image =
    img.mat.drawLine(Point(at.x - 8, at.y), Point(at.x + 8, at.y), color)
    img.mat.drawLine(Point(at.x, at.y - 8), Point(at.x, at.y + 8), color)
    img
```

That is the same thing `paint` does, minus the take-and-rewrap step: `paint` exists so a verb can move the
Mat out of one handle and into a new one without copying, which matters only when the verb is written inside
the library. From outside, borrowing gets you there with less ceremony.

```scala mdoc:silent
scene().drawCrosshair(Point(80, 60)).close()
```

## Masking & compositing

`inRange` turns a colour range into a binary mask; `applyMask` keeps only the pixels the mask marks. The
mask is *borrowed* — `applyMask` does not consume it, so close it yourself.

```scala mdoc:silent
val src        = scene()
val mask       = src.copy.toHsv.inRange(Scalar(0, 0, 200), Scalar(180, 40, 255)) // bright pixels
val onlyBright = src.applyMask(mask).bytes(".png")  // `src` consumed, `mask` borrowed
mask.close()                                        // the borrowed mask is ours to free
```

`blend` mixes two images of the same size and type. It computes

```text
result = this * weight + other * (1 - weight)
```

so `weight` is **this image's** share, not `other`'s — `scene().blend(overlay, 0.7)` keeps 70% of the scene
and 30% of the overlay. It defaults to `0.5`, an even mix, and a value outside `[0, 1]` is rejected up front
with an `IllegalArgumentException`.

```scala mdoc:silent
val overlay   = Image.blank(160, 120, Scalar.White)                 // same size and type as scene()
val washedOut = scene().blend(overlay, weight = 0.7).bytes(".png")  // 70% scene, 30% white
overlay.close()                                                     // borrowed, so ours to free
```

:::warning[A borrowed mask is yours to close]
`applyMask`, `inpaint`, `blend`, and `seamlessCloneInto` consume the **receiver** but only *borrow* the
mask/other image you pass in. Whatever you passed is still live afterwards — `close()` it, or it leaks. The
[Colour & masking](/color-masking) guide walks through the full segmentation workflow.
:::

## Scoping with `reading`

If the body of your work is a **query** (no terminal to release the image), `Image.reading` closes it for
you — even on an exception, and harmlessly even if the body already consumed it:

```scala mdoc:compile-only
val faceCount: Either[CvError, Int] =
  Image.reading("crowd.jpg")(_.faces(detector).size)
```

`reading` runs the whole body inside [`Cv.attempt`](#handling-errors), so a `CvError.NativeCall` thrown by a
transform in the chain comes back as a `Left` rather than escaping — the `Either` is honest about failure,
not just about the read. Because `Image` is `AutoCloseable`, `scala.util.Using` works too; `reading` is the
tidier spelling for the read-and-scope case.

`reading` takes the same optional [`ImreadFlags`](#reading-options--imreadflags) that `read` does — the full
signature is `reading(path, flags)(use)`. So you do not have to give up scoping to get a cheap decode: a
scoped, greyscale, quarter-resolution read is one extra argument, not a fall back to `Image.read` plus a
hand-written `close()`.

```scala mdoc:compile-only
val thumbFlags = ImreadFlags(ImreadColor.Grayscale, ImreadScale.Quarter) // 1 channel, quarter size

val crowdFaces: Either[CvError, Int] =
  Image.reading("crowd.jpg", thumbFlags)(_.faces(detector).size)
```

## Branching with `copy`

Move semantics forbid using one image twice — so when you genuinely need to, take an independent deep copy:

```scala mdoc:silent
val base   = scene()
val branch = base.copy
val a = base.gray.bytes(".png")             // consumes base
val b = branch.canny(80, 160).bytes(".png") // consumes the copy
```

:::tip[`copy` is the one deliberate pixel copy]
Every other transform threads one live Mat through the chain with no copying. `copy` is where you opt into a
second buffer on purpose, precisely because you want two independent lifetimes. If you find yourself copying
inside a per-frame video loop, that is a signal to restructure — see [/performance](/performance).
:::

## Handling errors

Three things fail in different ways, and knowing which is which saves a lot of confusion:

| Situation | How it surfaces |
|---|---|
| Boundary I/O (`read`, `decode`, `write`, `bytes`) | an `Either[CvError, …]` — a value you must handle |
| A transform OpenCV rejects at runtime (bad pixels) | throws `CvError.NativeCall` (unchecked), naming the op |
| An argument mistake this library can see up front | throws `IllegalArgumentException` |
| Reusing a consumed handle | throws `IllegalStateException` |

Transforms deliberately do **not** return `Either` — a chain of twenty `Either`s would be unreadable, and
the data-dependent failures are rare. When you do want a transform's throw folded into a value, wrap the
chain in `Cv.attempt`:

```scala mdoc:silent
val measured: Either[CvError, Int] =
  Cv.attempt("measure"):
    val g = scene().gray.canny(80, 160)
    try g.width finally g.close()
```

The full taxonomy of `CvError` and the reasoning behind this split live in [/error-model](/error-model).

## Interop with AWT & notebooks

`toBufferedImage` copies the image into a `java.awt.image.BufferedImage` for Swing, `ImageIO`, or a Jupyter
notebook (Almond renders a `BufferedImage` automatically). It borrows the image, which stays alive.
`Image.fromBufferedImage` is the reverse, always producing a 3-channel BGR image.

```scala mdoc:silent
val buffered = scene().toBufferedImage        // java.awt.image.BufferedImage, a copy of the pixels
val roundTrip = Image.fromBufferedImage(buffered)
roundTrip.close()
```

```scala mdoc
roundTrip.toString  // "Image(<closed>)" — a spent handle is safe to print
```

See [/notebooks](/notebooks) for using this in an interactive session.

## Dropping to the low level

`Image` is a convenience, never a wall. `mat` borrows the raw `org.opencv.core.Mat` for any `org.opencv.*`
call or mid-level extension op that `Image` does not wrap; the image stays yours:

```scala mdoc:silent
val img2 = scene()

// A raw org.opencv.* call on the borrowed Mat — the image still owns it.
val mean = org.opencv.core.Core.mean(img2.mat)

// Or a mid-level extension op, which returns an owned Managed[Mat]:
val sharpenedBytes: Either[CvError, Array[Byte]] =
  img2.mat.gaussianBlur(Size(3, 3)).use(Images.encode(_, ".png"))

img2.close()
```

And `managed` hands the whole `Managed[Mat]` over when you want to manage the lifetime directly — this is a
*terminal*, so it spends the `Image`:

```scala mdoc:silent
val handle: Managed[Mat] = scene().managed  // ownership transfers to `handle`
handle.release()                            // now it is ours to free
```

See [Working with the raw OpenCV API](/low-level) for the full story on moving between the two levels, and
[/mat-lifecycle](/mat-lifecycle) for how `Managed` guarantees release-exactly-once underneath it all.

## Next

- [Mat lifecycle & `Managed`](/mat-lifecycle) — the ownership machinery `Image` is built on.
- [Transforms](/transforms) — the geometric and morphology verbs in depth.
- [Colour, masking & compositing](/color-masking) — segmentation, filters, and blending end to end.
