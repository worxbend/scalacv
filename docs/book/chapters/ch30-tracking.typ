#import "../lib/book.typ": *

#chapter("Tracking", subtitle: [Turning per-frame detections into objects with identity over time.])

A detector has no memory. That is not a shortcoming to be apologised for --- it is the contract. The
face detector of Chapter 24 is handed one image and answers a question about that image; the motion
detector of Chapter 21 compares a frame against a background model and reports the regions that
differ. Neither is told what came before. Run either over a corridor camera and you get a list of
boxes per frame, twenty-five times a second, with nothing joining them up.

So consider the smallest useful question a corridor camera can be asked: how many people walked
through today. You have the boxes. You cannot count them, because the same person contributes a box
to every frame they are visible in, and a detector that returns two boxes on frame 41 and two boxes
on frame 42 has told you nothing about whether that is two people or four. The boxes on frame 42 are
not even in a dependable order. `Image.contours` hands back regions in whatever order OpenCV walked
the mask, and `MotionDetector`'s `Motion.regions` is sorted largest first, so the two swap places the
moment one person walks nearer the camera than the other. There is no index you can trust and no
field to compare.

Tracking supplies the missing thread. It takes the per-frame boxes and decides which box now is the
same object as which box a moment ago, attaching an identity that persists: not "a person at
(310, 88)" but "person \#3, who was at (290, 86) last frame and has been in view for two seconds".
Three things fall out of that. Identity makes counting possible, because a count is over distinct
objects rather than over sightings. Continuity survives a detector that blinks: a person half-hidden
behind a pillar stops producing detections, and a tracker that models motion carries the identity
across the gap and picks it up on the far side. And a motion model smooths the jitter that makes a
detector's box wobble by several pixels even on a stationary object.

scalacv puts all of this in the `scalacv.vision` package --- `Tracker.scala`, `Kalman.scala` and
`ObjectTracker.scala`, one type per file --- so every type in this
chapter needs `scalacv-vision` on the classpath beside `scalacv` --- the same module as the detectors
of Chapters 24 to 29, and as the `MotionDetector` of Chapter 21. It arrives in three layers that
build on each other: a `Kalman` filter that models one moving point, a `Tracker` that follows one
object by its appearance without any detector at all, and an `ObjectTracker` that runs a bank of
Kalman filters to give many detections stable identities. Every one of them owns native memory that
the Java bindings will not free, which is why this chapter's native-memory callouts run longer than
most.

#sect("The Kalman filter, without the matrices")

A Kalman filter is usually introduced as five matrix equations, which is accurate and unhelpful.
The idea underneath is this: you have two independent statements about where an object is, and both
are wrong. The first is a prediction from a model of how things move --- it was here, it was going
that way, so it should now be there. The second is a measurement --- the detector says it is here.
The filter's whole job is to blend them in proportion to how much you trust each, and to keep track
of how uncertain the blend is so that the next blend can be weighted better.

`Kalman.point` builds the specific filter scalacv needs: constant velocity over a 2D point. Its
state has four numbers, position and velocity, `(x, y, vx, vy)`, and its motion model says that each
step adds the velocity to the position with a time step of one frame. The measurement is only the
position, two numbers; velocity is never observed, it is inferred from how the position keeps
changing. That inference is what makes the filter able to coast: after a few frames of a steadily
moving object it has learned `vx` and `vy`, and a prediction with no measurement behind it still
moves.

There are exactly two verbs, and they alternate.

#example("The two calls, and what each returns.")[
```scala
val k = Kalman.point(Point(0, 0))
try
  for i <- 1 to 5 do
    k.predict()                    // advance the model: where it *should* be
    k.correct(Point(i * 10.0, 0))  // fold in the reading: where it *is*
  val next = k.predict()           // one step past the last measurement
  println(f"next x = ${next.x}%.1f")   // past the last reading of 50
finally k.close()
```
]

`predict()` advances the state one step and returns the predicted `Point`; `correct(measurement)`
folds a reading in and returns the corrected --- that is, smoothed --- `Point`. Skipping the
`correct` is legal and is the entire point of having a model: a frame in which the detector found
nothing is a frame in which you call `predict` and trust it. Do that twice and the box keeps gliding
at the velocity the filter last believed in, which is what you want while an object is behind a
pillar and not at all what you want fifty frames after it left the scene. That trade-off is what
`maxAge` settles, below.

