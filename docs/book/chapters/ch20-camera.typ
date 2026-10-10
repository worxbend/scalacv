#import "../lib/book.typ": *

#chapter("Cameras and Recording", subtitle: [Live capture, where the source has no end, no honesty about itself, and no patience.])

A video file is a well-behaved thing. It has a beginning, an end, a fixed frame size and a frame
rate written into its container, and reading it twice gives you the same frames in the same order.
Every chapter up to this one has assumed that shape, or something like it: an input that sits still
while you work on it.

A camera is none of that. It is hardware with a driver in front of it, and the driver has opinions.
It will tell you the frame rate is zero, because no frame has come out yet and it does not care to
guess. It will accept a request for 1280 × 720 and give you 640 × 480 without mentioning the
substitution. It will hand you a black first frame, because the sensor's auto-exposure loop has not
converged, and report it as a complete success. It queues frames on your behalf, so the image you
receive describes the recent past. And at some point --- a jostled cable, a switch reboot, a
power-saving mode you did not know about --- it will stop delivering, in a way bit-for-bit identical
to a file reaching its last frame.

None of that is fixable at the library layer. What a library can do is refuse to paper over it:
return the failures as values, say which numbers are advisory, and free the six megabytes of pixels
behind every frame whatever else goes wrong. That is what `Camera` and `Recorder` do. They are thin,
deliberately, and the interesting part of this chapter is not their surface but the four operational
problems underneath: warm-up, latency, reconnection, and the per-frame copy. The running example ---
watch a camera, find faces, draw boxes, record the result, stop cleanly on Ctrl-C --- accounts for
none of them at first and all four by the end.

One piece of setup. The face-detection lines below --- `Cascades`, `CascadeName` and the `detect` /
`detectHaar` verbs --- live in `scalacv-vision`, not in the core artifact, so add
`com.worxbend::scalacv-vision:0.4.1` beside the core dependency of Chapter 2, and add
`import scalacv.vision.*` beside `import scalacv.*`. The rest of the chapter is core only.

#sect("Opening a source")

`Camera.open` takes a device index; `Camera.openFile` takes anything the videoio backend
understands --- a filesystem path, an `rtsp://` or `http://` URL, a `frame_%04d.png` numbered
sequence. Both hand back an `Either[CvError, Camera]`, because whether a source opens is
data-dependent: the device may not exist, may be held by another process, or may speak a protocol no
compiled-in backend can read. None of those is a programming error, so none of them throws.

#example("The four ways in, and the two you should use.")[
```scala
import scalacv.*

val byIndex: Either[CvError, Camera] = Camera.open(0)
val byUrl: Either[CvError, Camera]   = Camera.openFile("rtsp://camera.local/stream")

// The scoped forms close the camera on every exit path, including an exception.
Camera.using(0) { cam => cam.snapshot().flatMap(_.write("shot.png")) }
Camera.usingFile("clip.mp4") { cam => cam.recordTo("blurred.avi")(_.blur(4)) }
```
]

`Camera` is `AutoCloseable` and caller-owned. `using` and `usingFile` wrap your block's result in
the `Right` and surface a failed open as the `Left`, so the outer type of that first `using` is
`Either[CvError, Either[CvError, Unit]]` --- the inner `snapshot` has an error case of its own.
`flatten` it.

#memory[
A `VideoCapture` is one of exactly three `org.opencv.*` types with a real public `release()`, the
others being `Mat` and `VideoWriter`. That does not make the handle optional: an unreleased capture
holds the device open, and on Linux the next process to ask for `/dev/video0` gets a `Left` saying
the device is in use. Prefer the scoped form; if you hold a `Camera` across calls, close it in a
`finally`.
]

#sect("Everything the source tells you is a guess")

`cam.info` returns a `CaptureInfo`; `cam.size` and `cam.fps` are shortcuts onto it. Every field is a
`CAP_PROP_*` query answered by the backend, and the backend is permitted to answer badly.

#figure-table("What CaptureInfo reports, and the conditions under which it is wrong.")[
#tbl(
  columns: (0.9fr, 0.7fr, 1.9fr),
  [Field], [Type], [When it lies to you],
  [`width` / `height`], [`Int`], [`0` on a camera that has not yet delivered a frame],
  [`fps`], [`Double`], [`0` for a camera that has not delivered a frame],
  [`frameCount`], [`Long`], [`0` for any live source, where the question is meaningless; off by a frame or two in some containers],
  [`backendName`], [`String`], [--- the one field that is true],
)
]

