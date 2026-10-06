package scalacv.vision

import org.opencv.core.Rect as CvRect
import org.opencv.video.Tracker as CvTracker

import scalacv.*

/* Native single-object tracking: model-free, appearance-based, follows one box across frames without
 * re-detecting. The motion model ([[Kalman]]) and the many-object tracker built on it ([[ObjectTracker]])
 * live in their own files.
 */

/** Which single-object tracking algorithm to run. All three ship in this OpenCV build.
  *
  *   - [[TrackerKind.Csrt]] — the accuracy pick: discriminative correlation filter with channel/spatial
  *     reliability. Slower, but it handles scale change and partial occlusion well.
  *   - [[Kcf]] — the speed pick: kernelised correlation filter. Fast and steady, but it does not adapt its
  *     box to scale.
  *   - [[Mil]] — multiple-instance learning. Robust to small appearance changes; no failure detection.
  */
enum TrackerKind:
  case Csrt, Kcf, Mil

/** A single-object tracker: told where an object is in one frame ([[init]]), it finds it in the next
  * ([[update]]) without re-detecting. This is *model-free* tracking — it learns the object's appearance from
  * the box you give it, so it works on anything, not just a class a detector knows.
  *
  * Owns a native tracker, so it is **caller-owned** — [[close]] it (or use `Using`). A tracker is stateful
  * and single-object; for many objects that come and go, use [[ObjectTracker]] instead.
  *
  * {{{
  * Tracker.create(TrackerKind.Csrt).foreach: tracker =>
  *   Using.resource(tracker): t =>
  *     t.init(firstFrame, box)
  *     for frame <- frames do t.update(frame).foreach(b => frame.drawRect(b).write(...))
  * }}}
  */
final class Tracker private (private val handle: Managed[CvTracker]) extends AutoCloseable:

  private var started = false

  /** Starts tracking the object inside `box` in `image`. May be called again to re-seed on a fresh box. */
  def init(image: Image, box: Rect): Unit =
    Cv.orThrow("Tracker.init")(handle.get.init(image.mat, box.toCv))
    started = true

  /** Locates the object in `image`. `None` when the tracker has lost it (CSRT and KCF report this; MIL always
    * returns a box). Must be preceded by [[init]].
    */
  def update(image: Image): Option[Rect] =
    require(started, "call Tracker.init before update")
    val out = CvRect()
    if Cv.orThrow("Tracker.update")(handle.get.update(image.mat, out)) then Some(Rect.from(out)) else None

  def close(): Unit = handle.release()

object Tracker:

  private given Releasable[CvTracker] = Releasable.nativeHandle

  /** Builds a tracker of the given kind; `Left` when this OpenCV build lacks the algorithm. Free the tracker
    * when done.
    */
  def create(kind: TrackerKind): Either[CvError, Tracker] =
    val native: CvTracker = kind match
      case TrackerKind.Csrt => org.opencv.tracking.TrackerCSRT.create()
      case TrackerKind.Kcf => org.opencv.tracking.TrackerKCF.create()
      case TrackerKind.Mil => org.opencv.video.TrackerMIL.create()
    // A build without a given algorithm returns null here; wrapping it in Managed would surface later as an
    // opaque "already released" at the first `init`. That is an environment failure, not a programmer error,
    // so it travels in the Either like every other native-resource factory (FaceDetect.create,
    // Cascades.load, Dnn.fromOnnx).
    if native == null then
      Left(
        CvError.NativeCall(
          s"creating a $kind tracker",
          IllegalStateException(
            s"OpenCV returned no $kind tracker — this build may not include that algorithm"
          )
        )
      )
    else Right(new Tracker(Managed(native)))
