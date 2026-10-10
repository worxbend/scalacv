#import "../lib/book.typ": *

#chapter("Pose Estimation and Gestures", subtitle: [Where the body is, and what it is doing.])

A face detector answers a question with a rectangle. A pose estimator answers a much better question
with a graph. Instead of "there is a person around here" you get "the left wrist is at (412, 208), the
left elbow at (390, 265), and the model is 0.87 sure of the first and 0.41 sure of the second" --- and
from that you can ask things a bounding box never answers. Is the arm raised. Is the person sitting.
Are the shoulders square to the camera. Is that hand making a fist.

None of that is answered by the network. A keypoint network emits a tensor: seventeen rows of three
floats, or seventeen heatmap planes, depending on which model you exported. It does not tell you that
row five is the left shoulder, and it certainly does not tell you that row five connects to row seven
and row eleven. That knowledge --- the naming and the connectivity --- lives in the documentation of
whichever model you downloaded, and every project that skips writing it down reinvents it, wrongly,
three files later. Here it is a value: `PoseTopology`, a `Seq[String]` of names and a
`Seq[(Int, Int)]` of index pairs, passed to the decode and passed again to the drawing.

This chapter sits on the far side of the boundary Chapter 23 drew. scalacv ships no pose weights and
downloads none: the model file is yours, loaded with `Dnn.fromOnnx` exactly as Chapter 26 loaded every
other network, and the library owns the typed result, the decode from a raw tensor into named pixel
coordinates, the overlay, and a rule-based gesture layer on top. One estimator needs no model at all
--- head pose, solved from the five landmarks a `Face` already carries --- and it is also the most
native-memory-dense call in the book, since `solvePnP` wants six `Mat`s alive at once and hands none
of them back.

One example runs the length of the chapter: a session monitor that reads a recorded video, tracks a
hand, smooths the jitter out of the landmarks, and fires an event once when a gesture settles.

Everything new here --- `Pose`, `PoseTopology`, `PoseEstimator`, `HeadPose`, `GestureRecognizer` and
the two `Image` extensions --- lives in `scalacv-vision`, next to the `Dnn` and `FaceDetect` of the
last chapters, so `com.worxbend::scalacv-vision:0.4.1` has to be on the classpath. `Image`, `Camera`,
`Point` and `Intrinsics` are core.

#sect("A skeleton is a graph with names")

Three case classes carry a pose across the native boundary, all of them ordinary immutable Scala data.
That matters more than it sounds. `Dnn.forward` hands back a `Managed[Mat]`, and a result still
shaped like a `Mat` is a result with a deadline --- the end of the `use` block frees it.
`PoseEstimator.decode` copies the numbers out into `Float`s and `Double`s, so what you hold afterwards
outlives the frame, the blob, the tensor and the network, which is what lets the smoother later in
this chapter keep the previous frame's pose in a field without owning anything native.

#example("The pose data model, as the source declares it.")[
```scala
final case class Keypoint(name: String, point: Point, score: Float)

final case class PoseTopology(names: Seq[String], edges: Seq[(Int, Int)]):
  def size: Int = names.size

final case class Pose(keypoints: Seq[Keypoint], topology: PoseTopology):
  def apply(name: String): Option[Keypoint]
  def confident(minScore: Float = 0.3f): Seq[Keypoint]
  def meanScore: Float
  def bones(minScore: Float = 0.3f): Seq[(Point, Point)]
```
]

A `Keypoint` is one landmark: its name, its position in image pixels --- not normalised, and not in
the model's input resolution, but in the coordinate space of the frame you handed in --- and the
model's confidence. A `PoseTopology` is the index scheme. A `Pose` is the pair, and it is where two
`require`s live, both firing at construction rather than at use. `PoseTopology` rejects an edge
indexing past the end of `names`. `Pose` rejects a keypoint count that disagrees with `topology.size`,
quoting both numbers --- without it the mismatch surfaces later as an `IndexOutOfBoundsException` from
`bones`, whose edge indices are validated against the topology rather than the keypoints, or from
`GestureRecognizer.recognize`, and both blame the wrong line.

