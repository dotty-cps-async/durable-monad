package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future

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

  // Pending events: eventName -> list of events
  private val pendingEvents = TrieMap.empty[String, mutable.ArrayBuffer[StoredPendingEvent]]

  // DurableStorageBackend implementation

  def clear(workflowId: WorkflowId): Future[Unit] =
    val wfId = workflowId.value
    activities.keys.filter(_._1 == wfId).foreach(activities.remove)
    workflows.remove(wfId)
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

  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      workflows.put(workflowId.value, record.copy(status = status.toString, updatedAt = Instant.now().toString))
    }
    Future.successful(())

  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitCondition: Option[WaitCondition[?, ?]]
  ): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      val (condType, condData) = waitCondition match
        case Some(WaitCondition.Timer(wakeAt, _)) => (Some("Timer"), Some(wakeAt.toString))
        case Some(WaitCondition.Event(name, _)) => (Some("Event"), Some(name))
        case Some(WaitCondition.ChildWorkflow(childId, _, _)) => (Some("ChildWorkflow"), Some(childId.value))
        case None => (None, None)
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
    }.map { r =>
      WorkflowRecord(
        id = WorkflowId(r.id),
        metadata = WorkflowMetadata(r.functionName, r.argCount, r.activityIndex),
        status = WorkflowStatus.valueOf(r.status),
        waitCondition = parseWaitCondition(r.waitConditionType, r.waitConditionData),
        parentId = r.parentId.map(WorkflowId(_)),
        createdAt = Instant.parse(r.createdAt),
        updatedAt = Instant.parse(r.updatedAt)
      )
    }.toSeq
    Future.successful(active)

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

  // Update workflow record with wait condition
  // Note: We only persist condition type and data, not storage (which is runtime-only)
  def updateWorkflowRecord(workflowId: WorkflowId, waitCondition: Option[WaitCondition[?, ?]]): Future[Unit] =
    workflows.get(workflowId.value).foreach { record =>
      val (condType, condData) = waitCondition match
        case Some(WaitCondition.Timer(wakeAt, _)) => (Some("Timer"), Some(wakeAt.toString))
        case Some(WaitCondition.Event(name, _)) => (Some("Event"), Some(name))
        case Some(WaitCondition.ChildWorkflow(childId, _, _)) => (Some("ChildWorkflow"), Some(childId.value))
        case None => (None, None)
      workflows.put(workflowId.value, record.copy(
        waitConditionType = condType,
        waitConditionData = condData,
        updatedAt = Instant.now().toString
      ))
    }
    Future.successful(())

  // Parse wait condition from persisted data
  // Note: Storage typeclass is not reconstructed (it's a runtime concern)
  // The engine will use registry to get proper storage when resuming
  private def parseWaitCondition(condType: Option[String], condData: Option[String]): Option[WaitCondition[?, ?]] =
    (condType, condData) match
      case (Some("Timer"), Some(wakeAtStr)) =>
        Some(WaitCondition.Timer(Instant.parse(wakeAtStr), null))
      case (Some("Event"), Some(eventName)) =>
        Some(WaitCondition.Event(eventName, null))
      case (Some("ChildWorkflow"), Some(childIdStr)) =>
        Some(WaitCondition.ChildWorkflow(WorkflowId(childIdStr), null, null))
      case _ => None

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

  /**
   * Pure typeclass instance for DurableStorage.
   * Can be summoned without a MemoryWithJsonBackupStorage instance.
   * Backend is passed as parameter to methods.
   * Requires JsonValueCodec[T] for serialization.
   */
  given [T: JsonValueCodec]: DurableStorage[T, MemoryWithJsonBackupStorage] with
    def store(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      val json = writeToString(StoredActivity(Right(writeToString(value)), summon[JsonValueCodec[T]].getClass.getName))
      backend.activities.put((workflowId.value, activityIndex), json)
      Future.successful(())

    def storeFailure(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      val json = writeToString(StoredActivity(Left(failure), ""))
      backend.activities.put((workflowId.value, activityIndex), json)
      Future.successful(())

    def retrieve(backend: MemoryWithJsonBackupStorage, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      backend.activities.get((workflowId.value, activityIndex)) match
        case Some(json) =>
          val stored = readFromString[StoredActivity](json)
          val result = stored.value match
            case Left(failure) => Left(failure)
            case Right(valueJson) => Right(readFromString[T](valueJson))
          Future.successful(Some(result))
        case None =>
          Future.successful(None)

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
