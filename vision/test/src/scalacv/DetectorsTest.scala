package scalacv

import scalacv.vision.*

import scala.util.Using

import org.opencv.core.{Core, CvType, Mat, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeEncoder

/** Round-trip tests. Every fixture is synthesised here — there is no image file in this repo and none may be
  * added — which for fiducials is the honest test anyway: OpenCV generates the marker, OpenCV reads it back,
  * and the assertion is on the id that came out.
  */
class DetectorsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** ArUco and QR both locate candidates as a dark quad on a light background, so a marker rendered flush to
    * the image edge is undetectable no matter how clean it is. Both generators emit the symbol alone; the
    * quiet zone is the caller's job, and forgetting it is the single most common reason a round-trip
    * "mysteriously" finds nothing.
    */
  private def withQuietZone(src: Mat, border: Int): Mat =
    val out = Mat()
    Core.copyMakeBorder(src, out, border, border, border, border, Core.BORDER_CONSTANT, CvScalar(255.0))
    out

  test("generateMarker produces a square 8-bit image the requested size"):
    Using.resource(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, 7, 120)): m =>
      assertEquals(m.get.rows, 120)
      assertEquals(m.get.cols, 120)
      assertEquals(m.get.`type`, CvType.CV_8UC1)

  test("a generated ArUco marker round-trips back to its own id"):
    val id = 23
    Using
      .Manager: use =>
        val marker = use(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id, 200))
        val scene = use(Managed(withQuietZone(marker.get, 60)))
        val found = Aruco.detect(scene.get, ArucoDictionary.Dict4x4_50)
        assertEquals(found.map(_.id), List(id))
        assertEquals(found.head.corners.size, 4, s"expected a quad, got ${found.head.corners}")
        // The quiet zone is 60px, so every corner must sit inside the marker, not on the border.
        found.head.corners.foreach: p =>
          assert(p.x >= 50 && p.x <= 270 && p.y >= 50 && p.y <= 270, s"corner outside the marker: $p")
      .get

  test("detect finds several markers at once, with the right ids"):
    Using
      .Manager: use =>
        val ids = List(1, 4, 9)
        val scene = use(Managed(Mat(300, 900, CvType.CV_8UC1, CvScalar(255.0))))
        ids.zipWithIndex.foreach: (id, i) =>
          val marker = use(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id, 180))
          val roi = use(Managed(scene.get.submat(60, 240, 60 + i * 300, 240 + i * 300)))
          marker.get.copyTo(roi.get)
        assertEquals(Aruco.detect(scene.get, ArucoDictionary.Dict4x4_50).map(_.id).sorted, ids)
      .get

  test("a marker from another dictionary is not reported"):
    Using
      .Manager: use =>
        val marker = use(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, 3, 200))
        val scene = use(Managed(withQuietZone(marker.get, 60)))
        // 5x5 tags carry more bits than a 4x4 pattern can satisfy, so this must come back empty
        // rather than mis-identify.
        assertEquals(Aruco.detect(scene.get, ArucoDictionary.Dict5x5_250), Seq.empty)
      .get

  test("detect on a blank image finds nothing and does not throw"):
    Managed(Mat(200, 200, CvType.CV_8UC1, CvScalar(255.0))).use: blank =>
      assertEquals(Aruco.detect(blank), Seq.empty)

  test("detect rejects an empty Mat rather than handing it to native code"):
    val e = intercept[IllegalArgumentException](Aruco.detect(Mat()))
    assert(e.getMessage.contains("non-empty"), e.getMessage)

  test("generateMarker guards its arguments"):
    intercept[IllegalArgumentException](Aruco.generateMarker(ArucoDictionary.Dict4x4_50, -1, 100))
    intercept[IllegalArgumentException](Aruco.generateMarker(ArucoDictionary.Dict4x4_50, 0, 0))

  test("an id beyond the dictionary is a native failure, not a silent empty Mat"):
    // Dict4x4_50 holds 50 markers, so 50 is one past the end.
    intercept[CvError.NativeCall](Aruco.generateMarker(ArucoDictionary.Dict4x4_50, 50, 100))

  test("ArucoDictionary values match OpenCV's own constants"):
    import org.opencv.objdetect.Objdetect
    assertEquals(ArucoDictionary.Dict4x4_50.cvValue, Objdetect.DICT_4X4_50)
    assertEquals(ArucoDictionary.Dict6x6_250.cvValue, Objdetect.DICT_6X6_250)
    assertEquals(ArucoDictionary.AprilTag36h11.cvValue, Objdetect.DICT_APRILTAG_36h11)
    assertEquals(ArucoDictionary.ArucoOriginal.cvValue, Objdetect.DICT_ARUCO_ORIGINAL)
    assertEquals(ArucoDictionary.values.length, 22)

  test("Qr.detectAndDecode on a blank image returns empty without throwing"):
    Managed(Mat(240, 320, CvType.CV_8UC3, CvScalar(255.0, 255.0, 255.0))).use: blank =>
      assertEquals(Qr.detectAndDecode(blank), Seq.empty)

  test("Qr.detectAndDecode on noise-free geometry that is not a QR code returns empty"):
    Managed(Mat(240, 320, CvType.CV_8UC1, CvScalar(255.0))).use: m =>
      val (tl, br) = (org.opencv.core.Point(40, 40), org.opencv.core.Point(160, 160))
      Imgproc.rectangle(m, tl, br, CvScalar(0.0), -1)
      assertEquals(Qr.detectAndDecode(m), Seq.empty)

  test("Qr.detectAndDecode rejects an empty Mat"):
    val e = intercept[IllegalArgumentException](Qr.detectAndDecode(Mat()))
    assert(e.getMessage.contains("non-empty"), e.getMessage)

  /** The QR *decode* step segfaults the JVM under `OpenCv.load()` as it stands — see the note on this test.
    * The round-trip matters because it is the only assertion that proves [[Qr]] reads OpenCV's Nx4 CV_32FC2
    * corner layout correctly, and because it is what caught a real defect in the native loader.
    */
  test("a QR code encoded by OpenCV round-trips back to its payload"):
    // This test was briefly skipped because it crashed the JVM:
    //   SIGSEGV ... cv::Mat::release() <- libopencv_objdetect
    // The cause was in OpenCv.load(), not here. The old loader dlopen'd every module library
    // speculatively, and the bundled libopencv_highgui.so carries *unversioned* NEEDED entries,
    // so loading it made the linker search the system path and map six libopencv_*.so.5.0.0
    // system libraries into the global namespace, where they interposed on our 4.13.0 symbols.
    // Decoding a QR code is the first thing in the suite that crosses between the two ABIs.
    // The loader is now demand-driven and never loads a library the JNI shim did not ask for.
    // QRCodeEncoder emits the symbol at one pixel per module, which is below the detector's
    // resolution floor, so scale up with INTER_NEAREST (anything smoothing would blur the
    // module edges) and then add the quiet zone.
    val payload = "scalacv-b12"
    Using
      .Manager: use =>
        given Releasable[QRCodeEncoder] = Releasable.nativeHandle
        val encoder = use(Managed(QRCodeEncoder.create()))
        val raw = use(Managed(Mat()))
        encoder.get.encode(payload, raw.get)
        assert(!raw.get.empty(), "the encoder produced nothing")

        val big = use(Managed(Mat()))
        Imgproc.resize(raw.get, big.get, CvSize(0, 0), 8, 8, Imgproc.INTER_NEAREST)
        val scene = use(Managed(withQuietZone(big.get, 40)))

        val codes = Qr.detectAndDecode(scene.get)
        assertEquals(codes.map(_.text), List(payload))
        assertEquals(codes.head.corners.size, 4, s"expected a quad, got ${codes.head.corners}")
      .get
