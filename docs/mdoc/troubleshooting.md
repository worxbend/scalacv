# Troubleshooting

The errors people actually hit with scalacv, and the one-line fix for each. Most of them are not bugs in your code — they are OpenCV reporting a missing native, an absent codec, or a spent handle in one of its three incompatible ways, and scalacv turning that into something you can act on. Find your symptom, apply the fix, move on.

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*

OpenCv.load()
```

## Quick reference

Scan the left column for what you saw, then jump to the section:

| Symptom | Likely cause | Section |
| --- | --- | --- |
| `UnsatisfiedLinkError` at load, natives "missing" | no platform-classifier jar on the classpath | [natives missing](#natives-missing) |
| `Not found: Cascades` / `value faces is not a member of Image` | missing module dependency or its wildcard import | [missing module](#missing-module) |
| Fails only on a headless server / CI, mentions GTK | loaded via `Loader.load` instead of `OpenCv.load()` | [headless / no GTK](#headless) |
| `IllegalStateException: already been released or consumed` | reused an `Image` a transform already spent | [move semantics](#move-semantics) |
| `Image.read` returns `Left(DecodeFailed)` on a real file | wrong working directory, or no decoder for the format | [decode failed](#decode-failed) |
| `Cascades.load` returns `Left(LoadFailed)`, "share/ directory is empty" | you are on Windows, whose jar ships no cascades | [cascades on Windows](#cascades-windows) |
| `add-opens` / `cannot open …/delete(long)` | OpenCV loaded from a named module | [add-opens](#add-opens) |
| `Recorder.open` returns `Left`, "codec unavailable" | the build has no encoder for that codec | [codec](#codec) |
| RSS climbs, heap stays small, no test fails | a leaked native `Mat` | [native leak](#native-leak) |
| You call `.close()` on every image and it *still* leaks | you closed a handle a transform had already spent | [closing the wrong handle](#closed-the-wrong-handle) |
| `drawText` drew nothing / text misplaced / `????` | baseline anchoring, or a non-ASCII glyph | [drawText](#drawtext) |
| `CvError.NativeCall` thrown mid-chain | OpenCV rejected the pixels for that op | [native call mid-chain](#native-call) |
| GraalVM native-image build fails | unsupported — reflection + runtime extraction | [GraalVM](#graalvm) |

## `OpenCv.load()` fails / `UnsatisfiedLinkError` / natives missing {#natives-missing}

The `scalacv` jar contains no native code — the OpenCV symbols live in per-platform classifier jars you add yourself. If they are absent, `OpenCv.load()` throws `CvError.NativesMissing` with a **copy-pasteable fix naming your actual platform**. Add the two lines it prints:

```scala
mvn"org.bytedeco:opencv:4.14.0-1.5.14;classifier=linux-x86_64",
mvn"org.bytedeco:openblas:0.3.34-1.5.14;classifier=linux-x86_64"
```

:::warning[Both lines are required]
`libopencv_core` links `libopenblas` — omit the second and the first will not resolve. And pick the classifier for where the code *runs*, not where you build it: a `linux-x86_64` jar does nothing on an Apple-silicon Mac.
:::

The classifiers, one per target:

| Platform | Classifier |
| --- | --- |
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-arm64` |
| macOS Intel | `macosx-x86_64` |
| macOS Apple silicon | `macosx-arm64` |
| Windows x86-64 | `windows-x86_64` |

If you would rather not pick, `org.bytedeco:opencv-platform:4.14.0-1.5.14` bundles every one — at a cost of about 408 MB. See [Getting Started](/getting-started) for the full build snippet.

## `Not found: Cascades` / `value faces is not a member of Image` {#missing-module}

These two are **compile** errors, not runtime ones. Check both the dependency and the import: the module must be on your classpath, and its wildcard import must bring its types and extensions into scope. scalacv is published as four separate artifacts under the group id `com.worxbend`, and only core is required:

| Artifact | What lives in it |
| --- | --- |
| `scalacv` | the core: `Image`, `Managed`, `Camera`, `Video`, `Recorder`, `contours`, `threshold`, the filters and the draw verbs |
| `scalacv-vision` | `Cascades`, `faces` / `FaceDetectorYN`, `FaceRecognizer`, `Dnn`, `Ocr`, `Ar`, `qrCodes`, `arucoMarkers`, `MotionDetector`, `Features`, `OpticalFlow`, pose, tracking, calibration, SLAM |
| `scalacv-graphs` | the `Picture` scene graph, `Color`, `Chart`, animated GIFs |
| `scalacv-zio` | the ZIO integration (`frameStream` and friends) |

