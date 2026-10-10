#import "../lib/book.typ": *

#chapter("Observability", subtitle: [The four numbers a vision service is judged on, none of which a JVM dashboard shows you.])

A vision service that has gone wrong rarely looks wrong. The HTTP handler still returns 200. The
thread pool is not saturated. Heap usage is a flat line at 380 MB, garbage-collection pause time is
under a millisecond, and the error rate is zero because nothing has thrown. Every panel on the
standard JVM dashboard is green, and the service has been returning empty detection lists for
twenty minutes because someone bumped the exposure on the camera.

That is the shape of the problem. The instrumentation the JVM ecosystem gives you for free was
designed for a system whose work is heap-allocated objects and whose failure is an exception. A
frame pipeline's work is native pixel buffers, and its failure modes are *silence*, *lateness* and
*drift* --- none of which raises anything. A leaked `Mat` produces no `OutOfMemoryError`, because the
memory is not on the heap; the kernel arrives instead, with a `SIGKILL` and exit code 137. A
consumer running four seconds behind the sensor produces no error either; it produces correct
answers about a world that no longer exists. And a detector whose input conditions have shifted
produces no error at all --- it produces fewer boxes, with slightly lower scores, forever.

So the dashboard has to be built out of different material. Four numbers matter, and a JVM agent
gives you none of them: process resident set size, the ratio of frames processed to frames the
source produced, per-stage latency at the tail, and the distribution of detection scores. All four
are a few lines of code away, and not one of them is written for you. scalacv ships no logging
facade, no metrics registry and no tracing hooks --- `scalacv`, `scalacv-vision` and `scalacv-graphs`
each declare exactly one dependency, the OpenCV Java API, and `scalacv-zio` adds ZIO and nothing
else. That is a trade with two halves. Nothing conflicts with your stack: no facade to bind, no
`NOP` warning at startup, no fight between the SLF4J version your service chose and the one a
library forced on it. And nothing is instrumented for you. This chapter is the other half of that
bargain --- which numbers to add, where the seams are, and which of them may wake someone up.

#sect("The signals that are worth a panel")

Start from what can go wrong, not from what is easy to measure. Each row below exists because a
real failure is invisible without it.

#figure-table("The vision-service dashboard. None of these is provided by the library; all of them are yours to add.")[
#tbl(
  columns: (1.35fr, 0.72fr, 1.6fr),
  [Signal], [Type], [What a bad value means],
  [process RSS], [gauge], [rising under steady load: a `Mat`, `Image` or detector is never closed],
  [frames in], [counter], [rate below the source's fps: you are not seeing every frame the device sent],
  [frames processed], [counter], [drifting below frames in: your drop discipline is running, or the loop is stalling],
  [frames dropped], [counter], [any sustained rate: the consumer is slower than the producer],
  [per-stage seconds], [histogram], [p99 above the frame budget: one stage owns the whole backlog],
  [detections per frame], [gauge], [a step to zero with no errors: the model, the lens or the light changed],
  [detection score], [histogram], [mass sliding toward the threshold: drift, before it becomes a step to zero],
  [`CvError` by case], [counter], [a rise in one label localises the fault to one resource],
  [thrown, not returned], [counter], [anything above zero is a bug, not bad data],
)
]

Count *frames in* yourself, inside the loop body. The temptation is to take a denominator from the
device instead --- `Camera.info` returns a `CaptureInfo` with an `fps` and a `frameCount` --- but every
field of it is a `CAP_PROP_*` query the backend is free to guess at. A live camera usually answers
`frameCount == 0`, because the question is meaningless for a stream that has not ended, and `fps`
can be `0` before the first frame arrives. Those fields are a hint for a file transcode, where
`Camera.recordTo` hands you the frames it actually wrote as a `Long` to divide by, and they are
nothing at all for the live source you are most likely watching.

Two of those rows are the ones people leave off, and both deserve an argument.

