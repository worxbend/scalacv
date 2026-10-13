package scalacv

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.locks.ReentrantLock

import scala.util.Using

/** Fixed-size coordination, independent of the number of roots, models or callers. */
private[scalacv] object ModelCache:
  private val StripeCount = 64
  private val locks = Array.fill(StripeCount)(new ReentrantLock())

  def checkInterrupted(): Unit =
    if Thread.currentThread().isInterrupted then throw new InterruptedException("model fetch interrupted")

  /** Fail closed if atomic replacement is unsupported: never delete/copy over the old entry. The caller owns
    * the temporary file and removes it on failure. Atomic visibility is not crash durability (no fsync).
    */
  def publish(tmp: Path, target: Path): Path =
    checkInterrupted()
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  private def directory(path: Path): Path =
    rejectLink(path)
    Files.createDirectories(path): Unit
    rejectLink(path)
    path

  private def rejectLink(path: Path): Unit =
    if Files.isSymbolicLink(path) then
      throw IOException(s"symbolic links are not allowed inside the model cache: $path")

  def withLock[A](root: Path, digest: String, fileName: String)(body: Path => A): A =
    checkInterrupted()
    val canonicalRoot = Files.createDirectories(root).toRealPath()
    val lockDirectory = directory(canonicalRoot.resolve(".locks"))
    val lockPath = lockDirectory.resolve(s"${Math.floorMod(digest.hashCode, StripeCount)}.lock")
    val local = locks(Math.floorMod(lockPath.hashCode, StripeCount))
    local.lockInterruptibly()
    try
      Using.resource(
        FileChannel
          .open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
      ): channel =>
        Using.resource(channel.lock()): _ =>
          val target = directory(directory(canonicalRoot.resolve("sha256")).resolve(digest)).resolve(fileName)
          rejectLink(target)
          body(target)
    finally local.unlock()
