#import "../lib/book.typ": *

#chapter("QR Codes, ArUco, and Augmented Reality", subtitle: [The cheapest way to put a known object into a scene is to print one.])

A camera looks down at a bench where plastic trays pass under an inspection head. You want three
things from each frame: which tray is under the head, how far below the lens its surface sits, and
whether it is lying square to the machine. A detector trained on trays gives you a tray-shaped
rectangle and nothing else --- not which of the four hundred trays in the building this one is, and
not how far away it is, because it does not know how big a tray is: a small tray close to the lens and
a large one further off project to the same rectangle. One photograph of an unknown object
under-determines its distance, and no quantity of training data repairs that.

What repairs it is a measurement you supply. Four points on a plane whose coordinates you have written
down, seen through a camera whose optics you have written down, determine the rigid transform between
that plane and the camera --- a solved problem with a name, perspective-#emph[n]-point, and OpenCV
ships the solver. The algebra was never the hard part. The hard part is having four points in the
scene whose real positions you know.

A fiducial marker is the cheapest way to manufacture those four points: a printed square of measured
side, carrying a pattern that stands out against clutter and encodes an integer. Finding it is not
recognition and needs no model file --- threshold the image, look for dark quadrilaterals on light
ground, warp each candidate flat, read the bits, check them against a codebook. Tape one to a tray and
the tray becomes an object whose identity, size and pose your program knows, for the price of a sheet
of paper.

This chapter uses one running scene. Every tray carries two printed things: an ArUco tag, whose id is
the tray number and whose corners carry the geometry, and a QR sticker, whose payload is the batch
record. By the end you will decode the sticker, recover the tag's 3D pose, stand a wireframe cube on
the tray, and record the result --- and know which of those steps is lying when the cube wobbles.

Everything here that finds a marker lives in `scalacv-vision` --- `Qr`, `Aruco`, `Ar` and the `Image`
extension methods they carry --- so that module has to be on the classpath beside `scalacv` itself,
with the coordinates Chapter 2 lists. The camera model, `Intrinsics`, is the exception: it sits in the
core module, which is why `Image.undistort` takes one without dragging the vision jar in. A single
`import scalacv.*` brings all of it into scope.

#sect("Two printed markers, two different jobs")

The two families look alike on paper and are not interchangeable. A QR code is a data carrier: its
geometry exists to make a payload readable, not to be measured. An ArUco marker holds an integer of
at most ten bits and exists to be measured --- fixed square shape, heavy border, and a codebook chosen
so that a few misread bits resolve to the right id or to nothing at all.

#figure-table("What each marker family is for, and what the library hands back.")[
#tbl(
  columns: (0.8fr, 1.3fr, 1fr, 0.9fr),
  [Marker], [Carries], [Entry point], [Result],
  [QR code], [arbitrary text, kilobytes of it], [`image.qrCodes`, `Qr.detectAndDecode`], [`Seq[QrCode]`],
  [ArUco tag], [one dictionary id, plus measurable geometry], [`image.arucoMarkers`, `Aruco.detect`], [`Seq[ArucoMarker]`],
  [ArUco tag + camera model], [id, rotation and translation], [`image.arMarkers`], [`Seq[MarkerPose]`],
)
]

None of those calls hands back a native handle, and that is the ownership model rather than an
omission.

#memory[
  `QRCodeDetector` and `ArucoDetector` are two of the 185 `org.opencv.*` types that expose no public
  `release()` --- only a private `delete(long)`. A program that constructs one per frame and lets the
  references go leaks native memory at a rate the heap never reveals: Chapter 1 measured 4000 leaked
  `KalmanFilter`s at 54 GB resident against 86 MB when released, and a detector is the same shape of
  object. scalacv frees them through `Releasable.nativeHandle`, the same bridge it uses for every
  handle-only type, inside the `Managed(...).use` block that created them. That is why a detector is
  never handed to you: an object with no public free is safest when its lifetime is a single call.
]

#sect("Reading the sticker")

`image.qrCodes` is a borrowing query in the sense of Chapter 4: it reads the image and returns plain
data, and the image is still yours afterwards.

#example("Decoding every QR code in a frame.")[
```scala
Image.reading("tray.jpg") { img =>
  img.qrCodes.map(c => s"${c.text} at ${c.corners.mkString(", ")}")
}
```
]

A `QrCode` is `final case class QrCode(text: String, corners: Seq[Point])`: the decoded payload, and
the symbol's four corners in image coordinates, in OpenCV's order.

