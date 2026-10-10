#import "../lib/book.typ": *

#chapter("Camera Calibration", subtitle: [What your lens actually is, measured rather than assumed.])

The marker overlay from Chapter 28 tracked beautifully. It stuck to the tag through rotation, it
survived motion blur, it looked --- on screen --- like a solved problem. Then somebody read the
distance off it. The tag was 41 cm from the lens; the pose said 45. Moving the tag to the corner of
the frame made it worse, and tilting it away from the camera made it worse again, in a way that was
smooth and repeatable and therefore not noise. Nothing was broken. The number was the honest output
of a camera model that had been guessed rather than measured.

That guess is `Intrinsics.approx`. It fabricates a camera from two things you already know --- the
image size and a rough field of view --- and two things it assumes: that the optical centre is the
centre of the sensor, and that the lens bends nothing. Both assumptions are wrong for every real
camera, and the second one is spectacularly wrong for the wide-angle modules that go into webcams,
dashcams and phones. A pose solver handed a wrong camera does not fail; it returns the pose that
would be correct *if* the camera were the one it was told about. The error comes back looking exactly
like a measurement.

The fix is a measurement of your own. Show the camera an object whose geometry you already know ---
a flat chessboard with squares of a known size --- from enough angles that the only self-consistent
explanation of what landed on the sensor is one particular focal length, one particular optical
centre, and one particular set of distortion coefficients. That is calibration, and it is a physical
procedure with a software step at the end, not the other way round. This chapter spends as much time
on how to hold the board as on which method to call, because the method is three lines and the
holding is where calibrations go wrong.

The running example is a bench rig: a fixed camera on an arm above a workbench, an ArUco tag of known
edge length on every part that passes under it, and a requirement to report each part's height in
millimetres to a tolerance the shop floor will accept. `Ar.estimatePose` from Chapter 28 takes exactly
one input this chapter produces.

#sect("The camera as a function")

A pinhole camera is a projection. A point sitting at $(X, Y, Z)$ in front of the lens, measured in
the camera's own frame with $Z$ running out along the optical axis, lands at the pixel

```text
u = fx · X/Z + cx
v = fy · Y/Z + cy
```

Four numbers, and they are the whole ideal camera: `fx`, `fy`, `cx`, `cy`. Written as the matrix
everyone calls $K$, they sit like this --- the exact layout `Intrinsics` writes into a 3×3 `CV_64F`
Mat internally whenever a native call needs one:

```text
      | fx   0  cx |
  K = |  0  fy  cy |
      |  0   0   1 |
```

`fx` and `fy` are the focal length expressed in pixels, not millimetres --- which trips up everyone
arriving from photography, where a lens is 24 mm or 50 mm and that is a property of the glass alone.
A focal length in pixels is a property of the glass *and* the sensor behind it: the optical focal
length divided by the physical size of one pixel. Bolt the same 6 mm lens onto a sensor with 3 µm
pixels and you get `fx = 2000`; put it on 6 µm pixels and you get `fx = 1000`. The pixel form is the
useful one because it turns a metric ratio into a pixel coordinate without ever needing the sensor's
dimensions. The two differ only when pixels are not square, so a recovered pair that disagrees by
more than a percent or two is a sign the fit went somewhere strange.

`cx` and `cy` are the principal point: where the optical axis actually pierces the sensor. It is
*near* the image centre and reliably not at it, because the lens assembly was placed by a machine
with a tolerance, and that tolerance is applied per unit, not per model. The offset is small enough
to be invisible in a picture and large enough to move a pose estimate, which is precisely why
guessing it costs you.

#sidebar("Why a longer lens has a bigger `fx`")[
`Intrinsics.approx` computes the focal length from the horizontal field of view with

```text
f = (width / 2) / tan(fov / 2)
```

so at 1280 px wide, a 60° field of view gives `fx ≈ 1109` and a 30° field of view gives
`fx ≈ 2389`. Narrower angle, longer lens, larger `fx`: the number grows as the camera sees less. If
your intuition says a wide lens should have the big number because it captures more, keep the formula
instead --- `fx` is how many pixels one unit of angle buys you, and a telephoto buys a great many.
]

