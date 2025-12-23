package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

import durable.engine.{ConfigSource, WorkflowSessionRunner, WorkflowSessionResult}

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
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val computeCount = TestCounter()
    val workflow = async[Durable] {
      val x = {
        computeCount.increment()
        42
      }
      x
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount.get, 1)
      assertEquals(backing.size, 1) // val was cached
    }
  }

  test("async block with multiple vals") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-2")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val computeCount = TestCounter()
    val workflow = async[Durable] {
      val a = { computeCount.increment(); 10 }
      val b = { computeCount.increment(); 20 }
      val c = { computeCount.increment(); 12 }
      a + b + c
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount.get, 3)
      assertEquals(backing.size, 3) // all vals cached
    }
  }

  test("async block replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-3")

    // First run - compute and cache
    val computeCount = TestCounter()
    val workflow = async[Durable] {
      val a = { computeCount.increment(); 10 }
      val b = { computeCount.increment(); 32 }
      a + b
    }

    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(computeCount.get, 2)

      // Second run - replay from cache
      computeCount.reset()
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 2, 0)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))
        assertEquals(computeCount.get, 0) // NOT recomputed - from cache
      }
    }
  }

  test("async block with if expression") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-4")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val condCount = TestCounter()
    val thenCount = TestCounter()
    val elseCount = TestCounter()

    val workflow = async[Durable] {
      val result = if { condCount.increment(); true } then {
        thenCount.increment()
        42
      } else {
        elseCount.increment()
        0
      }
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(condCount.get, 1)
      assertEquals(thenCount.get, 1)
      assertEquals(elseCount.get, 0) // else branch not executed
    }
  }

  test("async block with nested block") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-5")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val outerCount = TestCounter()
    val innerCount = TestCounter()

    val workflow = async[Durable] {
      val outer = {
        outerCount.increment()
        val inner = { innerCount.increment(); 21 }
        inner * 2
      }
      outer
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      // Only outer val is wrapped, inner is part of outer's RHS
      assertEquals(outerCount.get, 1)
      assertEquals(innerCount.get, 1)
    }
  }

  test("async block with match expression") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-6")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val scrutineeCount = TestCounter()
    val caseCount = TestCounter()

    val workflow = async[Durable] {
      val x = { scrutineeCount.increment(); 2 }
      val result: Int = x match {
        case 1 => { caseCount.increment(); 10 }
        case 2 => { caseCount.increment(); 42 }
        case _ => { caseCount.increment(); 0 }
      }
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(scrutineeCount.get, 1)
      assertEquals(caseCount.get, 1) // only matching case executed
    }
  }

  test("preprocessor captures DurableStorage in Activity") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    def computeValue(): Int = 42

    // Create a workflow via async - the preprocessor should capture DurableStorage
    // Note: literals like `42` are not wrapped (deterministic), so we use a function call
    val workflow = async[Durable] {
      val x = computeValue()  // Function call - will be wrapped as Activity
      x
    }

    // Verify the Activity node contains a DurableStorage
    workflow match
      case Durable.FlatMap(Durable.Activity(_, capturedStorage, _, _), _) =>
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
    val condCallCount = TestCounter()
    def nonDeterministicCondition(): Boolean = {
      condCallCount.increment()
      condCallCount.get == 1  // true on first call, false on subsequent
    }

    val thenCount = TestCounter()
    val elseCount = TestCounter()

    val workflow = async[Durable] {
      val result = if (nonDeterministicCondition()) {
        thenCount.increment()
        42
      } else {
        elseCount.increment()
        0
      }
      result
    }

    // First run - condition is true, takes then branch
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(condCallCount.get, 1)  // condition evaluated once
      assertEquals(thenCount.get, 1)
      assertEquals(elseCount.get, 0)

      // Reset counters for replay
      condCallCount.reset()
      thenCount.reset()
      elseCount.reset()

      // Second run (replay) - should use cached condition value (true)
      // even though nonDeterministicCondition() would now return false
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 2, 0)  // both condition and result cached
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))  // same result
        assertEquals(condCallCount.get, 0)  // condition NOT re-evaluated
        assertEquals(thenCount.get, 0)  // then branch NOT re-executed
        assertEquals(elseCount.get, 0)  // else branch NOT executed either
      }
    }
  }

  test("non-deterministic scrutinee in match is cached and replayed") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-match-nondet")

    // Simulate non-deterministic scrutinee with a counter
    // First call returns 1, subsequent calls would return 2
    val scrutineeCallCount = TestCounter()
    def nonDeterministicValue(): Int = {
      scrutineeCallCount.increment()
      if (scrutineeCallCount.get == 1) 1 else 2
    }

    val case1Count = TestCounter()
    val case2Count = TestCounter()

    val workflow = async[Durable] {
      val x = nonDeterministicValue()
      val result: Int = x match {
        case 1 => { case1Count.increment(); 42 }
        case 2 => { case2Count.increment(); 0 }
        case _ => -1
      }
      result
    }

    // First run - scrutinee is 1, takes case 1
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(scrutineeCallCount.get, 1)  // scrutinee evaluated once
      assertEquals(case1Count.get, 1)
      assertEquals(case2Count.get, 0)

      // Reset counters for replay
      scrutineeCallCount.reset()
      case1Count.reset()
      case2Count.reset()

      // Second run (replay) - should use cached scrutinee value (1)
      // even though nonDeterministicValue() would now return 2
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 3, 0)  // x, scrutinee, and result cached
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))  // same result
        assertEquals(scrutineeCallCount.get, 0)  // scrutinee NOT re-evaluated
        assertEquals(case1Count.get, 0)  // case 1 NOT re-executed
        assertEquals(case2Count.get, 0)  // case 2 NOT executed either
      }
    }
  }

  // ============================================================
  // Tests for preprocessor resource detection (DurableEphemeralResource)
  // ============================================================

  // Test resource type with DurableEphemeralResource
  class TestResource(val id: Int):
    var released: Boolean = false
    def release(): Unit = released = true
    def compute(x: Int): Int = x * id

  test("preprocessor auto-wraps val with DurableEphemeralResource in WithSessionResource") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val releaseCount = TestCounter()

    // Only release is provided - user's expression is used for acquisition
    given DurableEphemeralResource[TestResource] = DurableEphemeralResource(
      r => { releaseCount.increment(); r.release() }
    )

    val workflow = async[Durable] {
      val resource: TestResource = new TestResource(42)  // User's expression - id=42
      val result = resource.compute(10)  // This should be wrapped as activity
      result
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 420))  // 42 * 10 = 420
      assertEquals(releaseCount.get, 1)
    }
  }

  test("ephemeral resource is acquired fresh on replay, activities are cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-replay")

    val releaseCount = TestCounter()
    val computeCount = TestCounter()

    given DurableEphemeralResource[TestResource] = DurableEphemeralResource(
      r => { releaseCount.increment(); r.release() }
    )

    val workflow = async[Durable] {
      val resource: TestResource = new TestResource(5)  // User's expression
      val result = { computeCount.increment(); resource.compute(10) }  // Activity - cached
      result
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 50))  // 5 * 10 = 50
      assertEquals(releaseCount.get, 1)
      assertEquals(computeCount.get, 1)

      // Reset counts
      releaseCount.reset()
      computeCount.reset()

      // Replay - resource created fresh (user expression runs), but activity cached
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)  // 1 cached activity
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 50))  // Same cached result
        assertEquals(releaseCount.get, 1)  // Resource released
        assertEquals(computeCount.get, 0)  // Activity NOT re-executed - cached
      }
    }
  }

  test("multiple ephemeral resource vals nest correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-resource-nested")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    // Use a mutable list builder outside the async block - appending via method call
    val releaseOrderBuilder = scala.collection.mutable.ListBuffer[Int]()

    given DurableEphemeralResource[TestResource] = DurableEphemeralResource(
      r => { releaseOrderBuilder.append(r.id); r.release() }
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
      assertEquals(releaseOrderBuilder.toList, List(2, 1))
    }
  }

  test("ephemeral resource and non-resource vals work together") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("preprocess-mixed-vals")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val releaseCount = TestCounter()

    given DurableEphemeralResource[TestResource] = DurableEphemeralResource(
      r => { releaseCount.increment(); r.release() }
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
      assertEquals(releaseCount.get, 1)
    }
  }

  // ============================================================
  // Tests that localCached properly caches values on replay
  // ============================================================

  test("Durable.localCached.await should NOT be re-evaluated on replay") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("nondet-local-await")

    // Counter that changes value on each call - simulates non-deterministic source
    val callCount = TestCounter()
    def getNonDeterministicValue(): Int = {
      callCount.increment()
      callCount.get * 10  // Returns 10, 20, 30, ... on successive calls
    }

    val workflow = async[Durable] {
      // localCached ensures the value is cached and replayed
      val nonDetValue = Durable.localCached { _ => getNonDeterministicValue() }.await

      // This IS cached because it's a regular val (wrapped by preprocessor)
      val cached = nonDetValue + 5

      cached
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 15))  // 10 + 5 = 15
      assertEquals(callCount.get, 1)

      // DON'T reset callCount - this simulates the counter persisting across runs

      // Replay from index 1 (nonDetValue cached, cached val IS cached)
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        // With localCached, function should NOT be called again on replay
        assertEquals(callCount.get, 1, "Durable.localCached.await was re-evaluated on replay!")
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 15))
      }
    }
  }

  test("Durable.localCached.await in condition should use cached value on replay") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("nondet-control-flow")

    val callCount = TestCounter()
    def getNonDeterministicBool(): Boolean = {
      callCount.increment()
      callCount.get == 1  // true on first call, false on subsequent
    }

    val workflow = async[Durable] {
      // localCached ensures the value is cached
      val shouldTakeThen = Durable.localCached { _ => getNonDeterministicBool() }.await

      // The if-condition IS cached (preprocessor wraps shouldTakeThen reference)
      if shouldTakeThen then 42 else 0
    }

    // First run - shouldTakeThen = true
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(callCount.get, 1)

      // Replay from index 2 (condition + branch cached)
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 2, 0)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        // With localCached, function should NOT be called again on replay
        assertEquals(callCount.get, 1, "Durable.localCached.await was re-evaluated on replay!")
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))
      }
    }
  }

  // ============================================================
  // Tests for lambda with await passed to higher-order functions
  // ============================================================

  test("lambda with await passed to List.map caches correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("lambda-hof-1")

    // Enable CPS code printing for debugging
    given cps.macros.flags.PrintCode = cps.macros.flags.PrintCode()

    val computeCounter = TestCounter()

    val workflow = async[Durable] {
      val items = List(1, 2, 3)
      // Lambda with await passed to map - body should be transformed
      val results = items.map { x =>
        val doubled = { computeCounter.increment(); x * 2 }
        Durable.pure(doubled).await
      }
      results.sum
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(workflow, ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 12))  // (1*2) + (2*2) + (3*2) = 12
      assertEquals(computeCounter.get, 3)  // computed for each item

      // Replay - all vals should be cached
      computeCounter.reset()
      val numCached = backing.size
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, numCached, 0)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 12))
        assertEquals(computeCounter.get, 0)  // NOT recomputed - from cache
      }
    }
  }

