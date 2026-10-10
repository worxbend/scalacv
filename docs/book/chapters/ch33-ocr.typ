#import "../lib/book.typ": *

#chapter("OCR Preprocessing", subtitle: [The library reads no characters. It decides whether the engine can.])

Every other chapter in this part ends with an answer: a box, a pose, a marker's rotation. This one
ends with a picture. scalacv does not recognise text and has no intention of learning how ---
recognition is a mature, well-served problem, and the engines that solve it are large native
projects with their own licences, their own language data and their own release cadence. Tesseract
ships a multi-megabyte `traineddata` file per language and its own natives underneath. A cloud OCR is
an HTTP call with a bill attached. Neither belongs inside a thin wrapper over OpenCV.

What the wrapper can do is the part that actually decides the outcome. Take a page of printed text,
scan it flat at a sensible resolution, and any modern engine reads it essentially perfectly.
Photograph the same page on a table, at a slight angle, with a window on one side, and the same
engine returns a mess: dropped words, `rn` where the page said `m`, a column of prices read as a
column of dates. Nothing changed about the characters. Everything changed about the image.

That asymmetry is why this chapter exists. The work in front of the engine --- greyscale, denoise,
binarise, straighten, crop, resample --- is ordinary image processing, all of it covered in Part II,
and it is where your accuracy is won or lost. scalacv owns that half, defines the contract for the
other half, and is explicit about the seam between them.

The running example is a supermarket receipt photographed on a desk: thermal paper, slightly curled,
lit from a lamp on the left so the right-hand edge falls into shadow, held at about five degrees off
level because nobody lines up a phone with a table edge. It is the worst realistic input and the
most common one.

One line of build setup first. `Ocr`, `OcrEngine`, `OcrResult`, `OcrWord` and the `forOcr` extension
live in `scalacv-vision`, beside the detectors of the last ten chapters, so
`mvn"com.worxbend::scalacv-vision:0.4.1"` has to be on the classpath next to the core dependency of
Chapter 2. Everything `forOcr` composes --- `gray`, `medianBlur`, `adaptiveThreshold`, `deskew` ---
and everything the pipelines below add to it --- `scale`, `crop`, `invert`, `morphology`, `contours`
--- is core, in the plain `scalacv` artifact underneath. Use `import scalacv.*` for core and
`import scalacv.vision.*` for OCR; nothing in this chapter needs `scalacv-graphs`.

#sect("The seam")

Three plain data types and one trait carry the whole design. `OcrEngine` has a single method, and
that method borrows.

#example("The entire recognition contract.")[
```scala
trait OcrEngine:
  def recognize(image: Image): OcrResult
```
]

`Ocr.read` sits in front of it and does the preparation:

#example("Preparation, then delegation.")[
```scala
def read(image: Image, engine: OcrEngine, preprocess: Boolean = true): OcrResult =
  if preprocess then
    val prepared = image.copy.forOcr()
    try engine.recognize(prepared)
    finally prepared.close()
  else engine.recognize(image)
```
]

Read that body as an ownership statement rather than as code. `image.copy` clones the pixels, so the
image you passed in is untouched and still yours. `forOcr()` consumes the clone --- every step in it
is a transform, and transforms consume (Chapter 4) --- leaving exactly one live handle. The
`finally` frees it whether the engine returns, throws, or hangs up on a socket. Your original is
never closed by this call, and never modified by it.

#memory[
  `Ocr.read` borrows `image` and frees only the copy it made. The image you handed it is still alive
  when the call returns and is still yours to close. The one-line summary of the whole library
  applies unchanged here: whoever created the handle releases it.
]

#figure-table("What comes back, and what each piece is for.")[
#tbl(
  columns: (1.35fr, 2.4fr),
  [Type or member], [What it holds],
  [`OcrResult(text, words)`], [The recognised `text`, plus per-word detail when the engine bothers
   to supply it. `words` defaults to `Seq.empty`, so an engine that only produces a blob of text is
   a legal engine.],
  [`OcrResult.isEmpty`], [`true` when `text.trim` is blank --- the difference between "read nothing"
   and "read something dubious".],
  [`OcrResult.confident(minConfidence: Float = 0.5f)`], [The words at or above a confidence. How you
   quarantine junk without throwing the page away.],
  [`OcrWord(text, confidence, box)`], [One word: its text, the engine's score, and the bounding
   `Rect` it occupied.],
)
]

All four are immutable Scala values with no native handle behind them, so they outlive the image
they describe: an `OcrResult` can be returned from inside `Image.reading`, put on a queue, and
inspected an hour later with nothing kept alive off-heap.

The `box` on each `OcrWord` is the one field with a trap in it. It is in the coordinate system of
the image the engine actually saw --- which, if you cropped, upscaled or deskewed first, is not the
photograph you started from. Nothing in the library maps it back. If you intend to draw the boxes
over the original, keep the crop rectangle and the scale factor and undo them yourself.

Under `Ocr.read`'s default the damage is smaller than it looks, and it is worth knowing exactly how
small. `forOcr` composes `gray`, `medianBlur`, `adaptiveThreshold` and `deskew`, and not one of
those four changes the frame size --- `deskew` in particular rotates into a destination the same
size as its source. So a box the engine reports under `Ocr.read(image, engine)` is already in your
image's pixel dimensions, and the only thing between it and your photograph is a rotation about the
centre by the skew `deskew` measured. Under a hand-built pipeline that crops or scales, all bets are
off, and the mapping is yours to carry.

Results survive the images, so they are the natural thing to inspect and filter after the fact.

#example("Keeping what the engine was sure about, and looking at what it was not.")[
```scala
def readPage(photo: Image, engine: OcrEngine): Option[String] =
  val result = Ocr.read(photo, engine)      // borrows photo; photo is still yours
  if result.isEmpty then None
  else if result.words.isEmpty then Some(result.text)   // an engine that only returns a blob
  else
    result.words
      .filter(_.confidence < 0.6f)
      .foreach(w => println(s"review '${w.text}' at ${w.box}, scored ${w.confidence}"))
    Some(result.confident(0.6f).map(_.text).mkString(" "))
```
]

`confident` defaults to `0.5f`, which is a reasonable floor for a page you are going to read
yourself and much too low for one that feeds a database. The three-branch shape above is what makes
the function honest about engines: `isEmpty` separates a blank page from a hard one, and the `words`
check keeps an engine that reports no per-word detail --- a legal engine, since `words` defaults to
`Seq.empty` --- from silently returning an empty string.

#sect("What `forOcr` does, one step at a time")

`forOcr` is a composition of four operations you already know, and its whole body fits in four lines:

#example("The OpenCV half of OCR, in full.")[
```scala
def forOcr(denoise: Int = 1, blockSize: Int = 15, c: Double = 10): Image =
  val gray = if img.channels >= 3 then img.gray else img
  val cleaned = if denoise > 0 then gray.medianBlur(denoise) else gray
  cleaned.adaptiveThreshold(blockSize = blockSize, c = c).deskew()
```
]

It is an extension method on `Image`, not a member of it --- OCR preparation is a domain verb, and
domain verbs live beside their domain. `import scalacv.vision.*` brings in this extension.

Each step earns its place, and each has a failure it is there to prevent.

#figure-table("The four steps, and what goes wrong without each.")[
#tbl(
  columns: (0.8fr, 1.2fr, 2fr),
  [Step], [Method], [What it prevents],
  [Greyscale], [`gray`], [Colour carries no information a recogniser uses, and costs three times the
   memory and bandwidth to carry it. The step is skipped when the image already has one channel, so
   `forOcr` on an already-grey scan does not waste a conversion.],
  [Denoise], [`medianBlur(denoise)`], [A median blur is the specific cure for salt-and-pepper
   speckle: it replaces a pixel with a neighbourhood median, so an isolated black dot vanishes
   instead of being smeared into a grey smudge the way a Gaussian would. Without it, thresholding
   promotes every sensor speck to ink.],
  [Binarise], [`adaptiveThreshold(blockSize, c)`], [A per-neighbourhood cutoff, which is what makes
   a lamp on one side survivable. A single global threshold has to choose between losing the shadowed
   edge and flooding the lit one.],
  [Deskew], [`deskew()`], [Tilt. See the next section --- it is the step people skip and the one that
   costs the most.],
)
]

The binarisation is the step readers most often get wrong before they find `forOcr`, and the wrong
version is worth seeing. On the receipt, with the lamp on the left:

```scala
photo.gray.threshold(128)   // one cutoff for the whole frame
```

The lit half of the paper sits well above 128 and comes out clean. The shadowed right-hand quarter
sits below it, so the paper itself is classified as ink and the last two characters of every price
disappear into a solid black block. The engine reads a truncated total with no error and no warning.
The adaptive version compares each pixel against a weighted average of its own
`blockSize` × `blockSize` neighbourhood, minus `c`, so a uniformly darker region is still mostly
paper:

```scala
photo.gray.adaptiveThreshold(blockSize = 31, c = 12)
```

The weighting is `AdaptiveMethod.Gaussian` by default, which is what `forOcr` gets --- a
Gaussian-weighted neighbourhood, softer at the edges than the flat `AdaptiveMethod.Mean` and slightly
more tolerant of a stroke sitting near the edge of the window. `Image.adaptiveThreshold` also takes
`method` and `mode` (`mode = Threshold.Mode.BinaryInv` gives white ink on a black page, which is what
`contours` and the morphology below want); `forOcr` exposes neither, so a pipeline that needs them is a
hand-built one.

#figure-table("The three knobs on `forOcr`, and which way to turn them.")[
#tbl(
  columns: (0.7fr, 0.45fr, 2.3fr),
  [Parameter], [Default], [What it does, and when to move it],
  [`denoise`], [`1`], [Median-blur radius, where `1` means a 3×3 kernel; `0` skips the step
   entirely. Raise it for a noisy phone photo at high ISO. Set it to `0` for a rendered PDF page or
   a clean flatbed scan, where there is no speckle to remove and the blur only softens the strokes.],
  [`blockSize`], [`15`], [The adaptive neighbourhood, which must be odd and at least 3 --- anything
   else is rejected with an `IllegalArgumentException` before OpenCV sees it. It wants to be
   comfortably larger than a stroke and smaller than a lighting gradient. Raise it for large text,
   lower it for small dense text.],
  [`c`], [`10`], [The bias subtracted from the weighted neighbourhood average. Raise it to keep
   less ink when letters blob together; lower it when thin strokes vanish. It is the knob to reach
   for first,
   because it is the cheapest to test.],
)
]

#sect("Deskew, and what two degrees costs")

Tilt is the failure mode that surprises people, because a page two degrees off level still looks
straight. To a recogniser it is not. Layout analysis begins by finding rows of text, and it finds
them by projecting ink onto the vertical axis and looking for the gaps. Over a 1500-pixel-wide
receipt, two degrees of tilt drags the right-hand end of a line `1500 * tan(2 deg)` below its
left-hand end --- about fifty pixels, several times the gap between lines. The projection that
should have shown five sharp peaks separated by white shows one continuous smear, the line finder
gives up or guesses, and characters from two rows get assembled into words that were never
printed.

`deskew` is the correction, and its implementation is short enough to describe exactly. It
binarises the image with an inverted Otsu threshold, so the ink becomes the white foreground and the
page becomes black. It calls `Core.findNonZero` to collect every ink pixel, converts that cloud to
`CV_32F` points, fits a minimum-area rectangle to it, and takes the rectangle's angle. That angle is
folded into the range from just above --45 to 45 degrees, and the image is rotated by it about its
centre with `warpAffine`, filling the exposed corners white.

Two guards make it safe to call unconditionally. A page with no ink --- `findNonZero` returning
zero rows --- is cloned through untouched. A measured skew below 0.1 degrees, or above `maxAngle`
(default `45.0`), is also left alone: a page dominated by a large graphic can fool the estimate
badly, and rotating by a wrong guess is worse than not rotating at all. If you know your scans are
never more than a few degrees out, say so:

