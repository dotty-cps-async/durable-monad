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
  case Suspended(snapshot: DurableSnapshot, condition: WaitCondition[?])

  /** Workflow failed with an error */
  case Failed(error: Throwable)

  /**
   * Workflow requested to continue as a new workflow.
   * Engine should clear activity storage, store new args, and run the new workflow.
   *
   * @param metadata New workflow metadata (functionName, argCount, activityIndex=argCount)
   * @param storeArgs Closure to store new args at indices 0..argCount-1
   * @param workflow Thunk to create the new workflow (lazy to avoid infinite recursion)
   * @param backend Storage backend for clear operation
   */
  case ContinueAs(
    metadata: WorkflowMetadata,
    storeArgs: (WorkflowId, ExecutionContext) => Future[Unit],
    workflow: () => Durable[?],
    backend: DurableStorageBackend
  )
