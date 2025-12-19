# WorkflowStateCoordinator Architecture

## Overview

The `WorkflowStateCoordinator` serializes all state-mutating operations, combining in-memory state management with blocking storage calls to ensure atomicity of check-then-act sequences. This prevents race conditions between concurrent operations like `sendEventTo`/`sendEventBroadcast` and `handleSuspended`.

## Problem Solved

### Race Condition Example

Without coordination, the following race could occur:

```
Thread 1 (sendEventBroadcast):              Thread 2 (handleSuspended):
1. findWaitingForEvent → empty
                                            2. markSuspended (workflow now waiting)
                                            3. checkPendingEventsInternal → empty
4. savePendingEvent
                                            5. persist suspended state

Result: Event saved as pending, workflow suspended waiting - no delivery!
```

### Crash Recovery Example

Non-atomic write order could cause data loss:
```scala
_ <- storage.removePendingEvent(...)     // 1. Remove from queue
_ <- storage.storeWinningCondition(...)  // 2. Store which event won
_ <- storage.storeStep(...)              // 3. Store event value
_ <- storage.updateWorkflowStatus(...)   // 4. Update status
```

Crash after step 1, before step 3: **Event lost!**

## Solution

Route ALL state-changing operations (in-memory + storage) through coordinator's single-threaded executor, using blocking storage calls and composite storage operations.

### Key Principles

1. **Atomicity via single thread**: Full check-then-act sequences run without interleaving
2. **Blocking is acceptable**: Coordinator has dedicated thread, blocking doesn't affect other threads
3. **Atomic storage operations**: Multiple writes in single database transaction
4. **Idempotent recovery**: Check for partially-completed operations on startup

## Coordinator Interface

```scala
trait WorkflowStateCoordinator:
  /** Primary method - all state-changing operations go through here */
  def submit[R](op: CoordinatorOp[R]): Future[R]

  /** Batch submission with potential fusion optimization */
  def submitBatch(ops: Seq[CoordinatorOp[?]]): Future[Seq[?]]

  /** Read-only query (eventually consistent, no queue) */
  def getActive(id: WorkflowId): Option[WorkflowRecord]

  // Legacy API (convenience wrappers around submit)
  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit]
  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit]
  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit]
  def markFinished(id: WorkflowId): Future[Unit]
  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]]
  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit]
  def cancelAllTimers(): Future[Seq[TimerHandle]]
  def shutdown(): Future[Unit]
```

## CoordinatorOp ADT

All coordinator operations are represented as a typed ADT (`CoordinatorOp[R]`) for better composition, testing, and fusion. Operation categories:

- **Registration**: `RegisterWorkflow`, `RegisterRunner`, `RegisterTimer`
- **Atomic state transitions**: `SuspendAndCheckPending`, `SendBroadcastEvent`, `SendTargetedEvent`, `HandleTimerFired`
- **Lifecycle**: `MarkFinished`, `CancelWorkflow`, `UpdateForContinueAs`
- **Bulk**: `RecoverWorkflows`, `CancelAllTimers`, `Shutdown`

Key result types:

```scala
enum SuspendResult:
  case Delivered(record: WorkflowRecord, eventName: String)  // Pending event found and delivered
  case Suspended  // No pending event, workflow is now suspended

enum SendResult:
  case Delivered(workflowId: WorkflowId)  // Event delivered to waiting workflow
  case Queued(eventId: EventId)           // Event queued for later
  case TargetTerminated(status: WorkflowStatus)
  case TargetNotFound
```

See `CoordinatorOp.scala` for full definitions.

## Composite Storage API

Atomic operations in `DurableStorageBackend` that combine multiple writes into single transactions:

```scala
trait DurableStorageBackend:
  // ... existing methods ...

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
    eventStorage: DurableStorage[E, ?]
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
    eventStorage: DurableStorage[E, ?],
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
    timeReachedStorage: DurableStorage[TimeReached, ?]
  ): Future[Unit]
```

### Database Implementation Example

