package scalacv.vision

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.video.KalmanFilter

import scalacv.*

/* The constant-velocity Kalman filter over a 2D point — the motion model that smooths a jittery detection
 * and coasts through dropped frames, used per track by [[ObjectTracker]] and usable on its own.
 */

/** A constant-velocity Kalman filter over a 2D point — the smoother behind [[ObjectTracker]], useful on its
  * own to steady a jittery detection or to coast through a frame where the measurement dropped out.
  *
  * The state is position and velocity `(x, y, vx, vy)`; you [[predict]] the next position, then [[correct]]
  * it with a fresh measurement (or skip the correction if you have none this frame and trust the model). Owns
  * a native filter — **caller-owned**, [[close]] it. Stateful and **not safe to share across threads**; give
  * each thread its own, as with the detectors.
  */
final class Kalman private (private val handle: Managed[KalmanFilter]) extends AutoCloseable:

  /** Advances the model one step and returns the predicted position. */
  def predict(): Point =
    Managed.use(handle.get.predict())(state => Point(state.get(0, 0)(0), state.get(1, 0)(0)))

  /** Folds `measurement` into the model and returns the corrected (smoothed) position. */
  def correct(measurement: Point): Point =
    Managed.use(Mat(2, 1, CvType.CV_32F)): z =>
      z.put(0, 0, measurement.x, measurement.y)
      Managed.use(handle.get.correct(z))(state => Point(state.get(0, 0)(0), state.get(1, 0)(0)))

  def close(): Unit = handle.release()

object Kalman:

  private given Releasable[KalmanFilter] = Releasable.nativeHandle

  /** A filter tracking `initial`, ready to [[Kalman.predict]]. `processNoise` is how much the model is
    * allowed to drift (larger ⇒ more responsive, more jitter); `measurementNoise` is how much the
    * measurements are trusted (larger ⇒ smoother, laggier).
    */
  def point(initial: Point, processNoise: Double = 1e-2, measurementNoise: Double = 1e-1): Kalman =
    // Owned from construction: if any of the get_*/put setup below throws, the filter is freed rather than
    // stranded (it holds no public release(), so a leak would need the delete bridge to reclaim).
    val handle = Managed(KalmanFilter(4, 2, 0))
    try
      val kf = handle.get
      // Constant-velocity transition: x += vx, y += vy each step (dt = 1).
      Managed.use(kf.get_transitionMatrix()): t =>
        t.put(0, 0, 1.0, 0, 1.0, 0, 0, 1.0, 0, 1.0, 0, 0, 1.0, 0, 0, 0, 0, 1.0): Unit
      // Measurement observes position only: the 2×4 identity picks x and y out of the state.
      Managed.use(kf.get_measurementMatrix())(Core.setIdentity(_))
      Managed.use(kf.get_processNoiseCov())(Core.setIdentity(_, Scalar(processNoise).toCv))
      Managed.use(kf.get_measurementNoiseCov())(Core.setIdentity(_, Scalar(measurementNoise).toCv))
      Managed.use(kf.get_errorCovPost())(Core.setIdentity(_, Scalar(1.0).toCv))
      Managed.use(kf.get_statePost())(_.put(0, 0, initial.x, initial.y, 0.0, 0.0): Unit)
      new Kalman(handle)
    catch
      case e: Throwable =>
        handle.release()
        throw e
