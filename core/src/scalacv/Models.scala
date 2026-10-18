package scalacv

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.channels.{ClosedByInterruptException, FileLockInterruptionException}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}

import scala.util.Using

/** A downloadable model file: its fixed name, the mirror URLs to try in order, the SHA-256 the fetched bytes
  * must match, and optionally the exact size they must have.
  *
  * Integrity checking is the default: build a spec with [[ModelSpec.apply]] and its pinned hash is verified
  * on every download and on every cache hit. Skipping the check is a deliberate, named opt-out —
  * [[ModelSpec.unverified]] — that loses the tamper/corruption guard, so reach for it only for a model with
  * no published checksum.
  *
  * `fileName` must be a single path component: no `/`, `\\`, `:` or NUL, and neither `.` nor `..`. The
  * constructor only checks that it is non-empty (a historical contract); [[Models.fetch]] and
  * [[Models.fetchCached]] both reject a name that is not a single component before touching the filesystem.
  *
  * `sizeBytes` is not redundant with the hash; it changes the *message*, and only ever in the direction of
  * being more useful. The failure it catches is the common one: a mirror that answers a model request with an
  * HTML error page, or a Git LFS host that serves a 131-byte pointer file, both with HTTP 200. Those hash
  * wrong, of course — but "SHA-256 mismatch" invites the reader to suspect tampering, whereas "expected
  * 232589 bytes, got 131" says what actually happened. It is also checked first, so it costs one `stat`
  * rather than a full digest of a file that was never the model.
  */
final case class ModelSpec private (
    fileName: String,
    urls: Seq[String],
    sha256: Option[String],
    sizeBytes: Option[Long]
):
  require(fileName.nonEmpty, "a model needs a file name")
  require(urls.nonEmpty, "a model needs at least one URL")
  require(sizeBytes.forall(_ > 0), s"a model's pinned size must be positive, got ${sizeBytes.orNull}")

object ModelSpec:

  /** The default, verifying form: the downloaded bytes must match `sha256` or the fetch fails.
    *
    * @param sizeBytes
    *   the model's exact size, when it is published. Optional, and only ever improves the error message — see
    *   the class scaladoc.
    */
  def apply(
      fileName: String,
      urls: Seq[String],
      sha256: String,
      sizeBytes: Option[Long] = None
  ): ModelSpec =
    new ModelSpec(fileName, urls, Some(sha256), sizeBytes)

  /** A spec with **no** integrity check — the explicit opt-out for a model with no pinned checksum. The bytes
    * are trusted as-is, so a corrupt or tampered download loads without complaint. Prefer [[apply]].
    */
  def unverified(fileName: String, urls: Seq[String]): ModelSpec =
    new ModelSpec(fileName, urls, None, None)

/** A small registry and downloader for the model files scalacv's detectors need. It is *the* downloader:
  * `scalacv.vision.FaceDetect.downloadModel` is a one-line alias for `fetch(FaceDetect.modelSpec, into)`.
  *
  * [[fetch]] downloads to a temp file beside the target and moves it into place only after it verifies, so an
  * interrupted run never leaves a truncated model for the next load to trip over. It is idempotent: a target
  * that already exists (and, if a hash or size is pinned, still matches) is returned without touching the
  * network. URLs may be `http(s)://` or `file://`, so a model you already have on disk is just another
  * source.
  *
  * The detector model specs live next to their detectors (`scalacv.vision.FaceDetect.modelSpec` and
  * `scalacv.vision.FaceRecognizer.modelSpec`); supply your own [[ModelSpec]] for anything else.
  */
