# Getting Started

**scalacv** is a Scala 3 wrapper for [OpenCV](https://opencv.org) 4.14 — the industry-standard
computer-vision library. It wraps the official OpenCV **Java API** so you write ordinary Scala:
`Seq`, `Option`, `Either`, extension methods, and a fluent [`Image`](/image-api) you transform by
chaining. Typed flags replace raw `int` constants; explicit ownership keeps native memory manageable.

If you have never touched OpenCV, that is fine — this page takes you from an empty `build.mill` to a
working edge-detection pipeline. If you *have*, the short version is: add one platform jar, call
`OpenCv.load()` once, and reach for `Image`.

:::tip[The 30-second mental model]
scalacv has **two tiers** (the high-level [`Image`](/image-api) and mid-level `Mat` extensions),
**four artifacts** (`core`, `vision`, `graphs`, optional `zio`), **one ownership rule** (a transform consumes the
image it was called on), and **one error policy** (expected failures are `Either`, bugs throw). The
[Architecture](/architecture) page unpacks all four; you do not need them yet to run the examples
below.
:::

## Add the dependency

**Source release, not verified Central availability:** these examples use the latest repository tag,
`v0.4.1`. Central upload and attestation are disabled and tag workflows create draft releases.
From a clean checkout of that tag, run `./mill __.publishLocal`, then enable local Ivy resolution
(`ivy2Local` in Coursier; sbt includes it by default). For unreleased fixes, build the current
checkout and substitute its actual `./mill show core.publishVersion` in every ScalaCV coordinate.
Do not infer registry publication from a Git tag or from this site's deployment.


scalacv defines publication coordinates under the group id `com.worxbend` as **four separate artifacts**: `scalacv`
(the core), `scalacv-vision`, `scalacv-graphs` and `scalacv-zio`. Only the core is required. The
other three are opt-in, so a program that reads and filters images never pulls a SLAM loop-closure
detector into its jar — but that also means a symbol that lives in one of them will not compile
until you add its line. Three of the five tutorials on this site need `scalacv-vision` — faces, DNN,
and the motion-alarm step of the video tutorial — so add that one too if you plan to follow along.

On top of scalacv itself you need OpenCV. scalacv depends on the OpenCV **Java API**, which has no
native code in it. The natives ship in per-platform classifier jars. This project keeps its POMs
platform-neutral (the pinned Mill publisher does not encode dependency classifiers), so you add
both native artifacts for your platform yourself.

All of it in Mill — delete the scalacv lines you do not need, and keep both bytedeco ones:

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",         // core: images, video, contours, drawing, filters
  mvn"com.worxbend::scalacv-vision:0.4.1",  // detectors, DNN, pose, tracking, motion, OCR, calibration, SLAM
  mvn"com.worxbend::scalacv-graphs:0.4.1",  // the Picture scene graph, charts, animated GIFs
  mvn"com.worxbend::scalacv-zio:0.4.1",     // only if you use ZIO
  mvn"org.bytedeco:opencv:4.14.0-1.5.14;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.34-1.5.14;classifier=linux-x86_64"
)
```

The same for sbt (note `%%` for the Scala artifacts, `%` for the Java-world bytedeco jars, and
`classifier`):

```scala
libraryDependencies ++= Seq(
  "com.worxbend" %% "scalacv"        % "0.4.1",
  "com.worxbend" %% "scalacv-vision" % "0.4.1",
  "com.worxbend" %% "scalacv-graphs" % "0.4.1",
  "com.worxbend" %% "scalacv-zio"    % "0.4.1",
  "org.bytedeco" %  "opencv"         % "4.14.0-1.5.14" classifier "linux-x86_64",
  "org.bytedeco" %  "openblas"       % "0.3.34-1.5.14" classifier "linux-x86_64"
)
```

Both `opencv` and `openblas` lines are needed: `libopencv_core` links `libopenblas`, so omitting the
second gives you a half-loaded library.

Pick the classifier that matches where the code will run:

| Platform | classifier |
|---|---|
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-arm64` |
| macOS Apple Silicon | `macosx-arm64` |
| macOS Intel | `macosx-x86_64` |
| Windows x86-64 | `windows-x86_64` |

There are `-gpu` variants of `linux-x86_64`, `linux-arm64` and `windows-x86_64` if you need CUDA.
There is **no** `windows-arm64` build.

Don't want to choose? `mvn"org.bytedeco:opencv-platform:4.14.0-1.5.14"` bundles every platform and
works anywhere — for about **408 MB** instead of 36–80 MB. Good for a laptop, wasteful in a
single-platform container.

Which modules do you actually need?

| You want to… | Add this coordinate |
|---|---|
| read/transform/write images, contours, video capture | `com.worxbend::scalacv` (core, always) |
| face/QR/marker detection, DNN, pose, tracking, motion, OCR, SLAM | `com.worxbend::scalacv-vision` |
| draw scene graphs, charts, animated GIFs | `com.worxbend::scalacv-graphs` |
| an effect-typed API | `com.worxbend::scalacv-zio` |

