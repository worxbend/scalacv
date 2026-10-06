package scalacv.vision

import scala.jdk.CollectionConverters.*

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f, Point3, Size as CvSize, TermCriteria}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** A planar chessboard calibration target, described by its **inner-corner** grid.
  *
  * The number that matters to OpenCV is the count of corners *between* squares, not the number of squares: a
  * board of 10×7 squares has a 9×6 inner grid, so that is what you pass here. `squareSize` is the real edge
  * length of one square, in whatever unit you want the calibration expressed in — millimetres and metres are
  * both common. It only scales the translation part of the recovered geometry; the intrinsics (`fx`, `fy`,
  * `cx`, `cy`) are in pixels regardless.
  *
  * @param columns
  *   inner corners across (squares-across − 1).
  * @param rows
  *   inner corners down (squares-down − 1).
  * @param squareSize
  *   the physical side length of one square.
  */
final case class ChessboardPattern(columns: Int, rows: Int, squareSize: Double = 1.0):
  require(columns >= 2 && rows >= 2, s"a chessboard needs at least a 2×2 inner grid, got $columns×$rows")
  require(squareSize > 0, s"squareSize must be positive, got $squareSize")

  /** Total inner corners the detector should find — `columns × rows`. */
  def corners: Int = columns * rows

  private[scalacv] def patternSize: CvSize = CvSize(columns.toDouble, rows.toDouble)

  /** The inner corners as 3D model points on the `z = 0` plane, in `findChessboardCorners`' row-major order
    * and scaled by [[squareSize]]. Caller-owned; release it.
    */
  private[scalacv] def objectPoints: MatOfPoint3f =
    val pts =
      for
        r <- 0 until rows
        c <- 0 until columns
      yield Point3(c * squareSize, r * squareSize, 0.0)
    MatOfPoint3f(pts*)

/** The result of a camera calibration: the recovered [[Intrinsics]], the image size they were measured at,
  * and the RMS reprojection error (in pixels) the solver achieved.
  *
  * The reprojection error is the headline quality number: it is the root-mean-square distance, over every
  * corner of every view, between where a corner actually sat and where the recovered model says it should be.
  * Under ~1px is a good calibration; several pixels means blurry captures, a bad board, or too few angles.
  *
  * The `intrinsics` are the same [[Intrinsics]] that [[Ar]], [[HeadPose]] and [[Localizer]] take — so a real
  * calibration drops straight into the pose stack in place of [[Intrinsics.approx]]'s field-of-view guess,
  * and an [[Image]] can be undistorted with [[Image.undistort]].
  */
final case class Calibration(intrinsics: Intrinsics, imageSize: Size, reprojectionError: Double):
  require(reprojectionError >= 0, s"reprojectionError cannot be negative, got $reprojectionError")

/** Camera calibration from chessboard captures — the foundation the metric parts of the vision stack were
  * built to sit on.
  *
  * Everything that turns pixels into geometry ([[VisualOdometry]], [[Localizer]], [[Ar]], [[HeadPose]],
  * [[StereoDepth]]) needs to know the camera's focal length, optical centre and lens distortion. Without a
  * calibration they run on [[Intrinsics.approx]]'s "focal ≈ image width, no distortion" guess — enough to
  * *see* an overlay track, not enough to *measure*. This turns the guess into measured numbers: show the
  * camera a chessboard from a handful of angles, and [[fromChessboard]] recovers the [[Intrinsics]].
  *
  * {{{
  * val board = ChessboardPattern(columns = 9, rows = 6, squareSize = 0.025) // 25 mm squares
  * val views = (1 to 15).flatMap(i => Image.read(s"calib/$i.jpg").toOption)
  * Calibration.fromChessboard(views, board) match
  *   case Right(calib) =>
  *     println(f"calibrated to ${calib.reprojectionError}%.3f px RMS")
  *     val straight = frame.undistort(calib)         // lens distortion removed
  *     Ar.estimatePose(marker, 0.05, calib.intrinsics) // metric marker pose
  *   case Left(err) => System.err.println(err.getMessage)
  * }}}
  */
