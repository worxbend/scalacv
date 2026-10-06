package scalacv

import org.opencv.core.{CvType, Mat}

/** The Mat preconditions the mid-level ops share, in one place.
  *
  * Three call sites had each grown their own spelling of the same checks — the draw primitives in
  * `Draw.scala`, the Hough transforms in `Hough.scala`, and `findContours` in `Contours.scala` — and the
  * spellings had drifted: the draw side said "needs an image with data" while the contour side said "needs a
  * non-empty image" for what is the identical defect. Drift in precondition messages is not a cosmetic issue:
  * these are the errors a caller greps for, so one defect gets one message.
  *
  * The checks stay as `require` — a precondition failure is a programmer error under the error policy in
  * [[Cv]], throwing [[IllegalArgumentException]] rather than yielding a [[CvError]]. OpenCV's own checks for
  * these live in native code and abort with a `CvException` quoting a C++ expression that names neither the
  * call nor the real reason; failing here names the operation first and reports the actual type via
  * [[CvType.typeToString]], the same style the call sites used before the convergence.
  */
private[scalacv] object Preconditions:

  /** `op` needs a Mat with data behind it.
    *
    * Drawing into or searching a Mat with no allocated buffer throws from native code with a message that
    * names neither the call nor the reason, so the emptiness check is made here where the operation's name is
    * still known.
    */
  def requireNonEmpty(op: String, mat: Mat): Unit =
    require(!mat.empty(), s"$op needs a non-empty image; this Mat has no data")

  /** `op` needs a binary image: single-channel 8-bit, `CV_8UC1`.
    *
    * This is the input contract of anything that consumes an edge map or a threshold result — the Hough
    * transforms assert `CV_8UC1` in native code, and `findContours` is meant for the same shape of image.
    * Checking it here turns a JNI-side abort into an ordinary precondition failure that names the offending
    * type, per the error policy in [[Cv]].
    */
  def requireGray8(op: String, mat: Mat): Unit =
    require(
      !mat.empty() && mat.`type`() == CvType.CV_8UC1,
      s"$op needs a non-empty 8-bit single-channel image (typically the output of Canny), " +
        s"but got ${mat.rows()}x${mat.cols()} of type ${CvType.typeToString(mat.`type`())}"
    )
