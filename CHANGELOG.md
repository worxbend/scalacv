# Changelog

All notable changes to scalacv are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
`early-semver`: while the library is on `0.x`, a minor bump may break compatibility.

## [Unreleased]

### Build and documentation
- Added a real historical JVM binary-compatibility gate: checksum-pinned japicmp 0.23.1 compares
  all four packaged artifacts against locally built, commit-pinned v0.4.0 and v0.4.1 baselines.
  Positive/negative controls include method finality and an unchanged compiled subclass.
- Added a packaged Scala 3.3.8/JDK 17 consumer alongside Java, exercising all four module APIs;
  strengthened exact dependency/version/scope POM assertions and API-golden finality/generic bounds.
- Aligned install examples with source tag 0.4.1 while explicitly distinguishing local artifacts,
  unreleased fixes, draft GitHub releases and unverified Central availability. Upload and attestation
  remain disabled. Corrected synchronous/effectful ownership guidance and stale graphics prose.


## [0.4.1] — 2026-10-07

### Security
- Updated website transitive dependencies: `serialize-javascript` 7.1.2, `brace-expansion` 5.0.12,
  `proxy-addr` 2.0.8, `shell-quote` 1.12.0, `compression` 1.8.2, `joi` 17.13.8,
  `source-map-js` 1.2.2 and `fast-uri` 3.1.8. These updates address their reviewed advisories;
  other website dependency audit findings remain.

### Internal
- Updated the pinned `coursier/setup-action` to 3.0.4 and the external native RSS stress driver to
  JDK 25, while preserving the Java 17 consumer and bytecode floor.
- Optimized website logos and the social-card image without changing their decoded pixels or SVG
  accessibility metadata.

## [0.4.0] — 2026-10-07

### Breaking
- `Video.frames` and ZIO `frameStream` now yield `BorrowedMat`, a liveness-checked view over the
  reused decode buffer. Use `frame.mat` for Mat-based operations inside the loop; use `framesCopied`
  when frames must outlive it. Accessing a spent view throws before JNI; retaining the raw `Mat`
  escape hatch is still unsafe.
- Low-level `threshold` returns `Thresholded(image, computed)` instead of a tuple. Adaptive threshold
  selects `Threshold.Mode.Binary` or `BinaryInv` instead of a Boolean. Geometric border colour is
  named `color`; `Color.toScalar` is now `toBgrScalar` to make the channel order explicit.
- File splits change JVM owners of top-level extension methods. Scala imports stay the same, but
  previously compiled consumers must be recompiled.
- `PolarLine` uses Double fields. Marker-axis/cube default sizes use `Option[Double]` instead of a
  NaN sentinel. `CvError.EndOfStream` distinguishes an exhausted capture from a loading failure;
  exhaustive error matches must handle it.

### Added
- `pencilSketchBoth` retains both native sketch outputs instead of discarding the grayscale result.
- Typed `PnpSolver` and shared ScalaCV point conversion at the PnP native boundary.
- ZIO `frameStream` accepts `attemptsPerFrame`, sharing the synchronous reader's bounded retry policy.
- Pure, validated `RigidTransform` supplies composition, inverse, point mapping and stable Rodrigues
  conversion; `Pose3D`, `CameraPose` and `CameraMotion` expose frame-labelled transform views.
- Backend-neutral `Renderer`, `RenderPrimitive`, `PictureStyle`, `TextMeasurer` and `PictureLayout`;
  the explicit `OpenCvRenderer` preserves existing raster and Image-consumption behavior.

### Fixed
- Rotated circle and rectangle bounds enclose the full geometry; text bounds match axis-aligned
  rendering. Rectangle paths and rounded rectangles widen edge sums before integer overflow.
- `Size` rejects non-finite extents while preserving fractional geometry and raster rounding rules.
  Pose value constructors reject malformed, non-finite or non-rigid transforms; composition and inverse
  stabilize computed rotations instead of rejecting accepted near-orthogonal inputs.
- `ObjectTracker` rejects invalid confirmation thresholds and updates after close. Head-pose defaults
  use `Intrinsics.approx` consistently. Intrinsics allocations release on fill failure.
- SFace downloads use verified Git LFS media URLs; feature decoding checks embedding shape. Shared
  model loading validates files, rejects null/empty native handles, and releases handles if validation
  throws while preserving the typed native-error channel.
- Closed frame sources reject reads before JNI. Borrowed-frame documentation distinguishes sequential
  liveness checks from concurrent lifetime locking and the unchecked raw-Mat escape hatch.
- PnP solver documentation reflects OpenCV's DLS/UPnP-to-EPnP fallback and IPPE point requirements.
- Image, contour, drawing, effect, vision and graph boundaries reject invalid inputs before JNI;
  geometry arithmetic and transform conventions have regression coverage.

