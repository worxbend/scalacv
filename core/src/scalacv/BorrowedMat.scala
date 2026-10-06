package scalacv

import org.opencv.core.Mat

/** A liveness-checked, read-only view over a video frame that belongs to someone else.
  *
  * `Video.frames` and scalacv.zio's `frameStream` decode every frame into **one** reused native buffer — that
  * is what keeps a two-hour video flat in memory. Before this type existed, the iterator handed out that
  * buffer as a raw `Mat`, and nothing stopped a caller from smuggling the reference out of the loop: the next
  * `read` overwrote its pixels underneath the retained reference, and once the scope released the buffer the
  * retained reference pointed at freed native memory. Reading it was not an exception, it was a SIGSEGV — the
  * exact crash [[Managed]] exists to prevent everywhere else in the library.
  *
  * `BorrowedMat` closes that hole. It wraps the shared buffer and flips a liveness flag the moment the frame
  * source advances or closes; **every** access — including the [[mat]] escape hatch — checks the flag first
  * and throws `IllegalStateException` instead of crossing JNI. A retained reference therefore fails loudly,
  * in Scala, with the same spent-handle spirit as [[Managed]]'s use-after-release error.
  *
  * Only the read-only surface a frame consumer needs is forwarded — geometry, pixel reads, `dataAddr` for
  * identity checks, and [[clone]] for "I need to keep this one". Mutation is not forwarded on purpose: the
  * buffer is owned by the frame source and is about to be decoded over, so a `put` through the view would be
  * a write into someone else's next frame.
  *
  * ==What "live" means==
  *
  * A view is live from the moment the frame source produces it until the source is next asked for a frame
  * (`next`/`hasNext` on `Video.frames`' iterator, the next pull on a zio stream) or the source closes —
  * whichever comes first. Asking *is* advancing: with decode-ahead iterators `hasNext` already overwrites the
  * buffer, so the previous view is spent by the call, not by the one after.
  *
  * Not a value type: two views over the same buffer compare by reference identity, and `dataAddr` — not
  * `equals` — is the way to ask "same underlying buffer?".
  */
final class BorrowedMat private[scalacv] (private val delegate: Mat):

  /** Volatile because a zio stream can invalidate a view on a blocking-pool fiber while a consumer on another
    * fiber is mid-access; the flag flip has to be visible across that boundary for the check to mean
    * anything. The flag only ever moves live -> spent, so a single volatile boolean is the whole protocol —
    * no lock, and no cost on the happy path beyond one read.
    */
  @volatile private var live = true

  /** Marks this view spent. Called by the owning frame source when it advances or closes; idempotent. */
  private[scalacv] def invalidate(): Unit = live = false

  /** The underlying buffer if this view is still live.
    *
    * @throws IllegalStateException
    *   if the frame source has advanced past this frame or has closed. Deliberately eager, for the same
    *   reason [[Managed.get]] is: the alternative is a JVM crash from native code, not a catchable error.
    */
  private def alive: Mat =
    if !live then
      throw IllegalStateException(
        "this frame was borrowed from a video frame stream that has since advanced or closed — its " +
          "native buffer has been decoded over or released, and reading it now would be a use-after-free " +
          "(a JVM crash, not an exception). Reduce each frame inside the loop, clone() the ones you need " +
          "to keep, or use Video.framesCopied / scalacv.zio.framesCopied for frames that outlive the stream."
      )
    delegate

  /** Frame height in pixels. */
  def rows: Int = alive.rows()

  /** Frame width in pixels. */
  def cols: Int = alive.cols()

  /** Channel count — 3 for the BGR frames video backends deliver by default. */
  def channels(): Int = alive.channels()

  /** The OpenCV element type, e.g. `CvType.CV_8UC3`. */
  def `type`(): Int = alive.`type`()

  /** Whether the frame decoded to nothing. Some backends signal a dropped frame this way; the frame source
    * already filters those out, so this is a consistency check, not something a loop should branch on.
    */
  def empty(): Boolean = alive.empty()

  /** The pixel at (`row`, `col`) as OpenCV's per-channel `double` array. */
  def get(row: Int, col: Int): Array[Double] = alive.get(row, col)

  /** The native address of the pixel buffer — stable across frames because the frame source reuses one
    * buffer, which is exactly what makes this the identity check for "the stream really is zero-copy".
    */
  def dataAddr(): Long = alive.dataAddr()

  /** A deep copy of this frame that **you own**: its own pixel buffer, valid after the stream advances and
    * after it closes, and yours to release. This is the per-frame escape from the borrowing contract —
    * `Video.framesCopied` is precisely `map(frame => Managed(frame.clone()))`.
    */
  override def clone(): Mat = alive.clone()

  /** The raw borrowed `Mat`, for APIs typed on `Mat` — `Recorder.write`, `Draw`, the `Ops` extensions.
    *
    * Liveness-checked like everything else: inside the loop this is the zero-copy path (no wrapper, no
    * clone), after the loop it throws rather than handing out a dangling native handle. The returned `Mat` is
    * still borrowed — do not release it, do not retain it, and do not mutate it; the frame source owns it and
    * is about to decode over it.
    */
  def mat: Mat = alive

  override def toString: String =
    // Diagnostics only, but a spent view must still print — toString going through `alive` would make
    // logging a dead frame the one access that crashes the crash report.
    if live then s"BorrowedMat($delegate)" else "BorrowedMat(<spent>)"
