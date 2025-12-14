package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.concurrent.Future

/**
 * JSON-based file storage for testing persistence across process restarts.
 *
 * Directory structure:
 *   baseDir/
 *     {workflowId}/
 *       activity-{index}.json     (success or failure)
 *       metadata.json             (workflow metadata)
 */
class JsonFileStorage(baseDir: Path) extends DurableStorageBackend:

  import JsonFileStorage.given

  def forType[T: JsonValueCodec]: DurableStorage[T, JsonFileStorage] = new DurableStorage[T, JsonFileStorage]:
    def store(workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit] =
      val file = activityFile(workflowId, activityIndex)
      Files.createDirectories(file.getParent)
      val wrapped = StoredValue[T](Right(value))
      Files.writeString(file, writeToString(wrapped), StandardCharsets.UTF_8)
      Future.successful(())

    def storeFailure(workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit] =
      val file = activityFile(workflowId, activityIndex)
      Files.createDirectories(file.getParent)
      // Store failure with a dummy type - we only care about the Left side
      val wrapped = StoredValue[T](Left(failure))
      Files.writeString(file, writeToString(wrapped), StandardCharsets.UTF_8)
      Future.successful(())

    def retrieve(workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]] =
      val file = activityFile(workflowId, activityIndex)
      if Files.exists(file) then
        val json = Files.readString(file, StandardCharsets.UTF_8)
        val wrapped = readFromString[StoredValue[T]](json)
        Future.successful(Some(wrapped.value))
      else
        Future.successful(None)

    def backend: JsonFileStorage = JsonFileStorage.this

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

  /** Store workflow metadata */
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
