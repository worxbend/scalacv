package scalacv.zio

import _root_.zio.*
import _root_.zio.stream.*
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

import scalacv.*

/** ZIO bindings for scalacv.
  *
  * The core library is deliberately effect-free and hands ownership of native objects to the caller through
  * [[Managed]]. This module expresses that same ownership as ZIO `Scope`, so a native object is tied to a
  * scope's lifetime and released when the scope closes — on success, on failure, and on interruption, which
  * the plain `try`/`finally` form cannot guarantee.
  *
  * Nothing here changes the memory model; it changes who is responsible for driving it. A `Mat` acquired
  * through [[acquireRelease]] is freed exactly once, by the scope, and using it after the scope has closed is
  * the same use-after-release error [[Managed]] guards against.
  *
  * Native and filesystem work here runs on ZIO's blocking pool, never the CPU-sized default executor: loading
  * the natives extracts ~196 MB and `dlopen`s it, decoding an image blocks on disk, and `VideoCapture.read`
  * blocks in native code with no timeout of its own. Parking those on the compute executor would starve it.
  */

/** Acquires any releasable native object into the current `Scope`.
  *
  * The object is freed when the scope closes, through the same [[Releasable]] the synchronous API uses — so
  * `acquireRelease(CascadeClassifier())` frees it via the `delete(long)` bridge with the finalizer disarmed,
  * exactly as [[Managed]] would.
  *
  * Acquisition runs on the blocking pool: constructing a native object can open a model file from disk.
  *
  * {{{
  * ZIO.scoped {
  *   acquireRelease(Mat(1080, 1920, CvType.CV_8UC3)).flatMap { frame => ... }
  * }
  * }}}
  */
def acquireRelease[A](make: => A)(using r: Releasable[A]): ZIO[Scope, Throwable, A] =
  ZIO.acquireRelease(ZIO.attemptBlocking(make))(a => nativeFinalizer(r.release(a)))

/** Native cleanup is blocking, uninterruptible, and failures remain visible as defects. */
private[zio] def nativeFinalizer(close: => Unit): UIO[Unit] =
  // A fresh, joined fiber also makes the executor shift effective when the calling fiber is already
  // interrupted (ZIO can elide yielding while unwinding that fiber). It cannot be abandoned: joining
  // and the finalizer itself are masked. Daemon scope avoids inheriting the interrupted parent's scope.
  ZIO.attemptBlocking(close).orDie.uninterruptible.forkDaemon.flatMap(_.join).uninterruptible

/** Acquires an existing ownership handle and registers its original release strategy atomically with respect
  * to fiber interruption. Put resource production INSIDE `acquire`; adopting a handle previously emitted by
  * an interruptible producer cannot repair that earlier ownership gap. Acquisition is masked; a native call
  * must return before cancellation can finish. The acquired value must not escape its scope.
  */
def managedScoped[R, E, A](acquire: ZIO[R, E, Managed[A]]): ZIO[R & Scope, E, A] =
  ZIO.acquireRelease(acquire)(m => nativeFinalizer(m.release())).map(_.get)

/** Effect-aware bracket: keeps the handle live until the callback effect finishes, fails or is interrupted.
  * Acquisition and finalization are uninterruptible; callback execution retains caller interruptibility.
  * Cleanup failures are defects, composed with the callback's failure by ZIO, not silently discarded. Native
  * acquisition/callback work must explicitly use the blocking executor. Return reduced data, not the resource
  * or an alias of it, and do not let background fibers outlive the callback.
  */
def useManaged[R, E, A, B](acquire: ZIO[R, E, Managed[A]])(use: A => ZIO[R, E, B]): ZIO[R, E, B] =
  ZIO.acquireReleaseWith(acquire)(m => nativeFinalizer(m.release()))(m => use(m.get))

extension [A](self: Managed[A])
  /** Adopts this handle when the effect RUNS, not when it is constructed. The caller remains responsible
    * until then. Prefer [[managedScoped]] with acquisition inside it to protect the producer handoff.
    */
  def scopedZIO: ZIO[Scope, Nothing, A] = managedScoped(ZIO.succeed(self))

  /** Unlike synchronous `Managed.use`, this waits for the callback effect before releasing. Adoption only
    * starts when this effect runs: it does not make caller-owned streams safe under arbitrary combinators.
    */
  def useZIO[R, E, B](use: A => ZIO[R, E, B]): ZIO[R, E, B] =
    useManaged(ZIO.succeed(self))(use)

