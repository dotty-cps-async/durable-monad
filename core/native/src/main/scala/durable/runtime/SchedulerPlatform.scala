package durable.runtime

import scala.collection.mutable
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.FiniteDuration

/**
 * Scala Native-specific Scheduler companion implementation.
 * Uses a background timer thread with priority queue for efficient delayed execution.
 */
trait SchedulerCompanionPlatform:

  /**
   * Internal timer for Native platform.
   * Uses a background thread with priority queue to schedule delayed tasks.
   */
  private object NativeTimer:
    class Sleeper(val wakeTime: Long, val runnable: Runnable, @volatile var cancelled: Boolean)

    private val queue = mutable.PriorityQueue[Sleeper]()(using Ordering.by[Sleeper, Long](-_.wakeTime))
    @volatile private var shutdownFlag = false

    private val timerThread = new Thread {
      override def run(): Unit =
        while !shutdownFlag do
          val now = System.currentTimeMillis()
          val next = NativeTimer.synchronized(queue.headOption)
          next match
            case Some(sleeper) =>
              if sleeper.wakeTime <= now then
                NativeTimer.synchronized(queue.dequeue())
                if !sleeper.cancelled then
                  sleeper.runnable.run()
              else
                val sleepTime = sleeper.wakeTime - now
                NativeTimer.synchronized {
                  NativeTimer.wait(sleepTime)
                }
            case None =>
              NativeTimer.synchronized {
                NativeTimer.wait(1000L)
              }
    }
    timerThread.setDaemon(true)
    timerThread.start()

    def schedule(duration: FiniteDuration)(f: => Unit): Unit =
      val wakeTime = System.currentTimeMillis() + duration.toMillis
      val sleeper = Sleeper(wakeTime, () => f, false)
      NativeTimer.synchronized {
        queue.enqueue(sleeper)
        NativeTimer.notifyAll()
      }

  /**
   * Default scheduler for Native using background timer thread.
   */
  val default: Scheduler = new Scheduler:
    def schedule[A](delay: FiniteDuration)(f: => Future[A])(using ec: ExecutionContext): Future[A] =
      if delay.toMillis <= 0 then
        f
      else
        val promise = Promise[A]()
        NativeTimer.schedule(delay) {
          // Execute the future computation and complete the promise
          ec.execute(() => f.onComplete(promise.complete))
        }
        promise.future
