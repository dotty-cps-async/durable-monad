package durable

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Success, Failure}
import munit.FunSuite
import cps.*

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Tests for async activities - operations returning Future[T] that:
 * - Return immediately (parallel execution)
 * - Cache the resolved result T when complete
 * - Replay from cache on subsequent runs
 *
 * Note: We test the async activity mechanism by returning the Future directly
 * from the workflow, then flatMapping on it to verify the result.
 */
class AsyncActivityTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given
  private val runner = WorkflowSessionRunner.forFuture

  test("async activity caches Future result") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("async-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var callCount = 0
    def asyncCompute(): Future[Int] = {
      callCount += 1
      Future.successful(42)
    }

    // Workflow returns the Future itself
    val workflow = async[Durable] {
      val result: Future[Int] = asyncCompute()
      result
    }

    runner.run(workflow, ctx).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, futureResult) =>
        futureResult.map { value =>
          assertEquals(value, 42)
          assertEquals(callCount, 1)
          assertEquals(backing.size, 1) // Future result was cached
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }

  test("async activity replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("async-2")

    var callCount = 0
    def asyncCompute(): Future[Int] = {
      callCount += 1
      Future.successful(42)
    }

    val workflow = async[Durable] {
      val result: Future[Int] = asyncCompute()
      result
    }

    // First run - computes
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    runner.run(workflow, ctx1).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, futureResult1) =>
        futureResult1.flatMap { value1 =>
          assertEquals(value1, 42)
          assertEquals(callCount, 1)

          // Second run - replay from cache
          callCount = 0
          val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)
          runner.run(workflow, ctx2).map(_.toOption.get).flatMap {
            case WorkflowSessionResult.Completed(_, futureResult2) =>
              futureResult2.map { value2 =>
                assertEquals(value2, 42)
                assertEquals(callCount, 0) // NOT recomputed - from cache
              }
            case other => fail(s"Expected Completed on replay, got $other")
          }
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }

  test("async activity caches failures") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("async-fail")

    var callCount = 0
    val testError = new RuntimeException("test error")

    def failingAsync(): Future[Int] = {
      callCount += 1
      Future.failed(testError)
    }

    val workflow = async[Durable] {
      val result: Future[Int] = failingAsync()
      result
    }

    // First run - fails after retries and caches failure
    // Default RetryPolicy.default has maxAttempts=3
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    runner.run(workflow, ctx1).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, futureResult1) =>
        futureResult1.transformWith {
          case Failure(e: MaxRetriesExceededException) =>
            assertEquals(e.getCause.getMessage, "test error")
            assertEquals(e.attempts, 3)
            assertEquals(e.history.size, 3) // 3 retry events recorded
            assertEquals(callCount, 3) // 3 attempts with default retry policy

            // Second run - replays failure from cache
            callCount = 0
            val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)
            runner.run(workflow, ctx2).map(_.toOption.get).flatMap {
              case WorkflowSessionResult.Completed(_, futureResult2) =>
                futureResult2.transformWith {
                  case Failure(e2: ReplayedException) =>
                    // Stored failure is MaxRetriesExceededException
                    assertEquals(e2.stored.className, "durable.MaxRetriesExceededException")
                    assert(e2.stored.message.contains("Max retries"))
                    assertEquals(callCount, 0) // NOT recomputed - failure from cache
                    Future.successful(())
                  case other =>
                    fail(s"Expected ReplayedException on replay, got $other")
                }
              case other => fail(s"Expected Completed on replay, got $other")
            }
          case Failure(other) =>
            fail(s"Expected MaxRetriesExceededException, got ${other.getClass.getName}: ${other.getMessage}")
          case Success(_) =>
            fail("Expected failure")
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }

  test("non-Future types still use activitySync") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("sync-fallback")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val callCount = TestCounter()

    val workflow = async[Durable] {
      // Option[Int] should use activitySync (no DurableAsyncWrapper[Option])
      val optResult: Option[Int] = { callCount.increment(); Some(42) }
      optResult.getOrElse(0)
    }

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(callCount.get, 1)
      // Both the Unit from increment() and the Option[Int] are cached
      assertEquals(backing.size, 2)
    }
  }

  test("mixed sync and async activities") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("mixed")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val syncCount = TestCounter()
    val asyncCount = TestCounter()

    val workflow = async[Durable] {
      val syncVal = { syncCount.increment(); 10 }
      val asyncVal: Future[Int] = { asyncCount.increment(); Future.successful(20) }
      val anotherSync = { syncCount.increment(); 12 }
      // Return tuple of sync values and the Future
      (syncVal, asyncVal, anotherSync)
    }

    runner.run(workflow, ctx).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, (s1, futureVal, s2)) =>
        futureVal.map { asyncResult =>
          assertEquals(s1 + asyncResult + s2, 42)
          assertEquals(syncCount.get, 2)
          assertEquals(asyncCount.get, 1)
          // 3 val blocks wrapped + 1 tuple expression = 4 activities
          assertEquals(backing.size, 4)
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }

  // === LocalAsync tests - ephemeral types (NOT cached) ===

  // Custom ephemeral type for testing - explicitly marked as non-cacheable
  case class LogEntry(msg: String)
  given DurableEphemeral[LogEntry] = new DurableEphemeralImpl[LogEntry]

  test("localAsync for Future[EphemeralType] is NOT cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("local-async-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var logCount = 0
    def log(msg: String): Future[LogEntry] = {
      logCount += 1
      Future.successful(LogEntry(msg))
    }

    val workflow = async[Durable] {
      // Future[LogEntry] - LogEntry is DurableEphemeral, so this uses LocalAsync
      val _: Future[LogEntry] = log("processing")
      42
    }

    runner.run(workflow, ctx).map(_.toOption.get).map {
      case WorkflowSessionResult.Completed(_, result) =>
        assertEquals(result, 42)
        assertEquals(logCount, 1)
        // LocalAsync does NOT cache - backing is empty
        // (42 at return position is not wrapped)
        assertEquals(backing.size, 0)
      case other => fail(s"Expected Completed, got $other")
    }
  }

  test("localAsync re-executes on replay (not cached)") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("local-async-replay")

    var logCount = 0
    def log(msg: String): Future[LogEntry] = {
      logCount += 1
      Future.successful(LogEntry(msg))
    }

    val workflow = async[Durable] {
      val _: Future[LogEntry] = log("hello")
      42
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    runner.run(workflow, ctx1).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, result1) =>
        assertEquals(result1, 42)
        assertEquals(logCount, 1)

        // Second run - replay
        // Reset counter to verify re-execution
        logCount = 0
        val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)
        runner.run(workflow, ctx2).map(_.toOption.get).map {
          case WorkflowSessionResult.Completed(_, result2) =>
            assertEquals(result2, 42)
            // LocalAsync re-executes on replay - NOT cached
            assertEquals(logCount, 1)
          case other => fail(s"Expected Completed on replay, got $other")
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }

  test("localAsync vs asyncActivity - ephemeral vs storable") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("local-vs-async")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var ephemeralCount = 0
    var storableCount = 0

    def ephemeralOp(): Future[LogEntry] = {
      ephemeralCount += 1
      Future.successful(LogEntry("log"))
    }

    def storableOp(): Future[Int] = {
      storableCount += 1
      Future.successful(42)
    }

    val workflow = async[Durable] {
      // Future[LogEntry] - ephemeral, uses LocalAsync, NOT cached
      val _: Future[LogEntry] = ephemeralOp()
      // Future[Int] - storable, uses AsyncActivity, cached
      val result: Future[Int] = storableOp()
      result
    }

    runner.run(workflow, ctx).map(_.toOption.get).flatMap {
      case WorkflowSessionResult.Completed(_, futureResult) =>
        futureResult.map { value =>
          assertEquals(value, 42)
          assertEquals(ephemeralCount, 1)
          assertEquals(storableCount, 1)
          // Only the Future[Int] result is cached (AsyncActivity), not Future[LogEntry] (LocalAsync)
          assertEquals(backing.size, 1)
        }
      case other => fail(s"Expected Completed, got $other")
    }
  }
