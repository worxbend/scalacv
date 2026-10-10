#import "../lib/book.typ": *

#chapter("Models: Fetching, Caching, Verifying", subtitle: [A detector is code plus weights, and only one of those two things fits in a jar.])

Everything in the book so far has been self-contained. A blur is arithmetic over a neighbourhood, a
contour is a walk along a boundary, a Hough transform is a vote in a parameter space. You added a
dependency, called a function, and the answer came back --- there was never a third thing that had to
be true about the machine.

A learned detector breaks that. The code that runs YuNet is in the OpenCV native library you already
have; the 232,589 bytes that make it a *face* detector rather than an untrained graph are not. They
live in a file somebody else trained, under somebody else's licence, on a host neither you nor this
library controls. Between "add the dependency" and "detect a face" there is now a step that fails for
reasons unrelated to your program: the network is down, the mirror moved, the proxy rewrote the
response, the container has no outbound route at all.

The tempting fix is to commit the file --- 232 kB, working on every machine forever. scalacv does not,
and the reason is not size: SFace is about 37 MB and even that would fit in a jar. It is that the
moment a copy of YuNet lands in this repository, scalacv takes on the obligation to reproduce YuNet's
MIT notice and hands it to every downstream redistributor of the jar. `THIRD-PARTY.md` records the
model as fetched rather than vendored, and keeps OpenCV's attribution and YuNet's as separate lines. A
runtime fetch leaves the obligation with whoever chose to redistribute the weights.

So the file arrives at run time, and this chapter is plumbing rather than algorithm: how to name a
model, how to prove the bytes you got are the bytes you meant, where to put them, and what to do when
none of that works. One example runs through it: the start-up path of a photo-triage service that
detects faces on upload and matches them against an enrolled gallery. It needs two models before it
can serve a request, and must refuse its health check until it has them.

#note[
This part of the book opens the `scalacv-vision` module, and the split matters here. `Models` and
`ModelSpec` are in the *core* `scalacv` artifact: fetching a file and proving it intact needs nothing
else on the classpath. Everything the fetch is *for* --- `FaceDetect`, `FaceRecognizer`, `Dnn`,
`PoseEstimator`, `Segmenter`, `Cascades` --- lives in `scalacv-vision`. Add
`mvn"com.worxbend::scalacv-vision:0.4.1"` beside the core line and your platform's natives; Chapter 2
has the sbt and scala-cli spellings.
]

#sect("Three payloads, and only one is a download")

Three kinds of binary payload get conflated, and they arrive by three different routes. Only one of
them is missing.

#figure-table("What ships in the jars, and what does not.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Payload*], [*In the jars?*], [*How you get it*],
  [OpenCV natives (`.so`, `.dylib`, `.dll`)], [Yes], [extracted on the first `OpenCv.load()`, into `~/.javacpp`],
  [Haar and LBP cascade XML], [Yes], [extracted from the bytedeco jars on the first `Cascades.load(CascadeName.FrontalFaceAlt)` --- no network],
  [ONNX models (YuNet, SFace, pose, segmentation, yours)], [No], [this chapter],
)
]

The cascades are the only detector family that needs no provisioning step at all --- a real property,
even though they are markedly less accurate than YuNet. The natives are a cache, not a download:
Chapter 2 covered the 196 MB `OpenCv.load()` unpacks once on Linux. Only the third row involves a URL.

#sect("A model is a spec, not a URL")

The naive version of this is a string.

```scala
val url = "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/" +
  "models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
```

That string is four decisions collapsed into one, three of them invisible: where the file comes from,
what it is called once it lands, what happens when that host is unreachable, and whether the bytes
that arrived are the bytes anybody intended. `ModelSpec` separates them.

#example("The whole `ModelSpec` surface. A private constructor and two named builders.")[
```scala
final case class ModelSpec private (
    fileName: String,
    urls: Seq[String],
    sha256: Option[String],
    sizeBytes: Option[Long]
)

object ModelSpec:
  def apply(
      fileName: String,
      urls: Seq[String],
      sha256: String,
      sizeBytes: Option[Long] = None
  ): ModelSpec

  def unverified(fileName: String, urls: Seq[String]): ModelSpec
```
]

