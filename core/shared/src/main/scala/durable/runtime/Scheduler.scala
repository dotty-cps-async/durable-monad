package durable.runtime

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.FiniteDuration

/**
 * Scheduler abstraction for delayed execution.
 * Platform-specific implementations provided in companion object.
 *
 * This is an internal runtime component used by WorkflowRunner
 * for retry delays.
 */
trait Scheduler:
  /**
   * Schedule a delayed computation.
   *
   * @param delay Duration to wait before executing
   * @param f The computation to execute after delay
   * @return Future containing the result
   */
  def schedule[A](delay: FiniteDuration)(f: => Future[A])(using ExecutionContext): Future[A]

object Scheduler extends SchedulerCompanionPlatform:
  /** Immediate scheduler - no delay, useful for testing */
  val immediate: Scheduler = new Scheduler:
    def schedule[A](delay: FiniteDuration)(f: => Future[A])(using ec: ExecutionContext): Future[A] = f
