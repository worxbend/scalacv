package scalacv

import java.nio.file.Files

import org.opencv.core.{CvException, CvType, Mat}
import org.opencv.videoio.VideoCapture
import org.opencv.objdetect.CascadeClassifier

private final class FailingBatchCapture extends VideoCapture:
  private var reads = 0
  override def isOpened(): Boolean = true
  override def read(frame: Mat): Boolean =
    reads += 1
    if reads == 2 then throw CvException("injected decoder failure")
    frame.create(8, 8, CvType.CV_8UC3)
    true

class CoreOwnershipRegressionTest extends munit.FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  test("framesCopied rolls back clones acquired before a later decoder failure"):
    val capture = new FailingBatchCapture
    var first: Option[Managed[Mat]] = None
    var pixels: Option[Mat] = None
    try
      intercept[CvError.NativeCall]:
        Video.framesCopied(capture): frames =>
          first = Some(frames.next())
          pixels = first.map(_.get)
          frames.next()
      assertEquals(pixels.get.dataAddr(), 0L)
      assert(first.get.isReleased)
    finally
      first.foreach(_.release())
      capture.release()

  private def camera(capture: VideoCapture): Camera =
    val constructor = classOf[Camera].getDeclaredConstructors.head
    constructor.setAccessible(true)
    constructor.newInstance(Managed(capture), "injected").asInstanceOf[Camera]

  test("snapshot reports decoder failures through its Either boundary"):
    val source = camera(new FailingBatchCapture)
    try
      source.snapshot().toOption.get.close()
      assert(source.snapshot().left.toOption.exists(_.isInstanceOf[CvError.NativeCall]))
    finally source.close()

  test("negative brightness saturates at zero rather than reflecting"):
    val image = Image.blank(2, 2, Scalar(10, 10, 10)).adjust(brightness = -20)
    try assertEquals(image.mat.get(0, 0).toSeq, Seq(0.0, 0.0, 0.0))
    finally image.close()

  test("native delete lookup failure leaves a subclass pointer recoverable"):
    val classifier = new CascadeClassifier() {}
    val address = classifier.getNativeObjAddr
    try
      intercept[CvError.NativesMissing](Releasable.nativeHandle[CascadeClassifier].release(classifier))
      assertEquals(classifier.getNativeObjAddr, address)
    finally
      // Clean up even against the broken implementation, which has already erased the pointer.
      val field = classOf[CascadeClassifier].getDeclaredField("nativeObj")
      field.setAccessible(true)
      field.setLong(classifier, 0L)
      val delete = classOf[CascadeClassifier].getDeclaredMethod("delete", classOf[Long])
      delete.setAccessible(true)
      delete.invoke(null, Long.box(address)): Unit

  test("an interrupted model download stops before a succeeding mirror and removes its temporary file"):
    val dir = Files.createTempDirectory("scalacv-interrupt-")
    val source = Files.createTempFile("scalacv-mirror-", ".bin")
    Files.write(source, Array[Byte](1, 2, 3)): Unit
    try
      val spec = ModelSpec.unverified("model.bin", Seq("http://127.0.0.1:1/model", source.toUri.toString))
      Thread.currentThread().interrupt()
      var interrupted = false
      try Models.fetch(spec, dir)
      catch case _: InterruptedException => interrupted = true
      assert(interrupted, "cancellation must propagate instead of trying the file mirror")
      assert(Thread.currentThread().isInterrupted)
      assert(!Files.exists(dir.resolve("model.bin")))
      scala.util.Using.resource(Files.list(dir))(paths => assertEquals(paths.count(), 0L))
    finally
      Thread.interrupted(): Unit
      Files.deleteIfExists(dir.resolve("model.bin")): Unit
      Files.deleteIfExists(source): Unit
      Files.deleteIfExists(dir): Unit

  test("scope follows copies, in-place moves, identities and managed pipelines"):
    val scope = new Image.Scope
    val root = scope.own(Image.blank(8, 8))
    val branch = root.copy.gray
    val painted = root.blur(0).drawRect(Rect(0, 0, 2, 2))
    val pipeline = painted.managed.pipe(_.cvtColor(ColorConversion.BgrToGray))
    val rawBranch = branch.mat
    val rawPipeline = pipeline.get
    assertEquals(scope.activeCount, 2)
    scope.close()
    assertEquals(rawBranch.dataAddr(), 0L)
    assertEquals(rawPipeline.dataAddr(), 0L)
    assert(pipeline.isReleased)
    assertEquals(scope.activeCount, 0)
    scope.close()

  test("scope stores only active owners during a long consuming chain"):
    val scope = new Image.Scope
    var image = scope.own(Image.blank(2, 2))
    try
      for _ <- 0 until 10000 do
        image = image.blur(0)
        assertEquals(scope.activeCount, 1)
      image.close()
      assertEquals(scope.activeCount, 0)
    finally scope.close()

  test("detach explicitly escapes while leaving copy branches scoped"):
    var branch: Option[Mat] = None
    val escaped = Image.scoped(Image.blank(2, 2)): image =>
      branch = Some(image.copy.mat)
      image.detach
    try
      assertEquals(escaped.width, 2)
      assertEquals(branch.get.dataAddr(), 0L)
    finally escaped.close()

  test("moves preserve a custom release strategy exactly once"):
    var releases = 0
    val raw = Mat(2, 2, CvType.CV_8UC3)
    val managed = Managed(raw)(using
      mat =>
        releases += 1; mat.release()
    )
    val scope = new Image.Scope
    val image = scope.own(Image.wrap(managed))
    val moved = image.blur(0).drawRect(Rect(0, 0, 1, 1)).managed
    assertEquals(releases, 0)
    scope.close()
    assertEquals(releases, 1)
    assertEquals(raw.dataAddr(), 0L)
    moved.release()
    assertEquals(releases, 1)

  test("a failing paint preserves its primary failure and the custom releaser"):
    val primary = IllegalArgumentException("drawing")
    val cleanup = RuntimeException("cleanup")
    var releases = 0
    val image = Image.wrap(
      Managed(Mat(2, 2, CvType.CV_8UC3))(using
        mat =>
          releases += 1
          mat.release()
          throw cleanup
      )
    )
    val observed = intercept[IllegalArgumentException](image.paint(_ => throw primary))
    assert(observed eq primary)
    assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))
    assertEquals(releases, 1)

  test("a failed input cleanup rolls back a successfully allocated pipe output"):
    val cleanup = RuntimeException("cleanup")
    val input = Managed(Mat(2, 2, CvType.CV_8UC3))(using
      mat =>
        mat.release(); throw cleanup
    )
    var output: Option[Mat] = None
    val observed = intercept[RuntimeException]:
      input.pipe: source =>
        val next = source.cvtColor(ColorConversion.BgrToGray)
        output = Some(next.get)
        next
    assert(observed eq cleanup)
    assertEquals(output.get.dataAddr(), 0L)

  test("scope cleanup attempts every owner and suppresses failures on the body"):
    val primary = IllegalArgumentException("body")
    val cleanup = RuntimeException("cleanup")
    var released = 0
    val observed = intercept[IllegalArgumentException]:
      scala.util.Using.resource(new Image.Scope): scope =>
        scope.own(
          Image.wrap(
            Managed(Mat())(using
              mat =>
                released += 1; mat.release()
            )
          )
        )
        scope.own(
          Image.wrap(
            Managed(Mat())(using
              mat =>
                released += 1; mat.release(); throw cleanup
            )
          )
        )
        throw primary
    assert(observed eq primary)
    assertEquals(released, 2)
    assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))

  test("camera foreach closes a transformed frame when the next read fails"):
    val source = camera(new FailingBatchCapture)
    var pixels: Option[Mat] = None
    try
      intercept[CvError.NativeCall]:
        source.foreach()(image => pixels = Some(image.gray.mat))
      assertEquals(pixels.get.dataAddr(), 0L)
    finally
      pixels.foreach(_.release())
      source.close()

  test("camera taking owns transformed descendants after its callback returns"):
    val source = camera(new FailingBatchCapture)
    var pixels: Option[Mat] = None
    try
      val width = source.taking(1): images =>
        val next = images.head.gray
        pixels = Some(next.mat)
        next.width
      assertEquals(width, 8)
      assertEquals(pixels.get.dataAddr(), 0L)
    finally
      pixels.foreach(_.release())
      source.close()

  test("successful copied batches transfer their buffers to the caller"):
    val capture = new FailingBatchCapture
    try
      val batch = Video.framesCopied(capture)(_.take(1).toList)
      try assertEquals(batch.head.get.rows(), 8)
      finally batch.foreach(_.release())
    finally capture.release()

  test("a frame-source cleanup failure is suppressed on the callback failure"):
    val primary = IllegalArgumentException("callback")
    val cleanup = RuntimeException("restore mode")
    val capture = new VideoCapture:
      override def isOpened(): Boolean = true
      override def getExceptionMode(): Boolean = true
      override def setExceptionMode(enabled: Boolean): Unit = if enabled then throw cleanup
    try
      val observed = intercept[IllegalArgumentException](Video.frames(capture)(_ => throw primary))
      assert(observed eq primary)
      assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))
    finally capture.release()

  test("fatal cleanup retains Using precedence instead of being swallowed"):
    val primary = IllegalArgumentException("body")
    val fatal = LinkageError("cleanup")
    val handle = Managed("value")(using _ => throw fatal)
    var observed: Throwable | Null = null
    try handle.use(_ => throw primary)
    catch case error: Throwable => observed = error
    assert(observed eq fatal)
    assertEquals(fatal.getSuppressed.toSeq, Seq(primary))
    assert(handle.isReleased)

  test("Managed.use preserves a body failure and suppresses a cleanup failure"):
    val primary = IllegalArgumentException("body")
    val cleanup = RuntimeException("cleanup")
    given Releasable[String] = _ => throw cleanup
    val handle = Managed("value")
    val observed = intercept[IllegalArgumentException](handle.use(_ => throw primary))
    assert(observed eq primary)
    assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))
    assert(handle.isReleased)

  test("Scoped.using cannot turn a programmer failure into a cleanup Left"):
    val primary = IllegalArgumentException("body")
    val cleanup = CvError.NativesMissing("cleanup")
    val resource = new AutoCloseable:
      def close(): Unit = throw cleanup
    val observed = intercept[IllegalArgumentException]:
      Scoped.using(Right(resource), "test")(_ => throw primary)
    assert(observed eq primary)
    assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))

  test("pipe preserves a body failure when release fails"):
    val primary = IllegalArgumentException("body")
    val cleanup = RuntimeException("cleanup")
    val raw = Image.blank(2, 2).managed
    val handle = Managed(raw.take())(using
      m =>
        m.release(); throw cleanup
    )
    val observed = intercept[IllegalArgumentException](handle.pipe(_ => throw primary))
    assert(observed eq primary)
    assertEquals(observed.getSuppressed.toSeq, Seq(cleanup))

  test("reading owns a transformed successor when the callback returns plain data"):
    val png = Files.createTempFile("scalacv-scope-", ".png")
    var successor: Option[Image] = None
    try
      assertEquals(Image.blank(8, 8).write(png.toString), Right(()))
      val result = Image.reading(png.toString): image =>
        val next = image.gray
        successor = Some(next)
        next.width
      assertEquals(result, Right(8))
      val retained = successor.get
      intercept[IllegalStateException](retained.width)
    finally
      successor.foreach(_.close())
      Files.deleteIfExists(png): Unit
