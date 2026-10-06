# Testing vision code

Vision code is deceptively hard to test well. Two properties trip up a naive test suite: it tends to depend on **image assets** that bloat the repo and rot over time, and its output is **not bit-identical across platforms** — different SIMD paths, different OpenCV builds, and even different CPUs round the last bit differently. A test that passes on your laptop and fails in CI, with a one-pixel difference nobody can see, is worse than no test at all: it trains everyone to ignore red.

This page shows the patterns scalacv's own suite uses to stay **fast, asset-free, portable, and honest** — so the tests are green because the code is right, not because the runner happens to match the machine that wrote the golden.

```scala mdoc:silent
import scalacv.graphs.*
import scalacv.*
import org.opencv.core.Core

OpenCv.load()
```

:::tip[New here?]
The techniques below assume you know how `Image` ownership works — a transform *consumes* its receiver, a query borrows it, a terminal releases it. If any of that is surprising, read [Mat lifecycle](/mat-lifecycle) first; it explains the move semantics these tests deliberately exercise.
:::

## Run the owning test module

Run every test module from the repository root:

```sh
./mill --no-server -j 1 core.test + vision.test + graphs.test + zio.test + examples.test + leaks.test
```

The `+` separators matter: `test` is a Mill command, so space-separated targets can be interpreted as test-name filters instead of running the other modules. `-j 1` keeps the full native/RSS run sequential; `--no-server` avoids sharing a long-lived Mill worker with another build.

Choose the module that owns the suite when running a targeted regression:

```sh
./mill --no-server -j 1 core.test.testOnly scalacv.FrameSourceSafetyTest
./mill --no-server -j 1 vision.test.testOnly scalacv.vision.RigidPoseInteropTest
./mill --no-server -j 1 graphs.test.testOnly scalacv.RendererContractTest
```

Vision and graphics unit suites live in `vision.test` and `graphs.test`. Core retains its unit tests, cross-module integration and ownership/property tests, and the public API/POM gates. Suite package names do not determine module ownership: a suite in package `scalacv` can belong to `vision.test` or `graphs.test`. The build rejects empty suite discovery and forks each MUnit suite into its own JVM so a native crash cannot erase another suite's results. The RSS canaries remain in the separate `leaks.test` module.

## A first test, end to end

Before the theory, here is the whole shape of a scalacv test in miniature: **draw** a scene with known geometry, **run** the code under test, then **assert** on a value you can predict. No files, no golden image, no platform assumptions.

```scala mdoc:silent
/** A deterministic BGR scene: shapes at fixed positions so a test can assert on known pixels. */
def scene(): Image =
  Image
    .blank(160, 120, Scalar(20, 40, 60))
    .drawRect(Rect(20, 20, 60, 40), Scalar.Green, Thickness.Filled)
    .drawCircle(Point(120, 80), 20, Scalar.Red, Thickness.Filled)
```

The circle is filled red (`Scalar.Red` is `(0, 0, 255)` in BGR) and centred at `(120, 80)`, so the pixel *at* that centre must have a red channel near 255. `mat.get(row, col)` reads one pixel as `[B, G, R]`:

```scala mdoc:silent
val shot = scene()
val redCentre = shot.mat.get(80, 120) // borrows the image; [B, G, R]
shot.close()                          // we opened it, so we close it
```

```scala mdoc
redCentre(2) // the red channel at the circle's centre
```

That assertion depends on *what you drew*, not on how OpenCV rounds — so it is stable everywhere. Everything below is a variation on this loop for cases where the answer is fuzzier.

## Draw your fixtures — don't ship them

Committing PNGs is the usual first instinct and the usual first regret: they bloat the repo, drift silently when a tool re-encodes them, and hide *what* about the image the test actually depends on. Draw a deterministic scene instead — it is explicit, versionable, and diffs as code. The `scene()` above is the pattern: a `blank` canvas plus a few `draw*` calls at fixed coordinates.

For repeatable *randomness* — many small shapes, textures, noise — seed a `scala.util.Random` with a fixed value. The scene then looks random but is byte-identical every run:

```scala mdoc:silent
def speckles(seed: Long): Image =
  val rng = scala.util.Random(seed)
  (0 until 40).foldLeft(Image.blank(120, 120)): (img, _) =>
    img.drawCircle(
      Point(rng.nextInt(120), rng.nextInt(120)),
      rng.nextInt(6) + 1,
      Scalar.White,
      Thickness.Filled
    )
```

