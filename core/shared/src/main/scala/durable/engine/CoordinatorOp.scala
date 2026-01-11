package durable.engine

import java.time.Instant
import scala.concurrent.Future

import durable.*

/**
 * All coordinator operations as data.
 * Each operation is a complete description of what needs to happen.
 *
 * Benefits:
 * - Clear enumeration of all state-changing operations
 * - Testability: can inspect/log operations before execution
 * - Fusion: combine operations for efficiency
 * - Single execution path: all ops go through submit → executeOp
 * - Type safety: CoordinatorOp[R] returns Future[R]
 */
enum CoordinatorOp[+R]:
  // === Registration ===
  case RegisterWorkflow(
    id: WorkflowId,
    record: WorkflowRecord
  ) extends CoordinatorOp[Unit]

  case RegisterRunner(
    id: WorkflowId,
    runner: Future[WorkflowSessionResult[?]]
  ) extends CoordinatorOp[Unit]

  case RegisterTimer(
    id: WorkflowId,
    handle: TimerHandle
  ) extends CoordinatorOp[Unit]

  // === State Transitions ===
  case SuspendAndCheckPending(
    id: WorkflowId,
    activityIndex: Int,
    condition: EventQuery.Combined[?, ?],
    eventStorages: Map[String, DurableStorage[?, ?]]
  ) extends CoordinatorOp[SuspendResult]

  case SendBroadcastEvent[E](
    eventName: String,
    event: E,
    eventId: EventId,
    timestamp: Instant,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ) extends CoordinatorOp[SendResult]

  case SendTargetedEvent[E](
    targetWorkflowId: WorkflowId,
    eventName: String,
    event: E,
    eventId: EventId,
    timestamp: Instant,
    policy: DeadLetterPolicy,
    eventStorage: DurableStorage[E, ? <: DurableStorageBackend]
  ) extends CoordinatorOp[SendResult]

  case HandleTimerFired(
    workflowId: WorkflowId,
    wakeAt: Instant,
    activityIndex: Int,
    timeReachedStorage: DurableStorage[TimeReached, ?]
  ) extends CoordinatorOp[Option[WorkflowRecord]]

  case MarkFinished(
    id: WorkflowId
  ) extends CoordinatorOp[Unit]

  case CancelWorkflow(
    id: WorkflowId
  ) extends CoordinatorOp[Option[WorkflowRecord]]

  case UpdateForContinueAs(
    id: WorkflowId,
    metadata: WorkflowMetadata
  ) extends CoordinatorOp[Unit]

  // === Bulk Operations ===
  case RecoverWorkflows(
    records: Seq[WorkflowRecord]
  ) extends CoordinatorOp[Unit]

  case CancelAllTimers() extends CoordinatorOp[Seq[TimerHandle]]

  case Shutdown() extends CoordinatorOp[Unit]

  // === Lazy Loading Operations ===

  /**
   * Ensure workflow is loaded into memory.
   * If already in memory, returns immediately.
   * If not, loads from storage and adds to activeMap.
   */
  case EnsureLoaded(
    workflowId: WorkflowId
  ) extends CoordinatorOp[Option[WorkflowRecord]]

  /**
   * Evict workflows from in-memory cache.
   * State remains in storage - only removes from activeMap.
   * Returns count of workflows evicted.
   */
  case EvictFromCache(
    workflowIds: Seq[WorkflowId]
  ) extends CoordinatorOp[Int]

  /**
   * Evict suspended workflows not accessed since the threshold time.
   * Performs filtering internally since coordinator has access to activeMap.
   * Returns count of workflows evicted.
   */
  case EvictByTtl(
    notAccessedSince: Instant
  ) extends CoordinatorOp[Int]

  /**
   * Update lastAccessedAt timestamp for a workflow.
   * Used to track activity for TTL-based eviction.
   */
  case TouchWorkflow(
    workflowId: WorkflowId
  ) extends CoordinatorOp[Unit]

/**
 * Result of SuspendAndCheckPending operation.
 */
enum SuspendResult:
  /** Pending event was found and delivered - workflow resumed */
  case Delivered(record: WorkflowRecord, eventName: String)
  /** No pending event found - workflow is now suspended */
  case Suspended

/**
 * Result of SendBroadcastEvent or SendTargetedEvent operation.
 */
enum SendResult:
  /** Event was delivered to a waiting workflow */
  case Delivered(workflowId: WorkflowId)
  /** Event was queued for later delivery */
  case Queued(eventId: EventId)
  /** Target workflow has terminated (for targeted events) */
  case TargetTerminated(status: WorkflowStatus)
  /** Target workflow not found (for targeted events) */
  case TargetNotFound
