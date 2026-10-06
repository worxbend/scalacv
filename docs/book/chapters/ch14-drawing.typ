#import "../lib/book.typ": *

#chapter(
  "Drawing and Annotation",
  subtitle: [Putting marks on an image, on a machine that has no screen.],
)

A vision pipeline does not throw when it is wrong. A detector hands back four boxes; three of them
sit on faces and the fourth sits on a door handle, and nothing in the return type distinguishes
them. A contour filter keeps the blobs above 400 pixels of area and quietly discards the one you
cared about because a shadow split it in two. A threshold that was tuned indoors leaks half the
background outdoors, and the only symptom is that a centroid drifts. None of this produces a stack
trace, a log line, or a failed assertion. It produces plausible numbers.

The way you find out is to draw the answer back onto the image and look. That is not a debugging
convenience layered on top of computer vision --- it is the primary instrument. A green rectangle
in the wrong place tells you in one glance what an hour of reading coordinates will not, and the
same rectangle burned into a saved frame is the artefact you attach to a bug report, hand to a
reviewer, or diff against last week's build.

Which is awkward, because the machine that runs the pipeline is a container on a build agent with
no display, no X server and no window manager. OpenCV's own answer to "look at the image" is
`imshow`, which lives in the highgui module and wants a GUI toolkit present at build and run time;
on a headless runner it is either absent or it opens nothing. scalacv does not have a display window
at all, and does not want one. Annotation here is ordinary raster work: you paint into the pixels,
then `write` the result or hand the encoded bytes to whatever is going to serve them.

That is why the drawing primitives live in `core` rather than in a module of their own. `Draw.scala`
references nothing from highgui and nothing from JavaFX. The same handful of functions that produce
a review JPEG also burn a timestamp into a recorded frame and rasterise a polygon into a mask, and
splitting `core` in two to separate them would draw a line that does not exist in the work.

The running example for this chapter is a batch job of the kind that annotation exists for: a gate
camera drops stills into a directory, a motion stage has already reduced each one to a handful of
`Rect` regions, and the job's task is to write a version of the still that a human on a review queue
can judge in two seconds. It starts as one line and ends up as something that stays readable over a
sunlit driveway.

#sect("Drawing mutates, and says so")

Everywhere else in scalacv an operation leaves its input alone and returns a fresh, caller-owned
result. Drawing is the deliberate exception. Every primitive mutates the image it is handed and
returns `Unit`:

```scala
def drawRect(
    rect: Rect,
    color: Scalar = Scalar.White,
    thickness: Thickness = Thickness.Default,
    lineType: LineType = LineType.Connected8
): Unit
```

The reason is that OpenCV's drawing functions have no out-of-place form. `cv::rectangle` rasterises
straight into the buffer you give it; there is no variant that returns a new image. Wrapping that in
a pure-looking signature would mean cloning a full frame for every annotation, and a per-frame
overlay --- five boxes, five labels, a HUD line --- cannot afford five clones of a 1080p frame at
thirty frames a second. So the library keeps the mutation and makes it impossible to miss: every
operation is named `draw…` or `fill…`, and every one returns `Unit`, so no call site can mistake one
for a transform.

The primitives are extension methods on `org.opencv.core.Mat`, brought in by `import scalacv.*`.
They are the mid-level tier: you allocated the `Mat`, you draw into it, you release it. `Mat` is not
a scalacv type and the library does not re-export it, so a file that works at this tier imports it
from OpenCV alongside the library. The shape is then always make, draw, encode, release, and
`Managed.use` does the release:

#example("The mid-level shape: a Mat you own, drawn into and encoded.")[
```scala
import scalacv.*
import org.opencv.core.{CvType, Mat}

val bytes: Either[CvError, Array[Byte]] =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    canvas.drawLine(Point(10, 10), Point(190, 110), Scalar.Red, Thickness.Stroke(2))
    Images.encode(canvas, ".png")
  }
```
]

A `Mat` with no allocated data has nothing to draw into. Passing one to native code aborts with a
message that names neither the call nor the reason, so every operation checks first and throws
`IllegalArgumentException` --- `drawLine needs an image with data; this Mat is empty`. That is a
programmer error, not a data-dependent failure, so it throws rather than returning a `CvError`.

#sect("The primitives")

There are nine of them on `Mat`, and they divide by what they can be filled with. #api("Thickness")
is a sealed trait with two cases: `Thickness.Stroke(pixels)`, an outline at least one pixel wide, and
`Thickness.Filled`, a solid shape. `Thickness.Default` is `Stroke(1)` and is what every operation
uses when you say nothing.