Two parameters set the filter's personality, and they pull against each other.

#figure-table("The two noise knobs on `Kalman.point`.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Parameter], [Default], [Larger means],
  [`processNoise`], [`1e-2`], [the model is allowed to drift more, so the filter chases the
    measurements: more responsive, more jitter],
  [`measurementNoise`], [`1e-1`], [the measurements are trusted less, so the filter leans on the
    model: smoother, laggier],
)
]

Both are variances written onto identity covariance matrices, so what matters is which is larger, not
the absolute value. A box that twitches on a standing object wants a higher `measurementNoise`; a box
that lags behind a fast walker wants a higher `processNoise`. Change one at a time on a recorded
clip.

#memory[
  `Kalman` wraps `org.opencv.video.KalmanFilter`, which is one of the 185 native-owning binding
  types with no public `release()`. It is freed through `Releasable.nativeHandle`, the reflective
  bridge onto the binding's private `delete(long)` described in Chapter 5, and it is freed only
  because you `close()` the `Kalman` --- or let `Using.resource` do it.

  This is the type the project measured to make the point. Four thousand `KalmanFilter`s allocated
  and dropped without release came to *54 GB* of resident memory; the same four thousand released
  came to *86 MB*. Roughly six hundred and forty times, for an object the heap reports as a few dozen
  bytes --- and the heap never grows enough to provoke a collection, so nothing reclaims them.
  Chapter 1's flat-heap, rising-RSS graph is this measurement drawn out over time.

  Four thousand is not a contrived number. `ObjectTracker` allocates one filter per track and a track
  is born for every detection that matches nothing, so a busy scene with a noisy detector births
  hundreds an hour.
]

#sidebar("Why the filter's own matrices can be released")[
  `Kalman.point` configures the filter by fetching its internal matrices --- `get_transitionMatrix`,
  `get_measurementMatrix`, `get_processNoiseCov`, `get_measurementNoiseCov`, `get_errorCovPost`,
  `get_statePost` --- writing to each one, and releasing it through `Managed.use`. Read that quickly
  and it looks like a bug: the setup frees the filter's own transition matrix, then goes on to use
  the filter for the rest of its life.

  It is safe because the generated binding does not hand back the member. It hands back a
  refcount-sharing header copy, so `release()` drops the extra reference and the matrix the filter
  holds survives. That is an assumption about OpenCV's Java bindings, not a guarantee in scalacv's
  own code, so it is pinned by a test: five hundred `predict`/`correct` steps along a straight
  diagonal, asserting the filter is still tracking the line at the end. Had a release genuinely
  freed the transition matrix, a run that long would wander off it.

  The construction is failure-safe for the same reason it has to be: `Kalman.point` builds the
  `Managed` first and wraps the setup in a `try`/`catch` that releases and rethrows, because a filter
  that throws halfway through configuration is otherwise stranded --- unreachable and unreleasable,
  with no public `release()` for anyone else to call.
]

#sect("ObjectTracker: the tracking-by-detection loop")

One filter smooths one point. Turning a stream of detections into a set of identities needs a filter
per object plus a rule for deciding which detection belongs to which object, and that is
`ObjectTracker` --- the pattern usually called SORT, here in its plainest form.

Before the right version, the wrong one, because it is the mistake everybody makes once. Detections
arrive as a `Seq[Rect]`, and a `Seq` has indices, and indices look like identities:

```scala
// Wrong. Contour order is however OpenCV walked the mask, not who is who.
val boxes = Using.resource(frame.copy.gray.threshold(128)): mask =>
  mask.contours().map(_.boundingRect)
boxes.zipWithIndex.foreach((box, i) => trailFor(i) += box)
```

Element 0 is whichever region the scan hit first. Two people crossing swap places in that ordering as
soon as one of them starts a row earlier than the other, and a person leaving the scene shifts every
index after them by one. The trails cross over and nothing warns you.

`ObjectTracker` replaces the index with an id it maintains itself. `update` takes this frame's
detections and returns the tracks confirmed this frame, and it does six things in order:

