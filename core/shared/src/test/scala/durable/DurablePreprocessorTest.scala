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
  import DurableCpsPreprocessor.given

  test("async block with single val") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
      assertEquals(backing.size, 1) // val was cached
    }
  }

  test("async block with multiple vals") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
      assertEquals(backing.size, 3) // all vals cached
    }
  }

  test("async block replays from cache") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
      val ctx2 = RunContext.resume(workflowId, 2)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(42))
        assertEquals(computeCount, 0) // NOT recomputed - from cache
      }
    }
  }

  test("async block with if expression") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
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

  test("preprocessor captures DurableStorage in Activity") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    // Create a workflow via async - the preprocessor should capture DurableStorage
    val workflow = async[Durable] {
      val x = 42
      x
    }

    // Verify the Activity node contains a DurableStorage
    workflow match
      case Durable.FlatMap(Durable.Activity(_, capturedStorage, _), _) =>
        // The captured storage should be a DurableStorage instance
        assert(capturedStorage.isInstanceOf[DurableStorage[?, ?]],
          s"Expected DurableStorage but got ${capturedStorage.getClass.getName}")
      case other =>
        fail(s"Expected FlatMap(Activity(...), ...) but got ${other.getClass.getSimpleName}")
  }

  test("preprocessor gives clear error when no DurableStorageBackend in scope") {
    // This test verifies that when no DurableStorageBackend is in scope,
    // the preprocessor gives a clear compile error.
    // We use scala.compiletime.testing.typeCheckErrors to check for the expected error.
    import scala.compiletime.testing.typeCheckErrors

    val errors = typeCheckErrors("""
      import durable.*
      import durable.DurableCpsPreprocessor.given
      import cps.*

      // Note: NO given DurableStorageBackend here!
      val workflow = async[Durable] {
        val x = 42
        x
      }
    """)

    assert(errors.nonEmpty, "Expected a compile error when no DurableStorageBackend is in scope")
    // The error message should mention DurableStorageBackend since that's what the preprocessor looks for first
    assert(errors.exists(_.message.contains("DurableStorageBackend")),
      s"Expected error about missing DurableStorageBackend, but got: ${errors.map(_.message).mkString(", ")}")
  }

  test("non-deterministic condition in if is cached and replayed") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
    val workflowId = WorkflowId("preprocess-nondet")

    // Simulate non-deterministic condition with a counter
    // First call returns true, subsequent calls would return false
    var condCallCount = 0
    def nonDeterministicCondition(): Boolean = {
      condCallCount += 1
      condCallCount == 1  // true on first call, false on subsequent
    }

    var thenCount = 0
    var elseCount = 0

    val workflow = async[Durable] {
      val result = if (nonDeterministicCondition()) {
        thenCount += 1
        42
      } else {
        elseCount += 1
        0
      }
      result
    }

    // First run - condition is true, takes then branch
    val ctx1 = RunContext.fresh(workflowId)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(42))
      assertEquals(condCallCount, 1)  // condition evaluated once
      assertEquals(thenCount, 1)
      assertEquals(elseCount, 0)

      // Reset counters for replay
      condCallCount = 0
      thenCount = 0
      elseCount = 0

      // Second run (replay) - should use cached condition value (true)
      // even though nonDeterministicCondition() would now return false
      val ctx2 = RunContext.resume(workflowId, 2)  // both condition and result cached
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(42))  // same result
        assertEquals(condCallCount, 0)  // condition NOT re-evaluated
        assertEquals(thenCount, 0)  // then branch NOT re-executed
        assertEquals(elseCount, 0)  // else branch NOT executed either
      }
    }
  }

  test("non-deterministic scrutinee in match is cached and replayed") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]
    val workflowId = WorkflowId("preprocess-match-nondet")

    // Simulate non-deterministic scrutinee with a counter
    // First call returns 1, subsequent calls would return 2
    var scrutineeCallCount = 0
    def nonDeterministicValue(): Int = {
      scrutineeCallCount += 1
      if (scrutineeCallCount == 1) 1 else 2
    }

    var case1Count = 0
    var case2Count = 0

    val workflow = async[Durable] {
      val x = nonDeterministicValue()
      val result: Int = x match {
        case 1 => { case1Count += 1; 42 }
        case 2 => { case2Count += 1; 0 }
        case _ => -1
      }
      result
    }

    // First run - scrutinee is 1, takes case 1
    val ctx1 = RunContext.fresh(workflowId)
    WorkflowRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowResult.Completed(42))
      assertEquals(scrutineeCallCount, 1)  // scrutinee evaluated once
      assertEquals(case1Count, 1)
      assertEquals(case2Count, 0)

      // Reset counters for replay
      scrutineeCallCount = 0
      case1Count = 0
      case2Count = 0

      // Second run (replay) - should use cached scrutinee value (1)
      // even though nonDeterministicValue() would now return 2
      val ctx2 = RunContext.resume(workflowId, 3)  // x, scrutinee, and result cached
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(42))  // same result
        assertEquals(scrutineeCallCount, 0)  // scrutinee NOT re-evaluated
        assertEquals(case1Count, 0)  // case 1 NOT re-executed
        assertEquals(case2Count, 0)  // case 2 NOT executed either
      }
    }
  }