The split exists because OpenCV encodes "filled" as a thickness of `-1`. That sentinel is a value
ordinary arithmetic on a thickness will happily produce by accident, and it is meaningful only for
closed shapes: `cv::line` asserts `0 < thickness`, so handing the fill sentinel to a line or to text
aborts in native code. Giving lines and text a parameter typed `Thickness.Stroke`, and shapes one
typed `Thickness`, turns that crash into a compile error. `Thickness.Stroke(0)` throws too, which
leaves `Filled` as the only way to say "solid".

#figure-table("The drawing primitives on `Mat`. Every one also takes `color: Scalar` and `lineType: LineType`.")[
#tbl(
  columns: (auto, 1fr, auto, auto),
  [Operation], [Shape arguments], [Thickness], [On `Image`],
  [`drawLine`], [`from: Point`, `to: Point`], [`Stroke`], [no],
  [`drawArrow`], [`from`, `to`, `tipLength: Double = 0.1`], [`Stroke`], [no],
  [`drawRect`], [`rect: Rect`], [`Thickness`], [yes],
  [`drawCircle`], [`center: Point`, `radius: Int`], [`Thickness`], [yes],
  [`drawText`], [`text: String`, `at`, `font`, `scale`], [`Stroke`], [partly],
  [`drawPolyline`], [`points: Seq[Point]`, `closed: Boolean = true`], [`Stroke`], [no],
  [`fillPolygon`], [`points: Seq[Point]`], [always filled], [no],
  [`drawContours`], [`contours: Seq[Contour]`], [`Thickness`], [yes],
  [`drawSegments`], [`segments: Seq[Segment]`], [`Stroke`], [no],
)
]

Two of those exist to make detector output visible. `drawContours` renders what `findContours`
returns, and with `Thickness.Filled` it is the usual way to turn a set of contours back into a mask.
`drawSegments` renders the `Seq[Segment]` that `houghLinesP` produces, which is otherwise a list of
four-integer tuples nobody can read.

Coordinates outside the image are clipped rather than rejected --- OpenCV's behaviour, and what
makes drawing a detection that runs off the edge of a frame safe. A face box regressed from an
anchor at the frame boundary legitimately has a negative `x`, and the box still draws. A negative
radius on `drawCircle` is a different matter: that is a mistake, not an edge case, so it is rejected
up front. And an empty point list is a no-op rather than a failure, because a polyline is frequently
the output of a filter and filtering everything away is a legitimate result.

`drawArrow`'s `tipLength` is a fraction of the whole line's length rather than a pixel count, which
is OpenCV's convention and the right one: a flow field whose arrows vary from three pixels to three
hundred keeps proportionate heads without any work at the call site. It sits last in the signature,
after `lineType` rather than beside the geometry, because it reaches an eight-argument
`Imgproc.arrowedLine` overload the shorter forms cannot; pass it by name.

