package scalacv

import org.opencv.videoio.VideoCapture

/* High-level video capture — reading a camera or a video file as owned [[Image]]s. The writer side lives in
 * Recorder.scala and the codec enum in Codec.scala; this file keeps only the capture concern and its scoped
 * combinators.
 */

/** High-level video capture — a camera or a video file, walked as owned [[Image]]s.
  *
  * `Camera` is the high-level counterpart to [[Video]]. Where `Video.frames` hands you one reused, borrowed
  * `Mat` for zero-copy speed, `Camera` hands you a fresh **owned** `Image` per frame — one you can transform,
  * detect on, annotate, or keep, on the same terms as any other `Image`. The price is one frame copy per
  * iteration; when that matters, drop to `Video.frames` on the borrowed [[capture]].
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * // Process every frame of a file into an edge video. Reading `.mp4` is fine — the container restriction
  * // is the writer's: the default codec is MJPG, which opens only in an `.avi` (see [[Codec.Mjpg]]).
  * Camera.usingFile("clip.mp4") { cam =>
  *   cam.recordTo("edges.avi")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
  * }
  *
  * // Grab a single webcam snapshot:
  * Camera.using(0)(_.snapshot().flatMap(_.write("shot.png")))
  * }}}
  *
  * The capture is **caller-owned**: [[close]] it, or acquire it through [[Camera.using]] /
  * [[Camera.usingFile]], which close for you. `Camera` is `AutoCloseable`.
  */
final class Camera private (private val handle: Managed[VideoCapture], sourceLabel: String)
    extends AutoCloseable:

  /** What the backend claims about this source — advisory, every field a `CAP_PROP_*` query. See
    * [[CaptureInfo]]; a live camera commonly reports `frameCount == 0` and an `fps` of `0` until it warms up.
    */
  def info: CaptureInfo = Video.info(handle.get)

  /** The source's reported frame size — advisory (see [[info]]). */
  def size: Size = info.size

  /** The source's reported frames-per-second — advisory, and `0` for a camera that has not delivered yet. */
  def fps: Double = info.fps

  /** The raw `VideoCapture`, **borrowed** — the low-level escape hatch for `Video.frames`, seeking with
    * `CAP_PROP_POS_FRAMES`, or any `org.opencv.videoio.*` call. It stays owned by this `Camera`.
    */
  def capture: VideoCapture = handle.get

  /** Grabs a single frame as an owned [[Image]].
    *
    * `Left(CvError.EndOfStream)` when the stream has ended or the device delivered nothing within
    * `attemptsPerFrame` reads — a camera can drop a frame without being dead, so the default retries a few
    * times. The error names this camera's own source (the file path or the device index), not the bare word
    * "camera".
    *
    * On a camera this is the *first frame after warm-up*, not the first frame off the device: [[Camera.open]]
    * discards a few frames so the auto-exposure loop has converged, which is what stops a snapshot taken
    * immediately after opening from being black. Tune or disable that with `CaptureOptions.warmupFrames`.
    */
  def snapshot(attemptsPerFrame: Int = 3): Either[CvError, Image] =
    Video.framesCopied(handle.get, attemptsPerFrame)(_.nextOption()) match
      case Some(frame) => Right(Image.wrap(frame))
      case None =>
        Left(
          CvError.EndOfStream(
            sourceLabel,
            "the stream ended or the device delivered nothing"
          )
        )

  /** Runs `f` over every frame, each as an owned [[Image]] that is **closed for you** when `f` returns.
    *
    * This is the processing loop to reach for. The `Image` is a caller-safe copy: transform it, detect on it,
    * write it — anything an `Image` allows. It stops at end-of-stream (a file's last frame, a camera's
    * disconnection), which OpenCV cannot tell apart, so a bounded `attemptsPerFrame` rides out dropped frames
    * without turning a dead camera into an endless loop.
    */
  def foreach(attemptsPerFrame: Int = 3)(f: Image => Unit): Unit =
    Video.framesCopied(handle.get, attemptsPerFrame): frames =>
      frames.foreach: frame =>
        val image = Image.wrap(frame)
        try f(image)
        finally image.close()

  /** The next `count` frames as owned [[Image]]s — **each is yours to close** (or take them into a
    * `Using.Manager`). Frames beyond the end of the stream are simply absent, so the result may be shorter.
    *
    * These are live resources in a bare collection, which the type cannot warn you about: prefer [[taking]],
    * which closes them for you, unless you specifically need to hold the frames past a scope.
    */
  def take(count: Int, attemptsPerFrame: Int = 3): Seq[Image] =
    require(count >= 0, s"take count cannot be negative, got $count")
    Video.framesCopied(handle.get, attemptsPerFrame)(_.take(count).map(Image.wrap).toList)

  /** Grabs the next `count` frames, runs `use` over them, and closes every one afterwards — on success, on
    * failure, and on exception. The scoped counterpart to [[take]]: reach for this when you need several
    * frames at once (to compare or composite them) without owning their lifetimes.
    */
  def taking[A](count: Int, attemptsPerFrame: Int = 3)(use: Seq[Image] => A): A =
    val images = take(count, attemptsPerFrame)
    try use(images)
    finally images.foreach(_.close())

  /** Reads every frame, applies `transform`, and writes the results to `path` as a video; returns the number
    * of frames written.
    *
    * The recorder is sized from the **first transformed frame**, not from [[info]]. So `transform` may
    * resize, as long as it resizes every frame the same way: a size that changes part-way through is a
    * `Left`, and a source that yields no frames at all returns `Right(0)` and creates no file. `transform`
    * must hand back 8-bit frames — colour-convert, filter and annotate all do; an operation that widens the
    * depth (a Sobel asked for `OutputDepth.Float32`, a raw disparity map) has to be brought back to 8 bits
    * first, or [[Recorder.write]] rejects it. A frame that fails to encode is a `Left`, as is a recorder that
    * cannot open (an unavailable codec, an unwritable path).
    *
    * @param fps
    *   frames per second for the output; `0` derives it from the source, falling back to 30 when the source
    *   does not report one (common for a camera).
    * @param codec
    *   defaults to [[Codec.Mjpg]], the one codec videoio can always write — which means `path` should end in
    *   `.avi`, since MJPG does not open in an `.mp4` or `.mkv` container.
    * @param attemptsPerFrame
    *   how many times a frame read is retried before it counts as end-of-stream — see [[foreach]]. The
    *   default of 3 tolerates a flaky live camera; a finite file source can set `1` to avoid the extra
    *   blocking reads at EOF.
    */
  def recordTo(path: String, fps: Double = 0, codec: Codec = Codec.Mjpg, attemptsPerFrame: Int = 3)(
      transform: Image => Image
  ): Either[CvError, Long] =
    val source = info
    val outFps = if fps > 0 then fps else if source.fps > 0 then source.fps else 30.0
    // The recorder waits for a real frame rather than taking its geometry from `info`. Every field of `info`
    // is a CAP_PROP_* query, and a camera that has not delivered a frame yet answers 0x0 — which would hit
    // Recorder.open's positive-size precondition and throw an IllegalArgumentException out of a method that
    // promises a Left for a recorder that cannot open. A decoded frame cannot misreport its own size. fps has
    // no equivalent source of truth, so it keeps the advisory-with-fallback treatment above.
    var recorder: Option[Recorder] = None
    var written = 0L
    try
      foreach(attemptsPerFrame): frame =>
        val processed = transform(frame)
        try
          val rec = recorder.getOrElse:
            val opened = Recorder.open(path, processed.size, outFps, codec).fold(throw _, identity)
            recorder = Some(opened)
            opened
          // A source that renegotiates its resolution mid-stream (adaptive RTSP, an MSMF format change) is
          // not the transform's mistake, so it is reported rather than left to Recorder.write's `require`,
          // whose IllegalArgumentException would escape this method's Either entirely.
          if processed.size != rec.size then
            throw CvError.EncodeFailed(
              path,
              s"the frame size changed mid-stream: the recorder was opened at ${rec.size}, then a frame " +
                s"arrived at ${processed.size}"
            )
          rec.write(processed).fold(e => throw e, _ => written += 1)
        finally processed.close()
      Right(written)
    catch case e: CvError => Left(e)
    finally recorder.foreach(_.close())

  /** Releases the capture. Idempotent; called for you by [[Camera.using]] / [[Camera.usingFile]] and `Using`.
    */
  def close(): Unit = handle.release()

