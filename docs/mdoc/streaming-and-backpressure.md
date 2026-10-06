---
title: Streaming and backpressure
description: What to do when your frame consumer is slower than your camera — how to measure the lag, the three strategies that work, and the ones the library cannot give you.
---

# Streaming and backpressure

**Backpressure** is the ability of a slow consumer to tell a producer "wait for me". A queue library
gives you that for free. A camera does not: the sensor exposes, the driver decodes, and neither of
them has any interest in whether your detector has finished the last frame. So the usual streaming
vocabulary only half applies here, and this page is about the half that does not.

The short version:

- If your source is a **file**, there is no problem to solve. `VideoCapture.read` pulls the next
  frame off the disk on demand, so a slow consumer makes the whole thing take longer and loses
  nothing. Everything below is irrelevant to you.
- If your source is **live** — a webcam, an RTSP (Real Time Streaming Protocol) camera, a capture
  card — you cannot slow it down. Your only two levers are **be fast enough** or **drop frames on
  purpose**. Doing neither means the frames you analyse get older and older, and *nothing in the API
  will tell you it is happening*.

:::note[What runs here and what does not]
Every snippet is type-checked. The ones that measure lag and the one-slot frame mailbox **run** —
they are plain Scala plus a couple of small `Mat`s, so they need no camera. Everything that opens a
capture is `compile-only`: it type-checks against the real library but is not executed, because CI
has no webcam and this repository ships no video fixture. If you have not met `Camera`, `Video` and
the borrowing contract yet, read [Video & the camera](/video) first — this page assumes all of it.
:::

```scala mdoc:silent
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.videoio.Videoio
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

OpenCv.load()
```

## The shape of the problem

Every frame source in scalacv is a **pull loop**. `Camera.foreach`, `Camera.recordTo`,
`Video.frames` and `Video.framesCopied` all end up in the same place: a `while` loop calling
`VideoCapture.read`, which blocks until a frame is available and then hands it over. Your body runs,
returns, and the loop asks for the next one. There is no queue in scalacv, no rate limiter, no drop
policy, and no thread boundary — the read and your work happen on the same thread, one after the
other.

