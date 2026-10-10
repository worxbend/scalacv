#import "../lib/book.typ": *

#chapter("Face Detection", subtitle: [Two detectors, twenty years apart, and why you build either of them exactly once.])

A face is the first thing anyone tries to detect, and it is a fortunate choice. Faces are roughly
rigid, roughly the same shape, roughly the same arrangement of dark and light in every human being
alive, and there are enormous public datasets of them. That made face detection the first object
detection problem to be considered solved for practical purposes --- and it was solved twice: once
with hand-designed features and a clever early-exit search, and again with a convolutional network
small enough to fit in a quarter of a megabyte. scalacv exposes both, and they are not
interchangeable.

The interesting difficulty is not which of them finds more faces --- that question has a boring
answer, and this chapter gives it. It is that both detectors are native objects with a lifetime, and
both are the kind of native object people build inside a loop. A `Mat` that leaks is six megabytes;
a detector that leaks is a parsed model, and building one per frame also pays to parse the model per
frame, so the leak and the performance bug arrive together and disguise each other. Chapter 5 gave
you `Managed`, and Chapter 23 gave you the model file.

There is a second, quieter difficulty: neither detector reports failure the way you would expect.
`CascadeClassifier` does not throw for a path it cannot read --- it constructs an empty classifier
that detects nothing for the rest of the process, which looks exactly like an empty room.
`FaceDetectorYN` does throw for a bad model, but its `detect` returns a status flag rather than a
face count, and reading it as a count reports one face on every successful call. Both mistakes
produce plausible output. scalacv catches them at the boundary, and it is worth knowing what it is
catching.

Both detectors live in `scalacv-vision`, not in the core artifact --- `Cascades.scala` and
`FaceDetect.scala` are both in that module --- so every listing in this chapter needs
`com.worxbend::scalacv-vision:0.4.1` on the classpath beside the core dependency of Chapter 2. The
vision package is `scalacv.vision`: add `import scalacv.vision.*` for `detectHaar`, `faces` and
`markFaces`, alongside `import scalacv.*` for core.

Start with the older detector, because its failure modes are the ones you will be talking a
colleague out of.

#sect("What Viola-Jones actually does")

The 2001 Viola-Jones detector is built from three ideas that fit together so well it is still worth
reading about, even though you will rarely deploy it now.

The first is the #keyterm[integral image]. Precompute, for every pixel, the sum of every pixel above
and to the left of it; the sum of any axis-aligned rectangle is then four table lookups and three
additions, regardless of how large the rectangle is. The detector's features are differences of
adjacent rectangle sums --- a dark band above a light band, which is what an eye socket over a
cheekbone looks like --- so every feature costs a constant handful of memory reads at any scale.

The second is that one such feature is a terrible classifier, barely better than a coin flip.
Boosting weights hundreds of these weak classifiers into one strong classifier, choosing at each
round the feature that best corrects the mistakes of the rounds before it.

The third idea made it real time on hardware of the period. Instead of running one enormous strong
classifier over every window, arrange the strong classifiers in a #keyterm[cascade]: a very cheap
stage first, then a slightly more expensive one, and so on. A window that fails any stage is
rejected immediately and never sees another. The overwhelming majority of windows in any photograph
are not faces and are thrown out by the first two stages, which between them evaluate a handful of
features, so the average cost per window is close to the cost of the cheapest stage. That is the
whole trick, and it is why the file is called a cascade.

Repeat the scan at a pyramid of scales and you have a face detector that ran at video rates on a
2001 desktop CPU, with no GPU and no model download. In 2026 it remains useful for two reasons: it
needs no model file you have to ship, and it is understandable end to end. It is the fallback, not
the default. It wants upright, roughly frontal, evenly lit faces, and it produces false positives at
a rate that will surprise you the first time you point it at foliage.

#sect("Loading a cascade without lying to yourself")