*Detections per frame* is the only signal that catches a silent model failure. A `Net` that loads,
runs and returns nothing is indistinguishable from a working detector pointed at an empty room ---
unless you know what the room usually looks like. A warehouse camera that has averaged 3.2
detections per frame for six months and now averages 0.0 is telling you something no error rate
can.

*Detection score* is the leading indicator for the same failure, and it is already in your data:
`Face` carries `score: Float` in `[0, 1]`, `Keypoint` carries `score`, `TemplateMatch` carries a
`Double`, `OcrWord` carries `confidence`. A detector whose scores are drifting from a mode at 0.94
toward a `scoreThreshold` of 0.9 is a week away from returning nothing, and its detection count
looks healthy for most of that week. Bucket the scores and you see it coming.

The `CvError` row is cheap because the vocabulary is fixed: a sealed hierarchy of exactly six cases,
so an exhaustive match over it is also an exhaustive list of your metric labels. Never label with
`e.getMessage` --- it contains file paths and pixel dimensions, and a metrics backend creates one
time series per distinct label value.

#example("Six labels, and a timer that is honest about the failures that never reach the Either.")[
```scala
/** Your metrics client: a Micrometer `Timer.record`, a Prometheus histogram `observe`, a Kamon timer. */
def record(operation: String, nanos: Long, outcome: String): Unit = ???

def errorLabel(e: CvError): String = e match
  case _: CvError.NativesMissing    => "natives_missing"
  case _: CvError.DecodeFailed      => "decode_failed"
  case _: CvError.LoadFailed        => "load_failed"
  case _: CvError.EncodeFailed      => "encode_failed"
  case _: CvError.CalibrationFailed => "calibration_failed"
  case _: CvError.NativeCall        => "native_call"

/** Times `call` and records it on every path — Right, Left, and thrown. */
def observing[A](operation: String)(call: => Either[CvError, A]): Either[CvError, A] =
  val start = System.nanoTime()
  var outcome = "threw"
  try
    val result = call
    outcome = result.fold(errorLabel, _ => "ok")
    result
  finally record(operation, System.nanoTime() - start, outcome)
```
]

The `"threw"` outcome is the part people delete, and the part that earns its keep. scalacv returns a
`Left` for failures that depend on the data --- a file that is not there, bytes that do not decode ---
and *throws* for programmer errors: an `IllegalArgumentException` from a violated precondition, an
`IllegalStateException` from an `Image` used after a transform consumed it. Those never reach the
`Either`, so a helper that records only `fold` outcomes reports a perfect success rate on a code path
crashing every request. The `try`/`finally` is what makes the timer tell the truth.

Take the operation name from the library rather than inventing one. Every fallible native call goes
through `Cv.attempt(operation)`, whose first argument ends up inside
`CvError.NativeCall(operation, cause)` and therefore inside the message an on-call engineer reads.
When a timer called `Imgproc.GaussianBlur` spikes and a log line says
`OpenCV failed during Imgproc.GaussianBlur`, the two join without a translation table.

#sect("Watching native memory, which is the whole ball game")

Everything above is ordinary service instrumentation applied to an unusual workload. This section is
the part that is genuinely different.

OpenCV's pixel buffers are allocated by C++, through `cv::fastMalloc`, outside the Java heap. `-Xmx`
does not bound them, heap-used gauges do not see them, and garbage-collection metrics do not react
to them. A leaked 1080p BGR frame is about six megabytes of resident memory hiding behind roughly
forty bytes of on-heap object, so a leak of a hundred frames per minute moves your heap graph by
four kilobytes and your machine by six hundred megabytes.

#memory[
  The measurement from Chapter 1 is the calibration for every dashboard in this chapter: 2000
  `Mat(1000, 1000, CV_8UC3)` with their references dropped and no explicit `System.gc()` finished at
  5,865 MB of RSS; the same loop with `release()` on each finished at 144 MB. Forty-one times the
  memory for one omitted call, and *no JVM-visible signal changed between the two runs*. Whatever
  else your dashboard shows, it must show RSS.
]

