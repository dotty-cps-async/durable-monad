package durable

import scala.concurrent.Future

/**
 * Storage backend for caching durable computation results.
 *
 * Type parameter:
 *   T - the value type being stored
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
trait DurableStorage[T]:
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

object DurableStorage:
  def apply[T](using storage: DurableStorage[T]): DurableStorage[T] = storage
