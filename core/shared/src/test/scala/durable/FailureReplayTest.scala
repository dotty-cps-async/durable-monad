package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}
import munit.FunSuite
import cps.*

import durable.engine.{ConfigSource, WorkflowSessionRunner, WorkflowSessionResult}
import durable.runtime.Scheduler

/**
 * Tests for failure storage and replay functionality.
 */
class FailureReplayTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  // Use immediate scheduler for fast tests
  val testConfig = WorkflowSessionRunner.RunConfig(scheduler = Scheduler.immediate)

  test("failure is stored in cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = Durable.activity(
      Future.failed(RuntimeException("test error")),
      RetryPolicy.noRetry
    )

    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("failure-store-1"), testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      // Workflow should fail
      result match
        case WorkflowSessionResult.Failed(_, e) =>
          assertEquals(e.originalMessage, "test error")
        case other =>
          fail(s"Expected Failed, got $other")

      // Check that failure was stored
      val stored = backing.get(WorkflowId("failure-store-1"), 0)
      assert(stored.isDefined, "Failure should be stored")
      stored.get match
        case Left(sf) =>
          assertEquals(sf.className, "java.lang.RuntimeException")
          assertEquals(sf.message, "test error")
        case Right(_) =>
          fail("Expected Left(StoredFailure), got Right")
    }
  }

  test("replayed failure becomes ReplayedException") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Pre-populate cache with a failure
    val storedFailure = StoredFailure("java.lang.RuntimeException", "replayed error")
    backing.put(WorkflowId("failure-replay-1"), 0, Left(storedFailure))

    val workflow = Durable.activity(
      Future.successful(999), // Won't be executed
      RetryPolicy.noRetry
    )

    // Replay from index 1 (activity at index 0 should be replayed from cache)
    val ctx = WorkflowSessionRunner.RunContext.resume(WorkflowId("failure-replay-1"), 1, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Failed(_, e) =>
          assert(e.isInstanceOf[ReplayedException], s"Expected ReplayedException, got ${e.getClass}")
          val re = e.asInstanceOf[ReplayedException]
          assertEquals(re.originalClassName, "java.lang.RuntimeException")
          assertEquals(re.originalMessage, "replayed error")
        case other =>
          fail(s"Expected Failed with ReplayedException, got $other")
    }
  }

  test("ReplayedException.Of extractor matches correctly") {
    val stored = StoredFailure("java.lang.RuntimeException", "test")
    val re = ReplayedException(stored)

    // Should match RuntimeException
    val runtimeMatcher = ReplayedException.Of[RuntimeException]
    assert(runtimeMatcher.unapply(re).isDefined, "Should match RuntimeException")

    // Should not match IOException
    val ioMatcher = ReplayedException.Of[java.io.IOException]
    assert(ioMatcher.unapply(re).isEmpty, "Should not match IOException")

    // Original exception should not match
    val original = RuntimeException("original")
    assert(runtimeMatcher.unapply(original).isEmpty, "Original exception should not match extractor")
  }

  test("ReplayedException.matches helper works") {
    val stored = StoredFailure("java.lang.RuntimeException", "test")
    val re = ReplayedException(stored)

    assert(ReplayedException.matches[RuntimeException](re), "Should match RuntimeException")
    assert(!ReplayedException.matches[java.io.IOException](re), "Should not match IOException")

    // Also works with original exceptions
    val original = RuntimeException("original")
    assert(ReplayedException.matches[RuntimeException](original), "Should match original RuntimeException")
    assert(!ReplayedException.matches[java.io.IOException](original), "Should not match original as IOException")
  }

  test("failure replay with manual catch handling") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Pre-populate cache with a failure
    val storedFailure = StoredFailure("java.lang.RuntimeException", "handled error")
    backing.put(WorkflowId("failure-catch-1"), 0, Left(storedFailure))

    // Create workflow with FlatMapTry to catch the error
    // This simulates what the preprocessor would generate
    val inner = Durable.activity(Future.successful(999), RetryPolicy.noRetry)
    val workflow = Durable.FlatMapTry[Int, Int](inner, {
      case Success(v) => Durable.pure(v)
      case Failure(e: RuntimeException) => Durable.pure(-1)
      case Failure(e: ReplayedException) if e.originalClassName == "java.lang.RuntimeException" => Durable.pure(-1)
      case Failure(e) => Durable.failed(e)
    })

    // Replay from index 1
    val ctx = WorkflowSessionRunner.RunContext.resume(WorkflowId("failure-catch-1"), 1, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, -1))
    }
  }

  test("StoredFailure.fromThrowable captures exception info") {
    val original = RuntimeException("test message")
    val stored = StoredFailure.fromThrowable(original)

    assertEquals(stored.className, "java.lang.RuntimeException")
    assertEquals(stored.message, "test message")
    assert(stored.stackTrace.isDefined, "Stack trace should be captured")
  }

  test("StoredFailure handles null message") {
    val original = RuntimeException(null: String)
    val stored = StoredFailure.fromThrowable(original)

    assertEquals(stored.className, "java.lang.RuntimeException")
    assertEquals(stored.message, "")
  }

  test("preprocessor transforms catch to handle ReplayedException") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var shouldFail = true
    val workflow = async[Durable] {
      try {
        // This if expression gets wrapped as activity by preprocessor
        val x = if (shouldFail) throw RuntimeException("error") else 42
        x + 1
      } catch {
        case e: RuntimeException => -1  // Should match ReplayedException too!
      }
    }

    // First run - will fail and store failure, catch handles original exception
    val workflowId = WorkflowId("catch-test")
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, -1))  // Caught original

      shouldFail = false  // Won't matter - we're replaying from cache

      // Replay - should catch ReplayedException (transformed catch pattern matches it)
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, testConfig, ConfigSource.empty)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, -1))  // Caught replayed
      }
    }
  }

  test("catch handler using exception is cached and replayed correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var shouldFail = true
    var capturedMessage: String = ""

    // Side-effect method to capture the message (avoids var assignment in async block)
    def capture(msg: String): Unit = capturedMessage = msg

    val workflow = async[Durable] {
      try {
        // Activity at index 0 - will fail on first run
        val x = if (shouldFail) throw RuntimeException("original error") else 42
        x + 1
      } catch {
        case e: RuntimeException =>
          // Activity at index 1 - uses e.getMessage
          // On first run: e is RuntimeException, getMessage returns "original error"
          // On replay: e is ReplayedException, but this activity result is cached!
          val msg = e.getMessage
          capture(msg)
          msg.length
      }
    }

    // First run - will fail and catch original exception
    val workflowId = WorkflowId("handler-test")
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 14))  // "original error".length = 14
      assertEquals(capturedMessage, "original error")

      capturedMessage = ""  // Reset
      shouldFail = false  // Won't matter - replaying

      // Replay - e is ReplayedException but handler activity is cached
      // Resume from index 4 (all activities: 0=condition, 1=throw, 2=getMessage, 3=capture)
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 4, testConfig, ConfigSource.empty)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 14))  // Same result - cached!
        // Note: capturedMessage might be different on replay since e.getMessage
        // on ReplayedException returns "java.lang.RuntimeException: original error"
        // BUT the activity result (msg.length) is cached, so we get 14
      }
    }
  }

  test("failure and success in sequence - failure is replayed correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Pre-populate: index 0 = success, index 1 = failure
    backing.put(WorkflowId("seq-1"), 0, Right(10))
    backing.put(WorkflowId("seq-1"), 1, Left(StoredFailure("java.lang.RuntimeException", "second failed")))

    // Workflow that does two activities
    val workflow = for
      a <- Durable.activity(Future.successful(10))
      b <- Durable.activity(Future.successful(20))
    yield a + b

    // Replay from index 2 (both should be replayed)
    val ctx = WorkflowSessionRunner.RunContext.resume(WorkflowId("seq-1"), 2, testConfig, ConfigSource.empty)
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      // Second activity's failure should propagate
      result match
        case WorkflowSessionResult.Failed(_, e: ReplayedException) =>
          assertEquals(e.originalClassName, "java.lang.RuntimeException")
          assertEquals(e.originalMessage, "second failed")
        case other =>
          fail(s"Expected Failed with ReplayedException, got $other")
    }
  }
