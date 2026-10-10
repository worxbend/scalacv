#import "../lib/book.typ": *

#chapter(
  "Screen Analysis and Video Conferencing",
  subtitle: [Finding a known patch on a screen, and separating a person from the room behind them.],
)

Every other chapter in this part has treated a frame as a photograph of the world. These two do not.
A screenshot is a picture of a rendering: pixels a compositor produced from a display list,
deterministic in principle and not quite reproducible in practice --- a font hinted differently on the
next machine, a scroll position a pixel off, a clock that ticks. A webcam frame of somebody on a call
is the reverse. Those pixels are natural, but you already know what is in them: one person, roughly
centred, and the only question is where they stop and the room begins.

Neither is the question "what is this?". Screen automation asks two narrower ones --- is this control
on screen and where, and what changed since the last capture --- and answers both with arithmetic over
pixels, no model anywhere. Conferencing asks which pixels are the person, and that one does want a
model; but softening the silhouette and blending two layers is arithmetic again.

So `Screen` is a complete application in fewer than seventy lines of code, and `BackgroundEffect` a
complete compositor that hands the hard half straight back to you: bring a mask, from wherever --- a
network you exported, a green screen, a filled contour from Chapter 12. Both then work perfectly on the
frame you tested them on and degrade on the next machine, at the next display scale, under the next
lighting. The first half of this chapter builds a nightly watchdog over an internal dashboard, the
second a conference feed recorded with a virtual background.

One line of setup first. `Screen`, `BackgroundEffect` and `Segmenter` all live in `scalacv-vision`, the
same module as the detectors of the last ten chapters, so `mvn"com.worxbend::scalacv-vision:0.4.1"` has
to sit on the classpath beside the core dependency of Chapter 2. Everything else this chapter touches
--- `Image`, `Rect`, `Camera`, `Codec` --- is core. Use both `import scalacv.*` and
`import scalacv.vision.*`; the optional artifact has its own package.

#sect("Part one: what is on the screen")

Every method on `Screen` is a query in the sense Chapter 4 gave the word: it borrows the images you
hand it and returns plain immutable data that outlives everything it was computed from.

#figure-table("The three entry points on Screen, and what each rejects.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Call*], [*Result*], [*Throws when*],
  [`Screen.locate(image, template, minScore)`],
  [`Option[TemplateMatch]`],
  [the template is larger than the image],

  [`Screen.findAll(image, template, minScore, maxMatches)`],
  [`Seq[TemplateMatch]`, best first],
  [the template is larger than the image; `maxMatches` is below `1`],

  [`Screen.diff(before, after, threshold, minArea)`],
  [`Seq[Rect]`, largest first],
  [the two captures differ in size],
)
]

The result type is two fields, neither of them native:

#example("Everything a match tells you.")[
```scala
final case class TemplateMatch(location: Rect, score: Double)
```
]

`location` is a `Rect` at the peak, carrying the template's own width and height; `score` is how well
it matched. `minScore` defaults to `0.8` on both `locate` and `findAll`, `maxMatches` to `20`.

#memory[
  `Screen` never takes ownership. Every image you pass it is alive when the call returns and still
  yours to `close()` --- the reverse of the `Image` transforms in Chapter 4, and the rule that decides
  whether a polling loop runs for a week or four minutes.
]

#subsect("Template matching is correlation, and only correlation")

Template matching slides the template over every position it fits, scores the overlap, and builds a
surface of scores --- one per candidate top-left corner. Finding the template is finding the peak of
that surface. There is no feature extraction, no descriptor, no invariance to anything.

OpenCV offers six scoring functions for the overlap. Two are squared-difference measures, where a
perfect match scores *zero* and a worse one higher; two more are unnormalised correlations whose
magnitude rides on how bright the template happens to be. `Screen` fixes `Imgproc.TM_CCOEFF_NORMED` and
does not expose the choice.