Hence a rule with no exceptions: never write a loop bound from `frameCount`.
`for i <- 0 until cam.info.frameCount.toInt` processes nothing against a webcam, and reads past the
end or stops short against a file whose container rounds. The only true frame count is the one the
loop delivers, and the loop already knows when to stop.

#subsect("Warm-up, and why your first frame is black")

A webcam is not ready when `open` returns. Auto-exposure, auto-white-balance and auto-gain are
closed loops running on the device, converging over a handful of real frames, and there is no
property to poll for "converged" --- so the only fix is to pull frames and throw them away.

`CaptureOptions.warmupFrames` is how many to discard. Its default is `None`, meaning "let the source
decide": a device index discards five, a file or URL discards none. That split is the whole point. A
file has no exposure loop, its first frame is as correct as its hundredth, and discarding frames
there would skip real content rather than junk.

#example("Overriding the warm-up in either direction.")[
```scala
val patient = CaptureOptions(warmupFrames = Some(20))   // a dim room, a slow sensor
val exact   = CaptureOptions(warmupFrames = Some(0))    // no discards: I want frame zero
```
]

#subsect("Asking for a resolution")

`CaptureOptions` carries a backend, two timeouts and the warm-up count, and nothing else --- no
`resolution` field and no `fps` field, because scalacv does not wrap a setting it cannot honour.
Both are set on the raw capture through the borrowed `cam.capture`, and every one of those calls is
a request rather than an instruction.

#example("Ask for a mode; then find out what you were given.")[
```scala
import org.opencv.videoio.Videoio

/** Requests a capture mode and returns what the driver actually settled on. */
def request(cam: Camera, width: Int, height: Int, fps: Double): CaptureInfo =
  val cap = cam.capture                      // borrowed; still owned by the Camera
  cap.set(Videoio.CAP_PROP_FRAME_WIDTH, width.toDouble)
  cap.set(Videoio.CAP_PROP_FRAME_HEIGHT, height.toDouble)
  cap.set(Videoio.CAP_PROP_FPS, fps)
  cam.info                                   // read it back — this is the only answer that counts
```
]

`set` returns a `Boolean`, and `true` means the backend accepted the property, not that the hardware
honoured the value. A camera offering 640 × 480, 1280 × 720 and 1920 × 1080 will snap a request for
800 × 600 to the nearest mode it has and report success. Read `cam.info` back, size your recorder
from what it says, and treat a disagreement as normal rather than an error.

#warning[
Changing the mode after `open` invalidates the warm-up `open` already paid for: the sensor
re-negotiates and the exposure loop starts over. A `cam.taking(5)(_ => ())` straight after the `set`
calls costs five frame periods --- a sixth of a second at 30 fps --- and saves you a dark first
frame.
]

#sect("The frame loop")

`foreach` runs your function over every frame, each one a fresh, owned `Image` on exactly the terms
Chapter 4 set out, and closes that `Image` when your function returns --- on success, on failure, and
on exception.

#example("The signature, which has two parameter lists.")[
```scala
def foreach(attemptsPerFrame: Int = 3)(f: Image => Unit): Unit
```
]

The empty parentheses are load-bearing: `cam.foreach() { frame => … }`, or `cam.foreach(1) { … }`
for a file, where the first failed read genuinely is end-of-stream. The default of `3` is a bound,
not a retry policy --- a live camera drops frames without being dead, and three consecutive empty
reads is where the library stops guessing. It cannot be unbounded, because `VideoCapture.read` blocks
in native code with no timeout of its own, so infinite retry against a dead device is a hung thread
that also spins a core.

`foreach` closes the `Image` it gave you, so it is tempting to conclude nothing inside the loop can
leak. Here is the loop that follows from that conclusion:

#example("Wrong: one full frame leaked per iteration, forever.")[
```scala
cam.foreach() { frame =>
  val boxes = frame.detectHaar(classifier)
  rec.write(frame.drawRects(boxes))          // the drawn Image is never closed
}
```
]

`drawRects` is a transform, so Chapter 4's move semantics apply: it *consumes* `frame` and returns a
*new* `Image` wrapping the same `Mat`. The handle `foreach` holds has been spent, so the `close()` in
its `finally` is a no-op, and the new `Image` --- the one that owns the pixels --- never reaches a
terminal, because `rec.write` borrows. Every iteration strands six megabytes at 1080p, 180 MB per
second at 30 fps, none of it visible to the collector: Chapter 1's failure, reproduced exactly.

