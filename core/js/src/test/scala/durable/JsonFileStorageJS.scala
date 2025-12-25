package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.time.Instant
import scala.concurrent.Future
import scala.scalajs.js

import durable.engine.{WorkflowMetadata, WorkflowRecord, PendingEvent}

/**
 * JSON-based file storage for testing persistence across process restarts (JS version).
 * Uses Node.js fs module for file operations.
 *
 * Directory structure:
 *   baseDir/
 *     {workflowId}/
 *       activity-{index}.json     (success or failure)
 *       metadata.json             (workflow metadata)
 *       winning-condition-{index}.json (which condition fired)
 */
class JsonFileStorageJS(val baseDir: String) extends DurableStorageBackend:

  import JsonFileStorageJS.given

  /** Convenience method - returns the typeclass instance from companion */
  def forType[T: JsonValueCodec]: DurableStorage[T, JsonFileStorageJS] =
    summon[DurableStorage[T, JsonFileStorageJS]]

  private def workflowDir(workflowId: WorkflowId): String =
    NodePath.join(baseDir, workflowId.value.replace("/", "_"))

  private def activityFile(workflowId: WorkflowId, index: Int): String =
    NodePath.join(workflowDir(workflowId), s"activity-$index.json")

  private def winningConditionFile(workflowId: WorkflowId, index: Int): String =
    NodePath.join(workflowDir(workflowId), s"winning-condition-$index.json")

  private def ensureDir(path: String): Unit =
    val dir = NodePath.dirname(path)
    if !NodeFS.existsSync(dir) then
      NodeFS.mkdirSync(dir, NodeFSOptions.mkdirRecursive)

  /** Clear all cached data for a workflow (implements DurableStorageBackend) */
  def clear(workflowId: WorkflowId): Future[Unit] =
    val dir = workflowDir(workflowId)
    if NodeFS.existsSync(dir) then
      NodeFS.rmSync(dir, NodeFSOptions.rmRecursive)
    Future.successful(())

  // Engine methods - not implemented for this test storage
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def listActiveWorkflows(): Future[Seq[WorkflowRecord]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def storeWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int,
    winning: SingleEventQuery[?]
  ): Future[Unit] =
    val file = winningConditionFile(workflowId, activityIndex)
    ensureDir(file)
    val serialized = winning match
      case SingleEvent(name) => s"event:$name"
      case TimerDuration(d) => s"timerDuration:${d.toMillis}"
      case TimerInstant(i) => s"timerInstant:${i.toString}"
      case WorkflowCompletion(id) => s"workflow:${id.value}"
    NodeFS.writeFileSync(file, serialized)
    Future.successful(())

  def retrieveWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int
  ): Future[Option[SingleEventQuery[?]]] =
    val file = winningConditionFile(workflowId, activityIndex)
    if NodeFS.existsSync(file) then
      val str = NodeFS.readFileSync(file, "utf-8")
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

  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def saveWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId, value: Any, timestamp: Instant, policy: DeadLetterPolicy = DeadLetterPolicy.Discard): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadAllWorkflowPendingEvents(workflowId: WorkflowId): Future[Seq[PendingEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def saveDeadEvent(eventName: String, deadEvent: DeadEvent[?]): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadDeadEvents(eventName: String): Future[Seq[DeadEvent[?]]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def removeDeadEvent(eventName: String, eventId: EventId): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def loadDeadEventById(eventId: EventId): Future[Option[(String, DeadEvent[?])]] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  // Composite operations - not implemented for this test storage
  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

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
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def suspendWorkflow(
    workflowId: WorkflowId,
    metadata: WorkflowMetadata,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  def deliverTimer(
    workflowId: WorkflowId,
    activityIndex: Int,
    wakeAt: Instant,
    timeReached: TimeReached,
    timeReachedStorage: DurableStorage[TimeReached, ? <: DurableStorageBackend]
  ): Future[Unit] =
    Future.failed(new NotImplementedError("JsonFileStorageJS is for cross-process tests only"))

  /** Store workflow metadata (local JSON format) */
  def storeMetadata(workflowId: WorkflowId, metadata: JsonWorkflowMetadataJS): Unit =
    val file = NodePath.join(workflowDir(workflowId), "metadata.json")
    ensureDir(file)
    NodeFS.writeFileSync(file, writeToString(metadata))

  /** Load workflow metadata */
  def loadMetadata(workflowId: WorkflowId): Option[JsonWorkflowMetadataJS] =
    val file = NodePath.join(workflowDir(workflowId), "metadata.json")
    if NodeFS.existsSync(file) then
      val json = NodeFS.readFileSync(file, "utf-8")
      Some(readFromString[JsonWorkflowMetadataJS](json))
    else
      None

  /** List all workflow IDs in storage */
  def listWorkflows(): List[WorkflowId] =
    if NodeFS.existsSync(baseDir) then
      NodeFS.readdirSync(baseDir).toList
        .filter(name => NodeFS.statSync(NodePath.join(baseDir, name)).isDirectory())
        .map(WorkflowId(_))
    else
      List.empty

  /** Clean up entire storage directory */
  def clearAll(): Unit =
    if NodeFS.existsSync(baseDir) then
      NodeFS.rmSync(baseDir, NodeFSOptions.rmRecursive)

object JsonFileStorageJS:
  def apply(baseDir: String): JsonFileStorageJS = new JsonFileStorageJS(baseDir)

  // JSON codecs for storage types
  given JsonValueCodec[StoredFailure] = JsonCodecMaker.make
  given JsonValueCodec[JsonWorkflowMetadataJS] = JsonCodecMaker.make
  given JsonValueCodec[JsonWorkflowStatusJS] = JsonCodecMaker.make

  // Codec for StoredValueJS wrapper
  given [T: JsonValueCodec]: JsonValueCodec[StoredValueJS[T]] = JsonCodecMaker.make

  // Basic type codecs
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[Long] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make
  given JsonValueCodec[Double] = JsonCodecMaker.make

  /** Pure typeclass instance for DurableStorage */
  given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorageJS] with
    private def workflowDir(backend: JsonFileStorageJS, workflowId: WorkflowId): String =
      NodePath.join(backend.baseDir, workflowId.value.replace("/", "_"))

    private def activityFile(backend: JsonFileStorageJS, workflowId: WorkflowId, index: Int): String =
      NodePath.join(workflowDir(backend, workflowId), s"activity-$index.json")

    private def resultFile(backend: JsonFileStorageJS, workflowId: WorkflowId): String =
      NodePath.join(workflowDir(backend, workflowId), "result.json")

    private def ensureDir(path: String): Unit =
      val dir = NodePath.dirname(path)
      if !NodeFS.existsSync(dir) then
        NodeFS.mkdirSync(dir, NodeFSOptions.mkdirRecursive)

    def storeStep(backend: JsonFileStorageJS, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      val file = activityFile(backend, workflowId, activityIndex)
      ensureDir(file)
      val wrapped = StoredValueJS[T](Right(value))
      NodeFS.writeFileSync(file, writeToString(wrapped))
      Future.successful(())

    def storeStepFailure(backend: JsonFileStorageJS, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      val file = activityFile(backend, workflowId, activityIndex)
      ensureDir(file)
      val wrapped = StoredValueJS[T](Left(failure))
      NodeFS.writeFileSync(file, writeToString(wrapped))
      Future.successful(())

    def retrieveStep(backend: JsonFileStorageJS, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      val file = activityFile(backend, workflowId, activityIndex)
      if NodeFS.existsSync(file) then
        val json = NodeFS.readFileSync(file, "utf-8")
        val wrapped = readFromString[StoredValueJS[T]](json)
        Future.successful(Some(wrapped.value))
      else
        Future.successful(None)

    def storeResult(backend: JsonFileStorageJS, workflowId: WorkflowId, value: T): Future[Unit] =
      val file = resultFile(backend, workflowId)
      ensureDir(file)
      NodeFS.writeFileSync(file, writeToString(value))
      Future.successful(())

    def retrieveResult(backend: JsonFileStorageJS, workflowId: WorkflowId): Future[Option[T]] =
      val file = resultFile(backend, workflowId)
      if NodeFS.existsSync(file) then
        val json = NodeFS.readFileSync(file, "utf-8")
        Future.successful(Some(readFromString[T](json)))
      else
        Future.successful(None)

/**
 * Wrapper for stored values - either success or failure.
 * Same structure as JVM StoredValue for cross-platform compatibility.
 */
case class StoredValueJS[T](value: Either[StoredFailure, T])

/**
 * JSON-specific workflow metadata for cross-process test (JS version).
 * Same structure as JVM JsonWorkflowMetadata for cross-platform compatibility.
 *
 * @param functionName Fully qualified function name for registry lookup
 * @param argTypes List of fully qualified type names for arguments
 * @param argsJson JSON-encoded arguments (as array)
 * @param activityIndex Current activity index (resume point)
 * @param status Current workflow status
 */
case class JsonWorkflowMetadataJS(
  functionName: String,
  argTypes: List[String],  // Fully qualified type names
  argsJson: String,        // JSON-encoded arguments
  activityIndex: Int,
  status: JsonWorkflowStatusJS
)

enum JsonWorkflowStatusJS:
  case Running
  case Suspended
  case Completed
  case Failed
