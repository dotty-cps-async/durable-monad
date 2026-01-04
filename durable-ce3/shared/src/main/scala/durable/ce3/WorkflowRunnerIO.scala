package durable.ce3

import scala.concurrent.ExecutionContext

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cps.CpsAsyncMonad

import durable.*
import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult, NeedsBiggerRunner}

/**
 * Factory for creating IO-based WorkflowSessionRunner.
 *
 * WorkflowSessionRunner[IO] can handle all activity effects:
 * - Activity[IO] - native execution
 * - Activity[Future] - lifted via IO.fromFuture
 * - Activity[CpsIdentity] - lifted via IO.pure
 *
 * This is the "biggest" runner - it never returns NeedsBiggerRunner.
 */
object WorkflowRunnerIO:

  /**
   * Create an IO-based runner.
   *
   * Uses the CpsAsyncMonad[IO] from dotty-cps-async-cats-effect.
   */
  def apply(using runtime: IORuntime): WorkflowSessionRunner[IO] =
    import cps.monads.catsEffect.{*, given}
    new WorkflowSessionRunner[IO](IOEffectTag.ioTag)

  /**
   * Create an IO-based runner with custom execution context.
   *
   * @param ec ExecutionContext for Future operations
   * @param runtime IORuntime for IO execution
   */
  def apply(ec: ExecutionContext)(using runtime: IORuntime): WorkflowSessionRunner[IO] =
    import cps.monads.catsEffect.{*, given}
    new WorkflowSessionRunner[IO](IOEffectTag.ioTag)

  /**
   * Run a workflow in IO, returning IO[WorkflowSessionResult[A]].
   *
   * Since IORunner can handle all effects, this never returns Left(NeedsBiggerRunner).
   * The Either is unwrapped to just the result.
   */
  def run[A](
    workflow: Durable[A],
    ctx: WorkflowSessionRunner.RunContext
  )(using runtime: IORuntime): IO[WorkflowSessionResult[A]] =
    val runner = apply
    runner.run(workflow, ctx).flatMap {
      case Right(result) => IO.pure(result)
      case Left(needsBigger) =>
        // This should never happen with IORunner since it handles all effects
        IO.raiseError(new RuntimeException(
          s"Unexpected: IORunner cannot handle effect ${needsBigger.activityTag}. " +
          "This indicates a bug or an unsupported effect type."
        ))
    }