#memory[
`drawPolyline`, `fillPolygon` and `drawContours` are not leak-free, and it would be dishonest to
imply otherwise. scalacv allocates one `MatOfPoint` per polygon and releases every one of them in a
`finally`. But the generated Java binding for these three then runs the list through
`Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus one for the outer
vector and releases none of them. That residue is upstream in the official Java API and cannot be
fixed from here without reimplementing the converter. It is bounded per call and unbounded across a
video loop, so the number of calls is the variable you control: batch every polygon you have into as
few `drawContours`, `drawPolyline` and `fillPolygon` calls as you can --- one call for fifty contours
strands fifty-one headers, fifty calls strand a hundred --- and watch RSS rather than heap, because
none of this residue is on the Java side. `drawRect`, `drawCircle`, `drawLine` and `drawText` are
unaffected --- they take plain values, not vectors.
]

Two shapes are absent that people go looking for: there is no `drawEllipse` and no marker glyph.
Both exist one layer up, in the `Picture` scene graph of the `scalacv-graphs` module, as
`Picture.ellipse` and `Picture.arc`; the last section of this chapter says when crossing that line is
the right move.

#sect("Colour is a Scalar, and a Scalar is BGR")

Colour is not a type in this library. It is a `Scalar` --- up to four channel components, in whatever
channel order the `Mat` uses --- and OpenCV's default order is blue, green, red.

#warning[
`Scalar(255, 0, 0)` is blue. Every time a rectangle comes out the wrong colour, this is why. The
`Scalar` is a raw pixel value, so it inherits the channel order of the image it is written into, and
for the 3-channel images OpenCV decodes by default that order is BGR.
]

The named constants encode the order so you do not have to think about it, and they are the whole
palette --- five of them, on the `Scalar` companion.

#figure-table("The named colours, and what they are in channel order.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Constant], [Value], [Note],
  [`Scalar.Black`], [`Scalar(0, 0, 0)`], [the default fill of `Image.blank`],
  [`Scalar.White`], [`Scalar(255, 255, 255)`], [the default `color` of all nine `Mat` primitives],
  [`Scalar.Red`], [`Scalar(0, 0, 255)`], [not `(255, 0, 0)`],
  [`Scalar.Green`], [`Scalar(0, 255, 0)`], [the default of `drawRects`, `markFaces`, `drawTracks`, `drawSkeleton`],
  [`Scalar.Blue`], [`Scalar(255, 0, 0)`], [not `(0, 0, 255)`],
)
]

Anything else you write by hand, in channel order: `Scalar(0, 191, 255)` is amber. Unset channels
default to `0`, so `Scalar(255)` on a single-channel mask is white and needs no padding.

#sect("Text, and the plate that makes it readable")

Here is the first version of the gate-camera annotator, and it is wrong in a way that costs
everybody an afternoon at least once:

#example("Text at the wrong anchor. Nothing appears.")[
```scala
Image.reading("gate-0417.jpg") { img =>
  img.drawRects(regions, Scalar.Green, Thickness.Stroke(2))
     .drawText(s"${regions.size} regions", Point(12, 0))
     .write("review/gate-0417.jpg")
}
```
]

The boxes appear. The label does not. `at` is not the top-left corner of the text: OpenCV anchors a
string at the left end of its baseline, so a `y` of `0` puts every ascender above the top of the
image and leaves only the descenders of a `g` or a `y` --- often nothing at all --- inside the frame.
Move the anchor down past the cap height and the label appears:

```scala
.drawText(s"${regions.size} regions", Point(12, 24))
```

That is the fix for a label at a fixed corner. It is not a fix for a label that has to sit above a
detection box whose position you do not know, or for a label drawn over a photograph rather than a
blank canvas, where white text on a sunlit driveway is invisible. Both of those need the string's
dimensions, which is what `Draw.textSize` answers:

```scala
def textSize(
    text: String,
    font: Font = Font.Simplex,
    scale: Double = 1.0,
    thickness: Thickness.Stroke = Thickness.Default
): TextMetrics
```

It is the one part of drawing that asks a question instead of changing an image, and its parameters
are deliberately the same ones `drawText` takes --- if they do not match the call you are about to
make, the answer does not describe it. What comes back is a `TextMetrics(size: Size, baseline: Int)`,
and the `baseline` is separate for a reason: it is how far the descenders reach below the anchor, so
a box that encloses the whole string has to be `size.height + baseline` tall. Leave it out and every
`g`, `y` and `p` is clipped by the bottom edge of the plate you drew to make them readable.

With that, the label helper the annotator actually needs. It measures, draws a filled plate with a
little padding, and puts anti-aliased text on top:

#example("A label that stays readable over any background.")[
```scala
def label(
    mat: Mat,
    text: String,
    at: Point,
    scale: Double = 0.6,
    fg: Scalar = Scalar.White,
    bg: Scalar = Scalar.Black,
    padding: Int = 3
): Unit =
  val m = Draw.textSize(text, scale = scale)
  val plate = Rect(
    (at.x - padding).toInt,
    (at.y - m.size.height - padding).toInt,
    (m.size.width + 2 * padding).toInt,
    (m.size.height + m.baseline + 2 * padding).toInt
  )
  mat.drawRect(plate, bg, Thickness.Filled)
  mat.drawText(text, at, fg, scale = scale, lineType = LineType.AntiAliased)
