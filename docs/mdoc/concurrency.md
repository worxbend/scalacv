# Concurrency & thread safety

Native handles are C++ objects behind a thin Java wrapper. Sharing one across threads without care is a data race in C++, not just in the JVM — and that means a **segfault**, not a caught exception: no stack trace, no test failure, just a dead process. The good news is that the rule for staying safe is short, and most parallel image work fits it naturally. This page says exactly what is safe to share, what isn't, and how to parallelise correctly.

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*

OpenCv.load()
```

## The rule in one line

**One owner per handle.** A `Mat`, a `Managed`, an `Image`, a detector, a `VideoCapture` — keep it on one thread, or guard it with your own lock. scalacv does **not** use JavaCPP `PointerScope`, so there is no thread-local-scope subtlety to reason about: ownership is always the explicit `Managed` you hold, wherever it travels.

The reason this rule is livable is that **detector results are plain, immutable Scala data**, copied out of native memory. So the natural pattern — do the native work on a worker, hand the *result* back — needs no synchronisation at all. A count, a `Seq[Rect]`, a `Pose`: once computed, it belongs to no thread and to every thread.

```scala mdoc:silent
// The native work happens on one owner; the RESULT is a plain Int, safe to send anywhere.
val edgeCount: Int =
  val edges = Image.blank(64, 64, Scalar.White).gray.canny(50, 150)
  try edges.contours().size
  finally edges.close()
```

```scala mdoc
edgeCount >= 0
```

## What is safe to share

| Thing | Concurrent use | Why |
|---|---|---|
| **Immutable results** — `Contour`, `Rect`, `Scalar`, `Pose`, `FaceMatch`, any `Seq[…]` a detector returns | ✅ fully safe | plain Scala data, copied out of native memory, no pointer behind them |
| **`Managed` release** — `release()`/`close()` from several threads | ✅ safe | idempotent atomic compare-and-set; frees exactly once even under a race |
| **A shared `Mat`'s pixels — reads only** | ✅ safe | concurrent reads of a buffer nobody writes are fine |
| **`OpenCv.load()`** | ✅ safe | idempotent, internally synchronised; call it from anywhere |
| **A shared `Mat` with a concurrent write** | ❌ **unsafe** | OpenCV's refcount ops are atomic, but pixel writes are not synchronised |
| **A stateful detector** — `MotionDetector`, `Odometry`, `ObjectTracker`, `LoopDetector` | ❌ **unsafe** | mutable internal state; documented single-thread |
| **`FaceDetectorYN`, `FaceRecognizer`, a `Dnn` net, a `Tracker`** | ❌ one per thread | stateful native objects; a second thread corrupts the in-flight call |
| **`VideoCapture` / `Camera` / `Recorder`** | ❌ one owner | a decode/encode in progress is not reentrant |

Two rows carry most of the weight:

- The **safe** results row is why the "detect on a worker, combine on the main thread" pattern below needs no locks.
- The **`Managed` release** row is subtler than it looks: `release()` is a `getAndSet(null)` compare-and-set, so even if two threads race to close the same handle, the buffer is freed **exactly once** and the loser is a no-op. You still shouldn't *use* a handle from two threads — but you can't double-free one. (See [Mat lifecycle](/mat-lifecycle).)

:::danger[A write during a read is still a race]
"Reads are safe" means *concurrent reads of a buffer nobody writes.* The moment one thread writes a `Mat` another is reading, you are back to undefined behaviour. When in doubt, give each thread its own buffer.
:::

## The pattern: one owner per worker

The stateful native objects aren't thread-safe, but they're cheap to hold per thread. Give each worker its own end-to-end pipeline, process independently, and collect the immutable results. Here each future reads, processes, and closes its own `Image`, returning only a plain `Int`:

```scala mdoc:compile-only
import scala.concurrent.{Future, ExecutionContext}
import ExecutionContext.Implicits.global

def edgeCountsInParallel(paths: Seq[String]): Future[Seq[Int]] =
  Future.sequence(paths.map { path =>
    Future {
      // Each worker owns its Image end to end; `reading` closes it on every path,
      // and the returned Int is safe to combine on any thread.
      Image.reading(path)(_.gray.canny(80, 160).contours().size).getOrElse(0)
    }
  })
```

`Image.reading` scopes the image to the block — success, failure, or exception — so nothing native escapes the worker. Nothing crosses a thread boundary but `Int`s.

### Sharing an expensive detector: pool, don't share

A loaded `Dnn` net or a `FaceRecognizer` is expensive to build. Reuse **one per bounded worker**, with the worker's whole synchronous processing loop inside its ownership scope:

```scala mdoc:compile-only
import org.opencv.dnn.Net

// `work` runs the worker's loop, not just one request. It must finish all native work before returning.
def withWorkerNet[A](modelPath: String)(work: Net => A): Either[CvError, A] =
  Dnn.fromOnnx(modelPath).map(_.use(work))
