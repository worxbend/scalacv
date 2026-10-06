package scalacv.vision

import scala.jdk.CollectionConverters.*

import org.opencv.core.{CvType, Mat}
import org.opencv.objdetect.{ArucoDetector, Dictionary, Objdetect, QRCodeDetector}

import scalacv.*

// Fiducial and barcode detection: QR codes ([[Qr]]) and ArUco markers ([[Aruco]]). The shared ownership
// model — detectors built, used and freed inside a single call, results crossing the boundary as plain data
// — is documented on [[Qr]], the canonical case, and referenced from [[Aruco]].

/** One decoded QR code.
  *
  * `text` is empty when OpenCV located a symbol but could not decode it — a common outcome for a blurred or
  * partially occluded code, and deliberately not filtered out, because the corners are still useful (to draw
  * an overlay, or to re-crop and retry).
  */
final case class QrCode(text: String, corners: Seq[Point])

/** QR code detection over `org.opencv.objdetect.QRCodeDetector`.
  *
  * Both this and [[Aruco]] report their geometry through `Mat`s that the caller would otherwise own, and
  * neither `QRCodeDetector` nor `ArucoDetector` has a public `release()` — they are two of the 185 types that
  * only expose a private `delete(long)`. So each detector is created, used and freed inside a single call
  * here, and the results cross the boundary as ordinary immutable Scala data ([[Point]] case classes) rather
  * than as live native handles. That is what makes these signatures return `Seq` instead of `Managed[…]`:
  * there is nothing left to own.
  */
object Qr:

  private given Releasable[QRCodeDetector] = Releasable.nativeHandle

  /** Finds and decodes every QR code in `mat`.
    *
    * Uses the *multi* variant unconditionally. The single-code `detectAndDecode` is not a simpler special
    * case of it — it returns only the first symbol it happens to find and gives no way to learn that there
    * were others, so exposing it as well would just be a way to silently drop data.
    *
    * The image is only read from; the caller keeps ownership of it.
    *
    * @return
    *   one entry per located symbol, in OpenCV's order. Empty when there is nothing to find — including for a
    *   blank image, which is not an error.
    * @throws IllegalArgumentException
    *   if `mat` is empty, which is a programmer error rather than a detection result (see [[Cv]]).
    */
  def detectAndDecode(mat: Mat): Seq[QrCode] =
    require(!mat.empty(), "Qr.detectAndDecode needs a non-empty image")
    Managed(QRCodeDetector()).use: detector =>
      Managed(Mat()).use: points =>
        val texts = java.util.ArrayList[String]()
        val decoded = Cv.orThrow("QRCodeDetector.detectAndDecodeMulti"):
          detector.detectAndDecodeMulti(mat, texts, points)
        if !decoded then Seq.empty
        else
          val quads = DetectorQuads.read(points)
          texts.asScala.iterator.zipWithIndex
            .map: (text, i) =>
              QrCode(text, if i < quads.size then quads(i) else Seq.empty)
            .toSeq

/** The predefined ArUco dictionaries.
  *
  * The name encodes the tag's bit grid and the dictionary size — `Dict5x5_250` is 250 distinct 5x5 markers.
  * Fewer markers in a dictionary means a larger Hamming distance between them and therefore more robust
  * detection, so pick the smallest one that has enough ids for the job rather than the largest.
  *
  * `Objdetect.DICT_*` are the underlying constants; the AprilTag families are addressable here too because
  * OpenCV's ArUco detector reads them through the same path.
  */
