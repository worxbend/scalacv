---
title: Degradation and error budgets
description: How a scalacv service stays up when a camera, a codec, a model or native memory is unavailable — the three failure tiers, the fallback ladders, and the numbers to put on an SLO.
---

# Degradation and error budgets

Every guide in this site so far has shown you the *happy path* plus the failure it is allowed to
return. This page is the other half of the job: what you do when a webcam is unplugged mid-shift,
when the box you deployed to has no H.264 encoder, when the model mirror 404s, and when native
memory runs out. It is written for whoever carries the pager.

Two ideas carry the whole page:

- **A ladder.** For each thing that can be unavailable — a codec, a capture source, a model — there
  is an ordered list of things to try, from "best" to "always works". You climb down it until
  something opens, and you record which rung you landed on.
- **A budget.** An *error budget* is a number you agree on in advance: how much of a particular
  failure is normal, so that everyone knows when to stop watching and start fixing. The last section
  turns each ladder into a budget line.

Everything below is compiled by mdoc against the real library.

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*

OpenCv.load()
```

## The failure taxonomy, by what can catch it

The [error model](/error-model) sorts failures by *what they mean* — a value to handle, or a bug to
fix. Operationally you need a different sort: **what will catch this?** There are three tiers, and
the third one is the one that takes services down.

| Tier | Arrives as | Caught by | Your move |
| --- | --- | --- | --- |
| 1 | `Either[CvError, A]` | an ordinary `match` / `fold` at your boundary | branch, degrade, count it |
| 2 | thrown `IllegalArgumentException` / `IllegalStateException` | a `catch` you should not write | fix the call site |
| 3 | thrown `java.lang.Error` | **nothing in scalacv** | catch it yourself, at the outermost frame |

### Tier 1 — the seven `CvError` cases

`CvError` is a sealed hierarchy with exactly seven shapes, so an exhaustive `match` over it is also an
exhaustive list of the labels your metrics can carry. Give each one a stable string and use the same
string for the log line, the metric label and the budget line:

```scala mdoc:silent
/** The label to attach to a metric or a log line for each failure the library can return. */
def budgetLabel(e: CvError): String = e match
  case CvError.DecodeFailed(_, _)   => "decode_failed"      // bytes that are not an image
  case CvError.EncodeFailed(_, _)   => "encode_failed"      // an unwritable path, an unknown extension
  case CvError.LoadFailed(_, _)     => "load_failed"        // a model, cascade, codec or capture source
  case CvError.EndOfStream(_, _)    => "end_of_stream"      // a video source run dry — a file's last frame
  case CvError.CalibrationFailed(_) => "calibration_failed" // too few chessboard views, or no convergence
  case CvError.NativesMissing(_, _) => "natives_missing"    // a build problem, not a runtime one
  case CvError.NativeCall(op, _)    => s"native_call:$op"   // OpenCV rejected a call; `op` names which
```

```scala mdoc
Image.read("/does/not/exist.png").left.map(budgetLabel)
```

`NativeCall` carries the *operation name* that `Cv.attempt` was given, which is why it is worth
putting into the label rather than collapsing it: `native_call:cvtColor(BGR2GRAY)` tells you where to
look, `native_call` alone does not.

### Tier 2 — thrown, and not yours to catch

An `IllegalArgumentException` (a negative blur radius, a `Thickness.Stroke(0)`) and an
`IllegalStateException` (touching an `Image` a transform already consumed) are **bugs in your code**.
They are deliberately outside the `Either`. Do not add a `catch` for them to keep a request alive —
that hides a defect that will produce wrong pixels somewhere else. Let them reach whatever your
framework does with a 500, and fix the call. See
[the error model](/error-model#programmer-errors-stay-outside-the-either) and
[Troubleshooting](/troubleshooting#move-semantics).

### Tier 3 — `java.lang.Error`, which nothing here catches

This is the tier that surprises people, so here is the whole of `Cv.attempt` from
`core/src/scalacv/Cv.scala`, verbatim — the single function every fallible native call in the
library goes through:

```scala
  def attempt[A](operation: String)(a: => A): Either[CvError, A] =
    try Right(a)
    catch
      case e: CvException => Left(CvError.NativeCall(operation, e))
      case e: CvError => Left(e)
      // OpenCV's throwJavaException falls back to a bare java.lang.Exception (std::bad_alloc, std::out_of_range,
      // unknown) for failures that are not cv::Exception. Match the exact class so genuine programmer errors —
      // IllegalArgumentException, RuntimeException, and other subclasses — still propagate.
      case e: Exception if e.getClass == classOf[Exception] => Left(CvError.NativeCall(operation, e))
