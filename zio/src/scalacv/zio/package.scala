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
  ZIO.acquireRelease(ZIO.attemptBlocking(make))(a => ZIO.succeed(r.release(a)))

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
  ZIO.acquireRelease(readImage(path, flags))(img => ZIO.succeed(img.close()))

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
    ZIO.acquireRelease(ZIO.succeed(self))(m => ZIO.succeed(r.release(m)))

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
    .acquireRelease(ZIO.blocking(fromCv(Video.open(source, options))))(m => ZIO.succeed(m.release()))
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

/** Frames from a capture as a `ZStream`, **each frame valid only until the next pull — and checked.**
  *
  * This inherits the borrowing contract of the synchronous `Video.frames` rather than ZIO's usual value
  * semantics, and the difference matters: every element is a [[BorrowedMat]], a liveness-checked view over
  * the single decode buffer the stream reuses. The view is spent the moment the stream pulls again or ends,
  * and every access then throws `IllegalStateException` — so combinators that retain elements (`runCollect`,
  * `broadcast`, `buffer`, `zipWithNext`) now fail loudly on access instead of silently seeing N aliases of
  * one buffer with the newest content. Map each frame to something owned (encode it, copy the pixels,
  * `_.clone()` it, reduce it) inside the stream. There is no memoization, so the stream stays flat in memory
  * over an arbitrarily long video; that is the whole point.
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
      ZIO.succeed(s.close())
    )
    .flatMap: source =>
      ZStream.repeatZIOOption:
        ZIO
          .attemptBlockingInterrupt(source.nextFrame())
          .mapError(Some(_))
          .flatMap:
            case Some(view) => ZIO.succeed(view)
            case None => ZIO.fail(None) // None terminates the stream without an error

/** Frames as owned `Managed[Mat]` values, cloned lazily as each is pulled.
  *
  * The safe-but-costlier counterpart to [[frameStream]]: every element is a caller-owned copy, so the usual
  * `ZStream` combinators behave as expected. Each clone must still be released — pair it with
  * `.mapZIO(m => m.use(...))` or acquire it into a scope.
  *
  * ==Consume each clone in the same fiber that pulls it==
  *
  * Ownership of a clone transfers to the consumer, so the consuming stage must release it — and it must do so
  * on the same fiber, promptly. A clone that is produced but then dropped because the fiber is
  * **interrupted** before a downstream `use`/scope takes it over leaks, exactly as a `Managed` dropped in
  * synchronous code would: the clone is a caller-owned resource the stream can no longer see. So map straight
  * into a releasing stage — `.mapZIO(m => m.use(process))` — rather than buffering the `Managed`s (`.buffer`,
  * `.grouped`, `runCollect` without prior release) across an interruptible boundary. When you want the stream
  * itself to own and release each frame, reduce it inside the stream on [[frameStream]] instead, whose one
  * reused buffer is tied to the stream's scope and released when the stream unwinds, interruption included —
  * though not before any in-flight native `read` has returned, for the reason [[frameStream]]'s scaladoc
  * gives.
  *
  * The open-capture check of [[frameStream]] applies here too: this fails rather than yielding nothing when
  * `capture` never opened.
  */
def framesCopied(capture: VideoCapture, attemptsPerFrame: Int = 1)(using
    Releasable[Mat]
): ZStream[Any, Throwable, Managed[Mat]] =
  frameStream(capture, attemptsPerFrame).map(frame => Managed(frame.clone()))
