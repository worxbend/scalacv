# Mat lifecycle — why scalacv exists

If you have ever written OpenCV in Java and watched a long-running service quietly balloon to gigabytes
of memory while the JVM heap sat calm and small, this page is the reason scalacv exists. OpenCV's pixel
data lives *off-heap*, where the garbage collector cannot see it clearly — so "just let the GC handle it"
does not work. scalacv's answer is a single ownership type, `Managed`, and one short rule about who frees
what. Read the [problem](#the-problem) once, learn the [cheat sheet](#the-cheat-sheet), and the rest of
the library follows from it.

:::tip[New here?]
The one thing to internalise: a scalacv **`Image` has move semantics** — a transform like `gray` or `blur`
*consumes* the image and hands you a new one. Take `.copy` first if you need the original again. Everything
else on this page elaborates that idea.
:::

## Scope and escape boundaries

`Managed.use` and callback-scoped image helpers are synchronous brackets, not effect interpreters.
Returning a lazy `ZIO`, `Future`-starting closure, iterator or raw handle does not extend their
lifetime. Guards protect access through the owner, not previously extracted JNI objects, and do
not make concurrent access versus close safe. Independent low-level allocations and ownership
transfers still require cleanup. A thrown cleanup error must not hide a body failure; ordinary
nonfatal cleanup failures are suppressed on the primary error.

The current ownership fixes extend scope cleanup to consuming image descendants and copies.
Within `Image.reading`, `Camera.foreach`/`taking`, animation callbacks and ZIO `imageScoped`,
consuming transforms move the registration to the live successor; `.copy` creates another scoped
branch. `.managed` preserves both that scope and its custom release strategy (including through
`pipe`); it is not an escape. To return an independently owned image, use `.detach` explicitly
and close the returned value yourself. Other branches remain scoped. Raw Mat operations do not
magically register unrelated allocations.

That behavior is newer than the 0.4.1 source tag; do not treat the old tag's cleanup of the original
wrapper as proof that a transformed successor is released. Finish manually owned pipelines with
`close` or a consuming terminal, and use the current implementation for descendant-scoped cleanup.

## The problem

An OpenCV `Mat` holds megabytes of pixel data off-heap, behind about forty bytes of Java object
on-heap. The garbage collector only runs under **heap** pressure, and heap pressure is
uncorrelated with **native** pressure. So a loop that allocates Mats and drops them exhausts native
memory — or trips the cgroup limit and gets OOM-killed — while the heap stays small and no
collection ever runs.

Measured on this project's own test machine: 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped,
no explicit `System.gc()`.

| | final RSS |
|---|---|
| unreleased | **5 865 MB** |
| `release()` | **144 MB** |

The same 41× on JDK 21 and JDK 25. It is not that reclamation is impossible — it is that nothing
makes it happen in time. And the mechanism that *would* eventually help, `finalize()`, is deprecated
for removal.

It is worse for detectors. Of the 188 `org.opencv.*` types that own native memory, exactly **three**
expose a public `release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `ArucoDetector` and 181
others do not — and each carries a finalizer that would free its pointer a second time if you freed
it yourself naively.

## The answer: `Managed`

`Managed[A]` owns a native object and releases it exactly once. Prefer the scoped form:

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*
import org.opencv.core.{CvType, Mat}

OpenCv.load()

val rows = Managed.use(Mat(1080, 1920, CvType.CV_8UC3)) { m =>
  m.rows
}
```

```scala mdoc
rows
```

After the synchronous block, the Mat is released on success or exception. Later access **through
the Managed owner** throws `IllegalStateException`. A raw Mat captured inside the block bypasses
that check: do not retain it, race it with close, or return a lazy effect that uses it later:

```scala mdoc:crash
val leaked = Managed(Mat(8, 8, CvType.CV_8UC1))
leaked.release()
leaked.get // throws: already released
```

`Managed` is `AutoCloseable`, so the whole of `scala.util.Using` already accepts it too. And because
release is a compare-and-set, calling `close()` twice (say, once explicitly and once from a scope) is a
harmless no-op, never a double free.

### Several handles at once: `Managed.scope`

`Managed.use` scopes one object. Some calls need a handful — OpenCV's `solvePnP` wants object points,
image points, a camera matrix, distortion coefficients and two output vectors, and every one of those is
a native object somebody has to free. Nesting six `use` blocks works but buries the actual call under
six levels of indentation, and the obvious alternative is worse than it looks: declaring the six as
`val`s and releasing them in a `try`/`finally` leaves every allocation *before* the `try` unguarded, so a
constructor that throws part-way strands the objects already built.

`Managed.scope` gives you a block that owns all of them. The `own` it hands you registers an object and
returns it, so a scoped handle reads as an ordinary binding:

```scala mdoc:silent
import org.opencv.core.Core

val brightest = Managed.scope { own =>
  val src   = own(Mat(64, 64, CvType.CV_8UC1, org.opencv.core.Scalar(7)))
  val blurb = own(Mat())
  Core.add(src, src, blurb)
  Core.minMaxLoc(blurb).maxVal      // plain data — safe to return
}
```

```scala mdoc
brightest
```

Because each object is registered the moment it is created, a throw anywhere — a later constructor, the
native call, the decode afterwards — releases everything acquired so far, in reverse order. A failure
raised *while* releasing is attached to the original error as a suppressed exception rather than
replacing it.

`own.adopt` is the same thing for a value that arrives already wrapped, which is what every mid-level
`Ops` call hands back:

```scala mdoc:silent
val edgePixels = Managed.scope { own =>
  val src  = own(Mat(64, 64, CvType.CV_8UC3, org.opencv.core.Scalar(0, 0, 0)))
  val gray = own.adopt(src.cvtColor(ColorConversion.BgrToGray))
  val edges = own.adopt(gray.canny(80, 160))
  Core.countNonZero(edges)
}
```

The rule is the one `use` already carries, applied to a group: **nothing acquired inside the block may
escape it**. Return plain data (a number, a `Seq[Double]`, a case class), or an object owned somewhere
else — never one of the scoped handles.

### The two release regimes

Which mechanism frees a handle is not a style choice — it is dictated by what the generated Java
binding exposes, and `Managed` hides the difference behind one API.

| Native type | How it frees | Notes |
|---|---|---|
| `Mat`, `VideoCapture`, `VideoWriter` | public `release()` | The only three with a public free. `Mat.release()` drops the multi-megabyte pixel **buffer** at once. |
| the other 185 (`CascadeClassifier`, `Net`, `ArucoDetector`, `KalmanFilter`, …) | a cached `MethodHandle` onto the binding's private `delete(long)`, after **disarming** its finalizer | Opt-in and loud: if the bridge cannot be opened it throws rather than leaking silently. |

You never pick the regime; `Managed.use(...)` (and the `Cascades` / `Dnn` helpers that return `Managed`
for you) does. See [`object-detection`](/object-detection) and [`dnn`](/dnn) for the detector helpers.

## The cheat sheet

Every rule below is derived from one idea — *exactly one owner frees exactly once* — but it helps to
have them in a table:

| You have | It is | Who frees it |
|---|---|---|
| a `Managed[A]` a scalacv call returned | **owned** by you | you: `.use`, `.close()`, or a scope |
| an `Image` a factory returned (`read`, `blank`, `decode`) | **owned** by you | you (or a transform consumes it — see below) |
| an `Image`/`Mat` after a transform (`gray`, `blur`, `canny`, `resize`, …) | the **receiver was consumed**; you own the **result** | the result is yours; the receiver is already spent |
| a `Mat` from `Video.frames` | **borrowed** — one reused buffer | the loop; do not retain it |
| a `mask` you pass to `applyMask`/`inpaint`/`blend`/`seamlessCloneInto` | **borrowed** by the call | you — close it yourself; the receiver *is* consumed |
| a `Managed[Mat]` from `Video.framesCopied` | an **owned** copy per frame | you — `.release()` each, or take them into a `Using.Manager` |
| a `Seq[Image]` from `Camera.take(n)` | **n owned images** in a plain collection | you — `.close()` every one; prefer `Camera.taking(n)(use)` |

`Camera.take(count)` is the one call that hands you several live handles at once, in a type —
`Seq[Image]` — that cannot warn you about it. `Camera.taking(count)(use)` grabs the same frames, runs
your body over them, and closes all of them afterwards: on success, on failure, and on exception.
Reach for `taking` unless you specifically need the frames to outlive a scope.

```scala mdoc:compile-only
import scalacv.*

// `taking` closes every frame for you. `take` would hand you three live Images to close yourself.
Camera.using(0) { cam =>
  cam.taking(3) { frames => frames.map(_.width).sum }
}
```

:::note[Queries borrow, transforms and terminals consume]
A **query** (`width`, `height`, `channels`, `contours`, `isEmpty`) *borrows* the image — it stays alive
afterwards. A **transform** (`gray`, `blur`, `canny`, `crop`, `draw*`, …) *consumes* it and returns a new
one. A **terminal** (`write`, `bytes`, `close`) consumes it and produces no new image.
:::

## Move semantics on `Image`, concretely

A transform threads the one live Mat forward: the receiver is spent, the result is yours. So a chain
just reads left to right, and only the final handle needs closing:

```scala mdoc:silent
val moveSrc   = Image.blank(64, 64)      // owned
val moveEdges = moveSrc.gray.canny(50, 150) // gray consumes moveSrc; canny consumes the gray result
moveEdges.close()                        // close only the survivor
```

Queries, by contrast, leave the image alive — read as many as you like, then close once:

```scala mdoc:silent
val queryImg = Image.blank(100, 50)
val qw = queryImg.width   // borrows
val qh = queryImg.height  // still alive
queryImg.close()
```

```scala mdoc
s"${qw}x$qh"
```

The mistake the type system catches for you is *reusing a consumed handle*. It throws at the reuse,
in Scala, instead of segfaulting in C++:

```scala mdoc:crash
val consumed = Image.blank(8, 8)
val gray = consumed.gray  // .gray moved the Mat out of `consumed`
gray.close()
consumed.width            // throws IllegalStateException — `consumed` was spent by .gray
```

:::tip[Diagnosing use-after-move]
The `IllegalStateException` fires at the *reuse* line, which is rarely the interesting one. Start the JVM
with `-Dscalacv.trackOwnership=true` and the exception carries, as its cause, the stack of the transform
that actually spent the handle. It is off by default because it allocates a `Throwable` on every consume;
the read only happens on the already-failing path, so a correct program pays nothing.
:::

### Branch with `.copy`

Need the same source twice? `copy` clones the pixel buffer, so the two handles are independent — one
transform cannot invalidate the other:

```scala mdoc:silent
val original = Image.blank(32, 32, Scalar.White)
val blurred  = original.copy.blur(2) // work on a clone…
val grayed   = original.gray         // …then consume the original
blurred.close()
grayed.close()
```

### Masks are borrowed, receivers are consumed

The domain operations that take a second image — `applyMask`, `blend`, `inpaint`, `seamlessCloneInto`,
`blurBackground` — **borrow** that second image and **consume** the receiver. So you close the mask
yourself, and you must not reuse the receiver:

```scala mdoc:silent
val maskPhoto = Image.blank(64, 64, Scalar.White)
val maskImg   = Image.blank(64, 64, Scalar.White, channels = 1) // CV_8UC1 mask
val maskKept  = maskPhoto.applyMask(maskImg) // maskPhoto consumed; maskImg borrowed
maskImg.close()                              // you still own the mask — close it
maskKept.close()
```

See [`color-masking`](/color-masking) and [`graphics`](/graphics) for where these show up in practice.

## The ownership contract

**The rule, in one sentence:** you own every `Managed` a scalacv call hands back and close it exactly
once — a scope (`Managed.use`, `Managed.scope`, `Image.reading`, `Video.framesCopied`) does that for
you — with a single
exception: a `Mat` yielded by `Video.frames` is *borrowed*, owned by the loop, and must not outlive its
iteration.

Operations that produce a new Mat return it **caller-owned**, wrapped in `Managed`. They never touch
the receiver. To chain them without stranding the intermediates, use `pipe`:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Managed.use(Mat(64, 64, CvType.CV_8UC3)) { src =>
    src
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.gaussianBlur(Size(3, 3)))
      .pipe(_.canny(50, 150))
      .use(Images.encode(_, ".png"))
  }
