# Environment/Dependency Injection for Durable Functions

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| `WithSessionResource` ADT case | ✅ Implemented | `Durable.scala:110-134` |
| `Durable.env[R]` | ✅ Implemented | Uses `AppContextProvider[R]` |
| `Durable.withResource` | ✅ Implemented | Bracket pattern with explicit acquire/release |
| `Durable.withResourceSync` | ✅ Implemented | Sync version for preprocessor (auto-transforms to async) |
| `DurableEphemeral[T]` typeclass | ✅ Implemented | Simple release-only typeclass |
| Preprocessor resource detection | ✅ Implemented | Auto-detects `DurableEphemeral[T]` |
| `RunContext.appContext` | ✅ Implemented | `WorkflowSessionRunner.scala:483` |
| `handleWithSessionResource` interpreter | ✅ Implemented | `WorkflowSessionRunner.scala:347-378` |
| `WorkflowEngineConfig.appContext` | ✅ Implemented | `WorkflowEngineConfig.scala:15` |
| Engine threading appContext | ✅ Implemented | `WorkflowEngineImpl.scala` |
| Unit tests | ✅ Implemented | `ResourceAccessTest.scala`, `DurablePreprocessorTest.scala` |
| Documentation | ✅ Implemented | `durable-monad.md` updated |

### What Works Now

**1. Preprocessor auto-detection of ephemeral resources:**

```scala
// Define how to release the resource type
given DurableEphemeral[FileHandle] = DurableEphemeral(_.close())

async[Durable] {
  val file: FileHandle = openFile("data.txt")  // Auto-detected, wrapped in bracket
  val content = file.read()                     // Activity - cached
  content
}  // file.close() called automatically
```

**2. Global cached resources (using AppContextProvider):**

```scala
val db = await(Durable.env[Database])
```

**3. Explicit scoped resources:**

```scala
await(Durable.withResource(acquire = openFile("x"), release = _.close()) { file =>
  Durable.activitySync { processFile(file) }
})
```

### Key Design: Two Orthogonal Concerns

| Concern | Mechanism | Purpose |
|---------|-----------|---------|
| **Resource cleanup** | `DurableEphemeral[R]` | Marks type as needing release, provides release function |
| **Getting from context** | `Durable.env[R]` + `AppContextProvider[R]` | Dependency injection pattern |

These are orthogonal - a type can have one, both, or neither.

---

## Problem Analysis

### The Core Tension

DurableFunctions need **environment resources** (databases, HTTP clients, external services) but the workflow engine must handle **suspension and resumption** across process restarts. This creates a fundamental tension:

1. **Serializable vs Ephemeral**: Workflow state (activity results, intermediate values) must be serialized and cached for replay. But environment resources (connections, clients) are inherently ephemeral and cannot be serialized.

2. **Deterministic Replay**: On resume, the workflow replays cached results to reach the suspension point. Environment access must work correctly both during initial execution AND during replay.

3. **General-Purpose Engine**: The workflow engine should not have hardcoded knowledge about specific environments (databases, services). It should provide a mechanism for environments, not concrete implementations.

### Solution Overview

The preprocessor distinguishes between cacheable values and resources:

| Type | Typeclass | On Resume |
|------|-----------|-----------|
| **Cacheable** | `DurableStorage[T, S]` | Return cached result |
| **Resource** | `WorkflowSessionResource[T]` | Acquire fresh |

Resources have three possible scopes:

| Scope | Lifecycle | Example |
|-------|-----------|---------|
| `Engine` | Shared across workflows, cached in AppContext | Connection pool |
| `Workflow` | Per workflow run, released on completion | Request context |
| `Bracket` | Per block, released at end of scope | Transaction, file |

**Example**:
```scala
async[Durable] {
  val db: Database = getDatabase()  // Has WorkflowSessionResource[Database]
  val result = db.query(...)        // Has DurableStorage[QueryResult, S]
  result
}
// Preprocessor wraps db in WithResource bracket, query in activity
```

### Current Architecture Gap

The current DurableFunction receives only:
```scala
def apply(args: Args)(using storageBackend: S): Durable[R]
```

And RunContext contains:
```scala
case class RunContext(
  workflowId: WorkflowId,
  backend: DurableStorageBackend,
  resumeFromIndex: Int,
  activityOffset: Int,
  config: RunConfig
)
```