#figure-table([What you can ask a `Pose`, and what comes back.])[
#tbl(
  columns: (auto, 1fr),
  [*Call*], [*Result*],
  [`pose(name)`], [`Option[Keypoint]` --- the named landmark, or `None` if the model reported none under that name],
  [`pose.confident(minScore)`], [the keypoints at or above `minScore`; the default is `0.3f`],
  [`pose.meanScore`], [mean confidence over every keypoint, `0f` for an empty pose --- a cheap "is anyone there"],
  [`pose.bones(minScore)`], [`Seq[(Point, Point)]` --- the edges whose #emph[both] endpoints clear `minScore`],
)
]

`bones` is the one worth reading twice. It keeps a bone only when both endpoints are confident, so a
half-detected limb draws nothing rather than drawing a line into the empty part of the frame where a
low-confidence wrist was hallucinated --- the difference between an overlay that looks like a skeleton
and one that looks like a scribble.

#sect("The two topologies that ship")

`PoseTopology.CocoBody17` is the seventeen-keypoint COCO body layout that both MoveNet and
OpenPose(COCO) emit. Its names, in the model's output order, are `nose`, `left_eye`, `right_eye`,
`left_ear`, `right_ear`, `left_shoulder`, `right_shoulder`, `left_elbow`, `right_elbow`, `left_wrist`,
`right_wrist`, `left_hip`, `right_hip`, `left_knee`, `right_knee`, `left_ankle`, `right_ankle`. Its
sixteen edges are grouped in the source the way you would draw them: four for the head, one across the
shoulders, two per arm, three for the torso box, two per leg.

`PoseTopology.Hand21` is the twenty-one-landmark hand layout MediaPipe Hands uses, and it is generated
rather than tabulated --- a wrist followed by five fingers of four points each:

#example([`Hand21` is built, not listed.])[
```scala
val fingers = Seq("thumb", "index", "middle", "ring", "pinky")
val joints  = Seq("cmc", "mcp", "ip", "tip")
val names   = "wrist" +: fingers.flatMap(f => joints.map(j => s"${f}_$j"))
```
]

Landmark 0 is `wrist`, 1 through 4 the thumb ending at `thumb_tip`, and so on to `pinky_tip` at 20.
The joint names are the thumb's --- the other fingers are anatomically `mcp`/`pip`/`dip`/`tip`, and the
source says so in a comment --- because four points per finger is what the skeleton needs. The twenty
edges follow the same pattern: a bone from the wrist to each finger's base, then three along the
finger. If your model has a different keypoint set, build your own; `PoseTopology(names, edges)` is
public and validates its edges immediately.

#warning[
A topology whose `names` are in the wrong order decodes perfectly and labels every point wrong. The
tensor carries no names, so there is nothing scalacv can compare against --- `pose("left_wrist")` will
cheerfully return the right ankle. Write the topology from the model card, next to the code that loads
the model.
]

#sect("Two output layouts, one decode")

One `decode` serves both MoveNet and OpenPose because the difference between them is the encoding, not
the keypoints. `KeypointLayout` names the two shapes that matter.

#figure-table([The two tensor encodings `PoseEstimator.decode` understands.])[
#tbl(
  columns: (auto, auto, 1fr, auto),
  [*`KeypointLayout`*], [*Tensor*], [*How one keypoint is read*], [*Typical model*],
  [`Regression`], [`[1, 1, K, 3]`], [a row of `(y, x, score)`, normalised to `[0, 1]` and scaled up by `imageSize`], [MoveNet],
  [`Heatmap`], [`[1, K, H, W]`], [the arg-max cell of that keypoint's own `H × W` plane], [OpenPose],
)
]

Note the axis order in the regression case: the row is `y` then `x`, and `decode` puts them the right
way round for you. That transposition is a classic afternoon lost to a skeleton that is a reflection
of a skeleton.

#example("The decode signature. The topology defaults to the body layout.")[
```scala
def decode(
    output: Mat,
    imageSize: Size,
    layout: KeypointLayout,
    topology: PoseTopology = PoseTopology.CocoBody17
): Pose
```
]

`imageSize` is the size you want the keypoints expressed in --- almost always the frame's own
`img.size`, not the network's input size. You blobbed a 1280×720 frame down to 192×192 to run it, and
you want the answer back in the frame's coordinates so the overlay lands on the person.