```
]

`LineType.AntiAliased` is worth the cost on text and on any diagonal a human will look at closely;
`Connected8` is the default and is fine for boxes. `Connected4` is the third case and produces
slightly thinner diagonals.

Now the annotator can put a label on every region, anchored immediately above each box:

#example("Every region boxed and labelled, in one pass over the borrowed Mat.")[
```scala
Image.reading("gate-0417.jpg") { img =>
  regions.zipWithIndex.foreach: (r, i) =>
    img.mat.drawRect(r, Scalar.Green, Thickness.Stroke(2))
    label(img.mat, s"region ${i + 1}", Point(r.x.toDouble, (r.y - 6).toDouble))

  label(img.mat, s"${regions.size} regions · gate · 04:17", Point(12, 26), scale = 0.7)
  img.write("review/gate-0417.jpg")
}
```
]

#sidebar("Why the fonts look like a plotter drew them")[
OpenCV cannot render a system font. The only faces it has are the Hershey fonts --- vector glyph
sets digitised by Allen V. Hershey at the US Naval Weapons Laboratory in the 1960s, designed to be
drawn by a pen plotter as sequences of straight strokes rather than filled outlines. That is why
they scale smoothly at any `scale` and why they thicken by stroke width instead of by weight.

scalacv exposes six of those faces, as the `Font` enumeration: `Simplex` (plain sans, the default),
`Plain` (small and thin), `Duplex` (double-stroke, heavier), `Complex` (serif), `Triplex`
(triple-stroke serif, heaviest) and `Script` (handwriting-style). The enumeration has no bold flag
and no italic case --- `Duplex` and `Triplex` are what "bolder" means here, and beyond them the only
lever is `thickness`.

The other consequence is ASCII. A non-ASCII character is drawn as `?`, silently. If your labels
carry names, currency symbols or anything accented, OpenCV text is not the renderer for them; draw
the overlay geometry here and composite real typography elsewhere.
]

#sect("Two tiers, and who owns the Mat")

Everything so far has been the mid-level tier, reached through `img.mat`. There is a higher one:
`Image` exposes an everyday subset of the same operations as chainable transforms. `drawRect`,
`drawCircle`, `drawText`, `drawContours` and `drawRects` are members of `Image`, and each one
consumes the image it is called on and returns a fresh one, so annotation reads as a pipeline that
ends in a terminal:

#example("The high-level tier: annotation as a chain that ends in a terminal.")[
```scala
val annotated: Either[CvError, Array[Byte]] =
  Image.blank(width = 200, height = 120)
    .drawRect(Rect(20, 20, 70, 60), Scalar.Green)
    .drawCircle(Point(150, 60), 30, Scalar.Red, Thickness.Filled)
    .drawText("scene", Point(16, 100), Scalar.White)
    .bytes(".png")
