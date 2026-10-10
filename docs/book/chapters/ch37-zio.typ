#import "../lib/book.typ": *

#chapter("ZIO Integration", subtitle: [Native lifetimes under a runtime that can cancel a fiber between two frames.])

A `try`/`finally` is a promise about one thread's control flow, and it keeps that promise perfectly
as long as the thread is the only thing deciding what runs next. Chapter 5 built the whole lifetime
model on it: `Managed.use` acquires, runs, and releases in a single block; `Managed.scope` registers
each object as it is created so a throw part-way through unwinds everything already acquired;
`Image.reading` closes the image on success, on failure, and on exception. Nothing there can leak,
because nothing there can be stopped from outside.

An effect runtime can stop you from outside. A ZIO fiber is interruptible at every suspension point,
and a `for` comprehension has one between every step. Acquire a `CascadeClassifier` in one step, use
it three steps later, release it in the fourth, and you have written a window: an interrupt delivered
in the middle skips the fourth step entirely, and the `finally` you were relying on never runs
because the block it belongs to never runs. For an ordinary JVM object that is a leak nobody
notices. Here it is a parsed cascade sitting in native memory behind a Java object that holds
nothing but a pointer, and the collector weighs the pointer. That is the arithmetic Chapter 1 opened
with: 2000 unreleased 1000×1000 Mats reach 5 865 MB of RSS against 144 MB when released, because a
multi-megabyte native buffer looks like about 40 bytes of Java header to the collector, and no
amount of heap pressure will make it care.

The pleasant surprise is that ZIO already has the right abstraction, under a different name. ZIO's
`Scope` is `Managed.scope`: a region that owns a set of resources and releases them in reverse
acquisition order when it closes. `ZIO.acquireRelease` is `Managed.use` with the acquisition made
uninterruptible and the finalizer guaranteed to run on every exit path an effect has --- success,
typed failure, defect, and interruption. Two libraries arrived at the same shape from opposite
directions, one because native memory demands it and one because cancellation demands it, and the
`scalacv-zio` module is nine definitions of glue that let you use the second to satisfy the first.

Nothing in this module changes the memory model. It changes who drives it. A `Mat` acquired through
a ZIO scope is freed exactly once, by the scope, through the same `Releasable` instance the
synchronous API uses. Using it after the scope closes is the same use-after-release error Chapter 5
catalogued, and it is caught in the same two ways: reached through a `Managed` or an `Image`, it is
an `IllegalStateException` raised on the Scala side before anything crosses JNI; reached as a raw
`Mat` you handed straight to `acquireRelease`, there is no wrapper to guard it and you get an
emptied Mat whose `dataAddr()` reads `0`.

#sect("The module")

`scalacv-zio` is published as its own artifact. Its Mill module lists `moduleDeps = Seq(core)` and
two library dependencies, `dev.zio::zio:2.1.26` and `dev.zio::zio-streams:2.1.26` --- nothing from
`scalacv-vision`, nothing from `scalacv-graphs`. Add it beside the natives you already picked in
Chapter 2:

#example("The dependency. `zio` and `zio-streams` come in transitively; the natives are still yours to choose.")[
```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",
  mvn"com.worxbend::scalacv-zio:0.4.1",
  // The detector examples later in this chapter need Cascades, which lives here.
  mvn"com.worxbend::scalacv-vision:0.4.1",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```
]

Everything the module provides is a top-level definition in the package `scalacv.zio`, so
`import scalacv.zio.*` brings all of it into scope. The module's own sources import ZIO as
`` `_root_.zio.*` `` because the package it lives in is itself called `zio`; your code does not have
to, unless you are writing inside a package of your own with the same collision.

