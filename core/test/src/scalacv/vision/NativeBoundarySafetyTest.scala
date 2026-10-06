package scalacv.vision

import java.nio.file.Files

import org.opencv.core.{CvException, CvType, Mat}

import scalacv.*

/** Cross-module ownership regressions: the native handle must stay owned until validation succeeds. */
class NativeBoundarySafetyTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("model validation exceptions release the newly created handle"):
    val file = Files.createTempFile("scalacv-native-boundary", ".onnx")
    val handle = Mat(2, 2, CvType.CV_8UC1)
    val original = IllegalArgumentException("invalid model shape")
    var releases = 0
    val recording: Releasable[Mat] = m =>
      releases += 1
      m.release()
    try
      val caught = intercept[IllegalArgumentException]:
        internal.ModelLoader.loadNative[Mat](
          file.toString,
          describe = "creating a test model",
          missingDetails = "missing test model",
          validate = _ => throw original
        )(handle)(using recording)
      assert(caught eq original, "validation must preserve the original programmer error")
      assertEquals(releases, 1, "a handle must not leak when validation throws")
      assert(handle.empty(), "the model buffer must be released before the error propagates")
    finally
      if releases == 0 then handle.release()
      Files.deleteIfExists(file): Unit

  test("native validation failures stay in the typed error channel"):
    val file = Files.createTempFile("scalacv-native-validation", ".onnx")
    val handle = Mat(2, 2, CvType.CV_8UC1)
    val original = CvException("validation failed in OpenCV")
    var releases = 0
    val recording: Releasable[Mat] = m =>
      releases += 1
      m.release()
    try
      val attempted = scala.util.Try:
        internal.ModelLoader.loadNative[Mat](
          file.toString,
          describe = "validating a test model",
          missingDetails = "missing test model",
          validate = _ => throw original
        )(handle)(using recording)
      assert(attempted.isSuccess, "an OpenCV validation exception must be a Left, not escape")
      attempted.get match
        case Left(error: CvError.NativeCall) =>
          assertEquals(error.operation, "validating a test model")
          assert(error.getCause eq original, "the native cause must remain available")
        case other => fail(s"expected a typed native error, got $other")
      assertEquals(releases, 1)
      assert(handle.empty())
    finally
      if releases == 0 then handle.release()
      Files.deleteIfExists(file): Unit
