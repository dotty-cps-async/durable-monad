package durable

import scala.concurrent.{Future, ExecutionContext}

/**
 * Type class providing DurableStorage for each element of a tuple.
 * Enables storing tuple arguments with a single method call.
 *
 * Type parameters:
 *   T - the tuple type
 *   S - the backing store type (extends DurableStorageBackend)
 *
 * Example:
 *   given TupleDurableStorage[(String, Int), MyBackend] is derived automatically
 *   when DurableStorage[String, MyBackend] and DurableStorage[Int, MyBackend] are in scope.
 */
trait TupleDurableStorage[T <: Tuple, S <: DurableStorageBackend]:
  /** Store all tuple elements starting at startIndex */
  def storeAll(workflowId: WorkflowId, startIndex: Int, args: T)(using ExecutionContext): Future[Unit]

  /** Number of elements in the tuple */
  def size: Int

  /** Access to the underlying storage backend */
  def backend: S

object TupleDurableStorage:
  /** Base case: empty tuple */
  given [S <: DurableStorageBackend](using b: S): TupleDurableStorage[EmptyTuple, S] with
    def storeAll(workflowId: WorkflowId, startIndex: Int, args: EmptyTuple)(using ExecutionContext): Future[Unit] =
      Future.successful(())
    def size: Int = 0
    def backend: S = b

  /** Inductive case: H *: T */
  given [H, T <: Tuple, S <: DurableStorageBackend](using
    headStorage: DurableStorage[H, S],
    tailStorage: TupleDurableStorage[T, S]
  ): TupleDurableStorage[H *: T, S] with
    def storeAll(workflowId: WorkflowId, startIndex: Int, args: H *: T)(using ExecutionContext): Future[Unit] =
      val head *: tail = args
      for
        _ <- headStorage.store(workflowId, startIndex, head)
        _ <- tailStorage.storeAll(workflowId, startIndex + 1, tail)
      yield ()
    def size: Int = 1 + tailStorage.size
    def backend: S = headStorage.backend
