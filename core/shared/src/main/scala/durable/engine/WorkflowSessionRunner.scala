package durable.engine

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration.*
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

import durable.*
import durable.runtime.Scheduler
import com.github.rssh.appcontext.*

/**
 * Stack frame types for the interpreter.
 * Distinguishes normal continuations from try handlers.
 */
private[engine] enum StackFrame:
  /** Normal continuation - only processes successful values */
  case Cont(f: Any => Durable[?])
  /** Try handler - processes both success and failure */
  case TryHandler(f: Try[Any] => Durable[?])

/**
 * Interpreter for Durable Free Monad.
 *
 * Runs a workflow step by step, handling:
 *   - Pure values
 *   - FlatMap sequencing
 *   - Errors
 *   - Local computations (sync, no cache)
 *   - Activity (async, cached with runtime index assignment)
 *   - Suspend (external calls)
 *
 * Activity indices are assigned at runtime as the interpreter
 * encounters Activity nodes. This ensures deterministic
 * replay: same execution path = same indices.
 *
 * Each Activity captures its own DurableStorage, so the runner
 * doesn't need storage - it only needs workflowId and resumeFromIndex.
 */
object WorkflowSessionRunner:

  /**
   * Run a workflow to completion or suspension.
   *
   * @param workflow The Durable workflow to run
   * @param ctx The execution context (workflowId, replay state)
   * @return WorkflowSessionResult - Completed, Suspended, or Failed
   */
  def run[A](
    workflow: Durable[A],
    ctx: RunContext
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    val state = new InterpreterState(ctx.resumeFromIndex, ctx.activityOffset)
    stepsUntilSuspend[A](workflow, ctx, state, Nil)

  /**
   * Mutable interpreter state for tracking activity index at runtime.
   *
   * Activity indices are assigned sequentially starting from activityOffset.
   * This allows the engine to store args at indices 0..argCount-1,
   * and activities start at argCount.
   *
   * For replay:
   * - resumeFromIndex indicates the first index to execute fresh
   * - indices < resumeFromIndex are replayed from cache
   * - indices >= resumeFromIndex are executed fresh
   *
   * @param resumeFromIndex First index to execute fresh (indices below are replayed)
   * @param activityOffset Starting index for activities (to skip over stored args)
   */
  private class InterpreterState(val resumeFromIndex: Int, val activityOffset: Int = 0):
    // Start current index at activityOffset (default 0 for direct runner use)
    private var _currentIndex: Int = activityOffset

    /** Get current index and increment for next activity */
    def nextIndex(): Int =
      val idx = _currentIndex
      _currentIndex += 1
      idx

    /** Current index (for snapshot) */
    def currentIndex: Int = _currentIndex

    /** Are we replaying at this index? */
    def isReplayingAt(index: Int): Boolean =
      index < resumeFromIndex

  /**
   * Execute workflow steps until completion, suspension, or failure.
   *
   * @param current Current Durable node to interpret
   * @param ctx Execution context
   * @param state Interpreter state (tracks activity index)
   * @param stack Continuation stack (StackFrame for FlatMap and FlatMapTry)
   */
  private def stepsUntilSuspend[A](
    current: Durable[?],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    current match
      case Durable.Pure(value) =>
        continueWith(Success(value), ctx, state, stack)

      case Durable.FlatMap(fa, f) =>
        stepsUntilSuspend(fa, ctx, state, StackFrame.Cont(f.asInstanceOf[Any => Durable[?]]) :: stack)

      case Durable.FlatMapTry(fa, f) =>
        stepsUntilSuspend(fa, ctx, state, StackFrame.TryHandler(f.asInstanceOf[Try[Any] => Durable[?]]) :: stack)

      case Durable.Error(error) =>
        continueWith(Failure(error), ctx, state, stack)

      case Durable.LocalComputation(compute) =>
        handleLocalComputation(compute.asInstanceOf[RunContext => Any], ctx, state, stack)

      case activity: Durable.Activity[b, s] =>
        handleActivity[A, b, s](activity, ctx, state, stack)

      case asyncActivity: Durable.AsyncActivity[f, t, s] =>
        handleAsyncActivity[A, f, t, s](asyncActivity, ctx, state, stack)

      case suspend: Durable.Suspend[a, s] =>
        handleSuspend[A, a, s](suspend, ctx, state, stack)

      case continueAs: Durable.ContinueAs[a] =>
        // ContinueAs is a terminal operation - return result for engine to handle
        // Type a = A by GADT refinement, cast is safe
        Future.successful(WorkflowSessionResult.ContinueAs(
          continueAs.metadata,
          continueAs.storeArgs,
          continueAs.workflow
        ).asInstanceOf[WorkflowSessionResult[A]])

      case wr: Durable.WithSessionResource[r, b] =>
        handleWithSessionResource[A, r, b](wr, ctx, state, stack)

  /**
   * Continue with a result (success or failure), applying the next continuation from the stack.
   * Handles error unwinding - failures skip Cont frames until a TryHandler is found.
   */
  private def continueWith[A](
    result: Try[Any],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    (result, stack) match
      // End of stack with success
      case (Success(value), Nil) =>
        Future.successful(WorkflowSessionResult.Completed(ctx.workflowId, value.asInstanceOf[A]))

      // End of stack with failure - workflow fails (wrap in ReplayedException for consistent API)
      case (Failure(error), Nil) =>
        val wrapped = error match
          case re: ReplayedException => re
          case e => ReplayedException(e)
        Future.successful(WorkflowSessionResult.Failed(ctx.workflowId, wrapped))

      // Success through normal continuation
      case (Success(value), StackFrame.Cont(f) :: rest) =>
        try
          val next = f(value)
          stepsUntilSuspend(next, ctx, state, rest)
        catch
          case NonFatal(e) =>
            continueWith(Failure(e), ctx, state, rest)

      // Success through try handler - passes Success(value)
      case (Success(value), StackFrame.TryHandler(f) :: rest) =>
        try
          val next = f(Success(value))
          stepsUntilSuspend(next, ctx, state, rest)
        catch
          case NonFatal(e) =>
            continueWith(Failure(e), ctx, state, rest)

      // Error skips normal continuations (unwinding)
      case (Failure(error), StackFrame.Cont(_) :: rest) =>
        continueWith(Failure(error), ctx, state, rest)

      // Error caught by try handler - passes Failure(error)
      case (Failure(error), StackFrame.TryHandler(f) :: rest) =>
        try
          val next = f(Failure(error))
          stepsUntilSuspend(next, ctx, state, rest)
        catch
          case NonFatal(e) =>
            continueWith(Failure(e), ctx, state, rest)

  /**
   * Handle local computation - just execute, no caching.
   */
  private def handleLocalComputation[A](
    compute: RunContext => Any,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    try
      val result = compute(ctx)
      continueWith(Success(result), ctx, state, stack)
    catch
      case NonFatal(e) =>
        continueWith(Failure(e), ctx, state, stack)

  /**
   * Handle Activity - assign index, check cache, execute if needed with retry, cache result.
   * Uses the DurableStorage and RetryPolicy captured in the Activity node.
   * Type parameter B is the activity's result type, A is the final workflow result type.
   * Type parameter S is the storage backend type.
   *
   * Both successes and failures are cached for deterministic replay.
   * On replay, failures are returned as ReplayedException.
   */
  private def handleActivity[A, B, S <: DurableStorageBackend](
    activity: Durable.Activity[B, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    val compute = activity.compute
    val storage = activity.storage
    val policy = activity.retryPolicy
    val backend = ctx.backend.asInstanceOf[S]
    // Assign index at runtime
    val index = state.nextIndex()

    if state.isReplayingAt(index) then
      // Replaying - retrieve from cache (success or failure)
      storage.retrieveStep(backend, ctx.workflowId, index).flatMap {
        case Some(Right(cached)) =>
          // Cached success
          continueWith(Success(cached), ctx, state, stack)
        case Some(Left(storedFailure)) =>
          // Cached failure - replay as ReplayedException
          continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
        case None =>
          continueWith(Failure(
            RuntimeException(s"Missing cached result for activity at index=$index during replay")
          ), ctx, state, stack)
      }.recoverWith { case e =>
        continueWith(Failure(e), ctx, state, stack)
      }
    else
      // Execute activity with retry logic, cache result (success or failure), continue
      executeWithRetry(compute, policy, ctx, index).transformWith {
        case Success(result) =>
          // Store success - if storage fails, fail workflow (no retry on storage)
          storage.storeStep(backend, ctx.workflowId, index, result).flatMap { _ =>
            continueWith(Success(result), ctx, state, stack)
          }
        case Failure(e) =>
          // Store failure for deterministic replay
          storage.storeStepFailure(backend, ctx.workflowId, index, StoredFailure.fromThrowable(e)).flatMap { _ =>
            continueWith(Failure(e), ctx, state, stack)
          }
      }.recoverWith { case e =>
        // Storage operation itself failed
        continueWith(Failure(e), ctx, state, stack)
      }

  /**
   * Handle Suspend - assign index, check cache for replay, otherwise suspend.
   * Uses storageForCondition to get the right storage based on which condition won.
   * Type parameter S is the storage backend type.
   *
   * On replay (index < resumeFromIndex), the event value should be cached.
   * On fresh run, we suspend and return the snapshot for later resumption.
   */
  private def handleSuspend[A, B, S <: DurableStorageBackend](
    suspend: Durable.Suspend[B, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    val condition = suspend.condition
    val backend = ctx.backend.asInstanceOf[S]
    // Assign index at runtime (like Activity)
    val index = state.nextIndex()

    if state.isReplayingAt(index) then
      // Replaying - look up which condition won, then retrieve value with that storage
      backend.retrieveWinningCondition(ctx.workflowId, index).flatMap {
        case Some(winning) =>
          val storage = condition.storageForCondition(winning)
            .getOrElse(throw RuntimeException(s"No storage for winning condition $winning"))
          storage.retrieveStep(backend, ctx.workflowId, index).flatMap {
            case Some(Right(cached)) =>
              // Cached event value - continue with it
              continueWith(Success(cached), ctx, state, stack)
            case Some(Left(storedFailure)) =>
              // Cached failure - replay as ReplayedException
              continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
            case None =>
              continueWith(Failure(
                RuntimeException(s"Missing cached event value for suspend at index=$index during replay")
              ), ctx, state, stack)
          }
        case None =>
          continueWith(Failure(
            RuntimeException(s"Missing winning condition for suspend at index=$index during replay")
          ), ctx, state, stack)
      }.recoverWith { case e =>
        continueWith(Failure(e), ctx, state, stack)
      }
    else
      // Fresh run - suspend and return snapshot
      // The event value will be stored externally when the event occurs
      Future.successful(WorkflowSessionResult.Suspended(
        DurableSnapshot(ctx.workflowId, index),
        condition
      ))

  /**
   * Handle AsyncActivity - assign index, delegate to DurableAsync.
   * Returns F[T] immediately (parallel execution preserved).
   * Type parameter F is the async effect type (e.g., Future).
   * Type parameter T is the result type that gets cached.
   * Type parameter S is the storage backend type.
   */
  private def handleAsyncActivity[A, F[_], T, S <: DurableStorageBackend](
    asyncActivity: Durable.AsyncActivity[F, T, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    val index = state.nextIndex()
    val backend = ctx.backend.asInstanceOf[S]
    given DurableStorage[T, S] = asyncActivity.storage
    given S = backend

    // Wrapper handles all logic: cache check, execution with retry, caching on completion
    val resultF: F[T] = asyncActivity.wrapper.wrap(
      asyncActivity.compute,
      index,
      ctx.workflowId,
      state.isReplayingAt(index),
      asyncActivity.retryPolicy,
      ctx.config.scheduler,
      ctx.config.retryLogger
    )

    // Continue IMMEDIATELY with F[T] (wrapper returns immediately)
    continueWith(Success(resultF), ctx, state, stack)

  /**
   * Handle WithSessionResource - acquire resource, run inner workflow, release.
   *
   * Resource acquisition is NOT journaled because:
   *   - Resources are ephemeral (connections, clients) and cannot be serialized
   *   - On resume, resources must be acquired fresh
   *   - Only the results of operations USING resources (activities) are cached
   *
   * The bracket pattern ensures release is called on success, failure, or suspension.
   */
  private def handleWithSessionResource[A, R, B](
    wr: Durable.WithSessionResource[R, B],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  )(using ec: ExecutionContext): Future[WorkflowSessionResult[A]] =
    // No index increment - resource acquisition is NOT journaled
    try
      val resource = wr.acquire(ctx)
      // Run inner workflow with resource
      stepsUntilSuspend(wr.use(resource), ctx, state, stack).transform { result =>
        // Release resource after workflow completes (success or failure)
        try
          wr.release(resource)
        catch
          case NonFatal(_) => () // Ignore release errors, preserve original result
        result
      }
    catch
      case NonFatal(e) =>
        // Acquire failed - no resource to release, propagate error
        continueWith(Failure(e), ctx, state, stack)

  /**
   * Execute activity computation with retry logic.
   *
   * @param compute The activity computation
   * @param policy Retry policy to apply
   * @param ctx Run context (for logging)
   * @param activityIndex Activity index (for logging)
   * @return Future containing the computation result
   */
  private def executeWithRetry[B](
    compute: () => Future[B],
    policy: RetryPolicy,
    ctx: RunContext,
    activityIndex: Int
  )(using ec: ExecutionContext): Future[B] =

    def attempt(attemptNum: Int, lastError: Option[Throwable]): Future[B] =
      if attemptNum > policy.maxAttempts then
        // Exhausted all retries
        Future.failed(lastError.getOrElse(
          RuntimeException("Max retries exceeded with no error recorded")
        ))
      else
        compute().recoverWith { case e: Throwable =>
          val isRecoverable = policy.isRecoverable(e)
          val hasMoreAttempts = attemptNum < policy.maxAttempts
          val willRetry = isRecoverable && hasMoreAttempts

          // Calculate delay for next attempt (if any)
          val nextDelayMs = if willRetry then
            val baseDelay = policy.delayForAttempt(attemptNum)
            val delayWithJitter = policy.applyJitter(baseDelay)
            Some(delayWithJitter.toMillis)
          else
            None

          // Log retry event
          val event = RetryEvent(
            workflowId = ctx.workflowId,
            activityIndex = activityIndex,
            attempt = attemptNum,
            maxAttempts = policy.maxAttempts,
            error = e,
            nextDelayMs = nextDelayMs,
            willRetry = willRetry
          )
          ctx.config.retryLogger(event)

          if willRetry then
            // Schedule retry after delay
            val delayMs = nextDelayMs.getOrElse(0L)
            scheduleRetry(delayMs.millis, ctx.config.scheduler) {
              attempt(attemptNum + 1, Some(e))
            }
          else
            // Not recoverable or exhausted retries
            Future.failed(e)
        }

    attempt(1, None)

  /**
   * Schedule a delayed retry using the configured scheduler.
   */
  private def scheduleRetry[B](
    delay: FiniteDuration,
    scheduler: Scheduler
  )(f: => Future[B])(using ec: ExecutionContext): Future[B] =
    if delay.toMillis <= 0 then
      f
    else
      scheduler.schedule(delay)(f)

  /**
   * Execution context for the workflow runner.
   *
   * @param workflowId Unique identifier for this workflow instance
   * @param backend Storage backend instance (passed to storage typeclass methods)
   * @param appContextCache Application context cache for environment resources (fresh on each start/resume)
   * @param configSource Source for external configuration (database URLs, API keys, etc.)
   * @param resumeFromIndex Activity index to resume from (0 = fresh start, indices < this are replayed)
   * @param activityOffset Starting index for activity assignment (to skip stored args when run via engine)
   * @param config Runner configuration (logging, scheduling)
   */
  case class RunContext(
    workflowId: WorkflowId,
    backend: DurableStorageBackend,
    appContextCache: AppContext.Cache,
    configSource: ConfigSource,
    resumeFromIndex: Int,
    activityOffset: Int = 0,
    config: RunConfig = RunConfig.default
  )

  object RunContext:
    /** Create a fresh context for new workflow execution (uses empty ConfigSource - for testing) */
    def fresh(workflowId: WorkflowId)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, ConfigSource.empty, resumeFromIndex = 0)

    /** Create a fresh context with custom run configuration and config source */
    def fresh(workflowId: WorkflowId, config: RunConfig, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, configSource, resumeFromIndex = 0, config = config)

    /** Create a fresh context with full custom configuration */
    def fresh(workflowId: WorkflowId, config: RunConfig, appContextCache: AppContext.Cache, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, appContextCache, configSource, resumeFromIndex = 0, config = config)

    /** Create a context for resuming from a specific index (uses empty ConfigSource - for testing) */
    def resume(workflowId: WorkflowId, resumeFromIndex: Int)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, ConfigSource.empty, resumeFromIndex)

    /** Create a context for resuming with custom run configuration and config source */
    def resume(workflowId: WorkflowId, resumeFromIndex: Int, config: RunConfig, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, configSource, resumeFromIndex, config = config)

    /** Create a context for resuming with full custom configuration */
    def resume(workflowId: WorkflowId, resumeFromIndex: Int, config: RunConfig, appContextCache: AppContext.Cache, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, appContextCache, configSource, resumeFromIndex, config = config)

    /** Create a context for resuming from snapshot (uses empty ConfigSource - for testing) */
    def fromSnapshot(snapshot: DurableSnapshot)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, AppContext.newCache, ConfigSource.empty, snapshot.activityIndex)

    /** Create a context for resuming from snapshot with custom run configuration and config source */
    def fromSnapshot(snapshot: DurableSnapshot, config: RunConfig, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, AppContext.newCache, configSource, snapshot.activityIndex, config = config)

    /** Create a context for resuming from snapshot with full custom configuration */
    def fromSnapshot(snapshot: DurableSnapshot, config: RunConfig, appContextCache: AppContext.Cache, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, appContextCache, configSource, snapshot.activityIndex, config = config)

  /**
   * Configuration for workflow runner.
   *
   * @param retryLogger Logger callback for retry events
   * @param scheduler Scheduler for retry delays
   */
  case class RunConfig(
    retryLogger: RetryLogger = RetryLogger.noop,
    scheduler: Scheduler = Scheduler.default
  )

  object RunConfig:
    /** Default configuration */
    val default: RunConfig = RunConfig()

  /**
   * Snapshot of workflow state for suspension and resumption.
   *
   * @param workflowId Unique identifier for the workflow instance
   * @param activityIndex The activity index to resume from
   */
  case class DurableSnapshot(
    workflowId: WorkflowId,
    activityIndex: Int
  )
