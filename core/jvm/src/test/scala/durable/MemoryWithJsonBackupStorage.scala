package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future

import durable.engine.{WorkflowMetadata, WorkflowRecord, PendingEvent}

/**
 * In-memory storage with JSON backup/restore for testing engine recovery.
 *
 * - All operations work in memory (fast)
 * - `shutdown()` serializes state to JSON file
 * - `restore()` loads state from JSON file
 *
 * Usage:
 *   import MemoryWithJsonBackupStorage.given
 *   val backend = MemoryWithJsonBackupStorage(path)
 *   // DurableStorage[T, MemoryWithJsonBackupStorage] is available as pure typeclass for T with JsonValueCodec
 *
 * Useful for testing:
 * - Engine recovery after restart
 * - Workflow resume from persisted state
 */
class MemoryWithJsonBackupStorage(backupFile: Path) extends DurableStorageBackend:

  import MemoryWithJsonBackupStorage.{*, given}

  // Activity storage: (workflowId, index) -> serialized value
  // Package private for typeclass access
  private[durable] val activities = TrieMap.empty[(String, Int), String]

  // Workflow records
  private val workflows = TrieMap.empty[String, StoredWorkflowRecord]

  // Pending broadcast events: eventName -> list of events
  private val pendingEvents = TrieMap.empty[String, mutable.ArrayBuffer[StoredPendingEvent]]

  // Pending targeted events: workflowId -> (eventName -> list of events)
  private val workflowPendingEvents = TrieMap.empty[String, mutable.Map[String, mutable.ArrayBuffer[StoredPendingEvent]]]

  // Workflow results: workflowId -> serialized result
  // Package private for typeclass access
  private[durable] val results = TrieMap.empty[String, String]

  // DurableStorageBackend implementation

  def clear(workflowId: WorkflowId): Future[Unit] =
    val wfId = workflowId.value
    activities.keys.filter(_._1 == wfId).foreach(activities.remove)
    workflows.remove(wfId)
    results.remove(wfId)
    Future.successful(())

  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit] =
    val now = Instant.now()
    val record = StoredWorkflowRecord(
      id = workflowId.value,
      functionName = metadata.functionName,
      argCount = metadata.argCount,
      activityIndex = metadata.activityIndex,
      status = status.toString,
      waitConditionType = None,
      waitConditionData = None,
      parentId = None,
      createdAt = now.toString,
      updatedAt = now.toString
    )
    workflows.put(workflowId.value, record)
    Future.successful(())

  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]] =
    workflows.get(workflowId.value) match
      case Some(record) =>
        val metadata = WorkflowMetadata(record.functionName, record.argCount, record.activityIndex)
        val status = WorkflowStatus.valueOf(record.status)
        Future.successful(Some((metadata, status)))
      case None =>
        Future.successful(None)

  def loadWorkflowRecord(workflowId: WorkflowId): Future[Option[WorkflowRecord]] =
    workflows.get(workflowId.value) match
      case Some(r) => Future.successful(Some(storedRecordToWorkflowRecord(r)))
      case None => Future.successful(None)

  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      workflows.put(workflowId.value, record.copy(status = status.toString, updatedAt = Instant.now().toString))
    }
    Future.successful(())

  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      // Store simple condition info - no Complex serialization needed
      val (condType, condData) =
        if waitingForTimer.isDefined then
          (Some("Timer"), waitingForTimer.map(_.toString))
        else if waitingForEvents.nonEmpty then
          (Some("Event"), Some(waitingForEvents.mkString(",")))
        else if waitingForWorkflows.nonEmpty then
          (Some("Workflow"), Some(waitingForWorkflows.map(_.value).mkString(",")))
        else
          (None, None)
      workflows.put(workflowId.value, record.copy(
        status = status.toString,
        waitConditionType = condType,
        waitConditionData = condData,
        updatedAt = Instant.now().toString
      ))
    }
    Future.successful(())

  def listActiveWorkflows(): Future[Seq[WorkflowRecord]] =
    val active = workflows.values.filter { r =>
      r.status == "Running" || r.status == "Suspended"
    }.map(storedRecordToWorkflowRecord).toSeq
    Future.successful(active)

  def listWorkflowsByStatus(status: WorkflowStatus): Future[Seq[WorkflowRecord]] =
    val filtered = workflows.values.filter(_.status == status.toString)
      .map(storedRecordToWorkflowRecord).toSeq
    Future.successful(filtered)

  def listWorkflowsWithTimerBefore(deadline: Instant): Future[Seq[WorkflowRecord]] =
    val filtered = workflows.values.filter { r =>
      r.status == "Suspended" &&
      r.waitConditionType.contains("Timer") &&
      r.waitConditionData.exists(d => Instant.parse(d).isBefore(deadline))
    }.map(storedRecordToWorkflowRecord).toSeq
    Future.successful(filtered)

  def listWorkflowsWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]] =
    val filtered = workflows.values.filter { r =>
      r.status == "Suspended" &&
      r.waitConditionType.contains("Event") &&
      r.waitConditionData.exists(_.split(",").contains(eventName))
    }.map(storedRecordToWorkflowRecord).toSeq
    Future.successful(filtered)

  def listAllPendingBroadcastEvents(): Future[Seq[(String, PendingEvent[?])]] =
    val all = pendingEvents.flatMap { case (eventName, events) =>
      events.map(e => (eventName, PendingEvent[String](EventId(e.eventId), e.eventName, e.value, Instant.parse(e.timestamp)): PendingEvent[?]))
    }.toSeq
    Future.successful(all)

  private def storedRecordToWorkflowRecord(r: StoredWorkflowRecord): WorkflowRecord =
    val (waitingForEvents, waitingForTimer, waitingForWorkflows) =
      (r.waitConditionType, r.waitConditionData) match
        case (Some("Timer"), Some(timerStr)) =>
          (Set.empty[String], Some(Instant.parse(timerStr)), Set.empty[WorkflowId])
        case (Some("Event"), Some(eventNames)) =>
          (eventNames.split(",").toSet, None, Set.empty[WorkflowId])
        case (Some("Workflow"), Some(workflowIds)) =>
          (Set.empty[String], None, workflowIds.split(",").map(WorkflowId(_)).toSet)
        case _ =>
          (Set.empty[String], None, Set.empty[WorkflowId])
    WorkflowRecord(
      id = WorkflowId(r.id),
      metadata = WorkflowMetadata(r.functionName, r.argCount, r.activityIndex),
      status = WorkflowStatus.valueOf(r.status),
      waitingForEvents = waitingForEvents,
      waitingForTimer = waitingForTimer,
      waitingForWorkflows = waitingForWorkflows,
      parentId = r.parentId.map(WorkflowId(_)),
      createdAt = Instant.parse(r.createdAt),
      updatedAt = Instant.parse(r.updatedAt)
    )

  // DurableStorageBackend: winning condition tracking for replay
  // For this simple test storage, we store winning conditions in the activities map with a special key prefix

  private val winningConditions = TrieMap.empty[(String, Int), String]

  def storeWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int,
    winning: SingleEventQuery[?]
  ): Future[Unit] =
    val serialized = winning match
      case SingleEvent(name) => s"event:$name"
      case TimerDuration(d) => s"timerDuration:${d.toMillis}"
      case TimerInstant(i) => s"timerInstant:${i.toString}"
      case WorkflowCompletion(id) => s"workflow:${id.value}"
    winningConditions.put((workflowId.value, activityIndex), serialized)
    Future.successful(())

  def retrieveWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int
  ): Future[Option[SingleEventQuery[?]]] =
    val result = winningConditions.get((workflowId.value, activityIndex)).map { str =>
      if str.startsWith("event:") then SingleEvent(str.stripPrefix("event:"))
      else if str.startsWith("timerDuration:") then
        TimerDuration(scala.concurrent.duration.Duration(str.stripPrefix("timerDuration:").toLong, "ms"))
      else if str.startsWith("timerInstant:") then
        TimerInstant(Instant.parse(str.stripPrefix("timerInstant:")))
      else if str.startsWith("workflow:") then
        WorkflowCompletion(WorkflowId(str.stripPrefix("workflow:")))
      else
        throw RuntimeException(s"Unknown winning condition: $str")
    }
    Future.successful(result)

  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit] =
    val events = pendingEvents.getOrElseUpdate(eventName, mutable.ArrayBuffer.empty)
    // Note: value is stored as string representation - for proper serialization need type info
    events += StoredPendingEvent(eventId.value, eventName, value.toString, timestamp.toString)
    Future.successful(())

  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]] =
    val events = pendingEvents.getOrElse(eventName, mutable.ArrayBuffer.empty).map { e =>
      PendingEvent[String](EventId(e.eventId), e.eventName, e.value, Instant.parse(e.timestamp))
    }.toSeq
    Future.successful(events)

  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit] =
    pendingEvents.get(eventName).foreach { events =>
      val idx = events.indexWhere(_.eventId == eventId.value)
      if idx >= 0 then events.remove(idx)
    }
    Future.successful(())

  def saveWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId, value: Any, timestamp: Instant, policy: DeadLetterPolicy = DeadLetterPolicy.Discard): Future[Unit] =
    val wfEvents = workflowPendingEvents.getOrElseUpdate(workflowId.value, TrieMap.empty)
    val events = wfEvents.getOrElseUpdate(eventName, mutable.ArrayBuffer.empty)
    // Note: policy not stored in this simple test storage - uses default
    events += StoredPendingEvent(eventId.value, eventName, value.toString, timestamp.toString)
    Future.successful(())

  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]] =
    val events = for
      wfEvents <- workflowPendingEvents.get(workflowId.value)
      events <- wfEvents.get(eventName)
    yield events.map { e =>
      PendingEvent[String](EventId(e.eventId), e.eventName, e.value, Instant.parse(e.timestamp))
    }.toSeq
    Future.successful(events.getOrElse(Seq.empty))

  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit] =
    for
      wfEvents <- workflowPendingEvents.get(workflowId.value)
      events <- wfEvents.get(eventName)
    do
      val idx = events.indexWhere(_.eventId == eventId.value)
      if idx >= 0 then events.remove(idx)
    Future.successful(())

  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit] =
    workflowPendingEvents.remove(workflowId.value)
    Future.successful(())

  def loadAllWorkflowPendingEvents(workflowId: WorkflowId): Future[Seq[PendingEvent[?]]] =
    val allEvents = workflowPendingEvents.get(workflowId.value) match
      case Some(eventsByName) =>
        eventsByName.values.flatMap { events =>
          events.map(e => PendingEvent[String](EventId(e.eventId), e.eventName, e.value, Instant.parse(e.timestamp)))
        }.toSeq
      case None =>
        Seq.empty
    Future.successful(allEvents)

  // Dead letter storage - simple implementation for testing
  private val deadLetterEvents = TrieMap.empty[String, mutable.ArrayBuffer[DeadEvent[Any]]]

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

  // Composite operations - simple sequential implementations
  // These need to store data in same format as DurableStorage typeclass
  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    storeWinningCondition(workflowId, activityIndex, winningCondition)
    // Store via the provided storage typeclass to maintain format compatibility
    eventStorage.asInstanceOf[DurableStorage[E, MemoryWithJsonBackupStorage]]
      .storeStep(this, workflowId, activityIndex, eventValue)
    workflows.get(workflowId.value).foreach { record =>
      workflows.put(workflowId.value, record.copy(
        status = "Running",
        waitConditionType = None,
        waitConditionData = None,
        updatedAt = Instant.now().toString
      ))
    }
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
    storeWinningCondition(workflowId, activityIndex, winningCondition)
    // Store via the provided storage typeclass to maintain format compatibility
    eventStorage.asInstanceOf[DurableStorage[E, MemoryWithJsonBackupStorage]]
      .storeStep(this, workflowId, activityIndex, eventValue)
    workflows.get(workflowId.value).foreach { record =>
      workflows.put(workflowId.value, record.copy(
        status = "Running",
        waitConditionType = None,
        waitConditionData = None,
        updatedAt = Instant.now().toString
      ))
    }
    if isTargeted then
      removeWorkflowPendingEvent(workflowId, eventName, pendingEventId)
    else
      removePendingEvent(eventName, pendingEventId)

  def suspendWorkflow(
    workflowId: WorkflowId,
    metadata: WorkflowMetadata,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      val (condType, condData) =
        if waitingForTimer.isDefined then
          (Some("Timer"), waitingForTimer.map(_.toString))
        else if waitingForEvents.nonEmpty then
          (Some("Event"), Some(waitingForEvents.mkString(",")))
        else if waitingForWorkflows.nonEmpty then
          (Some("Workflow"), Some(waitingForWorkflows.map(_.value).mkString(",")))
        else
          (None, None)
      workflows.put(workflowId.value, record.copy(
        activityIndex = metadata.activityIndex,
        status = "Suspended",
        waitConditionType = condType,
        waitConditionData = condData,
        updatedAt = Instant.now().toString
      ))
    }
    Future.successful(())

  def deliverTimer(
    workflowId: WorkflowId,
    activityIndex: Int,
    wakeAt: Instant,
    timeReached: TimeReached,
    timeReachedStorage: DurableStorage[TimeReached, ? <: DurableStorageBackend]
  ): Future[Unit] =
    storeWinningCondition(workflowId, activityIndex, TimerInstant(wakeAt))
    // Store via the provided storage typeclass to maintain format compatibility
    timeReachedStorage.asInstanceOf[DurableStorage[TimeReached, MemoryWithJsonBackupStorage]]
      .storeStep(this, workflowId, activityIndex, timeReached)
    workflows.get(workflowId.value).foreach { record =>
      workflows.put(workflowId.value, record.copy(
        status = "Running",
        waitConditionType = None,
        waitConditionData = None,
        updatedAt = Instant.now().toString
      ))
    }
    Future.successful(())

  // Backup/Restore

  /** Save all state to JSON file */
  def shutdown(): Unit =
    val snapshot = StorageSnapshot(
      activities = activities.map { case ((wfId, idx), json) =>
        StoredActivityEntry(StoredActivityKey(wfId, idx), json)
      }.toSeq,
      workflows = workflows.toMap,
      pendingEvents = pendingEvents.map { case (name, events) =>
        name -> events.toSeq
      }.toMap
    )
    Files.createDirectories(backupFile.getParent)
    Files.writeString(backupFile, writeToString(snapshot), StandardCharsets.UTF_8)

  /** Restore state from JSON file (if exists) */
  def restore(): Unit =
    if Files.exists(backupFile) then
      val json = Files.readString(backupFile, StandardCharsets.UTF_8)
      val snapshot = readFromString[StorageSnapshot](json)

      activities.clear()
      snapshot.activities.foreach { entry =>
        activities.put((entry.key.workflowId, entry.key.activityIndex), entry.value)
      }

      workflows.clear()
      workflows ++= snapshot.workflows

      pendingEvents.clear()
      snapshot.pendingEvents.foreach { case (name, events) =>
        pendingEvents.put(name, mutable.ArrayBuffer.from(events))
      }

  /** Clear all in-memory state */
  def clearAll(): Unit =
    activities.clear()
    workflows.clear()
    pendingEvents.clear()
    workflowPendingEvents.clear()
    deadLetterEvents.clear()
    winningConditions.clear()

  /** Delete backup file */
  def deleteBackup(): Unit =
    if Files.exists(backupFile) then
      Files.delete(backupFile)

  /** Convenience method - returns the typeclass instance from companion */
  def forType[T: JsonValueCodec]: DurableStorage[T, MemoryWithJsonBackupStorage] =
    summon[DurableStorage[T, MemoryWithJsonBackupStorage]]

