package scalacv

import java.lang.invoke.{MethodHandle, MethodHandles}
import java.util.concurrent.ConcurrentHashMap

import org.opencv.core.Mat
import org.opencv.videoio.{VideoCapture, VideoWriter}

/** How a native OpenCV object gets freed.
  *
  * There are two regimes, and which one applies is not a style choice — it is dictated by what the generated
  * Java binding exposes. Of the 188 `org.opencv.*` types that hold a native pointer, exactly **three** have a
  * public `release()`: `Mat`, `org.opencv.videoio.VideoCapture` and `org.opencv.videoio.VideoWriter`. The
  * other 185 — including every detector this library wraps — expose only a
  * `private static native void delete(long)` plus a `finalize()`.
  *
  * Relying on that `finalize()` is not viable. It is not disabled (a common myth), but it only runs when the
  * collector runs, and the collector sees ~40 bytes of Java header per multi- megabyte native buffer.
  * Measured: 2000 unreleased 1000x1000 Mats reach 5.8 GB RSS against 144 MB when released.
  */
trait Releasable[-A]:
  def release(a: A): Unit

object Releasable:

  /** `Mat` frees through `release()`, which drops the reference to the pixel **buffer** at once — the
    * multi-megabyte allocation that actually costs memory. The small (~100 B) `cv::Mat` **header** is not
    * freed here; it is reclaimed by the inherited `CleanableMat.finalize()` whenever the collector next runs.
    *
    * We deliberately do **not** route `Mat` through the `delete(long)` bridge the other 185 types use: `Mat`
    * exposes no accessible `delete(long)` — only its superclass's private `CleanableMat.n_delete` — and the
    * header is too small for reflection to reclaim it a little sooner to be worth the cost. The buffer, which
    * is the part that matters, is already gone the instant `release()` returns.
    */
  given Releasable[Mat] = _.release()
  given Releasable[VideoCapture] = _.release()
  given Releasable[VideoWriter] = _.release()

  /** The fallback for the other 185 types: a cached `MethodHandle` onto the binding's private `delete(long)`.
    *
    * This is deliberately **opt-in and loud**. `delete(long)` is private API with no compatibility promise,
    * and `setAccessible` stops working the moment OpenCV's classes are loaded from a named module rather than
    * the classpath — which a consumer controls via `--add-opens`, and we cannot. So [[handle]] never degrades
    * silently: if the bridge cannot be opened it throws, because the alternative is an unbounded leak that
    * looks like success.
    */
  def handle[A <: AnyRef](getNativeAddr: A => Long): Releasable[A] =
    a => free(a, getNativeAddr(a))

  /** [[handle]] without the accessor: the address is read from the binding's own `nativeObj` field.
    *
    * Every one of the 185 types spells its accessor the same way, so the [[handle]] call naming it was
    * written out identically at two dozen sites -- two dozen chances to hand one type another type's
    * accessor, which compiles cleanly and frees the wrong pointer. This form cannot be given the wrong one.
    *
    * It reads `nativeObj` reflectively rather than calling `getNativeObjAddr`, which sounds like the weaker
    * choice and is not: the disarm step below must reflect on that exact field on this exact path anyway, so
    * the `Field` is already open and cached by the time the address is wanted, and reading it costs nothing
    * beyond the lookup that was going to happen regardless. It also fails in the same place and with the same
    * message as the disarm would, instead of succeeding at reading an address the disarm then refuses to
    * neutralise.
    *
    * [[handle]] stays for anything that does not follow the pattern -- a binding whose address lives
    * somewhere other than `nativeObj`, or a type outside `org.opencv.*` altogether.
    */
  def nativeHandle[A <: AnyRef]: Releasable[A] =
    a => free(a, NativeFinalizer.address(a))

  /** The release sequence both forms share, once the address is in hand. */
  private def free[A <: AnyRef](a: A, addr: Long): Unit =
    if addr != 0L then
      // Disarm BEFORE deleting, never after: between the two there is a window in which the
      // finalizer could run against a pointer we have already freed.
      NativeFinalizer.disarm(a)
      NativeDelete.of(a.getClass).invokeExact(addr): Unit

/** The `--add-opens` line that would let reflection reach `cls`, computed from the class's own module and
  * package rather than guessed. A class in the unnamed module — OpenCV on the classpath, the normal case —
  * has no module to open, so this is not a module-access failure at all; we say so instead of emitting a flag
  * with a `null` module name in it.
  */
