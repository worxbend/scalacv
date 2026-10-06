package scalacv

import scalacv.vision.*

import org.opencv.calib3d.Calib3d
import org.opencv.core as cv
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/** Camera calibration from chessboard captures.
  *
  * The load-bearing test renders views of a board through a **known** pinhole camera and checks that
  * `fromChessboard` recovers it. A planar target seen by a real camera induces a homography per view (`H =
  * K·[r₁ r₂ t]`), so warping one flat board by those homographies produces physically consistent views —
  * exactly the input Zhang's method calibrates from — and the recovered `fx/fy/cx/cy` can be compared against
  * the ground truth that generated them. No camera, no fixture files: the pixels are synthesised.
  */
class CalibrationTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  // -- ground-truth camera & board -----------------------------------------------------------------

  private val fx = 800.0
  private val fy = 800.0
  private val imgW = 640
  private val imgH = 480
  private val cx = imgW / 2.0 // 320
  private val cy = imgH / 2.0 // 240

  private val board = ChessboardPattern(columns = 9, rows = 6) // 10×7 squares
  private val squarePx = 60 // pixels per square in the flat rendering
  private val marginPx = 60

  // -- a flat, front-on chessboard -----------------------------------------------------------------

  /** A crisp black-and-white chessboard with a white quiet zone, drawn directly into a Mat. */
  private def flatBoard(): Image =
    val squaresX = board.columns + 1
    val squaresY = board.rows + 1
    val w = squaresX * squarePx + 2 * marginPx
    val h = squaresY * squarePx + 2 * marginPx
    val m = Mat(h, w, CvType.CV_8UC3, cv.Scalar(255, 255, 255))
    for
      r <- 0 until squaresY
      c <- 0 until squaresX
      if (r + c) % 2 == 0
    do
      val roi = m.submat(
        marginPx + r * squarePx,
        marginPx + (r + 1) * squarePx,
        marginPx + c * squarePx,
        marginPx + (c + 1) * squarePx
      )
      roi.setTo(cv.Scalar(0, 0, 0))
      roi.release()
    Image.wrap(Managed(m))

  /** Maps board-square coordinates `(X, Y)` to flat-image pixels: `p = margin + (X+1)·squarePx`. The `+1`
    * accounts for the flat board's first square, so the first inner corner lands at `margin + squarePx`.
    */
  private def flatToWorld: Mat =
    // p_flat = A · [X, Y, 1];  A = [[S, 0, m+S], [0, S, m+S], [0, 0, 1]]
    val a = Mat(3, 3, CvType.CV_64F, cv.Scalar(0))
    a.put(
      0,
      0,
      squarePx.toDouble,
      0.0,
      (marginPx + squarePx).toDouble,
      0.0,
      squarePx.toDouble,
      (marginPx + squarePx).toDouble,
      0.0,
      0.0,
      1.0
    )
    a

  private def kMatrix: Mat =
    val k = Mat(3, 3, CvType.CV_64F, cv.Scalar(0))
    k.put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
    k

  private def matMul(a: Mat, b: Mat): Mat =
    val dst = Mat()
    Core.gemm(a, b, 1.0, Mat(), 0.0, dst)
    dst

  /** Renders the flat board as this camera would see it from orientation `(rxDeg, ryDeg)`, placed so the
    * whole board fits the frame. Returns a `640×480` view.
    */
  private def renderView(flat: Image, rxDeg: Double, ryDeg: Double): Image =
    val rvec = Mat(3, 1, CvType.CV_64F)
    rvec.put(0, 0, math.toRadians(rxDeg), math.toRadians(ryDeg), 0.0)
    val rot = Mat()
    Calib3d.Rodrigues(rvec, rot)
    // Centre the board (world centre ≈ (5, 3.5)) in front of the camera, deep enough that a tilt keeps the
    // whole board — plus its white quiet zone — inside the frame.
    val t = Array(-5.0, -3.5, 18.0)
    // M = [r1 | r2 | t] — the plane-to-camera map for z = 0 points.
    val model = Mat(3, 3, CvType.CV_64F)
    for i <- 0 until 3 do
      model.put(i, 0, rot.get(i, 0)(0))
      model.put(i, 1, rot.get(i, 1)(0))
      model.put(i, 2, t(i))
    val hCam = matMul(kMatrix, model) // flatWorld-coords → view pixels
    val aInv = Mat()
    Core.invert(flatToWorld, aInv)
    val h = matMul(hCam, aInv) // flat pixels → view pixels
    val dst = Mat()
    Imgproc.warpPerspective(
      flat.mat,
      dst,
      h,
      cv.Size(imgW.toDouble, imgH.toDouble),
      Imgproc.INTER_LINEAR,
      Core.BORDER_CONSTANT,
      cv.Scalar(255, 255, 255)
    )
    Seq(rvec, rot, model, hCam, aInv, h).foreach(_.release())
    Image.wrap(Managed(dst))

  private def views(): Seq[Image] =
    val flat = flatBoard()
    try
      Seq((0.0, 0.0), (14.0, 0.0), (-14.0, 0.0), (0.0, 14.0), (0.0, -14.0), (10.0, 10.0), (-10.0, -10.0))
        .map((rx, ry) => renderView(flat, rx, ry))
    finally flat.close()

  // -- tests ---------------------------------------------------------------------------------------

  test("findCorners locates the full inner grid on a flat board"):
    val flat = flatBoard()
    try
      val corners = Calibration.findCorners(flat, board)
      assert(corners.isDefined, "the whole board is visible; corners should be found")
      assertEquals(corners.get.size, board.corners) // 9 × 6 = 54
      // Every corner must sit inside the image.
      assert(corners.get.forall(p => p.x >= 0 && p.y >= 0 && p.x < flat.width && p.y < flat.height))
    finally flat.close()

  test("findCorners returns None when the board is absent"):
    val blank = Image.blank(320, 240, Scalar.White)
    try assertEquals(Calibration.findCorners(blank, board), None)
    finally blank.close()

  test("fromChessboard recovers the camera that rendered the views"):
    val vs = views()
    try
      Calibration.fromChessboard(vs, board, minViews = 5) match
        case Left(err) => fail(s"calibration should succeed on 7 synthetic views: ${err.getMessage}")
        case Right(calib) =>
          assertEquals(calib.imageSize, Size(imgW.toDouble, imgH.toDouble))
          val i = calib.intrinsics
          // The reprojection error is dominated by corner-rasterisation, not model mismatch: sub-pixel.
          assert(
            calib.reprojectionError < 1.0,
            s"RMS reprojection error too high: ${calib.reprojectionError}"
          )
          // Recovered focal length and principal point track the ground truth (fx=fy=800, cx=320, cy=240).
          assert(math.abs(i.fx - fx) / fx < 0.15, s"fx off: got ${i.fx}, expected $fx")
          assert(math.abs(i.fy - fy) / fy < 0.15, s"fy off: got ${i.fy}, expected $fy")
          assert(math.abs(i.cx - cx) < 40, s"cx off: got ${i.cx}, expected $cx")
          assert(math.abs(i.cy - cy) < 40, s"cy off: got ${i.cy}, expected $cy")
    finally vs.foreach(_.close())

  test("fromChessboard fails cleanly when too few views show the board"):
    val flat = flatBoard()
    val blank = Image.blank(imgW, imgH, Scalar.White)
    try
      // One good view, one blank — below the default minViews.
      Calibration.fromChessboard(Seq(flat, blank), board) match
        case Left(_: CvError.CalibrationFailed) => ()
        case other => fail(s"expected CalibrationFailed, got $other")
      // Empty input is also a clean Left, not a throw.
      assert(Calibration.fromChessboard(Seq.empty, board).isLeft)
    finally
      flat.close()
      blank.close()

  test("undistort of a distortion-free calibration is a size-preserving pass-through"):
    val calib = Calibration(Intrinsics(fx, fy, cx, cy), Size(imgW.toDouble, imgH.toDouble), 0.2)
    val src = Image.blank(imgW, imgH, Scalar(30, 60, 90))
    val out = src.undistort(calib)
    try
      assertEquals(out.width, imgW)
      assertEquals(out.height, imgH)
    finally out.close()

  test("undistort with real distortion coefficients runs and preserves size"):
    val i = Intrinsics(fx, fy, cx, cy, distortion = Seq(-0.25, 0.08, 0.0, 0.0, 0.0))
    val calib = Calibration(i, Size(imgW.toDouble, imgH.toDouble), 0.3)
    val src = flatBoard().resizeTo(Size(imgW.toDouble, imgH.toDouble))
    val out = src.undistort(calib)
    try assertEquals(Size(out.width.toDouble, out.height.toDouble), Size(imgW.toDouble, imgH.toDouble))
    finally out.close()

  test("ChessboardPattern rejects a degenerate grid or non-positive square"):
    intercept[IllegalArgumentException](ChessboardPattern(1, 6))
    intercept[IllegalArgumentException](ChessboardPattern(9, 6, squareSize = 0))
    assertEquals(ChessboardPattern(9, 6).corners, 54)
