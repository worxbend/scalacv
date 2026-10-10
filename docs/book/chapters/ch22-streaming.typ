#import "../lib/book.typ": *

#chapter("Streaming and Backpressure", subtitle: [What to do when frames arrive faster than you can process them.])

A camera has no interest in your pipeline. The sensor exposes on its own clock, the driver decodes
what the sensor gives it, and neither of them will wait while your detector finishes the previous
frame. At 30 frames per second a new one appears every 33.3 milliseconds, and it appears whether or
not you are ready for it. This is the one place in the book where the data source is a physical
device with its own opinion about time, and it changes what "handling load" means.

Backpressure --- a slow consumer telling a producer to wait --- is the usual answer, and it is the
one answer you cannot have here. A queue library gives it to you for free because both ends are
programs. A camera is not a cooperating producer; it is a device. There is no protocol by which
your face detector informs a CMOS sensor that it needs another twenty milliseconds. The vocabulary
of streaming applies to the half of the system you wrote and not at all to the half that generates
the data.

What makes this genuinely dangerous rather than merely annoying is that falling behind produces no
error. There is no exception, no log line, no dropped-frame counter. The reads keep succeeding, the
frames keep decoding, and every one of them is real. They are just increasingly *old*. A detector
running twenty seconds behind the world is not a slow detector, it is a wrong one, and it will
report its wrong answers with total confidence for as long as you leave it running.

There are exactly three responses to a producer that outruns a consumer: drop work, buffer it, or
slow the producer down. This chapter is about which of the three you can actually have, what
buffering costs when every queued item is a six-megabyte native allocation, and how to write the
drop discipline so that the frames you throw away are also freed. The repository has a page on this
--- `docs/mdoc/streaming-and-backpressure.md` --- and the patterns below are its patterns, because
this is not an area to improvise in.

Every listing here assumes `import scalacv.*`, plus `org.opencv.core.Mat` wherever a raw frame is
named and `java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}` for the threaded ones. Two
listings reach into `scalacv-vision` --- `MotionDetector` and `Dnn`, with `org.opencv.dnn.Net` ---
so that artifact has to be on the classpath alongside the core `scalacv` one; the rest of the
chapter is core only.

#sect("The arithmetic that decides everything")

Suppose the source delivers 30 frames per second and your loop body takes 50 milliseconds. Each
frame you process costs 50 ms of wall clock but advances you only 33.3 ms through the stream. You
lose 16.7 ms per frame, permanently, and the loss compounds because nothing ever gives it back.

#figure-table("A 50 ms loop body against a 33.3 ms frame budget. Nothing is dropped; everything gets older.")[
#tbl(
  columns: (1fr, 1fr, 1fr, 1fr, 1.2fr),
  [After], [Produced], [Consumed], [Backlog], [Frame age],
  [1 second], [30], [20], [10 frames], [0.3 s],
  [10 seconds], [300], [200], [100 frames], [3.3 s],
  [1 minute], [1800], [1200], [600 frames], [20 s],
)
]

The backlog is real memory sitting somewhere, and if you did not allocate it, the driver did. There
*is* a buffer between the sensor and your code --- V4L2 on Linux, AVFoundation on macOS, Media
Foundation or DirectShow on Windows, FFmpeg's receive buffer for an RTSP stream. You did not create
it, you cannot see its depth, and `CaptureInfo` does not report it. When you fall behind, that is
where the frames pile up until it fills, at which point the driver starts discarding them itself,
silently, with a policy you did not choose and cannot query.

Now take the three responses in turn.

*Slow the source* works perfectly --- for a file. Every frame loop in scalacv is a pull loop:
`Camera.foreach`, `Camera.recordTo`, `Video.frames` and `Video.framesCopied` all bottom out in a
`while` loop calling `VideoCapture.read`, which blocks until a frame is available. Against a file on
disk that blocking *is* backpressure. A slow consumer makes the whole job take longer and loses
nothing. If your source is a file, this chapter is not about you.

*Buffer* is what you get by default from a live source, and it is a way of converting a latency
problem into a memory problem. The next section prices it.

*Drop* is the only lever a live source leaves you, and the only question worth arguing about is
which frames to lose.