The constructor is `private`, so those two builders are the only ways in, and the asymmetry between
them is the design. `ModelSpec(...)` takes `sha256` as a required `String`: the verifying form is what
you get by default and by accident. `ModelSpec.unverified(...)` is the opt-out, and it says the word
at the call site, in the diff, and in the review. Both reject an empty `fileName` and empty `urls`
with an `IllegalArgumentException`, and `sizeBytes` must be positive if given --- a spec with no
source or no name is a programmer error, not a runtime condition. `urls` is tried first to last;
`fileName` is fixed rather than derived from the URL, which is what makes caching possible, because
without a name agreed in advance "is this already here?" has no answer.

`sizeBytes` earns its place by changing the message rather than the outcome. A wrong-size file fails
the checksum anyway --- but *"expected 232589 bytes for `face_detection_yunet_2023mar.onnx` but got
131"* sends the reader to the download, while *"SHA-256 mismatch"* invites them to suspect tampering.
It is checked first, so it costs one `stat` rather than a full digest over a file that was never the
model.

#sect("What `fetch` actually does")

One method does the work.

```scala
def fetch(spec: ModelSpec, into: Path): Either[CvError, Path]
```

`into` is a *directory*, created if absent; the file is written to `into.resolve(spec.fileName)` and
that path comes back. The sequence inside is worth stating in full, because every step is a defence.

+ *Is it already here?* A regular file at the target that still passes verification --- the pinned
  size, then the pinned hash --- is returned untouched and no socket opens. This is what makes it safe
  to call on every start-up.
+ *Create the directory.* A failure here is
  `Left(CvError.LoadFailed(into.toString, "could not create the download directory: …"))`. Only this
  step sits under that message; a wider `try` would report a refused connection as a directory
  problem.
+ *Try each URL in turn.* The first that downloads *and* verifies wins, and the fold stops there ---
  the remaining mirrors are not contacted. Every failure is accumulated in order, so if all of them
  fail you get one `Left` listing what each one did.
+ *Download to a sibling temp file*, named `.model-<random>.part` in the target's own directory, so
  the final move stays on one filesystem.
+ *Verify the temp file*, before anything else looks at those bytes --- with the message re-attributed
  to the URL it came from rather than the temp path, because the mirror is what you have to act on.
+ *Move it onto the target*, and only then. Then *delete the temp file* in a `finally`, whatever
  happened.

The last four steps are one property: an interrupted run, a full disk, a `SIGKILL` mid-transfer or a
mirror that dies halfway can never leave a truncated model at the target path. Either the file at
`into/<fileName>` is a verified model or it does not exist.

#warning[
An unreadable cache entry is a *miss*, not a failure. Hashing it opens the file, which can throw for
reasons unrelated to the model --- deleted between the `isRegularFile` check and the read, permissions
lost, volume gone --- and `fetch`'s signature promises to report failure as a `Left`. Re-downloading
answers all of those, and a destination that really is unusable produces a `Left` naming the real
problem.
]

Each mirror gets a 15-second connect budget and a 60-second request budget, and the request budget
covers the whole exchange rather than idle time. That matters for the 37 MB model on a slow link:
every mirror times out and you get a `Left` that reads like an outage but is a bandwidth problem.
Redirects are followed --- the client is built with `Redirect.NORMAL`. Do not wrap `fetch` in a retry
loop; the retry across mirrors is the loop, and a second one on top multiplies the time to a clear
failure.

#sect("The two specs that ship")

Two models are pinned in the library itself, because two of its APIs are inert without them.

