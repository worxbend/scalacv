#import "../lib/book.typ": *

#chapter("Visual Navigation", subtitle: [Turning pixels into geometry: flow, features, depth, odometry and a map.])

A camera on a moving thing --- a drone, a rover, a phone held out at arm's length --- is a sensor
that measures nothing. It reports brightness. Every quantity a navigation stack wants (how far away
that wall is, how fast I am going, whether I have been in this corridor before) must be inferred from
how brightness moved between one frame and the next, and each inference is a different piece of
geometry with its own failure mode.

The chain has a shape worth holding in your head before you meet the types. Distinctive points are
found and followed. Their motion, run through a camera model, becomes the camera's motion. Two
cameras a known distance apart turn the same points into distance. Chained motion becomes a
trajectory, and the trajectory drifts, because every step's error is added to every step after it. A
map fixes the drift, but only if you can tell you are looking at a piece of the map you already have
--- the recognition problem again, one level up.

That chain is the *front end* of a visual-SLAM system, and it is what this chapter covers, because it
is what OpenCV provides and therefore what scalacv wraps. Everything on the front-end side is one
typed call; everything past it is a decision to take a dependency.

The navigation sources under `vision/src/scalacv/vision/` carry it --- `OpticalFlow`, `Features`, `VisualOdometry`,
`Odometry`, `Depth`, `Navigator`, `Localizer`, `OccupancyGrid` and `LoopDetector` --- so every type in
this chapter needs `com.worxbend::scalacv-vision` on the classpath beside `scalacv`, the same module
as the detectors of Chapters 24 to 29 and the trackers of Chapter 30. The worked example at the end
also plots, so it adds `scalacv-graphs` for `Chart` and `Color`. Add `import scalacv.vision.*`
and `import scalacv.graphs.*` alongside core’s `import scalacv.*`.

Most of those nine own native memory the Java bindings will not free: an ORB detector, a brute-force
matcher, a stereo matcher, a descriptor `Mat` per keyframe, a retained previous frame. Of the 188
`org.opencv.*` types that hold a native pointer, exactly three expose a public `release()` ---
`Mat`, `VideoCapture` and `VideoWriter` --- and `ORB`, `BFMatcher` and `StereoSGBM` are not among
them. That is why `Descriptors`, `Odometry` and `LoopDetector` are all `AutoCloseable`, and why the
native-memory callouts below come thick and fast.

#sect("The line between front end and back end")

Half the mistakes in this area are scope mistakes --- reaching for a pose graph when a reflex would
do, or expecting a drift-free trajectory out of dead-reckoning --- so start with the territory.

#figure-table("What the front end answers, and what it hands upward.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [*Task*], [*In scalacv*], [*Needs something beyond OpenCV*],
  [Tracking], [`OpticalFlow` --- follow points frame to frame], [---],
  [Recognition], [`Features` --- ORB keypoints and matching], [---],
  [Motion], [`VisualOdometry.estimate` per pair; `Odometry` as the running loop], [absolute scale, drift correction],
  [Localization], [`Localizer.locate` --- absolute pose from a map], [the map to localize against],
  [Depth], [`StereoDepth.disparity`, `Obstacles.fromDisparity`], [---],
  [Steering], [`Navigator.steer` --- reactive avoidance], [a goal and a planner],
  [Mapping], [`OccupancyGrid`, `LoopDetector`], [---],
  [Full SLAM], [all of the above, as the front end], [pose-graph optimisation, bundle adjustment],
)
]

#sect("Optical flow: following what you already found")

The cheapest correspondence you can buy is the one you do not have to search for. If a point was at
`(412, 208)` in the last frame and the camera has moved a little, the same point is *near* `(412,
208)` in this frame, and a local optimisation over a small window will find it in a few iterations.
That is Lucas--Kanade, run over an image pyramid so that a large motion at full resolution is a small
motion at a quarter of it.

`OpticalFlow` is two calls. `goodFeatures` picks the corners worth following --- Shi--Tomasi, which
scores a pixel by how badly a small window around it matches itself when nudged in any direction, so
a corner scores well and a stretch of blank wall scores nothing. `track` follows a given set of
points from one image into another.

#example("The two halves of sparse flow, and their defaults.")[
```scala
def goodFeatures(
    image: Image,
    maxPoints: Int = 200,
    quality: Double = 0.01,
    minDistance: Double = 7.0
): Seq[Point]

def track(previous: Image, current: Image, points: Seq[Point]): Seq[Track]
def track(previous: Image, current: Image): Seq[Track]

final case class Track(from: Point, to: Point, found: Boolean):
  def displacement: Point
  def distance: Double
```
]

