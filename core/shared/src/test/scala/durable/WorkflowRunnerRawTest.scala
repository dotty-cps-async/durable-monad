package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

/**
 * Tests for WorkflowSessionRunner using async[DurableRaw] syntax.
 *
 * This tests the raw API without preprocessor transformation.
 * Vals are NOT automatically wrapped as activities - you must use
 * explicit await(DurableRaw.activity(...)) calls.
 *
 * This is the minimal layer for debugging - if something breaks,
 * start here to isolate whether it's a runner issue or preprocessor issue.
 */
class WorkflowSessionRunnerRawTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  test("async[DurableRaw] - pure value") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-pure-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      42
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
    }
  }

  test("async[DurableRaw] - val without activity is not cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-val-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      val x = 21
      x * 2
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(backing.size, 0, "No activities should be cached")
    }
  }

  test("async[DurableRaw] - explicit activity is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-activity-1")
    val ctx = RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      val x = await(DurableRaw.activity {
        executeCount += 1
        Future.successful(42)
      })
      x
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 1)
      assertEquals(backing.size, 1)
    }
  }

  test("async[DurableRaw] - activity replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-replay-1")

    // Pre-populate cache
    backing.put(workflowId, 0, Right(42))
    val ctx = RunContext.resume(workflowId, 1)

    var executeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      val x = await(DurableRaw.activity {
        executeCount += 1
        Future.successful(999) // different value - should NOT be used
      })
      x
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42)) // cached value
      assertEquals(executeCount, 0) // NOT executed
    }
  }

  test("async[DurableRaw] - multiple activities in sequence") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-seq-1")
    val ctx = RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      val a = await(DurableRaw.activity { executeCount += 1; Future.successful(10) })
      val b = await(DurableRaw.activity { executeCount += 1; Future.successful(20) })
      val c = await(DurableRaw.activity { executeCount += 1; Future.successful(12) })
      a + b + c
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 3)
      assertEquals(backing.size, 3)
    }
  }

  test("async[DurableRaw] - replay from middle") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-middle-1")

    // Pre-populate cache with first two values
    backing.put(workflowId, 0, Right(10))
    backing.put(workflowId, 1, Right(20))
    val ctx = RunContext.resume(workflowId, 2)

    var executeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      val a = await(DurableRaw.activity { executeCount += 1; Future.successful(10) })
      val b = await(DurableRaw.activity { executeCount += 1; Future.successful(20) })
      val c = await(DurableRaw.activity { executeCount += 1; Future.successful(12) })
      a + b + c
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 1) // Only c executed
      assertEquals(backing.size, 3)
    }
  }

  test("async[DurableRaw] - sync activity") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-sync-1")
    val ctx = RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow: Durable[Int] = async[DurableRaw] {
      val x = await(DurableRaw.activitySync {
        executeCount += 1
        42
      })
      x + 1
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 43))
      assertEquals(executeCount, 1)
      assertEquals(backing.size, 1)
    }
  }

  test("async[DurableRaw] - if expression") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-if-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      val cond = await(DurableRaw.activitySync(true))
      if cond then
        await(DurableRaw.activitySync(42))
      else
        await(DurableRaw.activitySync(0))
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(backing.size, 2) // cond + result
    }
  }

  test("async[DurableRaw] - error handling") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-error-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      val x = await(DurableRaw.activitySync(10))
      throw RuntimeException("test error")
      x + 1
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Failed])
      val failed = result.asInstanceOf[WorkflowSessionResult.Failed]
      assertEquals(failed.error.originalMessage, "test error")
    }
  }

  test("async[DurableRaw] - try/catch") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-try-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      try {
        val x = await(DurableRaw.activity(Future.failed(RuntimeException("fail"))))
        x
      } catch {
        case e: RuntimeException => -1
      }
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, -1))
    }
  }

  test("async[DurableRaw] - local computation") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("raw-local-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[WorkflowId] = async[DurableRaw] {
      await(DurableRaw(Durable.local(ctx => ctx.workflowId)))
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, WorkflowId("raw-local-1")))
    }
  }

  test("async[DurableRaw] - suspend on event") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("test-signal")
    val workflowId = WorkflowId("raw-suspend-1")
    val ctx = RunContext.fresh(workflowId)

    val workflow: Durable[Int] = async[DurableRaw] {
      val a = await(DurableRaw.activitySync(10))
      val signal = await(DurableRaw(Durable.awaitEvent[String, MemoryBackingStore]))
      val b = await(DurableRaw.activitySync(32))
      a + b
    }.toDurable

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]])
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      suspended.condition match
        case WaitCondition.Event(name, _) => assertEquals(name, "test-signal")
        case _ => fail("Expected Event condition")
    }
  }