object Models:

  /** How long to wait for a mirror's TCP connection before giving up and moving to the next. */
  private val ConnectTimeout: Duration = Duration.ofSeconds(15)

  /** How long a single download — headers and body — may take before it is abandoned for the next mirror. */
  private val RequestTimeout: Duration = Duration.ofSeconds(60)

  /** Read size for [[sha256Of]]. 64 KiB is the usual sweet spot: large enough that the syscall count stops
    * mattering, small enough to stay in cache and out of the humongous-allocation region of the heap.
    */
  private val DigestBufferBytes: Int = 64 * 1024

  private lazy val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(ConnectTimeout)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  /** Fetches `spec` into the directory `into` (created if absent), returning the verified file's path or a
    * `Left` describing which stage failed — the directory, every URL tried, the size, or the checksum.
    * Cancellation is not a mirror failure: `InterruptedException` propagates with the interrupt flag
    * restored. The path remains exactly `into.resolve(spec.fileName)`; this API adds no lock artifacts or
    * digest directories. Publication requires atomic replacement and fails closed if unsupported, leaving the
    * prior target unchanged rather than falling back to a potentially partial copy. For coordinated,
    * content-addressed storage, opt in to [[fetchCached]].
    *
    * `spec.fileName` must be a single path component — see [[isSafeFileName]]. A name that would resolve
    * outside `into` (`../x`, an absolute path) or below it (`sub/x`) is a `Left(CvError.LoadFailed)` returned
    * before the cache is consulted or the directory created, never a write somewhere else.
    */
  def fetch(spec: ModelSpec, into: Path): Either[CvError, Path] = fetch(spec, into, RequestTimeout)

  /** [[fetch]] with an explicit per-download budget — the seam the timeout tests use. */
  private[scalacv] def fetch(spec: ModelSpec, into: Path, requestTimeout: Duration): Either[CvError, Path] =
    // Checked before `resolve`, not just before the filesystem: `Path.resolve` itself throws for a NUL byte.
    if !isSafeFileName(spec.fileName) then
      Left(
        CvError.LoadFailed(
          spec.fileName,
          "a model file name must be a single path component inside the target directory " +
            "(no separators, '.', '..' or drive prefixes)"
        )
      )
    else
      val target = into.resolve(spec.fileName)
      if cacheHit(spec, target) then Right(target)
      else
        // Only this one step belongs under the `catch` below. A wider `try` would take a failure raised while
        // downloading — a temp file that could not be created, a mirror that refused the connection — and
        // report it as "could not create the download directory", pointing the reader at the step that had
        // demonstrably just succeeded. `fetchFirst` reports its own failures, per mirror.
        val prepared: Either[CvError, Path] =
          try Right(Files.createDirectories(into))
          catch
            case e: Exception =>
              Left(
                CvError.LoadFailed(into.toString, s"could not create the download directory: ${describe(e)}")
              )
        prepared.flatMap(_ => fetchFirst(spec, target, requestTimeout))

  /** Fetches a pinned model at `cacheRoot/sha256/<lowercase SHA-256>/<fileName>`. The root is the caller's
    * namespace; unlike [[fetch]], distinct revisions with the same filename coexist. Only an exact
    * 64-hex-digit SHA-256 and a single non-special filename are accepted (no separators, NUL or drive
    * prefixes); malformed tokens are rejected, never trimmed or path-normalized into valid ones.
    *
    * Every call verifies the bytes again, after acquiring JVM and process locks. Concurrent callers sharing a
    * target coalesce a successful miss into one download; failures are not cached. Coordination uses 64 JVM
    * lock stripes and at most 64 persistent lock files in `.locks` per root. Never remove these files or the
    * cache root while callers may be active or waiting. Stripe collisions can serialize unrelated keys.
    *
    * The root may itself be a symlink; layout components and entries beneath it may not. The caller must
    * control the root and its ancestors: this is not a sandbox against hostile concurrent filesystem edits.
    * Sharing requires a filesystem supporting process file locks and atomic replacement. As with [[fetch]],
    * unsupported atomic publication fails closed, with no non-atomic copy/delete fallback. Neither API
    * promises fsync/crash durability or protects a returned path from subsequent external modification.
    * `InterruptedException` propagates with the flag restored, including cancellation while waiting for a
    * lock.
    */
  def fetchCached(spec: ModelSpec, cacheRoot: Path): Either[CvError, Path] =
    spec.sha256 match
      case Some(digest) if digest.matches("[0-9a-fA-F]{64}") && isSafeFileName(spec.fileName) =>
        val key = digest.toLowerCase(java.util.Locale.ROOT)
        try
          ModelCache.withLock(cacheRoot, key, spec.fileName): target =>
            val result =
              if cacheHit(spec, target) then Right(target) else fetchFirst(spec, target, RequestTimeout)
            result.map(_ => cacheRoot.resolve("sha256").resolve(key).resolve(spec.fileName))
        catch
          case interrupted: InterruptedException =>
            Thread.currentThread().interrupt()
            throw interrupted
          case interrupted: FileLockInterruptionException => cancelled(interrupted)
          case interrupted: ClosedByInterruptException => cancelled(interrupted)
          case e: Exception =>
            Left(CvError.LoadFailed(cacheRoot.toString, s"could not access model cache: ${describe(e)}"))
      case _ =>
        Left(
          CvError.LoadFailed(
            spec.fileName,
            "cached models require a 64-digit SHA-256 and a single safe filename"
          )
        )

  /** Whether `name` is a single, non-special path component: non-empty, neither `.` nor `..`, and free of
    * `/`, `\\`, `:` and NUL. Shared by [[fetch]] and [[fetchCached]] so the two cannot drift on what counts
    * as escaping the target directory.
    */
  private[scalacv] def isSafeFileName(name: String): Boolean =
    name.nonEmpty && name != "." && name != ".." &&
      !name.exists(c => c == '/' || c == '\\' || c == ':' || c == 0.toChar)

  private def cancelled(cause: IOException): Nothing =
    Thread.currentThread().interrupt()
    val interrupted = new InterruptedException("model cache operation interrupted")
    interrupted.initCause(cause): Unit
    throw interrupted

  /** Whether `target` is already the model we want, so nothing needs downloading.
    *
    * An unreadable cache entry is a *miss*, not a failure. Hashing it opens the file, which can fail for
    * reasons that have nothing to do with the model — the file was deleted between the `isRegularFile` check
    * and the read, the directory lost its permissions, the disk went away. Letting that `IOException` out
    * would throw from a method whose entire signature is a promise to report failure as a `Left`. Treating it
    * as "not cached" instead re-downloads, which is the right answer for every one of those causes and, if
    * the destination really is unusable, produces a `Left` from the download that names the actual problem.
    */
  private def cacheHit(spec: ModelSpec, target: Path): Boolean =
    ModelCache.checkInterrupted()
    try Files.isRegularFile(target) && verify(spec, target).isRight
    catch
      case interrupted: ClosedByInterruptException => cancelled(interrupted)
      case _: IOException => false

  /** Checks a file against everything the spec pins, cheapest first.
    *
    * Size before digest: a mirror that answered with an HTML error page or a Git LFS pointer is caught by a
    * `stat` instead of by hashing a file that was never the model, and it is reported as the wrong size
    * rather than as a checksum mismatch — which reads like tampering and sends the reader somewhere else.
    *
    * `file` is whichever copy is being judged: the temp file during a download, the cached model on a hit.
    * Both paths go through here, so "verified" means the same thing in both — the cache check used to be a
    * separate, weaker expression of the same idea.
    */
  private def verify(spec: ModelSpec, file: Path): Either[CvError, Unit] =
    ModelCache.checkInterrupted()
    val actualSize = Files.size(file)
    spec.sizeBytes.filter(_ != actualSize) match
      case Some(want) =>
        Left(
          CvError.LoadFailed(
            file.toString,
            s"expected $want bytes for ${spec.fileName} but got $actualSize — the download is truncated, " +
              "or the server answered with something that is not the model"
          )
        )
      case None =>
        spec.sha256
          .map(want => want -> sha256Of(file))
          .filter((want, got) => !got.equalsIgnoreCase(want)) match
          case Some((want, got)) =>
            Left(
              CvError.LoadFailed(
                file.toString,
                s"SHA-256 mismatch for ${spec.fileName}: expected $want, got $got. Refusing to load an " +
                  "unverified model."
              )
            )
          case None => Right(())

  /** Tries each mirror in turn, keeping the first that downloads and (if pinned) verifies. */
  private def fetchFirst(spec: ModelSpec, target: Path, requestTimeout: Duration): Either[CvError, Path] =
    // A left fold rather than `find` over a side-effecting predicate: the failures have to accumulate in
    // order so the final message can list every mirror that was tried, and `iterator.map(...).find(...)`
    // only collects them because the iterator happens to be pulled lazily and left to right.
    spec.urls.foldLeft(Left(Nil): Either[List[String], Path]) { (soFar, url) =>
      soFar match
        case Right(path) => Right(path) // already downloaded; do not touch the remaining mirrors
        case Left(failures) =>
          fetchOne(spec, url, target, requestTimeout).left.map(e => failures :+ describe(e))
    } match
      case Right(path) => Right(path)
      case Left(failures) =>
        Left(
          CvError.LoadFailed(
            spec.fileName,
            s"could not be downloaded from any source.\n  ${failures.mkString("\n  ")}"
          )
        )

  /** Downloads one URL to a sibling temp file, verifies it, and only then moves it onto `target`.
    *
    * Creating the temp file sits inside the `try`, not ahead of it: a destination that is read-only, full, or
    * without permission to create files makes `createTempFile` throw, and that is as much a reason this
    * mirror did not work as a refused connection is. Caught here it joins the per-mirror list [[fetchFirst]]
    * builds; escaping instead, it would surface from [[fetch]] under an unrelated message. The inner bracket
    * deletes the temp file on every path and suppresses cleanup failure on a primary error. Interruption
    * propagates instead of advancing to another mirror.
    */
  private def fetchOne(
      spec: ModelSpec,
      url: String,
      target: Path,
      requestTimeout: Duration
  ): Either[CvError, Path] =
    try
      val tmp = Files.createTempFile(target.getParent, ".model-", ".part")
      val cleanup = new AutoCloseable:
        def close(): Unit =
          Files.deleteIfExists(tmp): Unit
      Using.resource(cleanup): _ =>
        download(url, tmp, requestTimeout)
        // The mirror, not the temp file, is what the reader has to act on, so `verify`'s message is
        // re-attributed to the URL it came from.
        verify(spec, tmp).left
          .map(e => CvError.LoadFailed(url, describe(e)))
          .map(_ => move(tmp, target))
    catch
      case interrupted: InterruptedException =>
        Thread.currentThread().interrupt()
        throw interrupted
      case interrupted: ClosedByInterruptException => cancelled(interrupted)
      case e: Exception => Left(CvError.LoadFailed(url, describe(e)))

  /** An exception as text a reader can act on. `getMessage` is `null` for several of the exceptions that
    * reach here — a bare `ConnectException` among them — and "could not be downloaded from any source: null"
    * names neither the cause nor the mirror.
    */
  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)

  /** Streams one URL onto `tmp`. `http(s)` goes through a timeout-bounded [[HttpClient]] so a stalled mirror
    * fails fast — surfacing as an exception that lets [[fetchFirst]] try the next mirror rather than hang
    * forever. Other schemes (notably `file://`, which the JDK HTTP client does not serve) fall back to a
    * plain stream.
    *
    * `HttpRequest.Builder.timeout` bounds only the wait for the response *headers*; a mirror that answers
    * `200` and then stalls mid-body would otherwise pin `send` forever. The body is therefore awaited through
    * `sendAsync` under the same budget, and a stall is cancelled and reported as an `IOException` so the next
    * mirror gets its turn. The scheme is compared case-insensitively: `HTTP://` is the same protocol and must
    * get the same timeouts and status check.
    */
  private def download(url: String, tmp: Path, requestTimeout: Duration): Unit =
    val uri = URI.create(url)
    Option(uri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT)) match
      case Some("http" | "https") =>
        val request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build()
        val pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(tmp))
        val response =
          try pending.get(requestTimeout.toMillis, TimeUnit.MILLISECONDS)
          catch
            case _: TimeoutException =>
              pending.cancel(true): Unit
              throw IOException(s"the download did not complete within ${describeBudget(requestTimeout)}")
            case interrupted: InterruptedException =>
              pending.cancel(true): Unit
              throw interrupted
            case failed: ExecutionException => throw failed.getCause
        if response.statusCode() >= 400 then throw IOException(s"HTTP ${response.statusCode()} from $url")
      case _ =>
        Using.resource(uri.toURL.openStream())(in =>
          Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING): Unit
        )

  /** `60 s` for whole seconds, `500 ms` otherwise — a sub-second test budget must not read as "0 s". */
  private def describeBudget(budget: Duration): String =
    if budget.toMillis % 1000 == 0 then s"${budget.toSeconds} s" else s"${budget.toMillis} ms"

  private def move(tmp: Path, target: Path): Path =
    ModelCache.publish(tmp, target)

  /** The SHA-256 of a file, hashed as it is read.
    *
    * Streamed rather than `digest(Files.readAllBytes(file))`, which materialises the whole model in the heap
    * — 37 MB for SFace, and these are only the small ones; an ONNX detector is routinely hundreds of
    * megabytes, and `readAllBytes` cannot return an array over 2 GB at all. A model is hashed twice in the
    * normal flow (once when downloaded, once on the next cache hit), so this is on the everyday path, not
    * just a first run.
    */
  private def sha256Of(file: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](DigestBufferBytes)
    Using.resource(Files.newInputStream(file)): in =>
      var read = in.read(buffer)
      while read >= 0 do
        ModelCache.checkInterrupted()
        digest.update(buffer, 0, read)
        read = in.read(buffer)
    ModelCache.checkInterrupted()
    digest.digest().map(b => f"$b%02x").mkString
