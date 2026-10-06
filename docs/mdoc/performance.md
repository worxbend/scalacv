# Performance

scalacv's speed story is mostly about **not allocating** — the wins come from the ownership model, not from clever inner loops. A native pixel buffer is expensive to create and expensive to free, so the fastest program is the one that creates the fewest. This page collects, from the ground up: the allocation model that keeps one buffer live per pipeline, the zero-copy paths for video, the AWT interop fast lanes, the benchmark harness that gates every perf claim, how to measure native memory without fooling yourself, and how to stop OpenCV's own thread pool from fighting yours.

```scala mdoc:silent
import scalacv.*
import org.opencv.core.{CvType, Mat}

OpenCv.load()
```

:::tip[The whole page in one sentence]
Prefer a **move-chain** (`img.gray.blur(2).canny(...)`) over building a `Seq[Image]`, prefer a **borrowed frame** (`Video.frames`) over a copied one when you only read it, cap OpenCV's inner thread pool when you fan out your own, and never trust `Pointer.totalBytes()` to see a leak.
:::

## The allocation model: one live Mat per chain

Every `Image` transform *consumes* its receiver (move semantics — see [Mat lifecycle](/mat-lifecycle)), so a pipeline of any length holds exactly **one** live `Mat` at a time: each step frees or hands on the previous buffer as it produces the next. There is no pile of intermediates for the GC to eventually notice, and — because native memory is off-heap — nothing the GC would even be in a hurry about.

```scala mdoc:silent
// gray, blurred, edges — three logical images, but never three live Mats at once.
val bytes = Image.blank(640, 480, Scalar.White).gray.blur(2).canny(80, 160).bytes(".png")
```

The chain above allocates a fresh buffer at each step and frees the one before it the instant it is no longer needed. To *prove* to yourself that the terminal really did run and release, compute a primitive from it:

```scala mdoc:silent
val pngSize: Int =
  Image.blank(64, 64, Scalar.White).gray.blur(1).canny(50, 150).bytes(".png").map(_.length).getOrElse(0)
```

```scala mdoc
pngSize > 0
```

### The one place you pay: branching with `.copy`

A chain is linear — but sometimes you need two results from one source (edges *and* a blur, say). Reusing a consumed `Image` throws; the deliberate cost is one clone via `.copy`, which borrows the receiver (does **not** consume it) and hands back an independent buffer:

```scala mdoc:silent
val original = Image.blank(128, 128, Scalar.White)
val edges = original.copy.gray.canny(50, 150) // .copy clones; `original` is still alive
val blurred = original.blur(3)                 // now `original` is consumed
edges.close()
blurred.close()
```

That is the only allocation on this path you asked for by name. **Rule of thumb:** if you find yourself calling `.copy` in a loop, or building a `Seq[Image]` of intermediates, ask whether a move-chain or a borrowed frame would do the same work with one buffer.

### The mid-level equivalent: `pipe` and `Mats.chain`

At the mid-level, the same one-buffer guarantee comes from [`pipe`](/low-level) / `Mats.chain`. Each mid-level op (`cvtColor`, `gaussianBlur`, `canny`, …) allocates a **fresh** destination and hands back a `Managed[Mat]` you own — so a chain of them produces one owned Mat per stage. Writing the chain naively strands the intermediates:

```scala
// WRONG: the blur output is freed by `use`, but `canny` returns a NEW Mat that
// outlives its own Managed — and nobody released the blur's parent handle cleanly.
val edges = src.gaussianBlur(Size(5, 5), 1.5).use(_.canny(50, 150))
```

`pipe` feeds the intermediate to the next stage and releases it the moment that stage has produced its own output, so nothing strands and nothing can be used after the chain moves on. `Mats.chain` is the n-stage form — a fold of `pipe` that reads as a list:

```scala mdoc:silent
// Each intermediate is freed as the next stage consumes it; only the final Mat survives.
val chained: Managed[Mat] =
  Mats.chain(Mat(64, 64, CvType.CV_8UC3))(
    _.cvtColor(ColorConversion.BgrToGray),
    _.gaussianBlur(Size(5, 5), 1.5),
    _.canny(50, 150)
  )
chained.release()
```

Note `Mats.chain` **borrows** its source (never releases it — it belongs to whoever created it) and releases every intermediate it allocates, including on the exception path. You own only the final result.

