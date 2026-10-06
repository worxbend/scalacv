package scalacv

/** Everything scalacv can fail with.
  *
  * Modelled as an exception hierarchy rather than a pure ADT because it has to interoperate with a JNI
  * boundary that throws: `org.opencv.core.CvException` escapes from ordinary `Imgproc` calls, and no wrapper
  * can make the core total. The API returns `Either[CvError, A]` where failure is *data-dependent* and
  * expected — a missing file, an undecodable image — and throws for programmer errors.
  */
sealed abstract class CvError(message: String, cause: Throwable | Null)
    extends RuntimeException(message, cause)

object CvError:

  /** The native libraries could not be loaded. Carries the exact dependency lines to add. When a reflective
    * access failed, `cause` preserves the underlying exception — its message names the module/package that
    * actually could not be opened.
    */
  final case class NativesMissing(details: String, cause: Throwable | Null = null)
      extends CvError(details, cause)

  /** An image could not be read or decoded. `imread` does not throw for this — it returns an empty Mat — so
    * the check has to be explicit.
    */
  final case class DecodeFailed(path: String, details: String)
      extends CvError(s"could not decode an image from '$path': $details", null)

  /** A named resource could not be resolved, loaded, or verified — a model file, a Haar cascade, an ONNX
    * network, a downloaded artifact, a video source. Distinct from [[DecodeFailed]], which is specifically
    * about image *bytes*: an HTTP 404 for a model download, a missing `.onnx`, or a checksum mismatch is not
    * an image-decode failure and should not read like one.
    */
  final case class LoadFailed(resource: String, details: String)
      extends CvError(s"could not load '$resource': $details", null)

  /** An image could not be written. */
  final case class EncodeFailed(path: String, details: String)
      extends CvError(s"could not write an image to '$path': $details", null)

  /** A video source yielded no frame where one was expected — a file's last frame read, a camera that went
    * away, a device that never delivered. Distinct from [[LoadFailed]], which is about *opening or resolving*
    * a resource: the source here opened fine and has simply run dry, which for a finite file is the ordinary,
    * expected end rather than a failure at all.
    */
  final case class EndOfStream(source: String, details: String)
      extends CvError(s"no frame available from '$source': $details", null)

  /** A camera calibration could not be produced. Either too few views showed the whole calibration target for
    * the solver to be well-posed, or `calibrateCamera` did not converge. This is *data-dependent* — it turns
    * on how many boards the capture actually saw, not on a programmer error — so it is returned rather than
    * thrown. See `scalacv.vision.Calibration.fromChessboard`.
    */
  final case class CalibrationFailed(details: String)
      extends CvError(s"camera calibration failed: $details", null)

  /** A native call failed. Wraps `org.opencv.core.CvException`, whose message is the only information OpenCV
    * gives us — deliberately not parsed for error codes.
    */
  final case class NativeCall(operation: String, cause: Throwable)
      extends CvError(s"OpenCV failed during $operation: ${cause.getMessage}", cause)