#figure-table("Everything in `scalacv.zio`. Nine definitions, and that is the whole module.")[
#tbl(
  columns: (1.35fr, 1.5fr, 2fr),
  [Definition], [Type], [What it does],
  [`loadNatives`], [`Task[Unit]`], [`OpenCv.load()` on the blocking pool. Idempotent.],
  [`acquireRelease(make)`], [`ZIO[Scope, Throwable, A]`], [Ties any `Releasable` object to the scope.],
  [`mat.scoped`], [`ZIO[Scope, Throwable, Mat]`], [Extension: adopts an existing `Mat` into the scope.],
  [`fromCv(result)`], [`IO[CvError, A]`], [Lifts an `Either[CvError, A]` into the typed error channel.],
  [`readImage(path, flags)`], [`IO[CvError, Image]`], [Decodes an image; caller-owned.],
  [`imageScoped(path, flags)`], [`ZIO[Scope, CvError, Image]`], [Decodes and closes with the scope.],
  [`captureScoped(source, options)`], [`ZIO[Scope, CvError, VideoCapture]`], [Opens a source, releases with the scope.],
  [`frameStream(capture)`], [`ZStream[Any, Throwable, BorrowedMat]`], [Frames as one *borrowed*, reused buffer.],
  [`framesCopied(capture)`], [`ZStream[Any, Throwable, Managed[Mat]]`], [Frames as *owned* clones.],
)
]

That is a deliberately small surface, and the last section of this chapter is about lifting the rest
of the library yourself rather than waiting for a wrapper that is not coming. Two defaults are worth
memorising now: `readImage` and `imageScoped` take `flags: ImreadFlags = ImreadFlags.Color`, and
`captureScoped` takes `options: CaptureOptions = CaptureOptions.Default`.

`loadNatives` is `ZIO.attemptBlocking(OpenCv.load())` and nothing else. The blocking pool matters on
the first call, which extracts about 196 MB of natives into `~/.javacpp` and `dlopen`s them; every
call after that is a no-op, so requiring it at the top of each entry point costs nothing. Put it
first in every program in this chapter --- every other definition here touches native code.

#sect("Scope, which is `Managed.scope` with a cancellation story")

`acquireRelease` takes anything with a `Releasable` instance --- by name, so the construction happens
on acquisition and not at the call site --- and returns it in the `Scope` environment:

```scala
def acquireRelease[A](make: => A)(using r: Releasable[A]): ZIO[Scope, Throwable, A]
```

The release path is not a special ZIO-only route. It calls `r.release(a)`, so a `Mat` goes through
`release()` and one of the other 185 native types goes through the `delete(long)` bridge with its
finalizer disarmed first, exactly as Chapter 5 described. Acquisition runs on the blocking pool,
because constructing a native object frequently means opening a model file.

#example("Two objects, one scope, released in reverse order when the scope closes.")[
```scala
import _root_.zio.*
import org.opencv.core.{CvType, Mat}
import scalacv.*
import scalacv.zio.*

val program: ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _     <- loadNatives
      frame <- acquireRelease(Mat(1080, 1920, CvType.CV_8UC3))
      work  <- ZIO.attemptBlocking(Mat(1080, 1920, CvType.CV_8UC1))
      grey  <- work.scoped
    yield frame.rows + grey.rows
  }
```
]

`work.scoped` is the extension for the case where something else already allocated the `Mat` and you
want the scope to own it from here on --- a mid-level `Ops` call that handed back a raw `Mat`, or a
clone you made deliberately. It is `acquireRelease` with the construction already done, which means
the object exists for a moment before the scope has it; keep that moment to a single `flatMap`.

#memory[
  The scope frees these objects the instant it closes, and `ZIO.scoped` closes when its body's effect
  completes --- not when the value you yielded is finished with. Yielding a `Mat`, an `Image`, or
  anything aliasing their native buffers out of `ZIO.scoped` is a use-after-release with a delay
  fuse. Reduce to owned data *inside* the scope --- a number, an `Array[Byte]` from `Images.encode`,
  a fresh `Image` that owns its own Mat --- and yield that. An escaped `Image` at least fails loudly:
  it reaches its Mat through a `Managed`, whose spent-handle guard throws an `IllegalStateException`
  at the first `get` rather than letting a SIGSEGV out, and `-Dscalacv.trackOwnership=true` attaches
  the consuming site as the cause. An escaped raw `Mat` has no wrapper and no guard: it comes back
  empty, and reads off it are garbage or a crash. Both are better caught in a test than in
  production.
]

