# Marker AR

Give scalacv a printed [ArUco](/object-detection#aruco-markers) tag and a camera model and it will tell
you where that tag sits in space — its 3D pose — and let you draw on top of it as if the drawing were
glued to the tag. That is the whole of marker-based augmented reality: **detect → pose → project**,
and each step is one call.

:::tip[New to AR? The mental model.]
An ArUco marker is a printed square whose black-and-white pattern encodes a number (its *id*). Because
the square's real size is known and its shape is fixed, one photo of it is enough to work out how far
away and at what angle it sits — its **pose**. Once you have the pose you can project any 3D shape back
onto the image so it appears stuck to the tag. Point a webcam at a printed tag and you are doing AR.
:::

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
```

The three steps, and the call that does each:

| Step | Call | Produces |
|---|---|---|
| **Detect** | `image.arucoMarkers` / [`Aruco.detect`](/object-detection#aruco-markers) | `Seq[ArucoMarker]` — id + 4 corners |
| **Pose** | `image.arMarkers` / [`Ar.estimatePose`](/api/core/scalacv/vision/Ar$.html) | `Seq[MarkerPose]` — marker + [`Pose3D`](/api/core/scalacv/vision/Pose3D.html) |
| **Project** | [`Ar.project`](/api/core/scalacv/vision/Ar$.html), `drawMarkerAxes`, `drawMarkerCube` | pixel points to draw with |

## The camera model

Pose recovery needs to know the camera's optics: focal length and principal point, the
[`Intrinsics`](/api/core/scalacv/Intrinsics.html). A real camera's numbers come from a chessboard
[calibration](/calibration), but when you just want an overlay to track, `Intrinsics.approx` guesses a
serviceable model from the image size and an estimated field of view:

```scala mdoc:silent
val intrinsics = Intrinsics.approx(Size(1280, 720), horizontalFovDegrees = 60)
```

A narrower field of view is a longer lens, so `fx`/`fy` grow as the angle shrinks; the principal point
sits at the image centre. If you have calibrated, construct it directly with your own
`fx, fy, cx, cy` and distortion coefficients.

:::warning[A guess tracks, it does not measure]
`Intrinsics.approx` is good enough to make an overlay *sit* on a tag, but the reported `distance` will be
off — sometimes by tens of percent. For a real measurement, feed a [`Calibration`](/calibration)'s
intrinsics instead. Nothing else about the code changes.
:::

## Generating a marker to print

Before you can detect a tag you need one. [`Aruco.generateMarker`](/object-detection#aruco-markers)
renders any id from a dictionary as an image. It carries the marker's own black border but **no quiet
zone**, and the detector will not find it until you add a white margin around it — it locates candidates
by looking for a dark quad on a light background:

```scala mdoc:compile-only
Aruco
  .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 480)
  .use(m => Image.wrap(Managed(m.clone())).border(60, 60, 60, 60, color = Scalar.White).write("marker-7.png"))
```

### Choosing a dictionary

A dictionary fixes the marker's bit grid and how many distinct ids exist. The name reads
`Dict<grid>_<count>` — a `Dict4x4_50` has a 4×4 bit pattern and 50 unique markers:

| Dictionary | Grid | Ids | Character |
|---|---|---|---|
| `Dict4x4_50` (default) | 4×4 | 50 | fewest bits — most robust far away, fewest ids |
| `Dict4x4_250`, `Dict5x5_250` | 4×4 / 5×5 | 250 | more ids, still forgiving |
| `Dict6x6_250`, `Dict7x7_1000` | 6×6 / 7×7 | 250 / 1000 | many ids — need more pixels on the tag |
| `AprilTag36h11` | 6×6 | 587 | AprilTag family, very high error tolerance |
| `ArucoOriginal` | — | 1024 | the classic legacy set |

The trade-off: a **smaller grid** (fewer bits) detects more reliably at distance and low resolution but
offers fewer ids and less error-correction margin; a **larger grid** gives many ids and robustness to
bit errors but needs the tag to occupy more pixels. Whatever you pick, the *same* dictionary must be
passed to generation and detection.

## Detect and pose in one step

`arMarkers` finds every tag from a dictionary and solves each one's pose, returning a
[`MarkerPose`](/api/core/scalacv/vision/MarkerPose.html) — the marker plus its [`Pose3D`](/api/core/scalacv/vision/Pose3D.html).
You give it the tag's real side length (metres, conventionally); the pose comes back in that unit, so
`distance` is a real camera-to-tag distance.

```scala mdoc:invisible
// A synthetic fronto-parallel view: tag id 7 on a white quiet zone so the detector finds it.
def markerScene(): Image =
  val bordered =
    Aruco
      .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 240)
      .use(_.border(80, 80, 80, 80, color = Scalar.White))
  Image.wrap(bordered).convert(ColorConversion.GrayToBgr)
