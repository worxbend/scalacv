# Object detection

**Object detection answers "what is in this image, and where?"** — it hands you back
rectangles (and sometimes landmarks or ids) marking the things it found. OpenCV's `objdetect`
module is a grab-bag of ready-made detectors for the everyday cases: faces, eyes, bodies,
licence plates, QR codes, and printed fiducial markers. You do not train anything; you point a
detector at an image and read the results.

scalacv wraps four of them behind a single, consistent contract: **you never own a native
handle, and every result crosses the boundary as ordinary immutable Scala data** — a
`Seq[Rect]`, a `Seq[Face]`, a `Seq[QrCode]`, a `Seq[ArucoMarker]`. Copy it out once and it stays
valid long after the source image is freed, so detector output is something you can
pattern-match, store in a `Map`, or send across threads (see [Geometry](/geometry) for why that
copy is the right trade).

:::tip[New here? Read this first.]
If you just want to find faces, skip to [YuNet](#yunet-the-modern-face-detector) — it is the
modern, accurate choice and needs only a 232 kB model download. Reach for Haar cascades when you
need to detect something YuNet does not (eyes, smiles, bodies, plates) or you cannot ship a model
file at all.
:::

## The four detectors at a glance

The four fall into two groups, and the split is the first thing to internalise:

| Detector | Finds | Needs | scalacv entry point | Returns |
|---|---|---|---|---|
| **Haar cascade** | faces, eyes, bodies, plates… | a cascade XML — shipped inside the OpenCV jar, nothing to fetch | `Cascades` + the `detect` extension | `Seq[Rect]` |
| **YuNet faces** | faces + 5 landmarks + score | an ONNX model, downloaded and checksum-verified at runtime | `FaceDetect` | `Seq[Face]` |
| **QR codes** | QR symbols + decoded text | nothing | `Qr` | `Seq[QrCode]` |
| **ArUco markers** | square fiducials + id | nothing | `Aruco` | `Seq[ArucoMarker]` |

The self-contained detectors (`Qr`, `Aruco`) build and free their own machinery inside a single
call — there is nothing to manage. The two model-driven ones (`Cascades`, `FaceDetect`) hand you
a `Managed[…]` for the loaded model, which you keep for as long as you detect and then release —
but the detections themselves are still plain data.

Every runnable example on this page assumes the imports and native load below, established once:

```scala mdoc:silent
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

```scala mdoc:invisible
lazy val faceDetector: org.opencv.objdetect.FaceDetectorYN = ??? // built via FaceDetect.create
```

## Haar cascades

The Viola–Jones cascades are the classic OpenCV detectors: a fast sliding-window classifier
trained from an XML file. They are cheap, need no download, and are showing their age next to
[YuNet](#yunet-the-modern-face-detector) — but they cover objects YuNet does not (eyes, smiles,
licence plates, full bodies) and they remain the right tool where no model file can be shipped.

### Minimal first example

The shortest path from nothing to a `Seq[Rect]`: load a face cascade, then run it. On a blank
canvas there is nothing to find, so the result is an empty `Seq` — which is a *result*, not an
error.

```scala mdoc:silent
val firstBoxes: Either[CvError, Seq[Rect]] =
  Cascades.load(CascadeName.FrontalFaceAlt).map { classifier =>
    classifier.use { c =>
      Managed.use(Mat(200, 200, CvType.CV_8UC1))(_.detect(c))
    }
  }
```

```scala mdoc
firstBoxes.map(_.size) // Right(0): a blank frame has no faces
```

### Cascade names are typed, not strings

Passing a cascade around as a filename has a nasty failure mode: a typo is *silent*. `new
CascadeClassifier("/frontalfaec.xml")` does not throw — it builds an **empty** classifier that
then detects nothing, forever, indistinguishable from an empty frame. `CascadeName` removes that
whole class of bug by making the name un-misspellable:

```scala mdoc
CascadeName.FrontalFaceAlt.fileName
```

```scala mdoc
CascadeName.values.map(_.toString).toList
```

`FrontalFaceAlt` is the usual first choice for faces — markedly fewer false positives than
`FrontalFaceDefault`. Here is what each named cascade is for:

| `CascadeName` | Detects | Notes |
|---|---|---|
| `FrontalFaceAlt` | frontal faces | recommended default — few false positives |
| `FrontalFaceAlt2` | frontal faces | alternative training, similar accuracy |
| `FrontalFaceDefault` | frontal faces | the original Viola–Jones; fast but permissive |
| `ProfileFace` | side-on faces | pairs well with a frontal cascade |
| `Eye` | eyes | run *inside* a detected face box |
| `EyeTreeEyeglasses` | eyes with spectacles | slower than `Eye` |
| `LeftEye2Splits` / `RightEye2Splits` | a single eye | subject's left/right |
| `Smile` | mouths that are smiling | run inside a face box |
| `FullBody` / `UpperBody` / `LowerBody` | pedestrians | whole / above waist / below waist |
| `RussianPlateNumber` | licence plates | the only non-anatomical cascade upstream ships |

The LBP cascades and the cat detectors are deliberately *not* named (a different model family and
a novelty, respectively), but both remain reachable through `Cascades.loadFrom` with a raw path.

:::note[Cascades compose — run one inside another's boxes]
Eyes and smiles are best found *inside* an already-detected face, not across the whole frame:
crop each face `Rect` out, equalise it, and run `Eye`/`Smile` on that submat. It is both faster
(less image to scan) and far more accurate (no eye-shaped clutter in the background).
:::

### Loading fails loudly

`Cascades.load` extracts the XML from the per-platform classifier jar — it is a classpath
resource, not a file on disk — and hands back a caller-owned `Managed[CascadeClassifier]`.
Crucially, where raw OpenCV would swallow a bad path, scalacv checks `empty()` and turns it into
a `Left`:

```scala mdoc
Cascades.loadFrom("/no/such/cascade.xml").isLeft
```

That is the whole point of the wrapper here. Raw `CascadeClassifier` returns you a silent
do-nothing object for that path; `loadFrom` (and `load`) tell you at the point of failure.
`resolve` is the extraction step on its own — useful before `OpenCv.load()`, since pulling a
resource out of a jar needs no native library:

```scala mdoc
Cascades.resolve(CascadeName.FrontalFaceAlt).map(_.getName)
```

:::warning[Windows ships no cascades]
The `windows-x86_64` bytedeco jar ships an *empty* `share/` directory and no cascades at all,
unlike every other platform. `resolve` (and therefore `load`) can only return a `Left` there, and
it says so in those words. If you target Windows, ship the cascade XML with your own application
and load it with `Cascades.loadFrom(path)`, or use a detector that needs no cascade file (YuNet,
QR, ArUco).
:::

`CascadeClassifier` is one of the OpenCV types with no public `release()`, so it is freed through
the `delete(long)` bridge. `Cascades.load` already returns a `Managed` that knows how to do that;
if you build your own classifier (a custom-trained XML, a non-default constructor) you can put it
on the same footing with the exposed `given`:

```scala mdoc:compile-only
import Cascades.given // given Releasable[CascadeClassifier]
val custom = Managed(org.opencv.objdetect.CascadeClassifier("my-trained.xml"))
```

### Detecting: the `detect` extension

Detection is an extension on `Mat`, so a classifier from `Cascades` reads the same as one you
built yourself. It runs the cascade, copies the rectangles out at the native boundary, frees the
internal `MatOfRect`, and returns a `Seq[Rect]` — the receiver is neither modified nor released:

```scala mdoc:silent
val haarBoxes: Either[CvError, Seq[Rect]] =
  Cascades.load(CascadeName.FrontalFaceAlt).map { classifier =>
    classifier.use { c =>
      Managed.use(Mat(200, 200, CvType.CV_8UC1)) { img =>
        img.detect(c, scaleFactor = 1.1, minNeighbors = 3, minSize = Some(Size(30, 30)))
      }
    }
  }
```

```scala mdoc
haarBoxes.map(_.size) // Right(0): a blank canvas has no faces, which is a result, not an error
```

The three knobs are the ones worth knowing:

| Parameter | Default | Effect |
|---|---|---|
| `scaleFactor` | `1.1` | how much the window grows per pyramid level. Closer to `1` finds more, runs slower. Must be `> 1`. |
| `minNeighbors` | `3` | how many overlapping hits a candidate needs to survive. Higher is stricter (fewer false positives). |
| `minSize` | `None` | ignore objects smaller than this. Setting it is the cheapest speed-up available. |

:::tip[Tuning cheat-sheet]
- **Too many false positives?** Raise `minNeighbors` (try `5`–`6`) or set a `minSize`.
- **Missing small/distant objects?** Lower `scaleFactor` toward `1.05` (slower).
- **Too slow?** Set `minSize`, raise `scaleFactor`, and always detect on a **grayscale** image.
:::

Best results come from a single-channel, histogram-equalised image — `image.gray.equalizeHist`
in the [Image API](/image-api), or the mid-level `cvtColor`/`equalizeHist` on a `Mat`. A colour
Mat works but is slower. An **empty** Mat makes OpenCV throw, and that throw is deliberately not
caught: it is a programmer error, not a data-dependent outcome. Two argument checks are enforced
up front — `scaleFactor > 1` and `minNeighbors >= 0` — so a nonsensical call fails immediately
with a clear message rather than deep inside OpenCV:

```scala mdoc:crash
Cascades.load(CascadeName.FrontalFaceAlt).foreach { classifier =>
  classifier.use { c =>
    Managed.use(Mat(100, 100, CvType.CV_8UC1))(_.detect(c, scaleFactor = 0.9)) // must be > 1
  }
}
```

### High level: `Image.detectHaar`

On an `Image`, `detectHaar` is the same call with the borrow handled for you — it is a query, so
the image stays alive:

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).map { classifier =>
  classifier.use { c =>
    Image.reading("portrait.jpg")(_.gray.equalizeHist.detectHaar(c, minNeighbors = 5))
  }
}
```

`detectHaar` takes the raw `CascadeClassifier`, not the `Managed` — a defaulted overload for the
`Managed` is not expressible alongside this one — so keep the classifier inside its `use` scope,
which is what preserves the spent-handle guard. To draw the boxes you get back, chain
[`drawRects`](/drawing) on a copy:

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).map { classifier =>
  classifier.use { c =>
    Image.reading("portrait.jpg") { img =>
      val boxes = img.copy.gray.equalizeHist.detectHaar(c)
      img.drawRects(boxes, color = Scalar.Green).write("faces.png")
    }
  }
}
```

## YuNet — the modern face detector {#yunet-the-modern-face-detector}

For faces specifically, reach for **YuNet** instead. It is a small CNN (232 kB) exposed through
OpenCV's `FaceDetectorYN`, and it beats the Haar cascades on every axis that matters:

- **Accuracy** — far fewer false positives and misses, and it handles pose and lighting a cascade
  cannot.
- **Landmarks** — every detection carries five facial points (both eyes, nose tip, both mouth
  corners), which a cascade does not give you at all.
- **A confidence score** in `[0, 1]` per face, so you can threshold and rank instead of guessing.

| | Haar cascade | YuNet |
|---|---|---|
| Setup | cascade XML in the jar | 232 kB ONNX download |
| Speed | fast | fast (comparable) |
| Accuracy | dated | strong |
| Landmarks | none | 5 per face |
| Confidence | none | score per face |
| Non-frontal faces | poor | good |
| Thread-safe? | yes (read-only) | no — one detector per thread |

### The model is downloaded and verified, never vendored

The `.onnx` file is **not** shipped with scalacv, and that is a licensing decision rather than a
size one: the model is MIT-licensed (Shiqi Yu), which would oblige this repository to reproduce
its notice the moment a copy shipped in the jar. Keeping it a runtime download keeps that
obligation with whoever redistributes it.

`FaceDetect.downloadModel` fetches it into a directory you choose, checks its exact size **and**
SHA-256 before the path is returned, and moves it into place only once verified — an interrupted
run cannot leave a truncated model behind. It is idempotent: an already-present, correctly-hashed
file is returned without touching the network, so you can call it freely at start-up.

```scala mdoc:compile-only
import java.nio.file.Path