#example("Right: the annotated image is named, and closed.")[
```scala
cam.foreach() { frame =>
  val boxes = frame.detectHaar(classifier)
  val marked = frame.drawRects(boxes, Scalar.Green, Thickness.Stroke(2))
  try rec.write(marked).fold(throw _, identity)
  finally marked.close()
}
```
]

The rule, once: inside `foreach`, the library owns the frame it handed you, and you own everything a
transform hands back.

#subsect("Batches")

When you need several frames at once --- to compare them, composite them, or pick the sharpest ---
`take(count, attemptsPerFrame = 3)` returns them as a `Seq[Image]`, every element a live resource in
a bare collection no type can warn you about. `taking` is the scoped counterpart, and the one to
reach for:

#example("A scoped batch closes every frame, including on an exception.")[
```scala
def taking[A](count: Int, attemptsPerFrame: Int = 3)(use: Seq[Image] => A): A

cam.taking(5) { frames =>
  frames.map(f => (f.width, f.height))       // plain Ints escape; the five Images must not
}
```
]

Frames beyond the end of the stream are absent rather than an error, so a batch may come back
shorter than you asked for.

#sect("Recording, and the size you do not know yet")

A `Recorder` is fixed at open time to one frame size, one frame rate and one codec, and every frame
written must match that size and be 8-bit. Both are `require`s, because a mismatched pipeline is a
programming error --- and for depth the check is the only signal that can exist: `VideoWriter.write`
never inspects the depth, so a `CV_32F` frame would encode as a playable file of noise with every
call reporting success.

That fixed geometry collides with everything the last section said about a camera's self-reporting.
The obvious code is wrong:

#example("Wrong: on a live camera this throws out of a method that promises an Either.")[
```scala
Camera.using(0) { cam =>
  Recorder.using("out.avi", cam.size, cam.fps) { rec => … }
}
```
]

`cam.fps` is `0` for a camera that has not delivered a frame, and `Recorder.open` opens with
`require(fps > 0, …)` --- so this raises an `IllegalArgumentException` from inside a call whose type
says it reports failure as a `Left`. `cam.size` can be `0` × `0` for the same reason, and trips the
positive-size `require` one line further down.

Take the geometry from a decoded frame instead, which cannot misreport its own dimensions, and
supply a frame rate rather than asking for one. `Camera.recordTo` does exactly that: it opens the
recorder from the *first transformed frame*, and its `fps` parameter defaults to `0`, meaning
"derive it from the source, falling back to 30 when the source reports none".

#example("Right: read, transform, write, with the recorder sized from real pixels.")[
```scala
val written: Either[CvError, Long] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.recordTo("edges.avi")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
  }.flatten
```
]

`transform` is therefore free to resize, as long as it resizes every frame the same way; a size that
changes part-way through is a `Left` rather than a throw, because a source that renegotiates
mid-stream (adaptive RTSP, a Media Foundation format change) is not the transform's mistake. A source
that yields no frames returns `Right(0)` and creates no file. The trailing
`convert(ColorConversion.GrayToBgr)` is not decoration: `canny` produces a single-channel edge map,
and the recorder was opened `color = true`.

#subsect("Codecs, and the container that comes with them")

Chapter 19's codec table applies here unchanged, and so does its rule: the codec and the file
extension travel together. What is worth repeating in a camera chapter is why the default is the
ugly one. `Codec.Mjpg` in an `.avi` is served by videoio's built-in MJPEG writer --- no FFmpeg, no
GStreamer, no system codec --- and the `org.bytedeco` `linux-x86_64` and `windows-x86_64` payloads
this project builds against ship no FFmpeg plugin at all, so `Mp4v` and `Avc1` do not open there. A
default of `Mp4v` would have returned a `Left` on the very natives the library pins. An unavailable
codec is a `Left` and never a silent black file: OpenCV leaves `isOpened` false, and `Recorder.open`
turns that into a `CvError.LoadFailed` naming the fallback to try.

#sect("write borrows, and at 30 fps that is the whole game")