Underneath, `Qr.detectAndDecode(mat)` calls `detectAndDecodeMulti` unconditionally. OpenCV's
single-symbol `detectAndDecode` is deliberately not exposed: it returns the first symbol it happens to
find and gives no way to learn there were others, so a tray with two stickers would become a tray with
one.

The field that surprises people is `text`, because it can be empty. When OpenCV locates a symbol but
cannot decode it --- a blurred sticker, a thumb over one corner, a highlight across the finder
patterns --- the entry is still returned, with the corners filled in and the text empty. The library
does not filter those out, and neither should you, at least not first:

```scala
img.qrCodes.filter(_.text.nonEmpty).map(_.text)   // discards the evidence you needed
```

A located-but-undecoded code is not a non-event: it is a sticker present, framed and unreadable, which
is actionable in a way that "no code found" is not. Partition instead, and draw the failures.

#example("Keep the codes that failed to decode --- they are the interesting ones.")[
```scala
Image.reading("tray.jpg") { img =>
  val (decoded, unreadable) = img.qrCodes.partition(_.text.nonEmpty)

  decoded.foreach(c => img.mat.drawPolyline(c.corners, true, Scalar.Green, Thickness.Stroke(2)))
  unreadable.foreach(c => img.mat.drawPolyline(c.corners, true, Scalar.Red, Thickness.Stroke(2)))

  img.drawText(s"${decoded.size} read, ${unreadable.size} unreadable", Point(10, 30))
     .write("tray-annotated.png")
}
```
]

`drawPolyline` is one of the mid-level drawing verbs from Chapter 14; it mutates the borrowed `Mat`
in place, which is why the annotation happens before `drawText` consumes the image.

An image with no QR code is an empty `Seq`, not an error --- there is nothing wrong with a blank
bench. An empty `Mat` is an `IllegalArgumentException`, because handing a detector an image with no
pixels is a bug rather than a fact about the world. That split is the error model of Chapter 6.

#subsect("How large does the sticker have to be?")

QR decoding has a resolution floor, expressed in pixels per module rather than pixels per code. A
module is one of the small squares; a version-1 code is 21 modules on a side and each version adds
four. Ask for more payload, or for a higher error-correction level at the same payload, and the
code grows in modules, so the same sticker at the same distance puts fewer pixels on each one until
the decoder stops. The trade runs the wrong way for intuition: raising error correction to survive
scuffs makes the code harder to resolve at distance.

Motion blur destroys those module edges while leaving the finder patterns detectable --- which is
exactly how you end up with good corners and empty text. Glare does the same locally. Both are reasons
to keep an undecoded symbol: with the quad in hand you can crop that region out of a later frame.

#sidebar("The QR round-trip that killed the JVM")[
  The library's own QR test encodes a payload with `QRCodeEncoder`, upscales it and decodes it straight
  back. For a while that test crashed the process: `SIGSEGV` inside `cv::Mat::release()`, called from
  `libopencv_objdetect`, with no Java frame to unwind.

  Nothing was wrong with the detector. The old `OpenCv.load()` opened every OpenCV module library
  speculatively, and the bundled `libopencv_highgui.so` carries unversioned `NEEDED` entries, so
  loading it sent the linker down the system path and mapped six system `libopencv_*.so.5.0.0`
  libraries into the global namespace, where they interposed on the bundled 4.13.0 symbols. Decoding a
  QR code was the first operation in the suite to cross between the two ABIs, so it was the one that
  died. The loader is demand-driven now (Chapter 2).

  The upscale is not incidental either: `QRCodeEncoder` emits one pixel per module, under the floor of
  the previous section, so the test resizes eightfold with `INTER_NEAREST` --- anything smoothing would
  blur the module edges the decoder thresholds on --- and only then adds the quiet zone.

  The moral is not about QR codes: a native crash names the operation that tripped over the problem,
  not the code that caused it.
]

#sect("ArUco: identity you can measure")

An ArUco dictionary is a codebook: a fixed bit-grid size, a fixed number of distinct markers, and a
minimum Hamming distance between any two. `ArucoDictionary` is an enum over the predefined ones, and
each name gives both numbers --- `Dict5x5_250` is 250 markers on a 5 × 5 grid. There are 22 cases,
AprilTag families and legacy sets included, all reachable through the same detector.