#sect("What the lens does to a straight line")

The pinhole model has no glass in it. Real glass bends rays by an amount that grows with how far
off-axis they are, and the standard correction --- the one OpenCV, and therefore `Intrinsics`,
uses --- is a polynomial in the radius $r$ from the principal point:

- *Radial* terms `k1`, `k2`, `k3` multiply the point's distance from the centre by
  $1 + k_1 r^2 + k_2 r^4 + k_3 r^6$. A negative `k1` pulls the edges of the frame inward: straight
  lines near the border bow *outward* away from the centre, which is barrel distortion, and it is
  what almost every wide webcam has. A positive `k1` does the opposite --- pincushion, straight lines
  sagging *towards* the centre --- and shows up more on the long end of a zoom.
- *Tangential* terms `p1`, `p2` account for the lens not being exactly parallel to the sensor. They
  are small, they are asymmetric, and they are the reason your straightened image is still very
  slightly skewed if you leave them out.

The visible signature is always the same: a straight edge in the world arrives as a curve. Photograph
a door frame at the edge of a wide shot and the jamb bows. That bow is a systematic error of several
pixels at the frame border, and every geometric method in the vision layer treats a corner position
as ground truth --- so a few pixels of bend becomes a few percent of error in a distance.

#sect("The `Intrinsics` type")

This chapter straddles two artifacts, and it is worth being exact about which. `Intrinsics` and
`Image.undistort(intrinsics)` live in the core module --- `Intrinsics.scala`, `Image.scala` and
`Ops.scala`, all under `core/src/scalacv/` --- because `undistort` is the one core operation that
needs a camera model, and nothing above it should have to be on the classpath to describe a lens.
`ChessboardPattern`, `Calibration`, `Calibration.findCorners`, `Calibration.fromChessboard` and the
`undistort(calibration)` extension are all in `vision/src/scalacv/vision/Calibration.scala`, so *producing* a
calibration needs `com.worxbend::scalacv-vision:0.4.1` on the classpath beside the core dependency of
Chapter 2 --- the same artifact Chapters 24 through 30 already asked for. Add
`import scalacv.vision.*` alongside `import scalacv.*`; the optional module has its own package.

`Intrinsics` itself is a plain case class: four doubles and a sequence.

#example("The whole constructor, with an ideal lens and with a real one.")[
```scala
final case class Intrinsics(
    fx: Double,
    fy: Double,
    cx: Double,
    cy: Double,
    distortion: Seq[Double] = Seq.empty
)

// An ideal pinhole — no glass, no bend.
val ideal = Intrinsics(1109.0, 1109.0, 640.0, 360.0)

// A measured wide webcam: k1, k2, p1, p2, k3.
val bench = Intrinsics(
  fx = 1043.7, fy = 1041.9, cx = 651.2, cy = 358.4,
  distortion = Seq(-0.2831, 0.0967, 0.0004, -0.0011, -0.0142)
)
```
]

`distortion` defaults to empty, which means an ideal lens: the internal `distCoeffs` accessor hands
OpenCV an empty `MatOfDouble`, every operation that consumes an `Intrinsics` reads that as "nothing to
correct", and `Image.undistort` gives back pixels indistinguishable from a copy.

Two `require` checks run in the constructor. The first is obvious --- `fx` and `fy` must be positive,
because a camera with a non-positive focal length is not a camera. The second is the interesting one.

#subsect("The distortion vector has legal lengths")

OpenCV's distortion model is extensible: four coefficients or fourteen, each legal count switching on
another block of the model. `Intrinsics` publishes the accepted counts as a value you can read:

```scala
val ValidDistortionSizes: Seq[Int] = Seq(0, 4, 5, 8, 12, 14)
```

