package scalacv.vision

import scalacv.*

/** A detected loop closure: the earlier keyframe this frame revisits, how many features matched, and a score
  * (matched features as a fraction of the current frame's).
  */
final case class LoopClosure(keyframe: Int, matches: Int, score: Double)

/** Loop-closure detection — recognising a place the camera has already been.
  *
  * This is the piece that turns drifting [[Odometry]] into something map-like: keep a keyframe's ORB
  * [[Features]] as you go, and when a new frame matches an *old* keyframe strongly, you have closed a loop —
  * the signal a SLAM back end uses to correct accumulated drift. (Doing that correction — re-optimising the
  * pose graph — is the back end itself, beyond OpenCV; this detects the opportunity.)
  *
  * It is appearance-based brute-force matching against every stored keyframe, which is fine for hundreds of
  * keyframes; a city-scale system would swap in a bag-of-words index, but the contract would be the same.
  *
  * Stateful and **caller-owned** — it holds a descriptor set per keyframe, so [[close]] it. Not thread-safe.
  *
  * ==Bounding memory==
  *
  * Each keyframe owns a native ORB descriptor Mat, so an unbounded run accumulates native memory. Pass
  * `maxKeyframes` to cap the number kept **live**: once exceeded, the oldest keyframes are evicted and their
  * descriptors freed. A bounded deque stores (stable ID, descriptors), without permanent tombstones. A
  * [[LoopClosure.keyframe]] ID is never reassigned during a run; evicted IDs are no longer searchable. The
  * default is unbounded, preserving the original behaviour; a bounded detector trades old-place recall for a
  * fixed memory ceiling.
  */
final class LoopDetector private (
    maxFeatures: Int,
    minMatches: Int,
    recentExclusion: Int,
    maxKeyframes: Int
) extends AutoCloseable:

  private val keyframes = scala.collection.mutable.ArrayDeque.empty[(Int, Descriptors)]
  private var nextId = 0

  /** Stores a keyframe with a stable monotonic ID. Eviction does not renumber surviving IDs. After close,
    * this reusable store starts again at ID zero, as before.
    */
  def addKeyframe(image: Image): Int =
    checkId()
    store(Features.detect(image, maxFeatures))

  private def checkId(): Unit =
    require(nextId < Int.MaxValue, "keyframe IDs exhausted; close/reset the detector")

  private def store(current: Descriptors): Int =
    val id = nextId
    try keyframes.append((id, current))
    catch
      case e: Throwable =>
        current.close()
        throw e
    nextId += 1
    while keyframes.size > maxKeyframes do keyframes.removeHead()._2.close()
    id

  /** Matches without storing the image, ignoring the most recent `recentExclusion` IDs. */
  def detect(image: Image): Option[LoopClosure] =
    val current = Features.detect(image, maxFeatures)
    try score(current)
    finally current.close()

  private def score(current: Descriptors): Option[LoopClosure] =
    if current.isEmpty then None
    else
      var bestIndex = -1
      var bestMatches = 0
      keyframes.iterator
        .takeWhile(_._1 < nextId - recentExclusion)
        .foreach: (id, kf) =>
          val count = Features.matches(current, kf).size
          if count > bestMatches then
            bestMatches = count
            bestIndex = id
      Option.when(bestMatches >= minMatches)(
        LoopClosure(bestIndex, bestMatches, bestMatches.toDouble / math.max(1, current.size))
      )

  /** Detects then records a keyframe, extracting its owned descriptors only once. */
  def process(image: Image): Option[LoopClosure] =
    checkId()
    val current = Features.detect(image, maxFeatures)
    val loop = try score(current)
    catch
      case e: Throwable =>
        current.close()
        throw e
    store(current): Unit
    loop

  /** Number of live keyframes; both descriptor and heap-entry storage are bounded by the cap. */
  def keyframeCount: Int = keyframes.size

  /** Releases the store and resets IDs. Idempotent; the detector remains reusable. */
  def close(): Unit =
    try keyframes.foreach(_._2.close())
    finally
      keyframes.clear()
      nextId = 0

object LoopDetector:

  /** @param maxFeatures
    *   ORB features per keyframe.
    * @param minMatches
    *   how many feature matches count as the same place.
    * @param recentExclusion
    *   how many of the most recent keyframes to ignore (they are always similar to now).
    * @param maxKeyframes
    *   the most keyframes to keep live before evicting the oldest and freeing their descriptors. Defaults to
    *   unbounded (the original behaviour); set it to cap native memory over a long run.
    */
  def apply(
      maxFeatures: Int = 500,
      minMatches: Int = 20,
      recentExclusion: Int = 5,
      maxKeyframes: Int = Int.MaxValue
  ): LoopDetector =
    require(maxFeatures > 0, s"maxFeatures must be positive, got $maxFeatures")
    require(minMatches > 0, s"minMatches must be positive, got $minMatches")
    require(recentExclusion >= 0, s"recentExclusion cannot be negative, got $recentExclusion")
    require(maxKeyframes > 0, s"maxKeyframes must be positive, got $maxKeyframes")
    new LoopDetector(maxFeatures, minMatches, recentExclusion, maxKeyframes)
