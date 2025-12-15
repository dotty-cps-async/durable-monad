package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

/**
 * Tests for WorkflowRunner - the interpreter for Durable Free Monad.
 * Uses async tests (returning Future) for cross-platform compatibility.
 */
class WorkflowRunnerTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  // Helper to run tests with isolated storage
  def withStorage[A](f: MemoryBackingStore ?=> A): A =
    given backing: MemoryBackingStore = MemoryBackingStore()
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
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = RunContext.fresh(WorkflowId("test-5"))

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(42)
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(executeCount, 1)
      assertEquals(backing.size, 1)
    }
  }

  test("run activity - replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    // Pre-populate cache at index 0
    backing.put(WorkflowId("test-6"), 0, Right(42))

    // Resume from index 1 (after index 0 is cached)
    val ctx = RunContext.resume(WorkflowId("test-6"), 1)

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(999) // different value - should NOT be used
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42)) // cached value
      assertEquals(executeCount, 0) // NOT executed
    }
  }

  test("run suspend") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("waiting for signal")
    val ctx = RunContext.fresh(WorkflowId("test-7"))

    val workflow = for
      a <- Durable.pure[Int](10)
      _ <- Durable.awaitEvent[String, MemoryBackingStore]
      b <- Durable.pure[Int](32)
    yield a + b

    WorkflowRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowResult.Suspended[?]])
      val suspended = result.asInstanceOf[WorkflowResult.Suspended[?]]
      suspended.condition match
        case WaitCondition.Event(name, _) => assertEquals(name, "waiting for signal")
        case _ => fail("Expected Event condition")
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
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = RunContext.fresh(WorkflowId("test-9"))

    var executeCount = 0
    val workflow = for
      a <- Durable.activity { executeCount += 1; Future.successful(10) }
      b <- Durable.activity { executeCount += 1; Future.successful(20) }
      c <- Durable.activity { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
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
    val ctx = RunContext.resume(workflowId, 2)

    var executeCount = 0
    val workflow = for
      a <- Durable.activity { executeCount += 1; Future.successful(10) }
      b <- Durable.activity { executeCount += 1; Future.successful(20) }
      c <- Durable.activity { executeCount += 1; Future.successful(12) }
    yield a + b + c

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(executeCount, 1) // Only c executed
      assertEquals(backing.size, 3) // All three now cached
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
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = RunContext.fresh(WorkflowId("test-12"))

    var callCount = 0
    val workflow = Durable.activity {
      callCount += 1
      Future.successful(42)
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(callCount, 1)
      assertEquals(backing.size, 1) // Result was cached
    }
  }
