# WorkflowEngine Design

## Overview

WorkflowEngine is the orchestration layer that manages multiple concurrent workflows. While `WorkflowSessionRunner` interprets a single `Durable[A]` computation, `WorkflowEngine` handles the full lifecycle of many workflows: starting, suspending, resuming, event delivery, child workflow coordination, and persistence.

## Architecture: WorkflowSessionRunner vs WorkflowEngine

### Separation of Concerns

```
┌─────────────────────────────────────────────────────────────┐
│                     WorkflowEngine                          │
│  - Workflow lifecycle (start, suspend, resume, complete)    │
│  - Multi-workflow state management                          │
│  - Event routing & delivery                                 │
│  - Timer scheduling                                         │
│  - Child workflow coordination                              │
│  - Persistence & recovery                                   │
│                                                             │
│  ┌───────────────────────────┐  ┌───────────────────────────┐        │
│  │   WorkflowSessionRunner   │  │   WorkflowSessionRunner   │  ...   │
│  │  (interprets one          │  │  (interprets one          │        │
│  │   Durable[A])             │  │   Durable[B])             │        │
│  └───────────────────────────┘  └───────────────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

**WorkflowSessionRunner** (keep unchanged):
- Pure interpreter for `Durable[A]` monad
- Executes activities with caching and retry
- Returns `WorkflowSessionResult[A]`: Completed, Suspended, Failed, ContinueAs
- Stateless beyond a single run (activity index counter)
- No knowledge of other workflows or events

**WorkflowEngine** (new):
- Manages collection of workflow instances
- Handles `WorkflowResult` outcomes
- Routes events to waiting workflows
- Schedules timers
- Coordinates parent-child relationships
- Persists and recovers workflow state

### Why Not Merge?

1. **Single Responsibility**: Runner interprets; Engine orchestrates
2. **Testability**: Runner can be unit-tested with single workflows
3. **Flexibility**: Different engine implementations (in-memory, distributed) with same runner
4. **Simplicity**: Runner stays simple; complexity lives in Engine

## WorkflowEngine Trait (Public API)

The `WorkflowEngine` trait defines the public interface. Implementation details are platform-specific.

```scala
// core/shared/src/main/scala/durable/WorkflowEngine.scala

/**
 * WorkflowEngine manages multiple concurrent workflows.
 *
 * Type parameter S is the storage type - inferred from constructor argument.
 * Same storage used for activity results, workflow metadata, and events.
 */