An unavailable **object** produces the first message — `Not found: Cascades`. An unavailable **extension method** produces the second — `value faces is not a member of scalacv.Image` — even when `import scalacv.*` brings `Image` into scope. `faces` belongs to `scalacv.vision`, not core: add `scalacv-vision` to the build and `import scalacv.vision.*` to the source. Adding only the jar or only the import is insufficient.

Add the line for the module you need (delete the ones you do not). In Mill:

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.2.0",         // core
  mvn"com.worxbend::scalacv-vision:0.2.0",  // detectors, DNN, tracking, OCR, calibration, SLAM
  mvn"com.worxbend::scalacv-graphs:0.2.0",  // the Picture scene graph, charts, GIFs
  mvn"com.worxbend::scalacv-zio:0.2.0"      // only if you use ZIO
)
```

In sbt:

```scala
libraryDependencies ++= Seq(
  "com.worxbend" %% "scalacv"        % "0.2.0",
  "com.worxbend" %% "scalacv-vision" % "0.2.0",
  "com.worxbend" %% "scalacv-graphs" % "0.2.0",
  "com.worxbend" %% "scalacv-zio"    % "0.2.0"
)
```

Two follow-on details:

- Each module owns a distinct package: `scalacv`, `scalacv.vision`, `scalacv.graphs`, or `scalacv.zio`. Import core plus the modules you use:

```scala mdoc:silent
import scalacv.*
import scalacv.vision.* // faces, detectors, tracking, OCR, AR
import scalacv.graphs.* // Picture, charts, Image drawing extensions
import scalacv.zio.*    // scoped resources and frame streams
```

- If the module *is* on your classpath and the error persists, check its wildcard import. `import scalacv.Image` imports the core type but no extension methods; `import scalacv.*` does not import extensions from `scalacv.vision` or `scalacv.graphs`.

The bytedeco natives are a separate question — those give you an `UnsatisfiedLinkError` at run time, not a compile error; see [natives missing](#natives-missing) above. Full build files for both tools are in [Getting Started](/getting-started).

## It fails on a headless server / CI runner (no GTK) {#headless}

Call `OpenCv.load()`, **not** `Loader.load(classOf[opencv_java])`. The latter eagerly initialises OpenCV's `highgui` module, which is GTK2-linked on Linux and drags `objdetect`/`calib3d`/`features2d`/`video` down with it on a box with no GTK — and `objdetect` is exactly what this library needs most. `OpenCv.load()` brings the natives up through a GUI-free path and needs no `apt-get install libgtk2.0-0`. This is the entire reason the loader exists; do not "simplify" it back to `Loader.load`.

:::note[The failure is loud, not silent]
The bundled `libopencv_highgui.so` carries *unversioned* dependency names, so on a machine that happens to have a different OpenCV installed, a naive bulk load can bind the wrong ABI and later die inside `cv::Mat::release()` with no Java stack trace. `OpenCv.load()` resolves dependencies on demand precisely to avoid that. If you see a JVM crash with no stack near a `Mat` operation, suspect a stray load path, not scalacv.
:::

## `IllegalStateException: this Image has already been released or consumed` {#move-semantics}

You reused a value a transform already consumed. `Image` has **move semantics**: `gray`/`blur`/`canny`/any `draw*`/a terminal *spends* the receiver and returns a fresh `Image`:

```scala mdoc:crash
val img = Image.blank(8, 8)
val a = img.gray
val b = img.blur(2) // throws: `img` was already spent by `.gray`
```

The fix is to take a `.copy` before the first use when you need the image twice:

```scala mdoc:silent
val original = Image.blank(8, 8)
val edges = original.copy.gray.canny(50, 150) // works on a copy
val small = original.resize(4, 4)             // original is still live here
edges.close(); small.close()
```

The exception fires at the *reuse*, which is rarely the interesting line. Start the JVM with `-Dscalacv.trackOwnership=true` and the error carries, as its cause, the stack of the call that actually consumed the handle:

```sh
java -Dscalacv.trackOwnership=true -jar your-app.jar
```

It is off by default because it allocates a `Throwable` every time a handle is spent; the read happens only on the already-failing path, so a correct program pays nothing. See [Mat lifecycle](/mat-lifecycle) for the full ownership story.

## `Image.read` returns `Left(DecodeFailed)` — but the file looks fine {#decode-failed}

`imread` does not throw on a missing file, a directory, or bytes it cannot decode — it returns an empty `Mat`, and scalacv turns that into a `Left` so you cannot accidentally run a pipeline on nothing. Check the `Either`:

```scala mdoc:silent
Image.read("does-not-exist.png") match
  case Right(img) => img.close()
  case Left(err)  => () // err names the path and the likely cause