**Missing**: `AppContext.Cache` for resource management and `WorkflowSessionResource[T]` typeclass.

## Key Design Insight

The critical insight is distinguishing between two categories of operations:

| Category | Journaled? | On Replay | Example |
|----------|------------|-----------|---------|
| **Activities** | YES | Return cached result | `httpGet(url)`, `db.query(sql)` |
| **Environment Access** | NO | Execute fresh | `getDatabase`, `getHttpClient` |

Environment access should be a **"side channel"** parallel to the journaled workflow. It executes on every run but doesn't affect replay determinism because it only provides resources—the actual operations using those resources are activities that ARE cached.

## Proposed Solution: AppContext Integration

### Design Overview

```
                    ┌─────────────────────────────────────────┐
                    │           WorkflowEngine                 │
                    │                                         │
                    │  ┌─────────────┐   ┌──────────────┐    │
  AppContext ──────▶│  │ RunContext  │   │ Coordinator  │    │
  (fresh on start)  │  │ + appCtx    │   │              │    │
                    │  └──────┬──────┘   └──────────────┘    │
                    │         │                               │
                    │         ▼                               │
                    │  ┌─────────────────────────────────┐   │
                    │  │    WorkflowSessionRunner        │   │
                    │  │                                 │   │
                    │  │  Durable.env[Database]          │   │
                    │  │    └─► NOT cached, fresh access │   │
                    │  │                                 │   │
                    │  │  db.query("SELECT ...")         │   │
                    │  │    └─► Activity, cached result  │   │
                    │  └─────────────────────────────────┘   │
                    └─────────────────────────────────────────┘
```

### Component 1: Extended RunContext

Add AppContext to the execution context:

```scala
// New RunContext with environment
case class RunContext(
  workflowId: WorkflowId,
  backend: DurableStorageBackend,
  appContext: AppContext.Cache,  // NEW: environment resources
  resumeFromIndex: Int,
  activityOffset: Int,
  config: RunConfig
)
```

### Component 2: Unified Resource Access Primitive

New unified Durable node for resource acquisition (NOT journaled). **Key insight**: Both `env` and `withResource` are resource acquisition patterns that differ only in scoping.

```scala
// In Durable.scala
enum Durable[+A]:
  // ... existing cases ...

  // Unified resource acquisition - NOT workflow-cached
  // acquire/release captured at creation time, acquire takes RunContext for appContext access
  case WithSessionResource[R, B](
    acquire: RunContext => R,
    release: R => Unit,
    use: R => Durable[B]
  ) extends Durable[B]

object Durable:
  // Get resource from AppContext (cached globally, no release)
  inline def env[R](using provider: AppContextProvider[R]): Durable[R] =
    val key = AppContext.cacheKey[R]
    WithSessionResource[R, R](
      acquire = ctx => ctx.appContext.getOrCreate(key)(provider.get),
      release = _ => (),  // No release for cached resources
      use = r => Durable.pure(r)
    )

  // Scoped resource with explicit acquire/release
  def withResource[R, A](acquire: => R, release: R => Unit)(use: R => Durable[A]): Durable[A] =
    WithSessionResource[R, A](
      acquire = _ => acquire,
      release = release,
      use = use
    )

  // Scoped resource from WorkflowSessionResource
  def withEnv[R, A](use: R => Durable[A])(using resource: WorkflowSessionResource[R]): Durable[A] =
    WithSessionResource[R, A](
      acquire = ctx => resource.acquire(ctx),
      release = resource.release,
      use = use
    )
```

### Component 3: Interpreter Handling

Single handler for all resource patterns:

```scala
private def handleWithSessionResource[A, R, B](
  wr: Durable.WithSessionResource[R, B],
  ctx: RunContext,
  state: InterpreterState,
  stack: List[StackFrame]
)(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
  // No index increment - not journaled
  val resource = wr.acquire(ctx)  // Access appContext via ctx
  try
    // Run inner workflow, then release and return result
    stepsUntilSuspend(wr.use(resource), ctx, state, stack).transform { result =>
      wr.release(resource)
      result  // Return the original result after release
    }
  catch
    case e: Throwable =>
      wr.release(resource)
      Future.failed(e)
```

**Unified pattern**:
- `Durable.env[R]`: acquire from AppContext cache, no release, use = pure(r)
- `Durable.withResource`: custom acquire/release, scoped use
- `Durable.withEnv[R]`: acquire/release from WorkflowSessionResource[R], scoped use

