#import "../lib/book.typ": *

#chapter("Testing", subtitle: [Deterministic assertions over approximate results, on a runner with no display and no photographs.])

The test you want to write for a blur is "the output looks blurred", and there is no assertion for
that. So you write something else, and the something else is usually wrong in one of three ways
that ordinary test suites never have to think about.

The first is that images are big. The instinct is to commit a photograph and assert on what a
pipeline does to it, and now the repository carries a binary blob forever, in every clone, under a
licence nobody read. Worse, the blob hides what the test depends on: when the assertion breaks,
nothing in the diff says whether it was the code or the picture, because the picture is not in the
diff at all. This library ships no image asset --- the rule in `CONTRIBUTING.md` is "no image
assets", and `Lena.png` was deleted to honour it.

The second is that results are approximate. OpenCV dispatches on the widest SIMD instruction set
the CPU offers, so the same `gaussianBlur` runs different code on AVX-512 and on Apple silicon and
the last bit of a pixel does not always agree. A test that demands bit-equality passes on the
machine that recorded the expectation and fails, by one imperceptible unit, everywhere else --- and
that is worse than no test, because it teaches a team that red is normal.

The third is native memory. A leaking test suite fails no assertion; it goes green, then greener,
and then the runner is killed by the kernel's OOM reaper on an unrelated job twenty minutes later
with no stack trace. Every ordinary assertion is blind to it, because the leak is off-heap and the
heap is fine. If you want a leak to fail a build, you have to go and measure the leak.

The repository answers all three with generated fixtures, tolerance-based assertions, and native
RSS canaries. Unit suites belong to `core.test`, `vision.test`, or `graphs.test`; cross-module
integration, ownership/property tests, and the API/POM gates stay in `core.test`. The leak workloads
run separately in `leaks.test`, and no image assets are committed.

#sect("Run the module that owns the suite")

The complete local test command names every module. Run it from the repository root:

#example("A sequential full run, including native RSS canaries.")[
```bash
./mill --no-server -j 1 core.test + vision.test + graphs.test + zio.test + examples.test + leaks.test
```
]

The `+` separators are not decoration: `test` is a Mill command, so space-separated targets can
become test-name filters rather than separate module runs. `-j 1` keeps the full native/RSS run
sequential; `--no-server` avoids sharing a long-lived Mill worker with another build.

#example("Select a suite through its owning module, not its package name.")[
```bash
./mill --no-server -j 1 core.test.testOnly scalacv.FrameSourceSafetyTest
./mill --no-server -j 1 vision.test.testOnly scalacv.vision.RigidPoseInteropTest
./mill --no-server -j 1 graphs.test.testOnly scalacv.RendererContractTest
```
]

A suite in package `scalacv` may live in the vision or graphics module. Package names were preserved
when those suites moved; running only `core.test` no longer runs their unit tests.

#sect("Draw the fixture, do not ship it")

A drawn fixture answers three questions a committed PNG cannot: what is in it (the code says so),
why the test cares (the assertion targets a shape you placed), and whether it changed (a source
diff, not a binary blob). `Image.blank` plus the drawing primitives from Chapter 14 is the whole
toolkit.

#example("A deterministic scene: every shape is at a coordinate the assertion can name.")[
```scala
private def scene(): Image =
  Image
    .blank(160, 120, Scalar(20, 40, 60))
    .drawRect(Rect(20, 20, 60, 40), Scalar.Green, Thickness.Filled)
    .drawCircle(Point(120, 80), 20, Scalar.Red, Thickness.Filled)
```
]

`Image.blank(width, height, color = Scalar.Black, channels = 3)` gives you a canvas of exactly the
shape you asked for, and each `draw*` call consumes its receiver and hands back the next one, so
the chain holds one live `Mat` from start to finish. The circle is filled with `Scalar.Red`, which
is `(0, 0, 255)` in BGR order, so `img.mat.get(80, 120)` must come back with 255 in element 2. That
assertion depends on what you drew and not on how OpenCV rounds, which is why it is stable on every
runner in the matrix.

