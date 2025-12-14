package durable

import scala.concurrent.Future

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

/**
 * Storage backend for caching durable computation results.
 *
 * Type parameters:
 *   T - the value type being stored
 *   S - the backing store type (extends DurableStorageBackend)
 *
 * All operations return Future to support async storage (DB, network, etc.).
 *
 * Cache key is (workflowId, activityIndex) - a single counter that increments
 * for each cached activity during workflow execution.
 *
 * Implementations encapsulate their backing store (memory, database, etc.)
 * and handle serialization internally if needed.
 *
 * Both successes and failures are stored for deterministic replay.
 * If an activity failed and was handled by a catch block, replaying
 * will return the same failure (as ReplayedException).
 */
trait DurableStorage[T, S <: DurableStorageBackend]:
  /** Store a successful result */
  def store(workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]

  /** Store a failure result as StoredFailure (easily serializable) */
  def storeFailure(workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit]

  /**
   * Retrieve cached result.
   * Returns:
   *   - None if not cached
   *   - Some(Left(failure)) if failure was cached
   *   - Some(Right(value)) if success was cached
   */
  def retrieve(workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]]

  /** Access to the underlying storage backend for workflow-level operations */
  def backend: S

object DurableStorage:
  def apply[T, S <: DurableStorageBackend](using storage: DurableStorage[T, S]): DurableStorage[T, S] = storage
