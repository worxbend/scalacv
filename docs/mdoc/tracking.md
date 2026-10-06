# Object tracking

Detection tells you *what is in this frame*. Tracking tells you *which one is which, frame after frame* —
so you can say "person #3" every time, count how many distinct objects have passed, or steady a jittery
box. This page starts from the simplest smoother and builds up to full multi-object identity.

scalacv gives you three layers, from lowest to highest:

- a [`Kalman`](/api/core/scalacv/vision/Kalman.html) filter — a motion smoother and short-term predictor;
- a single-object [`Tracker`](/api/core/scalacv/vision/Tracker.html) — model-free CSRT/KCF/MIL tracking;
- an [`ObjectTracker`](/api/core/scalacv/vision/ObjectTracker.html) — tracking-by-detection with stable ids.

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
```

## Detection vs. tracking, in one table

If you have never drawn the line between the two, this is it. A detector answers *what and where, this
instant, with no memory*. A tracker adds the memory — the thread that says "this box now is the same object
as that box a moment ago".

| Question | Answered by |
|---|---|
| Is there a face in this frame? Where? | a **detector** — [faces](/object-detection), a [DNN](/dnn), [motion boxes](/motion-detection) |
| Is *this* box the same object as the one last frame? | a **tracker** (this page) |
| How many distinct objects have crossed so far? | [`ObjectTracker.count`](#objecttracker-tracking-by-detection) |
| Where will it be next frame, if I miss a reading? | [`Kalman.predict`](#kalman-smoothing-and-prediction) |

:::tip[Which layer do I reach for?]
Skip to [Choosing between them](#choosing-between-them) if you already know the vocabulary. If not, read
top to bottom — each layer is built from the one above it. `ObjectTracker` is a bank of `Kalman` filters
plus a matcher, so understanding `Kalman` first makes the rest obvious.
:::

All three own native state and are **caller-owned** (`AutoCloseable`) and **not thread-safe** — feed one
thread, and `close()` (or use `scala.util.Using`) when done.

## Kalman: smoothing and prediction {#kalman-smoothing-and-prediction}

A [`Kalman`](/api/core/scalacv/vision/Kalman.html) filter models an object's position *and velocity*
`(x, y, vx, vy)`, so it can predict where the object goes next and smooth a noisy measurement toward that
prediction. The rhythm is always the same two calls:

1. `predict()` — advance the model one step, return where it *thinks* the object now is;
2. `correct(measurement)` — fold in the reading you actually have, return the smoothed position.

You can skip the `correct` when a frame dropped out and simply coast on the model.

```scala mdoc:silent
val k = Kalman.point(Point(0, 0))
try
  // Feed a few measurements moving steadily right; the filter learns the velocity.
  for i <- 1 to 5 do
    k.predict()
    k.correct(Point(i * 10.0, 0))
  val next = k.predict() // extrapolates *past* the last measurement
  println(f"predicted next x ≈ ${next.x}%.1f")
finally k.close()
```

Once velocity is learned, a bare `predict` reaches beyond the last reading — that is the "predict where it's
going" property in one Boolean:

```scala mdoc:silent
val sm = Kalman.point(Point(0, 0))
val readings = Seq(10.0, 19.0, 31.0, 39.0, 51.0) // noisy, roughly +10 per step
readings.foreach { x => sm.predict(); sm.correct(Point(x, 0)) }
val extrapolated = sm.predict().x // one step past the last reading
sm.close()
```

```scala mdoc
extrapolated > 51.0
```

### Coasting through a dropped frame

The point of a predictor is that you can *skip* the measurement. When a frame arrives without a usable
reading — the detector blinked, the object was briefly occluded — call `predict` and trust the model:

```scala mdoc:silent
val coast = Kalman.point(Point(0, 0))
try
  for i <- 1 to 5 do
    coast.predict()
    coast.correct(Point(i * 10.0, 0)) // learns vx ≈ 10
  // Two frames with no measurement: predict only. The box keeps gliding forward.
  coast.predict()
  val stillMoving = coast.predict()
  println(f"coasted to x ≈ ${stillMoving.x}%.1f")