Both decoders validate the tensor before reshaping it, because OpenCV does not. A `Regression` output
whose `total()` is not `K × 3`, or a `Heatmap` output that is not four-dimensional, raises a
`CvError.NativeCall` naming the mismatch --- "this is not the regression pose model this topology
decodes" --- instead of letting `reshape` throw a raw `CvException` about total sizes. Note the verb:
`decode` returns a bare `Pose`, not an `Either`, so this `CvError` arrives #emph[thrown]. It is not
something the environment did to you, which is what Chapter 6 reserves a `Left` for, but a
disagreement between two arguments you chose --- the wrong `KeypointLayout`, or a topology whose
`size` does not match the network. The heatmap path checks that second one on its own, with a
`require` comparing the tensor's `K` against `topology.size` before it reads a plane.

#sidebar("Why you get one pose, not several")[
Both decoders return exactly one `Pose`. The regression path insists on `K × 3` values in total, which
is one person's worth; the heatmap path takes the arg-max of each plane, and an arg-max has one answer
even when the frame holds four people --- so a crowd yields a skeleton assembled from several bodies.

That is a deliberate boundary. Multi-person OpenPose does not stop at heatmaps: it also emits part
affinity fields, and turning those into per-person skeletons is a bipartite matching problem with its
own tuning, not a decode. For multiple people, run a single-person model once per detected person box
--- detect, crop, estimate, map the keypoints back into frame coordinates --- or write your own
grouping over the output tensor `Dnn.forward` copies out for you.
]

#subsect("Choosing between them")

MoveNet-style regression is the layout to prefer when you have the choice. It is single-person by
construction, its output is seventeen rows of three floats rather than seventeen full-resolution
planes, and the decode is a `reshape` and a loop with no arg-max scan over `H × W` cells per keypoint.
The Lightning export takes a 192×192 RGB input; the heavier Thunder export trades that input size for
accuracy. Take the input size, the scale factor and the mean from the model card, not from this book:
they are properties of the export, not of the layout.

Heatmaps are what you already have if you inherited an OpenPose pipeline, and they carry more than the
decode uses --- the peak's neighbourhood, the second peak, the affinity fields. For one skeleton the
arg-max extracts the useful part and ignores the rest.

#sidebar("Arg-max is a quantised estimate")[
The heatmap decode maps a cell index straight into image coordinates: `px / w × imageWidth`. A
keypoint's precision is therefore one heatmap cell, blown up by the ratio between the frame and the
heatmap. Take an export with 46×46 planes --- what a stride-8 network makes of a 368-pixel input ---
over a 1280-pixel-wide frame: every `x` lands on a 28-pixel grid, and a joint drifting across a cell
boundary moves 28 pixels in one frame while the person moved one.

Refinements exist --- fit a parabola to the peak and its neighbours, or read an offset head if the
model has one --- and neither is in `decode`, because both are model-specific. For a live overlay the
practical answer is the temporal smoothing later in this chapter, which turns a 28-pixel jump into a
few frames of glide.
]

#sect("From frame to skeleton")

The full path is blob, forward, decode, and Chapter 26 covers the first two. When you need neither
intermediate, `Image.estimatePose` collapses all three into one call --- the pose counterpart to
`image.faces(detector)`:

#example("The one-call form, with the defaults the extension declares.")[
```scala
def estimatePose(
    net: Net,
    inputSize: Size,
    layout: KeypointLayout,
    topology: PoseTopology = PoseTopology.CocoBody17,
    scaleFactor: Double = 1.0 / 255,
    mean: Scalar = Scalar(0, 0, 0),
    swapRB: Boolean = true
): Pose
```
]

Two of those defaults deliberately disagree with `Dnn.blobFromImage`, which defaults `scaleFactor` to
`1.0` and `swapRB` to `false` because those are OpenCV's own. Here they are aimed at a MoveNet-style
export --- RGB input, values scaled into `[0, 1]` --- because that is the model this method exists to
make easy. They remain model-specific knobs: pass what your card documents.

`estimatePose` borrows both arguments. The image stays alive and the `Net` is not released, which is
what lets you call it once per frame with the network living outside the loop.

#memory[
A `Net` is one of the 185 generated OpenCV types with no public `release()`; scalacv frees it through
the `delete(long)` bridge, so `Dnn.fromOnnx` hands back a `Managed[Net]` you must scope. Load the
model #emph[outside] the frame loop. A `fromOnnx` inside `foreach` leaks one network per frame, and an
ONNX graph is not a rounding error: that is the difference between a process that runs for days and
one killed at the end of the first clip.
]

#subsect("Drawing it")

