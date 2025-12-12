package durable

import scala.collection.mutable
import scala.concurrent.Future

/**
 * In-memory async storage for testing and development.
 *
 * Stores values directly without serialization - works with any type T.
 * Not persistent across process restarts.
 * Returns Future to match the async DurableCacheBackend interface.
 *
 * Cache key is (workflowId, activityIndex).
 *
 * Note: Uses mutable.HashMap for cross-platform compatibility (JVM, JS, Native).
 * For production JVM use with concurrent access, consider a thread-safe implementation.
 */
class MemoryStorage:
  private val store: mutable.HashMap[(WorkflowId, Int), Any] = mutable.HashMap.empty

  def get(workflowId: WorkflowId, activityIndex: Int): Option[Any] =
    store.get((workflowId, activityIndex))

  def put(workflowId: WorkflowId, activityIndex: Int, value: Any): Unit =
    store.put((workflowId, activityIndex), value)

  def clear(): Unit =
    store.clear()

  def clear(workflowId: WorkflowId): Unit =
    store.keys.filter(_._1 == workflowId).foreach(store.remove)

  def size: Int = store.size

  def keys: Iterable[(WorkflowId, Int)] = store.keys

object MemoryStorage:
  def apply(): MemoryStorage = new MemoryStorage

  /**
   * Universal async DurableCacheBackend for MemoryStorage - works with any type T.
   * Values are stored directly without serialization.
   * Returns Future.successful for immediate completion.
   */
  given memoryDurableCacheBackend[T]: DurableCacheBackend[T, MemoryStorage] with
    def store(storage: MemoryStorage, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      storage.put(workflowId, activityIndex, value)
      Future.successful(())

    def retrieve(storage: MemoryStorage, workflowId: WorkflowId, activityIndex: Int): Future[Option[T]] =
      val result = storage.get(workflowId, activityIndex).map(_.asInstanceOf[T])
      Future.successful(result)