`quality` is relative, not absolute: a corner is kept if its Shi--Tomasi score is at least that
fraction of the best corner's, so `0.01` means "anything within two orders of magnitude of the
strongest corner in this image". `minDistance` enforces a pixel spacing between kept corners, which
is what stops the detector returning thirty points along one strong edge. These are the knobs you
turn when the tracker gives back either too few points to estimate anything or two hundred clustered
on one lamp post. `maxPoints` and `quality` must be positive; both are `require`d.

The two-argument `track` seeds `goodFeatures(previous)` for you. Use it for a first cut, and the
three-argument form the moment you want to reuse last frame's survivors instead of re-detecting ---
which is what a real loop does.

#subsect("The mistake: trusting a lost point")

`track` returns one `Track` per input point, in the order the points went in, and a lost point still
occupies its slot --- its `to` being whatever the optimiser held when it gave up. So this computes a
number, and the number is wrong:

```scala
val tracks = OpticalFlow.track(previous, current)
val meanX = tracks.map(_.displacement.x).sum / tracks.size   // wrong: includes lost points
```

The fix is one filter, and it is why `found` is a field rather than expressed by omitting the
track:

```scala
val kept = OpticalFlow.track(previous, current).filter(_.found)
```

Positional alignment with the input is worth more than a compacted result, because it is what lets
you zip a forward pass against a backward one.

#subsect("The forward--backward check")

`found` is the tracker's own opinion, and the tracker is an optimiser: it converges happily onto the
wrong pixel when a point crosses an occlusion boundary or lands on a repeating texture. The standard
defence costs one more `track` call --- follow the survivors back and keep only the points that land
where they started.

#example("A forward--backward consistency filter, built from the public API.")[
```scala
def stableTracks(previous: Image, current: Image, maxDrift: Double = 1.0): Seq[Track] =
  val seeds     = OpticalFlow.goodFeatures(previous)
  val forward   = OpticalFlow.track(previous, current, seeds).filter(_.found)
  val backward  = OpticalFlow.track(current, previous, forward.map(_.to))
  forward.zip(backward).collect:
    case (f, b) if b.found && b.to.distanceTo(f.from) <= maxDrift => f
```
]

The `zip` is only sound because of the ordering guarantee: `backward(i)` is the return trip of
`forward(i)`. A point that survives both passes and comes home within a pixel was matched
consistently in both directions, which an occlusion-induced slide almost never is. It costs a second
Lucas--Kanade pass over the survivors, and it is worth it, because a handful of consistently wrong
correspondences will bend an essential-matrix estimate in a way RANSAC does not always catch.

#note[
Sparse flow is what scalacv wraps. A *dense* field --- a displacement for every pixel, as
`calcOpticalFlowFarneback` produces --- is a two-channel float `Mat` rather than a `Seq` of typed
values, and costs far more per frame. Nothing hides it: as Appendix A spells out, the full typed
`org.opencv.*` surface is one step away, and `Managed` will own the result mat as it owns everything
else.
]

#sect("Features: recognising a place you have left")

Flow assumes the camera barely moved. Recognition assumes nothing --- the two views may be a minute
and a corridor apart, rotated, at different scales. That needs a descriptor: a compact signature of
the neighbourhood around a keypoint, robust enough to be matched against a signature computed
elsewhere, elsewhen. `Features` wraps ORB and returns its output as an owned value.

#example("Detect, then match. Both results are plain data; the container is not.")[
```scala
final case class FeatureMatch(queryIndex: Int, trainIndex: Int, distance: Float)

final class Descriptors extends AutoCloseable:
  val points: Seq[Point]
  def size: Int
  def isEmpty: Boolean
  def close(): Unit

object Features:
  def detect(image: Image, maxFeatures: Int = 500): Descriptors
  def matches(a: Descriptors, b: Descriptors, maxDistance: Float = 64f): Seq[FeatureMatch]
```
]

`maxFeatures` must be positive, and `detect` *borrows* the image --- it takes its own greyscale copy
internally and never consumes or closes what you passed --- while *returning* an owned `Descriptors`.
There is no public constructor: `Features.detect` is the only way to make one, which is what keeps the
`Mat` inside it from ever arriving unowned. That is Chapter 4's borrow/consume split applied to a
second resource type, and it means a two-image snippet has four native handles alive, not two.