```

```scala mdoc:silent
val markers = markerScene().arMarkers(intrinsics = Intrinsics.approx(Size(400, 400)), markerLength = 0.05)
```

```scala mdoc
{
  markers.map(m => s"marker ${m.id}: ${f"${m.distance}%.3f"} m away").mkString("\n")
}
```

A `MarkerPose` bundles everything the pipeline recovered:

| Member | Type | What it is |
|---|---|---|
| `id` | `Int` | the marker's dictionary id |
| `distance` | `Double` | camera-to-tag distance, in `markerLength`'s unit (exported from the pose) |
| `pose` | [`Pose3D`](/api/core/scalacv/vision/Pose3D.html) | the full rotation + translation |
| `marker` | [`ArucoMarker`](/object-detection#aruco-markers) | the raw detection (`id`, 4 `corners`) |

Under the hood this is `solvePnP` with the square-planar `IPPE_SQUARE` solver — faster and steadier for
a flat tag than the general iterative method. The pose's `rvec`/`tvec` are OpenCV's axis-angle rotation
and translation; you rarely read them directly (a head-on tag comes back as a ~180° flip, because the
marker frame is y-up and the image frame y-down), but `distance` is often all you want.

### Just the ids

If you only need to know *which* tags are present — not where — skip pose recovery with `arucoMarkers`,
which returns the raw [`ArucoMarker`](/object-detection#aruco-markers)s (id + corners):

```scala mdoc:silent
val scene0 = markerScene()
val tags = scene0.arucoMarkers()
scene0.close()
```

```scala mdoc
tags.map(_.id)
```

### Pose from an existing detection

When you already have an `ArucoMarker` (from `arucoMarkers`, or filtered by id), solve its pose alone
with `Ar.estimatePose`. It returns `None` if `solvePnP` fails to converge — rare for four coplanar
corners, but possible:

```scala mdoc:silent
val poses = tags.flatMap(m => Ar.estimatePose(m, markerLength = 0.05, intrinsics))
```

```scala mdoc
poses.size
```

## Draw on the tag

`drawMarkerAxes` overlays a 3D coordinate frame at every tag — **X red, Y green, Z blue** (Z points out
of the plane toward the camera) — the classic "is my pose right?" check:

```scala mdoc:silent
markerScene()
  .drawMarkerAxes(Intrinsics.approx(Size(400, 400)), markerLength = 0.05)
  .write("axes.png")
```

`drawMarkerCube` stands a wireframe cube on each tag — the hello-world of AR:

```scala mdoc:silent
markerScene()
  .drawMarkerCube(Intrinsics.approx(Size(400, 400)), markerLength = 0.05, color = Scalar.Green)
  .write("cube.png")
```

Both overlays take the same first two arguments (`intrinsics`, `markerLength`) plus a couple of optional
knobs:

| Overlay | Knob | Default | Effect |
|---|---|---|---|
| `drawMarkerAxes` | `axisLength` | half the marker side | how long each drawn axis is |
| `drawMarkerCube` | `color` | `Scalar.Green` | wireframe colour |
| `drawMarkerCube` | `size` | the marker side | cube edge length |
| both | `dictionary` | `Dict4x4_50` | which dictionary to detect |

:::note[These verbs consume the image]
`drawMarkerAxes` and `drawMarkerCube` follow [move semantics](/mat-lifecycle): each consumes the
receiver and returns the annotated image. In a live loop the frame flows straight through — detect, draw,
display — with no copy.
:::

## Projecting your own geometry

Both overlays are thin wrappers over [`Ar.project`](/api/core/scalacv/vision/Ar$.html), which maps any set of
3D model points — in the tag's own frame — through a pose and the camera to pixel coordinates you draw
with the ordinary [drawing verbs](/drawing). The tag's frame has its origin at the marker centre, `x`
right, `y` up, `z` out of the plane toward the camera. To hang your own model off a tag, project its
points and draw the edges:

```scala mdoc:silent
val scene = markerScene()
val intr = Intrinsics.approx(scene.size)
scene.arMarkers(intr, markerLength = 0.05).headOption.foreach { mp =>
  // A vertical mast rising 10 cm out of the tag centre.
  val Seq(base, tip) = Ar.project(Seq(Point3(0, 0, 0), Point3(0, 0, 0.1)), mp.pose, intr)
  scene.mat.drawLine(base, tip, Scalar.Red, Thickness.Stroke(3))
}
scene.close()
```

Because `project` takes plain [`Point3`](/api/core/scalacv/Point3.html) data, anything you can describe
as 3D points — a bounding box, a label anchor, a mesh — rides on the tag with no extra machinery. A few
patterns:

| To draw | Model points (tag frame) | Then |
|---|---|---|
| A label anchor above the tag | `Point3(0, 0, 0.15)` | `drawText` at the projected point |
| A footprint square on the plane | four `Point3(±h, ±h, 0)` | `drawLine` around them |
| A cube (what `drawMarkerCube` does) | `Ar.cubeCorners`-style 8 points | `drawLine` over the 12 edges |

## A live AR loop

Everything above composes into the real thing — read frames from a [`Camera`](/video), draw a cube on
every tag, show or record the result:

```scala mdoc:compile-only
val camera = Intrinsics.approx(Size(1280, 720))
Camera.using(0) { cam =>
  cam.recordTo("ar.mp4")(_.drawMarkerCube(camera, markerLength = 0.05, color = Scalar.Green))
}
```

Swap `Intrinsics.approx` for a [`Calibration`](/calibration)'s intrinsics and the same loop becomes
metric — the cube sits at the right scale and the reported distances are real.

## Next

- [Camera calibration](/calibration) — turn the field-of-view guess into a measured camera, and AR into metrology.
- [Object detection](/object-detection#aruco-markers) — the ArUco detector and dictionaries in more depth.
- [Pose estimation](/pose-estimation) — the same `solvePnP` machinery applied to faces and heads.
