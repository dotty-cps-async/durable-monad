package durable

import java.time.Instant

/**
 * Conditions that a workflow can wait for.
 * Type parameter A is the result type when the condition is satisfied.
 */
enum WaitCondition[+A]:
  /** Wait for a broadcast event of type E */
  case Event[E](eventName: String) extends WaitCondition[E]

  /** Wait until a specific instant, returns actual wake time */
  case Timer(wakeAt: Instant) extends WaitCondition[Instant]

  /** Wait for a child workflow to complete, returns its result directly */
  case ChildWorkflow[B](childId: WorkflowId, resultType: String) extends WaitCondition[B]
