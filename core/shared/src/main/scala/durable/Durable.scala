package durable

import scala.concurrent.Future
import scala.util.{Try, Success, Failure}
import cps.*

/**
 * Durable[A, S] - A Free Monad describing a durable computation.
 *
 * Type parameters:
 *   A - the result type
 *   S - the storage type for caching
 *
 * This is a data structure describing the computation, not executing it.
 * The runner (interpreter) handles execution, async operations, and storage.
 *
 * Usage with async/await:
 *   async[Durable[*, MyStorage]] {
 *     val a = await(activity1)
 *     await(sleep(1.hour))        // suspends here
 *     val b = await(activity2(a))
 *     b
 *   }
 */
enum Durable[A, S]:
  /** Lift a value */
  case Pure(value: A)

  /** Sequencing */
  case FlatMap[A, B, S](fa: Durable[A, S], f: A => Durable[B, S]) extends Durable[B, S]

  /** Error */
  case Error(error: Throwable)

  /** Local computation - sync, deterministic, no caching needed. Has access to context. */
  case LocalComputation(compute: RunContext[S] => A)

  /**
   * Activity (outbound operation).
   * Created during monad construction (by preprocessor or user code).
   * Index is assigned at runtime by the interpreter.
   *
   * On interpretation:
   *   - Runner assigns index (runtime counter)
   *   - Checks storage: if cached, returns cached value
   *   - If not cached: executes compute, caches result, returns
   *   - Can retry on recoverable failures
   *
   * The backend is captured at creation time to preserve type safety.
   * This avoids the need for DurableCacheBackend[Any, S] at runtime.
   */
  case Activity[A, S](compute: () => Future[A], backend: DurableCacheBackend[A, S]) extends Durable[A, S]

  /** Suspend for external call (inbound) - timer, signal, etc. */
  case Suspend(waitingFor: String)

  def map[B](f: A => B): Durable[B, S] =
    Durable.FlatMap(this, (a: A) => Durable.Pure(f(a)))

  def flatMap[B](f: A => Durable[B, S]): Durable[B, S] =
    Durable.FlatMap(this, f)


object Durable:
  /** Lift a pure value */
  def pure[A, S](a: A): Durable[A, S] =
    Pure(a)

  /** Lift an error */
  def failed[A, S](e: Throwable): Durable[A, S] =
    Error(e)

  /** Local computation - sync, deterministic, no caching */
  def local[A, S](compute: RunContext[S] => A): Durable[A, S] =
    LocalComputation(compute)

  /**
   * Create an activity - async operation that will be cached.
   * Index is assigned at runtime by the interpreter.
   * Backend is captured to preserve type safety at runtime.
   */
  def activity[A, S](compute: => Future[A])(using backend: DurableCacheBackend[A, S]): Durable[A, S] =
    Activity(() => compute, backend)

  /**
   * Create an activity from a synchronous computation.
   * Convenience method that wraps the result in Future.successful.
   * Backend is captured to preserve type safety at runtime.
   */
  def activitySync[A, S](compute: => A)(using backend: DurableCacheBackend[A, S]): Durable[A, S] =
    Activity(() => Future.successful(compute), backend)

  /** Suspend the workflow, waiting for external input (inbound) */
  def suspend[A, S](waitingFor: String): Durable[A, S] =
    Suspend(waitingFor)

  /**
   * CpsMonadContext for Durable - provides context for async/await.
   * Also provides activity methods for the preprocessor to wrap val definitions.
   */
  class DurableCpsContext[S] extends CpsTryMonadContext[[A] =>> Durable[A, S]]:
    def monad: CpsTryMonad[[A] =>> Durable[A, S]] = durableCpsTryMonad[S]

    /**
     * Create an activity from an async computation.
     * Used by preprocessor to wrap val definitions.
     * Backend is captured to preserve type safety at runtime.
     */
    def activity[A](compute: => Future[A])(using backend: DurableCacheBackend[A, S]): Durable[A, S] =
      Durable.activity(compute)

    /**
     * Create an activity from a synchronous computation.
     * Wraps the result in Future.successful.
     * Used by preprocessor to wrap val definitions.
     * Backend is captured to preserve type safety at runtime.
     */
    def activitySync[A](compute: => A)(using backend: DurableCacheBackend[A, S]): Durable[A, S] =
      Durable.activitySync(compute)

  /**
   * CpsTryMonad instance for async/await syntax.
   */
  given durableCpsTryMonad[S]: CpsTryMonad[[A] =>> Durable[A, S]] with
    type Context = DurableCpsContext[S]

    def pure[A](a: A): Durable[A, S] = Durable.pure(a)

    def map[A, B](fa: Durable[A, S])(f: A => B): Durable[B, S] =
      fa.map(f)

    def flatMap[A, B](fa: Durable[A, S])(f: A => Durable[B, S]): Durable[B, S] =
      fa.flatMap(f)

    def error[A](e: Throwable): Durable[A, S] =
      Durable.failed(e)

    def flatMapTry[A, B](fa: Durable[A, S])(f: Try[A] => Durable[B, S]): Durable[B, S] =
      FlatMap(fa, (a: A) => f(Success(a))).asInstanceOf[Durable[B, S]] // TODO: handle errors properly

    def apply[A](op: Context => Durable[A, S]): Durable[A, S] =
      op(new DurableCpsContext[S])