### Component 4: WorkflowEngine Integration

Engine receives AppContext and passes it through:

```scala
object WorkflowEngine:
  def apply[S <: DurableStorageBackend](
    storage: S,
    appContext: AppContext.Cache  // NEW: environment context
  )(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S]

// Or via config
case class WorkflowEngineConfig(
  runConfig: RunConfig = RunConfig.default,
  appContext: AppContext.Cache = AppContext.newCache  // NEW
)
```

### Component 5: DurableFunction with Environment

Two approaches (can support both):

**Approach A: Implicit resolution (recommended)**
```scala
// Environment resolved implicitly within workflow
object OrderWorkflow extends DurableFunction1[String, Order, MyBackend]:
  override def apply(orderId: String)(using MyBackend): Durable[Order] =
    async[Durable] {
      // Environment accessed via Durable.env - fresh on each run
      val db = await(Durable.env[Database])
      val http = await(Durable.env[HttpClient])

      // Activities - results cached
      val orderData = db.query(s"SELECT * FROM orders WHERE id = $orderId")
      val payment = http.post(paymentUrl, orderData)

      Order(orderId, orderData, payment)
    }
```

**Approach B: Explicit environment type parameter**
```scala
// Environment type is explicit in the signature
trait DurableFunctionEnv[Args <: Tuple, R, S <: DurableStorageBackend, E]:
  def apply(args: Args)(using backend: S, env: E): Durable[R]
```

### Usage Example

```scala
// 1. Define environment resources
case class AppEnv(
  database: Database,
  httpClient: HttpClient,
  emailService: EmailService
)

// 2. Create providers
given AppContextProvider[Database] = AppContextProvider.of(
  Database.connect(config.dbUrl)
)
given AppContextProvider[HttpClient] = AppContextProvider.of(
  HttpClient.create(config.timeout)
)

// 3. Build AppContext
val appContext = AppContext.newCache
// Providers are resolved lazily when first accessed

// 4. Create engine with context
val engine = WorkflowEngine(storage, appContext)

// 5. On restart - recreate AppContext fresh
def restart(): Unit =
  val freshContext = AppContext.newCache
  val engine = WorkflowEngine(storage, freshContext)
  engine.recover()  // Workflows resume with fresh resources
```

## Alternative Approaches Considered

### Alternative 1: Environment Type Parameter on Durable

```scala
trait Durable[E, +A]  // E = environment type
```

**Rejected because:**
- Adds complexity to all Durable types
- Hard to compose workflows with different environments
- Environment becomes part of the type signature

### Alternative 2: Reader Monad Pattern

```scala
type Workflow[E, A] = E => Durable[A]
```

**Rejected because:**
- Changes the mental model significantly
- Complicates existing API
- Environment is threaded explicitly everywhere

### Alternative 3: Service Locator in Backend

```scala
trait DurableStorageBackend:
  def getService[S]: S
```

**Rejected because:**
- Mixes concerns (storage + services)
- Not type-safe
- Backend becomes a god object

## Implementation Plan

### Phase 1: Add scala-appcontext Dependency ✅ COMPLETED

1. **Update build.sbt** ✅
   - Dependency already present: `"com.github.rssh" %%% "appcontext" % "0.3.0"`
   - Cross-platform compatibility (JVM/JS/Native) confirmed

### Phase 2: Core Durable ADT Extensions ✅ COMPLETED

2. **Extend Durable enum with unified resource case** ✅
   - File: `core/shared/src/main/scala/durable/Durable.scala`
   - Add single unified case (handles both env and bracket patterns):
     ```scala
     case WithSessionResource[R, B](
       acquire: RunContext => R,  // Can access appContext or ignore it
       release: R => Unit,
       use: R => Durable[B]
     ) extends Durable[B]
     ```
   - Add companion methods:
     ```scala
     // Get cached resource from AppContext
     inline def env[R](using provider: AppContextProvider[R]): Durable[R] =
       val key = AppContext.cacheKey[R]
       WithSessionResource[R, R](
         acquire = ctx => ctx.appContext.getOrCreate(key)(provider.get),
         release = _ => (),
         use = r => Durable.pure(r)
       )

     // Explicit acquire/release (ignores AppContext)
     def withResource[R, A](acquire: => R, release: R => Unit)(use: R => Durable[A]): Durable[A] =
       WithSessionResource[R, A](_ => acquire, release, use)

     // From WorkflowSessionResource
     def withEnv[R, A](use: R => Durable[A])(using r: WorkflowSessionResource[R]): Durable[A] =
       WithSessionResource[R, A](ctx => r.acquire(ctx), r.release, use)
     ```

   **Note**: `env[R]` and `withResource` are implemented. `withEnv[R]` uses WorkflowSessionResource.