```

Three `catch` clauses. Read what they are *not*: there is no `case e: Throwable`, and no
`case e: Error`. So:

- `org.opencv.core.CvException` — the exception OpenCV's JNI layer raises for a `cv::Exception` — is
  caught and named.
- A `CvError` the block already produced passes through unchanged, so wrapping an already-lifted call
  does not double-wrap it.
- The third clause is guarded by `e.getClass == classOf[Exception]`, an **exact-class** test. It
  catches a *bare* `java.lang.Exception` and nothing else. OpenCV's `throwJavaException` degrades to
  exactly that for failures it cannot classify, including `std::bad_alloc` — so a native allocation
  failure raised *inside OpenCV* does land in tier 1 as a `Left`. Because the test demands the exact
  class, every `Exception` **subclass** — `IllegalArgumentException`, `IllegalStateException`, your
  own — still propagates. That five-line body is the entire mechanism that keeps tier 2 out of the
  `Either`; it is not a convention anyone has to remember.
- Nothing at all handles `java.lang.Error`. `OutOfMemoryError`, `StackOverflowError`,
  `NoClassDefFoundError`, `UnsatisfiedLinkError` — all of them travel straight through
  `Cv.attempt`, through every `flatMap` in your pipeline, and out of the thread.

You can watch it happen:

```scala mdoc
val escaped: String =
  try
    Cv.attempt("an allocation that trips the ceiling") {
      throw OutOfMemoryError("Physical memory usage is too high: physicalBytes (1200M) > maxPhysicalBytes (1G)")
    }.fold(e => s"returned Left(${e.getClass.getSimpleName})", _ => "returned a Right")
  catch case e: OutOfMemoryError => s"escaped the Either as a ${e.getClass.getName}"
```

The same is true of `Cv.orThrow`, which is `attempt` plus a rethrow, and of every high-level method
built on them.

## The memory ceiling is an `Error`, not a `CvError`

:::danger[`maxPhysicalBytes` failures pass through every `Either` in the library]
[Deploying to production](/deploying-to-production#cap-memory--and-cap-it-on-the-right-counter) and
[Mat lifecycle](/mat-lifecycle) both tell you to set
`-Dorg.bytedeco.javacpp.maxPhysicalBytes` so that a leak "fails fast, not slow". That advice is
right, and this is the part it does not say: when the ceiling is breached, JavaCPP throws

```
java.lang.OutOfMemoryError: Physical memory usage is too high: physicalBytes (1200M) > maxPhysicalBytes (1G)
```

`java.lang.OutOfMemoryError` extends `VirtualMachineError` extends `Error`. It is **tier 3**. No
`Either` in scalacv contains it, no `CvError` describes it, and no `catch` in the library sees it. On
a worker thread with no handler of its own, the thread dies, the request never completes, and — with
an executor that swallows uncaught throwables — nothing is logged. The pod stays "ready" and returns
timeouts.
:::

### Shed the request, then quarantine the instance

The fix is one `catch` at the **outermost frame you own** — the request boundary, the queue-consumer
loop, the batch item. Two things happen there: the request is shed with a 503 (telling the load
balancer to send it elsewhere), and the instance marks itself unhealthy so it stops receiving new
work instead of failing each one in turn.

```scala mdoc:silent
import java.util.concurrent.atomic.AtomicBoolean