#figure-table("The coefficient counts `Intrinsics` accepts, and what each one adds.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Size*], [*Coefficients*], [*Model*],
  [0], [--], [an ideal lens; `undistort` becomes a copy],
  [4], [`k1, k2, p1, p2`], [radial to $r^4$ plus tangential --- the usual minimum],
  [5], [`+ k3`], [radial to $r^6$; what `calibrateCamera` returns by default],
  [8], [`+ k4, k5, k6`], [the rational model, for strongly curved wide lenses],
  [12], [`+ s1, s2, s3, s4`], [thin-prism terms],
  [14], [`+ taux, tauy`], [tilted-sensor terms],
)
]

Here is the mistake the check exists for. You read that radial distortion is `k1`, `k2`, `k3`,
you have three radial numbers from somewhere, and you write:

```scala
Intrinsics(1043.7, 1041.9, 651.2, 358.4, Seq(-0.2831, 0.0967, -0.0142))
```

Three coefficients is not a length OpenCV has a meaning for, and the constructor throws an
`IllegalArgumentException` naming the legal counts and the layout
`k1, k2, p1, p2[, k3[, k4, k5, k6[, s1, s2, s3, s4[, taux, tauy]]]]`. The correct vector is five long,
with the tangential pair in the middle: `Seq(-0.2831, 0.0967, 0.0004, -0.0011, -0.0142)`.

Why check at all, rather than let the native call complain? Because it does not complain usefully.
Hand `undistort` or `solvePnP` a badly-shaped vector whose length happens to be one OpenCV
recognises, and the call runs to completion and gives back an image or a pose that is quietly,
plausibly wrong --- the most expensive failure mode there is, a number that looks like an answer.
Rejecting at construction moves the error to the line where the bad value was written rather than to
a solver three layers away. It is the same argument as the focal-length check above it: refuse what
cannot be a camera, at the point the camera is made.

#warning[The check catches shapes, not values. A five-element vector with one coefficient
mistyped, or with `k3` swapped into a tangential slot, is still five elements long, so the
constructor accepts it and the results are wrong in exactly the way described above. The only real
defence is to take the vector from a calibration rather than from a spreadsheet, which is what the
rest of this chapter is about.]

`Intrinsics.approx(imageSize, horizontalFovDegrees = 60.0)` remains what it always was: a centred
principal point, square pixels, no distortion, and a focal length from the field-of-view formula in
the sidebar. Keep it as the placeholder that makes a pipeline run before the camera has been
measured, and treat every metric number that comes out of a pipeline running on it as indicative.

#sect("Describing the board")

The target is a printed chessboard, and what the detector looks for is the *inner corners* --- the
points where four squares meet. The outer edge of the printed pattern is not a corner of that kind,
so a board of 10×7 squares presents a 9×6 grid of inner corners, and 9×6 is what you pass.

#example("The bench rig's target: a 10 x 7 board with 25 mm squares.")[
```scala
val board = ChessboardPattern(columns = 9, rows = 6, squareSize = 0.025)

board.corners   // 54 — the number of inner corners the detector must find, all of them
```
]

`columns` and `rows` must each be at least 2, and `squareSize` must be positive; both are enforced in
the constructor. `squareSize` sets the unit of the world and only that: pass metres and the poses
downstream come back in metres. It has no effect whatever on `fx`, `fy`, `cx` or `cy`, which are in
pixels no matter what you feed the board. The default `1.0` means "board squares", which is fine when
the intrinsics are all you want.

Prefer an asymmetric inner grid, 9×6 rather than 8×8. A square grid has a rotational ambiguity ---
nothing says which corner the detector called the origin from one view to the next --- while an
odd-by-even grid has exactly one consistent orientation.

#sect("Finding the board in a frame")

`Calibration.findCorners` locates the whole inner grid in one image and refines every corner to
sub-pixel precision with `cornerSubPix`. It returns `Option[Seq[Point]]`, and the `Option` is the
point: the detector is all-or-nothing. If one corner is occluded by your thumb, or the board runs off
the edge of the frame, you get `None` rather than a partial grid.