That method subtracts the mean from both the template and the window under it, then divides by the
product of their standard deviations, and the two consequences are what make one `minScore` constant
usable across a whole test suite. The score always lands in `[-1, 1]` --- `1.0` pixel-perfect, `0` no
linear relationship, negatives inverted --- so `0.8` means the same thing for a 20×20 icon and a 400×90
banner. And with the mean and deviation divided out, a uniform brightness or contrast shift barely
moves it: the same button on a dimmer theme still matches.

The price sits in the denominator. A template of one flat colour has zero standard deviation, so the
correlation is undefined --- OpenCV does not throw, it returns a surface you cannot interpret. People
meet this first, because a block of brand colour looks like the most distinctive thing on the page:

#example("Wrong: a template with no variance to correlate against.")[
```scala
// A solid 40x24 swatch of the brand colour. Nothing to correlate with.
val flat = Image.blank(40, 24, Scalar(0, 90, 200))
try Screen.locate(screenshot, flat) // meaningless, whatever it returns
finally flat.close()
```
]

The fix is to include the edges: crop a border of surrounding pixels so the template carries the
button's outline, shadow and label --- the parts that vary.

#example("Right: the control plus a margin, cropped from a copy so the capture survives.")[
```scala
// `crop` is a transform and consumes its receiver, so crop a copy — the
// screenshot has to stay alive to be searched.
val refreshButton = screenshot.copy.crop(Rect(844, 96, 88, 40))

Screen.locate(screenshot, refreshButton, minScore = 0.85) match
  case Some(m) => click(m.location.topLeft)
  case None    => fail("the refresh control never rendered")
```
]

`Rect` gives you `topLeft` and `bottomRight` as `Point`s, so a hit hands the automation layer
somewhere to aim.

#subsect("Every occurrence, and the hole each one leaves behind")

`locate` is `findAll(image, template, minScore, maxMatches = 1).headOption` --- literally, in the
source. When the same element can appear more than once, call `findAll` directly.

Its loop dictates what you can ask of it. Each iteration takes the global maximum of the score surface
with `Core.minMaxLoc`; if that clears `minScore` it is recorded, and the peak's footprint is painted
out of the surface --- a filled rectangle reaching half a template-width either side and half a
template-height above and below --- so the next iteration finds a different location rather than the
same peak's shoulder. The loop ends at `maxMatches` hits, or at the first maximum below `minScore`.

So `findAll` cannot report two occurrences whose corners sit closer than about half a template apart.
For a grid of controls that is what you want; for two overlapping windows showing the same icon a few
pixels apart, it finds one and suppresses the other, whatever `minScore` you pick.

#sidebar("The score surface is not an image")[
The `Mat` that `matchTemplate` fills is `CV_32F`, one channel, and smaller than the input: one score
per position the template fits, so its width is the image's minus the template's, plus one. It is a
scalar field, not a picture, and `Screen` treats it as one: the suppression step calls
`Imgproc.rectangle` directly rather than the library's own `drawRect`, and fills with a raw `-1.0`
rather than a `Scalar` colour --- neither of which a `Rect` and a `Scalar` would model honestly. `-1.0`
is the floor of `TM_CCOEFF_NORMED`'s range, so a suppressed footprint can never outscore a live peak in
a later `minMaxLoc`.
]

#subsect("Scale and rotation: the limit you must design around")

#warning[
  Template matching is not scale invariant and it is not rotation invariant. A template captured at one
  display scale will not match the same control captured at another, and a control rotated five degrees
  will not match at all. No `minScore` value fixes it. Every pixel of the template is correlated against
  a fixed offset in the window, so a rescaled control misaligns its own interior against itself, and the
  peak sinks towards the surrounding noise rather than degrading gently.
]