finally coast.close()
```

### Tuning the two noise knobs

[`Kalman.point`](/api/core/scalacv/vision/Kalman.html) takes two parameters that trade responsiveness against
smoothness. They are the whole personality of the filter.

| Parameter | Default | Larger means… |
|---|---|---|
| `processNoise` | `1e-2` | the model may drift more → **more responsive**, more jitter |
| `measurementNoise` | `1e-1` | measurements trusted less → **smoother**, laggier |

:::note[Rule of thumb]
Twitchy, over-reactive box? Raise `measurementNoise` (trust the model, smooth harder). Sluggish, always
behind a fast object? Raise `processNoise` (let the model chase the readings). Change one at a time.
:::

:::warning[It owns native state]
`Kalman` holds a native `KalmanFilter`. Always `close()` it — the `try/finally` above, or wrap it in
`Using.resource`. See [Mat lifecycle](/mat-lifecycle) for the ownership model that governs every native
handle in scalacv.
:::

## Tracker: follow one object without re-detecting {#tracker-follow-one-object-without-re-detecting}

A [`Tracker`](/api/core/scalacv/vision/Tracker.html) is *model-free*: you show it a box in one frame and it finds
that same patch in the next, learning the appearance as it goes. It works on anything — you do not need a
detector that knows the object's class. This is the tool for "the user clicked a thing, now follow it".

Pick the algorithm with [`TrackerKind`](/api/core/scalacv/vision/TrackerKind.html):

| `TrackerKind` | Strength | Trade-off | Reports loss? |
|---|---|---|---|
| `Csrt` | most accurate; handles scale change and partial occlusion | slowest | yes (`None`) |
| `Kcf` | fast and steady | box does not follow scale | yes (`None`) |
| `Mil` | robust to small appearance changes | no failure detection | no — always a box |

```scala mdoc:compile-only
import scala.util.Using

Tracker.create(TrackerKind.Csrt).foreach: tracker => // Left when the build lacks CSRT
  Using.resource(tracker): t =>
    Image.reading("frame0.png"): first =>
      t.init(first, Rect(120, 80, 60, 60)) // seed with the object's box

    for n <- 1 to 100 do
      Image.reading(s"frame$n.png"): frame =>
        t.update(frame) match
          case Some(box) => frame.drawRect(box).write(s"tracked$n.png")
          case None      => println(s"lost the object at frame $n")
```

The lifecycle is exactly two verbs: `init(image, box)` once to seed, then `update(image)` per frame.

- `init` may be called again to **re-seed** on a fresh box — useful when a detector reacquires the object
  after `update` returned `None`.
- `update` returns `Option[Rect]`. CSRT and KCF report loss by returning `None`; **MIL always returns a
  box**, so it can silently drift onto the background — pair it with your own sanity check if you use it.

:::danger[init before update]
`update` requires a prior `init` (it `require`s `started`). Calling `update` on a fresh tracker throws.
:::

:::tip[Tracker vs. re-detecting every frame]
A `Tracker` is cheaper than running a full detector on every frame and it keeps following even when the
detector would miss (odd pose, motion blur). The classic pattern is **detect occasionally, track in
between**, re-seeding the tracker with each new detection.
:::

## ObjectTracker: tracking-by-detection {#objecttracker-tracking-by-detection}

The highest layer turns a per-frame stream of *detections* — from any source, [faces](/object-detection),
[motion boxes](/motion-detection), a [DNN](/dnn) — into *tracks* with identities that persist. This is the
"SORT-lite" pattern. Each frame:

1. every live track is advanced by its own [`Kalman`](#kalman-smoothing-and-prediction) filter;
2. detections are matched to tracks by bounding-box overlap (IoU, greedily best-first);
3. matched tracks are corrected toward their detection;
4. unmatched detections spawn new tracks with fresh ids;
5. tracks unseen for `maxAge` frames retire (freeing their filters).

It never looks at the image — only the boxes — so it composes with whatever produced them.

```scala mdoc:silent
val tracker = ObjectTracker.create(iouThreshold = 0.3, maxAge = 5)

// Two objects, each drifting a little between frames. In a real pipeline these boxes come from a detector.
val frames = Seq(
  Seq(Rect(10, 10, 20, 20), Rect(120, 120, 20, 20)),
  Seq(Rect(14, 10, 20, 20), Rect(124, 120, 20, 20)),
  Seq(Rect(19, 11, 20, 20), Rect(129, 121, 20, 20))
)