+ *Predict.* Every live track is advanced one step by its own `Kalman`, its `box` is moved to the
  predicted centre keeping its last known size, and its `age` and `timeSinceUpdate` are incremented.
+ *Associate.* Every (track, detection) pair whose intersection-over-union reaches `iouThreshold` is
  a candidate; the candidates are sorted by IoU descending and taken greedily, each track and each
  detection used at most once. This runs against the *predicted* boxes from step 1, which is what
  lets association keep up with a fast-moving object.
+ *Correct.* Each matched track feeds its detection's centre to `correct`, adopts the detection's
  size and box, increments `hits`, and resets `timeSinceUpdate` to zero.
+ *Birth.* Every detection that matched nothing spawns a track with a fresh id, a new `Kalman`
  seeded at its centre, and `hits` of one. The running `count` goes up.
+ *Death.* Every track whose `timeSinceUpdate` now exceeds `maxAge` is retired and its filter
  closed.
+ *Report.* The tracks with at least `minHits` hits *and* a `timeSinceUpdate` of zero are returned
  as `ObjectTrack` values.

Ids come from a counter that only ever increases, so an id is never reused and `count` is the number
of distinct objects the tracker has ever seen. It never looks at the image: `update` takes `Rect`s
and returns `ObjectTrack`s, so it composes with any detector in the book --- motion regions from
Chapter 21, faces from Chapter 24, DNN boxes from Chapter 26, contour bounding boxes from
Chapter 12.

#figure-table("What an `ObjectTrack` carries.")[
#tbl(
  columns: (auto, 1fr),
  [Field], [Meaning],
  [`id`], [the stable identity: unique, monotonically increasing, never recycled],
  [`box`], [the current bounding box],
  [`hits`], [how many frames this track has been matched to a detection],
  [`age`], [how many frames this track has existed],
)
]

One consequence of step 6 is worth stating plainly, because the smoothing story invites the opposite
assumption: only tracks with `timeSinceUpdate == 0` are reported, and those are exactly the tracks
that took their detection's box in step 3, so the `box` you get back is always the raw detection. The
Kalman prediction drives association and coasting; it does not soften what you draw. For a smoothed
box on screen, smooth it downstream of `update` yourself.

#example("Two objects, three frames, two stable ids.")[
```scala
val tracker = ObjectTracker.create(iouThreshold = 0.3, maxAge = 5)
try
  val frames = Seq(
    Seq(Rect(10, 10, 20, 20), Rect(120, 120, 20, 20)),
    Seq(Rect(14, 10, 20, 20), Rect(124, 120, 20, 20)),
    Seq(Rect(19, 11, 20, 20), Rect(129, 121, 20, 20))
  )
  frames.foreach(dets => println(tracker.update(dets).map(_.id).sorted))
  println(s"distinct objects: ${tracker.count}")
finally tracker.close()
```
]

```text
List(0, 1)
List(0, 1)
List(0, 1)
distinct objects: 2
```

Ids start at zero and are handed out in the order detections arrive on the frame that births them,
so the leftmost box in this clip is `\#0` for as long as it is tracked. Nothing about that ordering
is guaranteed on a real clip --- what is guaranteed is that once an id is attached it stays attached
until the track dies, and that the id is never handed to a second object.

#sect("Tuning, and where it goes wrong")

`ObjectTracker.create` takes three numbers and they are the whole configuration. Two of them are
checked: `iouThreshold` must lie in `[0, 1]` and `maxAge` must not be negative, both enforced by
`require`, both programming errors rather than expected failures, so both throw rather than
returning a `Left`.

#figure-table("The three knobs, their defaults, and which way to turn them.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Parameter], [Default], [Turn it],
  [`iouThreshold`], [`0.3`], [*down* when boxes jump between frames --- a fast object at a low frame
    rate can leave its own predicted box entirely, and no overlap means no match; *up* when tracks
    latch onto the wrong neighbour in a crowd],
  [`maxAge`], [`5`], [*up* to bridge occlusions, at the cost of a ghost track coasting across the
    frame after the object has gone; *down* for a clean scene where a lost object is genuinely gone],
  [`minHits`], [`1`], [*up* when the detector produces one-frame false positives, at the cost of
    reporting every genuine track a frame or two late],
)
]

