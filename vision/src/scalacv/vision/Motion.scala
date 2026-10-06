package scalacv.vision

import org.opencv.core.{Core, Mat}
import org.opencv.video.BackgroundSubtractorMOG2

import scalacv.*

/** What one frame of motion detection found — plain immutable data, valid after the frame is freed.
  *
  * @param moving
  *   `true` when [[ratio]] crosses the detector's `motionRatio` threshold.
  * @param ratio
  *   the fraction of the frame that changed, in `[0, 1]`.
  * @param regions
  *   bounding boxes of the moving blobs, **largest first**, already filtered by the detector's `minArea`.
  */
final case class Motion(moving: Boolean, ratio: Double, regions: Seq[Rect]):

  /** How many distinct moving regions were found. */
  def regionCount: Int = regions.size

  /** The largest moving region, if any. */
  def largest: Option[Rect] = regions.headOption

object Motion:

  /** No motion — the result for the very first frame, or a still scene. */
  val still: Motion = Motion(moving = false, ratio = 0.0, regions = Seq.empty)

/** Detects motion across a sequence of frames from a (usually static) camera.
  *
  * This is the piece for the classic surveillance / trail-cam / **ESP32-CAM** job: a fixed camera streaming
  * frames — often low frame-rate MJPEG, i.e. a run of independent JPEGs — where you want to know *when
  * something moved and where*, cheaply. Feed frames in order; the detector is **stateful** (it remembers the
  * previous frame or an adaptive background) and **not thread-safe**.
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * val detector = MotionDetector.frameDifference()
  * try
  *   for jpeg <- mjpegFrames do            // each an Array[Byte] straight off the stream
  *     detector.detect(jpeg).foreach: motion =>
  *       if motion.moving then
  *         println(s"motion in ${motion.regionCount} region(s), ${(motion.ratio * 100).round}% of frame")
  * finally detector.close()
  * }}}
  *
  * Or drive it from a [[Camera]] — an ESP32 MJPEG endpoint opens like any other source:
  * {{{
  * Camera.usingFile("http://esp32-cam.local:81/stream") { cam =>
  *   cam.foreach(frame => if detector.detect(frame).moving then alert())
  * }
  * }}}
  *
  * Two strategies, both reached through the factories:
  *   - [[MotionDetector.frameDifference]] — compares each frame to the one before. Cheap and immediate; the
  *     right default for a static camera and low frame rates.
  *   - [[MotionDetector.backgroundSubtraction]] — an adaptive background model (OpenCV's MOG2). Heavier and
  *     needs a few frames to settle, but shrugs off gradual lighting changes and repetitive background
  *     motion.
  *
  * A detector holds native memory (a retained frame, or the background model), so it is `AutoCloseable`:
  * [[close]] it when done.
  */
trait MotionDetector extends AutoCloseable:

  /** Feeds the next frame and reports what moved. The image is **borrowed** (read only), not consumed. */
  def detect(image: Image): Motion

  /** Decodes an encoded image (a JPEG straight off an MJPEG stream, a PNG, …) and detects motion in it — the
    * MJPEG-stream entry point. `Left` only if the bytes are not a decodable image.
    */
  def detect(encoded: Array[Byte]): Either[CvError, Motion] =
    Image
      .decode(encoded)
      .map: image =>
        try detect(image)
        finally image.close()

  /** Forgets accumulated state, so the next frame is treated as a fresh baseline. */
  def reset(): Unit

  /** Releases native memory. Idempotent. */
  def close(): Unit = ()

