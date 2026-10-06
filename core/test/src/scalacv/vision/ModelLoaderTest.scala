package scalacv.vision

import java.nio.file.Files

import org.opencv.core.{CvType, Mat}

import scalacv.*

/** The shared model loader that [[Cascades]], [[Dnn]], [[FaceDetect]] and [[FaceRecognizer]] converge on.
  *
  * The per-loader suites cover the happy and error paths end to end; this suite pins the loader's own
  * contract — the four-step guard in order — with `Mat` standing in for a native model handle, so the
  * null-return and validate-rejection paths are exercised directly rather than through a model file that
  * happens to trigger them.
  */
class ModelLoaderTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def tempModel(): java.nio.file.Path =
    val f = Files.createTempFile("scalacv-model-loader", ".onnx")
    f.toFile.deleteOnExit()
    Files.write(f, Array[Byte](1, 2, 3))

  test("a missing path is a Left naming the path, before any native call runs"):
    val path = "/no/such/scalacv-model.onnx"
    var nativeRan = false
    val result = internal.ModelLoader.loadNative[Mat](
      path,
      describe = s"creating a test model from '$path'",
      missingDetails = "no file here"
    ) { nativeRan = true; Mat() }
    assert(!nativeRan, "the native factory must not run when there is no file")
    result match
      case Left(e: CvError.LoadFailed) =>
        assert(e.getMessage.contains(path), e.getMessage)
        assert(e.getMessage.contains("no file here"), e.getMessage)
      case other => fail(s"expected a LoadFailed, got $other")

  test("a factory that returns null is a Left, not a Right wrapping null"):
    // The quiet failure mode of the generated factory methods — the guard FaceRecognizer.load was missing
    // before the loaders converged on ModelLoader.
    val f = tempModel()
    internal.ModelLoader.loadNative[Mat](
      f.toString,
      describe = "creating a test model",
      missingDetails = "no file here"
    )(null) match
      case Left(e: CvError.LoadFailed) =>
        assert(e.getMessage.contains(f.toString), e.getMessage)
        assert(e.getMessage.contains("null"), e.getMessage)
      case other => fail(s"expected a LoadFailed, got $other")

  test("a handle validate rejects is released before the Left is returned"):
    // The handle is real even though the model is not — CascadeClassifier's silent-empty failure mode —
    // so the error path must free it, which is observable here through a recording Releasable.
    val f = tempModel()
    var released = false
    val recording: Releasable[Mat] = m => { released = true; m.release() }
    internal.ModelLoader.loadNative[Mat](
      f.toString,
      describe = "creating a test model",
      missingDetails = "no file here",
      validate = m => Option.when(m.empty())("an empty Mat is not a model")
    )(Mat())(using recording) match
      case Left(e: CvError.LoadFailed) =>
        assert(e.getMessage.contains("an empty Mat is not a model"), e.getMessage)
      case other => fail(s"expected a LoadFailed, got $other")
    assert(released, "a rejected handle must be released, not leaked")

  test("a good handle comes back Right and caller-owned"):
    val f = tempModel()
    internal.ModelLoader.loadNative[Mat](
      f.toString,
      describe = "creating a test model",
      missingDetails = "no file here",
      validate = m => Option.when(m.empty())("an empty Mat is not a model")
    )(Mat(2, 2, CvType.CV_8UC1)) match
      case Left(e) => fail(s"a usable handle should load: $e")
      case Right(mat) => mat.release()