#memory[
`Descriptors` holds the descriptor `Mat` --- 32 bytes per keypoint, so 16 KB for a default
500-feature frame, which sounds harmless until a detector loop runs for an hour. Close it, or take it
into a `Using` block. The ORB detector and the `BFMatcher` are shorter-lived but no less native:
neither has a public `release()`, so `Features` declares a `Releasable` for each via
`Releasable.nativeHandle` and frees them through the binding's private `delete(long)` (Chapter 5).
`ORB.create` in a per-frame loop with nothing freeing it is a leak measured in gigabytes.
]

#subsect("Matching, and what is thrown away")

`matches` runs a brute-force matcher with `NORM_HAMMING` and cross-check enabled, keeps the pairs
within `maxDistance`, and sorts them best-first. In each `FeatureMatch`, `queryIndex` indexes
`a.points` and `trainIndex` indexes `b.points`, so the two are not interchangeable and swapping the
arguments swaps the fields. One metric and two filters, each worth understanding.

The *Hamming distance* is a popcount of an XOR. ORB's descriptor is a bit string, so comparing two of
them is a couple of machine instructions rather than a 128-dimensional float subtraction --- most of
why ORB is affordable at frame rate.

*Cross-check* is the outlier filter, and it ships on: a pair survives only if `a`'s best match in `b`
is also `b`'s best match in `a`. It rejects the classic failure, twelve keypoints in this frame all
claiming the same distinctive keypoint in the old one, for the price of matching both directions. It
is a stricter, cheaper cousin of Lowe's ratio test, and because it is built into the matcher rather
than exposed as a knob, no caller gets a one-directional result by accident.

*`maxDistance`* is the absolute cutoff, `64f` by default --- a quarter of ORB's 256 descriptor bits
differing. Raise it for recall on hard viewpoint changes, and take the junk with it; lower it and
`LoopDetector` stops firing on genuine revisits. It is the number that repays tuning against your own
footage. The result is sorted, so `matches.take(30)` is the thirty most confident correspondences and
needs no further ceremony.

#sidebar("Why ORB, and not something with a better reputation")[
ORB is FAST corners with an orientation, described by a rotation-steered BRIEF bit string, published
in 2011 as a freely usable alternative to the then-patented SIFT and SURF. The licensing weather has
since changed; the engineering argument never depended on it.

A 256-bit binary descriptor is 32 bytes. A SIFT descriptor is 128 floats: 512 bytes, sixteen times
the memory per keypoint, matched by Euclidean distance rather than a popcount. On a keyframe store
that grows for the length of a drive, that ratio is the difference between a bounded process and a
slow leak. You give up some robustness to scale and severe viewpoint change; for loop closure over a
trajectory that revisits places from a similar direction --- what a ground vehicle does --- it is a
good trade, and the one scalacv makes for you.
]

#figure-table("Flow or features? They answer different questions.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [], [*`OpticalFlow`*], [*`Features`*],
  [Assumes], [small motion between consecutive frames], [nothing --- any two views],
  [Cost], [one pyramidal LK pass over N points], [detect, describe, then N×M descriptor comparisons],
  [Output], [`Seq[Track]`, aligned with the seeds], [`Descriptors` (owned) and `Seq[FeatureMatch]`],
  [Use for], [frame-to-frame tracking, odometry], [recognition: relocalization, loop closure],
  [Breaks on], [large jumps, occlusion, motion blur], [textureless scenes, repeated architecture],
)
]

#sect("Motion between two frames")

Given matched correspondences and a calibrated camera, the geometry is determined. Two views of the
same rigid scene constrain each other through the essential matrix, and decomposing it recovers the
rotation and translation that separate the two camera positions. `VisualOdometry.estimate` is that,
in one call.

#example("From correspondences to camera motion.")[
```scala
final case class CameraMotion(rotation: Seq[Seq[Double]], translation: Seq[Double], inliers: Int)

def estimate(from: Seq[Point], to: Seq[Point], intrinsics: Intrinsics): Option[CameraMotion]
```
]

`from` and `to` must be the same length and in matched order --- a `require`, because a length
mismatch is a bug in your pairing code, not a runtime condition. Fewer than five correspondences
returns `None`; five is the theoretical minimum for the essential matrix, and the internal call uses
`Calib3d.RANSAC` at 0.999 confidence with a one-pixel threshold, so it fits a consensus rather than
solving exactly. `inliers` is how many correspondences that consensus kept, and it is the honest
quality signal in the result: a `CameraMotion` with eleven inliers out of two hundred is arithmetic,
not measurement.