```

Each stage's output is released as the next stage consumes it. The original `src` is never modified.

For a long pipeline, `Mats.chain` reads better than nested `pipe`s — it is a fold over `pipe`, with
identical release semantics (`src` borrowed, every intermediate freed, only the final result returned):

```scala mdoc:silent
val chained: Either[CvError, Array[Byte]] =
  Managed.use(Mat(64, 64, CvType.CV_8UC3)) { src =>
    Mats
      .chain(src)(
        _.cvtColor(ColorConversion.BgrToGray),
        _.gaussianBlur(Size(5, 5), 1.5),
        _.canny(50, 150)
      )
      .use(Images.encode(_, ".png"))
  }
```

:::note[Why not just `use`?]
`src.gaussianBlur(...).use(_.canny(...))` frees the blur output — but `use` *returns* the canny Mat,
which then outlives its own `Managed` and leaks. `pipe` exists precisely for the "feed the intermediate
forward and free it" shape; reach for `use` only at the **terminal** stage that produces a non-Mat
(a count, a `Seq[Rect]`, some bytes). See [`filters`](/filters) and [`transforms`](/transforms).
:::

### Detectors, the same way

The 185 finalizer-only types work identically — the helper hands you a `Managed`, and `use` frees it:

```scala mdoc:silent
Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { classifier =>
    classifier.empty() // a plain query on the raw CascadeClassifier; false for a loaded cascade
  }
}
```

`Cascades.load` returns `Either[CvError, Managed[CascadeClassifier]]`; the `Managed` you get is yours to
close (here, `.use` does it). The XML travels in the bytedeco jars — see [`native-cache`](/native-cache).

## The one place a Mat is borrowed, not owned: `Video.frames`

Everything above hands you *owned* Mats. There is exactly one aliasing surface in the public API, and
it is the one people get wrong: `Video.frames` streams frames through a **single reused buffer**. The
`BorrowedMat` you get each iteration is a checked view over that buffer. Keeping the view past its
turn or collecting the iterator leaves spent views that throw on access. The raw `frame.mat` escape
hatch must still not be retained or released; its lifetime is not checked after extraction.

There is no `row`/`col`/`submat` view API to trip over here — `Image.crop` returns an independent
copy, not a view. This borrowed frame is the only alias you have to reason about.

:::danger[Use-after-free]
`frames` yields **BorrowedMat** views over one buffer, refilled each step. Collecting the iterator
keeps spent views: accessing them after the traversal throws `IllegalStateException`.

```scala mdoc:compile-only
import scalacv.*