trait WorkflowEngine[S <: DurableStorageBackend]:
  /** The storage instance */
  def storage: S

  /** Start a new workflow, returns workflow ID */
  def start[Args <: Tuple, R](
    function: DurableFunction[Args, R],
    args: Args,
    workflowId: Option[WorkflowId] = None
  )(using
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Future[WorkflowId]

  /** Send an event to a specific workflow by ID */
  def sendEventTo[E](workflowId: WorkflowId, event: E)(using
    eventName: DurableEventName[E],
    storage: DurableStorage[E, S]
  ): Future[Unit]

  /** Broadcast an event to workflows waiting for this event type */
  def sendEventBroadcast[E](event: E)(using
    eventName: DurableEventName[E],
    storage: DurableStorage[E, S]
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
  def queryDeadLetters[E](using DurableEventName[E], DurableStorage[E, S]): Future[Seq[DeadEvent[E]]]
  /** Replay a dead letter event to broadcast queue */
  def replayDeadLetter(eventId: EventId): Future[Boolean]
  /** Replay a dead letter event to a specific workflow */
  def replayDeadLetterTo(eventId: EventId, workflowId: WorkflowId): Future[Unit]
  /** Remove a dead letter event */
  def removeDeadLetter(eventId: EventId): Future[Boolean]

  // Observability APIs (listByStatus, history queries, metrics) out of scope for this design

object WorkflowEngine extends WorkflowEnginePlatform:
  /** Create engine - type S inferred from storage argument */
  def apply[S <: DurableStorageBackend](storage: S)(using ExecutionContext): WorkflowEngine[S] =
    create(storage, WorkflowEngineConfig.default)

  /** Create engine with custom configuration */
  def apply[S <: DurableStorageBackend](storage: S, config: WorkflowEngineConfig)(using ExecutionContext): WorkflowEngine[S] =
    create(storage, config)
```

### Usage

```scala
val storage = MemoryBackingStore()
val engine = WorkflowEngine(storage)  // WorkflowEngine[MemoryBackingStore]

// Start workflow - DurableStorage instances resolved for this storage type
engine.start(MyWorkflow, Tuple1("order-123"))
```

## Platform-Specific Implementation

Similar to `DurableFunctionRegistry`, each platform provides its own `WorkflowEnginePlatform` that creates `WorkflowEngine[S]` instances.

```scala
// core/shared/src/main/scala/durable/WorkflowEnginePlatform.scala
trait WorkflowEnginePlatform:
  def create[S <: DurableStorageBackend](
    storage: S,
    config: WorkflowEngineConfig
  )(using ExecutionContext): WorkflowEngine[S]
```

### Platform Differences

| Aspect | JVM | JS | Native |
|--------|-----|-----|--------|
| Implementation | `WorkflowEngineJvm` | `WorkflowEngineJs` | `WorkflowEngineNative` |
| Timer scheduling | `ScheduledExecutorService` | `setTimeout` | Platform timer |
| State mutations | Via Future chaining | Via Future chaining | Via Future chaining |

All platforms use same model: state mutations sequenced through Futures, actual work on `ExecutionContext`.

## Internal State Model

### In-Memory State

State is managed by `WorkflowStateCoordinator`, which provides serialized access:

```scala
// State managed internally by WorkflowStateCoordinator:
// - active: Map[WorkflowId, WorkflowRecord]     // Active (non-terminal) workflows
// - runners: Map[WorkflowId, Future[...]]       // Running workflow futures
// - timers: Map[WorkflowId, TimerHandle]        // Scheduled timer handles
```

### Persistent State (in DurableStorageBackend)

```scala
case class WorkflowRecord(
  id: WorkflowId,
  metadata: WorkflowMetadata,
  status: WorkflowStatus,
  waitCondition: Option[WaitCondition[?]],
  parentId: Option[WorkflowId],
  createdAt: Instant,
  updatedAt: Instant
)
```

- Active workflows (Running, Suspended): cached in memory + persisted
- Terminal workflows (Succeeded, Failed, Cancelled): only in storage

## Lifecycle State Machine

```
                      ┌─────────────┐
             start()  │   Running   │
          ──────────▶ │             │
                      └──────┬──────┘
                             │
           ┌─────────────────┼─────────────────┐
           │                 │                 │
           ▼                 ▼                 ▼
     ┌───────────┐    ┌───────────┐    ┌───────────┐
     │ Suspended │    │ Succeeded │    │  Failed   │
     │           │    │ (result)  │    │  (error)  │
     └─────┬─────┘    └───────────┘    └───────────┘
           │
           │ event/timer/child completes
           ▼
     ┌───────────┐
     │  Running  │ (resume)
     └───────────┘

     cancel() from Running or Suspended:
     ┌───────────┐         ┌───────────┐
     │  Running  │────────▶│ Cancelled │
     └───────────┘         └───────────┘
     ┌───────────┐         ┌───────────┐
     │ Suspended │────────▶│ Cancelled │
     └───────────┘         └───────────┘
```

**Terminal states**: Succeeded (with result), Failed (with error), Cancelled (no transitions out)

**Note**: `cancel()` on terminal states (Succeeded, Failed, Cancelled) is a no-op, returns `false`.

## Event Delivery

The engine supports two modes of event delivery: **targeted** (to a specific workflow) and **broadcast** (to any workflow waiting for the event type).

### Targeted Events (`sendEventTo`)

Send an event to a specific workflow by ID. This is similar to Temporal's signal mechanism.

**Flow:**

1. `sendEventTo[E](workflowId, event)` called
2. Validate workflow exists and is not in terminal state:
   - If not found: throw `WorkflowNotFoundException`
   - If terminal (Succeeded/Failed/Cancelled): throw `WorkflowTerminatedException`
3. If workflow is suspended and waiting for this event type:
   - Store event value at workflow's current activity index
   - Mark workflow as Running
   - Resume via `WorkflowSessionRunner.run(workflow, ctx)`
4. If workflow is running or waiting for a different condition:
   - Store event in workflow-specific pending queue
   - Event will be delivered when workflow reaches a matching `await`

**Error Handling:**

```scala
// Exceptions thrown by sendEventTo
case class WorkflowNotFoundException(workflowId: WorkflowId) extends Exception
case class WorkflowTerminatedException(workflowId: WorkflowId, status: WorkflowStatus) extends Exception
```

### Broadcast Events (`sendEventBroadcast`)

Broadcast an event to workflows waiting for this event type. Events are queued if no workflow is currently waiting.

**Flow:**

1. `sendEventBroadcast[E](event)` called
2. Engine looks up `eventName` via `DurableEventName[E]`
3. Engine queries `eventWaiters(eventName)` for waiting workflows
4. If workflows are waiting:
   - Deliver based on `DurableEventConfig[E]` semantics
   - Store event value at each recipient's current activity index
   - Mark workflow(s) as Running
   - Resume via `WorkflowSessionRunner.run(workflow, ctx)`
5. If no workflows waiting:
   - Store event in shared pending queue by event name
6. Apply `DurableEventConfig[E]` semantics:
   - `consumeOnRead`: Remove event after first delivery vs broadcast to all
   - `deliveryMode`: Which workflows see the event (First, All, AfterWait, AfterCreate)
   - `expireAfter`: Discard old events

### Comparison

| Aspect | `sendEventTo` | `sendEventBroadcast` |
|--------|---------------|----------------------|
| Target | Specific workflow by ID | Any workflow waiting for event type |
| Not waiting | Queue in workflow-specific queue | Queue in shared pending events |
| Not found | Throw `WorkflowNotFoundException` | N/A (no target) |
| Terminal state | Throw `WorkflowTerminatedException` | N/A (skip terminal) |
| Multiple recipients | No (single target) | Yes (based on `DurableEventConfig`) |

### Event Storage

Events are stored in two separate structures:

**Broadcast pending events** (shared by event name):

```scala
case class PendingEvent[E](
  eventId: EventId,
  eventName: String,
  value: E,
  timestamp: Instant,
  config: EventConfig
)

// Shared queue by event name
val broadcastPendingEvents: Map[String, Queue[PendingEvent[?]]]
```

**Targeted pending events** (per-workflow):

```scala
// Per-workflow queue, indexed by workflow ID then event name
val workflowPendingEvents: Map[WorkflowId, Map[String, Queue[PendingEvent[?]]]]
```

When a workflow suspends waiting for an event, the engine checks both queues:
1. First, workflow-specific queue (targeted events have priority)
2. Then, shared broadcast queue

### Dead Letter Handling for Targeted Events

When a workflow terminates (Succeeded, Failed, or Cancelled) with undelivered events in its pending queue, these events become "orphaned". The engine handles them based on `DurableEventConfig[E]`:

**Configuration:**

```scala
trait DurableEventConfig[E]:
  // ... existing fields ...

  /** What to do with targeted events when workflow terminates without reading them */
  def onTargetTerminated: DeadLetterPolicy = DeadLetterPolicy.Discard

enum DeadLetterPolicy:
  case Discard          // Silently discard the event (default)
  case MoveToBroadcast  // Reroute to broadcast queue for other workflows
  case MoveToDeadLetter // Move to dead letter queue for inspection/replay
```

**Dead Letter Storage:**

```scala
case class DeadEvent[E](
  eventId: EventId,
  eventName: String,
  value: E,
  originalTarget: WorkflowId,
  targetTerminatedAt: Instant,
  targetStatus: WorkflowStatus,  // Why it wasn't delivered
  timestamp: Instant             // When event was originally sent
)

// Dead letter queue by event name
val deadLetterEvents: Map[String, Queue[DeadEvent[?]]]
```

**Engine API for Dead Letters:**

```scala
trait WorkflowEngine[S <: DurableStorageBackend]:
  // ... existing methods ...

  /** Query dead letter events by type */
  def queryDeadLetters[E](using
    eventName: DurableEventName[E],
    storage: DurableStorage[E, S]
  ): Future[Seq[DeadEvent[E]]]

  /** Replay a dead letter event to broadcast queue */
  def replayDeadLetter(eventId: EventId): Future[Boolean]

  /** Replay a dead letter event to a specific workflow */
  def replayDeadLetterTo(eventId: EventId, workflowId: WorkflowId): Future[Unit]

  /** Remove a dead letter event */
  def removeDeadLetter(eventId: EventId): Future[Boolean]
```

**Flow when workflow terminates:**

1. Workflow transitions to terminal state (Succeeded/Failed/Cancelled)
2. Engine calls `clearWorkflowPendingEvents(workflowId)` to process pending events
3. For each pending event in the workflow's queue:
   - Look up `DurableEventConfig[E].onTargetTerminated`
   - `Discard`: Remove event, no further action
   - `MoveToBroadcast`: Move event to broadcast queue, may wake waiting workflows
   - `MoveToDeadLetter`: Store in dead letter queue with metadata

**DurableStorageBackend Extensions:**

```scala
trait DurableStorageBackend:
  // ... existing methods ...

  // Dead letter storage
  def saveDeadEvent(eventName: String, deadEvent: DeadEvent[?]): Future[Unit]
  def loadDeadEvents(eventName: String): Future[Seq[DeadEvent[?]]]
  def removeDeadEvent(eventName: String, eventId: EventId): Future[Unit]
```

**Usage Example:**

```scala
// Event that should be rerouted if target terminates
case class PaymentConfirmation(orderId: String, amount: BigDecimal)

given DurableEventConfig[PaymentConfirmation] with
  def onTargetTerminated = DeadLetterPolicy.MoveToBroadcast

// Event that should be inspectable if lost
case class CriticalAlert(alertId: String, message: String)

given DurableEventConfig[CriticalAlert] with
  def onTargetTerminated = DeadLetterPolicy.MoveToDeadLetter

// Monitoring dead letters
val deadAlerts = engine.queryDeadLetters[CriticalAlert]
deadAlerts.foreach { dead =>
  logger.warn(s"Alert ${dead.eventId} not delivered to ${dead.originalTarget}: ${dead.targetStatus}")
  // Optionally replay to another workflow
  engine.replayDeadLetterTo(dead.eventId, fallbackWorkflowId)
}
```

## Timer Scheduling

### Flow

1. Workflow suspends with `WaitCondition.Timer(wakeAt)`
2. Engine stores in `timerWaiters(wakeAt) += workflowId`
3. Engine schedules callback at `wakeAt` via `Scheduler`
4. On wake:
   - Store `Instant.now()` at activity index
   - Resume workflow

### Scheduler Integration

```scala
// Platform implementations provide scheduler
trait WorkflowEngine[S <: DurableStorageBackend]:
  protected def scheduler: Scheduler

  private def scheduleTimer(workflowId: WorkflowId, wakeAt: Instant): Unit =
    val delay = Duration.between(Instant.now(), wakeAt)
    scheduler.schedule(delay) {
      resumeFromTimer(workflowId, wakeAt)
    }
```

## Child Workflow Coordination

### Starting Child

```scala
// In parent workflow
val childResult = await(startChild(ChildWorkflow, args))

// Creates Suspend with WaitCondition.ChildWorkflow(childId)
```

### Completion Flow

1. Child workflow completes with result
2. Engine looks up `parentByChild(childId)` → parentId
3. Engine stores child result at parent's waiting activity index
4. Engine resumes parent workflow

### Failure Handling

Depends on whether parent awaits the child:

```scala
async[Durable] {
  // Option 1: Await child - parent receives result or exception
  val result = try {
    await(startChild(RiskyWorkflow, args))
  } catch {
    case e: ChildWorkflowFailed => defaultValue
  }

  // Option 2: Fire-and-forget - no waiting, no failure propagation
  startChild(BackgroundWorkflow, args)  // returns child WorkflowId
  // parent continues immediately, child runs independently
}
```

Fire-and-forget children are independent workflows - parent doesn't track their completion or failure.

## Persistence Layer

Engine uses `DurableStorageBackend` for all persistence. Backend trait is extended with workflow-level methods:

### DurableStorageBackend Extensions

```scala
trait DurableStorageBackend:
  // Existing: activity storage (store, retrieve, storeFailure, clear)
  // ...

  // Engine additions: workflow metadata
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit]
  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]]
  def updateWorkflowStatus(workflowId: WorkflowId, status: WorkflowStatus): Future[Unit]
  def listActiveWorkflows(): Future[Seq[WorkflowRecord]]  // Running or Suspended

  // Engine additions: broadcast pending events (shared by event name)
  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit]
  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]]
  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit]
  // Event config (expiry, consumeOnRead) derived from DurableEventConfig[E] typeclass at delivery

  // Engine additions: targeted pending events (per-workflow)
  def saveWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit]
  def loadWorkflowPendingEvents(workflowId: WorkflowId, eventName: String): Future[Seq[PendingEvent[?]]]
  def removeWorkflowPendingEvent(workflowId: WorkflowId, eventName: String, eventId: EventId): Future[Unit]
  def clearWorkflowPendingEvents(workflowId: WorkflowId): Future[Unit]  // Called when workflow terminates