`rotation` is 3 rows by 3 columns and `translation` has length 3, both copied out of their native
mats before the call returns. Nothing native survives it except the `Image`s you already owned: a
`Managed.scope` registers every intermediate mat as it is built, so a throw from `findEssentialMat`
--- which does throw on genuinely degenerate input --- frees the ones already made. Note the
consequence: `estimate` returns `Option`, but it is not total. A native failure comes back as a
thrown `CvError.NativeCall`, not as `None`. `None` means *too few points, or no pose recoverable*;
an exception means OpenCV refused the input.

#warning[
`translation` is a *unit direction*. It has to be: a single camera cannot distinguish a small nearby
motion from a large distant one, because both produce the same image displacement. Multiplying it by
metres requires scale from somewhere else --- wheel odometry, an IMU, a stereo baseline, or a known
object size. Any code that adds these vectors up and calls the sum a position is measuring shape, not
distance, and should say so in its own type or its own comment.
]

#sect("The odometry loop")

`Odometry` is the running form: `goodFeatures`, `track`, `filter(_.found)` and `estimate` composed,
with the previous frame retained across calls.

#example("The whole surface of the pipeline.")[
```scala
final class Odometry extends AutoCloseable:
  def update(frame: Image): Option[CameraMotion]
  def framesProcessed: Int
  def close(): Unit

object Odometry:
  def monocular(intrinsics: Intrinsics): Odometry
```
]

`update` returns `None` on the first frame --- that frame becomes the reference --- and whenever
fewer than eight tracked points survive the `found` filter. Note the eight: the estimator itself
accepts five, and the pipeline is deliberately stricter than the thing it calls, because a five-point
fit from a nearly-lost tracker is a number that looks like an answer.

#memory[
`Odometry` keeps a `copy` of the previous frame --- a full `Mat`, roughly 900 KB for a 640×480 colour
frame, held for the pipeline's whole lifetime. That single retained handle is why the class is
`AutoCloseable`, and why it is not thread-safe: two `update` calls race on one native buffer. The
internals are worth reading for the pattern: the new baseline and its features are built *before*
either field is mutated, and the old frame is closed only once its replacement exists.
]

#sect("Depth from a stereo pair")

Two cameras a fixed distance apart see the same point at different horizontal positions, and that
shift is inversely proportional to distance. Write the shift as `d` pixels, the focal length as `f`
pixels and the baseline --- the separation of the two cameras --- as `B` metres, and depth is
`Z = f * B / d`. Everything about stereo follows from that fraction: doubling the baseline doubles
the precision at a given range, and a shift of zero means infinitely far away, which is why the far
field is the noisiest part of any disparity map.

`StereoDepth.disparity` computes the shift for every pixel by semi-global block matching: a
`blockSize` window from the left image is matched against candidate positions in the right over a
search range of `numDisparities` pixels, and --- the "semi-global" part --- solutions in which
neighbouring pixels disagree are penalised, aggregated along several scanline directions at once.
Plain block matching decides each pixel alone; it is faster and far noisier on low-texture surfaces.
SGBM's smoothness term is what makes a blank wall come out as a wall rather than confetti.

#example("A disparity map, with the two knobs that matter.")[
```scala
def disparity(left: Image, right: Image, numDisparities: Int = 64, blockSize: Int = 9): Image
```
]

Three preconditions run before anything native does: `numDisparities` positive and a multiple of 16
(OpenCV's requirement, not scalacv's invention), `blockSize` odd and at least 3, and the pair
agreeing in size. `numDisparities` is the range of depths you are willing to look for, so raising it
finds closer objects at a linear cost in time; `blockSize` trades detail for stability.

#note[
The result is an 8-bit single-channel `Image`, normalised so that *brighter is nearer*. That
normalisation is what makes it viewable and what makes `nearness` a clean `0`--`1` quantity, and it
is also what discards the metric depth: the raw `CV_16S` fixed-point disparity that SGBM produces is
consumed inside the call. If you need metres rather than an ordering, compute `Z = f * B / d` from
the raw disparity via the low-level surface --- and remember you need a real `B`, which is a
calibration output (Chapter 31), not a tape measurement.
]

