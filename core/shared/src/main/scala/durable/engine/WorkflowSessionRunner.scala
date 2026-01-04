package durable.engine

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

import cps.*
import cps.monads.CpsIdentity
import durable.*
import durable.runtime.{Scheduler, EffectTag}
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
  /** Cached continuation - caches f(a) result at given index */
  case ContCached[S <: DurableStorageBackend](
    f: Any => Durable[?],
    index: Int,
    storage: DurableStorage[?, S]
  )
  /** Cache result frame - caches the value when continuation completes */
  case CacheResult[S <: DurableStorageBackend](
    index: Int,
    storage: DurableStorage[?, S]
  )

/**
 * Signal that the runner needs to switch to a "bigger" effect.
 *
 * This happens when:
 * - Current runner (e.g., Future) encounters Activity[IO]
 * - Future can't convert IO → Future
 * - But IO CAN convert Future → IO (IO is "bigger")
 * - Engine should switch to IO runner and resume from saved state
 *
 * @param activityTag The effect tag of the activity that triggered the switch
 * @param state Saved interpreter state for resumption
 */
case class NeedsBiggerRunner(
  activityTag: EffectTag[?],
  state: RunnerState
)

/**
 * Saved interpreter state for runner switching.
 *
 * When a runner can't handle an activity but a "bigger" runner can,
 * it saves its state so the bigger runner can resume from the exact
 * point where switching was needed.
 *
 * @param currentNode The Durable node being interpreted when switch was triggered
 * @param stack The continuation stack (FlatMap continuations, TryHandlers, etc.)
 * @param activityIndex Current activity index (for deterministic replay)
 * @param ctx The run context
 */
case class RunnerState(
  currentNode: Durable[?],
  stack: List[StackFrame],
  activityIndex: Int,
  ctx: WorkflowSessionRunner.RunContext
)

/**
 * Generic interpreter for Durable workflows.
 *
 * Parameterized over effect type G[_] with CpsAsyncMonad instance.
 * Uses monad operations for sequencing and adoptCallbackStyle for lifting Futures.
 *
 * Returns G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]:
 * - Right(result): Workflow completed/suspended/failed
 * - Left(needsBigger): Need to switch to a bigger runner
 *
 * Effect hierarchy: IO > Future > CpsIdentity
 *
 * @tparam G The target effect type
 * @param targetTag Effect tag for G (determines what activities can be handled)
 * @param monad CpsAsyncMonad instance for G
 */
