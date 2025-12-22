package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Tests for DurableRaw - verifying raw API behavior without preprocessor.
 *
 * These tests demonstrate that when using async[DurableRaw]:
 * - val definitions are NOT automatically wrapped as activities
 * - you must explicitly use Durable.activity/await to cache values
 */
class DurableRawTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  test("DurableRaw async - vals are NOT wrapped as activities") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-no-wrap-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    // Create workflow factory to test multiple runs
    def makeWorkflow(counter: () => Unit): Durable[Int] = async[DurableRaw] {
      // This val is NOT wrapped as activity - it's just a regular val
      val x = {
        counter()
        42
      }
      x + 1
    }.toDurable

    var computeCount = 0
    val workflow = makeWorkflow(() => computeCount += 1)

    // First run
    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 43))
      // The val computation happened during workflow construction
      assertEquals(computeCount, 1)
      // No activities were created
      assertEquals(backing.size, 0, "DurableRaw should not create activities for vals")
    }
  }

  test("DurableRaw async - explicit activity is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-explicit-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var computeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      // Explicitly wrap as activity - this IS cached
      val x = await(DurableRaw.activitySync {
        computeCount += 1
        42
      })
      x + 1
    }.toDurable

    // First run
    WorkflowSessionRunner.run(workflow, ctx).flatMap { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 43))
      assertEquals(computeCount, 1)
      assertEquals(backing.size, 1, "Activity should be cached")

      // Second run (replay) - x is replayed from cache
      computeCount = 0
      val ctx2 = WorkflowSessionRunner.RunContext.resume(WorkflowId("raw-explicit-1"), 1)
      WorkflowSessionRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(ctx.workflowId, 43))
        assertEquals(computeCount, 0, "With explicit activity, val is cached on replay")
      }
    }
  }

  test("DurableRaw can use for-comprehension with await") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-for-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      val a = await(DurableRaw.activity(Future.successful(10)))
      val b = await(DurableRaw.activity(Future.successful(20)))
      a + b
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 30))
      assertEquals(backing.size, 2)
    }
  }

  test("DurableRaw.pure creates Pure node") {
    val dr = DurableRaw.pure(42)
    dr.toDurable match
      case Durable.Pure(v) => assertEquals(v, 42)
      case _ => fail("Expected Pure")
  }

  test("DurableRaw.failed creates Error node") {
    val dr = DurableRaw.failed[Int](RuntimeException("test"))
    dr.toDurable match
      case Durable.Error(e) => assertEquals(e.getMessage, "test")
      case _ => fail("Expected Error")
  }

  test("DurableRaw.activity creates Activity node") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given MemoryBackingStore = backing

    var computed = false
    val dr = DurableRaw.activity {
      computed = true
      Future.successful(42)
    }

    // Activity is lazy
    assertEquals(computed, false)

    dr.toDurable match
      case Durable.Activity(_, _, _, _) => ()
      case _ => fail("Expected Activity")
  }

  test("DurableRaw.local creates LocalComputation node") {
    val dr = DurableRaw.local { ctx => ctx.workflowId.value }

    dr.toDurable match
      case Durable.LocalComputation(_, _, _) => ()
      case _ => fail("Expected LocalComputation")
  }

  test("compare: Durable preprocessor wraps vals, DurableRaw doesn't") {
    // Test with preprocessor - val is wrapped as activity
    {
      given backing: MemoryBackingStore = MemoryBackingStore()

      val durableCount = TestCounter()
      val workflowWithPreprocessor = async[Durable] {
        val x = {
          durableCount.increment()
          42
        }
        x
      }

      val workflowId = WorkflowId("compare-preprocessor")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
      WorkflowSessionRunner.run(workflowWithPreprocessor, ctx).map { _ =>
        // Preprocessor version caches the val as activity
        assert(backing.size > 0, "Preprocessor should create activities")
      }
    }

    // Test without preprocessor - val is NOT wrapped
    {
      given backing: MemoryBackingStore = MemoryBackingStore()

      var rawCount = 0
      val workflowRaw: Durable[Int] = async[DurableRaw] {
        val x = {
          rawCount += 1  // This is DurableRaw - no preprocessor, so assignment is OK
          42
        }
        x
      }.toDurable

      val workflowId = WorkflowId("compare-raw")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
      WorkflowSessionRunner.run(workflowRaw, ctx).map { _ =>
        // Raw version has no cached activities
        assertEquals(backing.size, 0, "Raw version should have no activities")
      }
    }
  }
