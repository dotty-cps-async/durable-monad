# Durable Monad Design - Replay-Based Execution

## Overview

This document describes the design of a replay-based durable execution monad for Scala. The goal is to enable workflows that can survive process restarts by caching intermediate results and replaying from saved state.

## Core Principles

1. **Replay-based** - Portable across JVM/JS/Native
2. **All vals cached** - Every `val` definition is automatically cached by execution index
3. **Type-enforced** - `DurableStorage[T, S]` required for all cached types
4. **Immutable** - No `var` allowed (`NoVars` marker), use recursion for iteration

## Execution Model

### Initial Run

```
Execute step 0 → cache result → Execute step 1 → cache result → ...
```

### Resume (from step N)

```
Step 0: return cached → Step 1: return cached → ... → Step N: execute live → ...
```

## Type Model

```scala
Durable[A]     // computation description, NOT cached (not serializable)
A              // concrete result, cached via DurableStorage[A, S]
await/execute  // converts Durable[A] → A, caches result
```

### Key Types

```scala
// Marker trait: no mutable variables allowed in async blocks
trait CpsMonadNoVars[F[_]]

// Marker trait for storage backends (memory, database, etc.)
trait DurableStorageBackend:
  def clear(workflowId: WorkflowId): Future[Unit]
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit]
  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]]
  // ... other workflow-level operations

// Storage for cached values - parameterized by value type T and backend type S
trait DurableStorage[T, S <: DurableStorageBackend]:
  // Step storage (activities, timers, events)
  def storeStep(backend: S, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]
  def storeStepFailure(backend: S, workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit]
  def retrieveStep(backend: S, workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]]
  // Workflow result storage (final result when workflow completes)
  def storeResult(backend: S, workflowId: WorkflowId, value: T): Future[Unit]
  def retrieveResult(backend: S, workflowId: WorkflowId): Future[Option[T]]

// The monad itself
trait Durable[+A] {
  def flatMap[B](f: A => Durable[B]): Durable[B]
  def map[B](f: A => B): Durable[B]
}

object Durable {
  def pure[A](value: A): Durable[A]
  def suspend: Durable[Unit]  // suspension point (e.g., sleep, wait for signal)
}
```

## Two Categories of Operations

### 1. Activities (outbound)

Operations where the workflow initiates and executes something external. No `await` needed - we call, get result, cache it.

```scala
def httpGet(url: String): Response        // we call external service
def dbQuery(sql: String): Rows            // we query database
def sendEmail(to: String, body: String): Unit  // we send email

async[Durable] {
  val resp = httpGet(url)      // activity - auto-cached, instant on replay
  val rows = dbQuery(sql)      // activity - auto-cached, instant on replay
}
```

**Caching behavior:**
- On success: result is cached, instant return on replay
- On recoverable failure: activity is retried automatically (configurable retry policy)
- On non-recoverable failure: exception propagates, workflow handles it

**Retry configuration:**
```scala
@activity(retryPolicy = RetryPolicy(
  maxAttempts = 3,
  initialBackoff = 1.second,
  maxBackoff = 30.seconds,
  retryOn = { case _: TimeoutException | _: TransientError => true }
))
def httpGet(url: String): Response
```

### 2. External Calls (inbound)

Operations where the workflow waits for something external to happen. Require `await` - we're waiting for input from outside.

```scala
def sleep(duration: Duration): Durable[Unit]       // wait for time to pass
def waitForSignal(name: String): Durable[Signal]   // wait for external signal
def waitForHumanApproval(): Durable[Decision]      // wait for human decision

async[Durable] {
  val resp = httpGet(url)                        // activity (outbound) - no await
  await(sleep(7.days))                           // external call (inbound) - await
  val decision = await(waitForHumanApproval())   // external call (inbound) - await
  process(resp, decision)
}
```

`await` marks: "workflow waits here for external input"

### Summary Table