// WRONG — .toList collects spent views; accessing any of them after the block throws.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c) { it => it.toList }
  }
}
```
:::

:::tip[Right]
```scala mdoc:compile-only
import scalacv.*

// RIGHT (a) — consume each frame fully inside the loop, before the next iteration overwrites it.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c) { it => it.map(_.rows).sum }
  }
}

// RIGHT (b) — to keep frames past their turn, ask for owned copies: each is a `Managed[Mat]` you own
// (and close). `framesCopied` clones per frame so there is nothing aliased to get wrong.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.framesCopied(c) { it => it.take(10).toList } // owned copies; close them when done
  }
}
```
:::

The `Ops` extensions are safe to run *inside* the loop even on the borrowed frame: each allocates its
own destination and never aliases the receiver, so `frame.mat.cvtColor(...)` yields a Mat you own. The
[`Camera`](/video) helpers (`foreach`, `taking`, `take`, `snapshot`) go a step further and hand you owned
`Image` copies directly, so there is no borrowing to reason about at all — see [`video`](/video).

The ZIO module mirrors this exactly: `frameStream` borrows one buffer (same contract), `framesCopied`
gives you owned `Managed[Mat]` per frame — see [`zio`](/zio) and [`concurrency`](/concurrency).

## Thread safety

Native handles are **not** safe to share across threads without your own synchronization: a `Mat`, a
`Managed`, a detector, or a `VideoCapture` touched from two threads at once is a data race in C++, not
just in the JVM. Keep one owner per handle, or guard it. scalacv does **not** use JavaCPP
`PointerScope`, so there is no thread-local-scope caveat to reason about — ownership is always the
explicit `Managed` you hold, wherever it travels. OpenCV also runs its own internal thread pool
(and OpenBLAS another); if you fan pipelines out across your own threads, cap theirs
(`cv::setNumThreads`, `OMP_NUM_THREADS`/`OPENBLAS_NUM_THREADS`) so the two layers don't oversubscribe
the cores. See [`performance`](/performance) and [`concurrency`](/concurrency).

## Verify you aren't leaking

Don't wait for RSS to climb in production. Put a hard cap on JavaCPP's off-heap budget and run your
workload — a leak then fails *fast and loud* (a JavaCPP `OutOfMemoryError` naming the byte count)
instead of slowly eating the machine:

```sh
java -Dorg.bytedeco.javacpp.maxBytes=256M -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

