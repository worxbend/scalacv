# ZIO

scalacv's core is deliberately effect-free: it hands you ownership of native objects through
[`Managed`](/mat-lifecycle) and expects you to release them with `try`/`finally` or a `use` block.
The `scalacv-zio` module expresses that *same* ownership as ZIO `Scope`, so a native object is tied
to a scope's lifetime and freed when the scope closes — on success, on failure, and on
**interruption**, which a plain `try`/`finally` cannot promise once a fiber can be cancelled.

Nothing here changes the memory model; it changes *who drives it*. A `Mat` acquired through a scope
is freed exactly once, by the scope, and using it after the scope closes is the same
use-after-release error `Managed` already guards against. On top of that, every native and
filesystem call runs on ZIO's **blocking** pool, so a stalled camera or a slow decode can never
starve the fibers doing your real work.

Reach for this module when you already run a ZIO app and want native resources to obey the same
`Scope`/interruption rules as everything else. If you are not on ZIO, the synchronous
[`Managed`](/mat-lifecycle), [`Image.reading`](/image-api), and [`Camera.using`](/video) give you the
same safety without the dependency.

## Install

Add it alongside your natives:

```scala
mvn"com.worxbend::scalacv-zio:0.2.0"
```

Everything below assumes these imports; in mdoc they are established once and persist across the
page:

```scala mdoc:silent
import scalacv.vision.*
import _root_.zio.*
import _root_.zio.stream.*
import scalacv.*
import scalacv.zio.*
import org.opencv.core.{CvType, Mat}
import org.opencv.videoio.VideoCapture
```

## The surface at a glance

| Function | Returns | What it does |
| --- | --- | --- |
| `loadNatives` | `Task[Unit]` | Loads OpenCV natives on the blocking pool. Idempotent. |
| `acquireRelease(make)` | `ZIO[Scope, Throwable, A]` | Ties any `Releasable` native object to the scope. |
| `mat.scoped` | `ZIO[Scope, Throwable, Mat]` | Ties an already-allocated `Mat` to the scope. |
| `fromCv(result)` | `IO[CvError, A]` | Lifts an `Either[CvError, A]` into ZIO's **typed** error channel. |
| `readImage(path, flags)` | `IO[CvError, Image]` | Decodes an image (blocking pool), caller-owned, typed failure. |
| `imageScoped(path, flags)` | `ZIO[Scope, CvError, Image]` | Reads an image and closes it when the scope ends. |
| `captureScoped(source, options)` | `ZIO[Scope, CvError, VideoCapture]` | Opens a video source and releases it when the scope ends; a source that will not open is a **typed failure**, not an empty stream. |
| `frameStream(capture, attemptsPerFrame)` | `ZStream[Any, Throwable, BorrowedMat]` | Frames as **borrowed**, liveness-checked views — one reused buffer; a retained frame throws. |
| `framesCopied(capture)` | `ZStream[Any, Throwable, Managed[Mat]]` | Frames as **owned** clones — the safe, costlier form. |

## Loading the natives

`loadNatives` is the effectful face of `OpenCv.load()`. It is idempotent — the underlying loader
does its ~196 MB extraction and `dlopen` at most once — so it is safe to require from many places.
It runs on the blocking pool because that first extraction touches disk:

```scala mdoc:silent
val boot: Task[Unit] = loadNatives
```

Require it before any native work in a `for` comprehension and you are done; a second call is free.

## Acquire a Mat into a scope

`acquireRelease` ties any native object to the current scope, so it is freed when the scope closes —
on success, on failure, and on **interruption**, which a plain `try`/`finally` cannot promise:

```scala mdoc:silent
val program: _root_.zio.ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _   <- loadNatives
      mat <- acquireRelease(Mat(1080, 1920, CvType.CV_8UC3))
    yield mat.rows
  }
```