When a fixture wants to look busy --- texture, speckle, something for a lossy codec to lose ---
seed the randomness and it stays reproducible. `ToleranceMetricTest` builds its scene from
`scala.util.Random(11)` and twelve circles; `PropertyTest` takes the seed as a generated parameter,
so a shrunk counterexample is replayable. Random-looking is not the same as random, and only the
first belongs in a test.

The examples module keeps its fixtures in `examples/src/scalacv/Fixtures.scala`, at the lower
`Managed[Mat]` level because the examples work there: `Fixtures.shapes(size)` for hard geometric
edges, `Fixtures.qrCode(payload, scale)`, which runs `QRCodeEncoder` and scales past the detector's
resolution floor, and `Fixtures.arucoMarker(id, sizePx)`, which generates a tag and then pads it,
because `Aruco.generateMarker` emits no quiet zone and the detector hunts for a dark quad on a
light ground. Without the margin the marker is undetectable and the fixture tests nothing. That
padding is the case for drawn fixtures in miniature: a line of code with a comment saying why it
exists, where in a committed PNG it would have been an invisible property of a file that the next
regeneration silently cropped away.

#memory[
  A fixture is an allocation like any other, and the shape that goes wrong is a fixture used only by
  queries: `val img = scene(); assertEquals(img.width, 160)` spends nothing, so nothing is freed ---
  once per test, several hundred times across a suite. Either finish with a terminal, or
  `try … finally img.close()`, which is what `ToleranceMetricTest` does around every comparison.
]

#sect("Compare with a tolerance, never byte for byte")

Here is the assertion readers write first, and it is wrong:

#example("Bit-exact equality against a recorded hash. Green on your laptop, red on arm64.")[
```scala
test("blur is stable"):
  val out = scene().gray.blur(2)
  assertEquals(PixelHash.of(out), 0x3f7c1a09bb42e5d1L)   // recorded on one machine
  out.close()
```
]

That constant is a fact about a CPU, an OpenCV build and a vectoriser, and this project runs its
suite on `ubuntu-latest` x86-64 across three JDKs #emph[and] on `macos-26` arm64. Compare within a
threshold instead; two comparators cover nearly everything, and `ToleranceMetricTest` defines both
as one-liners:

#example("The two tolerance metrics. Both borrow their arguments rather than consuming them.")[
```scala
/** Peak signal-to-noise ratio in dB — higher is closer. */
private def psnr(a: Image, b: Image): Double = Core.PSNR(a.mat, b.mat)

/** Largest absolute per-element difference across all channels — 0 is pixel-identical. */
private def maxAbsDiff(a: Image, b: Image): Double = Core.norm(a.mat, b.mat, Core.NORM_INF)
```
]

Both take `a.mat`, the borrowing accessor from Chapter 4, so a comparison leaves both images alive
for the next assertion and for the `finally` that closes them.

#figure-table("Choosing a comparator. The third row is a local convenience, not a CI gate.")[
#tbl(
  columns: (0.9fr, 1.3fr, 0.8fr, 1.5fr),
  [Comparator], [Measures], [Assert], [Use it for],
  [`maxAbsDiff`], [largest single-element error], [`== 0.0`], [PNG round-trips, flips, quarter-turns, crops --- anything lossless],
  [`psnr`], [overall similarity, log scale], [`> 30.0`], [JPEG and WebP round-trips, blurs, filters --- anything lossy or SIMD-sensitive],
  [pixel hash], [did anything at all change], [equality], [a fast same-platform regression key; never across an OS matrix],
)
]

The thresholds are not folklore; `ToleranceMetricTest` pins each with a test that fails if the
metric stops behaving. A JPEG round-trip is asserted both to differ from its source and to clear
30 dB, so it fails if the codec becomes lossless #emph[and] if it becomes bad. And `gamma(1.0)` ---
an identity in arithmetic that nonetheless routes through an integer lookup table --- is asserted
to differ by at most 1, which is the case for tolerance in one line: an exact operation a bit-exact
check would reject.