The cascades are XML files inside the bytedeco per-platform classifier jar, under
`share/opencv4/haarcascades/`. They are a classpath resource, and OpenCV is a C++ library that only
knows filesystem paths, so they have to be extracted before they can be loaded. `Cascades` does that
through javacpp's resource cache, and needs no native library to do it --- `Cascades.resolve` works
before `OpenCv.load()` has been called.

scalacv names the bundled cascades with a type rather than a string because of the silent failure
above: a mistyped path produces a classifier whose `empty()` is `true` and whose every
`detectMultiScale` returns nothing. `CascadeName` removes the typo, and `Cascades.load` checks
`empty()` afterwards anyway and returns a `Left` --- releasing the real-but-useless handle first,
because a classifier that loaded no model still allocated one.

#example("A cascade is loaded by name, scoped, and used inside its scope.")[
```scala
import scalacv.*

OpenCv.load()

Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { classifier =>
    Image.reading("group.jpg") { photo =>
      val faces: Seq[Rect] = photo.detectHaar(classifier)
      photo.copy.drawRects(faces, Scalar.Green).write("group-haar.png")
    }
  }
}
```
]

`Cascades.load` hands back an `Either[CvError, Managed[CascadeClassifier]]`, and `use` releases the
classifier on every exit path. Keep the detection inside that scope: `detectHaar` takes the raw
`CascadeClassifier` rather than the `Managed` --- a defaulted overload for both is not expressible
--- so the spent-handle guard only protects you while the `Managed` is in scope. The `.copy` is
there because drawing consumes an `Image` (Chapter 4) and `photo` belongs to the `reading` scope;
the copy is consumed by `drawRects` and released by `write`.

`CascadeName` covers the faces --- `FrontalFaceAlt` (the usual first choice, with markedly fewer
false positives), `FrontalFaceAlt2`, `FrontalFaceDefault` (the original Viola-Jones cascade, fast
and permissive) and `ProfileFace` --- the features (`Eye`, `EyeTreeEyeglasses`, `LeftEye2Splits`,
`RightEye2Splits`, `Smile`), the bodies (`FullBody`, `UpperBody`, `LowerBody`) and
`RussianPlateNumber`. The LBP cascades and the two cat detectors are deliberately absent, and both
remain reachable through `Cascades.loadFrom`, which takes a filesystem path and applies the same
`empty()` check.

#warning[
  The `windows-x86_64` bytedeco jar ships an empty `share/` directory and no cascade XML at all,
  unlike every other platform. `Cascades.resolve` and `Cascades.load` can only return a
  `Left(CvError.LoadFailed(...))` there, and the message says so in those words rather than leaving
  you to guess. If you target Windows, ship `haarcascade_frontalface_alt.xml` with your application
  and swap in `Cascades.loadFrom("cascades/haarcascade_frontalface_alt.xml")` --- it returns the
  same type, so nothing else in your code changes.
]

#memory[
  `CascadeClassifier` is one of the 185 generated OpenCV binding types with no public `release()`.
  Its `delete(long)` is private, so scalacv reaches it through `Releasable.nativeHandle`: read the
  address, disarm the binding's unconditional `finalize()`, #emph[then] free the pointer. Without
  that disarm a released classifier is a live double free, and the crash arrives from the JVM's own
  Finalizer thread with no frame of yours on the stack.

  A leaked classifier costs the parsed cascade --- every stage, every weak classifier, every feature
  rectangle --- held in native memory behind a Java object small enough that the collector will never
  feel it. One is a rounding error. One per frame is a service that dies overnight with a flat heap
  graph, the failure Chapter 1 opened with. `Cascades.load(...).map(_.use(...))` is the shape that
  releases its registered handles; `Managed.scope` is the shape for holding several detectors at once.
]

#sect("Running the cascade")