The interruption guarantee is the reason to do any of this. Write the same program with a
`try`/`finally` spanning two steps of a comprehension and it is correct until the day something
calls `.timeout`, `.race`, or `.interrupt` on the fiber running it --- at which point the release
step is skipped in silence, the process RSS starts climbing, and the heap graph in your dashboard
shows nothing at all.

#sect("The error channel: `CvError` typed, programmer errors as defects")

Chapter 6 drew a line through the library's failure modes, and the ZIO module keeps that line where
it is instead of redrawing it. Data-dependent failure --- a file that is not there, bytes that do not
decode, a model that will not load --- is an `Either[CvError, A]`. A precondition violation is an
`IllegalArgumentException`, because it is a bug in your code and pattern-matching on it would be
pretending otherwise.

`fromCv` is the bridge for the first kind, and it is deliberately nothing but `ZIO.fromEither`:

```scala
def fromCv[A](result: => Either[CvError, A]): IO[CvError, A]
```

The point is the type. Here is the version people write first, and it throws away the distinction
the core spent its whole design budget establishing:

#example("Wrong. The `CvError` is intact but the type says `Throwable`, so nothing downstream can match on it without a cast.")[
```scala
val img: Task[Image] =
  ZIO.attempt(Image.read("photo.jpg").fold(throw _, identity))
```
]

#example("Right. The failure keeps its type, and the effect declares that it can only fail this way.")[
```scala
val img: IO[CvError, Image] = fromCv(Image.read("photo.jpg"))
```
]

`readImage` is that composition with the blocking pool attached: `ZIO.blocking(fromCv(Image.read(path,
flags)))`, so a decode that hits a slow disk cannot occupy a compute thread while it waits. Its
result is caller-owned --- you close it, or you use `imageScoped` and let a scope do it.

#figure-table("Where each kind of failure lands, and why that is the right place for it.")[
#tbl(
  columns: (1.5fr, 1.1fr, 2fr),
  [Failure], [ZIO channel], [Reasoning],
  [`CvError.DecodeFailed`, `LoadFailed`, `EncodeFailed`], [typed error], [Data-dependent and expected; you have a recovery.],
  [`CvError.NativeCall`], [typed error], [OpenCV threw where `Cv.attempt` was watching.],
  [`IllegalArgumentException` from a `require`], [defect], [A programmer error. Recovering from it hides the bug.],
  [`IllegalStateException` from a spent `Managed`], [defect], [Use-after-release. There is no correct recovery.],
  [A `CvError` thrown past an `Either`], [defect, via `attempt`], [Still a `RuntimeException`; catchable if you must.],
)
]

Defects are the correct destination for the middle two rows, and it is worth being explicit about
why. `.catchAll` will not see them --- that is the whole point. A fiber that hands a negative
`minNeighbors` to a detector, or uses an `Image` a transform already consumed, has a bug that a
retry cannot fix; ZIO's defect channel takes it to the fiber's supervisor and your logs rather than
into a recovery branch that pretends it was a transient failure. `CvError` itself extends
`RuntimeException`, so when a `CvError` does escape as a defect --- from a code path that throws
rather than returning --- `.catchAllDefect` can still match it by type. You should rarely want to.

#sect("Frames as a stream, with Chapter 19's borrowing contract intact")