### Internal
- Split effects, deskew, Mat helpers, codecs, recording, pose estimation, head pose and tracking into
  focused files without changing their Scala packages. Shared preconditions and native scopes replace
  duplicated guards and cleanup islands.
- Vision/graphs suites run in their owning test modules; integration, property, API and POM gates
  remain in core. Shared test settings reject empty discovery, and CI/release run every owning suite.
- Scene data uses immutable style overrides instead of captured functions; native drawing and
  text-dependent layout are separate from the reusable scene model.
- Corrected module imports and API links to the published vision/graphs packages; preserved the borrowing
  guide anchor. The website typecheck unsets its preset's removed TypeScript `baseUrl` option and keeps
  the `@site/*` alias explicit.
- Added borrowed-frame lifecycle, validation and native RSS regressions, including ZIO scope cleanup;
  refreshed public API goldens and migrated documentation examples.

## [0.3.0] — 2026-09-21

### Breaking
- **`scalacv-vision` and `scalacv-graphs` moved to their own packages** — `scalacv.vision` and
  `scalacv.graphs` — instead of contributing classes to `package scalacv`. Three artifacts publishing
  into one package is a hard split-package error on a JPMS module path, and unfixable after 1.0, so it is
  fixed now under early-semver. Consumers keep one import per module: add `import scalacv.vision.*`
  and/or `import scalacv.graphs.*` beside `import scalacv.*`.
- **`Tracker.create` now returns `Either[CvError, Tracker]`** instead of throwing `CvError.NativeCall`
  when an OpenCV build lacks the algorithm — an environment failure belongs in the `Either`, like every
  other native-resource factory (`FaceDetect.create`, `Cascades.load`, `Dnn.fromOnnx`).

### Changed
- **The scoped-body error policy is now uniform.** `Camera.using`, `Camera.usingFile` and
  `Recorder.using` wrap their body in `Cv.attempt`, as `Image.reading` already did: a `CvError` thrown by
  an operation inside the block comes back as a `Left` instead of escaping past the `Either`. Other
  exceptions (`IllegalArgumentException`, use-after-close, anything not a `CvError`) still throw.

### Fixed
- The README's flagship example dropped a full cloned `Mat` unclosed (`img.copy.faces(detector)`) —
  `faces` borrows, so the copy was both unnecessary and a leak, in the snippet that teaches the ownership
  model. The docs' install snippets also drifted a release behind (0.1.0 coordinates, "OpenCV 4.13"
  descriptors); they now match the build.

### Internal
- The `solvePnP` ceremony (six owned Mats, `Cv.attempt` guard, decode-before-release) was written out in
  three places; it now lives once in core (`private[scalacv] Pnp.solve`), with head-pose, localization and
  marker-AR keeping only their solver flag and decode. The mask → contours → filtered boxes tail shared by
  motion, screen-diff and obstacle detection is one `Mats.blobs`.
- The zio module's public surface is now covered by the API golden gate (`zio/api.golden`), alongside
  core/vision/graphs.