`maxAge` has an off-by-one worth knowing, because the difference between five and six missed frames
is the difference between recovering an identity and minting a new one. A track is retired when
`timeSinceUpdate` is strictly greater than `maxAge`, and `timeSinceUpdate` is incremented before the
test, so the default `maxAge = 5` survives five consecutive unmatched frames and dies on the sixth.
`maxAge = 0` retires a track the first time it is missed.

`minHits` introduces a deliberate confirmation delay. A brand-new track predicts, associates and
accumulates hits from its first frame, but it is not *reported* until it has been matched `minHits`
times: with `minHits = 2` the first `update` that sees a new object returns nothing for it and the
second returns it. `count`, by contrast, counts births rather than confirmations, so it rises on the
first frame regardless --- decide which of the two you mean before showing a figure to a user.

The honest failure is identity switching. Greedy IoU association has no notion of appearance and no
notion of which pairing is best *overall*: it takes the highest-overlap pair, locks both sides, and
repeats. When two people cross, their boxes overlap each other as much as they overlap their own
predictions, and for a frame or two the highest-overlap pair is the wrong one. From then on the ids
are swapped, permanently and silently --- `count` stays correct while every per-id trail is nonsense.
A Hungarian assignment over the full cost matrix would pick the better global pairing more often; it
would not fix the case where the boxes genuinely coincide, and nothing that looks only at boxes can.
If it matters *which* person went into which room, you need appearance: a `Tracker` per object,
embeddings from Chapter 25, or a camera angle where people do not overlap.

#warning[
  A retired track that reappears is a new object with a new id, and `count` goes up. A tracker
  pointed at a doorway where people pause out of the detector's sight will over-count, and no
  tuning of `maxAge` removes the problem --- it only moves the threshold at which it starts.
]

#sect("Counting across a line")

The commonest thing built on a tracker is a counter: how many crossed this line, and in which
direction. It is also where a first attempt reliably produces a comically wrong number. The naive
version tests proximity:

```scala
// Wrong: counts once per frame spent near the line, not once per crossing.
if math.abs(centreY - lineY) < 8 then crossings += 1
```

Somebody walking briskly gives you four. Somebody who stops on the line to check their phone gives
you sixty. A smaller distance is not the fix --- it makes it possible to step over the line between
two frames and be missed entirely. The fix is to ask which *side* of the line each identity is on and
count only transitions.

That still leaves an object hovering on the boundary flipping sides on detector jitter alone, so the
side test gets a dead band: a strip `band` pixels either side of the line in which the counter forms
no opinion at all. An object must leave the band on one side and arrive outside it on the other
before anything is counted. This is hysteresis, the trick a thermostat uses to stop itself
chattering, and it is the only interesting line in the class.

#example("A line-crossing counter with hysteresis, keyed by track id.")[
```scala
/** Counts tracks crossing the horizontal line at `y`, once each, per direction. */
final class LineCounter(y: Int, band: Int = 8):

  // The side each id was last confidently on: -1 above the band, +1 below it.
  private val side = scala.collection.mutable.Map.empty[Int, Int]
  private var down = 0
  private var up = 0

  def observe(tracks: Seq[ObjectTrack]): Unit =
    tracks.foreach: t =>
      val centreY = t.box.y + t.box.height / 2.0
      val now =
        if centreY < y - band then -1
        else if centreY > y + band then 1
        else 0 // inside the band: no opinion, and no update
      if now != 0 then
        side.get(t.id) match
          case Some(before) if before != now => if now > 0 then down += 1 else up += 1
          case _ => ()
        side(t.id) = now

  def counts: (Int, Int) = (down, up)
```
]

`observe` is called with whatever `update` returned, so it sees confirmed tracks only and inherits
`minHits`' protection against one-frame phantoms. The band should be wider than the detector's
jitter and narrower than the distance an object covers between frames: eight pixels is a fair starting
point on a 640×480 corridor camera, and anything busier wants it measured rather than guessed --- log
`centreY` per id for a minute and look at the spread while an object is stationary.

