package scalacv.vision

import java.nio.file.Path

import org.opencv.core.{CvType, Mat, Size as CvSize}
import org.opencv.objdetect.FaceDetectorYN

import scalacv.*

/** One face reported by [[FaceDetect.detect]].
  *
  * Plain immutable Scala data, copied out of OpenCV's result Mat, so it stays valid after every native object
  * involved has been freed — see `Geometry` for why that copy is the right trade.
  *
  * @param box
  *   the face's bounding box. It is **not** clipped to the image: YuNet regresses boxes from anchors, so a
  *   face at the edge of the frame legitimately yields a negative `x`/`y` or a box running past
  *   `cols`/`rows`. `Image.crop` and `Mat.submat` both reject such a rectangle outright, so clip it with
  *   [[clippedBox]] before cropping — that is the intersection with the frame, and it answers `None` for a
  *   box that lies entirely outside it.
  * @param landmarks
  *   exactly five points, always in this order: right eye, left eye, nose tip, right mouth corner, left mouth
  *   corner. "Right" is the *subject's* right, so it appears on the left of the image.
  * @param score
  *   the model's confidence in `[0, 1]`. Only faces at or above the detector's `scoreThreshold` are reported.
  */
final case class Face(box: Rect, landmarks: Seq[Point], score: Float):
  require(landmarks.sizeIs == 5, s"a YuNet face has exactly 5 landmarks, got ${landmarks.size}")

  /** The subject's right eye — image-left. */
  def rightEye: Point = landmarks(0)

  /** The subject's left eye — image-right. */
  def leftEye: Point = landmarks(1)
  def noseTip: Point = landmarks(2)
  def rightMouthCorner: Point = landmarks(3)
  def leftMouthCorner: Point = landmarks(4)

  /** This face's [[box]] intersected with a `width`×`height` frame, or `None` when the box falls entirely
    * outside that frame.
    *
    * This is the clip [[box]]'s own documentation asks for, and it is a method rather than a note because
    * every caller would otherwise write it by hand: `Image.crop` and `Mat.submat` both reject a region of
    * interest that runs past an edge, YuNet produces exactly such a box for any face at the border of the
    * frame, and a hand-rolled four-way `min`/`max` is the classic home of an off-by-one.
    *
    * The intersection is **half-open** on both axes — the region is `[x, x + width)` — matching what
    * [[Rect.bottomRight]] already documents ("one past the last enclosed pixel") and what `submat` expects. A
    * box that merely *touches* the frame along an edge therefore answers `None` rather than a zero-extent
    * `Rect`: `Rect` permits a zero extent but `submat` throws on one, so an empty overlap must not be
    * representable as something a caller can hand to `crop`.
    *
    * {{{
    * val boxes  = frame.faces(detector).flatMap(_.clippedBox(frame.width, frame.height))
    * val thumbs = boxes.map(r => frame.copy.crop(r))
    * }}}
    *
    * @param width
    *   the frame's width in pixels — the image this face was detected in.
    * @param height
    *   the frame's height in pixels.
    * @return
    *   a rectangle that lies wholly inside the frame and has a positive extent on both axes, so it always
    *   satisfies `Image.crop`'s precondition; `None` when there is no overlap at all.
    * @throws IllegalArgumentException
    *   if `width` or `height` is negative. A frame has no such shape, and clipping to it would silently
    *   answer `None` for every face rather than reporting the mistake.
    */
  def clippedBox(width: Int, height: Int): Option[Rect] =
    require(width >= 0 && height >= 0, s"a frame cannot have a negative extent: ${width}x$height")
    // Long, not Int, because the box is decoded with `.round` from the model's floats: a degenerate
    // detection can land Int.MaxValue in x or width, and `x + width` in Int arithmetic would then wrap
    // negative and turn a box far off the right edge into one that appears to overlap. Each value below is
    // bounded by the frame, so narrowing back to Int afterwards cannot lose anything.
    val x0 = math.max(box.x.toLong, 0L)
    val y0 = math.max(box.y.toLong, 0L)
    val x1 = math.min(box.x.toLong + box.width, width.toLong)
    val y1 = math.min(box.y.toLong + box.height, height.toLong)
    if x1 <= x0 || y1 <= y0 then None
    else Some(Rect(x0.toInt, y0.toInt, (x1 - x0).toInt, (y1 - y0).toInt))

  /** `clippedBox(image.width, image.height)` — the convenience form for the usual case, where the frame to
    * clip to is the image the detection was run on. `image` is only queried for its size, so it stays alive
    * and owned by the caller, exactly as [[FaceDetect.detect]] left it.
    */
  def clippedBox(image: Image): Option[Rect] = clippedBox(image.width, image.height)

