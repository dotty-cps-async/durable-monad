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

### 1b. Async Activities (outbound, parallel)

Activities returning `Future[T]` (or other effect types with `DurableAsync`):

```scala
async[Durable] {
  val r: Future[Int] = asyncFetch(url)  // starts immediately, returns Future
  val x = compute()                      // runs IN PARALLEL with r
  val result = await(r) + x              // wait for r HERE
}
```

Key differences from sync activities:
- Returns `Future[T]` immediately (no blocking at assignment)
- Result `T` is cached when Future completes
- On replay: returns `Future.successful(cachedValue)` if found, restarts activity if not found (handles engine restart during async execution)

**Extensibility:** Async handling works for any monad `F[_]` by implementing the `DurableAsync[F]` typeclass. The preprocessor detects when `DurableAsync[F]` exists in scope and uses `activityAsync` instead of `activitySync`.

See [Async Activities](async-activities.md) for full details.

**Cats-Effect Integration:** For workflows using cats-effect `IO`, the `durable-ce3` module provides `WorkflowSessionRunner[IO]` and `DurableAsync[IO]`. See [Cats-Effect Integration](cats-effect-integration.md) for the design.

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

### 3. Ephemeral Resources (non-cacheable)

Resources that cannot be serialized but need lifecycle management. The preprocessor auto-detects types with `DurableEphemeral[R]` and wraps them in `WithSessionResource` for automatic release.

```scala
// Define ephemeral resource typeclass
given DurableEphemeral[FileHandle] = DurableEphemeral(_.close())

async[Durable] {
  val file = openFile("data.txt")  // Auto-detected as ephemeral
  val content = file.read()         // Activity - cached
  content
}  // file.close() called automatically at scope end
```

**How it works:**
- Preprocessor checks if `DurableEphemeral[R]` exists for val's type
- If found, wraps remaining code in `WithSessionResource(acquire, release)(use)`
- Resource is acquired fresh on each run/resume (NOT cached)
- Release is called at end of scope (success, failure, or suspension)

**Explicit bracket pattern:**

```scala
async[Durable] {
  await(Durable.withResource(
    acquire = openConnection(),
    release = _.close()
  ) { conn =>
    Durable.activitySync { conn.query("SELECT ...") }
  })
}
```

**Use cases:**
- File handles: release closes the file
- Database transactions: release rolls back if not committed
- Network connections: release closes the connection
- Scoped locks: release unlocks

### Summary Table

| Operation Type | Direction | Await? | On Replay | On Failure |
|---------------|-----------|--------|-----------|------------|
| Pure computation | - | No | Return cached | N/A |
| Activity (sync) | Outbound | No | Return cached | Retry if recoverable |
| Activity (async) | Outbound | No (at assignment) | Return cached or restart | Retry if recoverable |
| External call | Inbound | Yes | Skip wait, return cached | Workflow-level handling |
| Ephemeral resource | - | No | Acquire fresh | Release called |

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

### Runtime Interpreter (WorkflowSessionRunner)

The interpreter is generic over effect type `G[_]` with `CpsAsyncMonad[G]`, tracks activity index, and handles replay. Each Activity carries its own `DurableStorage[A, S]` and `EffectTag[F]`.

```scala
class WorkflowSessionRunner[G[_]](
  val targetTag: EffectTag[G]
)(using val monad: CpsAsyncMonad[G]) {

  def run[A](workflow: Durable[A], ctx: RunContext)
            (using S <: DurableStorageBackend, backend: S): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]

  // Lift Future storage operations into G
  def liftFuture[A](fa: Future[A]): G[A] =
    monad.adoptCallbackStyle[A](callback =>
      fa.onComplete(callback)(ExecutionContext.parasitic)
    )
}

object WorkflowSessionRunner {
  case class RunContext(
    workflowId: WorkflowId,
    resumeFromIndex: Int,
    ...
  )

  // Create a Future-based runner
  def forFuture(using ExecutionContext): WorkflowSessionRunner[Future] =
    import cps.monads.FutureAsyncMonad
    new WorkflowSessionRunner[Future](EffectTag.futureTag)
}

// Usage:
val runner = WorkflowSessionRunner.forFuture
runner.run(workflow, ctx).map(_.toOption.get).map { result => ... }

// When handling Activity - checks effect compatibility:
case Durable.Activity(compute, tag, storage, retryPolicy) =>
  targetTag.conversionFrom(tag) match
    case Some(conversion) =>
      // Can handle - convert and execute
      val computeG: G[A] = conversion(compute())
      ...
    case None =>
      // Need bigger runner - return Left(NeedsBiggerRunner)
      ...
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
val config = WorkflowSessionRunner.RunConfig(
  retryLogger = event => println(s"Retry ${event.attempt}/${event.maxAttempts}: ${event.error}"),
  scheduler = Scheduler.default
)
val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("order-123"), config)
```

