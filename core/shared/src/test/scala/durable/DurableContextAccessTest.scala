package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite
import cps.*
import cps.monads.FutureAsyncMonad

import com.github.rssh.appcontext.*
import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

class DurableContextAccessTest extends FunSuite:

  // Test backend
  given testBackend: MemoryBackingStore = MemoryBackingStore()
  import MemoryBackingStore.given

  test("Durable.workflowId returns correct workflow ID") {
    val expectedId = WorkflowId("context-test-1")

    val workflow = async[Durable] {
      val wfId = await(Durable.workflowId)
      wfId.value
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(expectedId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-1")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("Durable.runContext provides full context") {
    val expectedId = WorkflowId("context-test-2")

    val workflow = async[Durable] {
      val ctx = await(Durable.runContext)
      s"${ctx.workflowId.value}-${ctx.resumeFromIndex}"
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(expectedId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-2-0")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("Durable.backend returns storage backend") {
    val workflowId = WorkflowId("context-test-3")

    val workflow = async[Durable] {
      val backend = await(Durable.backend)
      // Check that backend is the expected type
      backend.isInstanceOf[MemoryBackingStore]
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, true)
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("RunContext.appContextCache returns AppContext cache") {
    val workflowId = WorkflowId("context-test-4")

    val workflow = async[Durable] {
      val runCtx = await(Durable.runContext)
      // Check that we got a cache instance
      runCtx.appContextCache.isInstanceOf[AppContext.Cache]
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, true)
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("Durable.context with accessor function") {
    val workflowId = WorkflowId("context-test-5")

    val workflow = async[Durable] {
      val resumeIdx = await(Durable.context(_.resumeFromIndex))
      resumeIdx
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, 0)
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("context access does NOT consume activity index (not cached)") {
    val workflowId = WorkflowId("context-test-6")

    // Workflow with only context accesses and one computation
    val workflow = async[Durable] {
      // Multiple context accesses should not increment activity index
      val wfId1 = await(Durable.workflowId)
      val wfId2 = await(Durable.workflowId)
      val ctx = await(Durable.runContext)

      // This val WILL consume an activity index since it's a computation
      val computation = s"${wfId1.value}-${wfId2.value}"

      computation
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-6-context-test-6")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("context access works correctly on replay") {
    val workflowId = WorkflowId("context-test-7")

    val workflow = async[Durable] {
      val wfId = await(Durable.workflowId)
      val computation = wfId.value + "-computed"
      computation
    }

    // First run
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result1 = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx1))
    }

    async[Future] {
      val res1 = await(result1)
      res1 match
        case WorkflowSessionResult.Completed(_, value1) =>
          assertEquals(value1, "context-test-7-computed")

          // Second run with resume (simulating replay behavior)
          val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)
          val result2 = await(WorkflowSessionRunner.run(workflow, ctx2))

          result2 match
            case WorkflowSessionResult.Completed(_, value2) =>
              // Should get same result on replay
              assertEquals(value2, "context-test-7-computed")
            case other =>
              fail(s"Expected Completed on replay, got $other")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("multiple context accesses in same workflow work correctly") {
    val workflowId = WorkflowId("context-test-8")

    val workflow = async[Durable] {
      val wfId1 = await(Durable.workflowId)
      val backend1 = await(Durable.backend)
      val runCtx = await(Durable.runContext)
      val wfId2 = await(Durable.workflowId)
      val appCtx = runCtx.appContextCache

      // All should provide consistent values
      val allMatch = wfId1 == wfId2 && wfId1 == runCtx.workflowId
      allMatch
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, true)
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("summon[DurableContext].workflowId also works") {
    val expectedId = WorkflowId("context-test-9")

    val workflow = async[Durable] {
      val ctx = summon[Durable.DurableContext]
      val wfId = await(ctx.workflowId)
      wfId.value
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(expectedId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-9")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("AppContext.asyncGet[Durable, WorkflowSessionRunner.RunContext] provides context via tagless-final") {
    val expectedId = WorkflowId("context-test-10")

    val workflow = async[Durable] {
      // Use tagless-final pattern to get WorkflowSessionRunner.RunContext
      val ctx = await(AppContext.asyncGet[Durable, WorkflowSessionRunner.RunContext])
      ctx.workflowId.value
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(expectedId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-10")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("InAppContext.get[Durable, WorkflowSessionRunner.RunContext] also works") {
    val expectedId = WorkflowId("context-test-11")

    val workflow = async[Durable] {
      // Alternative syntax via InAppContext
      val ctx = await(InAppContext.get[Durable, WorkflowSessionRunner.RunContext])
      s"${ctx.workflowId.value}-${ctx.resumeFromIndex}"
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(expectedId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "context-test-11-0")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }
