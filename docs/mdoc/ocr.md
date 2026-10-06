# Optical character recognition (OCR)

OCR — turning a picture of text back into a `String` — has a counter-intuitive property: **whether it
works is decided almost entirely *before* the recogniser runs**, by how clean and how straight the image
is. A crisp, upright, high-contrast page reads perfectly; the same text photographed at a slight tilt,
with uneven lighting or JPEG mush, reads as garbage. That preprocessing is OpenCV's job, and scalacv's:

```
Image ─▶ grayscale ─▶ denoise ─▶ threshold ─▶ deskew ─▶ [ OCR engine ] ─▶ text
        └──────────────── scalacv (Image.forOcr) ─────────────┘   └ you bring this ┘
```

scalacv owns the pipeline up to the engine and defines the recognition **contract** (`OcrEngine`); the
engine itself — Tesseract, a cloud OCR — stays a dependency *you* add, because it is a heavy,
separately-licensed native library that has no place inside a thin OpenCV wrapper. Wiring one in is a few
lines (below).

:::tip[The shortest possible OCR]
`Ocr.read(image, engine).text` — that one call greyscales, denoises, thresholds and deskews the image for
you, then hands the clean result to your `engine`. The only decision you make is *which* engine.
:::

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*
OpenCv.load()
def scan(): Image =
  val page = Image
    .blank(240, 160, Scalar.White)
    .drawRects(Seq(Rect(30, 30, 180, 10), Rect(30, 60, 180, 10), Rect(30, 90, 180, 10)), Scalar.Black, Thickness.Filled)
  val skewed = Image.wrap(page.mat.rotated(9.0, color = Scalar.White))
  page.close()
  skewed
```

Every runnable example below builds its input with one helper, `scan()`: a white page with three black
"text" bars, rotated 9° so it arrives crooked — a realistic stand-in for a phone photo of a document, with
no fixture file needed. In your own program the page comes from [`Image.read("scan.jpg")`](/image-io).

## Preprocessing with `forOcr`

`Image.forOcr` runs the whole pre-pipeline — grayscale → denoise → adaptive threshold → deskew — and hands
back a clean, upright, single-channel binary image:

```scala mdoc
val prepared = scan().forOcr()
val shape = s"${prepared.channels} channel, ${prepared.width}x${prepared.height}, upright & binarised"
prepared.close()
shape
```

One channel, binarised, and straightened — exactly what a recogniser wants. Each step earns its place:

| Step | Why it matters | scalacv method |
| --- | --- | --- |
| grayscale | OCR reads intensity, not colour; drops 2/3 of the data for free | [`gray`](/image-processing) |
| denoise (median blur) | kills salt-and-pepper speckle that thresholding would otherwise turn into fake ink | [`medianBlur`](/image-processing) |
| adaptive threshold | a per-neighbourhood cutoff that survives uneven lighting where a global threshold fails | [`adaptiveThreshold`](/image-processing) |
| deskew | even a few degrees of tilt smears character rows into each other | `deskew` (below) |

### The `forOcr` knobs

The defaults suit a typical document scan; three parameters let you adapt to a harder image:

| Parameter | Default | What it does | Turn it… |
| --- | --- | --- | --- |
| `denoise` | `1` | median-blur radius before thresholding (`0` skips it) | **up** for a noisy photo, **`0`** for an already-clean render |
| `blockSize` | `15` | adaptive-threshold neighbourhood (odd, ≥ 3) | **up** for large text, **down** for small dense text |
| `c` | `10` | threshold bias — raise it to keep less ink | **up** if letters blob together, **down** if strokes vanish |

```scala mdoc:silent
// A gentler prep for a clean render: no denoise, smaller neighbourhood.
val custom = scan().forOcr(denoise = 0, blockSize = 11, c = 8)
custom.close()
```

:::note[Build your own variant]
`forOcr` is just a convenience composition — the individual steps are all first-class on `Image`, so you
can assemble a bespoke pipeline (`scan().gray.medianBlur(1).adaptiveThreshold(...).deskew()`) when the
defaults do not fit. See [image processing](/image-processing) and [colour masking](/color-masking).
:::

### Deskew

The one step unique to OCR is **deskew**: a scan is rarely perfectly straight, and even a few degrees of
tilt hurts recognition. `deskew` binarises the ink, fits a minimum-area rectangle to it, and rotates the
page back to level (the exposed corners fill white):

```scala mdoc:compile-only
Image.read("crooked-scan.jpg").map(_.deskew().write("straight.png"))
```

It is deliberately conservative. A detected skew beyond `maxAngle` (default `45.0`) is treated as a
misread and the page is left untouched — a full page of large graphics can fool the estimate, and rotating
it by a wrong guess is worse than leaving it alone. A blank page (no ink to measure) is also left as-is.

```scala mdoc:silent
// Only correct small tilts; ignore anything the estimator reports beyond 20°.
val straightened = scan().deskew(maxAngle = 20.0)
straightened.close()
```

## The engine contract

An `OcrEngine` turns a prepared image into text. It has a single method, `recognize(image): OcrResult`,
and it **borrows** the image (reads it, does not consume or close it). `Ocr.read` preprocesses (unless told
not to) and delegates:

```scala mdoc:silent
// A stand-in engine; a real one calls Tesseract (see below).
val engine: OcrEngine = new OcrEngine:
  def recognize(image: Image): OcrResult =
    OcrResult(text = s"<${image.width}x${image.height} page>", words = Seq.empty)
