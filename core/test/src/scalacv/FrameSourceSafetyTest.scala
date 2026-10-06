package scalacv

import java.nio.file.Files

import org.opencv.core.{CvType, Mat, Scalar as CvScalar}

/** The shared sync/ZIO source must not enter JNI once its decode buffer has been released. */
class FrameSourceSafetyTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("a closed frame source rejects reads before entering the decoder"):
    val directory = Files.createTempDirectory("scalacv-source-lifetime")
    val file = directory.resolve("frame_00.png")
    try
      Managed.use(Mat(16, 16, CvType.CV_8UC3, CvScalar(42, 42, 42))): mat =>
        assert(Images.write(file.toString, mat).isRight, "could not write the lossless fixture frame")
      Video.open(file.toString, CaptureOptions(backend = CaptureBackend.ImageSequence)) match
        case Left(error) => fail(s"could not open the fixture: $error")
        case Right(capture) =>
          capture.use: native =>
            val source = Video.FrameSource(native, 1)
            try
              val frame = source.nextFrame().getOrElse(fail("the fixture must contain one frame"))
              assertEquals(frame.get(0, 0)(0), 42.0)
              source.close()
              intercept[IllegalStateException](frame.mat)
              val error = intercept[IllegalStateException](source.nextFrame())
              assert(error.getMessage.contains("closed"), error.getMessage)
            finally source.close()
    finally
      Files.deleteIfExists(file): Unit
      Files.deleteIfExists(directory): Unit