/** Flipped to false once this instance has shed. The readiness probe reads it. */
val healthy = AtomicBoolean(true)

/** One request boundary. A CvError becomes a response; an Error sheds and quarantines. */
def handleUpload(upload: Array[Byte]): (Int, String) =
  try
    Image.decode(upload) match
      case Left(e) => (400, budgetLabel(e)) // the bytes are not an image: the caller's problem
      case Right(image) =>
        image.gray.canny(80, 160).bytes(".png") match
          case Right(png) => (200, s"${png.length} bytes of PNG")
          case Left(e)    => (500, budgetLabel(e))
  catch
    case e: OutOfMemoryError =>
      // Tier 3. Nothing below this line caught it, and nothing was going to.
      healthy.set(false)
      (503, s"shed: ${e.getMessage}")
```

```scala mdoc:silent
val upload =
  Image
    .blank(240, 160, Scalar(30, 30, 30))
    .drawRect(Rect(30, 30, 90, 60), Scalar.White, Thickness.Filled)
    .drawCircle(Point(170, 100), 35, Scalar.White, Thickness.Filled)
    .bytes(".png")
```

```scala mdoc
upload.map(handleUpload)
```

```scala mdoc
handleUpload("this is not an image".getBytes)
```

Three details make this recipe work rather than merely look tidy:

1. **Catch `OutOfMemoryError`, not `Throwable`.** Catching `Throwable` would also swallow tier 2 —
   the programmer errors you want to see — and turn a bug into a quiet 503.
2. **Do not retry inside the handler.** The process is at its ceiling; a retry allocates again and
   fails again, faster. Shed and let the next instance take it.
3. **Make the shed visible in the readiness probe.** Gate readiness on
   `OpenCv.isLoaded && healthy.get()`. An instance that has shed once has a leak or a workload it
   cannot serve; it should drain, not limp.

:::warning[Set the ceiling *below* the container limit, on purpose]
In a cgroup (what a container's memory limit actually is), whoever hits the limit first decides what
diagnostic you get. If the kernel gets there first, the process is killed with `SIGKILL` — no stack,
no log line, exit code 137, nothing to read. If JavaCPP gets there first, you get the message above,
with a stack trace pointing at the allocation. So set `maxPhysicalBytes` **comfortably under** the
container limit — a JVM heap plus code cache plus thread stacks live in the same budget — precisely
so the error you receive is the one that explains itself.
:::

### What the ceiling does not do

Be honest with yourself about the mechanism, because it changes what you monitor. On javacpp
`1.5.13` (this project now builds against `1.5.14`; re-check there before relying on the detail), the
comparison against `maxPhysicalBytes` lives
in one place: `org.bytedeco.javacpp.Pointer.deallocator(Pointer.Deallocator)`, the method a JavaCPP
`Pointer` calls to register its native buffer. It is not a background watchdog; it runs **when
JavaCPP allocates**, and nowhere else.

scalacv's pixel buffers are `org.opencv.core.Mat`s, allocated by OpenCV's own JNI layer
(`cv::fastMalloc`) and never routed through a JavaCPP `Pointer`. The *number* being compared is
honest — `Pointer.physicalBytes()` reads the whole process's resident set size, so it does include
your Mats, which is exactly why
[`maxBytes` is the wrong counter](/performance#measuring-memory-do-it-right). What is not guaranteed
is the *moment of comparison*: in a service whose hot path allocates only through `org.opencv.*`,
there may be no JavaCPP allocation to trigger the check, and resident memory can drift past the
ceiling without an `OutOfMemoryError` ever being raised.

Treat `maxPhysicalBytes` as a **backstop that produces a good diagnostic when it fires**, not as a
hard cap. The alarm is still an RSS gauge you sample yourself — see
[Testing](/testing#guard-against-native-leaks-with-an-rss-assertion) for the measurement, and
[Troubleshooting](/troubleshooting#native-leak) for what to do when it climbs.

## One ladder helper, three ladders

All three ladders below have the same shape: try things in order, keep the first success, and if
everything fails report the *last* failure — because the last rung is the most portable one, and its
message describes the situation you are actually stuck in.

```scala mdoc:silent
/** Runs each rung in order and keeps the first success.
  *
  * If every rung fails, the failure returned is the last one: the ladder is ordered
  * best-first / most-portable-last, so the final message describes the fallback that
  * was supposed to always work.
  */