```scala
Calibration.findCorners(frame, board) match
  case Some(corners) => // exactly board.corners of them, in row-major order
  case None          => // the whole board is not in this frame
```

Two decisions inside `findCorners` set what the physical setup has to provide. The search runs
`findChessboardCorners` with `CALIB_CB_ADAPTIVE_THRESH | CALIB_CB_NORMALIZE_IMAGE`: the first
thresholds locally, so a board lit brighter on one side than the other still binarises into squares,
and the second normalises the image gamma before the search, so the exposure does not have to be
nailed. Then `cornerSubPix` refines each corner inside an 11×11 window, stopping at thirty iterations
or a movement below `1e-3` px. That refinement is where sub-pixel accuracy comes from, and it is also
why motion blur hurts so specifically: a smeared corner has no sharp saddle for the refinement to walk
towards, so it settles somewhere confidently wrong rather than reporting nothing.

That `None` is an ordinary query result, not a failure, and the return type says so --- the same choice the
detectors in Chapter 27 and the trackers in Chapter 30 make. It is the solve step, later, where "too
few boards" genuinely is a failure and an `Either` appears.

`findCorners` borrows the image in the sense Chapter 4 gave the word: the `Image` you pass is still
alive and still yours to close. What comes back is `Seq[Point]`, immutable Scala data holding two
doubles apiece, so the corners stay valid long after the frame that produced them is released. That
is what makes the capture loop below possible.

#example("A capture tool that banks only the frames where the whole board is visible.")[
```scala
import scala.collection.mutable.ArrayBuffer

val keep = ArrayBuffer.empty[Image]

Camera.using(0) { cam =>
  cam.foreach() { frame =>
    Calibration.findCorners(frame, board) match
      case Some(_) if keep.size < 20 => keep += frame.copy
      case _                         => ()
  }
}
```
]

#memory[`cam.foreach` closes the frame the moment the block returns --- the contract from Chapter 20,
and what keeps a camera loop from growing without bound. `keep += frame` would therefore bank twenty
`Image` handles whose Mats have all been released, and the first thing `fromChessboard` touched would
throw a use-after-release from `Managed`. `frame.copy` allocates a fresh Mat that outlives the loop.
It is also twenty full-resolution frames of native memory that nothing will free for you: close every
one when the calibration is done.]

#sect("Shooting a set worth solving")

This is the part that decides whether the numbers are any good, and no care in the code substitutes
for it. Calibration recovers the camera from the *disagreements* between views: give it views that
disagree in interesting ways and the solution is pinned down, give it twenty near-identical ones and
there is nothing to work with however many of them there are.

The pathological case is a set of head-on shots. A board held flat and square to the sensor a metre
away produces almost exactly the image of the same board at half the size from two metres --- the
geometry cannot separate "the board is far away" from "the lens is long", so `fx` and distance trade
off against each other and the solver picks somewhere along that valley. Tilting breaks the tie,
because a tilted plane's foreshortening depends on focal length in a way pure scale does not. This is
why a beginner's calibration set, careful and neat and all square to the camera, is usually the worst
one.

#figure-table("The shot list for a calibration set.")[
#tbl(
  columns: (auto, 1fr),
  [*What*], [*Why*],
  [8--15 good views, more for a wide lens], [three is the solver's floor, not a target; past a point you are adding views, not information],
  [Tilt in both axes, not one], [a head-on-only set is degenerate: focal length and distance trade off],
  [Board in every part of the frame], [distortion is estimated from off-axis corners, so the corners of the frame must be visited],
  [Board large in frame, fully inside it], [a small board localises noisily; a clipped board is not detected at all],
  [Rigid, flat mount], [a board that flexes is not the geometry you told the solver about],
  [Even, diffuse light], [glare on a glossy print erases corners; the detector then finds nothing at all],
  [Short exposure, camera and board still], [motion blur moves corners systematically, not randomly],
  [Autofocus off, zoom fixed], [the intrinsics belong to one focus and one zoom setting],
)
]

Three of those earn extra words:

*The board must be rigid.* Glue the print to foam board, aluminium or glass. Paper on a clipboard
bows by a couple of millimetres when you hold it, and the solver has no representation for a curved
board --- it absorbs the bow into the distortion coefficients, which is exactly where you did not
want it.

*Measure the squares you actually printed.* Printers scale: a 25 mm square in the PDF arrives at
24.6 mm because a driver fitted the page to the paper. Put a caliper across ten squares and divide by
ten. This affects nothing about `fx` or `cx`, but it scales every downstream distance by the same
factor, silently.

*Calibrate in the state the rig will run in.* Focus, zoom and any knock to the mount change the
intrinsics, and so does the capture resolution --- which is why `Calibration` records the `imageSize`
the numbers were measured at.

#sect("Solving")

With the views in hand, the solve is one call.

#example("Recovering the bench rig's camera from a folder of captures.")[
```scala
val views: Seq[Image] = (1 to 22).flatMap(i => Image.read(s"calib/$i.png").toOption)

val result = Calibration.fromChessboard(views, board, minViews = 12)

views.foreach(_.close())

result match
  case Right(calib) =>
    val i = calib.intrinsics
    println(f"fx=${i.fx}%.1f fy=${i.fy}%.1f  centre=(${i.cx}%.1f, ${i.cy}%.1f)")
    println(f"RMS reprojection error: ${calib.reprojectionError}%.3f px  @ ${calib.imageSize}")
  case Left(err) =>
    System.err.println(err.getMessage)
```
]

`fromChessboard` runs the corner search over every view and *silently drops* the ones where the whole
board is not visible, so handing it an entire capture folder --- blurred frames, half-boards and
all --- is the intended use rather than sloppiness. `minViews` is your floor on how many must
survive. It defaults to 3, the practical minimum for the solver to be well posed and far below what
you want in production; set it near the number of good views you believe you captured, so a folder
where two thirds of the frames failed the detector reports a failure instead of quietly calibrating
from seven views.

#figure-table("Choosing `minViews`.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*`minViews`*], [*When*], [*What it buys*],
  [`3` (the default)], [a quick check in controlled light], [enough to watch the call work; the numbers wobble],
  [8--15], [a production calibration], [where the intrinsics start being worth trusting],
  [20 or more], [wide or fisheye lenses, tight tolerance], [angles enough to pin the distortion terms down],
)
]

A `minViews` below one is a programmer error rather than a data one, and `require` throws for it ---
the split Chapter 6 draws, applied to one argument: bad arguments throw, bad *data* comes back as a
`Left`.

The image size is taken from the first view, and every view should share it. The result is a
`Calibration`: the recovered `intrinsics`, that `imageSize`, and the `reprojectionError`.

#sect("Reading the reprojection error")

The reprojection error is the RMS distance, over every corner of every surviving view, between where
a corner actually sat in the image and where the recovered model says it should have sat. It is
reported alongside the intrinsics rather than buried, because a calibration you have not judged is a
calibration you should not ship.

#figure-table("How to read the RMS reprojection error.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*RMS*], [*Verdict*], [*What to look at*],
  [< 0.5 px], [excellent], [--],
  [0.5--1 px], [good --- ship it], [--],
  [1--2 px], [marginal], [some motion blur, or too few distinct angles],
  [> 2 px], [poor --- recapture], [a flexing board, bad focus, or a board that clipped the frame],
)
]

The number has one important limitation: it measures *fit*, not *accuracy*. A model with enough free
parameters can fit four near-identical views to a quarter of a pixel and still be wrong about the
focal length by five percent, because those four views never asked the question that would have
caught it. A low RMS from a rich, varied set is strong evidence; from a thin set it is almost none.
For a second opinion, calibrate twice from disjoint halves of your captures and compare the two `fx`
values --- agreement to within a percent means something RMS alone does not.

