# The error model

Things go wrong in image code all the time — a file is not where you thought, a download 404s, a model expects three channels and gets one. The question every wrapper has to answer is: *when* is a failure a value you handle, and *when* is it a bug you fix? scalacv draws that line once, deliberately, so you never have to guess. This page is the whole policy, and every snippet here is compiled by mdoc against the real library, so it cannot drift out of date.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

OpenCV's Java API reports failure three incompatible ways — a `false` return, an *empty* `Mat`, or a thrown `org.opencv.core.CvException` several call frames from the mistake. The job of the wrapper is to flatten that into a policy you can reason about:

- **`Either[CvError, A]`** for failures that are **data-dependent and expected** — a file that is not there, bytes that do not decode, a model download that 404s. These are values you handle, not bugs.
- **A thrown `IllegalArgumentException`** for **programmer errors** — an even Gaussian kernel, an empty `Mat` handed to an op that needs pixels. These come from `require`, are not part of the `Either`, and should never be pattern-matched.
- **A thrown `IllegalStateException`** for **use-after-move** — touching an `Image` a transform already consumed. Also a bug, also outside the `Either`.
- **`CvError.NativeCall`** for the residual `CvException` that OpenCV throws from an ordinary in-memory op we did not anticipate. It is wrapped so the operation is *named*, never swallowed.

## At a glance

| Failure | Delivered as | You should | Example |
| --- | --- | --- | --- |
| Missing / undecodable image | `Left(CvError.DecodeFailed)` | branch on it | a bad path from a user |
| Missing model, cascade, video source | `Left(CvError.LoadFailed)` | branch on it | a 404 on a model download |
| Unwritable destination / bad encoder | `Left(CvError.EncodeFailed)` | branch on it | writing to a missing directory |
| Ill-posed calibration | `Left(CvError.CalibrationFailed)` | recapture data | too few chessboard views |
| Natives absent, **or** the release bridge cannot be opened | thrown `CvError.NativesMissing` | fix the build, or add the `--add-opens` flag the message names | forgot the classifier jar; OpenCV on the module path |
| Unforeseen native rejection | `CvError.NativeCall` (returned via `Cv.attempt`, else thrown) | usually a bug; sometimes handle | wrong channel count |
| Bad argument | thrown `IllegalArgumentException` | fix the call | `blur(-1)` |
| Reusing a spent `Image` | thrown `IllegalStateException` | fix the code | use-after-move |

## Why an exception hierarchy, not a pure ADT