Here is the exact picture to recognise at three in the morning. Put the heap-used gauge and the RSS
gauge on the same panel, with the same time axis, and read the pair rather than either one.

#figure-table("Reading the heap graph and the RSS graph together. Only the second row is a leak.")[
#tbl(
  columns: (0.9fr, 0.9fr, 2fr),
  [Heap], [RSS], [Diagnosis],
  [flat, sawtooth], [flat plateau], [healthy. RSS never returns to its start --- arenas and the code cache keep what they took],
  [flat, sawtooth], [climbing, linear], [a native leak. The slope is proportional to your frame rate, and it does not stop],
  [flat, sawtooth], [one step, then flat], [a bigger input, not a leak --- somebody uploaded a 40-megapixel photograph],
  [climbing], [climbing], [an ordinary Java leak. Take a heap dump; this chapter is not about you],
)
]

The second row is the signature, and its distinguishing feature is that the climb is *straight*. A
Java leak bends, because the collector fights it. A `Mat` leak has no opponent: nothing in the
runtime has a motive to reclaim the memory or a deadline by which to try, so the line rises at
exactly the rate you allocate until the cgroup limit is reached and the kernel ends the argument.

The gauge that sees it is process resident set size. On Linux the cheapest source is
`/proc/self/statm`, a one-line pseudo-file whose second field is the resident page count; elsewhere,
JavaCPP's `Pointer.physicalBytes()` is also RSS-based and works everywhere. This is exactly the
implementation the repository's own leak harness uses, in `leaks/test/src/scalacv/LeakAssertions.scala`.

#example("The only counter that can see an `org.opencv.core.Mat`.")[
```scala
import java.nio.file.{Files, Path}
import org.bytedeco.javacpp.Pointer

object NativeMemory:
  /** 4 KiB on x86_64 and arm64; the env var is the escape hatch for a large-page kernel. */
  private val pageBytes: Long =
    sys.env.get("SCALACV_PAGE_BYTES").flatMap(_.toLongOption).getOrElse(4096L)

  private val statm: Path = Path.of("/proc/self/statm")

  def rssBytes(): Long =
    if Files.isReadable(statm) then
      String(Files.readAllBytes(statm)).trim.split("\\s+")(1).toLong * pageBytes
    else Pointer.physicalBytes() // macOS, Windows, or a container without /proc
```
]

Export it as a gauge on a slow schedule --- once every 10 to 60 seconds is ample for a trend. Two
warnings come with it. RSS is a *whole-process* number, so a cache, another library's off-heap
buffers or a second workload in the same JVM moves it too: fine on a dashboard, where total
footprint is what you care about, and fatal in an assertion, which is why the repository's leak suite
runs in a JVM of its own. And pair the gauge with `-Dorg.bytedeco.javacpp.maxPhysicalBytes`, set
comfortably *below* the container's memory limit, so that crossing the ceiling gives you a
`java.lang.OutOfMemoryError` with a stack trace instead of a silent kernel kill. Chapter 39 explains
why that error is not a `CvError` and passes straight through every `Either` in the library.

#sidebar("Two counters that will lie to you about this")[
  The first is `Pointer.totalBytes()`, JavaCPP's own accounting, and the budget
  `-Dorg.bytedeco.javacpp.maxBytes` that is checked against it. It tracks memory JavaCPP allocated
  through its own `Pointer` allocators. scalacv wraps the official `org.opencv.core.Mat` Java API,
  whose buffers never pass through one. This project's memory audit measured the consequence
  precisely: a deliberate 1.4 GB `Mat` leak moved `Pointer.totalBytes()` by *zero bytes*. A dashboard
  built on it reads flat while the process doubles.

  The second is the JVM's own Native Memory Tracking, which you reach with
  `-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory summary`. It is an excellent
  tool and it is equally blind here, for the same structural reason: NMT accounts for memory the JVM
  allocates --- heap, metaspace, thread stacks, code cache, GC structures --- and OpenCV's `fastMalloc`
  is none of those. A leaking pipeline shows an NMT summary that is entirely unremarkable.

  What NMT is good for is the *subtraction*. Take its total committed away from RSS and the
  remainder is memory the JVM did not allocate --- in a scalacv service, overwhelmingly pixels. A
  growing remainder puts the leak in your frame handling and nowhere in Java.
]

