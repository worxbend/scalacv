# Working with the raw OpenCV API

scalacv is a wrapper, never a wall. Underneath every convenience is the ordinary OpenCV 4.14 Java
API — `org.opencv.core.Mat`, `org.opencv.imgproc.Imgproc`, the detectors — and scalacv is built so
you can reach it at any point, use the exact call you need, and come back up without ceremony. This
page is the map of how to move between levels.

:::tip[New here? Read this first.]
If you only ever use [`Image`](/image-api), you never need this page — the high-level API covers the
common path. Come back when OpenCV has a function scalacv doesn't wrap yet, or when you need a knob
(a `CV_16S` output depth, a raw detector) the higher tiers hide. Nothing here is exotic; it's just
"call the Java method directly, then let scalacv manage the memory again."
:::

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

## Three levels, one library

There are three altitudes, and picking the right one per step is the whole skill:

| Level | What you hold | Frees itself? | Reach for it when |
|---|---|---|---|
| **High** — [`Image`](/image-api) | an owned image, chained by verbs | yes, per chain | the common `read → transform → detect → write` path |
| **Mid** — `Managed[Mat]` + extension ops | one owned Mat at a time | yes, via `use`/`pipe` | you need a Mat-level knob or to hold intermediates |
| **Low** — raw `org.opencv.*` | whatever the Java binding hands back | **you decide** | OpenCV exposes a call the higher levels don't wrap |

The levels are not sealed tiers you commit to at the top of a file. A single pipeline can start on
`Image`, drop to a raw `Imgproc` call for one operator, and rise back to `Image` for the write — and
the rest of this page is exactly those moves.

:::note[Why bother having levels at all?]
Native memory is not garbage-collected in any useful timeframe (a multi-megabyte pixel buffer looks
like ~40 bytes of Java header to the collector — see [Mat lifecycle](/mat-lifecycle)). Every level is
really an answer to the same question: *who frees this Mat, and when?* `Image` answers "the chain
does"; `Managed` answers "the `use` block does"; raw OpenCV answers "you do." Moving between levels is
moving that answer around.
:::

## The one primitive: `Managed`

Everything below `Image` rests on one type, [`Managed[A]`](/mat-lifecycle) — a native handle that is
freed **exactly once**. Before the escape hatches make sense, it helps to see it on its own.

```scala mdoc:silent
// Managed.use is the form to reach for by default: it runs the body, then frees, even on exception.
val rows: Int =
  Managed.use(Mat(48, 48, CvType.CV_8UC3)) { m => m.rows } // Mat freed when the block returns
```

```scala mdoc
rows
```

The handle-holding form exists too, for when a value must outlive a single block. You own it, so you
close it:

```scala mdoc:silent
val handle: Managed[Mat] = Managed(Mat(16, 16, CvType.CV_8UC3))
val cols: Int = handle.get.cols // `get` borrows the Mat; the handle still owns it
handle.close()                  // your responsibility, since you did not use `use`
```

Two guarantees make this safe, and both exist because getting them wrong is a JVM crash rather than an
exception:

| Method | What it does | After it |
|---|---|---|
| `get` | borrows the underlying object | handle still owns it |
| `use(f)` | runs `f(get)`, then releases | handle spent |
| `release()` / `close()` | frees the native memory (idempotent) | handle spent |
| `isReleased` | has it been freed/consumed yet? | (a query) |

Touching a spent handle throws `IllegalStateException` on the Scala side, *before* anything crosses
JNI — you get a stack trace, not a segfault:

```scala mdoc:crash
val spent = Managed(Mat(8, 8, CvType.CV_8UC3))
spent.close()
spent.get // throws IllegalStateException — the alternative would be a SIGSEGV from native code
```

:::tip[Diagnosing use-after-move]
When a spent-handle error fires, the failing line is the *reuse*, which is rarely the interesting one.
Start the JVM with `-Dscalacv.trackOwnership=true` and the exception carries, as its cause, the stack
of the transform or terminal that actually consumed the handle. It is off by default because it
allocates a `Throwable` on every consume; on the happy path it costs nothing.
:::

## Escaping from an `Image`

An `Image` owns a single `Managed[Mat]`. Three methods let you get at it, and which one you pick
depends on whether you are *borrowing* the Mat or *taking ownership* of it.

