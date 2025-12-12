package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

/**
 * Tests for DurablePreprocessor - verifies that async blocks
 * are correctly transformed to wrap vals with activities.
 * Uses async tests (returning Future) for cross-platform compatibility.
 */
class DurablePreprocessorTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryStorage.memoryDurableCacheBackend
  import DurableCpsPreprocessor.given

  test("async block with single val") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("preprocess-1"))

    var computeCount = 0
    val workflow = async[Durable] {
      val x = {
        computeCount += 1
        42
      }
      x
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(computeCount, 1)
      assertEquals(storage.size, 1) // val was cached
    }
  }

  test("async block with multiple vals") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("preprocess-2"))

    var computeCount = 0
    val workflow = async[Durable] {
      val a = { computeCount += 1; 10 }
      val b = { computeCount += 1; 20 }
      val c = { computeCount += 1; 12 }
      a + b + c
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(computeCount, 3)
      assertEquals(storage.size, 3) // all vals cached
    }
  }

  test("async block replays from cache") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val workflowId = WorkflowId("preprocess-3")

    // First run - compute and cache
    var computeCount = 0
    val workflow = async[Durable] {
      val a = { computeCount += 1; 10 }
      val b = { computeCount += 1; 32 }
      a + b
    }

    val ctx1 = RunContext.fresh(workflowId)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(42))
      assertEquals(computeCount, 2)

      // Second run - replay from cache
      computeCount = 0
      val ctx2 = RunContext(workflowId, resumeFromIndex = 2)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(42))
        assertEquals(computeCount, 0) // NOT recomputed - from cache
      }
    }
  }

  test("async block with if expression") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("preprocess-4"))

    var condCount = 0
    var thenCount = 0
    var elseCount = 0

    val workflow = async[Durable] {
      val result = if { condCount += 1; true } then {
        thenCount += 1
        42
      } else {
        elseCount += 1
        0
      }
      result
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(condCount, 1)
      assertEquals(thenCount, 1)
      assertEquals(elseCount, 0) // else branch not executed
    }
  }

  test("async block with nested block") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("preprocess-5"))

    var outerCount = 0
    var innerCount = 0

    val workflow = async[Durable] {
      val outer = {
        outerCount += 1
        val inner = { innerCount += 1; 21 }
        inner * 2
      }
      outer
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      // Only outer val is wrapped, inner is part of outer's RHS
      assertEquals(outerCount, 1)
      assertEquals(innerCount, 1)
    }
  }

  test("async block with match expression") {
    val storage = MemoryStorage()
    given MemoryStorage = storage
    val ctx = RunContext.fresh(WorkflowId("preprocess-6"))

    var scrutineeCount = 0
    var caseCount = 0

    val workflow = async[Durable] {
      val x = { scrutineeCount += 1; 2 }
      val result: Int = x match {
        case 1 => { caseCount += 1; 10 }
        case 2 => { caseCount += 1; 42 }
        case _ => { caseCount += 1; 0 }
      }
      result
    }

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
      assertEquals(scrutineeCount, 1)
      assertEquals(caseCount, 1) // only matching case executed
    }
  }

  test("preprocessor finds correct storage type via DurableStorage") {
    val storage = MemoryStorage()
    given MemoryStorage = storage

    // Create a workflow via async - the preprocessor should find MemoryStorage
    val workflow = async[Durable] {
      val x = 42
      x
    }

    // Verify the Activity node contains MemoryStorage as its storage
    workflow match
      case Durable.FlatMap(Durable.Activity(_, _, capturedStorage), _) =>
        // The captured storage should be the same MemoryStorage instance
        assert(capturedStorage.isInstanceOf[MemoryStorage],
          s"Expected MemoryStorage but got ${capturedStorage.getClass.getName}")
        assertEquals(capturedStorage.asInstanceOf[MemoryStorage], storage)
      case other =>
        fail(s"Expected FlatMap(Activity(...), ...) but got ${other.getClass.getSimpleName}")
  }

  test("preprocessor gives clear error when no DurableStorage in scope") {
    // This test verifies that when no DurableStorage is in scope,
    // the preprocessor gives a clear compile error.
    // We use scala.compiletime.testing.typeCheckErrors to check for the expected error.
    import scala.compiletime.testing.typeCheckErrors

    val errors = typeCheckErrors("""
      import durable.*
      import durable.DurableCpsPreprocessor.given
      import cps.*

      // Note: NO given MemoryStorage here!
      val workflow = async[Durable] {
        val x = 42
        x
      }
    """)

    assert(errors.nonEmpty, "Expected a compile error when no DurableStorage is in scope")
    assert(errors.exists(_.message.contains("No DurableStorage found")),
      s"Expected error about missing DurableStorage, but got: ${errors.map(_.message).mkString(", ")}")
  }