```

The application chooses the worker bound and admission/backpressure policy. Every worker owns and closes its own net; do not return a lazy effect or launch work that outlives the callback. An unbounded `ThreadLocal` is not a resource pool, and a migrating fiber cannot use it to establish exclusive ownership.

A model revision creates new workers with the new model. Stop admitting work to old workers, let their in-flight calls finish, then close their scopes. Each application instance does this independently. Never close a cached net merely because a TTL elapsed while inference is still running.

The rule of thumb: **share immutable results, not concurrently used handles.** An explicit exclusive lease or lock can serialize a shared net, but it must cover `setInput`, `forward`, output copying, and shutdown—not just lookup.

## Splitting work over one image

To process regions of a single image concurrently, give each worker its **own** buffer to write into — never let two threads write the same `Mat`. The cleanest approach is per-worker crops, which are independent **copies** (not aliasing views), processed in isolation, then composited back on one thread:

```scala mdoc:silent
// crop returns an INDEPENDENT copy, so two workers holding two crops share no buffer.
val source = Image.blank(256, 256)
val topLeft = source.copy.crop(Rect(0, 0, 128, 128))
val topRight = source.crop(Rect(128, 0, 128, 128)) // consumes `source`
// Hand each crop to its own worker; they write disjoint buffers with no lock.
topLeft.close()
topRight.close()
```

Note the `.copy` on the first crop: `crop` consumes its receiver (it returns a fresh buffer and releases the parent), so to take two crops from one source you branch with `.copy` first. Each resulting crop owns its own `Mat`.

Reads are different: many threads may *read* one shared `Mat` at once — measuring, hashing, encoding — with no lock, as long as nobody writes it concurrently. That is the one place shared native memory is fine.

## Common mistakes

| You wrote… | What happens | Fix |
|---|---|---|
| shared one `Dnn`/detector across futures | in-flight native state races | one per bounded worker, closed with its scope |
| collected borrowed `Video.frames` Mats into a `Seq` and processed off-thread | N references to one reused buffer; a data race the moment decode continues | `Video.framesCopied` for frames that outlive the loop |
| two workers `crop` the same `Image` | second `crop` throws use-after-move (the first consumed it) | `.copy` before the first crop |
| two threads write the same `Mat` | undefined behaviour | give each its own buffer |
| passed a detector *result* between threads and added a lock | harmless, but pointless | results are immutable — no lock needed |

## Thread-pool oversubscription

OpenCV and OpenBLAS each run an internal thread pool. If your outer parallelism already saturates the cores, the inner pools fight it and everything slows down. When you fan work out yourself, cap the inner layers:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

or `org.opencv.core.Core.setNumThreads(1)` from code before you spread work. Conversely, for a single sequential pipeline, leave OpenCV's threading on — it parallelises the heavy kernels for you. Measure both; see [Performance](/performance#thread-pool-oversubscription).

## ZIO: fibers are just threads here

The [ZIO](/zio) module expresses ownership as `Scope`, so a native object is released when the scope closes — on success, failure, **and interruption**, which `try`/`finally` cannot promise once an interrupt is in play. Native and blocking work runs on the blocking pool, never the CPU-sized default executor.

The two **borrowing** contracts carry over unchanged from the synchronous world:

- `frameStream` borrows one reused buffer — reduce each frame *inside* the stream (`.mapZIO(...)`), don't buffer the borrowed `Mat` across stages.
- `framesCopied` is a low-level stream of caller-owned `Managed[Mat]` clones. Filtering, buffering or interruption before adoption can abandon them; even immediate downstream `useZIO` cannot repair that earlier handoff. Prefer `processFrames`, which acquires each clone inside an effect-aware bracket and releases it before emitting a reduced result. Never return a lazy effect from synchronous `Managed.use`.

Both synchronous and ZIO readers use the same `Video.FrameSource` and accept an `attemptsPerFrame` bound. `Video.frames` and `frameStream` default to `1`; the `Camera` helpers default to `3`. After that many consecutive unsuccessful reads, the stream ends cleanly: OpenCV does not distinguish ordinary EOF from every disconnected-source failure. See [A dropped frame ends your stream](/zio#dropped-frame). JVM interruption cannot forcibly stop a JNI read; timeout support remains backend-dependent.

:::tip[Fibers do not change the rule]
"One owner per handle" is about the *handle*, not the concurrency primitive. A ZIO fiber is still a thread as far as native memory is concerned — a native object still belongs to exactly one fiber at a time, and `Scope` is how you make that ownership survive interruption.
:::

## Next

- Why release is safe under a race, and the borrowing contract in full: [Mat lifecycle](/mat-lifecycle).
- Effect-based resource scoping and stream contracts: [ZIO integration](/zio).
- Capping inner thread pools, and measuring what parallelism actually buys: [Performance](/performance).