**Key design decisions:**
- Retry state is transient (not stored in snapshot) - killed workflow resumes from last cached activity
- Storage failures are not retried - they fail the workflow immediately
- `defaultIsRecoverable` excludes `InterruptedException`, `VirtualMachineError`, `LinkageError`
- Exceptions wrapped in `ExecutionException` are unwrapped before checking recoverability

### Environment/Resource Access

Workflows often need access to environment resources (database connections, HTTP clients, external services). These resources are fundamentally different from activity results:

| Category | Journaled? | On Resume | Example |
|----------|------------|-----------|---------|
| **Activities** | YES | Return cached result | `httpGet(url)`, `db.query(sql)` |
| **Environment Access** | NO | Acquire fresh | `getDatabase`, `getHttpClient` |

Resources are ephemeral (connections cannot be serialized) but the operations using them (activities) are cached.

**Getting global resources from AppContext:**

```scala
import com.github.rssh.appcontext.*

// Define provider for your resource
given AppContextProvider[Database] = new AppContextProvider[Database]:
  def get: Database = Database.connect(config.url)

// In workflow - resource is cached in AppContext, fresh on each run
async[Durable] {
  val db = await(Durable.env[Database])  // Not journaled - acquired fresh
  val result = db.query("SELECT ...")     // Activity - cached
}
```

**Scoped resources with bracket pattern:**

```scala
async[Durable] {
  await(Durable.withResource(
    acquire = openFile("data.csv"),
    release = _.close()
  ) { file =>
    Durable.activitySync { processFile(file) }
  })  // file.close() called automatically
}
```

**Key design principles:**
- `WorkflowSessionRunner.RunContext` contains `AppContext.Cache` for environment resources
- `Durable.env[R]` gets resource from AppContext (cached globally, no release)
- `Durable.withResource` provides bracket semantics (acquire/use/release)
- Resource acquisition is NOT journaled - on resume, resources are acquired fresh
- Only the results of operations USING resources (activities) are cached

**Engine configuration:**

```scala
// Engine shares AppContext across all workflows
val config = WorkflowEngineConfig(
  runConfig = WorkflowSessionRunner.RunConfig.default,
  appContext = AppContext.newCache  // Fresh on engine restart
)
val engine = WorkflowEngine(storage, config)

// On restart - create fresh AppContext for fresh resources
def restart(): Unit =
  val freshConfig = WorkflowEngineConfig(appContext = AppContext.newCache)
  val engine = WorkflowEngine(storage, freshConfig)
  engine.recover()  // Workflows resume with fresh resources
```

### Configuration Access

Workflows often need access to external configuration (database URLs, API keys, service endpoints). The `ConfigSource` trait provides a simple way to access configuration without coupling workflows to specific configuration systems.

**ConfigSource trait:**

```scala
trait ConfigSource:
  def getRaw(section: String): Option[String]

object ConfigSource:
  val empty: ConfigSource = _ => None
  def fromMap(entries: Map[String, String]): ConfigSource = entries.get(_)
```

**Accessing configuration in workflows:**

```scala
async[Durable] {
  val dbConfig = await(Durable.configRaw("database"))
    .getOrElse(throw ConfigNotFoundException("database"))
  val config = parseJson[DbConfig](dbConfig)
  val result = queryDatabase(config.url, "SELECT ...")
  result
}
```

**With AppContext caching (for expensive resources):**

```scala
async[Durable] {
  val appCtx = await(Durable.appContext)
  val db = appCtx.getOrCreate[DatabaseConnection] {
    val raw = await(Durable.configRaw("database"))
      .getOrElse(throw ConfigNotFoundException("database"))
    val config = parseJson[ConnectionConfig](raw)
    new DatabaseConnection(config.url, config.poolSize)
  }
  db.query("SELECT * FROM users")
}
```