In screen work this arrives through DPI. A template cut on a 1× display and searched for in a 2×
capture returns `None` with total confidence --- as it does for a browser at 110% zoom, a
remote-desktop session scaling the framebuffer, or a CI runner whose virtual display differs from the
laptop the fixtures came from. Remove the variable where you can: capture templates at the scale you
search at, and record that scale beside the fixture. Where you cannot, sweep the scale yourself, since
`Screen` offers no multi-scale entry point:

#example("A scale sweep, written by hand.")[
```scala
/** The best hit for `template` across a range of rescalings, with the scale that won. */
def locateMultiScale(
    screen: Image,
    template: Image,
    scales: Seq[Double] = Seq(0.75, 0.9, 1.0, 1.1, 1.25, 1.5)
): Option[(Double, TemplateMatch)] =
  val hits = scales.flatMap { s =>
    // `scale` consumes its receiver, so rescale a copy and release it each pass.
    val scaled = template.copy.scale(s)
    try
      // `locate` throws on a template bigger than the image; skip rather than throw.
      if scaled.width > screen.width || scaled.height > screen.height then None
      else Screen.locate(screen, scaled, minScore = 0.7).map(s -> _)
    finally scaled.close()
  }
  hits.maxByOption(_._2.score)
```
]

The `copy` keeps the caller's template alive across six passes, and the size guard keeps an upscaled
template from tripping `locate`'s `require`. Scores are comparable across scales because the method is
normalised --- but a smaller template has fewer pixels to disagree over, so on a tie prefer the
larger.

#subsect("Anti-aliasing, subpixel text, and where minScore should sit")

What is left to erode a score is rendering, not geometry, and text is the worst offender. Subpixel
rendering writes different values into the red, green and blue channels of the same edge pixel, so an
identical string rendered on two machines differs per-channel along every stroke while looking the same
to a person. `matchTemplate` sums correlation over channels, so that fringing lands straight in your
score. Greyscaling both images averages it away:

```scala
val hit = Screen.locate(shot.copy.gray, tmpl.copy.gray, minScore = 0.85)
```

#warning[
  Convert both or neither: OpenCV requires the image and the template to share a type, so greyscaling
  one raises a `CvError.NativeCall` rather than a poor score. It also discards the only signal
  separating two controls that differ purely in colour.
]

#figure-table("Choosing minScore, and what each band assumes about the capture.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*minScore*], [*Behaviour*], [*Use when*],
  [`0.95`+], [near-exact only], [the template is a byte-for-byte crop of the same asset],
  [`0.8` (default)], [a confident hit], [the normal case --- same widget, same theme, same scale],
  [`0.6`--`0.75`], [tolerant], [mild rescaling, JPEG compression or anti-aliasing differ],
  [below `0.6`], [loose], [expect false positives; usually a sign the template is wrong],
)
]

#subsect("What changed since the last capture")

The second question needs no template.
`Screen.diff(before, after, threshold = 25, minArea = 100)` compares two same-size captures and returns
the changed regions as plain `Rect`s, largest first, in five unsurprising steps: absolute difference,
greyscale, threshold, a `dilate(radius = 2)` fusing neighbouring changed pixels into one blob, then the
bounding rectangle of every contour whose area reaches `minArea`.

Raise `threshold` when compression noise and subpixel jitter produce specks; lower it to catch a status
colour shifting one shade. One giant merged region instead of several means the dilation bridged nearby
changes, and its radius is not a knob --- `crop` into the regions you care about and diff those
separately. Sizes must agree exactly: a resized window against yesterday's capture is an
`IllegalArgumentException`, because a diff of misaligned frames is not a wrong answer but a meaningless
one.

`diff` is stateless: two captures in, rectangles out, nothing remembered between calls. That is what a
poll wants, because the two captures it compares are minutes apart and the baseline is whichever one you
kept. It is *not* what a live feed wants --- for thirty frames a second against a background that drifts
with the light, reach for the stateful `MotionDetector` of Chapter 21, which retains the previous frame
or an adaptive MOG2 model for you.

#minor("Masking out the parts that always change")