#warning[
  `attemptsPerFrame` is not a rate limit. `Camera.foreach(attemptsPerFrame = 3)` and
  `Video.frames(cap, attemptsPerFrame = 3)` control exactly one thing: how many consecutive *empty*
  reads it takes before the traversal decides the source is finished. `Video.frames` defaults it to
  `1`, which is right for a file where the first empty read is end-of-file; the `Camera` helpers
  default it to `3`, so a webcam that drops one frame keeps going. Neither value skips frames,
  bounds latency, or changes the read rate.
]

#sect("Measure the lag before you tune anything")

You cannot ask a capture how far behind you are. But a pull loop hands you the measurement anyway,
because the interval between two frame arrivals tells you which side of the budget you are on. If
your body is faster than the source, `read` blocks on the sensor and the interval settles at the
source's real frame period. If your body is slower, `read` returns immediately --- something is
always queued --- so the interval between arrivals *is* your body's duration.

So timestamp each frame at the top of the loop, divide consecutive deltas by the frame budget, and
watch the ratio. scalacv ships no metrics facade of any kind, so the meter is yours to write; it is
about thirty lines and you write it once.

#example("A lag meter: budgets used per frame, where 1.0 is break-even.")[
```scala
final case class LagReport(frames: Long, meanRatio: Double, worstRatio: Double, slowFrames: Long)

/** Single-threaded on purpose: it lives inside one frame loop, and the loop is one thread. */
final class LagMeter(nominalFps: Double):
  private val budgetNs: Double = if nominalFps > 0 then 1e9 / nominalFps else 0.0
  private var previousNs: Long = 0L
  private var seenOne: Boolean = false
  private var frames: Long = 0L
  private var slow: Long = 0L
  private var worst: Double = 0.0
  private var total: Double = 0.0

  def tick(nowNs: Long): Unit =
    if budgetNs > 0 && seenOne then
      val ratio = (nowNs - previousNs) / budgetNs
      frames += 1
      total += ratio
      if ratio > worst then worst = ratio
      if ratio > 1.0 then slow += 1
    previousNs = nowNs
    seenOne = true

  def report: LagReport =
    LagReport(frames, if frames == 0 then 0.0 else total / frames, worst, slow)
```
]

Wiring it into a real loop is three lines, and the only subtlety is where the budget comes from.

#example("The meter inside a Camera loop. Never divide by a reported zero.")[
```scala
def analyseFrame(frame: Image): Unit = ??? // the detector, the network, the real work

def measuredLoop(source: String): Either[CvError, LagReport] =
  Camera.usingFile(source) { cam =>
    val lag = LagMeter(if cam.fps > 0 then cam.fps else 30.0)
    cam.foreach(3) { frame =>
      lag.tick(System.nanoTime())
      analyseFrame(frame)
    }
    lag.report
  }
```
]

Every field of `CaptureInfo` is a `CAP_PROP_*` query answered by the backend, and `fps` is a claim
rather than a measurement: a live camera commonly reports `0` until it has delivered a frame, and
some report a nominal rate they never achieve. That is why the example falls back rather than
trusting `cam.fps`. If the ratio is the number you alert on, derive the budget from the rate you
*configured the device for*, or from a calibration run against the real hardware.

#subsect("Why the mean is the wrong number to watch")

`LagReport` carries four fields and not one, which is the whole point. A loop that averages 0.85 of
its budget looks healthy and can still be broken: if one frame in twenty takes three budgets ---
a garbage collection, a detector meeting a crowded scene, a network stream that stalls --- then
5 per cent of your frames are late, the driver's buffer absorbs them, and the age of what you are
analysing ratchets upward in steps that the mean averages away completely.

The number that shows it is a high percentile. `worstRatio` is a maximum, which is a p100 and
therefore noisy --- one unlucky sample defines it forever --- but paired with `slowFrames` it tells
you whether the worst case is an event or a habit. Twelve slow frames in a hundred thousand is a
GC pause. Twelve hundred is a design problem. For a service that matters, keep the samples and emit
a histogram, so that "p99 above the frame budget" becomes something you can alert on; the
repository's observability guidance names exactly that alert.

There is one reading of the meter that looks like success and is not. A ratio sustained *below* 1.0
means reads are returning instantly because frames are already queued --- you are draining a
backlog, and the frames you are looking at are old. It ends when the queue empties, and it should
be read as a warning.