| Operation Type | Direction | Await? | On Replay | On Failure |
|---------------|-----------|--------|-----------|------------|
| Pure computation | - | No | Return cached | N/A |
| Activity | Outbound | No | Return cached | Retry if recoverable |
| External call | Inbound | Yes | Skip wait, return cached | Workflow-level handling |

## Cache Key Strategy

**Index-based** (execution order):

```scala
async[Durable] {
  val config = loadConfig()     // cached[0]
  val resp = httpGet(url)       // cached[1]
  await(sleep(1.hour))          // cached[2]
  val data = process(resp)      // cached[3]
}
```

Index-based keys are deterministic during replay as long as code structure is unchanged.

## Automatic Transform

Every `val` definition is transformed by the macro:

```scala
// Source
async[Durable] {
  val config = loadConfig()
  val resp = httpGet(config.url)
  await(sleep(1.hour))
  val result = process(resp)
  result
}

// Transformed (conceptual)
async[Durable] {
  val config = ctx.cached(0) { loadConfig() }
  val resp = ctx.cached(1) { httpGet(config.url) }
  await(ctx.cachedSuspend(2)) { sleep(1.hour) }
  val result = ctx.cached(3) { process(resp) }
  result
}
```

## Design Decisions

### Q1: Loops

**Decision**: Recursion, `continueWith`, and combinators only, no `var`

```scala
// Option 1: Use continueWith for long-running loops (recommended)
// Each iteration clears history, preventing unbounded growth
object ProcessItemsWorkflow extends DurableFunction2[List[Item], List[Result], List[Result], S]:
  def apply(args: (List[Item], List[Result]))(using ...): Durable[List[Result]] = async[Durable] {
    val (items, acc) = args
    items match
      case Nil => acc
      case h :: t =>
        val r = process(h)         // cached
        await(sleep(1.minute))     // suspension
        await(continueWith(t, acc :+ r))  // continue with remaining items
  }

// Option 2: Simple recursion (for bounded iteration within single workflow)
def processItems(items: List[Item], acc: List[Result]): Durable[List[Result]] =
  items match {
    case Nil => Durable.pure(acc)
    case h :: t =>
      val r = process(h)           // cached
      await(sleep(1.minute))       // suspension
      processItems(t, acc :+ r)    // recurse
  }

// Option 3: Combinators
Durable.traverse(list)(item => process(item))
```

**When to use `continueWith` vs recursion:**
- `continueWith`: Long-running loops, many iterations, or when history growth is a concern
- Recursion: Short-lived loops, bounded iteration count

### Q2: Branch Handling

**Decision**: Determinism handles it automatically

```scala
async[Durable] {
  val cond = checkSomething()     // cached[0] = true
  if (cond) {
    val a = compute1()            // cached[1]
  } else {
    val b = compute2()            // never reached
  }
}
```

On replay: `cond` returns cached `true` → same branch → same indices.

### Q3: Non-deterministic Functions

**Decision**: Discipline + auto-cache for control flow

next code:
```
if (Random.nextBoolean()) { ... }  // different result on replay!
```

should be transformed to
```
if (ctx.cashed(Random.nextBoolean())) { ... }  // different result on replay!
```


### Q4: Exception Handling

**Decision**: Cache try-catch results; unhandled = workflow restart

```scala
async[Durable] {
  val result = try {
    riskyOperation()
  } catch {
    case e: ExpectedException => fallback()
  }
  // `result` is cached (success or fallback)

  dangerousOperation()  // if throws: workflow suspends, restart from savepoint
}
```

| Exception Type | Handling |
|----------------|----------|
| Caught (try-catch) | Result cached (success or fallback) |
| Uncaught | Workflow suspends, restart from last savepoint |

### Q5: Higher-Order Functions Cache Granularity

**Decision**: Coarse-grained by default; user opts into fine-grained