```

```scala mdoc
Ocr.read(scan(), engine).text
```

### `preprocess = false` — when you did the prep yourself

`Ocr.read(image, engine, preprocess = true)` (the default) copies the image, runs `forOcr` on the copy, and
hands *that* to the engine — leaving your original untouched. Pass `preprocess = false` when the image is
already prepared (you called `forOcr` yourself, or the source is already a clean binary scan) and you do
not want a second, redundant pass:

```scala mdoc:silent
val ready = scan().forOcr()          // prepare once, explicitly
val text = Ocr.read(ready, engine, preprocess = false).text
ready.close()                        // preprocess = false borrows; the caller still closes
```

:::warning[Who closes what]
`Ocr.read` **borrows** the `image` you pass and never closes it — the prepared *copy* it makes internally
(when `preprocess = true`) is the only thing it frees. Your original is yours to `close()`, as with every
borrowing API in the library.
:::

## The result is plain data

`OcrResult` is immutable data you can inspect, filter and carry past the images it came from:

| Type / member | Meaning |
| --- | --- |
| `OcrResult(text, words)` | the full recognised `text`, plus per-word detail when the engine provides it |
| `OcrResult.isEmpty` | `true` when nothing legible was found (`text` is blank) |
| `OcrResult.confident(min = 0.5f)` | the words at or above a confidence, dropping the shaky ones |
| `OcrWord(text, confidence, box)` | one recognised word: its text, the engine's `[0, 1]` confidence, and its bounding `Rect` |

```scala mdoc
val result = OcrResult(
  "hello world",
  Seq(OcrWord("hello", 0.95f, Rect(0, 0, 40, 12)), OcrWord("world", 0.30f, Rect(45, 0, 40, 12)))
)
result.confident(0.5f).map(_.text).mkString(", ") // drops the low-confidence "world"
```

`confident` is how you quarantine junk: keep only what the engine is sure about, and treat the rest as a
maybe. The count tells you how much survived the filter:

```scala mdoc
result.confident(0.5f).size
```

`isEmpty` distinguishes "read nothing" from "read something low-confidence" — a blank scan versus a hard
one:

```scala mdoc
OcrResult("").isEmpty
```

Because each `OcrWord` carries a `box`, you can draw the recognised words back onto the page with
[`drawRects`](/drawing) (`result.words.map(_.box)`) — the standard way to visualise what the engine saw
and where.

## Wiring in Tesseract

Add [tess4j](https://github.com/nguyenq/tess4j) (or bytedeco's `tesseract` preset) and its language data,
then implement `OcrEngine` over it. scalacv has already produced the clean, deskewed image; the adapter
just hands the pixels across:

```scala
// build: libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "5.x"
import net.sourceforge.tess4j.Tesseract
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

final class TesseractEngine(dataPath: String, language: String = "eng") extends scalacv.OcrEngine:
  private val tess = new Tesseract()
  tess.setDatapath(dataPath) // the folder holding eng.traineddata
  tess.setLanguage(language)

  def recognize(image: scalacv.Image): scalacv.OcrResult =
    // scalacv encodes the prepared image; tess4j reads a BufferedImage.
    val png = image.copy.bytes(".png").fold(e => throw e, identity)
    val buffered = ImageIO.read(new ByteArrayInputStream(png))
    scalacv.OcrResult(text = tess.doOCR(buffered).trim)
```

```scala
OpenCv.load()
val ocr = TesseractEngine(dataPath = "/usr/share/tessdata")

Image.read("receipt.jpg").map { img =>
  try Ocr.read(img, ocr).text   // forOcr + Tesseract
  finally img.close()
}
```

:::note[Why Tesseract is not bundled]
Tesseract pulls its own native libraries and multi-megabyte `*.traineddata` files per language, and is
GPL-adjacent — exactly the kind of heavy, opinionated dependency a wrapper should let you *choose* rather
than impose. Keeping it behind the `OcrEngine` seam means scalacv stays a thin OpenCV layer and you pick
the recogniser.
:::

The same shape works for any recogniser — a cloud OCR, an ONNX text-detection/recognition model through
[Dnn](/dnn): implement `recognize`, return an `OcrResult`, and the preprocessing is done for you. If your
engine reports per-word boxes and confidences, populate `words` and callers get `confident` and box
overlays for free.

## Next

- [Image processing](/image-processing) — grayscale, blur and threshold, the steps `forOcr` composes.
- [Colour, masking & compositing](/color-masking) — adaptive thresholding and cleanup in depth.
- [DNN](/dnn) — plug a neural text model in behind the same `OcrEngine` contract.