Anything with a [`Releasable`](/mat-lifecycle) instance works, not just `Mat`. A handle type — a
`CascadeClassifier`, a `FaceDetectorYN` — is released through the `delete(long)` bridge with its
finalizer disarmed, exactly as `Managed` would do it:

```scala mdoc:silent
import org.opencv.objdetect.CascadeClassifier

given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)

val classifierProgram: _root_.zio.ZIO[Any, Throwable, Unit] =
  ZIO.scoped {
    for
      _ <- loadNatives
      _ <- acquireRelease(CascadeClassifier())
    yield ()
  }
```

If an operation *already* allocated a `Mat` and you just want the scope to own it from here on, use
the `.scoped` extension rather than re-wrapping it:

```scala mdoc:silent
val adopt: _root_.zio.ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _   <- loadNatives
      raw <- ZIO.attemptBlocking(Mat(32, 32, CvType.CV_8UC1))
      mat <- raw.scoped
    yield mat.cols
  }
```

:::warning[Do not escape the scope]
The `Mat` is freed the instant the scope closes. Returning it — or a value that aliases its native
buffer — from `ZIO.scoped` is a use-after-release waiting to happen. Reduce it to an *owned* value
(a number, an encoded `Array[Byte]`, a fresh `Image`) **inside** the scope, and yield that.
:::

## Typed errors, not bare Throwables

`readImage`, `imageScoped`, and `fromCv` keep scalacv's [`CvError`](/error-model) ADT in ZIO's
**typed** error channel instead of collapsing it to `Throwable` — so a missing file or a bad decode
is a value you can pattern-match, not a defect you hope someone catches.

`fromCv` is the general bridge for any `Either[CvError, A]` the synchronous API returns:

```scala mdoc:silent
val loadCascade: _root_.zio.IO[CvError, Unit] =
  fromCv(Cascades.load(CascadeName.FrontalFaceDefault)).unit
```

`imageScoped` reads on acquire and closes when the scope ends — on success, failure, *and*
interruption, which the synchronous `Image.reading` cannot promise once an interrupt is in play:

```scala mdoc:silent
val dims: _root_.zio.ZIO[Any, CvError, (Int, Int)] =
  ZIO.scoped {
    imageScoped("photo.jpg").map(img => (img.width, img.height))
  }
```

A full read-process-write pipeline stays scoped end to end. The write returns an `Either[CvError,
Unit]`, so it is lifted back through `fromCv`:

```scala mdoc:silent
val edgesToDisk: _root_.zio.ZIO[Any, CvError, Unit] =
  ZIO.scoped {
    for
      _   <- loadNatives.orElseFail(CvError.LoadFailed("natives", "could not load OpenCV"))
      img <- imageScoped("photo.jpg")
      _   <- fromCv(img.copy.gray.canny(80, 160).write("edges.png"))
    yield ()
  }
```

`readImage` is the un-scoped form when you want to own the `Image` yourself (then `.close()` it, or
prefer `imageScoped`). Both take an optional [`ImreadFlags`](/image-io) — `Grayscale`, `Color`
(default), `ColorRgb`, `Unchanged`, `AnyDepth`:

```scala mdoc:silent
val readGrey: _root_.zio.IO[CvError, Image] =
  readImage("scan.png", ImreadFlags.Grayscale)
```

## Stream frames

`frameStream` inherits the borrowing contract of the synchronous [`Video.frames`](/video), enforced
the same way: each emitted `BorrowedMat` is **one buffer** decoded into in place, spent the moment the
stream advances or ends. Reduce each frame to something owned **inside** the stream — retaining an
view throws `IllegalStateException` on access rather than silently showing you the newest frame.
The raw `frame.mat` escape hatch is checked only at extraction: retaining it is still unsafe, and
concurrent access while the source advances is not supported:

