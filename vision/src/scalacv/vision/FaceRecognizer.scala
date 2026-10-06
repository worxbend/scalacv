package scalacv.vision

import org.opencv.core.{CvType, Mat}
import org.opencv.objdetect.FaceRecognizerSF

import scalacv.*

/** A face's identity as a fixed-length embedding — a 128-dimensional vector produced by [[FaceRecognizer]].
  *
  * Two faces are compared by the *angle* between their embeddings ([[cosineSimilarity]], higher is more
  * alike) or their [[l2Distance]] (lower is more alike). The vector is plain immutable data, so it outlives
  * every native object and is cheap to store in a [[Gallery]] or a database.
  */
final case class FaceEmbedding(values: Vector[Float]):
  require(values.nonEmpty, "a face embedding cannot be empty")

  /** Cosine similarity in `[-1, 1]` — SFace's own metric. ~0.36 and above is typically the same person. */
  def cosineSimilarity(other: FaceEmbedding): Double =
    require(values.size == other.values.size, "embeddings must have the same length to compare")
    // Every product is widened to Double *before* it is multiplied, not after. `a * b` on two Floats is
    // Float multiplication, and the result is only then widened by `+=`, so each term was being rounded to
    // 24 bits of mantissa while the two norms below — which do carry a `.toDouble` — were not. The
    // comparison is against a fixed threshold (0.363), so it is the one place a systematic rounding bias
    // could nudge a borderline face across the line; and the mismatch made the three accumulators here
    // disagree about their own arithmetic, which is how a reader loses trust in the whole expression.
    val n = values.size
    var dot, normA, normB = 0.0
    var i = 0
    while i < n do
      val a = values(i).toDouble
      val b = other.values(i).toDouble
      dot += a * b
      normA += a * a
      normB += b * b
      i += 1
    if normA == 0 || normB == 0 then 0.0 else dot / (math.sqrt(normA) * math.sqrt(normB))

  /** Euclidean (L2) distance between the embeddings — SFace's alternative metric, ~1.13 and below is the same
    * person.
    */
  def l2Distance(other: FaceEmbedding): Double =
    require(values.size == other.values.size, "embeddings must have the same length to compare")
    // Widened before subtracting, for the same reason as [[cosineSimilarity]]: the difference of two Floats
    // is not always representable as a Float, but is always exact as a Double. No intermediate collection
    // either — `identify` runs this (or its sibling) once per enrolled face, so a 128-element Vector
    // allocated per comparison is a per-lookup cost with nothing to show for it.
    val n = values.size
    var sum = 0.0
    var i = 0
    while i < n do
      val d = values(i).toDouble - other.values(i).toDouble
      sum += d * d
      i += 1
    math.sqrt(sum)

/** A named best match from a [[Gallery]]: who it is and how strong the cosine similarity was. */
final case class FaceMatch(name: String, similarity: Double)

/** An immutable set of enrolled faces — a "who is this?" lookup. Enroll named embeddings, then [[identify]] a
  * fresh one against them; the highest-scoring entry above the threshold wins, or `None` for a stranger.
  *
  * Immutable and value-like: [[enroll]] returns a new gallery, so a gallery is safe to share and snapshot.
  */
final class Gallery private (private val entries: Vector[(String, FaceEmbedding)]):

  /** This gallery plus one more enrolled face. The same name may be enrolled several times (different poses);
    * [[identify]] takes the best-scoring of them.
    */
  def enroll(name: String, embedding: FaceEmbedding): Gallery = new Gallery(entries :+ (name -> embedding))

  /** The best match for `embedding` at or above `threshold` cosine similarity, or `None` if no one is close
    * enough. The default threshold is SFace's recommended 0.363.
    */
  def identify(embedding: FaceEmbedding, threshold: Double = Gallery.CosineThreshold): Option[FaceMatch] =
    entries.iterator
      .map((name, e) => FaceMatch(name, e.cosineSimilarity(embedding)))
      .filter(_.similarity >= threshold)
      .maxByOption(_.similarity)

  /** Every enrolled name (with duplicates if a name was enrolled more than once). */
  def names: Seq[String] = entries.map(_._1)
  def size: Int = entries.size
  def isEmpty: Boolean = entries.isEmpty

object Gallery:

  /** SFace's recommended same-person cutoff for cosine similarity. */
  val CosineThreshold: Double = 0.363

  /** An empty gallery to enroll into. */
  val empty: Gallery = new Gallery(Vector.empty)

/** Face recognition via `org.opencv.objdetect.FaceRecognizerSF` (SFace) — turns an aligned face into an
  * embedding you can compare or look up in a [[Gallery]].
  *
  * The model is **yours to supply**, exactly as with the [[FaceDetect YuNet detector]]: download the SFace
  * ONNX (`face_recognition_sface_2021dec.onnx`, ~37 MB, from the OpenCV Zoo) and hand [[FaceRecognizer.load]]
  * its path. Recognition builds on detection: [[embed]] takes a [[Face]] (from `image.faces`) and the image
  * it came from, aligns and crops the face using its five landmarks, then extracts the embedding.
  *
  * {{{
  * for recognizer <- FaceRecognizer.load("sface.onnx") yield
  *   Using.resource(recognizer): rec =>
  *     val enrolled = Gallery.empty.enroll("ada", rec.embed(refImage, refFace))
  *     enrolled.identify(rec.embed(frame, face)) match
  *       case Some(FaceMatch(name, s)) => println(f"$name ($s%.2f)")
  *       case None                     => println("stranger")
  * }}}
  *
  * Owns a native recognizer — **caller-owned**, [[close]] it (or use `Using`).
  */
