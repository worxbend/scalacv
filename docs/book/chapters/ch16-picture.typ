#import "../lib/book.typ": *

#chapter("The Picture Scene Graph", subtitle: [An overlay you can build, transform and test before a single pixel moves.])

Chapter 14 gave you a set of drawing verbs, and they are the right tool for exactly as long as an
overlay stays small. Two rectangles and a caption on a debug frame: `drawRect`, `drawText`, write it
out, look at it. Nobody needs a scene graph for that, and this chapter is not going to pretend
otherwise.

The trouble starts at about the fifth element. An annotated frame in a real system is a box per
detection, in a colour that encodes the class; a readable tag over each box, with the score to two
decimal places; a connector between the boxes the tracker believes belong to the same object; and a
legend in one corner mapping colour to class name. Written as drawing verbs, that is thirty or forty
imperative calls against a `Mat`, interleaved with the arithmetic that decides where each one goes,
all of it executing for its side effect and none of it producing a value you can hold.

Everything you would normally want to do with that overlay is then out of reach. You cannot render
it twice --- once at full resolution for the archive, once at a quarter size for the operator's
preview --- without running the whole thing again and hoping the two agree. You cannot move it,
because "it" is not a thing; it is a sequence of calls with pixel coordinates baked into each one.
You cannot check whether a legend entry landed six pixels too low without rendering it and looking,
because the only artefact it leaves behind is pixels. And you cannot reuse the legend in the report generator, because the legend is not
a value either.

`scalacv-graphs` answers this with one idea, borrowed from Doodle and adapted to image space: a
drawing is a value. `Picture` is an immutable description of what to draw --- shapes, styles,
transforms, groups --- built out of ordinary Scala data and owning no native memory. You assemble
it, transform it, measure it, compare it, cache it and pass it around like any other case class.
Rasterisation happens once, at the end, when you hand the finished value to an image. Up to that
moment nothing has touched a pixel.

#sect("A module of its own")

`Picture` does not live in the core artifact. It is published separately as `scalacv-graphs`, whose
whole dependency list is `core` and the same OpenCV Java bindings `core` already pulls in --- not
`scalacv-vision`, not any GUI toolkit. If you want it, add the second line:

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.1.0",
  mvn"com.worxbend::scalacv-graphs:0.1.0",
  // plus the two native lines for your platform, as in Chapter 2
)
```

The split is not ceremony: a service that decodes frames, thresholds them and writes a mask has no
use for a scene graph. What the extra dependency buys is `Picture` and its supporting types `Color`,
`Dash` and `Bounds`, plus `Chart`, `Animation`, and the `image.draw(picture)` extension that bridges
back to `Image` --- all of them in package `scalacv`, through the same `import scalacv.*` you
already have. The module boundary is a publishing decision, not a namespace.

#sect("The frame you cannot annotate twice")

The running example for this chapter is an inspection camera over a conveyor. A detector upstream
hands you a sequence of parts, each with a box, a class name and a confidence:

```scala
final case class Part(box: Rect, name: String, score: Double)
```

The obvious first attempt does not survive contact with Chapter 4's move semantics.

#example("Wrong. `drawRect` consumes the frame, so the second part throws.")[
```scala
Image.reading("belt.jpg") { frame =>
  parts.foreach(p => frame.drawRect(p.box, Scalar(0, 255, 0), Thickness.Stroke(2)))
  frame.write("belt-annotated.png")
}
```
]

`drawRect` is a transform: it spends the receiver and hands back the annotated image. The first
iteration takes the handle, and the second gets an `IllegalStateException` from a `Managed` that has
already been moved. The fix is to thread the image through, which is what a fold is for.

#example("Correct, and still nothing but a sequence of side effects.")[
```scala
Image.reading("belt.jpg") { frame =>
  parts
    .foldLeft(frame) { (img, p) =>
      img
        .drawRect(p.box, Scalar(0, 255, 0), Thickness.Stroke(2))
        .drawText(p.name, Point(p.box.x.toDouble, p.box.y - 6.0), Scalar(0, 255, 0), scale = 0.5)
    }
    .write("belt-annotated.png")
}
```
]

This is correct code and it will ship. It is also the last version of the overlay that fits in one
listing: add per-class colours, tags with backgrounds, connectors and a legend, and the fold's
accumulator becomes a hundred lines of arithmetic whose only output is a file you have to open in a
viewer to review.

#sect("A picture is a value")

Here is the same annotation as a `Picture`. Note what is absent: no `Image`, no `Mat`, no `Scalar`,
no ownership question, and nothing that has to run in a particular order.

#example("The per-part annotation, as data.")[
```scala
def annotate(part: Part, color: Color): Picture =
  Picture
    .roundedRectangle(part.box, radius = 6)
    .stroke(color, 2)
    .dashed
    .on(tag(f"${part.name} ${part.score}%.2f", part.box.topLeft, color))
