package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

import durable.runtime.Scheduler

/**
 * Typeclass for wrapping async operations in durable workflows.
 *
 * When the preprocessor detects `val x: F[T] = expr` where F has a DurableAsyncWrapper,
 * it transforms to use activityAsync which delegates to this wrapper.
 *
 * The wrapper is responsible for:
 * - Checking storage for cached value (return F with cached value if found)
 * - Executing op() if not cached, with retry logic
 * - Caching the result when F completes
 * - Returning F[T] immediately (don't block)
 * - Failing workflow if storage operation fails
 *
 * @tparam F the async monad type (e.g., Future)
 */
trait DurableAsyncWrapper[F[_]]:
  /**
   * Wrap an async operation for durable execution.
   *
   * @param op Thunk producing F[T] - only called on fresh run
   * @param index Activity index for caching
   * @param workflowId Workflow identifier
   * @param isReplaying Whether we're replaying from cache
   * @param policy Retry policy for failed operations
   * @param scheduler Scheduler for retry delays
   * @param retryLogger Logger for retry events
   * @param storage Storage for caching the result T
   * @param backend Storage backend instance
   * @return F[T] - either from cache or fresh execution
   */
  def wrap[T, S <: DurableStorageBackend](
    op: () => F[T],
    index: Int,
    workflowId: WorkflowId,
    isReplaying: Boolean,
    policy: RetryPolicy,
    scheduler: Scheduler,
    retryLogger: RetryLogger
  )(using storage: DurableStorage[T, S], backend: S): F[T]


object DurableAsyncWrapper:

  /**
   * Instance for Future - the primary use case.
   *
   * On Replay:
   * - Retrieve cached value from storage (if storage fails, workflow fails)
   * - If found success: return Future.successful(cachedValue)
   * - If found failure: return Future.failed(ReplayedException)
   * - If not found: restart the activity (execute as fresh run)
   *   This handles the case where engine restarted while async activity was in progress
   *
   * On Fresh Run:
   * - Execute op() with retry logic (exponential backoff with jitter)
   * - Chain storage operation (store on success, storeFailure on failure)
   * - If storage fails, the Future fails (workflow fails)
   * - Return the chained Future (completes after both computation AND storage)
   */
  given futureWrapper(using ec: ExecutionContext): DurableAsyncWrapper[Future] with
    def wrap[T, S <: DurableStorageBackend](
      op: () => Future[T],
      index: Int,
      workflowId: WorkflowId,
      isReplaying: Boolean,
      policy: RetryPolicy,
      scheduler: Scheduler,
      retryLogger: RetryLogger
    )(using storage: DurableStorage[T, S], backend: S): Future[T] =

      def executeFresh(): Future[T] =
        executeWithRetry(op, policy, scheduler, retryLogger, workflowId, index).transformWith {
          case Success(value) =>
            storage.storeStep(backend, workflowId, index, value).transform {
              case Success(_) => Success(value)
              case Failure(storageError) => Failure(storageError)
            }
          case Failure(computeError) =>
            storage.storeStepFailure(backend, workflowId, index, StoredFailure.fromThrowable(computeError)).transform {
              case Success(_) => Failure(computeError)
              case Failure(storageError) => Failure(storageError)
            }
        }

      if isReplaying then
        // Replay: retrieve from cache
        // If storage.retrieveStep fails, the Future fails (workflow fails)
        storage.retrieveStep(backend, workflowId, index).flatMap {
          case Some(Right(cached)) =>
            Future.successful(cached)
          case Some(Left(failure)) =>
            Future.failed(ReplayedException(failure))
          case None =>
            // Activity was started but not completed before engine restart - restart it
            executeFresh()
        }
      else
        executeFresh()

    /**
     * Execute computation with retry logic.
     * Follows same pattern as WorkflowSessionRunner.executeWithRetry.
     * Accumulates retry events for inclusion in MaxRetriesExceededException.
     */
    private def executeWithRetry[T](
      op: () => Future[T],
      policy: RetryPolicy,
      scheduler: Scheduler,
      retryLogger: RetryLogger,
      workflowId: WorkflowId,
      activityIndex: Int
    )(using ec: ExecutionContext): Future[T] =

      def attempt(attemptNum: Int, lastError: Option[Throwable], history: List[RetryEvent]): Future[T] =
        if attemptNum > policy.maxAttempts then
          // Exhausted all retries
          Future.failed(MaxRetriesExceededException(
            workflowId = workflowId,
            activityIndex = activityIndex,
            attempts = attemptNum - 1,
            maxAttempts = policy.maxAttempts,
            history = history.reverse,
            cause = lastError.orNull
          ))
        else
          op().recoverWith { case NonFatal(e) =>
            val isRecoverable = policy.isRecoverable(e)
            val hasMoreAttempts = attemptNum < policy.maxAttempts
            val willRetry = isRecoverable && hasMoreAttempts

            // Calculate delay for next attempt (if any)
            val nextDelayMs = if willRetry then
              val baseDelay = policy.delayForAttempt(attemptNum)
              val delayWithJitter = policy.applyJitter(baseDelay)
              Some(delayWithJitter.toMillis)
            else
              None

            // Create retry event
            val event = RetryEvent(
              workflowId = workflowId,
              activityIndex = activityIndex,
              attempt = attemptNum,
              maxAttempts = policy.maxAttempts,
              error = e,
              nextDelayMs = nextDelayMs,
              willRetry = willRetry
            )
            retryLogger(event)
            val updatedHistory = event :: history

            if willRetry then
              // Schedule retry after delay
              val delayMs = nextDelayMs.getOrElse(0L)
              scheduleRetry(delayMs.millis, scheduler) {
                attempt(attemptNum + 1, Some(e), updatedHistory)
              }
            else
              // Not recoverable or exhausted retries - wrap in MaxRetriesExceededException
              Future.failed(MaxRetriesExceededException(
                workflowId = workflowId,
                activityIndex = activityIndex,
                attempts = attemptNum,
                maxAttempts = policy.maxAttempts,
                history = updatedHistory.reverse,
                cause = e
              ))
          }

      attempt(1, None, Nil)

    /**
     * Schedule a delayed retry using the scheduler.
     */
    private def scheduleRetry[T](
      delay: FiniteDuration,
      scheduler: Scheduler
    )(f: => Future[T])(using ec: ExecutionContext): Future[T] =
      if delay.toMillis <= 0 then
        f
      else
        scheduler.schedule(delay)(f)
