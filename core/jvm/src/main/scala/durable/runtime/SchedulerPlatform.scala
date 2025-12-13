package durable.runtime

import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{ScheduledExecutorService, Executors, TimeUnit}

/**
 * JVM-specific Scheduler companion implementation.
 * Uses ScheduledExecutorService for efficient delayed execution.
 */
trait SchedulerCompanionPlatform:
  private lazy val scheduledExecutor: ScheduledExecutorService =
    Executors.newScheduledThreadPool(1, (r: Runnable) => {
      val t = new Thread(r, "durable-scheduler")
      t.setDaemon(true)
      t
    })

  /**
   * Default scheduler for JVM using ScheduledExecutorService.
   * More efficient than Thread.sleep as it doesn't block a thread during the delay.
   */
  val default: Scheduler = new Scheduler:
    def schedule[A](delay: FiniteDuration)(f: => Future[A])(using ec: ExecutionContext): Future[A] =
      if delay.toMillis <= 0 then
        f
      else
        val promise = Promise[A]()
        scheduledExecutor.schedule(
          new Runnable {
            def run(): Unit =
              // Execute the future computation and complete the promise
              f.onComplete(promise.complete)(using ec)
          },
          delay.toMillis,
          TimeUnit.MILLISECONDS
        )
        promise.future
