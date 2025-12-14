package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}
import munit.FunSuite
import cps.*

import durable.runtime.Scheduler

/**
 * Tests for failure storage and replay functionality.
 */
class FailureReplayTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  // Use immediate scheduler for fast tests
  val testConfig = RunConfig(scheduler = Scheduler.immediate)

  test("failure is stored in cache") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val workflow = Durable.activity(
      Future.failed(RuntimeException("test error")),
      RetryPolicy.noRetry
    )

    val ctx = RunContext.fresh(WorkflowId("failure-store-1"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      // Workflow should fail
      result match
        case WorkflowResult.Failed(e) =>
          assertEquals(e.getMessage, "test error")
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    // Pre-populate cache with a failure
    val storedFailure = StoredFailure("java.lang.RuntimeException", "replayed error")
    backing.put(WorkflowId("failure-replay-1"), 0, Left(storedFailure))

    val workflow = Durable.activity(
      Future.successful(999), // Won't be executed
      RetryPolicy.noRetry
    )

    // Replay from index 1 (activity at index 0 should be replayed from cache)
    val ctx = RunContext(WorkflowId("failure-replay-1"), resumeFromIndex = 1, testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowResult.Failed(e) =>
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

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
    val ctx = RunContext(WorkflowId("failure-catch-1"), resumeFromIndex = 1, testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(-1))
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

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
    val ctx1 = RunContext.fresh(WorkflowId("catch-test"), testConfig)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(-1))  // Caught original

      shouldFail = false  // Won't matter - we're replaying from cache

      // Replay - should catch ReplayedException (transformed catch pattern matches it)
      val ctx2 = RunContext(WorkflowId("catch-test"), 1, testConfig)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(-1))  // Caught replayed
      }
    }
  }

  test("catch handler using exception is cached and replayed correctly") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    var shouldFail = true
    var capturedMessage: String = ""

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
          capturedMessage = msg
          msg.length
      }
    }

    // First run - will fail and catch original exception
    val ctx1 = RunContext.fresh(WorkflowId("handler-test"), testConfig)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(14))  // "original error".length = 14
      assertEquals(capturedMessage, "original error")

      capturedMessage = ""  // Reset
      shouldFail = false  // Won't matter - replaying

      // Replay - e is ReplayedException but handler activity is cached
      val ctx2 = RunContext(WorkflowId("handler-test"), 2, testConfig)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(14))  // Same result - cached!
        // Note: capturedMessage might be different on replay since e.getMessage
        // on ReplayedException returns "java.lang.RuntimeException: original error"
        // BUT the activity result (msg.length) is cached, so we get 14
      }
    }
  }

  test("failure and success in sequence - failure is replayed correctly") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    // Pre-populate: index 0 = success, index 1 = failure
    backing.put(WorkflowId("seq-1"), 0, Right(10))
    backing.put(WorkflowId("seq-1"), 1, Left(StoredFailure("java.lang.RuntimeException", "second failed")))

    // Workflow that does two activities
    val workflow = for
      a <- Durable.activity(Future.successful(10))
      b <- Durable.activity(Future.successful(20))
    yield a + b

    // Replay from index 2 (both should be replayed)
    val ctx = RunContext(WorkflowId("seq-1"), resumeFromIndex = 2, testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      // Second activity's failure should propagate
      result match
        case WorkflowResult.Failed(e: ReplayedException) =>
          assertEquals(e.originalClassName, "java.lang.RuntimeException")
          assertEquals(e.originalMessage, "second failed")
        case other =>
          fail(s"Expected Failed with ReplayedException, got $other")
    }
  }