```scala
scan.deskew(maxAngle = 8.0)   // anything larger is a misread, not a tilt
```

`maxAngle` is itself checked: it must lie in `(0, 90]`, and a zero or negative value is an
`IllegalArgumentException` rather than a silently disabled correction.

Two properties of the rotation matter when you compose it with anything else.

The frame size is preserved. `deskew` rotates into a destination the same size as the source, so the
corners of a strongly tilted page swing outside the frame and are clipped. This is deliberate --- a
deskewed page that changes dimensions breaks every downstream coordinate --- but it means a
ten-degree correction on a page whose text reaches the margins will shave the corners. `rotate`, the
general-purpose transform from Chapter 10, does the opposite: it widens the destination to the
rotated bounding box so nothing is lost. If your text lives near the edges, pad before you deskew.

#warning[
  Pad with white, not with the default. `pad(size)` and `border(...)` fill with `Scalar.Black` unless
  you say otherwise, and `deskew` binarises with an *inverted* Otsu threshold --- so a black border
  is not neutral background to it, it is a thick rectangular blob of ink around the page. The
  minimum-area rectangle then fits that border rather than the text, and the measured skew is
  whatever the border happens to be, usually zero. Write `page.pad(60, color = Scalar.White)` and the
  estimate is undisturbed.
]

The rotation resamples with bilinear interpolation. That is the right choice for a photograph and a
slightly awkward one for a binary image: `forOcr` binarises and *then* deskews, so the two-valued
image it produces comes back with soft grey edges on every stroke. Engines cope with this
comfortably --- most re-binarise internally anyway --- but if you need a strictly two-valued result,
swap the order and threshold last.

#example("Deskew first, binarise second, when the output must stay two-valued.")[
```scala
photo.gray
  .medianBlur(1)
  .deskew(maxAngle = 15.0)               // resamples greys, which is fine: they are greys
  .adaptiveThreshold(blockSize = 31, c = 12)
```
]

#sidebar("Two ways to measure a tilt")[
  `deskew` fits a minimum-area rectangle to the ink. It is fast, needs no parameters, and works on
  anything with a dominant text direction --- but it measures the *shape of the ink cloud*, so a
  page whose text block happens to be taller than it is wide, or one with a large logo in a corner,
  can pull the estimate off.

  The other classical estimator uses the line structure directly, with the Hough transform from
  Chapter 13. Detect edges, take the long near-horizontal segments, and use their median angle:

```scala
val edges = photo.copy.gray.canny(80, 160)
val angles = edges.mat
  .houghLinesP(threshold = 80, minLineLength = photo.width / 4.0, maxLineGap = 20)
  .map(s => math.toDegrees(math.atan2((s.y2 - s.y1).toDouble, (s.x2 - s.x1).toDouble)))
  .filter(a => math.abs(a) < 20)
edges.close()
val tilt = if angles.isEmpty then 0.0 else angles.sorted.apply(angles.size / 2)
```

  A median is the right statistic here: one spurious segment along the edge of the desk cannot move
  it. Feed `tilt` to `rotate`, and check the sign against one sample page before you trust it ---
  OpenCV's positive angle is counter-clockwise.

  This is more robust on documents with ruled lines, tables or long underscores, and more expensive
  and more parameterised on everything else. `deskew` is the default because it has no knobs to get
  wrong. Reach for Hough when the default visibly misjudges your particular pages.
]

#sect("Cropping to the text")

An engine given a photograph of a receipt on a desk spends its layout analysis on the desk. Crop
first, and both the accuracy and the runtime improve.

The text block is findable with the contour machinery from Chapter 12. Binarise, then use a
morphological closing to weld neighbouring characters into solid blobs --- a closing dilates then
erodes, so it fills the gaps between letters without growing the outline overall --- then take the
union of the bounding boxes that are large enough to be text.

