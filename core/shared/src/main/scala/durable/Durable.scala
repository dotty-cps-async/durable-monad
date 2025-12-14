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
 *   given backing: MemoryBackingStore = MemoryBackingStore()
 *   given DurableStorageBackend = backing
 *   given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
   * Suspend for external input - timer, event, child workflow, etc.
   * Storage is captured in the WaitCondition for replay.
   */
  case Suspend[A, S <: DurableStorageBackend](
    condition: WaitCondition[A, S]
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
    Activity(() => Future.successful(compute), storage, policy)

  /** Suspend the workflow with a wait condition (condition already contains storage) */
  def suspend[A, S <: DurableStorageBackend](condition: WaitCondition[A, S]): Durable[A] =
    Suspend(condition)

  /** Sleep until a specific instant, returns actual wake time */
  def sleepUntil[S <: DurableStorageBackend](wakeAt: Instant)(using storage: DurableStorage[Instant, S]): Durable[Instant] =
    Suspend(WaitCondition.Timer(wakeAt, storage))

  /** Sleep for a duration, returns actual wake time */
  def sleep[S <: DurableStorageBackend](duration: FiniteDuration)(using storage: DurableStorage[Instant, S]): Durable[Instant] =
    sleepUntil(Instant.now().plusMillis(duration.toMillis))

  /** Wait for a broadcast event of type E */
  def awaitEvent[E, S <: DurableStorageBackend](using eventName: DurableEventName[E], storage: DurableStorage[E, S]): Durable[E] =
    Suspend(WaitCondition.Event(eventName.name, storage))

  /**
   * Continue as a new workflow with the given arguments.
   * Clears activity storage, stores new args, and restarts with the new workflow.
   *
   * @param functionName Name of the function (for metadata)
   * @param args Tuple of arguments to store
   * @param workflow The new workflow to run (by-name to avoid infinite recursion)
   * @return Durable[R] - the result type of the continued workflow
   */
  def continueAs[Args <: Tuple, R, S <: DurableStorageBackend](
    functionName: String,
    args: Args,
    workflow: => Durable[R]
  )(using argsStorage: TupleDurableStorage[Args, S]): Durable[R] =
    val argCount = argsStorage.size
    val metadata = WorkflowMetadata(functionName, argCount, argCount)
    val storeArgsFn = (backend: DurableStorageBackend, wfId: WorkflowId, ec: ExecutionContext) =>
      given ExecutionContext = ec
      argsStorage.storeAll(backend.asInstanceOf[S], wfId, 0, args)
    ContinueAs(metadata, storeArgsFn, () => workflow)

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
      Durable.Activity(() => Future.successful(compute), storage, policy)

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
