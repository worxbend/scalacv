package scalacv.graphs

import org.opencv.core.{Mat, MatOfInt}
import org.opencv.imgcodecs.{Animation as CvAnimation, Imgcodecs}

import scalacv.*

/** Animation — a drawing that is a function of the frame number, rendered to a video.
  *
  * The natural extension of a composable [[Picture]]: describe frame `i` as `frame(i)`, and [[record]] draws
  * every frame onto a fresh canvas and writes them out through a [[Recorder]]. Creative coding, a rendered
  * data animation, a synthetic test clip — all fall out of the same graphics vocabulary.
  *
  * {{{
  * // `.avi`, not `.mp4`: the default codec is MJPG, which opens only in an AVI container — see [[Codec.Mjpg]].
  * Animation.record("spin.avi", frames = 60, width = 320, height = 240) { i =>
  *   Picture.regularPolygon(Point(160, 120), sides = 5, radius = 80, rotation = i * 6)
  *     .strokeColor(Color.hsl(i * 6, 0.8, 0.6)).strokeWidth(3)
  * }
  * }}}
  */
object Animation:

  private given Releasable[CvAnimation] = Releasable.nativeHandle

  /** Removes a half-written output after a failed render or encode. A truncated video or GIF still opens in a
    * player, so a partial file reads as success; deleting it makes a failure a failure. Best-effort — a
    * cleanup that itself fails must not mask the original error.
    */
  private def deletePartial(path: String): Unit =
    try java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path)): Unit
    catch case _: Throwable => ()

  /** Renders `frames` frames — each the picture `frame(i)` drawn on a `width`×`height` `background` canvas —
    * and writes them to `path` as a video at `fps`. Returns the number of frames written, or a `Left` if the
    * recorder cannot open or a frame fails to encode.
    *
    * @param codec
    *   defaults to [[Codec.Mjpg]], the one codec videoio can always write, because it is served by the
    *   built-in MJPEG writer rather than by an FFmpeg plugin the natives may not ship. That makes `path`'s
    *   extension part of the contract: MJPG opens only in an `.avi`, so a `.mp4` fails to open even though
    *   the codec itself is present. Pass [[Codec.Mp4v]] with an `.mp4` only on a build you know has FFmpeg.
    */
  def record(
      path: String,
      frames: Int,
      width: Int,
      height: Int,
      fps: Double = 30,
      background: Color = Color.Black,
      codec: Codec = Codec.Mjpg
  )(frame: Int => Picture): Either[CvError, Long] =
    require(frames >= 0, s"frames cannot be negative, got $frames")
    Recorder
      .open(path, Size(width.toDouble, height.toDouble), fps, codec)
      .flatMap: recorder =>
        // One close, in the finally: the recorder is released exactly once however the loop ends, and the
        // close must run BEFORE `deletePartial` — the writer holds the file, and an open file will not
        // delete on Windows. Recording the failure and acting on it after the finally keeps that order
        // with a single close call; the previous shape closed in the try and again in the catch, correct
        // only because `Managed.release` happens to be idempotent.
        var written = 0L
        var failure: Throwable | Null = null
        try
          var i = 0
          while i < frames do
            val canvas = frame(i).render(width, height, background)
            try recorder.write(canvas).fold(e => throw e, _ => written += 1)
            finally canvas.close()
            i += 1
        catch case e => failure = e
        finally recorder.close()
        // A CvError (a failed encode) becomes a Left; a bad Picture's IllegalArgumentException stays a throw.
        failure match
          case null => Right(written)
          case cv: CvError =>
            deletePartial(path)
            Left(cv)
          case other =>
            deletePartial(path)
            throw other

  /** Renders `count` frames as owned [[Image]]s (each `frame(i)` on a fresh `width`×`height` `background`
    * canvas) — for feeding elsewhere than a file. **Each image is yours to close.**
    *
    * These are live resources in a bare collection, which the type cannot warn you about: prefer [[foreach]],
    * which closes each canvas for you, unless you specifically need to hold the frames past a scope. All
    * `count` canvases are alive at once here, so the peak native cost is `count × width × height × 3` bytes —
    * about 6 MB per frame at 1080p.
    *
    * Strict, and deliberately not a `LazyList`: a lazy sequence memoises, so every canvas it has ever yielded
    * stays reachable behind ~16 bytes of JVM heap — no GC pressure at all against a multi-megabyte native
    * buffer — and a second traversal after the caller has closed them, which is the whole reason to reach for
    * a re-traversable lazy type, hands back spent handles that throw. [[Video]] spells the argument out.
    *
    * If `frame` throws partway through, the canvases already rendered are closed before the exception
    * propagates: the `Seq` that would have carried them never reaches the caller, so nobody else can.
    */
  def frames(count: Int, width: Int, height: Int, background: Color = Color.Black)(
      frame: Int => Picture
  ): Seq[Image] =
    require(count >= 0, s"count cannot be negative, got $count")
    // Accumulated in a buffer under a catch rather than by `List.tabulate`: tabulate has nowhere to put the
    // canvases it already built when element k throws, so they leak. Same argument as `gif` below.
    val rendered = scala.collection.mutable.ArrayBuffer.empty[Image]
    try
      var i = 0
      while i < count do
        rendered += frame(i).render(width, height, background)
        i += 1
      rendered.toList
    catch
      case e =>
        rendered.foreach(_.close())
        throw e

  /** Renders `count` frames one at a time and hands each to `f` as an owned [[Image]] that is **closed for
    * you** when `f` returns — on success, on failure, and on exception.
    *
    * This is the streaming form to reach for, and the one to use for anything long: exactly one canvas is
    * live at a time, so the peak native cost is a single frame however large `count` is. [[frames]] is the
    * counterpart for when the frames must outlive a scope, at `count` canvases held at once.
    *
    * {{{
    * Animation.foreach(900, 1920, 1080)(myPicture) { canvas =>
    *   recorder.write(canvas).fold(e => throw e, _ => ())
    * }
    * }}}
    */
  def foreach(count: Int, width: Int, height: Int, background: Color = Color.Black)(
      frame: Int => Picture
  )(f: Image => Unit): Unit =
    require(count >= 0, s"count cannot be negative, got $count")
    var i = 0
    while i < count do
      val canvas = frame(i).render(width, height, background)
      try f(canvas)
      finally canvas.close()
      i += 1

  /** Renders `frames` frames and writes them to `path` as an **animated GIF** at `fps` — the shareable format
    * for a short loop (a demo, a rendered chart animation). `loop` true repeats forever. Returns the number
    * of frames written, or a `Left` if encoding fails.
    *
    * GIF is 256 colours per frame; OpenCV dithers to fit. For full-colour or long clips, use [[record]] to a
    * video instead.
    *
    * ==Peak memory: every frame is held natively at once==
    *
    * `imwriteanimation` takes the whole animation in a single call, so unlike [[record]] — which streams one
    * canvas at a time through the writer — this renders and holds **all `frames` canvases in native memory
    * until the encode returns**: a peak of `frames × width × height × 3` bytes, about 6 MB per frame at 1080p
    * (a 60-frame 1080p GIF peaks near 373 MB). That is the exact accumulation [[Video.frames]] warns against,
    * forced here by the encoder's API; keep GIFs short and small, and reach for [[record]] past that.
    */
  def gif(
      path: String,
      frames: Int,
      width: Int,
      height: Int,
      fps: Double = 15,
      background: Color = Color.Black,
      loop: Boolean = true
  )(frame: Int => Picture): Either[CvError, Long] =
    require(frames >= 0, s"frames cannot be negative, got $frames")
    require(fps > 0, s"fps must be positive, got $fps")
    if frames == 0 then Right(0L)
    else
      // Rendered INSIDE the try, not eagerly before it: each frame(i).render owns a Mat, and if
      // render throws partway (a throwing frame lambda, a Picture that fails on a later frame) the
      // frames already built must still be closed. Accumulating in the try's own buffer makes the
      // finally cover them; the previous `Vector.tabulate` ran before the try and leaked them.
      val images = scala.collection.mutable.ArrayBuffer.empty[Image]
      val result =
        try
          var i = 0
          while i < frames do
            images += frame(i).render(width, height, background)
            i += 1
          Managed(CvAnimation()).use: anim =>
            anim.set_loop_count(if loop then 0 else 1)
            val list = java.util.ArrayList[Mat](frames)
            images.foreach(img => list.add(img.mat))
            anim.set_frames(list)
            val durationMs = math.max(1, math.round(1000.0 / fps).toInt)
            Managed.use(MatOfInt(Array.fill(frames)(durationMs)*)): durations =>
              anim.set_durations(durations)
              Cv.attempt(s"imwriteanimation('$path')")(Imgcodecs.imwriteanimation(path, anim)).flatMap {
                case true => Right(frames.toLong)
                case false => Left(CvError.EncodeFailed(path, "imwriteanimation returned false"))
              }
        finally images.foreach(_.close())
      // A returned-false or CvException encode can still leave a half-written GIF behind; drop it.
      if result.isLeft then deletePartial(path)
      result
