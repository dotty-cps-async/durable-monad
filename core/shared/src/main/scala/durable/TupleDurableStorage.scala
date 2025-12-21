package durable

import scala.concurrent.{Future, ExecutionContext}

/**
 * Pure typeclass providing DurableStorage for each element of a tuple.
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
  def storeAll(backend: S, workflowId: WorkflowId, startIndex: Int, args: T)(using ExecutionContext): Future[Unit]

  /** Retrieve all tuple elements starting at startIndex */
  def retrieveAll(backend: S, workflowId: WorkflowId, startIndex: Int)(using ExecutionContext): Future[Option[T]]

  /** Number of elements in the tuple */
  def size: Int

object TupleDurableStorage:
  /** Base case: empty tuple */
  given [S <: DurableStorageBackend]: TupleDurableStorage[EmptyTuple, S] with
    def storeAll(backend: S, workflowId: WorkflowId, startIndex: Int, args: EmptyTuple)(using ExecutionContext): Future[Unit] =
      Future.successful(())
    def retrieveAll(backend: S, workflowId: WorkflowId, startIndex: Int)(using ExecutionContext): Future[Option[EmptyTuple]] =
      Future.successful(Some(EmptyTuple))
    def size: Int = 0

  /** Inductive case: H *: T */
  given [H, T <: Tuple, S <: DurableStorageBackend](using
    headStorage: DurableStorage[H, S],
    tailStorage: TupleDurableStorage[T, S]
  ): TupleDurableStorage[H *: T, S] with
    def storeAll(backend: S, workflowId: WorkflowId, startIndex: Int, args: H *: T)(using ExecutionContext): Future[Unit] =
      val head *: tail = args
      for
        _ <- headStorage.storeStep(backend, workflowId, startIndex, head)
        _ <- tailStorage.storeAll(backend, workflowId, startIndex + 1, tail)
      yield ()
    def retrieveAll(backend: S, workflowId: WorkflowId, startIndex: Int)(using ExecutionContext): Future[Option[H *: T]] =
      for
        headOpt <- headStorage.retrieveStep(backend, workflowId, startIndex)
        tailOpt <- tailStorage.retrieveAll(backend, workflowId, startIndex + 1)
      yield
        for
          headEither <- headOpt
          head <- headEither.toOption  // Ignore failures - args should always be stored as success
          tail <- tailOpt
        yield head *: tail
    def size: Int = 1 + tailStorage.size