val model: Either[CvError, Path] = FaceDetect.downloadModel(Path.of("models"))
```

An unverified model is the most direct supply-chain hole this library could have — it is
executable content fetched over a network we do not control — so a checksum mismatch is a `Left`
carrying both digests, never a silently accepted file. The pinned values are constants you can
inspect:

```scala mdoc
(FaceDetect.ModelFileName, FaceDetect.ModelSha256, FaceDetect.ModelSizeBytes)
```

:::note[Two ways to fetch a model]
`FaceDetect.downloadModel(dir)` is the dedicated one-liner. There is also a generic registry —
`Models.fetch(FaceDetect.modelSpec, dir)` — that takes any [`ModelSpec`](/dnn) and works the same
way (temp file, verify, move). Use the generic form when you are fetching several models with the
same code path; both share the "verify before load" guarantee.
:::

### Building a detector and detecting

`FaceDetect.create` builds a caller-owned `Managed[FaceDetectorYN]` from the model path. Unlike
`CascadeClassifier`, `FaceDetectorYN.create` *does* throw for a bad model, so the `Either` here is
load-bearing — a mistyped path comes back as a `Left`, not an unhandled `CvException`:

```scala mdoc:compile-only
import java.nio.file.Path

val faces: Either[CvError, Seq[Face]] =
  FaceDetect.downloadModel(Path.of("models")).flatMap { modelPath =>
    FaceDetect.create(modelPath.toString, inputSize = Size(320, 320)).flatMap { detector =>
      detector.use { yunet =>
        Image.reading("crowd.jpg")(_.faces(yunet))
      }
    }
  }