```scala
// Framework: cache entire result
val results = list.map(x => httpGet(x))  // cached[0] = entire List[Response]

// User choice: fine-grained
val results = Durable.traverse(list)(x => httpGet(x))  // each cached separately

// Or explicit recursion
def fetchAll(urls: List[String]): Durable[List[Response]] = ...
```

### Q6: Code Versioning

**Decision**: Version tag; no cross-version resume

```scala
@DurableWorkflow(version = "v1.2.3")
def myWorkflow(): Durable[Result] = async[Durable] {
  ...
}
```

- Same version → resume from cache
- Different version → start fresh or fail clearly
- Deployment: separate containers per version

### Q7: DurableStorage Types

**Decision**: All vals require `DurableStorage[T, S]` where `S` is the storage backend type

```scala
async[Durable] {
  val a: Int = compute()           // needs DurableStorage[Int, S] ✓
  val b: String = fetch()          // needs DurableStorage[String, S] ✓
  val c: MyClass = create()        // needs DurableStorage[MyClass, S] ✓
  val d: Connection = open()       // DurableStorage[Connection, S]? ✗ compile error
}
```

Storage instances are provided via a backing store:
```scala
// Universal given from backing store
given backing: MemoryStorage = MemoryStorage()
given [T]: DurableStorage[T, MemoryStorage] = backing.forType[T]

// Production: backing store handles serialization internally
given backing: PostgresBackingStore = PostgresBackingStore(conn)
given [T: JsonCodec]: DurableStorage[T, PostgresBackingStore] = backing.forType[T]
```

### Q8: Nested Durable Values

**Decision**: `Durable[A]` not cached; only `execute`/`await` result cached

```scala
async[Durable] {
  val inner: Durable[Int] = createSubWorkflow()  // NOT cached (description)
  val result: Int = await(inner)                  // cached[0] = execution result
}
```

`Durable[A]` is a computation description (like Free monad). No `DurableStorage[Durable[A], S]` exists.

## Workflow Instance Identity

### Two Levels

1. **Workflow Instance (Global)**: Unique ID for each workflow run
2. **Step Index (Local)**: Sequential index within the instance

```scala
val workflowId = "order-123"

Durable.run(workflowId) {
  async[Durable] {
    val a = step1()           // step[0]
    await(sleep(1.day))       // step[1]
    val b = step2(a)          // step[2]
  }
}
```

### Storage Structure

```
Cache[workflowId] = {
  version: "v1.0",
  currentStep: 2,
  steps: {
    0: serialized(a),
    1: serialized(()),
    2: serialized(b)
  }
}
```

## Example Workflow

```scala
@DurableWorkflow(version = "v1.0")
def orderProcessing(orderId: String): Durable[OrderResult] = async[Durable] {
  // Step 0: Validate order
  val order = validateOrder(orderId)

  // Step 1: Charge payment
  val payment = chargePayment(order.paymentInfo)

  // Step 2: Wait for inventory
  await(waitForInventory(order.items))

  // Step 3: Ship order
  val tracking = shipOrder(order)

  // Step 4: Wait for delivery (could be days)
  await(waitForDelivery(tracking))

  // Step 5: Complete
  OrderResult(orderId, tracking, status = "delivered")
}
```

On replay after restart:
- Steps 0-4: Return cached results instantly
- Step 5: Execute live (if we stopped at step 4)

## Comparison with Alternatives

| Feature | This Design | Temporal | Resonate |
|---------|-------------|----------|----------|
| Execution model | Replay | Replay | Replay |
| Cache granularity | All vals | Activities | Steps |
| Type safety | Full (DurableStorage[T, S]) | Runtime | Runtime |
| Suspension | `await(Durable[A])` | `sleep`, signals | `ctx.run` |
| Language | Scala (macro) | Multiple | TypeScript |

## Cache Backend

### Design

The cache backend uses two levels of abstraction:
1. `DurableStorageBackend` - marker trait for storage implementations (memory, database, etc.)
2. `DurableStorage[T, S]` - parameterized by cached type `T` and backend type `S`