```

If a *real* image reports `DecodeFailed`, the usual culprits are:

| Culprit | Tell | Fix |
| --- | --- | --- |
| Wrong working directory | a relative path that "exists" in your editor | pass an absolute path, or check `new java.io.File(path).getAbsolutePath` |
| Unsupported format | an exotic extension (`.heic`, some `.tiff`) | this OpenCV build has no decoder; re-encode, or add the codec |
| A URL or classpath resource | `http://…` or `classpath:…` | OpenCV understands neither — download to a file first, then read |
| Zero-byte / truncated file | a half-finished download | re-fetch; `Image.decode` rejects empty byte arrays up front |

`DecodeFailed` is specifically about image *bytes*. A model, cascade, or video source that will not load reports `LoadFailed` instead — see [the error model](/error-model) for the distinction.

## `Cascades.load` returns `Left(LoadFailed)` on Windows {#cascades-windows}

Everywhere except Windows, the Haar cascade XML files ship *inside* the bytedeco classifier jar, under `share/opencv4/haarcascades/`, and `Cascades.load` extracts the one you name to a cache directory before handing it to OpenCV. The `windows-x86_64` jar is the exception: its `share/` directory is **empty** and contains no cascade XML at all, so on Windows there is nothing to extract and `Cascades.load` can only fail. This is a property of the upstream jar, not of your build — no dependency you add will fix it.

The failure is a `Left`, never a throw, and it says so in those words (line-wrapped here to fit the page):

```text
could not load '/org/bytedeco/opencv/windows-x86_64/share/opencv4/haarcascades/haarcascade_frontalface_alt.xml':
the bytedeco OpenCV jar for windows-x86_64 ships no Haar cascades at all — its
share/ directory is empty, unlike every other platform's. Ship the cascade XML with your own
application and use Cascades.loadFrom(path), or use a detector that does not need one.
```

The workaround is the one the message names. Download the cascade you need from OpenCV's own repository — the file for frontal faces is [`haarcascade_frontalface_alt.xml`](https://github.com/opencv/opencv/blob/master/data/haarcascades/haarcascade_frontalface_alt.xml) — ship it alongside your application, and load it by path with `Cascades.loadFrom` instead of by name with `Cascades.load`:

```scala mdoc:compile-only
// `loadFrom` takes a filesystem path; `load` takes a CascadeName and extracts from the jar.
// Both return the same Either[CvError, Managed[CascadeClassifier]], so nothing downstream changes.
val cascade = Cascades.loadFrom("cascades/haarcascade_frontalface_alt.xml")

cascade match
  case Right(classifier) => classifier.use(c => println(s"cascade is usable: ${!c.empty()}"))
  case Left(err)         => println(s"still no cascade: $err")
```

`loadFrom` checks the loaded classifier is not empty, so a typo in that path comes back as a `Left` too rather than as a detector that silently finds nothing forever. If you would rather not ship a file at all, the detectors that need **no** model file work identically on every platform: QR codes (`image.qrCodes`) and ArUco markers (`image.arucoMarkers()`). YuNet face detection (`image.faces`) also avoids cascades, but it is a neural network and needs its own model download — see [Object detection](/object-detection).

