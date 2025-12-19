package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Success, Failure}
import java.time.Instant
import cps.*

/**
 * Durable[A] - A Monad describing a durable computation.
 *
 * Type parameters:
 *   A - the result type
 *
 * Each Activity captures a DurableStorage[A, S] instance for caching.
 *
 * This is a data structure describing the computation, not executing it.
 * The runner (interpreter) handles execution, async operations, and storage.
 *
 * Usage with async/await:
 *   import MemoryBackingStore.given
 *   given backing: MemoryBackingStore = MemoryBackingStore()
 *   async[Durable] {
 *     val a = await(activity1)
 *     await(sleep(1.hour))        // suspends here
 *     val b = await(activity2(a))
 *     b
 *   }
 */
enum Durable[A]:
  /** Lift a value */
  case Pure(value: A)

  /** Sequencing */
  case FlatMap[A, B](fa: Durable[A], f: A => Durable[B]) extends Durable[B]

  /** Error */
  case Error(error: Throwable)

  /** Local computation - sync, deterministic, no caching needed. Has access to context. */
  case LocalComputation(compute: RunContext => A)

  /**
   * Activity (outbound operation).
   * Created during monad construction (by preprocessor or user code).
   * Index is assigned at runtime by the interpreter.
   *
   * On interpretation:
   *   - Runner assigns index (runtime counter)
   *   - Checks storage: if cached, returns cached value
   *   - If not cached: executes compute with retry policy, caches result, returns
   *   - Retries on recoverable failures according to retryPolicy
   *
   * Storage and retryPolicy are captured at creation time via given resolution.
   */
  case Activity[A, S <: DurableStorageBackend](
    compute: () => Future[A],
    storage: DurableStorage[A, S],
    retryPolicy: RetryPolicy
  ) extends Durable[A]

  /**
   * Suspend for external input - timer, event, child workflow, or combined.
   * All conditions are represented as EventQuery.Combined.
   * Storage is captured in the Combined for replay.
   */
  case Suspend[A, S <: DurableStorageBackend](
    condition: EventQuery.Combined[A, S]
  ) extends Durable[A]

  /** Try/catch semantics - handles both success and failure of fa */
  case FlatMapTry[A, B](fa: Durable[A], f: Try[A] => Durable[B]) extends Durable[B]

  /**
   * Continue as a new workflow - clears storage and restarts with new args.
   * Used for looping patterns since workflows are immutable.
   *
   * @param metadata New workflow metadata (functionName, argCount, activityIndex=argCount)
   * @param storeArgs Closure to store new args - takes (backend, workflowId, ec)
   * @param workflow Thunk to create the new workflow (lazy to avoid infinite recursion)
   */
  case ContinueAs[A](
    metadata: WorkflowMetadata,
    storeArgs: (DurableStorageBackend, WorkflowId, ExecutionContext) => Future[Unit],
    workflow: () => Durable[A]
  ) extends Durable[A]

  /**
   * Async activity - returns F[T] immediately, runs in parallel with subsequent code.
   * Created during monad construction (by preprocessor or user code).
   * Index is assigned at runtime by the interpreter.
   *
   * On interpretation:
   *   - Runner assigns index (runtime counter)
   *   - Delegates to DurableAsync.wrap which handles:
   *     - Cache check (replay) or execution with retry (fresh run)
   *     - Caching result when F completes
   *   - Returns F[T] immediately (parallel execution preserved)
   *
   * Storage, retryPolicy, and wrapper are captured at creation time via given resolution.
   */
  case AsyncActivity[F[_], T, S <: DurableStorageBackend](
    compute: () => F[T],
    storage: DurableStorage[T, S],
    retryPolicy: RetryPolicy,
    wrapper: DurableAsync[F]
  ) extends Durable[F[T]]

  def map[B](f: A => B): Durable[B] =
    Durable.FlatMap(this, (a: A) => Durable.Pure(f(a)))

  def flatMap[B](f: A => Durable[B]): Durable[B] =
    Durable.FlatMap(this, f)


