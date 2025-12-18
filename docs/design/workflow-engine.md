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

  /** Send an event to matching workflows */
  def sendEvent[E](event: E)(using
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

### Flow

1. `sendEvent[E](event)` called
2. Engine looks up `eventName` via `DurableEventName[E]`
3. Engine queries `eventWaiters(eventName)` for waiting workflows
4. For each waiting workflow:
   - Store event value at workflow's current activity index
   - Mark workflow as Running
   - Resume via `WorkflowSessionRunner.run(workflow, ctx)`
5. Apply `DurableEventConfig[E]` semantics:
   - `consumeOnRead`: Remove event after first delivery vs broadcast to all
   - `deliveryMode`: Which workflows see the event
   - `expireAfter`: Discard old events

### Event Storage

Events waiting for delivery are stored in engine state:

```scala
case class PendingEvent[E](
  eventName: String,
  value: E,
  timestamp: Instant,
  config: EventConfig
)

val pendingEvents: Map[String, Queue[PendingEvent[?]]]
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

  // Engine additions: pending events
  def savePendingEvent(eventName: String, eventId: EventId, value: Any, timestamp: Instant): Future[Unit]
  def loadPendingEvents(eventName: String): Future[Seq[PendingEvent[?]]]
  def removePendingEvent(eventName: String, eventId: EventId): Future[Unit]
  // Event config (expiry, consumeOnRead) derived from DurableEventConfig[E] typeclass at delivery
```

### Recovery Process

On engine startup:

1. Load active workflows via `listActiveWorkflows()` (Running or Suspended in one query)
2. For each `Suspended` workflow:
   - Load metadata via `loadWorkflowMetadata(id)`
   - Check if wait condition is satisfied:
     - Timer: check if `wakeAt` has passed
     - Event: check via `loadPendingEvents(eventName)`
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

The engine uses a **WorkflowStateCoordinator** to serialize all state-mutating operations, preventing race conditions between concurrent operations like `sendEvent` and `handleSuspended`.

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
  def findWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]]
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
| sendEvent/handleSuspended | Check-then-act on activeWorkflows | Lost event or stuck workflow |
| resumeFromTimer/cancel | Status check then act | Cancelled workflow resumes |
| handleCompleted/sendEvent | Early state cleanup | Stale record usage |

The named operations serialize these:

```scala
def sendEvent[E](event: E)(...): Future[Unit] =
  for
    // Sequential execution guaranteed
    waitingWorkflows <- stateCoordinator.findWaitingForEvent(name)
    _ <- if waitingWorkflows.isEmpty then
      storage.savePendingEvent(...)
    else
      for
        _ <- storage.storeWinningCondition(...)
        _ <- stateCoordinator.markResumed(target.id, activityIndex + 1)
        // ...
      yield ()
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
| `findWaitingForEvent` | Find suspended workflows waiting for event |
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
| Events | - | Route, deliver, store value |
| Timers | - | Schedule, fire, store value |
| Children | - | Start, coordinate, propagate |
| Persistence | Activity values (via Storage) | Workflow metadata, events |
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