object MemoryWithJsonBackupStorage:
  def apply(backupFile: Path): MemoryWithJsonBackupStorage = new MemoryWithJsonBackupStorage(backupFile)
  def apply(backupFile: String): MemoryWithJsonBackupStorage = new MemoryWithJsonBackupStorage(Path.of(backupFile))

  // JSON codecs for internal storage types
  given JsonValueCodec[StoredFailure] = JsonCodecMaker.make
  given JsonValueCodec[StoredActivity] = JsonCodecMaker.make
  given JsonValueCodec[StoredWorkflowRecord] = JsonCodecMaker.make
  given JsonValueCodec[StoredPendingEvent] = JsonCodecMaker.make
  given JsonValueCodec[StoredActivityKey] = JsonCodecMaker.make
  given JsonValueCodec[StoredActivityEntry] = JsonCodecMaker.make
  given JsonValueCodec[StorageSnapshot] = JsonCodecMaker.make

  // Basic type codecs
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[Long] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make
  given JsonValueCodec[Double] = JsonCodecMaker.make
  given JsonValueCodec[Instant] = JsonCodecMaker.make
  given JsonValueCodec[TimeReached] = JsonCodecMaker.make

  /**
   * Pure typeclass instance for DurableStorage.
   * Can be summoned without a MemoryWithJsonBackupStorage instance.
   * Backend is passed as parameter to methods.
   * Requires JsonValueCodec[T] for serialization.
   */
  given [T: JsonValueCodec]: DurableStorage[T, MemoryWithJsonBackupStorage] with
    def storeStep(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      val json = writeToString(StoredActivity(Right(writeToString(value)), summon[JsonValueCodec[T]].getClass.getName))
      backend.activities.put((workflowId.value, activityIndex), json)
      Future.successful(())

    def storeStepFailure(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      val json = writeToString(StoredActivity(Left(failure), ""))
      backend.activities.put((workflowId.value, activityIndex), json)
      Future.successful(())

    def retrieveStep(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      backend.activities.get((workflowId.value, activityIndex)) match
        case Some(json) =>
          val stored = readFromString[StoredActivity](json)
          val result = stored.value match
            case Left(failure) => Left(failure)
            case Right(valueJson) => Right(readFromString[T](valueJson))
          Future.successful(Some(result))
        case None =>
          Future.successful(None)

    def storeResult(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, value: T): Future[Unit] =
      backend.results.put(workflowId.value, writeToString(value))
      Future.successful(())

    def retrieveResult(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId): Future[Option[T]] =
      backend.results.get(workflowId.value) match
        case Some(json) => Future.successful(Some(readFromString[T](json)))
        case None => Future.successful(None)

// Storage data types

case class StoredActivity(
  value: Either[StoredFailure, String],  // Right contains JSON-encoded value
  valueType: String
)

case class StoredWorkflowRecord(
  id: String,
  functionName: String,
  argCount: Int,
  activityIndex: Int,
  status: String,
  waitConditionType: Option[String],
  waitConditionData: Option[String],
  parentId: Option[String],
  createdAt: String,
  updatedAt: String
)

case class StoredPendingEvent(
  eventId: String,
  eventName: String,
  value: String,  // String representation
  timestamp: String
)

case class StoredActivityKey(
  workflowId: String,
  activityIndex: Int
)

case class StoredActivityEntry(
  key: StoredActivityKey,
  value: String
)

case class StorageSnapshot(
  activities: Seq[StoredActivityEntry],
  workflows: Map[String, StoredWorkflowRecord],
  pendingEvents: Map[String, Seq[StoredPendingEvent]]
)