#figure-table("Reading a sustained lag ratio.")[
#tbl(
  columns: (0.8fr, 2fr, 1.4fr),
  [Ratio], [What it means], [What to do],
  [≈ 1.0], [Keeping up. `read` blocks on the sensor, which is where the wait belongs.], [Nothing.],
  [> 1.0], [You are the bottleneck. Frame age grows by (ratio − 1) × budget per frame.], [Do less per frame, then drop on purpose.],
  [< 1.0 sustained], [You are draining a backlog. The frames you see are stale.], [Treat as a warning, not a win.],
  [≈ 1.0 with spikes], [A slow frame the driver's buffer absorbed.], [Watch the slow-frame count, not the mean.],
)
]

A ratio of 1.02 needs no architecture. Measure first; the rest of this chapter is for the loops that
are genuinely over budget.

#sect("Drop the oldest, not the newest")

Once you accept that frames must be lost, the policy question is which ones. For live vision ---
monitoring, detection, tracking-by-detection, an inference endpoint answering "what is in front of
the camera *now*" --- the answer is nearly always *latest-frame-wins*: keep the newest frame and
discard the backlog behind it.

This is where the obvious tool does the wrong thing. A bounded `java.util.concurrent` queue drops
by refusing the *incoming* item when it is full, which means it preserves the oldest frames and
rejects the freshest. That is drop-newest, exactly backwards for this workload.

#example("Wrong twice: it keeps the stale frames, and it leaks the fresh ones.")[
```scala
// WRONG. `offer` refuses the NEW frame when the queue is full, so the worker keeps
// chewing through history — and the refused clone is dropped without being released.
val queue = java.util.concurrent.ArrayBlockingQueue[Managed[Mat]](8)

def fillQueue(cam: Camera): Unit =
  Video.framesCopied(cam.capture, attemptsPerFrame = 3) { frames =>
    frames.foreach(frame => queue.offer(frame))  // `false` when full, and nobody looks
  }
```
]

Two defects, and the second one is the one this book is about. `offer` returns `false` when the
queue is full and the caller ignores it, so that `Managed[Mat]` --- a real clone, with its own pixel
buffer --- goes out of scope with nothing holding a reference and nothing having called `release()`.
The garbage collector will reclaim the forty-byte Java object and leave the six megabytes behind it
exactly where they are. At 30 frames per second, that is a leak that runs at the frame rate of your
camera.

#memory[
  A queue of frames is a queue of native buffers. A 1920 × 1080 three-channel frame is
  1920 × 1080 × 3 = 6,220,800 bytes --- call it 6.2 MB. A queue bounded at 8 holds up to 50 MB. A
  queue bounded at 1000 holds up to *6.2 GB*, which will kill the container long before it delays a
  frame. And an unbounded queue is not "a queue with no limit"; it is a native-memory leak with a
  scheduler attached, because the only thing that ever bounds it is the OOM killer. Whatever depth
  you choose, choose it in megabytes and then divide.
]

The right shape is not a deeper queue. It is a queue of depth one, where a newly arriving frame
*displaces* the frame already waiting and frees it on the spot.

#example("A one-frame mailbox. The newest frame wins and the loser is released immediately.")[
```scala
/** Ownership moves with the frame: `offer` takes it from the caller, `take` gives it to the
  * caller, and whatever is displaced is released here, because at that instant this slot is
  * the only thing that still knows about it.
  */
final class LatestFrame:
  private val slot = AtomicReference[Managed[Mat]](null)

  /** Publishes `frame`, releasing whatever it displaces. The caller must not touch it again. */
  def offer(frame: Managed[Mat]): Unit =
    Option(slot.getAndSet(frame)).foreach(_.release())

  /** Takes the newest frame, if any. The caller now owns it and must release it. */
  def take(): Option[Managed[Mat]] = Option(slot.getAndSet(null))

  /** Releases anything still in the slot. Call it once nothing can `offer` again. */
  def drain(): Unit = take().foreach(_.release())
```
]

