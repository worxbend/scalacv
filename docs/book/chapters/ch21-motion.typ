#import "../lib/book.typ": *

#chapter("Motion Detection", subtitle: [Finding what changed, so an expensive detector only runs when there is something to find.])

A camera bolted above a loading bay looks at the same scene for weeks. The concrete does not move.
The roller door does not move. For hours at a stretch the only thing in the frame that changes is
sensor noise and the shadow of the fence creeping across the tarmac. Then a van arrives, something
happens for ninety seconds, and nothing happens again until morning.

If the job is "tell me when a van arrives", a person-and-vehicle detector run on every frame spends
essentially its whole budget confirming that concrete is still concrete --- which is why the camera
needs a GPU it should not need, and why the service falls behind on the one minute of the day that
mattered. Recognition is also fragile: it wants a model file, a resolution, a lighting condition,
and a confidence threshold you tuned on somebody else's dataset.

Noticing that something changed is none of those things: arithmetic over pixel differences, with no
weights and no colour, working on a grainy infrared feed at three in the morning as well as at noon.
It cannot tell a van from a fox, and does not need to --- its job is to decide which frames are
worth thinking about. Everything here refines one idea: keep a picture of the scene when nothing is
happening, compare each new frame against it, report the pixels that disagree.

One assumption underneath all of it is load-bearing: the camera does not move. A fixed camera --- a
doorway cam, a trail cam, an ESP32-CAM dribbling low-frame-rate MJPEG over Wi-Fi --- is what
`MotionDetector` is built for. Point it at a handheld phone or a panning PTZ head and every pixel
changes on every frame, which is a true answer to the question asked and a useless one. That case
belongs to optical flow --- `OpticalFlow`, Chapter 32 --- not to this chapter.

One piece of setup first. `MotionDetector` and `Motion` live in `scalacv-vision`, not in the core
artifact --- this is the first chapter in this part of the book to need the second module. Add
`scalacv-vision` at the same version as the core dependency of Chapter 2, then import both
`scalacv.*` and `scalacv.vision.*`. The motion types and extensions belong to `scalacv.vision`;
core's wildcard import does not activate them.

#sect("What \"moved\" means in pixels")

Frame differencing is five stages, and each one deletes a specific class of false positive. Every
tuning parameter you will meet is a knob on one of these stages, and knowing which stage a knob
belongs to is most of knowing which knob to turn.

The frame arrives as BGR. Colour says nothing about whether something moved --- a red car and a blue
car change the same pixels --- so the first step converts to a single channel with
`ColorConversion.BgrToGray`, a two-thirds reduction in the work every later stage does, for no loss.

Next, a Gaussian blur. A CMOS sensor returns a different value for the same photon count on every
exposure, and at any useful sensitivity that speckle sits comfortably above the difference threshold
you want for real objects. Blurring spreads each pixel over its neighbours, so isolated noise
averages away while a moving object --- hundreds of contiguous pixels --- survives intact, which is
what lets the threshold stay low without every speck firing.

Then the difference itself: `absdiff` against the retained baseline, giving one channel where each
value is how much that pixel changed. `threshold` turns that into a binary mask --- 0 or 255 --- and
a `dilate` closes the pinholes a hard threshold punches through the middle of a real blob, so a
person does not arrive as forty separate specks.

Finally the mask is measured twice. `Core.countNonZero` divided by the pixel count gives the fraction of
the frame that changed. `findContours` over the same mask (outermost contours only, as in Chapter
12) gives one outline per blob, and each outline's `boundingRect` gives a box; boxes below a minimum
area are dropped and the survivors sorted largest first.

#example("The five stages, in the order the detector applies them.")[
```text
BGR frame ──▶ grey ──▶ blur ──▶ absdiff(baseline) ──▶ threshold ──▶ dilate ──▶ mask
                                                                                │
                                        countNonZero / pixels ──▶ ratio ────────┤
                                        findContours ──▶ boundingRect ──▶ regions
```
]

Both measurements come out of that one mask, but they are gated by different parameters --- the
ratio against `motionRatio`, the boxes by `minArea` --- so a frame can report `moving = true` with
zero regions: a great deal of the frame changed, no single blob survived the gate. Read `moving` for
"should I care?" and `regions` for "where, exactly?".

#sect("The detector")