When you do want exactness, fold the pixels the same way everywhere. The test module has one hash,
`PixelHash.of`, an FNV-1a over the raw bytes read #emph[one row at a time] --- an OpenCV `Mat` can
be a view into a larger buffer, whose rows are not adjacent in memory, so a whole-buffer read would
fold in padding that is not part of the image.

#sect("Golden images, and the trap in them")

A golden image earns its place when correctness is genuinely visual --- a chart with axis labels, a
scene graph composited with alpha, an annotated frame where you care that the text did not move
three pixels. If you keep them: small, because they live in your history forever; PNG, because
lossless storage adds nothing to the comparison; and generated by a committed script, because "run
this and review the diff" is a reviewable action where "someone dragged a file in" is not.

The trap is that a golden image records bytes and you care about a picture. An OpenCV point
release, a libpng bump, a changed JPEG quality default or a new SIMD path on the runner all move
bytes without moving anything a human could see. The suite goes red for something that is not a
defect, someone regenerates to make it green, and the regeneration absorbs a real regression
sitting in the same diff. Two things prevent that: compare goldens with `psnr` rather than
equality, and pin the OpenCV version --- which this build does in one place, `Deps.opencv` in
`build.mill`, at `4.13.0-1.5.13`.

#sidebar("The golden this repository does keep")[
  There is no golden image here, but there are three golden #emph[files]:
  `core/api.golden`, `vision/api.golden` and `graphs/api.golden`, together 3,522 lines. Each is
  the compiled public surface of one published module, rendered by `PublicApiTest` as sorted text
  read back out of the class files --- JVM-public, not source-public, because
  `private[scalacv]` compiles to `ACC_PUBLIC` and really is callable from Java.

  It cannot suffer the trap above, because every list is sorted and every compiler-invented name is
  excluded: `$anonfun` lambda bodies, `$lzy` backing fields, the `OFFSET$_m_N` offsets whose
  numbering shifts when an unrelated lazy val appears, the per-`enum` `$$anon$N` classes, and the
  static forwarders Scala emits so Java can call an `object`. What is left changes only when the API
  changes. Regenerate with `SCALACV_API_REGENERATE=1` --- an environment variable, because Mill
  1.1.7 offers no way to add a `-D` to a test fork from the command line --- and `git diff` is the
  review.
]

#sect("Properties, where the law is the point")

Example-based tests check the cases you thought of; the regressions that happen live at the edges
you did not, an odd width or a fourth channel. `PropertyTest` extends `munit.ScalaCheckSuite`
(`org.scalameta::munit-scalacheck:1.3.1`, added to `core.test` on top of the `munit:1.3.6` every
test module already carries) and states laws instead.

#example("An identity law over generated sizes and channel counts.")[
```scala
property("rotate 90° clockwise then 90° counter-clockwise is the identity"):
  forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
    val (w, h) = dims
    val src = build(w, h, ch, seed)
    val back = src.copy.rotate(Rotation.Clockwise).rotate(Rotation.CounterClockwise)
    try samePixels(src, back)
    finally
      src.close()
      back.close()
```
]

Three details are worth copying. `src.copy` is there because `rotate` consumes its receiver and the
law still needs `src` to compare against; without it the comparison throws `IllegalStateException`
on a spent handle instead of failing an assertion. The `finally` matters more here than in an
example test, because ScalaCheck runs the body once per generated case, so a missing `close()` is
not one leak but twenty-five. And `samePixels` compares width, height, channel count #emph[and]
`PixelHash.of` --- a law checking only dimensions would pass for an operation that returned a black
rectangle. The generators stay deliberately small (`Gen.choose(4, 40)` per dimension,
`Gen.oneOf(1, 3, 4)` for channels, `withMinSuccessfulTests(25)` rather than the default 100),
because every case crosses JNI and allocates native memory.

#figure-table("The laws PropertyTest pins, and what each one would catch.")[
#tbl(
  columns: (1.5fr, 1.5fr),
  [Property], [Regression it catches],
  [`imencode`/`imdecode` PNG round-trip is lossless], [an encoder default that silently quantises],
  [double horizontal flip is the identity], [an off-by-one on odd widths],
  [rotate clockwise then counter-clockwise is the identity], [a transposed-but-not-flipped rotation],
  [`BgrToRgb` then `RgbToBgr` is the identity], [a channel swap applied twice, or not at all],
  [resize up then down preserves dimensions], [rounding that loses a row at odd sizes],
  [`canny` always yields one channel], [an output type that changes with the input],
)
]

