package scalacv.vision

import java.io.{File, IOException}

import org.bytedeco.javacpp.Loader
import org.opencv.core.{Mat, MatOfRect}
import org.opencv.objdetect.CascadeClassifier

import scalacv.*

/** One of the Haar cascades shipped inside the bytedeco OpenCV classifier jar.
  *
  * Typed rather than a raw filename because the failure mode of a typo is silent: `CascadeClassifier` does
  * not throw for a path it cannot read, it constructs an *empty* classifier that then detects nothing,
  * forever. A name that cannot be misspelled removes the most common way to reach that state.
  *
  * The `lbpcascades` and the two `frontalcatface` files are deliberately omitted: LBP is a different model
  * family with different tuning, and the cat detectors are a novelty. Both are still reachable through
  * [[Cascades.loadFrom]].
  */
enum CascadeName(val fileName: String):

  /** The usual first choice for faces: markedly fewer false positives than [[FrontalFaceDefault]]. */
  case FrontalFaceAlt extends CascadeName("haarcascade_frontalface_alt.xml")
  case FrontalFaceAlt2 extends CascadeName("haarcascade_frontalface_alt2.xml")

  /** The original Viola-Jones cascade. Fast and permissive — expect false positives. */
  case FrontalFaceDefault extends CascadeName("haarcascade_frontalface_default.xml")
  case ProfileFace extends CascadeName("haarcascade_profileface.xml")

  case Eye extends CascadeName("haarcascade_eye.xml")

  /** Eyes, trained to survive spectacles. Slower than [[Eye]]. */
  case EyeTreeEyeglasses extends CascadeName("haarcascade_eye_tree_eyeglasses.xml")
  case LeftEye2Splits extends CascadeName("haarcascade_lefteye_2splits.xml")
  case RightEye2Splits extends CascadeName("haarcascade_righteye_2splits.xml")
  case Smile extends CascadeName("haarcascade_smile.xml")

  case FullBody extends CascadeName("haarcascade_fullbody.xml")
  case UpperBody extends CascadeName("haarcascade_upperbody.xml")
  case LowerBody extends CascadeName("haarcascade_lowerbody.xml")

  /** Russian licence plates. The only non-anatomical cascade upstream ships. */
  case RussianPlateNumber extends CascadeName("haarcascade_russian_plate_number.xml")

/** Haar cascade resolution and loading.
  *
  * Two things here are load-bearing, and both are about failing loudly.
  *
  *   1. **`CascadeClassifier` never reports a bad path.** `new CascadeClassifier("/nope.xml")` succeeds,
  *      prints nothing useful, and hands back an object whose `empty()` is `true`. Every subsequent
  *      `detectMultiScale` then returns zero rectangles, which is indistinguishable from "there was nothing
  *      in the frame". [[Cascades.load]] and [[loadFrom]] check `empty()` and return a `Left` instead.
  *   1. **The cascades are a classpath resource, not a file on disk.** They live in the per-platform
  *      classifier jar under `share/opencv4/haarcascades/`, so they have to be extracted before OpenCV — a
  *      C++ library that only knows filesystem paths — can read them. `Loader.cacheResource` does that and
  *      caches it, and notably needs **no native load**: [[resolve]] works before `OpenCv.load()`.
  *
  * `windows-x86_64` is the exception that has to be handled by name: its jar ships an empty `share/`
  * directory and no cascades at all, so [[resolve]] can only fail there. It says so in those words rather
  * than surfacing a null or an opaque IO error.
  */