### Phase 2b: DurablePreprocessor Update ❌ DEFERRED

4. **Modify DurablePreprocessor to handle both cacheables and resources**
   - File: `core/shared/src/main/scala/durable/DurablePreprocessor.scala`

   **New typeclass**: `WorkflowSessionResource[T]` for non-cacheable resources with scope:
   ```scala
   enum ResourceScope:
     case Engine    // Shared across workflows, cached in AppContext.Cache
     case Workflow  // Per workflow invocation, acquired on start/resume, released on completion
     case Bracket   // Per bracket block, acquired at val, released at end of scope

   trait WorkflowSessionResource[T]:
     def scope: ResourceScope
     def acquire(ctx: RunContext, cache: AppContext.Cache): T
     def release(value: T): Unit
   ```

   **Scope semantics**:
   - `Engine`: Resource is cached in `AppContext.Cache`, no release (e.g., connection pool)
   - `Workflow`: Resource acquired per workflow run, released when workflow completes/suspends (e.g., request-scoped context)
   - `Bracket`: Resource acquired at val definition, released at end of enclosing block (e.g., transaction, file handle)

   **Updated preprocessor logic** for `val x: T = expr`:

   ```scala
   def wrapWithActivity(expr: Term, isLastInBlock: Boolean): Term =
     val exprType = widenAll(expr.tpe)

     // 1. Check if already Durable[A]
     if isDurableType(exprType) then
       buildAwaitCall(expr)

     // 2. Check for DurableStorage[T, S] - cacheable
     else if hasDurableStorage(exprType) then
       wrapWithActivitySync(expr, exprType)

     // 3. Check for WorkflowSessionResource[T] - non-cacheable resource
     else if hasWorkflowSessionResource(exprType) then
       val resource = summon[WorkflowSessionResource[exprType]]
       resource.scope match
         case ResourceScope.Engine =>
           // Cache in AppContext, no bracket needed
           wrapWithEngineScopedResource(expr, exprType)
         case ResourceScope.Workflow =>
           // Acquire at workflow start, release at completion
           wrapWithWorkflowScopedResource(expr, exprType)
         case ResourceScope.Bracket =>
           // Wrap rest of block in bracket
           wrapWithSessionResourceBracket(expr, exprType, restOfBlock)

     // 4. Check for DurableAsync[F] - async activity
     else if hasDurableAsync(exprType) then
       wrapWithActivityAsync(expr, exprType)

     // 5. Neither - compile error
     else
       report.errorAndAbort(
         s"Type ${exprType.show} is not cacheable (no DurableStorage) " +
         s"and not a resource (no WorkflowSessionResource)"
       )
   ```

   **Scope-specific handling**:
   - `Engine`: `val db = ctx.appContext.getOrCreate(key)(resource.acquire(ctx, cache))`
   - `Workflow`: Wrap entire workflow body in `WithSessionResource`, resource acquired once per run
   - `Bracket`: Wrap rest of current block in `WithSessionResource`, resource scoped to that block

   **Example transformation**:
   ```scala
   // Source
   async[Durable] {
     val db: Database = getDatabase()  // Has WorkflowSessionResource[Database]
     val result = db.query(...)        // Has DurableStorage[QueryResult, S]
     result
   }

   // Transformed
   async[Durable] {
     await(WithSessionResource[Database, QueryResult](
       acquire = ctx => summon[WorkflowSessionResource[Database]].acquire(ctx, ctx.appContext),
       release = value => summon[WorkflowSessionResource[Database]].release(value),
       use = db => {
         val result = await(ctx.activitySync { db.query(...) })
         Durable.pure(result)
       }
     ))
   }
   ```

   **Key insight**: Resource vals don't get cached - instead they trigger a bracket that wraps the rest of the block. The resource is:
   - Acquired fresh on each run/resume
   - Released at end of scope (or on suspend/failure)
   - NOT journaled (ephemeral)

   **Reason deferred**: This is a convenience feature. Users can achieve the same result by explicitly using `Durable.env[R]` or `Durable.withResource`. Auto-detection adds complexity to the preprocessor and can be added later when there's demand.