/** YuNet face detection over `org.opencv.objdetect.FaceDetectorYN`.
  *
  * A small CNN (232 kB) that is both far more accurate and far faster than the Haar cascades in [[Cascades]],
  * and unlike them it returns five facial landmarks per face. It is the detector to reach for; the cascades
  * remain for heritage and for environments where no model file can be shipped.
  *
  * Four things about `FaceDetectorYN` are easy to get wrong, and each is handled here rather than left to the
  * caller. All four were verified against the 4.13.0 bindings and the running library, not read off a blog:
  *
  *   1. **The input size is fixed at construction and enforced at detect time.** `detect` runs
  *      `CV_CheckEQ(input_image.size(), input_size)` and throws a `CvException` if a later frame differs by a
  *      single pixel — which is exactly what happens the first time you feed it a resized frame, or a webcam
  *      that renegotiated its resolution. [[detect]] therefore calls `setInputSize` for every frame, so any
  *      Mat works. The cost is that a detector is **stateful and not safe to share across threads**; give
  *      each thread its own.
  *   1. **`detect` returns an `int` status flag, not a face count.** It is `1` when the network ran and `0`
  *      when the input was empty. Reading it as a count silently reports one face for every successful call.
  *      The count is `faces.rows()`.
  *   1. **No faces means a 0x0 Mat**, not an Nx15 Mat with zero rows. Any decode loop that trusts `cols()`
  *      without checking `empty()` first will read column 14 of a Mat that has no columns.
  *   1. **A detection row has 15 columns**, all `CV_32F`: `x, y, w, h`, then five `(x, y)` landmark pairs,
  *      then the score. [[detect]] fails loudly if a future model emits a different width rather than
  *      decoding garbage.
  *
  * The model itself is **not shipped with scalacv** — see [[downloadModel]].
  */