```

### Recovery Process

On engine startup:

1. Load active workflows via `listActiveWorkflows()` (Running or Suspended in one query)
2. For each `Suspended` workflow:
   - Load metadata via `loadWorkflowMetadata(id)`
   - Check if wait condition is satisfied:
     - Timer: check if `wakeAt` has passed
     - Event: check workflow-specific queue first via `loadWorkflowPendingEvents(id, eventName)`, then broadcast queue via `loadPendingEvents(eventName)`
   - If satisfied: resume immediately
   - If not: re-register in memory (cache + timer scheduling)
3. For each `Running` workflow:
   - Atomically claim and resume from last cached activity

```scala
def recover(): Future[RecoveryReport] =
  for
    active <- storage.listActiveWorkflows()
    (suspended, running) = active.partition(_.status == WorkflowStatus.Suspended)
    _ <- Future.traverse(suspended)(recoverSuspended)
    _ <- Future.traverse(running)(recoverRunning)
  yield RecoveryReport(...)
```

## WorkflowSessionRunner Integration

### Resume Flow

```scala
private def runWorkflow[A](
  workflowId: WorkflowId,
  workflow: Durable[A],
  resumeFrom: Int
): Future[Unit] =
  val ctx = RunContext(workflowId, resumeFrom, config)
  WorkflowSessionRunner.run(workflow, ctx).flatMap {
    case WorkflowResult.Completed(value) =>
      markSucceeded(workflowId, value)

    case WorkflowResult.Suspended(snapshot, condition) =>
      markSuspended(workflowId, snapshot, condition)
      registerWaiter(workflowId, condition)

    case WorkflowResult.Failed(error) =>
      markFailed(workflowId, error)

    case WorkflowResult.ContinueAs(metadata, storeArgs, workflow, backend) =>
      handleContinueAs(workflowId, metadata, storeArgs, workflow, backend)
  }
