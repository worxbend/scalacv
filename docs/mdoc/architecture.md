# Architecture & mental model

A quick tour of how scalacv is put together, so the rest of the docs read as one system rather than a
pile of methods. Four ideas carry the whole library: **two API tiers**, **four published modules**,
**one ownership primitive**, and **one error policy**. Learn these once and every other page — from
[filters](/filters) to [SLAM navigation](/navigation) — is a variation on them.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Two tiers, on purpose

scalacv gives you the same operations at two altitudes, and you move between them freely.

**High-level — [`Image`](/image-api).** An owned image you transform by chaining. It manages the
native `Mat` for you, hides every raw `int` constant behind a typed enum, and turns boundary
failures into an `Either`. This is the tier to reach for first:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Image.blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray.blur(2).canny(80, 160)
    .bytes(".png")
```

**Mid-level — extension methods on `Mat`.** The same operations, one step down, working directly on
the raw `org.opencv.core.Mat`. Every `Image` transform is a thin wrapper over one of these. It's a
documented escape hatch, not a wall: when `Image` doesn't wrap the call you need, borrow the `Mat`
and stay in Scala.

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

val count: Int =
  Managed.use(Mat(64, 64, CvType.CV_8UC3)) { src =>
    src.cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(50, 150))
      .use(_.findContours().size)
  }
```

The two tiers are twins — an `Image` method and its mid-level counterpart call the *same* OpenCV
function and share the same types (`Scalar`, `Rect`, the enums), so they never disagree on
behaviour:

| High-level (`Image`) | Mid-level (`Mat` extension) | OpenCV call |
|---|---|---|
| `img.gray` | `mat.cvtColor(ColorConversion.BgrToGray)` | `cvtColor` |
| `img.canny(80, 160)` | `mat.canny(80, 160)` | `Canny` |
| `img.resize(w, h)` | `mat.resize(Size(w, h))` | `resize` |
| `img.contours()` | `mat.findContours()` | `findContours` |
| `img.blur(2)` | `mat.gaussianBlur(Size(5, 5))` | `GaussianBlur` |

The one deliberate divergence is parameter *order* on `adaptiveThreshold` (the tiers lead with
different arguments), and even that cannot bite silently — the leading types differ, so a positional
call meant for one tier will not compile against the other. See
[Working with the raw OpenCV API](/low-level) for the full escape-hatch story.

:::tip[When to drop a tier]
Stay on `Image` for read → transform → detect → annotate → write. Drop to `Mat` extensions when you
need an operation `Image` doesn't surface, when you're processing borrowed video frames (below), or
when you want to thread one `Mat` through several stages without an `Image` wrapper per step.
:::

Movement between the tiers is explicit and cheap. Borrow the `Mat` with `image.mat` (the `Image`
keeps ownership), or hand the whole [`Managed`](/mat-lifecycle) over with `image.managed`; go the
other way with `Image.wrap(managed)`:

```scala mdoc:silent
val handle: Managed[Mat] = Image.blank(32, 32).managed   // Image → Managed[Mat]
val back: Image = Image.wrap(handle)                      // Managed[Mat] → Image
back.close()
```

## Four modules, split along real lines {#three-modules-split-along-real-lines}

The published surface is deliberately four artifacts, so you only pull what you use:

| Module | Coordinate | Holds |
|---|---|---|
| **core** | `com.worxbend::scalacv` | the OpenCV wrapping — `Image`, `Managed`, filters, contours, drawing, Hough, video capture, the camera model |
| **vision** | `com.worxbend::scalacv-vision` | detectors, DNN inference, pose/tracking/motion, OCR, calibration, the SLAM/navigation front end |
| **graphs** | `com.worxbend::scalacv-graphs` | the `Picture` scene graph, charts, GIF animation, the RGBA `Color` palette |
| **zio** | `com.worxbend::scalacv-zio` | optional scoped resources and streams on top of core |

The dependency graph is a shallow star — `vision` and `graphs` each depend only on `core`, and
`core` depends on neither:

```
                 scalacv (core)
                /      |      \
        scalacv-vision |  scalacv-graphs
                       |
                  scalacv-zio
```

So someone who only wants `Image.read(…).gray.canny(…)` never pulls a SLAM loop-closure detector
into their jar. A fourth artifact, `scalacv-zio`, adds the [ZIO](/zio) bindings on top of `core`.