#example("Finding the ink, and nothing but the ink.")[
```scala
def textBlock(prepared: Image, minArea: Long = 40): Option[Rect] =
  // adaptiveThreshold leaves ink black on white; contours want the ink to be the foreground.
  val blobs = prepared.copy.invert.morphology(MorphOp.Close, radius = 4)
  try
    val boxes = blobs.contours().map(_.boundingRect).filter(_.area > minArea)
    if boxes.isEmpty then None
    else
      val x0 = boxes.map(_.x).min
      val y0 = boxes.map(_.y).min
      val x1 = boxes.map(b => b.x + b.width).max
      val y1 = boxes.map(b => b.y + b.height).max
      Some(Rect(x0, y0, x1 - x0, y1 - y0))
  finally blobs.close()
```
]

`prepared.copy` is there because `invert` and `morphology` are both transforms and both consume;
without it, asking where the text is would destroy the image you were about to recognise.
`contours` is a query, so it borrows `blobs` and leaves it alive for the `finally`.

Give the crop a margin. Engines want white space around the glyphs, and a box that clips the
descenders of the last line reads worse than one that includes a strip of desk. `crop` refuses a
rectangle that does not fit inside the image --- an `IllegalArgumentException` naming the rectangle
and the dimensions --- so clamp rather than hope:

#example("Padding a region of interest without walking off the image.")[
```scala
def padded(r: Rect, by: Int, w: Int, h: Int): Rect =
  val x = math.max(0, r.x - by)
  val y = math.max(0, r.y - by)
  Rect(x, y, math.min(w - x, r.width + 2 * by), math.min(h - y, r.height + 2 * by))
```
]

#sect("Resolution, and the order of operations")

The engines are conventionally trained on pages scanned at roughly 300 dpi, which puts ordinary
body text around twenty pixels tall --- a rule of thumb to plan against, not a number scalacv
measures. A receipt photographed from a phone at arm's length may give you eight. Somewhere around
ten, accuracy starts falling away in a manner no amount of thresholding recovers, because the
information is not in the file: a stroke thinner than a pixel was never sampled.

Upscaling does not add information either, but it does give the engine's own filters something to
work with, and it reliably helps. `scale` with cubic interpolation is the tool:

```scala
photo.gray.scale(2.0, Interpolation.Cubic)
```

Do it on the greyscale image, before binarising. Interpolating a two-valued image produces grey
edges that then have to be thresholded a second time, and each round trip thickens the strokes a
little more; interpolating continuous tone and thresholding once does not. This is the second reason
to assemble a pipeline by hand rather than call `forOcr` --- the composition it hard-codes is a good
default, not a law.

#tip[
  Measure before you scale. Run `textBlock` on a prepared page, divide the block's height by the
  number of lines you expect, and you have an estimate of the line height in pixels. Scale to bring
  that to twenty, and no further --- upscaling past the point where it helps only makes the engine
  slower.
]

#sect("Tables, columns, and text that is not level")

Three layouts break the single-block assumption, and each has a preprocessing answer.

*Columns.* Two columns of text produce, after a closing, two clusters of bounding boxes with a
vertical gap between them: group the boxes `textBlock` collects by their `x`, find the widest empty
vertical band, and recognise each side as its own image. Engines attempt column detection
themselves, but on the assumption of a clean scan; handing them one column at a time removes the
guess.

*Tables.* Ruled lines are ink, and they confuse a character recogniser, which sees a horizontal rule
as a very long underscore or a row of hyphens. The classical removal uses a deliberately anisotropic
structuring element --- long and one pixel high --- to isolate the horizontal rules, which are then
subtracted. scalacv's morphology deliberately will not build one. Both `Image.morphology` and the
mid-level `Ops.morphology` derive their structuring element from a single `radius`, always
`2 * radius + 1` on both axes, with `shape` choosing between `MorphShape.Rect`, `Ellipse` and `Cross`
and `Ops.morphology` adding an `iterations` count on top. There is no parameter that can make it
40 wide and 1 tall, because a symmetric element is what nine uses in ten want and an axis-dependent
one is easy to get backwards. For the tenth, borrow the `Mat` and call OpenCV directly.