object FaceDetect:

  /** `FaceDetectorYN` is one of the 185 generated types with no public `release()`, so it needs the
    * `delete(long)` bridge. Public so callers who build their own detector — with a `MatOfByte` buffer, or a
    * non-default `topK` — can `import FaceDetect.given` and manage it on the same terms.
    *
    * This is the same one-liner every other handle type uses ([[Cascades]], [[Dnn]], [[Qr]], [[Aruco]]):
    * [[Releasable.nativeHandle]] reads the address, then disarms the binding's unconditional `finalize()`
    * *before* freeing the pointer. That disarm is not optional — without it a released `FaceDetectorYN` is a
    * live double-free, and because a DNN allocates enough to make the collector run mid-suite, this class's
    * own tests are where that SIGSEGV first surfaced:
    *
    * {{{
    * SIGSEGV (0xb)  C  [libopencv_java.so+0x163155]  Java_org_opencv_objdetect_FaceDetectorYN_delete
    * Current thread: JavaThread "Finalizer"
    * }}}
    *
    * The hazard belongs to every one of the 185 types, not to YuNet, so the fix lives in `handle` rather than
    * here.
    */
  given Releasable[FaceDetectorYN] = Releasable.nativeHandle

  /** The number of columns in one row of YuNet's output Mat: `x, y, w, h`, 5 landmark pairs, score. */
  val ResultColumns: Int = 15

  /** The number of landmarks YuNet reports per face. */
  val LandmarkCount: Int = 5

  /** The file name [[downloadModel]] writes, and the name every OpenCV Zoo mirror uses. */
  val ModelFileName: String = "face_detection_yunet_2023mar.onnx"

  /** The SHA-256 of that file. Checked before the model is ever handed to OpenCV. */
  val ModelSha256: String = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

  /** Its exact size. Cheap pre-check that turns a truncated or HTML-error-page download into a clear message
    * instead of a hash mismatch.
    */
  val ModelSizeBytes: Long = 232589L

  /** Where the model is fetched from, in the order tried.
    *
    * Both are `media.githubusercontent.com`, **not** `raw.githubusercontent.com`: the OpenCV Zoo keeps its
    * `.onnx` files in Git LFS, and the `raw` host serves the 131-byte LFS *pointer* for them, which downloads
    * with HTTP 200 and then fails to load as a network. (That pointer does at least carry the same
    * `oid sha256:` we pin below, which is where [[ModelSha256]] was cross-checked from.)
    *
    * The first URL pins the commit that last touched the file, so the bytes cannot change under us; the
    * second follows `main` and exists only so a repository reorganisation degrades to a fallback rather than
    * an outage. Both are verified by the checksum regardless, so neither is trusted.
    */
  val ModelUrls: Seq[String] = Seq(
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/" +
      "f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_detection_yunet/" + ModelFileName,
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/" +
      ModelFileName
  )

  /** This detector's model as a [[ModelSpec]] for the generic [[Models.fetch]] downloader — the registry form
    * of [[downloadModel]], carrying the same file name, mirrors and pinned checksum.
    */
  val modelSpec: ModelSpec =
    ModelSpec(ModelFileName, ModelUrls, ModelSha256, sizeBytes = Some(ModelSizeBytes))

  /** Builds a detector from an ONNX model on disk.
    *
    * `inputSize` is required by the constructor but does not constrain what you may later detect on:
    * [[detect]] re-sets it per frame. It still matters, because YuNet's anchors are laid out for it — pass
    * the size of the frames you actually expect and detections on off-size images stay well calibrated.
    *
    * A `Left` means the file is missing, unreadable, or not a network OpenCV's ONNX importer accepts. That
    * check is deliberate and it is the whole point of returning an `Either` here: unlike `CascadeClassifier`,
    * `FaceDetectorYN.create` does throw for a bad model, and an unhandled `CvException` out of a constructor
    * is not a useful failure for a caller who merely mistyped a path.
    *
    * The returned detector is **caller-owned**: release it, or take it with [[Managed.use]].
    *
    * @param modelPath
    *   path to `face_detection_yunet_2023mar.onnx` — see [[downloadModel]].
    * @param scoreThreshold
    *   minimum confidence to report. OpenCV's own default is 0.9; lower it to catch small or profile faces at
    *   the cost of false positives.
    * @param nmsThreshold
    *   IoU above which two overlapping boxes are treated as the same face and the weaker one dropped.
    * @throws IllegalArgumentException
    *   if `inputSize` has a zero side or a threshold is outside `[0, 1]`.
    */
  def create(
      modelPath: String,
      inputSize: Size,
      scoreThreshold: Float = 0.9f,
      nmsThreshold: Float = 0.3f
  ): Either[CvError, Managed[FaceDetectorYN]] =
    require(
      inputSize.width > 0 && inputSize.height > 0,
      s"FaceDetectorYN needs a positive input size, got ${inputSize.width}x${inputSize.height}"
    )
    require(
      scoreThreshold >= 0f && scoreThreshold <= 1f,
      s"scoreThreshold is a confidence in [0, 1], was $scoreThreshold"
    )
    require(
      nmsThreshold >= 0f && nmsThreshold <= 1f,
      s"nmsThreshold is an IoU in [0, 1], was $nmsThreshold"
    )
    internal.ModelLoader
      .loadNative[FaceDetectorYN](
        modelPath,
        describe = s"creating a FaceDetectorYN from '$modelPath'",
        missingDetails =
          "there is no readable file at this path. The YuNet model is not shipped with scalacv — " +
            s"fetch it with FaceDetect.downloadModel(dir), which writes $ModelFileName and verifies its " +
            "SHA-256."
      )(
        // The empty String is the `config` argument: ONNX carries its weights and topology in one file, so
        // there is no second file to point at. The two ints we leave defaulted are topK (5000) and the
        // backend/target pair (0, 0 = the default DNN backend on the CPU).
        FaceDetectorYN.create(modelPath, "", inputSize.toCv, scoreThreshold, nmsThreshold)
      )
      .map(Managed(_))

  /** Detects every face in `image`.
    *
    * The image is only read from; the caller keeps ownership of it, and the result Mat OpenCV fills in is
    * decoded into [[Face]] values and released before returning — there is no native handle left to own,
    * which is why this returns a `Seq` and not a `Managed`.
    *
    * `detector` is **mutated**: its input size is set to this image's size first, which is what makes frames
    * of differing sizes work at all (see the class comment). Do not share one detector between threads.
    *
    * @return
    *   one [[Face]] per detection, in OpenCV's order — descending score after NMS. Empty when there is no
    *   face, which is not an error. Boxes are not clipped to `image`; clip with [[Face.clippedBox]] before
    *   cropping one out.
    * @throws IllegalArgumentException
    *   if `image` is empty or is not 8-bit 3-channel. Both are programmer errors: YuNet's blob step needs BGR
    *   `CV_8UC3` and fails inside the DNN module otherwise, with a message about layer shapes that says
    *   nothing about the real mistake. Convert with `image.cvtColor(ColorConversion.GrayToBgr)` first.
    * @throws CvError.NativeCall
    *   if the result Mat is not `ResultColumns` wide — i.e. a model that is not this YuNet.
    */
  def detect(detector: FaceDetectorYN, image: Mat): Seq[Face] =
    require(!image.empty(), "FaceDetect.detect needs a non-empty image")
    require(
      image.channels() == 3 && CvType.depth(image.`type`()) == CvType.CV_8U,
      "FaceDetect.detect needs an 8-bit 3-channel BGR image, got " +
        s"${CvType.typeToString(image.`type`())} (${image.cols}x${image.rows})"
    )
    // Per frame, unconditionally. Skipping this when the size "looks unchanged" is how the CvException
    // gets back in: the detector's size is also changed by any other caller holding it.
    Cv.orThrow("FaceDetectorYN.setInputSize")(
      detector.setInputSize(CvSize(image.cols.toDouble, image.rows.toDouble))
    )
    Managed.use(Mat()): faces =>
      // An int, and not a count: 1 = the network ran, 0 = the input was empty (which `require` above has
      // already excluded). The number of faces is faces.rows(). Wrapped so a malformed model surfaces as
      // CvError.NativeCall, matching the column-count check below rather than escaping as a raw CvException.
      val status = Cv.orThrow("FaceDetectorYN.detect")(detector.detect(image, faces))
      // faces stays a 0x0 Mat when nothing was found, so cols() is 0 too — the empty() check has to come
      // before the column-count check or every blank frame looks like a corrupt model.
      if status <= 0 || faces.empty() || faces.rows == 0 then Seq.empty
      else if faces.cols != ResultColumns then
        throw CvError.NativeCall(
          "decoding the FaceDetectorYN result",
          IllegalStateException(
            s"expected $ResultColumns columns (x, y, w, h, 5 landmark pairs, score) but the model " +
              s"produced ${faces.cols}. This is not the YuNet this API decodes."
          )
        )
      else
        val row = Array.ofDim[Float](ResultColumns)
        // Mat.get(r, 0, row) throws UnsupportedOperationException for a non-CV_32F Mat, so the element
        // type is checked by the read itself rather than asserted here.
        (0 until faces.rows).map { r =>
          faces.get(r, 0, row)
          Face(
            box = Rect(row(0).round, row(1).round, row(2).round, row(3).round),
            landmarks =
              (0 until LandmarkCount).map(i => Point(row(4 + i * 2).toDouble, row(5 + i * 2).toDouble)),
            score = row(14)
          )
        }.toSeq

  /** Downloads the YuNet model into the directory `into` and returns the file it wrote.
    *
    * **The model is fetched, never vendored, and that is a licensing decision, not a size one** — it is MIT
    * (Shiqi Yu), which would oblige scalacv to reproduce its notice the moment a copy shipped in this
    * repository or in a published jar. Keeping it a runtime download keeps that obligation with whoever
    * redistributes it. Recorded in `THIRD-PARTY.md`; do not "simplify" this by committing the file.
    *
    * The bytes are verified against [[ModelSha256]] **before** the path is returned, and a mismatch is a
    * `Left` with the two digests in it — never a silently accepted file. A model is executable content
    * fetched over the network from a host we do not control; an unverified one is the most direct supply
    * chain hole this library could have. The download lands in a temp file next to the target and is moved
    * into place only once it has been verified, so an interrupted run cannot leave a truncated model behind
    * for the next one to load.
    *
    * Idempotent: if `into/`[[ModelFileName]] is already there and already hashes correctly, it is returned
    * without touching the network. Call it freely at start-up.
    *
    * This is the named, discoverable form of `Models.fetch(FaceDetect.modelSpec, into)` and nothing more —
    * the two were separate implementations of the same download-verify-move dance until they were merged,
    * which is how one of them ended up with a bug the other had already fixed. If you are fetching several
    * models, prefer [[Models.fetch]] and a list of specs.
    *
    * @param into
    *   a **directory**, created if absent. The file name is fixed — that is what makes the check above
    *   possible.
    * @return
    *   the path to the verified model, or a `Left` describing which stage failed: the directory, every URL
    *   tried, the size, or the checksum.
    */
  def downloadModel(into: Path): Either[CvError, Path] = Models.fetch(modelSpec, into)