```scala mdoc:silent
// Same seed ⇒ same pixels ⇒ a comparison of two runs is exactly zero.
val run1 = speckles(42)
val run2 = speckles(42)
val identical = Core.norm(run1.mat, run2.mat, Core.NORM_INF) // 0.0
run1.close(); run2.close()
```

```scala mdoc
identical // deterministic fixtures are bit-for-bit reproducible
```

:::note[Why draw instead of load]
A drawn fixture answers three questions a committed PNG cannot: *what* is in it (the code says so), *why* the test cares (the assertion targets a known shape), and *whether it changed* (a code diff, not a binary blob). It also keeps the repo small — no asset directory, no Git LFS.
:::

## Compare with a tolerance, never byte-for-byte

Exact pixel equality is correct only on the machine that produced the golden. Across SIMD paths or OpenCV versions it breaks on rounding that no human could see. Compare within a threshold instead. The two workhorses:

```scala mdoc:silent
/** Peak signal-to-noise ratio in dB — higher means closer; identical images report a huge sentinel. */
def psnr(a: Image, b: Image): Double = Core.PSNR(a.mat, b.mat)

/** The largest absolute per-channel difference — 0 means bit-identical. */
def maxAbsDiff(a: Image, b: Image): Double = Core.norm(a.mat, b.mat, Core.NORM_INF)
```

Pick the metric to match the operation you are testing:

| Metric | What it measures | Assert | Use when |
| --- | --- | --- | --- |
| `maxAbsDiff` (`NORM_INF`) | largest single-channel error | `== 0.0` for a lossless op | PNG roundtrips, flips, rotations, crops — anything that must be exact |
| `psnr` (dB) | overall similarity, log scale | `> 30.0` (or higher) | JPEG/WebP roundtrips, blurs, filters — anything lossy or platform-sensitive |
| bit-exact hash | "did *anything* change" | equality, **same platform only** | a fast local regression key; never in a multi-OS CI matrix |

A PNG roundtrip is lossless, so it is one of the few places you *can* demand exactness:

```scala mdoc:silent
val original = scene()
val png = Image.decode(original.copy.bytes(".png").toOption.get).toOption.get
val pngDiff = maxAbsDiff(original, png) // exactly 0.0 — PNG loses nothing
original.close(); png.close()
```

```scala mdoc
pngDiff // a lossless roundtrip is bit-identical
```

A JPEG roundtrip never is — but it stays well above a quality floor, which is what you assert:

```scala mdoc:silent
val src = scene()
val jpeg = Image.decode(src.copy.bytes(".jpg").toOption.get).toOption.get
val db = psnr(src, jpeg)
src.close(); jpeg.close()
```

```scala mdoc
db > 30.0 // assert this, not equality
```

:::warning[Exactness is fragile here]
Keep bit-exact hashes only as *same-platform* regression keys — they answer "did anything change at all" fast, but they will flap the moment CI runs a different OpenCV build or CPU. For anything a multi-OS/arch matrix touches, compare with a tolerance. [Performance](/performance) explains why exactness cannot be promised across builds.
:::

## Assert use-after-close throws — in a forked JVM

scalacv's ownership guard turns what would be a native segfault into an `IllegalStateException` you can *assert on*. Testing that a consumed handle is genuinely dead is how you prove the guard still works:

```scala mdoc:crash
val img = scene()
img.gray  // consumes img
img.width // throws IllegalStateException, not a crash
```

In a real suite, wrap that in `intercept[IllegalStateException] { ... }`:

```scala
test("a consumed image is dead") {
  val img = scene()
  img.gray                                    // spends img
  intercept[IllegalStateException](img.width) // reuse is caught, not a crash
}
```

:::danger[Run ownership tests in a forked JVM]
If a regression ever *does* segfault — the exact failure the guard prevents — a forked JVM reports it as a nonzero exit code instead of silently killing the whole test process (and every other suite sharing it). In Mill, test modules fork by default. Keep crash-prone ownership tests in their own suite so a failure names the offender instead of taking innocents down with it.
:::

When a use-after-move slips through into a *test failure* rather than an assertion, the exception fires at the reuse, which is rarely the interesting line. Run the suite with ownership tracking on and the error carries the consuming call's stack as its cause:

```sh
./mill core.test -Dscalacv.trackOwnership=true
```

See [Mat lifecycle](/mat-lifecycle) for what that flag records and why it is off by default.

## Property tests over shapes and types

The laws a regression is most likely to break — roundtrips and identities — are best expressed as *properties* over generated sizes and channel counts, not as a handful of hand-picked cases. This needs `munit-scalacheck` on the test classpath:

```scala
// in your test module (munit-scalacheck):
property("double flip is the identity") {
  forAll(genDims, genChannels) { (dims, ch) =>
    val src  = build(dims, ch)
    val back = src.copy.flip(Flip.Horizontal).flip(Flip.Horizontal)
    try samePixels(src, back) finally { src.close(); back.close() }
  }
}
```

Good laws to pin down, and how to check each:

| Law | Property | Metric |
| --- | --- | --- |
| PNG encode/decode roundtrip is lossless | `decode(encode(img)) == img` | `maxAbsDiff == 0` |
| A flip is its own inverse | `flip(H).flip(H) == img` | `maxAbsDiff == 0` |
| Quarter-turns compose to identity | `rotate(90°).rotate(270°) == img` | `maxAbsDiff == 0` |
| A colour roundtrip returns home | `BGR→RGB→BGR == img` | `maxAbsDiff == 0` |
| An op preserves its declared type | `canny(...)` ⇒ `CV_8UC1`, 1 channel | assert `channels == 1` |
| An unsupported type/channel combo is rejected cleanly | yields a typed `CvError`, never a crash | see [error model](/error-model) |

The point of a property test here is that it explores the *edges* — a 1×1 image, an odd width, a 4-channel input — that a fixed example never thinks to try, and every one of those is a place OpenCV's C++ has historically thrown.

## Guard against native leaks with an RSS assertion

A leaked `Mat` never fails an ordinary assertion — it just grows resident memory. To catch it you have to gate memory directly. And you must measure the right number: scalacv's Mats live *outside* JavaCPP's `Pointer` accounting, so `Pointer.totalBytes()` is blind to them. **Measure process RSS** (see [Performance](/performance#measuring-memory-do-it-right)):

```scala
// Sketch of an RSS-based leak assertion (run it as its own suite in its own JVM):
def rssBytes(): Long =
  val fields = String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/statm")))
  fields.trim.split("\\s+")(1).toLong * 4096   // resident pages * page size

def assertBounded(n: Int)(work: () => Unit): Unit =
  for _ <- 0 until 40 do work()                // warm up arenas/JIT
  System.gc(); Thread.sleep(150)
  val before = rssBytes()
  for _ <- 0 until n do work()
  System.gc(); org.bytedeco.javacpp.Pointer.deallocateReferences(); Thread.sleep(150)
  assert((rssBytes() - before) / (1024 * 1024) <= 48)  // bounded, not zero
```

Point it at the paths you actually worry about:

| Suspect | Why it leaks | What to wrap |
| --- | --- | --- |
| An error branch | the `catch` forgot to free the receiver | the whole fallible call |
| A per-frame loop | one retained `Mat` per iteration | the body of `Video.frames` / `Camera.foreach` |
| A repeatedly-constructed detector | a cascade or DNN never released | the construct-and-drop cycle |

The clean high-level `Image` chain is flat under this bound — it holds exactly one live Mat at a time. A per-iteration leak, by contrast, clears any sane ceiling within a few hundred iterations. Assert `<= 48 MB`, not `== 0`: arenas and the JIT wobble RSS a little, and a zero bound would flap.

:::warning[Isolate the leak suite]
Run it in its own JVM. If it shares a process with suites running in parallel, their allocations contaminate the RSS reading and the bound becomes meaningless.
:::

## Skip what the environment can't provide

Camera and GUI tests should *skip*, not *fail*, when the hardware or display is absent — so the suite stays green on a headless CI runner without pretending the hardware was tested. Gate them on an env var with munit's `assume` (a failed `assume` marks the test skipped, not failed):

```scala
test("captures a webcam frame") {
  assume(sys.env.contains("SCALACV_CAMERA")) // skips unless you opt in
  Camera.using(0) { cam =>
    // ... grab and assert on a frame ...
  }
}
```

Run the gated tests locally by opting in:

```sh
SCALACV_CAMERA=1 ./mill core.test
```

| Gate on | Guards | Set it when |
| --- | --- | --- |
| `SCALACV_CAMERA` | webcam capture, live video | a real camera is attached |
| a display check | GUI windows, `imshow`-style code | running with a desktop, not headless CI |
| a model-present check | DNN / cascade tests needing a download | the model file is on disk |

The principle generalises: a test that needs something the environment might not have should announce that need with `assume` and step aside quietly, rather than turning a missing webcam into a red build.

## Next

- The measurement details behind the leak assertion, and why RSS is the honest number: [Performance](/performance).
- The ownership guarantees these tests exercise — move semantics, idempotent release, the tracking flag: [Mat lifecycle](/mat-lifecycle).
- What the `CvError` cases mean and when a failure is a value versus a thrown bug: [The error model](/error-model).