def firstSuccess[A](rungs: Seq[() => Either[CvError, A]]): Either[CvError, A] =
  require(rungs.nonEmpty, "a fallback ladder needs at least one rung")
  rungs.foldLeft[Either[CvError, A]](Left(CvError.LoadFailed("ladder", "no rung ran"))) { (soFar, rung) =>
    soFar match
      case ok @ Right(_) => ok
      case Left(_)       => rung()
  }
```

## The codec ladder

A **codec** is the algorithm that compresses video frames into a file; a **container** is the file
format that holds them (`.mp4`, `.avi`). Which codecs exist is a property of the OpenCV build on your
classpath, not of your code — and the bytedeco `linux-x86_64` and `windows-x86_64` payloads this
project builds against ship **no FFmpeg plugin at all**, so `Codec.Mp4v` and `Codec.Avc1` will not
open there. That is not a bug to work around; it is a fact to plan for.

`Recorder.open` reports it as a `Left`, never as a silently black file, and the message already tells
you the fallback:

> `could not load 'out.mp4': VideoWriter could not open with codec Mp4v — the codec may be unavailable in this OpenCV build, or the path may not be writable. Try Codec.Mjpg with an .avi extension, which encodes with the built-in codecs.`

The ladder goes best-compression-first, always-works-last. `Codec.Mjpg` in an `.avi` is the bottom
rung because videoio's MJPEG writer is built in — no FFmpeg, no GStreamer, no system codec — and it
is the reason `Codec.Mjpg` is the default for `Recorder.open`, `Recorder.using` and
`Camera.recordTo`. The container travels with the codec: MJPG opens **only** in an `.avi`, so a path
ending `.mp4` fails even where the codec itself is present.

```scala mdoc:silent
/** Best compression first, most portable last. The extension is part of each rung, not an afterthought. */
val codecLadder: Seq[(Codec, String)] =
  Seq(Codec.Avc1 -> ".mp4", Codec.Mp4v -> ".mp4", Codec.Mjpg -> ".avi")

/** Opens the best recorder this build can actually give us, and reports which rung that was. */
def openRecorder(base: String, size: Size, fps: Double): Either[CvError, (Codec, Recorder)] =
  firstSuccess(codecLadder.map { case (codec, extension) =>
    () => Recorder.open(base + extension, size, fps, codec).map(recorder => (codec, recorder))
  })
```

```scala mdoc:silent
val outDir = java.nio.file.Files.createTempDirectory("scalacv-codec-ladder")
```

```scala mdoc
val chosen =
  openRecorder(outDir.resolve("clip").toString, Size(320.0, 240.0), 25.0)
    .map { case (codec, recorder) => recorder.close(); codec }
```

That `Right(...)` is what the machine building this page actually landed on. Record the same value as
a metric label at start-up: if a deployment silently drops from `Avc1` to `Mjpg`, your output files
grow by an order of magnitude, and the codec label is the only place that shows up before the disk
does.

:::note[Probe the ladder once, at start-up — not per recording]
Opening a recorder is cheap but not free, and each failed rung prints an OpenCV warning to stderr
(building this page produced a `VIDEOIO(CV_IMAGES): raised OpenCV exception` line for the rungs that
did not open) which you do not want once per clip. Run the ladder during warm-up against a throwaway
path, keep the winning `Codec`, and pass it explicitly from then on. See
[Video & the camera](/video#codecs-and-portability) for the codec table and
[Troubleshooting](/troubleshooting#codec) for the symptom.
:::

## The capture ladder

`Camera.open(index)` and `Camera.openFile(source)` return a `Left` when — in the API's own words —
the device "does not exist, is in use, or no backend can drive it". Those three causes need different
responses, and OpenCV cannot tell them apart for you: `VideoCapture.open` reports failure by leaving
`isOpened` false, and `Video.open` turns that into

> `could not load 'camera 0': VideoCapture.open reported failure without an OpenCV message — the source may not exist, may be in use, or no available backend can read it`

Because you cannot distinguish "unplugged forever" from "busy for another two seconds", the honest
strategy is **reopen with exponential backoff and a cap**. Backoff, so a genuinely dead device does
not spin a core; a cap on the wait, so a device that comes back is picked up within a bounded time.

```scala mdoc:compile-only
import scala.concurrent.duration.*