class WorkflowSessionRunner[G[_]](
  val targetTag: EffectTag[G]
)(using val monad: CpsAsyncMonad[G]):
  import WorkflowSessionRunner.*

  /**
   * Lift a Future[A] into G[A].
   * Uses monad.adoptCallbackStyle to convert callback-based Future to G.
   */
  def liftFuture[A](fa: Future[A]): G[A] =
    monad.adoptCallbackStyle[A](callback => fa.onComplete(callback)(ExecutionContext.parasitic))

  /**
   * Run a workflow to completion or suspension.
   *
   * @param workflow The Durable workflow to run
   * @param ctx The execution context (workflowId, replay state)
   * @return G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]
   */
  def run[A](
    workflow: Durable[A],
    ctx: RunContext
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val state = new InterpreterState(ctx.resumeFromIndex, ctx.activityOffset)
    interpret[A](workflow, ctx, state, Nil)

  /**
   * Resume interpretation from a saved state (after runner switch).
   *
   * @param savedState Saved state from the previous runner
   * @return G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]
   */
  def resumeFrom[A](
    savedState: RunnerState
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val state = new InterpreterState(savedState.ctx.resumeFromIndex, savedState.ctx.activityOffset)
    state.setIndex(savedState.activityIndex)
    interpret[A](savedState.currentNode.asInstanceOf[Durable[A]], savedState.ctx, state, savedState.stack)

  /**
   * Execute workflow steps until completion, suspension, or failure.
   */
  private def interpret[A](
    current: Durable[?],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    current match
      case Durable.Pure(value) =>
        continueWith(Success(value), ctx, state, stack)

      case Durable.FlatMap(fa, f) =>
        interpret(fa, ctx, state, StackFrame.Cont(f.asInstanceOf[Any => Durable[?]]) :: stack)

      case Durable.FlatMapTry(fa, f) =>
        interpret(fa, ctx, state, StackFrame.TryHandler(f.asInstanceOf[Try[Any] => Durable[?]]) :: stack)

      case fmc: Durable.FlatMapCached[a, b, s] =>
        handleFlatMapCached[A, a, b, s](fmc, ctx, state, stack)

      case Durable.Error(error) =>
        continueWith(Failure(error), ctx, state, stack)

      case localComp: Durable.LocalComputation[a, s] =>
        localComp.storage match
          case Some(storage) =>
            handleCachedLocalComputation[A, a, s](localComp.compute, storage, localComp.sourcePos, ctx, state, stack)
          case None =>
            handleLocalComputation(localComp.compute.asInstanceOf[RunContext => Any], ctx, state, stack)

      case activity: Durable.Activity[f, b, s] =>
        handleActivity[A, f, b, s](activity, ctx, state, stack)

      case asyncActivity: Durable.AsyncActivity[f, t, s] =>
        handleAsyncActivity[A, f, t, s](asyncActivity, ctx, state, stack)

      case localAsync: Durable.LocalAsync[f, t] =>
        handleLocalAsync[A, f, t](localAsync, ctx, state, stack)

      case suspend: Durable.Suspend[a, s] =>
        handleSuspend[A, a, s](suspend, ctx, state, stack)

      case continueAs: Durable.ContinueAs[a] =>
        monad.pure(Right(WorkflowSessionResult.ContinueAs(
          continueAs.metadata,
          continueAs.storeArgs,
          continueAs.workflow
        ).asInstanceOf[WorkflowSessionResult[A]]))

      case wr: Durable.WithSessionResource[r, b] =>
        handleWithSessionResource[A, r, b](wr, ctx, state, stack)

  /**
   * Continue with a result (success or failure), applying the next continuation from the stack.
   */
  private def continueWith[A](
    result: Try[Any],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    (result, stack) match
      case (Success(value), Nil) =>
        monad.pure(Right(WorkflowSessionResult.Completed(ctx.workflowId, value.asInstanceOf[A])))

      case (Failure(error), Nil) =>
        val wrapped = error match
          case re: ReplayedException => re
          case e => ReplayedException(e)
        monad.pure(Right(WorkflowSessionResult.Failed(ctx.workflowId, wrapped)))

      case (Success(value), StackFrame.Cont(f) :: rest) =>
        monad.flatMapTry(monad.tryPure(f(value))) {
          case Success(next) => interpret(next, ctx, state, rest)
          case Failure(e) => continueWith(Failure(e), ctx, state, rest)
        }

      case (Success(value), StackFrame.TryHandler(f) :: rest) =>
        monad.flatMapTry(monad.tryPure(f(Success(value)))) {
          case Success(next) => interpret(next, ctx, state, rest)
          case Failure(e) => continueWith(Failure(e), ctx, state, rest)
        }

      case (Failure(error), StackFrame.Cont(_) :: rest) =>
        continueWith(Failure(error), ctx, state, rest)

      case (Failure(error), StackFrame.TryHandler(f) :: rest) =>
        monad.flatMapTry(monad.tryPure(f(Failure(error)))) {
          case Success(next) => interpret(next, ctx, state, rest)
          case Failure(e) => continueWith(Failure(e), ctx, state, rest)
        }

      case (Success(value), StackFrame.ContCached(f, index, storage) :: rest) =>
        monad.flatMapTry(monad.tryPure(f(value))) {
          case Success(next) =>
            interpret(next, ctx, state, StackFrame.CacheResult(index, storage) :: rest)
          case Failure(e) =>
            continueWith(Failure(e), ctx, state, rest)
        }

      case (Failure(error), StackFrame.ContCached(_, index, storage) :: rest) =>
        val storeFuture = storage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
          .storeStepFailure(ctx.backend, ctx.workflowId, index, StoredFailure.fromThrowable(error))
        monad.flatMap(liftFuture(storeFuture)) { _ =>
          continueWith(Failure(error), ctx, state, rest)
        }

      case (Success(value), StackFrame.CacheResult(index, storage) :: rest) =>
        val storeFuture = storage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
          .storeStep(ctx.backend, ctx.workflowId, index, value)
        monad.flatMap(liftFuture(storeFuture)) { _ =>
          continueWith(Success(value), ctx, state, rest)
        }

      case (Failure(error), StackFrame.CacheResult(index, storage) :: rest) =>
        val storeFuture = storage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
          .storeStepFailure(ctx.backend, ctx.workflowId, index, StoredFailure.fromThrowable(error))
        monad.flatMap(liftFuture(storeFuture)) { _ =>
          continueWith(Failure(error), ctx, state, rest)
        }

  /**
   * Handle local computation - just execute, no caching.
   */
  private def handleLocalComputation[A](
    compute: RunContext => Any,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    monad.flatMapTry(monad.tryPure(compute(ctx))) {
      case Success(result) => continueWith(Success(result), ctx, state, stack)
      case Failure(e) => continueWith(Failure(e), ctx, state, stack)
    }

  /**
   * Handle cached local computation - assign index, check cache, execute and cache if needed.
   */
  private def handleCachedLocalComputation[A, B, S <: DurableStorageBackend](
    compute: RunContext => B,
    storage: DurableStorage[B, S],
    sourcePos: durable.runtime.SourcePos,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val backend = ctx.backend.asInstanceOf[S]
    val index = state.nextIndex()

    if ctx.config.traceEnabled then
      val mode = if state.isReplayingAt(index) then "REPLAY" else "EXECUTE"
      println(s"[TRACE] LocalComputation #$index at $sourcePos: $mode")

    if state.isReplayingAt(index) then
      monad.flatMap(liftFuture(storage.retrieveStep(backend, ctx.workflowId, index))) {
        case Some(Right(cached)) =>
          continueWith(Success(cached), ctx, state, stack)
        case Some(Left(storedFailure)) =>
          continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
        case None =>
          continueWith(Failure(RuntimeException(
            s"Missing cached result for local computation at index=$index during replay"
          )), ctx, state, stack)
      }
    else
      monad.flatMapTry(monad.tryPure(compute(ctx))) {
        case Success(result) =>
          monad.flatMap(liftFuture(storage.storeStep(backend, ctx.workflowId, index, result))) { _ =>
            continueWith(Success(result), ctx, state, stack)
          }
        case Failure(e) =>
          monad.flatMap(liftFuture(storage.storeStepFailure(backend, ctx.workflowId, index, StoredFailure.fromThrowable(e)))) { _ =>
            continueWith(Failure(e), ctx, state, stack)
          }
      }

  /**
   * Handle FlatMapCached - assign index, check cache, skip inner computation if cached.
   */
  private def handleFlatMapCached[A, X, B, S <: DurableStorageBackend](
    fmc: Durable.FlatMapCached[X, B, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val storage = fmc.storage
    val backend = ctx.backend.asInstanceOf[S]
    val index = state.nextIndex()

    if ctx.config.traceEnabled then
      val mode = if state.isReplayingAt(index) then "REPLAY" else "EXECUTE"
      println(s"[TRACE] FlatMapCached #$index at ${fmc.sourcePos}: $mode")

    if state.isReplayingAt(index) then
      monad.flatMap(liftFuture(storage.retrieveStep(backend, ctx.workflowId, index))) {
        case Some(Right(cached)) =>
          continueWith(Success(cached), ctx, state, stack)
        case Some(Left(storedFailure)) =>
          continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
        case None =>
          continueWith(Failure(RuntimeException(
            s"Missing cached result for FlatMapCached at index=$index during replay"
          )), ctx, state, stack)
      }
    else
      interpret(fmc.fa, ctx, state,
        StackFrame.ContCached(fmc.f.asInstanceOf[Any => Durable[?]], index, storage) :: stack)

  /**
   * Handle Activity - the key method for effect handling.
   *
   * If targetTag can accept the activity's effect → convert and execute.
   * If not, but activity's effect can accept targetTag → return NeedsBiggerRunner.
   * Otherwise → error.
   */
  private def handleActivity[A, F[_], B, S <: DurableStorageBackend](
    activity: Durable.Activity[F, B, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val tag = activity.tag

    targetTag.conversionFrom[F](tag) match
      case Some(conversion) =>
        // We can handle it - proceed with execution
        val index = state.nextIndex()

        if ctx.config.traceEnabled then
          val mode = if state.isReplayingAt(index) then "REPLAY" else "EXECUTE"
          println(s"[TRACE] Activity #$index at ${activity.sourcePos}: $mode (effect: ${tag.getClass.getSimpleName})")

        if state.isReplayingAt(index) then
          handleActivityReplay(activity.storage, index, ctx, state, stack)
        else
          handleActivityExecution(activity.compute, conversion, activity.storage, activity.retryPolicy,
            index, ctx, state, stack)

      case None =>
        // Can't handle - check if activity's effect can accept our target (i.e., it's "bigger")
        if tag.canAcceptErased(targetTag) then
          // Activity's effect is bigger - need to switch runners
          val savedState = RunnerState(
            currentNode = activity,
            stack = stack,
            activityIndex = state.currentIndex,
            ctx = ctx
          )
          monad.pure(Left(NeedsBiggerRunner(tag, savedState)))
        else
          // Incompatible effects
          monad.pure(Right(WorkflowSessionResult.Failed(
            ctx.workflowId,
            ReplayedException(new RuntimeException(
              s"Cannot handle effect ${tag} - not compatible with ${targetTag} and not a bigger effect"
            ))
          )))

  private def handleActivityReplay[A, B, S <: DurableStorageBackend](
    storage: DurableStorage[B, S],
    index: Int,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val backend = ctx.backend.asInstanceOf[S]
    monad.flatMap(liftFuture(storage.retrieveStep(backend, ctx.workflowId, index))) {
      case Some(Right(cached)) =>
        continueWith(Success(cached), ctx, state, stack)
      case Some(Left(storedFailure)) =>
        continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
      case None =>
        continueWith(Failure(RuntimeException(
          s"Missing cached result for activity at index=$index during replay"
        )), ctx, state, stack)
    }

  private def handleActivityExecution[A, F[_], B, S <: DurableStorageBackend](
    compute: () => F[B],
    conversion: CpsMonadConversion[F, G],
    storage: DurableStorage[B, S],
    policy: RetryPolicy,
    index: Int,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val backend = ctx.backend.asInstanceOf[S]

    // Thunk that creates G[B] fresh each attempt - needed for retry to re-execute
    val computeG: () => G[B] = () => monad.flatWrap(conversion(compute()))

    // Execute with retry logic
    monad.flatMapTry(executeWithRetry(computeG, policy, ctx, index)) {
      case Success(result) =>
        monad.flatMap(liftFuture(storage.storeStep(backend, ctx.workflowId, index, result))) { _ =>
          continueWith(Success(result), ctx, state, stack)
        }
      case Failure(e) =>
        monad.flatMap(liftFuture(storage.storeStepFailure(backend, ctx.workflowId, index, StoredFailure.fromThrowable(e)))) { _ =>
          continueWith(Failure(e), ctx, state, stack)
        }
    }

  private def executeWithRetry[B](
    computeG: () => G[B],
    policy: RetryPolicy,
    ctx: RunContext,
    activityIndex: Int
  ): G[B] =
    def attempt(attemptNum: Int, lastError: Option[Throwable]): G[B] =
      if attemptNum > policy.maxAttempts then
        monad.error(lastError.getOrElse(
          RuntimeException("Max retries exceeded with no error recorded")
        ))
      else
        monad.flatMapTry(computeG()) {
          case Success(value) =>
            monad.pure(value)
          case Failure(e) =>
            val isRecoverable = policy.isRecoverable(e)
            val hasMoreAttempts = attemptNum < policy.maxAttempts
            val willRetry = isRecoverable && hasMoreAttempts

            val nextDelayMs = if willRetry then
              val baseDelay = policy.delayForAttempt(attemptNum)
              val delayWithJitter = policy.applyJitter(baseDelay)
              Some(delayWithJitter.toMillis)
            else
              None

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
              val delayMs = nextDelayMs.getOrElse(0L)
              val retryFuture = ctx.config.scheduler.schedule(delayMs.millis)(
                Future.successful(())
              )(using ExecutionContext.parasitic)
              monad.flatMap(liftFuture(retryFuture)) { _ =>
                attempt(attemptNum + 1, Some(e))
              }
            else
              monad.error(e)
        }

    attempt(1, None)

  /**
   * Handle Suspend - assign index, check cache for replay, otherwise suspend.
   */
  private def handleSuspend[A, B, S <: DurableStorageBackend](
    suspend: Durable.Suspend[B, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val condition = suspend.condition
    val backend = ctx.backend.asInstanceOf[S]
    val index = state.nextIndex()

    if state.isReplayingAt(index) then
      monad.flatMap(liftFuture(backend.retrieveWinningCondition(ctx.workflowId, index))) {
        case Some(winning) =>
          val storage = condition.storageForCondition(winning)
            .getOrElse(throw RuntimeException(s"No storage for winning condition $winning"))
          monad.flatMap(liftFuture(storage.retrieveStep(backend, ctx.workflowId, index))) {
            case Some(Right(cached)) =>
              continueWith(Success(cached), ctx, state, stack)
            case Some(Left(storedFailure)) =>
              continueWith(Failure(ReplayedException(storedFailure)), ctx, state, stack)
            case None =>
              continueWith(Failure(RuntimeException(
                s"Missing cached event value for suspend at index=$index during replay"
              )), ctx, state, stack)
          }
        case None =>
          continueWith(Failure(RuntimeException(
            s"Missing winning condition for suspend at index=$index during replay"
          )), ctx, state, stack)
      }
    else
      monad.pure(Right(WorkflowSessionResult.Suspended(
        DurableSnapshot(ctx.workflowId, index),
        condition
      )))

  /**
   * Handle AsyncActivity - assign index, delegate to DurableAsync.
   */
  private def handleAsyncActivity[A, F[_], T, S <: DurableStorageBackend](
    asyncActivity: Durable.AsyncActivity[F, T, S],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val index = state.nextIndex()
    val backend = ctx.backend.asInstanceOf[S]
    given DurableStorage[T, S] = asyncActivity.storage
    given S = backend

    val resultF: F[T] = asyncActivity.wrapper.wrapCached(
      asyncActivity.compute,
      index,
      ctx.workflowId,
      state.isReplayingAt(index),
      asyncActivity.retryPolicy,
      ctx.config.scheduler,
      ctx.config.retryLogger
    )

    continueWith(Success(resultF), ctx, state, stack)

  /**
   * Handle LocalAsync - ephemeral async activity, NOT cached.
   */
  private def handleLocalAsync[A, F[_], T](
    localAsync: Durable.LocalAsync[F, T],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    val resultF: F[T] = localAsync.wrapper.wrapEphemeral(localAsync.compute, ctx)
    continueWith(Success(resultF), ctx, state, stack)

  /**
   * Handle WithSessionResource - acquire resource, run inner workflow, release.
   */
  private def handleWithSessionResource[A, R, B](
    wr: Durable.WithSessionResource[R, B],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[StackFrame]
  ): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]] =
    monad.flatMapTry(monad.tryPure(wr.acquire(ctx))) {
      case Success(resource) =>
        monad.withAsyncAction(interpret(wr.use(resource), ctx, state, stack)) {
          monad.tryPure(wr.release(resource))
        }
      case Failure(e) =>
        continueWith(Failure(e), ctx, state, stack)
    }


object WorkflowSessionRunner:

  /**
   * Mutable interpreter state for tracking activity index at runtime.
   */
  private[engine] class InterpreterState(val resumeFromIndex: Int, val activityOffset: Int = 0):
    private var _currentIndex: Int = activityOffset

    def nextIndex(): Int =
      val idx = _currentIndex
      _currentIndex += 1
      idx

    def currentIndex: Int = _currentIndex

    def setIndex(idx: Int): Unit =
      _currentIndex = idx

    def isReplayingAt(index: Int): Boolean =
      index < resumeFromIndex

  /**
   * Execution context for the workflow runner.
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
    def fresh(workflowId: WorkflowId)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, ConfigSource.empty, resumeFromIndex = 0)

    def fresh(workflowId: WorkflowId, config: RunConfig, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, AppContext.newCache, configSource, resumeFromIndex = 0, config = config)

    def fresh(workflowId: WorkflowId, config: RunConfig, appContextCache: AppContext.Cache, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, appContextCache, configSource, resumeFromIndex = 0, config = config)

    def resume(
      workflowId: WorkflowId,
      resumeFromIndex: Int,
      activityOffset: Int,
      config: RunConfig = RunConfig.default,
      configSource: ConfigSource = ConfigSource.empty,
      appContextCache: AppContext.Cache = AppContext.newCache
    )(using backend: DurableStorageBackend): RunContext =
      RunContext(workflowId, backend, appContextCache, configSource, resumeFromIndex, activityOffset, config)

    def fromSnapshot(snapshot: DurableSnapshot)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, AppContext.newCache, ConfigSource.empty, snapshot.activityIndex)

    def fromSnapshot(snapshot: DurableSnapshot, config: RunConfig, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, AppContext.newCache, configSource, snapshot.activityIndex, config = config)

    def fromSnapshot(snapshot: DurableSnapshot, config: RunConfig, appContextCache: AppContext.Cache, configSource: ConfigSource)(using backend: DurableStorageBackend): RunContext =
      RunContext(snapshot.workflowId, backend, appContextCache, configSource, snapshot.activityIndex, config = config)

  case class RunConfig(
    retryLogger: RetryLogger = RetryLogger.noop,
    scheduler: Scheduler = Scheduler.default,
    traceEnabled: Boolean = false
  )

  object RunConfig:
    val default: RunConfig = RunConfig()

  case class DurableSnapshot(
    workflowId: WorkflowId,
    activityIndex: Int
  )

  /**
   * Create a Future-based runner (default).
   */
  def forFuture(using ec: ExecutionContext): WorkflowSessionRunner[Future] =
    import cps.monads.{FutureAsyncMonad, given}
    new WorkflowSessionRunner[Future](EffectTag.futureTag)