### Phase 3: RunContext Extension ✅ COMPLETED

4. **Add AppContext.Cache to RunContext** ✅
   - File: `core/shared/src/main/scala/durable/WorkflowSessionRunner.scala` (RunContext at line 480)
   - Added field: `appContext: AppContext.Cache`
   - Updated factory methods in `object RunContext` (fresh, resume, fromSnapshot)

### Phase 4: Interpreter Updates ✅ COMPLETED

5. **Handle WithSessionResource in WorkflowSessionRunner** ✅
   - File: `core/shared/src/main/scala/durable/WorkflowSessionRunner.scala`
   - Added `handleWithSessionResource` at lines 347-378
   - No index increment (not journaled)
   - Proper cleanup on failure/suspend
   - Updated pattern match in `stepsUntilSuspend` function

### Phase 5: Engine Integration ✅ COMPLETED

6. **Update WorkflowEngineConfig** ✅
   - File: `core/shared/src/main/scala/durable/WorkflowEngineConfig.scala`
   - Added: `appContext: AppContext.Cache = AppContext.newCache`

7. **Thread AppContext through WorkflowEngine** ✅
   - File: `core/shared/src/main/scala/durable/engine/coordinator/WorkflowEngineImpl.scala`
   - Pass appContext from config to RunContext when starting/resuming workflows

### Phase 6: Testing ✅ COMPLETED

8. **Add unit tests** ✅
   - File: `core/shared/src/test/scala/durable/ResourceAccessTest.scala`
   - Test cases (7 tests):
     - `Durable.env[T]` resolves from AppContext ✅
     - `Durable.env[T]` returns same instance within same run ✅
     - `withResource` releases on success ✅
     - `withResource` releases on failure ✅
     - Activity caching with fresh resources on replay ✅
     - Nested `withResource` releases in correct order ✅
     - Resource acquisition failure is handled ✅

### Phase 7: Documentation ✅ COMPLETED

9. **Update design docs** ✅
   - File: `docs/design/durable-monad.md`
   - Added section on Environment/Resource Access
   - Documented the env vs activity distinction

## Files Modified/Created

| File | Action | Status | Description |
|------|--------|--------|-------------|
| `build.sbt` | Modify | ✅ Done | appcontext dependency already present |
| `core/shared/src/main/scala/durable/Durable.scala` | Modify | ✅ Done | Added `WithSessionResource` case, `env[R]`, `withResource`, `withEnv` |
| `core/shared/src/main/scala/durable/WorkflowSessionResource.scala` | Create | ✅ Done | Resource typeclass with Process/Bracket scopes |
| `core/shared/src/main/scala/durable/DurablePreprocessor.scala` | Modify | ❌ Deferred | Auto-detect resources via WorkflowSessionResource |
| `core/shared/src/main/scala/durable/WorkflowSessionRunner.scala` | Modify | ✅ Done | Added appContext to RunContext, handleWithSessionResource |
| `core/shared/src/main/scala/durable/WorkflowEngineConfig.scala` | Modify | ✅ Done | Added appContext field |
| `core/shared/src/main/scala/durable/engine/coordinator/WorkflowEngineImpl.scala` | Modify | ✅ Done | Thread appContext to RunContext |
| `core/shared/src/test/scala/durable/ResourceAccessTest.scala` | Create | ✅ Done | 7 tests for resource access patterns |
| `docs/design/durable-monad.md` | Modify | ✅ Done | Added Environment/Resource Access section |

## Design Decisions (Confirmed)

1. **Dependency**: scala-appcontext is a **hard dependency** - part of core API ✅
2. **Scoping**: **Global scope** - resources shared via AppContext.Cache across workflows ✅
3. **Lifecycle**: **Explicit acquire/release** - support bracket pattern for scoped resources ✅
4. **Async Init**: **Sync only for v1** - keep initial implementation simple ✅
5. **Preprocessor auto-detection**: **Deferred** - users must explicitly use `Durable.env[R]` or `Durable.withResource`

## Detailed Design: Resource Lifecycle

