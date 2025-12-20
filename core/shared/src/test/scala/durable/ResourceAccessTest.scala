package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite
import cps.*
import cps.monads.FutureAsyncMonad

import com.github.rssh.appcontext.*

class ResourceAccessTest extends FunSuite:

  // Test resource that tracks creation count
  class TestResource:
    var accessCount = 0
    def access(): String =
      accessCount += 1
      s"access-$accessCount"

  // Provider for test resource
  given testResourceProvider: AppContextProvider[TestResource] =
    new AppContextProvider[TestResource]:
      def get: TestResource = new TestResource

  // Test backend
  given testBackend: MemoryBackingStore = MemoryBackingStore()
  import MemoryBackingStore.given

  test("Durable.env[T] resolves from AppContext cache") {
    val workflowId = WorkflowId("env-test-1")

    val workflow = async[Durable] {
      val resource = await(Durable.env[TestResource])
      val result1 = resource.access()
      val result2 = resource.access()
      s"$result1,$result2"
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "access-1,access-2")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("Durable.env[T] returns same instance within same run") {
    val workflowId = WorkflowId("env-test-2")

    val workflow = async[Durable] {
      val r1 = await(Durable.env[TestResource])
      val r2 = await(Durable.env[TestResource])
      // Should be same instance from AppContext cache
      (r1 eq r2).toString
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "true")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  // Resource that tracks acquire/release
  class BracketResource:
    var acquired = false
    var released = false
    def doWork(): String =
      if !acquired then throw RuntimeException("Not acquired")
      if released then throw RuntimeException("Already released")
      "work-done"

  test("withResource releases on success") {
    val workflowId = WorkflowId("bracket-success-1")
    val resource = new BracketResource

    val workflow = Durable.withResource(
      acquire = { resource.acquired = true; resource },
      release = r => { r.released = true }
    ) { r =>
      Durable.pure(r.doWork())
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "work-done")
          assert(resource.acquired, "Resource should be acquired")
          assert(resource.released, "Resource should be released after success")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("withResource releases on failure") {
    val workflowId = WorkflowId("bracket-failure-1")
    val resource = new BracketResource

    val workflow = Durable.withResource(
      acquire = { resource.acquired = true; resource },
      release = r => { r.released = true }
    ) { r =>
      Durable.failed[String](RuntimeException("Intentional failure"))
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Failed(_, error) =>
          // Error is wrapped in ReplayedException, get underlying cause
          val cause = error match
            case re: ReplayedException => re.stored.message
            case e => e.getMessage
          assertEquals(cause, "Intentional failure")
          assert(resource.acquired, "Resource should be acquired")
          assert(resource.released, "Resource should be released after failure")
        case other =>
          fail(s"Expected Failed, got $other")
    }
  }

  test("withResource with activity - activity is cached but resource is fresh") {
    val workflowId = WorkflowId("bracket-activity-1")

    var resourceCreations = 0
    var activityExecutions = 0

    val workflow = Durable.withResource(
      acquire = { resourceCreations += 1; s"resource-$resourceCreations" },
      release = _ => ()
    ) { resource =>
      Durable.activitySync {
        activityExecutions += 1
        s"$resource-activity-$activityExecutions"
      }
    }

    // First run - creates resource and executes activity
    val ctx1 = RunContext.fresh(workflowId)
    val result1 = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx1))
    }

    async[Future] {
      val res1 = await(result1)
      res1 match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "resource-1-activity-1")
          assertEquals(resourceCreations, 1)
          assertEquals(activityExecutions, 1)
        case other =>
          fail(s"Expected Completed, got $other")

      // Replay - resource is created fresh but activity replays from cache
      val ctx2 = RunContext.resume(workflowId, 1) // Resume past the activity
      val res2 = await(WorkflowSessionRunner.run(workflow, ctx2))
      res2 match
        case WorkflowSessionResult.Completed(_, value) =>
          // Activity result is cached, so we get the original value
          assertEquals(value, "resource-1-activity-1")
          // Resource was acquired again (fresh on replay)
          assertEquals(resourceCreations, 2)
          // Activity was NOT re-executed (cached)
          assertEquals(activityExecutions, 1)
        case other =>
          fail(s"Expected Completed on replay, got $other")
    }
  }

  test("nested withResource releases in correct order") {
    val workflowId = WorkflowId("nested-bracket-1")
    var releaseOrder = List.empty[String]

    val workflow = Durable.withResource(
      acquire = "outer",
      release = r => { releaseOrder = releaseOrder :+ r }
    ) { outer =>
      Durable.withResource(
        acquire = "inner",
        release = r => { releaseOrder = releaseOrder :+ r }
      ) { inner =>
        Durable.pure(s"$outer-$inner")
      }
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "outer-inner")
          // Inner released first, then outer (LIFO order)
          assertEquals(releaseOrder, List("inner", "outer"))
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("resource acquisition failure is handled") {
    val workflowId = WorkflowId("acquire-failure-1")

    val workflow = Durable.withResource(
      acquire = throw RuntimeException("Acquire failed"),
      release = (_: String) => ()
    ) { resource =>
      Durable.pure(resource)
    }

    val ctx = RunContext.fresh(workflowId)
    val result = async[Future] {
      await(WorkflowSessionRunner.run(workflow, ctx))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Failed(_, error) =>
          // Error is wrapped in ReplayedException, get underlying cause
          val cause = error match
            case re: ReplayedException => re.stored.message
            case e => e.getMessage
          assertEquals(cause, "Acquire failed")
        case other =>
          fail(s"Expected Failed, got $other")
    }
  }

  // Tests for DurableEphemeral

  test("DurableEphemeral.apply creates ephemeral resource typeclass") {
    var releaseCount = 0
    val ephemeral = DurableEphemeral[String](s => releaseCount += 1)

    ephemeral.release("test")
    assertEquals(releaseCount, 1)

    ephemeral.release("test2")
    assertEquals(releaseCount, 2)
  }