The library's own test does the honest version of that check. It renders seven views of a 9×6 board
by warping one flat rendering through homographies built from a known camera --- `fx = fy = 800`,
centre `(320, 240)`, a 640×480 frame --- so the ground truth that produced the pixels is available to
compare against. It then asserts three separate things: that the RMS comes back under one pixel, that
each recovered focal length is within 15% of 800, and that the recovered principal point is within
40 px of `(320, 240)`. Those last two tolerances are far wider than a good real calibration achieves,
and deliberately so --- they are a regression guard against the solve going wrong, not a claim about
precision. Note which number is tight and which are slack: the fit is excellent and the accuracy is
merely bounded, on synthetic views that are *exactly* the model. That is the previous paragraph, in
the test suite.

#sect("When it fails")

`fromChessboard` returns `Either[CvError, Calibration]`, and there are two distinct ways to land on
the left.

`CvError.CalibrationFailed` fires when the *input* was not enough: an empty `views` sequence gives
`"no views were provided"`, and a run where fewer than `minViews` frames showed the whole grid gives
a message naming the shortfall --- views passed, views that contained the board, and the grid it was
looking for. Both are facts about what the capture happened to catch rather than programmer errors,
which is why they are values.

The second way is a throw from OpenCV's own `calibrateCamera`, and it does *not* arrive as
`CalibrationFailed`. It is caught and wrapped as `CvError.NativeCall("calibrateCamera", cause)`, per
the error model in Chapter 6. So this match is a trap:

```scala
Calibration.fromChessboard(views, board) match
  case Right(calib)                      => use(calib)
  case Left(CvError.CalibrationFailed(m)) => retryCapture(m)
  // MatchError when the native solver is the thing that failed
```

Match the sum type, not one member of it:

```scala
Calibration.fromChessboard(views, board) match
  case Right(calib)                       => use(calib)
  case Left(CvError.CalibrationFailed(m)) => retryCapture(m)
  case Left(err)                          => log.error(err.getMessage, err)
```

Note also what is *not* a failure here: a view that makes the corner detector itself throw is folded
into "no board in this frame" and dropped, so one pathological frame in a folder of twenty cannot
abort the whole calibration.

#sect("Straightening the frame")

With a calibration, `undistort` maps the lens bend back out. There are two spellings --- pass the
`Intrinsics` directly, or pass the whole `Calibration` and let the extension method in the vision
layer unwrap it for you.

```scala
Image.reading("bench-raw.png")(_.undistort(calib).write("bench-straight.png"))
```

Like every transform in Chapter 4, `undistort` *consumes* its receiver and returns a new `Image`.
If you need the original as well --- and for a calibration rig you often do, because the detector
should run on one and the display on the other --- take a `.copy` first.

#memory[`Intrinsics` is pure Scala data; the native call underneath is not. `cameraMatrix` and
`distCoeffs` each allocate a fresh Mat on every access, so `undistorted` wraps both in a
`Managed.scope` --- freed whether the call returns or throws --- and deliberately leaves the
destination Mat *unowned*, because that one is the value being handed back. The leak suite pins it
down: three hundred `undistort` calls in a row, each of them allocating its own camera matrix and
distortion Mat out of the same `Intrinsics`, with an assertion that native growth stays bounded. It is the ownership rule from Chapter 5 in miniature: what the operation borrows goes in the
scope, what it produces does not.]

#subsect("All the pixels, or only the valid ones")

Undistortion is a resample: for each output pixel it asks where that ray came from in the input. Near
the frame border under barrel distortion the answer is sometimes "from outside the sensor", and those
output pixels have no source. What to do about them is a choice, spelled in OpenCV as the `alpha`
parameter of `getOptimalNewCameraMatrix`: at one extreme you keep the whole rectified field of view
and accept curved black wedges at the edges where nothing mapped in; at the other you crop to the
largest rectangle in which every pixel is real, which costs field of view --- most of it at the
corners, where a wide lens bends hardest --- and hands you a *different* camera matrix that anything
measuring on the result must then use.