`image.detectHaar(classifier)` returns `Seq[Rect]` --- plain immutable Scala data, copied out of the
`MatOfRect` OpenCV filled in and released before the method returns, so the rectangles stay valid
after everything involved has been freed. The receiver is borrowed, not consumed. The mid-level
form, `mat.detect(classifier, ...)`, is the same method on a raw `Mat` with the same defaults.

Three parameters control the search, and their defaults are conservative.

#figure-table("The three knobs on Haar detection, and what each one trades.")[
#tbl(
  columns: (0.8fr, 0.5fr, 2fr),
  [Parameter], [Default], [What it trades],
  [`scaleFactor`], [`1.1`],
  [How much the detection window grows per pyramid level. Just above 1 --- say `1.05` --- means more
   levels, so more windows examined, so more faces found and a slower scan. A value at or below 1
   would never terminate, and is rejected by a `require`.],
  [`minNeighbors`], [`3`],
  [How many overlapping detections a candidate needs before it is reported. Raise it to 5 or 6 to
   kill false positives; lower it when real faces are being missed. Cannot be negative.],
  [`minSize`], [`None`],
  [Objects smaller than this are never examined. Setting it is the cheapest speed-up available,
   because it removes whole pyramid levels from the search rather than filtering results afterwards.],
)
]

There is no `maxSize`. OpenCV's underlying call has one and scalacv does not expose it, because an
upper bound on face size is a common source of detectors that mysteriously stop working when someone
walks towards the camera. Filter large boxes in Scala instead; `Rect.area` is right there.

Cascades want a single-channel, contrast-normalised image. A colour `Mat` works and is slower; a
backlit one works badly. The preparation is two transforms, and because both consume the image you
have to own the result:

#example("Preparing the frame the way the cascade wants it.")[
```scala
Image.reading("group.jpg") { photo =>
  val prepared = photo.copy.gray.equalizeHist
  try prepared.detectHaar(classifier, scaleFactor = 1.05, minNeighbors = 6, minSize = Some(Size(40, 40)))
  finally prepared.close()
}
```
]

`prepared` is an owned `Image` that nothing else will release --- `gray` and `equalizeHist` each
consume their receiver and hand back a new one, and `detectHaar` only borrows. Forgetting the
`finally` leaks one full-size greyscale frame per call: the leaked detector in different clothes.

#sect("YuNet: the detector to actually use")

YuNet is a small convolutional network --- 232,589 bytes of ONNX --- that `FaceDetect`'s own
documentation calls #emph[far more accurate and far faster] than the Haar cascades. Take the speed
half of that as the library's stated position rather than a figure you can quote: scalacv publishes
no benchmark that times the two detectors against each other, so this book will not invent one. The
accuracy half you will see for yourself inside a dozen frames. YuNet tolerates pose, expression and
lighting that send a cascade home empty, reports a calibrated confidence per detection, and returns
five facial landmarks that Chapter 25 will need. It is the detector to reach for; the cascades
remain for heritage and for environments where no model file can be shipped. The model is not
vendored in this repository, and that is a licensing decision rather than a size one.

#sidebar("Why the model is a download and not a jar")[
  YuNet is MIT licensed (Shiqi Yu). Committing a copy here, or shipping one inside a published jar,
  would oblige scalacv to reproduce that notice everywhere the jar goes; a runtime download keeps the
  obligation with whoever redistributes it. It is recorded in `THIRD-PARTY.md`, and it is not an
  accident to be tidied away by committing the file.

  A model is executable content fetched from a host nobody here controls, so
  `FaceDetect.downloadModel(into)` --- an alias for `Models.fetch(FaceDetect.modelSpec, into)` ---
  tries `FaceDetect.ModelUrls` in order, checks the size against `ModelSizeBytes` and then the
  SHA-256 against `ModelSha256`, and moves the file into place from a temp file beside it only once
  both pass. An interrupted run cannot leave a truncated model behind, and a mismatch is a `Left`
  carrying both digests. `into` is a directory, created if absent; the file name is fixed at
  `FaceDetect.ModelFileName` --- `face_detection_yunet_2023mar.onnx` --- and that fixity is what
  makes the already-downloaded check possible at all. The check is a `stat` and a digest of a file
  already on disk, so calling `downloadModel` on every start-up is the intended use.

  Both mirrors are `media.githubusercontent.com` rather than `raw.githubusercontent.com`, and that
  is load-bearing: the OpenCV Zoo keeps its `.onnx` files in Git LFS, and the `raw` host serves the
  131-byte LFS #emph[pointer] for them --- with HTTP 200, so nothing looks wrong until the network
  fails to load. The pinned size check turns that into "expected 232589 bytes, got 131" instead of a
  hash mismatch that invites you to suspect tampering.
]

