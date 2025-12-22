package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Tests for WorkflowSessionRunner - the interpreter for Durable Free Monad.
 * Uses async tests (returning Future) for cross-platform compatibility.
 */
class WorkflowSessionRunnerTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  // Helper to run tests with isolated storage
  def withStorage[A](f: MemoryBackingStore ?=> A): A =
    given backing: MemoryBackingStore = MemoryBackingStore()
    f

  test("run pure value") {
    withStorage {
      val workflowId = WorkflowId("test-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = Durable.pure[Int](42)
      WorkflowSessionRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      }
    }
  }

  test("run map") {
    withStorage {
      val workflowId = WorkflowId("test-2")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = Durable.pure[Int](21).map(_ * 2)
      WorkflowSessionRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      }
    }
  }

  test("run flatMap") {
    withStorage {
      val workflowId = WorkflowId("test-3")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = for
        a <- Durable.pure[Int](10)
        b <- Durable.pure[Int](32)
      yield a + b

      WorkflowSessionRunner.run(workflow, ctx).map { result =>
        assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      }
    }
  }

  test("run local computation") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-4")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var computed = false
    val workflow = Durable.localCached[Int, MemoryBackingStore] { _ =>
      computed = true
      42
    }

    assertEquals(computed, false) // not computed yet

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(computed, true) // now computed
    }
  }

  test("run activity - executes and caches") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-5")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(42)
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 1)
      assertEquals(backing.size, 1)
    }
  }

  test("run activity - replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    // Pre-populate cache at index 0
    backing.put(WorkflowId("test-6"), 0, Right(42))

    // Resume from index 1 (after index 0 is cached)
    val workflowId = WorkflowId("test-6")
    val ctx = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(999) // different value - should NOT be used
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42)) // cached value
      assertEquals(executeCount, 0) // NOT executed
    }
  }

  test("run suspend") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("waiting for signal")
    val workflowId = WorkflowId("test-7")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = for
      a <- Durable.pure[Int](10)
      _ <- Durable.awaitEvent[String, MemoryBackingStore]
      b <- Durable.pure[Int](32)
    yield a + b

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]])
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasEvent("waiting for signal"), "Expected Event condition with waiting for signal")
    }
  }

  test("run error") {
    withStorage {
      val workflowId = WorkflowId("test-8")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = for
        a <- Durable.pure[Int](10)
        _ <- Durable.failed[Int](RuntimeException("test error"))
        b <- Durable.pure[Int](32)
      yield a + b

      WorkflowSessionRunner.run(workflow, ctx).map { result =>
        assert(result.isInstanceOf[WorkflowSessionResult.Failed])
        val failed = result.asInstanceOf[WorkflowSessionResult.Failed]
        assertEquals(failed.error.originalMessage, "test error")
      }
    }
  }

  test("run multiple activities in sequence") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-9")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow = for
      a <- Durable.activity { executeCount += 1; Future.successful(10) }
      b <- Durable.activity { executeCount += 1; Future.successful(20) }
      c <- Durable.activity { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 3)
      assertEquals(backing.size, 3)
    }
  }

  test("run replay from middle") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-10")

    // Pre-populate cache with first two values (indices 0 and 1)
    backing.put(workflowId, 0, Right(10))
    backing.put(workflowId, 1, Right(20))

    // Resume from index 2 (first two cached)
    val ctx = WorkflowSessionRunner.RunContext.resume(workflowId, 2, 0)

    var executeCount = 0
    val workflow = for
      a <- Durable.activity { executeCount += 1; Future.successful(10) }
      b <- Durable.activity { executeCount += 1; Future.successful(20) }
      c <- Durable.activity { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 1) // Only c executed
      assertEquals(backing.size, 3) // All three now cached
    }
  }

  test("localCached computation has access to context") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-11")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = Durable.localCached[WorkflowId, MemoryBackingStore] { ctx =>
      ctx.workflowId
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, WorkflowId("test-11")))
    }
  }

  test("activity result is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("test-12")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var callCount = 0
    val workflow = Durable.activity {
      callCount += 1
      Future.successful(42)
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(callCount, 1)
      assertEquals(backing.size, 1) // Result was cached
    }
  }