#note[
  `side` grows by one entry per distinct id, and ids are never reused. That is heap, not native
  memory, so it will not take the process down the way a leaked filter will --- but it is unbounded.
  Drop ids that have not appeared for a while, or rebuild the counter on whatever boundary your
  service already has.
]

#sect("Following one object without a detector")

`ObjectTracker` needs detections. When you have none --- a user has drawn a box around something the
system has no model for --- the tool is `Tracker`, which is *model-free*: it learns the object's
appearance from the box you give it and finds that patch in the next frame. `Tracker.create(kind)`
builds one, `init(image, box)` seeds it, and `update(image)` returns `Option[Rect]` per frame. `init`
may be called again to re-seed on a fresh box, which is how you recover after a loss and how the
"detect occasionally, track in between" pipeline is built.

#figure-table("The three algorithms in `TrackerKind`.")[
#tbl(
  columns: (auto, 1fr, auto),
  [`TrackerKind`], [Character], [Reports loss],
  [`Csrt`], [the accuracy pick: handles scale change and partial occlusion, slowest of the three],
    [yes],
  [`Kcf`], [the speed pick: fast and steady, but its box never changes size], [yes],
  [`Mil`], [robust to small appearance changes, with no failure detection at all], [no],
)
]

The "reports loss" column is the one that bites. CSRT and KCF return `None` when they have lost the
object; MIL always returns a box, so a MIL tracker that has drifted onto the wallpaper reports the
wallpaper with the same enthusiasm it reported the object. If you use MIL, add your own sanity check
--- a box that stops moving, or grows implausibly, or leaves the frame.

The idiom that makes a `Tracker` worth the trouble is *detect occasionally, track in between*: a
detector runs only when there is nothing being followed, and the tracker carries the box the rest of
the time. It costs one detector call per acquisition instead of one per frame, and it re-seeds itself
the moment `update` returns `None`.

#example("Detect once, track until lost, then detect again.")[
```scala
import scala.util.Using
import scalacv.*

OpenCv.load()

Using.resource(Tracker.create(TrackerKind.Csrt)): tracker =>
  val motion = MotionDetector.backgroundSubtraction(minArea = 300)
  var seeded = false
  var n = 0
  try
    Camera.usingFile("clip.mp4"): cam =>
      cam.foreach(): frame =>
        n += 1
        val box =
          if seeded then tracker.update(frame)        // borrows the frame, never consumes it
          else
            motion.detect(frame).largest.map: seed => // the biggest moving region re-seeds
              tracker.init(frame, seed)
              seeded = true
              seed
        box match
          case Some(b) => frame.copy.drawRect(b).write(f"out/track$n%05d.png")
          case None => seeded = false                 // lost: back to the detector next frame
  finally motion.close()
```
]

`Motion.largest` is `regions.headOption`, and `regions` is sorted largest first, so the seed is the
biggest thing that moved --- crude, but it is the whole point of the pattern that the seed only has
to be right once. Both `tracker.update` and `motion.detect` *borrow* `frame`; the `.copy` before
`drawRect` is what keeps the loop's own image intact for `foreach` to close.

`update` before `init` throws: the class holds a `started` flag and `require`s it, turning a
sequencing mistake into a readable Scala exception rather than undefined behaviour on the far side of
JNI. `Tracker.create` guards the other end --- an OpenCV build without a given algorithm returns
`null` from `TrackerCSRT.create()` and friends instead of failing, so `create` tests for it and
throws a `CvError.NativeCall` naming the kind, rather than wrapping the `null` in a `Managed` and
surfacing it as an opaque "already released" at the first `init`. It throws rather than returning a
`Left` because a missing algorithm is a broken build, not a runtime condition to branch on.

#memory[
  All three algorithms carry a learned appearance model --- CSRT's is the largest --- and
  `org.opencv.video.Tracker` has no public `release()` either. `Tracker` is `AutoCloseable` and
  releases through the same `Releasable.nativeHandle` bridge; `Using.resource` is the idiom. A
  tracker per object in a crowded scene, rebuilt whenever an object is re-acquired, is the shape
  that leaks fastest.

  `ObjectTracker` is `AutoCloseable` at one remove: its `close()` walks every live track and closes
  that track's filter. Retired tracks were already freed by step 5 of `update`, so a scene where
  objects come and go stays flat --- but the tracks alive when you stop are freed only by `close()`.
  All three types are stateful and none is safe to share across threads, since `update` mutates the
  live-track buffer. Keep the detect-and-track loop on one thread, as Chapter 36 will insist for
  every detector in this part.
]