#example("The escape hatch, for a kernel the high-level API will not build.")[
```scala
import org.opencv.core.{Mat, Size as CvSize}
import org.opencv.imgproc.Imgproc

/** The long horizontal rules of `ink` --- a binary image with the ink white. `ink` is borrowed. */
def horizontalRules(ink: Image): Image =
  // A 40x1 element opens to runs at least forty pixels long and one tall: rules, not glyphs.
  Managed.use(Imgproc.getStructuringElement(MorphShape.Rect.cvValue, CvSize(40, 1))): kernel =>
    val dst = Mat()
    Cv.orThrow("openRules")(Imgproc.morphologyEx(ink.mat, dst, MorphOp.Open.cvValue, kernel))
    Image.wrap(Managed(dst))
```
]

Three ownership moves are packed into those five lines. `Managed.use` adopts the kernel OpenCV
allocated and releases it when the block returns, whatever the block does. `ink.mat` borrows --- it
hands out the underlying `Mat` without spending the `Image`, so the caller's handle is still alive
afterwards. And `Image.wrap(Managed(dst))` takes ownership of the raw `Mat` the call filled in, which
is the only way a `Mat` you allocated yourself becomes an `Image` the rest of the library will free
for you. `Cv.orThrow` is there because `morphologyEx` is a native call like any other: it turns a
raw `CvException` into a `CvError.NativeCall` carrying the operation name --- the same currency of
failure as every other error in the library (Chapter 6) --- and throws that instead.

Subtracting the rules is the other half, and it is a plain absolute difference:

#example("Rules found, rules removed.")[
```scala
def withoutRules(prepared: Image): Image =
  val ink = prepared.copy.invert                        // ink white, page black
  try
    val rules = horizontalRules(ink)                    // borrows ink
    try Image.wrap(ink.mat.absdiff(rules.mat)).invert   // borrows both; back to ink-on-white
    finally rules.close()
  finally ink.close()
```
]

`prepared.copy` again, because `invert` consumes. `absdiff` is an extension on `Mat` returning a
`Managed[Mat]`, so both operands are borrowed and both are still yours to close --- hence the nested
`finally`. The cost of this trick is that it erases the parts of a glyph that sat on the rule: a
descender crossing a table line comes back with a bite out of it. That is almost always a better
trade than leaving the line in, but it is a trade.

*Text that is not level by a quarter turn.* `deskew` folds its measured angle into the range from
--45 to 45 degrees, which is exactly right for a tilt and exactly wrong for a page scanned sideways:
a 90-degree rotation folds to zero, the guard sees a skew below 0.1 degrees, and the page is
returned untouched and still sideways. Quarter turns are yours to detect and yours to fix, with the
lossless `rotate(Rotation.Clockwise)` from Chapter 10. The cheapest detector is the aspect ratio of
the text block: a portrait document whose block is wider than it is tall has been laid on its side.

#sect("Bringing your own engine")

An adapter over Tesseract is about a dozen lines. scalacv has already produced the clean, upright,
binarised image; the adapter's job is to move pixels across a boundary and shape the answer.

#example("A Tesseract binding behind the library's interface. The library ships no such class.")[
```scala
import net.sourceforge.tess4j.Tesseract
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

final class TesseractEngine(dataPath: String, language: String = "eng") extends scalacv.OcrEngine:
  private val tess = new Tesseract()
  tess.setDatapath(dataPath)   // the folder holding eng.traineddata
  tess.setLanguage(language)

  def recognize(image: scalacv.Image): scalacv.OcrResult =
    val png = image.copy.bytes(".png").fold(e => throw e, identity)
    val buffered = ImageIO.read(new ByteArrayInputStream(png))
    scalacv.OcrResult(text = tess.doOCR(buffered).trim)
```
]