This design decouples the storage type from the monad while enabling compile-time type checking.

```scala
import scala.concurrent.Future

opaque type WorkflowId = String

// Marker trait for storage backends
trait DurableStorageBackend:
  def clear(workflowId: WorkflowId): Future[Unit]
  def saveWorkflowMetadata(workflowId: WorkflowId, metadata: WorkflowMetadata, status: WorkflowStatus): Future[Unit]
  def loadWorkflowMetadata(workflowId: WorkflowId): Future[Option[(WorkflowMetadata, WorkflowStatus)]]
  // ... other workflow-level operations

// Storage for individual values
trait DurableStorage[T, S <: DurableStorageBackend]:
  def store(workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]
  def storeFailure(workflowId: WorkflowId, activityIndex: Int, failure: StoredFailure): Future[Unit]
  def retrieve(workflowId: WorkflowId, activityIndex: Int): Future[Option[Either[StoredFailure, T]]]
  def backend: S
```

Storage instances are created from a backing store:
```scala
class MemoryStorage extends DurableStorageBackend:
  private val data = mutable.HashMap[(WorkflowId, Int), Either[StoredFailure, Any]]()

  def forType[T]: DurableStorage[T, MemoryStorage] = new DurableStorage[T, MemoryStorage]:
    def store(wid, idx, value) = Future.successful(data.put((wid, idx), Right(value)))
    def storeFailure(wid, idx, failure) = Future.successful(data.put((wid, idx), Left(failure)))
    def retrieve(wid, idx) = Future.successful(data.get((wid, idx)).map(_.map(_.asInstanceOf[T])))
    def backend = MemoryStorage.this

  // DurableStorageBackend operations
  def clear(wid) = Future.successful(data.filterInPlace((k, _) => k._1 != wid))
  // ... other operations
```

### Single Activity Index

Cache key is `(workflowId, activityIndex)` - a single counter that increments for each cached activity during workflow execution.

```scala
async[Durable] {
  val a = compute1()     // index 0
  val b = compute2()     // index 1
  await(sleep(1.hour))   // index 2 (suspend)
  val c = compute3(a)    // index 3
  val d = compute4(b)    // index 4
  await(sleep(1.hour))   // index 5 (suspend)
  d
}
```

Storage structure:
```
workflow-123/
  0: a
  1: b
  2: () (suspend result)
  3: c
  4: d
  5: () (suspend result)
```

### Why Single Index?

- Simpler implementation - no need to track two counters
- Deterministic during replay - same execution path = same indices
- Resume uses `resumeFromIndex` to skip already-cached activities

### Activity Node Design

Each Activity captures its own `DurableStorage[A, S]` and `RetryPolicy`:

```scala
// Activity captures storage and retry policy
case Activity[A, S <: DurableStorageBackend](
  compute: () => Future[A],
  storage: DurableStorage[A, S],
  retryPolicy: RetryPolicy
) extends Durable[A]
```

This ensures:
- Type safety at activity creation (compile-time)
- Storage encapsulates its backing store implementation
- Storage type `S` is tracked but doesn't pollute the monad type - user writes `Durable[A]`
- Storage provided via universal given pattern

### Runtime Interpreter (WorkflowRunner)

The interpreter tracks activity index and handles replay.
Each Activity carries its own `DurableStorage[A, S]`:

```scala
case class RunContext(
  workflowId: WorkflowId,
  resumeFromIndex: Int
)

object WorkflowRunner {
  private class InterpreterState(val resumeFromIndex: Int) {
    private var _currentIndex: Int = 0

    def nextIndex(): Int = {
      val idx = _currentIndex
      _currentIndex += 1
      idx
    }

    def isReplayingAt(index: Int): Boolean = index < resumeFromIndex
  }

  def run[A](workflow: Durable[A], ctx: RunContext)
            (using ec: ExecutionContext): Future[WorkflowResult[A]] = {
    val state = new InterpreterState(ctx.resumeFromIndex)
    step(workflow, ctx, state, Nil)
  }

  // When handling Activity - storage from the Activity node:
  case Durable.Activity(compute, storage, retryPolicy) =>
    val index = state.nextIndex()
    if (state.isReplayingAt(index))
      storage.retrieve(ctx.workflowId, index).flatMap {
        case Some(Right(value)) => // cached success
        case Some(Left(failure)) => // cached failure - rethrow as ReplayedException
        case None => // cache miss - shouldn't happen during replay
      }
    else
      compute().flatMap { result =>
        storage.store(ctx.workflowId, index, result)  // cache success & continue
      }.recoverWith { case ex =>
        storage.storeFailure(ctx.workflowId, index, StoredFailure.from(ex)) // cache failure
      }
}
```

### Activity Retry Mechanism

Activities support automatic retries on recoverable failures with configurable policy:

```scala
case class RetryPolicy(
  maxAttempts: Int = 3,
  initialBackoff: FiniteDuration = 100.millis,
  maxBackoff: FiniteDuration = 30.seconds,
  backoffMultiplier: Double = 2.0,
  jitterFactor: Double = 0.1,
  isRecoverable: Throwable => Boolean = RetryPolicy.defaultIsRecoverable,
  computeDelay: Option[(Int, FiniteDuration, FiniteDuration) => FiniteDuration] = None
)
```

**Retry algorithm:**
1. Execute activity's `compute()` function
2. On failure, check `isRecoverable(exception)`
3. If recoverable and attempts < maxAttempts: wait with exponential backoff, retry
4. If not recoverable or max attempts reached: fail workflow
5. On success: store result, continue workflow

**Usage:**
```scala
// Default policy (3 attempts, exponential backoff)
val workflow = Durable.activity { httpCall() }

// Custom policy
val workflow = Durable.activity(
  httpCall(),
  RetryPolicy(maxAttempts = 5, isRecoverable = { case _: TimeoutException => true; case _ => false })
)

// No retries
val workflow = Durable.activity(httpCall(), RetryPolicy.noRetry)
```

**Logging:**
```scala
val config = RunConfig(
  retryLogger = event => println(s"Retry ${event.attempt}/${event.maxAttempts}: ${event.error}"),
  scheduler = Scheduler.default
)
val ctx = RunContext.fresh(WorkflowId("order-123"), config)
```

**Key design decisions:**
- Retry state is transient (not stored in snapshot) - killed workflow resumes from last cached activity
- Storage failures are not retried - they fail the workflow immediately
- `defaultIsRecoverable` excludes `InterruptedException`, `VirtualMachineError`, `LinkageError`
- Exceptions wrapped in `ExecutionException` are unwrapped before checking recoverability

### Compile-Time Check

At each `val` definition, the macro generates code that requires `DurableStorage[T, S]`:

```scala
async[Durable] {
  val x: Int = compute()       // needs DurableStorage[Int, S] ✓
  val y: MyClass = create()    // needs DurableStorage[MyClass, S] ✓ or compile error
}
```

### Storage Instances

Storage instances are created from a backing store:

```scala
// Test: universal given - stores values directly without serialization
given backing: MemoryStorage = MemoryStorage()
given [T]: DurableStorage[T, MemoryStorage] = backing.forType[T]

// Production: backing store handles serialization
given backing: PostgresBackingStore = PostgresBackingStore(conn)
given [T: JsonCodec]: DurableStorage[T, PostgresBackingStore] = backing.forType[T]
```

### Dependencies

