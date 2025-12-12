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
    val ctx = RunContext.fresh(storage, WorkflowId("preprocess-1"))

    var computeCount = 0
    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
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
    val ctx = RunContext.fresh(storage, WorkflowId("preprocess-2"))

    var computeCount = 0
    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
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
    val workflowId = WorkflowId("preprocess-3")

    // First run - compute and cache
    var computeCount = 0
    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
      val a = { computeCount += 1; 10 }
      val b = { computeCount += 1; 32 }
      a + b
    }

    val ctx1 = RunContext.fresh(storage, workflowId)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(42))
      assertEquals(computeCount, 2)

      // Second run - replay from cache
      computeCount = 0
      val ctx2 = RunContext(storage, workflowId, resumeFromIndex = 2)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(42))
        assertEquals(computeCount, 0) // NOT recomputed - from cache
      }
    }
  }

  test("async block with if expression") {
    val storage = MemoryStorage()
    val ctx = RunContext.fresh(storage, WorkflowId("preprocess-4"))

    var condCount = 0
    var thenCount = 0
    var elseCount = 0

    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
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
    val ctx = RunContext.fresh(storage, WorkflowId("preprocess-5"))

    var outerCount = 0
    var innerCount = 0

    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
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
    val ctx = RunContext.fresh(storage, WorkflowId("preprocess-6"))

    var scrutineeCount = 0
    var caseCount = 0

    val workflow = async[[A] =>> Durable[A, MemoryStorage]] {
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