/** Reopens a source with exponential backoff, returning the last failure if every attempt fails. */
def openWithBackoff(source: String, attempts: Int = 5): Either[CvError, Camera] =
  def go(attempt: Int, waitMillis: Long): Either[CvError, Camera] =
    Camera.openFile(source, CaptureOptions.withTimeout(5.seconds)) match
      case Right(camera)                  => Right(camera)
      case Left(e) if attempt >= attempts => Left(e)
      case Left(_) =>
        Thread.sleep(waitMillis)
        go(attempt + 1, math.min(waitMillis * 2, 30_000L))
  go(1, 250L)
```

Two things to get right around this loop.

**A reopened camera is cold again.** A webcam's auto-exposure, auto-white-balance and auto-gain are
closed loops running on the device; they need a handful of real frames to converge, and there is no
property to poll for "converged". So the first frames after every reopen can be black or badly
under-exposed — and OpenCV reports them as successes. `Camera.open` therefore discards a few frames
before handing the capture back (`CaptureOptions.warmupFrames`, defaulting to 5 for a device index
and 0 for a file or URL, which have no exposure loop). If you compute anything from a frame's overall
brightness — a motion baseline, an auto-threshold — do not seed it from the first frame after a
reopen.

**Reopen, do not re-read.** A `Camera` whose device has gone away does not heal. `Camera.foreach` and
`Camera.snapshot` end the stream after `attemptsPerFrame` consecutive empty reads — the default of
`3` rides out a dropped frame without turning a dead camera into an endless loop — and after that
they stop. `snapshot` says so:
`"no frame available — the stream ended or the device delivered nothing"`. When you see that, close
the `Camera` and go back through `open`; do not keep pulling.

**Naming a backend is a portability decision, not a tuning knob.** `CaptureOptions` lets you ask for
`CaptureBackend.FFmpeg`, `V4L2`, `MediaFoundation` and so on. From the enum's own documentation:

> A backend that is not compiled into the OpenCV build on the classpath simply cannot open anything,
> so naming one turns a working `open` into a failing one. The bytedeco 4.13.0 builds do not all
> carry the same set — this is a portability decision, not a tuning knob.

`CaptureBackend.Any` (the default) asks OpenCV to try its registered backends in priority order and
use the first that works, which is what you want in production. Name one only when the automatic
choice is demonstrably wrong on *that* deployment, and then treat it as pinned to that platform. If
you must pin, put the named backend *above* `Any` in a ladder so a build without it still opens:

```scala mdoc:compile-only
def openPreferringV4l2(index: Int): Either[CvError, Camera] =
  firstSuccess(
    Seq(
      () => Camera.open(index, CaptureOptions(backend = CaptureBackend.V4L2)),
      () => Camera.open(index) // CaptureOptions.Default — CAP_ANY, no timeouts
    )
  )
```

:::note[Timeouts are best-effort, and that is not scalacv's choice]
`VideoCapture.read` has no timeout overload and blocks in native code, so a stream that stops
delivering hangs the calling thread. OpenCV's only lever is `CAP_PROP_OPEN_TIMEOUT_MSEC` /
`CAP_PROP_READ_TIMEOUT_MSEC`, which FFmpeg and GStreamer honour for network sources and which V4L2,
AVFoundation and the built-in MJPEG reader ignore entirely — with nothing in the API to report which
you got. Set them for `rtsp://` and `http://` sources, where a hang is the failure you face; do not
expect them to bound a local file or a USB camera. Your real protection there is a watchdog on your
own side: a thread that has not produced a frame in N seconds gets the instance drained.
:::