`img.drawSkeleton(pose)` is a drawing transform in the sense of Chapter 14. It draws
`pose.bones(minScore)` as two-pixel lines in `color` --- green by default --- and
`pose.confident(minScore)` as filled three-pixel circles in `jointColor`, red by default. The topology
does all the work: `bones` is the only thing that knows an elbow connects to a wrist, so a different
topology changes the drawing without touching the call.

Being a transform, it #emph[consumes] its receiver, and the move semantics from Chapter 4 apply
without exception. That combines with `Camera.foreach` --- which hands you an owned `Image` and closes
it when your block returns --- in a way that catches people out:

#example("Wrong. The annotated frame is never released.")[
```scala
cam.foreach() { frame =>
  val pose = frame.estimatePose(net, Size(192, 192), KeypointLayout.Regression)
  frame.drawSkeleton(pose)          // consumes `frame`, returns a new Image — dropped
}
```
]

`drawSkeleton` takes the Mat out of `frame`'s handle without freeing it, mutates it, and rewraps it in
a fresh `Image`. The `frame.close()` that `foreach` runs afterwards finds a spent handle and does
nothing --- correctly, since it now owns nothing --- while the annotated `Image` you discarded still
owns the pixels: one frame's worth of leak per frame, at video rates. End the chain in a terminal:

#example([Right. `write` is a terminal: it releases what it wrote.])[
```scala
var n = 0
cam.foreach() { frame =>
  val pose = frame.estimatePose(net, Size(192, 192), KeypointLayout.Regression)
  frame.drawSkeleton(pose, minScore = 0.3f).write(s"frames/$n.png")
  n += 1
}
```
]

#memory[
The rule generalises past `drawSkeleton`: any transform inside a `foreach` block moves ownership out
of the `Image` the loop was going to close for you. Finish with a terminal (`write`, `bytes`,
`close`), or hand the result to a `Recorder`. Nothing throws in the listing above, which is what makes
the leak quiet; the neighbouring mistake --- touching `frame` again after the transform --- does throw,
and under `-Dscalacv.trackOwnership=true` that `IllegalStateException` carries the stack of the call
that spent the handle as its cause.
]

#sect("Head pose, and six matrices at once")

Head pose is the estimator with no model file. It takes the five landmarks a `Face` from the YuNet
detector already carries --- right eye, left eye, nose tip, right mouth corner, left mouth corner,
where "right" is the subject's right and therefore the image's left --- and solves for the orientation
of a canonical 3D head that would project to those points.

That reference is five `Point3`s in arbitrary units, nose tip at the origin, `x` to the image right,
`y` down and `z` away from the camera: eyes at `(±45, -34, 27)`, nose tip at `(0, 0, 0)`, mouth
corners at `(±30, 35, 22)`. It is a generic head, not yours, which is the first and largest reason the
answer is indicative rather than metric.

The second input is the camera. `solvePnP` cannot turn pixels into angles without a focal length and a
principal point, so `HeadPose.estimate` takes an `Intrinsics` --- the pinhole model Chapter 31
measures with a chessboard. A second overload takes a `Size` and fabricates one: focal length equal to
the image width, principal point centred, no distortion. It exists so you can run head pose the moment
you have a face, and it is a guess.

#example("Head pose over a detected face, with a camera model.")[
```scala
FaceDetect.create("models/face_detection_yunet_2023mar.onnx", Size(320, 320)).flatMap { detector =>
  detector.use { yunet =>
    Image.reading("portrait.jpg") { img =>
      val intrinsics = Intrinsics.approx(img.size, horizontalFovDegrees = 60)
      img.faces(yunet).flatMap(face => HeadPose.estimate(face, intrinsics))
    }
  }
}
```
]

#subsect("What the call actually owns")

`solvePnP` wants object points, image points, a camera matrix, distortion coefficients and two output
vectors --- six `Mat`s alive at once --- and `Rodrigues` and `RQDecomp3x3` want three more. None is
returned to the caller; every one has to be freed, including where a constructor throws part-way
through.

Nested `Managed.use` blocks would bury the two interesting lines under nine levels of indentation, and
the alternative people reach for instead --- `val`s followed by `try`/`finally` --- has a real hole,
since every allocation before the `try` is unguarded. `Managed.scope` from Chapter 5 is both: `own`
registers each object the moment it exists, so a throw anywhere releases everything acquired so far,
in reverse order.

