package durable

import java.time.Instant

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
 * Unique identifier for pending events.
 */
opaque type EventId = String

object EventId:
  def apply(s: String): EventId = s
  def generate(): EventId = java.util.UUID.randomUUID().toString

  extension (id: EventId)
    def value: String = id

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