```scala
// Future is the base async primitive (standard Scala)
// DurableStorageBackend provides workflow-level operations
// DurableStorage[T, S] uses Future for async storage operations
// WorkflowRunner interprets Durable monad and uses captured storage
// Durable monad is the user-facing API

Future  ←──  DurableStorageBackend  ←──  DurableStorage[T, S]  ←──  WorkflowRunner  ←──  Durable[A]
(base)       (backend trait)             (typed storage)            (interpreter)        (user API)
```

`A ← B` means "B depends on A" / "B uses A"

## dotty-cps-async Integration


### Durable Implementation

```scala
// Preprocessor for Durable
given durablePreprocessor[C <: Durable.DurableCpsContext]: CpsPreprocessor[Durable, C] with
  transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
    ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

object DurablePreprocessor {
  def impl[A: Type, C <: Durable.DurableCpsContext: Type](
    body: Expr[A],
    ctx: Expr[C]
  )(using Quotes): Expr[A] = {
    // Walk AST and transform (top-level only):
    //
    // Val definitions:
    //    val x = rhs  →  val x = await($ctx.activitySync[A] { rhs })
    //
    // Control flow (auto-cache conditions):
    //    if (cond) { ... }  →  if (await($ctx.activitySync[Boolean] { cond })) { ... }
    //
    // DurableStorage[A] is resolved via normal given resolution
    //
    // SKIP (do not transform inside):
    // - Lambda bodies: x => ...
    // - Nested function bodies: def f(...) = ...
    ...
  }
}

// Example transformation:
//
// Source:
//   given backing: MemoryStorage = MemoryStorage()
//   given [T]: DurableStorage[T, MemoryStorage] = backing.forType[T]
//   async[Durable] {
//     val a = compute()
//     val b = list.map(x => process(x))  // lambda untouched
//     val c = await(op)
//     val d = transform(c)
//   }
//
// After preprocessing:
//   async[Durable] {
//     val a = await(ctx.activitySync[Int] { compute() })
//     val b = await(ctx.activitySync[List[X]] { list.map(...) })
//     val c = await(op)
//     val d = await(ctx.activitySync[Y] { transform(c) })
//   }
```

## DurableFunction - Serializable Workflow Definitions

To restore workflows from storage after process restart, we need a way to recreate the `Durable[R]` computation. Since lambdas can't be serialized, we use `DurableFunction` - objects with a known type name that can be looked up by name.

### Unified Trait Design

```scala
/**
 * @tparam Args tuple of argument types (EmptyTuple for no args, Tuple1[T] for one arg, etc.)
 * @tparam R result type (must have DurableStorage for caching)
 * @tparam S storage backend type - concrete for each workflow implementation
 */
trait DurableFunction[Args <: Tuple, R, S <: DurableStorageBackend]:
  def functionName: String
  def apply(args: Args)(using
    backend: S,
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

// Type aliases for common arities (for convenience)
type DurableFunction0[R, S <: DurableStorageBackend] = DurableFunction[EmptyTuple, R, S]
type DurableFunction1[T1, R, S <: DurableStorageBackend] = DurableFunction[Tuple1[T1], R, S]
type DurableFunction2[T1, T2, R, S <: DurableStorageBackend] = DurableFunction[(T1, T2), R, S]
type DurableFunction3[T1, T2, T3, R, S <: DurableStorageBackend] = DurableFunction[(T1, T2, T3), R, S]
```

### DurableFunctionName Typeclass

Provides fully qualified name via compile-time macro:

```scala
trait DurableFunctionName[F <: DurableFunction[?, ?, ?]]:
  def name: String

object DurableFunctionName:
  // Macro derives full class name at compile time
  inline def derived[F <: DurableFunction[?, ?, ?]]: DurableFunctionName[F] = ...

  // Get name and register in one call
  inline def ofAndRegister[F <: DurableFunction[?, ?, ?]](f: F)(using fn: DurableFunctionName[F]): String
```

### Auto-Registration Pattern

When the object initializes, it registers itself in the global registry:

```scala
object PaymentWorkflow extends DurableFunction[Tuple1[String], Payment, MyBackend] derives DurableFunctionName:
  override val functionName = DurableFunctionName.ofAndRegister(this)

  override def apply(args: Tuple1[String])(using
    MyBackend, TupleDurableStorage[Tuple1[String], MyBackend], DurableStorage[Payment, MyBackend]
  ): Durable[Payment] =
    val Tuple1(orderId) = args
    for
      order <- Durable.activity { fetchOrder(orderId) }
      payment <- Durable.activity { processPayment(order) }
    yield payment
```

### ContinueWith - Loop Patterns

Durable workflows don't support mutable variables (`var`). For loops, use `continueWith` which:
- Clears the activity history (prevents unbounded growth)
- Stores new arguments
- Restarts the workflow with new state

Each iteration becomes a new workflow run, making it safe for long-running loops.

**Using continueWith with the preprocessor:**

```scala
import DurableFunctionSyntax.*  // for cleaner single-arg syntax

object CountdownWorkflow extends DurableFunction1[Int, Int, MyBackend] derives DurableFunctionName:
  override val functionName = DurableFunctionName.ofAndRegister(this)

  def apply(args: Tuple1[Int])(using ...): Durable[Int] = async[Durable] {
    val Tuple1(count) = args
    if count <= 0 then
      count  // base case - return result
    else
      // Continue with new count - await because continueWith returns Durable[R]
      await(continueWith(count - 1))
  }
```

**Accumulator pattern (loop with state):**

```scala
object SumWorkflow extends DurableFunction2[Int, Int, Int, MyBackend] derives DurableFunctionName:
  override val functionName = DurableFunctionName.ofAndRegister(this)

  def apply(args: (Int, Int))(using ...): Durable[Int] = async[Durable] {
    val (count, acc) = args
    if count <= 0 then
      acc  // return accumulated result
    else
      await(continueWith(count - 1, acc + count))  // extension syntax for 2 args
  }
```

**Syntax options:**

```scala
// 1. Extension syntax (import DurableFunctionSyntax.*) - no Tuple wrapping
await(continueWith(arg1))              // DurableFunction1
await(continueWith(arg1, arg2))        // DurableFunction2
await(continueWith(arg1, arg2, arg3))  // DurableFunction3

// 2. Base method - explicit Tuple wrapping
await(this.continueWith(Tuple1(arg1)))
await(this.continueWith((arg1, arg2)))

// 3. Raw API - when switching to different workflow
await(Durable.continueAs(otherWorkflow.functionName, Tuple1(arg), otherWorkflow(Tuple1(arg))))
```

### Registry

Platform-specific implementations provide thread-safety where needed:
- JVM: `ConcurrentHashMap`
- JS: Simple `mutable.Map` (single-threaded)
- Native: `TrieMap` (pure Scala, thread-safe)

```scala
trait DurableFunctionRegistry:
  def registerByName(name: String, f: DurableFunction[?, ?, ?]): Unit
  def lookup(name: String): Option[DurableFunction[?, ?, ?]]
  def lookupTyped[Args <: Tuple, R, S <: DurableStorageBackend](name: String): Option[DurableFunction[Args, R, S]]

object DurableFunctionRegistry:
  val global: DurableFunctionRegistry = createRegistry()
```

### Workflow Engine Integration

See [WorkflowEngine Design](design/workflow-engine.md) for the full design.

```scala
// Start workflow
val workflowId = engine.start(PaymentWorkflow, "order-123")
// Engine stores: workflowId -> ("durable.PaymentWorkflow", serialized("order-123"))

// On restore (after process restart):
// 1. Load ("durable.PaymentWorkflow", serialized args) from storage
// 2. Lookup "durable.PaymentWorkflow" in registry -> PaymentWorkflow object
// 3. Deserialize args -> "order-123"
// 4. Call PaymentWorkflow.apply("order-123") to get Durable[Payment]
// 5. Resume with cached activity results
```