```
]

`roundedRectangle(rect, radius)` is one of the companion's constructors, and it clamps the radius to
half the shorter side so an over-large corner degrades into a stadium rather than into nonsense;
`stroke(color, width)` sets the outline colour and width in one call; `dashed` asks for the
`Dash(10, 8)` preset; and `a.on(b)` draws `a` over `b`. The result is a small tree --- an overlay of
the styled box and the tag --- and it has drawn nothing.

The tag itself is worth building by hand, because it shows the one combinator that makes layout
possible --- measurement:

#example("A tag: text over a background box, sized from the text's own extent.")[
```scala
def tag(text: String, at: Point, color: Color): Picture =
  val glyphs = Picture.text(text, at).strokeColor(Color.Black).fontScale(0.45)
  glyphs.bounds match
    case Some(b) =>
      val box = Rect(
        (b.minX - 4).round.toInt,
        (b.minY - 3).round.toInt,
        (b.width + 8).round.toInt,
        (b.height + 6).round.toInt
      )
      glyphs.on(Picture.rectangle(box).fillColor(color).noStroke)
    case None => glyphs
```
]

`bounds` returns `Option[Bounds]` --- `None` for a picture that draws nothing, a `Bounds` with
`minX`, `minY`, `maxX`, `maxY`, `width`, `height`, `centerX` and `centerY` otherwise. For text it is
not a guess: the measurement runs the string through `Draw.textSize` with the style's own font and
scale, so the descenders on a `g` or a `y` are inside the box rather than clipped by it.

There is a ready-made version of the whole tag on the companion, and it is worth reading its
signature closely before you reach for it:

```scala
Picture.label(
  text: String,
  at: Point,
  textColor: Color = Color.White,
  background: Color = Color.Black,
  padding: Int = 4,
  fontScale: Double = 0.5,
  font: Font = Font.Simplex
): Picture
```

`at` is the box's top-left, not the text's baseline, and the box comes out
`size.width + 2·padding` by `size.height + baseline + 2·padding` --- that `baseline` term being the
descender room a `y` or a `g` needs, measured rather than guessed.

#warning[
  Read `label`'s composition before you ship it. It builds the tag as
  `text.under(rectangle(box).fillColor(background).noStroke)`, and `under` is
  `Over(over, this)` --- so the filled box is the *top* layer and the glyphs the bottom one.
  Rendering draws the bottom first and the top over it, and an opaque `background` therefore paints
  out the very text it was meant to sit behind. Until that composes with `on` instead, build the tag
  yourself as `glyphs.on(box)`, exactly as `tag` above does.
]

#sect("The primitives")

Every constructor on the `Picture` companion returns a `Picture`, so every one of them styles,
transforms, measures and composes the same way. There is no separate "shape" type and no builder.

#figure-table("Every `Picture` constructor, with the parameters that have defaults spelled out.")[
#tbl(
  columns: (1fr, 1.5fr),
  [Constructor], [Signature],
  [`circle`], [`circle(center: Point, radius: Double)`],
  [`rectangle`], [`rectangle(rect: Rect)`],
  [`roundedRectangle`], [`roundedRectangle(rect: Rect, radius: Double)`],
  [`line`], [`line(from: Point, to: Point)`],
  [`polyline`], [`polyline(points: Seq[Point], closed: Boolean = false)`],
  [`polygon`], [`polygon(points: Seq[Point])`],
  [`text`], [`text(text: String, at: Point)`],
  [`ellipse`], [`ellipse(center, rx, ry, rotation = 0, segments = 64)`],
  [`arc`], [`arc(center, rx, ry, startDegrees, endDegrees, rotation = 0, segments = 48)`],
  [`sector`], [`sector(center, rx, ry, startDegrees, endDegrees, rotation = 0, segments = 48)`],
  [`curve`], [`curve(p0, c0, c1, p1, segments = 32)`],
  [`quadraticCurve`], [`quadraticCurve(p0, control, p1, segments = 24)`],
  [`regularPolygon`], [`regularPolygon(center, sides, radius, rotation = 0)`],
  [`star`], [`star(center, points, outer, inner, rotation = 0)`],
  [`dot`], [`dot(at: Point, radius: Double = 3)`],
  [`marker`], [`marker(at: Point, color: Color, radius: Double = 3)`],
  [`cross`], [`cross(at: Point, size: Double = 5)`],
  [`arrow`], [`arrow(from, to, headLength = 12, headAngle = 28)`],
  [`label`], [`label(text, at, textColor = White, background = Black, padding = 4, fontScale = 0.5, font = Simplex)`],
  [`empty`], [`val empty: Picture`],
  [`all`], [`all(pictures: Seq[Picture])`],
  [`grid`], [`grid(pictures: Seq[Picture], columns: Int, gap: Double = 8)`],
)
]

Underneath there are exactly four drawing primitives --- a circle, a quad, a path and a run of text
--- and everything else in that table is built from them. `ellipse`, `arc`, `sector`,
`roundedRectangle`, `curve`, `quadraticCurve`, `regularPolygon` and `star` all reduce to a path with
enough points to look smooth, which is why they fill, dash and transform with exactly the same rules
as a hand-written `polygon`. `segments` is your control over that trade: drop it for a hundred small
markers per frame, raise it for a full-screen arc.

`regularPolygon` requires at least three sides, `star` at least two points and `grid` at least one
column; each of those is a `require`, so a nonsensical value throws where you wrote it rather than
producing a degenerate shape three layers down.

#note[
  Coordinates are image pixels: the origin is the top-left corner and `y` increases downward, the
  same convention as every `Rect` a detector hands you. A detection box therefore needs no
  conversion --- `Picture.rectangle(part.box)` is already in the right space. The cost of that
  choice is that positive rotation angles turn clockwise, which is the opposite of the mathematical
  convention and the same as everything else in OpenCV.
]

#sect("Styling, and the rule that surprises people")

Styling methods return a new picture wrapping the old one, carrying a function that adjusts the
style in effect. An unstyled picture is drawn with a white one-pixel outline, no fill, `Font.Simplex`
at a font scale of `0.5`, and antialiasing on.

#figure-table("The styling combinators. Each returns a new `Picture`; none mutates.")[
#tbl(
  columns: (1fr, 1.6fr),
  [Method], [Effect],
  [`strokeColor(c)`], [Outline colour],
  [`strokeWidth(w)`], [Outline width, clamped to at least 1],
  [`stroke(c, w)`], [Both, in one call (`w` defaults to 1)],
  [`noStroke`], [No outline at all],
  [`fillColor(c)`], [Fill colour --- closed shapes only],
  [`noFill`], [No fill],
  [`strokeDash(d)`], [A dash pattern],
  [`dashed` / `dotted`], [`Dash(10, 8)` / `Dash(1, 6)`],
  [`solidStroke`], [Clears an inherited dash],
  [`font(f)` / `fontScale(s)`], [Text font and size],
  [`smooth(on)`], [Antialiasing, on by default],
)
]

A style set on a group is a default that its members inherit unless they set their own. That is the
behaviour you want --- `Picture.all(everything).strokeColor(Color.Green)` colours a whole overlay,
and any member that already asked for red keeps it --- and it follows from one rule about how the
tree is walked: styles are applied from the outside in, so *the setting nearest the primitive wins*.

The consequence catches everyone once. Chained styling calls are not last-write-wins:

```scala
val a = Picture.circle(c, 10).strokeColor(Color.Red).strokeColor(Color.Blue)  // draws RED
```

`.strokeColor(Color.Red)` produced the inner node and `.strokeColor(Color.Blue)` wrapped it, so red
is applied last and blue never survives. The same rule explains why `Picture.dot` --- which is
defined as a circle with a white fill and no stroke --- ignores a fill you set afterwards:

#example("Wrong, then right: restyling a picture that already styled itself.")[
```scala
Picture.dot(p).fillColor(Color.Red)          // still white: dot's own fill is nearer the leaf
Picture.marker(p, Color.Red)                 // right --- marker takes the colour as an argument
Picture.circle(p, 3).fillColor(Color.Red).noStroke   // or style a bare primitive yourself
```
]

Read `solidStroke` the same way: it clears a dash inherited from an enclosing group, not one already
set closer to the shape.

#subsect("Dashed strokes")

OpenCV has no dashed line. `Imgproc.line` draws solid, and that is the whole of it. `Picture`
supplies dashes by walking each segment of a path and emitting one short `line` call per dash, so
`Dash(on, off)` alternates run lengths in pixels along the path --- both must be positive, which
`Dash` enforces with a `require`.

#figure-table("The three dash presets.")[
#tbl(
  columns: (1fr, 1fr, 2fr),
  [Preset], [Pattern], [Reads as],
  [`Dash.dashed`], [`Dash(10, 8)`], [Long dashes],
  [`Dash.dense`], [`Dash(4, 4)`], [Tight dashes],
  [`Dash.dotted`], [`Dash(1, 6)`], [Dots],
)
]

A dashed circle is the one place the representation shows through: with no dash a circle goes to
`Imgproc.circle`, and with a dash it is redrawn as a 48-sided polygon so the dashes can be spaced
along it. At any radius you would put on a frame the difference is under a pixel.

#sect("Transforms and layout")

`translate(dx, dy)`, `at(point)`, `rotate(degrees, about = Point(0, 0))` and
`scale(factor, about = Point(0, 0))` each wrap a picture in an affine transform. Unlike styles,
transforms chain in reading order: `p.rotate(30, c).translate(10, 0)` rotates and then translates,
because composition accumulates as the tree is walked and the outermost transform ends up applied
last.

The transform reaches everything a shape is made of. A rotated rectangle is genuinely turned,
because its four corners are transformed as points. `scale` also scales circle radii and text,
through a uniform scale factor derived from the transform's determinant --- which is what makes it
possible to author an overlay once at full-frame coordinates and render it into a preview:

#example("One overlay, two resolutions, from the same value.")[
```scala
Image.reading("belt.jpg") { frame =>
  val small = frame.copy.scale(0.5).draw(overlay.scale(0.5)).write("belt-preview.png")
  val full = frame.draw(overlay).write("belt-full.png")
  small.flatMap(_ => full)
}
```
]

Read the ownership as carefully as the geometry. `frame.copy` clones the pixels so the original
survives; `scale`, `draw` and `write` then consume that clone in turn, and `write` releases it. Only
after the preview is on disk does `frame` itself get spent. Running both writes to completion before
combining the two `Either`s is deliberate: a `for` comprehension would short-circuit on the first
`Left` and leave the second canvas unwritten and unreleased.

#warning[
  A transform moves text but does not turn it. OpenCV's `putText` has no rotation parameter, so a
  rotated `Picture.text` is placed at the rotated position and drawn upright. Scale is honoured ---
  the renderer multiplies the style's `fontScale` by the transform's scale factor --- and rotation is
  not. `bounds`, meanwhile, transforms the measured box like any other geometry, so under a rotation
  it reports a turned rectangle the renderer will never draw, and laying out rotated text with
  `beside` or `above` will not line up. If you need turned lettering, you are drawing it as paths.
]

On top of transforms sit three layout combinators that place pictures relative to one another by
measuring them, so you never compute an offset by hand.

#figure-table("Composition and layout.")[
#tbl(
  columns: (1fr, 1.7fr),
  [Combinator], [Meaning],
  [`a.on(b)` / `a.under(b)`], [Overlay `a` over / under `b`],
  [`Picture.all(seq)`], [Group a sequence, the first element at the bottom],
  [`a.beside(b, gap = 8)`], [Place `b` to the right of `a`, vertical centres aligned],
  [`a.above(b, gap = 8)`], [Place `b` below `a`, horizontal centres aligned],
  [`Picture.grid(seq, columns, gap = 8)`], [Row-major grid, every cell sized to the largest picture],
  [`a.bounds`], [The axis-aligned extent, as `Option[Bounds]`],
)
]

`beside` and `above` measure both boxes and place the second relative to the first. If either
picture measures nothing they degrade rather than throw: laying an empty path beside a square gives
back the square. That matters more than it sounds, because an empty path is the ordinary result of
filtering a set of detections down to none. `grid` differs deliberately: it places by index, so a
measureless picture keeps its cell and the rest of the grid stays aligned.

`Picture.empty` is the identity for `on`, and it measures as `None`, so every layout combinator
passes it straight through. That makes it the seed a fold wants:

#example("The legend: a swatch beside a name, stacked, with no offsets computed by hand.")[
```scala
val palette: Seq[Color] = Color.wheel(classes.size)