#sect("The one diagnostic flag the library actually has")

Grep the sources for system properties and you find exactly one:
`-Dscalacv.trackOwnership=true`, read once at class load in `Managed`. It emits no metric and it is
not a production setting; it solves one specific and maddening problem.

The move semantics of `Image` make the commonest mistake a reuse of a handle some transform already
consumed, and the resulting `IllegalStateException` fires at the *reuse*, which is almost never the
interesting line. Turn the flag on and the exception carries, as its cause, the stack of the
transform or terminal that actually spent the handle. It is off by default because turning it on
allocates and fills in a `Throwable` on every spend --- every `gray`, every `blur`, every `write` ---
which is the one thing you do not want at frame rate. Off, the spend path is a read of a `private
val` that the JIT folds away and the field stays `null`, so a correct program pays nothing for the
flag existing. Run it in the reproduction rather
than the fleet --- one pod with the flag set and the failing request steered to it, or a staging tier
that mirrors production traffic.

#sect("Logging: almost nothing, once per frame")

The instinct that has served you well in a request/response service is a disaster here, and it is
worth writing out the wrong version because it is what everybody writes first.

#example("Wrong. At 30 fps this is 2,592,000 lines per camera per day.")[
```scala
camera.foreach() { frame =>
  val faces = frame.faces(detector)
  logger.info(s"frame processed: ${faces.size} faces, scores=${faces.map(_.score)}")
  // …
}
```
]

Thirty structured lines a second per camera, each carrying a serialised sequence of floats; eight
cameras on a box is 240 a second. The JSON encoding shows up in your frame budget, the log shipper's
back-pressure shows up in your frame *drops*, and the ingestion bill arrives at the end of the month
with more force than either. Logging at frame rate is its own outage, and the outage it causes looks
exactly like the performance problem you were trying to diagnose.

The right version keeps the same information and pays for it once per second. Aggregate in the loop
--- it is one thread, so plain `var`s are correct and free --- and emit on a boundary.

#example("Right. One line per second, with the shape of the whole second in it.")[
```scala
/** Single-threaded on purpose: it lives inside one frame loop, and the loop is one thread. */
final class SecondSummary(source: String):
  private var windowStartMs = System.currentTimeMillis()
  private var frames = 0L
  private var detections = 0L
  private var minScore = Float.MaxValue
  private var slowFrames = 0L

  def observe(faces: Seq[Face], elapsedMs: Long, budgetMs: Long): Unit =
    frames += 1
    detections += faces.size
    faces.foreach(f => if f.score < minScore then minScore = f.score)
    if elapsedMs > budgetMs then slowFrames += 1
    val now = System.currentTimeMillis()
    if now - windowStartMs >= 1000 then
      // `minScore` is still MaxValue when the whole second saw no detection at all.
      val worst = if minScore == Float.MaxValue then 0f else minScore
      logger.info(
        s"source=$source frames=$frames detections=$detections " +
          s"minScore=$worst slow=$slowFrames"
      )
      windowStartMs = now; frames = 0; detections = 0; slowFrames = 0
      minScore = Float.MaxValue
```
]

Two things do belong at frame granularity, and only two: a `Left` you did not expect, and a thrown
exception. Both are rare by construction, and if either becomes common the volume is itself the
signal. Everything else --- counts, durations, scores --- goes into metrics, where the aggregation
happens in the backend rather than in your log pipeline.

#warning[
  Do not alert on OpenCV's own stderr output. Several of those lines appear on paths scalacv
  *expects* to fail and handles correctly: the speculative open that a `CaptureOptions.withTimeout`
  triggers, which attaches the timeout parameters, lets several backends reject them outright, and
  then retries without them; the codec probe in `Recorder.open` that becomes a clean
  `Left(CvError.LoadFailed(...))`; and the `imread` warning accompanying every
  `Left(CvError.DecodeFailed(...))` from a bad upload. They are written by native code straight to
  file descriptor 2, so `System.setErr` does not even capture them --- redirect fd 2 at the process
  level and keep the stream as context to read *after* an alert, never as the trigger.
]