enum ArucoDictionary(val cvValue: Int):
  case Dict4x4_50 extends ArucoDictionary(Objdetect.DICT_4X4_50)
  case Dict4x4_100 extends ArucoDictionary(Objdetect.DICT_4X4_100)
  case Dict4x4_250 extends ArucoDictionary(Objdetect.DICT_4X4_250)
  case Dict4x4_1000 extends ArucoDictionary(Objdetect.DICT_4X4_1000)
  case Dict5x5_50 extends ArucoDictionary(Objdetect.DICT_5X5_50)
  case Dict5x5_100 extends ArucoDictionary(Objdetect.DICT_5X5_100)
  case Dict5x5_250 extends ArucoDictionary(Objdetect.DICT_5X5_250)
  case Dict5x5_1000 extends ArucoDictionary(Objdetect.DICT_5X5_1000)
  case Dict6x6_50 extends ArucoDictionary(Objdetect.DICT_6X6_50)
  case Dict6x6_100 extends ArucoDictionary(Objdetect.DICT_6X6_100)
  case Dict6x6_250 extends ArucoDictionary(Objdetect.DICT_6X6_250)
  case Dict6x6_1000 extends ArucoDictionary(Objdetect.DICT_6X6_1000)
  case Dict7x7_50 extends ArucoDictionary(Objdetect.DICT_7X7_50)
  case Dict7x7_100 extends ArucoDictionary(Objdetect.DICT_7X7_100)
  case Dict7x7_250 extends ArucoDictionary(Objdetect.DICT_7X7_250)
  case Dict7x7_1000 extends ArucoDictionary(Objdetect.DICT_7X7_1000)
  case ArucoOriginal extends ArucoDictionary(Objdetect.DICT_ARUCO_ORIGINAL)
  case AprilTag16h5 extends ArucoDictionary(Objdetect.DICT_APRILTAG_16h5)
  case AprilTag25h9 extends ArucoDictionary(Objdetect.DICT_APRILTAG_25h9)
  case AprilTag36h10 extends ArucoDictionary(Objdetect.DICT_APRILTAG_36h10)
  case AprilTag36h11 extends ArucoDictionary(Objdetect.DICT_APRILTAG_36h11)
  case ArucoMip36h12 extends ArucoDictionary(Objdetect.DICT_ARUCO_MIP_36h12)

/** One detected ArUco marker: its dictionary id and its four corners, clockwise from the top left in the
  * marker's own frame.
  */
final case class ArucoMarker(id: Int, corners: Seq[Point])

/** ArUco marker detection and generation over `org.opencv.objdetect.ArucoDetector`. Follows the same
  * build-use-free ownership model documented on [[Qr]] — detectors are never handed to the caller.
  */
object Aruco:

  private given Releasable[ArucoDetector] = Releasable.nativeHandle
  private given Releasable[Dictionary] = Releasable.nativeHandle

  /** Finds every marker from `dictionary` in `mat`.
    *
    * A fresh detector is built per call. That is not an oversight: `ArucoDetector` copies the dictionary into
    * its implementation at construction, so a shared instance would have to be either immutable-by-convention
    * or synchronised, and construction is cheap next to the detection itself. Rejected markers are discarded
    * — they are a tuning aid, not a result.
    *
    * @return
    *   one entry per accepted marker, in OpenCV's order. Empty when there is nothing to find.
    * @throws IllegalArgumentException
    *   if `mat` is empty.
    */
  def detect(mat: Mat, dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50): Seq[ArucoMarker] =
    require(!mat.empty(), "Aruco.detect needs a non-empty image")
    Managed(Objdetect.getPredefinedDictionary(dictionary.cvValue)).use: dict =>
      Managed(ArucoDetector(dict)).use: detector =>
        Managed(Mat()).use: ids =>
          // detectMarkers fills this with one freshly allocated 1x4 CV_32FC2 Mat per marker, all of
          // them ours to free.
          val corners = java.util.ArrayList[Mat]()
          try
            Cv.orThrow("ArucoDetector.detectMarkers")(detector.detectMarkers(mat, corners, ids))
            val quads = corners.asScala.toSeq
            (0 until ids.rows).iterator
              .map: i =>
                val corner = if i < quads.size then DetectorQuads.first(quads(i)) else Seq.empty
                ArucoMarker(ids.get(i, 0)(0).toInt, corner)
              .toSeq
          finally corners.asScala.foreach(_.release())

  /** Renders marker `id` from `dictionary` as a `sizePixels` x `sizePixels` 8-bit single-channel image.
    *
    * The result is **caller-owned** — hence `Managed`. It carries the marker's own black border but no quiet
    * zone, exactly as OpenCV produces it; [[detect]] will not find it until you add a white margin around it
    * (`Core.copyMakeBorder`), because the detector locates candidates by looking for a dark quad on a light
    * background.
    *
    * @throws IllegalArgumentException
    *   if `id` is negative or `sizePixels` is not positive.
    * @throws CvError.NativeCall
    *   if `id` is beyond the dictionary's size, or `sizePixels` is too small for the marker's bit grid.
    */
  def generateMarker(dictionary: ArucoDictionary, id: Int, sizePixels: Int): Managed[Mat] =
    require(id >= 0, s"an ArUco marker id cannot be negative: $id")
    require(sizePixels > 0, s"an ArUco marker must have a positive size: $sizePixels")
    // Mats.produce is the one place a destination Mat is allocated and released-on-throw; the dictionary's
    // scope can close before the Mat escapes because the result does not alias it.
    Managed(Objdetect.getPredefinedDictionary(dictionary.cvValue)).use: dict =>
      Mats.produce("Objdetect.generateImageMarker"): out =>
        Objdetect.generateImageMarker(dict, id, sizePixels, out)

