package durable

import java.time.Instant

/**
 * Conditions that a workflow can wait for.
 *
 * Type parameters:
 *   A - the result type when the condition is satisfied
 *   S - the storage backend type (captures DurableStorage for the result)
 *
 * Each condition carries its own DurableStorage[A, S] for:
 *   - Storing the result when the condition is satisfied
 *   - Retrieving the cached result during replay
 *
 * This allows workflows to wait for custom event types - the storage
 * is captured at construction time when the concrete backend type is known.
 */
enum WaitCondition[A, S <: DurableStorageBackend]:
  /** Wait for a broadcast event of type E */
  case Event[E, S <: DurableStorageBackend](
    eventName: String,
    storage: DurableStorage[E, S]
  ) extends WaitCondition[E, S]

  /** Wait until a specific instant, returns actual wake time */
  case Timer[S <: DurableStorageBackend](
    wakeAt: Instant,
    storage: DurableStorage[Instant, S]
  ) extends WaitCondition[Instant, S]

  /** Wait for a child workflow to complete, returns its result directly */
  case ChildWorkflow[R, S <: DurableStorageBackend](
    childId: WorkflowId,
    resultType: String,
    storage: DurableStorage[R, S]
  ) extends WaitCondition[R, S]

  /** Get the storage for this condition */
  def getStorage: DurableStorage[A, S] = this match
    case e: Event[_, _] => e.storage.asInstanceOf[DurableStorage[A, S]]
    case t: Timer[_] => t.storage.asInstanceOf[DurableStorage[A, S]]
    case c: ChildWorkflow[_, _] => c.storage.asInstanceOf[DurableStorage[A, S]]

  /** Get the event name (for Event conditions) */
  def getEventName: Option[String] = this match
    case e: Event[_, _] => Some(e.eventName)
    case _ => None
