# Motion detection

A fixed camera — a doorway cam, a trail-cam, an **ESP32-CAM** dribbling low-frame-rate MJPEG over Wi-Fi —
almost never needs to know *what* it is looking at. It needs to know two cheap things: **when** something
moved, and **where**. `MotionDetector` is that piece. Feed it frames in order and it hands back a
[`Motion`](#the-motion-result) — plain immutable data you can act on, log, or draw — without you ever
touching a native handle.

If you have never done this before, the mental model is short: keep a picture of "the scene when nothing is
happening", compare each new frame against it, and report the pixels that disagree. Everything below is a
refinement of that one idea.

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
def frame(squareX: Int): Image =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawRect(Rect(squareX, 40, 20, 20), Scalar.White, Thickness.Filled)
def alert(): Unit = ()
```

Every runnable example below uses one synthetic frame builder — a dark 160×120 scene with a movable
20×20 white square — so the detector runs against real pixels with no fixture file:

```scala
def frame(squareX: Int): Image =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawRect(Rect(squareX, 40, 20, 20), Scalar.White, Thickness.Filled)
```

## Your first detection

Three lines: build a detector, feed a baseline, feed a frame where something changed. The first frame has
nothing to compare against, so it reports [`Motion.still`](#the-motion-result) and becomes the reference.

```scala mdoc:silent
val hello = MotionDetector.frameDifference()

val baselineMoving =
  val f = frame(20)
  try hello.detect(f).moving finally f.close() // first frame — becomes the baseline

val jumpedMoving =
  val f = frame(90)
  try hello.detect(f).moving finally f.close() // square jumped — motion

hello.close()
```

```scala mdoc
(baselineMoving, jumpedMoving)
```

The baseline reports `false` (nothing to compare to yet); the second frame reports `true`. That is the
whole contract — the rest of this page is about tuning it and choosing the right strategy.

:::warning[Borrow, don't consume]
`detect(image)` **borrows** the frame — it reads it and does not release it. So *you* close each frame you
build (the `try/finally` above). This is the opposite of a transform like `gray` or `blur`, which consumes
its receiver. See [Mat lifecycle](/mat-lifecycle) for the full ownership model.
:::

## Two strategies

Both are reached through a factory on `MotionDetector`, and both satisfy the same contract; the choice is
about the scene, not the API.

| | [`frameDifference`](#frame-difference-the-default) | [`backgroundSubtraction`](#background-subtraction-mog2) |
|---|---|---|
| Compares each frame to… | the **previous** frame | an **adaptive background model** (OpenCV's MOG2) |
| Cost | cheap, immediate | heavier, needs a warm-up |
| Handles slow lighting drift | no | yes |
| Marks the object at… | both where it **was** and where it **is** | only where it **is** now |
| Right for | a static cam at low frame rate — the default | a scene with gradual light changes or repetitive background motion |

A detector is **stateful** (it retains the previous frame, or the background model) and **not
thread-safe** — feed frames in order, and give each thread its own.

:::note[Both build the same shape]
Whichever factory you pick, you get a `MotionDetector` with the same three methods — `detect`, `reset`,
`close`. You can swap strategies without touching the rest of your loop.
:::

## Frame difference — the default {#frame-difference-the-default}

Each frame is compared to the one before it. The very first frame has nothing to compare against, so it
reports [`Motion.still`](#the-motion-result) and becomes the baseline. An identical frame reports no
motion; a frame where the square has moved reports `moving = true` with the changed regions.

```scala mdoc:silent
val fd = MotionDetector.frameDifference()

// detect(image) BORROWS the frame (it does not consume it), so close each one you build.
val baseline =
  val f = frame(20)
  try fd.detect(f) finally f.close()   // first frame -> Motion.still, becomes the baseline

val unchanged =
  val f = frame(20)
  try fd.detect(f) finally f.close()   // same square -> nothing moved

val moved =
  val f = frame(90)
  try fd.detect(f) finally f.close()   // square jumped -> motion
```

```scala mdoc
(baseline.moving, unchanged.moving, moved.moving)
```

The moved frame carries the detail — the fraction of the frame that changed and one box per moving blob:

```scala mdoc
(moved.moving, moved.regionCount, moved.ratio)
```

```scala mdoc:invisible
fd.close()
```

The four knobs, all with sensible defaults:

| Parameter | Default | Effect |
|---|---|---|
| `threshold` | `25` | per-pixel intensity delta (0–255) that counts as changed. Lower is more sensitive. |
| `minArea` | `500` | moving blobs smaller than this many pixels are dropped — the noise gate. |
| `blurRadius` | `2` | pre-blur to suppress sensor noise before differencing; `0` disables it. |
| `motionRatio` | `0.002` | fraction of the frame that must change for `moving` to be `true`. |

:::tip[It marks both endpoints]
Frame differencing lights up wherever pixels *changed* between the two frames — so a moving object shows up
**twice**: a hole where it was, and a blob where it is now. That is fine for "did anything move?", but if
you plan to feed the boxes to a [tracker](/tracking), prefer background subtraction, which marks only the
object's current position.
:::

### The noise gate: `minArea`

`minArea` decides how big a moving blob must be before it counts as a *region*. It is your primary defence
against sensor speckle and swaying leaves: a real intruder is hundreds of pixels; noise is a handful. Here
a small movement produces small blobs — a lenient gate keeps them, a strict gate discards them:

```scala mdoc:silent
val lenient = MotionDetector.frameDifference(minArea = 50)
val strict = MotionDetector.frameDifference(minArea = 5000)

for det <- Seq(lenient, strict) do
  val a = frame(20)
  try det.detect(a) finally a.close() // baseline for each

val lenientRegions =
  val f = frame(30)
  try lenient.detect(f).regionCount finally f.close() // a small 10px shift

val strictRegions =
  val f = frame(30)
  try strict.detect(f).regionCount finally f.close()

lenient.close()
strict.close()
```

```scala mdoc
(lenientRegions > 0, strictRegions)
```

The lenient detector keeps the small blobs; the strict one gates them all out (`0` regions).

:::note[`moving` vs. `regions` gate differently]
`moving` is decided purely by `motionRatio` — the *fraction* of the frame that changed — while `regions`
is filtered by `minArea` (blob *size*). So a frame can report `moving = true` with **zero** regions: a lot
of the frame changed, but no single blob was big enough to survive the gate. Read `moving` for "should I
care?" and `regions` for "where, exactly?".
:::

:::warning[Sensitivity is a trade]
There is no "correct" setting — every scene is a balance between missing real motion (gates too high) and
crying wolf at sensor noise (too low). Tune against a recording of the actual camera. `threshold` sets how
different a *pixel* must be; `blurRadius` smooths away single-pixel sensor noise before the comparison, so
you can keep `threshold` low without every speckle firing; `motionRatio` sets how much of the frame must
change for `moving`; and `minArea` gates the regions.
:::

## Background subtraction (MOG2) {#background-subtraction-mog2}

When the light drifts over the day, frame differencing either misses slow change or cries wolf at a
passing cloud. `backgroundSubtraction` builds an adaptive model of "the empty scene" (OpenCV's MOG2) and
flags only pixels that depart from it. It is heavier and **needs a few frames to settle** — during warm-up
it may over-report — but then it shrugs off gradual lighting and repetitive background motion.

```scala mdoc:silent
val bg = MotionDetector.backgroundSubtraction(minArea = 100)

// Warm up on a still scene so the model learns the background...
for _ <- 1 to 40 do
  val f = frame(20)
  try bg.detect(f) finally f.close()

// ...then a frame where the square has moved stands out as foreground.
val bgMotion =
  val f = frame(90)
  try bg.detect(f) finally f.close()

bg.close()
```

```scala mdoc
(bgMotion.moving, bgMotion.regionCount)
```

Its knobs (`minArea` and `motionRatio` mean the same as above):

| Parameter | Default | Effect |
|---|---|---|
| `history` | `200` | how many recent frames the model blends over. |
| `varThreshold` | `16` | Mahalanobis distance a pixel must exceed to count as foreground. Higher is stricter. |
| `detectShadows` | `true` | detect cast shadows and drop them (they are marked, then removed). Costs a little. |
| `learningRate` | `-1` | how fast the model adapts; `-1` lets OpenCV choose. |

:::note[Warm-up is not optional]
MOG2 has no baseline until it has seen a handful of frames. Detecting on frame one gives noisy, over-eager
results. Let it watch the empty scene for a second or two (the loop above uses 40 frames) before you trust
its output. `reset()` is a no-op here — the model already re-learns the background on its own.
:::

## Two ways in: an Image, or raw JPEG bytes

`detect` comes in two shapes. `detect(image: Image)` takes a decoded frame and borrows it. But an MJPEG
stream is a run of independent JPEGs, so `detect(encoded: Array[Byte])` **decodes and detects in one
call** — it is *the* MJPEG entry point — and returns an `Either`, `Left` only when the bytes are not a
decodable image.

```scala mdoc:silent
// A short stream of encoded frames, as you would pull them off an MJPEG endpoint.
val jpegFrames: Seq[Array[Byte]] =
  Seq(20, 20, 60, 90).map(x => frame(x).bytes(".jpg").toOption.get)

val stream = MotionDetector.frameDifference()
val streamResults: Seq[Either[CvError, Motion]] = jpegFrames.map(stream.detect)
stream.close()
```

```scala mdoc
streamResults.map(_.map(_.moving))
```

The first frame is the baseline (`false`), the repeat is still (`false`), and the two moves fire (`true`).
Because the byte overload returns `Either`, a corrupt frame in the middle of a stream becomes a `Left` you
can log and skip rather than an exception that tears down the loop — see the [error model](/error-model)
for how `CvError` boundaries work.

## Driving it from a Camera

An ESP32-CAM MJPEG endpoint (or an RTSP camera, or a file) opens like any other source through
[`Camera`](/video). `foreach` hands you an owned `Image` per frame and closes it for you, so the whole
watch loop is one expression — raise an alert the moment something moves:

```scala mdoc:compile-only
Camera.usingFile("http://esp32-cam.local:81/stream") { cam =>
  val motionDetector = MotionDetector.frameDifference()
  try cam.foreach()(f => if motionDetector.detect(f).moving then alert())
  finally motionDetector.close()
}
```

## The `Motion` result

A [`Motion`](/api/core/scalacv/vision/Motion.html) is plain data, valid long after the frame is freed:

| Field / method | Type | Meaning |
|---|---|---|
| `moving` | `Boolean` | `true` once `ratio` crosses the detector's `motionRatio`. |
| `ratio` | `Double` | the fraction of the frame that changed, in `[0, 1]`. |
| `regions` | `Seq[Rect]` | the moving blobs' bounding boxes, **largest first**, already filtered by `minArea`. |
| `regionCount` | `Int` | how many survived the `minArea` gate. |
| `largest` | `Option[Rect]` | the biggest region, or `None` if nothing moved. |

Because `regions` is just a `Seq[Rect]`, annotating a frame with what moved is one call —
[`drawRects`](/drawing) paints them all in a single pass:

```scala mdoc:silent
val annotator = MotionDetector.frameDifference()

val overlay: Either[CvError, Array[Byte]] =
  val warm = frame(20)
  try annotator.detect(warm) finally warm.close()  // baseline

  val f = frame(90)
  val motion = annotator.detect(f)                 // borrows f — f is still alive
  f.drawRects(motion.regions).bytes(".png")        // ...so we can draw on it, then encode

annotator.close()
```

Since `detect` only borrows, the frame is still yours after the call — that is what lets you draw on it and
encode it in the same block.

## From "did it move?" to "how many moved?"

`Motion.regions` is a `Seq[Rect]`, and an [`ObjectTracker`](/tracking) turns per-frame boxes into stable
identities — so motion detection is the cheapest possible front end for **counting**. Background
subtraction is the better source here because it marks only the object's current position (frame
differencing would double every object). Feed each frame's regions straight into the tracker:

```scala mdoc:silent
val watcher = MotionDetector.backgroundSubtraction(minArea = 50)
val idTracker = ObjectTracker.create(iouThreshold = 0.1, maxAge = 5)

// Learn the empty background first.
for _ <- 1 to 40 do
  val f = frame(20)
  try watcher.detect(f) finally f.close()

// Then follow the square as it moves; each frame's regions feed the tracker.
for x <- Seq(30, 45, 60, 75, 90) do
  val f = frame(x)
  try idTracker.update(watcher.detect(f).regions) finally f.close()

val objectsSeen = idTracker.count // running count of distinct blobs the tracker has stitched together
watcher.close()
idTracker.close()
```

```scala mdoc
objectsSeen
```

That is the whole "N people entered" pipeline in miniature. See [Object tracking](/tracking) for the
identity model, `maxAge`/`minHits` tuning, and `drawTracks` for labelling each id.

## Resetting and closing

`reset()` forgets accumulated state, so the next frame is treated as a fresh baseline — reach for it after
a deliberate scene change (the camera was repositioned, the lights came on) that you do not want reported
as motion. For `frameDifference` it clears the retained frame; MOG2 adapts on its own, so its `reset` is a
no-op.

```scala mdoc:silent
val rd = MotionDetector.frameDifference()

val a = frame(20); try rd.detect(a) finally a.close()                      // baseline
val movedBefore =
  val f = frame(90); try rd.detect(f).moving finally f.close()             // motion vs. baseline

rd.reset()                                                                 // forget everything

val afterReset =
  val f = frame(90); try rd.detect(f).moving finally f.close()             // fresh baseline -> still

rd.close()
```

```scala mdoc
(movedBefore, afterReset)
```

Before the reset the move is detected (`true`); after it, the same frame is the new baseline, so nothing
has moved yet (`false`).

A detector holds native memory — a retained frame, or the background model — so it is `AutoCloseable`:
`close()` it when done (it is idempotent), or manage it with `scala.util.Using`:

```scala mdoc:silent
import scala.util.Using

val ratioSeen =
  Using.resource(MotionDetector.frameDifference()) { det =>
    val a = frame(20); try det.detect(a) finally a.close()
    val f = frame(90)
    try det.detect(f).ratio finally f.close()
  } // det.close() runs here, even on exception
```

```scala mdoc
ratioSeen > 0.0
```

## Next

- [Object tracking](/tracking) — turn motion regions into stable ids and count distinct objects.
- [Video & Camera](/video) — open MJPEG/RTSP streams and files, and the per-frame loop that drives detection.
- [Drawing](/drawing) — annotate the `regions` a detector returns, and everything else you overlay on a frame.