A dashboard has a clock, a "last updated 4s ago" caption and a sparkline on a timer, and a diff a
minute apart reports all three. `diff` takes no mask parameter, and filtering afterwards is cheaper,
since it neither copies nor consumes the captures. `Rect` carries no intersection helper, so the
overlap test is four comparisons you write inline:

#example("Volatile regions declared once, and excluded from every poll.")[
```scala
val volatile = Seq(
  Rect(1712, 12, 200, 32),  // the clock
  Rect(24, 640, 360, 180)   // the auto-refreshing sparkline
)

def overlaps(a: Rect, b: Rect): Boolean =
  a.x < b.x + b.width && b.x < a.x + a.width &&
    a.y < b.y + b.height && b.y < a.y + a.height

def realChanges(before: Image, after: Image): Seq[Rect] =
  Screen
    .diff(before, after, threshold = 30, minArea = 200)
    .filterNot(r => volatile.exists(overlaps(r, _)))
```
]

Painting those regions flat on copies of both captures instead --- `drawRect(clock, Scalar.Black,
Thickness.Filled)` --- costs two clones per poll, and earns them when a volatile blob keeps merging
with a real change beside it.

#minor("Detecting a stuck render")

A live dashboard is *supposed* to change, so "nothing changed" is itself the alarm --- but an unmasked
diff cannot tell a frozen page from a healthy quiet one. Split it across two diffs, one on the clock
alone and one on everything else:

#example("The clock proves the page is alive; the rest proves it is doing something.")[
```scala
val clock = Rect(1712, 12, 200, 32)

def health(before: Image, after: Image): String =
  // `crop` consumes, so crop copies — the full captures are still needed below.
  val b = before.copy.crop(clock)
  val a = after.copy.crop(clock)
  try
    val ticking = Screen.diff(b, a, minArea = 4).nonEmpty
    (ticking, realChanges(before, after).nonEmpty) match
      case (false, _)    => "STUCK: the render is frozen"
      case (true, false) => "alive, no data change"
      case (true, true)  => "alive, data moved"
  finally
    b.close()
    a.close()
```
]

#minor("The poll loop, and why it is the memory-critical part")

A watchdog is a loop that holds two captures: the baseline and the one just taken. `Screen` borrows both
and closes neither, so the loop owns them --- and the moment it forgets one, the leak is proportional to
uptime rather than to anything you will see in a heap dump.

#example("A watchdog that holds exactly one capture between iterations.")[
```scala
/** Polls until `stop` says otherwise, keeping the previous capture as the baseline. */
def watch(grab: () => Image, everyMs: Long, stop: () => Boolean): Unit =
  var last = grab()
  try
    while !stop() do
      Thread.sleep(everyMs)
      val now = grab()
      try println(health(last, now))
      finally
        // The new capture becomes the baseline; the old one is released here,
        // on the exception path as well as the normal one.
        last.close()
        last = now
  finally last.close()
```
]

#memory[
  A 1920×1080 BGR capture is `1920 × 1080 × 3` bytes --- 5.9 MiB of native memory behind a Java object
  the collector sizes in bytes. Drop one per poll at one poll a minute and the process is 8.3 GiB heavier
  a day later, with a heap that never grew. That is Chapter 1's arithmetic applied to a loop that is
  meant to run for weeks.
]

#sect("Part two: the person and everything else")

The conferencing half has a smaller API and a harder problem. Two extension methods on `Image` do the
compositing:

#example("The two effects, exactly as the source declares them.")[
```scala
extension (img: Image)
  def blurBackground(mask: Image, strength: Int = 15, feather: Int = 7): Image
  def replaceBackground(mask: Image, background: Image, feather: Int = 7): Image
```
]

Both are transforms: they consume `img` and return a new owned `CV_8UC3` `Image`, while `mask` and
`background` are borrowed and come back alive.