`MotionDetector` is a trait with three operations, reached through one of two factories. `detect`
feeds the next frame and reports what moved; `reset()` forgets accumulated state; `close()` releases
native memory, which is what its `AutoCloseable` gets you. `detect` is overloaded --- the second
takes encoded bytes and is the MJPEG entry point, below. Both factories return that same trait, so
switching strategy is one word and nothing else in your loop moves.

The result is a `Motion` --- a plain case class, immutable, and still valid long after the frame it
came from has been freed.

#figure-table("Everything a `Motion` carries.")[
#tbl(
  columns: (0.9fr, 1fr, 2.1fr),
  [Member], [Type], [Meaning],
  [`moving`], [`Boolean`], [`true` once `ratio` crosses the detector's `motionRatio`.],
  [`ratio`], [`Double`], [the fraction of the frame that changed, in `[0, 1]`.],
  [`regions`], [`Seq[Rect]`], [bounding boxes of the moving blobs, largest first, already filtered by `minArea`.],
  [`regionCount`], [`Int`], [how many boxes survived the `minArea` gate.],
  [`largest`], [`Option[Rect]`], [the biggest region, or `None`.],
)
]

`Motion.still` is the constant for "nothing moved" --- `moving = false`, `ratio = 0.0`, no
regions --- and it is what the very first frame returns, because there is nothing yet to compare it
against. That frame becomes the baseline.

The running example for this chapter is that loading-bay camera: an ESP32-CAM streaming MJPEG at
about five frames a second, to be watched, gated, and eventually recorded from. Here is the
mistake to get out of the way first, because it is the one everyone writes.

#example("Wrong. A detector built inside the loop can never detect anything.")[
```scala
Camera.usingFile("http://bay-cam.local:81/stream") { cam =>
  cam.foreach() { frame =>
    val detector = MotionDetector.frameDifference()   // fresh state, every frame
    if detector.detect(frame).moving then alert()     // always Motion.still
    detector.close()
  }
}
```
]

Every frame is a first frame, so every frame is a baseline, so `moving` is never `true` and the
alert never fires --- silent rather than wrong, the worst failure mode a watchdog can have. A
detector is *stateful* by design, and that state is the entire mechanism. It is also not
thread-safe: feed frames in order, and give each thread its own.

#example("Right. One detector, outside the loop, closed when the loop ends.")[
```scala
Camera.usingFile("http://bay-cam.local:81/stream") { cam =>
  val detector = MotionDetector.frameDifference(minArea = 1500)
  try
    cam.foreach() { frame =>
      val motion = detector.detect(frame)
      if motion.moving then
        println(f"motion: ${motion.regionCount} region(s), ${motion.ratio * 100}%.1f%% of frame")
    }
  finally detector.close()
}
```
]

`Camera.usingFile` returns an `Either[CvError, A]`, so a stream that will not open is a value you
handle rather than an exception --- the error model of Chapter 6, unchanged. `foreach` hands you an
owned `Image` per frame and closes it when your block returns, which is why there is no `close` on
`frame` here.

#memory[
  `detect` *borrows* the frame. It reads the pixels and does not release them --- the opposite of a
  transform like `gray` or `blur`, which consumes its receiver (Chapter 4). Inside `Camera.foreach`
  that is invisible, because the loop owns the frame. Outside it, you close every frame you build.

  The detector itself owns native memory nothing else will free. `frameDifference` retains one
  full-size single-channel `Mat` --- the baseline --- from its first frame until `reset()` or
  `close()`. `backgroundSubtraction` holds a `BackgroundSubtractorMOG2`, one of the 185
  `org.opencv.*` types with no public `release()` at all; scalacv frees it through
  `Releasable.nativeHandle`, the private-`delete` bridge of Chapter 5. Build one per frame, as in the
  wrong listing above, and you leak a background model per frame with nothing in the JVM motivated to
  notice.
]

#sect("Frame difference and its knobs")

`MotionDetector.frameDifference` takes four parameters, all defaulted, and each one belongs to
exactly one stage of the pipeline above.

#figure-table("`frameDifference` parameters, their defaults, and the stage each one controls.")[
#tbl(
  columns: (0.85fr, 0.45fr, 2.4fr),
  [Parameter], [Default], [What it controls],
  [`threshold`], [`25`], [per-pixel intensity delta, 0--255, that counts as changed. Lower is more sensitive.],
  [`minArea`], [`500`], [moving blobs smaller than this many pixels are dropped --- the noise gate on `regions`.],
  [`blurRadius`], [`2`], [pre-blur applied before differencing, to suppress sensor noise; `0` disables it.],
  [`motionRatio`], [`0.002`], [fraction of the frame that must change for `moving` to be `true`.],
)
]

