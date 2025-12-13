package durable

import scala.collection.mutable
import scala.concurrent.Future

/**
 * In-memory backing store for testing and development.
 *
 * Stores values directly without serialization - works with any type T.
 * Not persistent across process restarts.
 *
 * Cache key is (workflowId, activityIndex).
 * Values are stored as Either[StoredFailure, T] to support both success and failure caching.
 *
 * Usage:
 *   given backing: MemoryBackingStore = MemoryBackingStore()
 *   given [T]: DurableStorage[T] = backing.forType[T]
 *
 * Note: Uses mutable.HashMap for cross-platform compatibility (JVM, JS, Native).
 * For production JVM use with concurrent access, consider a thread-safe implementation.
 */
class MemoryBackingStore:
  // Stores Either[StoredFailure, Any] to support both success and failure
  private val store: mutable.HashMap[(WorkflowId, Int), Either[StoredFailure, Any]] = mutable.HashMap.empty

  /**
   * Create a DurableStorage[T] backed by this store.
   * Values are stored directly without serialization.
   */
  def forType[T]: DurableStorage[T] = new DurableStorage[T]:
    def store(workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      MemoryBackingStore.this.store.put((workflowId, activityIndex), Right(value))
      Future.successful(())

    def storeFailure(workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      MemoryBackingStore.this.store.put((workflowId, activityIndex), Left(failure))
      Future.successful(())

    def retrieve(workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      val result = MemoryBackingStore.this.store.get((workflowId, activityIndex))
        .map(_.map(_.asInstanceOf[T]))
      Future.successful(result)

  /** Get raw value (for testing/debugging) */
  def get(workflowId: WorkflowId, activityIndex: Int): Option[Either[StoredFailure, Any]] =
    store.get((workflowId, activityIndex))

  /** Put raw value (for testing/debugging) */
  def put(workflowId: WorkflowId, activityIndex: Int, value: Either[StoredFailure, Any]): Unit =
    store.put((workflowId, activityIndex), value)

  def clear(): Unit =
    store.clear()

  def clear(workflowId: WorkflowId): Unit =
    store.keys.filter(_._1 == workflowId).foreach(store.remove)

  def size: Int = store.size

  def keys: Iterable[(WorkflowId, Int)] = store.keys

object MemoryBackingStore:
  def apply(): MemoryBackingStore = new MemoryBackingStore