`getAndSet` is a single atomic operation, and that is what makes this correct without a lock:
exactly one caller ever sees a given frame, so exactly one caller is responsible for releasing it. A
second `take()` against an empty slot gets `None`, not a second reference to something already
freed. Note also why this must be `Video.framesCopied` rather than `Video.frames` --- a frame that
crosses a thread boundary has to have its own pixel buffer, and the borrowed reused `Mat` from
`frames` is valid only until the next pull.

#subsect("Who frees the frame that was dropped")

This is the part to get exactly right, because every row below is a transfer of ownership and a
missing release at any of them is an unbounded leak.

#figure-table("Ownership of a cloned frame as it crosses the slot.")[
#tbl(
  columns: (1.3fr, 1fr, 1.7fr),
  [Moment], [Owner], [Who releases it],
  [`frames.next()` returns a clone], [the capture thread], [the capture thread, unless it offers],
  [`offer(frame)` returns], [the slot], [---],
  [a later `offer` displaces it], [the displacing writer], [`offer`, before it returns],
  [`take()` returns `Some(frame)`], [the thread that took it], [that thread, via `use`],
  [the writer has stopped and been joined], [whoever calls `drain()`], [`drain()`],
)
]

Three ways to get it wrong, all of which compile and none of which fails a test on your laptop:

- *Filtering with `takeWhile`.* `frames.takeWhile(_ => running.get)` pulls a frame, evaluates the
  predicate on it, and discards the element when the predicate is false. `framesCopied` cloned it
  before the predicate ran, so the final clone is dropped without being released. Use a `while` loop
  that checks the flag *before* pulling.
- *Draining too early.* Calling `drain()` while the capture thread can still `offer` frees a frame
  the writer may be about to displace, and the frame offered after the drain is never freed at all.
  Stop the writer, join it, then drain.
- *Losing a taken frame.* A worker that takes a frame and throws before releasing it leaks that
  frame. `Managed.use` releases in a `finally`; use it rather than a bare `get`.

#example("The two halves. The capture thread never waits; the worker always gets the newest frame.")[
```scala
def process(frame: Mat): Unit = ??? // the detector, the network, the tracker

// Checking `running` before `hasNext` is why this is a while loop and not `takeWhile`:
// no clone is ever created that the slot does not receive.
def captureInto(source: String, newest: LatestFrame, running: AtomicBoolean): Either[CvError, Unit] =
  Camera.usingFile(source) { cam =>
    Video.framesCopied(cam.capture, attemptsPerFrame = 3) { frames =>
      while running.get && frames.hasNext do newest.offer(frames.next())
    }
  }

def workerLoop(newest: LatestFrame, running: AtomicBoolean): Unit =
  while running.get do
    newest.take() match
      case Some(frame) => frame.use(process)  // `use` releases even if `process` throws
      case None        => Thread.sleep(5)     // nothing new yet; back off rather than spin
```
]

If the worker took 200 ms and six frames arrived meanwhile, five were dropped --- deliberately, at a
known point in the code, in favour of the freshest. That is the entire difference between this and
the driver silently discarding frames on your behalf.

#caution[
  Latest-frame-wins is wrong whenever frames are only meaningful as a sequence. Recording and
  re-encoding lose footage. Frame differencing and background subtraction compare frames whose
  spacing they cannot observe, so a 200 ms gap silently becomes a different
  measurement. Optical flow, odometry and trackers assume small motion between frames, and a
  dropped frame is precisely the large jump they handle worst. If you must drop *and* the sequence
  matters, record an arrival timestamp with each frame and make the algorithm take the elapsed time
  as a parameter.
]

#sect("Three shapes that work")

#subsect("The single-consumer loop")

One thread reads and processes, which is what `Camera.foreach` already gives you. No queue, no
handoff, no ownership question --- the `Image` is closed for you when the body returns. Reach for
this until the lag meter says you cannot, and when it does, spend the first effort on making the
body cheaper rather than on architecture. Detection cost scales with pixels, so halving each side
quarters the pixel count; the cost is missing the smallest objects, which now occupy a quarter of
the pixels they did.

```scala
cam.foreach(3) { frame =>
  val small = frame.scale(0.5)  // consumes `frame`; a quarter of the pixels to detect on
  try detect(small) finally small.close()
}
```