`Recorder`'s two `write` overloads both borrow. `write(image: Image)` does not consume, which is why
the corrected loop still had to close `marked`. `write(frame: Mat)` exists for one reason: `Camera`
sits on `Video.framesCopied`, which clones every frame so the `Image` is safe to keep, while
`Video.frames` (Chapter 19's borrowing exception) decodes into one reused `Mat` and allocates
nothing. Annotate a frame and write it straight out, and that clone buys nothing.

#example("A zero-copy annotate-and-record loop: one Mat for the whole stream.")[
```scala
Video.frames(cam.capture, attemptsPerFrame = 3) { frames =>
  frames.foreach { frame =>                                  // borrowed, reused, never copied
    val boxes = frame.detect(classifier)                     // mid-level: Seq[Rect], no handles
    boxes.foreach(b => frame.drawRect(b, Scalar.Green, Thickness.Stroke(2)))
    rec.write(frame).fold(throw _, identity)                 // borrows the same Mat
  }
}
```
]

The mid-level `drawRect` and `drawText` mutate their receiver and return `Unit`, so the annotations
land in the decode buffer itself and do not accumulate: the next `read` overwrites it. The library's
own benchmark notes put the priority plainly --- stop copying frames before you start reusing
buffers. Per-frame destination allocation measured inside the noise; the copies were worth an order
of magnitude more.

#memory[
Chapter 19's borrowing contract is in force throughout that loop. The `Mat` is valid from the
`next()` that produced it until you next touch the iterator, and released when the `frames` block
returns. `frames.toList` compiles and gives you N references to one buffer holding the last frame.
Reduce inside the loop, or use `Camera` and pay for the clone.
]

#sect("The frame you read is not the frame that is happening")

Between the sensor and your `read` there is a queue. The driver fills it as frames arrive; you drain
it as fast as your processing allows. If your loop takes longer than a frame period the queue grows
and never shrinks, and the frame you are handed ages with it. Nothing reports this: the frames keep
arriving, keep decoding, keep looking correct. Whether that matters depends on what they are for.

#figure-table("Latency: when the backlog is a bug and when it is free.")[
#tbl(
  columns: (1.1fr, 1.9fr),
  [Job], [What a growing backlog does],
  [Recording, re-encoding], [Nothing. Every frame is footage; late is fine, missing is not],
  [Motion detection, frame differencing], [Changes the measurement --- two frames 200 ms apart are not two frames 33 ms apart],
  [Interaction, gesture, a live endpoint], [Breaks it. The answer describes a moment that has passed],
  [Optical flow, tracking, odometry], [Breaks it differently --- these assume small motion between frames],
)
]

Two levers exist, both on the borrowed capture, because neither behaves the same way on two
backends. The first is the driver's queue depth, exposed as a capture property:

#example("A diagnostic, not a fix.")[
```scala
val accepted = cam.capture.set(Videoio.CAP_PROP_BUFFERSIZE, 1.0)
val reported = cam.capture.get(Videoio.CAP_PROP_BUFFERSIZE)
```
]

`accepted` being `true` does not mean the driver honoured it; `reported` may be the value you asked
for, the value in force, `0` or `-1`; and nothing in `CaptureInfo` can confirm it either way. Try it,
then measure --- if the backlog does not change, you did not get it.

The second lever is the split between `grab()` and `retrieve(mat)`, which is what `read` does
together: `grab` fetches the next frame from the device, `retrieve` decodes and colour-converts it.
The decode is the expensive half, so discarding a queued frame with a bare `grab()` is far cheaper
than reading it --- a way to catch up to the head of the queue. But `grab` blocks exactly as `read`
does when the queue is empty: ask for two extra grabs when only one frame is queued and the second
waits on the sensor, putting you a frame period behind a source you were keeping up with. Reasonable
on a source you have measured yourself to be behind on; a bad permanent setting.

#tip[
For a recorder, ignore all of this. A recording has no latency requirement, only a completeness
requirement --- which dropping frames violates. Make the consumer fast enough and let the queue do
its job.
]

#sect("When the camera goes away")

`Camera.foreach` and `Camera.snapshot` end the stream after `attemptsPerFrame` consecutive empty
reads, and OpenCV cannot say which reason applied: the file ended, or the device is gone. For a
camera only one of those is possible, and a `Camera` whose device has disappeared does not heal.