#figure-table("The built-in specs, with the values the source declares.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [], [*YuNet*], [*SFace*],
  [Spec], [`FaceDetect.modelSpec`], [`FaceRecognizer.modelSpec`],
  [File name], [`face_detection_yunet_2023mar.onnx`], [`face_recognition_sface_2021dec.onnx`],
  [Size pinned], [`FaceDetect.ModelSizeBytes` = 232589], [none],
  [Digest], [`FaceDetect.ModelSha256`], [`0ba9fbfa…4e79`],
  [Mirrors], [2, both on `media.githubusercontent.com`], [1, a `github.com/…/raw/main/…` URL],
  [Fetch], [`FaceDetect.downloadModel(dir)`], [`Models.fetch(FaceRecognizer.modelSpec, dir)`],
  [Load], [`FaceDetect.create(path, inputSize)`], [`FaceRecognizer.load(path)`],
  [Produces], [boxes plus 5 landmarks per face], [a 128-value `FaceEmbedding`],
)
]

`FaceDetect.downloadModel(into)` is one line --- `Models.fetch(modelSpec, into)` --- and exists only
as the discoverable name beside the detector that needs it. The two were separate implementations of
the same download-verify-move dance until they were merged, which is how one of them ended up carrying
a bug the other had already fixed. Once you fetch more than one model, use `Models.fetch` and a list
of specs. YuNet's first mirror pins the exact commit that last touched the file, so those bytes cannot
change underneath you; the second follows `main`, so a repository reorganisation degrades to a
fallback instead of an outage. Neither is trusted --- the checksum decides, on both.

#sidebar("The 131-byte model")[
Large files in a Git repository are usually stored with Git LFS: the repository holds a small text
*pointer*, and the real bytes live on a separate media host. The OpenCV Zoo keeps its `.onnx` files
this way.

Fetch one from a `raw.githubusercontent.com` URL and you get HTTP 200 and the pointer. Nothing errors.
You now have a 131-byte file called `face_detection_yunet_2023mar.onnx`, and the first thing to
complain is OpenCV's ONNX importer, with a message about the graph rather than the download.

Both defences are in the source. `FaceDetect.ModelUrls` uses `media.githubusercontent.com/media/…`,
the host that serves the object rather than the pointer --- and that pointer at least carries an
`oid sha256:` line, which is where `ModelSha256` was cross-checked from. And `FaceDetect.modelSpec`
pins `sizeBytes`, so a pointer is reported as the wrong byte count rather than an opaque digest
mismatch. SFace takes the other route: its single `github.com/…/raw/…` URL is followed through
whatever redirect that host answers with, and the checksum decides what arrived. Expect this failure
first in your own specs: a three-digit byte count is a pointer or an error page, not a corrupt model.
]

#sect("What the library knows how to decode")

Two specs ship, but three further capabilities have a decoder in the library waiting for a model you
supply. A tensor does not say what its numbers mean --- `[1, 1, 17, 3]` is seventeen keypoints, not
seventeen *named* keypoints --- so scalacv splits the job: you bring the ONNX, and the library names
the layout and turns the tensor into typed data.

#figure-table("The model families with a decoder in the library.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [*Capability*], [*Family*], [*Input*], [*Decoder, and the shape it wants*],
  [Face detection], [YuNet], [`Size(320, 320)`], [`image.faces(detector)` --- 15-column rows],
  [Face recognition], [SFace], [fixed by the model], [`rec.embed(image, face)` --- 128 floats],
  [Pose, regression], [MoveNet-style], [`Size(192, 192)`], [`PoseEstimator.decode(out, size, KeypointLayout.Regression, PoseTopology.CocoBody17)` --- `[1, 1, K, 3]`],
  [Pose, heatmap], [OpenPose-style], [as documented], [the same call with `KeypointLayout.Heatmap` --- `[1, K, H, W]`],
  [Hand landmarks], [MediaPipe hand, as ONNX], [`Size(224, 224)`], [`PoseEstimator.decode(…, PoseTopology.Hand21)`, then `GestureRecognizer`],
  [Selfie segmentation], [MODNet, MediaPipe selfie], [`Size(256, 256)`], [`Segmenter.decodeMask(out, size)` --- `[1, 1, H, W]` or `[1, 2, H, W]`],
  [Anything else], [any ONNX export], [as documented], [none --- `Dnn.fromOnnx`, and read the tensor yourself],
)
]