The pair must already be *rectified*: row-aligned, so a point in the left image lies on the same
scanline in the right one. That is a one-time stereo-calibration step (`stereoRectify`), done off the
hot path, and it is deliberately not wrapped here.

#memory[
`StereoDepth.disparity` creates a `StereoSGBM` per call, and it has no public `release()` either ---
so it too is freed through `Releasable.nativeHandle`, inside a `use` block that runs on the exception
path as well. Two greyscale conversions and a raw `CV_16S` mat are allocated and released inside the
call. What comes back is one owned `Image`, yours to close.
]

#subsect("From a depth map to obstacles, and from obstacles to a decision")

A disparity map is still an image. Two small types turn it into a decision.

#example("Obstacles, and the steering reflex on top of them.")[
```scala
final case class Obstacle(region: Rect, nearness: Double)

def fromDisparity(disparity: Image, minNearness: Double = 0.5, minArea: Int = 200): Seq[Obstacle]

enum Steering:
  case Straight, Left, Right, Stop

final case class Guidance(
    steering: Steering,
    clearanceAhead: Double,
    leftNearness: Double,
    centreNearness: Double,
    rightNearness: Double
)

def steer(disparity: Image, dangerNearness: Double = 0.55, blockedNearness: Double = 0.8): Guidance
```
]

`Obstacles.fromDisparity` is the pipeline from Chapters 9, 11 and 12 pointed at a depth map instead
of a colour one: threshold at `minNearness × 255`, close the mask with a radius-2 morphological close
to knit up speckle, find contours, take bounding rectangles, drop anything under `minArea` pixels,
and attach each region's mean nearness from a submat. The list comes back nearest first, which is the
order you want for an avoidance decision.

`Navigator.steer` is deliberately dumber and therefore faster: split the map into vertical thirds,
take each one's mean nearness, and choose. Centre clearer than `dangerNearness`, go `Straight`. Both
sides past `blockedNearness`, `Stop`. Otherwise turn toward the clearer side. `clearanceAhead` is
`1.0 - centreNearness`, and the three raw per-third numbers come back with it so you can log why a
decision was taken rather than reconstruct it later.

This is a Braitenberg reflex, not a planner: no memory, no goal, no map. It keeps you off the wall
while something slower thinks. Four `require`s guard the way in: both thresholds in `[0, 1]`, the map
non-empty, and --- the interesting one --- at least three pixels wide. Below three columns the thirds
collapse, and the right band gets a zero or negative width, which is a raw `CvException` out of
`submat` rather than a steering answer. `Obstacles.fromDisparity` guards the same way: `minNearness`
in `[0, 1]` and `minArea` non-negative.

#sect("Absolute pose: where am I, really")

Odometry drifts because it only ever knows about the previous frame. `Localizer` answers in map
coordinates instead, and its error does not accumulate.

#example("Pose from 3D-to-2D correspondences.")[
```scala
final case class CameraPose(rotation: Seq[Seq[Double]], translation: Seq[Double]):
  def position: Seq[Double]

def locate(
    worldPoints: Seq[(Double, Double, Double)],
    imagePoints: Seq[Point],
    intrinsics: Intrinsics
): Option[CameraPose]
```
]

The inputs are pairs: a known 3D point in the map, and where it appears in this frame. In practice
they come from matching this frame's `Features` against descriptors stored with the map's points ---
the same recognition machinery loop closure uses, pointed at a different question. `solvePnP`
recovers six degrees of freedom, and `rotation` and `translation` are the transform into the camera
frame, `x_cam = R·x_world + t`. Note which part of `Intrinsics` each call reads: `locate` passes both
the camera matrix *and* the `distortion` coefficients, so a wide lens is corrected for; `estimate`
uses the camera matrix alone. Undistort your points before an essential-matrix estimate on a lens
that needs it. `position` is the inverse of that: the camera's own location in world
coordinates, `-Rᵀ·t`, computed for you so you do not transpose a matrix by hand at two in the
morning.

How many correspondences you need is not one number. Four are enough when the world points are
coplanar --- all on one wall or one floor --- because that admits a homography-based initialiser; six
are needed when they are not, because the non-planar initialiser is a direct linear transform with no
solution below six. OpenCV decides which case applies, by an SVD on the point covariance, so scalacv
does not try to predict it --- it contains the consequence instead. On four or five non-coplanar
points the default solver aborts in its own assertion and throws a raw `org.opencv.core.CvException`,
which is not a `CvError` and which a caller catching scalacv's error type would miss entirely. The
call is wrapped in `Cv.attempt` and `locate` returns `None`. It does not throw. Too few pairs, a
degenerate configuration, a solver that refuses: all `None`. The mismatched-length case is the one
`require`, because that one is your bug.

