package scalacv

import scala.util.Using

/** Explicit sequential ownership region. Only live owners are retained, never spent-wrapper history. This is
  * not a JNI concurrency lock; callers must serialize use, moves and scope closure.
  */
private[scalacv] final class NativeScope extends AutoCloseable:
  private val active = new java.util.LinkedHashSet[Managed[?]]()
  private var closed = false

  def own[A](handle: Managed[A]): Managed[A] =
    require(!closed, "cannot acquire into a closed native scope")
    require(!handle.isReleased, "cannot acquire a spent handle")
    require(handle.region.forall(_ eq this), "the handle already belongs to another scope")
    handle.region = Some(this)
    active.add(handle): Unit
    handle

  def forget(handle: Managed[?]): Unit =
    active.remove(handle): Unit

  def size: Int = active.size()

  /** Commit a transaction: its live handles become caller-owned without being released. */
  def detachAll(): Unit =
    val iterator = active.iterator()
    while iterator.hasNext do iterator.next().region = None
    active.clear()

  override def close(): Unit =
    if !closed then
      closed = true
      // Register in acquisition order so Using closes in reverse, attempts every cleanup, and applies
      // the same primary/suppressed (including fatal-error precedence) policy as Managed.use.
      Using
        .Manager: manager =>
          val iterator = active.iterator()
          while iterator.hasNext do
            val handle = iterator.next()
            handle.region = None
            manager(handle)
          active.clear()
        .get