```scala
class PostgresStorageBackend extends DurableStorageBackend:

  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ?]
  ): Future[Unit] = Future {
    db.transaction { tx =>
      tx.execute("""
        INSERT INTO winning_conditions (workflow_id, activity_index, condition_type, event_name)
        VALUES (?, ?, ?, ?)
      """, workflowId, activityIndex, "event", winningCondition.eventName)

      tx.execute("""
        INSERT INTO activity_steps (workflow_id, activity_index, value)
        VALUES (?, ?, ?)
      """, workflowId, activityIndex, serialize(eventValue))

      tx.execute("""
        UPDATE workflows SET status = 'Running', updated_at = NOW()
        WHERE id = ?
      """, workflowId)
    }
  }

  def deliverPendingEvent[E](...): Future[Unit] = Future {
    db.transaction { tx =>
      // Same as deliverEvent plus:
      if isTargeted then
        tx.execute("DELETE FROM workflow_pending_events WHERE workflow_id = ? AND event_name = ? AND event_id = ?", ...)
      else
        tx.execute("DELETE FROM pending_events WHERE event_name = ? AND event_id = ?", ...)
    }
  }
```

### Memory Implementation Example

```scala
class MemoryBackingStore extends DurableStorageBackend:

  def deliverEvent[E](
    workflowId: WorkflowId,
    activityIndex: Int,
    winningCondition: SingleEventQuery[?],
    eventValue: E,
    eventStorage: DurableStorage[E, ?]
  ): Future[Unit] =
    // Already protected by coordinator's single thread - just do sequentially
    winningConditions.put((workflowId, activityIndex), winningCondition)
    activityStore.put((workflowId, activityIndex), Right(eventValue))
    workflowRecords.updateWith(workflowId)(_.map(_.copy(
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    )))
    Future.successful(())

  def deliverPendingEvent[E](...): Future[Unit] =
    deliverEvent(workflowId, activityIndex, winningCondition, eventValue, eventStorage)
      .map { _ =>
        if isTargeted then
          workflowPendingEvents.get(workflowId).foreach(_.get(eventName).foreach { events =>
            val idx = events.indexWhere(_.eventId == pendingEventId)
            if idx >= 0 then events.remove(idx)
          })
        else
          pendingEvents.get(eventName).foreach { events =>
            val idx = events.indexWhere(_.eventId == pendingEventId)
            if idx >= 0 then events.remove(idx)
          }
      }
```

## Platform Implementations

### JVM (`WorkflowStateCoordinatorImpl`)

Uses a dedicated single-threaded `ExecutorService` with blocking `Await.result` for storage calls:

```scala
class WorkflowStateCoordinatorImpl(storage: DurableStorageBackend)(using ec: ExecutionContext):
  private val activeMap = mutable.Map[WorkflowId, WorkflowRecord]()
  private val runnersMap = mutable.Map[WorkflowId, Future[WorkflowSessionResult[?]]]()
  private val timersMap = mutable.Map[WorkflowId, TimerHandle]()

  private val executor: ExecutorService = Executors.newSingleThreadExecutor(...)
  private val storageTimeout = Duration(30, "seconds")

  private def awaitStorage[T](f: Future[T]): T = Await.result(f, storageTimeout)

  def submit[R](op: CoordinatorOp[R]): Future[R] =
    val promise = Promise[R]()
    executor.execute { () =>
      try promise.success(executeOp(op))
      catch case e: Throwable => promise.failure(e)
    }
    promise.future
```

### JS (`WorkflowStateCoordinatorImpl`)

JavaScript is single-threaded so in-memory operations don't need locks, but storage operations are async and must be properly chained. Uses `dotty-cps-async` for clean async/await syntax:

```scala
private def executeSendBroadcastEvent(...): Future[SendResult] = async[Future] {
  val waiting = activeMap.values.filter(_.isWaitingForEvent(eventName)).toSeq
  if waiting.isEmpty then
    await(storage.savePendingEvent(...))
    SendResult.Queued(eventId)
  else
    await(storage.deliverEvent(...))
    updateInMemoryAfterDeliver(...)
    SendResult.Delivered(target.id)
}
```