object MotionDetector:

  private given Releasable[BackgroundSubtractorMOG2] = Releasable.nativeHandle

  /** Frame-difference motion detection: each frame is compared to the previous one. The first frame reports
    * [[Motion.still]] and becomes the baseline.
    *
    * @param threshold
    *   per-pixel intensity delta (0–255) that counts as changed. Lower is more sensitive.
    * @param minArea
    *   moving blobs smaller than this many pixels are ignored — the noise gate.
    * @param blurRadius
    *   pre-blur applied before differencing, to suppress sensor noise; `0` disables it.
    * @param motionRatio
    *   the fraction of the frame that must change for [[Motion.moving]] to be `true`.
    */
  def frameDifference(
      threshold: Int = 25,
      minArea: Int = 500,
      blurRadius: Int = 2,
      motionRatio: Double = 0.002
  ): MotionDetector =
    require(threshold >= 0 && threshold <= 255, s"threshold must be in [0, 255], got $threshold")
    require(minArea >= 0, s"minArea cannot be negative, got $minArea")
    require(blurRadius >= 0, s"blurRadius cannot be negative, got $blurRadius")
    require(motionRatio >= 0 && motionRatio <= 1, s"motionRatio must be in [0, 1], got $motionRatio")
    FrameDiff(threshold, minArea, blurRadius, motionRatio)

  /** Background-subtraction motion detection using OpenCV's adaptive MOG2 model. Robust to slow lighting
    * changes; needs a few frames to warm up, during which it may over-report.
    *
    * @param history
    *   how many recent frames the background model blends over.
    * @param varThreshold
    *   Mahalanobis distance a pixel must exceed to be foreground. Higher is stricter.
    * @param detectShadows
    *   detect and drop cast shadows (they are marked, then removed here). Costs a little.
    * @param minArea
    *   noise gate, as in [[frameDifference]].
    * @param motionRatio
    *   frame-fraction threshold for [[Motion.moving]].
    * @param learningRate
    *   how fast the model adapts; `-1` lets OpenCV choose automatically.
    */
  def backgroundSubtraction(
      history: Int = 200,
      varThreshold: Double = 16,
      detectShadows: Boolean = true,
      minArea: Int = 500,
      motionRatio: Double = 0.002,
      learningRate: Double = -1
  ): MotionDetector =
    require(history >= 1, s"history must be at least 1, got $history")
    require(minArea >= 0, s"minArea cannot be negative, got $minArea")
    require(motionRatio >= 0 && motionRatio <= 1, s"motionRatio must be in [0, 1], got $motionRatio")
    val rebuild = () =>
      org.opencv.video.Video.createBackgroundSubtractorMOG2(history, varThreshold, detectShadows)
    BgSubtract(Managed(rebuild()), rebuild, minArea, motionRatio, learningRate)

  /** Grayscale + optional blur — the common front end of both strategies. */
  private def prepare(mat: Mat, blurRadius: Int): Managed[Mat] =
    val side = (blurRadius * 2 + 1).toDouble
    // gaussianBlur allocates its own destination and only borrows its receiver, so when the frame is
    // already grey and a blur follows, it is blurred directly — no defensive clone (the clone was freed
    // unused, ~40% of this per-frame front end). A clone is kept only for the already-grey, no-blur
    // case, where it is the ownership tax: the caller keeps `mat`, so we cannot hand back a view of it.
    (mat.channels >= 3, blurRadius > 0) match
      case (true, true) => mat.cvtColor(ColorConversion.BgrToGray).pipe(_.gaussianBlur(Size(side, side)))
      case (true, false) => mat.cvtColor(ColorConversion.BgrToGray)
      case (false, true) => mat.gaussianBlur(Size(side, side))
      case (false, false) => Managed(mat.clone())

  /** A binary foreground mask (`CV_8UC1`, 0/255) → a [[Motion]]: change ratio plus the bounding boxes of the
    * blobs that survive `minArea`.
    */
  private def measure(mask: Mat, minArea: Int, motionRatio: Double): Motion =
    val changed = Core.countNonZero(mask)
    val total = mask.rows * mask.cols
    val ratio = if total == 0 then 0.0 else changed.toDouble / total
    Motion(ratio >= motionRatio, ratio, Mats.blobs(mask, minArea))

  private final class FrameDiff(threshold: Int, minArea: Int, blurRadius: Int, motionRatio: Double)
      extends MotionDetector:

    // The previous frame, grayscale and blurred. Retained between calls; one Mat, replaced each frame.
    private var previous: Managed[Mat] | Null = null

    def detect(image: Image): Motion =
      val current = prepare(image.mat, blurRadius)
      try
        previous match
          case null =>
            previous = current // first frame becomes the baseline; kept, not released
            Motion.still
          case prev =>
            val motion =
              prev.get
                .absdiff(
                  current.get
                ) // throws CvException if this frame's size/type differs from the baseline
                .use: diff =>
                  diff
                    .threshold(threshold.toDouble, 255)
                    .image
                    .use: mask =>
                      mask.dilate(radius = 2).use(merged => measure(merged, minArea, motionRatio))
            prev.release() // the old baseline is done
            previous = current // this frame is the new baseline
            motion
      catch
        case e: Throwable =>
          // Free this frame unless it was just installed as the new baseline (the `null` branch above).
          if previous ne current then current.release()
          throw e

    def reset(): Unit =
      val p = previous
      previous = null
      if p != null then p.release()

    override def close(): Unit = reset()

  private final class BgSubtract(
      initial: Managed[BackgroundSubtractorMOG2],
      rebuild: () => BackgroundSubtractorMOG2,
      minArea: Int,
      motionRatio: Double,
      learningRate: Double
  ) extends MotionDetector:

    // The live model; replaced wholesale by `reset`. Rebuilding is the only meaningful reset MOG2 has —
    // it exposes no clear/reset of its own.
    private var subtractor: Managed[BackgroundSubtractorMOG2] = initial

    def detect(image: Image): Motion =
      Managed.use(Mat()): fgMask =>
        Cv.orThrow("BackgroundSubtractorMOG2.apply")(subtractor.get.apply(image.mat, fgMask, learningRate))
        // MOG2 marks 0 = background, 255 = foreground, 127 = shadow; keep only strong foreground.
        fgMask
          .threshold(200, 255)
          .image
          .use: mask =>
            mask.morphology(MorphOp.Open, radius = 2).use(cleaned => measure(cleaned, minArea, motionRatio))

    def reset(): Unit =
      // Build the replacement BEFORE releasing the old model: if the native factory throws, the
      // detector is left holding its previous (still valid) model rather than a spent handle. The old
      // model is released the moment the new one exists, so no two models are ever live together.
      val fresh = Managed(rebuild())
      subtractor.release()
      subtractor = fresh

    override def close(): Unit = subtractor.release()
