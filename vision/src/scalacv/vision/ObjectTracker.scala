package scalacv.vision

import scala.collection.mutable

import scalacv.*

/* Tracking-by-detection: turns a per-frame stream of detections into tracks with stable identities (the
 * "SORT-lite" pattern), one [[Kalman]] filter per live track. The single-object appearance tracker lives in
 * Tracker.scala, the filter itself in Kalman.scala.
 */

/** One tracked object as reported by [[ObjectTracker.update]]: a stable [[id]] that persists across frames,
  * the current [[box]], and how long the track has lived.
  */
final case class ObjectTrack(id: Int, box: Rect, hits: Int, age: Int)

/** Tracking-by-detection: turns a per-frame stream of *detections* (from any detector — faces, motion boxes,
  * a DNN) into *tracks* with stable identities. This is the "SORT-lite" pattern — the piece that lets you say
  * "person #3" frame after frame, or count how many distinct objects have passed.
  *
  * Each frame it [[ObjectTracker.update]]s: every live track is advanced by its own [[Kalman]] filter,
  * detections are matched to tracks by bounding-box overlap (IoU, greedily best-first), matched tracks are
  * corrected toward their detection, unmatched detections spawn new tracks, and tracks unseen for `maxAge`
  * frames are retired. Stateful and caller-owned — [[close]] it to free the per-track filters.
  *
  * It is detector-agnostic by design: it never looks at the image, only at the boxes, so it composes with
  * whatever produced them.
  *
  * Build one with [[ObjectTracker.create]] and [[close]] it when done, the same shape as [[Tracker.create]]
  * and [[Kalman.point]]. Like those, it is **stateful and not safe to share across threads** — [[update]]
  * mutates the live-track buffer — so keep the detect-and-track loop on one thread, or serialise access.
  */
final class ObjectTracker private (
    iouThreshold: Double,
    maxAge: Int,
    minHits: Int
) extends AutoCloseable:

  private final class Trk(
      val id: Int,
      val kalman: Kalman,
      var size: (Int, Int),
      var box: Rect,
      var hits: Int,
      var age: Int,
      var timeSinceUpdate: Int
  )

  private val tracks = mutable.ArrayBuffer.empty[Trk]
  private var nextId = 0
  private var total = 0
  // No Managed handle wraps this class (unlike Tracker and Kalman, whose handle.get throws after release),
  // so the closed state needs its own flag: without it, update after close would happily spawn tracks and
  // allocate fresh native KalmanFilters on a dead object — a native leak, not a crash.
  private var closed = false

  /** How many distinct objects have ever been tracked — a running unique count. */
  def count: Int = total

  /** Advances every track, associates `detections` to them, and returns the tracks confirmed this frame (seen
    * at least `minHits` times and matched to a detection this frame), each with its stable id.
    *
    * @throws IllegalStateException
    *   if this tracker has been closed.
    */
  def update(detections: Seq[Rect]): Seq[ObjectTrack] =
    if closed then
      throw IllegalStateException(
        "this ObjectTracker has already been closed — updating it now would spawn tracks and allocate " +
          "native KalmanFilters on a dead object (a native leak). Create a fresh tracker instead."
      )
    // 1. Predict every existing track forward one step.
    tracks.foreach: t =>
      val c = t.kalman.predict()
      t.box = ObjectTracker.centeredRect(c, t.size)
      t.age += 1
      t.timeSinceUpdate += 1

    // 2. Greedily associate detections to tracks by IoU, best overlap first.
    val matched = greedyMatch(detections)
    val matchedDets = matched.values.toSet

    // 3. Correct matched tracks toward their detection.
    matched.foreach: (ti, di) =>
      val det = detections(di)
      tracks(ti).kalman.correct(ObjectTracker.center(det)): Unit
      tracks(ti).size = (det.width, det.height)
      tracks(ti).box = det
      tracks(ti).hits += 1
      tracks(ti).timeSinceUpdate = 0

    // 4. Spawn a track for every detection that matched nothing.
    detections.indices
      .filterNot(matchedDets)
      .foreach: di =>
        val det = detections(di)
        tracks += Trk(nextId, Kalman.point(ObjectTracker.center(det)), (det.width, det.height), det, 1, 0, 0)
        nextId += 1
        total += 1

    // 5. Retire tracks unseen for too long, freeing their filters.
    val dead = tracks.filter(_.timeSinceUpdate > maxAge)
    dead.foreach(_.kalman.close())
    tracks --= dead

    // 6. Report the confirmed, freshly-seen tracks.
    tracks.iterator
      .filter(t => t.hits >= minHits && t.timeSinceUpdate == 0)
      .map(t => ObjectTrack(t.id, t.box, t.hits, t.age))
      .toSeq

  /** Greedy IoU association: track index → detection index, each used at most once. */
  private def greedyMatch(detections: Seq[Rect]): Map[Int, Int] =
    val candidates =
      for
        ti <- tracks.indices
        di <- detections.indices
        iou = ObjectTracker.iou(tracks(ti).box, detections(di))
        if iou >= iouThreshold
      yield (iou, ti, di)
    val usedTracks = mutable.Set.empty[Int]
    val usedDets = mutable.Set.empty[Int]
    val result = mutable.Map.empty[Int, Int]
    candidates
      .sortBy(-_._1)
      .foreach: (_, ti, di) =>
        if !usedTracks(ti) && !usedDets(di) then
          result(ti) = di
          usedTracks += ti
          usedDets += di
    result.toMap

  /** Frees every per-track Kalman filter. Idempotent: on a second call the buffer is already empty, so there
    * is nothing left to free.
    */
  def close(): Unit =
    tracks.foreach(_.kalman.close())
    tracks.clear()
    closed = true

