package durable

import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * WorkflowEngine manages multiple concurrent workflows.
 *
 * Type parameter S is the storage type - inferred from constructor argument.
 * Same storage used for activity results, workflow metadata, and events.
 */
trait WorkflowEngine[S <: DurableStorageBackend]:
  /** The storage instance */
  def storage: S

  /**
   * Start a new workflow, returns workflow ID.
   *
   * Storage typeclasses are obtained from the function's trait context parameters.
   *
   * @param function The workflow function to execute (must be for this backend type S)
   * @param args Arguments to pass to the workflow
   * @param workflowId Optional specific ID (auto-generated if None)
   * @return Future containing the workflow ID once durably registered
   */
  def start[Args <: Tuple, R](
    function: DurableFunction[Args, R, S],
    args: Args,
    workflowId: Option[WorkflowId] = None
  ): Future[WorkflowId]

  /**
   * Send an event to a specific workflow by ID.
   *
   * If the workflow is waiting for this event type, delivers immediately.
   * If the workflow is running or waiting for a different condition, queues for later delivery.
   * The `onTargetTerminated` policy from DurableEventConfig controls what happens if the
   * workflow terminates without reading the event.
   *
   * @throws WorkflowNotFoundException if workflow doesn't exist
   * @throws WorkflowTerminatedException if workflow is in terminal state (Succeeded/Failed/Cancelled)
   */
  def sendEventTo[E](workflowId: WorkflowId, event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S],
    eventConfig: DurableEventConfig[E] = DurableEventConfig.defaultEventConfig[E]
  ): Future[Unit]

  /**
   * Broadcast an event to workflows waiting for this event type.
   *
   * Delivers to first workflow waiting for this event type.
   * If no workflows are waiting, queues the event for future subscribers.
   * Event config (expiry, consumeOnRead) derived from DurableEventConfig typeclass.
   */
  def sendEventBroadcast[E](event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Unit]

  /** Query workflow status */
  def queryStatus(workflowId: WorkflowId): Future[Option[WorkflowStatus]]

  /** Query workflow result (if completed) */
  def queryResult[A](workflowId: WorkflowId)(using DurableStorage[A, S]): Future[Option[A]]

  /** Cancel a running or suspended workflow */
  def cancel(workflowId: WorkflowId): Future[Boolean]

  /** Recover all workflows on startup */
  def recover(): Future[RecoveryReport]

  /** Shutdown the engine gracefully */
  def shutdown(): Future[Unit]

  // Dead letter management (for undelivered targeted events)

  /** Query dead letter events by type */
  def queryDeadLetters[E](using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Seq[DeadEvent[E]]]

  /** Replay a dead letter event to broadcast queue */
  def replayDeadLetter(eventId: EventId): Future[Boolean]

  /** Replay a dead letter event to a specific workflow */
  def replayDeadLetterTo(eventId: EventId, workflowId: WorkflowId): Future[Unit]

  /** Remove a dead letter event */
  def removeDeadLetter(eventId: EventId): Future[Boolean]

  // Observability APIs (listByStatus, history queries, metrics) out of scope for this design

object WorkflowEngine extends WorkflowEnginePlatform:
  /** Create engine - type S inferred from storage argument */
  def apply[S <: DurableStorageBackend](storage: S)(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S] =
    create(storage, WorkflowEngineConfig.default)

  /** Create engine with custom configuration */
  def apply[S <: DurableStorageBackend](storage: S, config: WorkflowEngineConfig)(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S] =
    create(storage, config)

/**
 * Exception thrown when sendEventTo targets a workflow that doesn't exist.
 */
case class WorkflowNotFoundException(workflowId: WorkflowId)
  extends Exception(s"Workflow not found: ${workflowId.value}")
  with NonRecoverableException

/**
 * Exception thrown when sendEventTo targets a workflow in terminal state.
 */
case class WorkflowTerminatedException(workflowId: WorkflowId, status: WorkflowStatus)
  extends Exception(s"Workflow ${workflowId.value} is terminated with status: $status")
  with NonRecoverableException