```

### Wait Condition Registration

```scala
private def registerWaiter(workflowId: WorkflowId, condition: WaitCondition[?]): Unit =
  condition match
    case WaitCondition.Timer(wakeAt) =>
      timerWaiters(wakeAt) += workflowId
      scheduleTimer(workflowId, wakeAt)

    case WaitCondition.Event(eventName) =>
      eventWaiters(eventName) += workflowId
      checkPendingEvents(workflowId, eventName)

    case WaitCondition.ChildWorkflow(childId, _) =>
      childrenByParent(workflowId) += childId
      parentByChild(childId) = workflowId
```

## Concurrency Model

### WorkflowStateCoordinator Pattern

The engine uses a **WorkflowStateCoordinator** to serialize all state-mutating operations, preventing race conditions between concurrent operations like `sendEventTo`/`sendEventBroadcast` and `handleSuspended`.

Unlike a generic coordinator with `execute()` blocks, `WorkflowStateCoordinator` owns the state and exposes **named operations** that make the code self-documenting:

```scala
trait WorkflowStateCoordinator:
  // === Registration ===
  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit]
  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit]
  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit]

  // === State Transitions ===
  def markFinished(id: WorkflowId): Future[Unit]  // completed or failed
  def markSuspended(id: WorkflowId, activityIndex: Int, condition: EventQuery.Combined[?, ?]): Future[Unit]
  def markResumed(id: WorkflowId, newActivityIndex: Int): Future[Option[WorkflowRecord]]
  def updateForContinueAs(id: WorkflowId, metadata: WorkflowMetadata): Future[Unit]

  // === Queries with Actions ===
  def findWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]]  // For broadcast events
  def getActiveForEvent(id: WorkflowId): Future[Option[WorkflowRecord]]    // For targeted events
  def getAndRemoveTimer(id: WorkflowId): Future[Option[WorkflowRecord]]
  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]]

  // === Bulk Operations ===
  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit]
  def cancelAllTimers(): Future[Seq[TimerHandle]]

  // === Read-Only (eventually consistent) ===
  def getActive(id: WorkflowId): Option[WorkflowRecord]

  def shutdown(): Future[Unit]