#figure-table("The dictionary families, and what each costs you.")[
#tbl(
  columns: (1.2fr, 0.6fr, 0.7fr, 1.5fr),
  [Family], [Grid], [Ids], [Character],
  [`Dict4x4_50` … `Dict4x4_1000`], [4 × 4], [50--1000], [fewest bits: reads at the greatest distance, fewest ids],
  [`Dict5x5_50` … `Dict5x5_1000`], [5 × 5], [50--1000], [more ids, still forgiving],
  [`Dict6x6_*`, `Dict7x7_*`], [6 × 6, 7 × 7], [50--1000], [many ids, needs the tag to fill more pixels],
  [`AprilTag16h5` … `AprilTag36h11`], [AprilTag], [587 for `36h11`], [robotics toolchains; very high error tolerance],
  [`ArucoOriginal`, `ArucoMip36h12`], [legacy], [1024 for `ArucoOriginal`], [compatibility with existing printed tags],
)
]

The rule that follows, and that people get backwards: pick the smallest dictionary with enough ids
for the job, not the largest. Fewer markers means a larger Hamming distance between them, which means
more bit errors survivable before a tag is rejected or, worse, misread. A bench with forty trays
wants `Dict4x4_50`, not `Dict7x7_1000`, and the choice buys metres of working distance. Whatever you
generated with, you must detect with.

#example("Every tag in the frame, with its id and its four corners.")[
```scala
Image.reading("bench.jpg") { img =>
  img.arucoMarkers(ArucoDictionary.Dict4x4_50).map(m => m.id -> m.corners)
}
```
]

`ArucoMarker(id: Int, corners: Seq[Point])` is the whole result type. The corners run clockwise from
the top left of the marker's own frame, which is what makes the pose step possible: corner zero of a
rotated tag is still the tag's top-left, not the image's.

A tag from the wrong dictionary comes back as nothing rather than as a wrong answer: ask for
`Dict5x5_250` while the bench is printed with `Dict4x4_50` and the result is an empty `Seq`, because a
5 × 5 codebook demands more bits than a 4 × 4 pattern can satisfy. Silence is the correct failure, and
it is also why a mismatched dictionary is such a frustrating bug --- nothing is reported at all.

`Aruco.detect` builds a fresh `ArucoDetector` on every call, by decision rather than oversight:
`ArucoDetector` copies the dictionary into its implementation at construction, so a shared instance
would have to be immutable by convention or synchronised, and construction is cheap beside detection.
Rejected candidates are discarded; they are a tuning aid, not a result.

#note[
  Everything OpenCV exposes for tuning detection --- adaptive-threshold window sizes, the minimum
  candidate perimeter, corner refinement --- lives on `org.opencv.objdetect.DetectorParameters`, which
  this library does not surface: `Aruco.detect` uses OpenCV's defaults. Drop to `org.opencv.objdetect`
  when you need those knobs; the low-level surface is never walled off.
]

#sect("Printing the tags")

`Aruco.generateMarker` renders a marker so you have something to tape to a tray. It is the one call
in this chapter that hands you ownership.

#example("Generate marker 7, give it a quiet zone, and write it out to print.")[
```scala
val bordered =
  Aruco
    .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 480)
    .use(_.border(60, 60, 60, 60, color = Scalar.White))

Image.wrap(bordered).write("tray-07.png")
```
]

The result is a `Managed[Mat]`: an 8-bit single-channel square of exactly `sizePixels` on a side, and
yours to release --- `use` does it here. What `border` returns may outlive that `use` block because
`copyMakeBorder` writes a fresh Mat rather than a view of the source; a `submat` shares the parent's
buffer, and letting one escape its owner is the aliasing mistake of Chapter 10. `Image.wrap` then
takes over the bordered Mat, and `write` spends it.

A negative `id` or a non-positive `sizePixels` is an `IllegalArgumentException`. An id past the end of
the dictionary, or a size too small for the bit grid, is a `CvError.NativeCall`, because OpenCV is
what discovers it: `Dict4x4_50` holds markers 0 through 49, so 50 throws.

The `border` call is not decoration, and leaving it out is the most common reason a freshly generated
marker "cannot be detected". `generateMarker` emits the marker's own black border and no quiet zone,
and the detector looks for a dark quad on a light background, so a tag flush to the image edge is
invisible to it however clean the bits are. The same holds for the printed article: leave white space
around it, and do not mount one against a dark bracket.

#sect("From four corners to a pose")