**Engine provides configuration:**

```scala
val configSource = ConfigSource.fromMap(Map(
  "database" -> """{"url": "jdbc:postgresql://localhost/mydb", "poolSize": 10}""",
  "api.client" -> """{"baseUrl": "https://api.example.com", "timeout": 5000}"""
))

val engine = WorkflowEngine(storage, WorkflowEngineConfig(
  configSource = configSource
))
```

**Non-recoverable exceptions for configuration errors:**

Configuration errors (missing section, parse failure) should not be retried since they won't be fixed by retrying. Use `NonRecoverableException` marker trait:

```scala
case class ConfigNotFoundException(section: String)
  extends RuntimeException(s"Config section not found: $section")
  with NonRecoverableException

case class ConfigParseException(section: String, cause: Throwable)
  extends RuntimeException(s"Failed to parse config section: $section", cause)
  with NonRecoverableException
```

Activities throwing `NonRecoverableException` fail immediately without retry attempts.

**Key characteristics:**
- Configuration is NOT cached (read fresh from ConfigSource on each access)
- `Durable.configRaw(section)` returns `Durable[Option[String]]`
- Use `NonRecoverableException` for config errors to skip retries
- Combine with `AppContext.Cache` for caching parsed configuration or created resources

### Runtime Context Access

Workflows can access runtime context (workflowId, storage backend, AppContext cache) inside `async[Durable]` blocks. Context access is NOT cached - it returns fresh values on each access during replay.

**Available context accessors:**

```scala
async[Durable] {
  // Get workflow ID (for logging, correlation, child workflow naming)
  val wfId = await(Durable.workflowId)

  // Get full WorkflowSessionRunner.RunContext (workflowId, backend, appContext, config, etc.)
  val ctx = await(Durable.runContext)

  // Get storage backend directly
  val backend = await(Durable.backend)

  // Get AppContext cache
  val appCtx = await(Durable.appContext)

  // Generic accessor with lambda
  val resumeIdx = await(Durable.context(_.resumeFromIndex))
}
```

**Key characteristics:**
- Context access uses `LocalComputation` internally - NOT cached, no activity index consumed
- Returns fresh values on each access (even during replay)
- `DurableContext` is the CPS context available inside `async[Durable]` blocks
- Can also use `summon[Durable.DurableContext].workflowId` syntax

**Use case: Logging with workflow ID:**

```scala
async[Durable] {
  val wfId = await(Durable.workflowId)
  println(s"[${wfId.value}] Starting workflow")

  val result = processData()
  println(s"[${wfId.value}] Completed with result: $result")
  result
}
```

### Tagless-Final Pattern for Context-Aware Services

For building reusable services that need runtime context, durable-monad integrates with scala-appcontext's tagless-final infrastructure via `appcontext-tf`. This enables polymorphic services that work with any effect type.

**Available providers:**

```scala
// WorkflowSessionRunner.RunContext is available via AppContextAsyncProvider
given durableRunContextProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]

// AppContextPure[Durable] is auto-derived from CpsMonad[Durable]
```

**Getting context via tagless-final:**

```scala
import com.github.rssh.appcontext.*

async[Durable] {
  // Via AppContext extension
  val ctx = await(AppContext.asyncGet[Durable, WorkflowSessionRunner.RunContext])

  // Via InAppContext (equivalent)
  val ctx2 = await(InAppContext.get[Durable, WorkflowSessionRunner.RunContext])

  println(s"Workflow: ${ctx.workflowId.value}")
}
```

**Building context-aware services:**

Services can be parameterized over effect type `F[_]` and use `InAppContext` to access dependencies:

```scala
import com.github.rssh.appcontext.*
import cps.*

// Service trait - polymorphic over effect type
trait WorkflowLogger[F[_]]:
  def info(msg: String): F[Unit]
  def error(msg: String, ex: Throwable): F[Unit]

object WorkflowLogger:
  // Implementation that needs WorkflowSessionRunner.RunContext
  def apply[F[_]: CpsMonad](using lookup: AppContextAsyncProviderLookup[F, WorkflowSessionRunner.RunContext]): WorkflowLogger[F] =
    new WorkflowLogger[F]:
      def info(msg: String): F[Unit] =
        summon[CpsMonad[F]].flatMap(lookup.get) { ctx =>
          summon[CpsMonad[F]].pure(println(s"[${ctx.workflowId.value}] INFO: $msg"))
        }

      def error(msg: String, ex: Throwable): F[Unit] =
        summon[CpsMonad[F]].flatMap(lookup.get) { ctx =>
          summon[CpsMonad[F]].pure(println(s"[${ctx.workflowId.value}] ERROR: $msg - ${ex.getMessage}"))
        }

  // Provider - makes WorkflowLogger available via AppContext.asyncGet
  given [F[_]: CpsMonad](using AppContextAsyncProviderLookup[F, WorkflowSessionRunner.RunContext]): AppContextAsyncProvider[F, WorkflowLogger[F]] with
    def get: F[WorkflowLogger[F]] = summon[CpsMonad[F]].pure(WorkflowLogger.apply[F])
```

**Using the service in a workflow:**

```scala
async[Durable] {
  // Get the logger (instantiated with Durable effect type)
  val logger = await(AppContext.asyncGet[Durable, WorkflowLogger[Durable]])

  await(logger.info("Starting order processing"))

  val result = try {
    processOrder()
  } catch {
    case ex: Exception =>
      await(logger.error("Order processing failed", ex))
      throw ex
  }

  await(logger.info(s"Order completed: $result"))
  result
}
```

**Key benefits:**
- Services are reusable across different effect types (Future, IO, Durable)
- Context is accessed lazily when service methods are called
- No manual context threading - `InAppContext` handles it
- Uses `LocalComputation` internally - NOT cached, fresh on each access

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
// WorkflowSessionRunner interprets Durable monad and uses captured storage
// Durable monad is the user-facing API

Future  ←──  DurableStorageBackend  ←──  DurableStorage[T, S]  ←──  WorkflowSessionRunner  ←──  Durable[A]
(base)       (backend trait)             (typed storage)                 (interpreter)            (user API)
```

`A ← B` means "B depends on A" / "B uses A"

## dotty-cps-async Integration


### Durable Implementation

```scala
// Preprocessor for Durable
given durablePreprocessor[C <: Durable.DurableContext]: CpsPreprocessor[Durable, C] with
  transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
    ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

object DurablePreprocessor {
  def impl[A: Type, C <: Durable.DurableContext: Type](
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

**Syntax options for `continueWith` (same workflow):**

```scala
// 1. Extension syntax - no Tuple wrapping (available automatically)
await(continueWith(arg1))              // DurableFunction1
await(continueWith(arg1, arg2))        // DurableFunction2
await(continueWith(arg1, arg2, arg3))  // DurableFunction3

// 2. Base method - explicit Tuple wrapping
await(this.continueWith(Tuple1(arg1)))
await(this.continueWith((arg1, arg2)))
```

### ContinueAs - Workflow Transitions

Use `continueAs` to transition from one workflow to another (different from `continueWith` which continues the same workflow):

```scala
object ProcessingWorkflow extends DurableFunction1[Int, String, MyBackend] derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  def apply(n: Int)(using MyBackend): Durable[String] = async[Durable] {
    if n > threshold then
      // Transition to a different workflow
      await(CompletionWorkflow.continueAs(s"result-$n"))  // extension syntax
    else
      await(continueWith(n + 1))  // continue as self
  }
```

**Syntax options for `continueAs` (different workflow):**

```scala
// 1. Extension syntax - no Tuple wrapping (available automatically)
await(OtherWorkflow.continueAs(arg1))              // DurableFunction1
await(OtherWorkflow.continueAs(arg1, arg2))        // DurableFunction2
await(OtherWorkflow.continueAs(arg1, arg2, arg3))  // DurableFunction3

// 2. Trait method with explicit target - explicit Tuple wrapping
await(this.continueAs(OtherWorkflow)(Tuple1(arg1)))
await(this.continueAs(OtherWorkflow)((arg1, arg2)))

// 3. Durable.continueAs - direct API
await(Durable.continueAs(OtherWorkflow)(Tuple1(arg1)))
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

