---
title: Getting models
description: How to obtain the ONNX model files scalacv's detectors need — Models.fetch, ModelSpec, the pinned checksums, the built-in specs, and which model each capability wants.
---

# Getting models

Some of what scalacv can do needs nothing but the library: contours, colour masks, optical flow, ArUco
markers, QR codes. The rest — face detection, face recognition, body pose, hand landmarks, background
segmentation, any [neural network](/dnn) at all — needs a **model file**: a `.onnx` file holding the
weights somebody else trained. **scalacv ships none of them.**

An ONNX file (Open Neural Network Exchange — the interchange format the training frameworks export to)
is the *whole* model: its graph and its numbers, in one file. OpenCV's `dnn` module reads it and runs the
forward pass on plain CPU. Nothing about that is hard; the hard part, and the part every other page on
this site hands off to you, is getting the file onto disk and knowing it is the file you meant.

That is what this page is for: **how to fetch a model, how to prove it is intact, where to put it, and
which model each capability actually wants.**

:::tip[The 30-second version]
`Models.fetch(spec, dir)` downloads a `ModelSpec` into `dir`, checks its SHA-256, and hands back the
path. It tries mirrors in order, accepts `file://` as well as `https://`, writes to a temp file and only
moves it into place once it verifies, and is idempotent — a correct file already on disk is returned
without touching the network. Two specs ship ready-made: `FaceDetect.modelSpec` and
`FaceRecognizer.modelSpec`.
:::

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
```

## What ships in the jars, and what does not

Three different kinds of payload get confused with each other. Only one of them is a download:

| Payload | In the jars? | How you get it |
| --- | --- | --- |
| OpenCV native libraries (`.so` / `.dylib` / `.dll`) | **yes**, in the bytedeco classifier jars | extracted on the first `OpenCv.load()` — see [the native cache](/native-cache) |
| Haar / LBP cascade XML | **yes**, in the same jars | `Cascades.load(CascadeName.FrontalFaceAlt)` — no network, no download |
| ONNX models (YuNet, SFace, pose, hand, segmentation, your own) | **no** | this page |

So the cascades are the one detector family that works on a machine with no internet access and no
provisioning step at all. They are also markedly less accurate than YuNet — that is the trade, and
[Object detection](/object-detection) spells it out.

:::warning[One exception to "the cascades always work": Windows]
The `windows-x86_64` bytedeco jar ships an **empty** `share/` directory and no cascade XML at all, so
`Cascades.load` returns a `Left(CvError.LoadFailed)` there — and says so in those words. On Windows you
ship the XML with your own application and call `Cascades.loadFrom("path/to/haarcascade_frontalface_alt.xml")`.
Every other platform needs nothing.
:::

## Fetching into a fixed directory

`FaceDetect.downloadModel` delegates to `Models.fetch` with the built-in YuNet spec. Both use the same downloader and verification. For concurrent callers or model revisions sharing a filename, use the additive [content-addressed cache](#content-addressed-cache).

| You want | Call | Verifies | Where the pinned values live |
| --- | --- | --- | --- |
| the YuNet face detector | `FaceDetect.downloadModel(dir)` | exact byte size, **then** SHA-256 | `FaceDetect.ModelSizeBytes`, `FaceDetect.ModelSha256` |
| anything else | `Models.fetch(spec, dir)` | SHA-256, when the spec pins one | the `ModelSpec` you (or scalacv) wrote |

### `FaceDetect.downloadModel` — the one dedicated one-liner

```scala mdoc:compile-only
import java.nio.file.Path

val yunetPath: Either[CvError, Path] = FaceDetect.downloadModel(Path.of("models"))
```

The argument (the parameter is called `into`) is a **directory**, created if it is absent; the file name
is fixed at `face_detection_yunet_2023mar.onnx`, which is what makes the "is it already here and still
correct?" check possible at all. Give it a directory that only your application writes to.

### `Models.fetch` — the general form

`Models.fetch` takes a [`ModelSpec`](#modelspec) and does the same thing for any model:

```scala mdoc:compile-only
import java.nio.file.Path