The current callback scope follows this consuming successor, so early `close` is optional and
idempotent. The older 0.4.1 tag closed only the original wrapper; there the explicit successor
close is necessary. To retain a branch beyond the current callback, explicitly `detach` it and
accept the responsibility to close it later.

#subsect("A fixed worker pool")

When the expensive stage parallelises --- independent per-frame inference, several cameras at once
--- put a fixed number of workers behind the slot and give each one its own native resources. Not a
shared one. A `Net`, a `FaceRecognizer`, a `Tracker` and every stateful detector in the library is a
native object with mutable internal state, and a second thread entering an in-flight native call
corrupts it. The failure is a segmentation fault in C++, not an exception you can catch.

#example("One net per worker thread, built on first use and never shared.")[
```scala
val perThreadNet = ThreadLocal.withInitial[Either[CvError, Managed[Net]]] { () =>
  Dnn.fromOnnx("model.onnx")
}
```
]

#memory[
  A pooled detector is native memory with no owner but you. Of the 188 `org.opencv.*` types that own
  native memory, exactly three expose a public `release()` --- the three that `Releasable` frees
  with a plain `_.release()`: `Mat`, `VideoCapture` and `VideoWriter`. `Net` is not among them, and
  neither is `CascadeClassifier`, `QRCodeDetector` or `ArucoDetector`. The `Managed[Net]` is what
  frees it, which means a `ThreadLocal` whose threads retire without closing their entry leaks one
  loaded model per thread. Size the pool, keep it fixed, and close each net when its thread retires --- a
  pool that grows with load is a memory profile that grows with load.
]

Cap the inner thread pools when you fan out. OpenCV and OpenBLAS each run one, and if your outer
parallelism already saturates the cores, the inner pools fight it. Set `OPENBLAS_NUM_THREADS=1` and
`OMP_NUM_THREADS=1` in the environment, or call `org.opencv.core.Core.setNumThreads(1)` from code
before you spread work. For a single sequential pipeline, leave OpenCV's threading on --- it
parallelises the heavy kernels for you. Measure both.

#subsect("Two stages: a cheap gate before an expensive detector")

The pattern that usually removes the problem rather than managing it is the staged one from
Chapter 21, #emph[Motion Detection]: run something cheap on every frame, and the expensive thing
only on the frames that survive. A motion gate on a downscaled frame costs a small fraction of a
network forward pass, and on a static camera it rejects the overwhelming majority of frames without ever cloning them.

#example("Stage one on the capture thread, on a borrowed frame; only survivors are cloned.")[
```scala
def gatedCapture(cam: Camera, gate: MotionDetector, newest: LatestFrame, running: AtomicBoolean): Unit =
  Video.frames(cam.capture, attemptsPerFrame = 3) { frames =>
    while running.get && frames.hasNext do
      val borrowed = frames.next()                            // valid until the next pull
      val small = Image.wrap(borrowed.resize(Size(320, 240)))  // resize allocates its own output
      val moving = try gate.detect(small).moving finally small.close()
      if moving then newest.offer(Managed(borrowed.clone()))   // the clone the worker will own
  }
```
]

Three things make this correct. `Video.frames` borrows one reused `Mat`, so the loop pays no
per-frame clone, and the gate then runs on a 320 × 240 copy rather than a 1080p one; `resize`
allocates its own destination and never aliases its receiver, so running it over a borrowed frame
yields an `Image` that you own and must close; and the full-frame clone happens only on the branch
where a worker is actually going to want it. The borrowed frame
never leaves the loop body --- retaining it would give you a reference to a buffer that the next
`read` overwrites.

#sidebar("The queue you did not build")[
  Two levers exist on the driver's buffer, and both are on the raw `VideoCapture` that `Camera`
  lends you through `cam.capture`. scalacv wraps neither, because neither behaves the same way on
  two backends.

  `cam.capture.set(Videoio.CAP_PROP_BUFFERSIZE, 1.0)` asks the backend to keep only the newest
  frame, moving the drop policy into the driver. Some backends implement it, some ignore it, some
  reject it; `set` returning `true` does not mean it was honoured, and `get` may report the value
  you asked for, the value in force, `0`, or `-1`. Nothing in `CaptureInfo` reports the depth, so
  the only confirmation is behavioural --- the sustained-below-1.0 backlog goes away.

  `VideoCapture` also splits a read in two: `grab()` fetches the next frame from the device and
  `retrieve(mat)` decodes and colour-converts it. Decoding is the expensive half, so discarding a
  queued frame with a bare `grab()` is cheaper than reading it. The catch is that `grab` blocks
  exactly like `read` when the queue is empty, so a fixed skip count turns a source you were keeping
  up with into one you are behind on by that many frame periods. It is a fair trick for a source you
  *know* you are behind on, and a bad permanent setting.
]