`vision` and `graphs` depend only on `core`, so you pull exactly what you use — see
[Architecture](/architecture#three-modules-split-along-real-lines).

:::note[A missing module looks like a missing method]
Each module has its own package and import: `scalacv.*` for core, `scalacv.vision.*` for vision,
`scalacv.graphs.*` for graphs, and `scalacv.zio.*` for ZIO. Add both the dependency and its wildcard
import. If the compiler says `value faces is not a member of Image` or `Not found: Cascades`, check
that `scalacv-vision` is on the classpath and `import scalacv.vision.*` is in scope; core's import
alone does not activate vision extensions. See [Troubleshooting](/troubleshooting).
:::

:::note[If you forget the native lines]
`scalacv` alone compiles without them, but it will not run: the OpenCV symbols are absent until you
add them. `OpenCv.load()` does not fail with a cryptic link error — it prints a copy-pasteable fix
naming the platform you are actually on. See [Troubleshooting](/troubleshooting) if you hit it.
:::

**What lands on disk.** For `linux-x86_64` the `opencv` jar is ~31 MB and `openblas` ~20 MB; the
first `OpenCv.load()` extracts them once into `~/.javacpp` (~196 MB on Linux) and every later run
reuses that cache — see [The native cache](/native-cache) to relocate or pre-warm it.

## Load the natives

Call this **once**, at the top of your program, before touching any other scalacv API. It is
idempotent and thread-safe, so calling it again from anywhere is free:

```scala mdoc:silent
import scalacv.graphs.*
import scalacv.*

OpenCv.load()
```

That single `import scalacv.*` brings in everything — `Image`, `Scalar`, `Rect`, the enums, and all
the extension methods. You import once per file, not per feature.

```scala mdoc
OpenCv.isLoaded
```

:::warning[Load before you build]
`Image.blank`, `Image.read`, `Camera.open` and friends all cross into native code. Calling them
before `OpenCv.load()` is the single most common first-run mistake. Put the call in your `main` (or
a test fixture) so it always runs first.
:::

## Your first pipeline

The high-level [`Image`](/image-api) API reads, transforms and writes in a single chain — every
intermediate is freed for you. Lead with `Image.reading`: it scopes the image to the block and
releases it on success, on failure, and on exception, so this is the one entry point that cannot
leak the source:

```scala mdoc:compile-only
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

Read that left to right: open `photo.jpg`, convert to greyscale, blur slightly to kill noise, run
[Canny edge detection](/image-processing), write `edges.png`. Each step returns a *new* `Image` and
spends the previous one, so only one native buffer is alive at a time — more on that
[below](#the-one-rule-you-must-know-move-semantics).

The `read`/`flatMap` form is equivalent when you would rather thread the `Either` by hand — every
terminal (`write`, `bytes`, `close`) still releases — but reach for it only when `reading` does not
fit:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

Here it is end to end on a scene we draw ourselves, so it runs with no image file:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Image
    .blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray
    .canny(50, 150)
    .bytes(".png")
```

`bytes` is a terminal, like `write` — it encodes to an in-memory PNG (great for an HTTP response or
a BLOB) and releases the image. Everything is typed; there are no raw `int` constants anywhere:

```scala mdoc
ColorConversion.BgrToGray.cvValue
```

## The one rule you must know: move semantics

`Image` has **move semantics**. Every transform (`gray`, `blur`, `canny`, `resize`, `crop`, any
`draw*`, …) *consumes* the image it was called on and hands back a fresh one. This is what makes a
long chain leak-free: each step frees the previous native `Mat`, so the pipeline never piles up
intermediates. The catch is that you may not reuse a spent image — doing so throws a clear
`IllegalStateException`, not a segfault:

```scala mdoc:crash
val img = Image.blank(8, 8)
val gray = img.gray  // consumes `img`
img.width            // throws: `img` was spent by `.gray`
```

To branch — use one image two ways — take a [`copy`](/image-api) first:

```scala mdoc:silent
val base = Image.blank(64, 64, Scalar.White)
val thumb = base.copy.resize(16, 16)   // copy is independent
val full = base.gray.canny(50, 150)    // base is still live for this branch
thumb.close()
full.close()
```

Two kinds of method behave differently from transforms:

| Kind | Examples | Effect on the receiver |
|---|---|---|
| **Transform** | `gray`, `blur`, `canny`, `crop`, `drawRect`, `filter` | **consumes** it, returns a new `Image` |
| **Query** | `width`, `height`, `channels`, `contours`, `faces` | **borrows** it — stays alive, call as often as you like |
| **Terminal** | `write`, `bytes`, `close` | **consumes** it and releases the native memory |

The full story — and why the JVM's garbage collector cannot manage this for you — is in
[Mat lifecycle](/mat-lifecycle).

## Building a scene from scratch

You do not need an image file to experiment. `Image.blank(width, height, color)` gives you a canvas,
and the `draw*` methods paint onto it. Coordinates are pixels with the origin at the top-left, `x`
right and `y` down. Colours are [`Scalar`](/drawing) values in **BGR** order (OpenCV's default), so
`Scalar.Red` is `Scalar(0, 0, 255)`:

```scala mdoc:silent
val poster =
  Image.blank(240, 160, Scalar(30, 30, 30))               // dark grey background
    .drawRect(Rect(20, 20, 80, 60), Scalar.Green, Thickness.Filled)
    .drawCircle(Point(170, 70), 40, Scalar.Red, Thickness.Filled)
    .drawText("scalacv", Point(20, 140), Scalar.White, scale = 1.2)
    .bytes(".png")
```

Pass `Thickness.Filled` for a solid shape or `Thickness.Stroke(n)` for an `n`-pixel outline; the
default is a one-pixel stroke. See [Drawing & annotation](/drawing) for the full palette and text
metrics.

## Querying an image

Queries only read, so you can call as many as you like before consuming the image. Here we count the
white shapes on a black canvas by finding their contours:

```scala mdoc:silent
val shapes =
  Image.blank(200, 120, Scalar.Black)
    .drawRect(Rect(20, 20, 60, 40), Scalar.White, Thickness.Filled)
    .drawCircle(Point(150, 60), 25, Scalar.White, Thickness.Filled)
    .gray

val shapeCount = shapes.contours().size   // borrows `shapes`, leaves it alive
val stillWide = shapes.width              // still alive — another query
shapes.close()                            // done: release it ourselves
```

```scala mdoc
shapeCount
```

:::note[Who closes what]
`Image.reading` and `Managed.use` close for you. When you build an `Image` by hand and never reach a
terminal (`write`/`bytes`/`close`), *you* must close it — as we did above — or it leaks. In a
long-running program a leak is silent until you run out of native memory, so prefer the scoped forms.
See [Mat lifecycle](/mat-lifecycle#the-ownership-contract).
:::

## Applying a named filter

The [graphs/filters](/filters) catalogue bundles ready-made "looks" as composable
`Image => Image` transforms. Applying one consumes the image, exactly like any other transform:

```scala mdoc:silent
val vintage =
  Image.blank(96, 96, Scalar(120, 90, 60))
    .filter(Filter.vintage)
    .bytes(".jpg")
```

Every built-in filter is available, and you can `andThen` them or define your own with
`Filter("mine")(_.gamma(1.2).saturate(1.3))`:

```scala mdoc
Filter.all.size
```

## Reading images efficiently

`Image.read` (and the scoped `Image.reading`) take an optional [`ImreadFlags`](/image-io). Decode
straight to greyscale to skip a colour conversion, or decode at a fraction of full resolution — the
codec skips the discarded detail rather than producing it and then shrinking, which is meaningfully
faster for large files:

```scala mdoc:compile-only
// Straight to single-channel grey — no BGR round-trip.
Image.read("scan.jpg", ImreadFlags.Grayscale)

// Decode a huge photo at half resolution in one step.
val half = ImreadFlags(ImreadColor.Color, ImreadScale.Half)
Image.read("huge.jpg", half)
```

| Flag | What it does |
|---|---|
| `ImreadFlags.Color` | 3-channel BGR (the default) |
| `ImreadFlags.Grayscale` | single-channel, no colour |
| `ImreadFlags.Unchanged` | keep alpha / original depth |
| `ImreadFlags(ImreadColor.Color, ImreadScale.Half)` | decode at ½ (or `Quarter`, `Eighth`) size |

Reduced-size decode exists only for `Grayscale` and `Color` — the type rejects nonsensical
combinations up front. See [Image I/O](/image-io) for the complete matrix.

## When things fail

scalacv splits failure into two deliberate kinds. **Expected, data-dependent** problems — a missing
file, undecodable bytes, a model that won't load — come back as `Either[CvError, A]` so you handle
them as values:

```scala mdoc:silent
val missing: Either[CvError, Image] = Image.read("does-not-exist.png")
```

```scala mdoc
missing.isLeft
```

**Programmer errors** — a negative blur radius, reusing a consumed image — *throw*
(`IllegalArgumentException`, `IllegalStateException`), because they are bugs to fix, not conditions
to branch on. That division, and how to fold a mid-chain native error into an `Either` with
`Cv.attempt`, is the whole of [The error model](/error-model).

## Where to go next

- **The high-level surface end to end:** [The Image API](/image-api).
- **The operation catalogue** — every filter, morphology, threshold and transform:
  [Image processing](/image-processing).
- **How it all fits together:** [Architecture & mental model](/architecture), then
  [Mat lifecycle](/mat-lifecycle) for the memory rules.
```
