# Hough transforms

The Hough line transforms answer a deceptively simple question: *given a scatter of edge pixels, which
straight lines run through them?* They are the classical way to find the edges of a table, the bars of a
barcode, lane markings on a road, or the sides of a scanned document — anything where the structure you care
about is made of straight runs. Each edge pixel "votes" for every line that could pass through it; the lines
that collect the most votes win.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
OpenCv.load()
```

OpenCV returns the winners as an anonymous multi-channel `Mat` whose element type differs per transform and
whose channels have no names — a shape you have to know three separate facts to read correctly, where getting
any of them wrong either aborts from JNI or, worse, silently reinterprets the bits. scalacv decodes each into
a small, immutable Scala case class so you never touch that `Mat` or guess at its layout.

Every snippet below runs against a synthetic edge image, so no fixture file is needed. All three transforms
require a non-empty 8-bit single-channel image ([`CV_8UC1`](/geometry)); handing them anything else fails with
a named precondition rather than a JNI abort.

## Two flavours

| Method | Returns | A line is… | Use when |
|---|---|---|---|
| `houghLines` | `Seq[PolarLine]` | **infinite**, in polar form `(rho, theta)` | you want the line's orientation/position, and endpoints are irrelevant (lane direction, dominant angle) |
| `houghLinesP` | `Seq[Segment]` | **finite**, with pixel endpoints `(x1, y1, x2, y2)` | you want to draw or measure the actual runs of edge (table edges, bar-code bars, wireframe sides) |

The probabilistic variant (`houghLinesP`) is the one most code wants: it reports where each line actually
starts and stops, is cheaper, and its output renders directly with [`drawSegments`](/drawing).

:::tip[Start with `houghLinesP`]
Unless you specifically need an *infinite* line's angle (a vanishing-point or dominant-orientation problem),
reach for `houghLinesP`. Real endpoints are almost always what you want, and `Segment` gives you `length`,
`start`, and `end` for free.
:::

## Building an edge image to work with

```scala mdoc:silent
val edges = Mat(200, 200, CvType.CV_8UC1, org.opencv.core.Scalar(0))
// One horizontal and one vertical bright line — this stands in for a real edge map.
Imgproc.line(edges, org.opencv.core.Point(20, 30), org.opencv.core.Point(180, 30), org.opencv.core.Scalar(255), 1)
Imgproc.line(edges, org.opencv.core.Point(100, 10), org.opencv.core.Point(100, 190), org.opencv.core.Scalar(255), 1)
```

## Standard: infinite lines

`houghLines` reports each line in Hesse normal form. `rho` is the signed distance in pixels from the image
origin to the line; `theta` is the angle of the *normal* to the line, in radians — `0` is a vertical line,
`Pi/2` a horizontal one. `threshold` is the minimum number of accumulator votes (roughly, collinear edge
pixels) a line needs to be reported, and is the only argument without a sensible default.

```scala mdoc
edges.houghLines(threshold = 120)
```

`PolarLine(rho, theta)` carries `Double` fields, widened from the `CV_32FC2` (two-channel float) `Mat` this
transform produces at the decode boundary — so a Hough result combines with `Point`, `Contour` and the rest
of the library's geometry without a manual conversion.

Because `theta` describes the *normal*, it is the usual source of confusion. Converting to degrees makes the
orientations obvious — our horizontal line's normal is vertical (`90°`) and vice-versa:

```scala mdoc
edges.houghLines(threshold = 120).map(l => math.round(math.toDegrees(l.theta.toDouble)))
```

`houghLines` takes several more knobs, all with sensible defaults:

| Param | Default | Meaning |
|---|---|---|
| `threshold` | *(required)* | minimum accumulator votes for a line to be reported |
| `rho` | `1.0` | accumulator distance resolution, in pixels |
| `theta` | `Pi/180` | accumulator angle resolution, in radians (1°) |
| `srn` | `0.0` | divisor for a coarse-to-fine `rho`; `0` (with `stn`) selects the classic transform |
| `stn` | `0.0` | divisor for a coarse-to-fine `theta` |
| `minTheta` | `0.0` | lower bound on the reported angle, radians |
| `maxTheta` | `Pi` | upper bound on the reported angle, radians |

:::tip[Constrain the angle to speed things up]
If you only want near-horizontal lines, set `minTheta`/`maxTheta` around `Pi/2`. The accumulator ignores every
other orientation, which is both faster and far less noisy than filtering the results afterwards.
:::

## Probabilistic: finite segments

`houghLinesP` gives real endpoints. `minLineLength` drops short segments and `maxLineGap` is the largest
break, in pixels, that will still be bridged into one segment.

```scala mdoc
edges.houghLinesP(threshold = 50, minLineLength = 50, maxLineGap = 5)
```

| Param | Default | Meaning |
|---|---|---|
| `threshold` | *(required)* | minimum accumulator votes for a line to be reported |
| `rho` | `1.0` | accumulator distance resolution, in pixels |
| `theta` | `Pi/180` | accumulator angle resolution, in radians (1°) |
| `minLineLength` | `0.0` | segments shorter than this are discarded; `0` keeps everything |
| `maxLineGap` | `0.0` | largest gap, in pixels, bridged into one segment |

`Segment(x1, y1, x2, y2)` fields are `Int`, not `Float`, and this is not a rounding choice: the raw `Mat` here
is `CV_32SC4`, genuine int32 pixel coordinates. A float-typed read of that `Mat` throws, so scalacv decodes it
as integers. `Segment` also gives you `start`/`end` [`Point`](/geometry)s and a `length`, which makes the
near-universal "keep only the long ones" filter a one-liner:

```scala mdoc
edges.houghLinesP(threshold = 50).filter(_.length > 100).map(_.length)
```

:::note[`maxLineGap` fights fragmentation]
A real Canny edge is rarely one unbroken run — noise and anti-aliasing chop it into pieces. Bump `maxLineGap`
(say to 10–20) to stitch a dashed-looking edge back into one segment; leave it small when you genuinely want
to detect the gaps (dashed lane lines, perforations).
:::

## Keeping the vote counts

The plain transform returns lines already sorted by strength but discards the magnitudes.
`houghLinesWithAccumulator` keeps them, which is the only way to rank or threshold results yourself:

```scala mdoc
edges.houghLinesWithAccumulator(threshold = 120).take(3)
```

The votes are the accumulator scores — the number of edge pixels that voted for each line — so a bare list of
them tells you at a glance how much stronger the top line is than the runners-up:

```scala mdoc
edges.houghLinesWithAccumulator(threshold = 120).map(_.votes)
```

`PolarLineWithVotes(rho, theta, votes)` comes from a third `Mat` shape again — `CV_32FC3`, where the third
channel is the vote count. Call `.line` on one to drop the votes and get a plain `PolarLine`.

## The pipeline: edges first

Hough needs *edges*, not a raw image — feed it a photo and it detects nothing. The normal first step is
[`canny`](/image-processing), whose output is always `CV_8UC1` and so drops straight into any of the three
transforms. Here a filled square stands in for a scene; Canny turns it into its four-sided outline, which
`houghLinesP` recovers as segments:

```scala mdoc:silent
val shape = Mat(200, 200, CvType.CV_8UC1, org.opencv.core.Scalar(0))
Imgproc.rectangle(shape, org.opencv.core.Point(50, 50), org.opencv.core.Point(150, 150), org.opencv.core.Scalar(255), -1)
```

```scala mdoc
shape.canny(50, 150).use { outline =>
  outline.houghLinesP(threshold = 40, minLineLength = 40, maxLineGap = 10).size
}
```

`canny` returns a `Managed[Mat]`; its `use` borrows the edge image for the transform and releases it
afterwards, so the intermediate never leaks. On an `Image`, the same pipeline chains through `.mat` — a
borrow, so the image stays alive to close:

```scala mdoc:compile-only
Image.reading("floor.jpg") { img =>
  val e = img.gray.canny(80, 160) // Image, CV_8UC1
  val lines = e.mat.houghLinesP(threshold = 60, minLineLength = 50, maxLineGap = 10)
  e.close()
  lines
}
```

:::danger[Wrong type in, exception out]
All three transforms assert `CV_8UC1`. Passing a colour image (or an empty one) fails a precondition *before*
reaching native code, so you get a message naming the offending type instead of a JNI abort:

```scala mdoc:crash
Managed.use(Mat(60, 60, CvType.CV_8UC3, org.opencv.core.Scalar(0)))(_.houghLines(threshold = 10))
```
:::

## Rendering the segments

`houghLinesP` results are invisible until you draw them. [`drawSegments`](/drawing) is their renderer — it
strokes each `Segment` into a Mat you own (it **mutates** that Mat, like every `draw*` op):

```scala mdoc:silent
val segments = edges.houghLinesP(threshold = 50, minLineLength = 50, maxLineGap = 5)