| Doorway | Direction | Ownership | You must… |
|---|---|---|---|
| `img.mat` | down (borrow) | stays with the `Image` | **not** release it — the `Image` will |
| `img.managed` | down (handover) | moves to the returned `Managed` | release the `Managed` (or `use` it) |
| `Image.wrap(managed)` | up (handover) | moves into the new `Image` | **not** release the `Managed` too |

### `mat` borrows — do not release it

`img.mat` hands you the underlying `org.opencv.core.Mat` for any raw call. The `Image` still owns it:
read from it, pass it to a detector, run an `Imgproc` function against it — but do **not** release it,
because the `Image` will.

```scala mdoc:silent
val img = Image.blank(64, 64)

// A raw org.opencv.core.Core call on the borrowed Mat. The Image still owns `img.mat`.
val average = org.opencv.core.Core.mean(img.mat)

img.close() // this frees the Mat — `average` is already a plain value, safe to keep
```

:::warning[A borrow is a loan, not a gift.]
Never call `.release()` on `img.mat`, and never stash it to use after the `Image` is closed. Both
free the same pointer that the `Image` still thinks it owns — a double free, or a read of freed
memory. If you need the Mat to outlive the `Image`, use `img.managed` (handover) or `img.copy.managed`
(independent copy).
:::

### `managed` hands the whole thing over

Inside a callback scope, `.managed` retains the scope registration and custom release strategy.
Use `img.detach.managed` only when explicitly transferring responsibility outside that scope; the
caller must then release it. Copies remain scoped too unless explicitly detached.

When you want to stop being an `Image` and manage the Mat's lifetime yourself, `img.managed` transfers
ownership out. The `Image` is spent afterwards; the returned `Managed[Mat]` is now yours to release.

```scala mdoc:silent
val owned: Managed[Mat] = Image.blank(32, 32).managed // the Image is spent; the Managed owns the Mat now
val elements: Long = owned.get.total                  // borrow for a raw query — `owned` still owns it
```

```scala mdoc
elements
```

### `Image.wrap` goes the other way

`Image.wrap(managed)` adopts a `Managed[Mat]` you already hold and gives you back the high-level API.
Ownership transfers *into* the `Image`, so do not also release the `Managed` yourself.

```scala mdoc:silent
val restored: Image = Image.wrap(owned) // adopts the `owned` handle above; do not release it separately
restored.close()                        // releases the Mat exactly once, here
```

`mat` / `managed` / `wrap` are the doorways between the top level and everything below it — a borrow, a
handover down, and a handover back up.

:::note[Branching an `Image`]
Because a transform *consumes* its receiver, you cannot use one `Image` twice. To keep the original,
take a `.copy` first — an independent deep copy — then transform the copy. See
[Mat lifecycle](/mat-lifecycle) for the full move-semantics story.
:::

## Calling raw `org.opencv.*`, then coming back up

The common shape is: you have an owned image, OpenCV has a function scalacv doesn't wrap, you call it
directly on the borrowed Mat, and you adopt the result back into managed ownership.

```scala mdoc:silent
import org.opencv.imgproc.Imgproc

val src = Image.blank(120, 160)

// Drop to raw org.opencv.* for a knob the higher levels don't expose (here, a CV_16S Laplacian):
val rawOut = new Mat()
Imgproc.Laplacian(src.mat, rawOut, CvType.CV_16S) // borrowed Mat in, our own Mat out
src.close()                                       // done borrowing; the source Image frees its Mat

// Come back up. Wrap the raw result so it is released exactly once...
val result: Managed[Mat] = Managed(rawOut)

// ...or lift it straight into the high-level API and keep chaining:
val encoded: Either[CvError, Array[Byte]] =
  Image.wrap(result).bytes(".png") // `result`'s Mat is now owned by the Image, released on bytes()
```

The rule of thumb: the moment a raw call hands you a bare `Mat`, wrap it in `Managed` (or adopt it as an
`Image`). From then on it is freed exactly once, on success or on exception, like everything else.

:::danger[Never leave a raw `Mat` unwrapped past the next line.]
A bare `new Mat()` that OpenCV fills is a native allocation with no owner. If an exception fires
before you wrap it — or you simply forget — it leaks, and the collector will not reclaim it in time to
matter. Wrap it in the *same block* it was created, so a `Managed` (or `Image`) is on the hook for its
release no matter what happens next.
:::

### The mid level: same story, no `Image` wrapper

