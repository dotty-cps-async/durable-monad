package durable

import scala.concurrent.Future
import java.time.Instant

import durable.engine.{WorkflowMetadata, WorkflowRecord, PendingEvent}

/**
 * Marker trait for storage backends that handle workflow-level operations.
 *
 * Implementations provide both workflow-level operations (clear, metadata)
 * and typed storage via `forType[T]`.
 *
 * This trait enables the ContinueAs pattern where workflow history is cleared
 * before restarting with new arguments.
 */
trait DurableStorageBackend:
  /** Clear all cached data for a workflow (for ContinueAs pattern) */
  def clear(workflowId: WorkflowId): Future[Unit]

  // Engine: workflow metadata operations

  /** Save workflow metadata and status */
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit]

  /** Load workflow metadata */
  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]]

  /** Load full workflow record (including wait conditions) for lazy loading */
  def loadWorkflowRecord(workflowId: WorkflowId): Future[Option[WorkflowRecord]]

  /** Update workflow status only */
  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit]

  /** Update workflow status and wait condition (simple fields, not Combined) */
  def updateWorkflowStatusAndCondition(
    workflowId: WorkflowId,
    status: WorkflowStatus,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit]

  /** List all active (Running or Suspended) workflows */
  def listActiveWorkflows(): Future[Seq[WorkflowRecord]]

  /** List workflows by specific status */
  def listWorkflowsByStatus(status: WorkflowStatus): Future[Seq[WorkflowRecord]]

  /** List suspended workflows with timer before deadline (for periodic sweep) */
  def listWorkflowsWithTimerBefore(deadline: Instant): Future[Seq[WorkflowRecord]]

  /** List suspended workflows waiting for a specific event type */
  def listWorkflowsWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]]

  /** List all pending broadcast events (for recovery) */
  def listAllPendingBroadcastEvents(): Future[Seq[(String, PendingEvent[?])]]

  // Combined query: winning condition tracking for replay

  /** Store which condition fired for a combined query (for replay) */
  def storeWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int,
    winning: SingleEventQuery[?]
  ): Future[Unit]

  /** Retrieve which condition fired (for replay) */
  def retrieveWinningCondition(
    workflowId: WorkflowId,
    activityIndex: Int
  ): Future[Option[SingleEventQuery[?]]]

  // Engine: broadcast pending events operations (shared by event name)

  /** Save a pending broadcast event */
  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit]

  /** Load pending broadcast events for a given event name */
  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]]

  /** Remove a pending broadcast event after delivery */
  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit]

  // Engine: targeted pending events operations (per-workflow)

  /** Save a pending event targeted at a specific workflow */
  def saveWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId, value: Any, timestamp: Instant, policy: DeadLetterPolicy = DeadLetterPolicy.Discard): Future[Unit]

  /** Load pending events targeted at a specific workflow for a given event name */
  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]]

  /** Remove a pending targeted event after delivery */
  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit]

  /** Clear all pending events for a workflow (called when workflow terminates) */
  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit]

  /** Load all pending events for a workflow (all event types) */
  def loadAllWorkflowPendingEvents(workflowId: WorkflowId): Future[Seq[PendingEvent[?]]]

  // Engine additions: dead letter storage

  /** Save a dead letter event */
  def saveDeadEvent(eventName: String, deadEvent: DeadEvent[?]): Future[Unit]

  /** Load dead letter events for a given event name */
  def loadDeadEvents(eventName: String): Future[Seq[DeadEvent[?]]]

  /** Remove a dead letter event after replay or manual removal */
  def removeDeadEvent(eventName: String, eventId: EventId): Future[Unit]

  /** Load a specific dead event by ID (across all event names) */
  def loadDeadEventById(eventId: EventId): Future[Option[(String, DeadEvent[?])]]

  // === Composite operations for atomic persistence ===

  /**
   * Atomically deliver event to workflow (single DB transaction):
   * - Store winning condition
   * - Store event value at activity index
   * - Update workflow status to Running
   *
   * Database impl: single transaction
   * Memory impl: sequential operations (coordinator already single-threaded)
   */
  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ): Future[Unit]

  /**
   * Atomically deliver pending event and remove from queue:
   * - Store winning condition
   * - Store event value
   * - Update status to Running
   * - Remove from pending queue (broadcast or targeted)
   */
  def deliverPendingEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend],
    pendingEventId: EventId,
    eventName: String,
    isTargeted: Boolean
  ): Future[Unit]

  /**
   * Atomically suspend workflow:
   * - Save workflow metadata
   * - Update status to Suspended with wait conditions
   */
  def suspendWorkflow(
    workflowId: WorkflowId,
    metadata: WorkflowMetadata,
    waitingForEvents: Set[String],
    waitingForTimer: Option[Instant],
    waitingForWorkflows: Set[WorkflowId]
  ): Future[Unit]

  /**
   * Atomically deliver timer to workflow:
   * - Store winning condition (TimerInstant)
   * - Store TimeReached value
   * - Update status to Running
   */
  def deliverTimer(
    workflowId: WorkflowId,
    activityIndex: Int,
    wakeAt: Instant,
    timeReached: TimeReached,
    timeReachedStorage: DurableStorage[TimeReached, ? <: DurableStorageBackend]
  ): Future[Unit]

/**
 * Pure typeclass for caching durable computation results.
 *
 * Type parameters:
 *   T - the value type being stored
 *   S - the backing store type (extends DurableStorageBackend)
 *
 * This is a stateless typeclass - storage backend is passed as parameter.
 * Instances can be created/summoned without a backend instance, enabling
 * registration at compile time and use with any backend instance at runtime.
 *
 * All operations return Future to support async storage (DB, network, etc.).
 *
 * Cache key is (workflowId, activityIndex) - a single counter that increments
 * for each cached activity during workflow execution.
 *
 * Both successes and failures are stored for deterministic replay.
 * If an activity failed and was handled by a catch block, replaying
 * will return the same failure (as ReplayedException).
 */
trait DurableStorage[T, S <: DurableStorageBackend]:
  /** Store a successful step result (activity/timer/event) */
  def storeStep(backend: S, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]

  /** Store a step failure as StoredFailure (easily serializable) */
  def storeStepFailure(backend: S, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit]

  /**
   * Retrieve cached step result.
   * Returns:
   *   - None if not cached
   *   - Some(Left(failure)) if failure was cached
   *   - Some(Right(value)) if success was cached
   */
  def retrieveStep(backend: S, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]]

  /** Store workflow final result (when workflow completes successfully) */
  def storeResult(backend: S, workflowId: WorkflowId, value: T): Future[Unit]

  /** Retrieve workflow final result */
  def retrieveResult(backend: S, workflowId: WorkflowId): Future[Option[T]]

object DurableStorage:
  def apply[T, S <: DurableStorageBackend](using storage: DurableStorage[T, S]): DurableStorage[T, S] = storage