```
]

Nothing is copied by either tier. Internally an `Image` draw takes the `Mat` out of its handle,
mutates it in place, and rewraps it in the returned `Image` --- the move that makes the old handle
throw `IllegalStateException` rather than alias live pixels. The difference between the tiers is not
performance, it is who tracks ownership: you, or the `Image`.

What the high-level tier costs you is knobs. `lineType` does not survive the trip up to `Image` on
any verb, and `Image.drawText` is `drawText(text, at, color, scale)` --- no `font`, no `thickness`.
`drawLine`, `drawArrow`, `drawPolyline`, `fillPolygon` and `drawSegments` have no `Image` form at
all. Going the other way, `drawRects` exists only on `Image`: it paints a whole `Seq[Rect]` in one
pass, which is the shape detector output actually arrives in, and it is the one place the high-level
tier is the more convenient of the two.

You do not have to choose per pipeline, only per line. `img.mat` is a query --- it borrows the handle
and leaves the `Image` alive --- so a chain can drop to the mid-level tier for one bold, anti-aliased
heading and pick up again on the next line, all writing into the same pixels.

#example("Mixing the tiers: the same Mat, two signatures.")[
```scala
val img = Image.blank(200, 120)
img.mat.drawArrow(Point(10, 60), Point(120, 60), Scalar.Green)
img.mat.drawText(
  "bold",
  Point(10, 30),
  Scalar.White,
  font = Font.Duplex,
  scale = 0.8,
  thickness = Thickness.Stroke(2),
  lineType = LineType.AntiAliased
)
img.drawText("go", Point(130, 66), Scalar.White).bytes(".png")
```
]

#memory[
`img.mat` hands you the underlying `Mat` without transferring ownership. Draw on it, pass it to a
detector, read from it --- but do not call `release()` on it. The `Image` still believes it owns that
buffer, and the next transform in the chain will read freed memory. Let a terminal (`write`, `bytes`)
or `close()` do the releasing, or take ownership explicitly with `managed`, which spends the `Image`
and hands the `Managed[Mat]` over.
]

#sect("Keeping the original")

A transform consumes its receiver, and a draw is a transform, so a single `Image` cannot be both
annotated and kept. When you need both --- the clean frame for the next pipeline stage, the marked-up
one for the review queue --- take a `copy` first. `Image.copy` is an independent deep copy, which is
to say a second full frame of native memory.

That is also the mechanism for a translucent overlay, which OpenCV has no direct support for: draw
the solid shapes onto a copy, then blend the copy back over the original. `Image.blend(other,
weight)` computes `this * weight + other * (1 - weight)`, borrowing `other` and consuming `this`:

#example("A translucent shade over a region, by drawing on a copy and blending back.")[
```scala
Image.reading("gate-0417.jpg") { img =>
  val shade = img.copy
  regions.foreach(r => shade.mat.drawRect(r, Scalar.Black, Thickness.Filled))
  try img.blend(shade, 0.65).write("review/gate-0417.jpg")
  finally shade.close()
}
```
]

Read the arithmetic where it matters. Outside the regions the copy is pixel-for-pixel the original,
so `0.65 × p + 0.35 × p` is `p` and the blend changes nothing. Inside them the copy is black, so what
survives is sixty-five per cent of the original and the region comes back dimmed by the remaining
thirty-five. Swap `Scalar.Black` for a colour and the same shape tints instead of dims; invert which
image is drawn on and it greys out everything the stage is *not* looking at. The weight is the only
knob, and `blend` rejects one outside `[0, 1]` with an `IllegalArgumentException` rather than letting
`addWeighted` clip its way to a plausible-looking wrong answer.

#memory[
`blend` borrows `other`; it does not consume it. The `copy` is yours, and nothing else will free it.
In the listing above the `finally` is doing real work: it releases the second frame whether the blend
succeeds, fails, or throws a `CvError.NativeCall`. In a per-frame loop the same shape without the
`finally` costs one whole frame per iteration, which is exactly the failure mode Chapter 5
measured --- a heap that stays small while the resident set climbs.
]

#sect("Overlays that know what they are drawing")

The nine primitives are general-purpose. `scalacv-vision` builds one-call overlays on top of them,
each turning a typed result straight into pixels, and each living beside the type it renders rather
than in `Image`. They are extension methods on `Image` in `scalacv.vision`, so add the vision
dependency and `import scalacv.vision.*` alongside `import scalacv.*`. Every one is built on the
same internal `paint` that the `Image` draw verbs use --- so each consumes its receiver and hands
back a fresh `Image`, exactly like `drawRect`.

`markFaces(faces, color = Scalar.Green)` draws a box per `Face` and a filled dot per landmark --- the
one-call "show me what YuNet found". `drawSkeleton(pose, minScore = 0.3f, color, jointColor)` draws
a line per bone and a dot per confident keypoint. `drawTracks(tracks, color)` draws a box per
`ObjectTrack` with its id above it. `drawMarkerAxes` and `drawMarkerCube` project a 3-D coordinate
frame or a wireframe cube onto every ArUco marker in the image, given the camera `Intrinsics` and the
marker's real side length; the axes are red, green and blue for X, Y and Z, which is the classic
"is my pose right?" overlay.

None of these is doing anything you could not write yourself from this chapter. `markFaces` is a
`drawRect` on the face box and a filled `drawCircle` of radius 2 on each landmark, and that is the
entire body. They earn their place by being one call at the point where you need them, and their
sources are worth reading as worked examples of the primitives at their intended scale. Each is
covered alongside the detector that produces its input: faces in the face-detection chapter,
skeletons in pose estimation, tracks in tracking, and both marker overlays in the marker-AR chapter.

#sect("Where the primitives stop")

Every recipe in this chapter has been a small pile of primitive calls held together by a helper
function, and that is the honest signal. When the helper starts taking a style, when two call sites
want the same overlay at different positions, when you need a dashed outline, a rotated shape, or an
overlay composed once and drawn onto many frames --- you have started writing a scene graph by hand,
and there is one in the box.

`Picture`, in the `scalacv-graphs` module, is a composable value: shapes, dashed strokes, text,
transforms and transparency, built up with combinators and rendered in one call by
`image.draw(picture)`. It renders through exactly the primitives in this chapter, so it costs nothing
in fidelity; what it costs is a second dependency, and what it buys is that an overlay becomes a
value you can name, transform, reuse and test rather than a sequence of side effects.

The line is worth stating plainly rather than leaving to taste. Use the primitives directly when the
annotation is a handful of marks whose coordinates you already have --- a box round a detection, a
timestamp in a corner, a contour rasterised into a mask. Reach for `Picture` when the overlay has
structure: repeated elements, a dash pattern, a rotation, an opacity, an ellipse, or a shape that
gets positioned by something other than the pixel coordinates it will end up at.

#sect("Next")

Chapter 15, on the photographic and stylisation operations, closes out the pixel-processing half of
the book: the verbs --- denoising, sharpening, tone work --- whose results are judged by eye rather
than by an assertion, which is to say the verbs that need everything this chapter said about getting
a look at the answer.

Chapter 16 then takes the second half of the split above and builds it out: `Picture` as an algebra,
the combinators that compose one overlay out of others, and the styling --- dashes, transparency,
affine transforms --- that the raw OpenCV primitives cannot express at all. Everything it draws still
ends up going through `drawLine`, `drawPolyline` and `drawText`, so the pixels are the ones you
already know how to reason about.
