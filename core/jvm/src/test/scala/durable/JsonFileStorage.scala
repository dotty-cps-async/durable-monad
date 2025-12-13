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
class JsonFileStorage(baseDir: Path):

  import JsonFileStorage.given

  def forType[T: JsonValueCodec]: DurableStorage[T] = new DurableStorage[T]:
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

  private def workflowDir(workflowId: WorkflowId): Path =
    baseDir.resolve(workflowId.value.replace("/", "_"))

  private def activityFile(workflowId: WorkflowId, index: Int): Path =
    workflowDir(workflowId).resolve(s"activity-$index.json")

  /** Store workflow metadata */
  def storeMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata): Unit =
    val file = workflowDir(workflowId).resolve("metadata.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, writeToString(metadata), StandardCharsets.UTF_8)

  /** Load workflow metadata */
  def loadMetadata(workflowId: WorkflowId): Option[WorkflowMetadata] =
    val file = workflowDir(workflowId).resolve("metadata.json")
    if Files.exists(file) then
      val json = Files.readString(file, StandardCharsets.UTF_8)
      Some(readFromString[WorkflowMetadata](json))
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

  /** Clean up storage directory */
  def clear(): Unit =
    if Files.exists(baseDir) then
      Files.walk(baseDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))

object JsonFileStorage:
  def apply(baseDir: Path): JsonFileStorage = new JsonFileStorage(baseDir)
  def apply(baseDir: String): JsonFileStorage = new JsonFileStorage(Paths.get(baseDir))

  // JSON codecs for storage types
  given JsonValueCodec[StoredFailure] = JsonCodecMaker.make
  given JsonValueCodec[WorkflowMetadata] = JsonCodecMaker.make
  given JsonValueCodec[WorkflowStatus] = JsonCodecMaker.make

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
 * Workflow metadata stored for restoration.
 *
 * @param functionName Fully qualified function name for registry lookup
 * @param argTypes List of fully qualified type names for arguments
 * @param argsJson JSON-encoded arguments (as array)
 * @param activityIndex Current activity index (resume point)
 * @param status Current workflow status
 */
case class WorkflowMetadata(
  functionName: String,
  argTypes: List[String],  // Fully qualified type names
  argsJson: String,        // JSON-encoded arguments
  activityIndex: Int,
  status: WorkflowStatus
)

enum WorkflowStatus:
  case Running
  case Suspended
  case Completed
  case Failed