The mask is a `CV_8UC1` image, white over the person and black over the background, the same size as
the frame. That convention is the whole contract: `BackgroundEffect` holds no model and no notion of a
human, so a filled contour, a white-filled detection rectangle, a green-screen key and a network's
output are equally valid and composited identically.

#subsect("Inside the blend, because it explains both knobs")

`BackgroundEffect.alphaBlend` is private, but its steps are what `feather` and `strength` control. It
Gaussian-blurs the mask with a kernel of side `2 * feather + 1` --- the only place a hard binary mask
acquires a soft edge --- converts that to a `CV_32F` alpha in `[0, 1]`, expands it to three channels,
builds the complement, and computes `fg × alpha + bg × (1 - alpha)` in float before returning to 8
bits.

`blurBackground` produces its background layer by Gaussian-blurring the frame with a kernel of side
`2 * strength + 1`: the default `strength = 15` is a 31×31 blur, `strength = 21` a 43×43 studio
softening. `replaceBackground` resizes your backdrop to the frame instead, which does not preserve
aspect ratio. `strength` must be at least `1`, `feather` may be `0` for a hard cut but not negative,
and the mask must match the frame's rows and columns.

#memory[
  A size-mismatched mask trips `alphaBlend`'s `require`, and because these are transforms the frame is
  consumed on that path too: the compositing deliberately runs *inside* the `try` whose `finally`
  closes the receiver, so a throw releases the frame rather than stranding it. The mask is never
  released for you.
]

#subsect("From tensor to mask: Segmenter")

`Segmenter.decodeMask(output, imageSize, threshold)` turns a segmentation network's output into the
mask the effects want. It accepts a three- or four-dimensional tensor --- `[C, H, W]` or `[1, C, H, W]`,
which is the difference between an export that keeps its batch axis and one that does not --- and reads
the channel count off whichever layout it got. A one-channel output is a single foreground probability
plane; a two-channel output is background and foreground, and the *last* channel is taken as the person.
It then thresholds each probability at `threshold` (default `0.5f`), writes `255` or `0`, and resizes up
to `imageSize` with `Interpolation.Nearest`.

So *the mask is binary*: the confidence gradient at the silhouette is discarded and the
nearest-neighbour upscale leaves stair-steps. All of the softness in the composite comes from
`feather`, which is why its defaults are as generous as they are.

`segment` collapses the whole path into one call:

#example("Blob, forward and decode, with the receiver left alive on purpose.")[
```scala
def segment(
    net: Net,
    inputSize: Size,
    threshold: Float = 0.5f,
    scaleFactor: Double = 1.0 / 255,
    mean: Scalar = Scalar(0, 0, 0),
    swapRB: Boolean = true
): Image
```
]

Unlike almost everything else on `Image`, `segment` only reads its receiver --- the one reason
`val mask = frame.segment(net, size)` can be followed by a line that consumes `frame`. The blob
parameters mirror `Dnn.blobFromImage` from Chapter 26 and default to what a MediaPipe-selfie or
MODNet-style export wants: RGB input in `[0, 1]`. The weights are yours; MediaPipe's own selfie model
ships as TFLite, which OpenCV's DNN module does not read, so convert it to ONNX first.

#memory[
  A `Net` is one of the 185 `org.opencv.*` native types with no public `release()`. `Dnn.fromOnnx`
  returns `Either[CvError, Managed[Net]]` so scalacv can free it anyway; load it once, outside the
  loop, and let `managedNet.use` scope it around the whole capture. Loading a network per frame
  abandons tens of megabytes of weights per second behind an object the collector sees as a few dozen
  bytes --- the failure Chapter 1 opened with.
]

#sidebar("A mask with no model in sight")[
If you control the room you do not need a network. Key the backdrop colour in HSV and invert, and the
white lands on the person --- exactly the convention the effects expect. It is the colour segmentation
of Chapter 11 with one extra step:

```scala
val key = frame.copy.toHsv
  .inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
  .invert
  .erode(radius = 2)
  .dilate(radius = 2)
```

