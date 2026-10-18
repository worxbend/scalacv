package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core.{CvType, Mat}

import scalacv.vision.*

/** Native-leak regression gate. Each test drives a workload a few hundred times and asserts process RSS stays
  * bounded — the signal that sees `org.opencv.core.Mat` buffers (see [[LeakAssertions]]). This suite owns its
  * JVM (the `leaks` module), so RSS is not contaminated by other suites.
  *
  * These complement the deterministic contract tests in `core.test` (which assert a consumed handle throws on
  * reuse): those prove *a* handle was released; these prove memory does not accumulate at scale.
  *
  * Every workload names, in its comment, the smallest Mat a regression on its path would leak per iteration
  * and shows `bytes × iterations` in MiB against the 48 MiB tolerance; [[LeakAssertions.assertBounded]]
  * refuses a configuration whose full leak would not clear three times that tolerance. Fixtures are generated
  * — a 30-frame 320×240 MJPEG `.avi` and a 640×480 PNG, written once per suite into a temp dir.
  */
class LeakTest extends munit.FunSuite:

  private val frameCount = 30
  private var fixtures: Path = scala.compiletime.uninitialized
  private def avi: String = fixtures.resolve("frames.avi").toString
  private def png: String = fixtures.resolve("white.png").toString
  private def reEncoded: String = fixtures.resolve("re-encoded.avi").toString

  override def beforeAll(): Unit =
    OpenCv.load()
    fixtures = Files.createTempDirectory("scalacv-leaks-")
    Recorder
      .using(avi, Size(320, 240)): recorder =>
        // Varying colours so the MJPEG frames are real encodes, not thirty copies of one macroblock.
        for i <- 0 until frameCount do
          val frame = Image.blank(320, 240, Scalar(i * 8, 255 - i * 8, 128))
          try recorder.write(frame).fold(throw _, identity)
          finally frame.close()
      .fold(throw _, identity)
    Image.blank(640, 480, Scalar.White).write(png).fold(throw _, identity)

  override def afterAll(): Unit =
    Seq(avi, png, reEncoded).foreach(p => Files.deleteIfExists(Path.of(p)))
    Files.deleteIfExists(fixtures)

  test("the high-level Image pipeline holds one Mat and does not accumulate"):
    // The smallest Mat in the chain is a single-channel 640×480 intermediate (gray/blur/canny): 307,200 B.
    // Leaking one per iteration is 307,200 × 500 = 153.6 MB = 146.5 MiB, past the 48 MiB tolerance and past
    // the 3× floor; the 640×480×3 input (921,600 B) would be 439.5 MiB.
    LeakAssertions.assertBounded("Image pipeline", n = 500, leakBytesPerIteration = 307_200): () =>
      Image
        .blank(640, 480, Scalar.White)
        .gray
        .blur(2)
        .canny(80, 160)
        .bytes(".png")
        .fold(throw _, _ => ())

  test("blurBackground on the error path frees the receiver — no leak per failed call (F1)"):
    // A size-mismatched mask makes every call throw; the fix frees the receiver on that path. A regression
    // would leak the 640×480×3 receiver (921,600 B) per call: 921,600 × 200 = 184.3 MB = 175.8 MiB, well past
    // the 48 MiB tolerance. (The original 320×240 receiver over 200 calls was 43.9 MiB — under tolerance, so
    // the gate passed while leaking.)
    LeakAssertions.assertBounded("blurBackground error path", n = 200, leakBytesPerIteration = 921_600): () =>
      val img = Image.blank(640, 480, Scalar(0, 0, 255))
      val badMask = Image.blank(2, 2, Scalar.Black, channels = 1)
      try
        try img.blurBackground(badMask).close() // .close() only reached if it wrongly succeeds
        catch case _: IllegalArgumentException => () // expected; the fix already freed img
      finally badMask.close()

  test("undistort with a fresh Intrinsics per call does not leak the frame (F/§3.4)"):
    // What RSS can see here is the 640×480×3 frame (921,600 B): 921,600 × 300 = 276.5 MB = 263.7 MiB. The
    // 3×3 camera matrix and 4×1 distortion vector Intrinsics allocates per call are ~100 B each — below RSS
    // resolution at any sane n — so their release is proven by the deterministic suites in core.test, not
    // here.
    val intr = Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240, distortion = Seq(0.1, -0.05, 0, 0))
    LeakAssertions.assertBounded("undistort", n = 300, leakBytesPerIteration = 921_600): () =>
      Image.blank(640, 480, Scalar.White).undistort(intr).close()

  test("prepared undistortion releases cached maps and per-frame results"):
    val intr = Intrinsics(500, 500, 320, 240, Seq(0.1, -0.05, 0, 0))
    Managed.use(Mat(480, 640, CvType.CV_8UC3, Scalar.White.toCv)): source =>
      // Each preparation holds two 640×480 maps: CV_16SC2 (4 B/px = 1,228,800 B) and CV_16UC1 (2 B/px =
      // 614,400 B); each remap result is 640×480×3 = 921,600 B. The smallest single leak is the CV_16UC1 map:
      // 614,400 × 250 = 153.6 MB = 146.5 MiB, past the 48 MiB tolerance.
      LeakAssertions.assertBounded("prepared undistort", n = 250, leakBytesPerIteration = 614_400): () =>
        scala.util.Using.resource(Undistorter.prepare(intr, Size(640, 480))): prepared =>
          prepared(source).release()
          prepared(source).release()

  test("the zio Scope mapping releases like any other owner — no accumulation over repeated scopes"):
    // scalacv.zio.acquireRelease routes release through the same Releasable as Managed; a regression in the
    // Scope mapping would leak a 640×480×3 Mat (921,600 B) per scope: 921,600 × 200 = 184.3 MB = 175.8 MiB.
    import _root_.zio.*
    import scalacv.zio.acquireRelease
    LeakAssertions.assertBounded("zio scoped Mats", n = 200, leakBytesPerIteration = 921_600): () =>
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run(ZIO.scoped(acquireRelease(Mat(640, 480, CvType.CV_8UC3))))
          .getOrThrowFiberFailure(): Unit
      }

  test("Video.frames borrows one buffer across a whole file"):
    // The frame source decodes every frame into one reused buffer. If it instead retained a 320×240×3 frame
    // (230,400 B) per `next`, one pass over the file leaks 30 × 230,400 = 6,912,000 B, and 100 passes leak
    // 691.2 MB = 659.2 MiB.
    LeakAssertions.assertBounded("Video.frames", n = 100, leakBytesPerIteration = 30L * 230_400): () =>
      Video
        .open(avi)
        .fold(throw _, identity)
        .use: capture =>
          val decoded = Video.frames(capture)(_.count(frame => frame.rows == 240))
          assertEquals(decoded, frameCount)

  test("Image.reading with sequential copies and transforms releases through the scope registry"):
    // Every `copy` registers a 640×480×3 clone (921,600 B) with the reading scope's registry and `gray`
    // replaces it with its successor. If the registry lost track of either, ten copies per scope leak
    // 10 × 921,600 = 9,216,000 B, and 60 scopes leak 553.0 MB = 527.3 MiB.
    LeakAssertions.assertBounded("Image.reading copies", n = 60, leakBytesPerIteration = 10L * 921_600): () =>
      Image
        .reading(png) { src =>
          for _ <- 1 to 10 do src.copy.gray.close()
        }
        .fold(throw _, identity)

  test("Camera.foreach + recordTo round trip releases every decoded and transformed frame"):
    // recordTo clones each 320×240×3 frame (230,400 B) into an owned Image and closes it after the write.
    // A retained clone per frame leaks 30 × 230,400 = 6,912,000 B per round trip; 100 round trips leak
    // 691.2 MB = 659.2 MiB.
    LeakAssertions.assertBounded("Camera.recordTo", n = 100, leakBytesPerIteration = 30L * 230_400): () =>
      val camera = Camera.openFile(avi).fold(throw _, identity)
      try
        val written = camera.recordTo(reEncoded, attemptsPerFrame = 1)(identity).fold(throw _, identity)
        assertEquals(written, frameCount.toLong)
      finally camera.close()

  test("zio processFrames releases every clone"):
    // processFrames clones each 320×240×3 frame (230,400 B) inside its bracket and releases it before the
    // result is emitted. A clone that survived the bracket leaks 30 × 230,400 = 6,912,000 B per run; 100
    // runs leak 691.2 MB = 659.2 MiB.
    import _root_.zio.*
    import scalacv.zio.processFrames
    LeakAssertions.assertBounded("zio processFrames", n = 100, leakBytesPerIteration = 30L * 230_400): () =>
      Video
        .open(avi)
        .fold(throw _, identity)
        .use: capture =>
          Unsafe.unsafe { implicit unsafe =>
            val processed = Runtime.default.unsafe
              .run(processFrames(capture, attemptsPerFrame = 1)(frame => ZIO.succeed(frame.rows)).runCount)
              .getOrThrowFiberFailure()
            assertEquals(processed, frameCount.toLong)
          }
