package durable

import scala.concurrent.{Future, ExecutionContext}

/**
 * Final result of a durable workflow execution.
 *
 * The runner interprets Durable (Free Monad) and eventually produces this result.
 */
enum WorkflowResult[+A]:
  /** Workflow completed successfully with a result */
  case Completed(value: A)

  /** Workflow suspended, waiting for external input */
  case Suspended[S <: DurableStorageBackend](snapshot: DurableSnapshot, condition: WaitCondition[?, S]) extends WorkflowResult[Nothing]

  /** Workflow failed with an error (always wrapped in ReplayedException for consistent API) */
  case Failed(error: ReplayedException)

  /**
   * Workflow requested to continue as a new workflow.
   * Engine should clear activity storage, store new args, and run the new workflow.
   *
   * @param metadata New workflow metadata (functionName, argCount, activityIndex=argCount)
   * @param storeArgs Closure to store new args - takes (backend, workflowId, ec)
   * @param workflow Thunk to create the new workflow (lazy to avoid infinite recursion)
   */
  case ContinueAs[A](
    metadata: WorkflowMetadata,
    storeArgs: (DurableStorageBackend, WorkflowId, ExecutionContext) => Future[Unit],
    workflow: () => Durable[A]
  ) extends WorkflowResult[A]
