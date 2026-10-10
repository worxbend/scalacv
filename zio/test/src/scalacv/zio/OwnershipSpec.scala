package scalacv.zio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import _root_.zio.*
import _root_.zio.test.*
import org.opencv.core.{CvType, Mat, Scalar}
import org.opencv.videoio.VideoCapture
import scalacv.*

object OwnershipSpec extends ZIOSpecDefault:
  private final class Releases extends Releasable[Mat]:
    val mats = new ConcurrentLinkedQueue[Mat]()
    val threads = new ConcurrentLinkedQueue[String]()
    val count = new AtomicInteger()
    def release(mat: Mat): Unit =
      mats.add(mat)
      threads.add(Thread.currentThread().getName)
      count.incrementAndGet()
      mat.release()
    def allFreed: Boolean =
      import scala.jdk.CollectionConverters.*
      mats.asScala.forall(_.empty())
    def allBlocking: Boolean =
      import scala.jdk.CollectionConverters.*
      threads.asScala.forall(_.contains("blocking"))
    override def toString: String = s"Releases(threads=$threads)"

  // Real native decode buffer / clone path, deterministic source and injected decoder fault.
  private final class Capture(failAt: Int = -1) extends VideoCapture:
    private var reads = 0
    private var mode = true
    val restoredOn = new java.util.concurrent.atomic.AtomicReference[String]("")
    override def isOpened: Boolean = true
    override def getExceptionMode: Boolean = mode
    override def setExceptionMode(value: Boolean): Unit =
      mode = value
      if value then restoredOn.set(Thread.currentThread().getName)
    override def read(target: Mat): Boolean =
      reads += 1
      if reads == failAt then throw IllegalStateException("decode")
      if reads > 4 then false
      else
        target.create(8, 8, CvType.CV_8UC1)
        target.setTo(Scalar(reads.toDouble))
        true

  private def capture(failAt: Int = -1): ZIO[Scope, Throwable, Capture] =
    acquireRelease(new Capture(failAt))

  def spec = suite("zio native ownership")(
    test("useZIO waits through suspension and releases exactly once on the blocking executor") {
      val releases = new Releases
      for
        _ <- loadNatives
        owner <- ZIO.attemptBlocking(Managed(Mat(8, 8, CvType.CV_8UC1))(using releases))
        rows <- owner.useZIO(m => ZIO.yieldNow *> ZIO.attemptBlocking(m.rows()))
      yield assertTrue(
        rows == 8,
        owner.isReleased,
        releases.count.get == 1,
        releases.allFreed,
        releases.allBlocking
      )
    },
    test("managedScoped preserves the original release strategy and effect lifetime") {
      val releases = new Releases
      for
        _ <- loadNatives
        rows <- ZIO.scoped {
          managedScoped(ZIO.attemptBlocking(Managed(Mat(8, 8, CvType.CV_8UC1))(using releases)))
            .flatMap(m => ZIO.yieldNow *> ZIO.attemptBlocking(m.rows()))
        }
      yield assertTrue(rows == 8, releases.count.get == 1, releases.allFreed, releases.allBlocking)
    },
    test("body failure and cleanup defect both survive, cleanup runs once") {
      val count = new AtomicInteger()
      val cleanup = RuntimeException("cleanup")
      val r = new Releasable[String]:
        def release(value: String): Unit =
          count.incrementAndGet()
          throw cleanup
      useManaged(ZIO.succeed(Managed("value")(using r)))(_ => ZIO.yieldNow *> ZIO.fail("body")).exit.map {
        exit =>
          assertTrue(
            count.get == 1,
            exit.causeOption.exists(_.failures.contains("body")),
            exit.causeOption.exists(_.defects.contains(cleanup))
          )
      }
    },
    test("interruption during producer handoff cannot abandon an acquired clone") {
      val releases = new Releases
      for
        _ <- loadNatives
        produced <- Promise.make[Nothing, Unit]
        deliver <- Promise.make[Nothing, Unit]
        fiber <- useManaged {
          ZIO
            .attemptBlocking(Managed(Mat(8, 8, CvType.CV_8UC1))(using releases))
            .flatMap(m => produced.succeed(()) *> deliver.await.as(m))
        }(_ => ZIO.never).fork
        _ <- produced.await
        _ <- fiber.interruptFork
        _ <- deliver.succeed(())
        exit <- fiber.await
      yield assertTrue(exit.isInterrupted, releases.count.get == 1, releases.allFreed, releases.allBlocking)
    },
    test("processFrames reduces live clones and releases before discarded or buffered results") {
      val releases = new Releases
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture()
          rows <- processFrames(cap)(m => ZIO.yieldNow *> ZIO.attemptBlocking(m.rows()))(using releases)
            .tap(_ => ZIO.succeed(Predef.assert(releases.allFreed)))
            .buffer(2)
            .runCollect
          cap2 <- capture()
          _ <- processFrames(cap2)(m => ZIO.attemptBlocking(m.rows()))(using releases)
            .filter(_ => false)
            .runDrain
        yield assertTrue(
          rows.toList == List(8, 8, 8, 8),
          releases.count.get == 8,
          releases.allFreed,
          releases.allBlocking,
          cap.getExceptionMode,
          cap2.getExceptionMode,
          cap.restoredOn.get.contains("blocking"),
          cap2.restoredOn.get.contains("blocking")
        )
      }
    },
    test("processFrames releases on consumer failure and callback construction defect") {
      val releases = new Releases
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture()
          failed <- processFrames(cap)(_ => ZIO.yieldNow *> ZIO.fail(RuntimeException("body")))(using
            releases
          ).runDrain.exit
          cap2 <- capture()
          died <- processFrames(cap2)(_ => throw IllegalStateException("construction"))(using
            releases
          ).runDrain.exit
        yield assertTrue(
          failed.isFailure,
          died.causeOption.exists(_.defects.nonEmpty),
          releases.count.get == 2,
          releases.allFreed,
          cap.getExceptionMode,
          cap2.getExceptionMode
        )
      }
    },
    test("decoder failure after a completed frame leaves no abandoned clone") {
      val releases = new Releases
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture(failAt = 2)
          exit <- processFrames(cap)(m => ZIO.attemptBlocking(m.rows()))(using releases).runCollect.exit
        yield assertTrue(exit.isFailure, releases.count.get == 1, releases.allFreed, cap.getExceptionMode)
      }
    },
    test("callback cancellation and early take release the clone and restore the source") {
      val releases = new Releases
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture()
          entered <- Promise.make[Nothing, Unit]
          fiber <- processFrames(cap)(_ => entered.succeed(()) *> ZIO.never)(using releases).runDrain.fork
          _ <- entered.await
          exit <- fiber.interrupt
          cap2 <- capture()
          values <- processFrames(cap2)(m => ZIO.attemptBlocking(m.rows()))(using releases).take(1).runCollect
        yield assertTrue(
          exit.isInterrupted,
          values.toList == List(8),
          releases.count.get == 2,
          releases.allFreed,
          releases.allBlocking,
          cap.getExceptionMode,
          cap2.getExceptionMode,
          cap.restoredOn.get.contains("blocking"),
          cap2.restoredOn.get.contains("blocking")
        )
      }
    },
    test("interruption after clone allocation but before callback adoption releases that clone") {
      val releases = new Releases
      val allocated = new java.util.concurrent.CountDownLatch(1)
      val deliver = new java.util.concurrent.CountDownLatch(1)
      val entered = new AtomicInteger()
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture()
          fiber <- processFramesWith(cap, 1) { view =>
            val owner = Managed(view.clone())(using releases)
            allocated.countDown()
            // Bounded only to keep a broken test from hanging the suite.
            if !deliver.await(10, java.util.concurrent.TimeUnit.SECONDS) then
              owner.release()
              throw IllegalStateException("handoff test timed out")
            owner
          }(m => ZIO.succeed(entered.incrementAndGet()) *> ZIO.attemptBlocking(m.rows())).runDrain.fork
          ready <- ZIO.attemptBlocking(allocated.await(10, java.util.concurrent.TimeUnit.SECONDS))
          _ <- fiber.interruptFork
          _ <- ZIO.succeed(deliver.countDown())
          exit <- fiber.await
        yield assertTrue(
          ready,
          exit.isInterrupted,
          entered.get == 0,
          releases.count.get == 1,
          releases.allFreed,
          releases.allBlocking,
          cap.getExceptionMode
        )
      }
    },
    test("buffer cancellation discards only reduced results, never allocated clones") {
      val releases = new Releases
      val acquired = new ConcurrentLinkedQueue[Mat]()
      ZIO.scoped {
        for
          _ <- loadNatives
          cap <- capture()
          result <- processFramesWith(cap, 1) { view =>
            val raw = view.clone()
            acquired.add(raw)
            Managed(raw)(using releases)
          }(m => ZIO.yieldNow *> ZIO.attemptBlocking(m.rows())).buffer(2).take(1).runCollect
        yield
          import scala.jdk.CollectionConverters.*
          assertTrue(
            result.toList == List(8),
            acquired.size() >= 1,
            acquired.size() == releases.count.get,
            acquired.asScala.forall(_.empty()),
            releases.allBlocking
          )
      }
    },
    test("cleanup waits uninterruptibly and a cleanup defect survives cancellation") {
      val started = new java.util.concurrent.CountDownLatch(1)
      val finish = new java.util.concurrent.CountDownLatch(1)
      val cleanup = RuntimeException("interrupted cleanup")
      val releases = new AtomicInteger()
      val r = new Releasable[String]:
        def release(value: String): Unit =
          releases.incrementAndGet()
          started.countDown()
          if !finish.await(10, java.util.concurrent.TimeUnit.SECONDS) then
            throw IllegalStateException("cleanup test timed out")
          throw cleanup
      for
        entered <- Promise.make[Nothing, Unit]
        fiber <- useManaged(ZIO.succeed(Managed("value")(using r)))(_ =>
          entered.succeed(()) *> ZIO.never
        ).fork
        _ <- entered.await
        _ <- fiber.interruptFork
        ready <- ZIO.attemptBlocking(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        pending <- fiber.poll
        _ <- fiber.interruptFork
        _ <- ZIO.succeed(finish.countDown())
        exit <- fiber.await
      yield assertTrue(
        ready,
        pending.isEmpty,
        releases.get == 1,
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(cleanup))
      )
    },
    test("generic acquisition and Mat adoption finalize on the blocking executor after yielding") {
      val releases = new Releases
      for
        _ <- loadNatives
        _ <- ZIO.scoped(acquireRelease(Mat(8, 8, CvType.CV_8UC1))(using releases) *> ZIO.yieldNow)
        raw <- ZIO.attemptBlocking(Mat(8, 8, CvType.CV_8UC1))
        _ <- ZIO.scoped(raw.scoped(using releases) *> ZIO.yieldNow)
      yield assertTrue(releases.count.get == 2, releases.allFreed, releases.allBlocking)
    }
  )
