package durable

import scala.collection.mutable
import scala.concurrent.Future
import java.time.Instant

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
 *   import MemoryBackingStore.given
 *   given backend: MemoryBackingStore = MemoryBackingStore()
 *   // DurableStorage[T, MemoryBackingStore] is now available as a pure typeclass
 *
 * Thread safety: Uses platform-specific concurrent maps (TrieMap on JVM/Native, mutable.Map on JS).
 */
class MemoryBackingStore(
  // Activity storage - package private for typeclass access
  private[durable] val activityStore: mutable.Map[(WorkflowId, Int), Either[StoredFailure, Any]],
  // Workflow records storage
  private val workflowRecords: mutable.Map[WorkflowId, WorkflowRecord],
  // Pending events storage
  private val pendingEvents: mutable.Map[String, mutable.ArrayBuffer[PendingEvent[Any]]],
  // Workflow result storage - package private for typeclass access
  private[durable] val resultStore: mutable.Map[WorkflowId, Any]
) extends DurableStorageBackend:

  // DurableStorageBackend: activity storage

  /** Clear all cached data for a workflow (implements DurableStorageBackend) */
  def clear(workflowId: WorkflowId): Future[Unit] =
    activityStore.keys.filter(_._1 == workflowId).foreach(activityStore.remove)
    workflowRecords.remove(workflowId)
    resultStore.remove(workflowId)
    Future.successful(())

  // DurableStorageBackend: workflow metadata

  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit] =
    val now = Instant.now()
    val record = WorkflowRecord(
      id = workflowId,
      metadata = metadata,
      status = status,
      waitCondition = None,
      parentId = None,
      createdAt = now,
      updatedAt = now
    )
    workflowRecords.put(workflowId, record)
    Future.successful(())

  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]] =
    val result = workflowRecords.get(workflowId).map(r => (r.metadata, r.status))
    Future.successful(result)

  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit] =
    workflowRecords.get(workflowId).foreach { record =>
      workflowRecords.put(workflowId, record.copy(status = status, updatedAt = Instant.now()))
    }
    Future.successful(())

  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitCondition: Option[WaitCondition[?, ?]]
  ): Future[Unit] =
    workflowRecords.get(workflowId).foreach { record =>
      workflowRecords.put(workflowId, record.copy(
        status = status,
        waitCondition = waitCondition,
        updatedAt = Instant.now()
      ))
    }
    Future.successful(())

  def listActiveWorkflows(): Future[Seq[WorkflowRecord]] =
    val active = workflowRecords.values.filter { r =>
      r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended
    }.toSeq
    Future.successful(active)

  // DurableStorageBackend: pending events

  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit] =
    val events = pendingEvents.getOrElseUpdate(eventName, mutable.ArrayBuffer.empty)
    events += PendingEvent(eventId, eventName, value, timestamp)
    Future.successful(())

  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]] =
    val events = pendingEvents.getOrElse(eventName, mutable.ArrayBuffer.empty).toSeq
    Future.successful(events)

  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit] =
    pendingEvents.get(eventName).foreach { events =>
      val idx = events.indexWhere(_.eventId == eventId)
      if idx >= 0 then events.remove(idx)
    }
    Future.successful(())

  // Engine-specific: update workflow record with wait condition
  def updateWorkflowRecord(workflowId: WorkflowId, update: WorkflowRecord => WorkflowRecord): Future[Unit] =
    workflowRecords.get(workflowId).foreach { record =>
      workflowRecords.put(workflowId, update(record))
    }
    Future.successful(())

  // Testing helpers

  /** Get raw value (for testing/debugging) */
  def get(workflowId: WorkflowId, activityIndex: Int): Option[Either[StoredFailure, Any]] =
    activityStore.get((workflowId, activityIndex))

  /** Put raw value (for testing/debugging) */
  def put(workflowId: WorkflowId, activityIndex: Int, value: Either[StoredFailure, Any]): Unit =
    activityStore.put((workflowId, activityIndex), value)

  /** Clear all cached data (for testing) */
  def clearAll(): Unit =
    activityStore.clear()
    workflowRecords.clear()
    pendingEvents.clear()
    resultStore.clear()

  def size: Int = activityStore.size

  def keys: Iterable[(WorkflowId, Int)] = activityStore.keys

  /** Convenience method - returns the typeclass instance from companion */
  def forType[T]: DurableStorage[T, MemoryBackingStore] =
    MemoryBackingStore.given_DurableStorage_T_MemoryBackingStore[T]

object MemoryBackingStore extends MemoryBackingStorePlatform:
  /**
   * Pure typeclass instance for DurableStorage.
   * Can be summoned without a MemoryBackingStore instance.
   * Backend is passed as parameter to methods.
   */
  given [T]: DurableStorage[T, MemoryBackingStore] with
    def storeStep(backend: MemoryBackingStore, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      backend.activityStore.put((workflowId, activityIndex), Right(value))
      Future.successful(())

    def storeStepFailure(backend: MemoryBackingStore, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      backend.activityStore.put((workflowId, activityIndex), Left(failure))
      Future.successful(())

    def retrieveStep(backend: MemoryBackingStore, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      val result = backend.activityStore.get((workflowId, activityIndex))
        .map(_.map(_.asInstanceOf[T]))
      Future.successful(result)

    def storeResult(backend: MemoryBackingStore, workflowId: WorkflowId, value: T): Future[Unit] =
      backend.resultStore.put(workflowId, value)
      Future.successful(())

    def retrieveResult(backend: MemoryBackingStore, workflowId: WorkflowId): Future[Option[T]] =
      val result = backend.resultStore.get(workflowId).map(_.asInstanceOf[T])
      Future.successful(result)
