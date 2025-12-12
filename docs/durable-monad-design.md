# Durable Monad Design - Replay-Based Execution

## Overview

This document describes the design of a replay-based durable execution monad for Scala. The goal is to enable workflows that can survive process restarts by caching intermediate results and replaying from saved state.

## Core Principles

1. **Replay-based** - Portable across JVM/JS/Native
2. **All vals cached** - Every `val` definition is automatically cached by execution index
3. **Type-enforced** - `DurableCacheBackend[T, S]` typeclass required for all cached types (where `S` is the storage type)
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
A              // concrete result, cached via DurableCacheBackend[A]
await/execute  // converts Durable[A] → A, caches result
```

### Key Types

```scala
// Marker trait: no mutable variables allowed in async blocks
trait CpsMonadNoVars[F[_]]

// Serialization typeclass for cached values (S = storage type)
trait DurableCacheBackend[T, S] {
  def store(workflowId: WorkflowId, stepIndex: Int, valIndex: Int, value: T): Future[Unit]
  def retrieve(workflowId: WorkflowId, stepIndex: Int, valIndex: Int): Future[Option[T]]
}

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

**Decision**: Recursion and combinators only, no `var`

```scala
// Use recursion
def processItems(items: List[Item], acc: List[Result]): Durable[List[Result]] =
  items match {
    case Nil => Durable.pure(acc)
    case h :: t =>
      val r = process(h)           // cached
      await(sleep(1.minute))       // suspension
      processItems(t, acc :+ r)    // recurse
  }

// Or combinators
Durable.traverse(list)(item => process(item))
```

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

### Q7: DurableCacheBackend Types

**Decision**: All vals require `DurableCacheBackend[T, S]` (where `S` is the storage type)

```scala
async[Durable] {
  val a: Int = compute()           // needs DurableCacheBackend[Int, S] ✓
  val b: String = fetch()          // needs DurableCacheBackend[String, S] ✓
  val c: MyClass = create()        // needs DurableCacheBackend[MyClass, S] ✓
  val d: Connection = open()       // DurableCacheBackend[Connection, S]? ✗ compile error
}
```

Standard instances provided:
```scala
given [S]: DurableCacheBackend[Int, S]
given [S]: DurableCacheBackend[String, S]
given [S]: DurableCacheBackend[Boolean, S]
given [A, S](using DurableCacheBackend[A, S]): DurableCacheBackend[List[A], S]
given [A, S](using DurableCacheBackend[A, S]): DurableCacheBackend[Option[A], S]
// etc.
```

Users derive for custom types:
```scala
case class MyData(x: Int, y: String) derives DurableCacheBackend
```

### Q8: Nested Durable Values

**Decision**: `Durable[A]` not cached; only `execute`/`await` result cached

```scala
async[Durable] {
  val inner: Durable[Int] = createSubWorkflow()  // NOT cached (description)
  val result: Int = await(inner)                  // cached[0] = execution result
}
```

`Durable[A]` is a computation description (like Free monad). No `DurableCacheBackend[Durable[A], S]` exists.

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
| Type safety | Full (DurableCacheBackend) | Runtime | Runtime |
| Suspension | `await(Durable[A])` | `sleep`, signals | `ctx.run` |
| Language | Scala (macro) | Multiple | TypeScript |

## Cache Backend

### Design

The cache backend is a typeclass parameterized by the cached type `T` and storage type `S`. It uses `Future` as the minimal async primitive from standard Scala (works on JVM/JS/Native).

```scala
import scala.concurrent.Future

opaque type WorkflowId = String

trait DurableCacheBackend[T, S] {
  def store(storage: S, workflowId: WorkflowId, activityIndex: Int, value: T): Future[Unit]
  def retrieve(storage: S, workflowId: WorkflowId, activityIndex: Int): Future[Option[T]]
}
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

### Type-Safe Backend Capture (Decoupled Storage)

The monad type is `Durable[A]` - the storage type `S` is NOT part of the monad type.
Instead, `S` is existential in the Activity node, tying backend and storage together:

```scala
// Marker trait for storage types - allows macro to find storage in scope
trait DurableStorage