## The model ladder

Detection weights are the one input that has to arrive over the network. `Models.fetch(spec, dir)`
tries each mirror in `spec.urls` **in order**, downloads to a temp file beside the target, verifies
the pinned SHA-256, and only then moves it into place — so an interrupted run never leaves a
truncated model for the next boot to trip over. It is idempotent: a target that already exists and
still matches its hash is returned without touching the network. Every mirror failure is collected,
and the final `Left` lists them all.

When the weights are unavailable, the degradation available to you is a **bundled Haar cascade**,
which needs no network at all — the XML ships inside the per-platform OpenCV classifier jar and
`Cascades.load` extracts it from the classpath.

```scala mdoc:compile-only
import java.nio.file.Path

/** Face boxes from YuNet when the weights are available, from the bundled cascade when they are not. */
def faceBoxes(image: Image, modelDir: Path): Either[CvError, Seq[Rect]] =
  firstSuccess(
    Seq(
      () =>
        Models
          .fetch(FaceDetect.modelSpec, modelDir)
          .flatMap(model => FaceDetect.create(model.toString, image.size))
          .map(_.use(detector => image.faces(detector).map(_.box))),
      () => Cascades.load(CascadeName.FrontalFaceAlt).map(_.use(c => image.detectHaar(c)))
    )
  )
```

The bottom rung runs offline, here, now:

```scala mdoc:silent
val probe = Image.blank(200, 200)
val fallbackBoxes = Cascades.load(CascadeName.FrontalFaceAlt).map(_.use(c => probe.detectHaar(c)))
probe.close()
```

```scala mdoc
fallbackBoxes.map(_.size) // Right(0) — a blank frame has no faces, which is a result, not an error
```

**Say out loud what you are trading.** YuNet is a 232 kB convolutional network that is both markedly
more accurate and faster than the Haar cascades, and it returns five facial landmarks per face
(eyes, nose tip, mouth corners) that the cascade cannot give you at all. `FrontalFaceAlt` is the best
of the shipped cascades and still produces visibly more false positives, finds fewer faces at an
angle or in poor light, and returns only a bounding box. So a fallback detection is **not**
interchangeable with a primary one:

- Tag every degraded detection in your output, so downstream consumers (and your evaluation set) can
  tell them apart. A landmark-dependent feature — alignment, face recognition, an AR overlay — must
  be *disabled*, not fed a guess.
- Count degraded detections as their own budget line. A fallback that has been silently serving for
  three weeks is an outage nobody declared.