/** The high-level face verbs on [[Image]] — extension methods so YuNet detection lives beside [[FaceDetect]]
  * rather than in the image class. `import scalacv.*` gives `image.faces(detector)` and `image.markFaces(…)`.
  */
extension (img: Image)

  /** Faces via a YuNet `FaceDetectorYN` you supply — the model is yours to build (see [[FaceDetect]]). The
    * detector is borrowed and mutated (its input size is set to this image), never released here.
    */
  def faces(detector: FaceDetectorYN): Seq[Face] = FaceDetect.detect(detector, img.mat)

  /** As [[faces]], but taking the [[Managed]] the loaders hand back directly — the recommended path, since
    * the spent-handle guard travels with the argument instead of being discarded by a bare `.get`.
    */
  def faces(detector: Managed[FaceDetectorYN]): Seq[Face] = FaceDetect.detect(detector.get, img.mat)

  /** Annotates detected faces: a box per face and a dot per landmark. The one-call "show me what YuNet found"
    * convenience.
    */
  def markFaces(faces: Seq[Face], color: Scalar = Scalar.Green): Image =
    img.paint: m =>
      faces.foreach: f =>
        m.drawRect(f.box, color)
        f.landmarks.foreach(p => m.drawCircle(p, 2, color, Thickness.Filled))
