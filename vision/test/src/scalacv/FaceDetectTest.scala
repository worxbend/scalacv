package scalacv

import scalacv.vision.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import org.opencv.core.{Core, CvType, Mat, Point as CvPoint, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN

/** Where the features of `FaceDetectTest.synthFace` were actually drawn, so the assertions can compare
  * YuNet's landmarks against ground truth instead of against themselves. Without this, a decode that read the
  * 15 columns in the wrong order would still "pass".
  */
private final case class FaceTruth(
    center: Point,
    halfWidth: Double,
    halfHeight: Double,
    rightEye: Point,
    leftEye: Point,
    nose: Point,
    mouthLeft: Point,
    mouthRight: Point
)

/** YuNet face detection.
  *
  * The fixture is drawn here, pixel by pixel — there is no image file in this repo and none may be added by
  * design. That turns out to be the *stronger* test: because the test knows where it put the eyes, the nose
  * and the mouth, it can assert that OpenCV's five landmarks land on them, which is precisely the assertion a
  * mis-indexed 15-column decode fails.
  *
  * Everything that needs the model needs the network the first time it runs, so those tests `assume`-skip
  * rather than fail when it is not reachable. The model is cached in the system temp directory and reused.
  */
class FaceDetectTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val modelDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "scalacv-models")

  private lazy val model: Either[CvError, Path] = FaceDetect.downloadModel(modelDir)

  /** The model path, or a skip carrying the reason the download failed. */
  private def modelPath: String =
    assume(
      model.isRight,
      s"skipped: the YuNet model could not be obtained (no network?): ${model.left.toOption.orNull}"
    )
    model.toOption.get.toString

  /** Draws a frontal face big enough for YuNet to find, and reports where every feature went.
    *
    * A bare skin-coloured ellipse is *not* detected (measured) — the model wants eyes, brows, a nose shadow
    * and a mouth, and it wants them slightly blurred rather than as hard vector edges.
    */
  private def withFace[A](w: Int, h: Int)(f: (Mat, FaceTruth) => A): A =
    val m = Mat(h, w, CvType.CV_8UC3, CvScalar(210, 215, 220))
    try
      val cx = w / 2.0
      val cy = h / 2.0
      val fw = w * 0.22
      val fh = h * 0.30
      val skin = CvScalar(150, 190, 220)
      val dark = CvScalar(40, 45, 55)

      def ell(x: Double, y: Double, rx: Double, ry: Double, c: CvScalar): Unit =
        Imgproc.ellipse(m, CvPoint(x, y), CvSize(rx, ry), 0, 0, 360, c, -1, Imgproc.LINE_AA, 0)

      def arc(x: Double, y: Double, rx: Double, ry: Double, a0: Int, a1: Int, c: CvScalar, t: Int): Unit =
        Imgproc.ellipse(m, CvPoint(x, y), CvSize(rx, ry), 0, a0, a1, c, t, Imgproc.LINE_AA, 0)

      // Hair first, face over it: that leaves a curved hairline rather than a floating ellipse.
      ell(cx, cy - fh * 0.25, fw * 1.12, fh * 0.95, CvScalar(30, 35, 45))
      ell(cx, cy, fw, fh, skin)

      val eyeY = cy - fh * 0.18
      val eyeDx = fw * 0.40
      val eyeR = fw * 0.20
      val browThickness = math.max(2, (fw * 0.08).toInt)
      for sx <- List(-1.0, 1.0) do
        ell(cx + sx * eyeDx, eyeY, eyeR, eyeR * 0.55, CvScalar(245, 245, 245)) // sclera
        ell(cx + sx * eyeDx, eyeY, eyeR * 0.42, eyeR * 0.42, dark) // iris
        arc(cx + sx * eyeDx, eyeY - eyeR * 0.9, eyeR * 1.1, eyeR * 0.5, 200, 340, dark, browThickness) // brow

      val noseY = cy + fh * 0.12
      val mouthY = cy + fh * 0.42
      val mouthDx = fw * 0.42
      ell(cx, noseY, fw * 0.16, fh * 0.16, CvScalar(120, 160, 195))
      arc(cx, mouthY, mouthDx, fh * 0.14, 0, 180, CvScalar(90, 90, 160), -1)

      Imgproc.GaussianBlur(m, m, CvSize(5, 5), 0)
      val truth = FaceTruth(
        center = Point(cx, cy),
        halfWidth = fw,
        halfHeight = fh,
        // The subject's right eye is the one on the left of the image, which is the order YuNet reports.
        rightEye = Point(cx - eyeDx, eyeY),
        leftEye = Point(cx + eyeDx, eyeY),
        nose = Point(cx, noseY),
        mouthRight = Point(cx - mouthDx, mouthY),
        mouthLeft = Point(cx + mouthDx, mouthY)
      )
      f(m, truth)
    finally m.release()

  /** Builds a detector, fails the test if it cannot be built, and always releases it. */
  private def withDetector[A](scoreThreshold: Float = 0.5f)(f: FaceDetectorYN => A): A =
    FaceDetect.create(modelPath, Size(320, 320), scoreThreshold) match
      case Left(e) => fail(s"could not build a detector from the downloaded model: $e")
      case Right(d) => d.use(f)

  private def near(actual: Point, expected: Point, tolerance: Double, what: String): Unit =
    val d = actual.distanceTo(expected)
    assert(d <= tolerance, s"$what: expected around $expected, got $actual (off by ${d.round}px)")

  private def sha256(p: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map(b => f"$b%02x").mkString

  // ---------------------------------------------------------------- create

  test("create on a path with no file is a Left, not a detector that silently finds nothing"):
    val missing = modelDir.resolve("definitely-not-here.onnx").toString
    FaceDetect.create(missing, Size(320, 320)) match
      case Left(e: CvError.LoadFailed) => assert(e.getMessage.contains(missing), e.getMessage)
      case Left(e) => fail(s"wrong error for a missing model: $e")
      case Right(d) =>
        d.release()
        fail("create accepted a model path that does not exist")

  test("create on a file that is not an ONNX network is a Left"):
    val junk = Files.createTempFile("scalacv-not-a-model-", ".onnx")
    try
      Files.write(junk, "this is not a protobuf".getBytes("UTF-8")): Unit
      FaceDetect.create(junk.toString, Size(320, 320)) match
        case Left(_) => ()
        case Right(d) =>
          d.release()
          fail("create accepted a file that is not a network")
    finally Files.deleteIfExists(junk): Unit

  test("create rejects a degenerate input size and out-of-range thresholds"):
    intercept[IllegalArgumentException](FaceDetect.create("ignored.onnx", Size(0, 320)))
    intercept[IllegalArgumentException](
      FaceDetect.create("ignored.onnx", Size(320, 320), scoreThreshold = 1.5f)
    )
    intercept[IllegalArgumentException](
      FaceDetect.create("ignored.onnx", Size(320, 320), nmsThreshold = -0.1f)
    )

  // ---------------------------------------------------------------- detect

  test("a drawn face is found, and all 15 columns decode to the features that were drawn"):
    withDetector() { det =>
      withFace(320, 320): (image, truth) =>
        val faces = FaceDetect.detect(det, image)
        assertEquals(faces.size, 1, s"expected exactly one face, got $faces")
        val face = faces.head

        // Columns 0..3 — the box. It has to sit on the head, not somewhere else in a 320x320 frame.
        val boxCentre = Point(face.box.x + face.box.width / 2.0, face.box.y + face.box.height / 2.0)
        near(boxCentre, truth.center, 40, "box centre")
        assert(
          face.box.width > truth.halfWidth && face.box.width < truth.halfWidth * 4,
          s"box width ${face.box.width} is nothing like the drawn face (half-width ${truth.halfWidth})"
        )

        // Columns 4..13 — the five landmarks, in YuNet's documented order. The tolerance is under half
        // the face's half-width: loose enough for a regressor on a cartoon, far tighter than the gap
        // between any two of these features, so no permutation of the pairs passes.
        val tolerance = truth.halfWidth * 0.4
        assertEquals(face.landmarks.size, 5)
        near(face.rightEye, truth.rightEye, tolerance, "right eye (image-left)")
        near(face.leftEye, truth.leftEye, tolerance, "left eye (image-right)")
        near(face.noseTip, truth.nose, tolerance, "nose tip")
        near(face.rightMouthCorner, truth.mouthRight, tolerance, "right mouth corner")
        near(face.leftMouthCorner, truth.mouthLeft, tolerance, "left mouth corner")

        // The relations that hold at any tolerance, and that a transposed or shifted decode breaks.
        assert(face.rightEye.x < face.leftEye.x, s"eyes are swapped: ${face.landmarks}")
        assert(face.rightEye.y < face.noseTip.y, s"the eyes are not above the nose: ${face.landmarks}")
        assert(
          face.noseTip.y < face.rightMouthCorner.y,
          s"the nose is not above the mouth: ${face.landmarks}"
        )
        assert(
          face.rightMouthCorner.x < face.leftMouthCorner.x,
          s"mouth corners are swapped: ${face.landmarks}"
        )

        // Column 14 — the score. Not a landmark coordinate, which is what an off-by-one puts here.
        assert(face.score > 0.5f && face.score <= 1.0f, s"score outside (0.5, 1]: ${face.score}")
    }

  test("one detector handles frames of a size it was never constructed with"):
    // Constructed for 320x320, then fed 240x240 and 400x400. Without the per-frame setInputSize inside
    // detect, OpenCV's CV_CheckEQ throws a CvException on the very first of these.
    withDetector() { det =>
      for size <- List(240, 400) do
        withFace(size, size): (image, truth) =>
          val faces = FaceDetect.detect(det, image)
          assertEquals(faces.size, 1, s"expected one face in a ${size}x$size frame, got $faces")
          near(faces.head.noseTip, truth.nose, truth.halfWidth * 0.5, s"nose tip at ${size}x$size")
    }

  test("an image with no face yields no faces — the 0x0 result Mat is not decoded as garbage"):
    withDetector() { det =>
      Managed.use(Mat(320, 320, CvType.CV_8UC3, CvScalar(200, 200, 200))): blank =>
        assertEquals(FaceDetect.detect(det, blank), Seq.empty)
      // Noise too, so "no faces" is not merely "no gradients anywhere".
      Managed.use(Mat(320, 320, CvType.CV_8UC3)): noise =>
        Core.randu(noise, 0, 255)
        assertEquals(FaceDetect.detect(det, noise), Seq.empty)
    }

  test("scoreThreshold reaches the detector"):
    // The drawn face scores ~0.89, so it survives 0.5 (asserted above) and must not survive 0.99. Were
    // the threshold dropped on the floor between here and the constructor, both would report a face.
    withDetector(scoreThreshold = 0.99f) { det =>
      withFace(320, 320)((image, _) => assertEquals(FaceDetect.detect(det, image), Seq.empty))
    }

  test("detect rejects inputs YuNet cannot take, before they reach the DNN module"):
    withDetector() { det =>
      Managed.use(Mat()): empty =>
        intercept[IllegalArgumentException](FaceDetect.detect(det, empty))
      Managed.use(Mat(320, 320, CvType.CV_8UC1, CvScalar(0))): gray =>
        intercept[IllegalArgumentException](FaceDetect.detect(det, gray))
      Managed.use(Mat(320, 320, CvType.CV_32FC3, CvScalar(0, 0, 0))): float =>
        intercept[IllegalArgumentException](FaceDetect.detect(det, float))
    }

  test("a Face always carries exactly five landmarks, in a fixed order"):
    intercept[IllegalArgumentException](Face(Rect(0, 0, 10, 10), Seq(Point(1, 1)), 0.9f))
    val ok = Face(Rect(0, 0, 10, 10), (1 to 5).map(i => Point(i.toDouble, i.toDouble)), 0.9f)
    assertEquals(ok.landmarks.size, FaceDetect.LandmarkCount)
    assertEquals(ok.rightEye, Point(1, 1))
    assertEquals(ok.leftEye, Point(2, 2))
    assertEquals(ok.noseTip, Point(3, 3))
    assertEquals(ok.rightMouthCorner, Point(4, 4))
    assertEquals(ok.leftMouthCorner, Point(5, 5))

  test("released detectors survive garbage collection"):
    // The binding's finalize() calls delete(nativeObj) too, so a detector we have already freed is a
    // double free waiting for the collector — and a double free here is a SIGSEGV on the Finalizer
    // thread, which takes the whole JVM down rather than failing a test. That is what this run looked
    // like before FaceDetect's Releasable zeroed nativeObj first, so: build a batch, free them, and
    // insist the collector actually gets to them while the process is still standing.
    val path = modelPath
    for _ <- 1 to 16 do
      FaceDetect.create(path, Size(320, 320)).fold(e => fail(s"create failed: $e"), _.release())
    for _ <- 1 to 3 do
      System.gc()
      System.runFinalization()
      Thread.sleep(100)
    // Still alive, and OpenCV is still usable afterwards.
    withDetector() { det =>
      withFace(320, 320)((image, _) => assertEquals(FaceDetect.detect(det, image).size, 1))
    }

  // ---------------------------------------------------------------- downloadModel

  test("downloadModel returns a file whose size and SHA-256 are the pinned ones"):
    val path = Path.of(modelPath)
    assertEquals(path.getFileName.toString, FaceDetect.ModelFileName)
    assertEquals(Files.size(path), FaceDetect.ModelSizeBytes)
    assertEquals(sha256(path), FaceDetect.ModelSha256)

  test("downloadModel replaces a corrupt cached model instead of handing it back"):
    val ok = modelPath // skips the test when there is no network, and warms the cache
    assert(ok.nonEmpty)
    val dir = Files.createTempDirectory("scalacv-yunet-corrupt-")
    val target = dir.resolve(FaceDetect.ModelFileName)
    try
      // Exactly the right length, wrong bytes: a size check on its own accepts this file.
      Files.write(target, Array.fill(FaceDetect.ModelSizeBytes.toInt)(0x41.toByte)): Unit
      FaceDetect.downloadModel(dir) match
        case Left(e) => fail(s"could not re-fetch over a corrupt cache: $e")
        case Right(p) =>
          assertEquals(p, target)
          assertEquals(sha256(p), FaceDetect.ModelSha256, "the corrupt cached file was returned as-is")
    finally
      Files.deleteIfExists(target): Unit
      Files.deleteIfExists(dir): Unit

  test("every mirror URL is an LFS media URL, not a raw one that serves a 131-byte pointer"):
    assert(FaceDetect.ModelUrls.nonEmpty)
    FaceDetect.ModelUrls.foreach: url =>
      assert(url.startsWith("https://"), s"not https: $url")
      assert(!url.contains("raw.githubusercontent.com"), s"raw. serves the LFS pointer, not the model: $url")
      assert(url.endsWith(FaceDetect.ModelFileName), s"does not point at the model file: $url")
