# Video & the camera

A video — a file, a webcam, an RTSP stream — is a source you open, walk frame by frame, and reduce
to something as you go: edges, a face count, a re-encoded clip. scalacv gives you two levels for
that, and this page leads with the one to reach for first.

- [`Camera`](#camera--frames-as-owned-images) is the **high-level** face. Every frame arrives as a
  fresh, owned [`Image`](/image-api) — transform it, detect on it, annotate it, keep it, on the same
  terms as any other `Image`, with the lifetime handled for you. This is where almost all video work
  should start.
- [`Video`](#the-low-level-videoframes) is the **zero-copy** floor underneath it: one reused,
  borrowed `Mat` for the whole traversal, no per-frame native-buffer allocation. Drop to it only when the copy
  `Camera` makes per frame is the thing that matters.

The whole surface is headless: it decodes and computes, and never draws to a window (see
[displaying frames](#displaying-frames)).

:::note[Nothing on this page runs under mdoc]
A real capture needs a camera, a file, or a codec that CI does not have, so **every runnable snippet
is `compile-only`** — it type-checks against the real library but is not executed. The paths
(`"clip.mp4"`), indices (`0`) and sizes are realistic stand-ins; swap in your own. If you are new
here, read [Getting started](/getting-started) first — it covers `OpenCv.load()` and the `Image`
basics this page assumes.
:::

## Which level, at a glance

Reach for `Camera` unless a profiler says otherwise. This table is the decision:

| You want to… | Use | Frame you get | Lifetime |
|---|---|---|---|
| Grab one frame | [`Camera.snapshot`](#snapshot--one-frame) | owned `Image` | yours (or scoped by `using`) |
| Process every frame | [`Camera.foreach`](#foreach--the-processing-loop) | owned `Image` | **closed for you** per frame |
| Collect a handful | [`Camera.taking`](#take--taking--collect-a-batch-of-frames) | owned `Image`s | closed for you at block end |
| Transform a whole file to a new video | [`Camera.recordTo`](#recordto--read-transform-write-in-one-line) | — | handled |
| Build frames and write them | [`Recorder`](#recorder) | you supply them | you own the recorder |
| Squeeze out the per-frame copy | [`Video.frames`](#the-low-level-videoframes) | **borrowed** `BorrowedMat` | one reused buffer, liveness-enforced |
| Keep frames past the loop, low-level | [`Video.framesCopied`](#keeping-a-frame) | owned `Managed[Mat]` | yours to release |

The split in one sentence: **`Camera` copies every frame so you can keep it; `Video.frames` never
copies, so a frame is spent the moment the stream advances — retaining one throws
`IllegalStateException` instead of reading overwritten memory.** See
[the ownership split](#the-ownership-split) for when the copy is worth avoiding.

## Camera — frames as owned Images

`Camera` is the high-level counterpart to `Video`. Where `Video.frames` hands you one reused buffer
behind a liveness-checked `BorrowedMat`, `Camera` hands you a fresh **owned** `Image` per frame. The
price is one frame copy per iteration; when that price is the bottleneck,
[drop to the low level](#the-ownership-split).

The methods, all on an open `Camera`:

| Method | Returns | Notes |
|---|---|---|
| `snapshot(attemptsPerFrame = 3)` | `Either[CvError, Image]` | one frame; `Left` at end-of-stream |
| `foreach(attemptsPerFrame = 3)(f)` | `Unit` | every frame; each `Image` closed for you |
| `take(count, attemptsPerFrame = 3)` | `Seq[Image]` | a batch — **you close them** |
| `taking(count, …)(use)` | `A` | scoped batch — closed for you |
| `recordTo(path, …)(transform)` | `Either[CvError, Long]` | read → transform → write, frames written |
| `info` / `size` / `fps` | `CaptureInfo` / `Size` / `Double` | advisory metadata |
| `capture` | `VideoCapture` | borrowed escape hatch |
| `close()` | `Unit` | idempotent; `using` calls it for you |

### Opening a camera

`Camera.open` takes a device index; `Camera.openFile` takes anything the backend understands — a
file path, an `rtsp://`/`http://` URL, or a `frame_%04d.png` image-sequence pattern. Both return
`Either[CvError, Camera]`, never a bare capture, because whether a source opens is
**data-dependent** — the file may be missing, the camera busy, no backend able to drive the
protocol. None of that is a programming error, so it is a `Left` carrying a
[`CvError`](/error-model), never a throw.

```scala mdoc:compile-only
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*

val fromCamera: Either[CvError, Camera] = Camera.open(0)          // device index
val fromFile: Either[CvError, Camera]   = Camera.openFile("clip.mp4")
```

A `Camera` is caller-owned and `AutoCloseable`. The scoped `using` / `usingFile` forms open, run
your block, and **close for you** on every exit path — even on an exception — so they are the safe
default:

```scala mdoc:compile-only
import scalacv.*

Camera.using(0) { cam =>
  cam.snapshot().flatMap(_.write("shot.png"))
}
```

`open` / `openFile` are there for when the camera has to outlive a single block; then you
[`close()`](#the-ownership-split) it yourself.

Both forms accept [`CaptureOptions`](#backends-and-options) — the same options `Video.open` takes,
for picking a backend or setting network timeouts.

:::tip[Every `using` returns `Either[CvError, A]`]
`Camera.using` and `usingFile` wrap your block's result in the `Right`, and surface a failed open as
the `Left` — so the whole capture-and-process is one value you can `.map` / `.flatMap`. The inner
`snapshot()` is itself an `Either`, which is why the example above uses `flatMap` (the outer `map`
would leave you an `Either[CvError, Either[CvError, Unit]]`; `flatten` it if you `.map`).
:::

### snapshot — one frame

`snapshot()` grabs a single frame as an owned `Image`. It is a `Left` when the stream has ended or
the device delivered nothing within `attemptsPerFrame` reads (a camera can drop a frame without
being dead, so it retries a few times by default):

```scala mdoc:compile-only
import scalacv.*

Camera.using(0) { cam =>
  cam.snapshot().flatMap(_.gray.write("frame.png"))
}
```

A snapshot is a first-class [`Image`](/image-api), so the whole surface applies before you write it —
crop a region of interest, resize, run a detector. Here it is resized to a thumbnail and encoded to
bytes instead of a file:

```scala mdoc:compile-only
import scalacv.*

val thumbBytes: Either[CvError, Array[Byte]] =
  Camera.using(0) { cam =>
    cam.snapshot().flatMap(_.resize(160, 120).bytes(".jpg"))
  }.flatten
```

### foreach — the processing loop

`foreach(f)` runs `f` over **every** frame, each as an owned `Image` that is **closed for you** when
`f` returns. This is the loop to reach for. It stops at end-of-stream — a file's last frame, a
camera's disconnection, which OpenCV cannot tell apart — so a bounded `attemptsPerFrame` rides out
dropped frames without turning a dead camera into an endless loop.

The `Image` is a caller-safe copy, so the full [`Image`](/image-api) surface applies: detect on it,
annotate it, write it out. Here is the detect → annotate → record shape, each frame written to a
[`Recorder`](#recording):

```scala mdoc:compile-only
import scalacv.*
import scalacv.vision.*
import org.opencv.objdetect.FaceDetectorYN

val detector: FaceDetectorYN = ??? // from FaceDetect.create(model, size); see /object-detection

Camera.usingFile("clip.mp4") { cam =>
  Recorder.using("faces.avi", cam.size, cam.fps, Codec.Mjpg) { rec =>
    cam.foreach() { frame =>
      val found = frame.faces(detector)         // query: borrows the frame
      val marked = frame.markFaces(found)        // transform: consumes it, returns a new Image
      rec.write(marked)                          // write borrows
      marked.close()
    }
  }
}
```

:::warning[Ownership inside the loop]
The `Image` `foreach` hands you is closed **when your function returns** — do not stash it in a
field or a collection to use later (that is a use-after-free; see [Mat lifecycle](/mat-lifecycle)).
And remember [`Image`](/image-api)'s move semantics: `frame.markFaces(...)` *consumes* `frame` and
returns a **new** `Image`. That new image is yours — `write` only borrows it, so you `.close()` it
yourself. If you need to keep frames beyond the loop, use [`take`](#take--taking--collect-a-batch-of-frames).
:::

`foreach` takes `attemptsPerFrame` as a leading parameter list; the default of `3` suits a camera.
For a file, where the first failed read is genuinely end-of-file, `cam.foreach(1)(...)` is exact.

A pure side-effect pass — counting frames, or gathering a statistic — needs no writer at all:

```scala mdoc:compile-only
import scalacv.*
import java.util.concurrent.atomic.AtomicLong

Camera.usingFile("clip.mp4") { cam =>
  val frames = AtomicLong(0)
  cam.foreach(1) { _ => frames.incrementAndGet() }
  frames.get
}
```

### take / taking — collect a batch of frames

`take(n)` returns the next `n` frames as owned `Image`s — **each is yours to close**. Frames beyond
the end of the stream are simply absent, so the result may be shorter than `n`. The type cannot warn
you that the elements are live resources, so prefer `taking(n) { … }`, which hands you the batch and
closes every frame when the block returns — on success, failure, and exception:

```scala mdoc:compile-only
import scalacv.*

Camera.usingFile("clip.mp4") { cam =>
  // Scoped: the frames are released for you when the block ends.
  cam.taking(5) { frames =>
    frames.foreach(img => println(img.width))
  }

  // Unscoped: only when you need to hold the frames past a scope — then you must close them.
  val firstFive: Seq[Image] = cam.take(5)
  try firstFive.foreach(img => println(img.width))
  finally firstFive.foreach(_.close())
}
```

`taking` is the natural fit when you need several frames *at once* — to compare them, composite them,
or pick the sharpest. Because the frames are owned copies, holding all five in memory is fine (unlike
[`Video.frames`](#the-borrowing-contract), where retaining is the cardinal sin):

```scala mdoc:compile-only
import scalacv.*

val sizes: Either[CvError, Seq[(Int, Int)]] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.taking(3) { frames =>
      frames.map(img => (img.width, img.height))
    }
  }
```

### info, size, fps

`info` reports what the backend *claims* about the source — width, height, fps, frame count,
backend name — and every field is **advisory**. A live camera commonly reports `frameCount == 0` and
an `fps` of `0` until it warms up. `size` and `fps` are shortcuts onto it. Use them to size a
recorder or show progress, never as a loop bound:

```scala mdoc:compile-only
import scalacv.*

Camera.usingFile("clip.mp4") { cam =>
  val meta: CaptureInfo = cam.info
  (meta.size, cam.fps, meta.backendName)
}
```

`CaptureInfo` carries exactly these fields, and none of them is a promise:

| Field | Type | What it means | When it lies |
|---|---|---|---|
| `width` / `height` | `Int` | reported frame size (`size` bundles them) | a camera may report 0 before warm-up |
| `fps` | `Double` | reported frame rate | `0` for a camera that has not delivered a frame |
| `frameCount` | `Long` | reported total frames | `0`/`-1` for a live source; off by a frame or two for some containers |
| `backendName` | `String` | which videoio backend opened it | — |

:::danger[Never loop on `frameCount`]
`for (i <- 0 until cam.info.frameCount.toInt)` is a bug: a live camera reports `0` (you process
nothing) and some containers over- or under-report by a frame (you read past the end, or stop
short). **The only frame count that is true is the one the frame loop actually delivers.** Let
`foreach` / `frames` stop at end-of-stream for you.
:::

### capture — the escape hatch

`capture` borrows the raw `org.opencv.videoio.VideoCapture` — the low level under `Camera`, for
`Video.frames`, seeking with `CAP_PROP_POS_FRAMES`, or any `org.opencv.videoio.*` call. It stays
owned by the `Camera`; do not release it yourself.

```scala mdoc:compile-only
import scalacv.*

Camera.usingFile("clip.mp4") { cam =>
  Video.frames(cam.capture) { frames => // zero-copy loop over the same capture
    frames.map(_.mat.cvtColor(ColorConversion.BgrToGray).use(_.findContours().size)).sum
  }
}
```

This is the bridge between the two levels: hold the `Camera` for its owned-`Image` convenience, then
step down to `Video.frames` on its `capture` for a hot inner loop that must not copy. Seeking works
the same way — set `CAP_PROP_POS_FRAMES` on the borrowed capture before you read:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.Videoio

Camera.usingFile("clip.mp4") { cam =>
  cam.capture.set(Videoio.CAP_PROP_POS_FRAMES, 100.0)  // jump to frame 100 (file sources only)
  cam.snapshot()
}
```

## Recording

### recordTo — read, transform, write, in one line

`recordTo(path)(transform)` reads every frame, applies `transform`, and writes the results to a
video, returning the number of frames written. The recorder is **sized from the first transformed
frame** — not from the source's advertised `CAP_PROP_FRAME_WIDTH`/`HEIGHT`, which a camera commonly
answers as `0`×`0`. So a resizing `transform` is fine, as long as it resizes *every* frame the same
way: the first frame fixes the geometry, and a later frame of a different size is reported as a
`Left`, not thrown.

Reading an `.mp4` is fine; the container restriction is the *writer's*. The default codec is MJPG,
which opens only inside an `.avi` — see [Codecs and portability](#codecs-and-portability).

```scala mdoc:compile-only
import scalacv.*

val written: Either[CvError, Long] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.recordTo("edges.avi")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
  }.flatten
```

The `transform` is any `Image => Image`. It must return a **3-channel, 8-bit** frame, because the
recorder is opened `color = true` — that is why the Canny example ends
`.convert(ColorConversion.GrayToBgr)`: `canny` produces a single-channel edge map, and it has to
become BGR again before it can be written.

A source that yields no frames at all returns `Right(0)` and creates no file — there was never a
frame to size a writer from, so there is nothing to write.

```scala mdoc:compile-only
import scalacv.*

// Blur every frame, keeping it BGR throughout — no channel round-trip needed.
Camera.usingFile("clip.mp4") { cam =>
  cam.recordTo("blurred.avi", codec = Codec.Mjpg)(_.blur(4))
}
```

`fps` defaults to the source's rate (falling back to 30 for a camera that reports none); `codec`
defaults to `Codec.Mjpg`. A frame that fails to encode, or a recorder that cannot open, is a `Left`.
The parameters:

| Parameter | Default | Notes |
|---|---|---|
| `path` | — | output file; extension must match the codec's container — `.avi` for the default |
| `fps` | `0` | `0` derives from the source, falling back to `30` |
| `codec` | `Codec.Mjpg` | see [Codecs and portability](#codecs-and-portability) |
| `attemptsPerFrame` | `3` | end-of-stream tolerance; `1` for a finite file |

### Recorder

For output that is not a straight source-to-file pass — writing frames you built yourself, a
different size from any source, mixing several inputs — open a `Recorder` directly. It is fixed at
open time to one frame size, fps and codec (defaulting to `Codec.Mjpg`, hence the `.avi` below);
**every frame written must match that size and be 8-bit**, or `write` throws
`IllegalArgumentException`. Like `Camera`, it is caller-owned and `AutoCloseable`, with a scoped
`using` form:

```scala mdoc:compile-only
import scalacv.*

Recorder.using("out.avi", Size(640, 480), fps = 30, codec = Codec.Mjpg) { rec =>
  (0 until 90).foreach { i =>
    val frame = Image.blank(640, 480, if i % 2 == 0 then Scalar.Black else Scalar.White)
    rec.write(frame)   // borrows — the frame is not consumed
    frame.close()
  }
}
```

`write(image)` **borrows** the image (it is not consumed, so close it yourself). There is also a
`write(mat)` overload that borrows a raw `org.opencv.core.Mat` directly — that is what lets the
zero-copy frames from [`Video.frames`](#the-low-level-videoframes) be recorded without the per-frame
clone an `Image` would force, through the frame's liveness-checked `mat` escape hatch:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

// Re-encode a source frame-for-frame at the lowest level: borrowed Mat straight into the recorder.
Video.open("clip.mp4").flatMap { capture =>
  capture.use { c =>
    val meta = Video.info(c)
    Recorder.using("copy.avi", meta.size, meta.fps, Codec.Mjpg) { rec =>
      Video.frames(c) { frames =>
        frames.foreach(frame => rec.write(frame.mat))
      }
    }
  }
}
```

`writer` borrows the raw `VideoWriter` as the escape hatch, and `size` is the fixed frame size.
`Recorder.open` also takes `color = false` for a single-channel (greyscale) output stream.

:::warning[Frame size and depth are fixed, and enforced]
A `Recorder` is opened at one size and never changes it. Writing a frame of any other dimensions
throws `IllegalArgumentException` immediately — this is a programming error (a mismatched pipeline),
not a data-dependent failure, so it throws rather than returning a `Left`. If your transform changes
size, size the recorder to the *output* and resize each frame to match before writing.

The same check covers **depth**: the encoder takes `CV_8U` and nothing else. A float frame — the
output of `sobel`, a distance transform, a disparity map — used to be accepted and encoded as
garbage; it now throws, naming the offending type. Bring it down with
`normalize(0, 255)`, which produces 8-bit by default.
:::

### Codecs and portability

`Codec` names a container/codec as a FOURCC — packed in pure Scala, so it needs no native call and
can be referenced before `OpenCv.load()`. Whether a codec actually *works* depends on what the
platform's videoio build links (FFmpeg, the OS frameworks):

| Codec | `fourcc` | Container | Notes |
|---|---|---|---|
| `Mjpg` | `MJPG` | `.avi` | **The default.** Motion-JPEG — large files, but served by videoio's **built-in** writer, so it needs no FFmpeg, no GStreamer and no system codec |
| `Mp4v` | `mp4v` | `.mp4` | MPEG-4 Part 2 — smaller files, but needs a videoio linked against FFmpeg or a platform MPEG-4 encoder |
| `Avc1` | `avc1` | `.mp4` | H.264, best compression, only if the build ships an H.264 encoder |
| `Xvid` | `XVID` | `.avi` | Xvid MPEG-4 |

The portability point: an unavailable codec is a **`Left`**, not a silent black file. OpenCV reports
it by leaving `isOpened` false, and `Recorder.open` turns that into a
[`CvError`](/error-model) whose message points you at the fallback.

`Mjpg` is the default precisely because it is the one combination that opens on every build. The
`org.bytedeco` `linux-x86_64` and `windows-x86_64` payloads this project builds against ship **no
FFmpeg plugin at all**, so `Mp4v` and `Avc1` do not open there — a zero-config `recordTo` defaulting
to `Mp4v` would have returned a `Left` on the natives the library itself pins. Reach for `Mp4v` when
you know your build has FFmpeg and you want the smaller file.

The container is part of the bargain in both directions: MJPG opens only in an `.avi`, and `Mp4v`
only in an `.mp4`, so the codec and the extension always have to move together.

```scala mdoc:compile-only
import scalacv.*

// Prefer the compact codec where the build supports it, fall back to the always-available default.
// Note the extension moves with the codec — MJPG cannot open inside an `.mp4`.
def openRecorder(base: String, size: Size, fps: Double): Either[CvError, Recorder] =
  Recorder.open(s"$base.mp4", size, fps, Codec.Mp4v)
    .orElse(Recorder.open(s"$base.avi", size, fps, Codec.Mjpg))
```

## Backends and options

Both `Camera.open`/`openFile` and `Video.open` accept a [`CaptureOptions`](#backends-and-options),
which chooses a backend and sets network timeouts. The default — `CaptureOptions.Default`, i.e.
`CAP_ANY` with no timeouts — is right almost always: OpenCV tries its registered backends in
priority order and uses the first that can read the source.

`CaptureBackend` is for the rare case where that choice is wrong:

| Backend | Purpose |
|---|---|
| `Any` | Let OpenCV choose (`CAP_ANY`). The default, and usually correct. |
| `FFmpeg` | Force FFmpeg — files and network streams. |
| `GStreamer` | GStreamer pipelines. |
| `V4L2` | Linux cameras (force a native pixel format). |
| `AVFoundation` | macOS cameras and files. |
| `MediaFoundation` | Windows Media Foundation. |
| `DirectShow` | Windows DirectShow — the older camera stack. |
| `ImageSequence` | Read a numbered image sequence (`frame_%04d.png`) as a video. |
| `BuiltinMjpeg` | OpenCV's own MJPEG reader — always built in, depends on nothing external. |

:::warning[Naming a backend can turn a working open into a failing one]
A backend that is not compiled into the OpenCV build on your classpath cannot open anything, so
forcing it makes `open` fail. The bytedeco 4.13.0 builds do not all carry the same set. This is a
portability lever, not a tuning knob — leave it `Any` unless you have a concrete reason.
:::

```scala mdoc:compile-only
import scalacv.*
import scala.concurrent.duration.*

// Force FFmpeg for a network stream, with a 5-second best-effort timeout.
val opts = CaptureOptions.withTimeout(5.seconds, CaptureBackend.FFmpeg)
val stream: Either[CvError, Camera] = Camera.openFile("rtsp://camera.local/stream", opts)
```

### Timeouts are best-effort

`CaptureOptions.withTimeout` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC` /
`CAP_PROP_READ_TIMEOUT_MSEC`. They matter only for network sources, where a hang is the real failure
mode, and they are **advisory**: FFmpeg and GStreamer honour them, while V4L2, AVFoundation and the
built-in MJPEG reader ignore them outright, and the API does not report which you got. They are off
by default because they can only be set at open time and some backends reject them (a local `.avi`
opened with the parameters attached reports `isOpened == false`), so `Video` retries without them
rather than turning a supported source into a failure. Set them for RTSP/HTTP; leave them alone for
local files.

### Camera warm-up

A webcam is not ready the instant `open` returns. Auto-exposure, auto-white-balance and auto-gain
are closed loops running on the device, and they need a handful of real frames to converge — which
is why `open`-then-`snapshot` so often produces a black or badly-under-exposed image *and reports it
as a success*. There is no property to poll for "converged", so the only fix is to pull some frames
and throw them away.

`warmupFrames` is how many to discard, and it defaults to `None`, meaning "let the source decide":
**a camera index discards 5, a file or URL discards 0**. That split is the point — a file has no
exposure loop, its first frame is exactly as correct as its hundredth, and discarding frames there
would silently skip real content. Set it explicitly to override either default; `Some(0)` turns
warm-up off on a camera, and a larger number helps a device that is slow to settle.

```scala mdoc:compile-only
import scalacv.*

// A camera in a dim room may need longer than the default five frames to expose correctly.
val patient = CaptureOptions(warmupFrames = Some(20))
val cam: Either[CvError, Camera] = Camera.open(0, patient)
```

The full `CaptureOptions` shape:

| Field | Default | Meaning |
|---|---|---|
| `backend` | `CaptureBackend.Any` | which videoio backend to ask for |
| `openTimeout` | `None` | best-effort cap on how long opening may block |
| `readTimeout` | `None` | best-effort cap on how long one frame read may block |
| `warmupFrames` | `None` | frames to grab and discard at open; `None` means 5 for a camera, 0 for a file |

## The ownership split

`Camera` copies every frame so the `Image` it gives you is safe to keep, transform and pass around —
correctness first. `Video.frames`, below, hands you one reused buffer and never copies — speed
first, at the cost of a [borrowing contract](#the-borrowing-contract) you must respect. The rule of
thumb: **stay on `Camera` until a profiler shows the per-frame copy is your bottleneck**, then drop
to `Video.frames` on the borrowed [`capture`](#capture--the-escape-hatch). A 1080p BGR frame is
~6 MB; whether copying it per frame matters depends entirely on your frame rate and what else the
loop does. See [Performance](/performance) for how to measure it before you decide.

Holding a `Camera` open across calls, released by hand rather than through `using`:

```scala mdoc:compile-only
import scalacv.*

Camera.openFile("clip.mp4").foreach { cam =>
  try cam.snapshot().flatMap(_.write("shot.png"))
  finally cam.close() // idempotent
}
```

## The low level: Video.frames

`Video` is the zero-copy floor. `Video.open` returns an owned capture and `Video.frames` walks it,
handing you **exactly one** reused `Mat` that every frame decodes into, in place. Everything
`Camera` does, it does on top of this.

### Opening a source

`Video.open` takes a device index or a source string and returns
`Either[CvError, Managed[VideoCapture]]` — not a bare capture. It checks `isOpened` before handing
the capture back, so a `Right` can actually deliver frames; you never get a silently empty stream
that looks like a zero-frame video.

```scala mdoc:compile-only
import scalacv.*
import scala.concurrent.duration.*
import org.opencv.videoio.VideoCapture

val fromFile: Either[CvError, Managed[VideoCapture]]    = Video.open("clip.mp4")
val fromCamera: Either[CvError, Managed[VideoCapture]]  = Video.open(0)
val fromNetwork: Either[CvError, Managed[VideoCapture]] =
  Video.open("rtsp://camera.local/stream", CaptureOptions.withTimeout(5.seconds))
```

`Video.info(capture)` reports what the backend *claims* — every field advisory, exactly as
[`Camera.info`](#info-size-fps) above:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val meta = Video.info(c)
    (meta.fps, meta.size, meta.backendName)
  }
}
```

### Walking the frames

`Video.frames` runs your function over an `Iterator[BorrowedMat]`, scoped to the call: the iterator
is created when the block begins and retired when it returns, and it owns **exactly one** `Mat` that
every frame decodes into, in place.

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

val totalContours: Either[CvError, Int] =
  Video.open("clip.mp4").map { capture =>
    capture.use { c =>
      Video.frames(c) { frames =>
        frames.map(_.mat.cvtColor(ColorConversion.BgrToGray).use(_.findContours().size)).sum
      }
    }
  }
```

A `BorrowedMat` answers the frame's shape itself (`rows`, `cols`, `channels`, `empty()`, `get`) and
hands out the raw `Mat` through its liveness-checked `mat` escape hatch (checked at extraction only;
never retain or release the raw handle), so the whole
[Ops](/image-api) surface applies: `frame.mat.cvtColor(...)`, `frame.mat.canny(...)`,
`frame.mat.resize(...)`. Each of those **allocates its own destination** and hands you an owned
`Managed[Mat]` — it never aliases the frame buffer — so running them inside the loop is correct and
leak-free.

### The borrowing contract, enforced {#the-borrowing-contract}

This is the one place in scalacv where the `Mat` you are handed is **not yours**. A `BorrowedMat`
is valid from the `next()` that produced it until the iterator advances or the `frames` block
returns — and that validity is *enforced*, the same way `Managed` enforces release-once: touch a
spent frame and it throws `IllegalStateException` on the Scala side, before anything crosses JNI.
Reading a frame the stream has moved past is not a wrong answer; it is freed native memory, and the
exception is what stands between you and a SIGSEGV.

The check is not a concurrent lifetime lock. Use one consumer, and never overlap view access with
an iterator advance or source close on another thread/fiber. Clone while the source is paused before
handing pixels to parallel work; a raw `frame.mat` reference has no checks after extraction.

So you still **reduce each frame to something owned inside the loop** — a count, a scalar, encoded
bytes, an owned `Managed[Mat]` from an `Ops` op. Writing each frame out as you go is fine, because
the work happens before the next pull:

```scala mdoc:compile-only
import scalacv.*
import java.nio.file.{Files, Path}
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    Video.frames(c) { frames =>
      frames.zipWithIndex.foreach { case (frame, i) =>
        frame.mat.cvtColor(ColorConversion.BgrToGray).use(Images.encode(_, ".png")).foreach { png =>
          Files.write(Path.of(s"frame-$i.png"), png)
        }
      }
    }
  }
}
```

What you must **not** do is retain the frame — stash it in a collection, or use any iterator
combinator that buffers. `toList`, `toVector`, `sliding` and `buffered` all compile, and what they
collect is N *spent* views: the first access to any of them throws. (To keep a frame, take an owned
copy — `frame.clone()` — or use [`Video.framesCopied`](#keeping-a-frame).)

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

// WRONG: N spent views that throw on first access — not N frames.
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    Video.frames(c)(_.toList)
  }
}
```

Here is the borrowing contract as a table — what a borrowed frame supports, and what the liveness
guard turns into an `IllegalStateException`:

| Operation | Safe? | Why |
|---|---|---|
| Read pixels, query (`empty`, `size`, `findContours`) | ✅ | consumed before the next pull |
| `frame.mat.cvtColor(...)` / `canny` / `resize` (any `Ops` op) | ✅ | allocates its own owned output |
| Write the frame out (`rec.write(frame.mat)`, encode to bytes) | ✅ | work happens before the next pull |
| `it.toList` / `toVector` / `sliding` / `buffered` | ❌ | N spent views; first access throws |
| Stash the frame in a `var` / field / collection | ❌ | spent after the next pull / block exit; access throws |
| `frame.clone()` and keep the clone | ✅ | but that is exactly what [`framesCopied`](#keeping-a-frame) does for you |

**Why not just make it a `LazyList`?** Because memoisation and per-frame release cannot both be
correct at once. A `LazyList` (or `Stream`, or any retaining combinator) keeps every cell it has
evaluated so a second traversal is cheap — which means every frame it ever produced stays reachable.
Either nothing is released, and you leak native memory without bound (a 1080p BGR frame is ~6 MB, so
a minute at 30 fps is over 10 GB), or frames are freed as consumed and the list becomes a field of
dangling handles that the next traversal hands back as empty Mats. There is no version of that API
that is both lazy-memoised and release-per-frame. The single-`Mat` iterator is what makes the memory
footprint one frame, whatever the length of the video. See [Mat lifecycle](/mat-lifecycle) for the
ownership model this is the exception to.

`capture` itself is only borrowed by `frames` — not released, not rewound — so calling `frames`
again resumes where the last traversal stopped, which is what makes `_.take(10)` behave as it reads.
That resume behaviour composes: two `frames` calls read consecutive spans of the same stream.

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val firstTen  = Video.frames(c)(_.take(10).size)   // frames 0..9
    val nextTen   = Video.frames(c)(_.take(10).size)   // frames 10..19 — resumes, does not rewind
    (firstTen, nextTen)
  }
}
```

### Keeping a frame

When you genuinely need frames that outlive the loop, `Video.framesCopied` clones each one into a
caller-owned `Managed[Mat]` with its own pixel buffer — the same copy `Camera` makes for you under
every frame. The clone happens as you pull, so frames you never reach are never copied — and
everything you *do* pull is yours to release:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val firstThree: Vector[Managed[Mat]] = Video.framesCopied(c)(_.take(3).toVector)
    try firstThree.foreach(m => process(m.get))
    finally firstThree.foreach(_.release())
  }
}

def process(frame: Mat): Unit = ()
```

The cost is one allocation and one full-frame copy per frame, which is why it is not the default —
and why `Camera`, which always makes it, is a copy dearer than `Video.frames`. In fact
[`Camera`](#camera--frames-as-owned-images) is `framesCopied` with the copy wrapped as an owned
`Image` and the lifetime handled — so if you find yourself reaching for `framesCopied`, ask whether
`Camera` already does what you want.

### End of stream, or a broken one

`VideoCapture.read` has no timeout overload and blocks in native code, and OpenCV reports the end of
a video through the *same* exception it uses for a broken stream. So `frames` turns exception mode
**off** for the duration of the loop: a `read` returning `false` cleanly ends the stream, while a
genuine decode failure still surfaces as [`CvError.NativeCall`](/error-model). With exception mode
on, "the video ended" and "the camera was unplugged" are indistinguishable, and the loop would have
to treat every real failure as a normal end.

For a **file**, the first failed read *is* end-of-file, so the default `attemptsPerFrame = 1` is
right. For a **camera**, a single dropped frame is not the end of the stream — but a dropped
connection reads exactly the same way, and the two are indistinguishable from the JVM.
`attemptsPerFrame` is a small bound (2–5) that rides out a transient drop without turning a dead
camera into a spinning, hung thread:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c, attemptsPerFrame = 3) { frames =>
      frames.foreach(frame => analyse(frame.mat))
    }
  }
}

def analyse(frame: org.opencv.core.Mat): Unit = ()
```

It is a bound, not retry-forever: because `read` blocks with no timeout of its own, an unbounded
loop against a dead source would hang. The `CAP_PROP_*_TIMEOUT_MSEC` options above are the only lever
on that blocking, and only for the backends that honour them.

| Source | Recommended `attemptsPerFrame` | Reasoning |
|---|---|---|
| File | `1` | the first failed read *is* end-of-file |
| Local camera | `3` (default) | rides out an occasional dropped frame |
| Flaky network stream | `2`–`5` | tolerates jitter without spinning forever on a dead link |

### Releasing the capture

`VideoCapture` is one of exactly **three** `org.opencv.*` types with a real public `release()` — the
others are `Mat` and `VideoWriter` (the one under [`Recorder`](#recorder)). Everything else scalacv
wraps (every detector, every classifier) exposes only a private `delete(long)` and must be freed
through the [handle bridge](/mat-lifecycle); a capture does not need it. `Video.open` still wraps it
in a `Managed` so release is once-only and use-after-free throws on the Scala side rather than
crashing from native code.

Prefer `.use`, which releases on every exit path:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map(_.use(c => Video.frames(c)(_.size)))
```

If you must hold the capture open across calls, release it yourself in a `finally`:

```scala mdoc:compile-only
import scalacv.*
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").foreach { capture =>
  try Video.frames(capture.get)(_.size)
  finally capture.release()
}
```

## An effectful version

The [`scalacv-zio`](/zio) module expresses the same capture and frame loop as a ZIO `Scope` and a
`ZStream`. `frameStream` emits liveness-checked `BorrowedMat` views over one reused buffer;
reduce each view before the next pull. `framesCopied` emits caller-owned clones: filtering, dropping
or abandoned queues do not release them. Prefer the current `processFrames` API for sequential
effectful processing with bracketed acquisition and cleanup, then apply combinators to its reduced
results. See the ZIO guide for synchronous `use` versus effect-aware `useZIO` and handoff limits.

If you are consuming frames from several threads, or want to see how the borrowing contract interacts
with parallelism, [Concurrency](/concurrency) covers the rules — the short version is that one
capture is single-threaded, so parallelise the *work* on owned copies, not the read.

## Displaying frames

Displaying frames on screen (OpenCV's `imshow`) is out of scope for the headless core — it needs a
GUI toolkit that resolves per host, which core deliberately does not depend on. The `examples-gui`
module carries a JavaFX webcam demo (`scalacv.CamFaceDetect`) that pulls frames, draws detections,
and paints them into a window; it is never built in CI and never published. Run it with
`./mill examples-gui.runMain scalacv.CamFaceDetect`.

For a headless equivalent — turning a frame into something you can look at without a window — encode
it to bytes and hand those to a notebook or a web response; [Notebooks](/notebooks) shows the
almond/Jupyter path.

## Next

- [Image API](/image-api) — the owned `Image` every `Camera` frame is, and its move semantics.
- [Mat lifecycle](/mat-lifecycle) — the ownership model that `Video.frames` is the one exception to.
- [Performance](/performance) — how to measure whether the per-frame copy is really your bottleneck
  before dropping from `Camera` to `Video.frames`.
