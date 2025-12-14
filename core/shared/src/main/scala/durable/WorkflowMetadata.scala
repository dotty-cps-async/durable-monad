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

/**
 * Full workflow record for persistence and in-memory cache.
 */
case class WorkflowRecord(
  id: WorkflowId,
  metadata: WorkflowMetadata,
  status: WorkflowStatus,
  waitCondition: Option[WaitCondition[?, ?]],
  parentId: Option[WorkflowId],
  createdAt: Instant,
  updatedAt: Instant
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
 */
case class PendingEvent[E](
  eventId: EventId,
  eventName: String,
  value: E,
  timestamp: Instant
)
