package scalacv

import java.util.concurrent.atomic.AtomicReference

import scala.util.Using

/** A native OpenCV object with a release that happens exactly once.
  *
  * Two guarantees, both of which exist because getting them wrong is a JVM crash rather than an exception.
  * Calling a method on a freed OpenCV object segfaults from native code — no stack trace, no catch, no test
  * report; a double `delete` is undefined behaviour that merely *often* happens to survive. Measured, both.
  *
  *   1. Release is a compare-and-set, so a second release is a no-op rather than a double free.
  *   1. Access after release throws `IllegalStateException` on the Scala side, before anything crosses JNI.
  *
  * Prefer [[use]] over holding one of these. The scoped form is the only one where the compiler helps you.
  *
  * ==Diagnosing use-after-move==
  *
  * The move semantics of [[Image]] mean the commonest mistake is reusing a handle a transform already
  * consumed, and the resulting `IllegalStateException` fires at the *reuse*, which is rarely the interesting
  * line. Start the JVM with `-Dscalacv.trackOwnership=true` and the exception carries, as its cause, the
  * stack of the transform or terminal that actually spent the handle. It is off by default because it
  * allocates a `Throwable` every time a handle is spent; the check that reads it lives only on the
  * already-failing path, so a program that never misuses a handle pays nothing.
  */
final class Managed[A] private (initial: A, releaser: Releasable[A]) extends AutoCloseable:

  private val ref = AtomicReference[A | Null](initial)

  // Region membership is explicit and follows moves; native access remains sequential.
  private[scalacv] var region: Option[NativeScope] = None

  private def unregister(): Unit =
    region.foreach(_.forget(this))
    region = None

  private[scalacv] def move(): Managed[A] =
    val owner = region
    val next = new Managed(take(), releaser)
    owner.foreach(_.own(next))
    next

  private[scalacv] def detached(): Managed[A] =
    unregister()
    move()

  private[scalacv] def alongside[B](next: Managed[B]): Managed[B] =
    region.foreach(_.own(next))
    next

  /** Consumes the input, rolling back an output if input cleanup prevents returning it. */
  private[scalacv] def replacing[B](f: A => Managed[B]): Managed[B] =
    var output: Option[Managed[B]] = None
    try
      use: value =>
        val next = alongside(f(value))
        output = Some(next)
        next
    catch
      case error: Throwable =>
        output match
          case Some(next) => Using.resource(next)(_ => throw error)
          case None => throw error

  /** The object's class, captured at construction so that [[spentError]] can name the type without this
    * `Managed` holding on to the object itself. Keeping the `initial` parameter alive would defeat the point
    * of nulling `ref` in [[release]]: the released object would stay strongly reachable for as long as the
    * handle lives, and the `cv::Mat` header it owns could never be reclaimed by the collector. A `Class`
    * costs one field and is already kept alive by its classloader.
    */
  private val ofType: Class[?] = initial.getClass

  /** Where this handle was spent, captured **only** when `-Dscalacv.trackOwnership=true` (see [[Managed]]).
    * Off, it stays `null` and costs nothing on the hot path; on, it is attached as the cause of the
    * use-after-move error so the crash points at the transform/terminal that consumed the handle, not just at
    * the reuse.
    */
  @volatile private var spentAt: Throwable | Null = null

  private def markSpent(): Unit =
    if Managed.TrackOwnership then spentAt = Throwable("this handle was consumed here")

  /** The `IllegalStateException` thrown when a spent handle is touched — worded for the common case, an
    * [[Image]] used after a move, and pointing at the consuming call when ownership tracking is on.
    */
  private def spentError(verb: String): IllegalStateException =
    val what = ofType.getSimpleName
    val hint =
      spentAt match
        case _: Throwable => "" // the consuming site is attached as the cause below
        case null if !Managed.TrackOwnership =>
          " Run with -Dscalacv.trackOwnership=true to record where it was consumed."
        case null => ""
    val e = IllegalStateException(
      s"this $what has already been released or consumed — $verb it now would crash the JVM from native " +
        "code. A high-level Image is spent by any transform (gray/blur/…) or terminal (write/bytes/close); " +
        s"call `.copy` before the first use if you need it twice.$hint"
    )
    spentAt match
      case t: Throwable => e.initCause(t)
      case null => ()
    e

  /** The underlying OpenCV object.
    *
    * Throws `IllegalStateException` if it has already been released or consumed. Deliberately eager: the
    * alternative is a SIGSEGV.
    */
  def get: A = ref.get match
    case null => throw spentError("using")
    case a => a.asInstanceOf[A]

  /** Hands the object out and leaves this `Managed` spent **without freeing it** — ownership transfers to the
    * caller, who becomes responsible for release.
    *
    * Internal to the high-level [[Image]], which threads one live Mat through a chain of handles: an in-place
    * step takes the Mat, mutates it, and rewraps it, so the predecessor handle is spent (`get` now throws)
    * yet nothing is freed or copied. This is deliberately **not** [[release]], which would free the very Mat
    * being transferred.
    *
    * @throws IllegalStateException
    *   if it has already been released or taken.
    */
  private[scalacv] def take(): A =
    ref.getAndSet(null) match
      case null => throw spentError("transferring")
      case a =>
        markSpent()
        unregister()
        a.asInstanceOf[A]

  /** Releases the native memory. Idempotent — a second call does nothing. */
  def release(): Unit =
    ref.getAndSet(null) match
      case null => ()
      case a =>
        markSpent()
        unregister()
        releaser.release(a.asInstanceOf[A])

  override def close(): Unit = release()

  def isReleased: Boolean = ref.get == null

  /** Runs `f` synchronously and releases afterwards. A lazy effect returned by `f` is not executed here.
    * Cleanup failures are suppressed on the body failure, subject to `Using`'s fatal-error precedence.
    */
  def use[B](f: A => B): B =
    Using.resource(this)(handle => f(handle.get))

  override def toString: String =
    // One snapshot: a concurrent release between an isReleased check and a second read could print
    // Managed(null). Diagnostics only, but cheap to make consistent.
    val snapshot = ref.get
    if snapshot == null then "Managed(<released>)" else s"Managed($snapshot)"