#sect("Testing lifetimes")

This section has no equivalent in a normal Scala project, and it splits in two: proving a
particular handle was released, and proving memory does not accumulate at scale. The first is a
deterministic assertion; the second cannot be.

The deterministic half rides on the guard from Chapter 5. Because `Managed` turns a use-after-move
into an `IllegalStateException` on the Scala side --- before anything reaches JNI, where the same
mistake is a SIGSEGV with no Java stack trace --- the ownership contract is directly assertable.
`ImageTest` does that four times:

#example("The move semantics, asserted rather than documented.")[
```scala
test("a transform spends the image it was called on"):
  val img = sample()
  val g = img.gray
  intercept[IllegalStateException](img.width)   // the source is spent
  assertEquals(g.channels, 1)
  g.close()

test("a query borrows: the image is still usable afterwards"):
  val img = sample()
  assertEquals(img.qrCodes, Seq.empty)          // there is no QR code in the scene
  assertEquals(img.width, Width)                // still alive after the query
  img.close()
```
]

The subtler case in that suite is `blur(0)`. A radius of zero is the identity, and the tempting
implementation returns the receiver untouched --- silently breaking the contract for that one
argument. The test asserts `img` is spent #emph[even when `r == 0`], and that the result still
reports the original dimensions: the handle moved, the pixels did not.

When such a test fails, the exception fires at the reuse, which is rarely the interesting line.
Start the JVM with `-Dscalacv.trackOwnership=true` and `Managed` attaches, as the cause, a
`Throwable` captured where the handle was spent. It is off by default because it allocates that
`Throwable` on every consumption. Mill cannot push a `-D` into a test fork from the command line,
so turn it on by adding it to that module's `forkArgs`.

#sidebar("A test with no assertion in it")[
  `DoubleFreeTest` allocates and releases 300 `CascadeClassifier`s, 300 `QRCodeDetector`s, 300
  `ArucoDetector`s and 300 `Net`s, calling `System.gc()` every fiftieth iteration, and then asserts
  nothing at all.

  It is a crash canary. The 185 `org.opencv.*` types with no public `release()` each carry an
  unconditional `protected void finalize() { delete(this.nativeObj); }`. Free one through
  `Releasable.nativeHandle`, then drop it, and `delete` runs twice on the same address: once from
  you, once from the finalizer thread. `Managed`'s compare-and-set is no defence --- it makes
  #emph[your] release idempotent and knows nothing about another thread's finalizer. The result is
  not a failed assertion but a corrupted heap that takes the JVM down somewhere else entirely, which
  is how the bug was found in the first place, in an unrelated suite. Reaching the end of the loop
  alive #emph[is] the pass; if the guard regresses, the worker dies without writing a result and the
  run goes red anyway.

  One test there does assert, and it makes the rest diagnosable: after `Managed(c).release()`,
  `c.getNativeObjAddr` must be `0L`. Zeroing the field is exactly what makes the inherited finalizer
  delete a null pointer instead of a live one.
]

#subsect("The leaks module")

A leaked `Mat` fails no assertion, so you have to gate memory directly --- and measure the right
number, which here is not the obvious one. The memory audit put a deliberate 1.4 GB
`org.opencv.core.Mat` leak in front of JavaCPP's `Pointer.totalBytes()` and it moved by zero bytes,
because those buffers come from OpenCV's own JNI via `cv::fastMalloc` rather than through JavaCPP's
tracked allocators. The `-Dorg.bytedeco.javacpp.maxBytes` budget derived from that counter is
equally blind. The only signal that sees these buffers is process resident set size, which is the
same conclusion Chapter 35 reaches from the other direction.