#example([The body of `HeadPose.estimate`, with the `org.opencv.calib3d.Calib3d` prefix shortened.])[
```scala
Managed.scope: own =>
  val objectPoints = own(MatOfPoint3f(model*))
  val imagePoints  = own(MatOfPoint2f(face.landmarks.map(_.toCv)*))
  val camera       = own(intrinsics.cameraMatrix)
  val distortion   = own(intrinsics.distCoeffs)
  val rvec         = own(Mat())
  val tvec         = own(Mat())
  Cv.attempt("solvePnP") {
    val ok = Calib3d.solvePnP(
      objectPoints, imagePoints, camera, distortion,
      rvec, tvec, false, Calib3d.SOLVEPNP_EPNP
    )
    if !ok then None
    else
      val rotation = own(Mat())
      Calib3d.Rodrigues(rvec, rotation)
      val euler = Calib3d.RQDecomp3x3(rotation, own(Mat()), own(Mat()))
      Some(HeadPose(yaw = euler(1), pitch = euler(0), roll = euler(2)))
  }.getOrElse(None)
```
]

Three details repay a pause. `own(Mat())` appears twice as an argument to `RQDecomp3x3`, because
OpenCV needs somewhere to write the decomposition's factor matrices and this code wants neither ---
registering them inline says "allocate this, free it, never look at it". The block runs inside
`Cv.attempt` because degenerate landmarks can make OpenCV throw rather than return `ok = false`, and
the contract is `None` on failure, so `.getOrElse(None)` folds both failure shapes together. And
nothing acquired in the scope escapes: what comes out is a `HeadPose`, three `Double`s.

#memory[
`Managed.scope` releases everything `own` registered, exactly once, at the end of the block --- which
is why `own` hands back the bare object rather than a `Managed`. A scoped handle has no second owner
to defend against. The corollary is the rule you must not break: never return one of these objects
from the block and never stash one in a field. Return plain data.
]

#subsect("Yaw, pitch and roll, and the sign that flips")

`RQDecomp3x3` returns Euler angles in degrees about the `x`, `y` and `z` axes, and `HeadPose` renames
them for the head: `pitch` is the nod, `yaw` the turn, `roll` the tilt toward a shoulder. For "is this
person looking at the screen" they are enough.

They are not enough for a number on a chart. The five landmarks sit on a shallow, nearly planar patch
of the face, and near-planar point sets are where perspective-n-point is weakest: two solutions --- a
head turned slightly left and one turned slightly right --- project to almost the same five pixels. A
pixel or two of landmark noise moves the solver between them, so an uncalibrated yaw near zero can
change sign between consecutive frames of a person sitting still. The model points are a generic head,
`SOLVEPNP_EPNP` runs with no refinement pass, and the Euler decomposition has a branch that flips near
the poles. Trust "looking left", "looking up", "head cocked". Do not trust "14.2 degrees" --- for that
you want a dedicated head-pose network through `Dnn` and a calibrated camera.

#sect("Gestures: geometry, not weights")

A gesture recogniser sounds like a model and is not. `GestureRecognizer` has no weights, no state and
no `Net`: it is a deterministic function from a twenty-one-landmark hand pose to a name, testable with
a hand-built `Pose` and an assertion. It knows six answers, and the enum is closed:

#example("The whole vocabulary.")[
```scala
enum HandGesture:
  case Fist, OpenPalm, ThumbsUp, Pointing, Victory, Unknown
```
]

One geometric idea decides everything. For each finger, compare the distance from the wrist to the
fingertip against the distance from the wrist to that finger's middle joint: tip farther means
extended, tip nearer means curled. The comparison is orientation-independent --- it holds whichever
way the hand is rotated in frame, which is why it survives a hand held sideways where a "tip is above
the joint" rule would not.

#figure-table([The landmark pairs the extension rule compares, by `Hand21` index.])[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [*Finger*], [*Tip*], [*Joint*], [*`Hand21` names*],
  [Thumb], [`4`], [`2`], [`thumb_tip` vs `thumb_mcp`],
  [Index], [`8`], [`6`], [`index_tip` vs `index_mcp`],
  [Middle], [`12`], [`10`], [`middle_tip` vs `middle_mcp`],
  [Ring], [`16`], [`14`], [`ring_tip` vs `ring_mcp`],
  [Pinky], [`20`], [`18`], [`pinky_tip` vs `pinky_mcp`],
)
]

