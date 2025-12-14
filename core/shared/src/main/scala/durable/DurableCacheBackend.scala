package durable

import scala.concurrent.Future
import java.time.Instant

/**
 * Marker trait for storage backends that handle workflow-level operations.
 *
 * Implementations provide both workflow-level operations (clear, metadata)
 * and typed storage via `forType[T]`.
 *
 * This trait enables the ContinueAs pattern where workflow history is cleared
 * before restarting with new arguments.
 */
trait DurableStorageBackend:
  /** Clear all cached data for a workflow (for ContinueAs pattern) */
  def clear(workflowId: WorkflowId): Future[Unit]

  // Engine: workflow metadata operations

  /** Save workflow metadata and status */
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit]

  /** Load workflow metadata */
  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]]

  /** Update workflow status only */
  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit]

  /** Update workflow status and wait condition */
  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitCondition: Option[WaitCondition[?, ?]]
  ): Future[Unit]

  /** List all active (Running or Suspended) workflows */
  def listActiveWorkflows(): Future[Seq[WorkflowRecord]]

  // Engine: pending events operations

  /** Save a pending event */
  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit]

  /** Load pending events for a given event name */
  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]]

  /** Remove a pending event after delivery */
  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit]

/**
 * Pure typeclass for caching durable computation results.
 *
 * Type parameters:
 *   T - the value type being stored
 *   S - the backing store type (extends DurableStorageBackend)
 *
 * This is a stateless typeclass - storage backend is passed as parameter.
 * Instances can be created/summoned without a backend instance, enabling
 * registration at compile time and use with any backend instance at runtime.
 *
 * All operations return Future to support async storage (DB, network, etc.).
 *
 * Cache key is (workflowId, activityIndex) - a single counter that increments
 * for each cached activity during workflow execution.
 *
 * Both successes and failures are stored for deterministic replay.
 * If an activity failed and was handled by a catch block, replaying
 * will return the same failure (as ReplayedException).
 */
trait DurableStorage[T, S <: DurableStorageBackend]:
  /** Store a successful result */
  def store(backend: S, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]

  /** Store a failure result as StoredFailure (easily serializable) */
  def storeFailure(backend: S, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit]

  /**
   * Retrieve cached result.
   * Returns:
   *   - None if not cached
   *   - Some(Left(failure)) if failure was cached
   *   - Some(Right(value)) if success was cached
   */
  def retrieve(backend: S, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]]

object DurableStorage:
  def apply[T, S <: DurableStorageBackend](using storage: DurableStorage[T, S]): DurableStorage[T, S] = storage
