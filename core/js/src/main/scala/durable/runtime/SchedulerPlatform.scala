package durable.runtime

import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers.setTimeout

/**
 * JavaScript-specific Scheduler companion implementation.
 * Uses setTimeout for delayed execution.
 */
trait SchedulerCompanionPlatform:
  /**
   * Default scheduler for JS using setTimeout.
   */
  val default: Scheduler = new Scheduler:
    def schedule[A](delay: FiniteDuration)(f: => Future[A])(using ec: ExecutionContext): Future[A] =
      if delay.toMillis <= 0 then
        f
      else
        val promise = Promise[A]()
        setTimeout(delay.toMillis.toDouble) {
          // Execute the future computation and complete the promise
          f.onComplete(promise.complete)
        }
        promise.future
