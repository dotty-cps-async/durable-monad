package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import scala.util.control.NonFatal

/**
 * Interpreter for Durable Free Monad.
 *
 * Runs a workflow step by step, handling:
 *   - Pure values
 *   - FlatMap sequencing
 *   - Errors
 *   - Local computations (sync, no cache)
 *   - Activity (async, cached with runtime index assignment)
 *   - Suspend (external calls)
 *
 * Activity indices are assigned at runtime as the interpreter
 * encounters Activity nodes. This ensures deterministic
 * replay: same execution path = same indices.
 *
 * Each Activity captures its own storage and backend, so the runner
 * doesn't need storage - it only needs workflowId and resumeFromIndex.
 */
object WorkflowRunner:

  /**
   * Run a workflow to completion or suspension.
   *
   * @param workflow The Durable workflow to run
   * @param ctx The execution context (workflowId, replay state)
   * @return WorkflowResult - Completed, Suspended, or Failed
   */
  def run[A](
    workflow: Durable[A],
    ctx: RunContext
  )(using ec: ExecutionContext): Future[WorkflowResult[A]] =
    val state = new InterpreterState(ctx.resumeFromIndex)
    step(workflow, ctx, state, Nil)

  /**
   * Mutable interpreter state for tracking activity index at runtime.
   */
  private class InterpreterState(val resumeFromIndex: Int):
    private var _currentIndex: Int = 0

    /** Get current index and increment for next activity */
    def nextIndex(): Int =
      val idx = _currentIndex
      _currentIndex += 1
      idx

    /** Current index (for snapshot) */
    def currentIndex: Int = _currentIndex

    /** Are we replaying at this index? */
    def isReplayingAt(index: Int): Boolean =
      index < resumeFromIndex

  /**
   * Execute one step of the workflow.
   *
   * @param current Current Durable node to interpret
   * @param ctx Execution context
   * @param state Interpreter state (tracks activity index)
   * @param stack Continuation stack (for FlatMap)
   */
  private def step[A](
    current: Durable[?],
    ctx: RunContext,
    state: InterpreterState,
    stack: List[Any => Durable[?]]
  )(using ec: ExecutionContext): Future[WorkflowResult[A]] =
    current match
      case Durable.Pure(value) =>
        continueWith(value, ctx, state, stack)

      case Durable.FlatMap(fa, f) =>
        step(fa, ctx, state, f.asInstanceOf[Any => Durable[?]] :: stack)

      case Durable.Error(error) =>
        Future.successful(WorkflowResult.Failed(error))

      case Durable.LocalComputation(compute) =>
        handleLocalComputation(compute.asInstanceOf[RunContext => Any], ctx, state, stack)

      case Durable.Activity(compute, backend, storage) =>
        // backend and storage have the same existential S, so types match
        handleActivity(
          compute,
          backend.asInstanceOf[DurableCacheBackend[Any, Any]],
          storage,
          ctx,
          state,
          stack
        )

      case Durable.Suspend(waitingFor) =>
        Future.successful(WorkflowResult.Suspended(
          DurableSnapshot(ctx.workflowId, state.currentIndex),
          waitingFor
        ))

  /**
   * Continue with a value, applying the next continuation from the stack.
   */
  private def continueWith[A](
    value: Any,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[Any => Durable[?]]
  )(using ec: ExecutionContext): Future[WorkflowResult[A]] =
    stack match
      case Nil =>
        Future.successful(WorkflowResult.Completed(value.asInstanceOf[A]))
      case f :: rest =>
        try
          val next = f(value)
          step(next, ctx, state, rest)
        catch
          case NonFatal(e) =>
            Future.successful(WorkflowResult.Failed(e))

  /**
   * Handle local computation - just execute, no caching.
   */
  private def handleLocalComputation[A](
    compute: RunContext => Any,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[Any => Durable[?]]
  )(using ec: ExecutionContext): Future[WorkflowResult[A]] =
    try
      val result = compute(ctx)
      continueWith(result, ctx, state, stack)
    catch
      case NonFatal(e) =>
        Future.successful(WorkflowResult.Failed(e))

  /**
   * Handle Activity - assign index, check cache, execute if needed, cache result.
   * Uses the backend and storage captured in the Activity node.
   * The existential S ties backend and storage together - type-safe operations.
   */
  private def handleActivity[A](
    compute: () => Future[Any],
    backend: DurableCacheBackend[Any, Any],
    storage: Any,
    ctx: RunContext,
    state: InterpreterState,
    stack: List[Any => Durable[?]]
  )(using ec: ExecutionContext): Future[WorkflowResult[A]] =
    // Assign index at runtime
    val index = state.nextIndex()

    if state.isReplayingAt(index) then
      // Replaying - retrieve from cache
      backend.retrieve(storage, ctx.workflowId, index).transformWith {
        case Success(Some(cached)) =>
          continueWith(cached, ctx, state, stack)
        case Success(None) =>
          Future.successful(WorkflowResult.Failed(
            RuntimeException(s"Missing cached result for activity at index=$index during replay")
          ))
        case Failure(e) =>
          Future.successful(WorkflowResult.Failed(e))
      }
    else
      // Execute activity, cache result, continue
      compute().transformWith {
        case Success(result) =>
          backend.store(storage, ctx.workflowId, index, result).transformWith {
            case Success(_) =>
              continueWith(result, ctx, state, stack)
            case Failure(e) =>
              Future.successful(WorkflowResult.Failed(e))
          }
        case Failure(e) =>
          Future.successful(WorkflowResult.Failed(e))
      }


/**
 * Execution context for the workflow runner.
 *
 * Storage is no longer needed here - each Activity captures its own storage.
 *
 * @param workflowId Unique identifier for this workflow instance
 * @param resumeFromIndex Activity index to resume from (0 = fresh start)
 */
case class RunContext(
  workflowId: WorkflowId,
  resumeFromIndex: Int
)

object RunContext:
  /** Create a fresh context for new workflow execution */
  def fresh(workflowId: WorkflowId): RunContext =
    RunContext(workflowId, 0)

  /** Create a context for resuming from snapshot */
  def fromSnapshot(snapshot: DurableSnapshot): RunContext =
    RunContext(snapshot.workflowId, snapshot.activityIndex)


/**
 * Snapshot of workflow state for suspension and resumption.
 *
 * @param workflowId Unique identifier for the workflow instance
 * @param activityIndex The activity index to resume from
 */
case class DurableSnapshot(
  workflowId: WorkflowId,
  activityIndex: Int
)