Each is validated on construction --- `threshold` in `[0, 255]`, `motionRatio` in `[0, 1]`, `minArea`
and `blurRadius` non-negative --- so a value outside those ranges is an `IllegalArgumentException` at
the call, not a detector that quietly does nothing.

The defaults are deliberately loose. `motionRatio = 0.002` means two-tenths of one per cent of the
frame raises the flag: on a 640 × 480 feed, roughly 600 pixels, a person at the far end of a yard.
You will almost always end up raising it.

#sect("A background model instead of a memory of one frame")

Comparing each frame to the one immediately before it has two consequences. It absorbs a change
instantly: a car that parks is motion for the frames it takes to stop, and from the next frame on it
is part of the scene, because the baseline already contains it. And it lights up wherever pixels
*changed*, so a moving object appears twice --- a hole where it was, a blob where it is. Fine for
"did anything move?", poor input for anything that wants to follow an object.

`MotionDetector.backgroundSubtraction` takes the other approach: OpenCV's MOG2 maintains a
statistical model of what each pixel normally looks like and flags the pixels that depart from it.
MOG2 marks background as 0, foreground as 255 and cast shadow as 127, and the detector keeps only
strong foreground by thresholding at 200 --- so shadows are detected and then discarded rather than
reported as objects. The clean-up is a morphological *open* (Chapter 9) rather than a plain dilate:
erode then dilate, which removes isolated foreground speckle instead of growing it.

#figure-table("`backgroundSubtraction` parameters. `minArea` and `motionRatio` mean what they mean above.")[
#tbl(
  columns: (0.95fr, 0.45fr, 2.3fr),
  [Parameter], [Default], [What it controls],
  [`history`], [`200`], [how many recent frames the background model blends over.],
  [`varThreshold`], [`16`], [Mahalanobis distance a pixel must exceed to count as foreground. Higher is stricter.],
  [`detectShadows`], [`true`], [detect cast shadows and drop them. Costs a little.],
  [`minArea`], [`500`], [the noise gate on `regions`.],
  [`motionRatio`], [`0.002`], [frame-fraction threshold for `moving`.],
  [`learningRate`], [`-1`], [how fast the model adapts; `-1` lets OpenCV choose from the frame count and `history`.],
)
]

MOG2 needs a warm-up: with no history it has no idea what "normal" is, so the first frames
over-report. Let it watch the empty scene for a second or two --- the repository's own example warms
up over 40 frames --- before trusting its output.

#figure-table("Choosing between the two strategies. The choice is about the scene, not the API.")[
#tbl(
  columns: (1.15fr, 1.2fr, 1.4fr),
  [], [`frameDifference`], [`backgroundSubtraction`],
  [Compares against], [the previous frame], [an adaptive model (MOG2)],
  [Cost], [cheap, immediate], [heavier, needs a warm-up],
  [Slow lighting drift], [no], [yes],
  [Marks the object], [where it was *and* where it is], [only where it is now],
  [Absorbs a stopped object in], [one frame], [on the order of `history` frames],
  [`reset()`], [drops the baseline], [a no-op --- the model relearns on its own],
)
]

The last two rows decide most real deployments. A car that parks in front of a frame-difference
detector stops being motion immediately. The same car in front of MOG2 keeps raising foreground
until the model blends it in, on the order of `history` frames --- at the default of 200, seven
seconds of a 30 fps feed, forty seconds of the 5 fps ESP32 stream --- and when it drives away, the
space it left is foreground for `history` frames more: a ghost, until the model relearns the
tarmac. Neither is a bug; both are the price of the respective memories.

#sidebar("The MJPEG shape")[
  An ESP32-CAM's `/stream` endpoint is not a video in any container sense. It is a `multipart` HTTP
  response whose parts are independent JPEG files, one per frame, and that is why a motion pipeline
  built for it has a byte-shaped entry point:

  ```scala
  val motion: Either[CvError, Motion] = detector.detect(jpegBytes)
  ```

  This overload decodes and detects in one call, and closes the decoded frame for you in a
  `finally`. Doing it by hand as `Image.decode(bytes).map(detector.detect)` compiles, detects the
  same motion, and leaks a frame on every iteration --- the `Image` that `decode` returns is yours,
  and nothing in that expression closes it.

  The `Left` case is narrow: those bytes were not a decodable image. A truncated frame in the middle
  of a Wi-Fi stream becomes a value you log and skip, rather than an exception that tears down a loop
  which has been running since Tuesday.
]