/** Reads OpenCV's corner Mats into plain points.
  *
  * Both detectors describe a quadrilateral as four 2-channel floats, but they disagree on the layout:
  * `detectAndDecodeMulti` returns one Nx4 `CV_32FC2` Mat for all codes, while `detectMarkers` returns a list
  * of 1x4 Mats. Reading every element in row-major order and regrouping by four covers both, and also the
  * 4Nx1 shape some OpenCV builds hand back — the alternative is a shape assertion that fails at runtime, in
  * native code, on a version bump.
  */
private object DetectorQuads:

  /** Every group of four consecutive points in `m`, dropping a trailing partial group. */
  def read(m: Mat): Seq[Seq[Point]] =
    if m.empty() then Seq.empty
    else
      // One bulk read of the whole buffer instead of a `Mat.get(row, col)` JNI round-trip per element
      // (rows × cols of them). The array type must match the Mat's depth — `get` refuses a mismatched
      // buffer — so the depth is checked first, exactly as the other bulk readers do; anything that is
      // not float or double is widened through the per-element path it would have taken anyway.
      val n = (m.total() * m.channels()).toInt
      val scalars: IndexedSeq[Double] =
        if m.depth() == CvType.CV_32F then
          val buf = Array.ofDim[Float](n)
          m.get(0, 0, buf)
          buf.toIndexedSeq.map(_.toDouble)
        else if m.depth() == CvType.CV_64F then
          val buf = Array.ofDim[Double](n)
          m.get(0, 0, buf)
          buf.toIndexedSeq
        else (for r <- 0 until m.rows; c <- 0 until m.cols yield m.get(r, c).toSeq).flatten
      scalars
        .grouped(2)
        .map(v => Point(v(0), v(1)))
        .toSeq
        .grouped(4)
        .filter(_.sizeIs == 4)
        .toSeq

  /** The first quad in `m`, or empty. */
  def first(m: Mat): Seq[Point] = read(m).headOption.getOrElse(Seq.empty)

/** The high-level [[Image]] entry points for fiducial and barcode detection. Extension methods rather than
  * members of [[Image]] so that class stays about the image, not about every detector; `import scalacv.*`
  * brings them in and `image.qrCodes` reads the same as a method would.
  */
extension (img: Image)

  /** Every QR code in the image, decoded. Self-contained — builds and frees its own detector. */
  def qrCodes: Seq[QrCode] = Qr.detectAndDecode(img.mat)

  /** Every ArUco marker from `dictionary`. Self-contained — builds and frees its own detector. */
  def arucoMarkers(dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50): Seq[ArucoMarker] =
    Aruco.detect(img.mat, dictionary)
