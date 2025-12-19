package durable.engine

import scala.concurrent.Future
import java.time.Instant

import durable.*

/**
 * Handle for a scheduled timer, allowing cancellation.
 */
trait TimerHandle:
  def cancel(): Unit

/**
 * Coordinator for workflow state operations.
 *
 * Owns the in-memory state and provides operations that are serialized
 * to prevent race conditions. All state-mutating operations go through
 * the submit method which executes operations on a single-threaded executor.
 *
 * The coordinator combines in-memory state management with blocking storage
 * calls to ensure atomicity of check-then-act sequences.
 *
 * Platform implementations:
 * - JVM: Uses single-threaded executor for serialization
 * - JS: No-op (JavaScript is single-threaded)
 * - Native: Uses dedicated thread with queue
 */
trait WorkflowStateCoordinator:

  /**
   * Submit operation to coordinator queue.
   * Returns Future that completes when operation is processed.
   *
   * This is the primary method for all state-changing operations.
   * Operations are executed sequentially on a dedicated thread to
   * prevent race conditions.
   */
  def submit[R](op: CoordinatorOp[R]): Future[R]

  /**
   * Submit batch of operations for potential fusion.
   * Operations may be reordered/combined if safe.
   */
  def submitBatch(ops: Seq[CoordinatorOp[?]]): Future[Seq[?]]

  /**
   * Read-only query (eventually consistent, no queue).
   * This method can be called from any thread without synchronization.
   */
  def getActive(id: WorkflowId): Option[WorkflowRecord]

  // === Legacy API (convenience wrappers around submit) ===

  /** Register a new workflow as active */
  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit] =
    submit(CoordinatorOp.RegisterWorkflow(id, record))

  /** Register the runner future for a workflow */
  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit] =
    submit(CoordinatorOp.RegisterRunner(id, runner))

  /** Register a timer handle for a workflow */
  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit] =
    submit(CoordinatorOp.RegisterTimer(id, handle))

  /** Mark workflow as finished (completed or failed) - removes from active state */
  def markFinished(id: WorkflowId): Future[Unit] =
    submit(CoordinatorOp.MarkFinished(id))

  /** Update workflow for continue-as operation */
  def updateForContinueAs(id: WorkflowId, metadata: WorkflowMetadata): Future[Unit] =
    submit(CoordinatorOp.UpdateForContinueAs(id, metadata))

  /** Cancel a workflow - removes from active state */
  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]] =
    submit(CoordinatorOp.CancelWorkflow(id))

  /** Register multiple workflows recovered from storage */
  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit] =
    submit(CoordinatorOp.RecoverWorkflows(records))

  /** Cancel all timers - returns handles that were cancelled */
  def cancelAllTimers(): Future[Seq[TimerHandle]] =
    submit(CoordinatorOp.CancelAllTimers())

  /** Shutdown the coordinator */
  def shutdown(): Future[Unit] =
    submit(CoordinatorOp.Shutdown())
