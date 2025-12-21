package durable

import scala.concurrent.duration.*

/**
 * Retry policy for activity execution.
 * Configures when and how to retry failed activities.
 *
 * @param maxAttempts Maximum number of execution attempts (including initial)
 * @param initialBackoff Initial delay before first retry
 * @param maxBackoff Maximum delay between retries
 * @param backoffMultiplier Multiplier for exponential backoff
 * @param jitterFactor Random jitter factor (0.0 to 1.0)
 * @param isRecoverable Predicate to determine if an error is recoverable
 * @param computeDelay Optional custom delay calculation function
 */
case class RetryPolicy(
  maxAttempts: Int = 3,
  initialBackoff: FiniteDuration = 100.millis,
  maxBackoff: FiniteDuration = 300.seconds,
  backoffMultiplier: Double = 2.0,
  jitterFactor: Double = 0.1,
  isRecoverable: Throwable => Boolean = RetryPolicy.defaultIsRecoverable,
  computeDelay: Option[(Int, FiniteDuration, FiniteDuration) => FiniteDuration] = None
):
  /**
   * Calculate delay for a given attempt number.
   * Uses custom computeDelay if provided, otherwise exponential backoff.
   */
  def delayForAttempt(attempt: Int): FiniteDuration =
    computeDelay match
      case Some(f) => f(attempt, initialBackoff, maxBackoff)
      case None =>
        // Exponential backoff: initialBackoff * multiplier^(attempt-1)
        val baseDelayMs = (initialBackoff.toMillis * math.pow(backoffMultiplier, attempt - 1)).toLong
        val cappedMs = math.min(baseDelayMs, maxBackoff.toMillis)
        FiniteDuration(cappedMs, MILLISECONDS)

  /**
   * Apply jitter to a delay.
   */
  def applyJitter(delay: FiniteDuration): FiniteDuration =
    if jitterFactor <= 0 then delay
    else
      val jitter = (delay.toMillis * jitterFactor * (math.random() - 0.5)).toLong
      FiniteDuration(math.max(0, delay.toMillis + jitter), MILLISECONDS)

object RetryPolicy:
  /** Default policy - 3 attempts with exponential backoff */
  val default: RetryPolicy = RetryPolicy()

  /** No retries - fail immediately on error */
  val noRetry: RetryPolicy = RetryPolicy(maxAttempts = 1)

  /** Default recoverability check - most exceptions are recoverable */
  def defaultIsRecoverable(e: Throwable): Boolean =
    // Unwrap ExecutionException to check the underlying cause
    val cause = e match
      case ee: java.util.concurrent.ExecutionException if ee.getCause != null => ee.getCause
      case other => other
    cause match
      case _: InterruptedException => false
      case _: VirtualMachineError => false
      case _: LinkageError => false
      case _: NonRecoverableException => false
      case _ => true


/**
 * Event emitted during retry attempts for logging/monitoring.
 *
 * @param workflowId The workflow instance ID
 * @param activityIndex The activity index within the workflow
 * @param attempt Current attempt number (1-based)
 * @param maxAttempts Maximum attempts configured
 * @param error The error that triggered the retry
 * @param nextDelayMs Delay before next attempt (None if this was final attempt)
 * @param willRetry Whether another retry will be attempted
 */
case class RetryEvent(
  workflowId: WorkflowId,
  activityIndex: Int,
  attempt: Int,
  maxAttempts: Int,
  error: Throwable,
  nextDelayMs: Option[Long],
  willRetry: Boolean
)

/**
 * Exception thrown when all retry attempts are exhausted.
 *
 * @param workflowId The workflow instance ID
 * @param activityIndex The activity index within the workflow
 * @param attempts Number of attempts made
 * @param maxAttempts Maximum attempts configured
 * @param history List of retry events (chronological order)
 * @param cause The last error that caused the final failure
 */
class MaxRetriesExceededException(
  val workflowId: WorkflowId,
  val activityIndex: Int,
  val attempts: Int,
  val maxAttempts: Int,
  val history: List[RetryEvent],
  cause: Throwable
) extends Exception(
  s"Max retries ($attempts/$maxAttempts) exceeded for activity $activityIndex in workflow ${workflowId.value}",
  cause
) with NonRecoverableException

/** Logger callback for retry events */
type RetryLogger = RetryEvent => Unit

object RetryLogger:
  /** No-op logger - discards all events */
  val noop: RetryLogger = _ => ()

  /** Simple console logger for development */
  val console: RetryLogger = event =>
    if event.willRetry then
      println(s"[RETRY] workflow=${event.workflowId.value} activity=${event.activityIndex} " +
              s"attempt=${event.attempt}/${event.maxAttempts} " +
              s"error=${event.error.getClass.getSimpleName}: ${event.error.getMessage} " +
              s"nextDelay=${event.nextDelayMs.getOrElse(0)}ms")
    else
      println(s"[FAILED] workflow=${event.workflowId.value} activity=${event.activityIndex} " +
              s"attempt=${event.attempt}/${event.maxAttempts} " +
              s"error=${event.error.getClass.getSimpleName}: ${event.error.getMessage}")