If it throws almost immediately, something is allocating without releasing; if it completes, your
release discipline holds under that ceiling. Bisect by wrapping suspects in `Managed.use` until it
passes. (Run the same workload under both a low and a generous cap to tell a genuine leak from a
ceiling that is simply too small for the working set.)

| Symptom | Likely cause | Fix |
|---|---|---|
| RSS climbs, heap flat | Mats/detectors dropped without release | wrap in `Managed.use`; add a `maxPhysicalBytes` cap |
| `IllegalStateException: already released or consumed` | reused an `Image`/`Managed` after a transform or terminal | `.copy` before the first use; run with `-Dscalacv.trackOwnership=true` to find the consuming site |
| video loop shows only the last frame | retained the borrowed `frames` Mat (e.g. `toList`) | consume in-loop, or use `framesCopied` |
| SIGSEGV, no Java stack | a raw `org.opencv.*` handle freed twice, or shared across threads | let `Managed` own it; one owner per handle |

See [`troubleshooting`](/troubleshooting) and [`performance`](/performance#measuring-memory-do-it-right)
for more.

## GraalVM native-image is not supported today

This is a known limitation, not yet worked around. The very mechanisms that let scalacv free the 185
bindings without a public `release()` are the ones a static image forbids by default, and the natives
are loaded in a way an image has no way to reproduce. Three concrete blockers:

1. **The release layer reflects a private `delete(long)` per binding class.** Freeing a
   `CascadeClassifier`, `Net`, `KalmanFilter` and the rest goes through a cached `MethodHandle` onto
   that class's `private static native void delete(long)`. A native image sees no reflective use at
   build time, so every binding class you free would need an entry in `reflect-config.json`.
2. **The finalizer disarm writes a `final` field via reflection.** Before freeing, scalacv zeroes the
   binding's `nativeObj` field so the generated `finalize()` cannot `delete` the same address a second
   time. That is a reflective write to a final field — again invisible to the image's static analysis
   and needing explicit configuration.
3. **Natives are extracted from a jar at runtime and `System.load`ed by absolute path.**
   `OpenCv.load()` unpacks the platform libraries out of the bytedeco jars into `~/.javacpp` and loads
   them by absolute path. A static image has no jar on disk to read, so this last step has nowhere to
   extract from.

If you need native-image today, the honest answer is that scalacv does not run under it yet.

## Next

- [`native-cache`](/native-cache) — where the natives, cascade XML and downloaded models are cached, and how to deploy lean.
- [`video`](/video) — the borrowing contract in action, plus the owned-copy `Camera` helpers.
- [`image-api`](/image-api) — the high-level `Image` tier whose move semantics this page explains.