That design is the right one (it is what keeps native memory at one frame regardless of video
length — see [Video](/video#the-borrowing-contract)), and it has one consequence worth internalising.

There *is* a buffer between the camera and you. It belongs to the driver — V4L2 (Video4Linux2) on
Linux, AVFoundation on macOS, Media Foundation or DirectShow on Windows, or FFmpeg's network
receive buffer for an RTSP stream. You did not create it, you cannot see its depth, and
`CaptureInfo` does not report it. When you fall behind, that is where the frames pile up.

Here is the arithmetic. Suppose the camera delivers 30 frames per second — one every 33.3 ms — and
your loop body takes 50 ms:

| After… | Frames produced | Frames you consumed | Backlog | The frame you are looking at is… |
|---|---|---|---|---|
| 1 second | 30 | 20 | 10 frames | 0.3 s old |
| 10 seconds | 300 | 200 | 100 frames | 3.3 s old |
| 1 minute | 1800 | 1200 | 600 frames | 20 s old |

You do not drop anything. Each frame you process costs 50 ms of wall clock but only advances you
33.3 ms through the stream, so you fall behind by 16.7 ms per processed frame, forever, and the
**wall-clock age of the frame you are analysing grows without bound**. A face detector that is
twenty seconds behind is not a slow face detector; it is a wrong one.

In practice the growth stops somewhere, because the driver's buffer is finite: once it fills, the
driver starts discarding frames itself, silently and with no policy you chose. Which frames it drops
and how deep the buffer was are both backend-specific and unreported. So the failure mode is not a
crash, or an error, or a log line. It is **stale output**, and the only way you find out is by
measuring.

| What a stream library gives you | What you have here |
|---|---|
| The consumer signals the producer to slow down | Impossible: a camera is a device, not a cooperating producer |
| A bounded buffer you configured | The driver's buffer, of unknown depth, which you did not configure |
| A drop policy as a combinator | You write it yourself — see [strategy 2](#strategy-2--drop-on-purpose-latest-frame-wins) |
| A queue-depth or lag metric | Nothing. Not in `CaptureInfo`, not anywhere |

:::warning[`attemptsPerFrame` is not a rate limit]
`Camera.foreach(attemptsPerFrame = 3)` and `Video.frames(cap, attemptsPerFrame = 3)` control one
thing only: how many consecutive **empty** reads it takes before the traversal decides the source is
finished. They do not skip frames, bound latency, or affect the read rate at all. See
[Video](/video#end-of-stream-or-a-broken-one).
:::

## Measure the lag before you tune it

You cannot ask the capture how far behind you are. But in a pull loop the gap between two frame
arrivals is a direct measurement of *your own loop*, and that turns out to be exactly the number you
want:

- If your body is **faster** than the source, `read` blocks waiting for the sensor, and the gap
  between arrivals settles at the source's real frame period. You are keeping up.
- If your body is **slower**, `read` returns immediately (there is always something queued), so the
  gap between arrivals *is* your body's duration. You are the bottleneck, and the ratio of the gap
  to the frame period is how fast you are falling behind.

So: timestamp each frame at the top of the loop body, divide consecutive deltas by the frame budget
(one second ÷ frames per second), and watch the ratio.

```scala mdoc:silent
/** A summary of how a frame loop kept up. Ratios are "budgets used per frame": 1.0 is break-even. */
final case class LagReport(frames: Long, meanRatio: Double, worstRatio: Double, slowFrames: Long)

/** Folds frame arrival times into a lag estimate.
  *
  * Single-threaded on purpose: it is meant to live inside one frame loop, and the loop is one
  * thread. `nominalFps` of 0 (what a camera reports before it has delivered anything) disables the
  * meter rather than dividing by zero.
  */
final class LagMeter(nominalFps: Double):
  private val budgetNs: Double = if nominalFps > 0 then 1e9 / nominalFps else 0.0
  private var previousNs: Long = 0L
  private var seenOne: Boolean = false
  private var frames: Long = 0L
  private var slow: Long = 0L
  private var worst: Double = 0.0
  private var total: Double = 0.0

  /** Record the arrival of a frame at `nowNs` (a `System.nanoTime()` reading). */
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

Feed it a run where the first three frames arrive on time and the last two each take 50 ms:

```scala mdoc:silent
val meter = LagMeter(30.0) // a 30 fps source — 33.3 ms of budget per frame
Seq(0L, 33_000_000L, 66_000_000L, 116_000_000L, 166_000_000L).foreach(meter.tick)
```

```scala mdoc
meter.report
```

Two of the four intervals used more than one budget, and the worst used one and a half — which is
the "50 ms of work against a 33.3 ms budget" case from the table above.

Wiring it into a real loop is three lines:

```scala mdoc:compile-only
def analyseFrame(frame: Image): Unit = () // stand-in for your real work

def measuredLoop(source: String): Either[CvError, LagReport] =
  Camera.usingFile(source) { cam =>
    // `cam.fps` is advisory and is 0 for a camera that has not delivered a frame yet, so fall back
    // to the rate you configured the device for rather than trusting it.
    val lag = LagMeter(if cam.fps > 0 then cam.fps else 30.0) // never divide by a reported 0
    cam.foreach(3) { frame =>
      lag.tick(System.nanoTime())
      analyseFrame(frame)
    }
    lag.report
  }
```

### Reading the ratio

| Sustained ratio | What it means | What to do |
|---|---|---|
| ≈ 1.0 | Keeping up. `read` is blocking on the sensor, which is where you want the wait to be. | Nothing. |
| > 1.0 | You are the bottleneck. Frame age is growing at `(ratio − 1) × budget` per frame. | [Strategy 1](#strategy-1--make-the-consumer-fast-enough), then [strategy 2](#strategy-2--drop-on-purpose-latest-frame-wins). |
| < 1.0, sustained | You are draining a backlog: reads return instantly because frames are already queued. The frames you are seeing are **old**. | Treat it as a warning, not a win. It ends when the queue empties. |
| ≈ 1.0 with an occasional spike | A slow frame (a garbage collection, a detector on a busy scene) that the queue absorbed. | Watch `worstRatio`; a spike is only a problem if it is frequent enough to accumulate. |

:::warning[`fps` is a claim, not a measurement]
Every field of [`CaptureInfo`](/video#info-size-fps) is a `CAP_PROP_*` query answered by the
backend. A live camera commonly reports `fps == 0` until it has delivered a frame, and some report a
nominal rate they never actually achieve. If the ratio is the number you alert on, derive the budget
from the rate you *configured*, or from a calibration run against the real device — not from
`cam.fps` alone.
:::

Exporting the ratio is your job: scalacv has no metrics facade of any kind. See
[Observability](/observability) for where to put the gauge and what else is worth instrumenting.

## Strategy 1 — make the consumer fast enough

The first strategy is the only one that loses nothing, so try it first. Two levers, in order of how
much they usually buy you.

### Do less work per frame

Detection cost scales with pixels. Halving each side quarters the pixel count, and for a detector
whose cost is dominated by the image it scans, that often approaches a 4× speed-up — at the cost of
missing the smallest objects, which now occupy a quarter of the pixels they did. A
transform *consumes* the `Image` it is called on ([move semantics](/mat-lifecycle)), so the scaled
frame is a new `Image` that you own and must close — the one `foreach` handed you is closed for you
either way:

```scala mdoc:compile-only
def analyseHalfSize(cam: Camera): Unit =
  cam.foreach(3) { frame =>
    val small = frame.scale(0.5) // consumes `frame`; a quarter of the pixels to detect on
    // ... run the detector on `small`, then scale any boxes back up by 2 ...
    small.close() // `small` is yours; the original was closed by `foreach`
  }
```

Beyond that: convert to greyscale once and reuse it rather than per detector; crop to the region you
actually care about before doing anything expensive; and check
[Benchmark results](/benchmark-results) before hand-rolling an optimisation — one tempting
candidate (reusing a destination `Mat` across frames) was measured and found not to pay.

### Skip the per-frame copy

`Camera` clones every frame so that the `Image` you get is safe to keep. That is one full-frame copy
per iteration — about 6 MB for 1080p BGR (blue-green-red, OpenCV's default channel order). If your
loop reads the frame, transforms it and writes it out without ever needing to keep it, you can skip
that clone entirely by dropping to `Video.frames` on the camera's borrowed `capture`, and writing
through `Recorder`'s `Mat` overload, which exists for exactly this:

```scala mdoc:compile-only
// Zero-copy re-encode: one borrowed frame in, one owned Mat per stage, a borrowed Mat out.
def recordEdges(source: String, out: String): Either[CvError, Unit] =
  try
    Camera
      .usingFile(source) { cam =>
        val meta = cam.info
        Recorder.using(out, meta.size, (if meta.fps > 0 then meta.fps else 30.0), Codec.Mjpg) { rec =>
          Video.frames(cam.capture, attemptsPerFrame = 1) { frames =>
            frames.foreach { frame =>
              // `Mats.chain` releases every intermediate as the next stage consumes it, so the whole
              // pipeline costs one live Mat at a time and hands back one owned result.
              val processed = Mats.chain(frame.mat)(
                _.cvtColor(ColorConversion.BgrToGray),
                _.canny(80.0, 160.0),
                _.cvtColor(ColorConversion.GrayToBgr)
              )
              // `write` borrows the Mat; `use` releases it afterwards, exception path included.
              processed.use(m => rec.write(m)).fold(e => throw e, identity)
            }
          }
        }
      }
      .flatten
  catch case e: CvError => Left(e)
```

Two things to note. The `throw`-and-catch is how `Camera.recordTo` handles a mid-loop failure
internally: `foreach` has no way to return an error, so a failed write is thrown across it and
turned back into a `Left` at the boundary. And the borrowed `frame` must never leave the loop body —
see [the borrowing contract](/video#the-borrowing-contract) for what breaks if it does.

:::tip[Measure before you drop a level]
The clone is a real cost, but it is rarely *the* cost — a detector or a network forward pass usually
dwarfs it. [Performance](/performance) shows how to find out which one you are paying, and
`LagMeter` above tells you whether you need to care at all.
:::

## Strategy 2 — drop on purpose (latest-frame-wins)

When the consumer cannot be made fast enough, the honest move is to decide *which* frames to lose
instead of letting the driver decide for you. For monitoring, detection and tracking-by-detection,
the right policy is almost always **latest-frame-wins**: keep exactly one frame — the newest — and
throw away anything the worker did not get to.

The shape is two threads and a one-slot mailbox:

- A **capture thread** does nothing but pull frames and put each one in the slot. It never blocks on
  the worker, so it always keeps pace with the camera and the driver's buffer never fills.
- A **worker thread** takes whatever is in the slot, processes it, and comes back for the newest one.
  If it took 200 ms and six frames arrived meanwhile, five of them were dropped — deliberately, at a
  known point, in favour of the freshest.

Because frames must survive being handed between threads, this is `Video.framesCopied`, not
`Video.frames`: each frame is cloned into a caller-owned `Managed[Mat]` with its own pixel buffer.
And *caller-owned* is the whole difficulty — a clone that nobody releases is native memory that no
garbage collector will reclaim.

### The slot

```scala mdoc:silent
/** A one-frame mailbox: the newest frame wins, and the loser is freed on the spot.
  *
  * Ownership moves with the frame. `offer` takes ownership from the caller; `take` gives it to the
  * caller. Whatever is displaced or left over is released here, because at that instant this slot is
  * the only thing that still knows about it.
  */
final class LatestFrame:
  private val slot = AtomicReference[Managed[Mat]](null)

  /** Publishes `frame`, releasing whatever it displaces. The caller must not touch `frame` again. */
  def offer(frame: Managed[Mat]): Unit =
    Option(slot.getAndSet(frame)).foreach(_.release())

  /** Takes the newest frame, if any. **The caller now owns it and must release it.** */
  def take(): Option[Managed[Mat]] = Option(slot.getAndSet(null))

  /** Releases anything still in the slot. Idempotent; call it once nothing can `offer` again. */
  def drain(): Unit = take().foreach(_.release())
```

`getAndSet` is a single atomic operation, which is what makes this correct without a lock: exactly
one caller ever sees a given frame, so exactly one caller is responsible for releasing it. A second
`take()` against an empty slot gets `None`, not a second reference.

It is small enough to watch work. Two frames go in; the first is displaced by the second and freed
by the writer, right there in `offer`:

```scala mdoc:silent
def blankFrame(): Managed[Mat] = Managed(Mat(4, 4, CvType.CV_8UC3))

val demoSlot = LatestFrame()
val frameOne = blankFrame()
val frameTwo = blankFrame()

demoSlot.offer(frameOne)
demoSlot.offer(frameTwo) // `frameOne` is displaced — and released — inside this call
```

```scala mdoc
(frameOne.isReleased, frameTwo.isReleased)
```

The worker takes the survivor, and owns it from that moment:

```scala mdoc:silent
demoSlot.take().foreach(_.release()) // in real code: `.foreach(_.use(process))`
demoSlot.drain()                     // nothing left, so this is a no-op
```

```scala mdoc
(frameTwo.isReleased, demoSlot.take().isEmpty)
```

### Who releases what, and when

This is the part to get exactly right. Every row is a transfer of ownership; a missing release at
any of them is an unbounded native leak at the frame rate of your camera.

| Moment | Who owns the clone | Who releases it |
|---|---|---|
| `frames.next()` returns a clone | the capture thread | the capture thread — it leaks the moment that thread drops its reference without either offering or releasing it |
| `offer(frame)` returns | the slot | — |
| a later `offer` displaces it | the **displacing writer** | `offer`, before it returns — this is the line that makes the whole thing leak-free |
| `take()` returns `Some(frame)` | the thread that took it | that thread — use `frame.use(...)`, which releases on the exception path too |
| the capture thread has stopped **and been joined** | whoever calls `drain()` | `drain()` |

Three ways to get it wrong, all of which compile:

- **Filtering with `takeWhile`.** `frames.takeWhile(_ => running.get)` pulls a frame, evaluates the
  predicate on it, and discards the element when the predicate is false. `framesCopied` already
  cloned it by then, so that last clone is dropped without being released. Use a `while` loop that
  checks the flag *before* pulling.
- **Draining too early.** Calling `drain()` while the capture thread can still `offer` frees a frame
  the writer may be about to displace, and the frame offered after the drain is never freed at all.
  Stop the writer, join it, then drain.
- **Losing a taken frame.** A worker that takes a frame and then throws before releasing it leaks
  that frame. `Managed.use` releases in a `finally`; use it rather than a bare `get`.

### Wiring it up

```scala mdoc:silent
// The capture thread: pull, publish, repeat. It never waits for the worker.
def captureInto(source: String, newest: LatestFrame, running: AtomicBoolean): Either[CvError, Unit] =
  Camera.usingFile(source) { cam =>
    Video.framesCopied(cam.capture, attemptsPerFrame = 3) { frames =>
      // `hasNext` is what blocks in `read`. Checking `running` first means no clone is ever created
      // that the slot does not receive — which is why this is a while loop and not `takeWhile`.
      while running.get && frames.hasNext do newest.offer(frames.next())
    }
  }
```

```scala mdoc:compile-only
def process(frame: Mat): Unit = () // stand-in for the detector, the network, the tracker

// The worker: always processes the newest frame available, and drops everything it missed.
def workerLoop(newest: LatestFrame, running: AtomicBoolean): Unit =
  while running.get do
    newest.take() match
      case Some(frame) => frame.use(process) // `use` releases even if `process` throws
      case None        => Thread.sleep(5)    // nothing new yet; back off rather than spin
```

```scala mdoc:compile-only
def start(source: String, newest: LatestFrame, running: AtomicBoolean): Thread =
  val body: Runnable = () =>
    captureInto(source, newest, running).fold(e => println(e.getMessage), identity)
  val reader = Thread(body, "scalacv-capture")
  // A daemon thread cannot keep the JVM alive. That matters here: a thread parked inside a native
  // read cannot be interrupted, so a non-daemon capture thread can block process exit indefinitely.
  reader.setDaemon(true)
  reader.start()
  reader
```

The `Thread.sleep(5)` in the worker is a deliberate simplification: it costs up to 5 ms of extra
latency and a little idle CPU. To remove it, keep the `AtomicReference` — it is what gives you
latest-*wins*, where a bounded `java.util.concurrent` queue would refuse the **new** frame and keep
the stale one — and add a wake-up: the writer calls `notifyAll` on a small lock object after
`offer`, and the worker `wait`s on it with a timeout instead of sleeping. The ownership rules in the
table above do not change.

:::danger[Latest-frame-wins is wrong for some jobs]
It is the right default for "what is in front of the camera *now*" — detection, monitoring,
gesture recognition, an inference endpoint. It is wrong whenever frames are only meaningful as a
sequence:

- **Recording or re-encoding.** Dropped frames are missing footage. Use
  [strategy 1](#strategy-1--make-the-consumer-fast-enough) and accept the frame rate you can sustain.
- **Frame differencing** — [motion detection](/motion-detection), background subtraction. The
  difference between two frames 200 ms apart is not the same measurement as the difference between
  two frames 33 ms apart, and the algorithm has no way to know which it got.
- **Optical flow, odometry and trackers** that assume small motion between frames. A dropped frame
  is a large jump, which is the input these are least robust to. See [Tracking](/tracking).

If you must drop *and* the sequence matters, record the arrival timestamp with each frame and make
your algorithm take the elapsed time as a parameter, rather than assuming a fixed step.
:::

## Strategy 3 — shrink the queue you did not build

The driver's buffer is what turns "slow consumer" into "old frames". Two levers exist on it. Both
are on the raw `VideoCapture`, which `Camera` lends you through
[`cam.capture`](/video#capture--the-escape-hatch); scalacv wraps neither, because neither can be
made to behave the same way on two backends.

### `CAP_PROP_BUFFERSIZE`

OpenCV exposes the driver's queue depth as a capture property. Setting it to 1 asks the backend to
keep only the newest frame, which moves the drop policy from your code into the driver:

```scala mdoc:compile-only
// Ask the backend for a one-frame queue, and see whether it admits to having taken it.
def minimiseBuffer(cam: Camera): (Boolean, Double) =
  val accepted = cam.capture.set(Videoio.CAP_PROP_BUFFERSIZE, 1.0)
  (accepted, cam.capture.get(Videoio.CAP_PROP_BUFFERSIZE))
```

Read that snippet as a diagnostic, not a fix. Unlike the timeout properties below, this one is set
*after* the capture is open — so it goes on the borrowed capture rather than through
[`CaptureOptions`](/video#backends-and-options) — and it comes with the same honesty problem:

- It is **backend-dependent**. Some backends implement it, some ignore it, some reject it outright.
- `set` returning `true` does not mean the driver honoured it, and `get` may report the value you
  asked for, the value in force, `0`, or `-1`.
- Nothing in `CaptureInfo` reports the buffer depth, so there is no way to confirm from the API that
  it took. The only real confirmation is behavioural: the sustained-below-1.0 backlog that
  `LagMeter` reports goes away.

Try it, then measure. If the measurement does not change, you did not get it, and
[strategy 2](#strategy-2--drop-on-purpose-latest-frame-wins) is the version that works everywhere
because it does not depend on the driver's cooperation.

### Grabbing without decoding

`VideoCapture` splits a read into two halves that OpenCV exposes separately: `grab()` fetches the
next frame from the device and `retrieve(mat)` decodes and colour-converts it into a `Mat`.
`read` is the two together. Decoding and converting is the expensive half, so discarding a queued
frame with a bare `grab()` is cheaper than reading it — which gives you a way to catch up to the
head of the queue:

```scala mdoc:compile-only
/** Throws away up to `extra` queued frames without decoding them, then decodes the newest grabbed. */
def freshest(cam: Camera, extra: Int): Option[Managed[Mat]] =
  var got = cam.capture.grab() // the frame you would have read anyway
  var skipped = 0
  while got && skipped < extra do
    got = cam.capture.grab() // each extra grab discards one queued frame, undecoded
    skipped += 1
  if !got then None
  else
    val frame = Mat()
    if cam.capture.retrieve(frame) && !frame.empty() then Some(Managed(frame))
    else
      frame.release()
      None
```

The caveat is the reason this is not a general answer: **`grab` blocks exactly like `read` when the
queue is empty.** If you ask for two extra grabs and only one frame is queued, the second one waits
for the sensor — so a fixed skip count turns a source you were keeping up with into one you are
behind on by that many frame periods. It is a reasonable trick for a source you *know* you are
behind on; it is a bad idea as a permanent setting.

(Exception mode is off on a capture that came from `Video.open`/`Camera.open`, so `grab` and
`retrieve` report failure by returning `false` rather than throwing. See
[Video](/video#end-of-stream-or-a-broken-one).)

## Timeouts: what they can and cannot do

`CaptureOptions.withTimeout(5.seconds)` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC` and
`CAP_PROP_READ_TIMEOUT_MSEC`. It is worth being precise about what that buys, because it is
routinely mistaken for a latency control. It is not: it bounds how long a *hung* read blocks. It
does not make frames arrive sooner, does not drop stale ones, and does nothing whatsoever about the
backlog problem this page is about.

What it actually does, from `CaptureOptions`' own documentation:

| Fact | Consequence for you |
|---|---|
| FFmpeg and GStreamer honour the timeouts for **network** sources | This is the case they exist for: an RTSP or HTTP stream that stops delivering |
| V4L2, AVFoundation and the built-in MJPEG reader **ignore them entirely** | A wedged USB webcam blocks forever regardless of what you set |
| Nothing in the API reports which backend you got | You cannot tell from a `Right` whether your timeout is real |
| They can only be set **at open time** — `set` on a not-yet-opened capture returns `false` (measured) | They travel through `CaptureOptions`, never through `cam.capture.set` |
| Backends that do not understand them **reject the open outright**: a local `.avi` opened with the parameters attached reports `isOpened == false`, where the same file opens fine without them | `Video.open` therefore retries without the parameters rather than reporting a failure that really means "your backend has no timeout support" |
| They default to `None` | Because of the row above: paying a failed open, plus OpenCV's stderr noise, on every local file to configure something local files never need is the wrong default |

The rule that follows: **set `CaptureOptions.withTimeout(...)` for `rtsp://` and `http://` sources
only.** Leave it off for files and for local cameras, where it either does nothing or costs you a
speculative failed open.

```scala mdoc:compile-only
import scala.concurrent.duration.*

// Right: a network source, where a read that never returns is the failure mode you actually face.
val networkCam: Either[CvError, Camera] =
  Camera.openFile("rtsp://camera.local/stream", CaptureOptions.withTimeout(5.seconds))

// Wasteful: V4L2, AVFoundation and the built-in MJPEG reader ignore the read timeout, and the open
// carrying the parameters may fail outright and have to be retried without them.
val localCam: Either[CvError, Camera] =
  Camera.open(0, CaptureOptions.withTimeout(5.seconds))
```

## Shutting down a capture loop

This is the part with no good answer, so it is worth stating plainly rather than discovering it in
production.

**A `read` that is blocked in native code cannot be interrupted from the JVM.** `Thread.interrupt()`
sets a flag that a thread parked inside OpenCV's C++ never looks at. There is no cancel, no timeout
parameter on `read`, and no way to make one from Scala. So after you set your `running` flag to
false, the capture thread is still inside `read`, and it stays there until the source delivers a
frame, the connection breaks, or a backend-honoured read timeout fires.

That gives you exactly three ways to bound a shutdown, in order of reliability:

1. **A read timeout at the source** — `CaptureOptions.withTimeout`, on a backend that honours it.
   Network sources only.
2. **A daemon capture thread.** `reader.setDaemon(true)` means a thread stuck in `read` cannot stop
   the JVM from exiting. Native memory it still holds is reclaimed by the operating system when the
   process dies, which is the one moment not releasing is acceptable.
3. **Waiting with a bound** — `reader.join(5000)` — and carrying on if it times out, having decided
   in advance what you will do about a thread that never came back.

The shutdown sequence for [strategy 2](#strategy-2--drop-on-purpose-latest-frame-wins), with the
ordering that matters:

```scala mdoc:compile-only
/** Stop the writer, wait for it, and only then release what is left in the slot. */
def shutdown(reader: Thread, running: AtomicBoolean, newest: LatestFrame): Unit =
  running.set(false) // the capture loop stops pulling once its current read returns
  reader.join(5000)  // may time out: a read blocked in native code cannot be interrupted
  if reader.isAlive then println("capture thread still blocked in read; leaving it to the daemon exit")
  else newest.drain() // safe only once nothing can offer again — otherwise the last clone leaks
```

Note the `isAlive` check. Draining while the capture thread might still be inside `offer` is the
"drain too early" leak from the ownership table; if the thread has not come back, the safe thing is
to leave the slot alone and let process exit reclaim it.

:::danger[Do not close the capture from another thread to unblock the read]
It looks like the escape hatch: call `cam.close()` from the shutdown thread and let the blocked
`read` fail. Do not. A `VideoCapture` is a native object with **one owner**, and releasing it while a
decode is in flight is a data race in C++ — which means a segmentation fault, not an exception: no
stack trace, no catch, a dead process. See [Concurrency](/concurrency#what-is-safe-to-share). The
capture must be closed by the thread that reads it, after the read has returned — which is exactly
what `Camera.usingFile`'s `finally` does for you inside `captureInto`.
:::

### What ZIO does and does not change

The [ZIO module](/zio) is the only path in the library that even attempts interruption:
`frameStream` wraps its read in `ZIO.attemptBlockingInterrupt`. It is worth being clear about what
that buys, because it is less than it sounds:

- **It does buy you** a blocked read parked on ZIO's *blocking* pool instead of the CPU-sized compute
  executor, so a wedged camera cannot starve the fibers doing your real work; and a `Scope` that
  releases the buffer, the capture and the exception-mode restore on failure and interruption as
  well as success.
- **It does not buy you** cancellation. `attemptBlockingInterrupt` delivers a JVM
  `Thread.interrupt()`, and a thread inside OpenCV's native read never observes one. Interrupting
  the stream, or closing the scope around it, takes effect only once the in-flight read returns by
  itself — the module's own documentation says so.

The honest summary is that nothing anywhere cancels a blocked `read`. Bound it at the source, or
plan for a thread that may not come back.

## Choosing a strategy

| Situation | Strategy | What you give up |
|---|---|---|
| Source is a file | None needed — a pull loop backpressures a file perfectly | Nothing |
| Live source, loop is close to budget | [1 — do less per frame / skip the copy](#strategy-1--make-the-consumer-fast-enough) | Detection of the smallest objects, if you downscale |
| Live source, loop is far over budget, freshness matters | [2 — latest-frame-wins](#strategy-2--drop-on-purpose-latest-frame-wins) | Frames, deliberately — and any algorithm that needs a fixed time step |
| Live source, must not drop a frame | 1 only, then accept a lower sustainable frame rate | Frame rate |
| Frames are old but the loop is fast | [3 — shrink the driver queue](#strategy-3--shrink-the-queue-you-did-not-build) | Portability: it is backend-dependent and unverifiable |
| Source can hang | [Timeouts](#timeouts-what-they-can-and-cannot-do), network sources only | Nothing, if the backend honours them |

And the one thing to do before any of them: [measure](#measure-the-lag-before-you-tune-it). A ratio
of 1.02 needs no architecture.

## Next

- [Video & the camera](/video) — the frame loops, the borrowing contract and `CaptureOptions` in full.
- [Concurrency](/concurrency) — why a capture has exactly one owner, and what is safe to send between threads.
- [Observability](/observability) — where to put the lag gauge, and what else is worth instrumenting.
- [Degradation and error budgets](/degradation-and-error-budgets) — what to do when the camera does not come back.
- [ZIO](/zio) — the same loops as a `ZStream`, with `Scope` handling acquisition and interruption.
