package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*
import munit.FunSuite

import durable.runtime.Scheduler

/**
 * Tests for activity retry functionality.
 */
class ActivityRetryTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  // Use immediate scheduler for fast tests
  val testConfig = RunConfig(scheduler = Scheduler.immediate)

  test("activity succeeds on first attempt - no retry needed") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    var attemptCount = 0
    val workflow = Durable.activity {
      attemptCount += 1
      Future.successful(42)
    }

    val ctx = RunContext.fresh(WorkflowId("retry-test-1"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(attemptCount, 1)
    }
  }

  test("activity succeeds after transient failures") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

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

    val ctx = RunContext.fresh(WorkflowId("retry-test-2"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(attemptCount, 3)
    }
  }

  test("activity fails after max retries exhausted") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(RuntimeException(s"Always fails $attemptCount"))
      },
      RetryPolicy(maxAttempts = 3)
    )

    val ctx = RunContext.fresh(WorkflowId("retry-test-3"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowResult.Failed[?]])
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

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

    val config = RunConfig(
      retryLogger = event => events += event,
      scheduler = Scheduler.immediate
    )
    val ctx = RunContext.fresh(WorkflowId("retry-test-4"), config)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowResult.Failed[?]])
      assertEquals(attemptCount, 1) // Only one attempt - no retries for non-recoverable
      assertEquals(events.size, 1) // One event logged
      assertEquals(events.head.willRetry, false) // Should not retry
    }
  }

  test("custom isRecoverable predicate is respected") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

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

    val ctx = RunContext.fresh(WorkflowId("retry-test-5"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowResult.Failed[?]])
      assertEquals(attemptCount, 1) // No retry - IllegalStateException not in recoverable list
    }
  }

  test("retry logger receives events") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    var attemptCount = 0
    val events = scala.collection.mutable.ListBuffer[RetryEvent]()
    val logger: RetryLogger = event => events += event

    val config = RunConfig(
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

    val ctx = RunContext.fresh(WorkflowId("retry-test-6"), config)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(events.size, 1)
      assertEquals(events.head.attempt, 1)
      assertEquals(events.head.willRetry, true)
      assert(events.head.nextDelayMs.isDefined)
    }
  }

  test("replay skips retry logic") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    // Pre-populate cache
    backing.put(WorkflowId("retry-test-7"), 0, Right(42))

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(RuntimeException("Should not be called during replay"))
      },
      RetryPolicy(maxAttempts = 3)
    )

    // Resume from index 1 (activity at index 0 is cached)
    val ctx = RunContext.resume(WorkflowId("retry-test-7"), 1, testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(attemptCount, 0) // No execution during replay
    }
  }

  test("RetryPolicy.noRetry fails immediately") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    var attemptCount = 0
    val workflow = Durable.activity(
      {
        attemptCount += 1
        Future.failed(RuntimeException("Fail"))
      },
      RetryPolicy.noRetry
    )

    val ctx = RunContext.fresh(WorkflowId("retry-test-8"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowResult.Failed[?]])
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