`frameStream` hands you frames as a `ZStream[Any, Throwable, BorrowedMat]`, and it inherits the contract of
the synchronous `Video.frames` rather than ZStream's usual value semantics. Each emitted `BorrowedMat` is a liveness-checked view over one
buffer decoded into in place, and it is valid only until the next pull or stream exit. That is not an oversight; it
is the property that lets the stream stay flat in memory across an arbitrarily long video, and the
module's test suite asserts it directly: streaming eight frames yields eight elements whose
`dataAddr()` values collapse to a single distinct address.

The consequence is that every combinator which *retains* elements is wrong here, and the spent views throw on access.

#figure-table("Combinators that mislead on `frameStream`. Map to an owned value first, then combine.")[
#tbl(
  columns: (1fr, 2.4fr),
  [Combinator], [What you actually get],
  [`runCollect`], [N spent views; accessing their pixels throws.],
  [`broadcast`], [Fan-out consumers racing on a single buffer.],
  [`buffer`, `bufferSliding`], [A queue of spent views.],
  [`zipWithNext`], [The previous view is spent by the next pull.],
)
]

#example("Wrong. Collecting borrowed views succeeds, but their pixels are no longer accessible.")[
```scala
ZIO.scoped {
  for
    _   <- loadNatives
    cap <- captureScoped("clip.mp4")
    all <- frameStream(cap).runCollect        // Chunk[BorrowedMat]; views are spent at stream exit
  yield all.size
}
```
]

#example("Right. The reduction to an owned value happens inside the stream, so what leaves it is data.")[
```scala
ZIO.scoped {
  for
    _      <- loadNatives
    cap    <- captureScoped("clip.mp4")
    // A Double per frame, not a frame per frame.
    levels <- frameStream(cap).map(f => f.get(0, 0)(0)).runCollect
  yield levels
}
```
]

Note which function opened the capture. `captureScoped` wraps `Video.open`, which checks `isOpened`
and performs the retry that `CaptureOptions` documents, so a missing file or a busy camera arrives as
a typed `CvError` at the point of opening. `acquireRelease(VideoCapture(source))` does not: OpenCV
reports "I could not open that" by leaving `isOpened` false rather than by throwing, so the bare
constructor hands you a live object whose every `read` returns false --- which is indistinguishable
from a video with no frames in it.

#sidebar("Why the open check is an effect and not a `require`")[
  `frameStream` guards against being handed an unopened capture, and it does so by prepending a
  `ZStream.execute` that fails with `CvError.LoadFailed("capture", …)` rather than by writing a
  `require` at the top of its body.

  The difference is where the failure happens. `frameStream` is a value-returning constructor: its
  body runs when the pipeline is *assembled*, which may be on a different fiber, at a different time,
  and outside any error channel at all. A `require` there throws during assembly --- past the
  `ZStream`'s error type, in whatever fiber happened to build the description. As an effect inside
  the stream, the check runs on the first pull, on the fiber that runs the stream, and surfaces as a
  typed failure the consumer can match. The module's spec pins that behaviour down: a capture opened
  on `/does/not/exist.avi` must make the stream *fail*, and the failure must be a
  `CvError.LoadFailed`, not a stream that completes with zero frames.
]

`framesCopied` emits caller-owned clones, not automatically released stream elements. Filtering,
dropping, abandoned queues and interruption before adoption can leak them. For effectful work,
the unreleased `processFrames(capture)(mat => ZIO.attemptBlocking(process(mat)))` API brackets
clone acquisition and the entire callback before emitting reduced results. Keep raw Mats, native
views and lazy work inside that callback, and join child work before it returns. The capture
remains scoped separately. This path is sequential; parallelize reduced data, not owned frames.

#memory[
  `Managed.use` is synchronous. Returning `ZIO.succeed(mat.rows())` from its callback closes the
  Mat before that effect runs. Wrap synchronous processing as `ZIO.attemptBlocking(m.use(process))`;
  use `m.useZIO` for effectful work after adoption, or `useManaged(acquire)(process)` to bracket
  acquisition too. Neither downstream form protects an earlier caller-owned stream handoff.
  Prefer `processFrames`, or reduce a sequential `frameStream` borrow before the next pull.
  Blocking finalizers are offloaded and uninterruptible, but JNI reads still cannot be forcibly
  cancelled by fiber interruption.
]

