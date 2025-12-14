package durable

import scala.collection.mutable
import scala.concurrent.Future

/**
 * In-memory state for WorkflowEngine.
 *
 * Holds:
 * - Active workflow records (cached from storage)
 * - Running workflow futures
 * - Scheduled timer handles
 *
 * Note: State mutations should be sequenced through Future chaining.
 * Platform-specific implementations may use concurrent collections if needed.
 */
class WorkflowEngineState(
  private val activeMap: mutable.Map[WorkflowId, WorkflowRecord],
  private val runnersMap: mutable.Map[WorkflowId, Future[WorkflowResult[?]]],
  private val timersMap: mutable.Map[WorkflowId, TimerHandle]
):
  // Active workflows (Running or Suspended)

  def getActive(workflowId: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(workflowId)

  def putActive(workflowId: WorkflowId, record: WorkflowRecord): Unit =
    activeMap.put(workflowId, record)

  def removeActive(workflowId: WorkflowId): Option[WorkflowRecord] =
    activeMap.remove(workflowId)

  def updateActive(workflowId: WorkflowId, f: WorkflowRecord => WorkflowRecord): Unit =
    activeMap.get(workflowId).foreach { record =>
      activeMap.put(workflowId, f(record))
    }

  def activeWorkflows: Iterable[WorkflowRecord] = activeMap.values

  // Running workflows (have active Future)

  def getRunner(workflowId: WorkflowId): Option[Future[WorkflowResult[?]]] =
    runnersMap.get(workflowId)

  def putRunner(workflowId: WorkflowId, future: Future[WorkflowResult[?]]): Unit =
    runnersMap.put(workflowId, future)

  def removeRunner(workflowId: WorkflowId): Option[Future[WorkflowResult[?]]] =
    runnersMap.remove(workflowId)

  def isRunning(workflowId: WorkflowId): Boolean =
    runnersMap.contains(workflowId)

  // Timer handles (for cancellation)

  def getTimer(workflowId: WorkflowId): Option[TimerHandle] =
    timersMap.get(workflowId)

  def putTimer(workflowId: WorkflowId, handle: TimerHandle): Unit =
    timersMap.put(workflowId, handle)

  def removeTimer(workflowId: WorkflowId): Option[TimerHandle] =
    timersMap.remove(workflowId)

  // Clear all state
  def clear(): Unit =
    activeMap.clear()
    runnersMap.clear()
    timersMap.clear()

/**
 * Handle for a scheduled timer, allowing cancellation.
 */
trait TimerHandle:
  def cancel(): Unit

object WorkflowEngineState extends WorkflowEngineStatePlatform