`FaceDetect.create` builds the detector:

```scala
def create(
    modelPath: String,
    inputSize: Size,
    scoreThreshold: Float = 0.9f,
    nmsThreshold: Float = 0.3f
): Either[CvError, Managed[FaceDetectorYN]]
```

`inputSize` does not constrain what you may later detect on --- see below --- but it still matters:
YuNet's anchors are laid out for it, so passing the size of the frames you actually expect keeps
detections on off-size images well calibrated. `scoreThreshold` is the minimum confidence reported,
inheriting OpenCV's own default of 0.9; lowering it catches small and profile faces at the cost of
false positives. `nmsThreshold` is the IoU above which two overlapping boxes are treated as the same
face and the weaker one dropped. A zero side or a threshold outside `[0, 1]` is an
`IllegalArgumentException` --- those are typos, not runtime conditions. A missing, unreadable or
unimportable model is a `Left`: unlike `CascadeClassifier`, `FaceDetectorYN.create` genuinely throws
for a bad model, and an unhandled `CvException` out of a constructor is not a useful failure for
someone who mistyped a path.

Four things about the underlying `FaceDetectorYN` are easy to get wrong, and `FaceDetect` handles
each of them rather than leaving it to you.

- #strong[The input size is fixed at construction and enforced at detect time.] OpenCV's `detect`
  checks the frame size for equality and throws a `CvException` if a later frame differs by a single
  pixel --- which is what happens the first time you feed it a resized frame, or a webcam that
  renegotiated its resolution. `FaceDetect.detect` calls `setInputSize` for every frame,
  unconditionally, so any `Mat` works. The cost is that a detector is stateful and #strong[not safe
  to share across threads]. Give each thread its own.
- #strong[`detect` returns a status flag, not a face count] --- `1` when the network ran, `0` when
  the input was empty. Reading it as a count silently reports one face on every successful call. The
  count is `faces.rows()`.
- #strong[No faces means a 0×0 result Mat], not an N×15 Mat with zero rows. A decode loop that
  trusts `cols()` without checking `empty()` first reads column 14 of a Mat with no columns.
- #strong[A detection row has 15 columns], all `CV_32F`. `FaceDetect.ResultColumns` is that 15, and
  any other width raises `CvError.NativeCall` naming the mismatch rather than decoding garbage.

`FaceDetect.detect` also refuses an image that is not 8-bit three-channel BGR, with an
`IllegalArgumentException` naming the actual type. YuNet's blob step needs `CV_8UC3`, and without
the check the failure surfaces from inside the DNN module as a message about layer shapes that says
nothing about the real mistake. Convert a greyscale frame back with
`image.convert(ColorConversion.GrayToBgr)` first.

#subsect("The Face record")

A detection comes back as a `Face`: plain immutable Scala data copied out of OpenCV's result Mat,
which is released before `detect` returns. There is no native handle to own, which is why the result
is a `Seq[Face]` and not a `Managed`.

`box` is a `Rect`, `score` is the model's confidence in `[0, 1]`, and `landmarks` is exactly five
`Point`s, always in this order: `rightEye`, `leftEye`, `noseTip`, `rightMouthCorner`,
`leftMouthCorner`. Each has a named accessor. "Right" is the #emph[subject's] right, so the right eye
appears on the left of the image --- a distinction that costs an afternoon exactly once.

