package scalacv.vision

import scalacv.*

/** A running visual-odometry pipeline: feed frames in order, get the camera's motion each step.
  *
  * This is the front-end **loop** of visual SLAM, composing the per-frame primitives: it keeps the previous
  * frame and its tracked points, follows them into each new frame with [[OpticalFlow]], and estimates the
  * step's motion with [[VisualOdometry]]. Chaining the steps is dead-reckoning — it drifts, and correcting
  * that drift (loop closure, global optimisation) is the SLAM back end, beyond OpenCV.
  *
  * Monocular, so each step's translation is a **unit direction** (scale is unobservable from one camera). The
  * pipeline retains a frame's worth of native memory between calls, so it is `AutoCloseable` — [[close]] it.
  *
  * {{{
  * val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240))
  * try camera.foreach(frame => odometry.update(frame).foreach(step => track(step)))
  * finally odometry.close()
  * }}}
  *
  * Not thread-safe: feed one frame at a time.
  */
final class Odometry private (intrinsics: Intrinsics) extends AutoCloseable:

  private var previous: Image | Null = null
  private var previousPoints: Seq[Point] = Seq.empty
  private var frames = 0

  /** Feeds the next frame and returns the camera's motion since the previous one. `None` on the very first
    * frame (it becomes the reference) and whenever too few points survive to estimate a motion.
    */
  def update(frame: Image): Option[CameraMotion] =
    val step =
      previous match
        case null =>
          // Build both new fields before touching either, so a throw leaves the (empty) state consistent.
          val (copy, points) = snapshot(frame)
          previous = copy
          previousPoints = points
          None
        case prev =>
          val tracked = OpticalFlow.track(prev, frame, previousPoints).filter(_.found)
          val motion =
            if tracked.size >= 8 then
              VisualOdometry.estimate(tracked.map(_.from), tracked.map(_.to), intrinsics)
            else None
          // Compute the new baseline first (prev still valid, no field mutated); only then swap it in as a
          // pair, so a throw in copy/goodFeatures cannot strand `previous` on a closed handle or leave
          // `previousPoints` stale against a fresh frame.
          val (copy, points) = snapshot(frame)
          prev.close()
          previous = copy
          previousPoints = points
          motion
    // Counted only here, after every throwing call has returned: a frame whose update fails part-way was
    // never processed, and inflating the counter would make `framesProcessed` lie about the pipeline's
    // throughput.
    frames += 1
    step

  /** A fresh baseline: an owned copy of `frame` and its good features. Releases the copy if feature detection
    * throws, so a failure allocates nothing.
    */
  private def snapshot(frame: Image): (Image, Seq[Point]) =
    val copy = frame.copy
    val points =
      try OpticalFlow.goodFeatures(frame)
      catch
        case e: Throwable =>
          copy.close()
          throw e
    (copy, points)

  /** How many frames have been successfully processed so far — an update that throws is not counted. */
  def framesProcessed: Int = frames

  /** Releases the retained frame. Idempotent. */
  def close(): Unit =
    val p = previous
    previous = null
    previousPoints = Seq.empty
    if p != null then p.close()

object Odometry:

  /** A monocular odometry pipeline for a camera with the given pinhole [[Intrinsics]]. */
  def monocular(intrinsics: Intrinsics): Odometry = new Odometry(intrinsics)
