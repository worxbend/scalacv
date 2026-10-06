---
title: Observability
description: What a scalacv service emits, what it does not, and the four seams — readiness, per-operation latency, frame accounting and native memory — where you add your own instrumentation.
---

# Observability

**Observability** is the practice of being able to answer, from outside a running program, the question *"is it healthy, and if not, where did it go wrong?"* — usually with three kinds of signal: **logs** (lines of text describing events), **metrics** (numbers sampled over time, like a request rate or a memory gauge), and **traces** (one request's path through a system).

scalacv emits **none of the three**. That is a deliberate design choice, and this page is about what to do with it: what the library really does emit (a little, and to a place you may not expect), the four places worth wiring your own instruments into, and two recipes — a native-memory gauge that measures the right number, and a way to catch OpenCV's own chatter — that are easy to get subtly wrong.

This page is the instrumentation half of [Deploying to production](/deploying-to-production). The mechanisms it measures live on other pages: [Mat lifecycle](/mat-lifecycle) for why native memory needs watching at all, [The error model](/error-model) for what the failures mean, [Performance](/performance) for how to make the numbers smaller.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## What scalacv emits

### No logging, no metrics — and no dependency to fight

Search every Scala source in the repository for `logger`, `slf4j`, `log4j`, `logback`, `micrometer`, `prometheus` or `opentelemetry` and you get zero hits — there is no logging facade and no instrumentation surface anywhere in the library. The `scalacv`, `scalacv-vision` and `scalacv-graphs` modules each declare exactly **one** dependency, the OpenCV Java API; `scalacv-zio` adds ZIO and nothing else.

That has two consequences, and they pull in opposite directions:

- **Nothing to configure, and nothing to conflict.** There is no logging facade to bind, no `NOP` warning at startup, no clash between the SLF4J (Simple Logging Facade for Java) version your service picked and the one a library forced on you. Adding scalacv to an application does not change a single line of its log output.
- **Nothing is instrumented for you.** No timer wraps a decode. No counter tracks failed reads. No gauge watches native memory. If you want a number, you add it — and the rest of this page is about which numbers are worth adding and where they go.

### What it *does* emit: OpenCV's own stderr

OpenCV, the C++ library underneath, writes diagnostics to **standard error** on its own initiative. A read of a path that does not exist is the everyday example: `imread` prints a warning and returns an empty image, and scalacv turns that empty image into a `Left(CvError.DecodeFailed(...))` for you — but the warning has already been printed, and nothing in Scala asked for it. See [Reading and writing images](/image-io) for that specific case.

Two things make this awkward, and both are covered below: those lines are written by native code straight to file descriptor 2 rather than through the JVM's `System.err` object (so the usual Java tricks do not catch them), and several of them are printed on paths that scalacv **expects** to fail and handles correctly (so alerting on them cries wolf).

## The four seams worth instrumenting

| Seam | The question it answers | Signal type | Built from |
|---|---|---|---|
| **Readiness** | can this instance serve at all? | boolean / probe | `OpenCv.load()`, `OpenCv.isLoaded` |
| **Per-operation latency and errors** | which operation is slow, and which is failing? | histogram + counter | your timer around `Cv.attempt("name")` |
| **Frame accounting** | are we processing every frame we were given? | two counters | `Camera.recordTo`'s `Long`, `CaptureInfo.frameCount` |
| **Native memory** | are we leaking pixel buffers? | gauge | process RSS |

### 1. Readiness

`OpenCv.load()` extracts and links roughly 196 MB of native libraries the first time it runs. It is idempotent (calling it again is a no-op) and safe to call from several threads, so a health-check endpoint may call it directly. `OpenCv.isLoaded` reports whether it has ever completed successfully.

The failure to plan for is `CvError.NativesMissing`, which `load()` **throws** rather than returning — its message carries a copy-pasteable dependency line naming the platform you are actually on:

```scala mdoc:silent
/** What a readiness probe should answer: `Right(())` for HTTP 200, `Left(reason)` for HTTP 503. */
def readiness(): Either[String, Unit] =
  try
    OpenCv.load() // idempotent: cheap on every call after the first
    if OpenCv.isLoaded then Right(())
    else Left("OpenCv.load returned but isLoaded is false")
  catch case e: CvError.NativesMissing => Left(e.getMessage)
```

```scala mdoc
readiness().isRight
```

Report **not ready** rather than **not alive** for this: a missing native library is a packaging problem that no restart fixes, so a liveness probe that kills and reschedules the container achieves nothing but a crash loop. Warm up any DNN model in the same place, before readiness flips true — see [Deploying to production](/deploying-to-production).

### 2. Per-operation latency and error rate

Every fallible call in scalacv returns `Either[CvError, A]`, and the native calls underneath are named by [`Cv.attempt`](/error-model#the-escape-hatch-cv-attempt), whose first argument is a plain `operation` string: `Cv.attempt("Imgproc.GaussianBlur") { … }`.

That `operation` string ends up inside `CvError.NativeCall(operation, cause)` and therefore inside the message a reader sees in a log. **Use the same string as your metric name.** When a timer called `Imgproc.GaussianBlur` spikes and an error line says `OpenCV failed during Imgproc.GaussianBlur`, an on-call engineer joins them without a translation table.

The error *labels* come from `CvError`, which has exactly seven cases ([the error model](/error-model#the-six-cases) explains each one). Seven is a small, fixed set, which is what you want for a metric label: a metrics backend creates one time series per distinct label value, so labelling with a raw exception message — which contains file paths and pixel dimensions — would create an unbounded number of series. Map each case to a stable snake-case label and stop there:

```scala mdoc:silent
/** The metric label for a failure: one per `CvError` case, and nothing else. Never label with
  * `e.getMessage` — it contains paths and sizes, and every distinct value costs a time series. */
def errorLabel(e: CvError): String = e match
  case _: CvError.NativesMissing    => "natives_missing"
  case _: CvError.DecodeFailed      => "decode_failed"
  case _: CvError.LoadFailed        => "load_failed"
  case _: CvError.EndOfStream       => "end_of_stream"
  case _: CvError.EncodeFailed      => "encode_failed"
  case _: CvError.CalibrationFailed => "calibration_failed"
  case _: CvError.NativeCall        => "native_call"
```

Now the timer. The shape below wraps anything that returns an `Either[CvError, A]` — a scalacv call such as `Image.read`, or your own `Cv.attempt` block — records how long it took, and labels the outcome:

```scala mdoc:silent
/** One timed call, in the shape a metrics backend wants: a name, a duration, an outcome label. */
final case class Observation(operation: String, nanos: Long, outcome: String)

/** Stand-in for your metrics client. In a real service `record` is a Micrometer `Timer.record`,
  * a Prometheus histogram `observe`, or a Kamon `Timer`; here it appends to a list so this page
  * can print what came out. */
var ledger: Vector[Observation] = Vector.empty
def record(o: Observation): Unit = ledger = ledger :+ o

/** Times `call` and records it on **every** path — success, `Left`, and thrown. */
def observing[A](operation: String)(call: => Either[CvError, A]): Either[CvError, A] =
  val start = System.nanoTime()
  var outcome = "threw"
  try
    val result = call
    outcome = result.fold(e => errorLabel(e), _ => "ok")
    result
  finally record(Observation(operation, System.nanoTime() - start, outcome))
```

The `"threw"` label is not decoration. scalacv returns an `Either` for failures that depend on the *data* — a file that is not there, bytes that do not decode — and **throws** for programmer errors, such as an `IllegalArgumentException` from a violated precondition or an `IllegalStateException` from using an image after it was consumed. Those never appear in the `Either`, so a helper that only records `fold` outcomes would show a suspiciously healthy error rate on a code path that is crashing every request. The `try … finally` is what makes the timer honest about them.

```scala mdoc
val blurred = observing("Image.blur") {
  Cv.attempt("Image.blur")(Image.blank(320, 240, Scalar.White).blur(2)).map(_.close())
}

val decoded = observing("Image.read")(Image.read("no-such-file.png")).left.map(e => errorLabel(e))

ledger.map(o => s"${o.operation} -> ${o.outcome}")
```

:::tip[Time the whole request too, not only the operations]
A per-operation histogram tells you which call is slow. It does not tell you that your handler decoded the same image three times. Keep one timer around the request boundary as the number you alert on, and use the per-operation timers to explain it.
:::

### 3. Frame accounting

For video there is one more question: **did we see every frame?** `Camera.recordTo` answers half of it directly — it returns `Either[CvError, Long]`, and the `Long` is the number of frames actually written:

```scala mdoc:compile-only
/** Transcodes `source` to `out`. Returns (frames written, frames the container claimed to hold). */
def transcode(source: String, out: String): Either[CvError, (Long, Long)] =
  // `usingFile` wraps whatever the block returns in its own Either, so the block's Either ends up
  // nested one level deep; `flatMap(identity)` flattens the two into one.
  val nested = Camera.usingFile(source) { cam =>
    val claimed = cam.info.frameCount // advisory — see the warning below
    cam.recordTo(out, codec = Codec.Mjpg)(_.blur(2)).map(written => (written, claimed))
  }
  nested.flatMap(identity)
```

:::warning[`frameCount` is advisory, and is never a loop bound]
Every field of `CaptureInfo` is a `CAP_PROP_*` query answered by the backend, and the backend is allowed to guess. A live camera usually reports `frameCount == 0`, because the question is meaningless for a stream that has not ended. Some containers report a count that is off by a frame or two from what actually decodes. `fps` can be `0` for a camera that has not delivered a frame yet.

So `written / claimed` is a **hint**, useful for a file transcode where a ratio of 0.6 means something went wrong, and meaningless for a live source where `claimed` is 0. Guard the division, and never write `for i <- 0L until info.frameCount`. See [Video & the camera](/video).
:::

For a live source, count frames yourself inside the `Camera.foreach` body and export the count as a counter; the derivative of that counter is your real frames-per-second, which is the number to compare against the fps the camera claims. A consumer that cannot keep up does not drop frames here — it falls further and further behind, and nothing in the API reports that. [Streaming and backpressure](/streaming-and-backpressure) covers how to measure and bound the lag.

### 4. Native memory

Pixel buffers are allocated by C++, outside the Java heap, so `-Xmx`, heap-used gauges and garbage-collection metrics are all blind to them. A leaked image raises no heap alarm at all: it grows the process until the kernel kills it. The gauge that sees it is process **RSS** — *resident set size*, the amount of physical memory the operating system currently has mapped for the process — and getting that gauge right is fiddly enough to deserve its own section.

## Recipe: an RSS gauge that is not a lie

There is an obvious-looking counter that does **not** work, and it is important to know why before trusting a dashboard built on it.

JavaCPP — the library that loads the native code — tracks the memory it allocates itself and reports it as `Pointer.totalBytes()`. That is also the number `-Dorg.bytedeco.javacpp.maxBytes` is checked against. But scalacv wraps the official `org.opencv.core.Mat` Java API, whose pixel buffers are allocated by OpenCV's own `cv::fastMalloc` inside the C++ library, never through a JavaCPP allocator. JavaCPP does not know they exist. This repository's memory audit measured it: a deliberate ~1.4 GB `Mat` leak moved `Pointer.totalBytes()` by **0 bytes**.

The number that *does* see them is process RSS. JavaCPP exposes one — `Pointer.physicalBytes()`, which is RSS-based and works on every platform — but on Linux there is a cheaper and more direct source: `/proc/self/statm`, a one-line pseudo-file whose **second** field is the resident page count. Multiply by the page size and you have the same number for the cost of reading a few dozen bytes. This is the implementation scalacv's own leak harness uses:

```scala mdoc:silent
import java.nio.file.{Files, Path}
import org.bytedeco.javacpp.Pointer

/** Process resident set size — the only counter that sees an `org.opencv.core.Mat`. */
object NativeMemory:

  /** Linux page size: 4 KiB on x86_64 and arm64. The environment variable is the escape hatch for a
    * kernel configured with larger base pages, where 4096 would silently under-report. */
  private val pageBytes: Long =
    sys.env.get("SCALACV_PAGE_BYTES").flatMap(_.toLongOption).getOrElse(4096L)

  private val statm: Path = Path.of("/proc/self/statm")

  def rssBytes(): Long =
    if Files.isReadable(statm) then
      // field 2 of /proc/self/statm is the resident page count
      String(Files.readAllBytes(statm)).trim.split("\\s+")(1).toLong * pageBytes
    else Pointer.physicalBytes() // macOS, Windows, or a container without /proc

  def rssMegabytes(): Long = rssBytes() / (1024L * 1024L)
```

Here is the difference between the two counters, measured live while this page was built. Allocating a 4000 × 3000 three-channel image reserves 4000 × 3000 × 3 bytes ≈ 34 MiB of native pixels, and fills every one of them, so the memory is genuinely resident:

```scala mdoc
val trackedBefore = Pointer.totalBytes()
val residentBefore = NativeMemory.rssBytes()

val big = Image.blank(4000, 3000, Scalar.White)

val trackedGrewMb = (Pointer.totalBytes() - trackedBefore) / (1024 * 1024)
val residentGrewMb = (NativeMemory.rssBytes() - residentBefore) / (1024 * 1024)
```

```scala mdoc:silent
big.close()
```

`trackedGrewMb` does not move; `residentGrewMb` does. Build the gauge on the second number:

```scala mdoc
NativeMemory.rssMegabytes()
```

That is this documentation build's own footprint, on the machine that produced this page. Export it as a **gauge** (a value that goes up and down, as opposed to a counter that only rises) on a slow schedule — once every 10 to 60 seconds is plenty; it is a memory trend, not a latency spike.

How to read it:

- **Flat under steady load** is healthy release discipline. RSS never returns exactly to its starting point — allocator arenas and the JIT compiler's code cache keep what they have taken — so expect a plateau, not a sawtooth returning to zero.
- **A steady climb under steady load** is a leak: an `Image`, a `Managed[Mat]` or a detector that is never closed. [Mat lifecycle](/mat-lifecycle#verify-you-arent-leaking) explains where to look; [Testing](/testing#guard-against-native-leaks-with-an-rss-assertion) turns the same measurement into a test that fails the build.
- **A step change** usually means a bigger input rather than a leak — someone uploaded a 40-megapixel photograph.

:::warning[RSS is a whole-process number]
`/proc/self/statm` reports the process, not your pipeline. Anything else in the same JVM — a cache, another library's off-heap buffers, a second test suite running in parallel — moves it too. That is exactly why scalacv's leak suite runs in a JVM of its own. On a dashboard this is not a problem (a service's total footprint is the thing you care about); in an assertion it is, so isolate it.
:::

:::tip[Pair the gauge with a ceiling]
A gauge tells you afterwards. `-Dorg.bytedeco.javacpp.maxPhysicalBytes` tells you at the moment it happens, by throwing when the process crosses the limit — which in a container you want set comfortably *below* the container's own memory limit, so you get a JVM error with a stack trace instead of a silent kernel OOM kill. Note that the throw is a `java.lang.OutOfMemoryError`, not a `CvError`, so no `Either` in this library catches it; see [Degradation and error budgets](/degradation-and-error-budgets) and [Performance](/performance#measuring-memory-do-it-right).
:::

## Recipe: capturing OpenCV's stderr, and what it cannot capture

Ideally OpenCV's diagnostics would arrive in your log pipeline with everything else, tagged and timestamped. You can get part of the way there by swapping the JVM's `System.err` for a stream you own:

```scala mdoc:silent
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Runs `body` with `System.err` diverted into `sink`, one line at a time.
  *
  * `System.setErr` is **process-global**: it redirects standard error for every thread in the JVM,
  * not only this one. Use it around startup or a batch job, never around one request in a
  * concurrent service, where it would swallow unrelated threads' output.
  */
def capturingStderr[A](sink: String => Unit)(body: => A): A =
  val buffer = ByteArrayOutputStream()
  val previous = System.err
  System.setErr(PrintStream(buffer, true, UTF_8))
  try body
  finally
    System.setErr(previous) // restored on the exception path too
    String(buffer.toByteArray, UTF_8).linesIterator.filter(_.nonEmpty).foreach(sink)
```

Now the honest part. Watch what this actually catches:

```scala mdoc
var captured: Vector[String] = Vector.empty

val readFailed = capturingStderr(line => { captured = captured :+ line }):
  System.err.println("a JVM-side write, from Scala")
  Image.read("no-such-file.png").isLeft

captured
```

The Scala-side line is there. Everything OpenCV printed about the missing file is **not** — the `Left(DecodeFailed)` came back exactly as it always does, but the warning that accompanied it went past `captured` entirely.

The reason is the boundary. `System.err` is a Java object, and replacing it only affects code that goes through it. OpenCV's warning is written by C++ (`fprintf` to `stderr`) directly to **file descriptor 2**, the operating system's standard-error stream, which the JVM object sits on top of but does not own. Swapping the Java object leaves the file descriptor untouched.

So there are two halves, and they need different tools:

| Half | Written by | How to route it |
|---|---|---|
| JVM-side (`System.err.println`, uncaught-exception stack traces, JavaCPP warnings) | Java/Scala | `System.setErr`, as above — or your logging framework's own stderr capture |
| Native (`imread` warnings, videoio codec probes, OpenCV error text) | C++, to fd 2 | process-level redirection only |

For the native half, redirect at the process level and let the platform's log plumbing do the work:

```sh
# systemd, Docker, Kubernetes: fd 2 is already collected — label it and move on.
java -jar app.jar 2> >(logger -t myapp)
```

OpenCV has one knob of its own, the `OPENCV_LOG_LEVEL` environment variable (`SILENT`, `FATAL`, `ERROR`, `WARNING`, `INFO`, `DEBUG`, `VERBOSE`), which quiets its internal logging channel. It is OpenCV's setting, not scalacv's; it changes what is *printed* and never what your code receives, so the `Left` values and thrown exceptions are exactly the same with it set to `SILENT`. Turning it down hides genuine diagnostics along with the noise, so prefer routing over silencing.

:::note[Do not alert on OpenCV's stderr lines]
Some stderr output is printed on paths scalacv **expects** to fail and already handles. Two are routine:

- **The speculative capture open.** When you set `CaptureOptions.withTimeout(...)`, `Video` first tries to open the source *with* the timeout parameters attached, and retries without them if that fails — because several backends reject the parameters outright rather than ignoring them. The rejected first attempt is run inside a `swallowing` helper that catches the exception, but OpenCV has already printed its complaint. Nothing is wrong; the second attempt succeeded. See [Timeouts are best-effort](/video#timeouts-are-best-effort).
- **The recorder's codec probe.** `Recorder.open` asks videoio for a codec, and videoio reports an unavailable one by printing to stderr and leaving the writer closed. scalacv turns that into `Left(CvError.LoadFailed(...))`, which your fallback ladder handles. See [Troubleshooting](/troubleshooting#codec).

A failed `Image.read` on a user upload is the third: an `imread` warning plus a `Left(DecodeFailed)` that your handler turns into a 4xx response.

**Alert on your own `CvError` rate**, which is a number you produce deliberately, and keep the stderr stream as context to read *after* an alert fires — not as the trigger.
:::

## A starter set of signals

| Name | Type | Source | Alert when |
|---|---|---|---|
| `scalacv_ready` | gauge (0/1) | `OpenCv.isLoaded` | 0 after the startup grace period |
| `scalacv_op_seconds{operation}` | histogram | `observing(...)` | p99 above your frame budget |
| `scalacv_op_total{operation,outcome}` | counter | `observing(...)` | `outcome != "ok"` share rises |
| `scalacv_op_total{outcome="threw"}` | counter | `observing(...)` | anything above zero — these are bugs, not bad data |
| `scalacv_frames_total` | counter | your `Camera.foreach` body | rate falls below the source's fps |
| `scalacv_rss_bytes` | gauge | `NativeMemory.rssBytes()` | rises monotonically over an hour of steady load |

Six numbers, none of which the library provides and all of which are a few lines to add. That is the trade the dependency-free design makes.

## Next

- The operational checklist these signals belong to: [Deploying to production](/deploying-to-production).
- What each failure means before you label it: [The error model](/error-model).
- Keeping the service up when a signal goes red: [Degradation and error budgets](/degradation-and-error-budgets).
- Why native memory needs a gauge at all: [Mat lifecycle](/mat-lifecycle).
- Turning the RSS measurement into a build gate: [Testing](/testing#guard-against-native-leaks-with-an-rss-assertion).