`CvError` is a `sealed abstract class` that extends `RuntimeException`. That looks unusual for an error ADT, and it is a considered choice: the core cannot be made total. `CvException` escapes from ordinary `Imgproc` calls — including on the empty `Mat` a failed `imread` hands back — and no wrapper can prevent that. Because `CvError` *is* a `Throwable`, it interoperates with that JNI boundary: it can be the `cause` of a wrapped native throw, it can be rethrown by [`Cv.orThrow`](#the-escape-hatch-cv-attempt), and it can cross a `try`/`catch` unchanged. You still get exhaustive `match` on the sealed hierarchy where you want it; you also get a type that behaves correctly at the one place the language cannot help you.

## The seven cases {#the-six-cases}

`CvError` has exactly seven shapes. Each names *when you see it*.

### `NativesMissing`

Something the JVM needs in order to talk to native OpenCV cannot be reached. Whatever the cause, `details` is written to be the *remedy*, meant to be copy-pasted:

```scala mdoc:compile-only
def report(e: CvError): String = e match
  case CvError.NativesMissing(details, _) => details // the remedy, ready to copy
  case other                              => other.getMessage
```

It is *thrown* rather than returned, because there is no sensible way to carry on. There are **two** causes, and — despite the name — only one of them happens at start-up.

**At load.** `OpenCv.load()` throws when the per-platform *classifier* jars are absent. Those are the jars carrying the actual `.so` / `.dylib` / `.dll` for the machine you are running on, as opposed to the Java classes that call into them. Here `details` is a dependency line naming the platform it detected, ready to paste into your build. This is the common case, and you hit it once, before any real work. See [Troubleshooting](/troubleshooting#natives-missing) for the classifier table.

**At release.** `Releasable.handle` — the bridge that frees the 185 OpenCV types that expose no public `release()` method of their own — throws `NativesMissing` too, and it throws it **when a handle is freed**. That is typically at the end of a `Managed.use` block, in the middle of a pipeline that has been working for a while, which is why the name can be misleading. It fires when reflection cannot reach a binding's private `delete(long)`, or cannot zero its `nativeObj` field to disarm the finalizer that would otherwise free the same pointer a second time. Here `details` carries an `--add-opens` flag rather than a dependency line; the usual trigger is OpenCV loaded from the *module path* instead of the classpath. It throws rather than degrading, because the fallback — leaving the memory to the garbage collector — is an unbounded native leak that looks like success, and freeing without disarming the finalizer is a double free. See [Working with the raw OpenCV API](/low-level) and [Troubleshooting](/troubleshooting#add-opens).

### `DecodeFailed`

An image could not be **read or decoded**. This is the subtle one: `imdecode` does *not* throw for non-image bytes — it returns a `Mat` with `empty() == true`. scalacv makes the check for you and turns it into a `Left`, so the failure surfaces here instead of as a `CvException` from some later op that had nothing to do with the mistake. Reading from a file adds the filesystem cases — missing, a directory, empty, a path the filesystem cannot represent — and because that half is done with the JVM's own file I/O rather than with `imread`, the `details` say which one happened instead of collapsing them all into one message:

```scala mdoc
Images.read("/does/not/exist.png").left.map(_.getMessage)
```

```scala mdoc
Images.read("/does/not/exist.png") match
  case Left(CvError.DecodeFailed(path, _)) => s"could not decode: $path"
  case Left(other)                         => other.getMessage
  case Right(_)                            => "decoded"
```

`DecodeFailed` is specifically about image *bytes*. See [Image I/O](/image-io) for the full read/decode surface.

### `LoadFailed`

A **non-image** resource could not be resolved, loaded, or verified — a model file, a Haar cascade, an ONNX network, a downloaded artifact, a video source. It is kept distinct from `DecodeFailed` on purpose: an HTTP 404 for a model download, a missing `.onnx`, or a checksum mismatch is *not* an image-decode failure and should not read like one.

```scala mdoc:compile-only
def explain(e: CvError): String = e match
  case CvError.DecodeFailed(path, _)     => s"$path holds no image"
  case CvError.LoadFailed(resource, why) => s"$resource did not load: $why"
  case other                             => other.getMessage
```

This is the failure you handle when loading detectors and networks — see [Object detection](/object-detection). A `Recorder` that cannot open its codec reports `LoadFailed` too; see [Troubleshooting](/troubleshooting#codec).

### `EndOfStream`

A capture opened successfully, but `Camera.snapshot` could not obtain another frame within its bounded
read attempts. The error names the file or device, rather than reporting a loading failure. OpenCV
cannot distinguish file exhaustion from a camera that stopped delivering; `Video.frames` and
`frameStream` complete their traversal normally for the same condition.

### `EncodeFailed`

An image could not be **written or encoded** — an extension with no registered encoder (which the codecs would otherwise signal by throwing, but scalacv checks `haveImageWriter` first and returns this instead), or a destination the JVM cannot write to: a missing parent directory, a permission denial, a path the filesystem cannot represent. `Images.write` encodes fully into memory and only then writes the bytes, so a failed *encode* can no longer leave a truncated file behind:

```scala mdoc:compile-only
import org.opencv.core.{CvType, Mat}

Managed.use(Mat(8, 8, CvType.CV_8UC3)): m =>
  Images.write("/no/such/dir/out.png", m) // Left(CvError.EncodeFailed(...))
```

Both encode-failure modes share the one type, so a single `case EncodeFailed(...)` catches them all.

### `CalibrationFailed`

A camera calibration could not be produced — either too few views showed the whole target for the solver to be well-posed, or `calibrateCamera` did not converge. This is *data-dependent*: it turns on how many boards the capture actually saw, not on a programmer error, so it is returned rather than thrown. The fix is to **capture more views**, not to change the code:

```scala mdoc:compile-only
def onCalibration(e: CvError): String = e match
  case CvError.CalibrationFailed(why) => s"recapture the board — $why"
  case other                          => other.getMessage
```

See [Calibration](/calibration) for `Calibration.fromChessboard` and how many views "enough" is.

### `NativeCall`

The catch-all for a `CvException` thrown by an ordinary op — a size mismatch, a channel-count violation, anything OpenCV signals by throwing rather than by an empty `Mat`. The wrapper *names the operation* and preserves OpenCV's message verbatim (it is deliberately not parsed for error codes — that text is not a stable interface). Here a `BGR→GRAY` conversion is asked of an image that is already single-channel:

```scala mdoc
import org.opencv.imgproc.Imgproc
import org.opencv.core.{CvType, Mat}

val bad: Either[CvError, Unit] =
  Managed.use(Mat(4, 4, CvType.CV_8UC1)): gray =>
    Managed.use(Mat()): out =>
      Cv.attempt("cvtColor(BGR2GRAY)"):
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_BGR2GRAY)

bad.left.map(_.getMessage)
```

## Programmer errors stay outside the `Either`

The two thrown cases are not accidents in the policy — they are the policy. A bad argument or a reused handle is a *bug*, and a bug should stop the program at the mistake, not thread quietly through a `flatMap` where someone might "handle" it by ignoring it. So they throw, and they are not `CvError`:

```scala mdoc:crash
Image.blank(8, 8).blur(-1) // IllegalArgumentException — a negative radius is a bug, not a value
```

```scala mdoc:crash
val im = Image.blank(8, 8)
im.gray   // consumes im
im.width  // IllegalStateException — use after move
```

:::warning[Do not pattern-match a programmer error]
`IllegalArgumentException` and `IllegalStateException` are deliberately *not* part of `CvError`. If you find yourself catching them to recover, that is a signal the bug should be fixed at the call site instead. [Troubleshooting](/troubleshooting#move-semantics) explains the tracking flag that points at the consuming call.
:::

## The escape hatch: `Cv.attempt` {#the-escape-hatch-cv-attempt}

Every built-in like `Images.read` already returns an `Either`. When you go *off the beaten path* — a raw `org.opencv.*` call scalacv does not wrap — `Cv.attempt` is the single tool that lifts it into the same policy. It runs your block, and its whole contract is **three** `catch` clauses:

1. an `org.opencv.core.CvException` becomes `Left(CvError.NativeCall(operation, e))`, with the operation you named;
2. a `CvError` the block already produced is passed through unchanged, so wrapping an already-lifted call does not re-wrap it;
3. a **bare** `java.lang.Exception` — matched by *exact class*, `e.getClass == classOf[Exception]` — also becomes `Left(CvError.NativeCall(operation, e))`. That clause exists because OpenCV's own `throwJavaException` degrades to a plain `Exception` for failures that are not a `cv::Exception`: `std::bad_alloc` (out of native memory), `std::out_of_range`, and anything it does not recognise.

That is the entire body — there is no hidden magic. The exact-class guard in (3) is load-bearing: because it demands the class be `Exception` itself, **every `Exception` subclass still propagates**. An `IllegalArgumentException` from a `require` and an `IllegalStateException` from a spent handle travel straight through `attempt` and are never turned into a `Left`. That is the mechanism that keeps programmer errors outside the `Either` — the split described above is not a convention anyone has to remember, it is enforced in those five lines:

```scala mdoc:compile-only
import org.opencv.core.{Core, Mat}

// A raw op scalacv does not wrap; attempt names it and keeps the Either policy intact.
def meanBrightness(m: Mat): Either[CvError, Double] =
  Cv.attempt("Core.mean")(Core.mean(m).`val`(0))
```

When a native failure genuinely *is* a bug at your call site — not a value to handle — reach for `Cv.orThrow(op)(...)` instead: it is `attempt` that rethrows the `CvError` rather than returning it.

| Tool | Returns | Reach for it when |
| --- | --- | --- |
| `Cv.attempt(op)(block)` | `Either[CvError, A]` | the failure is data you want to branch on |
| `Cv.orThrow(op)(block)` | `A`, throwing on failure | a native failure here is a bug, not an outcome |

## Composing

Because every fallible step is an `Either[CvError, A]`, they thread together with `flatMap` and for-comprehensions, and the **first** failure short-circuits the rest — you never run the encode when the decode already failed:

```scala mdoc
Images.read("/does/not/exist.png").flatMap(_.use(Images.encode(_, ".png"))).isLeft
```

A whole read → transform → write pipeline is one comprehension, and its type says precisely what can go wrong: nothing but a `CvError`.

```scala mdoc:compile-only
val pipeline: Either[CvError, Array[Byte]] =
  for
    src   <- Image.read("photo.jpg")            // DecodeFailed if it is not an image
    edges <- src.gray.canny(80, 160).bytes(".png") // EncodeFailed / NativeCall from here on
  yield edges
```

The high-level [`Image`](/image-api) chain uses exactly this: reads and terminals return `Either`, transforms move the image along, and the errors compose the same way. Programmer errors — an even kernel, a spent handle — stay *outside* this `Either` as thrown `IllegalArgumentException`/`IllegalStateException`, because they are bugs to fix, not outcomes to branch on.

## Next

- Where each failure actually shows up in practice, with a fix per symptom: [Troubleshooting](/troubleshooting).
- Reading and writing images — the one boundary with all three OpenCV failure shapes: [Image I/O](/image-io).
- Taking a native call all the way to the raw API yourself: [Working with the raw OpenCV API](/low-level).
