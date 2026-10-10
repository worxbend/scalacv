<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="website/static/img/logo-dark.svg">
  <img src="website/static/img/logo.svg" alt="scalacv" width="140" height="140">
</picture>

# scalacv

**An eloquent Scala 3 API for OpenCV 4.14 — a high-level image toolkit over the complete Java bindings. Typed, headless, and honest about native memory.**

[![CI](https://github.com/w0rxbend/scalacv/actions/workflows/ci.yml/badge.svg)](https://github.com/w0rxbend/scalacv/actions/workflows/ci.yml)
[![Scala 3.3 LTS](https://img.shields.io/badge/scala-3.3%20LTS-DC322F.svg)](https://www.scala-lang.org)
[![JDK 17+](https://img.shields.io/badge/jdk-17%2B-blue.svg)](https://adoptium.net)
[![OpenCV 4.14.0](https://img.shields.io/badge/opencv-4.14.0-5C3EE8.svg)](https://docs.opencv.org/4.14.0/)
[![License](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

</div>

---

## ✨ Features

- **Typed everything.** No raw `int` constants. `ColorConversion.BgrToGray`, not `6`.
- **Explicit native ownership.** `Managed[A]` releases at most once and guards access through the owner. Raw handles, escaped values and concurrent JNI calls remain the caller’s responsibility; the wrapper is not a linear type or a concurrency lock.
- **Genuinely headless.** `OpenCv.load()` needs no GUI toolkit and no `apt-get` on any runner.
- **Errors as values where they belong.** `Either[CvError, A]` for the failures you can expect; exceptions for the bugs you cannot.
- **Two levels, one library.** A high-level `Image` pipeline for the common cases, and the full typed `org.opencv.*` surface underneath — never hidden.
- **Batteries included.** Filters and morphology, geometric transforms, contours and Hough, colour segmentation; a composable **2D graphics layer** (`Picture`) for overlays, dashed strokes, charts and animation; Haar/YuNet faces, QR, ArUco, ONNX inference; a high-level `Camera`/`Recorder`; **motion detection** for a static/MJPEG cam; **pose estimation** (skeletons, hands, head pose); **gesture recognition**; **video-conferencing** background blur & virtual backgrounds; **screen analysis** (template matching, change detection); **OCR** preprocessing (deskew + a pluggable engine); **camera calibration** (chessboard intrinsics + lens undistortion); and a **visual-navigation front end** — optical flow, ORB features, stereo depth & obstacles, and visual odometry.

## 🚀 Quick start

**Publication status:** the latest repository tag is `v0.4.1`; the coordinates below refer to that
source release, not a verified Central deployment. Central upload and attestation are disabled,
and the release workflow creates drafts. For a reproducible local install, use a clean `v0.4.1`
checkout and run `./mill __.publishLocal`; configure your build to resolve local Ivy artifacts
(e.g. `ivy2Local` in Coursier). sbt uses local Ivy by default; a Maven consumer instead needs
`./mill __.publishM2Local`. See [RELEASING.md](RELEASING.md) before relying on remote availability.
Unreleased fixes in this checkout require its actual `./mill show core.publishVersion`, not the
`0.4.1` coordinates. All four module versions must match.

```scala
// build.mill  (or the equivalent for your build tool)
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",         // the OpenCV wrapping: Image, Managed, filters, contours…
  // Optional layers, each depending only on the core — add the ones you use:
  //   mvn"com.worxbend::scalacv-vision:0.4.1"  // detectors, DNN, pose/tracking, OCR, calibration, SLAM
  //   mvn"com.worxbend::scalacv-graphs:0.4.1"  // the Picture scene graph, charts, GIF animation

  // Natives for YOUR platform. This project keeps classifiers out of its platform-neutral
  // published POM, so this line is yours to pick — see "Why two lines?" below.
  mvn"org.bytedeco:opencv:4.14.0-1.5.14;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.34-1.5.14;classifier=linux-x86_64"
)
```

**High-level — the `Image` pipeline.** `Image.reading` scopes the image for you and is the entry
point for synchronous scoped work. Keep borrowed handles inside the callback; finish manually owned
branches with `close` or a consuming terminal. Scope cleanup is not a guarantee for raw handles or
resources explicitly transferred out.

```scala
import scalacv.*

OpenCv.load()

// Every intermediate frees itself; `reading` guarantees the source is freed too, no matter what.
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

The `read`/`flatMap` form is there when you want to thread the `Either` yourself, and each terminal
(`write`, `bytes`, `close`) still releases its receiver:

```scala
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

Detect, annotate, and drop to plain data just as fluently. The detectors live in the optional
`scalacv-vision` layer, which adds one import of its own (`scalacv.graphs.*` works the same way for the
graphics layer):

```scala
import scalacv.vision.*

Image.reading("street.jpg") { img =>
  val codes = img.qrCodes                       // Seq[QrCode] — decoded, immutable
  img.markFaces(img.faces(detector))            // draw boxes + landmarks (faces borrows; no copy needed)
     .drawText(s"${codes.size} codes", Point(10, 30))
     .write("annotated.png")
}
```

**Low-level — never walled off.** `mat` borrows the underlying handle; the full typed `org.opencv.*`
surface and the mid-level `Managed[Mat]` extension ops are always one step away:

```scala
Image.reading("photo.jpg") { img =>
  img.mat.cvtColor(ColorConversion.BgrToGray)   // mid-level extension → Managed[Mat]
     .pipe(_.gaussianBlur(Size(5, 5)))
     .pipe(_.canny(80, 160))
     .use(Images.encode(_, ".png"))
}
```

### Why two lines?

`scalacv` depends on the OpenCV **Java API** jar, which contains no native code. The natives ship in per-platform classifier jars, and this project’s Mill publication model does not encode dependency classifiers — so if we picked one for you, it would be wrong for everyone else.

| Your platform | classifier |
|---|---|
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-arm64` |
| macOS Apple Silicon | `macosx-arm64` |
| macOS Intel | `macosx-x86_64` |
| Windows x86-64 | `windows-x86_64` |

Don't want to choose? `mvn"org.bytedeco:opencv-platform:4.14.0-1.5.14"` bundles every platform and works anywhere — for about **408 MB** instead of 36–80 MB.

`scalacv` alone compiles fine without either native line, but nothing runs: the OpenCV symbols are absent until you add them. Get it wrong and `OpenCv.load()` does not fail with a link error — it prints a copy-pasteable fix naming the platform you are actually on.

**Footprint, so nothing surprises you.** For `linux-x86_64` the `opencv` jar is ~31 MB and `openblas` ~20 MB; on the **first** `OpenCv.load()` these are extracted once into `~/.javacpp` (~196 MB on Linux), and every later run reuses that cache. Point it elsewhere with `-Dorg.bytedeco.javacpp.cachedir=…` for a read-only home or a thin container layer.

> **Scala-first.** scalacv targets Scala 3 consumers: the API returns `Seq`/`Option`/`Either` and reaches you through extension methods brought in by one import per module (`scalacv.*`, `scalacv.vision.*`, `scalacv.graphs.*`). It wraps a Java library but is not designed to be called _from_ Java.

## 🧠 Why this exists

**OpenCV's `Mat` holds megabytes off-heap behind about forty bytes on-heap.** Heap pressure is the only thing that triggers a collection, and it is uncorrelated with native pressure — so a frame loop exhausts native memory while the heap stays small and the collector never runs.

Measured on this project's own test machine: 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped, no explicit `System.gc()`.

| | final RSS |
|---|---|
| unreleased | **5 865 MB** |
| `release()` | **144 MB** |

The same 41× on JDK 21 and JDK 25. It is not that reclamation is impossible — it is that nothing makes it happen in time.

It is worse for detectors. Of the 188 `org.opencv.*` types that own native memory, exactly **three** expose a public `release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `ArucoDetector` and 181 others do not. scalacv frees them anyway: 4000 leaked `KalmanFilter`s measured **54 GB**, against **86 MB** released.

That is the library. The typed API is the pleasant part; the lifetime handling is the part that keeps your process alive.

## 📚 Documentation

Full guide, API reference and cookbook: **[w0rxbend.github.io/scalacv](https://w0rxbend.github.io/scalacv)**

## 🤝 Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the explicit headless module list, tests, packaged consumers and compatibility gate.

## ⚖️ License

[Apache-2.0](LICENSE). See [`NOTICE`](NOTICE) and [`THIRD-PARTY.md`](THIRD-PARTY.md).

**Credits.** The scalacv name and original spark come from [`mcallisto/scalacv`](https://github.com/mcallisto/scalacv) by Mario Càllisto; two example ideas trace to [`rladstaetter/isight-java`](https://github.com/rladstaetter/isight-java) and [`chimpler/blog-scala-javacv`](https://github.com/chimpler/blog-scala-javacv). None of those repositories carried a license, so this is a clean-room library that shares no code with them — the credit is for the inspiration, recorded here and in [`NOTICE`](NOTICE).
