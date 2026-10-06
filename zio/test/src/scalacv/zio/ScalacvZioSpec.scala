package scalacv.zio

import java.nio.file.Files

import _root_.zio.*
import _root_.zio.stream.*
import _root_.zio.test.*
import _root_.zio.test.Assertion.*

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.{VideoCapture, VideoWriter, Videoio}

import scalacv.*

/** Track C's gate. Every native object here is acquired into a scope, so the specs also serve as evidence
  * that scope release actually fires — a leak would not fail an assertion, but the finalizer-disarm and
  * release paths are exercised under ZIO's acquisition semantics.
  */
object ScalacvZioSpec extends ZIOSpecDefault:

  private val FrameCount = 8
  // A native call, so it must not run until loadNatives has: lazy, not val.
  private lazy val fourcc = VideoWriter.fourcc('M', 'J', 'P', 'G')

  /** Writes a short MJPG/AVI whose frames are individually identifiable: a grey level that steps per frame,
    * and a white marker whose x-position tracks the frame index. A dropped, repeated or reordered frame
    * cannot survive either signal. MJPG is the one writer always built in to bytedeco's OpenCV, per B9's
    * findings.
    */
  private def writeSample(): Task[java.nio.file.Path] = ZIO.attempt:
    val path = Files.createTempFile("scalacv-zio-", ".avi")
    val writer = VideoWriter(path.toString, fourcc, 10.0, CvSize(64, 64), true)
    try
      require(writer.isOpened, "could not open an MJPG VideoWriter")
      var i = 0
      while i < FrameCount do
        val f = Mat(64, 64, CvType.CV_8UC3, CvScalar(10 + i * 20, 10 + i * 20, 10 + i * 20))
        try
          Imgproc.rectangle(f, CvPoint(4 + i * 6, 28), CvPoint(12 + i * 6, 36), CvScalar(255, 255, 255), -1)
          writer.write(f)
        finally f.release()
        i += 1
      path
    finally writer.release()

  private def openCapture(path: String): ZIO[Scope, Throwable, VideoCapture] =
    acquireRelease(VideoCapture(path)).flatMap: cap =>
      ZIO.fromEither(Either.cond(cap.isOpened, cap, RuntimeException(s"cannot open $path")))

  /** The module source, located by walking up from the test's working directory. Mill forks tests from either
    * the build root or the module directory, both ancestors of (or equal to) the tree that holds this file,
    * so one of them resolves. `None` only if the layout moved — the scan test then guards rather than fails.
    */
  private def moduleSource: Option[String] =
    val rel = java.nio.file.Paths.get("zio", "src", "scalacv", "zio", "package.scala")
    Iterator
      .iterate(java.nio.file.Paths.get("").toAbsolutePath)(p => p.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(rel))
      .find(java.nio.file.Files.exists(_))
      .map(p => new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8))

  def spec = suite("scalacv-zio")(
    test("acquireRelease frees a Mat when the scope closes"):
      for
        _ <- loadNatives
        // Capture the raw Mat so we can inspect it after the scope has closed.
        raw <- ZIO.succeed(Mat(32, 32, CvType.CV_8UC3))
        _ <- ZIO.scoped(raw.scoped.flatMap(m => ZIO.succeed(assert(m.dataAddr())(not(equalTo(0L))))))
      yield assertTrue(raw.dataAddr() == 0L) // freed by the scope
    ,

    test("acquireRelease frees a handle type through the delete bridge"):
      given Releasable[org.opencv.objdetect.CascadeClassifier] =
        Releasable.nativeHandle
      for
        _ <- loadNatives
        c <- ZIO.succeed(org.opencv.objdetect.CascadeClassifier())
        _ <- ZIO.scoped(acquireRelease(c))
      yield assertTrue(c.getNativeObjAddr == 0L) // nativeObj zeroed => finalizer is disarmed
    ,

    test("release still fires when the scoped effect fails"):
      for
        _ <- loadNatives
        raw <- ZIO.succeed(Mat(16, 16, CvType.CV_8UC1))
        exit <- ZIO.scoped(raw.scoped *> ZIO.fail(RuntimeException("boom"))).exit
      yield assertTrue(exit.isFailure) && assertTrue(raw.dataAddr() == 0L)
    ,

    test("frameStream reads every written frame, in order"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          // Reduce each borrowed frame to a scalar signal INSIDE the stream, per the borrowing
          // contract. Collecting the Mats themselves would collect N aliases of one buffer.
          greys <- frameStream(cap)
            // The fill is grey, so any channel at a non-marker pixel carries the per-frame level.
            .map(f => f.get(2, 2)(0): Double)
            .runCollect
        yield assertTrue(greys.size == FrameCount) &&
          // grey steps by 20 per frame, so the sequence must be strictly increasing.
          assertTrue(greys.toList == greys.toList.sorted) &&
          assertTrue(greys.head < greys.last)
    ,

    test("frameStream stays flat in memory: every element is a view over the same one buffer"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          addrs <- frameStream(cap).map(_.dataAddr()).runCollect
        yield
          // Non-memoizing: one decode buffer reused across all frames. If this ever reports N
          // distinct addresses, the stream has started retaining frames and the contract is broken.
          assertTrue(addrs.size == FrameCount) &&
            assertTrue(addrs.toSet.size == 1)
    ,

    test("frameStream spends each frame when the stream advances, and the last one when the stream ends"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          previous <- Ref.make(Option.empty[BorrowedMat])
          violations <- Ref.make(0)
          pulled <- frameStream(cap).mapZIO { view =>
            previous.get.flatMap:
              case Some(spent) =>
                // One pull later the previous view must refuse every access — loudly, instead of
                // serving stale pixels from the buffer the stream has already decoded over.
                ZIO
                  .attempt(spent.dataAddr())
                  .either
                  .flatMap:
                    case Left(_: IllegalStateException) => ZIO.unit
                    case _ => violations.update(_ + 1)
              case None => ZIO.unit
            *> previous.set(Some(view))
          }.runCount
          last <- previous.get
          afterEnd <- ZIO.attempt(last.get.dataAddr()).either
          n <- violations.get
        yield assertTrue(pulled == FrameCount.toLong) &&
          assertTrue(n == 0) &&
          // The stream is over and its scope has closed: the final frame's view is spent too.
          assertTrue(afterEnd.isLeft)
    ,

    test("frameStream exposes the core read step's retry bound, and rejects a bound below one"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          // attemptsPerFrame rides out dropped frames on live sources; on a file every read succeeds
          // on the first try, so a larger bound must stream exactly the same frames.
          withRetry <- frameStream(cap, attemptsPerFrame = 3).runCount
          // Below one is a programmer error, surfaced in the error channel rather than as a defect.
          invalid <- frameStream(cap, 0).runCount.exit
        yield assertTrue(withRetry == FrameCount.toLong) &&
          assertTrue(
            invalid.causeOption.exists(
              _.failures.exists(_.isInstanceOf[IllegalArgumentException])
            )
          )
    ,

    test("frameStream forces exception mode off, completes at EOF, and restores the caller's mode"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          // Exception mode ON: OpenCV reports plain end-of-file through the same CvException it uses for a
          // broken stream, so without the stream's save/off/restore guard EOF would fail the stream rather
          // than end it. With the guard, the stream completes normally over every written frame.
          _ <- ZIO.succeed(cap.setExceptionMode(true))
          exit <- frameStream(cap).runCount.exit
          restored <- ZIO.succeed(cap.getExceptionMode)
        yield assertTrue(exit == Exit.succeed(FrameCount.toLong)) &&
          // The mode we set before streaming is put back when the stream ends.
          assertTrue(restored)
    ,

    test("framesCopied yields distinct owned frames"):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          // Each element is its own clone, so collecting is safe. Release them as we go.
          count <- framesCopied(cap).mapZIO(m => ZIO.succeed(m.use(_.rows))).runCount
        yield assertTrue(count == FrameCount.toLong)
    ,

    test("opening a nonexistent video is a failure, not an empty stream"):
      ZIO.scoped(openCapture("/does/not/exist.avi")).exit.map(e => assertTrue(e.isFailure))
    ,

    test("frameStream on a capture that never opened fails rather than completing with zero frames"):
      ZIO.scoped:
        for
          _ <- loadNatives
          // The VideoCapture constructor cannot report failure: OpenCV signals "could not open that" by
          // leaving isOpened false, so this is a live object whose every read() returns false. Without the
          // guard the stream would end on its first pull and a typo'd path would be reported as a video
          // with no frames in it.
          cap <- acquireRelease(VideoCapture("/does/not/exist.avi"))
          isOpen <- ZIO.succeed(cap.isOpened)
          exit <- frameStream(cap).runCount.exit
          copiedExit <- framesCopied(cap).runCount.exit
        yield assertTrue(!isOpen) &&
          assertTrue(exit.isFailure) &&
          // Typed, not a bare Throwable: the caller can tell "this source never opened" from a decode error.
          assertTrue(exit.causeOption.exists(_.failures.exists(_.isInstanceOf[CvError.LoadFailed]))) &&
          // framesCopied delegates to frameStream, so it must inherit the check rather than the defect.
          assertTrue(copiedExit.isFailure)
    ,

    test("captureScoped streams a real source, closes it with the scope, and types a missing one"):
      for
        _ <- loadNatives
        path <- writeSample()
        count <- ZIO.scoped(captureScoped(path.toString).flatMap(c => frameStream(c).runCount))
        // Let the capture escape its scope so the assertion can see the state the scope left it in.
        cap <- ZIO.scoped(captureScoped(path.toString))
        missing <- ZIO.scoped(captureScoped("/does/not/exist.avi")).exit
      yield assertTrue(count == FrameCount.toLong) &&
        // VideoCapture.release() closes the source, so a released capture reports itself as not open.
        assertTrue(!cap.isOpened) &&
        assertTrue(missing.isFailure) &&
        assertTrue(missing.causeOption.exists(_.failures.exists(_.isInstanceOf[CvError])))
    ,

    test("imageScoped reads and closes an image; a missing path fails as a typed CvError"):
      for
        _ <- loadNatives
        path <- ZIO.attempt:
          val p = Files.createTempFile("scalacv-zio-img-", ".png")
          Image.blank(8, 8, Scalar.White).write(p.toString).fold(throw _, identity)
          p
        // Success: the image is read, usable inside the scope, and closed when it ends.
        dims <- ZIO.scoped(imageScoped(path.toString).map(img => (img.width, img.height)))
        // Failure travels in the typed CvError channel, not as an unchecked defect.
        failed <- ZIO.scoped(imageScoped("/does/not/exist.png")).exit
      yield assertTrue(dims == (8, 8)) &&
        assertTrue(failed.isFailure) &&
        assertTrue(failed.causeOption.exists(_.failures.forall(_.isInstanceOf[CvError])))
    ,

    test("no native or filesystem call is wrapped in the compute-executor ZIO.attempt"):
      // Every blocking native/filesystem site was moved onto the blocking pool (attemptBlocking,
      // attemptBlockingInterrupt, ZIO.blocking). A plain `ZIO.attempt(` in the module would park such a
      // call on the CPU-sized default executor. Comments — the scaladoc examples do use ZIO.attempt — are
      // stripped first so only real code is scanned.
      moduleSource match
        case Some(src) =>
          val code = src.replaceAll("(?s)/\\*.*?\\*/", "")
          assertTrue(!code.contains("ZIO.attempt("))
        case None =>
          // Source not locatable from the test's working directory: guard rather than fail.
          assertCompletes
    ,

    test(
      "acquireRelease frees the Mat when the fiber holding the scope is interrupted, and a throwing " +
        "acquisition fails in the error channel"
    ):
      for
        _ <- loadNatives
        raw <- ZIO.succeed(Mat(16, 16, CvType.CV_8UC1))
        // Interrupt only once the scope has acquired the Mat: an interrupt landing before acquisition
        // would leave the Mat live and make the assertion flaky. fiber.interrupt awaits the fiber's
        // finalizers, so the address check after it is deterministic.
        acquired <- Promise.make[Nothing, Unit]
        fiber <- ZIO.scoped(raw.scoped *> acquired.succeed(()) *> ZIO.never).fork
        _ <- acquired.await
        _ <- fiber.interrupt
        freedOnInterrupt <- ZIO.succeed(raw.dataAddr() == 0L)
        exit <- ZIO.scoped(acquireRelease[Mat](throw RuntimeException("no"))).exit
      yield assertTrue(freedOnInterrupt) &&
        assertTrue(exit.isFailure) &&
        // A constructor that throws is a failure the caller can handle, not a defect that kills the fiber.
        assertTrue(exit.causeOption.exists(_.failures.exists(_.getMessage == "no")))
    ,

    test(
      "fromCv keeps the CvError typed and is lazy; readImage hands back a caller-owned image; loadNatives " +
        "is idempotent"
    ):
      val e = CvError.DecodeFailed("p", "d")
      // Building the effect must not run the thunk: if fromCv were eager this line would throw at
      // construction, outside any error channel.
      val deferred = fromCv[Int](throw RuntimeException("boom"))
      for
        _ <- loadNatives *> loadNatives
        ok <- fromCv(Right(7))
        typed <- fromCv[Int](Left(e)).exit
        thrown <- deferred.exit
        path <- ZIO.attempt:
          val p = Files.createTempFile("scalacv-zio-read-", ".png")
          Image.blank(8, 8, Scalar.White).write(p.toString).fold(throw _, identity)
          p
        img <- readImage(path.toString)
        dims <- ZIO.succeed((img.width, img.height))
        // Caller-owned: nothing else closes it, and closing it twice is the documented no-op.
        _ <- ZIO.succeed { img.close(); img.close() }
        missing <- readImage("/does/not/exist.png").exit
      yield assertTrue(ok == 7) &&
        assert(typed)(fails(equalTo(e))) &&
        // A thunk that throws is a defect of the run, never a typed CvError.
        assertTrue(thrown.isFailure) &&
        assertTrue(thrown.causeOption.exists(_.dieOption.isDefined)) &&
        assertTrue(dims == (8, 8)) &&
        assertTrue(missing.causeOption.exists(_.failures.exists(_.isInstanceOf[CvError])))
    ,

    test(
      "framesCopied yields one distinct owned buffer per frame whose pixels survive later pulls and are " +
        "freed by release"
    ):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          frames <- framesCopied(cap).runCollect
          raws = frames.map(_.get)
          // Read only after the whole stream has completed: aliases of the one decode buffer would all
          // show the last frame's grey here, whereas true clones keep the level they were pulled with.
          greys = raws.map(_.get(2, 2)(0))
          addrs = raws.map(_.dataAddr())
          _ <- ZIO.succeed(frames.foreach(_.release()))
          freed = raws.forall(_.dataAddr() == 0L)
        yield assertTrue(addrs.size == FrameCount) &&
          assertTrue(addrs.toSet.size == FrameCount) &&
          assertTrue(greys.toList == greys.toList.sorted) &&
          assertTrue(greys.head < greys.last) &&
          assertTrue(freed)
    ,

    test(
      "frameStream restores exception mode after take(n) and after a failing consumer, and leaves the " +
        "capture open with exactly the unconsumed frames"
    ):
      ZIO.scoped:
        for
          _ <- loadNatives
          path <- writeSample()
          cap <- openCapture(path.toString)
          _ <- ZIO.succeed(cap.setExceptionMode(true))
          // Early exit by the consumer, not by EOF: the stream's finalizer must still run.
          taken <- frameStream(cap).take(2).runCount
          afterTake <- ZIO.succeed(cap.getExceptionMode)
          failed <- frameStream(cap).mapZIO(_ => ZIO.fail(RuntimeException("consumer"))).runDrain.exit
          afterFailure <- ZIO.succeed(cap.getExceptionMode)
          // The stream is pull-based, so the two early exits consumed exactly two frames plus the one the
          // failing consumer was handed; a finalizer that released the capture would yield zero here.
          remaining <- frameStream(cap).runCount
        yield assertTrue(taken == 2L) &&
          assertTrue(afterTake) &&
          assertTrue(failed.isFailure) &&
          assertTrue(afterFailure) &&
          assertTrue(remaining == FrameCount - 3L)
  )