Detection gives you a quadrilateral in pixels. Pose estimation turns it into a position and an
orientation in space, and it needs exactly two things from you that detection did not: the tag's real
side length, and a model of the camera.

#example("Detect and solve in one step.")[
```scala
Image.reading("bench.jpg") { img =>
  val intrinsics = Intrinsics.approx(img.size, horizontalFovDegrees = 60)
  img.arMarkers(intrinsics, markerLength = 0.05, ArucoDictionary.Dict4x4_50)
     .map(mp => f"tray ${mp.id} at ${mp.distance}%.3f m")
}
```
]

`arMarkers(intrinsics, markerLength, dictionary)` detects every tag and solves each one's pose,
returning `Seq[MarkerPose]`. A marker whose pose fails to solve is dropped, so the result can be
shorter than `arucoMarkers` on the same frame. `MarkerPose(marker, pose)` exports `distance` from the
pose and defines `id` as the marker's, so both read like fields; `mp.marker.corners` is still there
when you want the pixels back.

`markerLength` is the tag's real side, in whatever unit you want the answer in; metres is the
convention, and `distance`, the cube's edge and the height of a label anchor all inherit it. Measure
the black square, not the paper.

When you already hold an `ArucoMarker` --- filtered by id, or carried over from an earlier stage ---
solve it alone:

```scala
val pose: Option[Pose3D] = Ar.estimatePose(marker, markerLength = 0.05, intrinsics)
```

`estimatePose` returns `Option[Pose3D]` because `solvePnP` can fail to converge --- rare for four
coplanar corners, not impossible. Four corners and a positive `markerLength` are required, and both
are `IllegalArgumentException`s rather than a `None`, because they are mistakes rather than outcomes.
It runs `solvePnP` with `SOLVEPNP_IPPE_SQUARE`, the square-planar solver, faster and steadier on a
flat tag than the general iterative method.

#memory[
  `estimatePose` needs six native `Mat`s for one call --- object points, image points, the camera
  matrix, the distortion coefficients and two output vectors --- and owns every one through
  `Managed.scope` (Chapter 5), so a throw from the fourth allocation frees the first three. `Pose3D`
  holds `Seq[Double]`, so the numbers are copied onto the heap before the scope closes and nothing
  native escapes. The same holds for `Ar.project`; neither hands you a handle.
]

#subsect("What `rvec` actually is")

`Pose3D(rvec: Seq[Double], tvec: Seq[Double])` looks like six numbers you can read off. `tvec` is:
it is the translation from camera to marker, in your unit, and `pose.distance` is its length --- the
straight-line camera-to-tag distance, and often the only number you wanted.

`rvec` is not. It is a rotation in OpenCV's Rodrigues form: axis-angle packed into three numbers, the
direction being the axis and the magnitude the angle in radians. Both fields are required to be
exactly three elements long at construction, because `Pose3D` is public data you are expected to build
by hand from another solver's output, and a four-element `rvec` would otherwise fail much later inside
a native `put`, blaming a line that had nothing to do with the mistake.

Two consequences follow. First, an axis-angle triple is not a rotation matrix and not a set of Euler
angles, and there is no shortcut between them. To compose two poses, transform a point into the camera
frame, or hand an orientation to a robot controller, you convert --- with `Calib3d.Rodrigues`,
yourself:

#example("Rodrigues, when you need the rotation as a matrix.")[
```scala
import org.opencv.calib3d.Calib3d
import org.opencv.core.{CvType, Mat}

def rotationMatrix(pose: Pose3D): Seq[Seq[Double]] =
  Managed.scope: own =>
    val rvec = own(Mat(3, 1, CvType.CV_64F))
    rvec.put(0, 0, pose.rvec*)
    val rot = own(Mat())
    Calib3d.Rodrigues(rvec, rot)
    (0 until 3).map(i => (0 until 3).map(j => rot.get(i, j)(0)))
```
]

Second, axis-angle vectors do not add. Averaging a tag's `rvec` over five frames to smooth out jitter
is arithmetic on a space that is not linear, and the error grows with the angles involved. Smooth the
translation if you like; for rotation, convert to matrices or quaternions first, or filter the
projected pixel points instead.

#warning[
  A tag facing the camera head-on comes back with an `rvec` of magnitude near π, not near zero, and
  that is correct: OpenCV's marker frame is y-up while the image frame is y-down, so a fronto-parallel
  tag is a 180° flip about an axis lying in the marker plane. In-plane spin lives in the third
  component --- `rvec(2)` near zero means the tag is not rotated in its own plane.
]