#sect("Detector, tracker, overlay")

Put the pieces together and the corridor counter is one expression per frame: detect, track, count,
draw. `drawTracks` is the one-call overlay --- a box and a `#`-prefixed id label per track, in
`Scalar.Green` unless you say otherwise --- and like every drawing verb in Chapter 14 it consumes the
image it is called on, so the frame is copied first: `Camera.foreach` owns each frame and closes it
when the block returns. `LineCounter` is the class from the previous listing, unchanged.

#example("Motion boxes, stable ids, a crossing count, and trails.")[
```scala
import scala.collection.mutable
import scalacv.*

OpenCv.load()

val motion = MotionDetector.backgroundSubtraction(minArea = 300)
val tracker = ObjectTracker.create(iouThreshold = 0.2, maxAge = 10)
val counter = LineCounter(y = 240)
val trails = mutable.Map.empty[Int, mutable.ArrayBuffer[Point]]
var frameNo = 0

try
  Camera.usingFile("corridor.mp4"): cam =>
    cam.foreach(): frame =>
      val boxes = motion.detect(frame).regions        // detect: what moved, where
      val tracks = tracker.update(boxes)              // track: stitch into identities
      counter.observe(tracks)                         // count: transitions only

      val annotated = frame.copy                      // the loop owns `frame`
      tracks.foreach: t =>
        val centre = Point(t.box.x + t.box.width / 2.0, t.box.y + t.box.height / 2.0)
        val trail = trails.getOrElseUpdate(t.id, mutable.ArrayBuffer.empty)
        trail += centre
        if trail.size > 30 then trail.remove(0, 1)   // remove(idx, count) returns Unit
        trail.sliding(2).foreach:
          case Seq(a, b) => annotated.mat.drawLine(a, b, Scalar.Red, Thickness.Stroke(2))
          case _ => ()
      frameNo += 1
      annotated
        .drawTracks(tracks)
        .drawText(s"in ${counter.counts._1}  out ${counter.counts._2}", Point(10, 30))
        .write(f"out/frame$frameNo%05d.png")
finally
  motion.close()
  tracker.close()

println(s"${tracker.count} distinct objects passed")
```
]

Two ownership points, both from Chapter 4's rules and both easy to get wrong here. `frame` is
borrowed by `motion.detect` and by `.copy`, and released by `foreach` when the block returns, which
is why the drawing happens on `annotated`. And `annotated.mat.drawLine` is a borrow: the mid-level
`Mat` extension mutates in place and returns `Unit`, so the trail is drawn without spending the
handle and `drawTracks` is still free to consume it afterwards. The `trails` map is plain heap data
--- `ObjectTrack`, `Rect` and `Point` are ordinary case classes of `Int`s and `Double`s, with nothing
native behind them --- but it grows one entry per id for the same reason `LineCounter`'s map does,
and wants the same eviction. That is also why the final `println` may read `tracker.count` after
`tracker.close()`: the running total is a field on the Scala object, and only the filters were
freed.

Everything in that loop is boxes and numbers. The tracker never sees a pixel, which is what makes it
swappable: replace `motion.detect(frame).regions` with `frame.faces(detector).map(_.box)` and the
same loop counts faces, with the same ids, the same crossings and the same trails.

#sect("Where this goes next")

Tracking answers "which one is which" in pixel coordinates, and every number in this chapter --- the
IoU gate, the band width, the trail --- is measured in pixels. That is enough to count people through
a doorway and not enough to say how far away one of them stood or how fast they were walking, because
a pixel spans a different distance at every depth and the lens has been bending the geometry all
along. Chapter 31, Camera Calibration, works out the intrinsics that turn pixels into rays: what a
chessboard capture session measures, how to undistort a frame, and why every metric claim in the rest
of this part depends on getting it right.
