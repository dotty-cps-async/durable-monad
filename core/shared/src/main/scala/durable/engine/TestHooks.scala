package durable.engine

import scala.concurrent.Future

/**
 * Injectable hooks for testing race conditions in WorkflowEngine.
 *
 * Default implementations are no-ops, so production code has zero overhead.
 * Test code can override to inject barriers, verify invariants, or force
 * specific thread interleavings.
 *
 * Yield points are named strings identifying locations in the code:
 * - "sendEvent.afterSavePending" - after saving pending event
 * - "sendEvent.afterCheckWaiting" - after checking for waiting workflows
 * - "handleSuspended.afterRegister" - after registering as waiting
 * - "handleSuspended.afterCheckPending" - after checking pending events
 */
trait TestHooks:
  /**
   * Called at yield points in the code.
   * Tests can inject CountDownLatch barriers to pause execution.
   */
  def yieldPoint(name: String): Future[Unit] = Future.successful(())

  /**
   * Called to check invariants at critical points.
   * Should throw/fail if invariant is violated.
   */
  def checkInvariant(): Future[Unit] = Future.successful(())

object TestHooks:
  /**
   * Default no-op hooks for production use.
   */
  val NoOp: TestHooks = new TestHooks {}