#sect("Backpressure you can actually have")

Chapter 22 made the case that a camera is not a cooperating producer: there is no protocol by which
your detector tells a CMOS sensor to wait, so of drop, buffer, and slow-the-producer, only the first
two are available against a live source. ZIO gives you both of the available ones as operators,
which is the single strongest practical argument for running your frame pipeline as a `ZStream`.

The ordering rule from the previous section decides the shape. Reduce each frame to an owned value
*first*; only then reach for a buffering operator, because the operator's whole job is to retain
elements and a borrowed `Mat` cannot survive being retained.

#example("Keep the newest, drop the oldest, bound the memory. The `map` must come before the buffer.")[
```scala
import scala.concurrent.duration.*

// summarise: Mat => Detection, a small owned value. record: Detection => Task[Unit].
ZIO.scoped {
  for
    _   <- loadNatives
    // A network source is the case the timeouts exist for, and FFMPEG honours them.
    cap <- captureScoped(
             "rtsp://camera.local/stream",
             CaptureOptions.withTimeout(5.seconds, CaptureBackend.FFmpeg)
           )
    // Each frame becomes a small owned result inside the stream …
    _   <- frameStream(cap)
             .mapZIO(f => ZIO.succeed(summarise(f)))
             // … and only owned data reaches the sliding buffer, which drops the
             // OLDEST pending element to make room for the newest arrival.
             .bufferSliding(1)
             .mapZIO(record)
             .runDrain
  yield ()
}
```
]

`bufferSliding` is the drop-latency policy: a full buffer discards its oldest entry, so a slow
consumer always sees recent work. `bufferDropping` is the opposite policy --- a full buffer discards
the *incoming* element --- and it is the wrong one for frames, because the element it throws away is
the newest one, which is the one you wanted. If you need the two ends on separate fibers, the same
choice appears as `Queue.sliding(capacity)` against `Queue.dropping(capacity)`, with `ZStream.fromQueue`
on the consuming side. `frameStream(cap, attemptsPerFrame = 3)` uses the same bounded read-retry
policy as `Video.frames`; both default to one attempt for file end-of-stream.

#warning[
  Neither buffer bounds how long a *read* can block. `frameStream` wraps its read in
  `attemptBlockingInterrupt`, which delivers a JVM `Thread.interrupt()` --- and a thread parked inside
  OpenCV's native `read` never observes one. Interrupting the stream, or closing the scope around it,
  takes effect only once the in-flight read returns by itself; until then the buffer `Mat`, the
  exception-mode restore, and every enclosing `Scope` stay pending. Running on the blocking pool means
  a wedged source pins a blocking thread instead of a compute one. It does not mean the read can be
  cancelled. Bound it at the source instead. Pass `captureScoped` a second argument built by
  `CaptureOptions.withTimeout`, which sets the open timeout and the per-read timeout to the same
  `FiniteDuration` and takes the backend as its second parameter. Both are best-effort, because only
  a backend that honours `CAP_PROP_READ_TIMEOUT_MSEC` acts on them --- `FFmpeg` and `GStreamer` do;
  `V4L2`, `AVFoundation` and the built-in MJPEG reader ignore it, and a local file needs neither,
  which is why both timeouts are `None` in `CaptureOptions.Default`.
]

#sect("A detector per fiber is not free")

Fibers are cheap enough that people stop counting them, and that habit is expensive here. A
`CascadeClassifier` is not a lightweight value. `Cascades.load` goes through `Cascades.resolve`,
which extracts the XML from the bytedeco classifier jar to disk --- javacpp caches that file, so only
the first call pays the extraction --- and then parses the whole cascade, which every call pays for,
and holds the parsed model in native memory for as long as the classifier lives. Spawning one per
frame, or one per fiber in a pool of a few hundred, multiplies that by a number nobody chose. It is also the class of object with no public `release()` --- Chapter 5's 185 ---
so leaking them is the expensive kind of leak: 4000 unreleased `KalmanFilter`s measured 54 GB against
86 MB released.