#sect("The camera model is the part you have to earn")

`Intrinsics(fx, fy, cx, cy, distortion)` is the pinhole model: focal length in pixels on each axis,
the principal point, and OpenCV's radial and tangential distortion coefficients. Focal lengths must
be positive, and the distortion vector must have one of the counts OpenCV itself accepts ---
`Intrinsics.ValidDistortionSizes` is `Seq(0, 4, 5, 8, 12, 14)`, empty meaning an ideal lens. That
check exists because a coefficient dropped from a five-element vector leaves a legal four-element one,
so the native call runs happily and returns a quietly wrong pose. A rejected constructor costs a
minute; a plausible wrong number costs a day.

`Intrinsics.approx(imageSize, horizontalFovDegrees = 60)` builds a model from the frame size and a
guess at the field of view: centred principal point, square pixels, no distortion. A narrower field of
view is a longer lens, so `fx` and `fy` grow as the angle shrinks.

#caution[
  `Intrinsics.approx` is enough to make an overlay sit on a tag and track it. It is not enough to
  measure with. The distance it reports is in the unit you gave `markerLength`, but its accuracy is
  bounded by the field-of-view guess, and being wrong by tens of percent is normal. A real lens also
  has distortion, which `approx` models as zero, so tags near the frame edge fare worse than tags near
  the centre. Chapter 31 turns the guess into a measured camera from a handful of chessboard
  photographs, and nothing else about your code changes: `Calibration` carries an `Intrinsics`, and
  every call here takes one.
]

#sect("Standing a cube on the tray")

Two overlays are built in, and both do the whole detect-solve-project sequence internally.

#example("The AR hello-world: a wireframe cube on every tag.")[
```scala
Image.reading("bench.jpg") { img =>
  img.drawMarkerCube(Intrinsics.approx(img.size), markerLength = 0.05, color = Scalar.Green)
     .write("bench-ar.png")
}
```
]

`drawMarkerAxes` draws a coordinate frame at each tag --- X red, Y green, Z blue, Z pointing out of
the tag toward the camera. It is the diagnostic overlay: axes that look glued to the paper mean the
pose is right, and a Z axis that swings into the tag means it is not.

#figure-table("The knobs on the two built-in overlays.")[
#tbl(
  columns: (1.1fr, 0.9fr, 1fr, 1.3fr),
  [Overlay], [Parameter], [Default], [Effect],
  [`drawMarkerAxes`], [`axisLength`], [`None` → half the marker side], [length of each drawn axis],
  [`drawMarkerCube`], [`color`], [`Scalar.Green`], [wireframe colour],
  [`drawMarkerCube`], [`size`], [`None` → the marker side], [cube edge length],
  [both], [`dictionary`], [`ArucoDictionary.Dict4x4_50`], [which codebook to detect],
)
]

Both verbs consume the receiver and return the annotated image --- move semantics, Chapter 4. That is
safe inside `reading`, whose own `close()` on the way out is idempotent and finds the handle already
spent; what it forbids is touching `img` again afterwards, which the compiler will not stop and the
runtime will, with the use-after-release exception of Chapter 5. Take `copy` first if you need the
original too. In a live loop you need neither: the frame flows straight through.

Each overlay runs its own detection and pose solve, so calling both on one frame detects the tags
twice. When you want both, or anything of your own, call `arMarkers` once and project by hand.

#subsect("Projecting your own geometry")

`Ar.project(points, pose, intrinsics)` maps any `Seq[Point3]` in the marker's own frame through a pose
and the camera to pixel `Point`s. That frame has its origin at the tag's centre, `x` right, `y` up and
`z` out of the plane toward the camera, in the unit of `markerLength`. Empty in, empty out.

#example("A label floating 15 cm above each tray, anchored to its tag.")[
```scala
Image.reading("bench.jpg") { img =>
  val intr = Intrinsics.approx(img.size)
  img.arMarkers(intr, markerLength = 0.05).foreach { mp =>
    val pts = Ar.project(Seq(Point3(0, 0, 0), Point3(0, 0, 0.15)), mp.pose, intr)
    img.mat.drawLine(pts(0), pts(1), Scalar.Red, Thickness.Stroke(2))
    img.mat.drawText(s"tray ${mp.id}", pts(1), Scalar.Red)
  }
  img.write("bench-labelled.png")
}
```
]