## Zero-copy: borrow frames instead of copying them

Video is where copies add up — a 1080p BGR frame is ~6 MB, so 30 fps is **~180 MB/s** of allocation if you copy every frame. scalacv gives you a copying path and a zero-copy path, named so you choose deliberately:

| API | What you get | Cost per frame | Reach for it when |
|---|---|---|---|
| [`Camera.foreach`](/video) | owned `Image`, closed for you | one clone | you transform / detect / annotate the frame like any `Image` |
| [`Camera.snapshot` / `take` / `taking`](/video) | owned `Image`(s) | one clone each | you want a handful of frames as first-class images |
| [`Video.frames`](/video) | **borrowed** reused `Mat` | **zero** | you only read/reduce the frame, or run mid-level `Ops` over it |
| [`Video.framesCopied`](/video) | owned `Managed[Mat]` per pulled frame | one clone per frame you pull | you must keep specific frames past their turn |

`Video.frames` decodes into a **single reused buffer** — the same `Mat`, refilled in place — so per-frame native-buffer allocation is zero no matter how long the video runs (this is also why the frame source is an `Iterator`, not a memoising `LazyList`; see the [Video](/video) rationale). Mid-level ops allocate their own destination and never alias the receiver, so running them over a borrowed frame is correct and yields a Mat *you* own:

```scala mdoc:compile-only
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    // The decode buffer is reused; cvtColor owns a separate destination, released by use.
    Video.frames(c) { frames =>
      frames.map(frame => frame.mat.cvtColor(ColorConversion.BgrToGray).use(_.rows)).sum
    }
  }
}
```

The trade is the borrowing contract: don't retain a borrowed frame past its turn, and don't feed it to an `Iterator` combinator that retains (`toList`, `sliding`, `buffered` retain views that throw once spent). See [Mat lifecycle](/mat-lifecycle).

:::tip[Record the zero-copy frame with no extra clone]
`Recorder.write` has a `Mat` overload, so a borrowed frame from `Video.frames` can be written straight through without the per-frame `Image` clone. Pay the copy only where you genuinely branch.
:::

When you *do* need to keep frames, `Video.framesCopied` clones per frame — but lazily, as you pull, so frames you never reach are never copied. You pay the copy only for the frames you keep, and each is yours to release.

## The `toBufferedImage` / `toMat` fast paths

AWT interop copies pixels once, straight into (or out of) the target's backing array — no intermediate `byte[]`, no defensive clone — when the raster is contiguous and already BGR. A `TYPE_3BYTE_BGR` `BufferedImage` round-trips through `toMat` / `toBufferedImage` on the fast path; other layouts fall back to a single normalising redraw. The upshot: the common notebook / `ImageIO` case is one bulk copy, not several. See [Image I/O](/image-io) for the round-trip, and [Notebooks](/notebooks) for the display path this feeds.

## The benchmark harness

Perf claims in this repo are gated by a rule: **no optimization without a measured delta and a bit-identical output hash.** The `benchmarks` module is a JNI-aware harness — deliberately not JMH, whose annotation processor is awkward under Mill 1.1.7 + natives — that warms the JIT to steady state, measures many iterations, reports mean ± a 95% confidence half-width so a delta can be judged against noise, and folds every result through a `blackhole` so the JIT cannot dead-code-eliminate the work.

```sh
./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench   # a wasted clone in Motion.prepare
./mill benchmarks.runMain scalacv.bench.ArenaReuseBench      # per-frame destination allocation
./mill benchmarks.runMain scalacv.bench.ToMatBench           # BufferedImage -> Mat fast path
./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench # Mat -> BufferedImage fast path
./mill benchmarks.runMain scalacv.bench.GraphicsAlphaBench   # translucent-shape blend overhead
./mill benchmarks.runMain scalacv.bench.PictureBoundsBench   # scene-graph layout cost vs shape count
./mill benchmarks.runMain scalacv.bench.ConfigProbeBench     # how a heavy op scales with setNumThreads
```