Detectors are not thread-safe (Chapter 36), so sharing one across fibers is not an option either.
The shape that works is a pool sized to your parallelism, with each detector scoped:

#example("One detector per pool slot, each freed when the outer scope closes.")[
```scala
import _root_.zio.*
import org.opencv.core.Mat
import org.opencv.objdetect.CascadeClassifier
import scalacv.*        // Cascades and Mat.detect live in scalacv-vision
import scalacv.zio.*

// Cascades.load returns Either[CvError, Managed[CascadeClassifier]], so the Managed
// is what the scope releases — release() on it, not on the raw classifier.
val detectorScoped: ZIO[Scope, CvError, CascadeClassifier] =
  ZIO
    .acquireRelease(ZIO.blocking(fromCv(Cascades.load(CascadeName.FrontalFaceAlt))))(
      m => ZIO.succeed(m.release())
    )
    .map(_.get)

// frames: Seq[Mat] you own — clones, or images you decoded. Not borrowed frames
// from frameStream, which are one buffer and cannot be looked at in parallel.
def pooled(frames: Seq[Mat]): ZIO[Scope, CvError, Unit] =
  for
    pool <- ZPool.make(detectorScoped, 4)
    _    <- ZIO.foreachParDiscard(frames) { frame =>
              ZIO.scoped(pool.get.map(detector => frame.detect(detector, minNeighbors = 5)))
            }
  yield ()
```
]

The pool's finalizers run when the scope that built it closes, which is the same guarantee every
other resource here gets. Size it to the number of frames you can actually process in parallel ---
which Chapter 35 argues is usually smaller than your core count, because OpenCV is already using
threads underneath you.

#sect("A complete application")

Everything above, as one program: open a source, clone each borrowed frame into a scoped owner,
run a detector, annotate the owned copy, and write it. Recording an unmodified borrowed frame can
use `rec.write(frame.mat)` without copying; this example copies because it mutates the pixels.

#example("Open, detect, annotate, record. Every native object belongs to a scope.")[
```scala
import _root_.zio.*
import org.opencv.core.Mat
import org.opencv.objdetect.CascadeClassifier
import scalacv.*
import scalacv.zio.*

object AnnotatedRecording extends ZIOAppDefault:

  // The detector from the previous listing, scoped: acquired on the blocking pool because
  // it extracts and parses an XML, released through the Managed that Cascades.load returns.
  private val detectorScoped: ZIO[Scope, CvError, CascadeClassifier] =
    ZIO
      .acquireRelease(ZIO.blocking(fromCv(Cascades.load(CascadeName.FrontalFaceAlt))))(
        m => ZIO.succeed(m.release())
      )
      .map(_.get)

  private def recorderScoped(path: String, info: CaptureInfo): ZIO[Scope, CvError, Recorder] =
    val fps = if info.fps > 0 then info.fps else 30.0
    ZIO.acquireRelease(ZIO.blocking(fromCv(Recorder.open(path, info.size, fps))))(
      r => ZIO.succeed(r.close())
    )

  private def annotate(frame: Mat, detector: CascadeClassifier): Unit =
    frame
      .detect(detector, minNeighbors = 5, minSize = Some(Size(60, 60)))
      .foreach(box => frame.drawRect(box, Scalar.Green, Thickness.Stroke(2)))

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for
        _        <- loadNatives
        cap      <- captureScoped("clip.mp4")
        info     <- ZIO.succeed(Video.info(cap))
        detector <- detectorScoped
        rec      <- recorderScoped("annotated.avi", info)
        written  <- frameStream(cap)
                      .mapZIO { frame =>
                        // Own the mutable copy and release it before the next pull.
                        ZIO.attemptBlocking {
                          Managed.use(frame.clone()) { owned =>
                            annotate(owned, detector)
                            rec.write(owned)
                          }
                        }.flatMap(result => fromCv(result))
                      }
                      .runCount
        _        <- Console.printLine(s"wrote $written frames")
      yield ()
    }
```
]