/** Loads the OpenCV natives as an effect. Idempotent, so it is safe to require from many places; the
  * underlying [[OpenCv.load]] does the work at most once.
  *
  * Runs on the blocking pool — the first load extracts ~196 MB of natives and `dlopen`s them.
  */
val loadNatives: Task[Unit] = ZIO.attemptBlocking(OpenCv.load())

/** Lifts a scalacv boundary result into ZIO's *typed* error channel, so a [[CvError]] stays a typed failure
  * rather than the bare `Throwable` a plain `ZIO.attempt` would give. The bridge for every `Either[CvError,
  * A]` the synchronous API returns — `fromCv(Image.read(path))`, `fromCv(Cascades.load(name))`,
  * `fromCv(Dnn.fromOnnx(path))`.
  *
  * `ZIO.fromEither` suspends, so the `Either` is evaluated when the effect *runs*, not when it is constructed
  * — but it runs on whatever executor executes the effect. Deferral is not executor placement: an `Either`
  * that does blocking work (decoding a file, opening a capture) still belongs inside `ZIO.blocking`, as
  * [[readImage]] and [[captureScoped]] do, or it will park that work on the compute pool.
  */
def fromCv[A](result: => Either[CvError, A]): IO[CvError, A] = ZIO.fromEither(result)

/** Reads an image as an effect, its failure typed as [[CvError]] — the ZIO face of [[Image.read]]. The
  * resulting [[Image]] is caller-owned; prefer [[imageScoped]] to have a scope close it, or `.close()` it
  * yourself.
  *
  * The decode blocks on disk, so it runs on the blocking pool while keeping the typed [[CvError]] channel.
  */
def readImage(path: String, flags: ImreadFlags = ImreadFlags.Color): IO[CvError, Image] =
  ZIO.blocking(fromCv(Image.read(path, flags)))

/** Acquires an [[Image]] into the current `Scope`: read on acquire, closed when the scope ends — on success,
  * failure, and interruption, which the synchronous `Image.reading` cannot promise once an interrupt is in
  * play. Its failure is the typed [[CvError]] from the read.
  *
  * {{{
  * ZIO.scoped {
  *   imageScoped("photo.jpg").flatMap { img => ZIO.attempt(img.gray.canny(80, 160).write("edges.png")) }
  * }
  * }}}
  */
def imageScoped(path: String, flags: ImreadFlags = ImreadFlags.Color): ZIO[Scope, CvError, Image] =
  ZIO
    .acquireRelease(
      readImage(path, flags).map { image =>
        // Populate before registering: an already closed ZIO scope runs its finalizer immediately.
        val scope = new Image.Scope
        (scope, scope.own(image))
      }
    ) { case (scope, _) => nativeFinalizer(scope.close()) }
    .map(_._2)

extension (self: Mat)
  /** Ties an existing Mat to the current scope. Use when a Mat is produced by an operation that already
    * allocated it and you want the scope to own it from here on.
    *
    * The acquire half is `ZIO.succeed`, not the `attemptBlocking` of [[acquireRelease]]: the Mat is already
    * allocated in the caller's hands, so there is no acquisition work left to suspend or to place on the
    * blocking pool — wrapping a pure value in `attemptBlocking` would only pay a blocking-pool hop for
    * nothing. Only the release needs registering, and the `Scope` runs that on close — on success, failure,
    * and interruption — exactly as [[acquireRelease]] would.
    */
  def scoped(using r: Releasable[Mat]): ZIO[Scope, Throwable, Mat] =
    ZIO.acquireRelease(ZIO.succeed(self))(m => nativeFinalizer(r.release(m)))

/** Opens a video source into the current `Scope`: opened on acquire, released when the scope ends — on
  * success, on failure, and on interruption. The ZIO face of [[Video.open]], and the way to get a capture to
  * hand to [[frameStream]].
  *
  * Prefer this over `acquireRelease(VideoCapture(source))`. The bare `VideoCapture` constructor cannot fail:
  * OpenCV reports "I could not open that" by leaving `isOpened` false rather than by throwing, so a missing
  * file, a busy camera, or a container this build has no backend for all hand you a live object whose every
  * `read` returns false. [[Video.open]] checks `isOpened`, and also performs the open-without-the-timeout-
  * parameters retry that [[CaptureOptions]] documents, so failure arrives here as a typed [[CvError]] instead
  * of as a stream that ends before its first frame.
  *
  * The open runs on the blocking pool: it hits the filesystem, or the network for an `rtsp://`/`http://`
  * source.
  *
  * {{{
  * ZIO.scoped {
  *   captureScoped("clip.mp4").flatMap(cap => frameStream(cap).map(f => f.get(0, 0)(0)).runCollect)
  * }
  * }}}
  *
  * @param source
  *   whatever the backend understands — a filesystem path, an `rtsp://` or `http://` URL, a `frame_%04d.png`
  *   sequence pattern, a GStreamer pipeline. See [[Video.open]].
  * @param options
  *   backend choice and the best-effort open/read timeouts; see [[CaptureOptions]] for why they are
  *   backend-dependent and off by default.
  */