val perFrame = frames.map(dets => tracker.update(dets).map(_.id).sorted)
```

```scala mdoc
{
  s"ids each frame: ${perFrame.mkString(", ")}\ndistinct objects seen: ${tracker.count}"
}
```

```scala mdoc:invisible
tracker.close()
```

Each frame reports the same two ids, and `count` is the running number of *distinct* objects ever seen —
the value you show as "3 people entered".

### What `update` gives back

`update(detections)` returns a `Seq[`[`ObjectTrack`](/api/core/scalacv/vision/ObjectTrack.html)`]` — the tracks
**confirmed this frame** (seen at least `minHits` times *and* matched to a detection this frame):

| `ObjectTrack` field | Meaning |
|---|---|
| `id` | the stable identity, unique and monotonically increasing |
| `box` | the current bounding box (the matched detection, or the Kalman prediction) |
| `hits` | how many frames this track has been matched to a detection |
| `age` | how many frames this track has existed |

### The three tuning knobs

[`ObjectTracker.create`](/api/core/scalacv/vision/ObjectTracker.html) has three parameters that shape how
readily tracks are formed, matched, and dropped:

| Parameter | Default | Effect | Raise it when… |
|---|---|---|---|
| `iouThreshold` | `0.3` | minimum box overlap to associate a detection with a track | boxes jump between frames → **lower** it so matches still land |
| `maxAge` | `5` | frames a track may go unseen before it retires | objects vanish briefly (occlusion) → **raise** it to bridge the gap |
| `minHits` | `1` | matches required before a track is *reported* as confirmed | detections are noisy → **raise** it to suppress one-frame false tracks |

`minHits` introduces a deliberate confirmation delay: a brand-new detection is tracked internally but not
reported until it has been matched enough times.

```scala mdoc:silent
val confirming = ObjectTracker.create(minHits = 2)
val firstReport = confirming.update(Seq(Rect(10, 10, 20, 20))) // 1 hit — tracked, not yet confirmed
val secondReport = confirming.update(Seq(Rect(12, 10, 20, 20))) // 2 hits — confirmed now
confirming.close()
```

```scala mdoc
(firstReport.size, secondReport.size)
```

The first frame reports nothing (`0`); by the second the track has crossed `minHits` and appears (`1`).

### Drawing the result

[`Image.drawTracks`](/api/core/scalacv/Image.html) labels every box with its `#id` in one call. Like every
transform it **consumes** the receiver and returns the annotated image — take a [`.copy`](/mat-lifecycle)
first if you need the original afterwards.

```scala mdoc:compile-only
val liveTracker = ObjectTracker.create()
Image.reading("frame.png"): frame =>
  // Detections from whatever source — here, contour boxes from a threshold on a throwaway copy.
  val detections = frame.copy.gray.threshold(128).contours().map(_.boundingRect)
  val tracks = liveTracker.update(detections)
  frame.drawTracks(tracks).write("annotated.png")
liveTracker.close()
```

`drawTracks` takes an optional `color` (defaults to `Scalar.Green`); see [Drawing](/drawing) for the full
overlay vocabulary and [Color & masking](/color-masking) for the BGR channel order.

## Choosing between them {#choosing-between-them}

| You have… | Use |
| --- | --- |
| one object, a box to start from, no detector | `Tracker` (CSRT/KCF/MIL) |
| a detector running every frame, many objects | `ObjectTracker` |
| a single noisy measurement to smooth or extrapolate | `Kalman` |

`ObjectTracker` is usually what you want for counting and multi-object work; a `Tracker` shines when you
have exactly one thing to follow and no detector for it; a `Kalman` is the building block underneath, handy
alone whenever you have a position over time and want it steadied.

## A full pipeline

The natural shape is **detect → track → draw**, one expression per frame. Motion boxes make a good source
because they need no model — see [Motion detection](/motion-detection) for the detector and
[Video & Camera](/video) for the frame loop:

```scala mdoc:compile-only
val motion = MotionDetector.backgroundSubtraction(minArea = 300)
val counter = ObjectTracker.create(iouThreshold = 0.2, maxAge = 10)

Camera.usingFile("http://esp32-cam.local:81/stream") { cam =>
  cam.foreach() { frame =>
    val boxes = motion.detect(frame).regions // detect: what moved, where
    val tracks = counter.update(boxes)       // track: stitch into stable ids
    frame.copy.drawTracks(tracks).write("out.png")
  }
}
motion.close()
counter.close()
println(s"${counter.count} distinct objects passed")
```

:::note[Ownership recap]
`ObjectTracker.update` takes plain `Rect`s and never touches your images — so the frame you pass to a
detector stays yours to draw on and encode. `drawTracks` consumes the image it's called on; `.copy` first
inside `foreach` since the loop owns and closes `frame`. Full rules in [Mat lifecycle](/mat-lifecycle).
:::

## Next

- [Motion detection](/motion-detection) — the cheapest source of per-frame boxes to feed an `ObjectTracker`.
- [Object detection](/object-detection) — faces and DNN detections, the other common track source.
- [Video & Camera](/video) — opening streams and files, and the per-frame loop that drives all of this.