```scala mdoc:silent
def brightnessOverTime(source: String): _root_.zio.ZIO[Any, Throwable, _root_.zio.Chunk[Double]] =
  ZIO.scoped {
    for
      _   <- loadNatives
      cap <- captureScoped(source)
      out <- frameStream(cap).map(f => f.get(0, 0)(0)).runCollect
    yield out
  }
```

The capture is acquired through `captureScoped` so the scope owns and releases it; `frameStream`
deliberately does **not** close it. Prefer `captureScoped` over `acquireRelease(VideoCapture(source))`:
the bare `VideoCapture` constructor cannot fail — OpenCV reports "I could not open that" by leaving
`isOpened` false rather than by throwing — so a typo'd path hands you a live object whose every read
returns false. `captureScoped` checks that for you and fails with a typed `CvError` instead, and
`frameStream` fails the stream on the first pull if it is handed a capture that never opened.

Past that, the stream stops at the first frame that fails to decode — for a file, end-of-stream; for a
camera, a dropped connection — the two being indistinguishable through OpenCV's API. For the duration of
the stream the capture's exception mode is forced off and restored afterwards, so a finished file
*completes* the stream instead of failing it.

:::danger[These combinators break on `frameStream`]
Anything that retains elements collects N *spent* views — the first access to any of them throws
`IllegalStateException`, exactly like a released `Managed`. On `frameStream`, avoid:

| Combinator | Why it fails |
| --- | --- |
| `runCollect` of the frames themselves | Collects N spent views; first access throws. |
| `broadcast` | Fan-out consumers race on one buffer; all but the current view are spent. |
| `buffer` | Holds spent views. |
| `zipWithNext` | The "previous" element is spent by the time the pair emits. |

Map to an owned value first (`.map(f => f.get(0,0)(0))`, encode it, copy the pixels), *then* combine.
:::

## A dropped frame ends your stream {#dropped-frame}

The *borrowing* contract carries over from `Video.frames` unchanged — and so does the retry bound.
Both take an `attemptsPerFrame`: how many consecutive `read()` calls have to come back empty before
the source is declared finished. `frameStream` defaults it to `1` — right for a file, where the first
empty read is end-of-file — so against a flaky camera, pass it explicitly:

```scala mdoc:compile-only
// `attemptsPerFrame = 3` rides out two dropped frames in a row; the third empty read ends the stream.
def brightnessRidingOutDrops(source: String): _root_.zio.ZIO[Any, Throwable, _root_.zio.Chunk[Double]] =
  ZIO.scoped {
    for
      _   <- loadNatives
      cap <- captureScoped(source)
      out <- frameStream(cap, attemptsPerFrame = 3).map(f => f.get(0, 0)(0)).runCollect
    yield out
  }
```

The **end-of-stream** rule is the part that does not change, and it is easy to miss because nothing
fails when it bites:

| | `Video.frames(cap, attemptsPerFrame = 3)` | `frameStream(cap, attemptsPerFrame = 3)` |
| --- | --- | --- |
| consecutive empty reads that declare the end | 3 — your choice, the default is 1 | 3 — your choice, the default is 1 |
| a transient dropped frame | read again, the traversal continues | read again, the stream continues |
| how the end is signalled | `hasNext` returns `false` | `ZIO.fail(None)`, which `ZStream` reads as "no more elements" |
| what your program sees | the block returns normally | the stream **completes successfully** — no error, no defect, no log line |

That last row is the trap. A camera that drops one frame too many and a file that reached its last
frame produce the identical outcome: a `ZStream` that finishes cleanly. Your `runCount` returns 7
instead of 7000 and nothing anywhere says why.

### Telling end-of-file from a dead camera

You cannot, not from OpenCV: a finished file and a broken connection are reported through the same
failed `read`, which is why the synchronous API documents the two as indistinguishable. The only honest
signal is time — how long since the last frame arrived — and the place to bound it is the source, not
the stream:

```scala mdoc:compile-only
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

// FFMPEG and GStreamer honour CAP_PROP_READ_TIMEOUT_MSEC for network sources. V4L2, AVFoundation and
// the built-in MJPEG reader ignore it, and nothing in the API reports which backend you got.
def countRtspFrames(url: String): _root_.zio.ZIO[Any, Throwable, Long] =
  ZIO.scoped {
    for
      _   <- loadNatives
      cap <- captureScoped(url, CaptureOptions.withTimeout(FiniteDuration(5L, TimeUnit.SECONDS)))
      n   <- frameStream(cap).runCount
    yield n
  }
```

:::warning[Interruption does not cut a blocked read short]
`frameStream` wraps its read in `attemptBlockingInterrupt`, which delivers a JVM `Thread.interrupt()` —
and a thread parked inside OpenCV's native code never observes one. So interrupting the stream, or
closing the scope around it, takes effect only once the in-flight `capture.read` returns on its own;
until then the buffer `Mat`, the exception-mode restore and any enclosing `Scope` all stay pending.
Running on the blocking pool means a wedged source pins a blocking thread instead of a compute one — it
does not mean the read can be cancelled. Bound it at the source with `CaptureOptions.withTimeout`, on a
backend that honours it.
:::

## When you genuinely need to keep frames

`framesCopied` is the safe-but-costlier counterpart: each element is its own clone as a
[`Managed[Mat]`](/mat-lifecycle), so the usual `ZStream` combinators behave. Each clone must still be
released — map straight into a releasing stage:

```scala mdoc:silent
def frameSizes(source: String): _root_.zio.ZIO[Any, Throwable, Long] =
  ZIO.scoped {
    for
      _     <- loadNatives
      cap   <- captureScoped(source)
      count <- framesCopied(cap).mapZIO(m => ZIO.succeed(m.use(_.rows))).runCount
    yield count
  }
```

:::warning[Consume each clone in the fiber that pulls it]
Ownership of a clone transfers to the consumer, so release it promptly on the same fiber —
`.mapZIO(m => m.use(process))`. A clone dropped because the fiber was **interrupted** before a
downstream `use`/scope took it over leaks, exactly as a dropped `Managed` would in synchronous code.
Do not buffer the `Managed`s (`.buffer`, `.grouped`, `runCollect` without a prior release) across an
interruptible boundary. When you want the *stream* to own each frame, reduce it inside
`frameStream` instead — its one reused buffer is tied to the stream's scope and released on
interruption.
:::

## Blocking work stays off the compute pool

`VideoCapture.read`, `OpenCv.load()`, and the native model/image decodes all block **inside native
code** with no JVM-interruptible timeout of their own. This module runs every one of them on ZIO's
blocking executor — `attemptBlocking` for the acquire/load/decode paths, `attemptBlockingInterrupt`
inside `frameStream` — never the CPU-sized default executor. That is the contract you can rely on:

- A stalled source — an RTSP stream that stops delivering frames, a dead camera — pins a thread on
  the **blocking** pool, not a compute thread, so it cannot starve the fibers doing your actual work.
- What it does **not** buy you is cancellation of a read already in flight. `attemptBlockingInterrupt`
  delivers a `Thread.interrupt()`, and a thread sitting inside OpenCV's native `read` never sees it, so
  an interrupted stream unwinds only after that read returns by itself, as *A dropped frame ends your
  stream* above spells out. Cap the wait at the source with `CaptureOptions.withTimeout`.

Parking these on the compute executor — which a plain `ZIO.attempt` would do — would let one hung
capture exhaust it; that is why the module never does. (There is even a test that greps the module
source to prove no bare `ZIO.attempt(` wraps a native call.)

## Next

- [Mat lifecycle](/mat-lifecycle) — the `Managed`/`Releasable` ownership model this module maps onto `Scope`.
- [Video](/video) — the synchronous `Video.frames`/`Camera.using` these streams mirror.
- [Error model](/error-model) — the `CvError` ADT that stays in ZIO's typed error channel.
