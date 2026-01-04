package durable.ce3

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import durable.*
import durable.engine.WorkflowSessionRunner.RunContext
import durable.runtime.Scheduler

/**
 * DurableAsync implementation for cats-effect IO.
 *
 * Provides two modes:
 * - wrapCached: Returns IO[T] immediately, caches result when completed
 * - wrapEphemeral: Returns IO[T] immediately, no caching (for ephemeral types)
 *
 * Uses IORuntime for executing IO effects.
 */
class IODurableAsync(using runtime: IORuntime) extends DurableAsync[IO]:

  def wrapCached[T, S <: DurableStorageBackend](
    op: () => IO[T],
    index: Int,
    workflowId: WorkflowId,
    isReplaying: Boolean,
    policy: RetryPolicy,
    scheduler: Scheduler,
    retryLogger: RetryLogger
  )(using storage: DurableStorage[T, S], backend: S): IO[T] =
    if isReplaying then
      // Replaying - retrieve from cache
      IO.fromFuture(IO.pure(storage.retrieveStep(backend, workflowId, index))).flatMap {
        case Some(Right(cached)) =>
          IO.pure(cached)
        case Some(Left(storedFailure)) =>
          IO.raiseError(ReplayedException(storedFailure))
        case None =>
          IO.raiseError(RuntimeException(
            s"Missing cached result for async activity at index=$index during replay"
          ))
      }
    else
      // Execute with retry logic
      executeWithRetry(op, policy, workflowId, index, scheduler, retryLogger).flatMap { result =>
        // Cache result
        IO.fromFuture(IO.pure(storage.storeStep(backend, workflowId, index, result))).as(result)
      }.handleErrorWith { error =>
        // Cache failure
        IO.fromFuture(IO.pure(
          storage.storeStepFailure(backend, workflowId, index, StoredFailure.fromThrowable(error))
        )).flatMap(_ => IO.raiseError(error))
      }

  def wrapEphemeral[T](op: () => IO[T], runContext: RunContext): IO[T] =
    // No caching - just execute
    op()

  private def executeWithRetry[T](
    compute: () => IO[T],
    policy: RetryPolicy,
    workflowId: WorkflowId,
    activityIndex: Int,
    scheduler: Scheduler,
    retryLogger: RetryEvent => Unit
  ): IO[T] =
    def attempt(attemptNum: Int, lastError: Option[Throwable]): IO[T] =
      if attemptNum > policy.maxAttempts then
        IO.raiseError(lastError.getOrElse(
          RuntimeException("Max retries exceeded with no error recorded")
        ))
      else
        compute().handleErrorWith { error =>
          val isRecoverable = policy.isRecoverable(error)
          val hasMoreAttempts = attemptNum < policy.maxAttempts
          val willRetry = isRecoverable && hasMoreAttempts

          val nextDelayMs = if willRetry then
            val baseDelay = policy.delayForAttempt(attemptNum)
            val delayWithJitter = policy.applyJitter(baseDelay)
            Some(delayWithJitter.toMillis)
          else
            None

          val event = RetryEvent(
            workflowId = workflowId,
            activityIndex = activityIndex,
            attempt = attemptNum,
            maxAttempts = policy.maxAttempts,
            error = error,
            nextDelayMs = nextDelayMs,
            willRetry = willRetry
          )
          retryLogger(event)

          if willRetry then
            val delayMs = nextDelayMs.getOrElse(0L)
            IO.sleep(scala.concurrent.duration.FiniteDuration(delayMs, "ms")) *>
              attempt(attemptNum + 1, Some(error))
          else
            IO.raiseError(error)
        }

    attempt(1, None)


object IODurableAsync:
  def apply(using runtime: IORuntime): IODurableAsync = new IODurableAsync
