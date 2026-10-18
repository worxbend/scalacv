# Changelog

All notable changes to scalacv are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
`early-semver`: while the library is on `0.x`, a minor bump may break compatibility.

## [Unreleased]

### Build and CI
- Binary-compatibility gate compares against the released `v0.4.4` (`32fd13a`) as well.

## [0.4.4] — 2026-10-18

**Compatibility.** A `0.4.x` patch: binary-, source- and TASTy-compatible with 0.4.0, 0.4.1, 0.4.2 and
0.4.3 (japicmp against all four release baselines; the API goldens changed by additions only). No
public member was removed, renamed, re-typed or re-defaulted. The only observable differences are
the validation and error-case items listed under *Changed*, each of which replaces a JVM crash, a
native leak, a silently wrong result or an undocumented raw exception escaping an `Either` method.

### Added
- `Camera.foreach(f: Image => Unit): Unit` — the one-parameter form the scaladoc examples already
  showed; `for frame <- camera do …` now compiles. `foreach(attemptsPerFrame = 3)(f)` is unchanged.
- `PencilSketchPair.color: Managed[Mat]` — the preferred spelling of `colour` (kept; same handle).
- `Guidance.centerNearness: Double` — the preferred spelling of `centreNearness` (kept as the field).
- Internal (JVM-visible, `private[scalacv]`, not part of the Scala API): `Preconditions.requireInside`,
  `Models.fetch(spec, into, requestTimeout)`, `Models.isSafeFileName`, `Calibration.toCalibration`,
  and the `Intrinsics.apply$default$5` getter emitted for the new defensive `Intrinsics.apply`.

### Changed
Behaviour consumers can observe. Each item states what happened before, what happens now, and what
to do about it.
- `Models.fetch(spec, into)` with a `fileName` that is not a single path component (`../x`, an
  absolute path, `a/b`, `.`, `..`, a drive prefix, a NUL byte). Before: resolved against `into` and
  wrote — and atomically replaced — a file wherever that landed. Now: `Left(LoadFailed)` before any
  filesystem access, as `fetchCached` already did. Use a single path component.
- `Images.encode`/`Images.write` and `Image.bytes`/`Image.write` on an empty Mat or a 2-channel Mat
  (or more than 4 channels). Before: `Left(NativeCall)` wrapping OpenCV's assertion. Now:
  `Left(EncodeFailed)`, as the docs always promised. Code that matched `NativeCall` for these inputs
  should match `EncodeFailed`. Non-8/16-bit depths are still not rejected (OpenCV saturates to
  8-bit and warns); the scaladoc now says so.
- `Calibration.fromChessboard` when the solver throws or yields a non-finite reprojection error or a
  non-positive focal length. Before: a thrown `IllegalArgumentException` out of an `Either` method,
  or `Left(NativeCall)`. Now: `Left(CalibrationFailed)` with the reason. Match `CalibrationFailed`.
- `PoseEstimator.decode` with `KeypointLayout.Heatmap` when the tensor's keypoint count disagrees
  with the topology. Before: `IllegalArgumentException`. Now: thrown `CvError.NativeCall` (a
  malformed model output), the same as its sibling tensor-shape check. Catch `CvError.NativeCall`.
- `MotionDetector.frameDifference(...).detect` and `Odometry.update` after `close()`. Before: silently
  re-armed and retained a frame until garbage collection. Now: `IllegalStateException`, like
  `ObjectTracker`. Create a fresh detector/pipeline instead of reusing a closed one; `reset()` is
  still legal.
- `Animation.record(frames = 0)`. Before: opened the recorder and left a header-only video file
  behind. Now: `Right(0)` and no file, matching `Animation.gif` and `Camera.recordTo`.
