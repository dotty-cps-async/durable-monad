package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*
import munit.FunSuite

import durable.engine.ConfigSource
import durable.runtime.Scheduler

/**
 * Tests for activity retry functionality.
 */
class ActivityRetryTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  // Use immediate scheduler for fast tests
  val testConfig = WorkflowSessionRunner.RunConfig(scheduler = Scheduler.immediate)

  test("activity succeeds on first attempt - no retry needed") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val workflow = Durable.activity {
      attemptCount += 1
      Future.successful(42)
    }

    val workflowId = WorkflowId("retry-test-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(attemptCount, 1)
    }
  }

  test("activity succeeds after transient failures") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        if attemptCount < 3 then
          Future.failed(RuntimeException(s"Transient failure $attemptCount"))
        else
          Future.successful(42)
      },
      RetryPolicy(maxAttempts = 5)
    )

    val workflowId = WorkflowId("retry-test-2")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(attemptCount, 3)
    }
  }

  test("activity fails after max retries exhausted") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(RuntimeException(s"Always fails $attemptCount"))
      },
      RetryPolicy(maxAttempts = 3)
    )

    val workflowId = WorkflowId("retry-test-3")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Failed])
      assertEquals(attemptCount, 3)
    }
  }

  test("defaultIsRecoverable function works correctly") {
    // Verify the function works as expected
    assert(RetryPolicy.defaultIsRecoverable(RuntimeException("test")) == true)
    assert(RetryPolicy.defaultIsRecoverable(InterruptedException("test")) == false)
    assert(RetryPolicy.defaultIsRecoverable(new VirtualMachineError("test") {}) == false)
    // Also verify it unwraps ExecutionException
    val wrapped = new java.util.concurrent.ExecutionException(InterruptedException("wrapped"))
    assert(RetryPolicy.defaultIsRecoverable(wrapped) == false)
  }

  test("non-recoverable error fails immediately without retry") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val events = scala.collection.mutable.ListBuffer[RetryEvent]()

    // Explicitly pass isRecoverable to ensure it's used
    val policy = RetryPolicy(
      maxAttempts = 5,
      isRecoverable = RetryPolicy.defaultIsRecoverable
    )

    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(InterruptedException("Non-recoverable"))
      },
      policy
    )

    val config = WorkflowSessionRunner.RunConfig(
      retryLogger = event => events += event,
      scheduler = Scheduler.immediate
    )
    val workflowId = WorkflowId("retry-test-4")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, config, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Failed])
      assertEquals(attemptCount, 1) // Only one attempt - no retries for non-recoverable
      assertEquals(events.size, 1) // One event logged
      assertEquals(events.head.willRetry, false) // Should not retry
    }
  }

  test("custom isRecoverable predicate is respected") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    // Only retry IllegalArgumentException
    val policy = RetryPolicy(
      maxAttempts = 5,
      isRecoverable = {
        case _: IllegalArgumentException => true
        case _ => false
      }
    )

    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(IllegalStateException("Not recoverable by our policy"))
      },
      policy
    )

    val workflowId = WorkflowId("retry-test-5")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Failed])
      assertEquals(attemptCount, 1) // No retry - IllegalStateException not in recoverable list
    }
  }

  test("retry logger receives events") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val events = scala.collection.mutable.ListBuffer[RetryEvent]()
    val logger: RetryLogger = event => events += event

    val config = WorkflowSessionRunner.RunConfig(
      retryLogger = logger,
      scheduler = Scheduler.immediate
    )

    val workflow = Durable.activity(
      {
        attemptCount += 1
        if attemptCount < 2 then
          Future.failed(RuntimeException("Fail once"))
        else
          Future.successful(42)
      },
      RetryPolicy(maxAttempts = 3)
    )

    val workflowId = WorkflowId("retry-test-6")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, config, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(events.size, 1)
      assertEquals(events.head.attempt, 1)
      assertEquals(events.head.willRetry, true)
      assert(events.head.nextDelayMs.isDefined)
    }
  }

  test("replay skips retry logic") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Pre-populate cache
    backing.put(WorkflowId("retry-test-7"), 0, Right(42))

    var attemptCount = 0
    val workflow: Durable[Int] = Durable.activity(
      {
        attemptCount += 1
        Future.failed[Int](RuntimeException("Should not be called during replay"))
      },
      RetryPolicy(maxAttempts = 3)
    )

    // Resume from index 1 (activity at index 0 is cached)
    val workflowId = WorkflowId("retry-test-7")
    val ctx = WorkflowSessionRunner.RunContext.resume(workflowId, 1, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) => assertEquals(value, 42)
        case other => fail(s"Expected Completed, got $other")
      assertEquals(attemptCount, 0) // No execution during replay
    }
  }

  test("RetryPolicy.noRetry fails immediately") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(RuntimeException("Fail"))
      },
      RetryPolicy.noRetry
    )

    val workflowId = WorkflowId("retry-test-8")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Failed])
      assertEquals(attemptCount, 1) // Only one attempt with noRetry
    }
  }

  test("exponential backoff calculates correct delays") {
    val policy = RetryPolicy(
      initialBackoff = 100.millis,
      backoffMultiplier = 2.0,
      maxBackoff = 10.seconds,
      jitterFactor = 0.0 // No jitter for deterministic test
    )

    assertEquals(policy.delayForAttempt(1).toMillis, 100L)
    assertEquals(policy.delayForAttempt(2).toMillis, 200L)
    assertEquals(policy.delayForAttempt(3).toMillis, 400L)
    assertEquals(policy.delayForAttempt(4).toMillis, 800L)
  }

  test("backoff respects maxBackoff") {
    val policy = RetryPolicy(
      initialBackoff = 1.second,
      backoffMultiplier = 10.0,
      maxBackoff = 5.seconds,
      jitterFactor = 0.0
    )

    assertEquals(policy.delayForAttempt(1).toMillis, 1000L)
    assertEquals(policy.delayForAttempt(2).toMillis, 5000L) // Capped at maxBackoff
    assertEquals(policy.delayForAttempt(3).toMillis, 5000L) // Still capped
  }

  test("custom computeDelay function is used") {
    val policy = RetryPolicy(
      computeDelay = Some((attempt, initial, max) =>
        // Linear backoff: attempt * initial
        val delay = initial * attempt
        if delay > max then max else delay
      )
    )

    assertEquals(policy.delayForAttempt(1), 100.millis)
    assertEquals(policy.delayForAttempt(2), 200.millis)
    assertEquals(policy.delayForAttempt(3), 300.millis)
  }