#sect("Sampling: one frame in N, and the only debugger you get")

Every production vision bug eventually reduces to one sentence: *I need to see what the camera saw*.
You cannot attach a debugger to a frame that arrived nine hours ago, and no quantity of numbers will
tell you that the lens has a smear on it. The technique that works is a small, bounded,
*deterministic* sample of annotated frames --- sampled on the frame counter, not on a random number.
One frame in every 900 at 30 fps is one every thirty seconds, the volume is exactly predictable, and
a problem reported at 14:32 is bracketed by two frames you can name.

#example("A sampler that scopes its image and encodes one JPEG every thirty seconds.")[
```scala
final class FrameSampler(everyN: Long, sink: (Long, Array[Byte]) => Unit):
  require(everyN > 0, s"everyN must be positive, was $everyN")
  private var seen = 0L

  /** Borrows `frame`; the caller keeps ownership and may go on using it. */
  def offer(frame: Image, faces: Seq[Face]): Unit =
    seen += 1
    if seen % everyN == 0 then
      val id   = seen
      val best = faces.map(_.score).maxOption.getOrElse(0f)
      // `.copy` first: drawRect and drawText are transforms, and would consume the caller's handle.
      val boxed = faces.foldLeft(frame.copy) { (img, f) =>
        img.drawRect(f.box, Scalar.Green, Thickness.Stroke(2))
      }
      // `.bytes` is a terminal: it encodes, then releases the copy on every path.
      boxed
        .drawText(s"frame $id, best score $best", Point(10, 30))
        .bytes(".jpg")
        .foreach(jpeg => sink(id, jpeg))
```
]

#memory[
  The `.copy` is not defensive style, it is a correctness requirement. `drawRect` and `drawText` are
  transforms: they consume the handle they are given. Hand them the caller's `frame` and the caller's
  next line throws `IllegalStateException` --- one frame in every 900, the worst possible frequency
  for a bug. The `.bytes` terminal then releases the copy on every path, so the sampler costs one
  extra frame on the sampled iteration and nothing on the other 899.
]

#caution[
  A sampled frame contains identifiable faces, licence plates, screens with data on them, and
  whatever was on the desk behind the subject. It is personal data the moment it lands in your
  bucket, and a debugging sample is the classic route by which personal data reaches a store nobody
  wrote a retention policy for. Before turning this on: set a lifecycle rule that deletes the objects
  (thirty days is generous), give the bucket its own access control rather than reusing the one your
  application logs go to, and ask whether the sample can be blurred first. Chapter 34 covers the
  background-blur path; the same operation applied to detected face boxes yields a frame that shows
  geometry, timing and lighting while showing nobody's face.
]

#sect("Tracing one frame from sensor to answer")

Distributed tracing assumes a request identifier handed to you at an ingress. A frame loop has no
ingress, so mint one: a monotonic counter beats a UUID here, because it is also the frame's position
in the stream. Carry three fields --- sequence number, capture timestamp in nanoseconds, source name
--- into every log line, every sampled object key and every span. The pay-off is the join: a
downstream consumer reporting a wrong answer reports the frame id, and that id names the sampled
JPEG, names the log line carrying the per-stage timings, and gives you a timestamp to line up
against the camera's own recording.

#example("Per-stage timing that keeps the frame id, so a bad output names its own input.")[
```scala
final case class StageTiming(stage: String, micros: Long)

final case class FrameTrace(id: Long, source: String, capturedNanos: Long,
                            stages: Vector[StageTiming]):
  def totalMicros: Long = stages.map(_.micros).sum

/** Times one stage and appends it to the trace. A `Left` is a result too, so it keeps its timing;
  * a thrown stage is recorded by the same `finally` that made `observing` honest. */
def stage[A](trace: FrameTrace, name: String)(body: => A): (FrameTrace, A) =
  val start   = System.nanoTime()
  var outcome = "threw"
  try
    val result  = body
    val elapsed = System.nanoTime() - start
    outcome = "ok"
    (trace.copy(stages = trace.stages :+ StageTiming(name, elapsed / 1000)), result)
  finally record(name, System.nanoTime() - start, outcome)
```
]