The `erode`/`dilate` pair is an opening: it removes the speckle every raw colour key has, before the
feather would turn it into fuzz. A green-screen key is crisper than a network's mask, so it wants a
*smaller* feather --- `3` to `5` rather than `7`.
]

#subsect("The four things that look wrong")

Everyone who ships this feature meets the same four artefacts.

*Edge halo* --- a rim of old background survives around the person, or the outline is eaten into. The
mask is systematically wrong at the boundary, and `feather` spreads that error over more pixels so none
reads as a hard mistake. `9` to `13` suits a network mask; beyond that the background bleeds onto the
shoulders.

*Hair and glasses* --- a strand covering a third of a pixel is classified as fully one thing, and no
parameter fixes that. What hides it is contrast: with `replaceBackground`, pick a backdrop whose
luminance is close to the real room's, so the pixels the mask gets wrong differ less. That is the
largest quality lever in the feature and it costs nothing.

*A background the colour of the person* --- a selfie network degrades badly when the wall behind is the
colour of a shirt or of skin. Lowering `threshold` below `0.5f` grows the person and recovers dropouts
at the cost of grabbing wall; raising it shrinks. No single value suits both a bright room and a dim
one, which argues for putting it in front of whoever is on the call.

*Temporal flicker* --- each frame is segmented independently, so boundary pixels flip and the edge
crawls. It is the one people notice first and the only one with an algorithmic fix: blend this frame's
mask with the last. Because `alphaBlend` reads the mask as an alpha channel rather than a boolean, an
averaged mask composites correctly --- and its intermediate greys become soft edge pixels.

#example("Temporal smoothing, built from Image.blend and nothing else.")[
```scala
/** Carries the previous mask between frames so the alpha edge moves instead of snapping. */
final class MaskSmoother(weight: Double = 0.6) extends AutoCloseable:
  private var previous: Option[Image] = None

  /** Consumes `fresh`, returns an owned smoothed mask the caller closes. */
  def smooth(fresh: Image): Image =
    // `blend` consumes the receiver and borrows the argument, so `fresh` is spent here.
    val blended = previous.fold(fresh)(prev => fresh.blend(prev, weight))
    previous.foreach(_.close())
    previous = Some(blended.copy)
    blended

  def close(): Unit =
    previous.foreach(_.close())
    previous = None
```
]

One clone of a single-channel mask per frame is the price. `blend` requires both operands to match in
size and type, so reset the smoother if the source changes resolution mid-stream.

#tip[
  A stronger blur hides a weaker mask: `blurBackground(mask, strength = 25)` destroys enough detail
  that wrong pixels along the edge have nothing recognisable to be wrong *about*. Prefer blur to
  replacement where you can.
]

#subsect("The latency budget")

Thirty frames a second is 33 milliseconds each, and the effect is not the only thing spending them: an
encoder, a network stack and a conferencing client compete for the same cores.

#figure-table("Where a frame's time goes, and the knob for each stage.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Stage*], [*Scales with*], [*Knob*],
  [Capture],
  [frame size],
  [`Camera` copies one frame per iteration; `Video.frames` borrows instead],

  [`blobFromImage`],
  [`inputSize`],
  [the resize is to `inputSize`, so it is cheap whatever the frame size],

  [`forward`],
  [`inputSize` and the model],
  [`inputSize` --- the dominant term, and the first thing to shrink],

  [`decodeMask`],
  [model output, then frame size],
  [a threshold at model resolution plus one nearest-neighbour upscale],

  [Feather],
  [frame size × `feather`],
  [one Gaussian over a single-channel mask],

  [Background blur],
  [frame size × `strength`],
  [`strength`; skipped entirely by `replaceBackground`],

  [`alphaBlend`],
  [frame size],
  [fixed --- nine full-frame passes in `CV_32FC3`],

  [Encode],
  [frame size and codec],
  [`Codec`, and the resolution you record at],
)
]