#sect("Mapping: the grid and the loop")

Two pieces move the front end toward something that deserves the word *map*.

#subsect("The occupancy grid")

`OccupancyGrid` is a top-down grid of cells, each holding a log-odds belief that the cell is
occupied. Log-odds because evidence then combines by addition: an obstacle at a cell adds a constant,
seeing *through* a cell subtracts one, and repeated observations accumulate without probabilities
underflowing.

#example("Accumulating free and occupied space from range readings.")[
```scala
val grid = OccupancyGrid(cols = 200, rows = 200, resolution = 0.05)   // 10m × 10m at 5cm

grid.observe(fromX = 0.0, fromY = 0.0, obstacleX = 2.0, obstacleY = 0.0)

grid.isOccupied(2.0, 0.0)      // true
grid.probability(1.0, 0.0)     // < 0.5 — seen through, so believed free
grid.probability(4.0, 0.0)     // 0.5 — never observed
grid.toImage.write("map.png")
```
]

`observe` does the work: it walks a Bresenham line from the sensor to the obstacle, marks every cell
along the way free, and marks the endpoint occupied. `hit` and `miss` are the single-cell forms. The
asymmetry in the constants is intentional --- an obstacle reading adds 0.85 to a cell and seeing
through one subtracts only 0.4, because a return is stronger evidence of an obstacle than a
non-return is of free space --- and every cell is clamped at ±4 log-odds, so a hundred identical
readings cannot saturate a cell beyond what a few contrary ones can undo. Without that clamp a map
can never unlearn a parked car that has since driven away.

`resolution` is world units per cell, `0.05` by default, with the grid centred on the origin.
`cellOf` exposes the quantisation if you need to reason in cells. `probability` returns `0.5` for
unobserved *and* out-of-bounds cells --- the right answer for both, and worth remembering when a map
seems to end abruptly; `isOccupied` takes a `threshold` parameter, `0.5` by default, so you can ask
for a stricter belief before treating a cell as solid. `toImage` renders it greyscale: occupied
white, free black, unknown mid-grey. The constructor requires positive dimensions, a positive
`resolution`, and `cols × rows` inside `Int.MaxValue` --- that last one so an over-large grid fails
with a named message rather than a bare `NegativeArraySizeException` from an overflowed array size.

#memory[
`OccupancyGrid` is the one type here that owns no native memory: a `Double` array and nothing else,
so a 200×200 grid is 320 KB of ordinary heap the collector handles correctly. The exception is
`toImage`, which hands you an owned `Image` --- and even that is built in the careful order, filling
a JVM byte array *before* allocating the `Mat`, so an `OutOfMemoryError` on a large grid cannot
strand a native buffer in the window between construction and ownership.
]

#warning[
  Display-normalized disparity is not metric depth. Its brightness changes with unrelated scene
  extrema; the 0.4.1 whole-band steering could miss narrow obstacles. The current local-mean
policy avoids whole-band dilution but is still a heuristic. Treat the image-based navigator
  as a demo heuristic, never a safety controller. Use measurements retaining disparity units and
  validity with an explicit obstacle policy. Similarly, a native pose solver's success is not
  sufficient evidence of observable motion or a supported pose.
]

#subsect("Loop closure")

`LoopDetector` keeps one keyframe's ORB descriptors per stored place and matches each new frame
against all of them. When a new frame matches an *old* keyframe strongly, you have been here before
--- which is the constraint a back end needs to redistribute accumulated drift around the loop.

#example("The detector's surface, and a run over a video.")[
```scala
final case class LoopClosure(keyframe: Int, matches: Int, score: Double)

final class LoopDetector extends AutoCloseable:
  def detect(image: Image): Option[LoopClosure]
  def addKeyframe(image: Image): Int
  def process(image: Image): Option[LoopClosure]
  def keyframeCount: Int
  def close(): Unit

object LoopDetector:
  def apply(
      maxFeatures: Int = 500,
      minMatches: Int = 20,
      recentExclusion: Int = 5,
      maxKeyframes: Int = Int.MaxValue
  ): LoopDetector

val loops = LoopDetector(minMatches = 25, maxKeyframes = 200)
try
  Camera.usingFile("drive.mp4"): camera =>
    camera.foreach(): frame =>
      loops.process(frame).foreach: closure =>
        println(f"revisited keyframe ${closure.keyframe}: ${closure.matches} matches, " +
          f"score ${closure.score}%.2f")
finally loops.close()
```
]