#memory[
  `image.copy` in that adapter is load-bearing, not defensive habit. `bytes` is a terminal: it
  encodes and then releases. Written as `image.bytes(".png")`, the adapter would free an image it
  only borrowed. Under `Ocr.read`'s default that damage is contained --- the engine would be
  destroying the internal copy, which was about to be freed anyway. Under `preprocess = false` it is
  your image, and your next use of it throws `IllegalStateException` from a line that has nothing to
  do with OCR. The rule for an `OcrEngine` implementation is one sentence long: the image is
  borrowed, so copy before you consume.

  The engine object has native memory of its own, held inside the recognition library and entirely
  outside `Managed` and `Releasable` (Chapter 5). Nothing in scalacv can free it and nothing will
  warn you. Construct one, keep it for the life of the process, and give it whatever shutdown its
  own API asks for --- building one per page is both a leak and, because the language data is
  reloaded each time, the slowest possible way to call it.
]

The same shape fits anything: a cloud OCR behind an HTTP client, a text-recognition ONNX model
through the `Dnn` layer of Chapter 26. Implement `recognize`, return an `OcrResult`, and if your
engine reports per-word boxes and scores, populate `words` --- callers get `confident` and box
overlays for nothing.

#sect("The receipt, end to end")

Putting it together, with each stage doing one job on the photograph described at the top of the
chapter.

#example("Photograph to clean crop, with the engine at the end.")[
```scala
def prepareReceipt(photo: Image): Image =
  val flat = photo.gray.medianBlur(1).scale(2.0, Interpolation.Cubic)
  val straight = flat.deskew(maxAngle = 15.0)
  val binary = straight.adaptiveThreshold(blockSize = 31, c = 12)
  textBlock(binary) match
    case Some(box) => binary.crop(padded(box, 12, binary.width, binary.height))
    case None      => binary   // no ink found; hand over what we have

Image.reading("receipt.jpg"): photo =>
  val prepared = prepareReceipt(photo.copy)
  try Ocr.read(prepared, engine, preprocess = false).text
  finally prepared.close()
```
]

Two details in that block are about ownership rather than pixels. `photo.copy` keeps the image
`reading` is managing intact, so you can still draw on it or write it out for an audit trail after
recognition. And `preprocess = false` tells `Ocr.read` not to run `forOcr` over work you have
already done --- a second adaptive threshold over a binary image is not merely wasted, it thickens
every stroke.

#figure-table("What each stage fixed, on one photographed receipt.")[
#tbl(
  columns: (0.85fr, 1.5fr, 1.5fr),
  [Stage], [Before], [After],
  [`gray`], [Three channels of a colour cast from a warm desk lamp.], [One channel. Nothing lost
   that a recogniser reads, two thirds of the bytes gone.],
  [`medianBlur(1)`], [Isolated dark pixels from a high-ISO phone sensor, scattered across the
   paper.], [Speckle gone; strokes intact, because a median does not average across an edge.],
  [`scale(2.0, Interpolation.Cubic)`], [Body text about nine pixels tall --- under the floor where
   recognition degrades badly.], [About eighteen pixels, interpolated on continuous tone before any
   thresholding.],
  [`deskew(15.0)`], [Five degrees off level; over the receipt's width, line ends displaced far
   enough to merge rows in a horizontal projection.], [Level to a fraction of a degree. Rows
   separate cleanly. The corners are clipped, which is why the crop comes last.],
  [`adaptiveThreshold(31, 12)`], [A lit left half and a shadowed right edge, with no single cutoff
   that keeps both.], [Two-valued throughout; the shadowed prices survive because their
   neighbourhood is dark too.],
  [`crop(padded(...))`], [A receipt occupying perhaps a third of a frame otherwise full of desk
   grain, which layout analysis would have to reason about.], [Just the paper, with a
   twelve-pixel white margin the engine expects.],
)
]

The stage that most often turns an unusable result into a usable one is the deskew, and it is the
stage with no visible symptom --- the photograph looks fine before it and fine after it. When an
engine returns plausible-looking nonsense, straighten the page first and measure again before you
touch anything else.

#sect("Where this goes next")

Everything here has assumed a still image that someone deliberately captured. Chapter 34 turns the
camera around: screen analysis, where the input is a rendering rather than a photograph and template
matching becomes exact, and the video-conferencing transforms, where the frames arrive at thirty a
second and the preparation budget is measured in milliseconds rather than in what the page deserves.