def swatch(name: String, color: Color): Picture =
  Picture
    .rectangle(Rect(0, 0, 14, 14))
    .fillColor(color)
    .noStroke
    .beside(Picture.text(name, Point(0, 11)).strokeColor(Color.White).fontScale(0.45), gap = 6)

val legend =
  classes
    .zip(palette)
    .map((name, color) => swatch(name, color))
    .foldLeft(Picture.empty)(_.above(_, gap = 6))
    .translate(12, 12)
```
]

The whole overlay is then one expression, and the connectors that link consecutive parts are three
lines of it:

#example("The finished overlay.")[
```scala
def centre(r: Rect): Point = Point(r.x + r.width / 2.0, r.y + r.height / 2.0)

val connectors = Picture.all(
  parts.sliding(2).collect { case Seq(a, b) =>
    Picture.arrow(centre(a.box), centre(b.box)).stroke(Color.White.fadeOut(0.4), 1)
  }.toSeq
)

val overlay =
  legend
    .on(Picture.all(parts.zip(palette).map((p, c) => annotate(p, c))))
    .on(connectors)
```
]

#sidebar("Doodle, adapted rather than copied")[
  The picture-as-a-value idea, the `on` composition, `strokeColor`/`fillColor`/`strokeDash`, and the
  `beside`/`above` layout all come from Doodle, the Creative Scala functional-graphics library. What
  changed is everything that touches the domain. Doodle's plane is centred and `y`-up; this one is
  image pixels, top-left origin, `y` down, so a detector's `Rect` drops straight in. Doodle renders
  through Java2D or a canvas; this renders through `Imgproc` onto a `Mat` you already own. And three
  things are here that a general graphics backend gives you for free and OpenCV does not: dashed
  strokes, per-shape alpha compositing, and text measured by its real font metrics --- most of what
  annotating computer-vision output actually needs.
]

#sect("Colour: RGBA here, BGR underneath")

`Color` is the graphics layer's palette: a case class of `red`, `green`, `blue` and `alpha`, each an
`Int` in `[0, 255]`, with `alpha` defaulting to `255` and a `require` that rejects anything outside
the range. It is a different type from core's `Scalar` on purpose, and the two differ in both
channel order and meaning.

#figure-table("The two colour types, and which layer uses which.")[
#tbl(
  columns: (1fr, 1fr, 1fr),
  [], [`Scalar` (core)], [`Color` (graphs)],
  [Channel order], [BGR --- `Scalar.Red` is `Scalar(0, 0, 255)`], [RGB --- `Color.Red` is `Color(220, 40, 40)`],
  [Alpha], [A fourth channel the drawing verbs ignore], [Honoured: real per-shape blending],
  [Range], [`Double`, unchecked], [`Int` in `[0, 255]`, checked],
  [Used by], [`drawRect`, `drawText`, `Image.blank`, `org.opencv.*`], [`Picture` styling, `Chart`, `Animation`],
  [Bridge], [`scalar.toColor`], [`color.toBgrScalar`],
)
]

Construction goes through `Color.rgb(r, g, b)`, `Color.rgba(r, g, b, a)`, `Color.gray(v)`, the case
class directly, or `Color.hsl(hue, saturation, lightness, alpha = 255)` --- hue in degrees, wrapped
into a turn, saturation and lightness in `[0, 1]`, clamped. The alpha is not clamped: it goes
straight into the case class, so it still has to be a legal channel value. Fifteen named constants
cover the usual overlay work, from `Color.Transparent` through `Color.Black`, `Color.White`, three
greys and the nine hues.

`lighten(a)` and `darken(a)` blend toward white and black; `blend(other, a)` mixes; `withAlpha(a)`
sets alpha outright and `fadeOut(a)` reduces it by a fraction; and `spin(degrees)`, `complement`,
`saturate(a)` and `desaturate(a)` round-trip through `hsl`, which is also readable directly as a
`(hue, saturation, lightness)` tuple. `desaturate(1)` is grey.

Two generators produce palettes, and choosing between them is a statement about your data:

- `Color.wheel(n, saturation = 0.65, lightness = 0.55)` spaces `n` hues evenly around the wheel.
  Categorical: use it for classes, tracks, series --- things with no order.
- `Color.ramp(from, to, n)` blends `n` steps between two colours. Sequential: use it for a heat
  scale, a confidence gradient, anything ordered.

`Color.categorical` is `wheel(8)`, kept around as a sensible default. Both generators accept `n = 0`
and refuse a negative one.

The bridge is lossy in exactly one way, and it is documented rather than hidden: `color.toBgrScalar`
reorders to BGR and *drops the alpha*, because a `Scalar`'s fourth channel is not an alpha the
OpenCV drawing verbs honour. If you want translucency baked into a colour you are handing to
`drawRect`, pre-blend it with `blend` against the background you expect. Going the other way,
`scalar.toColor` rounds and clamps each channel into `[0, 255]` and returns a fully opaque colour,
so `Scalar(-10.0, 127.6, 300.0).toColor` is `Color(255, 128, 0)`.

#sect("Compositing onto a frame")

Three calls turn a `Picture` into pixels, and they differ only in where the pixels come from.

#figure-table("The three ways to rasterise, and what each one does with ownership.")[
#tbl(
  columns: (1.3fr, 1.4fr, 1.3fr),
  [Call], [Canvas], [Ownership],
  [`picture.render(w, h, background = Color.Black)`], [A fresh `w`×`h` image], [Returns an `Image` you own and must close],
  [`picture.renderOn(image)`], [An existing image], [Consumes `image`, returns the annotated one],
  [`image.draw(picture)`], [The same thing, from the image side], [Consumes `image`, returns the annotated one],
)
]

`image.draw(picture)` is the one you will write. It is an extension method on `Image`, defined in the
graphs module so that `Image` itself carries no dependency on the graphics layer, and it does no
copying: it takes the image's handle, renders the picture into the `Mat` in place, and rewraps the
same buffer as a new `Image`. That is why it consumes its receiver --- exactly like `blur`, `resize`
or any other transform in Chapter 4, and for the same reason.

#memory[
  A `Picture` owns no native memory. It is JVM data all the way down: case classes, a `Seq[Point]`
  or two, and some functions. Hold one for the life of the process, share it across threads, hand it
  to a hundred frames --- there is nothing in it to leak and nothing in it to close. What it is not
  is load-free: `bounds` measures text through `Draw.textSize`, and `Picture.label` calls that at
  construction time, so any picture that involves a string still wants `OpenCv.load()` to have run.
  Shapes on their own do not. The native object in this chapter is the `Image`, and it obeys the
  rules Chapters 4 and 5 set out --- `draw` and `renderOn` spend it, `render` hands you a new one
  that nothing will close for you, and the terminals (`write`, `bytes`, `close`) release.
  `frame.copy` before `draw` is how you keep the original, and it costs a full clone of the
  pixels.
]

Alpha is the one place the renderer needs a copy of your pixels. OpenCV's drawing functions are
opaque --- they overwrite what they touch and have no notion of blending --- so to honour a translucent
`Color`, `Picture` computes a conservative bounding box for the shape (stroke half-width, round
joins and antialiasing spread included, clipped to the image), saves that region, paints the shape
opaquely, and blends the region back toward what it covered. The arithmetic is identical to cloning
and blending the whole frame, but a small badge on a 4K frame costs a badge-sized clone instead of a
frame-sized one. Both temporaries go through `Managed.use`, so they are released even if the draw
throws.

#tip[
  Antialiasing is on by default and is what makes an overlay look drawn rather than stamped. Turn it
  off with `smooth(false)` when you need exact pixel values --- a mask you are going to threshold, or
  a test that asserts a specific channel value at a specific coordinate.
]

#sect("Testing a picture without rendering it")

Because a picture is data, most of what you want to assert about an overlay needs no image, no
canvas and no rendered pixel. The layout is the part that actually breaks, and the layout is what
`bounds` reports. Keep one honest caveat in view: measuring text is a call into `getTextSize`, so a
suite that lays out a label still opens with `OpenCv.load()` in `beforeAll`, exactly as the
library's own graphics suites do. What it does not need is a canvas, an encoder, or a fixture image
on disk.

#example("Layout assertions that never touch a pixel.")[
```scala
test("the legend stacks one row per class"):
  val legend = buildLegend(Seq("bolt", "nut", "washer"))
  val b = legend.bounds.get
  assert(b.height > 3 * 14, "three 14-pixel swatches plus the gaps")