A topology is not in the file, which is why `PoseTopology` is an argument: its `size` must match the
model's keypoint count or `decode` fails by name rather than throwing OpenCV's total-size exception.
The parameter that goes wrong silently is `swapRB`. Almost every published model was trained on RGB
while OpenCV decodes to BGR, so `Image.estimatePose` and `Image.segment` default it to `true` while
`Dnn.blobFromImage` keeps OpenCV's own `false`. Keypoints that are plausible but consistently off are
this, first.

#sect("Everything fails as `LoadFailed`")

One error case covers all of it. `CvError.LoadFailed(resource, details)` is a named resource that
could not be resolved, loaded, or verified --- a model file, a cascade, an ONNX network, a download.
It is deliberately not `DecodeFailed`, which is about image *bytes*: an HTTP 404 for a model is not an
image-decode failure and should not read like one. Chapter 6 laid out the hierarchy; this chapter uses
one branch of it. The `resource` field tells you which stage failed, because it is set to a different
thing at each one.

#figure-table("Reading a `LoadFailed` from the download path.")[
#tbl(
  columns: (auto, 1fr),
  [*`resource` is*], [*Then `details` says*],
  [the directory you passed], [`could not create the download directory: …` --- `into` is not writable, or a file already sits at that path],
  [`spec.fileName`], [`could not be downloaded from any source.` followed by one indented line per mirror],
  [a URL], [what that one mirror did: `HTTP 404 from …`, a JDK timeout message, a size mismatch, or a digest mismatch],
  [the model path], [`there is no readable file at this path.` --- `FaceDetect.create`'s own pre-check, before OpenCV sees it],
)
]

Learn the two verification messages so you recognise them at three in the morning. The size mismatch
is the one quoted above; the digest mismatch reads *"SHA-256 mismatch for
`face_detection_yunet_2023mar.onnx`: expected …, got …. Refusing to load an unverified model."*
Roughly the right size with the wrong digest means the publisher moved the file under the same name
and your pin is stale --- precisely the situation the pin exists to surface.

The edges are worth stating. `Models.fetch` sends a bare `GET` with no headers, so a model behind a
token or a private bucket is out of reach. There is no proxy knob, no resume, no progress, and no size
limit --- a mirror that serves ten gigabytes writes ten gigabytes to disk and *then* fails the
checksum. Nor is there locking between processes. All of that has the same workaround, and it is the
next section.

#sect("`file://` is not a special case")

The scheme dispatch inside `download` has two branches. `http` and `https` go through the
timeout-bounded `HttpClient`; everything else falls back to `uri.toURL.openStream()`, because the JDK
HTTP client does not serve `file://`. That one fallback is what makes offline deployment fall out of
the design rather than being bolted onto it.

#example("The same code path on a connected machine and an air-gapped one.")[
```scala
val yunet = ModelSpec(
  fileName = "face_detection_yunet_2023mar.onnx",
  urls = Seq(
    "file:///opt/models/face_detection_yunet_2023mar.onnx",  // the baked-in copy, if there is one…
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/" +
      "models/face_detection_yunet/face_detection_yunet_2023mar.onnx"  // …otherwise the network
  ),
  sha256 = FaceDetect.ModelSha256,
  sizeBytes = Some(FaceDetect.ModelSizeBytes)
)
```
]

A machine that has the file never opens a socket; a machine that does not falls through to the mirror.
Both run the same verification, so a container whose `/opt/models` layer is corrupt, or whose
read-only mount points at last quarter's file, fails at start-up with a named error instead of
misbehaving at inference time. The pin is not only a network defence: it states which bytes this build
was tested against.