val sfacePath: Either[CvError, Path] = Models.fetch(FaceRecognizer.modelSpec, Path.of("models"))
```

Prefer this one as soon as you fetch more than one model — the loop over several specs is then the same
three lines rather than one special case plus a loop.

### Content-addressed cache {#content-addressed-cache}

`Models.fetchCached` keeps pinned revisions separate and coordinates concurrent callers:

```scala mdoc:compile-only
val cachedModel: Either[CvError, java.nio.file.Path] =
  Models.fetchCached(FaceRecognizer.modelSpec, java.nio.file.Path.of("model-cache"))
```

The path is `cacheRoot/sha256/<lowercase digest>/<fileName>`. The root is your namespace: use separate access-controlled roots for private models from unrelated tenants. Only an exact 64-hex-digit SHA-256 and a single safe filename are accepted. Unverified specs, separators, drive prefixes and malformed digests are rejected before cache directories are created.

There is no TTL. A new digest selects a new entry; hits rehash the bytes under the lock, so tampering/corruption is still detected. Concurrent successful misses for the same target result in one download; failed downloads are not memoized. Each caller still pays verification cost. Fetch at startup, not on every frame.

Coordination uses a fixed set of JVM locks and at most 64 persistent `.locks` files per root. Processes sharing the same root need a filesystem with process file-lock and atomic-move support. Stripe collisions may serialize unrelated models. Root aliases are supported, but symlinks inside the cache layout are rejected. The root and its ancestors must remain application-controlled: this is not protection against a hostile process rewriting the filesystem during a call.

Do not delete lock files or the root while callers are active or waiting. There is no automatic disk eviction; perform quota cleanup while the cache is quiescent, retaining any files still needed by readers. A returned path is not a lease against later external deletion or modification.

### What "verified" means, step by step

Both paths run the same sequence, and each step exists because of a failure mode that has actually
happened to somebody:

1. **Is it already here?** If `into/<fileName>` is a regular file *and* it still hashes to the pinned
   value, that path is returned immediately. No network, no re-download. This is what makes it safe to
   call at every start-up.
2. **Create the directory** if it does not exist. A failure here is a
   `Left(CvError.LoadFailed(dir, "could not create the download directory: …"))`.
3. **Try each URL in order.** The first one that downloads *and* verifies wins. Every failure is
   collected, so if all of them fail you get one `Left` listing what each mirror did.
4. **Download to a temp file beside the target**, not to the target. The temp file is a sibling named
   `.model-<random>.part`, so the final move stays inside one filesystem.
5. **Verify the temp file**, before publishing those bytes.
6. **Atomically move it onto the target** — and only then. Both fetch APIs fail closed if the filesystem
   cannot perform atomic replacement; there is no non-atomic copy/delete fallback. The old target remains
   intact on an unsupported move. This is atomic visibility, not fsync-backed crash durability. The checksum
   is checked before the returned path is handed to OpenCV.
7. **Delete the temp file** on the way out, whatever happened.

:::note[Timeouts you inherit]
`Models.fetch` gives each mirror a **15-second** connect budget and a **60-second** budget for the whole
download (headers and body), and follows HTTP redirects. `FaceDetect.downloadModel` delegates to the same downloader and has the same budgets. Mirrors are tried in order, so a mirror that hangs costs
you its timeout and then the next one is tried. Do not wrap either call in your own retry loop; the
retry across mirrors is already there.
:::

## Writing a `ModelSpec` for your own model {#modelspec}

A `ModelSpec` carries the filename, mirrors and integrity expectations:

| Field | Type | What it is |
| --- | --- | --- |
| `fileName` | `String` | the fixed name the file gets on disk — a single path component (no separators, `.`/`..`); escaping names are rejected with `Left(LoadFailed)` before anything touches the filesystem. The legacy `fetch` cache keys by this name within `into` |
| `urls` | `Seq[String]` | the mirrors to try, **in order**. `http://`, `https://` and `file://` all work |
| `sha256` | `Option[String]` | the pinned digest, lower- or upper-case. `Some` for `ModelSpec(...)`, `None` for `ModelSpec.unverified(...)` |
| `sizeBytes` | `Option[Long]` | optional exact positive byte count; checked before hashing, not a download-size cap |

`ModelSpec` has a private constructor and two named builders, so the verifying form is the one you reach
for by default and the unverified form is something you have to ask for by name:

```scala
ModelSpec(fileName: String, urls: Seq[String], sha256: String): ModelSpec   // verifying — the default
ModelSpec.unverified(fileName: String, urls: Seq[String]): ModelSpec        // no integrity check
```

Both reject an empty `fileName` and an empty `urls` with an `IllegalArgumentException` at construction —
a spec with no source is a programmer error, not a runtime failure to handle.

### The recipe, end to end

1. **Pick your source.** A release asset, a model-zoo URL, an object-storage path, a file you already
   have. Anything reachable by URL.
2. **Download it once, by hand**, and look at it. Is it the size the publisher says? Is it actually ONNX
   and not, say, an HTML "sign in to continue" page?
3. **Hash it.**

   ```sh
   sha256sum my-model.onnx                       # Linux
   shasum -a 256 my-model.onnx                   # macOS
   certutil -hashfile my-model.onnx SHA256       # Windows
   ```

4. **Write the spec**, pasting that digest in.
5. **Call `Models.fetch`** at start-up and gate readiness on the `Right`.

```scala mdoc:compile-only
import java.nio.file.Path

val myModel = ModelSpec(
  fileName = "yolov8n-v3.onnx",
  urls = Seq(
    "file:///opt/models/yolov8n-v3.onnx",                     // the local copy, if the image has one…
    "https://models.example.com/yolov8n-v3.onnx"              // …otherwise the network
  ),
  sha256 = "0000000000000000000000000000000000000000000000000000000000000000" // paste yours here
)

val myModelPath: Either[CvError, Path] = Models.fetch(myModel, Path.of("models"))
```

The order of `urls` is the order they are tried, so putting a `file://` first means a machine that
already has the model never opens a socket, while a machine that does not falls through to the network —
the same code, the same verification, on both.

### Prove it to yourself, with no download

You do not need a network or a real model to see the whole mechanism work: `file://` is just another
source, so a few bytes in a temporary directory make a complete, honest demonstration. Everything in this
section really runs when this page is built.

```scala mdoc:silent
import java.nio.file.Files
import java.security.MessageDigest

// Stand in for "a model file I already have": a handful of bytes in a temp directory.
// Files.write returns the path it wrote, which is exactly the path we want to point a spec at.
val sourceDir = Files.createTempDirectory("scalacv-docs-source")
val sourceFile =
  Files.write(sourceDir.resolve("pretend-model.onnx"), "not a network, but it hashes like one".getBytes)

// Exactly the digest `sha256sum` would print for that file.
val pretendSha =
  MessageDigest
    .getInstance("SHA-256")
    .digest(Files.readAllBytes(sourceFile))
    .map(b => f"$b%02x")
    .mkString

val pretendSpec =
  ModelSpec(fileName = "pretend-model.onnx", urls = Seq(sourceFile.toUri.toString), sha256 = pretendSha)
```

Fetch it into a fresh directory:

```scala mdoc:silent
val modelDir = Files.createTempDirectory("scalacv-docs-models")
val fetched = Models.fetch(pretendSpec, modelDir)
```

```scala mdoc
fetched.map(_.getFileName.toString)
```

And now the two properties the rest of this page keeps claiming. **Idempotent** — the second call finds
the file, re-hashes it, and returns the same answer without reading the source again:

```scala mdoc
Models.fetch(pretendSpec, modelDir) == fetched
```

**Verified** — the same bytes under a wrong pinned hash are refused:

```scala mdoc:silent
val tamperedSpec = ModelSpec("pretend-model.onnx", Seq(sourceFile.toUri.toString), "00" * 32)
val rejectedDir = Files.createTempDirectory("scalacv-docs-rejected")
```

```scala mdoc
Models.fetch(tamperedSpec, rejectedDir).isLeft
```

That `Left` is a `CvError.LoadFailed` whose details read
`could not be downloaded from any source.` followed by one indented line per mirror — here,
`SHA-256 mismatch for …: expected 0000…, got <the real digest>`. Nothing was moved into `rejectedDir`: the
temp file is deleted and the target is never created.

### `file://` and air-gapped deployments

Because `file://` is a first-class source **and the hash is still checked**, an offline deployment is not
a special case in the code — it is a spec whose only URL is local:

```scala mdoc:compile-only
val offlineSpec = ModelSpec(
  fileName = "face_detection_yunet_2023mar.onnx",
  urls = Seq("file:///opt/models/face_detection_yunet_2023mar.onnx"),
  sha256 = FaceDetect.ModelSha256
)
```

Bake the file into the container image (or mount it read-only), list only the `file://` URL, and keep the
pinned digest. You get a real integrity gate — a corrupted layer or a mounted volume pointing at the
wrong file fails at start-up with a named error instead of misbehaving at inference time. See
[Deploying to production](/deploying-to-production) and [the native cache](/native-cache) for where this
sits in a container build.

### Opting out of the checksum

Sometimes a model has no published digest. `ModelSpec.unverified(fileName, urls)` builds a spec with no
integrity check at all:

```scala mdoc:compile-only
val noChecksum = ModelSpec.unverified("some-model.onnx", Seq("https://models.example.com/some-model.onnx"))
```

It is a deliberate, named opt-out: the word `unverified` is right there in the diff, so nobody can reach
this behaviour by accident. It also costs more than you would expect:

:::danger[`unverified` disables cache invalidation, not just integrity]
`Models.fetch` keys its cache on **`into.resolve(spec.fileName)` and nothing else**, and the "is the file
on disk still good?" test is *"does it match the pinned hash?"*. With no hash pinned, that test is
vacuously true for **any** file already sitting at that path.

The consequences, in order of how much they hurt:

- **Same name, same hash** → no network. What you want.
- **Same name, new hash** → the stale file fails verification, the model is re-downloaded and replaces it.
  This is how a genuine model upgrade rolls out.
- **Same name, `unverified`** → whatever is on disk is served **forever**. The URL is never contacted
  again. Ship a new model under the old file name and every replica that has ever run keeps answering with
  last quarter's weights, silently, with no error anywhere.

The fix is a one-word habit: **put the version in the file name** — `"yolov8n-v3.onnx"`, not
`"yolov8n.onnx"`. Two versions then coexist on disk, a rollback is a config change rather than a cache
purge, and a rolling deploy never has two replicas fighting over one path. Do that whether or not you pin
a hash. See [the native cache](/native-cache) for the deployment side of the same story.
:::

## The two built-in specs {#built-in-specs}

scalacv pins two models itself, because two of its APIs are useless without them. Both carry a checksum,
so you never write these by hand:

```scala mdoc:silent
val yunetSpec: ModelSpec = FaceDetect.modelSpec        // YuNet — face detection
val sfaceSpec: ModelSpec = FaceRecognizer.modelSpec    // SFace — face recognition
```

```scala mdoc
(yunetSpec.fileName, FaceDetect.ModelSizeBytes, yunetSpec.urls.size)
```

```scala mdoc
(sfaceSpec.fileName, sfaceSpec.sha256)
```

