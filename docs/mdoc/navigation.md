# Visual navigation & SLAM

SLAM, localization, navigation and obstacle detection are built from the same visual pieces, and OpenCV — so
scalacv — provides the **front end** of that stack: detecting and tracking what the camera sees, and turning
motion and stereo into geometry. The **back end** that makes it full *SLAM* — a persistent map, loop closure,
global bundle adjustment — is a different kind of software (g2o, GTSAM, ORB-SLAM) and is deliberately out of
scope. Knowing where that line falls is half the battle:

| Task | scalacv provides (OpenCV front end) | Needs a back end beyond OpenCV |
|---|---|---|
| **Tracking** | [`OpticalFlow`](#optical-flow) — follow points frame to frame | — |
| **Visual odometry** | [`VisualOdometry`](#visual-odometry) per pair; [`Odometry`](#the-odometry-pipeline) — the running loop | scale, drift correction |
| **Localization** | [`Localizer`](#absolute-localization) — absolute pose via `solvePnP`; [`Features`](#features--matching) to match a map | a map to localize against |
| **Obstacle detection** | [`StereoDepth`](#stereo-depth-obstacles) + `Obstacles` | — |
| **Navigation** | [`Navigator`](#reactive-navigation) — reactive obstacle-avoidance steering | a map, a goal, a planner |
| **Mapping** | [`LoopDetector`](#mapping-loop-closure--occupancy) — revisit detection; [`OccupancyGrid`](#mapping-loop-closure--occupancy) | — |
| **Full SLAM** | all of the above as the front end | pose-graph optimisation, bundle adjustment |

:::tip[New to visual navigation? Start here.]
Everything on this page turns *pixels* into *geometry*. The chain, roughly: find distinctive points
([`OpticalFlow`](#optical-flow) / [`Features`](#features--matching)) → work out how the camera moved
([`VisualOdometry`](#visual-odometry)) or where it is ([`Localizer`](#absolute-localization)) → avoid
what is close ([`StereoDepth`](#stereo-depth-obstacles), [`Navigator`](#reactive-navigation)) → build a
map ([`OccupancyGrid`](#mapping-loop-closure--occupancy)) and know when you have been somewhere before
([`LoopDetector`](#mapping-loop-closure--occupancy)). Each is one call; you can use any piece on its own.
:::

The typed result each primitive returns, in one place:

| Type | Returned by | Carries |
|---|---|---|
| `Track` | `OpticalFlow.track` | `from`, `to`, `found`, `displacement`, `distance` |
| `FeatureMatch` | `Features.matches` | `queryIndex`, `trainIndex`, `distance` (Hamming) |
| `CameraMotion` | `VisualOdometry.estimate` | `rotation` (3×3), `translation` (unit dir), `inliers` |
| `CameraPose` | `Localizer.locate` | `rotation`, `translation`, `position` (world coords) |
| `Obstacle` | `Obstacles.fromDisparity` | `region` (`Rect`), `nearness` (0…1) |
| `Guidance` | `Navigator.steer` | `steering`, `clearanceAhead`, per-third nearness |
| `LoopClosure` | `LoopDetector.detect` | `keyframe`, `matches`, `score` |

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
def scene(ox: Int, oy: Int): Image =
  Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(
    Seq(Rect(30 + ox, 30 + oy, 26, 26), Rect(130 + ox, 40 + oy, 30, 22), Rect(70 + ox, 110 + oy, 22, 34), Rect(150 + ox, 120 + oy, 26, 26)),
    Scalar.White, Thickness.Filled)
```

## Optical flow

Seed good corners, then follow them into the next frame with pyramidal Lucas–Kanade. Each surviving
[`Track`](/navigation) carries its own displacement — the raw material of egomotion and of "is anything
moving, and which way":

```scala mdoc
{
  val a = scene(0, 0)
  val b = scene(6, 4) // the same scene shifted right-and-down
  val tracks = OpticalFlow.track(a, b).filter(_.found)
  val meanShift = (tracks.map(_.displacement.x).sum / tracks.size, tracks.map(_.displacement.y).sum / tracks.size)
  a.close(); b.close()
  s"${tracks.size} points, mean shift $meanShift"
}
```

`track(a, b)` is the one-call form: it seeds Shi–Tomasi corners on `a` for you. When you want to control
the seeds — reuse last frame's points, mask a region, cap the count — call
[`goodFeatures`](/api/core/scalacv/vision/OpticalFlow$.html) yourself and pass them to the three-argument
`track`:

```scala mdoc:silent
val seedFrame = scene(0, 0)
val corners = OpticalFlow.goodFeatures(seedFrame, maxPoints = 100, quality = 0.01, minDistance = 7.0)
val nextFrame = scene(6, 4)
val tracked = OpticalFlow.track(seedFrame, nextFrame, corners)
seedFrame.close(); nextFrame.close()
```

```scala mdoc
s"seeded ${corners.size} corners, tracked ${tracked.count(_.found)} into the next frame"
```

| `goodFeatures` knob | Meaning | Default |
|---|---|---|
| `maxPoints` | most corners to return | `200` |
| `quality` | keep corners at least this fraction as strong as the best | `0.01` |
| `minDistance` | minimum pixel spacing between kept corners | `7.0` |

:::note[The returned tracks line up with the seeds]
`track(prev, cur, points)` returns one `Track` per input point, **in order**. A point the tracker lost
has `found == false` — filter on it before you trust its `to`.
:::

## Features & matching

ORB finds repeatable keypoints and binary descriptors; a cross-checked Hamming matcher pairs them across
images. This is how a system recognises a place it has seen before — relocalization and loop-closure
detection:

```scala mdoc
{
  val imgA = scene(0, 0)
  val imgB = scene(8, 0)
  val one = Features.detect(imgA)
  val two = Features.detect(imgB)
  try
    val matched = Features.matches(one, two).size
    s"${one.size} vs ${two.size} features, $matched matches"
  finally
    one.close(); two.close()
    imgA.close(); imgB.close()
}
```

`Descriptors` owns native memory — close it (or take it into a `Using` block). So do the images: there
are **four** native handles in that snippet, not two. `Features.detect` **borrows** the image (it makes
its own greyscale copy internally and never consumes or closes what you pass it) and **returns** an owned
`Descriptors`. That is the same borrow/own split this page spells out for `OpticalFlow` — whoever created
the `Image` still has to close it, and the result you were handed is yours as well. Holding the images in
named `val`s rather than passing `scene(...)` inline is what makes them closeable at all.

Two knobs shape the recognition:

| Call | Knob | Effect |
|---|---|---|
| `Features.detect(image, maxFeatures = 500)` | `maxFeatures` | ceiling on keypoints per image |
| `Features.matches(a, b, maxDistance = 64f)` | `maxDistance` | reject pairs whose Hamming distance exceeds this |

Matching is **cross-checked** — every returned pair is each other's mutual best — and sorted best
(smallest distance) first, so `matches.take(n)` gives the `n` most confident correspondences.

## Optical flow vs. features — which one?

Both give you point correspondences to feed [`VisualOdometry`](#visual-odometry) or
[`Localizer`](#absolute-localization); they differ in what they assume:

| | `OpticalFlow` | `Features` (ORB) |
|---|---|---|
| Assumes | small motion between consecutive frames | nothing — matches across any two views |
| Speed | very fast (sparse LK) | slower (detect + describe + match) |
| Use for | frame-to-frame **tracking**, odometry | **recognition** — relocalization, loop closure |
| Fails when | large jumps, occlusion | textureless scenes |

## Visual odometry

Feed matched correspondences to the essential-matrix estimator and `recoverPose` to get the camera's motion
between two frames — a 3×3 rotation and a **unit** translation direction (a single camera cannot see absolute
scale). Here the correspondences come from projecting known 3D points before and after a sideways move:

```scala mdoc
{
  val world = Seq((-1.0, -1.0, 5.0), (1.0, -1.0, 6.0), (-1.0, 1.0, 7.0), (1.0, 1.0, 5.5), (0.0, 0.0, 6.0), (0.5, -0.7, 5.2), (-0.6, 0.4, 6.5), (0.2, 0.8, 5.8))
  def project(p: (Double, Double, Double), camX: Double): Point =
    val (x, y, z) = p
    Point(500 * (x - camX) / z + 320, 500 * y / z + 240)
  val motion = VisualOdometry.estimate(world.map(project(_, 0.0)), world.map(project(_, 0.4)), Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240))
  motion.map(m => s"${m.inliers} inliers, unit translation, rotation ~identity").getOrElse("degenerate")
}
```

:::warning[Monocular odometry is up-to-scale, and it drifts]
A single camera cannot tell a small nearby motion from a large distant one, so `translation` is a unit
*direction*, not metres. Recover scale by fusing wheel odometry, an IMU, or a known stereo baseline.
And chaining the per-frame motions is dead-reckoning — error accumulates. Cancelling that drift is the
back end's job (loop closure + global optimisation).
:::

`estimate` needs at least **5** correspondences and returns `None` on too few, or on degenerate geometry
(all points coplanar and the motion pure rotation, say).

## Stereo depth & obstacles {#stereo-depth-obstacles}

From a rectified stereo pair, `StereoDepth.disparity` produces a map where **brighter is nearer**, and
`Obstacles.fromDisparity` reads the near-field blobs off it — the obstacle detector for a robot or drone:

```scala mdoc
{
  // A disparity map with one near (bright) block, as StereoDepth would output.
  val disparity = Image.blank(200, 150, Scalar.Black, channels = 1).drawRect(Rect(60, 50, 44, 40), Scalar(210), Thickness.Filled)
  val obstacles = Obstacles.fromDisparity(disparity, minNearness = 0.5)
  disparity.close()
  obstacles.map(o => s"obstacle ${o.region} nearness ${(o.nearness * 100).round}%").mkString("; ")
}
```

Each [`Obstacle`](/api/core/scalacv/vision/Obstacle.html) is a bounding `region` plus a mean `nearness` in
`0…1`; the list comes back **largest first**. The two knobs:

| `fromDisparity` knob | Meaning | Default |
|---|---|---|
| `minNearness` | how near (0…1) a region must be to count | `0.5` |
| `minArea` | ignore blobs smaller than this many pixels | `200` |

The disparity search itself is tunable too — `StereoDepth.disparity(left, right, numDisparities = 64,
blockSize = 9)`, where `numDisparities` (the depth range searched) must be a positive multiple of 16 and
`blockSize` an odd matching window.

:::note[Rectification is assumed]
The pair must already be **rectified** (row-aligned). That is a one-time stereo-calibration step
(`stereoRectify`) done off the hot path, so it is not wrapped here — see [calibration](/calibration).
:::

## Localization against a map {#absolute-localization}

`Localizer` gives the camera's **absolute** pose from correspondences between a map's known 3D points and their
matches in this frame, via `solvePnP` (the same routine [head pose](/pose-estimation) uses, at map scale).
Unlike odometry it does not drift — it is what a map is *for*. Here the correspondences are synthetic, from a
camera two units to the side of the world origin:

```scala mdoc
{
  val world = Seq((-1.0, -1.0, 6.0), (1.0, -1.0, 6.5), (-1.0, 1.0, 7.0), (1.0, 1.0, 5.5), (0.0, 0.0, 8.0), (0.6, -0.4, 7.2))
  def seen(p: (Double, Double, Double)): Point =
    val (x, y, z) = p
    Point(600 * (x - 2.0) / z + 320, 600 * y / z + 240)
  Localizer.locate(world, world.map(seen), Intrinsics(fx = 600, fy = 600, cx = 320, cy = 240))
    .map(pose => f"camera at world (${pose.position(0)}%.1f, ${pose.position(1)}%.1f, ${pose.position(2)}%.1f)")
    .getOrElse("could not localize")
}
```

`locate` needs at least **4** 3D↔2D pairs and returns a [`CameraPose`](/api/core/scalacv/vision/CameraPose.html),
whose `position` gives the camera's location in *world* coordinates (`-Rᵀ·t`, computed for you). In
practice the pairs come from matching this frame's [`Features`](#features--matching) to the map; the
recovered pose then anchors the drifting odometry.

| | `VisualOdometry` | `Localizer` |
|---|---|---|
| Answers | how did I *move* between two frames? | where am I, absolutely? |
| Relative to | the previous frame | the map's origin |
| Scale | up-to-scale (unit direction) | metric (at map scale) |
| Drifts? | yes, accumulates | no |

## Reactive navigation

The shortest path from "where are the obstacles" to "what do I do" is `Navigator`: read a disparity map, split
the view into thirds, and pick a `Steering` toward the clearest — obstacle avoidance with no map at all:

```scala mdoc
{
  // Something near, filling the right two-thirds of the view ahead.
  val disparity = Image.blank(300, 150, Scalar.Black, channels = 1).drawRect(Rect(120, 0, 180, 150), Scalar(220), Thickness.Filled)
  val guidance = Navigator.steer(disparity)
  disparity.close()
  s"${guidance.steering}, clearance ahead ${(guidance.clearanceAhead * 100).round}%"
}
```

The returned [`Guidance`](/api/core/scalacv/vision/Guidance.html) has a `steering` and the per-third nearness it
decided from. `steer` reads the disparity centre and picks one of four moves:

| `Steering` | When | 
|---|---|
| `Straight` | the centre third is clearer than `dangerNearness` (default `0.55`) |
| `Left` / `Right` | the centre is blocked — turn toward the clearer side |
| `Stop` | both sides are past `blockedNearness` (default `0.8`) — boxed in |

A planner — a map, a goal, a path — layers on top; the reflex keeps you off the walls while it thinks.

## The odometry pipeline

`Odometry` wires the primitives into the running loop: feed it frames and it tracks features and estimates
each step's motion for you, keeping the previous frame internally. It is `AutoCloseable`, and monocular (each
step's translation is up to scale). Drive it straight off a [`Camera`](/video):

```scala mdoc:compile-only
val odometry = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240))
try Camera.usingFile("drive.mp4")(_.foreach()(frame => odometry.update(frame).foreach(step => println(step.inliers))))
finally odometry.close()
```

`update` returns `None` on the very first frame (it becomes the reference) and whenever too few points
survive to estimate a motion; otherwise a [`CameraMotion`](/api/core/scalacv/vision/CameraMotion.html) for that
step. `framesProcessed` tells you how many frames it has consumed. The pipeline retains a frame's worth
of native memory between calls — that is why it is `AutoCloseable`, and why it is **not** thread-safe:
feed one frame at a time.

## Mapping: loop closure & occupancy

Two pieces move the front end toward an actual map.

**Loop closure.** `LoopDetector` keeps each keyframe's [`Features`](#features--matching) and flags when a new
frame revisits an old place — the cue that lets a back end cancel accumulated drift:

```scala mdoc
{
  def place(seed: Int): Image =
    val r = new scala.util.Random(seed)
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(
      Seq.fill(7)(Rect(10 + r.nextInt(170), 10 + r.nextInt(130), 18 + r.nextInt(16), 18 + r.nextInt(16))),
      Scalar.White, Thickness.Filled)
  val loops = LoopDetector(minMatches = 25, recentExclusion = 2)
  try
    (1 to 5).foreach(s => { val p = place(s); try loops.process(p) finally p.close() })
    val revisit = place(1) // revisit the very first place
    val closure = try loops.detect(revisit).map(l => s"loop to keyframe ${l.keyframe}, ${l.matches} matches") finally revisit.close()
    closure.getOrElse("no loop")
  finally loops.close()
}
```

`LoopDetector` is stateful and caller-owned — it holds a descriptor set per keyframe, so close it. Its
construction knobs:

| Knob | Meaning | Default |
|---|---|---|
| `maxFeatures` | ORB features stored per keyframe | `500` |
| `minMatches` | matches that count as "the same place" | `20` |
| `recentExclusion` | recent keyframes to skip (always look similar to *now*) | `5` |
| `maxKeyframes` | live keyframes before the oldest are evicted and freed | unbounded |

`process` is the usual per-step call — `detect` then `addKeyframe` in one. Over a long run, set
`maxKeyframes` to bound native memory: evicted keyframes leave a tombstone so an index handed out earlier
stays valid, it is simply skipped when matching.

**Occupancy grid.** `OccupancyGrid` accumulates free/occupied evidence into a top-down log-odds map. A range
reading marks the ray to an obstacle as free and its endpoint as occupied; `toImage` renders the map:

```scala mdoc
{
  val grid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
  grid.observe(0.0, 0.0, 2.0, 0.0) // sensor at origin, obstacle 2m ahead
  s"ahead occupied ${grid.isOccupied(2.0, 0.0)}, 1m out free ${!grid.isOccupied(1.0, 0.0)}"
}
```

Under the hood each cell holds a **log-odds** estimate that it is occupied; repeated evidence accumulates
and clamps, so a single stray reading cannot flip a well-observed cell. `probability(x, y)` reads the
soft belief (0.5 = unknown or out of bounds):

```scala mdoc
{
  val grid = OccupancyGrid(cols = 40, rows = 40, resolution = 0.1)
  (1 to 5).foreach(_ => grid.observe(0.0, 0.0, 1.5, 0.0)) // five confirming hits
  f"P(occupied at 1.5m ahead) = ${grid.probability(1.5, 0.0)}%.2f, P(0.7m) = ${grid.probability(0.7, 0.0)}%.2f"
}
```

The grid is pure in-memory data (no native memory); `toImage` renders it as grayscale — occupied white,
free black, unknown mid-grey — for viewing or saving.

## Where OpenCV ends

The front end now reaches quite far: it tracks, estimates motion, localizes against a map, detects revisited
places, and builds an occupancy grid. What remains is the **global optimisation** — taking the keyframes, the
loop closures, and the odometry constraints and solving for the trajectory and map that best fit them all
(pose-graph optimisation, bundle adjustment). That is a nonlinear-least-squares back end (g2o, GTSAM,
Ceres), not computer vision, and belongs to those libraries. scalacv gives you every per-frame piece that
feeds them — clean, typed, and resource-safe.

## Next

- [Camera calibration](/calibration) — the intrinsics that make odometry, localization and depth metric.
- [Tracking](/tracking) — object trackers, the higher-level cousin of optical flow.
- [Motion detection](/motion-detection) — background subtraction, when you only need "did something move".