Every compositing row is a handful of linear passes over the frame; the `forward` row is a
convolutional network. For scale, the project's benchmark page records a three-stage full-frame chain
--- greyscale, blur, Canny --- at 145.3 µs for 640×480 and 574.2 µs for 1920×1080 on one developer
machine. Those are different operations from these, and the absolutes belong to that machine rather
than yours, but they place the order of magnitude: a few full-frame passes are hundreds of
microseconds, and a selfie network is milliseconds.

So, the knobs in order. Shrink `inputSize` first: 160×160 is 39% of the pixels of 256×256, and most
selfie exports tolerate it because the mask is upscaled and feathered anyway. Second, segment on
alternate frames and reuse the previous mask --- half the dominant term and half the flicker rate, for
a frame of lag. Only then drop the frame resolution, which scales every remaining row at once.

#subsect("A camera to a recorder, correctly scoped")

The running example finishes as a complete loop. Five things in it own native memory. Four --- the
network, the backdrop, the camera and the recorder --- are scoped by a construct rather than a
`close()` you must remember. The fifth is the smoother's retained mask, which outlives no block the
library knows about, so it gets the one explicit `try`/`finally` in the listing.

#example("Camera to recorder, with a virtual background and no leaks.")[
```scala
OpenCv.load()

Dnn.fromOnnx("models/selfie_segmentation.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.reading("backdrops/office.png") { backdrop =>
      Camera.using(0) { cam =>
        val smoother = MaskSmoother(weight = 0.6)
        try
          cam.recordTo("call.avi", fps = 30, codec = Codec.Mjpg) { frame =>
            // `segment` reads the frame and leaves it alive, so the effect on
            // the next line can consume it.
            val mask = smoother.smooth(frame.segment(net, Size(256, 256)))
            try frame.replaceBackground(mask, backdrop, feather = 11)
            finally mask.close()
          }
        finally smoother.close()
      }
    }
  }
}
```
]

`recordTo`'s transform is handed an owned frame and must return an `Image`; the recorder writes that
result and closes it, so nothing in the lambda is yours to release except the mask.
`replaceBackground` consumes `frame` and borrows `mask` and `backdrop`, so the backdrop --- read once
outside the loop --- survives every frame. The constructs nest longest-lived outermost.

Two details of `recordTo` matter. It sizes the recorder from the *first transformed frame*, not from
the camera's reported geometry, because a camera that has not yet delivered a frame reports 0×0 --- so
the transform may resize, as long as it resizes every frame identically. And its default codec is
`Codec.Mjpg`, the one codec videoio can always write --- and it does not open in an `.mp4` or an
`.mkv`, so a path ending `.mp4` fails even though the codec is present. Record to `.avi`.

#memory[
  `Camera.using` closes the capture on every path out of the block, thrown exceptions included, and
  `recordTo` closes the recorder in its own `finally`. `Camera.open` does neither, and `Either.foreach`
  is not a scoping combinator: `Camera.open(0).foreach { cam => ... }` runs the body and leaves the
  device open, so the camera light stays on until the process exits.
]

#sect("What these two hand to Part VI")

Both halves of this chapter have their correctness settled long before their performance. A template
match either finds the control or it does not, and if it does not, no profiler helps: the fix is a
different template or a different capture scale. A background effect either has a good enough mask or
it does not, and the knobs only redistribute an error the model already made.

But both are loops around native memory. A watchdog holds two captures and runs for weeks; a
conference loop holds a network, a backdrop, a camera, a recorder and two `Image`s per frame, thirty
times a second, beside a video call already using the machine. This part has been about getting the
answer right; from here the question is what happens when it must be produced continuously, on a
machine that is also doing something else.

Chapter 35, #emph[Performance], opens Part VI by making that measurable rather than assumed: where the time
goes, where the memory goes, and how to find out instead of guessing.
