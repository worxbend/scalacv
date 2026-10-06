package scalacv

import org.opencv.core.{CvType, Mat}
import org.opencv.videoio.VideoWriter

/* Video writing: the fixed-geometry frame sink behind `Camera.recordTo` and direct recording. Split from
 * Camera.scala because writing is a separate concern from capture — the only thing the two share is the
 * [[Codec]] they both name.
 */

/** Writes [[Image]]s to a video file — the counterpart to [[Camera]] for output.
  *
  * A recorder is fixed at open time to one frame size, fps and codec; every frame written must match that
  * size and be 8-bit. `VideoWriter` is one of the three OpenCV types with a real public `release()`, and the
  * recorder is **caller-owned** — [[close]] it, or use [[Recorder.using]].
  */
final class Recorder private (private val handle: Managed[VideoWriter], val size: Size) extends AutoCloseable:

  /** Appends `image` as the next frame. The image is **borrowed**, not consumed. `Left` if OpenCV rejects the
    * write; throws [[IllegalArgumentException]] if the frame size does not match the recorder's, or if the
    * frame is not 8-bit.
    */
  def write(image: Image): Either[CvError, Unit] = write(image.mat)

  /** Appends a raw `Mat` as the next frame — the borrowing overload, so the zero-copy frames from
    * `Video.frames` can be recorded without the per-frame clone an [[Image]] would require. The Mat is
    * **borrowed**, not consumed. `Left` if OpenCV rejects the write; throws [[IllegalArgumentException]] if
    * the frame size does not match the recorder's, or if the frame is not 8-bit.
    */
  def write(frame: Mat): Either[CvError, Unit] =
    require(
      frame.cols == size.width.toInt && frame.rows == size.height.toInt,
      s"frame ${frame.cols}x${frame.rows} does not match the recorder's ${size.width.toInt}x${size.height.toInt}"
    )
    // `VideoWriter.write` returns void and the encoder never inspects the depth, so a CV_32F or CV_16S frame
    // is accepted, its raw bytes reinterpreted as 8-bit pixels, and a playable file of noise is produced with
    // every call reporting success. This precondition is the only signal that can exist — it sits beside the
    // size check because a wrong-depth frame is the same class of programmer error, per the policy in [[Cv]].
    require(
      CvType.depth(frame.`type`()) == CvType.CV_8U,
      s"a recorder needs 8-bit frames, got ${CvType.typeToString(frame.`type`())} — convert first, for " +
        "example with convertScaleAbs, or by normalising to 0..255 and converting to CV_8U"
    )
    Cv.attempt("VideoWriter.write")(handle.get.write(frame)).map(_ => ())

  /** The raw `VideoWriter`, **borrowed** — the low-level escape hatch. Owned by this `Recorder`. */
  def writer: VideoWriter = handle.get

  /** Finalises and closes the file. Idempotent; called for you by [[Recorder.using]] and `Using`. */
  def close(): Unit = handle.release()

object Recorder:

  /** Opens a recorder writing to `path`.
    *
    * @param size
    *   the exact frame size every written frame must have.
    * @param fps
    *   output frames per second.
    * @param codec
    *   defaults to [[Codec.Mjpg]], the one codec videoio can always write, because it is served by the
    *   built-in MJPEG writer instead of an optional FFmpeg or system encoder. MJPG opens only in an `.avi`,
    *   so the default and `path`'s extension go together.
    * @param color
    *   `false` for a single-channel (greyscale) stream.
    * @return
    *   `Left` if the writer cannot open — most often an unavailable codec for this build, or an unwritable
    *   path. OpenCV reports that by leaving `isOpened` false rather than throwing.
    */
  def open(
      path: String,
      size: Size,
      fps: Double = 30.0,
      codec: Codec = Codec.Mjpg,
      color: Boolean = true
  ): Either[CvError, Recorder] =
    require(fps > 0, s"fps must be positive, got $fps")
    require(size.width > 0 && size.height > 0, s"a recorder needs a positive frame size, got $size")
    val vw = VideoWriter()
    // `vw` is a native object with no owner until it reaches `new Recorder`, so every exit that does not get
    // there has to release it by hand. Failure arrives in two shapes: `vw.open` can throw at the codec
    // boundary, which `Cv.attempt` turns into a `Left` so `flatMap` never runs its body, or it can return
    // without throwing while leaving the writer closed. Releasing once, after the whole attempt, whenever the
    // outcome is a `Left` covers both — the same discipline `Video.openCapture` applies to its capture.
    val outcome =
      Cv.attempt(s"VideoWriter.open('$path')")(vw.open(path, codec.fourcc, fps, size.toCv, color))
        .flatMap: opened =>
          if opened && vw.isOpened then Right(new Recorder(Managed(vw), size))
          else
            Left(
              CvError.LoadFailed(
                path,
                s"VideoWriter could not open with codec $codec — the codec may be unavailable in this OpenCV " +
                  "build, or the path may not be writable. Try Codec.Mjpg with an .avi extension, which " +
                  "encodes with the built-in codecs."
              )
            )
    if outcome.isLeft then vw.release()
    outcome

  /** Opens a recorder, runs `use`, and closes it afterwards — even on an exception. `codec` defaults to
    * [[Codec.Mjpg]] for the reason given on [[open]]; `path` should end in `.avi` to match it.
    *
    * The whole `use` body runs inside [[Cv.attempt]], as in [[Image.reading]]: a [[CvError.NativeCall]]
    * thrown by an operation inside the block comes back as a `Left` rather than escaping past the `Either`.
    * Programmer errors (`IllegalArgumentException`, use-after-close) still throw.
    */
  def using[A](
      path: String,
      size: Size,
      fps: Double = 30.0,
      codec: Codec = Codec.Mjpg,
      color: Boolean = true
  )(use: Recorder => A): Either[CvError, A] =
    Scoped.using(open(path, size, fps, codec, color), "recorder")(use)