:::warning[The cascade fallback does not exist on Windows]
The `windows-x86_64` bytedeco jar ships an **empty** `share/` directory and no cascade XML at all, so
`Cascades.load` returns `Left(CvError.LoadFailed)` there — and says so in those words. On Windows the
bottom rung of this ladder is not "no network needed", it is "no detector". Ship
`haarcascade_frontalface_alt.xml` with your application and use `Cascades.loadFrom(path)`, which
takes a filesystem path and is otherwise identical. See
[Troubleshooting](/troubleshooting#cascades-windows).
:::

The better answer for anything air-gapped is to remove the network from the ladder entirely: bake the
model into the image or a mounted volume and point a `ModelSpec` at a `file://` URL, which
`Models.fetch` serves like any other source. See
[The native cache](/native-cache#downloaded-model-files-dnn-face-recognition) and
[Deploying to production](/deploying-to-production#provision-models-offline).

## Setting a budget

An error budget is only useful if each line maps to a signal you actually emit. scalacv has no
logging facade and no metrics of its own, so *every* line below is something you instrument at your
boundary — which is exactly why the `budgetLabel` function above uses the same strings as your
metric labels.

| Budget line | The signal | Where it comes from | A starting number | When it burns |
| --- | --- | --- | --- | --- |
| Decode failures | share of requests answered `Left(DecodeFailed)` | `Image.read` / `Image.decode` at the upload boundary | under 2% of uploads | usually a client bug or a new file type — a 4xx, not a page |
| Encode failures | count of `Left(EncodeFailed)` | `Image.write` / `Image.bytes` | 0 in steady state | a full or read-only volume; page |
| Capture reopens | reopens per camera-hour | your backoff loop around `Camera.open` / `openFile` | under 1 per camera-hour | a flaky USB link, a contended device, or a network stream dropping |
| Frames dropped | `1 − (framesWritten / expected)` | `Camera.recordTo`'s `Right(n)` against `CaptureInfo.frameCount` **(advisory — never a loop bound)** | under 1% per clip | the pipeline is slower than the source; shrink the transform or drop resolution |
| Recorder-open failures | `Left(LoadFailed)` per recording started | the codec ladder | 0 once the ladder has settled | a platform change — the ladder found no rung at all |
| Codec rung in use | the `Codec` label from the start-up probe | `openRecorder` | pinned to one value | a silent drop to `Mjpg` multiplies output size |
| Model-fetch failures | `Left(LoadFailed)` per boot | `Models.fetch` | 0 | a mirror outage, a proxy, or a checksum mismatch (which is never retryable) |
| Degraded detections | share of frames served by the fallback rung | your model ladder | under 1% of frames | accuracy is quietly worse than your evaluation says |
| Native-call errors | rate of `Left(NativeCall)`, **split by `operation`** | `Cv.attempt`'s operation name | near 0 | one operation dominating is a shape/type bug, not load |
| OOM sheds | 503s from the tier-3 `catch` | the request boundary | 0 | page immediately: an instance shed and quarantined itself |
| RSS headroom | process RSS as a fraction of `maxPhysicalBytes` | `/proc/self/statm`, or `Pointer.physicalBytes()` | under 70% | a steady climb under steady load is a leak |

Two notes on reading this table.

**`CaptureInfo.frameCount` is advisory.** Every field of `CaptureInfo` is a `CAP_PROP_*` query: a
live camera commonly reports `0`, and some containers are off by a frame or two from what actually
decodes. It is fine as the denominator of a *ratio you watch over time*, and wrong as a loop bound or
a hard assertion.

**Split `NativeCall` by operation before you alert on it.** A flat "native errors" rate hides the
only thing that distinguishes a load problem from a bug: whether the failures are spread across
operations or piled onto one. `Cv.attempt` already names the operation for you; keep the name.

Finally, do not alert on OpenCV's stderr. Two paths in the library print an OpenCV warning as part of
working correctly: `Video.open` speculatively tries the timeout parameters and retries without them
when a backend rejects them, and the codec ladder above probes writers that are expected to fail.
Both are `Left`s or retries you already count. Alert on your own `CvError` rate instead.

## The checklist

- [ ] One `catch case e: OutOfMemoryError` at every boundary you own — request handler, queue
      consumer, batch item.
- [ ] Readiness gated on `OpenCv.isLoaded` **and** a health flag the shed path clears.
- [ ] `maxPhysicalBytes` set below the container limit, with an RSS gauge as the real alarm.
- [ ] The codec ladder run once at start-up; the winning `Codec` exported as a label.
- [ ] Capture reopened through backoff, never re-read after end-of-stream.
- [ ] `CaptureBackend.Any` unless a specific deployment proves otherwise — and then laddered.
- [ ] Models provisioned from `file://` where the network is not guaranteed.
- [ ] Degraded detections tagged in the output and counted as their own budget line.
- [ ] Every `CvError` case mapped to a stable metric label; `NativeCall` split by operation.

## Next

- The policy these tiers implement, case by case: [The error model](/error-model).
- The operational checklist this page assumes: [Deploying to production](/deploying-to-production).
- Codecs, backends and the frame loop in depth: [Video & the camera](/video).
- What the fallback detector can and cannot do: [Object detection](/object-detection).
- Measuring the memory the ceiling is guarding: [Performance](/performance#measuring-memory-do-it-right).