### Native (`WorkflowStateCoordinatorImpl`)

Uses a background thread with `LinkedBlockingQueue` and blocking storage calls.

## Operation Execution

The coordinator pattern-matches on `CoordinatorOp` and executes each operation atomically within the single-threaded context. Key operations:

### SuspendAndCheckPending

The key operation that eliminates the race condition:

1. Update in-memory state to Suspended
2. Check for pending events (targeted first, then broadcast) - blocking storage call
3. If pending event found: deliver via `storage.deliverPendingEvent()` and return `Delivered`
4. If no pending event: persist via `storage.suspendWorkflow()` and return `Suspended`

All steps execute atomically in coordinator thread - no window for concurrent `sendEvent` to miss the workflow.

### SendBroadcastEvent / SendTargetedEvent

1. Check in-memory state for waiting workflows
2. If waiting: deliver via `storage.deliverEvent()` and return `Delivered`
3. If not waiting: queue via `storage.savePendingEvent()` and return `Queued`

See `WorkflowStateCoordinatorImpl` for full implementation.

## Operation Fusion

The `CoordinatorOpFusion` object analyzes batches of operations submitted via `submitBatch` and combines them where safe:

```scala
enum FusedOp:
  case Single(op: CoordinatorOp[?])
  case BatchDeliverEvents(workflowId: WorkflowId, events: Seq[SendTargetedEvent])
  case SuspendWithImmediateEvent(suspend: SuspendAndCheckPending, event: SendBroadcastEvent)
```

Fusion optimizations:
1. **Multiple targeted events to same workflow**: Batch delivery - first event wins, rest queued
2. **Suspend + immediate broadcast event**: Skip suspend state, deliver directly

## Engine Usage

The engine submits operations to coordinator and handles results:

```scala
// Suspend handling
for
  result <- coordinator.submit(CoordinatorOp.SuspendAndCheckPending(...))
  _ <- result match
    case SuspendResult.Delivered(record, _) => recreateAndResume(...)
    case SuspendResult.Suspended => registerTimer(...)
yield ()

// Event broadcast
for
  result <- coordinator.submit(CoordinatorOp.SendBroadcastEvent(...))
  _ <- result match
    case SendResult.Delivered(targetId) => recreateAndResume(...)
    case SendResult.Queued(_) => Future.successful(())
yield ()
```

See `WorkflowEngineImpl` for full implementation.

## Recovery

On startup, the engine checks for partially-completed deliveries:

1. Load all active workflows from storage
2. For each Suspended workflow, check if `winningCondition` exists at current activity index
   - If exists: event was delivered but status not updated → fix status to Running
   - If not: workflow is correctly suspended
3. Register recovered workflows with coordinator
4. Reschedule timers for suspended workflows
5. Resume running workflows from last activity index

This ensures idempotent recovery - crashes at any point during delivery are detected and fixed.

## Key Invariants

1. **No race window**: Check-then-act sequences are atomic in coordinator thread
2. **Crash-safe write order**: Destination written before source removal
3. **Idempotent recovery**: WinningCondition presence indicates delivery happened
4. **Single source of truth**: Coordinator owns all state transitions

## File Structure

```
core/shared/src/main/scala/durable/engine/
├── WorkflowStateCoordinator.scala    # Trait with submit method
├── CoordinatorOp.scala               # CoordinatorOp[R] ADT + SuspendResult + SendResult
├── CoordinatorOpFusion.scala         # Operation fusion for batch optimization
└── coordinator/
    └── WorkflowEngineImpl.scala      # Engine using coordinator.submit

core/{jvm,js,native}/src/main/scala/durable/engine/
└── WorkflowStateCoordinatorImpl.scala  # Platform-specific implementations
```

## Benefits

1. **No race conditions**: All state-changing operations serialized through single thread
2. **Operations as data**: All operations in typed ADT - testable, loggable, fusable
3. **Composite storage**: Single database transactions for multi-step persistence
4. **Recovery safety**: Idempotent recovery handles crashes at any point
5. **Type safety**: `CoordinatorOp[R]` returns `Future[R]`