The joint is always the landmark two before the tip. Under `Hand21`'s uniform naming that reads
`..._mcp`; in MediaPipe's anatomy the non-thumb ones are the PIP joints. The index is what the code
uses, so the difference costs nothing but a moment of confusion when you print them.

`minScore`, defaulting to `0.3f`, gates the whole thing: a tip reported below that confidence counts
as not extended, wherever it sits. That is the right failure direction: an occluded ring and pinky
that the network placed by guesswork would put the count at four and turn a `Victory` into an
`OpenPalm`, and gating on the tip's score is what stops them.

From the five booleans the mapping is one pattern match. No finger extended is a `Fist`; the thumb
alone is `ThumbsUp`; the index alone `Pointing`; index and middle together `Victory`; four or more
fingers out `OpenPalm`. Everything else is `Unknown` --- not a failure, but the honest answer for a
shape outside a six-word vocabulary, including every shape a hand passes through between two named
ones.

#warning[
`recognize` requires a 21-landmark pose and says so with a `require`. Hand it a `CocoBody17` pose and
it fails at the call, naming both sizes, rather than reading five body keypoints as fingers and
returning a confident, meaningless `Pointing`.
]

#subsect("A gesture is a trajectory; this layer sees a frame")

The word "gesture" promises more than a per-frame rule can deliver. A held handshape --- a thumbs-up,
a fingerspelled letter --- is a single hand configuration, which is exactly what `GestureRecognizer`
reads. A wave, a swipe, or almost any sign in a signed language is movement: the meaning lives in how
the landmarks travel over time, and no function of one frame can answer it.

scalacv's role there is upstream. You accumulate decoded poses in a sliding window, flatten each into
its `(x, y)` pairs, and hand the window to a temporal classifier --- an LSTM or a transformer,
exported to ONNX and run through `Dnn` --- that you train. The library gives per-frame landmark
extraction and a small static vocabulary; the sequence model is yours, and nothing here ships one.

What you #emph[can] build from the static layer is the thing most interfaces actually want: a gesture
that has #emph[settled]. That is temporal too, but it fits in ten lines.

#sect("Making it steady enough to act on")

Three techniques turn a jittering per-frame reading into something you would let control a machine.
None is in the library: all three depend on your frame rate and your tolerance.

#minor("Filter by confidence, twice")

`pose.meanScore` is the frame-level gate: near zero means no hand in view, and running a gesture rule
over a pose the network invented is worse than skipping the frame. `minScore` is the landmark-level
gate inside `recognize`, `confident` and `bones`. Use both.

#minor("Smooth the landmarks, not the answer")

Majority-voting over `HandGesture` values throws away the sub-pixel information that would have
prevented the flapping in the first place. Smooth the geometry instead --- an exponential moving
average per keypoint is enough, and because `Pose` is immutable data it is a `map`:

#example("An EMA over poses. Low-confidence points hold their last position instead of jumping.")[
```scala
final class PoseSmoother(alpha: Double = 0.4, minScore: Float = 0.3f):
  private var previous: Option[Pose] = None

  def apply(pose: Pose): Pose =
    val smoothed = previous match
      case None => pose
      case Some(prev) =>
        val kps = pose.keypoints.zip(prev.keypoints).map: (now, was) =>
          val a = if now.score >= minScore then alpha else 0.0
          val x = was.point.x + a * (now.point.x - was.point.x)
          val y = was.point.y + a * (now.point.y - was.point.y)
          now.copy(point = Point(x, y))
        Pose(kps, pose.topology)
    previous = Some(smoothed)
    smoothed
```
]

A smaller `alpha` is steadier and laggier; `0.4` is a reasonable start at thirty frames per second.
An unconfident keypoint gets `a = 0`, so it keeps its previous position rather than teleporting to
wherever the network guessed --- usually what you want for an overlay, and exactly what you do not
want if the joint is genuinely moving fast. Every filter makes that trade.

The `zip` quietly assumes both poses share a topology. If they ever do not, it truncates to the
shorter, and the `Pose` it then builds fails the constructor `require` from the first section of this
chapter --- loudly, at the frame where the topologies diverged, instead of producing a skeleton with
the wrong names on it.

#minor("Normalise before you compare")