Trace the exits. A missing `clip.mp4` fails at `captureScoped` with a typed `CvError.LoadFailed`,
and nothing after it runs. An unavailable codec fails at `recorderScoped` the same way, after the
capture is open --- and the capture is released on the way out, because the scope holds it. A
`Ctrl-C` during recording interrupts the fiber: the recorder is finalised (so the AVI's index is
written and the file plays), the detector goes through the `delete(long)` bridge, the capture is
released, and the stream's own decode buffer is freed --- in reverse order, without a single
`finally` in the program.

#warning[
  `Video.info` reads `CAP_PROP_*` properties, and every one of them is advisory. A camera that has
  not yet delivered a frame commonly reports `0×0`, which would trip `Recorder.open`'s positive-size
  precondition and throw an `IllegalArgumentException` --- a defect, not a typed failure. Against a
  live camera, size the recorder from the first decoded frame instead, which is what
  `Camera.recordTo` does for you.
]

#sect("Lifting the rest yourself")

Nine definitions do not wrap a library this size, and they are not meant to. The module covers the
things whose ZIO shape is not obvious: the blocking-pool placement, the typed error channel, the
open-check on a capture, and a frame stream that respects the borrowing contract. Everything else is
mechanical, and writing it inline is clearer than hunting for a wrapper.

There are three patterns and no fourth. A pure `Either[CvError, A]` becomes `fromCv(…)`. An
object you own for a while becomes `ZIO.acquireRelease(acquire)(release)` --- with
`ZIO.succeed(m.release())` for a `Managed`, `ZIO.succeed(c.close())` for a `Camera`, `Recorder` or
`Image`, and plain `acquireRelease(…)` when a `Releasable` instance exists for the raw type. A
synchronous loop that must stay synchronous --- `Camera.foreach`, `Video.frames` with an
`attemptsPerFrame` bound --- becomes `ZIO.attemptBlockingInterrupt(…)` wrapping the whole traversal,
reducing each frame to owned data inside.

#example("A camera by index, which `captureScoped` does not cover: `Camera.open` takes an `Int`, so lift that.")[
```scala
// Camera.open(index, options) discards 5 warm-up frames by default, so the first frame
// this hands back is one the exposure loop has already converged on.
def cameraScoped(
    index: Int,
    options: CaptureOptions = CaptureOptions.Default
): ZIO[Scope, CvError, Camera] =
  ZIO.acquireRelease(ZIO.blocking(fromCv(Camera.open(index, options))))(c =>
    ZIO.succeed(c.close())
  )
```
]

#tip[
  The one rule that is not mechanical: use `ZIO.attemptBlocking` or `ZIO.attemptBlockingInterrupt`,
  never a bare `ZIO.attempt`, around anything that touches native code or the filesystem. A hung
  `VideoCapture.read` on the CPU-sized default executor can starve every other fiber in the process.
  The module holds itself to this so strictly that one of its own tests reads its own source file,
  strips the block comments --- the scaladoc examples do use `ZIO.attempt` --- and asserts that the
  string `ZIO.attempt(` does not appear in what is left.
]

#sect("Where this goes next")

The scoping discipline in this chapter makes a fiber's native footprint deterministic, which is the
precondition for measuring it. Chapter 38, #emph[Observability], takes that up: what a frame pipeline
should emit, how to watch RSS rather than heap when the interesting allocations are off-heap, and
how to instrument the stages of a `ZStream` so a rising frame age shows up as a metric long before
it shows up as a wrong answer.