object Calibration:

  /** `findChessboardCorners` flags: adaptive thresholding copes with uneven lighting, and normalising the
    * image gamma before the search makes it robust to exposure. The pair is OpenCV's own recommended default.
    */
  private val DetectFlags = Calib3d.CALIB_CB_ADAPTIVE_THRESH | Calib3d.CALIB_CB_NORMALIZE_IMAGE

  /** Sub-pixel refinement stops at 30 iterations or a 1e-3 px move, whichever comes first. */
  private val RefineCriteria = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 1e-3)

  /** Finds and sub-pixel-refines the inner-corner grid of `pattern` in `image`.
    *
    * `None` if the *whole* board is not visible — the detector is all-or-nothing, so a partially occluded or
    * cropped board finds nothing rather than a subset. The returned points are in image pixels, row-major
    * from the board's origin corner, and are plain immutable data safe to keep after `image` is freed.
    *
    * This returns `Option`, not `Either`, on purpose: "the board is not in this frame" is an ordinary query
    * result, not a failure — the same reason [[Tracker.update]] and the detectors return `Option`/`Seq`. It
    * is [[fromChessboard]], where too few boards or a non-converging solver *is* a failure, that returns
    * `Either[`[[CvError.CalibrationFailed]]`, _]`.
    */
  def findCorners(image: Image, pattern: ChessboardPattern): Option[Seq[Point]] =
    Mats
      .grayscale(image.mat)
      .use: g =>
        detect(g, pattern).map: corners =>
          try corners.toArray.map(Point.from).toSeq
          finally corners.release()

  /** Calibrates a pinhole camera from several `views` of the same chessboard `pattern`. The views are
    * **borrowed** (read only), not consumed — closing them stays the caller's job.
    *
    * Each view is searched for the board ([[findCorners]]); views where it is not fully visible are silently
    * skipped, so you can pass a whole capture folder and let the blurred or badly-angled frames drop out.
    * With at least `minViews` good views spanning a range of angles, `calibrateCamera` recovers the
    * intrinsics and lens distortion.
    *
    * `Left(`[[CvError.CalibrationFailed]]`)` if fewer than `minViews` views showed the whole board, or the
    * solver itself fails. The image size is taken from the first view; all views should share it.
    *
    * @param minViews
    *   how many good views to require. Three is the practical floor; ten-plus from varied angles is where the
    *   numbers get trustworthy.
    */
  def fromChessboard(
      views: Seq[Image],
      pattern: ChessboardPattern,
      minViews: Int = 3
  ): Either[CvError, Calibration] =
    require(minViews >= 1, s"minViews must be positive, got $minViews")
    if views.isEmpty then Left(CvError.CalibrationFailed("no views were provided"))
    else
      val imageSize = views.head.size
      val imagePoints = scala.collection.mutable.ArrayBuffer.empty[MatOfPoint2f]
      try
        for view <- views do Mats.grayscale(view.mat).use(g => detect(g, pattern).foreach(imagePoints += _))
        if imagePoints.size < minViews then
          Left(
            CvError.CalibrationFailed(
              s"need at least $minViews views showing the whole ${pattern.columns}×${pattern.rows} inner " +
                s"grid, but only ${imagePoints.size} of ${views.size} did"
            )
          )
        else calibrate(imagePoints.toSeq, pattern, imageSize)
      finally imagePoints.foreach(_.release())

  /** Runs `calibrateCamera` over the collected correspondences and reads the result back into a
    * [[Calibration]].
    */
  private def calibrate(
      imagePoints: Seq[MatOfPoint2f],
      pattern: ChessboardPattern,
      imageSize: Size
  ): Either[CvError, Calibration] =
    // Every view sees the same rigid board, so one object-point Mat is shared across all views (OpenCV
    // accepts the same Mat repeated) — and freed exactly once in the `finally`.
    val obj = pattern.objectPoints
    val objectPoints = Seq.fill(imagePoints.size)(obj)
    val cameraMatrix = Mat()
    val distCoeffs = Mat()
    val rvecs = java.util.ArrayList[Mat]()
    val tvecs = java.util.ArrayList[Mat]()
    try
      Cv.attempt("calibrateCamera"):
        val rms = Calib3d.calibrateCamera(
          objectPoints.map(m => m: Mat).asJava,
          imagePoints.map(m => m: Mat).asJava,
          imageSize.toCv,
          cameraMatrix,
          distCoeffs,
          rvecs,
          tvecs
        )
        Calibration(readIntrinsics(cameraMatrix, distCoeffs), imageSize, rms)
    finally
      obj.release() // the single shared object-point Mat, freed once regardless of view count
      cameraMatrix.release()
      distCoeffs.release()
      rvecs.asScala.foreach(_.release())
      tvecs.asScala.foreach(_.release())

  /** Reads the 3×3 camera matrix and the `CV_64F` distortion row into an [[Intrinsics]]. */
  private def readIntrinsics(camera: Mat, dist: Mat): Intrinsics =
    val n = dist.total().toInt
    val d = Array.ofDim[Double](n)
    // reads the coeffs into `d`; the pixel count `get` returns is unused
    if n > 0 then
      val _ = dist.get(0, 0, d)
    Intrinsics(
      fx = camera.get(0, 0)(0),
      fy = camera.get(1, 1)(0),
      cx = camera.get(0, 2)(0),
      cy = camera.get(1, 2)(0),
      distortion = d.toSeq
    )

  /** Detects and refines the board in an already-grayscale Mat, returning the live corners Mat (caller frees)
    * or `None`. Kept separate from [[findCorners]] because [[fromChessboard]] needs the Mat, not a copy.
    */
  private def detect(gray: Mat, pattern: ChessboardPattern): Option[MatOfPoint2f] =
    val corners = MatOfPoint2f()
    // A view that makes OpenCV throw (a degenerate or oversized frame) is a view that yielded no usable
    // corners, not a reason to abort the whole multi-view calibration — fold the throw into `None`, the same
    // outcome as "no board found", and let `fromChessboard` decide if too few views survived.
    val found = Cv
      .attempt("findChessboardCorners")(
        Calib3d.findChessboardCorners(gray, pattern.patternSize, corners, DetectFlags)
      )
      .getOrElse(false)
    if !found then
      corners.release()
      None
    else
      Cv.attempt("cornerSubPix")(
        Imgproc.cornerSubPix(gray, corners, CvSize(11, 11), CvSize(-1, -1), RefineCriteria)
      ) match
        case Right(_) => Some(corners)
        case Left(_) =>
          corners.release()
          None

/** Undistort an [[Image]] straight from a [[Calibration]] — unwraps its [[Intrinsics]] and delegates to
  * `Image.undistort(intrinsics)`. An extension method (in the vision layer) rather than a member of the core
  * `Image`, so the core need not depend on this camera-calibration type. `import scalacv.*` gives
  * `frame.undistort(calib)`.
  */
extension (img: Image) def undistort(calibration: Calibration): Image = img.undistort(calibration.intrinsics)