```

**Key design decisions:**
- State lives inside the coordinator (not in WorkflowEngineImpl)
- Named operations instead of generic `execute { ... }` blocks
- `markSuspended` takes `EventQuery.Combined` and extracts fields internally
- `markResumed` handles both timer and event resume (cancels any pending timer)

**Platform implementations:**
- **JVM**: `WorkflowStateCoordinatorImpl` using a dedicated `ExecutorService`
- **JS**: `WorkflowStateCoordinatorImpl` (operations execute immediately - JS is single-threaded)
- **Native**: `WorkflowStateCoordinatorImpl` using a background thread with queue

### Race Condition Prevention

Without coordination, the following race conditions can occur:

| Race | Operations | Result |
|------|------------|--------|
| sendEventTo/handleSuspended | Check-then-act on activeWorkflows | Lost event or stuck workflow |
| resumeFromTimer/cancel | Status check then act | Cancelled workflow resumes |
| handleCompleted/sendEventTo | Early state cleanup | Stale record usage |

The named operations serialize these:

```scala
def sendEventTo[E](workflowId: WorkflowId, event: E)(...): Future[Unit] =
  for
    // Sequential execution guaranteed
    recordOpt <- stateCoordinator.getActiveForEvent(workflowId)
    _ <- recordOpt match
      case None => Future.failed(WorkflowNotFoundException(workflowId))
      case Some(record) if record.status.isTerminal =>
        Future.failed(WorkflowTerminatedException(workflowId, record.status))
      case Some(record) if record.isWaitingForEvent(name) =>
        for
          _ <- storage.storeWinningCondition(...)
          _ <- stateCoordinator.markResumed(workflowId, activityIndex + 1)
          // resume workflow...
        yield ()
      case Some(record) =>
        // Queue for later delivery
        storage.saveWorkflowPendingEvent(workflowId, name, ...)
  yield ()

private def handleSuspended(...): Future[Unit] =
  for
    _ <- stateCoordinator.markSuspended(workflowId, activityIndex, condition)
    pendingResult <- checkPendingEvents(...)
    // ...
  yield ()
```

### Named Operations

| Operation | Purpose |
|-----------|---------|
| `registerWorkflow` | Add new workflow to active state |
| `registerRunner` | Track running workflow's future |
| `registerTimer` | Track timer handle for cancellation |
| `markFinished` | Remove runner and active (completed/failed) |
| `markSuspended` | Update to Suspended with wait condition |
| `markResumed` | Cancel timer, update to Running |
| `findWaitingForEvent` | Find suspended workflows waiting for event (broadcast) |
| `getActiveForEvent` | Get workflow record by ID (targeted events) |
| `getAndRemoveTimer` | Get suspended workflow, remove timer (for timer callback) |
| `cancelWorkflow` | Cancel and remove workflow |
| `recoverWorkflows` | Bulk register from storage recovery |
| `cancelAllTimers` | Shutdown - cancel all pending timers |

### Future Optimizations

If the single coordinator becomes a bottleneck:

1. **Sharded Coordinators**: Partition by event name or workflow ID
2. **Versioned State**: Use CAS operations with version numbers for optimistic concurrency

See `docs/brainshtorm/race-conditions-debug.md` for detailed analysis.

## Error Handling

### Event Delivery Errors

`sendEventTo` throws exceptions for invalid targets:

```scala
// Thrown when target workflow doesn't exist
case class WorkflowNotFoundException(workflowId: WorkflowId)
  extends Exception(s"Workflow not found: $workflowId")