Keep the stage names few and stable --- `decode`, `preprocess`, `detect`, `annotate`, `encode` is a
complete taxonomy for most pipelines --- because they are metric labels as well as trace names, and a
name derived from the data multiplies your time series without telling you anything. And record the
capture timestamp when the frame arrives in your loop, not when you finish with it: the difference
between the two, accumulated, *is* the lag Chapter 22 showed nothing in the capture API will report.
A frame whose id is 40,000 and whose capture timestamp is four seconds old is the backlog, made
observable.

#sect("Alert on the derivative, not on the level")

The last decision is which of these numbers may wake a human, and the answer is fewer than you would
like. Two rules cover most of it.

*Do not alert on CPU.* A vision service is supposed to be CPU-bound; OpenCV parallelises its heavy
kernels across cores by design, and a box at 85 per cent CPU with every frame inside budget is doing
its job well. CPU says nothing about whether the answers are right or timely, and a CPU alert trains
everyone to ignore the pager.

*Alert on slopes and ratios.* Two earn a page: the dropped-frame rate --- the divergence between
frames in and frames processed, which measures the service failing at its actual job --- and the
slope of RSS over an hour of steady load, which is the only warning before the kernel kills the
process.

#figure-table("A starter alert set. Everything else is a dashboard panel, not a page.")[
#tbl(
  columns: (1.3fr, 1.45fr, 1.25fr),
  [Alert], [Condition], [Why this one],
  [frames dropped], [processed / in below 0.98 for 5 min], [the service is not doing its job, and nothing throws],
  [RSS slope], [monotonic rise over 60 min of steady load], [the only warning before exit code 137],
  [not ready], [`OpenCv.isLoaded` false past the grace period], [a packaging fault; no restart fixes it],
  [thrown outcomes], [any rate above zero], [these are bugs, not bad data --- there is no acceptable rate],
  [detections per frame], [below the 7-day floor for 15 min], [the silent failure no error rate can see],
  [stage p99], [above the frame budget for 10 min], [the backlog, localised to one stage],
)
]

The readiness alert is easy to get wrong. `OpenCv.load()` *throws* `CvError.NativesMissing` rather
than returning it, and the message carries a copy-pasteable dependency line naming the platform you
are actually on. That is a packaging failure, so report not-ready rather than not-alive: a liveness
probe that kills and reschedules the container achieves nothing but a faster crash loop. Gate
readiness on `OpenCv.isLoaded`, warm any DNN model in the same place before readiness flips true, and
let the orchestrator route around the instance.

The RSS-slope alert is the one to tune carefully, since it is the one that pages legitimately at four
in the morning. Do not alert on RSS crossing a threshold: a service that genuinely runs at 3.2 GB
crosses any threshold you pick once and then never tells you anything again. Alert on the *sign of
the trend* over a window long enough to average out a burst of large uploads. An hour of monotonic
increase under a flat request rate is a leak, and an hour is still early enough to redeploy before
the container dies.

#sect("Where this goes next")

Nine signals, one flag, a sampler and six alerts, none of which the library provides and all of
which are a few dozen lines: that is the shape of the dependency-free bargain.

What this chapter deliberately did not answer is what to *do* when one of those alerts fires. A red
signal is only useful if the service has somewhere to degrade to --- a codec it can fall back to, a
capture it can reopen, a bundled cascade for when the model mirror is down, a request it can shed
when native memory runs out before anything catches it. Chapter 39, #emph[Degradation and Error Budgets],
is the ladder each of these signals climbs down, and the arithmetic for deciding how much of each
failure is normal enough to leave alone.
