package durable

import scala.concurrent.Future
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
 * Each Activity captures a DurableStorage[A] instance for caching.
 *
 * This is a data structure describing the computation, not executing it.
 * The runner (interpreter) handles execution, async operations, and storage.
 *
 * Usage with async/await:
 *   given backing: MemoryBackingStore = MemoryBackingStore()
 *   given [T]: DurableStorage[T] = backing.forType[T]
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
  case Activity[A](
    compute: () => Future[A],
    storage: DurableStorage[A],
    retryPolicy: RetryPolicy
  ) extends Durable[A]

  /** Suspend for external input - timer, event, child workflow, etc. */
  case Suspend[A](condition: WaitCondition[A]) extends Durable[A]

  /** Try/catch semantics - handles both success and failure of fa */
  case FlatMapTry[A, B](fa: Durable[A], f: Try[A] => Durable[B]) extends Durable[B]

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
  def activity[A](compute: => Future[A], policy: RetryPolicy = RetryPolicy.default)
                 (using storage: DurableStorage[A]): Durable[A] =
    Activity(() => compute, storage, policy)

  /**
   * Create an activity from a synchronous computation.
   * Convenience method that wraps the result in Future.successful.
   * Storage is captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activitySync[A](compute: => A, policy: RetryPolicy = RetryPolicy.default)
                     (using storage: DurableStorage[A]): Durable[A] =
    Activity(() => Future.successful(compute), storage, policy)

  /** Suspend the workflow with a wait condition */
  def suspend[A](condition: WaitCondition[A]): Durable[A] =
    Suspend(condition)

  /** Sleep until a specific instant, returns actual wake time */
  def sleepUntil(wakeAt: Instant): Durable[Instant] =
    Suspend(WaitCondition.Timer(wakeAt))

  /** Sleep for a duration, returns actual wake time */
  def sleep(duration: FiniteDuration): Durable[Instant] =
    sleepUntil(Instant.now().plusMillis(duration.toMillis))

  /** Wait for a broadcast event of type E */
  def awaitEvent[E](using eventName: DurableEventName[E], storage: DurableStorage[E]): Durable[E] =
    Suspend(WaitCondition.Event(eventName.name))

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
    def activity[A](compute: => Future[A], policy: RetryPolicy)
                   (using storage: DurableStorage[A]): Durable[A] =
      Durable.Activity(() => compute, storage, policy)

    /**
     * Create an activity from a synchronous computation.
     * Wraps the result in Future.successful.
     * Used by preprocessor to wrap val definitions.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activitySync[A](compute: => A, policy: RetryPolicy)
                       (using storage: DurableStorage[A]): Durable[A] =
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