- New `IllegalArgumentException` preconditions (programmer errors, thrown before any native call):
  - `Image.crop(rect)` and `Tracker.init(frame, box)`: a `Rect` whose `x + width` or `y + height`
    exceeds `Int.MaxValue`. Before: the `Int` overflow made the far edge negative, the bounds check
    passed, and the JVM crashed (SIGSEGV in `Mat.clone`, or in the native tracker's `init`).
    `Tracker.init` checks only the overflow; a box that is merely partly outside the frame keeps its
    per-tracker behaviour (CSRT/KCF accept it, MIL returns `NativeCall`).
  - `Dnn.blobFromImage(size)`, `FaceDetect.create(inputSize)`, `Recorder.open(size)` and
    `gaussianBlur(kernel)` with `sigmaX = 0`: a `Size` whose width or height truncates to 0 pixels
    (for example `Size(0.5, 0.5)`). Before: an un-resized blob, an unusable 0×0 writer, or OpenCV's
    `ksize.width > 0` assertion as `NativeCall`.
  - `Recorder.open(fps)`: a non-finite `fps`. Before: a writer was opened with `fps = +Infinity`.
  - `Image.scale(factor)`, `scaled(fx, fy)` and `rotated(angle, scale)`: a non-finite factor or scale.
    Before: OpenCV's `!dsize.empty()` assertion as `NativeCall`, or a silently wrong image.
  - `Color.hsl(hue, …)` (and therefore `spin`, `saturate`, `desaturate`): a non-finite hue. Before:
    silently returned grey.
  - `Picture.text(text, at)`: a non-finite anchor. Before: failed later, inside the renderer's
    transform.
  - `Image.blur`, `Image.medianBlur`, `erode`, `dilate`, `morphologyEx`,
    `MotionDetector.frameDifference(blurRadius)`, `Image.blurBackground(strength, feather)` and
    `Image.replaceBackground(feather)`: a radius above `(Int.MaxValue - 1) / 2`, whose kernel side
    `radius * 2 + 1` overflows. Before: the overflowed, negative kernel failed with the misleading
    "a Size cannot be negative" / "ksize must be odd". The message now names the radius.
- `CvError.NativeCall.getMessage` when the cause carries no message. Before: "OpenCV failed during
  <op>: null". Now: the cause's class name (`cause.toString`). Message text is not a stable
  interface.
- `Image.blank`, `Interop.toMat`, `Mats.column`, `Intrinsics.cameraMatrix`/`distCoeffs` and
  `OccupancyGrid.toImage` when OpenCV refuses the allocation. Before: a raw `org.opencv.core.CvException`.
  Now: `CvError.NativeCall` naming the method. Catch `CvError`.
- `Intrinsics(...)` and `RigidTransform(...)` (the companion `apply`) copy their `Seq` arguments, so a
  `Seq` over mutable storage (`ArraySeq.unsafeWrapArray`) can no longer be edited after
  construction-time validation. `copy` still stores by reference. Call sites are unchanged;
  callers that relied on later mutation reaching the stored values must construct a new instance.

### Fixed
- **Scope registry races (regression, 0.4.2 → 0.4.3).** `NativeScope` — the registry behind
  `Image.reading`, `Camera.foreach`/`taking`, `Animation.foreach` and zio `imageScoped`, introduced
  in 0.4.2 with scoped ownership — kept live owners in an unsynchronized `LinkedHashSet`. Copies and
  transforms made on several threads inside one scoped block were dropped from the registry and
  leaked when the block closed: the review probe lost about 31,900 of 32,000 copies at four
  threads, and the registry could report a negative size. Registration, forgetting and closing are
  now synchronized (the release loop runs outside the lock); native access to any one handle is
  still single-owner. New `NativeScopeConcurrencyTest` fails on the old code every run.
- `Models.fetch`/`FaceDetect.downloadModel`: the 60-second budget bounds the whole download. Before
  it bounded only the response headers, so a mirror that answered and then stalled mid-body pinned
  the caller forever.
- `Models.fetch`: an upper-case `HTTP://`/`HTTPS://` URL takes the timeout-bounded HTTP client path
  and gets the same status check as a lower-case one; before it fell through to `URL.openStream`.
- `Images.read` on a file above 2 GB returns `Left(DecodeFailed)` instead of letting
  `OutOfMemoryError` ("Required array size too large") escape.
- examples: `CalibrationDemo` releases its warp result and helper matrices on the failure path.
- The error-case corrections and new preconditions are behaviour changes and are listed under
  *Changed* above rather than repeated here.

### Security
- `Models.fetch` rejects a `ModelSpec.fileName` that is not a single path component; a `../` or
  absolute name could write (and atomically replace) a file anywhere the process can. `fetchCached`
  already rejected these. See *Changed*.
- Out-of-range `Image.crop`/`Tracker.init` rectangles reachable through the public API no longer
  crash the JVM. See *Changed*.
- Both fall under SECURITY.md's scope — "memory-safety defects reachable through the public API …
  and any way the library mishandles untrusted input into a native crash or worse" — and were found
  by the internal hardening review, not reported externally.

### Build and CI
- Binary-compatibility gate (`ci/binary-compatibility.py`) compares against the released `v0.4.3`
  (`7f2d0e6`) as well; its success line now counts the baselines instead of saying "both".
- Test sources are scalafix-linted: they compile with `-Wunused:imports` (the one `-Wunused` flag
  `OrganizeImports.removeUnused` needs; still never fatal), every test module is a `ScalafixModule`,
  48 test files lost their dead imports, and the `style` job runs the six `*.test.fix --check`.
- `ScalacvTestIsolation` (extracted from `ScalacvTests`) gives `zio.test` the same one-JVM-per-suite
  fork grouping and empty-discovery guard as the munit suites, and passes `-Dscalacv.zio.source`
  (the `zio/src/scalacv/zio/package.scala` path) to the `ZIO.attempt` scan.
- `Deps.opencvNatives` is a `lazy val`, so `Deps.platform` is no longer forced on every build
  evaluation.
- API golden renderer falls back to erased parameter lists (marked `/* erased */`) when the JVM
  generic signature is malformed — Scala 3 emits one for extension methods with their own type
  parameters — and `classesIn` fails on a class it cannot load instead of dropping it; the zio
  golden's `useZIO` line now records both parameters. New `MatExtensionShadowingTest` fails if any
  `Mat` extension method shares a name with a `Mat` member.
- Leak gates: `LeakAssertions.assertBounded` takes a required `leakBytesPerIteration` and refuses a
  configuration whose full leak is below 3× the RSS tolerance (the previous `blurBackground` and
  `undistort` gates could not detect the leak they named). Four new RSS workloads: `Video.frames`
  borrowing one buffer across a file, `Image.reading` with sequential copies and transforms, a
  `Camera.foreach` + `recordTo` round trip, and zio `processFrames`. The CI `LeakStress` driver runs
  6000 iterations instead of 2000: a leaked single-channel 240×240 intermediate (57,600 B) over the
  5800 measured iterations is 334 MB, past the 200 MB ceiling; at 2000 it was 104 MB and the gate
  passed while leaking.
- `SCALACV_REQUIRE_MODELS=1` on the CI build matrix and the macOS natives job: `FaceDetectTest`
  fails, rather than skips, when the YuNet model cannot be fetched.
- New `docs-site` CI job on pull requests: `scripts/assemble-docs.sh` (mdoc, Scaladoc, `/api/` link
  check) and the Docusaurus build, previously run only on push to master by `docs.yml`.
- Consumer smoke: both smoke programs print `OPENCV-ARTIFACT=<scalacv.Build.openCvArtifactVersion>`
  and `run.sh` pins its natives coordinate to it, so a Scala Steward bump that leaves the script
  behind fails the smoke.
- `scala-steward.yml` declares `contents: write` / `pull-requests: write` and a 30-minute timeout.
- The benchmarks compile the tests' `PixelHash` instead of keeping a second copy (`BenchImages.hash`
  removed).
- Three tests that could not fail now can: `VisionNumericalRegressionTest` asserts on the five-point
  candidates, the zio `ZIO.attempt` scan fails when it cannot find the module source, and
  `ExamplesTest` asserts the Haar detector finds nothing on a blank scene. `OpsTest`/`ContoursTest`
  pin the shared precondition messages.

### Documentation
- `error-model`: the anchor `#the-six-cases` is renamed to `#the-seven-cases` (external links need
  updating; `observability` was repointed); new row for `CvError.NativeCall` with an
  `IllegalStateException` cause (wrong model shape / algorithm missing); release-time
  `NativesMissing` inside `Image.reading`/`Camera.using`/`Recorder.using` is a `Left`, not a throw;
  an empty or 2-channel image is `EncodeFailed`.
- `operations-reference`: the 0.3-era `threshold`/`adaptiveThreshold` rows show the real signatures
  (`Thresholded` result; `adaptiveThreshold(blockSize, c, method, mode)`) with a compiled example.
- `streaming-and-backpressure` → `video`: the two "end of stream" links now resolve (explicit
  `{#end-of-stream-or-a-broken-one}` id on the heading).
- `video`: `CaptureInfo.frameCount` — backends that report `-1` are normalised to `0`.
- `models`: `fileName` must be a single path component; the 60-second budget bounds the whole
  download; a mirror that stalls mid-body is abandoned at the same deadline.