It is also the answer to every limitation above. A model behind an artifact registry, a 37 MB file on
a link too slow for the request budget, a proxy that needs headers --- fetch it with a tool that can
do those things at image-build time and point a `file://` spec at the result. You keep the integrity
gate and lose nothing. The container shape then mirrors the native cache, which Chapter 41 covers in
full: bake the models into an image layer for a self-contained cold start, or mount them from a shared
volume for a thin image.

#memory[
Nothing in `Models` owns native memory. Everything it *unlocks* does. `FaceDetect.create` hands back
`Either[CvError, Managed[FaceDetectorYN]]` and `Dnn.fromOnnx` hands back
`Either[CvError, Managed[Net]]`; `FaceRecognizer.load` hands back an `Either[CvError, FaceRecognizer]`
whose `FaceRecognizer` is an `AutoCloseable` wrapping a `Managed[FaceRecognizerSF]`. Only `Mat`,
`VideoCapture` and `VideoWriter` expose a public `release()`; the other 185 generated types --- these
three among them --- do not, so `Managed` frees them through `Releasable.nativeHandle`, which reads the
address and disarms the binding's unconditional `finalize()` *before* freeing the pointer. Without
that disarm a released `FaceDetectorYN` is a live double-free, and because a DNN allocates enough to
make the collector run mid-suite, this library's own face tests are where that SIGSEGV first appeared,
on the `Finalizer` thread, inside `Java_org_opencv_objdetect_FaceDetectorYN_delete`. Build one
detector at boot and keep it; a service that builds one per request allocates a network per request.
]

#sect("Pin the model, or pin nothing at all")

`Models.fetch` keys its cache on `into.resolve(spec.fileName)` and nothing else, and the test for "is
the file on disk still good?" is "does it match what the spec pins". Follow both halves of that
sentence through the `unverified` case and the consequence is larger than a lost integrity check.
With a hash pinned, a model upgrade rolls itself out: same name, new hash, the stale file fails
verification and is replaced. With `ModelSpec.unverified`, the cache test is vacuously true for
*anything* already at that path. Ship new weights under the old file name and every replica that has
ever run keeps answering with the old ones, with no error anywhere and no request to the URL.

#caution[
A silently stale model is worse than a broken one. A broken model fails at boot, on one replica, with
a message naming the file --- you find out in minutes and nothing downstream believed anything false.
A stale model serves plausible answers indefinitely: accuracy drifts, your recognition threshold stops
meaning what it meant, and the first evidence is a ticket about a result nobody can reproduce, because
the replica that produced it has since been rescheduled onto a node with a different cache.
]

The habit that fixes it: *put the version in the file name.* `yolov8n-v3.onnx`, not `yolov8n.onnx`.
Two versions then coexist on disk, a rollback is a configuration change rather than a cache purge, and
a rolling deploy never has two replicas fighting over one path. Pin the hash as well, because the
digest is the only thing that survives a model being republished under a name you already trusted.
Then record what ran: a `ModelSpec` is a value with a `fileName` and an `Option[String]` digest, both
cheap to log at start-up and stamp onto whatever your service emits beside a result. A stored
`FaceEmbedding` is meaningless without knowing which SFace produced it --- a gallery enrolled under one
version and queried under another degrades quietly rather than failing.

#tip[
A converted model's digest is *yours*. `tf2onnx` is not byte-reproducible across tool versions, so if
you converted a TFLite export --- which is what MediaPipe publishes, and OpenCV's ONNX importer will
not read --- pin the artefact you actually tested and give it a versioned name. Conversion is a
build-time step; do not attempt it at run time.
]

#sect("Licences travel with weights, not with the library")

Say this plainly, because it is the part discovered during a legal review rather than during
development. scalacv is Apache-2.0. The models are not scalacv. YuNet is MIT (Shiqi Yu), and
`THIRD-PARTY.md` carries that attribution separately from OpenCV's own. SFace comes from the same
OpenCV Zoo under the terms published there, which scalacv does not restate for you; a model you found
on a hub carries whatever its author chose --- sometimes Apache-2.0, sometimes MIT, sometimes
non-commercial or research-only with no exception for what you are building. Bake a model into a
container image and you are redistributing it, terms and all. Read them before the file goes into the
image, not after the image goes to a customer. The download is the point
at which that decision becomes yours --- which is why it is a download.