#sect("Tuning as a procedure")

Adjusting whichever number you can remember the name of produces a detector that works on the
afternoon you tuned it. There is an order that converges, and it follows the pipeline. Record ten
minutes of the actual camera first --- a real event and a stretch of nothing --- so every change is
measured against the same footage. Then:

+ *`minArea` first.* By a wide margin the most effective knob, because it is the only one that
  distinguishes objects by *size* rather than by intensity. Sensor speckle and a swaying leaf are a
  handful of pixels; a person at the range you care about is hundreds or thousands. Measure --- on a
  still, do not guess --- how many pixels the smallest thing you want to catch covers at the far end
  of the scene, and gate at about half of that.
+ *Then `threshold` and `blurRadius`, together.* They trade against each other. Raising `blurRadius`
  removes single-pixel noise before the comparison, which lets you *lower* `threshold` and catch
  low-contrast movement --- a dark coat against dark tarmac --- without the speckle firing.
+ *`motionRatio` last,* because it only decides the boolean. Log `ratio` for a day and look at the
  distribution: the gap between the noise floor and a real event is usually a factor of ten, and any
  value in that gap works.

#tip[
  `ratio` is the cheapest telemetry a vision service can emit. It is one `Double` per frame, it
  needs no decoding to interpret, and a week of it plotted against time will show you the daily
  lighting cycle, the moment the auto-exposure hunts, and the exact hour a spider built a web across
  the lens.
]

#sect("The failure modes you will actually hit")

None of these are hypothetical. A fixed outdoor camera meets all of them within a month.

#figure-table("What goes wrong outdoors, and what to do about it.")[
#tbl(
  columns: (1fr, 1.15fr, 1.6fr),
  [Failure], [What you see], [What to do],
  [Slow lighting drift], [`ratio` creeps up over an hour; nothing has moved], [`backgroundSubtraction` --- this is precisely what the adaptive model is for],
  [Auto-exposure hunting], [the whole frame flips brightness for one or two frames], [raise `motionRatio`; require motion on two consecutive frames before acting],
  [Camera shake in wind], [thin regions along every high-contrast edge in the scene], [raise `minArea` and `blurRadius`; mount the camera better --- this is a hardware fault],
  [Rain, snow, foliage], [many small regions, scattered, every frame], [`minArea` first; then `backgroundSubtraction`, which learns a repetitive leaf],
  [Day/night IR switch], [one frame of total change, then a grey feed], [`reset()` on the transition if you can detect it; otherwise ride it out with a consecutive-frames rule],
)
]

Two of those deserve more than a table row. The consecutive-frames rule is worth building in
generally: hold the alarm until `moving` has been true on two or three frames in a row, and every
single-frame artefact --- an exposure step, a compression glitch, a moth crossing the lens --- stops
firing, for one or two frames of latency you will not notice.

And the IR switch has a sharper edge: some cameras change resolution when they change mode. A frame
that is not the size of the retained baseline cannot be differenced, so OpenCV's `absdiff` throws
and scalacv surfaces it at the boundary as `CvError.NativeCall`. What matters is what happens next:
the detector neither leaks that frame nor strands a half-swapped baseline, so a correctly-sized
frame detects normally afterwards. One exception at a real scene change, not a wedged detector.

#example("A mid-stream size change is a value, not the end of the loop.")[
```scala
cam.foreach() { frame =>
  val motion =
    try Right(detector.detect(frame))
    catch case e: CvError => Left(e)

  motion match
    case Right(m) if m.moving => alert(m.regions)
    case Right(_)             => ()
    case Left(err) =>
      println(s"frame rejected: ${err.getMessage}")
      detector.reset()   // the stream changed shape; take a fresh baseline
}
```
]

`reset()` is the right response to any *deliberate* scene change --- the camera was repositioned,
the floodlights came on, the stream renegotiated --- because it treats the next frame as a fresh
baseline instead of reporting the change itself as an event.