The box is #strong[not] clipped to the frame. YuNet regresses boxes from anchors, so a face at the
edge of the image legitimately yields a negative `x` or `y`, or a box running past `width`/`height`.
`Image.crop` and `Mat.submat` both reject such a rectangle, so the naive crop fails on the one frame
where somebody walks in from the side:

```scala
val thumbs = faces.map(f => frame.copy.crop(f.box))   // throws on any face at an edge
```

`Face.clippedBox` is the fix, and it is a method rather than a footnote because every caller would
otherwise write the four-way `min`/`max` by hand and put an off-by-one in it. It returns the
intersection with the frame as an `Option[Rect]`, `None` when the box lies entirely outside. The
intersection is half-open on both axes, matching what `submat` expects, so a box that merely touches
an edge answers `None` rather than a zero-extent `Rect` that `submat` would throw on.

#example("Cropping every detected face safely.")[
```scala
val boxes  = frame.faces(detector).flatMap(_.clippedBox(frame))
val thumbs = boxes.map(r => frame.copy.crop(r))
```
]

The `clippedBox(image)` overload is `clippedBox(image.width, image.height)`; the image is only
queried for its size, so it stays alive and owned by you.

#sect("faces and markFaces")

Two extension methods on `Image` carry the whole high-level story, and `import scalacv.*` brings
them in. `image.faces(detector)` runs the detection, overloaded on both the raw `FaceDetectorYN` and
the `Managed[FaceDetectorYN]` the loaders hand back; prefer the `Managed` form, since the
spent-handle guard then travels with the argument instead of being discarded by a bare `.get`.
`image.markFaces(faces, color)` draws a box per face and a dot per landmark, defaulting to
`Scalar.Green`.

`faces` borrows. It reads the image and returns data; it does not consume the receiver and does not
release the detector. That matters because the obvious defensive move is wrong:

```scala
photo.markFaces(photo.copy.faces(detector)).write("marked.png")   // the copy is never released
```

The `.copy` allocates a full frame, hands it to a method that only reads it, and nothing ever frees
it --- a leak of one frame per call, hidden inside a line that looks careful. Because `faces`
borrows, there is nothing there to defend against:

#example("Detect on the borrowed image, then consume it to draw.")[
```scala
def annotate(in: String, out: String, detector: FaceDetectorYN): Either[CvError, Int] =
  Image
    .reading(in) { photo =>
      val faces = photo.faces(detector)
      photo.copy.markFaces(faces).write(out).map(_ => faces.size)
    }
    .flatMap(identity)
```
]

Here the `.copy` #emph[is] earned: `markFaces` consumes its receiver, `photo` is owned by the
`reading` scope, and the annotated image `markFaces` returns is released by `write`. The
`flatMap(identity)` flattens `reading`'s `Either` around the block's own.

Wiring it up needs the model, the detector and one scope:

#example("A still photograph, end to end.")[
```scala
import java.nio.file.Path
import org.opencv.objdetect.FaceDetectorYN
import scalacv.*

OpenCv.load()

val cache = Path.of(sys.props("user.home"), ".cache", "scalacv-models")

val marked: Either[CvError, Int] =
  for
    model    <- FaceDetect.downloadModel(cache)
    detector <- FaceDetect.create(model.toString, Size(1280, 720))
    count    <- detector.use(d => annotate("group.jpg", "group-yunet.png", d))
  yield count
```
]

Every stage that can fail is a `Left`: no network, a corrupt download, a model OpenCV will not load,
an unreadable photograph, an unwritable output. A machine with no network degrades to a message.

#sect("Choosing between them")