So the supervision boundary is the `Camera` itself: close it and go back through `open`. Backoff so
a dead device does not spin a core, a cap on the wait so a device that returns is picked up within a
bounded time, and a bounded count so a permanently dead device becomes an error somebody can act on
rather than a process that looks healthy and does nothing.

#example("A supervised capture loop with a reopen budget.")[
```scala
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicBoolean

/** Runs `onFrame` over a source, reopening it when the stream ends. Gives up after
  * `maxReopens` consecutive attempts that produce no frames, returning the last failure.
  */
def supervised(
    open: () => Either[CvError, Camera],
    maxReopens: Int,
    running: AtomicBoolean
)(onFrame: Image => Unit): Either[CvError, Long] =

  // The shift is capped: an uncapped one overflows to a negative sleep, which throws.
  def backoff(failures: Int): Unit = Thread.sleep(math.min(250L << math.min(failures, 8), 30_000L))

  @tailrec
  def loop(seen: Long, failures: Int, last: Option[CvError]): Either[CvError, Long] =
    if !running.get then Right(seen)
    else if failures > maxReopens then
      Left(last.getOrElse(CvError.LoadFailed("camera", s"no frames after $maxReopens reopens")))
    else
      open() match
        case Left(err) =>
          backoff(failures)
          loop(seen, failures + 1, Some(err))
        case Right(cam) =>
          var got = 0L
          try cam.foreach(3) { frame => got += 1; onFrame(frame) }
          finally cam.close()
          // A reopen that delivered frames earns the budget back; one that delivered
          // nothing spends another line of it.
          if got > 0 then loop(seen + got, 0, None)
          else
            backoff(failures)
            loop(seen, failures + 1, last)

  loop(0L, 0, None)
```
]

Two things around that loop are easy to get wrong. A reopened camera is cold again --- the exposure
loop restarts, so nothing derived from overall brightness should be seeded from the first frame after
a reopen. And reopens are a signal, not an implementation detail: count them.
Chapter 39 treats "reopens per camera-hour" as an error-budget line with a target of under one, on
the grounds that a camera reopening several times an hour is telling you something about the cable,
the switch or the contended device that no retry logic will fix.

#sect("Network cameras")

Two protocols cover almost all of them, and they fail differently.

An *MJPEG* camera serves a `multipart/x-mixed-replace` HTTP stream of independently compressed JPEG
frames. Every frame is a keyframe, so there is no inter-frame state to lose: drop one, reconnect
mid-stream, and the next frame still decodes on its own. The cost is bandwidth --- no temporal
compression at all. Open one with `Camera.openFile("http://…")`.

*RTSP* is what almost every IP security camera speaks, carrying H.264 or H.265, so a dropped packet
corrupts frames until the next keyframe. It also needs a backend that can decode it --- FFmpeg or
GStreamer --- and if your OpenCV build ships neither, `open` returns a `Left` that retrying will not
change.

For both, set the timeouts. `CaptureOptions.withTimeout` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC`
and `CAP_PROP_READ_TIMEOUT_MSEC`, and network sources are where they earn their keep:

#example("Timeouts, and the backend that honours them.")[
```scala
import scala.concurrent.duration.*