| | YuNet | SFace |
| --- | --- | --- |
| What it does | finds faces, with five landmarks each | turns an aligned face into a 128-value embedding |
| File | `face_detection_yunet_2023mar.onnx` | `face_recognition_sface_2021dec.onnx` |
| Size | 232,589 bytes (232 kB) | ~37 MB |
| Pinned SHA-256 | `8f2383e4…52fa4` (`FaceDetect.ModelSha256`) | `0ba9fbfa…4e79` (`FaceRecognizer.modelSpec.sha256`) |
| Mirrors in the spec | 2 (a commit-pinned URL, then `main`) | 1 |
| Fetch it with | `FaceDetect.downloadModel(dir)` or `Models.fetch(FaceDetect.modelSpec, dir)` | `Models.fetch(FaceRecognizer.modelSpec, dir)` |
| Then load it with | `FaceDetect.create(path, inputSize)` | `FaceRecognizer.load(path)` |
| Guide | [Object detection](/object-detection#yunet-the-modern-face-detector) | [Face recognition](/face-recognition) |

YuNet's first mirror pins the exact commit that last touched the file, so those bytes cannot change under
you; the second follows `main` and exists only so that a repository reorganisation degrades to a fallback
instead of an outage. Neither is trusted — the checksum decides.

## The Git-LFS trap {#git-lfs}

This one is worth reading once even if you never touch the OpenCV Zoo, because the failure looks like a
successful download.

Large files in a Git repository are usually stored with **Git LFS** (Large File Storage): the repository
itself contains a ~131-byte *pointer* text file, and the real bytes live on a separate media host. The
OpenCV Zoo keeps its `.onnx` files this way. If you fetch such a file from a `raw.githubusercontent.com`
URL, you get **HTTP 200 and the pointer**, not the model. Nothing errors. You have a 131-byte file called
`something.onnx` that OpenCV then refuses to load as a network, with a message about the graph rather
than about the download.

Two defences, both already in the code:

- `FaceDetect.ModelUrls` uses `media.githubusercontent.com/media/…`, the host that serves the real
  object, rather than the `raw` host that serves the pointer. (The pointer does at least carry an
  `oid sha256:` line — which is where the pinned digest was cross-checked from.)
- `FaceDetect` additionally pins `ModelSizeBytes` and checks the **size before the hash**, so a 131-byte
  pointer is reported as *"expected 232589 bytes … but got 131 — the download is truncated, or the server
  answered with something that is not the model"* rather than as an opaque digest mismatch.

`ModelSpec.sizeBytes` enables the same size-before-hash check for any model. Without a size pin, a pointer, an HTML
error page and a corrupted transfer are all detected by the digest check:

```text
SHA-256 mismatch for <file>: expected <the pinned digest>, got <digest of whatever arrived>
```

If you see that, look at the file size on disk first. A three-digit byte count means you got a pointer or
an error page, not a corrupt model.

:::note[The SFace spec has a single mirror, and it is a raw-style URL]
`FaceRecognizer.modelSpec`'s single URL is of the form `https://github.com/opencv/opencv_zoo/raw/main/…`.
GitHub redirects that form to the media host for LFS objects and `Models.fetch` follows redirects, so it
normally lands on the real 37 MB file. If it ever does not — a checksum mismatch on a suspiciously small
file — fetch it by hand from the media host and point a local spec at your copy:

```scala mdoc:compile-only
val sfaceFallback = ModelSpec(
  fileName = "face_recognition_sface_2021dec.onnx",
  urls = Seq(
    "file:///opt/models/face_recognition_sface_2021dec.onnx",
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/" +
      "models/face_recognition_sface/face_recognition_sface_2021dec.onnx"
  ),
  sha256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
)
```
:::

## Which model do I need? {#capability-table}

The other guides on this site tell you what to *do* with a model. This table tells you what to *look for*
and how to configure it. The three blob parameters are the ones that go wrong silently: get them wrong
and the model runs, produces numbers, and is quietly worse rather than broken — see
[`blobFromImage`](/dnn#making-a-blob-blobfromimage) for why.

| Capability | Model family to look for | Typical `inputSize` | `scaleFactor` / `mean` / `swapRB` | What decodes the output |
| --- | --- | --- | --- | --- |
| **Face detection** | YuNet, from the OpenCV Zoo — [spec built in](#built-in-specs) | the frame size you expect, e.g. `Size(320, 320)` | handled inside `FaceDetectorYN` | `FaceDetect.create` → `image.faces(detector)` |
| **Face recognition** | SFace, from the OpenCV Zoo — [spec built in](#built-in-specs) | fixed by the model | handled inside `FaceRecognizerSF` | `FaceRecognizer.load` → `rec.embed(image, face)` |
| **Body pose, regression** | a MoveNet-style single-pose ONNX export | `Size(192, 192)` (MoveNet Lightning) | `1.0 / 255` / `Scalar(0, 0, 0)` / `true` | `PoseEstimator.decode(out, size, KeypointLayout.Regression, PoseTopology.CocoBody17)` |
| **Body pose, heatmap** | an OpenPose-style export | whatever the model documents | whatever the model documents | `PoseEstimator.decode(out, size, KeypointLayout.Heatmap, …)` |
| **Hand landmarks** | a MediaPipe hand-landmark export, converted to ONNX | `Size(224, 224)` | `1.0 / 255` / `Scalar(0, 0, 0)` / `true` | `PoseEstimator.decode(…, PoseTopology.Hand21)`, then `GestureRecognizer` |
| **Selfie segmentation** | MediaPipe selfie-segmentation, MODNet or U²-Net, as ONNX | `Size(256, 256)` | `1.0 / 255` / `Scalar(0, 0, 0)` / `true` | `Segmenter.decodeMask(out, size)` — accepts `[1, 1, H, W]` and `[1, 2, H, W]` |
| **Classification, generic detection, depth, …** | any ONNX export | whatever the model documents | whatever the model documents | none — read the tensor yourself with [`Dnn`](/dnn) |

Notes on that table, because a table cannot carry caveats:

- **`swapRB` is the one to double-check.** Almost every published model was trained on RGB, while OpenCV
  decodes images to **BGR**. `Dnn.blobFromImage` defaults it to `false` (that is OpenCV's own default and
  silently disagreeing with upstream documentation would be worse), while the one-call helpers
  `Image.estimatePose` and `Image.segment` default it to `true` (that is what their models want). If your
  keypoints are plausible but consistently a bit off, this is the first thing to flip.
- **`inputSize` for YuNet is not a constraint.** `FaceDetect.create` requires one, but `FaceDetect.detect`
  re-sets it on every frame, so any image size works. It still matters, because YuNet's anchors are laid
  out for it — pass the size of the frames you actually expect.
- **A topology is not in the file.** A model's tensor says "17 keypoints"; it does not say which one is
  the left wrist. `PoseTopology` supplies that, and its `size` must match the model's keypoint count or
  `decode` fails with a named error. See [Decoding a model's output](/pose-estimation#decoding-a-models-output).

Where each of these is used in anger: [Pose estimation](/pose-estimation),
[Gesture & sign recognition](/gestures), [Video conferencing](/conferencing),
[Face recognition](/face-recognition), [Deep learning](/dnn).

## MediaPipe ships TFLite, and OpenCV does not read it {#tflite}

This is the single most common dead end on the way to a working pose or segmentation pipeline, so here it
is once, in full.

Google's **MediaPipe** publishes the obvious models for body pose, hand landmarks and selfie segmentation
— and publishes them as **TFLite** (`.tflite`), the TensorFlow Lite format. scalacv runs models through
OpenCV's `dnn` module, and scalacv exposes **only** the ONNX importer ([why](/dnn#only-onnx-and-why)).
A `.tflite` file will not load. There is no flag for this.

Two ways forward:

1. **Convert it.** `tf2onnx` reads TFLite and writes ONNX:

   ```sh
   pip install tf2onnx
   python -m tf2onnx.convert --tflite hand_landmark.tflite --output hand_landmark.onnx --opset 13
   ```

   Then hash the result and pin it in a `ModelSpec` exactly like any other file. Note that the digest is
   now *yours*: a conversion is not byte-reproducible across tool versions, so pin the artefact you
   actually tested, and give it a versioned file name.

2. **Find something already exported.** Plenty of pose, hand and segmentation networks are published as
   ONNX directly — MoveNet exports, MODNet, U²-Net, the OpenCV Zoo. If a model was trained in PyTorch it
   almost certainly has an ONNX export somewhere, because `torch.onnx.export` is one line.

Whichever route you take, the conversion is a **build-time** step. Do not try to convert at runtime; fetch
the converted artefact like any other model.

## Sanity-checking a model you have never run {#sanity-check}

You have a `.onnx` file. Before wiring it into a pipeline, ask it two questions: *what are its outputs
called*, and *what shape is the tensor it produces*. The shape is what tells you which decoder in the
[table above](#capability-table) applies.

```scala mdoc:compile-only
/** Runs one grey probe frame through a model and prints what came back. A diagnostic, not a pipeline. */
def describeOutput(modelPath: String, inputSize: Size): Either[CvError, Unit] =
  Dnn.fromOnnx(modelPath).map { managedNet =>
    managedNet.use { net =>
      // The blob *names* you may pass as `outputName` to Dnn.forward. For an ONNX import these are the
      // graph's declared outputs, not the `onnx_node!…` layer names.
      println(s"outputs: ${net.getUnconnectedOutLayersNames()}")

      // A mid-grey frame is enough: we are asking about the tensor's shape, not its values.
      val probe = Image.blank(inputSize.width.toInt, inputSize.height.toInt, Scalar(128, 128, 128))
      try
        Dnn
          .blobFromImage(probe.mat, scaleFactor = 1.0 / 255, size = Some(inputSize), swapRB = true)
          .use { blob =>
            Dnn.forward(net, blob).use { out =>
              val shape = (0 until out.dims()).map(i => out.size(i))
              println(s"output: ${out.dims()} dims, shape ${shape.mkString("[", ", ", "]")}")
            }
          }
      finally probe.close()
    }
  }

val checked: Either[CvError, Unit] = describeOutput("models/unknown.onnx", Size(256, 256))
```

Read the printed shape against this:

| Printed shape | What it is | Decode with |
| --- | --- | --- |
| `[1, 1, K, 3]` | K keypoints as `(y, x, score)`, normalised to `[0, 1]` | `KeypointLayout.Regression` with a topology whose `size` is K |
| `[1, K, H, W]` | one heatmap plane per keypoint | `KeypointLayout.Heatmap` |
| `[1, 1, H, W]` | one foreground-probability plane | `Segmenter.decodeMask` |
| `[1, 2, H, W]` | background / foreground; the **last** channel is the person | `Segmenter.decodeMask` |
| `[1, N]` | N class scores | your own arg-max — see [Reading the output](/dnn#reading-the-output) |
| `[1, N, 15]`-ish rows | detection boxes | your own decode plus NMS |

If the shape does not match what you expected, the usual causes are: the wrong model, the wrong
`inputSize` (a model with a fixed input rejects a differently-shaped blob), or a multi-output graph where
you need to name the output rather than take the last layer.

:::note[The decoders fail by name, not by riddle]
`PoseEstimator.decode` and `Segmenter.decodeMask` both validate the tensor *before* they reshape it, and
raise a `CvError.NativeCall` that says what it expected and what it got — for example *"expected 51 values
(K=17 keypoints × (y, x, score)) but the model produced 63. This is not the regression pose model this
topology decodes."* That is a much better starting point than OpenCV's raw total-size exception, and it is
almost always either the wrong `KeypointLayout` or a topology whose size does not match the network.
:::

## When a fetch fails {#errors}

Download and cache failures are `Left(CvError.LoadFailed(resource, details))` values, composing with
[the error model](/error-model). Cancellation is different: `InterruptedException` propagates with the
interrupt flag restored; it does not try another mirror. These are the messages you will see,
and what each one means:

| Message fragment | Where from | What it means |
| --- | --- | --- |
| `could not create the download directory:` | both paths | `into` is not writable, or a *file* already exists at that path |
| `could not be downloaded from any source.` | `Models.fetch` | every mirror failed; one indented line per URL follows |
| `HTTP 404 from <url>` | `Models.fetch` | wrong path on the mirror — any status ≥ 400 is reported in this form |
| `expected 232589 bytes … but got 131` | `FaceDetect.downloadModel` | the size pre-check fired: you got a pointer or an error page |
| `SHA-256 mismatch for … Refusing to load an unverified model.` | all fetch entry points | wrong bytes, or an LFS/HTML response without a size pin |
| a timeout message from the JDK HTTP client | all fetch entry points | the mirror was too slow for the request budget |
| `cached models require a 64-digit SHA-256 and a single safe filename` | `Models.fetchCached` | invalid cache identity; nothing was downloaded |
| `could not access model cache:` | `Models.fetchCached` | directory, symlink or process-lock failure |
| `there is no readable file at this path.` | `FaceDetect.create` | the loader's own pre-check: you never fetched the model, or you passed the directory instead of the file |
| `no such file — supply the SFace ONNX model path` | `FaceRecognizer.load` | the same, for SFace |

Diagnosis order, when it is a checksum mismatch: **size first, then content.** `ls -l` the file (or read
the byte count out of the message). Three digits means a pointer or an error page. Roughly the right size
but the wrong digest means the publisher moved the file under the same name, and your pin is now stale —
which is exactly the situation the pin exists to surface.

## What the downloader will not do for you

`Models.fetch` is a thin, deliberate wrapper over the JDK's `HttpClient`. It is not a download manager, and
being honest about the edges saves you a bad afternoon:

- **No authentication.** It sends a bare `GET` with no headers. A model behind a token, a private bucket
  or an artifact registry cannot be fetched with it. Download it with a tool that can authenticate, put
  it on disk, and point a `file://` spec at it — you keep the checksum gate that way.
- **No proxy configuration surface.** Whatever the JDK's `HttpClient` does by default in your environment
  is what you get; there is no knob here.
- **No resume, and no progress reporting.** Each mirror gets one request with a **60-second** budget
  for the whole download — headers and body — shared by `FaceDetect.downloadModel`; a mirror that answers
  the headers and then stalls mid-body is abandoned at the same deadline.
  A 37 MB model on a slow link can exhaust it, at which point *every* mirror reports a timeout and you
  get a `Left` that looks like an outage but is a bandwidth problem. If that is your situation, fetch the
  file with `curl -C -` or your package pipeline and use a `file://` spec.
- **No size limit.** The response body is written to the temp file before anything inspects it, so a
  mirror that serves ten gigabytes writes ten gigabytes to your disk and *then* fails the checksum. Point
  specs at hosts you trust.
- **A size pin is not a streaming limit.** `ModelSpec.sizeBytes` is checked after downloading; it improves validation and diagnostics, not disk-usage bounds.
- **No locking in legacy `fetch`.** Concurrent calls can duplicate downloads; different revisions sharing
  a filename can replace one another. Use `fetchCached` for digest-separated paths and process coordination.
- **Atomic publication requires filesystem support.** Unsupported atomic moves return a failure without
  a non-atomic fallback. Neither API promises durable persistence across power loss.
- **No conversion and no model validation.** It moves bytes and checks a digest. Whether those bytes are
  a network OpenCV can import is discovered by `Dnn.fromOnnx` / `FaceDetect.create` /
  `FaceRecognizer.load`, each of which returns its own `Left`.

## Fail fast at boot

The place to fetch models is start-up, next to `OpenCv.load()`, with readiness gated on the result. Both
calls are idempotent. Model cache hits still read and hash the entire file, so fetch once at startup rather than per frame:

```scala mdoc:compile-only
import java.nio.file.Path

/** Everything that must be on disk before this process serves a request. */
def warmUp(into: Path): Either[CvError, (Path, Path)] =
  OpenCv.load() // extract + load the natives; idempotent and thread-safe
  for
    detector <- Models.fetch(FaceDetect.modelSpec, into)
    embedder <- Models.fetch(FaceRecognizer.modelSpec, into)
  yield (detector, embedder)

val ready: Boolean = warmUp(Path.of("/var/lib/myapp/models")).isRight
```

A process that starts, passes its health check, and only discovers at the first request that the model
directory is empty is the failure mode this avoids. See
[Deploying to production](/deploying-to-production).

## Why nothing is vendored

The models are fetched rather than committed, and that is a **licensing** decision rather than a size one.
YuNet is MIT-licensed (Shiqi Yu). Shipping a copy inside this repository — or inside a published jar —
would oblige scalacv to reproduce that notice and carry that obligation on behalf of everyone who
redistributes the jar. Keeping the model a runtime download keeps the obligation where it belongs: with
whoever actually redistributes the weights. The same reasoning applies to every other model, and it is
recorded in `THIRD-PARTY.md`.

The practical consequence for you: **when you redistribute an application that bundles a model, you
inherit that model's licence, not scalacv's.** Read it before you bake the file into an image. Some model
zoos are MIT or Apache-2.0 and ask only for a notice; some are non-commercial; some are research-only.
The download is where that decision belongs to you, which is exactly why it is a download.

:::note[The same reasoning explains the missing test images]
This repository ships no photographs or video clips either, for the same licensing reason — which is why
every runnable example on this site *draws* its input with `Image.blank(...).drawRect(...)` instead of
reading a file.
:::

## Next

- [Deep learning (DNN)](/dnn) — load the model you just fetched, blob a frame, run it.
- [Object detection](/object-detection#yunet-the-modern-face-detector) — YuNet end to end, the model
  scalacv fetches for you.
- [Face recognition](/face-recognition) — SFace, and what to do with an embedding.
- [Pose estimation](/pose-estimation) and [Gesture & sign recognition](/gestures) — the two capabilities
  that need a converted model most often.
- [Video conferencing](/conferencing) — selfie segmentation and the mask it produces.
- [The native cache](/native-cache) and [Deploying to production](/deploying-to-production) — where models
  live in a container, and how to warm everything before the first request.
- [Run a neural network (ONNX)](/tutorial-dnn) — the tutorial this page unblocks.