#sect("Motion as a trigger")

Now the payoff. The reason to compute all of this is to *not* compute something else: put the
detector in front of the expensive stage and run that stage only on the frames that pass. That is
three lines more than the ungated version, and it changes the cost profile of the whole service.

#example("The gate: a face detector that runs on the frames where something happened.")[
```scala
Camera.usingFile("http://bay-cam.local:81/stream") { cam =>
  val gate = MotionDetector.frameDifference(minArea = 1500, motionRatio = 0.004)
  FaceDetect.create("models/face_detection_yunet_2023mar.onnx", Size(320, 320)).foreach { yunet =>
    var openFor = 0
    try
      cam.foreach() { frame =>
        if gate.detect(frame).moving then openFor = 15   // hold the gate open
        else if openFor > 0 then openFor -= 1

        if openFor > 0 then
          val faces = frame.faces(yunet)
          if faces.nonEmpty then
            frame.copy.markFaces(faces).write(s"hits/${System.currentTimeMillis}.png")
      }
    finally
      yunet.release()
      gate.close()
  }
}
```
]

The `openFor` counter is not decoration, and leaving it out is the mistake that makes gated
pipelines look unreliable. A motion detector reports *change*, and a person who walks into frame and
then stands still stops producing change while remaining entirely present: gating strictly on
`moving` would run the face detector for the two seconds of walking and then blind you for as long
as they stand there. Holding the gate open for a fixed number of frames --- 15 here, three seconds
of a 5 fps stream --- converts "something changed" into "something is probably still here".

The arithmetic is worth doing explicitly. Ungated, every frame pays the detector's cost; gated,
every frame pays the gate's cost and only the passing fraction pays the detector's on top. Gating
wins whenever the gate cost plus the pass rate times the detector cost is less than the detector
cost alone --- at a two per cent pass rate, whenever the detector costs more than about 1.02 times
the gate. It pays as soon as the expensive stage is more expensive than the cheap one, which is the
whole reason you called it expensive.

And the gate is genuinely cheap. `GrayBlurCloneBench` measures its front end directly --- the
default `blurRadius = 2`, so a 5 × 5 Gaussian, over an already-grey frame --- at 126.8 ± 1.7 µs for
1920 × 1080; around it sit the greyscale conversion and single passes over one 8-bit channel. A DNN
forward pass is measured in milliseconds. At a two per cent pass rate the expensive stage's
contribution to the per-frame cost falls to a fiftieth, and the gate does not show up against what
it saved.

#warning[
  Those microseconds came from one developer machine, and `docs/mdoc/benchmark-results.md`, the
  page that records them, says plainly that the deltas reproduce and the absolutes do not. Measure
  both costs on your own hardware before you size a service on them. The *shape* of the argument
  survives any machine; the fiftyfold does not, because the pass rate is a property of your scene,
  not of the library.
]

#sect("The watcher")

Everything so far assembles into the thing people actually want: a camera that writes a clip when
something happens and stops when it is over. The interesting part is not the recording ---
`Recorder`, from Chapter 19, handles that --- but the cooldown, because motion is bursty. A person
crossing the yard produces motion, a gap while they pass behind a pillar, and motion again; without
a cooldown that is three clips of four seconds instead of one clip of twelve.

#example("A clip watcher: open on the first motion, close after a run of quiet frames.")[
```scala
import java.nio.file.Path
import java.time.Instant

final class ClipWatcher(dir: Path, fps: Double, cooldownFrames: Int) extends AutoCloseable:

  private var recorder: Option[Recorder] = None
  private var quiet = 0

  def onFrame(frame: Image, motion: Motion): Unit =
    if motion.moving then
      quiet = 0
      if recorder.isEmpty then start(frame)
    else quiet += 1

    recorder.foreach { rec =>
      rec.write(frame).fold(e => println(s"dropped a frame: ${e.getMessage}"), identity)
      if quiet >= cooldownFrames then stop()
    }

  private def start(frame: Image): Unit =
    val path = dir.resolve(s"clip-${Instant.now().toEpochMilli}.avi").toString
    Recorder.open(path, frame.size, fps) match
      case Right(rec) => recorder = Some(rec)
      case Left(err)  => println(s"cannot record: ${err.getMessage}")

  private def stop(): Unit =
    recorder.foreach(_.close())
    recorder = None
    quiet = 0

  def close(): Unit = stop()
```
]

