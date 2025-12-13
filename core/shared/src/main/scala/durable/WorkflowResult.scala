package durable

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