object Camera:

  /** Opens a camera by device index. `Left` if the device does not exist, is busy, or no backend can drive
    * it.
    */
  def open(index: Int, options: CaptureOptions = CaptureOptions.Default): Either[CvError, Camera] =
    Video.open(index, options).map(new Camera(_, s"camera $index"))

  /** Opens a video file, URL (`rtsp://`, `http://`), or `frame_%04d.png` sequence. */
  def openFile(source: String, options: CaptureOptions = CaptureOptions.Default): Either[CvError, Camera] =
    Video.open(source, options).map(new Camera(_, source))

  /** Opens camera `index`, runs `use`, and closes the camera afterwards — even on an exception.
    *
    * The whole `use` body runs inside [[Cv.attempt]], as in [[Image.reading]]: a [[CvError.NativeCall]]
    * thrown by an operation inside the block comes back as a `Left` rather than escaping past the `Either`.
    * Programmer errors (`IllegalArgumentException`, use-after-close) still throw.
    */
  def using[A](index: Int, options: CaptureOptions = CaptureOptions.Default)(
      use: Camera => A
  ): Either[CvError, A] = Scoped.using(open(index, options), "camera")(use)

  /** Opens `source`, runs `use`, and closes the camera afterwards. The body runs inside [[Cv.attempt]] for
    * the reason given on [[using]].
    */
  def usingFile[A](source: String, options: CaptureOptions = CaptureOptions.Default)(
      use: Camera => A
  ): Either[CvError, A] = Scoped.using(openFile(source, options), "camera")(use)