// Thrown when target workflow is in terminal state
case class WorkflowTerminatedException(workflowId: WorkflowId, status: WorkflowStatus)
  extends Exception(s"Workflow $workflowId is terminated with status: $status")
```

`sendEventBroadcast` does not throw for missing recipients - events are queued.

### Workflow Failures

- Workflow throws unhandled exception → `WorkflowResult.Failed`
- Engine marks workflow as Failed, stores error
- If has parent: propagate or isolate based on config

### Engine Failures

- Engine crash → recover from persistence on restart
- Running workflows → resume from last activity
- Suspended workflows → re-register waiters

### Storage Failures

- Storage failure during activity → workflow fails (current behavior)
- Storage failure during engine operations → retry with backoff

## Configuration

```scala
case class WorkflowEngineConfig(
  // Retry policy for engine-level operations
  storageRetryPolicy: RetryPolicy = RetryPolicy.default,

  // Timer precision (minimum delay)
  timerResolution: FiniteDuration = 100.millis,

  // Child workflow failure propagation
  propagateChildFailures: Boolean = true,

  // Event retention
  maxPendingEventsPerType: Int = 10000,
  eventRetention: FiniteDuration = 7.days
)
```

## Summary: Division of Responsibilities

| Aspect | WorkflowSessionRunner | WorkflowEngine |
|--------|---------------|----------------|
| Scope | Single workflow | Multiple workflows |
| State | Activity index counter | Workflow instances, indexes |
| Activities | Execute, cache, retry | - |
| Suspend | Returns `Suspended` result | Registers waiter, schedules |
| Events | - | Targeted (`sendEventTo`) and broadcast (`sendEventBroadcast`) |
| Timers | - | Schedule, fire, store value |
| Children | - | Start, coordinate, propagate |
| Persistence | Activity values (via Storage) | Workflow metadata, events (broadcast + per-workflow) |
| Recovery | Resume from index | Full system recovery |

## File Structure

```
core/shared/src/main/scala/durable/
├── WorkflowEngine.scala              # Trait + companion object
├── WorkflowEnginePlatform.scala      # Platform factory trait
├── WorkflowEngineConfig.scala        # Configuration
├── WorkflowMetadata.scala            # Workflow record data classes
└── engine/
    ├── WorkflowStateCoordinator.scala    # Coordinator trait with named operations + TimerHandle
    ├── TestHooks.scala                   # Test instrumentation hooks
    └── coordinator/
        └── WorkflowEngineImpl.scala      # Engine implementation using coordinator

core/jvm/src/main/scala/durable/
├── WorkflowEnginePlatform.scala              # JVM factory
└── engine/
    └── WorkflowStateCoordinatorImpl.scala    # JVM: single-threaded ExecutorService

core/js/src/main/scala/durable/
├── WorkflowEnginePlatform.scala              # JS factory
└── engine/
    └── WorkflowStateCoordinatorImpl.scala    # JS: immediate execution (single-threaded)

core/native/src/main/scala/durable/
├── WorkflowEnginePlatform.scala              # Native factory
└── engine/
    └── WorkflowStateCoordinatorImpl.scala    # Native: background thread with queue
```

Note: Persistence uses `DurableStorageBackend` - no separate persistence layer needed.

## Next Steps

1. Define `WorkflowEngine[S]` trait (shared)
2. Define `WorkflowEnginePlatform` trait (shared)
3. Extend `DurableStorageBackend` with workflow metadata methods
4. Implement `WorkflowStateCoordinator` (shared trait, platform-specific impls)
5. Implement `WorkflowEngineJvm` (JVM platform)
6. Implement `WorkflowEngineJs` (JS platform)
7. Implement `WorkflowEngineNative` (Native platform)
8. Add event delivery with `DurableEventConfig` semantics
9. Add timer scheduling integration
10. Add child workflow support
11. Implement recovery logic
