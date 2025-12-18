# Async Activities

Sync activities are created when we allow on top-level only activities which results can be put in database
(i.e. `T`, for which we have given `DurableStorage[T,S]`).

## Problem

Currently, if you write:
```scala
val r: Future[T] = someAsyncCall()
```

The preprocessor transforms to `val r = await(ctx.activitySync { someAsyncCall() })`, which tries to find `DurableStorage[Future[T], S]` for caching - this fails at compile time since no such instance exists.

## Desired Semantics

```scala
async[Durable] {
  val r: Future[T] = fetchData(url)  // returns immediately, Future starts running
  val x = compute()                   // runs in PARALLEL with r
  val result = await(r) + x           // waits for r HERE, not at assignment
  result
}
```

Key properties:
1. Return the Future **immediately** (don't block at assignment)
2. Run in **parallel** with subsequent code
3. Cache the resolved result `T` when the Future completes

## Solution: DurableAsync[F[_]]

Abstract the wrapping logic to any monad via typeclass:

```scala
trait DurableAsync[F[_]]:
  /**
   * Wrap an async operation for durable execution.
   *
   * Implementation is responsible for:
   * - Checking storage for cached value (return F with cached value if found)
   * - Executing op() if not cached, with retry logic
   * - Caching the result when F completes
   * - Returning F[T] immediately (don't block)
   * - Failing workflow if storage operation fails
   */
  def wrap[T, S <: DurableStorageBackend](
    op: () => F[T],
    index: Int,
    workflowId: WorkflowId,
    isReplaying: Boolean,
    policy: RetryPolicy,
    scheduler: Scheduler,
    retryLogger: RetryLogger
  )(using storage: DurableStorage[T, S], backend: S): F[T]
```

### Future Instance Logic

**On Replay (`isReplaying = true`):**
1. Retrieve cached value from storage (if storage fails, workflow fails)
2. If found success: return `Future.successful(cachedValue)`
3. If found failure: return `Future.failed(ReplayedException(...))`
4. If not found: restart the activity (execute `op()` as fresh run - see below)

**On Fresh Run (`isReplaying = false`):**
1. Execute `op()` with retry logic:
   - On recoverable failure: retry according to `RetryPolicy` (maxAttempts, backoff, jitter)
   - On non-recoverable failure or max attempts exceeded: proceed to step 2 with failure
2. Chain storage operation:
   - On computation success: store result, then return value
   - On computation failure: store failure, then propagate original error
3. If storage operation fails: fail the workflow
4. Return the chained Future (completes after both computation AND storage)

Note: Retry logic follows the same pattern as sync activities - exponential backoff with jitter, configurable via `RetryPolicy`. Requires `Scheduler` for scheduling delayed retries.

## Durable Monad Integration

### New Case: AsyncActivity

```scala
case AsyncActivity[F[_], T, S <: DurableStorageBackend](
  compute: () => F[T],
  storage: DurableStorage[T, S],
  retryPolicy: RetryPolicy,
  wrapper: DurableAsyncWrapper[F]
) extends Durable[F[T]]
```

### API

```scala
def activityAsync[F[_], T, S <: DurableStorageBackend](
  compute: => F[T],
  policy: RetryPolicy = RetryPolicy.default
)(using
  wrapper: DurableAsync[F],
  storage: DurableStorage[T, S]
): Durable[F[T]] =
  AsyncActivity(() => compute, storage, policy, wrapper)
```

## Preprocessor Transformation

The CpsPreprocessor detects when `val x: F[T] = expr` where `DurableAsync[F]` exists in scope:

1. Check if `exprType` is `AppliedType(fType, List(tType))`
2. Search for `DurableAsync[F]` in scope via `Implicits.search`
3. If found: use `activityAsync`
4. If not found: use existing `activitySync` path

Transforms:
- `val x: Future[T] = expr` → `val x: Future[T] = ctx.activityAsync { expr }`
- `val x: T = expr` → `val x: T = await(ctx.activitySync { expr })` (unchanged)

## Behavior

### Fresh Run
- `val r: Future[Int] = asyncOp()` → starts Future, continues immediately
- `val x = syncOp()` → executes while Future is running (parallel!)
- `await(r)` → waits for Future AND caching to complete
- If storage fails: workflow fails

### Replay
- `val r: Future[Int] = asyncOp()` → returns completed Future with cached value
- `val x = syncOp()` → executes (from cache)
- `await(r)` → completes immediately with cached value

## Extensibility

Other effect types can implement `DurableAsync`:

```scala
// Example: cats-effect IO
given ioWrapper: DurableAsync[IO] with
  def wrap[T, S <: DurableStorageBackend](...): IO[T] =
    // IO-specific implementation
```