final class FaceRecognizer private (private val handle: Managed[FaceRecognizerSF]) extends AutoCloseable:

  /** Aligns and crops `face` out of `image` (using its landmarks) and returns its [[FaceEmbedding]]. The
    * image must be the BGR frame the face was detected in.
    */
  def embed(image: Image, face: Face): FaceEmbedding =
    Managed.scope: own =>
      val row = own(FaceRecognizer.faceRow(face))
      val aligned = own(Mat())
      val feature = own(Mat())
      Cv.orThrow("FaceRecognizerSF.alignCrop")(handle.get.alignCrop(image.mat, row, aligned))
      Cv.orThrow("FaceRecognizerSF.feature")(handle.get.feature(aligned, feature))
      // A model that is not SFace still runs `feature` — it just hands back a row of the wrong shape, which
      // used to become a garbage-length embedding that compared fine against nothing. Mirror FaceDetect's
      // column-count check: a wrong shape is a wrong model, reported as a NativeCall.
      if feature.rows != 1 || feature.cols != FaceRecognizer.EmbeddingSize then
        throw CvError.NativeCall(
          "decoding the FaceRecognizerSF feature",
          IllegalStateException(
            s"expected a 1x${FaceRecognizer.EmbeddingSize} feature row but the model produced " +
              s"${feature.rows}x${feature.cols}. This is not the SFace this API decodes."
          )
        )
      // feature() reuses an internal buffer across calls, so copy the row out before it is overwritten.
      // Mat.get throws UnsupportedOperationException for a non-CV_32F Mat, so the element type is checked
      // by the read itself rather than asserted here.
      val out = Array.ofDim[Float](feature.cols)
      feature.get(0, 0, out)
      FaceEmbedding(out.toVector)

  def close(): Unit = handle.release()

object FaceRecognizer:

  private given Releasable[FaceRecognizerSF] = Releasable.nativeHandle

  /** The embedding length SFace produces — what [[embed]] asserts the feature row to be. */
  val EmbeddingSize: Int = 128

  /** The file name [[Models.fetch]] writes, and the name every OpenCV Zoo mirror uses. */
  val ModelFileName: String = "face_recognition_sface_2021dec.onnx"

  /** The SHA-256 of that file. Checked before the model is ever handed to OpenCV. */
  val ModelSha256: String = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"

  /** Its exact size. Cheap pre-check that turns a truncated or HTML-error-page download into a clear message
    * instead of a hash mismatch.
    */
  val ModelSizeBytes: Long = 38696353L

  /** Where the model is fetched from, in the order tried.
    *
    * Both are `media.githubusercontent.com`, **not** `github.com/.../raw`: the OpenCV Zoo keeps its `.onnx`
    * files in Git LFS, and the raw host serves the 131-byte LFS *pointer* for them, which downloads with HTTP
    * 200 and then fails to load as a network. (That pointer does at least carry the same `oid sha256:` pinned
    * above, which is where [[ModelSha256]] was cross-checked from.)
    *
    * The first URL pins the commit that last touched the file, so the bytes cannot change under us; the
    * second follows `main` and exists only so a repository reorganisation degrades to a fallback rather than
    * an outage. Both are verified by the checksum regardless, so neither is trusted.
    */
  val ModelUrls: Seq[String] = Seq(
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/" +
      "ba91a3b91d00d76e86540d4013f944bd6b514e39/models/face_recognition_sface/" + ModelFileName,
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/" +
      ModelFileName
  )

  /** The SFace model as a [[ModelSpec]] for the generic [[Models.fetch]] downloader, with its SHA-256 pinned
    * so the fetched bytes are verified before the model is handed to OpenCV.
    */
  val modelSpec: ModelSpec =
    ModelSpec(ModelFileName, ModelUrls, ModelSha256, sizeBytes = Some(ModelSizeBytes))

  /** Loads an SFace recognizer from an ONNX model file. `Left` if the path has no file, the model cannot be
    * read as an SFace network, or the factory returns `null` — the quiet failure mode of the generated
    * bindings, which the shared loader ([[internal.ModelLoader]]) guards for every model type in this module.
    */
  def load(modelPath: String): Either[CvError, FaceRecognizer] =
    internal.ModelLoader
      .loadNative[FaceRecognizerSF](
        modelPath,
        describe = s"FaceRecognizerSF.create('$modelPath')",
        missingDetails = "no such file — supply the SFace ONNX model path"
      )(FaceRecognizerSF.create(modelPath, ""))
      .map(sf => new FaceRecognizer(Managed(sf)))

  /** The 1×15 detection row SFace's `alignCrop` expects: box, five landmarks, score — the YuNet output format
    * reconstructed from a decoded [[Face]]. The layout constants come from [[FaceDetect]] — the one place the
    * YuNet row format is defined — so a format change has exactly one site to update.
    *
    * The write is guarded because it is the one step that can fail after the allocation: the caller only
    * takes ownership of the Mat once this method returns, so a throwing `put` would strand a native buffer
    * nobody ever saw and nobody can free.
    */
  private[scalacv] def faceRow(face: Face): Mat =
    // Face's own constructor guarantees LandmarkCount landmarks, so this array is always ResultColumns long.
    val row: Array[Float] =
      (Seq(face.box.x, face.box.y, face.box.width, face.box.height).map(_.toFloat)
        ++ face.landmarks.flatMap(p => Seq(p.x.toFloat, p.y.toFloat))
        :+ face.score).toArray
    val m = Mat(1, FaceDetect.ResultColumns, CvType.CV_32F)
    try
      m.put(0, 0, row): Unit
      m
    catch
      case e: Throwable =>
        m.release()
        throw e