private def addOpensRemedy(cls: Class[?]): String =
  Option(cls.getModule.getName) match
    case Some(m) => s"  --add-opens $m/${cls.getPackageName}=ALL-UNNAMED"
    case None =>
      "  (OpenCV is on the classpath, so this is not a module-access failure — " +
        "please report it with the cause below.)"

/** Opens and caches the private `delete(long)` of a generated OpenCV binding class. */
private object NativeDelete:

  private val cache = ConcurrentHashMap[Class[?], MethodHandle]()

  def of(cls: Class[?]): MethodHandle =
    cache.computeIfAbsent(cls, open)

  private def open(cls: Class[?]): MethodHandle =
    try
      val m = cls.getDeclaredMethod("delete", classOf[Long])
      m.setAccessible(true)
      MethodHandles.lookup().unreflect(m)
    catch
      case e: NoSuchMethodException =>
        throw CvError.NativesMissing(
          s"${cls.getName} has no delete(long): this build of the OpenCV bindings is not one " +
            "scalacv can free. Please report the bytedeco version.",
          e
        )
      case e: RuntimeException =>
        // InaccessibleObjectException, typically: OpenCV is on the module path.
        val remedy = addOpensRemedy(cls)
        throw CvError.NativesMissing(
          s"""cannot open ${cls.getName}.delete(long) (${e.getClass.getSimpleName}).
             |
             |$remedy
             |
             |scalacv fails here rather than falling back to the garbage collector, because that
             |fallback does not reclaim native memory in any useful timeframe.""".stripMargin,
          e
        )

/** Stops a generated OpenCV binding from freeing a pointer we have already freed.
  *
  * Every one of the 185 handle classes carries this, verbatim:
  *
  * {{{
  * protected void finalize() throws Throwable { delete(this.nativeObj); }
  * }}}
  *
  * It is unconditional. So releasing through [[Releasable.handle]] and then dropping the Java object means
  * `delete` runs twice on the same address — the first time from us, the second from the finalizer thread
  * whenever the collector gets round to it. That is heap corruption, and it surfaces as a SIGSEGV somewhere
  * else entirely, at an unpredictable later moment. `Managed`'s compare-and-set cannot help: it makes *our*
  * release idempotent and knows nothing about a finalizer running on another thread.
  *
  * The fix is to zero `nativeObj` first, so the finalizer's `delete(0)` becomes `delete nullptr`, which C++
  * defines as a no-op.
  *
  * If the field cannot be written — a future JDK tightening final-field reflection, or OpenCV loaded from a
  * named module — this **throws instead of deleting**. Deleting anyway would reintroduce exactly the double
  * free this exists to prevent, and a leak is recoverable where a corrupted heap is not.
  */
private object NativeFinalizer:

  private val fields = java.util.concurrent.ConcurrentHashMap[Class[?], java.lang.reflect.Field]()

  /** The address currently in `target`'s `nativeObj`, through the same cached `Field` [[disarm]] uses. Zero
    * once disarmed, which is why callers must read it first.
    */
  def address(target: AnyRef): Long =
    fields.computeIfAbsent(target.getClass, findNativeObj).getLong(target)

  def disarm(target: AnyRef): Unit =
    val f = fields.computeIfAbsent(target.getClass, findNativeObj)
    try f.setLong(target, 0L)
    catch
      case e: IllegalAccessException =>
        throw CvError.NativesMissing(
          s"cannot disarm ${target.getClass.getName}.nativeObj (${e.getMessage}); refusing to " +
            "free it, because the binding's finalizer would then free it a second time"
        )

  private def findNativeObj(cls: Class[?]): java.lang.reflect.Field =
    var c: Class[?] | Null = cls
    var found: Option[java.lang.reflect.Field] = None
    while c != null && found.isEmpty do
      val here = c.nn.getDeclaredFields.find(f => f.getName == "nativeObj" && f.getType == classOf[Long])
      found = here
      c = c.nn.getSuperclass
    found match
      case Some(f) =>
        try
          f.setAccessible(true)
          f
        catch
          case e: RuntimeException =>
            val remedy = addOpensRemedy(cls)
            throw CvError.NativesMissing(
              s"""cannot make ${cls.getName}.nativeObj writable (${e.getClass.getSimpleName}).
                 |
                 |scalacv must zero this field before freeing the object, because the binding's
                 |finalizer calls delete(nativeObj) unconditionally and would otherwise free the
                 |same pointer twice.
                 |
                 |$remedy
                 |
                 |scalacv refuses to free the object rather than risk corrupting the heap.""".stripMargin,
              e
            )
      case None =>
        throw CvError.NativesMissing(
          s"${cls.getName} has no nativeObj field; scalacv cannot safely free this type"
        )
