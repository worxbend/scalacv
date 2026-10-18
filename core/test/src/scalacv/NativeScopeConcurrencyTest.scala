package scalacv

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import org.opencv.core.Mat

/** The one concurrency promise a scope makes: *registering* handles with it from several threads is safe,
  * even though native access to any one handle stays single-owner. Before the registry was synchronized,
  * workers copying inside one `Image.reading` block silently dropped most of their copies from the registry,
  * and the block "closed" cleanly while leaving them allocated — no exception, just a native leak.
  */
class NativeScopeConcurrencyTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Threads = 4
  private val CopiesPerThread = 5000

  /** Runs `body` on `count` threads, joins them all, and rethrows the first worker failure on this thread. */
  private def onThreads(count: Int)(body: Int => Unit): Unit =
    val failures = new ConcurrentLinkedQueue[Throwable]
    val workers = (0 until count).map: i =>
      val worker = new Thread(() =>
        try body(i)
        catch case e: Throwable => failures.add(e): Unit
      )
      worker.start()
      worker
    workers.foreach(_.join())
    failures.asScala.headOption.foreach(throw _)

  test("copies made on several threads inside Image.reading are all released when the block returns"):
    val png = Files.createTempFile("scalacv-scope-concurrency-", ".png")
    val kept = new ConcurrentLinkedQueue[Mat]
    try
      Image.blank(8, 8).write(png.toString).fold(e => fail(e.getMessage), identity)
      Image
        .reading(png.toString): root =>
          val roots = Vector.fill(Threads)(root.copy)
          onThreads(Threads): i =>
            for _ <- 0 until CopiesPerThread do kept.add(roots(i).copy.mat): Unit
        .fold(e => fail(e.getMessage), identity)
      assertEquals(kept.size, Threads * CopiesPerThread)
      val leaked = kept.asScala.count(!_.empty())
      assertEquals(
        leaked,
        0,
        s"$leaked of ${kept.size} copies made inside the scope are still allocated after it closed"
      )
    finally
      kept.asScala.foreach(_.release()) // the probe's own cleanup, so a red run does not leak 20k Mats
      Files.deleteIfExists(png): Unit

  test("an explicit Image.Scope reports zero active handles after concurrent own/forget"):
    val scope = new Image.Scope
    onThreads(Threads): _ =>
      for _ <- 0 until 2000 do scope.own(Image.blank(8, 8)).gray.invert.close()
    scope.close()
    assertEquals(scope.activeCount, 0)