`Image.undistort` exposes no such knob. `Ops.undistorted` calls the four-argument
`Calib3d.undistort(src, dst, cameraMatrix, distCoeffs)`, which takes the calibrated matrix as its own
new-camera matrix, so the output keeps the input's size and the same `fx`, `fy`, `cx`, `cy`: a
measurement made on the undistorted image goes straight back through the intrinsics you already hold,
and the invalid corners come back black. That is the right default for both things undistortion is
usually for --- straightening a frame before measuring on it, and straightening one for a person to
look at --- and it is one fewer matrix to keep in step with the other. If you want the cropped
variant, build the optimal new camera matrix yourself and pass it as `Calib3d.undistort`'s fifth
argument; Appendix A shows how to reach the raw API without leaving the library's ownership model,
which here means wrapping that matrix in a `Managed` so it is freed with everything else.

#tip[If the pipeline downstream is `Ar.estimatePose`, `HeadPose.estimate` or `Localizer.locate`, do
*not* undistort first. Those solvers take the distortion coefficients themselves and account for the
bend analytically --- more accurate and far cheaper than resampling every pixel of every frame.
Undistort when a person, or something that assumes straight lines, has to look at the frame;
otherwise hand the `Intrinsics` along and leave the pixels alone.]

#sect("Where the numbers live afterwards")

An `Intrinsics` is not a property of your program. It is a property of one camera body, with one
lens, at one zoom and one focus distance, sampled at one resolution. Two nominally identical webcams
out of the same box do not share a principal point, because the tolerance that placed each lens
assembly was spent per unit. Store the calibration with the device, not with the build.

`Intrinsics` and `Calibration` are ordinary case classes of `Double` and `Seq[Double]`, so any codec
you already use serialises them without ceremony; there is no bespoke format in the library and none
is needed. What matters is what you store *alongside* them: the device identifier, the `imageSize`,
the date, and the reprojection error you accepted. That last one earns its keep the day a rig starts
producing suspect distances and the first question is whether the calibration was ever good.

Resolution is the trap. Calibrate at 1280×720, run the camera at 640×360, and every one of `fx`,
`fy`, `cx`, `cy` is twice what it should be with nothing to tell you: the `Calibration` records the
size it was measured at, but nothing enforces it at the point of use. Recalibrating at the running
resolution is the honest fix. Scaling is the pragmatic one, and it is exact for a clean downsample:

#example("Rescaling intrinsics for a different capture resolution. Distortion coefficients carry over unchanged.")[
```scala
def scaledTo(i: Intrinsics, from: Size, to: Size): Intrinsics =
  val sx = to.width / from.width
  val sy = to.height / from.height
  i.copy(fx = i.fx * sx, fy = i.fy * sy, cx = i.cx * sx, cy = i.cy * sy)
```
]

The distortion coefficients need no scaling: they act on normalised coordinates --- the image *after*
`K` has been divided out --- so they are dimensionless and survive a resize intact. Cropping is a
different operation: it moves the principal point without changing the focal length, so a camera that
switches between full-sensor and cropped modes has two calibrations, not one scaled one.

#caution[Do not carry a calibration across a firmware update, a lens change, a knock that moved the
mount, or a switch of the camera's own digital zoom or "field of view" setting. All of those change
the optics behind the numbers, and a stale calibration is worse than an honest `Intrinsics.approx`:
the guess is visibly a guess, while the stale file looks measured.]

#sect("What the bench rig gets")

With `calib` in hand the rig's pipeline changes in exactly one place. Where Chapter 28 wrote
`Ar.estimatePose(marker, markerLength = 0.05, Intrinsics.approx(img.size))` and got a pose that
tracked, it now writes `Ar.estimatePose(marker, markerLength = 0.05, calib.intrinsics)` and gets a
pose that measures. The same
substitution turns `HeadPose.estimate` from Chapter 29 into real head distances.

Chapter 32, #emph[Visual Navigation], takes the calibrated camera and asks where it is: optical flow and
ORB features, visual odometry frame to frame, absolute localisation against a map, and stereo depth.
Every one of them takes an `Intrinsics`, and every one is worth exactly as much as the calibration
you hand it.
