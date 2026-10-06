package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.nio.file.{Files, Path}

import scala.concurrent.duration.DurationInt

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.{VideoCapture, VideoWriter}

/** The video under test is written here, frame by frame, so that every assertion can name the exact content
  * the round trip has to preserve. No asset ships with this repository and none may.
  *
  * The codec is MJPG in an AVI container, which is the one combination OpenCV can always write: it is served
  * by the built-in MJPEG writer and needs no FFmpeg, no GStreamer and no system codec. Measured on the
  * bytedeco 4.13.0 linux-x86_64 build, FFV1, HFYU and IYUV all fail to open a writer at all. MJPG is lossy,
  * hence the tolerances below — they are wide enough for JPEG and far narrower than the 25-level step between
  * consecutive frames, so a frame delivered out of order, repeated, or dropped cannot pass.
  */
class VideoTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 96
  private val Height = 64
  private val FrameCount = 10
  private val Fps = 10.0

  /** Frame `i`: a uniform grey that steps by 25 per frame, plus an 8x8 white square whose x position tracks
    * `i`. Two independent ways to identify a frame, so an assertion cannot pass on a coincidence.
    */
  private def frameFixture(i: Int): Mat =
    val grey = (20 + i * 25).toDouble
    val m = Mat(Height, Width, CvType.CV_8UC3, CvScalar(grey, grey, grey))
    Imgproc.rectangle(m, CvPoint(squareX(i), 4), CvPoint(squareX(i) + 8, 12), CvScalar(255, 255, 255), -1)
    m

  private def squareX(i: Int): Int = 4 + i * 8
  private def expectedGrey(i: Int): Double = (20 + i * 25).toDouble

  /** The centre of frame `i`'s white square. */
  private def markerPixel(m: Mat, i: Int): Double = m.get(8, squareX(i) + 4)(0)

  /** A background pixel, sampled well clear of the marker row. */
  private def backgroundPixel(m: Mat): Double = m.get(40, 8)(0)

  /** [[backgroundPixel]] for a live borrowed frame. */
  private def backgroundPixel(m: BorrowedMat): Double = m.get(40, 8)(0)

  private def assertIsFrame(m: Mat, i: Int)(using munit.Location): Unit =
    assertEquals(m.rows, Height, s"frame $i has the wrong height")
    assertEquals(m.cols, Width, s"frame $i has the wrong width")
    assert(
      markerPixel(m, i) > 200,
      s"frame $i: the white marker should be at x=${squareX(i)}, but that pixel is ${markerPixel(m, i)}"
    )
    assert(
      math.abs(backgroundPixel(m) - expectedGrey(i)) < 8,
      s"frame $i: background is ${backgroundPixel(m)}, expected about ${expectedGrey(i)}"
    )

  /** The borrowed-frame face of the raw-Mat assertion above: the `mat` escape hatch is the live buffer while
    * the iterator has not moved, so checking a frame through it checks the frame.
    */
  private def assertIsFrame(frame: BorrowedMat, i: Int)(using munit.Location): Unit =
    assertIsFrame(frame.mat, i)

  /** Writes [[FrameCount]] fixture frames to a fresh temp file and returns its path. */
  private def writeFixtureVideo(): Path =
    val dir = Files.createTempDirectory("scalacv-video")
    dir.toFile.deleteOnExit()
    val file = dir.resolve("fixture.avi")
    val fourcc = VideoWriter.fourcc('M', 'J', 'P', 'G')
    Managed.use(VideoWriter(file.toString, fourcc, Fps, CvSize(Width, Height), true)): writer =>
      assert(writer.isOpened, "could not open an MJPG AVI writer; the test fixture cannot be built")
      for i <- 0 until FrameCount do Managed.use(frameFixture(i))(writer.write)
    assert(Files.size(file) > 0, "the fixture video is empty")
    file

  /** Opens the fixture video and hands the capture to `f`, releasing it afterwards. */
  private def withFixtureCapture[A](file: Path)(f: VideoCapture => A): A =
    Video.open(file.toString) match
      case Left(e) => fail(s"could not open the fixture video: $e")
      case Right(capture) => capture.use(f)

  test("a written video round-trips: every frame comes back, in order, with its content intact"):
    val file = writeFixtureVideo()
    val seen = withFixtureCapture(file): capture =>
      Video.frames(capture): frames =>
        var count = 0
        for frame <- frames do
          assertIsFrame(frame, count)
          count += 1
        count
    assertEquals(seen, FrameCount, "the iterator did not yield exactly the frames that were written")

  test("the iterator decodes into one buffer and overwrites it in place — it never memoises"):
    val file = writeFixtureVideo()
    val (distinctBuffers, backgrounds) = withFixtureCapture(file): capture =>
      Video.frames(capture): frames =>
        val buffers = scala.collection.mutable.Set.empty[Long]
        val values = Vector.newBuilder[Double]
        while frames.hasNext do
          val frame = frames.next()
          // The native data address, not object identity: each frame is a fresh BorrowedMat view over
          // the iterator's single decode buffer, so one address for the whole traversal is the contract
          // — and it is the reason a LazyList cannot express this iterator.
          buffers += frame.dataAddr()
          values += backgroundPixel(frame)
        (buffers.size, values.result())
    assertEquals(distinctBuffers, 1, "the frame iterator allocated more than one decode buffer")
    assertEquals(backgrounds.size, FrameCount)
    // A single reused buffer is only correct if it really is rewritten each time.
    assertEquals(backgrounds.distinct.size, FrameCount, s"frames repeated content: $backgrounds")

  test(
    "a borrowed frame is spent when the iterator advances — retention throws instead of reading freed memory"
  ):
    // The ownership hole BorrowedMat exists to close: handed out as a raw Mat, a retained frame reference
    // read whatever the next decode had overwritten it with, and freed native memory once the scope had
    // ended — a SIGSEGV, not an exception. Now the access fails in Scala, before anything crosses JNI.
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      Video.frames(capture): frames =>
        val first = frames.next()
        assertIsFrame(first, 0) // live: the contract holds while the iterator has not moved
        frames.next() // decodes over the same buffer — `first` is spent by the advance
        val e = intercept[IllegalStateException](first.get(0, 0))
        assert(e.getMessage.contains("borrowed"), e.getMessage)
        intercept[IllegalStateException](first.empty())
        intercept[IllegalStateException](first.clone())
        intercept[IllegalStateException](first.mat) // the escape hatch is checked like everything else

  test("a borrowed frame kept past the frames block is spent, exactly like a released Managed"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      val escaped = Video.frames(capture)(_.next())
      val e = intercept[IllegalStateException](escaped.mat)
      assert(e.getMessage.contains("borrowed"), e.getMessage)
      // The capture is borrowed, not consumed: the next traversal resumes where this one stopped.
      assertEquals(Video.frames(capture)(_.size), FrameCount - 1)

  test("a borrowed frame records through the mat escape hatch, with no per-frame copy"):
    val dir = Files.createTempDirectory("scalacv-rec")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("borrowed.avi")
    val source = writeFixtureVideo()
    val result = withFixtureCapture(source): capture =>
      Recorder.using(out.toString, Size(Width.toDouble, Height.toDouble), Fps, Codec.Mjpg): recorder =>
        Video.frames(capture): frames =>
          frames.foreach: frame =>
            val written = recorder.write(frame.mat)
            assert(written.isRight, s"a borrowed frame should record: $written")
    assert(result.isRight, s"recorder should have opened: $result")
    assert(Files.size(out) > 0, "the recording is empty")

  test("hasNext is idempotent — asking twice does not swallow a frame"):
    val file = writeFixtureVideo()
    val count = withFixtureCapture(file): capture =>
      Video.frames(capture): frames =>
        var n = 0
        while frames.hasNext && frames.hasNext && frames.hasNext do
          frames.next()
          n += 1
        n
    assertEquals(count, FrameCount)

  test("next() past the end throws NoSuchElementException rather than an empty Mat"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      Video.frames(capture): frames =>
        frames.foreach(_ => ())
        intercept[NoSuchElementException](frames.next())

  test("an iterator kept past its scope is retired, not left decoding into a released Mat"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      // Escaping the scope is a programmer error, but the failure has to be inert: without retirement,
      // read() would happily reallocate the released Mat and hand back a frame nothing owns.
      val escaped = Video.frames(capture): frames =>
        frames.next()
        frames
      assert(!escaped.hasNext, "a retired iterator must not offer more frames")
      val e = intercept[NoSuchElementException](escaped.next())
      assert(e.getMessage.contains("already returned"), e.getMessage)
      // The capture itself is untouched by retirement — only the iterator is finished.
      assertEquals(Video.frames(capture)(_.size), FrameCount - 1)

  test("frames does not rewind: a second traversal resumes where the first stopped"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      val firstThree = Video.frames(capture)(_.take(3).map(backgroundPixel).toVector)
      assertEquals(firstThree.size, 3)
      val rest = Video.frames(capture)(_.map(backgroundPixel).toVector)
      assertEquals(rest.size, FrameCount - 3, "the capture restarted instead of resuming")
      // Position, not just count: the fourth frame must be the fourth one written.
      assert(
        math.abs(rest.head - expectedGrey(3)) < 8,
        s"resumed at a background of ${rest.head}, expected about ${expectedGrey(3)}"
      )

  test("framesCopied hands back owned Mats that outlive the iterator and the scope"):
    val file = writeFixtureVideo()
    val copies = withFixtureCapture(file)(capture => Video.framesCopied(capture)(_.take(4).toVector))
    try
      assertEquals(copies.size, 4)
      val mats = copies.map(_.get)
      assertEquals(mats.map(_.getNativeObjAddr).distinct.size, 4, "the copies alias each other")
      // Read them after both the iterator and the frames() scope are gone. A borrowed frame would be
      // released and empty here; a copy is not.
      for (m, i) <- mats.zipWithIndex do
        assert(!m.empty(), s"copy $i was released with the iterator")
        assertIsFrame(m, i)
    finally copies.foreach(_.release())

  test("framesCopied only copies what is pulled"):
    val file = writeFixtureVideo()
    val copies = withFixtureCapture(file)(capture => Video.framesCopied(capture)(_.take(2).toVector))
    try assertEquals(copies.size, 2)
    finally copies.foreach(_.release())

  test("opening a nonexistent path is a Left, not a capture that yields no frames"):
    val missing = Files.createTempDirectory("scalacv-video").resolve("no-such-video.avi").toString
    Video.open(missing) match
      case Right(c) => c.release(); fail("a missing file must not produce an open capture")
      case Left(e) => assert(e.getMessage.contains(missing), s"the error should name the source: $e")

  test("opening an existing file that is not a video is a Left"):
    val dir = Files.createTempDirectory("scalacv-video")
    dir.toFile.deleteOnExit()
    val junk = dir.resolve("not-a-video.avi")
    Files.write(junk, Array.fill[Byte](512)(0x41))
    Video.open(junk.toString) match
      case Right(c) => c.release(); fail("garbage bytes must not produce an open capture")
      case Left(e) => assert(e.getMessage.contains(junk.toString), s"the error should name the source: $e")

  test("an empty source is rejected before it reaches OpenCV"):
    intercept[IllegalArgumentException](Video.open(""))
    intercept[IllegalArgumentException](Video.open(-1))

  test("timeout options do not break opening a local file"):
    // The backend that reads this file rejects the timeout parameters outright (measured: the
    // parameterised open reports isOpened == false), so this asserts the documented fallback actually
    // happens rather than the options turning a working open into a failure.
    val file = writeFixtureVideo()
    Video.open(file.toString, CaptureOptions.withTimeout(2.seconds)) match
      case Left(e) => fail(s"timeout options broke a local open: $e")
      case Right(capture) =>
        capture.use: c =>
          val count = Video.frames(c)(_.size)
          assertEquals(count, FrameCount)

  test("a file source is not warmed up, so its very first frame is still frame 0"):
    // The per-source default split is the whole point of warmupFrames: a file has no auto-exposure loop
    // to converge, so discarding frames there would silently skip content the caller asked for. This is
    // the guard against someone "simplifying" the two defaults into one non-zero number.
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      Video.frames(capture)(frames => assertIsFrame(frames.next(), 0))

  test("an explicit warmupFrames discards exactly that many frames before the first read"):
    // Pinned on a file rather than a camera because CI has no camera; the discard loop is the same code
    // either way, and the fixture's per-frame marker makes "which frame arrived first" observable.
    val file = writeFixtureVideo()
    val Warmup = 3
    Video.open(file.toString, CaptureOptions(warmupFrames = Some(Warmup))) match
      case Left(e) => fail(s"warmupFrames broke a local open: $e")
      case Right(capture) =>
        capture.use: c =>
          Video.frames(c): frames =>
            assertIsFrame(frames.next(), Warmup)
            // and the rest of the file is still there, three frames shorter
            assertEquals(1 + frames.size, FrameCount - Warmup)

  test("a warm-up longer than the source stops at the end instead of spinning"):
    // grab() returns false past the last frame; the loop has to break on that rather than run the full
    // count, or a short source would cost `warmup` pointless native calls.
    val file = writeFixtureVideo()
    Video.open(file.toString, CaptureOptions(warmupFrames = Some(FrameCount * 10))) match
      case Left(e) => fail(s"an over-long warm-up should not fail the open: $e")
      case Right(capture) => capture.use(c => assertEquals(Video.frames(c)(_.size), 0))

  test("a negative warmupFrames is a programmer error"):
    intercept[IllegalArgumentException](CaptureOptions(warmupFrames = Some(-1)))

  test("info reports the geometry the file was written with"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      val i = Video.info(capture)
      assertEquals(i.width, Width)
      assertEquals(i.height, Height)
      assertEquals(i.size, Size(Width.toDouble, Height.toDouble))
      assertEquals(i.frameCount, FrameCount.toLong)
      assertEqualsDouble(i.fps, Fps, 0.5)
      assert(i.backendName.nonEmpty, "the backend should name itself")

  test("Recorder.write accepts a borrowed Mat directly, and rejects a mis-sized one"):
    val dir = Files.createTempDirectory("scalacv-rec")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("rec.avi")
    val result = Recorder.using(out.toString, Size(Width.toDouble, Height.toDouble), Fps, Codec.Mjpg):
      recorder =>
        // The borrowing overload: a raw video frame writes with no Image wrapping and no clone.
        Managed.use(frameFixture(0))(m =>
          assert(recorder.write(m).isRight, "a correctly-sized Mat should write")
        )
        Managed.use(Mat(Height + 4, Width, CvType.CV_8UC3, CvScalar(0, 0, 0))): wrong =>
          intercept[IllegalArgumentException](recorder.write(wrong))
    assert(result.isRight, s"recorder should have opened: $result")
    assert(Files.size(out) > 0, "the recording is empty")

  test("Camera.taking yields a batch and closes every frame when the block returns"):
    val file = writeFixtureVideo()
    val taken = Camera.usingFile(file.toString): cam =>
      cam.taking(3): imgs =>
        assertEquals(imgs.size, 3)
        assert(imgs.forall(img => !img.mat.empty()), "frames should be live inside the block")
        imgs
    taken match
      case Left(e) => fail(s"could not open the fixture as a Camera: $e")
      case Right(imgs) =>
        // The block handed the images back out; taking has since closed them all, so touching one now throws
        // the same use-after-release Managed guards against — proof the batch was released.
        imgs.foreach(img => intercept[IllegalStateException](img.mat))

  test("frames refuses a capture that is not open"):
    val capture = VideoCapture()
    try intercept[IllegalArgumentException](Video.frames(capture)(_.size))
    finally capture.release()

  test("attemptsPerFrame must be at least one"):
    val file = writeFixtureVideo()
    withFixtureCapture(file): capture =>
      intercept[IllegalArgumentException](Video.frames(capture, 0)(_.size))

  test("a nonsensical timeout is a programmer error"):
    intercept[IllegalArgumentException](CaptureOptions(openTimeout = Some(0.millis)))

  test("every CaptureBackend maps to a distinct videoio constant"):
    val values = CaptureBackend.values.map(_.cvValue)
    assertEquals(values.distinct.length, values.length)
    assertEquals(CaptureBackend.Any.cvValue, 0)

  // Camera tests are opt-in: an unguarded Video.open(0) on a developer machine opens whatever webcam is
  // attached, and in CI there is nothing to open. Run with SCALACV_CAMERA=1.
  test("a real camera delivers frames of the size it reports"):
    assume(sys.env.contains("SCALACV_CAMERA"), "set SCALACV_CAMERA=1 to run camera tests")
    Video.open(0) match
      case Left(e) => assume(false, s"no camera at index 0: ${e.getMessage}")
      case Right(capture) =>
        capture.use: c =>
          val i = Video.info(c)
          // Two attempts per frame: a camera may drop one without the stream being over.
          val sizes = Video.frames(c, attemptsPerFrame = 3)(_.take(3).map(m => (m.cols, m.rows)).toVector)
          assert(sizes.nonEmpty, "the camera opened but delivered no frames")
          assert(
            sizes.forall(_ == (i.width, i.height)),
            s"frames are $sizes but the camera reports ${i.width}x${i.height}"
          )