object Cascades:

  /** `CascadeClassifier` is one of the 185 generated types with no public `release()`, so it needs the
    * `delete(long)` bridge. Exposed as a `given` so callers who build their own classifiers can
    * `import Cascades.given` and put them in a [[Managed]] on the same terms.
    */
  given Releasable[CascadeClassifier] = Releasable.nativeHandle

  private val ResourceDir = "share/opencv4/haarcascades"

  /** Extracts a cascade from the classifier jar and returns the file on disk.
    *
    * Needs no native library: this is pure resource extraction, so it can be called before `OpenCv.load()`.
    * The extracted file is cached by javacpp, so repeated calls are cheap and return the same path.
    */
  def resolve(name: CascadeName): Either[CvError, File] =
    val platform =
      try Loader.getPlatform
      catch case _: Throwable => "<unknown>"
    val resource = s"/org/bytedeco/opencv/$platform/$ResourceDir/${name.fileName}"
    try
      // cacheResource returns null — rather than throwing — when the resource is not on the classpath,
      // which is exactly the Windows case and also what a missing classifier jar looks like.
      Option(Loader.cacheResource(classOf[org.bytedeco.opencv.opencv_java], resource)) match
        case Some(f) if f.isFile && f.canRead => Right(f)
        case Some(f) => Left(CvError.LoadFailed(f.getPath, "extracted, but not a readable file"))
        case None => Left(CvError.LoadFailed(resource, unavailable(platform)))
    catch
      case e: IOException => Left(CvError.LoadFailed(resource, s"could not be extracted: ${e.getMessage}"))
      case e: NoClassDefFoundError =>
        Left(
          CvError.LoadFailed(resource, s"the bytedeco OpenCV jar is not on the classpath (${e.getMessage})")
        )

  /** Extracts a cascade and loads it, failing if the result is an empty classifier.
    *
    * The returned classifier is **caller-owned**: release it, or take it with [[Managed.use]].
    */
  def load(name: CascadeName): Either[CvError, Managed[CascadeClassifier]] =
    resolve(name).flatMap(f => loadFrom(f.getAbsolutePath))

  /** Loads a cascade from a filesystem path — your own trained XML, or one of OpenCV's that [[CascadeName]]
    * does not name.
    *
    * A `Left` here means the file is missing, unreadable, or not a cascade OpenCV understands. The
    * distinction is not always available: for a missing file OpenCV constructs an empty classifier silently,
    * while for a malformed XML it throws `CvException`. Both end up as a `Left`.
    *
    * The returned classifier is **caller-owned**.
    */
  def loadFrom(path: String): Either[CvError, Managed[CascadeClassifier]] =
    internal.ModelLoader
      .loadNative[CascadeClassifier](
        path,
        describe = s"loading a cascade classifier from '$path'",
        missingDetails =
          "there is no readable file at this path. CascadeClassifier does not report that as an error — " +
            "it would construct an empty classifier that detects nothing — so scalacv checks the path first.",
        validate = classifier =>
          Option.when(classifier.empty())(
            "OpenCV loaded no cascade from this path. It does not report that as an error — it returns " +
              "an empty classifier that detects nothing — so scalacv reports it here instead. Check that " +
              "the file exists, is readable, and is a Haar or LBP cascade XML."
          )
      )(CascadeClassifier(path))
      .map(Managed(_))

  private def unavailable(platform: String): String =
    if platform.startsWith("windows") then
      s"""the bytedeco OpenCV jar for $platform ships no Haar cascades at all — its
         |share/ directory is empty, unlike every other platform's. Ship the cascade XML with your own
         |application and use Cascades.loadFrom(path), or use a detector that does not need one.""".stripMargin
    else s"""not found in the OpenCV classifier jar for $platform. The cascades ship in the
         |per-platform classifier artifact, so this usually means only the classifier-less
         |org.bytedeco:opencv jar is on the classpath. Add:
         |
         |  "org.bytedeco" % "opencv" % "${Build.openCvArtifactVersion}" classifier "$platform"""".stripMargin

/** Object detection on a Mat.
  *
  * Kept as an extension rather than a method on a wrapper so a classifier obtained from [[Cascades]] reads
  * the same way as one a caller built themselves.
  */
extension (mat: Mat)

  /** Runs a cascade over this image and returns the detections as immutable Scala values.
    *
    * The receiver is neither modified nor released. The `MatOfRect` OpenCV fills in is internal and is
    * released before returning, which is why the result is `Seq[Rect]` and not a live Mat — the rectangles
    * are copied out at the native boundary and stay valid after everything here is freed.
    *
    * Best results come from a single-channel, histogram-equalised image; a colour Mat works but is slower. An
    * empty Mat makes OpenCV throw, and that throw is deliberately not caught: it is a programmer error, not a
    * data-dependent failure.
    *
    * @param scaleFactor
    *   how much the detection window grows per pyramid level. Just above 1 is slower and finds more.
    * @param minNeighbors
    *   how many overlapping detections a candidate needs to survive. Higher is stricter.
    * @param minSize
    *   objects smaller than this are ignored. Setting it is the cheapest speed-up available.
    */
  def detect(
      classifier: CascadeClassifier,
      scaleFactor: Double = 1.1,
      minNeighbors: Int = 3,
      minSize: Option[Size] = None
  ): Seq[Rect] =
    require(scaleFactor > 1.0, s"scaleFactor must be greater than 1, was $scaleFactor")
    require(minNeighbors >= 0, s"minNeighbors cannot be negative, was $minNeighbors")
    Managed.use(MatOfRect()): out =>
      minSize match
        // The 3rd int is OpenCV's legacy CV_HAAR_* flag word, ignored by every non-old-format cascade; 0
        // is the only value the modern implementation honours.
        case Some(s) => classifier.detectMultiScale(mat, out, scaleFactor, minNeighbors, 0, s.toCv)
        case None => classifier.detectMultiScale(mat, out, scaleFactor, minNeighbors)
      out.toArray.map(Rect.from).toSeq

/** The high-level Haar-detection verb on [[Image]] — an extension method so it sits beside [[Cascades]] and
  * the mid-level `Mat.detect` above. `import scalacv.*` gives `image.detectHaar(classifier)`.
  */
extension (img: Image)

  /** Rectangles via a Haar `CascadeClassifier` you supply (see [[Cascades]]). Borrowed, not released.
    *
    * Takes the raw classifier, not the [[Managed]] [[Cascades.load]] returns — a defaulted overload for the
    * `Managed` is not expressible alongside this one — so keep the classifier inside its scope to keep the
    * spent-handle guard: `Cascades.load(name).map(_.use(c => image.detectHaar(c)))`. (The `FaceDetectorYN`
    * detector, `faces`, does take a `Managed` directly, since it has no defaults to collide.)
    */
  def detectHaar(
      classifier: CascadeClassifier,
      scaleFactor: Double = 1.1,
      minNeighbors: Int = 3,
      minSize: Option[Size] = None
  ): Seq[Rect] = img.mat.detect(classifier, scaleFactor, minNeighbors, minSize)