`process` is `detect` followed by `addKeyframe`; `detect` alone checks without storing, which is what
you want when frames arrive faster than you want keyframes. Both borrow the frame --- neither
consumes nor closes it --- so `camera.foreach` is still the one closing each `Image`.

`recentExclusion` skips the most recent keyframes, defaulting to five, because the last few places
you were always look like where you are now and would report a "loop closure" every frame.
`minMatches` is the acceptance bar: the best-matching old keyframe is returned only if it clears
that count, twenty by default. Raise it and you miss real revisits under changed lighting; lower it
and repeated architecture starts firing. `score` is the honest companion --- matched features as a
fraction of the *current* frame's --- so twenty-five matches out of thirty features and twenty-five
out of five hundred, which are very different pieces of evidence, are distinguishable in the result
rather than collapsed into one integer.

Matching is brute force against every live keyframe, which is fine for hundreds. A city-scale system
swaps in a bag-of-words index; the contract would not change, and that index is not in scalacv.

#memory[
The most dangerous object in the chapter, because its leak is proportional to how long the run goes
well. Every keyframe owns a native descriptor `Mat`, and `maxKeyframes` defaults to `Int.MaxValue` ---
unbounded. An hour of driving at one keyframe a second is 3600 descriptor mats nothing will free. Set
the cap for any run whose length you do not control: past it, the oldest keyframes are evicted and
their descriptors freed. Eviction leaves a tombstone rather than renumbering, so a
`LoopClosure.keyframe` index handed out earlier stays meaningful --- it refers to a slot that is now
skipped. `keyframeCount` reports the live count, not the total ever added, and `close()` frees
whatever is still live.
]

#sect("The front end, assembled")

The pieces compose in one direction. Here is the whole flow, before the worked example puts part of
it to work.

#figure-table("What feeds what, from pixels to a map.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Stage*], [*Consumes*], [*Produces*],
  [`OpticalFlow.goodFeatures`], [one `Image`], [`Seq[Point]` --- the seeds],
  [`OpticalFlow.track`], [two `Image`s, the seeds], [`Seq[Track]` --- correspondences],
  [`Features.detect`], [one `Image`], [`Descriptors` (owned)],
  [`Features.matches`], [two `Descriptors`], [`Seq[FeatureMatch]`],
  [`VisualOdometry.estimate`], [correspondences, `Intrinsics`], [`Option[CameraMotion]` --- relative, up to scale],
  [`Odometry.update`], [a frame per call], [the same, as a running loop],
  [`StereoDepth.disparity`], [a rectified pair], [an `Image`, brighter = nearer],
  [`Obstacles.fromDisparity`], [that map], [`Seq[Obstacle]`, nearest first],
  [`Navigator.steer`], [that map], [`Guidance` --- a `Steering` and its reasons],
  [`Localizer.locate`], [3D↔2D pairs, `Intrinsics`], [`Option[CameraPose]` --- absolute, metric],
  [`LoopDetector.process`], [a frame per keyframe], [`Option[LoopClosure]`],
  [`OccupancyGrid.observe`], [a pose and an obstacle], [accumulated belief; `toImage` to look at it],
)
]

Read the middle column: every stage consumes either an `Image` you own or plain Scala data, and only
three hand back something that owns native memory. That is what makes the front end assemblable ---
you can hold a `CameraMotion` or a `Seq[Track]` for the length of a run without holding anything that
has a deadline.

#sect("A trajectory from a video")

Read a file, run monocular odometry over it, integrate the per-step motions into a path, and plot the
path with the `Chart` API from Chapter 17. The result is shape rather than distance, for the reason
the warning above gave, and the code says so where it happens.

