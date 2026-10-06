# 2D graphics & creative coding

scalacv draws with a small compositional graphics language: a **`Picture`** is an immutable value you build,
style, and combine, then render onto an image. It is inspired by
[Doodle](https://github.com/creativescala/doodle) — the picture-as-a-value idea, `on`/transform composition,
`strokeColor`/`fillColor`/`strokeDash`, `beside`/`above` layout — adapted to scalacv's world: **image
(pixel) coordinates**, OpenCV rendering, and the same resource-safe [`Image`](/image-api) everything else
uses. The payoff is one vocabulary for **annotating a detection**, **plotting data**, and **making
generative art**.

New to it? The mental model is one sentence: **you describe a drawing as a value, then render it.** Nothing
touches a pixel until you call `render` (onto a fresh canvas) or `image.draw` (onto an existing image). Up to
that moment you are only assembling and transforming plain data, so a picture can be reused, moved, coloured,
or laid out as many times as you like without side effects.

:::note[Where this lives]
`Picture`, `Color`, `Chart`, and `Animation` are in the **`scalacv-graphs`** module. `import scalacv.*`
brings all of them in, along with the `image.draw(picture)` extension. The module depends only on `core`.
:::

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*
OpenCv.load()
lazy val detector: org.opencv.objdetect.FaceDetectorYN = ??? // a YuNet model, from FaceDetect.create
```

## A picture is a value

Build it, then draw it — onto a fresh canvas with `render`, or onto an existing image with `image.draw`
(which consumes the image and returns the annotated one):

```scala mdoc
{
  val badge = Picture.circle(Point(50, 50), 30).fillColor(Color.Orange).strokeColor(Color.White).strokeWidth(3)
  badge.render(100, 100, Color.Black).bytes(".png").fold(_.getMessage, b => s"${b.length} bytes")
}
```

Nothing is drawn until `render`/`draw`; up to that point you are only building and combining values, so the
same picture can be reused, transformed, or laid out freely.

`render(width, height, background)` and `renderOn(image)` are the two ways to turn a picture into pixels:

| Call | What it does | Owns/consumes |
| --- | --- | --- |
| `picture.render(w, h, bg)` | Draws onto a **fresh** `w`×`h` canvas filled with `bg` | Returns a new `Image` you own |
| `picture.renderOn(image)` | Draws onto an **existing** image | Consumes `image`, returns the annotated one |
| `image.draw(picture)` | Same as `renderOn`, spelled from the image side | Consumes `image`, returns the annotated one |

:::warning[Move semantics]
`image.draw(...)` (and `renderOn`) **consume** the image — the receiver is spent, exactly like any other
[`Image`](/image-api) transform. Reusing it afterwards throws. Take `image.copy` first if you need to branch,
and remember terminals like `write`/`bytes` release the result. See [Mat lifecycle](/mat-lifecycle).
:::

## The coordinate system

Coordinates are **image pixels**: the origin `(0, 0)` is the **top-left** corner and **y increases
downward**. This is the opposite of Doodle's centred, y-up plane, and it matters for two things:

- Rotation is **clockwise** (positive `degrees` on `rotate`), because y points down.
- A detection box's `(x, y)` is already its top-left in this space, so annotating it needs no conversion.

```scala mdoc
{
  // A clockwise-rotated square, to show which way positive degrees turns:
  val tilted = Picture.rectangle(Rect(30, 30, 40, 40)).strokeColor(Color.Cyan).strokeWidth(2)
    .rotate(20, about = Point(50, 50))
  tilted.render(100, 100, Color.Black).bytes(".png").fold(_.getMessage, b => s"tilted square, ${b.length} bytes")
}
```

## The primitives

Every primitive is a `Picture`, so they all style, transform, and compose the same way.

| Kind | Factories |
| --- | --- |
| Basic | `circle`, `rectangle`, `roundedRectangle`, `line`, `polyline`, `polygon`, `text` |
| Round | `ellipse`, `arc`, `sector` (pie slice) |
| Curves | `curve` (cubic Bézier), `quadraticCurve` |
| Markers | `dot`, `marker`, `cross`, `arrow` |
| Shapes | `regularPolygon`, `star` |
| Composite | `label` (text on a filled box), `all` (group) |

Signatures worth knowing at a glance (all live on the `Picture` companion):

| Factory | Signature (key params) |
| --- | --- |
| `circle` | `circle(center: Point, radius: Double)` |
| `rectangle` | `rectangle(rect: Rect)` |
| `roundedRectangle` | `roundedRectangle(rect: Rect, radius: Double)` |
| `line` | `line(from: Point, to: Point)` |
| `polyline` | `polyline(points: Seq[Point], closed: Boolean = false)` |
| `polygon` | `polygon(points: Seq[Point])` (closed polyline) |
| `text` | `text(text: String, at: Point)` |
| `ellipse` | `ellipse(center, rx, ry, rotation = 0, segments = 64)` |
| `arc` | `arc(center, rx, ry, startDegrees, endDegrees, rotation = 0, segments = 48)` |
| `sector` | `sector(center, rx, ry, startDegrees, endDegrees, rotation = 0, segments = 48)` |
| `curve` | `curve(p0, c0, c1, p1, segments = 32)` |
| `quadraticCurve` | `quadraticCurve(p0, control, p1, segments = 24)` |
| `dot` | `dot(at: Point, radius: Double = 3)` (white filled) |
| `marker` | `marker(at: Point, color: Color, radius: Double = 3)` |
| `cross` | `cross(at: Point, size: Double = 5)` |
| `arrow` | `arrow(from, to, headLength = 12, headAngle = 28)` |
| `regularPolygon` | `regularPolygon(center, sides, radius, rotation = 0)` |
| `star` | `star(center, points, outer, inner, rotation = 0)` |
| `label` | `label(text, at, textColor = White, background = Black, padding = 4, fontScale = 0.5, font = Simplex)` |

The round shapes and curves are drawn as fine polylines, so they fill, dash, and transform with the same
uniform styling as anything else:

```scala mdoc
{
  val flower = Picture.all((0 until 6).map { i =>
    Picture.ellipse(Point(50, 50), 30, 12, rotation = i * 30).fillColor(Color.Pink.withAlpha(120)).noStroke
  })
  flower.render(100, 100, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"ellipse flower, ${b.length} bytes")
}
```

### Markers, arcs & curves

Markers are the small vocabulary you reach for when overlaying keypoints, matches, or directions:

```scala mdoc:silent
val overlay = Picture.all(Seq(
  Picture.marker(Point(20, 20), Color.Red, radius = 4),      // a filled dot
  Picture.cross(Point(50, 20), size = 6).strokeColor(Color.Yellow), // an X
  Picture.arrow(Point(20, 60), Point(80, 40)).strokeColor(Color.Cyan).strokeWidth(2)
))
```

```scala mdoc
overlay.render(100, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"markers, ${b.length} bytes")
```

An `arc` is an open slice of an ellipse; a `sector` closes it back through the centre into a filled pie
wedge; a `curve` is a cubic Bézier pulled toward two control points:

```scala mdoc
{
  val wedge = Picture.sector(Point(50, 50), 40, 40, startDegrees = -90, endDegrees = 40)
    .fillColor(Color.Orange).noStroke
  val swoosh = Picture.curve(Point(10, 70), Point(30, 10), Point(70, 90), Point(90, 30))
    .strokeColor(Color.Green).strokeWidth(2)
  wedge.on(swoosh).render(100, 100, Color.Black).bytes(".png").fold(_.getMessage, b => s"arc+curve, ${b.length} bytes")
}
```

## Styling

Styling methods return a new styled picture — they never mutate. A style set on a **group** is a *default*
its members inherit unless they set their own, which is what makes `Picture.all(...).strokeColor(...)` colour
a whole overlay at once.

| Method | Effect |
| --- | --- |
| `strokeColor(c)` | Outline colour |
| `stroke(c, w)` | Outline colour **and** width in one call |
| `strokeWidth(w)` | Outline width (clamped to ≥ 1) |
| `noStroke` | No outline |
| `fillColor(c)` | Fill colour (closed shapes only) |
| `noFill` | No fill |
| `strokeDash(Dash(on, off))` | A custom dash pattern |
| `dashed` / `dotted` | Preset dash patterns |
| `solidStroke` | Clears any dash |
| `font(f)` / `fontScale(s)` | Text font and size |
| `smooth(on)` | Antialiasing (default on) |

:::tip[Fill vs stroke]
A shape can carry **both** a fill and a stroke — the fill paints first, the outline over it. `noStroke` on a
`fillColor` shape gives you flat-filled swatches (as in the palette below); `noFill` on an outline-only shape
avoids a solid interior.
:::

### Fonts

Text uses OpenCV's Hershey fonts, via the core [`Font`](/drawing) enum:

| `Font` | Look |
| --- | --- |
| `Simplex` | Clean sans (the default) |
| `Plain` | Thin, small |
| `Duplex` | Double-stroke sans |
| `Complex` | Serif |
| `Triplex` | Heavy serif |
| `Script` | Handwriting |

```scala mdoc:silent
val fonts = Seq(Font.Simplex, Font.Complex, Font.Script)
val sample = Picture.all(fonts.zipWithIndex.map { (f, i) =>
  Picture.text("Scala", Point(6, 20 + i * 22)).font(f).fontScale(0.6).strokeColor(Color.White)
})
```

```scala mdoc
sample.render(120, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"font sample, ${b.length} bytes")
```

## Dashed and dotted strokes

OpenCV has no dashed line; `Picture` draws one by segmenting the path, so `dashed`, `dotted`, and any custom
`strokeDash(Dash(on, off))` just work — exactly what a **dotted detection box** wants:

```scala mdoc
{
  val box = Picture.roundedRectangle(Rect(20, 20, 60, 40), radius = 10).strokeColor(Color.Green).strokeWidth(2).dashed
  box.render(100, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"dashed rounded box, ${b.length} bytes")
}
```

`Dash` alternates `on`/`off` run lengths in pixels; three presets cover the usual needs:

| Preset | Pattern | Reads as |
| --- | --- | --- |
| `Dash.dashed` | `Dash(10, 8)` | Long dashes |
| `Dash.dense` | `Dash(4, 4)` | Tight dashes |
| `Dash.dotted` | `Dash(1, 6)` | Dots |

```scala mdoc:silent
val patterns = Seq(Dash.dashed, Dash.dense, Dash.dotted)
val lines = Picture.all(patterns.zipWithIndex.map { (d, i) =>
  Picture.line(Point(8, 16 + i * 16), Point(112, 16 + i * 16))
    .strokeColor(Color.White).strokeWidth(2).strokeDash(d)
})
```

```scala mdoc
lines.render(120, 64, Color.Black).bytes(".png").fold(_.getMessage, b => s"dash patterns, ${b.length} bytes")
```

## Labelling a detection

The most common annotation is a box with a readable tag. `Picture.label` draws text on a filled background
box (sized to the text), and everything composes into one overlay you draw on the frame:

```scala mdoc:silent
def annotate(box: Rect, tag: String): Picture =
  Picture.all(Seq(
    Picture.rectangle(box).strokeColor(Color.Green).strokeWidth(2).dashed,
    Picture.label(tag, Point(box.x, box.y - 20), Color.Black, Color.Green)
  ))
```

```scala mdoc:compile-only
// On a real detection:
Image.reading("crowd.jpg") { img =>
  val faces = img.faces(detector)
  img.draw(Picture.all(faces.map(f => annotate(f.box, f"${f.score}%.2f"))))
    .write("annotated.png")
}
```

:::tip[Why `label`, not raw `text`]
`label` measures the string with its font metrics (including the descender room `y` and `g` need), then sizes
a filled box around it — so the tag is always legible over a busy frame, and never clipped. Placing bare
`text` on a light image often leaves it unreadable.
:::

## Composition, transforms & layout

`on`/`under` overlay; `at`/`translate`/`rotate`/`scale` move; styling set on a group is a default its members
inherit unless they override it. On top of that, scalacv adds Doodle-style **layout** — `beside`, `above`,
and `Picture.grid` — which measure each picture's [bounding box](/api/core/scalacv/Bounds.html) and place
them relative to one another, so you never hand-compute offsets:

| Combinator | Meaning |
| --- | --- |
| `a.on(b)` / `a.under(b)` | Overlay `a` over / under `b` |
| `Picture.all(seq)` | Group a sequence, first at the bottom |
| `a.beside(b, gap)` | Place `b` to the right of `a`, centres aligned |
| `a.above(b, gap)` | Place `b` below `a`, centres aligned |
| `Picture.grid(seq, columns, gap)` | Arrange a sequence into a grid, cells sized to the largest |
| `a.at(p)` / `a.translate(dx, dy)` | Move |
| `a.rotate(deg, about)` | Rotate (clockwise) about a pivot |
| `a.scale(factor, about)` | Scale about a pivot |
| `a.bounds` | The axis-aligned bounding box, as `Option[Bounds]` |

```scala mdoc
{
  val a = Picture.circle(Point(20, 20), 18).fillColor(Color.Red).noStroke
  val b = Picture.rectangle(Rect(0, 0, 36, 36)).fillColor(Color.Blue).noStroke
  val c = Picture.star(Point(20, 20), 5, 18, 8).fillColor(Color.Yellow).noStroke
  val row = a.beside(b, gap = 12).beside(c, gap = 12)
  row.render(160, 60, Color.Black).bytes(".png").fold(_.getMessage, s => s"a laid-out row, ${s.length} bytes")
}
```

`Picture.grid(pictures, columns)` arranges a sequence into a grid — handy for a contact sheet of variations:

```scala mdoc:silent
val shapes = (3 to 8).map { sides =>
  Picture.regularPolygon(Point(20, 20), sides, radius = 16).strokeColor(Color.Cyan).strokeWidth(2)
}
val sheet = Picture.grid(shapes, columns = 3, gap = 10)
```

```scala mdoc
sheet.render(120, 90, Color.Black).bytes(".png").fold(_.getMessage, b => s"3x2 contact sheet, ${b.length} bytes")
```

### Measuring with `bounds`

`bounds` is what layout uses under the hood, and you can call it yourself to size a canvas or align content.
It returns `None` for the empty picture (which draws nothing) and a [`Bounds`](/api/core/scalacv/Bounds.html)
otherwise, with `width`, `height`, `centerX`, and `centerY`:

```scala mdoc
Picture.circle(Point(50, 50), 30).bounds.map(_.width).getOrElse(0.0)
```

## Colour & palettes

[`Color`](/api/core/scalacv/Color.html) is RGBA with named colours, an `hsl` constructor, and a full set of
transforms: `lighten`/`darken`/`fadeOut`/`blend`, and `spin`/`complement`/`saturate`/`desaturate` for hue
work.

| Constructor | Builds |
| --- | --- |
| `Color.rgb(r, g, b)` | An opaque colour |
| `Color.rgba(r, g, b, a)` | A colour with alpha |
| `Color.gray(v)` | A shade of grey |
| `Color.hsl(hue, sat, light, alpha = 255)` | From hue (degrees) + saturation + lightness in `[0, 1]` |

| Transform | Effect |
| --- | --- |
| `lighten(a)` / `darken(a)` | Toward white / black by `a` in `[0, 1]` |
| `withAlpha(a)` | Set alpha directly (`[0, 255]`) |
| `fadeOut(a)` | Reduce alpha by fraction `a` |
| `blend(other, a)` | Mix `a` of `other` in |
| `spin(deg)` / `complement` | Rotate hue / 180° opposite |
| `saturate(a)` / `desaturate(a)` | More / less vivid (`desaturate(1)` is grey) |

Two palette generators cover the common needs — `Color.wheel(n)` for **distinct categorical** colours and
`Color.ramp(from, to, n)` for a **sequential** scale (and `Color.categorical`, a ready-made eight):

| Palette | Use for |
| --- | --- |
| `Color.wheel(n, saturation = 0.65, lightness = 0.55)` | Distinct labels: series, tracks, classes |
| `Color.ramp(from, to, n)` | Ordered data: a heat scale, a gradient fill |
| `Color.categorical` | A sensible eight-colour default (`wheel(8)`) |

```scala mdoc
{
  val swatches = Color.wheel(6).zipWithIndex.map { (col, i) =>
    Picture.rectangle(Rect(i * 26 + 4, 10, 22, 40)).fillColor(col).noStroke
  }
  Picture.all(swatches).render(164, 60, Color.Black).bytes(".png").fold(_.getMessage, b => s"palette, ${b.length} bytes")
}
```

A sequential `ramp` reads as an ordered scale rather than a set of categories:

```scala mdoc
{
  val heat = Color.ramp(Color.Blue, Color.Red, 8).zipWithIndex.map { (col, i) =>
    Picture.rectangle(Rect(i * 20 + 2, 10, 18, 40)).fillColor(col).noStroke
  }
  Picture.all(heat).render(164, 60, Color.Black).bytes(".png").fold(_.getMessage, b => s"heat ramp, ${b.length} bytes")
}
```

Alpha gives real transparency when a picture is drawn over an image — the highlight below tints the pixels
beneath it rather than replacing them:

```scala mdoc
{
  val highlight = Picture.rectangle(Rect(10, 10, 80, 40)).fillColor(Color.Yellow.withAlpha(90)).noStroke
  highlight.render(100, 60, Color.Blue).bytes(".png").fold(_.getMessage, b => s"translucent highlight, ${b.length} bytes")
}
```

:::note[How alpha composites]
Each translucent shape blends only the region it covers back toward the pixels underneath — bit-identical to
blending the whole image, but far cheaper for a small overlay on a big frame. Note that OpenCV's own drawing
verbs ignore alpha; the `Picture` layer honours it.
:::

### Bridging to core drawing

The RGBA `Color` palette and OpenCV's BGR [`Scalar`](/low-level) meet with two conversions, so a colour you
generate here can feed the raw [drawing](/drawing) verbs and vice versa:

| Direction | Call | Notes |
| --- | --- | --- |
| `Color` → `Scalar` | `color.toBgrScalar` | BGR-ordered; drops alpha (pre-blend with `fadeOut`/`blend` if you need it baked in) |
| `Scalar` → `Color` | `scalar.toColor` | Reads BGR, result is fully opaque |

```scala mdoc
Color.Orange.toBgrScalar.toColor == Color.Orange
```

## Data visualisation

Because pictures compose, charts are just pictures. [`Chart`](/api/core/scalacv/Chart$.html) covers `bars`,
`line`, `area`, `scatter`, `pie`, and `histogram`. Each returns a `Picture` sized to a `width`×`height` box
with its origin at the top-left, so you render one standalone or drop it into a corner of a frame with
`chart.at(Point(x, y))`.

| Chart | Signature (key params) | Default colour |
| --- | --- | --- |
| `bars` | `bars(values, width, height, color = Blue, gap = 4)` | Blue |
| `line` | `line(values, width, height, color = Green, strokeWidth = 2)` | Green |
| `area` | `area(values, width, height, color = Blue, strokeWidth = 2)` | Blue (faded fill) |
| `scatter` | `scatter(points: Seq[(Double, Double)], width, height, color = Red, radius = 3)` | Red |
| `pie` | `pie(values, width, height, palette = Color.categorical)` | Categorical |
| `histogram` | `histogram(data, bins, width, height, color = Purple)` | Purple |

:::warning[bars, line and area are magnitude charts]
Each value is plotted as its **absolute value** `|v|`, measured up from a zero baseline at the bottom of the
box and scaled to `max(|v|)` across the series. A series that crosses zero — an audio waveform, a
day-on-day delta, a profit-and-loss line — is therefore silently **folded** upwards rather than clipped or
drawn below a mid-line, and the chart still looks entirely plausible while being wrong.

If your data is signed, either shift it into the positive range yourself (subtract the minimum, `v - min`)
and draw your own zero line, or use `scatter`, which maps the true `(x, y)` range into the box and handles
negatives correctly. `pie` rectifies too — a negative value contributes a positive share of the total.
`histogram` is unaffected: it bins the raw values over `[min, max]`, so negative *data* lands in the right
bucket, and the counts it then charts are never negative.
:::

Here is the fold, made visible: a zero-crossing series and its own absolute value render to byte-identical
pixels.

```scala mdoc
{
  val signed = Seq(-4.0, -1.5, 1.0, 3.0, -2.5) // a series that crosses zero
  val folded = signed.map(math.abs)           // what `bars` actually draws
  val fromSigned = Chart.bars(signed, 120, 60).render(120, 60, Color.Black).bytes(".png")
  val fromFolded = Chart.bars(folded, 120, 60).render(120, 60, Color.Black).bytes(".png")
  s"identical pixels: ${fromSigned.map(_.toSeq) == fromFolded.map(_.toSeq)}"
}
```

```scala mdoc
{
  val pie = Chart.pie(Seq(5, 3, 2, 4), 100, 100, Color.wheel(4))
  pie.render(100, 100, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"pie chart, ${b.length} bytes")
}
```

```scala mdoc
{
  val hist = Chart.histogram(Seq(1.0, 2, 2, 3, 3, 3, 3, 4, 4, 5), bins = 5, width = 200, height = 80)
  hist.render(200, 80, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"histogram, ${b.length} bytes")
}
```

Bars, line and area all take a sequence of values across the width, plotting each as a magnitude against
the bottom of the box (see the warning above); scatter takes `(x, y)` pairs and maps their range into the
box:

```scala mdoc
{
  val series = Seq(3.0, 5, 2, 8, 6, 9, 4)
  val chart = Chart.line(series, 200, 80, Color.Cyan).on(Chart.area(series, 200, 80, Color.Cyan))
  chart.render(200, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"line over area, ${b.length} bytes")
}
```

```scala mdoc
{
  val cloud = Seq((1.0, 2.0), (2.0, 3.5), (3.0, 1.0), (4.0, 4.0), (5.0, 2.5))
  Chart.scatter(cloud, 160, 100).render(160, 100, Color.DarkGray).bytes(".png")
    .fold(_.getMessage, b => s"scatter, ${b.length} bytes")
}
```

Because a chart is a picture, it drops onto a frame like any overlay — a signal in the corner, a histogram
beside a detection:

```scala mdoc:compile-only
Image.reading("frame.jpg") { frame =>
  val spark = Chart.line(Seq(3.0, 5, 2, 8, 6, 9), width = 120, height = 40, color = Color.Yellow)
  frame.draw(spark.at(Point(10, 10))).write("with-sparkline.png")
}
```

:::warning[Positive chart box]
Every chart requires a positive `width`×`height` — a non-positive box throws rather than drawing a degenerate
shape. `histogram` likewise requires `bins >= 1`.
:::

## Animation

An animation is a `Picture` valued by frame number. `Animation.record` renders each frame and writes a video
through a [`Recorder`](/video); `Animation.gif` writes a shareable animated GIF; `Animation.foreach` and
`Animation.frames` hand you rendered `Image`s instead of a file.

| Entry point | Writes | Returns |
| --- | --- | --- |
| `Animation.record(path, frames, w, h, fps = 30, background, codec = Mjpg)(frame)` | A video | `Either[CvError, Long]` (frames written) |
| `Animation.gif(path, frames, w, h, fps = 15, background, loop = true)(frame)` | An animated GIF | `Either[CvError, Long]` |
| `Animation.foreach(count, w, h, background)(frame)(f)` | Nothing | `Unit` — one canvas live at a time, **closed for you** |
| `Animation.frames(count, w, h, background)(frame)` | Nothing | `Seq[Image]` — all `count` live at once, **each is yours to close** |

```scala mdoc:compile-only
// A shareable loop as an animated GIF:
Animation.gif("spin.gif", frames = 60, width = 320, height = 240, fps = 20) { i =>
  Picture.star(Point(160, 120), points = 5, outer = 90, inner = 40, rotation = i * 6)
    .strokeColor(Color.hsl(i * 6, 0.8, 0.6)).strokeWidth(3)
}

// Or a full-colour video, for longer or richer clips. `.avi`, not `.mp4`: the default codec is
// MJPG, which is the one videoio can always write, and it opens only in an AVI container.
Animation.record("spin.avi", frames = 300, width = 320, height = 240) { i =>
  Picture.regularPolygon(Point(160, 120), sides = 6, radius = 80, rotation = i * 2).strokeColor(Color.Cyan)
}
```

`Animation.foreach` is the in-memory variant — no file, just each rendered frame handed to your function and
closed again before the next one is drawn. One canvas is alive at a time, so a 900-frame 1080p render costs
about 6 MB of native memory rather than 5.6 GB:

```scala mdoc:silent
// Render three frames and measure the first — every canvas is closed for you:
var firstWidth = 0
Animation.foreach(count = 3, width = 64, height = 64) { i =>
  Picture.circle(Point(32, 32), 10 + i * 6).fillColor(Color.Red).noStroke
} { img =>
  if firstWidth == 0 then firstWidth = img.width
}
```

```scala mdoc
firstWidth
```

`Animation.frames` is the variant for when the frames have to outlive the loop — it returns a strict
`Seq[Image]` holding all `count` canvases at once, and **each one is yours to close**:

```scala mdoc:compile-only
val frames = Animation.frames(count = 3, width = 64, height = 64) { i =>
  Picture.circle(Point(32, 32), 10 + i * 6).fillColor(Color.Red).noStroke
}
try frames.foreach(img => println(img.width))
finally frames.foreach(_.close())
```

It is deliberately not a `LazyList`. A lazy sequence memoises: every canvas it has ever produced stays
reachable behind a few bytes of JVM heap, which gives the garbage collector no signal at all about the
multi-megabyte native buffer behind it — and if you close the images as instructed, a second traversal hands
back the same, now-spent handles and throws. That is the same argument [Video](/video) makes for having no
frame `LazyList`.

:::note[GIF vs video]
GIF is 256 colours per frame and OpenCV dithers to fit — great for a short, shareable loop. For full-colour
or long clips, encode a video with `record`. Its default [`Codec`](/video) is `Mjpg` in an `.avi`, the one
combination that opens on every build; `Mp4v`, `Avc1` and `Xvid` are smaller but depend on what the
platform's videoio links, and the extension must move with the codec. Both `gif` and `record` delete a
half-written output on a failed encode, so a `Left` never leaves a misleading partial file behind.
:::

## On the Doodle inspiration

Doodle is a beautiful functional-graphics library, and its core idea — a picture is an immutable value you
compose, not a sequence of side-effecting draw calls — is the one worth borrowing. scalacv adapts rather than
copies it: coordinates are image pixels with y down (not Doodle's centred, y-up plane), rendering is OpenCV
onto a `Mat`, styling is contextual over the same `Picture` tree, layout measures bounding boxes in pixel
space, and the whole thing folds back into the move-semantics [`Image`](/image-api). What's added for *this*
library's job — annotating computer-vision output — is the part Doodle's canvas backends give for free but
OpenCV does not: hand-rolled dashed strokes, per-shape alpha compositing, and label boxes sized to their
text.

## Next

- [Drawing](/drawing) — the low-level `Mat` draw verbs `Picture` builds on, and the `Font`/`Thickness` types.
- [Image API](/image-api) — the move-semantics `Image` that `render`/`draw` return and consume.
- [Video](/video) — the `Recorder` and `Codec` that `Animation.record` writes through.
