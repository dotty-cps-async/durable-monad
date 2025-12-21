package durable

import java.time.Instant

/**
 * Metadata for a workflow instance - used for persistence and restart.
 *
 * Args are stored separately via DurableStorage at indices 0..argCount-1.
 * Activity results start at index argCount.
 *
 * Status is managed separately by the engine, not part of this metadata.
 *
 * @param functionName Fully qualified name of the DurableFunction (for registry lookup)
 * @param argCount Number of arguments (stored at indices 0..argCount-1)
 * @param activityIndex Current activity index (resume point)
 */
case class WorkflowMetadata(
  functionName: String,
  argCount: Int,
  activityIndex: Int
)

/**
 * Workflow execution status - managed by the engine.
 *
 * Terminal states:
 *   - Succeeded: finished with result value
 *   - Failed: finished with error/exception
 *   - Cancelled: externally cancelled
 */
enum WorkflowStatus:
  case Running
  case Suspended
  case Succeeded   // finished with result
  case Failed      // finished with error
  case Cancelled   // externally cancelled

  /** Check if this is a terminal state (no more transitions possible) */
  def isTerminal: Boolean = this match
    case Succeeded | Failed | Cancelled => true
    case Running | Suspended => false

/**
 * Full workflow record for persistence and in-memory cache.
 *
 * Wait condition info is stored as simple fields (not the full Combined)
 * since we only need to know what we're waiting for, not the storage types.
 */
case class WorkflowRecord(
  id: WorkflowId,
  metadata: WorkflowMetadata,
  status: WorkflowStatus,
  // Simple wait condition fields (no need to serialize Combined)
  waitingForEvents: Set[String],
  waitingForTimer: Option[Instant],
  waitingForWorkflows: Set[WorkflowId],
  parentId: Option[WorkflowId],
  createdAt: Instant,
  updatedAt: Instant
):
  /** Check if waiting for a specific event */
  def isWaitingForEvent(name: String): Boolean = waitingForEvents.contains(name)

  /** Check if waiting for any condition */
  def isWaiting: Boolean =
    waitingForEvents.nonEmpty || waitingForTimer.isDefined || waitingForWorkflows.nonEmpty

  /** Clear all wait conditions */
  def clearWaitConditions: WorkflowRecord = copy(
    waitingForEvents = Set.empty,
    waitingForTimer = None,
    waitingForWorkflows = Set.empty
  )

/**
 * Unique identifier for pending events.
 */
opaque type EventId = String

object EventId:
  def apply(s: String): EventId = s
  def generate(): EventId = java.util.UUID.randomUUID().toString

  extension (id: EventId)
    def value: String = id

/**
 * Event waiting to be delivered to a workflow.
 *
 * @param eventId Unique event identifier
 * @param eventName Event type name
 * @param value Event payload
 * @param timestamp When event was sent
 * @param onTargetTerminated Policy for handling if target workflow terminates (for targeted events)
 */
case class PendingEvent[E](
  eventId: EventId,
  eventName: String,
  value: E,
  timestamp: Instant,
  onTargetTerminated: DeadLetterPolicy = DeadLetterPolicy.Discard
)

/**
 * Dead letter event - a targeted event that was not delivered because
 * the target workflow terminated without reading it.
 *
 * @param eventId Unique event identifier
 * @param eventName Event type name
 * @param value Event payload
 * @param originalTarget The workflow ID that was supposed to receive this event
 * @param targetTerminatedAt When the target workflow terminated
 * @param targetStatus Why it wasn't delivered (Succeeded/Failed/Cancelled)
 * @param timestamp When the event was originally sent
 */
case class DeadEvent[E](
  eventId: EventId,
  eventName: String,
  value: E,
  originalTarget: WorkflowId,
  targetTerminatedAt: Instant,
  targetStatus: WorkflowStatus,
  timestamp: Instant
)