Raw pixel coordinates encode how far the subject is from the camera. A shoulder-to-elbow distance of
90 pixels means one thing at two metres and another at four, so a threshold written against raw pixels
is a threshold against distance. Before comparing shapes across frames, subjects or sessions,
translate the keypoints relative to an anchor --- the wrist for a hand, the hip midpoint for a body
--- and divide by a span that scales with the subject: for a hand, landmark 0 to landmark 9, the wrist
to the middle finger's knuckle, which barely changes with the handshape.

Angles need this less than distances do, which is the argument for working in angles: the angle at an
elbow, from three keypoints, is already scale-invariant. Compute it from smoothed points even so,
because an angle amplifies the noise in both limbs that form it.

#sect("The session monitor")

Everything above assembles into the running example: read a recorded session, decode a hand pose per
frame, smooth it, and fire an event exactly once when a gesture becomes stable --- not on every frame
it is held, and not during the `Unknown` shapes a hand passes through on the way. The latch that turns
a per-frame reading into an event counts consecutive agreeing frames and reports only the transition:

#example([Debounce: report a gesture once, when it has held for `hold` frames.])[
```scala
final class GestureLatch(hold: Int = 4):
  private var candidate: HandGesture = HandGesture.Unknown
  private var run = 0
  private var reported: HandGesture = HandGesture.Unknown

  /** Some(gesture) on the frame it becomes stable; None on every other frame. */
  def update(gesture: HandGesture): Option[HandGesture] =
    if gesture == candidate then run += 1
    else
      candidate = gesture
      run = 1
    if run >= hold && candidate != reported then
      reported = candidate
      Some(candidate)
    else None
```
]

At thirty frames per second, `hold = 4` is about 130 milliseconds of steadiness --- long enough to
reject transitional shapes, short enough that the interface still feels immediate. Tune it against
your frame rate, not against a number in a book.

#example("The whole monitor: video in, gesture events out.")[
```scala
Dnn.fromOnnx("models/hand_landmark.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    val smoother = PoseSmoother(alpha = 0.4)
    val latch    = GestureLatch(hold = 4)

    Camera.usingFile("session.mp4") { cam =>
      cam.foreach() { frame =>
        val raw = frame.estimatePose(
          net,
          inputSize = Size(224, 224),
          layout = KeypointLayout.Regression,
          topology = PoseTopology.Hand21
        )
        if raw.meanScore >= 0.3f then
          val pose = smoother(raw)
          latch.update(GestureRecognizer.recognize(pose)).foreach {
            case HandGesture.Victory => println("start")
            case HandGesture.Fist    => println("stop")
            case other               => println(s"ignored: $other")
          }
      }
    }
  }
}
```
]

Trace the lifetimes once, because they are the point. `fromOnnx` yields a `Managed[Net]` and `use`
releases the network when the video ends, on an exception, on anything. `Camera.usingFile` closes the
capture the same way and returns an `Either[CvError, Unit]`, so a missing file is a value rather than
a surprise. `foreach` hands over an owned `Image` per frame and closes it, and this block never
consumes it --- `estimatePose` only borrows `frame.mat` --- so there is nothing to leak; the blob and
the output tensor live and die inside that call. The only long-lived native object is the one `Net`.

The `Pose` values crossing between iterations are plain data, so `PoseSmoother` holds the previous
frame's with no risk: there is nothing native left in it to dangle.

#sect("Where the numbers come from")

Both of this chapter's estimators asked for something they could not produce. The gesture layer wanted
landmarks, which came from a model Chapter 23 showed you how to fetch and verify and Chapter 26 how to
load. Head pose wanted an `Intrinsics`, and every example here either guessed one from the image width
or approximated one from a field-of-view estimate --- serviceable for "the head is turned", not for a
measurement. Chapter 31, on camera calibration, is where that guess becomes a chessboard, a set of
views, and the focal lengths, principal point and distortion coefficients of your actual lens.

The debt the smoother and the latch left is nearer. Both are per-frame machines wearing a thin coat of
memory: the smoother remembers one pose, the latch remembers one gesture, and neither has any notion
of #emph[which] hand it is watching. Put a second hand in the frame and a single-person decode hops
between them, silently; the smoother averages two hands into one, and the latch reports the hop as an
event. Chapter 30, #emph[Tracking], is the piece that fixes it --- giving per-frame detections an identity
that survives the next frame, so the state a smoother or a latch carries can belong to an object
rather than to the camera.