val canvas = Mat(200, 200, CvType.CV_8UC3, org.opencv.core.Scalar(0))
canvas.drawSegments(segments, Scalar.Red, Thickness.Stroke(2))
```

```scala mdoc
Images.encode(canvas, ".png").map(_.length)
```

```scala mdoc:invisible
edges.release(); shape.release(); canvas.release()
```

## Tuning cheat-sheet

When a Hough call reports too much or too little, these are the knobs, in the order worth trying:

| Symptom | Try |
|---|---|
| No lines at all | lower `threshold`; confirm the input is a real edge map (run `canny` first) |
| Too many near-duplicate lines | raise `threshold`; coarsen `theta` (e.g. `2 * Pi/180`) |
| One line split into fragments | raise `maxLineGap` (`houghLinesP`) |
| Short noise segments survive | raise `minLineLength` (`houghLinesP`) |
| Wrong orientations detected | narrow `minTheta`/`maxTheta` (`houghLines`) |

## Next

- [The Image API](/image-api) — the high-level `read → canny → detect → draw` chain
- [Image processing](/image-processing) — `canny` and the rest of the edge/filter surface
- [Drawing](/drawing) — `drawSegments` and the other annotation primitives
- [Geometry & typed values](/geometry) — `Point`, `Scalar`, `Thickness` and the pixel types
