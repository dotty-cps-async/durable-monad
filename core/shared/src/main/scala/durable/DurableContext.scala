package durable

import scala.concurrent.{Future, ExecutionContext}
import cps.*

/**
 * Execution context for durable computations (direct Future-based execution).
 *
 * Tracks activity index, manages replay from cached results.
 * This is an alternative to the Free monad approach (Durable ADT + WorkflowRunner).
 *
 * Type parameter S is the storage type.
 */
class DurableContext[S](
  val storage: S,
  val workflowId: WorkflowId,
  private var activityIndex: Int,
  private val resumeFromIndex: Int
)(using ec: ExecutionContext):

  /** Expose ExecutionContext for Durable operations */
  def executionContext: ExecutionContext = ec

  /** Current activity index */
  def currentIndex: Int = activityIndex

  /** Whether we're replaying cached results */
  def isReplaying: Boolean = activityIndex < resumeFromIndex

  /** Whether this is a resumed execution (vs fresh start) */
  def isResumed: Boolean = resumeFromIndex > 0

  /**
   * Cache a value computation.
   * During replay: returns cached result.
   * During live execution: computes, caches, and returns.
   */
  def cached[T](compute: => T)(using cache: DurableCacheBackend[T, S]): Future[T] =
    val index = activityIndex
    activityIndex += 1

    if index < resumeFromIndex then
      // Replaying - retrieve cached result
      cache.retrieve(storage, workflowId, index).map {
        case Some(v) => v
        case None => throw RuntimeException(s"Missing cached result for workflow=$workflowId index=$index during replay")
      }
    else
      // Live execution - compute and cache
      val result = compute
      cache.store(storage, workflowId, index, result).map(_ => result)

  /**
   * Create a snapshot for persistence.
   */
  def toSnapshot: DurableSnapshot =
    DurableSnapshot(workflowId, activityIndex)

object DurableContext:
  /** Create a fresh context for new workflow execution */
  def fresh[S](storage: S, workflowId: WorkflowId)(using ExecutionContext): DurableContext[S] =
    new DurableContext[S](
      storage = storage,
      workflowId = workflowId,
      activityIndex = 0,
      resumeFromIndex = 0
    )

  /** Create a context for resuming from snapshot */
  def fromSnapshot[S](storage: S, snapshot: DurableSnapshot)(using ExecutionContext): DurableContext[S] =
    new DurableContext[S](
      storage = storage,
      workflowId = snapshot.workflowId,
      activityIndex = 0,
      resumeFromIndex = snapshot.activityIndex
    )

/**
 * Serializable snapshot of workflow execution state.
 *
 * @param workflowId Unique identifier for the workflow instance
 * @param activityIndex Current activity index (for replay)
 */
case class DurableSnapshot(
  workflowId: WorkflowId,
  activityIndex: Int
)