def captureScoped(
    source: String,
    options: CaptureOptions = CaptureOptions.Default
): ZIO[Scope, CvError, VideoCapture] =
  ZIO
    .acquireRelease(ZIO.blocking(fromCv(Video.open(source, options))))(m => nativeFinalizer(m.release()))
    .map(_.get)

/** Fails a stream that would otherwise report a capture which never opened as a video with no frames in it.
  *
  * This is the typed twin of the `isOpened` require inside `Video.FrameSource`: there — a synchronous API — a
  * dead capture is a programmer error and throws `IllegalArgumentException`; here a bare throw would be a
  * defect, so the check runs as an effect and fails with a typed [[CvError.LoadFailed]] instead. Both exist
  * because a not-open capture reads as an instantly-empty stream, and "a video with no frames in it" is the
  * one failure shape neither layer may produce.
  *
  * Checked as an effect inside the stream rather than as a `require` in [[frameStream]]'s body because
  * [[frameStream]] is a value-returning constructor: a bare `require` would throw where the stream is *built*
  * — outside the error channel, and in whatever fiber happened to assemble the pipeline rather than the one
  * that runs it.
  */
private def requireOpen(capture: VideoCapture): ZStream[Any, CvError, Nothing] =
  ZStream.execute(
    ZIO.blocking(
      ZIO.unless(capture.isOpened)(
        ZIO.fail(
          CvError.LoadFailed(
            "capture",
            "cannot read frames from a capture that is not open — that would be an empty stream that looks " +
              "like a video with no frames in it. Obtain it with captureScoped, which reports failure as a " +
              "typed CvError."
          )
        )
      )
    )
  )

/** Frames from a capture as a `ZStream`, **each frame valid only until the next pull — and checked.**
  *
  * This inherits the borrowing contract of the synchronous `Video.frames` rather than ZIO's usual value
  * semantics, and the difference matters: every element is a [[BorrowedMat]], a liveness-checked view over
  * the single decode buffer the stream reuses. The view is spent the moment the stream pulls again or ends,
  * and access to an already-spent view throws `IllegalStateException`. This is a single-consumer contract,
  * not a concurrent lifetime lock: no access may overlap the next pull or source close, and the raw `.mat`
  * result is checked only at extraction. Retaining/prefetching combinators (`runCollect`, `broadcast`,
  * `buffer`, `zipWithNext`) therefore remain invalid for borrowed frames; fan-out can race before a view is
  * marked spent. Map each frame to something owned (encode it, copy the pixels, `_.clone()` it, reduce it)
  * before a retaining or parallel stage. There is no memoization, so the stream stays flat in memory over an
  * arbitrarily long video; that is the whole point.
  *
  * The read step is the core library's `Video.FrameSource` — the same exception-mode save/restore and the
  * same `attemptsPerFrame` retry loop the synchronous `Video.frames` uses, so a dropped frame is ridden out
  * identically in both APIs. For the duration of the stream the capture's exception mode is forced off and
  * its previous value restored when the stream ends: with exception mode on, plain end-of-file surfaces as
  * the same `CvException` a broken stream does, so a finished file would fail the stream rather than complete
  * it.
  *
  * The capture itself is not closed by the stream — acquire it through [[captureScoped]] so the scope owns
  * it. A capture that is not open fails the stream with a [[CvError.LoadFailed]] on the first pull: OpenCV
  * signals "could not open that" by leaving `isOpened` false, and every `read` on such a capture returns
  * false, so without the check a typo'd path would be indistinguishable from a video with no frames in it.
  * Past that, the stream stops after `attemptsPerFrame` consecutive frames that fail to decode, which for a
  * file is end-of-stream and for a camera is a dropped connection; those two *are* indistinguishable through
  * OpenCV's API, as `Video.frames` documents.
  *
  * ==Interruption cannot cut a read short==
  *
  * The read runs on the blocking pool, so a source that stops delivering pins a blocking thread rather than a
  * compute one. It is wrapped in `attemptBlockingInterrupt`, but that only delivers a JVM
  * `Thread.interrupt()` — which a thread parked inside OpenCV's native code never observes. So interrupting
  * the stream, or closing the scope around it, does not take effect until the in-flight `capture.read`
  * returns on its own; until then the buffer `Mat`, the exception-mode restore, and any enclosing `Scope` all
  * stay pending. Bounding that is the source's job, not the stream's: open the capture with
  * `CaptureOptions.withTimeout` on a backend that honours `CAP_PROP_READ_TIMEOUT_MSEC` (FFMPEG, GStreamer —
  * V4L2, AVFoundation and the built-in MJPEG reader ignore it), which [[captureScoped]] takes as its
  * `options`.
  *
  * @param attemptsPerFrame
  *   how many consecutive failed `read()` calls end the stream; see `Video.frames`. `1` — the default — is
  *   right for a file; a small value (2–5) rides out a live camera's dropped frames.
  */