object Managed:

  /** Whether a spent handle records where it was consumed — see the class scaladoc. Read once at class load
    * from `-Dscalacv.trackOwnership=true`; off by default.
    */
  private val TrackOwnership: Boolean = java.lang.Boolean.getBoolean("scalacv.trackOwnership")

  def apply[A](a: A)(using r: Releasable[A]): Managed[A] = new Managed(a, r)

  /** Scoped acquisition — the form to reach for by default.
    *
    * {{{
    * Managed.use(Mat(8, 8, CvType.CV_8UC3)) { m => m.rows }
    * }}}
    */
  def use[A, B](a: => A)(f: A => B)(using Releasable[A]): B = Managed(a).use(f)

  /** Owns the several native objects one operation needs and none of which it hands back.
    *
    * [[use]] scopes exactly one object, so an operation needing six of them — `solvePnP` wants object points,
    * image points, a camera matrix, distortion coefficients and two output vectors — nests six `use` blocks
    * and buries its two interesting lines under six levels of indentation. The alternative people reach for
    * instead, `val`s followed by a `try`/`finally` that releases each one, has a real hole in it: every
    * allocation before the `try` is unguarded, so a constructor that throws part-way strands the objects
    * already built.
    *
    * `scope` is both, correctly. Each object is registered the moment it is created, so a throw anywhere — in
    * a later constructor, in the native call, in the decode — releases everything acquired so far, in reverse
    * order:
    *
    * {{{
    * Managed.scope: own =>
    *   val camera = own(intrinsics.cameraMatrix)
    *   val dist   = own(intrinsics.distCoeffs)
    *   val rvec   = own(Mat())
    *   val tvec   = own(Mat())
    *   Calib3d.solvePnP(objectPoints, imagePoints, camera, dist, rvec, tvec)
    *   Pose3D(readColumn(rvec, 3), readColumn(tvec, 3))
    * }}}
    *
    * `own` hands back the object itself rather than a [[Managed]], because a scoped handle has no second
    * owner to protect it from: the scope releases it exactly once, at the end. **Nothing acquired here may
    * escape the block** — the value `body` returns must be plain data (a `Seq[Double]`, a case class) or a
    * separately-owned object, never one of the scoped ones. That is the same rule [[use]] carries, applied to
    * a group.
    *
    * The exception a failing body throws propagates unchanged; a failure raised by a *release* is attached to
    * it as a suppressed exception rather than replacing it, so the original cause is never lost.
    */
  def scope[B](body: Scope => B): B = Using.Manager(manager => body(Scope(manager))).get

  /** The `own` handed to a [[Managed.scope]] body: registers a native object with the scope and hands it
    * straight back, so `val m = own(Mat())` reads as an ordinary binding.
    */
  final class Scope private[Managed] (manager: Using.Manager):

    /** Takes ownership of `a` for the rest of the enclosing [[Managed.scope]] and returns it for use. */
    def apply[A](a: A)(using Releasable[A]): A = manager(Managed(a)).get

    /** [[apply]] for a value that arrives already wrapped — the `Managed[Mat]` every mid-level `Ops` call
      * hands back. The scope takes over that handle rather than making a second one, so the Mat is still
      * released exactly once.
      *
      * A separate name rather than an overload of [[apply]]: `Managed[A]` is itself an `A` as far as overload
      * resolution is concerned, and the two candidates would be picked between before the `Releasable` search
      * that distinguishes them.
      */
    def adopt[A](handle: Managed[A]): A = manager(handle).get

  // Managed is AutoCloseable, so scala.util.Using — including Using.Manager — already accepts it
  // through Using.Releasable.AutoCloseableIsReleasable. Defining our own given here as well made
  // every `use(Managed(...))` call ambiguous.