```

`create` takes four arguments; only the first two are usually worth touching:

| Parameter | Default | Meaning |
|---|---|---|
| `modelPath` | — | path to `face_detection_yunet_2023mar.onnx` |
| `inputSize` | — | frame size the anchors are laid out for (e.g. `Size(320, 320)`) |
| `scoreThreshold` | `0.9f` | minimum confidence to report; lower to catch small/profile faces |
| `nmsThreshold` | `0.3f` | IoU above which two boxes are merged as one face |

`inputSize` does *not* constrain what you may later detect on — `detect` re-sets it per frame —
but passing the size you actually expect keeps detections on off-size images well calibrated. A
zero-sided size or an out-of-range threshold is an `IllegalArgumentException` at the call, not a
`Left`:

```scala mdoc:crash
FaceDetect.create("model.onnx", inputSize = Size(0, 320)) // zero side — programmer error
```

`FaceDetect.detect(detector, mat)` returns one `Face` per detection, in descending-score order
after NMS:

```scala mdoc:compile-only
val frame: Mat = ??? // a BGR CV_8UC3 image
val found: Seq[Face] = FaceDetect.detect(faceDetector, frame)
found.map(f => (f.box, f.score, f.rightEye, f.noseTip))
```

### The `Face` type

A `Face` is plain immutable data — a box, five landmarks, a score — copied out of OpenCV's result
Mat, so it survives the detector being freed:

```scala mdoc:compile-only
val f: Face = FaceDetect.detect(faceDetector, ???).head
(f.box, f.score)
(f.rightEye, f.leftEye, f.noseTip, f.rightMouthCorner, f.leftMouthCorner)
```

`Face` is a public case class, so you can construct one directly — handy for tests and for the
annotation example below:

| Accessor | Landmark index | Where it appears |
|---|---|---|
| `box` | — | bounding box (may extend past the frame) |
| `clippedBox(w, h)` | — | `box` trimmed to a `w`×`h` frame, `None` if it falls outside |
| `rightEye` | 0 | subject's right eye — image **left** |
| `leftEye` | 1 | subject's left eye — image **right** |
| `noseTip` | 2 | centre |
| `rightMouthCorner` | 3 | image left |
| `leftMouthCorner` | 4 | image right |
| `score` | — | confidence in `[0, 1]` |

Two subtleties encoded in the type:

- The `box` is **not clipped** to the image. YuNet regresses boxes from anchors, so a face at the
  edge of the frame legitimately yields a negative `x`/`y` or a box running past the image bounds.
  `crop` rejects such a rectangle rather than trimming it, so clip first with
  `face.clippedBox(image.width, image.height)` (or `face.clippedBox(image)`), which returns
  `Option[Rect]` — `None` when the box lies entirely outside the frame.
- "Right" in `rightEye`/`rightMouthCorner` is the *subject's* right, which appears on the **left**
  of the image. The landmark order is fixed: right eye, left eye, nose tip, right mouth corner,
  left mouth corner.

### Four gotchas `FaceDetect` handles for you

`FaceDetectorYN` is easy to misuse, and all four traps are handled inside `detect` rather than
left to the caller. Knowing them explains why the API looks the way it does:

1. **Input size is fixed at construction and enforced at detect time.** `detect` runs an internal
   `CV_CheckEQ(image.size, input_size)` and throws the moment a frame differs by a single pixel —
   exactly what happens the first time you feed a resized frame or a webcam that renegotiated its
   resolution. `FaceDetect.detect` calls `setInputSize` for *every* frame, so any Mat works. The
   cost: a detector is **stateful and not thread-safe** — give each thread its own.
2. **`detect` returns an `int` status, not a face count.** It is `1` when the network ran and `0`
   for an empty input. Reading it as a count reports one face per successful call, forever. The
   count is `faces.rows()`.
3. **No faces means a 0×0 Mat**, not an N×15 Mat with zero rows. A decode loop that trusts
   `cols()` without checking `empty()` first reads column 14 of a Mat that has no columns.
4. **A detection row is 15 `CV_32F` columns** — `x, y, w, h`, five `(x, y)` landmark pairs, then
   the score. `FaceDetect` fails loudly if a future model emits a different width rather than
   decoding garbage.

:::danger[YuNet needs a 3-channel BGR image]
`detect` requires an 8-bit **3-channel BGR** image; a greyscale Mat fails deep inside the DNN
module with a message about layer shapes that says nothing about the real mistake. Convert first
with `image.convert(ColorConversion.GrayToBgr)`. An empty image is likewise a programmer error and
throws rather than returning an empty `Seq`.
:::

### High level: `Image.faces` and `Image.markFaces` {#faces}

On an `Image`, `faces(detector)` is a borrowing query and `markFaces` is the one-call "show me
what YuNet found" — a box per face and a dot per landmark. `faces` has two overloads: one takes
the raw `FaceDetectorYN`, and one takes the `Managed[FaceDetectorYN]` directly (the recommended
path, since the spent-handle guard travels with the argument):

```scala mdoc:compile-only
FaceDetect.create("model.onnx", Size(320, 320)).map { detector =>
  Image.reading("crowd.jpg") { img =>
    val found = img.faces(detector) // Managed overload — no `.get`
    img.markFaces(found).write("annotated.png")
  }
}
```

`markFaces` is an ordinary transform: it **consumes** the receiver and returns a new annotated
`Image`. Because `Face` is a public case class, you can watch it work end to end without a model —
paint synthetic detections onto a blank canvas:

```scala mdoc:silent
val canvas = Image.blank(320, 240)
val synthetic = Face(
  box = Rect(80, 60, 100, 100),
  landmarks = Seq(Point(110, 90), Point(150, 90), Point(130, 110), Point(112, 140), Point(148, 140)),
  score = 0.99f
)
val annotated = canvas.markFaces(Seq(synthetic), color = Scalar.Green)
val annotatedWidth = annotated.width
annotated.close()
```

```scala mdoc
annotatedWidth // the annotated image keeps the canvas size: 320
```

See [Drawing](/drawing) for annotating detections by hand, and [DNN](/dnn) for running your own
ONNX networks — YuNet is a `FaceDetectorYN`-shaped convenience over the same DNN machinery.
Recognition — turning a detected face into an identity — is the subject of
[Face recognition](/face-recognition).

## QR codes

`Qr.detectAndDecode` finds and decodes **every** QR code in an image, returning a `Seq[QrCode]`
— it uses OpenCV's *multi* detector unconditionally, because the single-code variant silently
drops every symbol but the first. Each `QrCode` carries the decoded `text` and its four `corners`;
the text is empty (but the corners still useful) when OpenCV located a symbol it could not decode.

| Field | Type | Notes |
|---|---|---|
| `text` | `String` | the decoded payload; **empty** if located-but-not-decoded (blurred/occluded) |
| `corners` | `Seq[Point]` | four corners of the symbol, useful even when `text` is empty |

It is self-contained — it builds and frees its own detector — so there is nothing to manage. Here
is a full round-trip: encode a code, then decode it straight back, no fixture file needed:

```scala mdoc:silent
import org.opencv.objdetect.QRCodeEncoder
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Mat, Size => CvSize}