| Benchmark | What it isolates | The question it answers |
|---|---|---|
| `GrayBlurCloneBench` | a clone that was freed unused in the already-grey blur branch | did removing the clone actually help? |
| `ArenaReuseBench` | fresh destination Mat per stage vs a reused arena | how much is per-frame allocation costing? |
| `ToMatBench` / `ToBufferedImageBench` | the AWT fast path vs the normalising fallback | is the contiguous-BGR shortcut worth its branch? |
| `GraphicsAlphaBench` | `Graphics.alpha` cloning the whole Mat regardless of shape size | does the alpha tax scale with image size (waste) or shape size? |
| `PictureBoundsBench` | repeated `beside` recomputing `bounds` — O(n²) layout | is quadratic layout slow at realistic N, or "cold"? |

`BenchImages.hash` is the pixel-exact regression key: an optimization must produce a **bit-identical** hash, so a "faster" change that quietly alters output fails the gate. Absolute microseconds are machine-specific — the **deltas** reproduce.

## Measuring memory (do it right)

scalacv wraps the official `org.opencv.core.Mat` JNI API, whose pixel buffers are allocated by OpenCV's own `cv::fastMalloc` — **outside** JavaCPP's `Pointer` accounting. Two consequences trip people up:

| Accounting | Sees scalacv Mats? | Verdict |
|---|---|---|
| `Pointer.totalBytes()` and `-Dorg.bytedeco.javacpp.maxBytes` | ❌ blind | a Mat leak runs RSS to the moon while this reads flat — **don't gate on it** |
| `Pointer.physicalBytes()` and `-Dorg.bytedeco.javacpp.maxPhysicalBytes` | ✅ reads process RSS | **this is the ceiling to set** |

```sh
# maxPhysicalBytes (RSS-based) catches a scalacv Mat leak; maxBytes alone would not.
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

For a regression gate, snapshot RSS (`/proc/self/statm` on Linux, or `Pointer.physicalBytes()`) before and after N iterations of a workload, settle with `System.gc()` + `Pointer.deallocateReferences()`, and assert **bounded** growth — never zero, since arenas and the JIT code cache never fully return:

```scala
// Sketch of an RSS-based leak gate (see /testing for the real harness).
val before = org.bytedeco.javacpp.Pointer.physicalBytes()
(1 to 500).foreach(_ => Image.reading("frame.png")(_.gray.canny(80, 160).contours().size))
System.gc(); org.bytedeco.javacpp.Pointer.deallocateReferences()
val grew = org.bytedeco.javacpp.Pointer.physicalBytes() - before
assert(grew < 64L * 1024 * 1024, s"RSS grew $grew bytes — suspect a Mat leak")
```

The clean high-level pipeline is flat under this test (each `reading` closes its image); a per-iteration leak clears any sane bound within a few hundred iterations. See [Mat lifecycle](/mat-lifecycle#verify-you-arent-leaking) and [Testing](/testing).

## Thread-pool oversubscription

OpenCV runs its own internal thread pool, and OpenBLAS another. If you fan pipelines out across your own threads (see [Concurrency](/concurrency)), the two layers can oversubscribe the cores and slow *everything* down — every kernel spawns workers that then contend with your own parallelism for the same physical cores. Cap the inner pools so your outer parallelism owns the cores:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

or from code, `org.opencv.core.Core.setNumThreads(1)` before you spread work across your own executor.

:::note[Measure both ways]
For a **single sequential** pipeline, OpenCV's internal threading is usually the faster default — it parallelises the heavy kernels (bilateral filter, DNN, resize) for you. Only cap it when *you* are the one saturating the cores. `ConfigProbeBench` is the evidence: it measures how a heavy op scales with `setNumThreads` on your hardware.
:::

## A quick checklist

| If you… | Do this instead | Because |
|---|---|---|
| build a `Seq[Image]` of pipeline stages | chain the transforms | one live Mat, not N |
| `.copy` inside a hot loop | branch once, outside the loop | each copy is a full-frame allocation |
| copy every video frame you only read | `Video.frames` (borrowed) | zero per-frame allocation |
| watch `Pointer.totalBytes()` for leaks | set `maxPhysicalBytes`, snapshot RSS | `totalBytes()` can't see `fastMalloc` |
| fan pipelines across threads | cap `OMP`/`OPENBLAS`/`setNumThreads` | inner + outer pools oversubscribe |
| claim a speedup | show a bench delta + identical `BenchImages.hash` | micros are machine-specific; deltas reproduce |

## Next

- The lifetimes behind these wins: [Mat lifecycle](/mat-lifecycle).
- Sharing work across threads safely: [Concurrency & thread safety](/concurrency).
- Proving the gate holds: [Testing](/testing).