`leaks/test/src/scalacv/LeakAssertions.scala` reads it from `/proc/self/statm` --- field 2,
resident pages, times a page size that defaults to 4096 and is overridable through
`SCALACV_PAGE_BYTES` --- and falls back to `Pointer.physicalBytes()`, itself RSS-based, where that
file does not exist.

#example("The whole leak gate, in one function.")[
```scala
def assertBounded(name: String, n: Int = 300, warmup: Int = 40, toleranceMB: Long = 48)(
    workload: () => Unit
): Unit =
  var i = 0
  while i < warmup do
    workload(); i += 1
  settle()
  val before = rssBytes()
  i = 0
  while i < n do
    workload(); i += 1
  settle()
  val grewMB = (rssBytes() - before) / (1024L * 1024L)
  assert(grewMB <= toleranceMB, s"$name: RSS grew ${grewMB}MB over $n iterations …")
```
]

Every part of that is load-bearing. The `warmup` runs amortise one-time costs --- native library
extraction, JIT compilation, the allocator's arenas reaching their working size --- into the
baseline, so the measurement sees growth and not start-up. `settle()` is `System.gc()`, a 150 ms
sleep, `Pointer.deallocateReferences()`, another `System.gc()` and another sleep, run identically
before both readings. And the bound is a ceiling rather than zero, because RSS never returns exactly
to baseline: arenas do not shrink and the code cache only grows. A per-iteration leak of even a
modest `Mat` clears 48 MB long before 300 iterations; a leak-free workload stays flat.

#figure-table("What the four leak tests drive, and what a regression would cost per call.")[
#tbl(
  columns: (1.4fr, 1.6fr),
  [Workload], [What a regression leaks],
  [`blank → gray → blur(2) → canny(80, 160) → bytes(".png")` at 640×480], [one intermediate per stage; the pipeline should hold exactly one live `Mat`],
  [`blurBackground` with a deliberately size-mismatched mask, 200 iterations], [a 320×240×3 receiver, about 230 KB per failed call --- roughly 46 MB over the run],
  [`undistort` with a fresh `Intrinsics` per call, 300 iterations], [the camera matrix and distortion coefficients, allocated per call],
  [`ZIO.scoped(acquireRelease(Mat(...)))`, 200 iterations], [a 640×480×3 native buffer per scope if the finalizer stops releasing it],
)
]

The second row is the shape leaks actually take: the happy path was never the problem, the `catch`
was. An error branch that forgets to release is invisible to every functional test in the suite ---
the exception is thrown, the assertion about the exception passes, and 230 KB goes missing each
time.

The suite runs with `./mill leaks.test`. RSS is process-global, so its single suite owns a JVM
and its workloads run sequentially. The functional MUnit suites also receive one fork per suite,
with the build rejecting empty discovery. Separate forks prevent a native crash from erasing
another suite's results; they do not prevent concurrent processes from contending for host memory,
which is why the full local command uses `-j 1`.

CI runs a coarser version of the same idea in its `leak-check` job. After `leaks.test` it compiles a
plain-Java driver against `examples.runClasspath`, runs
`Fixtures.shapes(240) → gray → blur(1) → canny(60, 160) → bytes(".png")` 2000 times in one
long-lived JVM under `-Xmx256m`, reads `VmRSS` from `/proc/self/status` after a 200-iteration
warm-up and again at the end, and fails if the delta clears 204,800 KB. The heap cap is the point
of the pairing: an on-heap leak OOMs and fails there, while a native `Mat` leak escapes the heap
and only the RSS delta sees it. At about 170 KB per leaked 240×240×3 frame a regression grows
roughly 300 MB across the measured run --- far past a 200 MB ceiling that steady-state jitter never
approaches.

#memory[
  If you copy `assertBounded` into your own project, copy the isolation with it. Two mistakes make
  the gate useless without making it fail: running it alongside other suites, and running it with
  parallelism on inside its own module. Both add allocations the workload did not make, and both
  push you toward "raise the tolerance until it stops flapping" --- a bound no leak can reach.
]

#sect("Isolation: one load, one JVM, one scope")

`OpenCv.load()` is idempotent but not free: the first call extracts classifier libraries into the
JavaCPP cache and loads them into the process. Native suites put it in `beforeAll`, once per suite
in that suite's forked JVM. Pure geometry and renderer contracts do not need to load natives.

