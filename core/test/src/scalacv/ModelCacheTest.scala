package scalacv

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.util.concurrent.{Callable, CountDownLatch, Executors, FutureTask, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import scala.jdk.CollectionConverters.*
import scala.util.Using

class ModelCacheTest extends munit.FunSuite:
  private def withDirectory[A](body: Path => A): A =
    val dir = Files.createTempDirectory("scalacv-model-cache-")
    try body(dir)
    finally
      Using.resource(Files.walk(dir))(
        _.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      )

  private def hash(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString

  private def source(dir: Path, content: String): ModelSpec =
    val bytes = content.getBytes(UTF_8)
    val file = Files.createTempFile(dir, "source-", ".bin")
    Files.write(file, bytes)
    ModelSpec("model.bin", Seq(file.toUri.toString), hash(bytes), Some(bytes.length.toLong))

  private def withServer[A](
      bytes: Array[Byte],
      firstChunkBytes: Int = 0,
      mirrorRequests: AtomicInteger = new AtomicInteger
  )(
      body: (String, AtomicInteger, CountDownLatch, CountDownLatch) => A
  ): A =
    val requests = new AtomicInteger
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val executor = Executors.newCachedThreadPool()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    try
      require(firstChunkBytes >= 0 && firstChunkBytes < bytes.length)
      server.setExecutor(executor)
      server.createContext(
        "/model",
        exchange =>
          requests.incrementAndGet()
          try
            if firstChunkBytes > 0 then
              exchange.sendResponseHeaders(200, bytes.length.toLong)
              exchange.getResponseBody.write(bytes, 0, firstChunkBytes)
              exchange.getResponseBody.flush()
            entered.countDown()
            if !release.await(10, TimeUnit.SECONDS) then
              throw new java.io.IOException("test response gate timed out")
            if firstChunkBytes == 0 then exchange.sendResponseHeaders(200, bytes.length.toLong)
            exchange.getResponseBody.write(bytes, firstChunkBytes, bytes.length - firstChunkBytes)
          finally exchange.close()
      )
      server.createContext(
        "/mirror",
        exchange =>
          mirrorRequests.incrementAndGet()
          try
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
          finally exchange.close()
      )
      server.start()
      body(s"http://127.0.0.1:${server.getAddress.getPort}/model", requests, entered, release)
    finally
      release.countDown()
      server.stop(0)
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))

  test("simultaneous cached misses download once and all callers receive verified bytes"):
    withDirectory: dir =>
      val bytes = "one download, many callers".getBytes(UTF_8)
      withServer(bytes): (url, requests, entered, release) =>
        val pool = Executors.newFixedThreadPool(12)
        val ready = new CountDownLatch(12)
        val start = new CountDownLatch(1)
        val spec = ModelSpec("model.bin", Seq(url), hash(bytes))
        try
          val jobs = (0 until 12).map: _ =>
            pool.submit(new Callable[Either[CvError, Path]]:
              def call(): Either[CvError, Path] =
                ready.countDown()
                if !start.await(10, TimeUnit.SECONDS) then throw new IllegalStateException("start timed out")
                Models.fetchCached(spec, dir.resolve("cache")))
          assert(ready.await(10, TimeUnit.SECONDS))
          start.countDown()
          assert(entered.await(10, TimeUnit.SECONDS))
          // Keep the first download in flight while the simultaneous requests contend.
          Thread.sleep(200)
          release.countDown()
          val results = jobs.map(_.get(15, TimeUnit.SECONDS))
          results.foreach(result => assert(result.isRight, result.toString))
          val paths = results.map(_.toOption.get)
          assertEquals(paths.distinct.size, 1)
          assertEquals(Files.readAllBytes(paths.head).toSeq, bytes.toSeq)
          println(s"ModelCache counting server: 12 simultaneous misses, ${requests.get()} downloads")
          assertEquals(requests.get(), 1)
          assertEquals(Models.fetchCached(spec, dir.resolve("cache")), results.head)
          assertEquals(requests.get(), 1)
        finally
          start.countDown()
          release.countDown()
          pool.shutdownNow()
          assert(pool.awaitTermination(10, TimeUnit.SECONDS))

  private def symbolicLink(link: Path, target: Path): Path =
    try Files.createSymbolicLink(link, target)
    catch
      case _: UnsupportedOperationException =>
        assume(false, "symbolic links are unsupported by this filesystem")
        link
      case error: java.nio.file.FileSystemException if Option(error.getReason).exists(reason =>
            val lower = reason.toLowerCase(java.util.Locale.ROOT)
            lower.contains("not supported") || lower.contains("privilege is not held")
          ) =>
        assume(false, s"symbolic links are unavailable: ${error.getReason}")
        link

  private def contendingFetches(
      first: ModelSpec,
      firstRoot: Path,
      second: ModelSpec,
      secondRoot: Path,
      entered: CountDownLatch,
      release: CountDownLatch
  ): (Path, Path) =
    val ownerJob = new FutureTask[Either[CvError, Path]](() => Models.fetchCached(first, firstRoot))
    val waiterJob = new FutureTask[Either[CvError, Path]](() => Models.fetchCached(second, secondRoot))
    val owner = new Thread(ownerJob, "model-cache-owner")
    val waiter = new Thread(waiterJob, "model-cache-waiter")
    owner.start()
    try
      val downloading = entered.await(10, TimeUnit.SECONDS)
      assert(downloading, "first download did not reach the response gate")
      waiter.start()
      // Observe actual contention, not just thread startup; an erroneous overlapping file lock can
      // complete with Left, which must fail here rather than masquerade as a successfully queued caller.
      awaitCondition(
        waiterJob.isDone || (waiter.getState == Thread.State.WAITING &&
          waiter.getStackTrace.exists(_.getMethodName == "lockInterruptibly"))
      )
      if waiterJob.isDone then
        fail(s"second caller escaped the held stripe: ${waiterJob.get(1, TimeUnit.SECONDS)}")
      release.countDown()
      val a = ownerJob.get(15, TimeUnit.SECONDS)
      val b = waiterJob.get(15, TimeUnit.SECONDS)
      assert(a.isRight, a.toString)
      assert(b.isRight, b.toString)
      (a.toOption.get, b.toOption.get)
    finally
      release.countDown()
      owner.interrupt()
      waiter.interrupt()
      owner.join(10000)
      waiter.join(10000)
      assert(!owner.isAlive && !waiter.isAlive, "cache callers did not terminate")

  test("distinct digests sharing a persistent lock stripe both succeed under contention"):
    withDirectory: dir =>
      // Sixty-five distinct candidates guarantee a pair in the 64 persistent stripes.
      val pair = (0 until 65)
        .map(i => s"stripe-collision-$i".getBytes(UTF_8))
        .groupBy(bytes => Math.floorMod(hash(bytes).hashCode, 64))
        .values
        .find(_.size > 1)
        .get
        .take(2)
      val firstBytes = pair.head
      val secondBytes = pair(1)
      val firstDigest = hash(firstBytes)
      val secondDigest = hash(secondBytes)
      assertNotEquals(firstDigest, secondDigest)
      assertEquals(Math.floorMod(firstDigest.hashCode, 64), Math.floorMod(secondDigest.hashCode, 64))
      val second = source(dir, new String(secondBytes, UTF_8))
      val root = dir.resolve("cache")
      withServer(firstBytes): (url, requests, entered, release) =>
        val first = ModelSpec("model.bin", Seq(url), firstDigest)
        val (a, b) = contendingFetches(first, root, second, root, entered, release)
        assertNotEquals(a, b)
        assertEquals(Files.readAllBytes(a).toSeq, firstBytes.toSeq)
        assertEquals(Files.readAllBytes(b).toSeq, secondBytes.toSeq)
        assertEquals(requests.get(), 1)
        assertEquals(Using.resource(Files.list(root.resolve(".locks")))(_.count()), 1L)

  test("real and symlink cache roots coalesce concurrent misses into one download"):
    withDirectory: dir =>
      val root = Files.createDirectory(dir.resolve("cache"))
      val alias = symbolicLink(dir.resolve("alias"), root)
      val bytes = "same cache through two root paths".getBytes(UTF_8)
      withServer(bytes): (url, requests, entered, release) =>
        val spec = ModelSpec("model.bin", Seq(url), hash(bytes))
        val (a, b) = contendingFetches(spec, root, spec, alias, entered, release)
        assertEquals(a, root.resolve("sha256").resolve(spec.sha256.get).resolve(spec.fileName))
        assertEquals(b, alias.resolve("sha256").resolve(spec.sha256.get).resolve(spec.fileName))
        assert(Files.isSameFile(a, b))
        assertEquals(Files.readAllBytes(a).toSeq, bytes.toSeq)
        assertEquals(Files.readAllBytes(b).toSeq, bytes.toSeq)
        assertEquals(requests.get(), 1)
        assertEquals(Using.resource(Files.list(root.resolve(".locks")))(_.count()), 1L)

  test("an already interrupted caller cannot create a cache or publish a verified temporary file"):
    withDirectory: dir =>
      val spec = source(dir, "bytes")
      val root = dir.resolve("cache")
      val (caller, callerResult) = cancelling:
        Thread.currentThread().interrupt()
        Models.fetchCached(spec, root)
        ()
      caller.join(10000)
      assertEquals(callerResult.get(), "interrupted:true")
      assert(!Files.exists(root))
      val temporary = Files.writeString(dir.resolve("download.part"), "new")
      val target = Files.writeString(dir.resolve("model.bin"), "old")
      val (publisher, publisherResult) = cancelling:
        Thread.currentThread().interrupt()
        ModelCache.publish(temporary, target)
        ()
      publisher.join(10000)
      assertEquals(publisherResult.get(), "interrupted:true")
      assertEquals(Files.readString(target), "old")
      assert(Files.exists(temporary))

  test("lock artifacts stay bounded across digest churn and filenames cannot collide with them"):
    withDirectory: dir =>
      val root = dir.resolve("cache")
      for i <- 0 until 96 do
        val original = source(dir, s"revision-$i")
        val spec = ModelSpec(".lock", original.urls, original.sha256.get)
        assert(Models.fetchCached(spec, root).isRight)
      assert(Using.resource(Files.list(root.resolve(".locks")))(_.count()) <= 64L)

  test("publication fails closed when atomic move is unsupported, preserving the old target"):
    withDirectory: dir =>
      val temporary = Files.writeString(dir.resolve("download.part"), "verified new bytes")
      val uri = java.net.URI.create("jar:" + dir.resolve("cache.zip").toUri.toString)
      Using.resource(java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))): zip =>
        val target = Files.writeString(zip.getPath("/model.bin"), "old bytes")
        intercept[java.nio.file.AtomicMoveNotSupportedException](ModelCache.publish(temporary, target))
        assertEquals(Files.readString(target), "old bytes")
        assertEquals(Files.readString(temporary), "verified new bytes")

  test("publication atomically replaces an existing target on the same filesystem"):
    withDirectory: dir =>
      val temporary = Files.writeString(dir.resolve("download.part"), "verified new bytes")
      val target = Files.writeString(dir.resolve("model.bin"), "old bytes")
      assertEquals(ModelCache.publish(temporary, target), target)
      assertEquals(Files.readString(target), "verified new bytes")
      assert(!Files.exists(temporary))

  private def awaitCondition(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while !condition && System.nanoTime() < deadline do Thread.sleep(10)
    assert(condition, "condition timed out")

  private def child(dir: Path, args: String*): Process =
    val executable = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val classpath = (Seq(System.getProperty("java.class.path")) ++
      Seq(getClass, classOf[ModelSpec], classOf[scala.Option[?]], classOf[scala.deriving.Mirror])
        .map(cls => Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toString))
      .mkString(java.io.File.pathSeparator)
    new ProcessBuilder((Seq(executable, "-cp", classpath, "scalacv.ModelCacheWorker") ++ args)*)
      .redirectErrorStream(true)
      .redirectOutput(dir.resolve("child-" + java.util.UUID.randomUUID() + ".log").toFile)
      .start()

  private def stop(process: Process): Unit =
    if process.isAlive then process.destroyForcibly()
    assert(process.waitFor(10, TimeUnit.SECONDS))

  private def cancelling(body: => Unit): (Thread, java.util.concurrent.atomic.AtomicReference[String]) =
    val outcome = new java.util.concurrent.atomic.AtomicReference[String]("not completed")
    val thread = new Thread(() =>
      try
        body
        outcome.set("returned normally")
      catch
        case _: InterruptedException => outcome.set(s"interrupted:${Thread.currentThread().isInterrupted}")
        case error: Throwable => outcome.set(error.toString)
    )
    thread.start()
    (thread, outcome)

  private def interrupted(
      thread: Thread,
      outcome: java.util.concurrent.atomic.AtomicReference[String]
  ): Unit =
    thread.interrupt()
    thread.join(10000)
    assert(!thread.isAlive)
    assertEquals(outcome.get(), "interrupted:true")

  test("independent JVMs coalesce simultaneous misses through persistent file locks"):
    withDirectory: dir =>
      val bytes = "across processes".getBytes(UTF_8)
      withServer(bytes): (url, requests, entered, release) =>
        val root = dir.resolve("cache")
        val gate = dir.resolve("start")
        val ready = (0 until 2).map(i => dir.resolve(s"ready-$i"))
        val processes = ready.map(path =>
          child(dir, "fetch", root.toString, url, hash(bytes), path.toString, gate.toString)
        )
        try
          awaitCondition(ready.forall(Files.exists(_)))
          Files.createFile(gate)
          assert(entered.await(10, TimeUnit.SECONDS))
          Thread.sleep(200)
          release.countDown()
          processes.foreach: process =>
            assert(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(process.exitValue(), 0)
          println(s"ModelCache counting server: 2 independent JVMs, ${requests.get()} downloads")
          assertEquals(requests.get(), 1)
          assertEquals(Using.resource(Files.list(root.resolve(".locks")))(_.count()), 1L)
        finally
          release.countDown()
          processes.foreach(stop)

  test("interruption while waiting on another process's file lock propagates with the flag restored"):
    withDirectory: dir =>
      val spec = source(dir, "file-lock cancellation")
      val root = dir.resolve("cache")
      val locks = Files.createDirectories(root.resolve(".locks"))
      val lock = locks.resolve(s"${Math.floorMod(spec.sha256.get.hashCode, 64)}.lock")
      val ready = dir.resolve("ready")
      val process = child(dir, "lock", lock.toString, ready.toString)
      var worker: Option[Thread] = None
      try
        awaitCondition(Files.exists(ready))
        val (thread, outcome) = cancelling { Models.fetchCached(spec, root); () }
        worker = Some(thread)
        awaitCondition(
          thread.getStackTrace.exists(frame =>
            frame.getClassName.contains("FileChannelImpl") && frame.getMethodName == "lock"
          )
        )
        interrupted(thread, outcome)
        assert(Files.exists(lock))
        assert(!Files.exists(root.resolve("sha256")))
      finally
        worker.foreach(_.interrupt())
        stop(process)
        worker.foreach(_.join(10000))
      assert(Models.fetchCached(spec, root).isRight)

  test("interruption cleans a download and a JVM lock waiter without trying a second mirror"):
    withDirectory: dir =>
      val bytes = "cancel me".getBytes(UTF_8)
      val local = source(dir, "cancel me")
      withServer(bytes): (url, requests, entered, release) =>
        val root = dir.resolve("cache")
        val spec = ModelSpec(local.fileName, url +: local.urls, hash(bytes))
        val (owner, ownerResult) = cancelling { Models.fetchCached(spec, root); () }
        var waiter: Option[Thread] = None
        try
          assert(entered.await(10, TimeUnit.SECONDS))
          val (thread, outcome) = cancelling { Models.fetchCached(spec, root); () }
          waiter = Some(thread)
          awaitCondition(thread.getStackTrace.exists(_.getMethodName == "lockInterruptibly"))
          interrupted(thread, outcome)
          interrupted(owner, ownerResult)
          assertEquals(requests.get(), 1)
          assert(!Files.exists(root.resolve("sha256").resolve(spec.sha256.get).resolve(spec.fileName)))
          assertEquals(
            Using.resource(Files.walk(root))(_.iterator.asScala.count(_.toString.endsWith(".part"))),
            0
          )
          assert(Models.fetchCached(local, root).isRight)
        finally
          owner.interrupt()
          waiter.foreach(_.interrupt())
          release.countDown()
          owner.join(10000)
          waiter.foreach(_.join(10000))

  test("post-headers cancellation removes partial bytes, preserves a corrupt target and skips mirrors"):
    withDirectory: dir =>
      val bytes = Array.tabulate[Byte](128 * 1024)(i => (i % 251).toByte)
      val chunkSize = 32 * 1024
      val mirrors = new AtomicInteger
      withServer(bytes, firstChunkBytes = chunkSize, mirrorRequests = mirrors):
        (url, requests, entered, release) =>
          val root = dir.resolve("cache")
          val spec = ModelSpec("model.bin", Seq(url, url.stripSuffix("/model") + "/mirror"), hash(bytes))
          val entry = Files.createDirectories(root.resolve("sha256").resolve(spec.sha256.get))
          val target = Files.writeString(entry.resolve(spec.fileName), "existing corrupt target")
          def parts: List[Path] =
            Using.resource(Files.walk(root))(_.iterator.asScala.filter(_.toString.endsWith(".part")).toList)
          val (caller, outcome) = cancelling { Models.fetchCached(spec, root); () }
          try
            val flushed = entered.await(10, TimeUnit.SECONDS)
            assert(flushed, "server did not flush the first body chunk")
            awaitCondition(parts.exists(path => Files.size(path) > 0) || !caller.isAlive)
            assert(caller.isAlive, s"download completed before cancellation: ${outcome.get()}")
            val partial = parts
            assertEquals(partial.size, 1)
            val partialBytes = Files.readAllBytes(partial.head)
            assert(partialBytes.nonEmpty && partialBytes.length <= chunkSize)
            assertEquals(partialBytes.toSeq, bytes.take(partialBytes.length).toSeq)
            assertEquals(release.getCount, 1L)
            interrupted(caller, outcome)
            assertEquals(requests.get(), 1)
            assertEquals(mirrors.get(), 0)
            assertEquals(parts, Nil)
            assertEquals(Files.readString(target), "existing corrupt target")
            release.countDown()
            assertEquals(Models.fetchCached(spec, root), Right(target))
            assertEquals(Files.readAllBytes(target).toSeq, bytes.toSeq)
            assertEquals(requests.get(), 2)
            assertEquals(mirrors.get(), 0)
            assertEquals(parts, Nil)
          finally
            caller.interrupt()
            release.countDown()
            caller.join(10000)
            assert(!caller.isAlive, "cancelled download caller did not terminate")

  test("every hit rehashes even same-size same-mtime tampering; roots remain separate namespaces"):
    withDirectory: dir =>
      val spec = source(dir, "good bytes")
      val root = dir.resolve("cache")
      val target = Models.fetchCached(spec, root).toOption.get
      val time = Files.getLastModifiedTime(target)
      Files.writeString(target, "evil bytes")
      Files.setLastModifiedTime(target, time)
      assertEquals(Models.fetchCached(spec, root), Right(target))
      assertEquals(Files.readString(target), "good bytes")
      Files.writeString(target, "evil bytes")
      Files.setLastModifiedTime(target, time)
      Files.delete(Path.of(java.net.URI.create(spec.urls.head)))
      assert(Models.fetchCached(spec, root).isLeft)
      assertEquals(Files.readString(target), "evil bytes")
      assert(Models.fetchCached(spec, dir.resolve("other-namespace")).isLeft)
      val parts =
        Using.resource(Files.walk(dir))(_.iterator.asScala.filter(_.toString.endsWith(".part")).toList)
      assertEquals(parts, Nil)

  test("cache rejects symlinked layout components and entries instead of following them"):
    withDirectory: dir =>
      val spec = source(dir, "bytes")
      val outside = Files.createDirectory(dir.resolve("outside"))
      for component <- Seq("sha256", ".locks") do
        val root = Files.createDirectory(dir.resolve("cache-" + component))
        symbolicLink(root.resolve(component), outside)
        assert(Models.fetchCached(spec, root).isLeft)
        assertEquals(Using.resource(Files.list(outside))(_.count()), 0L)
      val root = Files.createDirectory(dir.resolve("entry-cache"))
      val entryDir = Files.createDirectories(root.resolve("sha256").resolve(spec.sha256.get))
      symbolicLink(entryDir.resolve(spec.fileName), Path.of(java.net.URI.create(spec.urls.head)))
      assert(Models.fetchCached(spec, root).isLeft)

  test("cached fetch rejects malformed pins and escaping filenames before creating any directories"):
    withDirectory: dir =>
      val good = source(dir, "bytes")
      val invalid = Seq(ModelSpec.unverified("model.bin", good.urls)) ++
        Seq("../escape", " " + good.sha256.get, good.sha256.get + "\n", "g" * 64, "0" * 63)
          .map(pin => ModelSpec(good.fileName, good.urls, pin)) ++
        Seq("../escape", "nested/model.bin", "/absolute", "..", ".", "x\\y", "C:escape", "x\u0000y")
          .map(name => ModelSpec(name, good.urls, good.sha256.get))
      invalid.zipWithIndex.foreach: (spec, i) =>
        val root = dir.resolve(s"invalid-$i")
        assert(Models.fetchCached(spec, root).isLeft, spec.toString)
        assert(!Files.exists(root))
      val lower = Models.fetchCached(good, dir.resolve("valid"))
      assertEquals(
        Models.fetchCached(
          ModelSpec(good.fileName, good.urls, good.sha256.get.toUpperCase(java.util.Locale.ROOT)),
          dir.resolve("valid")
        ),
        lower
      )

  test("digest directories preserve two revisions with the same filename and legacy fetch stays flat"):
    withDirectory: dir =>
      val first = source(dir, "first revision")
      val second = source(dir, "other revision")
      val root = dir.resolve("cache")
      val a = Models.fetchCached(first, root).toOption.get
      val b = Models.fetchCached(second, root).toOption.get
      assertEquals(a, root.resolve("sha256").resolve(first.sha256.get).resolve(first.fileName))
      assertNotEquals(a, b)
      assertEquals(Files.readString(a), "first revision")
      assertEquals(Files.readString(b), "other revision")
      assertEquals(Models.fetch(first, dir.resolve("legacy")), Right(dir.resolve("legacy/model.bin")))

  /** A loopback server whose every request runs `handle`; `body` receives the bound port. */
  private def withRawServer[A](handle: HttpExchange => Unit)(body: Int => A): A =
    val executor = Executors.newCachedThreadPool()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    try
      server.setExecutor(executor)
      server.createContext(
        "/",
        exchange =>
          try handle(exchange)
          finally exchange.close()
      )
      server.start()
      body(server.getAddress.getPort)
    finally
      server.stop(0)
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))

  test("legacy fetch rejects escaping, absolute and nested file names before touching the filesystem"):
    withDirectory: dir =>
      val good = source(dir, "bytes")
      val into = dir.resolve("inner")
      val absolute = dir.resolve("absolute-escape.bin")
      // The NUL name is the one `Path.resolve` itself refuses: the check must run before any path is built.
      Seq("../escaped.bin", absolute.toString, "sub/dir/model.bin", ".", "..", "x\u0000y").foreach: name =>
        val spec = ModelSpec.unverified(name, good.urls)
        Models.fetch(spec, into) match
          case Left(_: CvError.LoadFailed) => ()
          case other => fail(s"$name: expected Left(LoadFailed), got $other")
        assert(!Files.exists(into), s"$name: fetch created the target directory")
      assert(!Files.exists(dir.resolve("escaped.bin")), "an escaping name was written beside the directory")
      assert(!Files.exists(absolute), "an absolute name was written at its absolute path")

  test("an upper-case HTTP scheme takes the HTTP client path"):
    withDirectory: dir =>
      withRawServer(exchange => exchange.sendResponseHeaders(404, -1)): port =>
        val spec = ModelSpec.unverified("m.bin", Seq(s"HTTP://127.0.0.1:$port/m.bin"))
        Models.fetch(spec, dir.resolve("models")) match
          case Left(e) => assert(e.getMessage.contains("HTTP 404"), e.getMessage)
          case Right(path) => fail(s"a 404 must not produce a model at $path")

  test("a mirror that stalls mid-body is abandoned within the request budget"):
    withDirectory: dir =>
      val bytes = Array.tabulate[Byte](1000)(i => (i % 251).toByte)
      val finish = new CountDownLatch(1)
      def stall(exchange: HttpExchange): Unit =
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        val out = exchange.getResponseBody
        out.write(bytes, 0, 100)
        out.flush()
        // Headers and a body prefix are on the wire; now hold the rest back for longer than the budget.
        finish.await(5, TimeUnit.SECONDS): Unit
        out.write(bytes, 100, bytes.length - 100)
      try
        withRawServer(stall): port =>
          val spec = ModelSpec.unverified("stalled.bin", Seq(s"http://127.0.0.1:$port/stalled.bin"))
          val started = System.nanoTime()
          val result = Models.fetch(spec, dir, java.time.Duration.ofMillis(500))
          val elapsedMs = (System.nanoTime() - started) / 1_000_000
          finish.countDown()
          result match
            case Left(e: CvError.LoadFailed) =>
              assert(e.getMessage.contains("did not complete within"), e.getMessage)
            case other =>
              fail(s"expected Left(LoadFailed) from a stalled mirror, got $other after $elapsedMs ms")
          assert(elapsedMs < 3000, s"a 500 ms budget took $elapsedMs ms to expire")
          val parts = Using.resource(Files.list(dir))(
            _.iterator.asScala.filter(_.getFileName.toString.endsWith(".part")).toList
          )
          assertEquals(parts, Nil)
      finally finish.countDown()

/** A real second JVM, with no shared Models singleton or in-memory synchronization. */
object ModelCacheWorker:
  def main(args: Array[String]): Unit =
    args(0) match
      case "fetch" =>
        Files.createFile(Path.of(args(4)))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while !Files.exists(Path.of(args(5))) && System.nanoTime() < deadline do Thread.sleep(10)
        require(Files.exists(Path.of(args(5))), "start gate timed out")
        val result = Models.fetchCached(ModelSpec("model.bin", Seq(args(2)), args(3)), Path.of(args(1)))
        require(result.isRight, result.toString)
      case "lock" =>
        Using.resource(
          java.nio.channels.FileChannel.open(
            Path.of(args(1)),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE
          )
        ): channel =>
          Using.resource(channel.lock()): _ =>
            Files.createFile(Path.of(args(2)))
            System.in.read()
      case other => throw new IllegalArgumentException(other)
