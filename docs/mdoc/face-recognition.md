# Face recognition

[Detection](/object-detection) finds *where* the faces are. **Recognition answers *whose* face it
is.** scalacv wraps OpenCV's SFace ([`FaceRecognizerSF`](/api/core/scalacv/vision/FaceRecognizer.html)) to
turn an aligned face into a 128-dimensional **embedding** — a fixed-length vector that is close (by
angle) to other pictures of the same person and far from everyone else. A
[`Gallery`](/api/core/scalacv/vision/Gallery.html) lets you enrol known people and identify new faces
against them.

The mental model is three steps, and the middle one is the whole trick:

```
detect a face  →  embed it into a 128-vector  →  compare / look up the vector
 (YuNet, Face)      (SFace, FaceEmbedding)         (cosineSimilarity, Gallery)
```

An embedding is **plain immutable data**. Once you have it, the native models can be freed and the
vector still compares, serialises, and stores like any other `Vector[Float]`.

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
OpenCv.load()
```

:::tip[What you need before you start]
Recognition builds on detection, so you need **two** models: the YuNet detector (232 kB, see
[Object detection](/object-detection#yunet-the-modern-face-detector)) to find and align faces, and
the SFace recognizer (~37 MB, below) to embed them. The detector supplies the five landmarks SFace
uses to align each crop.
:::

## The model is yours to supply

Like the [YuNet detector](/object-detection#faces), the SFace model is a file you provide —
`face_recognition_sface_2021dec.onnx` (~37 MB) from the
[OpenCV Zoo](https://github.com/opencv/opencv_zoo). Two ways to get it onto disk:

```scala mdoc:compile-only
import java.nio.file.Path

// The generic, checksum-verifying downloader (temp file, verify, atomic move — as for YuNet).
val sfacePath: Either[CvError, Path] = Models.fetch(FaceRecognizer.modelSpec, Path.of("models"))
```

Or download it by hand and point `FaceRecognizer.load` at the path. Either way, the loader returns
an `Either`, so a missing or invalid file is a value, not an exception:

```scala mdoc:compile-only
import scala.util.Using

for recognizer <- FaceRecognizer.load("sface.onnx") yield
  Using.resource(recognizer): rec =>
    // ... use rec.embed(...)
    ()