#figure-table("The two detectors, side by side.")[
#tbl(
  columns: (0.75fr, 1.1fr, 1.15fr),
  [], [Haar cascade], [YuNet],
  [API],
  [`Cascades.load`, `image.detectHaar`],
  [`FaceDetect.create`, `image.faces`],
  [Result per face],
  [`Rect`, nothing else],
  [`Face`: box, `score`, five landmarks],
  [Model source],
  [bundled in the bytedeco classifier jar; absent on `windows-x86_64`],
  [232,589-byte ONNX, downloaded and SHA-256 verified at runtime],
  [Speed],
  [a pyramid scan whose cost rises sharply as `scaleFactor` falls],
  [one fixed-cost forward pass, whatever the scene holds; documented as far faster, not measured
   here],
  [Accuracy],
  [more false positives; needs `minNeighbors` tuning per scene],
  [markedly higher, with a calibrated confidence you can threshold],
  [Pose tolerance],
  [upright and roughly frontal; a separate `ProfileFace` cascade for the rest],
  [tolerant of moderate yaw, roll and expression],
  [Preprocessing],
  [wants greyscale and `equalizeHist`],
  [wants 8-bit BGR `CV_8UC3`, nothing else],
  [Threading],
  [one classifier per thread],
  [one detector per thread --- `detect` mutates its input size],
  [Failure when unavailable],
  [`Left` from `Cascades.load`, naming the platform],
  [`Left` from `downloadModel` or `create`, naming the stage],
)
]

The rule of thumb is short: if you can fetch a 232 kB file, use YuNet. Use a cascade when you
cannot, or when you are detecting one of the non-face objects `CascadeName` covers and have nothing
better trained.

#sect("Build once, detect many")

The most common performance bug in this area is not a slow algorithm but a detector constructed
inside the frame loop. It is easy to write, it produces correct output, and it costs more than the
detection itself by a wide margin:

```scala
camera.foreach() { frame =>
  Cascades.load(CascadeName.FrontalFaceAlt).foreach {          // wrong: per frame
    _.use(c => log(frame.detectHaar(c).size))
  }
}
```

That parses an XML cascade tree, allocates the native classifier and frees it again --- thirty times
a second, for a scan that costs a fraction of the parse. Only the resource extraction is spared:
javacpp caches the file it wrote, so `Cascades.resolve` after the first call is a lookup. The parse
is not cached, and it is the expensive half. The YuNet version of the same mistake is worse:
`downloadModel` inside the loop re-digests 232,589 bytes on every frame before `create` re-inflates
the ONNX graph. The model fetch and the detector construction belong at start-up; the
loop should contain nothing but detection and drawing.

#example("Faces in a video, with the detector hoisted and the results thresholded twice.")[
```scala
val recorded: Either[CvError, Long] =
  for
    model    <- FaceDetect.downloadModel(cache)
    detector <- FaceDetect.create(model.toString, Size(1280, 720), scoreThreshold = 0.6f)
    written  <- detector.use { d =>
                  Camera
                    .usingFile("interview.mp4") { camera =>
                      camera.recordTo("interview-faces.avi") { frame =>
                        val faces = frame.faces(d).filter(_.score >= 0.9f)
                        frame.markFaces(faces, Scalar.Green)
                      }
                    }
                    .flatMap(identity)
                }
  yield written
```
]

Two thresholds, deliberately. The detector is built with `scoreThreshold = 0.6f` so marginal
detections reach your code at all, and the drawing filter keeps only those at or above `0.9f`. Adopt
that split as a habit: the network's threshold decides what you are allowed to see, and a plain
Scala `filter` decides what you act on. Raising the network's threshold throws information away
inside a native call you cannot inspect; filtering a `Seq[Face]` is a decision you can log, tune, or
make per-region without rebuilding anything.

Everything in that loop has an owner. `Camera.usingFile` closes the capture on every path.
`recordTo` reads every frame as an owned `Image`, hands it to the transform, writes what comes back
and closes it. `faces` borrows the frame, `markFaces` consumes it and returns the annotated image
that `recordTo` releases --- so the frame's Mat is written and freed exactly once, with no copy
anywhere in the loop. `detector.use` frees the detector when the video ends, by exception included.