The same `LoadFailed` shape appears on non-Windows platforms for a different reason: if only the classifier-less `org.bytedeco:opencv` jar is present, the cascades are not on the classpath either. That message names your platform and prints the dependency line to add — see [natives missing](#natives-missing).

## `cannot open …/delete(long)` / `add-opens` in the error {#add-opens}

OpenCV was loaded from a **named module** rather than the classpath, so reflection cannot reach the private `delete(long)` scalacv uses to free the 185 detector types. Note *when* this fires: it is thrown at the moment a handle is **freed** — typically at the end of a `Managed.use` block, in the middle of a working pipeline — not at `OpenCv.load()`.

The error prints the exact flag, computed from the offending class's own module and package, so add the one it names:

```sh
--add-opens org.bytedeco.opencv/org.opencv.objdetect=ALL-UNNAMED
```

That is the flag for a `CascadeClassifier` or a `QRCodeDetector`; a `Net` asks for `--add-opens org.bytedeco.opencv/org.opencv.dnn=ALL-UNNAMED` instead. The module name is `org.bytedeco.opencv`, not `org.opencv`: the OpenCV Java classes live in packages named `org.opencv.*`, but they are *shipped* by the bytedeco artifact, whose `module-info` declares `module org.bytedeco.opencv`. `--add-opens` takes the module, then the package — and here the two do not share a prefix, which is exactly the pair that is easy to get wrong. You never have to work it out yourself: the error message prints the flag it needs, computed from the offending class's own module at the moment it failed. It is never `java.base/java.lang` — the field being opened is OpenCV's own `nativeObj`, not a JDK internal.

On the classpath — the normal case — there is no module to open at all, so the flag would be meaningless; if you somehow see this failure there, the message says exactly that and asks for a bug report rather than printing a flag with a `null` module name in it. So the simplest fix, when you have the choice, is to run OpenCV on the classpath rather than as a module.

## `Recorder.open` returns `Left` — "codec may be unavailable" {#codec}

`VideoWriter` reports a missing codec by refusing to open, not by throwing — so scalacv returns `Left(CvError.LoadFailed)`. Whether a codec works depends on what your platform's OpenCV build links (FFmpeg, the OS frameworks). The portable fallback encodes with the always-built-in codecs:

```scala mdoc:compile-only
Recorder.open("out.avi", Size(640, 480), fps = 30, codec = Codec.Mjpg)
```

`Codec.Mjpg` with an `.avi` extension works anywhere `Mp4v`/`Avc1` do not:

| Codec | Container | Portability |
| --- | --- | --- |
| `Codec.Mjpg` | `.avi` | works everywhere — the safe fallback |
| `Codec.Mp4v` | `.mp4` | needs the platform's MPEG-4 encoder |
| `Codec.Avc1` | `.mp4` | needs H.264 — often absent in a bare build |

If even `Mjpg` fails, the path itself is unwritable — check the parent directory exists. See [Video](/video) for the capture-and-record loop.

## RSS keeps climbing but the heap is small / no test fails {#native-leak}

That is the classic native leak: a `Mat` frees its off-heap buffer only when you release it, and the GC never sees the pressure, so nothing fails — memory just grows. Put a ceiling on **physical** bytes (RSS-based; `maxBytes` alone would *not* catch it, see [Performance](/performance#measuring-memory-do-it-right)) and a leak fails fast:

```sh
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

Then bisect by wrapping suspects in `Managed.use`, and reach for the scoped entry points that close for you:

| Instead of holding… | Use… | It closes… |
| --- | --- | --- |
| `Image.read(p)` then forgetting | `Image.reading(p)(use)` | the image, even if the body consumed it |
| `Camera.open(i)` in a loop | `Camera.using(i)(use)` | the camera and its capture |
| `Video.frames` and retaining a `Mat` | `Video.framesCopied` / `Camera.foreach` | each owned copy per iteration |
| a raw `Mat` | `Managed.use(mat)(f)` | the Mat on the way out |

A borrowed mask passed to `applyMask`/`inpaint`/`blend`/`blurBackground`/`seamlessCloneInto` is **not** consumed — you must `.close()` it yourself. That is a common source of a slow leak. See [Testing](/testing#guard-against-native-leaks-with-an-rss-assertion) to gate it in CI.

## I called `.close()` and it still leaked {#closed-the-wrong-handle}

You closed the **spent** handle instead of the live one. This is the same move-semantics rule as [above](#move-semantics), seen from the other side: there, reusing a spent `Image` threw an exception you could not miss; here, closing a spent `Image` does nothing at all and says nothing about it, so the leak is silent.

The mechanism is short. A **transform** — `gray`, `blur`, `canny`, `resize`, any `draw*` — *consumes* the `Image` you called it on and returns a **new** `Image` that owns the pixels. Releasing a handle is idempotent by design (a second release is a harmless no-op rather than a double free, which would crash the JVM from native code). Put those two facts together and closing the receiver after a transform is a no-op on an already-spent handle, while the result nobody closed keeps its Mat alive:

```scala mdoc:silent
val source = Image.blank(64, 64)
val greyed = source.gray // a transform: it spends `source` and returns a new Image
source.close()           // no-op — `source` was already spent by `.gray`, and release is idempotent
```

Nothing throws, no test fails, and one Mat's worth of off-heap pixels is now unreachable and unfreed. The fix is to close the **survivor** of the chain — the last handle, the one still holding live pixels:

```scala mdoc:silent
greyed.close() // this is the handle that owns the memory
```

Three ways to stop hitting this at all:

- **Write the chain as a chain.** `Image.blank(64, 64).gray.canny(50, 150)` threads one live handle from left to right, so there is exactly one value at the end and exactly one thing to close — no spent intermediate is left lying around with a name you could close by mistake.
- **Remember which side of a `.mat` borrow you are on.** `image.mat` *borrows* the underlying OpenCV matrix: the `Image` still owns it and is still live, so the `Image` is what you close, and you must not call `release()` on the borrowed `Mat`. But if you wrote `img.gray.mat`, the borrow came from the anonymous greyscale `Image` the transform produced — and there is no longer a name in scope to close. Bind the transform result first (`val grey = img.gray`), then borrow from `grey`, then close `grey`. See [Working with the raw OpenCV API](/opencv-java).
- **Let a scope do it.** `Image.reading(path)(use)`, `Camera.using(index)(use)` and `Managed.use(mat)(f)` close on every path — success, failure and exception — and close the right handle even if the body consumed it.

Note that `-Dscalacv.trackOwnership=true` does not help here: it annotates the *exception* thrown when a spent handle is used, and this failure throws nothing. What it is good for is the neighbouring case — if some later line does throw "already been released or consumed", the flag names the call that spent the handle, which usually also tells you which handle you should have been closing. The full ownership story, including which verbs are queries (they borrow) and which are transforms and terminals (they consume), is in [Mat lifecycle](/mat-lifecycle#the-cheat-sheet).

## `drawText` drew nothing / text is in the wrong place {#drawtext}

OpenCV anchors text on the **baseline's left end**, not the top-left corner — a `y` of 0 puts almost the whole string *above* the image, out of frame. Give the baseline enough headroom:

```scala mdoc:silent
val labelled = Image
  .blank(200, 60)
  .drawText("hello", Point(10, 40), Scalar.White, scale = 1.0) // y=40, not 0
labelled.close()
```

Use [`Draw.textSize`](/drawing) to measure a string first and place it exactly. Two more gotchas:

- Only the Hershey **vector** fonts exist — there is no TrueType. Non-ASCII characters render as `?`.
- A `scale` too small on a large image draws text you cannot see; scale to the image, not to a fixed pixel size.

## A transform threw `CvError.NativeCall` mid-chain {#native-call}

OpenCV rejected the pixels for a reason scalacv cannot foresee — a wrong type/channel combination for that operation, say. Transforms do not return an `Either`, so this is an unchecked throw that names the operation. To fold it into an `Either`, wrap the chain in `Cv.attempt` — or use `Image.reading`, which does that for you:

```scala mdoc:silent
val r: Either[CvError, Int] =
  Cv.attempt("measure")(Image.blank(16, 16).gray.canny(50, 150).mat.rows)
```

```scala mdoc:compile-only
// Image.reading runs the whole body inside Cv.attempt, so a mid-chain throw comes back as Left:
Image.reading("photo.jpg")(_.gray.canny(80, 160).write("edges.png"))
```

If instead you see an `IllegalArgumentException` (an even Gaussian kernel, a negative radius, an empty `Mat`), that is a *programmer error* — a bug to fix, deliberately kept outside the `Either`. [The error model](/error-model) draws exactly where that line sits.

## GraalVM native-image doesn't work {#graalvm}

Known limitation — the reflective release layer and the runtime native extraction are exactly what a static image forbids. See [Mat lifecycle](/mat-lifecycle#graalvm-native-image-is-not-supported-today) for the three concrete blockers.

## Still stuck?

The [API reference](/api/core/index.html) documents every method, and most pages here end with a "Next" trail. If a `CvError` message or an assertion is not clear, that is a doc bug worth reporting.

## Next

- Why a failure is sometimes a value and sometimes a throw: [The error model](/error-model).
- The ownership rules behind the "released or consumed" error: [Mat lifecycle](/mat-lifecycle).
- Gating leaks and skipping absent hardware in CI: [Testing](/testing).