### Unified Resource Pattern (WithSessionResource)

All resource access goes through a single `WithSessionResource` case. The `acquire` function takes `RunContext` to optionally access `appContext`.

```scala
// Single unified case in Durable ADT
case WithSessionResource[R, B](
  acquire: RunContext => R,  // Can access ctx.appContext or ignore ctx
  release: R => Unit,
  use: R => Durable[B]
) extends Durable[B]
```

**User-facing APIs (one ADT case):**

| API | acquire | release | use | Purpose | Status |
|-----|---------|---------|-----|---------|--------|
| `env[R]` | From AppContext cache | No-op | pure(r) | Get cached global resource | ✅ Implemented |
| `withResource` | Custom | Custom | User-provided | Manual bracket pattern | ✅ Implemented |
| `withEnv[R]` | From WorkflowSessionResource | From WorkflowSessionResource | User-provided | Scoped resource from typeclass | ✅ Implemented |

### WorkflowSessionResource Typeclass ✅ IMPLEMENTED

```scala
// Resource scope determines lifecycle
object WorkflowSessionResource:
  enum Scope:
    case Process  // Cached in AppContext, no release (process lifetime)
    case Bracket  // Per block, released at end of scope

// Typeclass for non-cacheable resources
trait WorkflowSessionResource[R]:
  def scope: WorkflowSessionResource.Scope
  def acquire(ctx: RunContext): R
  def release(r: R): Unit

object WorkflowSessionResource:
  // Factory for Process-scoped resources (no release)
  def process[R](acquireFn: RunContext => R): WorkflowSessionResource[R]

  // Factory for Bracket-scoped resources (released at end of scope)
  def bracket[R](acquireFn: RunContext => R, releaseFn: R => Unit): WorkflowSessionResource[R]
```

**Note**: Engine scope (released on shutdown) was deferred to avoid cross-platform issues with synchronized.

### Usage Examples

```scala
// ✅ WORKS: Global resource - no cleanup needed
val result = async[Durable] {
  val db = await(Durable.env[Database])  // From global pool
  db.query("SELECT ...")
}

// ✅ WORKS: withEnv uses WorkflowSessionResource typeclass
given WorkflowSessionResource[Transaction] =
  WorkflowSessionResource.bracket(
    ctx => ctx.appContext.getOrCreate[Database].beginTransaction(),
    tx => tx.rollback()  // cleanup if not committed
  )

val result = async[Durable] {
  await(Durable.withEnv[Transaction] { tx =>
    for {
      _ <- Durable.activitySync { tx.insert(record1) }
      _ <- Durable.activitySync { tx.insert(record2) }
      _ <- Durable.activitySync { tx.commit() }
    } yield ()
  })  // tx.release() called automatically
}

// ✅ WORKS: Or use withResource with explicit acquire/release
val result = async[Durable] {
  await(Durable.withResource(
    acquire = db.beginTransaction(),
    release = tx => tx.rollback()  // cleanup if not committed
  ) { tx =>
    for {
      _ <- Durable.activitySync { tx.insert(record1) }
      _ <- Durable.activitySync { tx.insert(record2) }
      _ <- Durable.activitySync { tx.commit() }
    } yield ()
  })
}

// ✅ WORKS: Manual bracket for custom resources
val result = async[Durable] {
  await(Durable.withResource(
    acquire = openFile("data.csv"),
    release = _.close()
  ) { file =>
    Durable.activitySync { processFile(file) }
  })
}
```

### Interpreter Handling for WithSessionResource ✅ IMPLEMENTED

```scala
// In WorkflowSessionRunner (lines 347-378)
private def handleWithSessionResource[A, R, B](
  wr: Durable.WithSessionResource[R, B],
  ctx: RunContext,
  state: InterpreterState,
  stack: List[StackFrame]
)(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
  val resource = wr.acquire(ctx)
  try
    // Run inner workflow with resource, then release and return result
    stepsUntilSuspend(wr.use(resource), ctx, state, stack).transform { result =>
      wr.release(resource)
      result  // Return the original result after release
    }
  catch
    case e: Throwable =>
      wr.release(resource)
      Future.failed(e)
```

**Important**: Bracket acquire/release are NOT journaled. If workflow suspends inside a bracket:
- On resume: resource is acquired fresh
- The inner workflow continues from cached state
- This is correct because the resource is ephemeral