val opts = CaptureOptions.withTimeout(5.seconds, CaptureBackend.FFmpeg)
val stream: Either[CvError, Camera] = Camera.openFile("rtsp://camera.local/stream", opts)
```
]

Be precise about what that buys. FFmpeg and GStreamer honour the timeouts for network sources; V4L2,
AVFoundation and the built-in MJPEG reader ignore them entirely, and nothing in the API says which
backend you got --- so a wedged USB webcam blocks forever whatever you set. They apply only at open
time, which is why they travel through `CaptureOptions` rather than `cam.capture.set`. And a backend
that does not understand them rejects the open outright: a local `.avi` opened with the parameters
attached reports `isOpened == false` where the same file opens fine without them, which is why
`Video.open` retries without them.

Naming a backend deserves the same caution. `CaptureBackend.Any` takes the first registered backend
that works, and that is what you want in production; one that is not compiled into the build on your
classpath cannot open anything, so naming it turns a working `open` into a failing one. If you must
pin, put the named backend above `Any` in a ladder so a build without it still opens.

#sidebar("The one place a window appears")[
Nothing in this chapter needs a display. Capture, detection, annotation and recording are
computation over pixels, and the core carries no GUI dependency --- OpenCV's `imshow` needs a toolkit
that resolves differently on every host, which is exactly the dependency a headless library must not
have.

To watch frames arrive, the `examples-gui` module carries `scalacv.CamFaceDetect`: a JavaFX
application that pulls frames through `Video.frames`, runs a Haar cascade, draws the boxes, and
paints the result into a window by encoding each frame to PNG bytes for a JavaFX `Image` --- no AWT,
no `SwingFXUtils`. Run it with `./mill examples-gui.runMain scalacv.CamFaceDetect`. It is an example,
not a library: `examples-gui` is never built in CI and never published, because OpenJFX resolves per
host. Read it for the `setOnCloseRequest` that releases the cascade and the capture; do not depend on
it. And note what a display costs --- `Mat`-to-`BufferedImage` measures 976 µs at 1920 × 1080 with
three channels, 3% of a 30 fps budget spent on looking at the thing.
]

#sect("The whole loop")

Everything above, assembled: open a camera, load a bundled Haar cascade, learn the true frame
geometry from a decoded frame rather than from `info`, record annotated frames through the borrowing
`write`, and stop on Ctrl-C via a shutdown hook that flips a flag --- so the loop finishes the frame
it is on and the `using` blocks close the recorder and the camera in order rather than the process
dying with a half-written file.

#example("Capture, detect, annotate, record, and shut down cleanly.")[
```scala
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.objdetect.CascadeClassifier
import scalacv.*

@main def watch(index: Int, out: String): Unit =
  OpenCv.load()

  val running = AtomicBoolean(true)
  Runtime.getRuntime.addShutdownHook(Thread(() => running.set(false), "scalacv-stop"))

  val result = Camera.using(index) { cam => record(cam, out, running) }.flatten
  result.fold(e => println(s"capture failed: ${e.getMessage}"), n => println(s"wrote $n frames"))

def record(cam: Camera, out: String, running: AtomicBoolean): Either[CvError, Long] =
  for
    cascade  <- Cascades.load(CascadeName.FrontalFaceAlt)
    // A decoded frame cannot misreport its own size; `cam.size` can, and does.
    geometry <- cam.snapshot().map(first => try first.size finally first.close())
    written  <- cascade.use { classifier =>
                  Recorder.using(out, geometry, fps = 15) { rec =>
                    loop(cam, rec, classifier, running)
                  }
                }
  yield written

/** The hot loop: one borrowed Mat for the whole stream, annotated in place, written through. */
def loop(cam: Camera, rec: Recorder, classifier: CascadeClassifier, running: AtomicBoolean): Long =
  var written = 0L
  Video.frames(cam.capture, attemptsPerFrame = 3) { frames =>
    while running.get && frames.hasNext do
      val frame = frames.next()
      val boxes = frame.detect(classifier, minNeighbors = 5)
      boxes.foreach(b => frame.drawRect(b, Scalar.Green, Thickness.Stroke(2)))
      frame.drawText(s"${boxes.size} faces", Point(10, 24), Scalar.White, scale = 0.7)
      rec.write(frame).fold(throw _, _ => written += 1)
  }
  written
```
]

Count the native objects and where each dies. The capture belongs to the `Camera`, released by
`Camera.using`. The cascade --- one of the 185 types with no public `release()`, freed through
Chapter 5's handle bridge --- is released by `cascade.use`. The `VideoWriter` is released by
`Recorder.using`, which is what finalises the file. The snapshot `Image` is closed in its own
`finally`, one line after its size is read. The decode buffer is the single `Mat` `Video.frames` owns
and releases when the block returns. Five native lifetimes, five scopes, and no `release()` written
by hand anywhere.

The `while running.get && frames.hasNext` ordering matters too: with the flag checked first, no
further read starts once Ctrl-C has been pressed, so shutdown latency is bounded by the frame being
processed rather than by a blocking read against a device that may already have gone quiet.

#sect("What the frames are worth looking at for")

This chapter got frames into your program and back out to a file with their lifetimes accounted for,
and said almost nothing about what to compute from them. The cheapest useful answer is the one most
specific to a fixed camera: has anything in this scene changed? Frame differencing costs a
subtraction and a threshold, runs at full frame rate on hardware that could not load a network, and
turns a continuous stream into a sparse list of events worth recording. It is also, as the latency
table hinted, the algorithm most sensitive to everything this chapter warned about. Chapter 21,
#emph[Motion Detection], takes `MotionDetector` and its `Motion` result apart on those terms.
