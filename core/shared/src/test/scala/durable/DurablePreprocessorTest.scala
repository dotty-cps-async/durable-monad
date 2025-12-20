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
  import MemoryBackingStore.given

  test("async block with single val") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-1")
    val ctx = RunContext.fresh(workflowId)

    var computeCount = 0
    val workflow = async[Durable] {
      val x = {
        computeCount += 1
        42
      }
      x
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount, 1)
      assertEquals(backing.size, 1) // val was cached
    }
  }

  test("async block with multiple vals") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-2")
    val ctx = RunContext.fresh(workflowId)

    var computeCount = 0
    val workflow = async[Durable] {
      val a = { computeCount += 1; 10 }
      val b = { computeCount += 1; 20 }
      val c = { computeCount += 1; 12 }
      a + b + c
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount, 3)
      assertEquals(backing.size, 3) // all vals cached
    }
  }

  test("async block replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-3")

    // First run - compute and cache
    var computeCount = 0
    val workflow = async[Durable] {
      val a = { computeCount += 1; 10 }
      val b = { computeCount += 1; 32 }
      a + b
    }

    val ctx1 = RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount, 2)

      // Second run - replay from cache
      computeCount = 0
      val ctx2 = RunContext.resume(workflowId, 2)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))
        assertEquals(computeCount, 0) // NOT recomputed - from cache
      }
    }
  }

  test("async block with if expression") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-4")
    val ctx = RunContext.fresh(workflowId)

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

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(condCount, 1)
      assertEquals(thenCount, 1)
      assertEquals(elseCount, 0) // else branch not executed
    }
  }

  test("async block with nested block") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-5")
    val ctx = RunContext.fresh(workflowId)

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

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      // Only outer val is wrapped, inner is part of outer's RHS
      assertEquals(outerCount, 1)
      assertEquals(innerCount, 1)
    }
  }

  test("async block with match expression") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-6")
    val ctx = RunContext.fresh(workflowId)

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

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(scrutineeCount, 1)
      assertEquals(caseCount, 1) // only matching case executed
    }
  }

  test("preprocessor captures DurableStorage in Activity") {
    given backing: MemoryBackingStore = MemoryBackingStore()

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
    given backing: MemoryBackingStore = MemoryBackingStore()
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
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
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
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))  // same result
        assertEquals(condCallCount, 0)  // condition NOT re-evaluated
        assertEquals(thenCount, 0)  // then branch NOT re-executed
        assertEquals(elseCount, 0)  // else branch NOT executed either
      }
    }
  }

  test("non-deterministic scrutinee in match is cached and replayed") {
    given backing: MemoryBackingStore = MemoryBackingStore()
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
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
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
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))  // same result
        assertEquals(scrutineeCallCount, 0)  // scrutinee NOT re-evaluated
        assertEquals(case1Count, 0)  // case 1 NOT re-executed
        assertEquals(case2Count, 0)  // case 2 NOT executed either
      }
    }
  }

  // ============================================================
  // Tests for preprocessor resource detection (DurableEphemeral)
  // ============================================================

  // Test resource type with DurableEphemeral
  class TestResource(val id: Int):
    var released: Boolean = false
    def release(): Unit = released = true
    def compute(x: Int): Int = x * id

  test("preprocessor auto-wraps val with DurableEphemeral in WithSessionResource") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-1")
    val ctx = RunContext.fresh(workflowId)

    var releaseCount = 0

    // Only release is provided - user's expression is used for acquisition
    given DurableEphemeral[TestResource] = DurableEphemeral(
      r => { releaseCount += 1; r.release() }
    )

    val workflow = async[Durable] {
      val resource: TestResource = new TestResource(42)  // User's expression - id=42
      val result = resource.compute(10)  // This should be wrapped as activity
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 420))  // 42 * 10 = 420
      assertEquals(releaseCount, 1)
    }
  }

  test("ephemeral resource is acquired fresh on replay, activities are cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-replay")

    var releaseCount = 0
    var computeCount = 0

    given DurableEphemeral[TestResource] = DurableEphemeral(
      r => { releaseCount += 1; r.release() }
    )

    val workflow = async[Durable] {
      val resource: TestResource = new TestResource(5)  // User's expression
      val result = { computeCount += 1; resource.compute(10) }  // Activity - cached
      result
    }

    // First run
    val ctx1 = RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 50))  // 5 * 10 = 50
      assertEquals(releaseCount, 1)
      assertEquals(computeCount, 1)

      // Reset counts
      releaseCount = 0
      computeCount = 0

      // Replay - resource created fresh (user expression runs), but activity cached
      val ctx2 = RunContext.resume(workflowId, 1)  // 1 cached activity
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 50))  // Same cached result
        assertEquals(releaseCount, 1)  // Resource released
        assertEquals(computeCount, 0)  // Activity NOT re-executed - cached
      }
    }
  }

  test("multiple ephemeral resource vals nest correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-nested")
    val ctx = RunContext.fresh(workflowId)

    var releaseOrder: List[Int] = Nil

    given DurableEphemeral[TestResource] = DurableEphemeral(
      r => { releaseOrder = releaseOrder :+ r.id; r.release() }
    )

    val workflow = async[Durable] {
      val r1: TestResource = new TestResource(1)
      val r2: TestResource = new TestResource(2)
      val result = r1.compute(r2.compute(5))
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 10))  // 1 * (2 * 5) = 10
      // Should release in reverse order (inner first)
      assertEquals(releaseOrder, List(2, 1))
    }
  }

  test("ephemeral resource and non-resource vals work together") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-mixed-vals")
    val ctx = RunContext.fresh(workflowId)

    var releaseCount = 0

    given DurableEphemeral[TestResource] = DurableEphemeral(
      r => { releaseCount += 1; r.release() }
    )

    val workflow = async[Durable] {
      val x = 10  // Regular val - wrapped as activity
      val resource: TestResource = new TestResource(3)  // Ephemeral resource - user's expression
      val y = 5   // Regular val inside resource scope
      val result = resource.compute(x + y)
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 45))  // 3 * (10 + 5) = 45
      assertEquals(releaseCount, 1)
    }
  }