Because `project` takes plain `Point3` data, anything you can describe as 3D points rides on the tag
with no further machinery. `drawMarkerCube` is exactly this: eight corners and twelve edges, projected
and stroked.

#subsect("When the cube wobbles")

The characteristic failure of marker AR is a cube that will not sit still. It jitters between two
orientations, or it leans one way when the tag is left of centre and the other way when it is right
of centre, while the detected corners themselves are visibly stable frame to frame.

That is almost never a detection problem, and chasing it in the detector wastes days. It is the
camera model. `solvePnP` on a planar target has a two-fold ambiguity --- two poses project the same
four corners to nearly the same pixels --- and how cleanly the solver separates them depends on the
focal length you gave it. Feed it an `fx` wrong by tens of percent, routine for a field-of-view guess,
and the two solutions sit close enough that a fraction of a pixel of noise flips the answer between
them, which is why the cube snaps rather than drifts. The lens-edge lean has the same root: real
distortion your model says is zero, worst at the corners of the frame.

The diagnosis needs no new machinery. Project the marker's own four corners back through the pose you
recovered and compare them with the corners that were detected.

#example("A reprojection check: if this is large, do not trust the pose.")[
```scala
val h = markerLength / 2
val model = Seq(Point3(-h, h, 0), Point3(h, h, 0), Point3(h, -h, 0), Point3(-h, -h, 0))
val err = marker.corners
  .zip(Ar.project(model, pose, intrinsics))
  .map((observed, projected) => observed.distanceTo(projected))
  .max
```
]

The library's own test asserts this stays under two pixels for a synthetic fronto-parallel tag. A
large reprojection error with stable corners means the intrinsics; a large error with corners that
dance means the image --- exposure, motion blur, a tag too small in frame.

#sect("The bench loop")

Everything above composes into the thing you actually ship: read frames, decode the sticker, solve
each tag, draw the cube, write the annotated video.

#example("Detect, pose, overlay and record, from a live camera.")[
```scala
OpenCv.load()

val intrinsics = Intrinsics.approx(Size(1280, 720), horizontalFovDegrees = 60)
val markerLength = 0.05 // the printed black square, measured

Camera.using(0) { cam =>
  cam.recordTo("bench.avi") { frame =>
    frame.qrCodes.filter(_.text.nonEmpty).foreach(c => println(s"batch ${c.text}"))

    frame.arMarkers(intrinsics, markerLength).foreach { mp =>
      println(f"tray ${mp.id} at ${mp.distance}%.3f m")
    }

    frame.drawMarkerCube(intrinsics, markerLength, color = Scalar.Green)
  }
}
```
]

Three details there are load-bearing. `qrCodes` borrows, so the frame survives it and
`drawMarkerCube` can consume it afterwards. `recordTo` returns `Either[CvError, Long]` inside
`Camera.using`'s own `Either`, so the camera that would not open and the recorder that would not
encode both reach you as values. And the file is `.avi`, because `recordTo` defaults to `Codec.Mjpg`
--- the one codec videoio can always write --- and MJPG does not open in an `.mp4` container.

#sect("Where the numbers come from")

Everything metric in this chapter --- the reported distance, the cube's proportions, whether the
overlay holds still --- rests on `fx`, `fy`, `cx`, `cy` and the distortion coefficients, and
`Intrinsics.approx` supplies all five by guessing. A wobbling cube is that guess's signature.

Chapter 31, #emph[Camera Calibration], replaces it with a measurement: a printed chessboard, a dozen
photographs from varied angles, `Calibration.fromChessboard`, and a `reprojectionError` you can quote.
The `Calibration` it returns carries an `intrinsics` field of exactly the type every call here already
takes, so nothing else about the bench loop changes --- and once it does, the cube sits at the right
scale and the distances are measurements. If your cube wobbles, go there next.

Chapter 29, #emph[Pose Estimation and Gestures], comes first, and points the same solver at something
you cannot print. `HeadPose.estimate` runs `solvePnP` against a canonical five-point 3D face, the
known points supplied by a detected face's landmarks instead of by four corners of paper. It takes the
same `Intrinsics`, and it performs for you the conversion this chapter did by hand:
`Calib3d.Rodrigues` to a matrix, then `RQDecomp3x3`, so what comes back is a `HeadPose` of yaw, pitch
and roll in degrees rather than an axis-angle triple.