:::warning[The split is a build invariant, not a suggestion]
`core` must never depend on `vision` or `graphs` — a new `core → vision/graphs` edge introduces a
cycle and breaks the build. Domain code that "starts from an image" (face detection, marker AR,
pose overlays) therefore lives in `vision` as **extension methods** on `Image`, not as members of
`Image` in `core`. That is why `image.faces(detector)` reads like a method but is defined in a
different module.
:::

Each module brings its own import — `scalacv.*` for core, `scalacv.vision.*` and `scalacv.graphs.*` for
the layers you have on the classpath (they are deliberately *not* one shared package, so the artifacts
can coexist on a JPMS module path). The corresponding import activates each module’s extension methods; merely adding its jar does not.

## Geometry and backend boundaries

`RigidTransform` is copied-out core data, not a native matrix owner. It maps column-vector points as
`x_destination = R * x_source + t`; `a.compose(b)` applies `b` first, then `a`. Rotation must be a
finite proper orthonormal 3×3 matrix, and translation contains three finite values. Construction and
`copy` validate these requirements. Composition and inversion re-orthonormalize computed rotations
so accepted input rounding does not accumulate beyond the validation tolerance. The inverse reverses
the mapping, and Rodrigues conversion uses
radians without loading OpenCV:

```scala mdoc:silent
val objectToCamera = RigidTransform.fromRotationVector(Vector(0.0, 0.0, 0.0), Vector(0.0, 0.0, 2.0))
val cameraPoint = objectToCamera.transformPoint(Point3(1, 0, 0))
assert(objectToCamera.inverse.transformPoint(cameraPoint) == Point3(1, 0, 0))
```

The vision views label their frames: `Pose3D.transform` is object → camera, `CameraPose.transform`
is world → camera, and `CameraMotion.transform` is first camera → second camera. Monocular motion's
translation is a unit direction, not metres. Recover a common scale before composing it with metric
poses or projecting metric landmarks; sharing the type does not make the units agree.

`Picture` is an immutable scene value. `Renderer` receives transformed primitives in painter's order
and resolved `PictureStyle` values. `PictureLayout` uses a supplied `TextMeasurer`; shape-only bounds
need neither text metrics nor native loading. `OpenCvRenderer` is the raster adapter and preserves
`Image` consumption. See [graphics](/graphics) for an independent command-renderer example.

`Size` retains finite, non-negative `Double` extents because geometry and text measurement can be
fractional. Allocating an image is an integer-pixel operation, but changing this shared type to `Int`
would also truncate valid layout measurements. Raster boundaries keep their documented policies:
absolute `resize(Size(...))` truncates, while `scaled` uses OpenCV's half-to-even rounding. Zero is
an empty geometric extent, not a valid allocation target. Non-finite extents are rejected at construction.

### Why these artifact and package boundaries stay

An optional IO artifact is deferred. Capture, codecs and image processing use the same classifier-less
OpenCV API dependency; moving the wrappers does not remove that Maven dependency or the native runtime
payload. `Image.read`/`write`, `Camera`, `Recorder`, and the scoped video bindings also share core ownership
and errors. Splitting them now would require moving APIs or introducing dependency cycles without the
dependency reduction that normally justifies an optional artifact.

The public `scalacv.vision` package stays intact. Its domains are already separate source responsibilities:

| Domain | Entry points | Guide |
|---|---|---|
| Detection and recognition | `FaceDetect`, `FaceRecognizer`, `Cascades`, `Dnn` | [Object detection](/object-detection) |
| Pose and marker geometry | `Pose3D`, `Ar`, `HeadPose` | [Marker AR](/marker-ar) |
| Motion and tracking | `OpticalFlow`, `Tracker`, `ObjectTracker` | [Tracking](/tracking) |
| Mapping and navigation | `CameraPose`, `Localizer`, `VisualOdometry`, `Odometry`, `OccupancyGrid`, `Navigator` | [Navigation](/navigation) |

Relocating these public types into subpackages would break imports, published names and API links without
changing their dependency graph. Prefer focused files and guide entry points; reconsider packages or
artifacts only when a concrete optional dependency or domain boundary requires them.

## One ownership primitive

Every native object scalacv hands you is owned by a [`Managed`](/mat-lifecycle) — an off-heap handle
that frees exactly once and throws an `IllegalStateException` (not a segfault) if you touch it after
release. `Image`, `Camera`, `Recorder`, `Descriptors` and the detectors all wrap one. The contract
is uniform across the whole library:

| Category | Who releases | Rule | Examples |
|---|---|---|---|
| **Owned** | you (or a scope) | close it once | `Image`, `Camera`, a `Managed[Mat]` |
| **Borrowed** | the owner | do **not** close it | `image.mat`, a `Video.frames` frame |
| **Copied-out** | nobody (plain data) | keep it forever | `Contour`, `Rect`, `Scalar`, a `Pose` |

