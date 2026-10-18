package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core as cv
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

import scalacv.vision.*

/** Camera calibration, headless.
  *
  * A real calibration reads a chessboard from a camera at several angles; here the views are synthesised so
  * the demo runs with no hardware and no fixture files. A flat board is projected through a **known** pinhole
  * camera (`fx = fy = 800`, centre `320, 240`) tilted a few ways — the same physically consistent input a
  * real capture gives — and [[Calibration.fromChessboard]] recovers that camera back. The recovered
  * [[Intrinsics]] are exactly what [[Ar]], [[HeadPose]] and [[Localizer]] take, and what [[Image.undistort]]
  * uses.
  */
@main def calibrationDemo(): Unit =
  OpenCv.load()

  val board = ChessboardPattern(columns = 9, rows = 6, squareSize = 0.025) // 25 mm squares
  val imgW = 640
  val imgH = 480
  val (fx, cx, cy) = (800.0, 320.0, 240.0)

  // --- a crisp flat board with a white quiet zone -------------------------------------------------
  val square = 60
  val margin = 60
  def flatBoard(): Image =
    val w = (board.columns + 1) * square + 2 * margin
    val h = (board.rows + 1) * square + 2 * margin
    val m = Mat(h, w, CvType.CV_8UC3, cv.Scalar(255, 255, 255))
    for
      r <- 0 until board.rows + 1
      c <- 0 until board.columns + 1
      if (r + c) % 2 == 0
    do
      // A submat is a view onto `m`'s pixels, but it is still a native header of its own to free — and
      // `Managed.use` frees it even if the fill throws, which a bare `release()` after the call does not.
      val cell = m.submat(
        margin + r * square,
        margin + (r + 1) * square,
        margin + c * square,
        margin + (c + 1) * square
      )
      Managed.use(cell)(roi => roi.setTo(cv.Scalar(0, 0, 0)): Unit)
    Image.wrap(Managed(m))

  // --- project the flat board through the known camera, tilted by (rx, ry) degrees ----------------
  // The three helper matrices outlive every view, so they are freed in a `finally`: the previous
  // `Seq(k, aFlat, aInv).foreach(_.release())` at the end of the program ran only on the happy path.
  val k = Mat(3, 3, CvType.CV_64F, cv.Scalar(0))
  val aFlat = Mat(3, 3, CvType.CV_64F, cv.Scalar(0))
  val aInv = Mat()
  try
    k.put(0, 0, fx, 0.0, cx, 0.0, fx, cy, 0.0, 0.0, 1.0)
    aFlat.put(
      0,
      0,
      square.toDouble,
      0.0,
      (margin + square).toDouble,
      0.0,
      square.toDouble,
      (margin + square).toDouble,
      0.0,
      0.0,
      1.0
    )
    Core.invert(aFlat, aInv)

    def view(flat: Image, rx: Double, ry: Double): Image =
      // One scope for every working matrix. The previous `Seq(...).foreach(_.release())` at the end of this
      // function released four of them but ran only on the happy path, and it missed two more entirely: the
      // empty Mat `gemm` insists on being handed, and the intermediate `matMul(k, model)` product that the
      // outer multiplication consumed and nobody named.
      Managed.scope: own =>
        def matMul(a: Mat, b: Mat): Mat =
          val dst = own(Mat())
          // gemm computes alpha*A*B + beta*C. beta is 0, so C contributes nothing — but the signature still
          // requires a Mat, and that Mat still has to be freed.
          Core.gemm(a, b, 1.0, own(Mat()), 0.0, dst)
          dst

        val rvec = own(Mat(3, 1, CvType.CV_64F))
        rvec.put(0, 0, math.toRadians(rx), math.toRadians(ry), 0.0)
        val rot = own(Mat())
        Calib3d.Rodrigues(rvec, rot)
        val t = Array(-5.0, -3.5, 18.0) // centre the board in front of the camera
        val model = own(Mat(3, 3, CvType.CV_64F))
        for i <- 0 until 3 do
          model.put(i, 0, rot.get(i, 0)(0))
          model.put(i, 1, rot.get(i, 1)(0))
          model.put(i, 2, t(i))
        val h = matMul(matMul(k, model), aInv)
        // The warped view is the one Mat here the scope must not own — it escapes to the caller. A bare
        // `Managed(Mat())` filled afterwards had no rollback: a throwing warpPerspective left it allocated
        // and unowned. `Mats.produce` allocates, fills under the Cv guard and releases on the exception path.
        Image.wrap(
          Mats.produce("warpPerspective"): dst =>
            Imgproc.warpPerspective(
              flat.mat,
              dst,
              h,
              cv.Size(imgW.toDouble, imgH.toDouble),
              Imgproc.INTER_LINEAR,
              Core.BORDER_CONSTANT,
              cv.Scalar(255, 255, 255)
            )
        )

    val flat = flatBoard()
    Calibration.findCorners(flat, board) match
      case Some(corners) =>
        println(s"found ${corners.size} of ${board.corners} inner corners on the flat board")
      case None => println("no board found (unexpected)")

    val tilts = Seq((0.0, 0.0), (14.0, 0.0), (-14.0, 0.0), (0.0, 14.0), (0.0, -14.0), (10.0, 10.0))
    val views = tilts.map(view(flat, _, _))
    flat.close()

    Calibration.fromChessboard(views, board, minViews = 4) match
      case Right(calib) =>
        val i = calib.intrinsics
        println(f"recovered:  fx=${i.fx}%.1f  fy=${i.fy}%.1f  centre=(${i.cx}%.1f, ${i.cy}%.1f)")
        println(f"ground truth: fx=$fx%.1f  fy=$fx%.1f  centre=($cx%.1f, $cy%.1f)")
        println(f"RMS reprojection error: ${calib.reprojectionError}%.4f px")
        // Undistort the first view with the recovered model — a no-op here (synthetic lens is ideal), but
        // this is exactly the call that straightens a real wide-angle frame.
        views.head
          .undistort(calib)
          .bytes(".png")
          .foreach(b => println(s"undistorted a frame: ${b.length} bytes"))
      case Left(err) => println(s"calibration failed: ${err.getMessage}")

    views.foreach(_.close())
  finally Seq(k, aFlat, aInv).foreach(_.release())

  // Reading a real capture from disk instead:
  //   val frames = (1 to 15).flatMap(i => Image.read(s"calib/$i.jpg").toOption)
  //   Calibration.fromChessboard(frames, board).foreach { calib =>
  //     Image.reading("wide.jpg")(_.undistort(calib).write("straight.jpg"))
  //   }
  println("OK")