- Release workflow: the GitHub Release is created as a **draft** until Central publishing is enabled (a
  tag must never announce artifacts that do not exist), the test suite runs as a `needs:` prerequisite
  before any publish step, and every GitHub Action across all four workflows is pinned to a commit SHA
  (Dependabot's `github-actions` ecosystem keeps them current).
- Website: `image-size` overridden to `^2.0.4` for the two infinite-loop advisories (GHSA-w3rx-r6r6-pgpr,
  GHSA-5p2g-fcmc-qvqq); the fix releases exist only on Codeberg, so the advisories list no patched
  version.

## [0.2.0] — 2026-09-19

### Fixed
- `Rect.bottomRight` no longer wraps negative for a corner past `Int.MaxValue`: the sums
  `x + width` and `y + height` were taken in `Int` and widened afterwards, so
  `Rect(Int.MaxValue - 1, Int.MaxValue - 1, 5, 5).bottomRight` returned `Point(-2147483645, …)`
  instead of `Point(2147483651, …)` — the same overflow `Rect.area` already widens to `Long` to
  avoid. Found by a new property test.

### Changed
- **OpenCV 4.14.0** (bytedeco `opencv:4.14.0-1.5.14`, JavaCPP 1.5.14) and **OpenBLAS 0.3.34**
  (`openblas:0.3.34-1.5.14`) replace 4.13.0-1.5.13 / 0.3.31-1.5.13. The two move together because
  `libopencv_core` links the OpenBLAS from the same presets line. Consumers must bump both classifier
  lines in their build to match; `Build.openCvVersion` now reports `4.14.0`. No scalacv API changed and
  the full suite passes unmodified against 4.14 on JDK 17 and 25 — including the Hough decode types and
  the `blobFromImage` mean/`swapRB` ordering, whose scaladoc now records the 4.14 verification.

### Internal
- **The test suite grew from 542 to 689 tests**, across core, vision, graphs and zio: `Image` ownership
  on the failure paths, pixel-level transform behaviour a dimension check cannot see, `Managed`
  transfer/adoption and suppressed-release policy, `Camera`/`Recorder` lifecycle at end-of-stream and on
  exceptions, the `BufferedImage` bridge, segmentation and compositing arithmetic, tracker confirmation
  and coasting, rotation conventions in `Localizer`/`VisualOdometry`, occupancy-grid ray integration,
  `Picture`/chart/GIF geometry, and ZIO scope release under interruption.
- **Two CI gates were silently hollow**, both the same Mill target-chaining bug: `./mill a.test b.test`
  passes the later targets to the first as test-name filters, so `./mill core.test zio.test examples.test`
  ran **only** `core.test` — the ZIO and examples suites never executed in CI — and the identical form in
  the scalafix step linted only `core` (20 of 75 sources) while its comment claimed all five modules.
  Both now use `+`-separated targets; nothing was hiding behind either.
- One forked JVM per test suite: a double free aborts the worker process, and Mill's default packing of
  several suites per worker meant such a crash took its co-tenants' results down unnamed.
- Build tooling: Mill 1.1.9, munit 1.3.6, munit-scalacheck 1.3.1, mdoc 2.9.2, OpenJFX 27 (local-only
  `examples-gui`). `-Werror` replaces the `-Xfatal-warnings` alias, which Scala 3.9 deprecates — the
  deprecation warning about the flag would otherwise be promoted to an error by the flag itself. Every
  CI job is now bounded by `timeout-minutes`, and `examples.test` also runs on the macOS arm64 leg.

## [0.1.0] — 2026-08-22

### Added
- `OpenCv.load()` — headless native loading that never requires a GUI toolkit, with a
  demand-driven resolver that never pulls a system OpenCV into the process.
- Resource lifecycle: `Managed[A]`, `Releasable` (with a finalizer-safe `delete(long)` bridge
  for the 185 handle types that have no public `release()`), and the `Cv.attempt` error policy.
- Typed enums and geometry value types; `Images` (read/write/encode/decode); imgproc extension
  ops with an explicit Mat-ownership contract; typed Hough, contours, cascades, QR, ArUco, YuNet
  face detection, ONNX inference, and headless drawing.
- Photo/stylisation transforms on `Image`: `colorMap`, `stylize`, `sketch`, `enhance`,
  `edgePreserving`, `inpaint`, `seamlessCloneInto`, `sepia`, `gamma`, `posterize`, `emboss`,
  `saturate`, `temperature`, plus colour-segmentation (`toHsv`/`inRange`/`applyMask`) and `blend`.
- **`scalacv-graphs`** — a 2D graphics layer: the immutable `Picture` scene graph (primitives, layout,
  affine transforms, dashed strokes), an RGBA `Color` palette (HSL, `wheel`/`ramp` palettes), `Chart`
  (bar/line), and `Animation` with a hand-rolled LZW `GIF` encoder. `image.draw(picture)` composites.
- **`scalacv-vision`** — the vision applications, each an extension layer over `core`:
  - **Faces & recognition**: YuNet detection with landmarks; SFace embeddings (`FaceRecognizer`,
    `FaceEmbedding` with cosine/L2 metrics) and an immutable `Gallery` for "who is this?".
  - **Pose**: `PoseEstimator` (MoveNet/OpenPose layouts, `PoseTopology`), `HeadPose` via `solvePnP`,
    `Gesture` recognition, and `drawSkeleton`.
  - **Markers/AR**: `Ar` marker pose (`Pose3D`/`MarkerPose`), axis/cube overlays.
  - **Tracking**: a constant-velocity `Kalman` point filter and `ObjectTracker` (SORT-lite
    tracking-by-detection with stable ids).
  - **Motion & video-conferencing**: `MotionDetector`; background blur / virtual backgrounds
    (`Segmenter` + `blurBackground`/`replaceBackground`).
  - **OCR** preprocessing (`forOcr`, deskew) with a pluggable engine; `Screen` analysis (template
    matching, change detection).
  - **Navigation / visual SLAM front end**: `OpticalFlow`, ORB `Features`, `StereoDepth` and obstacle
    detection, `VisualOdometry`/`Odometry`, `Localizer`, `Navigator`, `OccupancyGrid`, `LoopDetector`.
- `Camera`/`Recorder` (high-level capture) — including `Camera.taking`, a scoped batch that closes its
  frames for you, and a borrowing `Recorder.write(Mat)` so `Video.frames` records with no per-frame copy —
  `Video` interop, and `BufferedImage` interop (`Image.fromBufferedImage`/`toBufferedImage`, for AWT/Swing
  and notebook display).
- `Contour` geometry beyond area/perimeter/boundingRect: `centroid` (image moments), `convexHull`, and
  `approx` (Ramer–Douglas–Peucker polygon simplification).
- A `Models` registry + verifying downloader (`Models.fetch`); model specs live with their detectors
  (`FaceDetect.modelSpec`, `FaceRecognizer.modelSpec`).
- `scalacv-zio`: native ownership as ZIO `Scope`, plus a non-memoizing frame `ZStream`, typed-`CvError`
  boundary helpers (`fromCv`, `readImage`), and a scope-managed `imageScoped`.
- Ergonomics: `Color.toScalar`/`Scalar.toColor` bridges between the palette and OpenCV colours;
  one-call model verbs `image.estimatePose(net, …)` and `image.segment(net, …)`; `ObjectTracker.create`.
- Camera calibration: `Calibration` / `ChessboardPattern`, `Calibration.findCorners` and
  `Calibration.fromChessboard` (chessboard intrinsics + lens distortion, with the RMS reprojection
  error reported), a `CvError.CalibrationFailed` value for under-constrained captures, and
  `Image.undistort` / `Mat.undistort`. The recovered `Intrinsics` feed the existing pose stack
  (`Ar`, `HeadPose`, `Localizer`), turning its field-of-view guess into a measurement.
- A golden public-API signature test, so accidental API changes fail CI.
- `faces(Managed[FaceDetectorYN])` — a detector overload that keeps the spent-handle guard with the
  argument instead of discarding it through a bare `.get`.
- Opt-in ownership tracing (`-Dscalacv.trackOwnership=true`): a use-after-move `IllegalStateException`
  now carries the transform/terminal that consumed the handle as its cause.
- `Point.distanceTo` — the straight-line distance between two points, via `math.hypot` so it neither
  overflows nor underflows while squaring.
- `Releasable.nativeHandle` — `Releasable.handle` without the accessor argument, reading the address
  from the binding's own `nativeObj` field. `handle` is generic, so passing one type's
  `_.getNativeObjAddr` for another compiles cleanly and would free the wrong pointer; this form cannot
  be given the wrong accessor. `handle` remains for bindings that keep their address elsewhere.
- `Intrinsics` now rejects a distortion vector whose length is not one OpenCV accepts (0, 4, 5, 8, 12
  or 14), instead of passing it to native code that returns a silently wrong undistortion or pose. The
  accepted counts are public as `Intrinsics.ValidDistortionSizes`.

### Changed
- **Split the published artifact into three**: `scalacv` (core OpenCV wrapping), `scalacv-vision`
  (detectors/DNN/pose/tracking/OCR/calibration/SLAM), and `scalacv-graphs` (the `Picture`/chart/GIF
  layer). `vision` and `graphs` depend only on `core`; a consumer who only wants
  `Image.read(…).gray.canny(…)` no longer pulls a SLAM detector or a GIF encoder into their jar. Done
  before the first tag with MiMa originally planned for `0.2.0` (it was not armed then; the
  historical bytecode gate is recorded under Unreleased above). The golden API dump now covers the core module only (~1,600 lines, down from ~3,300).
- Slimmed `Image` to a lean core type. The domain verbs that only *start* from an image — `faces`,
  `detectHaar`, `qrCodes`, `arucoMarkers`, `arMarkers`, `drawSkeleton`, `markFaces`, `drawMarkerAxes`,
  `drawMarkerCube`, `drawTracks`, `forOcr`, `blurBackground`, `replaceBackground`, and `draw(Picture)` —
  are now **extension methods** in their domain files rather than members of `Image`. Call sites are
  unchanged under `import scalacv.*` (e.g. `image.faces(detector)` still reads the same).
- `Image.reading` now runs its body inside `Cv.attempt`, so a `CvError.NativeCall` from a transform in
  the chain returns as `Left` instead of escaping past the `Either`.
- `Intrinsics` is now a core type (was in `Ar`); `Image.undistort` takes `Intrinsics` directly, with the
  `Calibration` overload provided as a vision extension. The mid-level `Mat.undistort` is now
  `Mat.undistorted` (participle convention, and to free the `undistort` name for the `Image` overload).
- The use-after-move error now names the fix (`.copy`) and the tracing flag.

### Documentation
- `Image` scaladoc now documents the throwing surface (a transform throws `CvError.NativeCall`, an
  unchecked throw, on OpenCV rejection) and the library's Scala-first stance; `CLAUDE.md` records the
  two-tier (managed high-level / borrowed mid-level) API contract and corrects two stale notes.

The first released version.