Test modules fork by default in Mill, and `ScalacvTests` sets `forkArgs` to
`Deps.headlessJvmArgs(jvmMajor())`. That adds `-Djava.awt.headless=true`, which is an assertion
rather than a preference: a GUI-toolkit regression then fails loudly in CI instead of silently
opening a window on whichever developer machine has a display. It also adds
`--enable-native-access=ALL-UNNAMED` and `--sun-misc-unsafe-memory-access=allow`, but only on JDK
24 and later, because both flags are younger than the JDK 17 floor and passing either to a 17 or 21
JVM is not a warning but `Unrecognized option` and a process that does not start.

Within a test, scope resources the way you would in production. `Managed.scope` releases in reverse
acquisition order on both the normal and the throwing path, and `ManagedTest` asserts both,
including the case that motivated it: a later allocation throws, and everything acquired before it
must still lose its buffer. For files, `Files.createTempFile` with a `finally` that deletes ---
there is no fixture directory, because no fixture file ships.

#sect("What this project's CI actually runs")

There is no `apt-get` line anywhere in `.github/workflows/` and no GUI package. `OpenCv.load()`
resolves what it needs out of the classifier jars, so a stock GitHub runner is a sufficient
environment --- the claim the smoke job exists to keep honest.

#figure-table("The six CI jobs and the failure each one exists to catch.")[
#tbl(
  columns: (1fr, 1.9fr),
  [Job], [What it gates],
  [`build` (JDK 17, 21, 25 on `ubuntu-latest`)], [compile, `core.test + vision.test + graphs.test + zio.test + examples.test`, `docs.mdocCheck`, and the headless natives smoke],
  [`leak-check`], [`leaks.test`, then a 2000-iteration external stress driver gated on RSS growth],
  [`style`], [`scalafmt` `checkFormatAll` and `scalafix --check` on every published module plus `examples`],
  [`natives` (`macos-26`)], [the same smoke and `core.test + vision.test + graphs.test + zio.test + examples.test` on Apple-silicon arm64],
  [`publish-shape`], [that exactly `core graphs vision zio` are publishable, and `PublishedPomTest`],
  [`consumer-smoke`], [a from-nothing resolve of the four published coordinates on a clean cache],
)
]

Two of those catch failures that are invisible from inside the build.

The matrix carries a step titled "Assert the build really ran on JDK 17" (and 21, and 25). Mill
1.1.7 ignores `JAVA_HOME` and `MILL_JVM_VERSION` when it runs modules, so `actions/setup-java`
cannot steer it and is deliberately absent from the matrix --- `leak-check` is the one job that does
use it, and only to put a plain `javac` on `PATH` for the stress driver, never to choose Mill's JDK.
The per-rung JDK comes from `MILL_JVM_ID`, which `build.mill` reads. If that override stopped being honoured, all three rungs would quietly run the
same JDK and all three would still go green. So the job runs
`./mill examples.runMain scalacv.jvmReport` and greps for `java.version=17`. Without that step the
matrix is decorative.

The `consumer-smoke` job publishes all four modules to the local ivy repository, points
`COURSIER_CACHE` at a fresh temporary directory, and `cs fetch`es the four published coordinates
plus the two documented natives ones. It then compiles and runs
`ci/consumer-smoke/ConsumerSmoke.java` --- Java on purpose, since it needs no Scala compiler and
calling `scalacv.OpenCv.load()` through the generated static forwarder proves the published
bytecode is callable from plain JVM code. It loads the natives, allocates an 8×8 `Mat` across JNI
(`Core.VERSION` is a constant and would prove nothing), and references `scalacv.QrCode.class` and
`scalacv.Color.class` so a failure to resolve the vision or graphs artifact surfaces here rather
than in a user's project. This is the gate that catches "green build, zero natives shipped".

#warning[
  The smoke, leak and consumer jobs all gate on a success line --- `OK`, `LEAK-OK`, `CONSUMER-OK`
  --- rather than on the exit code. The reason will bite you too: OpenCV and OpenBLAS teardown at
  JVM shutdown occasionally exits non-zero after a completely successful run. A genuine load failure
  prints no success line, so the gate still fails loudly.
]

