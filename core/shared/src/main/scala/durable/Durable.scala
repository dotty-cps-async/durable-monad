package durable

import scala.concurrent.Future
import scala.util.{Try, Success, Failure}
import cps.*

/**
 * Durable[A] - A Monad describing a durable computation.
 *
 * Type parameters:
 *   A - the result type
 *
 * Storage type S is determined via AppContext at activity creation time.
 * Each Activity captures its own storage instance and backend.
 *
 * This is a data structure describing the computation, not executing it.
 * The runner (interpreter) handles execution, async operations, and storage.
 *
 * Usage with async/await:
 *   given MemoryStorage = AppContext[MemoryStorage]
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
   *   - If not cached: executes compute, caches result, returns
   *   - Can retry on recoverable failures
   *
   * S is existential - hidden from Durable[A] but ties backend and storage together.
   * The backend and storage are captured at creation time to preserve type safety.
   */
  case Activity[A, S](
    compute: () => Future[A],
    backend: DurableCacheBackend[A, S],
    storage: S
  ) extends Durable[A]

  /** Suspend for external call (inbound) - timer, signal, etc. */
  case Suspend(waitingFor: String)

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
   * Backend and storage are captured to preserve type safety at runtime.
   * Storage is provided via AppContext (using parameter).
   */
  def activity[A, S](compute: => Future[A])(using backend: DurableCacheBackend[A, S], storage: S): Durable[A] =
    Activity(() => compute, backend, storage)

  /**
   * Create an activity from a synchronous computation.
   * Convenience method that wraps the result in Future.successful.
   * Backend and storage are captured to preserve type safety at runtime.
   * Storage is provided via AppContext (using parameter).
   */
  def activitySync[A, S](compute: => A)(using backend: DurableCacheBackend[A, S], storage: S): Durable[A] =
    Activity(() => Future.successful(compute), backend, storage)

  /** Suspend the workflow, waiting for external input (inbound) */
  def suspend[A](waitingFor: String): Durable[A] =
    Suspend(waitingFor)

  /**
   * CpsMonadContext for Durable - provides context for async/await.
   * Also provides activity methods for the preprocessor to wrap val definitions.
   */
  class DurableCpsContext extends CpsTryMonadContext[[A] =>> Durable[A]]:
    def monad: CpsTryMonad[[A] =>> Durable[A]] = durableCpsTryMonad

    /**
     * Create an activity from an async computation.
     * Used by preprocessor to wrap val definitions.
     * Backend and storage are captured to preserve type safety at runtime.
     */
    def activity[A, S](compute: => Future[A])(using backend: DurableCacheBackend[A, S], storage: S): Durable[A] =
      Durable.activity(compute)

    /**
     * Create an activity from a synchronous computation.
     * Wraps the result in Future.successful.
     * Used by preprocessor to wrap val definitions.
     * Backend and storage are captured to preserve type safety at runtime.
     */
    def activitySync[A, S](compute: => A)(using backend: DurableCacheBackend[A, S], storage: S): Durable[A] =
      Durable.activitySync(compute)

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
      FlatMap(fa, (a: A) => f(Success(a))).asInstanceOf[Durable[B]] // TODO: handle errors properly

    def apply[A](op: Context => Durable[A]): Durable[A] =
      op(new DurableCpsContext)