#memory[
  `FaceDetectorYN` is also one of the 185 types with no public `release()`, and it is where the
  double-free hazard was first found: a DNN allocates enough to make the collector run mid-suite, so
  a released detector whose `finalize()` had not been disarmed crashed the test JVM from the
  Finalizer thread, inside `Java_org_opencv_objdetect_FaceDetectorYN_delete`. That is why
  `Releasable.nativeHandle` disarms before it frees.

  The 232,589-byte file is only the serialised weights. A live detector also holds the inflated
  graph and the per-layer blobs, sized for whatever input size was last set, and none of that
  appears on the Java heap either. The `given Releasable[FaceDetectorYN]` in `FaceDetect` and the
  `given Releasable[CascadeClassifier]` in `Cascades` are both public, so a detector you build
  yourself --- from a `MatOfByte` buffer, or with a `topK` other than OpenCV's default of 5000 ---
  is managed on the same terms with `import FaceDetect.given`.
]

#sect("What actually breaks these detectors")

Both detectors fail in the same directions. YuNet fails later along each of them, and it tells you
so through the score rather than by returning nothing.

#strong[Profile.] A face turned past roughly forty-five degrees stops looking like the thing either
detector was trained on. For a cascade the answer is a second pass with `CascadeName.ProfileFace`,
run over the frame and its horizontal mirror, since that cascade is trained on one side only. For
YuNet, lower `scoreThreshold` and filter on `score` and box geometry afterwards.

#strong[Occlusion.] A hand across the mouth is survivable; sunglasses are worse than they look,
because the eye region carries most of a cascade's early-stage discrimination and is where YuNet's
first two landmarks sit. A mask removes the mouth corners and shifts the box, so downstream
alignment that trusts `noseTip` and both mouth corners degrades before the detection itself does.

#strong[Small faces.] This is the one you control, and the trade is direct: doubling the frame's
long side quadruples the pixels examined. On a 4K stream where the people you care about are a few
metres from the camera, downscale to 720p and set a `minSize` --- the same faces, several times
faster. For a crowd shot, do the opposite: keep the resolution, leave `minSize` at `None`, and lower
`scaleFactor` towards `1.05`.

#strong[Backlight.] A subject against a window is close to a silhouette, and a cascade reads
silhouettes as absence. `equalizeHist` recovers a surprising amount of it for almost nothing, which
is why it is the standard preparation. YuNet is more tolerant but not immune, and when backlit
frames specifically lose faces the fix is exposure upstream, not a detector parameter.

#strong[Rotation in the image plane.] Neither detector handles a head tilted past about thirty
degrees, and neither has a parameter for it. For a camera mounted at an angle, rotate the frame
before detection (Chapter 10) and rotate the boxes back afterwards.

The detector is rarely the thing to tune first. Frame size, `minSize` and lighting move the numbers
further than any threshold does, in directions you can reason about.

#sect("Where this goes next")

You now have a box, a confidence and five landmarks per face, all of it plain Scala data that
outlives every native object involved. The landmarks are not decoration: they are the input to
alignment, and alignment is what turns "there is a face here" into "this is the same face as that
one."

Chapter 25, #emph[Face Recognition], picks up there. `FaceRecognizer.load` returns a recognizer
holding the SFace network --- a second model download, and at roughly 37 MB a far larger one ---
and its `embed(image, face)` takes the same `Face` this chapter produced, aligns and crops it by
those five landmarks, and hands back a `FaceEmbedding` wrapping a `Vector[Float]` with no native
memory in it at all. `Gallery.empty.enroll(name, embedding)` accumulates the people you know, and
`identify(embedding)` answers the question detection cannot: not "is there a face here" but "whose".
