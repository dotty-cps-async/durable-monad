package durable

import scala.collection.mutable
import scala.concurrent.Future
import java.time.Instant

import durable.engine.{WorkflowMetadata, WorkflowRecord, PendingEvent}

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
  // Pending broadcast events storage (by event name)
  private val pendingEvents: mutable.Map[String, mutable.ArrayBuffer[PendingEvent[Any]]],
  // Pending targeted events storage (by workflow ID, then event name)
  private val workflowPendingEvents: mutable.Map[WorkflowId, mutable.Map[String, mutable.ArrayBuffer[PendingEvent[Any]]]],
  // Dead letter events storage (by event name)
  private val deadLetterEvents: mutable.Map[String, mutable.ArrayBuffer[DeadEvent[Any]]],
  // Workflow result storage - package private for typeclass access
  private[durable] val resultStore: mutable.Map[WorkflowId, Any],
  // Winning condition storage for combined queries (for replay)
  private val winningConditions: mutable.Map[(WorkflowId, Int), SingleEventQuery[?]]
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
      waitingForEvents = Set.empty,
      waitingForTimer = None,
      waitingForWorkflows = Set.empty,
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
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    workflowRecords.get(workflowId).foreach { record =>
      workflowRecords.put(workflowId, record.copy(
        status = status,
        waitingForEvents = waitingForEvents,
        waitingForTimer = waitingForTimer,
        waitingForWorkflows = waitingForWorkflows,
        updatedAt = Instant.now()
      ))
    }
    Future.successful(())

  def listActiveWorkflows(): Future[Seq[WorkflowRecord]] =
    val active = workflowRecords.values.filter { r =>
      r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended
    }.toSeq
    Future.successful(active)

  // DurableStorageBackend: winning condition tracking for replay

  def storeWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int,
    winning: SingleEventQuery[?]
  ): Future[Unit] =
    winningConditions.put((workflowId, activityIndex), winning)
    Future.successful(())

  def retrieveWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int
  ): Future[Option[SingleEventQuery[?]]] =
    Future.successful(winningConditions.get((workflowId, activityIndex)))

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

  // DurableStorageBackend: targeted pending events (per-workflow)

  def saveWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId, value: Any, timestamp: Instant, policy: DeadLetterPolicy = DeadLetterPolicy.Discard): Future[Unit] =
    val workflowEvents = workflowPendingEvents.getOrElseUpdate(workflowId, mutable.Map.empty)
    val events = workflowEvents.getOrElseUpdate(eventName, mutable.ArrayBuffer.empty)
    events += PendingEvent(eventId, eventName, value, timestamp, policy)
    Future.successful(())

  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]] =
    val events = for
      workflowEvents <- workflowPendingEvents.get(workflowId)
      events <- workflowEvents.get(eventName)
    yield events.toSeq
    Future.successful(events.getOrElse(Seq.empty))

  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit] =
    for
      workflowEvents <- workflowPendingEvents.get(workflowId)
      events <- workflowEvents.get(eventName)
    do
      val idx = events.indexWhere(_.eventId == eventId)
      if idx >= 0 then events.remove(idx)
    Future.successful(())

  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit] =
    workflowPendingEvents.remove(workflowId)
    Future.successful(())

  def loadAllWorkflowPendingEvents(workflowId: WorkflowId): Future[Seq[PendingEvent[?]]] =
    val allEvents = workflowPendingEvents.get(workflowId) match
      case Some(eventsByName) =>
        eventsByName.values.flatMap(_.toSeq).toSeq
      case None =>
        Seq.empty
    Future.successful(allEvents)

  // DurableStorageBackend: dead letter storage

  def saveDeadEvent(eventName: String, deadEvent: DeadEvent[?]): Future[Unit] =
    val events = deadLetterEvents.getOrElseUpdate(eventName, mutable.ArrayBuffer.empty)
    events += deadEvent.asInstanceOf[DeadEvent[Any]]
    Future.successful(())

  def loadDeadEvents(eventName: String): Future[Seq[DeadEvent[?]]] =
    val events = deadLetterEvents.getOrElse(eventName, mutable.ArrayBuffer.empty).toSeq
    Future.successful(events)

  def removeDeadEvent(eventName: String, eventId: EventId): Future[Unit] =
    deadLetterEvents.get(eventName).foreach { events =>
      val idx = events.indexWhere(_.eventId == eventId)
      if idx >= 0 then events.remove(idx)
    }
    Future.successful(())

  def loadDeadEventById(eventId: EventId): Future[Option[(String, DeadEvent[?])]] =
    val result = deadLetterEvents.collectFirst {
      case (eventName, events) if events.exists(_.eventId == eventId) =>
        (eventName, events.find(_.eventId == eventId).get)
    }
    Future.successful(result)

  // Engine-specific: update workflow record with wait condition
  def updateWorkflowRecord(workflowId: WorkflowId, update: WorkflowRecord => WorkflowRecord): Future[Unit] =
    workflowRecords.get(workflowId).foreach { record =>
      workflowRecords.put(workflowId, update(record))
    }
    Future.successful(())

  // === Composite operations for atomic persistence ===

  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    // Already protected by coordinator's single thread - just do sequentially
    winningConditions.put((workflowId, activityIndex), winningCondition)
    activityStore.put((workflowId, activityIndex), Right(eventValue))
    workflowRecords.updateWith(workflowId)(_.map(_.copy(
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    ).clearWaitConditions))
    Future.successful(())

  def deliverPendingEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend],
    pendingEventId: EventId,
    eventName: String,
    isTargeted: Boolean
  ): Future[Unit] =
    // First deliver the event
    winningConditions.put((workflowId, activityIndex), winningCondition)
    activityStore.put((workflowId, activityIndex), Right(eventValue))
    workflowRecords.updateWith(workflowId)(_.map(_.copy(
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    ).clearWaitConditions))
    // Then remove from pending queue
    if isTargeted then
      for
        workflowEvents <- workflowPendingEvents.get(workflowId)
        events <- workflowEvents.get(eventName)
      do
        val idx = events.indexWhere(_.eventId == pendingEventId)
        if idx >= 0 then events.remove(idx)
    else
      pendingEvents.get(eventName).foreach { events =>
        val idx = events.indexWhere(_.eventId == pendingEventId)
        if idx >= 0 then events.remove(idx)
      }
    Future.successful(())

  def suspendWorkflow(
    workflowId: WorkflowId,
    metadata: WorkflowMetadata,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    workflowRecords.updateWith(workflowId)(_.map(_.copy(
      metadata = metadata,
      status = WorkflowStatus.Suspended,
      waitingForEvents = waitingForEvents,
      waitingForTimer = waitingForTimer,
      waitingForWorkflows = waitingForWorkflows,
      updatedAt = Instant.now()
    )))
    Future.successful(())

  def deliverTimer(
    workflowId: WorkflowId,
    activityIndex: Int,
    wakeAt: Instant,
    timeReached: TimeReached,
    timeReachedStorage: DurableStorage[TimeReached, ? <: DurableStorageBackend]
  ): Future[Unit] =
    winningConditions.put((workflowId, activityIndex), TimerInstant(wakeAt))
    activityStore.put((workflowId, activityIndex), Right(timeReached))
    workflowRecords.updateWith(workflowId)(_.map(_.copy(
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    ).clearWaitConditions))
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
    workflowPendingEvents.clear()
    deadLetterEvents.clear()
    resultStore.clear()
    winningConditions.clear()

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