- `troubleshooting`: JDK 26+ final-field mutation (JEP 500) and `--enable-final-field-mutation`.
- `image-api`: transforms are not accessors even when they look like fields; a discarded `img.gray`
  leaks its result.
- `concurrency`, `mat-lifecycle`: scope registration is synchronized across threads; native access
  to one handle stays single-owner.
- Scaladoc: SPI compatibility policy on `Renderer`, `TextMeasurer`, `OcrEngine` and
  `MotionDetector`; `drawRect`/`drawRects` colour defaults corrected (`White` vs `Green`);
  `withPolygons` converter residue described accurately; a warning against wildcard-importing
  `ContourRetrieval`/`ContourApproximation` (their `List`/`None` cases shadow the Scala ones);
  `NativeCall`'s `IllegalStateException` cause documented.
- `RELEASING.md` lists the `v0.4.3` baseline and the open-trait compatibility rule;
  `CONTRIBUTING.md` says "every pinned 0.4.x release tag", adds the parameter-rename/default-value
  rule, the factory-naming conventions, `PixelHash.of` and the `SCALACV_SFACE_MODEL` gate note;
  `API-REFERENCE.md` no longer points at the retired `DOCS-PLAN.md`.

## [0.4.3] — 2026-10-15

### Build
- Added CI-gated publishing of all four libraries to GitHub Packages and Sonatype Maven Central,
  with exact tag/version/changelog checks, remote artifact verification, Java 17/Scala 3.3.8
  consumer checks and Central build-provenance attestations.
- Made VCS-derived publish versions track Git changes when build outputs are cached.

## [0.4.2] — 2026-10-13

### Added
- Scoped `Undistorter` prepares fixed-resolution lens-correction maps once, snapshots calibration,
  guards use after close, and returns independently owned frame results.
- Opt-in `TextMetricCache` bounds session text retention by entry count and UTF-16 character budget,
  with explicit invalidation; `PictureLayout.row` and `column` avoid repeated accumulated-scene scans.
- `Models.fetchCached` separates pinned revisions by SHA-256 and coordinates concurrent misses across
  threads and processes. Cache hits still verify the file; private roots remain caller-controlled.
- `Features.countMatches` avoids sorting and constructing result objects when only a count is needed;
  loop-closure scoring uses it without caching frame results.

### Fixed
- Model publication now requires atomic replacement and fails closed when unsupported, preserving an
  existing target rather than falling back to a partial copy. Legacy `fetch` retains its flat paths.
- Model-cache cancellation preserves interruption during lock waits, hashing and downloading.

### Build and documentation
- Made the ZIO clone-handoff cancellation test target the actual acquisition fiber before releasing
  its gate, removing a wrapper-to-channel cancellation race without weakening cleanup assertions.
- Added a real historical JVM binary-compatibility gate: checksum-pinned japicmp 0.23.1 compares
  all four packaged artifacts against locally built, commit-pinned v0.4.0 and v0.4.1 baselines.
  Positive/negative controls include method finality and an unchanged compiled subclass.
- Added a packaged Scala 3.3.8/JDK 17 consumer alongside Java, exercising all four module APIs;
  strengthened exact dependency/version/scope POM assertions and API-golden finality/generic bounds.
- Aligned install examples with source tag 0.4.2 while explicitly distinguishing local artifacts,
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