test("beside places the second shape to the right of the first"):
  val a = Picture.rectangle(Rect(0, 0, 20, 20))
  val b = Picture.rectangle(Rect(0, 0, 20, 20))
  assertEquals(a.beside(b, gap = 10).bounds.get.width, 50.0)   // 20 + 10 + 20

test("a frame with no detections annotates to nothing"):
  assertEquals(buildOverlay(Seq.empty).bounds, None)
```
]

That last one is the assertion an imperative overlay cannot express at all. "Draws nothing" is a
property of a value; it is not a property of a sequence of calls that happened not to execute.

Structural equality works too, with one caveat worth knowing before it wastes an afternoon. The
primitives are case classes, so two identically-built unstyled shapes compare equal --- but a styling
combinator stores a function in the tree (a `Style => Style`, over a `Style` the module keeps to
itself), and two separately-constructed lambdas are never equal to each other.

#warning[
  `Picture.circle(c, 10) == Picture.circle(c, 10)` is `true`.
  `Picture.circle(c, 10).strokeColor(Color.Red) == Picture.circle(c, 10).strokeColor(Color.Red)` is
  `false`, because each `strokeColor` call captured its own closure. Assert on `bounds`, on the
  values you fed into the builder, or --- when the question really is about colour --- on rendered
  pixels. Do not assert on `==` between two styled pictures.
]

When the question genuinely is "what colour is that pixel", render a small canvas and read it back.
`smooth(false)` makes the answer exact, and the image is yours to close:

```scala
val img = Picture.circle(Point(50, 50), 20).fillColor(Color.Red).noStroke.render(100, 100, Color.Black)
try assert(img.mat.get(50, 50)(2) > 180, "the centre should be red")
finally img.close()
```

Two conventions bite in that one line. `Mat.get` takes `(row, column)` --- `y` first, then `x`,
which is why the library's own graphics suites define a `px(img, x, y) = img.mat.get(y, x)` helper
rather than calling `get` directly. And the array it returns is in the `Mat`'s BGR order, so red is
index `2` while `Color.Red` names it first. That is the `Color`/`Scalar` distinction showing up in a
test.

#sect("When to stay with the drawing verbs")

None of this deprecates Chapter 14. `Picture` bottoms out in the same `Imgproc` calls those verbs
make, and it costs a tree walk, some `Option` handling and a second artifact on the classpath. When
the overlay is two
rectangles and a caption on a frame you are about to look at once, the verbs are shorter:

```scala
Image.reading("debug.png")(_.drawRect(roi, Scalar.Green).drawText("roi", roi.topLeft).write("out.png"))
```

Reach for a `Picture` when the overlay has structure --- when something is composed from parts, laid
out relative to something else, rendered at more than one size, reused in more than one place, or
worth a test. Reach for a drawing verb when you want to see one thing, now. Both arrive through the
same `import`, and mixing them within one program is normal.

#sect("Next")

The `Picture` algebra was designed for annotation, but nothing in it knows what it is drawing. A bar
chart is a stack of rectangles laid out with the same combinators; an animation is a picture valued
by frame number. Chapter 17, #emph[Charts], takes the value you have been building here and points it at
data: `Chart.bars`, `line`, `area`, `scatter`, `pie` and `histogram`, six functions that each hand
back a `Picture` sized to a `width`×`height` box, so a plot drops into the corner of a frame with
`chart.at(Point(x, y))` and needs no new vocabulary at all. Chapter 18, #emph[Animation and GIF], points
it at time instead --- `Animation.record` to a video, `Animation.gif` to the format nobody has to
install anything to watch, `Animation.frames` for a `Seq[Image]` whose lifetimes are yours to get
right, and `Animation.foreach` for the streaming form that closes each canvas for you.
