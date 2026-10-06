package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.{VideoCapture, VideoWriter}

/** The high-level [[Camera]] and [[Recorder]]. Exercised entirely on the filesystem — record synthetic frames
  * with the built-in MJPG/AVI codec, then read them back — so it needs no camera and runs headless. A real
  * device is a separate, opt-in concern (see [[VideoTest]]'s SCALACV_CAMERA test).
  */
class CameraTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 96
  private val Height = 64
  private val FrameCount = 8
  private val FrameSize = Size(Width.toDouble, Height.toDouble)

  /** Frame `i`: a grey that steps per frame, with a white square that tracks `i` — two ways to tell frames
    * apart, so nothing passes on a coincidence.
    */
  private def frame(i: Int): Image =
    val grey = (20 + i * 20).toDouble
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(grey, grey, grey))
    Imgproc.rectangle(m, cv.Point(4 + i * 8, 4), cv.Point(12 + i * 8, 12), cv.Scalar(255, 255, 255), -1)
    Image.wrap(Managed(m))

  /** Records [[FrameCount]] synthetic frames to a fresh temp `.avi` (MJPG, the built-in codec) and returns
    * the path.
    */
  private def recordFixture(): Path =
    val dir = Files.createTempDirectory("scalacv-camera")
    dir.toFile.deleteOnExit()
    val file = dir.resolve("fixture.avi")
    Recorder
      .using(file.toString, FrameSize, fps = 10.0, codec = Codec.Mjpg): rec =>
        for i <- 0 until FrameCount do
          val f = frame(i)
          try rec.write(f).fold(e => fail(e.getMessage), identity)
          finally f.close()
      .fold(e => fail(s"could not open the recorder: ${e.getMessage}"), identity)
    assert(Files.size(file) > 0, "the fixture video is empty")
    file

  test("Codec FOURCC packs the four characters like CV_FOURCC, with no native call"):
    val expected = 'M'.toInt | ('J'.toInt << 8) | ('P'.toInt << 16) | ('G'.toInt << 24)
    assertEquals(Codec.Mjpg.fourcc, expected)

  test("a recorder writes a video that Camera reads back, frame for frame"):
    val file = recordFixture()
    val count = Camera
      .usingFile(file.toString): cam =>
        var n = 0
        cam.foreach(): img =>
          assertEquals((img.width, img.height), (Width, Height))
          n += 1
        n
      .fold(e => fail(e.getMessage), identity)
    assertEquals(count, FrameCount)

  test("snapshot grabs a single owned frame"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        cam.snapshot() match
          case Right(img) =>
            try assertEquals((img.width, img.height), (Width, Height))
            finally img.close()
          case Left(e) => fail(e.getMessage)
      .fold(e => fail(e.getMessage), identity)

  test("take returns the requested number of owned frames"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        val frames = cam.take(3)
        try
          assertEquals(frames.size, 3)
          frames.foreach(img => assertEquals(img.width, Width))
        finally frames.foreach(_.close())
      .fold(e => fail(e.getMessage), identity)

  test("recordTo pipes every frame through a transform into a new video"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-out-", ".avi")
    try
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg)(_.gray.convert(ColorConversion.GrayToBgr))
          .flatMap(identity)
      assertEquals(written, Right(FrameCount.toLong))
      assert(Files.size(out) > 0, "the piped video is empty")
    finally Files.deleteIfExists(out)

  test("info reports the geometry the video was written with"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        assertEquals((cam.info.width, cam.info.height), (Width, Height))
      .fold(e => fail(e.getMessage), identity)

  test("opening a nonexistent video is a Left, not a throw"):
    Camera.openFile("/no/such/scalacv-video.avi") match
      case Left(_) => ()
      case Right(cam) => cam.close(); fail("a missing video must not open")

  test("a recorder on an unwritable path is a Left, not a throw"):
    Recorder.open("/no/such/dir/scalacv-out.avi", FrameSize, codec = Codec.Mjpg) match
      case Left(_) => ()
      case Right(rec) => rec.close(); fail("an unwritable path must not open")

  test("the default codec opens a writer on this build"):
    val dir = Files.createTempDirectory("scalacv-camera-default")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("default.avi")
    // Deliberately no `codec` argument. Every other case here passes Codec.Mjpg explicitly, which is exactly
    // how a default that no OpenCV build can open stayed unnoticed: nothing exercised it.
    Recorder.open(out.toString, FrameSize) match
      case Left(e) => fail(s"the default codec must open a writer: ${e.getMessage}")
      case Right(rec) => rec.close()

  test("writing a frame that is not 8-bit is a rejected precondition"):
    val out = Files.createTempFile("scalacv-camera-depth-", ".avi")
    try
      Recorder.open(out.toString, FrameSize, codec = Codec.Mjpg) match
        case Left(e) => fail(e.getMessage)
        case Right(rec) =>
          try
            // What a Sobel asked for Float32 output, or a raw disparity map, hands you. The MJPG encoder
            // takes it, reinterprets the float bytes as pixels and reports success, so the precondition is
            // the only thing between the caller and a playable file of noise.
            val float = Mat(Height, Width, CvType.CV_32FC3, cv.Scalar(0.25, 0.5, 0.75))
            try intercept[IllegalArgumentException](rec.write(float))
            finally float.release()
          finally rec.close()
    finally Files.deleteIfExists(out)

  test("recordTo sizes the writer from the transformed frame, so a resizing transform records"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-resize-", ".avi")
    try
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg)(_.resize(Width / 2, Height / 2))
          .flatMap(identity)
      assertEquals(written, Right(FrameCount.toLong))
      Camera
        .usingFile(out.toString): cam =>
          assertEquals((cam.info.width, cam.info.height), (Width / 2, Height / 2))
        .fold(e => fail(e.getMessage), identity)
    finally Files.deleteIfExists(out)

  test("recordTo reports a mid-stream size change as a Left, not a thrown precondition"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-midstream-", ".avi")
    try
      var seen = 0
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg): img =>
              seen += 1
              // Stands in for a source that renegotiates its resolution part-way through, which no file
              // fixture can produce: the second frame no longer matches the geometry the writer opened with.
              if seen == 1 then img.resize(Width, Height) else img.resize(Width / 2, Height / 2)
          .flatMap(identity)
      written match
        case Left(_: CvError.EncodeFailed) => ()
        case other => fail(s"expected an EncodeFailed Left, got $other")
    finally Files.deleteIfExists(out)

  test("recordTo on an exhausted source writes nothing and creates no file"):
    val file = recordFixture()
    val dir = Files.createTempDirectory("scalacv-camera-empty")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("empty.avi")
    val written: Either[CvError, Long] =
      Camera
        .usingFile(file.toString): cam =>
          cam.foreach()(_ => ())
          cam.recordTo(out.toString, codec = Codec.Mjpg, attemptsPerFrame = 1)(
            _.gray.convert(ColorConversion.GrayToBgr)
          )
        .flatMap(identity)
    assertEquals(written, Right(0L))
    assert(!Files.exists(out), "with no frames there is nothing to size a writer from, so no file")

  test("writing a frame of the wrong size is a rejected precondition"):
    val out = Files.createTempFile("scalacv-camera-mismatch-", ".avi")
    try
      Recorder.open(out.toString, FrameSize, codec = Codec.Mjpg) match
        case Left(e) => fail(e.getMessage)
        case Right(rec) =>
          try
            val wrong = Image.blank(Width * 2, Height)
            try intercept[IllegalArgumentException](rec.write(wrong))
            finally wrong.close()
          finally rec.close()
    finally Files.deleteIfExists(out)

  test("foreach closes every frame it hands out, even when the body throws"):
    val file = recordFixture()
    var seen = Vector.empty[Image]
    Camera
      .usingFile(file.toString): cam =>
        cam.foreach(attemptsPerFrame = 1): img =>
          seen :+= img
          assert(!img.mat.empty(), "a frame must be live inside the body")
      .fold(e => fail(e.getMessage), identity)
    assertEquals(seen.size, FrameCount)
    seen.foreach(img => intercept[IllegalStateException](img.mat))

    var leaked: Option[Image] = None
    // `usingFile` folds a CvError thrown by the body into a Left (as Image.reading does), but any other
    // exception still escapes as a throw — the finally blocks in foreach and scoped are what is under test
    // here. The message check keeps an IllegalStateException from a double release inside the scope from
    // passing as the expected exception.
    val stopped = intercept[RuntimeException]:
      Camera.usingFile(file.toString): cam =>
        cam.foreach(attemptsPerFrame = 1): img =>
          leaked = Some(img)
          throw RuntimeException("stop")
    assertEquals(stopped.getMessage, "stop")
    intercept[IllegalStateException](leaked.get.width)

  test("usingFile folds a CvError thrown by the body into a Left, and still closes the camera"):
    val file = recordFixture()
    var escaped: Option[Camera] = None
    val result = Camera.usingFile(file.toString): cam =>
      escaped = Some(cam)
      throw CvError.NativeCall("boom", org.opencv.core.CvException("boom"))
    assert(result.isLeft, "a CvError from the body must come back as a Left, not escape the Either")
    assert(result.left.toOption.get.getMessage.contains("boom"))
    intercept[IllegalStateException](escaped.get.capture)

  test("usingFile closes the camera on the way out of a failing body"):
    val file = recordFixture()
    var escaped: Option[Camera] = None
    val stopped = intercept[RuntimeException]:
      Camera.usingFile(file.toString): cam =>
        escaped = Some(cam)
        throw RuntimeException("stop")
    assertEquals(stopped.getMessage, "stop")
    intercept[IllegalStateException](escaped.get.capture)

  test("usingFile never runs the body for a source that did not open"):
    var ran = false
    val missing = Camera.usingFile("/no/such/scalacv.avi"): _ =>
      ran = true
      0
    assert(missing.isLeft, "a missing source must be a Left")
    assert(!ran, "the body must not run for a source that did not open")

  test("take rejects a negative count and take(0) is Nil"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        intercept[IllegalArgumentException](cam.take(-1))
        assertEquals(cam.take(0), Nil)
      .fold(e => fail(e.getMessage), identity)

  test("taken frames are distinct owned copies, not views of one buffer"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        val two = cam.take(2, attemptsPerFrame = 1)
        try
          assertEquals(two.map(_.mat.getNativeObjAddr).distinct.size, 2, "the taken frames alias each other")
          // Two references to one decode buffer would both read the second frame's grey; owned copies keep
          // their own. Sampled below the marker row, within the tolerance VideoTest uses for MJPG.
          for (img, i) <- two.zipWithIndex do
            val grey = img.mat.get(40, 8)(0)
            assert(math.abs(grey - (20 + i * 20)) < 8, s"frame $i reads $grey, expected about ${20 + i * 20}")
        finally two.foreach(_.close())
      .fold(e => fail(e.getMessage), identity)

  test("take past the end is shorter, never padded"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        cam.taking(2, attemptsPerFrame = 1)(imgs => assertEquals(imgs.size, 2))
        val rest = cam.take(FrameCount + 5, attemptsPerFrame = 1)
        try assertEquals(rest.size, FrameCount - 2)
        finally rest.foreach(_.close())
      .fold(e => fail(e.getMessage), identity)

  test("snapshot on an exhausted source is an EndOfStream Left naming the source"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        cam.foreach(attemptsPerFrame = 1)(_ => ())
        cam.snapshot(attemptsPerFrame = 1) match
          case Left(e: CvError.EndOfStream) =>
            assert(
              e.getMessage.contains(file.toString),
              s"the error should name the exhausted file, not the bare word 'camera': ${e.getMessage}"
            )
          case Left(other) => fail(s"expected an EndOfStream, got $other")
          case Right(img) => img.close(); fail("an exhausted source must not snapshot")
      .fold(e => fail(e.getMessage), identity)

  test("a recorder rejects a non-positive fps or frame size as a programmer error, not a Left"):
    val out = Files.createTempFile("scalacv-rec-params-", ".avi")
    try
      intercept[IllegalArgumentException](Recorder.open(out.toString, FrameSize, fps = 0))
      intercept[IllegalArgumentException](Recorder.open(out.toString, FrameSize, fps = -1))
      intercept[IllegalArgumentException](Recorder.open(out.toString, Size(0, 10)))
    finally Files.deleteIfExists(out)

  test("a recorder borrows the frame it writes"):
    val out = Files.createTempFile("scalacv-rec-borrow-", ".avi")
    try
      Recorder
        .using(out.toString, FrameSize, codec = Codec.Mjpg): rec =>
          val img = frame(0)
          try
            assert(rec.write(img).isRight, "a matching frame should write")
            assertEquals((img.width, img.height), (Width, Height), "write must borrow, not consume")
          finally img.close()
        .fold(e => fail(e.getMessage), identity)
    finally Files.deleteIfExists(out)

  test("a recorder is dead but harmless after close, and close is idempotent"):
    val out = Files.createTempFile("scalacv-rec-lifecycle-", ".avi")
    try
      Recorder.open(out.toString, FrameSize, codec = Codec.Mjpg) match
        case Left(e) => fail(e.getMessage)
        case Right(rec) =>
          try
            val img = frame(0)
            try assert(rec.write(img).isRight, "a matching frame should write")
            finally img.close()
          finally rec.close()
          rec.close()
          // The size and depth preconditions still pass on a closed recorder; it is the spent handle that
          // refuses, and it does so on the Scala side rather than as a native write into a released writer.
          val again = frame(1)
          try intercept[IllegalStateException](rec.write(again))
          finally again.close()
          intercept[IllegalStateException](rec.writer)
          val readBack = Camera
            .usingFile(out.toString): cam =>
              var n = 0
              cam.foreach(attemptsPerFrame = 1)(_ => n += 1)
              n
            .fold(e => fail(e.getMessage), identity)
          assertEquals(readBack, 1, "close must finalise the file with the one frame that was written")
    finally Files.deleteIfExists(out)

  test("recordTo writes at the source's fps by default and at the explicit fps when one is given"):
    val file = recordFixture()
    val byDefault = Files.createTempFile("scalacv-camera-fps-default-", ".avi")
    val explicit = Files.createTempFile("scalacv-camera-fps-explicit-", ".avi")
    try
      Camera
        .usingFile(file.toString)(cam => cam.recordTo(byDefault.toString, attemptsPerFrame = 1)(identity))
        .flatMap(identity)
        .fold(e => fail(e.getMessage), identity)
      Camera
        .usingFile(byDefault.toString)(cam => assertEqualsDouble(cam.fps, 10.0, 0.5))
        .fold(e => fail(e.getMessage), identity)

      Camera
        .usingFile(file.toString): cam =>
          cam.recordTo(explicit.toString, fps = 25.0, attemptsPerFrame = 1)(identity)
        .flatMap(identity)
        .fold(e => fail(e.getMessage), identity)
      Camera
        .usingFile(explicit.toString)(cam => assertEqualsDouble(cam.fps, 25.0, 0.5))
        .fold(e => fail(e.getMessage), identity)
    finally
      Files.deleteIfExists(byDefault)
      Files.deleteIfExists(explicit)

  test(
    "every Codec packs its four characters exactly as VideoWriter.fourcc does, and the codes are distinct"
  ):
    val spelled = Map(Codec.Mjpg -> "MJPG", Codec.Mp4v -> "mp4v", Codec.Avc1 -> "avc1", Codec.Xvid -> "XVID")
    assertEquals(spelled.keySet, Codec.values.toSet, "a new Codec case needs its spelling pinned here")
    for (codec, s) <- spelled do
      assertEquals(codec.fourcc, VideoWriter.fourcc(s(0), s(1), s(2), s(3)), codec.toString)
    assertEquals(Codec.values.map(_.fourcc).distinct.length, Codec.values.length)

  test("Video.frames hands the capture back with the exception mode it found, even when the block throws"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        val capture = cam.capture
        capture.setExceptionMode(true)
        var inside = true
        Video.frames(capture): it =>
          inside = capture.getExceptionMode
          it.next()
        assert(!inside, "the loop needs exception mode off to tell end-of-file from a broken stream")
        assert(capture.getExceptionMode, "the caller's exception mode must be restored")

        var escaped: Option[Iterator[BorrowedMat]] = None
        intercept[RuntimeException]:
          Video.frames(capture): it =>
            escaped = Some(it)
            it.next()
            throw RuntimeException("boom")
        assert(capture.getExceptionMode, "restored on the throw path too")
        assert(!escaped.get.hasNext, "an iterator whose block threw must be retired")

        assertEquals(Video.frames(capture)(_.size), FrameCount - 2, "exactly one frame per block was pulled")
      .fold(e => fail(e.getMessage), identity)

  test("Video.info refuses a capture that is not open"):
    val capture = VideoCapture()
    try intercept[IllegalArgumentException](Video.info(capture))
    finally capture.release()

  test("CaptureOptions accepts timeouts that fit OpenCV's int milliseconds and rejects the rest"):
    for ms <- Seq(1L, 1000L, Int.MaxValue.toLong) do
      CaptureOptions(openTimeout = Some(ms.millis))
      CaptureOptions(readTimeout = Some(ms.millis))
    for d <- Seq(0.millis, -1.millis, (Int.MaxValue.toLong + 1).millis, 30.days) do
      intercept[IllegalArgumentException](CaptureOptions(openTimeout = Some(d)))
      intercept[IllegalArgumentException](CaptureOptions(readTimeout = Some(d)))
    assertEquals(
      CaptureOptions.withTimeout(3.seconds, CaptureBackend.FFmpeg),
      CaptureOptions(CaptureBackend.FFmpeg, Some(3.seconds), Some(3.seconds))
    )