The mid level is the same story without the `Image` wrapper — every extension op on a `Mat` already
returns an owned `Managed[Mat]`, so `pipe` threads them and frees each intermediate:

```scala mdoc:silent
val midLevel: Either[CvError, Array[Byte]] =
  Managed.use(Mat(80, 80, CvType.CV_8UC3)) { m =>
    m.cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(50, 150))
      .use(Images.encode(_, ".png"))
  }
```

`pipe` feeds the intermediate Mat to the next stage and releases it once that stage has its own output,
so the blur (or grey) output cannot be leaked and cannot be used after the chain moves on. When the
*last* stage produces something other than a Mat — a count, a `Seq[Rect]`, an encoded byte array — use
`Managed.use` instead, which has the same shape and the same guarantee.

For a longer pipeline, `Mats.chain` reads as a flat list of stages rather than nested lambdas — it is a
fold of `pipe`, releasing each intermediate as the next consumes it, and it **borrows** its source
(never releasing it):

```scala mdoc:silent
val chained: Either[CvError, Array[Byte]] =
  Managed.use(Mat(80, 80, CvType.CV_8UC3)) { m =>
    Mats
      .chain(m)(
        _.cvtColor(ColorConversion.BgrToGray),
        _.gaussianBlur(Size(5, 5), 1.5),
        _.canny(50, 150)
      )
      .use(Images.encode(_, ".png"))
  }
```