`Recorder.open` defaults to `Codec.Mjpg`, the one codec videoio can always write, which is why the
path ends in `.avi` --- MJPG does not open in an `.mp4` container. The recorder is sized from the
frame that triggered it, so it inherits what the stream is actually delivering rather than what
`info` claims --- and that size is then fixed. `write` returns `Left` when OpenCV rejects the
encode, but a frame of the wrong size is a programmer error and *throws*, which is the day/night
resolution switch arriving in the writer instead of the detector. Where a camera does that, close
the clip on the size change and open a new recorder. Driving the watcher is the loop you already
have:

#example("The whole watcher: a stream, a gate, and clips on disk.")[
```scala
import scala.concurrent.duration.*

Camera.usingFile(
  "http://bay-cam.local:81/stream",
  CaptureOptions.withTimeout(5.seconds)
) { cam =>
  val detector = MotionDetector.frameDifference(minArea = 1500, motionRatio = 0.004)
  val watcher = ClipWatcher(Path.of("clips"), fps = 5.0, cooldownFrames = 25)
  try cam.foreach()(frame => watcher.onFrame(frame, detector.detect(frame)))
  finally
    watcher.close()
    detector.close()
}
```
]

`cooldownFrames = 25` at five frames a second is five seconds of quiet before a clip is closed ---
long enough to bridge a pillar, short enough that an empty yard is not written to disk.
`CaptureOptions.withTimeout` sets the open and the read deadline together (Chapter 20), and it
matters for a Wi-Fi camera specifically: without it, a stream that stops delivering can block the
read indefinitely instead of ending the loop. The deadline is best-effort --- it is a hint to
whichever backend opened the source --- but the failure it prevents is the common one.

#memory[
  Three ownership rules meet in `onFrame`, and they are all different. `detect` *borrows* the frame.
  `Recorder.write` also *borrows* it --- it reads `image.mat` and does not release --- which is what
  makes it safe to call inside `Camera.foreach`, whose own `finally` closes the frame afterwards.

  Drawing does not borrow. `drawRects` consumes its receiver and hands back a new `Image` owning the
  same `Mat`, so writing annotated clips means taking a copy and closing it yourself:

  ```scala
  val marked = frame.copy.drawRects(motion.regions, Scalar.Red)
  try rec.write(marked) finally marked.close()
  ```

  Skip the `.copy` and the frame you spend is the one `foreach` is holding; skip the `close` and you
  leak a full frame every time something moves --- which is to say, every time the system is doing
  its job.
]

#sect("Where this goes next")

A `Motion` gives you boxes per frame and nothing across frames. It cannot tell you that the box on
the left of this frame is the same van as the box in the middle of the last one, so it cannot count
vans, measure how long one stayed, or tell one leaving from another arriving. `ObjectTracker`,
Chapter 30, is the consumer that can: it never looks at the image, only at the boxes. Background
subtraction is the better source of those boxes, because it marks only where an object is; frame
differencing would hand the tracker two blobs per object and invite it to invent an identity for the
hole.

#example("Motion boxes in, stable identities out --- the counting pipeline in miniature.")[
```scala
val watcher = MotionDetector.backgroundSubtraction(minArea = 1500)
val tracks = ObjectTracker.create(iouThreshold = 0.3, maxAge = 5)
try
  cam.foreach() { frame =>
    val live = tracks.update(watcher.detect(frame).regions)
    live.foreach(t => println(s"#${t.id} at ${t.box} for ${t.age} frames"))
  }
  println(s"${tracks.count} distinct objects crossed the bay")
finally
  tracks.close()
  watcher.close()
```
]

`update` returns a `Seq[ObjectTrack]` --- a persistent `id`, the current `box`, and the `hits` and
`age` counts that say how much to trust it --- while `count` is the running total of distinct
objects stitched together. Tracker and detector share a contract: stateful, holding native memory,
not safe to share across threads.

Before that comes the problem this chapter has been assuming away: that the loop keeps up. A
detector falling behind a live camera neither throws nor slows the camera down --- it measures older
and older frames, and for frame differencing a skipped frame corrupts the *measurement* rather than
delaying it, because the baseline and the current frame are no longer adjacent. Chapter 22,
#emph[Streaming and Backpressure], comes next: the three things you can do when frames arrive faster than
you can process them, and how to drop the ones you skip without leaking them.