val code = Mat()
QRCodeEncoder.create().encode("scalacv: https://github.com/w0rxbend/scalacv", code)

// The encoder emits a tiny 1-channel bitmap. Upscale it so the modules are detectable...
val scaled = Mat()
Imgproc.resize(code, scaled, CvSize(code.cols * 8, code.rows * 8), 0, 0, Imgproc.INTER_NEAREST)

// ...and give it three channels, since detection expects a BGR image.
val bgr = Mat()
Imgproc.cvtColor(scaled, bgr, Imgproc.COLOR_GRAY2BGR)
```

```scala mdoc
Qr.detectAndDecode(bgr).map(_.text)
```

```scala mdoc:invisible
code.release(); scaled.release(); bgr.release()
```

An **empty** Mat is a programmer error and throws; an image with no QR code is simply an empty
`Seq`. On an `Image`, the same detector is a borrowing query:

```scala mdoc:compile-only
Image.reading("poster.png")(_.qrCodes.map(_.text))
```

:::tip[Codes it locates but cannot decode]
A blurred or partially-occluded symbol comes back with an empty `text` but usable `corners`.
Filter on `_.text.nonEmpty` when you only want decoded payloads; keep the empty ones when you want
to draw an overlay or re-crop and retry at higher resolution.
:::

## ArUco markers

ArUco markers are square fiducials — a black-bordered bit grid encoding an integer id — used for
pose estimation, camera calibration and AR anchors. `Aruco.detect` returns a `Seq[ArucoMarker]`,
each with its `id` and four `corners` (clockwise from the top-left in the marker's own frame). See
[Marker AR](/marker-ar) and [Pose estimation](/pose-estimation) for what to do with those corners
once you have them.

### Dictionaries

A dictionary fixes the bit-grid size and how many distinct markers exist. The name says both:
`Dict5x5_250` is 250 distinct 5×5 markers. **Fewer markers means a larger Hamming distance between
them and more robust detection**, so pick the smallest dictionary with enough ids for the job
rather than the largest. The AprilTag families are addressable through the same enum.

```scala mdoc
ArucoDictionary.values.take(6).map(d => d.toString -> d.cvValue).toList
```

```scala mdoc
ArucoDictionary.values.length // every predefined dictionary, AprilTag families included
```

| Dictionary family | Grid | Sizes available | Use for |
|---|---|---|---|
| `Dict4x4_*` | 4×4 | 50, 100, 250, 1000 | small markers, few ids |
| `Dict5x5_*` | 5×5 | 50, 100, 250, 1000 | a good general default |
| `Dict6x6_*` / `Dict7x7_*` | 6×6 / 7×7 | 50, 100, 250, 1000 | many ids, needs more resolution |
| `AprilTag16h5` … `AprilTag36h11` | AprilTag | — | robotics/AprilTag toolchains |
| `ArucoOriginal`, `ArucoMip36h12` | legacy | — | compatibility with older data |

### Generate and detect

`Aruco.generateMarker` renders a marker as a caller-owned `Managed[Mat]` (8-bit, single-channel).
One catch, and the API comment is emphatic about it: the rendered marker carries its own black
border but **no quiet zone**, and the detector locates candidates by looking for a dark quad on a
*light* background — so it will not find the marker until you pad a white margin around it with
`Core.copyMakeBorder`. With that, a full round-trip:

```scala mdoc:silent
import org.opencv.core.{Core, Mat, Scalar => CvScalar}

