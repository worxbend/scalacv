package scalacv

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Explicit sequential ownership region. Only live owners are retained, never spent-wrapper history.
  *
  * Registration is synchronized: handles may be acquired, moved and released from several threads inside one
  * scope. That is the whole promise — this is not a JNI concurrency lock; callers must still serialize native
  * access to any one handle, and scope closure against the work inside it.
  */
private[scalacv] final class NativeScope extends AutoCloseable:
  private val active = new java.util.LinkedHashSet[Managed[?]]()
  private var closed = false

  def own[A](handle: Managed[A]): Managed[A] = this.synchronized:
    require(!closed, "cannot acquire into a closed native scope")
    require(!handle.isReleased, "cannot acquire a spent handle")
    require(handle.region.forall(_ eq this), "the handle already belongs to another scope")
    handle.region = Some(this)
    active.add(handle): Unit
    handle

  def forget(handle: Managed[?]): Unit = this.synchronized:
    active.remove(handle): Unit

  def size: Int = this.synchronized(active.size())

  /** Commit a transaction: its live handles become caller-owned without being released. */
  def detachAll(): Unit = this.synchronized:
    val iterator = active.iterator()
    while iterator.hasNext do iterator.next().region = None
    active.clear()

  override def close(): Unit =
    // Everything that touches the registry happens under the lock; the native releases do not, so a release
    // that blocks or throws never holds up a concurrent `own`/`forget` on another scope's thread. Membership
    // is dropped from the snapshot first, so a handle released below has no registry left to unregister from.
    val snapshot = this.synchronized:
      if closed then None
      else
        closed = true
        val handles = active.iterator().asScala.toList
        handles.foreach(_.region = None)
        active.clear()
        Some(handles)
    snapshot.foreach: handles =>
      // Register in acquisition order so Using closes in reverse, attempts every cleanup, and applies
      // the same primary/suppressed (including fatal-error precedence) policy as Managed.use.
      Using
        .Manager: manager =>
          handles.foreach(handle => manager(handle): Unit)
        .get
