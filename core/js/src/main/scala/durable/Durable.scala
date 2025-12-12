package example.durable

import scala.scalajs.js
import scala.scalajs.js.special.debugger
import cps.*

/**
 * Durable[A] - A computation that can be suspended and resumed.
 *
 * The key insight: we use replay-based execution.
 * - Each step is numbered automatically
 * - On first run: execute and cache results
 * - On resume: replay from beginning, return cached results until reaching saved step
 * - Then continue actual execution
 *
 * This gives us a proper monad where flatMap works correctly:
 * - The function f in flatMap(fa)(f) doesn't need to be serializable
 * - We re-execute f on resume, but it gets the cached input
 *
 * V8 Auto-Capture:
 * - flatMap emits debugger() at each boundary
 * - External V8 Inspector captures/restores locals automatically
 * - No manual debugger() calls needed in workflow code
 */
final class Durable[A] private (
  private[durable] val run: DurableContext => A
):
  def map[B](f: A => B): Durable[B] =
    Durable(ctx => f(run(ctx)))

  def flatMap[B](f: A => Durable[B]): Durable[B] =
    Durable { ctx =>
      val a = run(ctx)
      // V8 checkpoint: Inspector captures/restores locals here
      debugger()
      f(a).run(ctx)
    }

object Durable:
  /** Create a Durable from a context function */
  def apply[A](f: DurableContext => A): Durable[A] =
    new Durable(f)

  /** Lift a pure value */
  def pure[A](a: A): Durable[A] =
    Durable(_ => a)

  /** Synonym for pure */
  def successful[A](a: A): Durable[A] = pure(a)

  /**
   * Suspend point for V8 auto-capture.
   *
   * Usage:
   *   await(Durable.suspend)
   *   val result = expensiveComputation()  // This line starts next flatMap
   *
   * The flatMap boundary emits debugger(), V8 captures/restores all locals.
   * On resume, locals are restored and computation continues from here.
   */
  def suspend: Durable[Unit] = pure(())

  /**
   * Create a durable step that will be cached and replayed.
   * @deprecated Use suspend + V8 auto-capture instead
   */
  def step[A](compute: => A)(using serializer: DurableSerializer[A]): Durable[A] =
    Durable(ctx => ctx.executeStep(compute))

  /**
   * Request shutdown. The computation will stop after the current step.
   */
  def shutdown: Durable[Unit] =
    Durable(ctx => ctx.shutdown())

  /**
   * Check if shutdown was requested.
   */
  def isShutdown: Durable[Boolean] =
    Durable(ctx => ctx.isShutdown)

  /**
   * Check if we're currently replaying cached results.
   */
  def isReplaying: Durable[Boolean] =
    Durable(ctx => ctx.isReplaying)

  /**
   * Check if this is a resumed execution (vs fresh start).
   * Unlike isReplaying, this remains true throughout a resumed execution.
   */
  def isResumed: Durable[Boolean] =
    Durable(ctx => ctx.isResumed)

  /**
   * Get the current step number.
   */
  def currentStep: Durable[Int] =
    Durable(ctx => ctx.currentStep)

  /**
   * Execute a Durable computation with a fresh context.
   * Returns either the result or a snapshot for resumption.
   */
  def execute[A](durable: Durable[A]): Either[DurableContextSnapshot, A] =
    val ctx = DurableContext.fresh()
    try
      val result = durable.run(ctx)
      if ctx.isShutdown then
        Left(ctx.toSnapshot)
      else
        Right(result)
    catch
      case ShutdownRequested(snapshot) =>
        Left(snapshot)

  /**
   * Resume a Durable computation from a snapshot.
   */
  def resume[A](durable: Durable[A], snapshot: DurableContextSnapshot): Either[DurableContextSnapshot, A] =
    val ctx = DurableContext.fromSnapshot(snapshot)
    try
      val result = durable.run(ctx)
      if ctx.isShutdown then
        Left(ctx.toSnapshot)
      else
        Right(result)
    catch
      case ShutdownRequested(snap) =>
        Left(snap)

  /**
   * CpsTryMonad instance for use with async/await syntax.
   * CpsTryMonad extends CpsMonad and adds error handling.
   *
   * Usage:
   *   async[Durable] {
   *     val a = await(step1)
   *     val b = await(step2(a))
   *     b
   *   }
   */
  /**
   * CpsMonadContext for Durable - minimal context for async/await
   */
  class DurableCpsContext extends CpsTryMonadContext[Durable]:
    def monad: CpsTryMonad[Durable] = Durable.given_CpsTryMonad_Durable

  given CpsTryMonad[Durable] with
    type Context = DurableCpsContext

    def pure[A](a: A): Durable[A] = Durable.pure(a)

    def map[A, B](fa: Durable[A])(f: A => B): Durable[B] =
      fa.map(f)

    def flatMap[A, B](fa: Durable[A])(f: A => Durable[B]): Durable[B] =
      fa.flatMap(f)

    def error[A](e: Throwable): Durable[A] =
      Durable(_ => throw e)

    def flatMapTry[A, B](fa: Durable[A])(f: scala.util.Try[A] => Durable[B]): Durable[B] =
      Durable { ctx =>
        val tryResult = scala.util.Try(fa.run(ctx))
        f(tryResult).run(ctx)
      }

    def apply[A](op: Context => Durable[A]): Durable[A] =
      op(new DurableCpsContext)

/**
 * Exception used to signal shutdown (alternative to checking isShutdown).
 */
case class ShutdownRequested(snapshot: DurableContextSnapshot) extends Exception("Shutdown requested")