object Durable:
  /** Lift a pure value */
  def pure[A](a: A): Durable[A] =
    Pure(a)

  /** Lift an error */
  def failed[A](e: Throwable): Durable[A] =
    Error(e)

  /** Local computation - sync, deterministic, no caching */
  def local[A](compute: RunContext => A): Durable[A] =
    LocalComputation(compute)

  /**
   * Create an activity - async operation that will be cached.
   * Index is assigned at runtime by the interpreter.
   * Storage is captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activity[A, S <: DurableStorageBackend](compute: => Future[A], policy: RetryPolicy = RetryPolicy.default)
                 (using storage: DurableStorage[A, S]): Durable[A] =
    Activity(() => compute, storage, policy)

  /**
   * Create an activity from a synchronous computation.
   * Convenience method that wraps the result in Future.successful.
   * Storage is captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activitySync[A, S <: DurableStorageBackend](compute: => A, policy: RetryPolicy = RetryPolicy.default)
                     (using storage: DurableStorage[A, S]): Durable[A] =
    Activity(() => Future.fromTry(scala.util.Try(compute)), storage, policy)

  /**
   * Create an async activity - returns F[T] immediately, runs in parallel.
   * The result T is cached when F completes.
   * Storage and wrapper are captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activityAsync[F[_], T, S <: DurableStorageBackend](compute: => F[T], policy: RetryPolicy = RetryPolicy.default)
                      (using wrapper: DurableAsync[F], storage: DurableStorage[T, S]): Durable[F[T]] =
    AsyncActivity(() => compute, storage, policy, wrapper)

  /** Suspend the workflow with a combined query (condition already contains storage) */
  def suspend[A, S <: DurableStorageBackend](condition: EventQuery.Combined[A, S]): Durable[A] =
    Suspend(condition)

  /** Sleep until a specific instant, returns actual wake time */
  def sleepUntil[S <: DurableStorageBackend](wakeAt: Instant)(using storage: DurableStorage[TimeReached, S]): Durable[Instant] =
    Suspend(EventQuery.Combined[TimeReached, S](
      events = Map.empty,
      timerAt = Some((wakeAt, storage)),
      workflows = Map.empty
    )).map(_.firedAt)

  /** Sleep for a duration, returns actual wake time */
  def sleep[S <: DurableStorageBackend](duration: FiniteDuration)(using storage: DurableStorage[TimeReached, S]): Durable[Instant] =
    sleepUntil(Instant.now().plusMillis(duration.toMillis))

  /** Wait for a broadcast event of type E (requires explicit backend type) */
  def awaitEvent[E, S <: DurableStorageBackend](using eventName: DurableEventName[E], storage: DurableStorage[E, S]): Durable[E] =
    Suspend(EventQuery.Combined[E, S](
      events = Map(eventName.name -> storage),
      timerAt = None,
      workflows = Map.empty
    ))

  /**
   * Continue as a different workflow with the given arguments.
   * Clears activity storage, stores new args, and restarts with the target workflow.
   *
   * This enables workflow transitions - switching from one workflow to another,
   * or continuing as the same workflow with new arguments (for loops).
   *
   * Example:
   * {{{
   * object WorkflowA extends DurableFunction1[Int, String, S] derives DurableFunctionName:
   *   def apply(n: Int)(using S): Durable[String] = async[Durable] {
   *     if n > 10 then
   *       await(WorkflowB.continueAs(s"value-$n"))  // extension syntax
   *     else
   *       "done"
   *   }
   * }}}
   *
   * @param target The target DurableFunction to continue as
   * @param args Tuple of arguments for the target workflow
   * @return Durable[R] - the result type of the target workflow
   */
  def continueAs[Args <: Tuple, R, S <: DurableStorageBackend](
    target: DurableFunction[Args, R, S]
  )(args: Args)(using storageBackend: S): Durable[R] =
    val argsStorage = target.argsStorage
    val argCount = argsStorage.size
    val metadata = WorkflowMetadata(target.functionName.value, argCount, argCount)
    val storeArgsFn = (backend: DurableStorageBackend, wfId: WorkflowId, ec: ExecutionContext) =>
      given ExecutionContext = ec
      argsStorage.storeAll(backend.asInstanceOf[S], wfId, 0, args)
    ContinueAs(metadata, storeArgsFn, () => target.applyTupled(args))

  /**
   * CpsMonadContext for Durable - provides context for async/await.
   * Also provides activity methods for the preprocessor to wrap val definitions.
   */
  class DurableCpsContext extends CpsTryMonadContext[[A] =>> Durable[A]]:
    def monad: CpsTryMonad[[A] =>> Durable[A]] = durableCpsTryMonad

    /**
     * Create an activity from an async computation.
     * Used by preprocessor to wrap val definitions.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activity[A, S <: DurableStorageBackend](compute: => Future[A], policy: RetryPolicy)
                   (using storage: DurableStorage[A, S]): Durable[A] =
      Durable.Activity(() => compute, storage, policy)

    /**
     * Create an activity from a synchronous computation.
     * Wraps the result in Future.successful.
     * Used by preprocessor to wrap val definitions.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activitySync[A, S <: DurableStorageBackend](compute: => A, policy: RetryPolicy)
                       (using storage: DurableStorage[A, S]): Durable[A] =
      Durable.Activity(() => Future.fromTry(scala.util.Try(compute)), storage, policy)

    /**
     * Async variant of activitySync for dotty-cps-async.
     * Called when there's an await inside the activitySync block.
     * The by-name `=> A` becomes `() => Durable[A]` after CPS transformation.
     */
    def activitySync_async[A, S <: DurableStorageBackend](compute: () => Durable[A], policy: RetryPolicy)
                             (using storage: DurableStorage[A, S]): Durable[A] =
      compute()

    /**
     * Async variant of activity for dotty-cps-async.
     * Called when there's an await inside the activity block.
     * The by-name `=> Future[A]` becomes `() => Durable[Future[A]]` after CPS transformation.
     */
    def activity_async[A, S <: DurableStorageBackend](compute: () => Durable[Future[A]], policy: RetryPolicy)
                         (using storage: DurableStorage[A, S]): Durable[A] =
      compute().flatMap(fut => Durable.Activity(() => fut, storage, policy))

    /**
     * Create an async activity from an effect F[T].
     * Used by preprocessor to wrap val definitions of type F[T].
     * Returns F[T] immediately, runs in parallel with subsequent code.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activityAsync[F[_], T, S <: DurableStorageBackend](compute: => F[T], policy: RetryPolicy)
                        (using wrapper: DurableAsync[F], storage: DurableStorage[T, S]): Durable[F[T]] =
      Durable.AsyncActivity(() => compute, storage, policy, wrapper)

  /**
   * CpsTryMonad instance for async/await syntax.
   */
  given durableCpsTryMonad: CpsTryMonad[[A] =>> Durable[A]] with
    type Context = DurableCpsContext

    def pure[A](a: A): Durable[A] = Durable.pure(a)

    def map[A, B](fa: Durable[A])(f: A => B): Durable[B] =
      fa.map(f)

    def flatMap[A, B](fa: Durable[A])(f: A => Durable[B]): Durable[B] =
      fa.flatMap(f)

    def error[A](e: Throwable): Durable[A] =
      Durable.failed(e)

    def flatMapTry[A, B](fa: Durable[A])(f: Try[A] => Durable[B]): Durable[B] =
      FlatMapTry(fa, f)

    def apply[A](op: Context => Durable[A]): Durable[A] =
      op(new DurableCpsContext)

  /**
   * CpsPreprocessor for Durable monad - available via companion object.
   * Automatically wraps val definitions with activity calls for replay-based execution.
   */
  given durablePreprocessor[C <: DurableCpsContext]: CpsPreprocessor[Durable, C] with
    transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
      ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

  /**
   * Formal conversion for any F[_] that can convert to Future.
   * Passes type-checking; actual transform done by preprocessor.
   * Uses inline + compiletime.error for compile-time failure if not transformed.
   *
   * Note: For await(Durable[T]), dotty-cps-async's identityConversion handles it
   * because there's no CpsMonadConversion[Durable, Future].
   */
  given durableFormalConversion[F[_], S <: DurableStorageBackend](using
      backend: S,
      toFuture: CpsMonadConversion[F, Future]
  ): CpsMonadConversion[F, Durable] with
    def apply[T](fa: F[T]): Durable[T] =
      throw RuntimeException(
        "F[_].await should be transformed by DurablePreprocessor. " +
        "This indicates the preprocessor failed to intercept the await call."
      )