// Activity has existential S - hidden from Durable[A]
case Activity[A, S](
  compute: () => Future[A],
  backend: DurableCacheBackend[A, S],
  storage: S  // S is existential - ties backend and storage together
) extends Durable[A]  // Note: Durable[A], not Durable[A, S]
```

This ensures:
- Type safety at activity creation (compile-time)
- No type erasure problems at runtime (backend and storage have same S)
- Storage type decoupled from the monad - user writes `Durable[A]`
- Storage provided via AppContext pattern (`given MemoryStorage = ...`)

### Runtime Interpreter (WorkflowRunner)

The interpreter tracks activity index and handles replay.
Note: `RunContext` no longer needs storage - each Activity carries its own:

```scala
// RunContext no longer has storage type parameter
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

  // No S type parameter - storage is in each Activity
  def run[A](workflow: Durable[A], ctx: RunContext)
            (using ec: ExecutionContext): Future[WorkflowResult[A]] = {
    val state = new InterpreterState(ctx.resumeFromIndex)
    step(workflow, ctx, state, Nil)
  }

  // When handling Activity - backend and storage from the Activity node:
  case Durable.Activity(compute, backend, storage) =>
    val index = state.nextIndex()
    if (state.isReplayingAt(index))
      backend.retrieve(storage, ctx.workflowId, index)  // use cached
    else
      compute().flatMap { result =>
        backend.store(storage, ctx.workflowId, index, result)  // cache & continue
      }
}
```

### Compile-Time Check

At each `val` definition, the macro checks that `DurableCacheBackend[T, S]` exists:

```scala
async[Durable] {
  val x: Int = compute()       // summon[DurableCacheBackend[Int, S]] ✓
  val y: MyClass = create()    // summon[DurableCacheBackend[MyClass, S]] ✓ or compile error
}
```

### Storage Instances

For test storage (MemoryStorage), a universal instance accepts any type:

```scala
// Test: universal given - stores values directly without serialization
given [T]: DurableCacheBackend[T, MemoryStorage] = ...

// Production: requires serialization proof
given [T: DurableSerializer]: DurableCacheBackend[T, PostgresDB] = ...
```

### Dependencies

```scala
// Future is the base async primitive (standard Scala)
// DurableCacheBackend uses Future for async storage operations
// WorkflowRunner interprets Durable monad and uses captured backends
// Durable monad is the user-facing API

Future  ←──  DurableCacheBackend[T, S]  ←──  WorkflowRunner  ←──  Durable[A]
(base)       (storage)                      (interpreter)        (user API)
```

`A ← B` means "B depends on A" / "B uses A"

Note: `Durable[A]` has no S in its type - storage is determined via `given DurableStorage` in scope.

## dotty-cps-async Integration


### Durable Implementation

```scala
// Preprocessor for Durable - no S type parameter
given durablePreprocessor[C <: Durable.DurableCpsContext]: CpsPreprocessor[Durable, C] with
  transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
    ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

object DurablePreprocessor {
  def impl[A: Type, C <: Durable.DurableCpsContext: Type](
    body: Expr[A],
    ctx: Expr[C]
  )(using Quotes): Expr[A] = {
    // 1. Find storage type S by searching for DurableStorage given in scope
    val storageType = Implicits.search(TypeRepr.of[DurableStorage]) match
      case success => success.tree.tpe.widen
      case failure => report.errorAndAbort("No DurableStorage found")

    // 2. Walk AST and transform (top-level only):
    //
    // Val definitions:
    //    val x = rhs  →  val x = await($ctx.activitySync[A, S] { rhs })
    //
    // Control flow (auto-cache conditions):
    //    if (cond) { ... }  →  if (await($ctx.activitySync[Boolean, S] { cond })) { ... }
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
//   given MemoryStorage = MemoryStorage()
//   async[Durable] {
//     val a = compute()
//     val b = list.map(x => process(x))  // lambda untouched
//     val c = await(op)
//     val d = transform(c)
//   }
//
// After preprocessing:
//   async[Durable] {
//     val a = await(ctx.activitySync[Int, MemoryStorage] { compute() })
//     val b = await(ctx.activitySync[List[X], MemoryStorage] { list.map(...) })
//     val c = await(op)
//     val d = await(ctx.activitySync[Y, MemoryStorage] { transform(c) })
//   }
```

