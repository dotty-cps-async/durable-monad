package durable

import scala.concurrent.{Future, ExecutionContext}

/**
 * Terminal workflow state - success or failure.
 * Extended by WorkflowSessionResult.Completed and WorkflowSessionResult.Failed.
 */
sealed trait WorkflowResult[+A]:
  def workflowId: WorkflowId

object WorkflowResult:
  /** Alias for WorkflowSessionResult.Completed */
  object Completed:
    def apply[A](workflowId: WorkflowId, value: A): WorkflowSessionResult.Completed[A] =
      WorkflowSessionResult.Completed(workflowId, value)
    def unapply[A](result: WorkflowResult[A]): Option[(WorkflowId, A)] =
      result match
        case WorkflowSessionResult.Completed(id, value) => Some((id, value))
        case _ => None

  /** Alias for WorkflowSessionResult.Failed */
  object Failed:
    def apply(workflowId: WorkflowId, error: ReplayedException): WorkflowSessionResult.Failed =
      WorkflowSessionResult.Failed(workflowId, error)
    def unapply(result: WorkflowResult[?]): Option[(WorkflowId, ReplayedException)] =
      result match
        case WorkflowSessionResult.Failed(id, error) => Some((id, error))
        case _ => None

/**
 * Result of a workflow session (one run of WorkflowSessionRunner).
 *
 * The runner interprets Durable (Free Monad) and produces this result.
 * Terminal cases (Completed, Failed) also extend WorkflowDone for use in EventQuery.
 */
enum WorkflowSessionResult[+A]:
  /** Workflow completed successfully with a result */
  case Completed(workflowId: WorkflowId, value: A) extends WorkflowSessionResult[A] with WorkflowResult[A]

  /** Workflow suspended, waiting for external input */
  case Suspended[S <: DurableStorageBackend](snapshot: WorkflowSessionRunner.DurableSnapshot, condition: EventQuery.Combined[?, S]) extends WorkflowSessionResult[Nothing]

  /** Workflow failed with an error (always wrapped in ReplayedException for consistent API) */
  case Failed(workflowId: WorkflowId, error: ReplayedException) extends WorkflowSessionResult[Nothing] with WorkflowResult[Nothing]

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
  ) extends WorkflowSessionResult[A]
