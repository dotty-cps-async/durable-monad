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
   * Send an event to matching workflows.
   *
   * Delivers to workflows waiting for this event type.
   * Event config (expiry, consumeOnRead) derived from DurableEventConfig typeclass.
   */
  def sendEvent[E](event: E)(using
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

  // Observability APIs (listByStatus, history queries, metrics) out of scope for this design

object WorkflowEngine extends WorkflowEnginePlatform:
  /** Create engine - type S inferred from storage argument */
  def apply[S <: DurableStorageBackend](storage: S)(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S] =
    create(storage, WorkflowEngineConfig.default)

  /** Create engine with custom configuration */
  def apply[S <: DurableStorageBackend](storage: S, config: WorkflowEngineConfig)(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S] =
    create(storage, config)