```

`FaceRecognizer` is `AutoCloseable` — it owns a native recognizer — so close it (`Using.resource`,
or an explicit `rec.close()`). The pinned checksum lives in the spec, so a corrupted download is
caught before OpenCV ever sees it:

```scala mdoc
(FaceRecognizer.modelSpec.fileName, FaceRecognizer.modelSpec.sha256.isDefined)
```

:::note[`load` fails as a value]
A path with no file, or a file that is not an SFace network, comes back as a `Left(CvError)` — not
a thrown `CvException`. Pattern-match or `map`/`flatMap` it; there is no happy-path assumption to
trip over.
:::

## From a detected face to an embedding

`embed` takes a [`Face`](/api/core/scalacv/vision/Face.html) (from
[`Image.faces`](/object-detection#faces)) and the frame it came from, aligns and crops the face
using its five landmarks, and returns the embedding. The image must be the **BGR frame the face was
detected in** — the landmarks are coordinates into that exact image.

```scala mdoc:compile-only
FaceRecognizer.load("sface.onnx").foreach { recognizer =>
  val detector = FaceDetect.create("yunet.onnx", Size(320, 320)).toOption.get
  Image.reading("photo.jpg") { img =>
    val face = img.faces(detector).head // Managed overload — no `.get`
    val embedding: FaceEmbedding = recognizer.embed(img, face)
    embedding.values.size // 128
  }
  detector.release()
  recognizer.close()
}
```

Both `faces` and `embed` **borrow** the image — neither consumes it — so you can detect and then
embed every face from the same `Image` inside one `reading` block without a `.copy`.

## Comparing embeddings

Two faces are compared by the angle between their embeddings. SFace ships two metrics; use one
consistently, do not mix them:

| Metric | Direction | Same-person cutoff | scalacv accessor |
|---|---|---|---|
| Cosine similarity | **higher** is more alike (range `[-1, 1]`) | `≥ 0.363` | `a.cosineSimilarity(b)` |
| L2 (Euclidean) distance | **lower** is more alike | `≤ ~1.13` | `a.l2Distance(b)` |

`Gallery` uses cosine similarity, which is the more common choice. Because an embedding is plain
immutable data, it outlives every native object and stores cheaply.

```scala mdoc
{
  // Stand-in vectors so the page runs without a model — real ones come from `embed`.
  val a = FaceEmbedding(Vector(1.0f, 0.2f, 0.1f))
  val b = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f)) // a lookalike
  val c = FaceEmbedding(Vector(-1.0f, -0.3f, 0.4f))  // someone else
  f"same-ish: ${a.cosineSimilarity(b)}%.3f, different: ${a.cosineSimilarity(c)}%.3f"
}
```

The same pair by L2 distance — note the direction flips (the lookalike is the *smaller* number):

```scala mdoc
{
  val a = FaceEmbedding(Vector(1.0f, 0.2f, 0.1f))
  val b = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f))
  val c = FaceEmbedding(Vector(-1.0f, -0.3f, 0.4f))
  f"lookalike: ${a.l2Distance(b)}%.3f, stranger: ${a.l2Distance(c)}%.3f"
}
```

:::warning[Comparisons must be same-length]
`cosineSimilarity` and `l2Distance` `require` both embeddings to have the same dimension — real
SFace embeddings are always 128, so this only bites when you accidentally mix in a stand-in vector
of a different length.
:::

## Enrolling and identifying: the Gallery

A `Gallery` is an immutable "who is this?" lookup. Enrol named embeddings, then `identify` a fresh
face — the best match at or above the threshold wins, or `None` for a stranger. The default
threshold is SFace's recommended `0.363` (exposed as `Gallery.CosineThreshold`).

```scala mdoc
{
  val gallery = Gallery.empty
    .enroll("ada", FaceEmbedding(Vector(1.0f, 0.2f, 0.1f)))
    .enroll("grace", FaceEmbedding(Vector(0.1f, 1.0f, 0.2f)))

  val probe = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f)) // looks like ada
  gallery.identify(probe) match
    case Some(FaceMatch(name, s)) => f"recognised $name ($s%.3f)"
    case None                     => "stranger"
}
```

A `FaceMatch` carries the winning `name` and the `similarity` that won — keep the score to reject
weak matches downstream or to rank candidates. `identify` takes an optional `threshold` argument if
you want to be stricter or looser than the default:

```scala mdoc
{
  val gallery = Gallery.empty.enroll("ada", FaceEmbedding(Vector(1.0f, 0.2f, 0.1f)))
  val probe = FaceEmbedding(Vector(0.6f, 0.5f, 0.4f))
  val lenient = gallery.identify(probe, threshold = 0.2).isDefined
  val strict = gallery.identify(probe, threshold = 0.9).isDefined
  s"lenient match: $lenient, strict match: $strict"
}
```

### Multiple poses per person

The same name may be enrolled several times — one embedding per pose, lighting, or expression.
`identify` takes the **best-scoring** of all a person's enrolments, so more reference shots means
more robust recognition. `names`, `size`, and `isEmpty` report on the gallery's contents:

```scala mdoc
{
  val gallery = Gallery.empty
    .enroll("ada", FaceEmbedding(Vector(1.0f, 0.2f, 0.1f)))   // frontal
    .enroll("ada", FaceEmbedding(Vector(0.9f, 0.3f, 0.15f)))  // slight turn
    .enroll("grace", FaceEmbedding(Vector(0.1f, 1.0f, 0.2f)))
  (gallery.size, gallery.names.distinct.sorted, gallery.isEmpty)
}
```

Enrolment is a **value operation** — `enroll` returns a new gallery — so a gallery is safe to
share, snapshot across threads, or persist. To save one, serialise the `(name, embedding.values)`
pairs (they are just `String`/`Vector[Float]`); to restore, `enroll` them back into
`Gallery.empty`:

```scala mdoc
{
  // Round-trip a gallery through plain data, as you would to a file or DB.
  val original = Gallery.empty.enroll("ada", FaceEmbedding(Vector(0.3f, 0.9f, 0.1f)))
  val saved: Seq[(String, Vector[Float])] = original.names.zip(Seq(Vector(0.3f, 0.9f, 0.1f)))
  val restored = saved.foldLeft(Gallery.empty)((g, e) => g.enroll(e._1, FaceEmbedding(e._2)))
  restored.size
}
```

## Putting it together

The end-to-end shape is: detect faces once to enrol your known people, then for each incoming frame
detect, embed, and `identify`. Both `faces` and `embed` borrow the frame, so no `.copy` is needed:

```scala mdoc:compile-only
FaceRecognizer.load("sface.onnx").foreach { rec =>
  val detector = FaceDetect.create("yunet.onnx", Size(320, 320)).toOption.get

  // Enrol from labelled reference photos.
  var gallery = Gallery.empty
  for (name, path) <- Seq("ada" -> "ada.jpg", "grace" -> "grace.jpg") do
    Image.reading(path) { ref =>
      ref.faces(detector).headOption.foreach(f => gallery = gallery.enroll(name, rec.embed(ref, f)))
    }

  // Identify everyone in a new frame.
  Image.reading("group.jpg") { frame =>
    for face <- frame.faces(detector) do
      val embedding = rec.embed(frame, face)
      gallery.identify(embedding) match
        case Some(FaceMatch(name, s)) => println(f"$name ($s%.2f)")
        case None                     => println("stranger")
  }

  detector.release()
  rec.close()
}
```

:::danger[Threading]
- **`FaceDetectorYN`** (the detector behind `Image.faces`) — stateful, **one per thread**: `detect`
  re-sets its input size on every call.
- **`FaceRecognizer`** — **also one per thread**. `alignCrop` and `feature` both run against a
  single native object, and OpenCV's `FaceRecognizerSF.feature()` writes into an internal buffer
  that `embed` copies its numbers out of. If a second thread calls `embed` in between, it
  overwrites that buffer and the first thread copies out the *other* face's vector. Build one
  recognizer per worker, or guard the shared one with a lock or a `ThreadLocal`. It is
  `AutoCloseable`, so close each one when its thread retires.
- **`Gallery` and `FaceEmbedding`** — genuinely safe to share: they are immutable values, and
  `enroll` returns a *new* gallery rather than mutating the one you had.

See [What is safe to share](/concurrency#what-is-safe-to-share).
:::

## Accuracy checklist

If recognition is flaky, the cause is almost always upstream of the metric:

| Symptom | Likely cause | Fix |
|---|---|---|
| Everyone matches everyone | threshold too low | keep the default `0.363`, or raise it |
| Known people read as strangers | too few enrolments, or bad reference crops | enrol several poses per person |
| Random misidentification | detection missed/mis-aligned the face | check `Image.faces` finds the face first |
| `embed` throws | frame is not BGR, or landmarks are stale | pass the exact BGR frame the `Face` came from |
| Embeddings differ run to run, or an occasional garbage vector | one `FaceRecognizer` shared across threads | one recognizer per worker — see [What is safe to share](/concurrency#what-is-safe-to-share) |

## Next

- [Object detection](/object-detection) — find and align the faces recognition depends on.
- [DNN](/dnn) — the ONNX machinery under SFace, and running your own networks.
- [Conferencing](/conferencing) — a camera loop to feed live frames into this pipeline.
