package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.Future

import durable.engine.{WorkflowMetadata, WorkflowRecord, PendingEvent}

/**
 * JSON-based file storage for testing persistence across process restarts.
 *
 * Directory structure:
 *   baseDir/
 *     {workflowId}/
 *       activity-{index}.json     (success or failure)
 *       metadata.json             (workflow metadata)
 */
class JsonFileStorage(val baseDir: Path) extends DurableStorageBackend:

  import JsonFileStorage.given

  /** Convenience method - returns the typeclass instance from companion */
  def forType[T: JsonValueCodec]: DurableStorage[T, JsonFileStorage] =
    summon[DurableStorage[T, JsonFileStorage]]

  private def workflowDir(workflowId: WorkflowId): Path =
    baseDir.resolve(workflowId.value.replace("/", "_"))

  private def activityFile(workflowId: WorkflowId, index: Int): Path =
    workflowDir(workflowId).resolve(s"activity-$index.json")

  /** Clear all cached data for a workflow (implements DurableStorageBackend) */
  def clear(workflowId: WorkflowId): Future[Unit] =
    val dir = workflowDir(workflowId)
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))
    Future.successful(())

  // Engine methods - not implemented for this test storage
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadWorkflowRecord(workflowId: WorkflowId): Future[Option[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def listActiveWorkflows(): Future[Seq[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def listWorkflowsByStatus(status: WorkflowStatus): Future[Seq[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def listWorkflowsWithTimerBefore(deadline: Instant): Future[Seq[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def listWorkflowsWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def listAllPendingBroadcastEvents(): Future[Seq[(String, PendingEvent[?])]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  private def winningConditionFile(workflowId: WorkflowId, index: Int): Path =
    workflowDir(workflowId).resolve(s"winning-condition-$index.json")

  def storeWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int,
    winning: SingleEventQuery[?]
  ): Future[Unit] =
    val file = winningConditionFile(workflowId, activityIndex)
    Files.createDirectories(file.getParent)
    val serialized = winning match
      case SingleEvent(name) => s"event:$name"
      case TimerDuration(d) => s"timerDuration:${d.toMillis}"
      case TimerInstant(i) => s"timerInstant:${i.toString}"
      case WorkflowCompletion(id) => s"workflow:${id.value}"
    Files.writeString(file, serialized, StandardCharsets.UTF_8)
    Future.successful(())

  def retrieveWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int
  ): Future[Option[SingleEventQuery[?]]] =
    val file = winningConditionFile(workflowId, activityIndex)
    if Files.exists(file) then
      val str = Files.readString(file, StandardCharsets.UTF_8)
      val winning =
        if str.startsWith("event:") then SingleEvent(str.stripPrefix("event:"))
        else if str.startsWith("timerDuration:") then
          TimerDuration(scala.concurrent.duration.Duration(str.stripPrefix("timerDuration:").toLong, "ms"))
        else if str.startsWith("timerInstant:") then
          TimerInstant(Instant.parse(str.stripPrefix("timerInstant:")))
        else if str.startsWith("workflow:") then
          WorkflowCompletion(WorkflowId(str.stripPrefix("workflow:")))
        else
          throw RuntimeException(s"Unknown winning condition: $str")
      Future.successful(Some(winning))
    else
      Future.successful(None)

  def savePendingEvent[E](eventName: String, eventId: EventId, value: E, timestamp: Instant)(
    using storage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def saveWorkflowPendingEvent[E](workflowId: WorkflowId, eventName: String, eventId: EventId, value: E, timestamp: Instant, policy: DeadLetterPolicy = DeadLetterPolicy.Discard)(
    using storage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def moveTargetedEventToBroadcast(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadAllWorkflowPendingEvents(workflowId: WorkflowId): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def saveDeadEvent(eventName: String, deadEvent: DeadEvent[?]): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadDeadEvents(eventName: String): Future[Seq[DeadEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def removeDeadEvent(eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def loadDeadEventById(eventId: EventId): Future[Option[(String, DeadEvent[?])]] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def replayDeadEventToBroadcast(eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def replayDeadEventToTargeted(eventName: String, eventId: EventId, targetWorkflowId: WorkflowId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  // Composite operations - not implemented for this test storage
  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

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
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def suspendWorkflow(
    workflowId: WorkflowId,
    metadata: WorkflowMetadata,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  def deliverTimer(
    workflowId: WorkflowId,
    activityIndex: Int,
    wakeAt: Instant,
    timeReached: TimeReached,
    timeReachedStorage: DurableStorage[TimeReached, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorage is for cross-process tests only"))

  /** Store workflow metadata (local JSON format) */
  def storeMetadata(workflowId: WorkflowId, metadata: JsonWorkflowMetadata): Unit =
    val file = workflowDir(workflowId).resolve("metadata.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, writeToString(metadata), StandardCharsets.UTF_8)

  /** Load workflow metadata */
  def loadMetadata(workflowId: WorkflowId): Option[JsonWorkflowMetadata] =
    val file = workflowDir(workflowId).resolve("metadata.json")
    if Files.exists(file) then
      val json = Files.readString(file, StandardCharsets.UTF_8)
      Some(readFromString[JsonWorkflowMetadata](json))
    else
      None

  /** List all workflow IDs in storage */
  def listWorkflows(): List[WorkflowId] =
    if Files.exists(baseDir) then
      Files.list(baseDir).toArray.toList
        .map(_.asInstanceOf[Path])
        .filter(Files.isDirectory(_))
        .map(p => WorkflowId(p.getFileName.toString))
    else
      List.empty

  /** Clean up entire storage directory */
  def clearAll(): Unit =
    if Files.exists(baseDir) then
      Files.walk(baseDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))

object JsonFileStorage:
  def apply(baseDir: Path): JsonFileStorage = new JsonFileStorage(baseDir)
  def apply(baseDir: String): JsonFileStorage = new JsonFileStorage(Paths.get(baseDir))

  // JSON codecs for storage types
  given JsonValueCodec[StoredFailure] = JsonCodecMaker.make
  given JsonValueCodec[JsonWorkflowMetadata] = JsonCodecMaker.make
  given JsonValueCodec[JsonWorkflowStatus] = JsonCodecMaker.make

  // Codec for StoredValue wrapper
  given [T: JsonValueCodec]: JsonValueCodec[StoredValue[T]] = JsonCodecMaker.make

  // Basic type codecs
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[Long] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make
  given JsonValueCodec[Double] = JsonCodecMaker.make

  /** Pure typeclass instance for DurableStorage */
  given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorage] with
    private def workflowDir(backend: JsonFileStorage, workflowId: WorkflowId): Path =
      backend.baseDir.resolve(workflowId.value.replace("/", "_"))

    private def activityFile(backend: JsonFileStorage, workflowId: WorkflowId, index: Int): Path =
      workflowDir(backend, workflowId).resolve(s"activity-$index.json")

    private def resultFile(backend: JsonFileStorage, workflowId: WorkflowId): Path =
      workflowDir(backend, workflowId).resolve("result.json")

    def storeStep(backend: JsonFileStorage, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      val file = activityFile(backend, workflowId, activityIndex)
      Files.createDirectories(file.getParent)
      val wrapped = StoredValue[T](Right(value))
      Files.writeString(file, writeToString(wrapped), StandardCharsets.UTF_8)
      Future.successful(())

    def storeStepFailure(backend: JsonFileStorage, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      val file = activityFile(backend, workflowId, activityIndex)
      Files.createDirectories(file.getParent)
      val wrapped = StoredValue[T](Left(failure))
      Files.writeString(file, writeToString(wrapped), StandardCharsets.UTF_8)
      Future.successful(())

    def retrieveStep(backend: JsonFileStorage, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      val file = activityFile(backend, workflowId, activityIndex)
      if Files.exists(file) then
        val json = Files.readString(file, StandardCharsets.UTF_8)
        val wrapped = readFromString[StoredValue[T]](json)
        Future.successful(Some(wrapped.value))
      else
        Future.successful(None)

    def storeResult(backend: JsonFileStorage, workflowId: WorkflowId, value: T): Future[Unit] =
      val file = resultFile(backend, workflowId)
      Files.createDirectories(file.getParent)
      Files.writeString(file, writeToString(value), StandardCharsets.UTF_8)
      Future.successful(())

    def retrieveResult(backend: JsonFileStorage, workflowId: WorkflowId): Future[Option[T]] =
      val file = resultFile(backend, workflowId)
      if Files.exists(file) then
        val json = Files.readString(file, StandardCharsets.UTF_8)
        Future.successful(Some(readFromString[T](json)))
      else
        Future.successful(None)

/**
 * Wrapper for stored values - either success or failure.
 */
case class StoredValue[T](value: Either[StoredFailure, T])

/**
 * JSON-specific workflow metadata for cross-process test.
 * Differs from main WorkflowMetadata by storing args as JSON.
 *
 * @param functionName Fully qualified function name for registry lookup
 * @param argTypes List of fully qualified type names for arguments
 * @param argsJson JSON-encoded arguments (as array)
 * @param activityIndex Current activity index (resume point)
 * @param status Current workflow status
 */
case class JsonWorkflowMetadata(
  functionName: String,
  argTypes: List[String],  // Fully qualified type names
  argsJson: String,        // JSON-encoded arguments
  activityIndex: Int,
  status: JsonWorkflowStatus
)

enum JsonWorkflowStatus:
  case Running
  case Suspended
  case Completed
  case Failed