:::note[Every op is pure with respect to its receiver.]
A mid-level op (`cvtColor`, `canny`, `gaussianBlur`, …) never writes to, releases, or aliases its
receiver — it allocates a fresh destination and hands *that* back as a `Managed[Mat]` you own. That is
exactly why you can call one on a **borrowed** Mat (a video frame, a detector's input) with no transfer
ceremony: the frame is untouched. See [Image processing](/image-processing) for the full operator set.
:::

## Managing *any* native type yourself

A `Mat` is not the only native object with an off-heap footprint. Detectors, networks and classifiers
all own native memory too, and `Managed` frees any of them — but *how* it frees them splits into two
regimes, and the split is dictated by the generated Java binding, not by taste. (The full argument for
why this matters lives in [Mat lifecycle](/mat-lifecycle).)

| Regime | Types | What the binding exposes | How to free it |
|---|---|---|---|
| **1** | `Mat`, `VideoCapture`, `VideoWriter` | a public `release()` | given `Releasable` already in scope — just works |
| **2** | the other 185 (`CascadeClassifier`, `QRCodeDetector`, `ArucoDetector`, `Net`, `FaceDetectorYN`, …) | only a private `delete(long)` + a `finalize()` | opt into the bridge with `Releasable.handle` |

### Regime 1: the three types with a public `release()`

`Mat`, `VideoCapture` and `VideoWriter` are the only `org.opencv.*` types that expose a public
`release()`. For them the `Releasable` instance is already in scope and `Managed` just works:

```scala mdoc:silent
val frameSize =
  Managed.use(Mat(48, 48, CvType.CV_8UC3)) { m => // Releasable[Mat] uses the public release()
    (m.rows, m.cols)
  }
```

### Regime 2: the other 185 — opt into the bridge

Every other native type — `CascadeClassifier`, `QRCodeDetector`, `ArucoDetector`, `Net`, `FaceDetectorYN`
and 180 more — has no public `release()`. All it exposes is a `private static native void delete(long)`
and an unconditional `finalize()`. To free one you opt into the bridge:

```scala mdoc:silent
import org.opencv.objdetect.QRCodeDetector

// Opt in: route release through the binding's private delete(long).
given Releasable[QRCodeDetector] = Releasable.handle(_.getNativeObjAddr)

val detectorClass =
  Managed(new QRCodeDetector()).use { detector =>
    // ...run `detector` against a Mat here; when the block returns, the bridge frees it...
    detector.getClass.getSimpleName
  }
```

`Releasable.handle(_.getNativeObjAddr)` does two things, in this order:

- it **disarms the binding's finalizer first** (zeroing `nativeObj`) so that after scalacv calls
  `delete`, the finalizer's later `delete(this.nativeObj)` becomes `delete nullptr` — a C++ no-op —
  instead of freeing the same pointer a second time and corrupting the heap;
- then it calls `delete(long)` through a cached `MethodHandle`.

It is deliberately **opt-in and loud**. `delete(long)` is private API with no compatibility promise, and
the reflection it needs stops working the moment OpenCV is loaded from a named module. So if the bridge
cannot be opened, `Releasable.handle` **throws** a `CvError.NativesMissing` rather than falling back to
the garbage collector — a silent fallback would look like success while leaking native memory without
bound.

The thrown message names the exact flag to add, and it is not a guess: scalacv computes it from the
offending class's *own* module and package, by asking the class itself
(`cls.getModule.getName` and `cls.getPackageName`). A *module* is the JDK 9+ unit that decides what
reflection may reach; a *package* is the familiar dotted namespace. Here the two have different
names, which is exactly why the flag is computed rather than hard-coded: the bytedeco jar declares
the module `org.bytedeco.opencv`, while the detector classes sit in packages called `org.opencv.…`.
So freeing a `QRCodeDetector` asks for
`--add-opens org.bytedeco.opencv/org.opencv.objdetect=ALL-UNNAMED`, and freeing a DNN `Net` asks for
`--add-opens org.bytedeco.opencv/org.opencv.dnn=ALL-UNNAMED`. It is never `java.base/java.lang` —
the member being opened is OpenCV's own private `nativeObj` field (and its private `delete(long)`),
not anything belonging to the JDK.

When OpenCV is on the **classpath** rather than the module path — the normal case, and the one this
page's examples assume — its classes are in the unnamed module, so there is no module to open and no
flag that would help. In that situation the message says exactly that and asks for a bug report,
instead of printing a flag with a `null` module name in it. See
[the error model](/error-model) for how that surfaces, and
[Troubleshooting](/troubleshooting#add-opens) for the fix.

:::warning[Do not drop a Regime-2 handle unwrapped.]
Constructing a `QRCodeDetector` (or any Regime-2 type) and letting it go out of scope leaks native
memory until the collector eventually runs its `finalize()` — which may be never, under load. Always
put it in a `Managed` with its `given Releasable[...]` in scope, and prefer `.use` so release is
scoped. The one place you *don't* declare the given yourself is when scalacv already wraps the detector
for you — see below.
:::

Anything that needs a real model file or trained network follows the identical pattern — only the
construction changes:

```scala mdoc:compile-only
import org.opencv.dnn.{Dnn, Net}

given Releasable[Net] = Releasable.handle(_.getNativeObjAddr)

Managed.use(Dnn.readNetFromONNX("model.onnx")) { net =>
  // set inputs, forward, read outputs — raw org.opencv.dnn throughout
  net.empty
}
```

For the batteries-included detectors, scalacv already declares these instances for you and wraps the
construction — see [Object detection](/object-detection), [DNN](/dnn), and [Face recognition](/face-recognition).
The bridge is what you reach for when you want a detector the library doesn't wrap yet.

## Borrowing frames from video and camera

The same borrow-vs-own distinction governs frame iteration, and it is the single most common place a
raw-Mat mistake bites. Some entry points hand you a **borrowed, reused** Mat; others hand you an
**owned copy**:

| Entry point | What you get | Safe to keep? |
|---|---|---|
| `Video.frames(capture) { it => … }` | one **reused, borrowed** Mat per iteration | no — copy it if you must retain it |
| `Video.framesCopied(capture) { it => … }` | an **owned copy** per iteration | yes — but close each one |
| `Camera.foreach() { img => … }` | an owned [`Image`](/image-api) per frame | yes — but close it |

With `Video.frames`, the iterator yields the *same* Mat object every step, overwritten in place — so
collecting them into a `Seq` gives you N references to one buffer holding the last frame. When in
doubt, reach for `framesCopied` or `Camera.foreach`, which give you an owned value you can safely hand
onward. Full detail lives in [Video](/video).

## The rule of thumb

Stay high-level for the common path; drop exactly one level at exactly the step that needs a knob the
level above doesn't expose, then come straight back up. `mat` borrows, `Managed` adopts, `Image.wrap`
lifts — no ceremony to go down, no lock-in keeping you there. The low-level OpenCV API is always one
method call away, and reaching for it is a normal thing to do, not an escape from the library.

## Next

- [Mat lifecycle](/mat-lifecycle) — the full ownership model, move semantics, and why native memory needs managing.
- [Image API](/image-api) — the high-level tier you drop out of and back into.
- [Error model](/error-model) — how `CvError`, thrown `CvException`s, and `NativesMissing` fit together.