#example("Monocular VO over a video, plotted as a trajectory.")[
```scala
import scalacv.*

OpenCv.load()

// Accumulated camera pose in the first frame's coordinate system.
final case class Where(rotation: Seq[Seq[Double]], position: Seq[Double])

def mul(a: Seq[Seq[Double]], b: Seq[Seq[Double]]): Seq[Seq[Double]] =
  (0 until 3).map(i => (0 until 3).map(j => (0 until 3).map(k => a(i)(k) * b(k)(j)).sum))

def rotate(r: Seq[Seq[Double]], v: Seq[Double]): Seq[Double] =
  (0 until 3).map(i => (0 until 3).map(k => r(i)(k) * v(k)).sum)

val eye   = (0 until 3).map(i => (0 until 3).map(j => if i == j then 1.0 else 0.0))
val start = Where(eye, Seq(0.0, 0.0, 0.0))

val path = scala.collection.mutable.ArrayBuffer(start)

// Real numbers come from Chapter 31; Intrinsics.approx(frameSize) is the stopgap.
val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240))
try
  Camera.usingFile("drive.mp4"): camera =>
    camera.foreach(): frame =>
      odometry.update(frame).foreach: step =>
        // Every step is given unit length. That IS the scale ambiguity, made explicit.
        val here = path.last
        val moved = rotate(here.rotation, step.translation)
        path += Where(
          mul(here.rotation, step.rotation),
          here.position.zip(moved).map((a, b) => a + b)
        )
finally odometry.close()

// Ground plane for a forward-facing camera: x to the right, z forward.
val plan = path.map(w => (w.position(0), w.position(2))).toSeq

Chart
  .scatter(plan, width = 480, height = 480, color = Color.Orange, radius = 2.0)
  .render(480, 480, Color.gray(20))
  .write("trajectory.png")

println(s"${odometry.framesProcessed} frames, ${path.size} pose estimates")
```
]

Trace the lifetimes once, because this loop is the shape every video pipeline in the book takes.
`Camera.usingFile` closes the capture when the block ends and returns an `Either[CvError, Unit]`, so
a missing file is a value rather than a surprise (Chapters 6 and 20). `foreach` hands over an owned
`Image` per frame and closes it; this block never consumes it, because `update` only borrows `frame`
--- it takes its own `copy` for the next iteration's baseline, and `odometry.close()` in the
`finally` releases exactly that copy. `Chart.scatter` returns a `Picture`, an immutable value with no
native content, and `render` is where a single `Mat` is finally allocated, one call before `write`
releases it.

Be blunt about what the plot shows. The path is the right *shape* over short intervals and wrong over
long ones, in two independent ways: every step was assigned unit length, so a slow metre and a fast
metre look identical, and every step's rotation error is multiplied into every later pose by that
`mul`, so the whole trajectory bends. Drive a closed loop and the plotted path will not close. That
is the drift `LoopDetector` detects and a back-end optimiser removes.

#sidebar("Getting scale back")[
Four practical sources. A *stereo pair* gives it directly: `B` is metres, so `Z = f * B / d` is
metres, and each step scales by the depth of the points it came from. *Wheel odometry* gives distance
travelled per step --- exactly the missing scalar. An *IMU*, integrated twice, gives it badly alone
and well when fused with vision. A *known object size* --- a door, a calibration target, an ArUco
marker of measured edge length (Chapter 28) --- gives it whenever that object is in frame.

The multiplication happens in your code, not in `CameraMotion`: the type deliberately carries no
scale field, because a field that is sometimes metres and sometimes not is worse than none.
]

#sect("What is honestly here")

The front end reaches further than people expect: it tracks, recognises, estimates relative motion
and absolute pose, turns stereo into obstacles and obstacles into steering, detects revisited places,
and accumulates an occupancy map --- each a typed call over data you own.

What is not here is the global optimisation: taking every keyframe, odometry constraint and loop
closure and solving for the one trajectory and map that best explain all of them at once. That is
pose-graph optimisation and bundle adjustment --- nonlinear least squares over a large sparse system,
numerics rather than computer vision, and the business of g2o, GTSAM or Ceres. Nothing in scalacv
will correct a drifting trajectory for you. `LoopDetector` tells you *when* a correction is possible
and *which* two poses it constrains; that is the interface to the thing you would add. Absent for
related reasons: dense flow fields, a persistent map format, bag-of-words place recognition at city
scale, and stereo rectification.

#sect("Next")

Every metric answer in this chapter went through an `Intrinsics`, and most examples took those
numbers on faith. Chapter 31 is where they come from a chessboard rather than a guess, and it is
worth rereading now that you have seen what a wrong focal length does to a pose.

Chapter 33 changes the subject from geometry to symbols. OCR asks what none of this machinery answers
--- not *where* is the thing, but *what does it say* --- and the answer hinges almost entirely on
what you do to the pixels before any recognition engine sees them: deskewing, binarising, and the
pluggable engine boundary that keeps scalacv out of the business of shipping a language model.
