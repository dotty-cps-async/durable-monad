package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Tests for WorkflowSessionRunner using async[Durable] syntax with preprocessor.
 *
 * This tests the high-level API with preprocessor transformation.
 * Vals ARE automatically wrapped as activities - no explicit await needed
 * for val definitions.
 *
 * These tests verify that the preprocessor correctly transforms code
 * and that the transformed code works with the runner.
 */
class WorkflowSessionRunnerPreprocessorTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  test("async[Durable] - pure value") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-pure-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = async[Durable] {
      42
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
    }
  }

  test("async[Durable] - val is automatically cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-val-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    def makeWorkflow(counter: () => Unit) = async[Durable] {
      val x = {
        counter()
        21
      }
      x * 2
    }

    var executeCount = 0
    val workflow = makeWorkflow(() => executeCount += 1)

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assert(backing.size > 0, "Preprocessor should cache val as activity")
    }
  }

  test("async[Durable] - val replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-replay-1")

    // First run - populate cache
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    var executeCount = 0

    def makeWorkflow(counter: () => Unit) = async[Durable] {
      val x = {
        counter()
        42
      }
      x
    }

    WorkflowSessionRunner.run(makeWorkflow(() => executeCount += 1), ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(executeCount, 1)
      val cachedCount = backing.size

      // Second run - replay from cache
      executeCount = 0
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, cachedCount, 0)
      WorkflowSessionRunner.run(makeWorkflow(() => executeCount += 1), ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))
        assertEquals(executeCount, 0, "Should not re-execute on replay")
      }
    }
  }

  test("async[Durable] - multiple vals in sequence") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-seq-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var aCount, bCount, cCount = 0
    def incA(): Int = { aCount += 1; 10 }
    def incB(): Int = { bCount += 1; 20 }
    def incC(): Int = { cCount += 1; 12 }

    val workflow = async[Durable] {
      val a = incA()
      val b = incB()
      val c = incC()
      a + b + c
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(aCount, 1)
      assertEquals(bCount, 1)
      assertEquals(cCount, 1)
      assert(backing.size >= 3, "Each val should be cached")
    }
  }

  test("async[Durable] - if expression condition is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-if-1")

    var condCount = 0
    def getCond(): Boolean = { condCount += 1; true }

    def makeWorkflow(condFn: () => Boolean) = async[Durable] {
      if condFn() then 42 else 0
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(makeWorkflow(getCond), ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(condCount, 1)
      val cachedCount = backing.size

      // Replay - condition should be cached
      condCount = 0
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, cachedCount, 0)
      WorkflowSessionRunner.run(makeWorkflow(getCond), ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, 42))
        assertEquals(condCount, 0, "Condition should be replayed from cache")
      }
    }
  }

  test("async[Durable] - match scrutinee is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-match-1")

    var scrutineeCount = 0
    def getScrutinee(): Int = { scrutineeCount += 1; 2 }

    def makeWorkflow(fn: () => Int) = async[Durable] {
      fn() match
        case 1 => "one"
        case 2 => "two"
        case _ => "other"
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    WorkflowSessionRunner.run(makeWorkflow(getScrutinee), ctx1).flatMap { result1 =>
      assertEquals(result1, WorkflowSessionResult.Completed(workflowId, "two"))
      assertEquals(scrutineeCount, 1)
      val cachedCount = backing.size

      // Replay - scrutinee should be cached
      scrutineeCount = 0
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, cachedCount, 0)
      WorkflowSessionRunner.run(makeWorkflow(getScrutinee), ctx2).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(workflowId, "two"))
        assertEquals(scrutineeCount, 0, "Scrutinee should be replayed from cache")
      }
    }
  }

  test("async[Durable] - explicit await still works") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-await-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow = async[Durable] {
      // Explicit await should work alongside preprocessor
      val x = await(Durable.activity {
        executeCount += 1
        Future.successful(42)
      })
      x
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(executeCount, 1)
    }
  }

  test("async[Durable] - try/catch with preprocessor") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-try-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = async[Durable] {
      try {
        val x = throw RuntimeException("fail")
        x
      } catch {
        case e: RuntimeException => -1
      }
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, -1))
    }
  }

  test("async[Durable] - nested blocks") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-nested-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var outerCount, innerCount = 0
    def incOuter(): Int = { outerCount += 1; 10 }
    def incInner(): Int = { innerCount += 1; 32 }

    val workflow = async[Durable] {
      val a = incOuter()
      val b = {
        val inner = incInner()
        inner
      }
      a + b
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(outerCount, 1)
      assertEquals(innerCount, 1)
    }
  }

  test("async[Durable] - suspend on event") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("prep-signal")
    val workflowId = WorkflowId("prep-suspend-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var beforeCount = 0
    def incBefore(): Int = { beforeCount += 1; 10 }

    val workflow = async[Durable] {
      val a = incBefore()
      val signal = await(Durable.awaitEvent[String, MemoryBackingStore])
      val b = 32
      a + b
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]])
      assertEquals(beforeCount, 1)
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasEvent("prep-signal"), "Expected Event condition with prep-signal")
    }
  }

  // Note: while loops with var assignments are NOT allowed in async[Durable]
  // because var mutations break replay semantics. Use continueWith instead.

  test("async[Durable] - Event[E].receive syntax") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("new-syntax-signal")
    val workflowId = WorkflowId("prep-event-syntax-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    // Using the new Event[E].receive.await syntax - backend type is inferred!
    val workflow = async[Durable] {
      val a = 10
      val signal = Event[String].receive.await
      a + signal.length
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]])
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasEvent("new-syntax-signal"), "Expected Event condition with new-syntax-signal")
    }
  }

  test("async[Durable] - complex workflow with multiple activities") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("prep-complex-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    def compute(n: Int): Int = n * 2

    val workflow = async[Durable] {
      val a = compute(5)
      val b = if a > 5 then compute(10) else compute(1)
      val c = await(Durable.activitySync(a + b))
      c
    }

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 30)) // 10 + 20 = 30
    }
  }