object ObjectTracker:

  /** Builds a tracking-by-detection tracker. Free it with [[ObjectTracker.close]] (or `Using`) so the
    * per-track [[Kalman]] filters are released.
    *
    * @param iouThreshold
    *   the minimum bounding-box overlap for a detection to be associated with an existing track.
    * @param maxAge
    *   how many frames a track may go unseen before it is retired.
    * @param minHits
    *   how many times a track must be matched before it is reported as confirmed.
    */
  def create(iouThreshold: Double = 0.3, maxAge: Int = 5, minHits: Int = 1): ObjectTracker =
    require(iouThreshold >= 0 && iouThreshold <= 1, s"iouThreshold must be in [0, 1], got $iouThreshold")
    require(maxAge >= 0, s"maxAge cannot be negative, got $maxAge")
    // minHits 0 or less would confirm a track on the very frame it spawns — before any hit — which defeats
    // the confirmation threshold the parameter exists to be. That is a caller mistake, so reject it here
    // rather than let `hits >= minHits` quietly pass for every fresh track.
    require(minHits >= 1, s"minHits must be at least 1, got $minHits")
    new ObjectTracker(iouThreshold, maxAge, minHits)

  private def center(r: Rect): Point = Point(r.x + r.width / 2.0, r.y + r.height / 2.0)

  private def centeredRect(c: Point, size: (Int, Int)): Rect =
    Rect(math.round(c.x - size._1 / 2.0).toInt, math.round(c.y - size._2 / 2.0).toInt, size._1, size._2)

  /** Intersection-over-union of two boxes: 0 when disjoint, 1 when identical. */
  private[scalacv] def iou(a: Rect, b: Rect): Double =
    val x1 = math.max(a.x, b.x)
    val y1 = math.max(a.y, b.y)
    val x2 = math.min(a.x + a.width, b.x + b.width)
    val y2 = math.min(a.y + a.height, b.y + b.height)
    val inter = math.max(0, x2 - x1).toDouble * math.max(0, y2 - y1)
    val union = a.area.toDouble + b.area - inter
    if union <= 0 then 0.0 else inter / union

/** The track overlay on [[Image]] — an extension method so it lives beside the tracker types.
  * `import scalacv.*` gives `image.drawTracks(tracks)`.
  */
extension (img: Image)

  /** Annotates [[ObjectTrack]]s: a box and an `#id` label per track — the one-call "show me what the tracker
    * is following". Consumes this image and returns the annotated one.
    */
  def drawTracks(tracks: Seq[ObjectTrack], color: Scalar = Scalar.Green): Image =
    img.paint: m =>
      tracks.foreach: t =>
        m.drawRect(t.box, color)
        m.drawText(s"#${t.id}", Point(t.box.x.toDouble, (t.box.y - 5).toDouble), color, scale = 0.5)