#sect("The triage service, at boot")

Everything above assembles into one function. The triage service needs both models on disk and both
handles built before it answers a health check, and it must say which stage failed if it cannot.

#example("Fetch, then load, then gate readiness on the result.")[
```scala
import java.nio.file.Path
import scalacv.*
import org.opencv.objdetect.FaceDetectorYN

final case class Triage(detector: Managed[FaceDetectorYN], recognizer: FaceRecognizer)

/** Everything that must exist before this process serves a request. */
def warmUp(modelDir: Path, expected: Size): Either[CvError, Triage] =
  OpenCv.load()                                    // idempotent, thread-safe, extracts on first call
  for
    yunet      <- Models.fetch(FaceDetect.modelSpec, modelDir)
    sface      <- Models.fetch(FaceRecognizer.modelSpec, modelDir)
    detector   <- FaceDetect.create(yunet.toString, expected)
    recognizer <- FaceRecognizer.load(sface.toString)
  yield Triage(detector, recognizer)
```
]

The comprehension is over `Either`, so the first `Left` short-circuits carrying its own `resource` and
`details` --- you never guess whether the download or the load failed. Both fetches are idempotent, so
every boot after the first costs two digests and no sockets. Nothing here reads an `Image`, so there
is no consumed handle to trip over, and both native owners leave in the `Triage` value --- the only
place anything can release them later.

#example("A `LoadFailed` at boot is a refusal to start, not a warning to log.")[
```scala
warmUp(Path.of("/var/lib/triage/models"), Size(320, 320)) match
  case Right(ready) =>
    server.run(ready)                              // your service, holding both handles for its life

  case Left(CvError.LoadFailed(resource, details)) =>
    System.err.println(s"triage cannot start: '$resource' --- $details")
    sys.exit(78)                                   // EX_CONFIG: this will not fix itself on retry

  case Left(other) =>
    throw other
```
]

The mistake this replaces is the one that looks more robust: catch the failure, log it, start anyway,
resolve the model lazily on the first request that needs a face. That process passes its health check,
joins the load balancer, and then fails every request with an error about a missing file --- on every
replica at once, minutes after the deploy that caused it. A process that refuses to start fails one
replica at a time, in front of the deploy that broke it, with the file name in the message. The final
`case Left(other) => throw other` matters too: `CvError` is a `RuntimeException` hierarchy, so anything
that is not a `LoadFailed` --- `NativesMissing`, say --- reaches the top of `main` with its own
message rather than one exit code.

`Size(320, 320)` is the frame size you expect, not a constraint. `FaceDetect.create` requires an
`inputSize` because `FaceDetectorYN`'s constructor does, but `FaceDetect.detect` re-sets it on every
frame, which is what lets differently sized images work at all. It still matters --- YuNet's anchors
are laid out for it --- so pass the size you expect and off-size detections stay well calibrated. The
corollary from the same mechanism: a detector is mutated by every `detect` call and is *not* safe to
share across threads. Give each worker its own, built from the same verified file.

#sect("What this bought")

Three properties, from one small object. The model that loads is the model you pinned, on every
machine and every boot, or the process names the stage that failed and stops. The offline case is the
online case with a different first URL. And the licence stayed with whoever chose to ship the weights.

What none of it tells you is whether the bytes are a network OpenCV can import: `Models.fetch` moves
bytes and checks a digest, while whether the graph loads is discovered by `FaceDetect.create`,
`FaceRecognizer.load` or `Dnn.fromOnnx`, each with its own `Left`. Chapter 24, #emph[Face Detection], picks
up the file this chapter put on disk --- YuNet against the Haar cascades it replaces, the fifteen
columns a detection row carries, and why a detector is something you build exactly once.
