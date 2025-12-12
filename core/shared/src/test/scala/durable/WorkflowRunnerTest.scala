package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

/**
 * Tests for WorkflowRunner - the interpreter for Durable Free Monad.
 * Uses async tests (returning Future) for cross-platform compatibility.
 */
class WorkflowRunnerTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryStorage.memoryDurableCacheBackend

  // Provide storage via given (AppContext pattern)
  // Each test creates its own storage instance for isolation
  def withStorage[A](f: MemoryStorage ?=> A): A =
    given MemoryStorage = MemoryStorage()
    f

  test("run pure value") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-1"))

      val workflow = Durable.pure[Int](42)
      WorkflowRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowResult.Completed(42))
      }
    }
  }

  test("run map") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-2"))

      val workflow = Durable.pure[Int](21).map(_ * 2)
      WorkflowRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowResult.Completed(42))
      }
    }
  }

  test("run flatMap") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-3"))

      val workflow = for
        a <- Durable.pure[Int](10)
        b <- Durable.pure[Int](32)
      yield a + b

      WorkflowRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowResult.Completed(42))
      }
    }
  }

  test("run local computation") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-4"))

      var computed = false
      val workflow = Durable.local[Int] { _ =>
        computed = true
        42
      }

      assertEquals(computed, false) // not computed yet

      WorkflowRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowResult.Completed(42))
        assertEquals(computed, true) // now computed
      }
    }
  }

  test("run activity - executes and caches") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("test-5"))

    var executeCount = 0
    val workflow = Durable.activity[Int, MemoryStorage] {
      executeCount += 1
      Future.successful(42)
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(executeCount, 1)
      assertEquals(storage.size, 1)
    }
  }

  test("run activity - replays from cache") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    // Pre-populate cache at index 0
    storage.put(WorkflowId("test-6"), 0, 42)

    // Resume from index 1 (after index 0 is cached)
    val ctx = RunContext(WorkflowId("test-6"), resumeFromIndex = 1)

    var executeCount = 0
    val workflow = Durable.activity[Int, MemoryStorage] {
      executeCount += 1
      Future.successful(999) // different value - should NOT be used
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42)) // cached value
      assertEquals(executeCount, 0) // NOT executed
    }
  }

  test("run suspend") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-7"))

      val workflow = for
        a <- Durable.pure[Int](10)
        _ <- Durable.suspend[Unit]("waiting for signal")
        b <- Durable.pure[Int](32)
      yield a + b

      WorkflowRunner.run(workflow, ctx).map { result =>
        assert(result.isInstanceOf[WorkflowResult.Suspended[?]])
        val suspended = result.asInstanceOf[WorkflowResult.Suspended[Int]]
        assertEquals(suspended.waitingFor, "waiting for signal")
      }
    }
  }

  test("run error") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-8"))

      val workflow = for
        a <- Durable.pure[Int](10)
        _ <- Durable.failed[Int](RuntimeException("test error"))
        b <- Durable.pure[Int](32)
      yield a + b

      WorkflowRunner.run(workflow, ctx).map { result =>
        assert(result.isInstanceOf[WorkflowResult.Failed[?]])
        val failed = result.asInstanceOf[WorkflowResult.Failed[Int]]
        assertEquals(failed.error.getMessage, "test error")
      }
    }
  }

  test("run multiple activities in sequence") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("test-9"))

    var executeCount = 0
    val workflow = for
      a <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(10) }
      b <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(20) }
      c <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(executeCount, 3)
      assertEquals(storage.size, 3)
    }
  }

  test("run replay from middle") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val workflowId = WorkflowId("test-10")

    // Pre-populate cache with first two values (indices 0 and 1)
    storage.put(workflowId, 0, 10)
    storage.put(workflowId, 1, 20)

    // Resume from index 2 (first two cached)
    val ctx = RunContext(workflowId, resumeFromIndex = 2)

    var executeCount = 0
    val workflow = for
      a <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(10) }
      b <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(20) }
      c <- Durable.activity[Int, MemoryStorage] { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(executeCount, 1) // Only c executed
      assertEquals(storage.size, 3) // All three now cached
    }
  }

  test("local computation has access to context") {
    withStorage {
      val ctx = RunContext.fresh(WorkflowId("test-11"))

      val workflow = Durable.local[WorkflowId] { ctx =>
        ctx.workflowId
      }

      WorkflowRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowResult.Completed(WorkflowId("test-11")))
      }
    }
  }

  test("activity result is cached") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("test-12"))

    var callCount = 0
    val workflow = Durable.activity[Int, MemoryStorage] {
      callCount += 1
      Future.successful(42)
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(callCount, 1)
      assertEquals(storage.size, 1) // Result was cached
    }
  }