#sect("Stopping")

A `read` blocked in native code cannot be interrupted from the JVM. `Thread.interrupt()` sets a flag
that a thread parked inside OpenCV's C++ never looks at, and there is no cancel and no timeout
parameter on `read`. So after you set `running` to false, the capture thread is still inside `read`
and stays there until the source delivers a frame, the connection breaks, or a backend-honoured read
timeout fires. `CaptureOptions.withTimeout(5.seconds)` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC`
and `CAP_PROP_READ_TIMEOUT_MSEC`, which FFmpeg and GStreamer honour for network sources and which
V4L2, AVFoundation and the built-in MJPEG reader ignore entirely --- so set it for `rtsp://` and
`http://` and leave it off for files and local cameras.

That leaves a daemon capture thread as the reliable bound: a thread stuck in `read` cannot then stop
the JVM from exiting, and native memory it still holds is reclaimed by the operating system when the
process dies, which is the one moment not releasing is acceptable. So the thread is created daemon,
and the shutdown stops the writer, `join`s it with a bound, and drains the slot *only* if the thread
actually came back.

#example("Start it as a daemon; drain only after the writer has provably stopped.")[
```scala
def start(source: String, newest: LatestFrame, running: AtomicBoolean): Thread =
  val body: Runnable = () =>
    captureInto(source, newest, running).fold(e => println(e.getMessage), identity)
  val reader = Thread(body, "scalacv-capture")
  reader.setDaemon(true)  // a thread parked in a native read must not hold the JVM open
  reader.start()
  reader

def shutdown(reader: Thread, running: AtomicBoolean, newest: LatestFrame): Unit =
  running.set(false)  // the loop stops pulling once the current read returns
  reader.join(5000)   // may time out; a read blocked in native code cannot be interrupted
  // Draining while the writer still lives is the early-drain leak from the ownership table.
  // If the thread never came back, leave the slot alone and let process exit reclaim it.
  if !reader.isAlive then newest.drain()
```
]

#caution[
  Do not close the capture from another thread to unblock the read. A `VideoCapture` is a native
  object with one owner (Chapter 36, #emph[Concurrency and Thread Safety]), and releasing it while
  a decode is in flight is a data race in C++ --- a segmentation fault, not an exception: no stack trace, no
  catch, a dead process. The capture must be closed by the thread that reads it, after the read has
  returned, which is what `Camera.usingFile`'s `finally` already does.
]

#sect("What comes next")

Everything in this chapter is a primitive you had to build: a mailbox, a lag meter, an ownership
table enforced by discipline. Chapter 37, #emph[ZIO Integration], shows the same loops expressed with an
effect system, where `Scope` releases the capture and the buffer on success, failure *and*
interruption, and blocking reads sit on a blocking pool instead of starving the fibers doing real
work. Be clear about what
that does not buy you: `ZIO.attemptBlockingInterrupt` delivers a JVM interrupt, and a thread inside
OpenCV's native read never observes one. Nothing anywhere cancels a blocked `read`. Bound it at the
source, or plan for a thread that may not come back.

The lag ratio, the slow-frame count and the native-memory gauge are numbers you now know you need
and have nowhere to send. Chapter 38, #emph[Observability], is about where they go: the four
numbers a vision service is judged on --- process resident set size, the ratio of frames processed
to frames the source produced, per-stage latency at the tail, and the distribution of detection
scores --- the memory measurement that actually sees a `Mat` leak, and why the library emits no
logs, no metrics and no traces of its own. The chapters between here and there change subject
rather than direction: the frame source has been fully described, and what runs *on* a frame ---
models, faces, networks, markers, poses --- is the next part of the book. Every detector in it is a
native object with the ownership rules this chapter has just spent a whole slot enforcing.