The scoped forms — `Managed.use`, `Image.reading`, `Camera.using` — release for you on success,
failure, and exception, so prefer them over holding a handle by hand:

```scala mdoc:silent
val area: Long =
  Managed.use(Mat(20, 10, CvType.CV_8UC1))(m => Rect(0, 0, m.cols, m.rows).area)
// `area` is copied-out plain data — safe to keep after the Mat is freed
```

```scala mdoc
area
```

`Image` adds **move semantics** on top: a transform *consumes* the image it was called on, so a long
chain holds exactly one live `Mat` at a time and never a pile of intermediates. Reuse a consumed
`Image` and you get a clear error, not freed memory:

```scala mdoc:crash
val img = Image.blank(8, 8)
val gray = img.gray  // consumes `img`
img.width            // throws: `img` was spent by `.gray`
```

To use one image two ways, branch off a `copy` first — the copy is independent, so the original
survives:

```scala mdoc:silent
val src = Image.blank(120, 80, Scalar.White)
val edgeBytes = src.copy.gray.canny(50, 150).bytes(".png")  // branch works on a copy
val thumbBytes = src.resize(30, 20).bytes(".png")           // consumes `src`
```

Read [Mat lifecycle](/mat-lifecycle) for why this exists (the GC cannot see off-heap pressure, so it
will not free native memory under pressure) and the full rules, including
`-Dscalacv.trackOwnership=true` to pin down *where* a handle was spent when a use-after-move fires.

## One error policy

scalacv draws a deliberate line between the two kinds of failure:

- **Data-dependent, expected** — a missing file, undecodable bytes, a model that won't load, a
  calibration that won't converge. These return `Either[CvError, A]`.
- **Programmer errors** — an even Gaussian kernel, a negative radius, reusing a consumed handle.
  These **throw** (`IllegalArgumentException`, `IllegalStateException`) rather than being
  pattern-matched, because they are bugs to fix, not conditions to branch on.

`CvError` is a typed hierarchy, so you can match on exactly what went wrong:

| Case | Means |
|---|---|
| `CvError.DecodeFailed` | image bytes / file could not be decoded |
| `CvError.EncodeFailed` | image could not be written |
| `CvError.LoadFailed` | a model, cascade, network, or video source could not be resolved |
| `CvError.EndOfStream` | an opened capture did not deliver the requested snapshot; stream traversal ends normally |
| `CvError.CalibrationFailed` | camera calibration did not converge |
| `CvError.NativesMissing` | the platform native jars are absent (carries the fix) |
| `CvError.NativeCall` | OpenCV threw mid-operation; wraps its message, names the op |

A transform can surface a `CvError.NativeCall` when OpenCV itself rejects the pixels mid-chain — an
unchecked throw. To fold that into an `Either`, wrap the chain in `Cv.attempt` (which is exactly
what `Image.reading` does for you):

```scala mdoc:silent
val safe: Either[CvError, Int] =
  Cv.attempt("measure") {
    val measured = Image.blank(32, 32).gray.canny(50, 150)
    try measured.height
    finally measured.close()
  }
```

```scala mdoc
safe.isRight
```

When a failure really would be a bug, `Cv.orThrow` runs the same wrapping but rethrows instead of
returning a `Left`. Full treatment in [The error model](/error-model).

## Putting it together

A realistic pipeline touches all four ideas: one import per module (the module split), the high-level tier
for the common path, a mid-level borrow where `Image` doesn't reach, a scope so nothing leaks (ownership),
and an `Either` at the boundary (error policy):

```scala mdoc:compile-only
Image.reading("photo.jpg") { img =>        // scope closes `img` for us
  val boxes = img.contours()               // query borrows — `img` stays alive
    .filter(_.area > 500)                  // copied-out data, safe to keep
    .map(_.boundingRect)
  img.drawRects(boxes, Scalar.Green)        // transform consumes, returns annotated image
    .write("annotated.png")                 // terminal releases
}
```

## Where to go next

- **Basics:** [Getting Started](/getting-started) → [The Image API](/image-api) →
  [Image processing](/image-processing).
- **Trust the memory model:** [Mat lifecycle](/mat-lifecycle).
- **Drop a tier:** [Working with the raw OpenCV API](/low-level).
- **Handle failure well:** [The error model](/error-model).
- **Go fast and wide:** [Performance](/performance) and [Concurrency & thread safety](/concurrency).
- **When something breaks:** [Troubleshooting](/troubleshooting).
```