#sect("Snippets that cannot rot")

Prose can lie about an API; a compiled snippet cannot. The `docs` Mill module runs
#link("https://scalameta.org/mdoc/")[mdoc] as a plain subprocess over `docs/mdoc/`, compiling every
fenced ```` ```scala mdoc ```` block against the #emph[runtime] classpath of `core`, `vision`,
`graphs`, `zio` and `examples` --- natives included, because a snippet that calls OpenCV must link
the JNI and not merely the Java API. `./mill docs.mdoc` splices each block's evaluated output back
into the markdown; `./mill docs.mdocCheck` passes `--check` instead, so a snippet that no longer
compiles fails the build rather than being quietly rewritten. That check runs on every pull request
in the `build` job, which is what turns the documentation from a courtesy into a gate.

The modifiers earn their keep. Of the 993 `scala mdoc` blocks across `docs/mdoc/`, 311 carry no
modifier and print their evaluated result into the page. The remaining 682 are shaped: 403
`mdoc:silent` (compile and run, show nothing), 195 `mdoc:compile-only` (type-check without running
--- the right choice for anything that would open a camera), 57 `mdoc:invisible` (setup the reader
does not need to see) and 27 `mdoc:crash`, which assert that a snippet #emph[does] throw. That last one is how the
use-after-move example on the testing page stays true: if `Managed` stopped throwing on a spent
handle, the documentation build would fail because the promised crash did not happen.

A book applies that discipline by hand: these listings are checked against the source, not compiled
by a build. Where you want certainty, the equivalent page under `docs/mdoc/` is the copy a compiler
has seen.

#sect("Skip what the runner cannot provide")

A test that needs hardware the environment may not have should step aside, not go red. munit's
`assume` marks a test skipped rather than failed, and the suite uses it in three shapes:

#example("Opt-in hardware, opt-in models, and a platform that ships no data.")[
```scala
// A webcam, if one is attached and you asked for it: SCALACV_CAMERA=1 ./mill core.test
assume(sys.env.contains("SCALACV_CAMERA"), "set SCALACV_CAMERA=1 to run camera tests")

// A model file the suite will not download: SCALACV_SFACE_MODEL=/path/to/face_recognition_sface.onnx
assume(model.isDefined, "set SCALACV_SFACE_MODEL to the SFace ONNX to run this test")

// The windows-x86_64 classifier jar ships an empty share/, so no Haar cascade can be resolved.
assume(cascadesShipped, "this platform ships no Haar cascades")
```
]

The camera gate protects developers as much as CI: an unguarded `Video.open(0)` on a laptop opens
whatever webcam is attached, which is a surprising thing for the headless CI test command to do. Note the
second `assume` inside that test, on the `Left` branch of `Video.open(0)` --- opting in does not
guarantee a device is there, so a missing camera skips rather than fails even then.

`CascadesTest` shows the shape worth imitating: alongside seven tests gated on `cascadesShipped` it
has one gated on `assume(!cascadesShipped)`, asserting that `Cascades.resolve` fails cleanly where
the data is absent. Every branch is covered somewhere; nothing is merely skipped everywhere.

#sect("Where this leaves you")

One question sits underneath all of it, and `CONTRIBUTING.md` states it as a rule: an assertion
that would still pass if the implementation returned a constant, or did nothing, is a false
assurance and worse than no test. Ask it of the fixture (would this scene tell a blur from a copy?),
of the tolerance (would 30 dB pass an image of noise?), of the leak bound (would 48 MB catch a
230 KB per-call leak?) and of the skip (is this branch exercised anywhere at all?).

What you have then is a suite that is green because the code is right, not because the runner
resembles the machine that recorded the answer. Chapter 41, #emph[Deploying to Production], takes
that build the rest of the way: the JVM flags a container needs, where `~/.javacpp` goes when the
home directory is read-only, and what to size a pod's memory limit against when the interesting
allocations never appear on the heap.
