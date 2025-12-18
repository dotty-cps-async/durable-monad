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
 * Owns the in-memory state and provides named operations that are
 * serialized to prevent race conditions. All state-mutating operations
 * go through this trait.
 *
 * Platform implementations:
 * - JVM: Uses single-threaded executor for serialization
 * - JS: No-op (JavaScript is single-threaded)
 * - Native: Uses dedicated thread with queue
 */
trait WorkflowStateCoordinator:

  // === Registration ===

  /** Register a new workflow as active */
  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit]

  /** Register the runner future for a workflow */
  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit]

  /** Register a timer handle for a workflow */
  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit]

  // === State Transitions ===

  /** Mark workflow as finished (completed or failed) - removes from active state */
  def markFinished(id: WorkflowId): Future[Unit]

  /**
   * Mark workflow as suspended with the given wait condition.
   * Removes runner, updates record to Suspended status.
   */
  def markSuspended(id: WorkflowId, activityIndex: Int, condition: EventQuery.Combined[?, ?]): Future[Unit]

  /**
   * Mark workflow as resumed (transition to Running).
   * Cancels any pending timer, updates record.
   * Returns the record if workflow was found and updated.
   */
  def markResumed(id: WorkflowId, newActivityIndex: Int): Future[Option[WorkflowRecord]]

  /** Update workflow for continue-as operation */
  def updateForContinueAs(id: WorkflowId, metadata: WorkflowMetadata): Future[Unit]

  // === Queries with Actions ===

  /** Find all workflows waiting for a specific event */
  def findWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]]

  /**
   * Get active workflow if suspended and remove its timer.
   * Used by timer callback to atomically check and prepare for resume.
   */
  def getAndRemoveTimer(id: WorkflowId): Future[Option[WorkflowRecord]]

  /**
   * Cancel a workflow - removes from active state.
   * Returns the record if workflow was found and cancelled.
   */
  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]]

  // === Bulk Operations ===

  /** Register multiple workflows recovered from storage */
  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit]

  /** Cancel all timers - returns handles that were cancelled */
  def cancelAllTimers(): Future[Seq[TimerHandle]]

  // === Read-Only Queries (eventually consistent) ===

  /** Get active workflow record (read-only, may be stale) */
  def getActive(id: WorkflowId): Option[WorkflowRecord]

  // === Lifecycle ===

  /** Shutdown the coordinator */
  def shutdown(): Future[Unit]