val dict = ArucoDictionary.Dict4x4_50

// Render marker id 23, then add the white quiet zone the detector needs to find it.
val marker = Aruco.generateMarker(dict, id = 23, sizePixels = 200)
val bordered = Mat()
Core.copyMakeBorder(marker.get, bordered, 40, 40, 40, 40, Core.BORDER_CONSTANT, CvScalar(255, 255, 255))
marker.release()
```

```scala mdoc
Aruco.detect(bordered, dict).map(_.id)
```

```scala mdoc:invisible
bordered.release()
```

The id comes straight back. `detect` builds a fresh `ArucoDetector` per call (construction is
cheap next to detection, and the detector copies the dictionary into itself), discards rejected
candidates, and — as everywhere on this page — frees every native Mat it allocated before
returning plain data. An **empty** Mat is a programmer error and throws; an image with no markers
is simply an empty `Seq`.

:::warning[A generated marker won't detect until you add a quiet zone]
`generateMarker` gives you the marker *only* — black border, no white margin. Detection fails on
it until you pad it (`Core.copyMakeBorder` with a white border, as above). When you print markers,
leave white space around them for the same reason.
:::

### High level: `Image.arucoMarkers`

```scala mdoc:compile-only
Image.reading("scene.jpg")(_.arucoMarkers(ArucoDictionary.Dict4x4_50).map(_.id))
```

## Detecting across a video

All four detectors take a `Mat` or an `Image`, so running them over a stream is just a loop —
mind the ownership rule that [`Video.frames`](/video) yields one reused borrowed Mat, while
`framesCopied` and [`Camera.foreach`](/conferencing) give you owned copies:

```scala mdoc:compile-only
FaceDetect.create("model.onnx", Size(320, 320)).map { detector =>
  detector.use { yunet =>
    Video.open("clip.mp4").map { capture =>
      capture.use { c =>
        Video.frames(c) { frames => // each `frame` is a reused borrowed Mat — read, don't retain
          frames.foreach { frame =>
            val found = FaceDetect.detect(yunet, frame.mat)
            println(s"${found.size} face(s) this frame")
          }
        }
      }
    }
  }
}
```

:::danger[One detector per thread]
`FaceDetectorYN` is stateful — `detect` mutates its input size on every call. Do **not** share one
across threads or fan frames out to a thread pool with a single shared detector; give each worker
its own. The cascade classifiers, `Qr`, and `Aruco` do not have this hazard.
:::

## Next

- [Face recognition](/face-recognition) — turn a detected `Face` into an identity.
- [Marker AR](/marker-ar) and [Pose estimation](/pose-estimation) — use ArUco corners for 3D pose.
- [Drawing](/drawing) — overlay boxes and landmarks; [Geometry](/geometry) for the `Rect`/`Point`
  value types detectors return.
- [DNN](/dnn) — run your own ONNX networks; [Cookbook](/cookbook) for copy-paste recipes.
