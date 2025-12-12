package durable

import scala.concurrent.Future

/**
 * Marker trait for durable storage types.
 * All storage implementations (MemoryStorage, PostgresDB, etc.) should extend this.
 * This allows the macro to find the storage type in scope.
 */
trait DurableStorage

/**
 * Async storage backend for caching durable computation results.
 *
 * Type parameters:
 *   T - the value type being stored
 *   S - the storage type (e.g., MemoryStorage, PostgresDB, etc.)
 *
 * All operations return Future to support async storage (DB, network, etc.).
 *
 * Cache key is (workflowId, activityIndex) - a single counter that increments
 * for each cached activity during workflow execution.
 */
trait DurableCacheBackend[T, S]:
  def store(storage: S, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]
  def retrieve(storage: S, workflowId: WorkflowId, activityIndex: Int): Future[Option[T]]

object DurableCacheBackend:
  def apply[T, S](using cache: DurableCacheBackend[T, S]): DurableCacheBackend[T, S] = cache