def frameStream(capture: VideoCapture, attemptsPerFrame: Int = 1): ZStream[Any, Throwable, BorrowedMat] =
  requireOpen(capture) ++ ZStream
    .acquireReleaseWith(ZIO.attemptBlocking(Video.FrameSource(capture, attemptsPerFrame)))(s =>
      nativeFinalizer(s.close())
    )
    .flatMap: source =>
      ZStream.repeatZIOOption:
        ZIO
          .attemptBlockingInterrupt(source.nextFrame())
          .mapError(Some(_))
          .flatMap:
            case Some(view) => ZIO.succeed(view)
            case None => ZIO.fail(None) // None terminates the stream without an error

/** Low-level caller-owned clones. Every emitted handle must be released by the caller, including values
  * discarded by filtering, buffering, failed consumers or interruption before adoption. Arbitrary stream
  * combinators do NOT release these clones. Even immediate downstream `useZIO` cannot protect the preceding
  * producer-to-consumer handoff. Prefer [[processFrames]] for effectful processing.
  *
  * `Managed.use` is synchronous: NEVER return a lazy effect from it. `m.useZIO(f)` brackets execution once
  * adopted; `ZIO.attemptBlocking(m.use(syncFunction))` is the synchronous alternative, with the same handoff
  * limitation. Cloning runs on the blocking pool. The open-capture check of [[frameStream]] applies here too.
  */
def framesCopied(capture: VideoCapture, attemptsPerFrame: Int = 1)(using
    Releasable[Mat]
): ZStream[Any, Throwable, Managed[Mat]] =
  frameStream(capture, attemptsPerFrame).mapZIO(frame => ZIO.attemptBlocking(Managed(frame.clone())))

/** Safe-by-default sequential frame processing. Each clone is acquired INSIDE an effect-aware bracket, kept
  * live throughout `process`, and released before a result is emitted. Consequently downstream filtering,
  * buffering, parallel processing of results, failure and cancellation cannot abandon clones. There is no
  * queue of caller-owned frames and no interruptible ownership handoff between producer and callback. Only
  * reduced data may escape: never return the Mat, its native views, or a lazy computation referring to it.
  * Join any child work before returning. Additional native resources allocated by the callback remain the
  * callback's responsibility.
  *
  * Reads use the shared [[frameStream]] / `Video.FrameSource` implementation; cloning and finalization run on
  * the blocking pool. Place blocking callback work there explicitly. Callback execution is interruptible, but
  * clone acquisition and cleanup are masked; interruption cannot forcibly stop JNI. The capture itself
  * remains caller-owned. This API deliberately does not provide parallel native-frame processing.
  */
def processFrames[R, E >: Throwable, B](capture: VideoCapture, attemptsPerFrame: Int = 1)(
    process: Mat => ZIO[R, E, B]
)(using Releasable[Mat]): ZStream[R, E, B] =
  processFramesWith(capture, attemptsPerFrame)(frame => Managed(frame.clone()))(process)

// Injection seam for acquisition/handoff regression tests; production still uses the shared FrameSource.
// copy must either return its owned handle or roll back its own partially completed acquisition.
private[zio] def processFramesWith[R, E >: Throwable, B](capture: VideoCapture, attemptsPerFrame: Int)(
    copy: BorrowedMat => Managed[Mat]
)(process: Mat => ZIO[R, E, B]): ZStream[R, E, B] =
  frameStream(capture, attemptsPerFrame).mapZIO { frame =>
    useManaged[R, E, Mat, B](ZIO.attemptBlocking(copy(frame)))(process)
  }
