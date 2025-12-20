package durable

import scala.concurrent.Future
import scala.util.Try
import cps.*

/**
 * DurableRaw - a variant of Durable without the CpsPreprocessor.
 *
 * Use this in tests that need to verify the raw Durable API behavior
 * without automatic val wrapping by the preprocessor.
 *
 * Usage:
 *   async[DurableRaw] {
 *     // val definitions here are NOT automatically wrapped as activities
 *     val x = 42  // just a val, not an activity
 *     await(Durable.activity(Future.successful(x + 1)))
 *   }
 */
opaque type DurableRaw[A] = Durable[A]

object DurableRaw:
  /** Convert from Durable to DurableRaw */
  def apply[A](d: Durable[A]): DurableRaw[A] = d

  /** Convert back to Durable */
  extension [A](dr: DurableRaw[A])
    def toDurable: Durable[A] = dr

  /** Lift a pure value */
  def pure[A](a: A): DurableRaw[A] = Durable.pure(a)

  /** Lift an error */
  def failed[A](e: Throwable): DurableRaw[A] = Durable.failed(e)

  /** Create an activity */
  def activity[A, S <: DurableStorageBackend](compute: => Future[A], policy: RetryPolicy = RetryPolicy.default)
               (using storage: DurableStorage[A, S]): DurableRaw[A] =
    DurableRaw(Durable.activity(compute, policy))

  /** Create a sync activity */
  def activitySync[A, S <: DurableStorageBackend](compute: => A, policy: RetryPolicy = RetryPolicy.default)
                   (using storage: DurableStorage[A, S]): DurableRaw[A] =
    DurableRaw(Durable.activitySync(compute, policy))

  /** Local computation */
  def local[A](compute: WorkflowSessionRunner.RunContext => A): DurableRaw[A] =
    Durable.local(compute)

  /**
   * CpsMonadContext for DurableRaw - provides context for async/await.
   * Unlike Durable.DurableContext, this does NOT wrap vals with activity.
   */
  class DurableRawCpsContext extends CpsTryMonadContext[[A] =>> DurableRaw[A]]:
    def monad: CpsTryMonad[[A] =>> DurableRaw[A]] = durableRawCpsTryMonad

  /**
   * CpsTryMonad instance for DurableRaw - enables async/await syntax.
   * No CpsPreprocessor is defined in this companion, so no automatic val wrapping.
   */
  given durableRawCpsTryMonad: CpsTryMonad[[A] =>> DurableRaw[A]] with
    type Context = DurableRawCpsContext

    def pure[A](a: A): DurableRaw[A] = DurableRaw.pure(a)

    def map[A, B](fa: DurableRaw[A])(f: A => B): DurableRaw[B] =
      DurableRaw(fa.toDurable.map(f))

    def flatMap[A, B](fa: DurableRaw[A])(f: A => DurableRaw[B]): DurableRaw[B] =
      DurableRaw(fa.toDurable.flatMap(a => f(a).toDurable))

    def error[A](e: Throwable): DurableRaw[A] =
      DurableRaw.failed(e)

    def flatMapTry[A, B](fa: DurableRaw[A])(f: Try[A] => DurableRaw[B]): DurableRaw[B] =
      DurableRaw(Durable.FlatMapTry(fa.toDurable, (t: Try[A]) => f(t).toDurable))

    def apply[A](op: Context => DurableRaw[A]): DurableRaw[A] =
      op(new DurableRawCpsContext)
