package durable

import java.time.Instant

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
