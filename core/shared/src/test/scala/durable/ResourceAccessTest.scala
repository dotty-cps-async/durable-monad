package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite
import cps.*
import cps.monads.FutureAsyncMonad

import com.github.rssh.appcontext.*
import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

class ResourceAccessTest extends FunSuite:

  private val runner = WorkflowSessionRunner.forFuture

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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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
    val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result1 = async[Future] {
      await(runner.run(workflow, ctx1).map(_.toOption.get))
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
      val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0) // Resume past the activity
      val res2 = await(runner.run(workflow, ctx2).map(_.toOption.get))
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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
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

  test("DurableEphemeralResource.apply creates ephemeral resource typeclass") {
    var releaseCount = 0
    val ephemeral = DurableEphemeralResource[String](s => releaseCount += 1)

    ephemeral.release("test")
    assertEquals(releaseCount, 1)

    ephemeral.release("test2")
    assertEquals(releaseCount, 2)
  }

  // Test resource that tracks creation and release for preprocessor auto-detection
  class TrackedResource:
    var released = false
    def doWork(): String = "work-done"

  test("DurableEphemeralResource auto-releases at end of async block") {
    val workflowId = WorkflowId("ephemeral-auto-release-1")
    val resource = new TrackedResource

    // Define DurableEphemeralResource for TrackedResource - preprocessor should detect this
    given DurableEphemeralResource[TrackedResource] = DurableEphemeralResource(r => r.released = true)

    val workflow = async[Durable] {
      // The preprocessor should detect this val has DurableEphemeralResource and wrap rest in withResource
      val r: TrackedResource = resource
      r.doWork()
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "work-done")
          assert(resource.released, "Resource should be released after async block completes")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  test("DurableEphemeralResource auto-releases on failure in async block") {
    val workflowId = WorkflowId("ephemeral-auto-release-failure-1")
    val resource = new TrackedResource

    given DurableEphemeralResource[TrackedResource] = DurableEphemeralResource(r => r.released = true)

    val workflow = async[Durable] {
      val r: TrackedResource = resource
      throw RuntimeException("Intentional failure")
      r.doWork() // Never reached
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Failed(_, error) =>
          val cause = error match
            case re: ReplayedException => re.stored.message
            case e => e.getMessage
          assertEquals(cause, "Intentional failure")
          assert(resource.released, "Resource should be released even after failure")
        case other =>
          fail(s"Expected Failed, got $other")
    }
  }

  // Test for DurableEphemeral behavior with continueWith
  // Tracks allocations and releases across multiple iterations

  // Shared tracker for continueWith test - needs to be at class level
  // so it's visible when the workflow object is compiled
  object ContinueWithTestTracker:
    var allocations: List[Int] = Nil
    var releases: List[Int] = Nil
    def reset(): Unit =
      allocations = Nil
      releases = Nil

  class IterationResource(val iteration: Int):
    ContinueWithTestTracker.allocations = ContinueWithTestTracker.allocations :+ iteration
    def doWork(): String = s"work-$iteration"

  // DurableEphemeralResource needs to be at class level so preprocessor can see it
  given iterationResourceEphemeral: DurableEphemeralResource[IterationResource] = DurableEphemeralResource { r =>
    ContinueWithTestTracker.releases = ContinueWithTestTracker.releases :+ r.iteration
  }

  // Workflow object using explicit withResource to verify release mechanism works
  object ResourceContinueWorkflow extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(count: Int)(using MemoryBackingStore): Durable[String] =
      Durable.withResource(
        acquire = new IterationResource(count),
        release = (r: IterationResource) => {
          ContinueWithTestTracker.releases = ContinueWithTestTracker.releases :+ r.iteration
        }
      ) { r =>
        async[Durable] {
          val result = r.doWork()
          if count <= 1 then
            result
          else
            await(continueWith(count - 1))
        }
      }

  test("DurableEphemeralResource with continueWith - releases resource on each ContinueAs") {
    import cps.*

    // Reset the tracker before the test
    ContinueWithTestTracker.reset()

    given backing: MemoryBackingStore = MemoryBackingStore()

    // Run iterations using async/await for proper Future handling
    val workflowId = WorkflowId("ephemeral-continue-test")

    async[Future] {
      // First iteration (count=3)
      val ctx1 = WorkflowSessionRunner.RunContext.fresh(workflowId)
      val result1 = await(runner.run(ResourceContinueWorkflow(3), ctx1).map(_.toOption.get))

      result1 match
        case WorkflowSessionResult.ContinueAs(metadata, storeArgs, nextWorkflow) =>
          // First iteration: allocated=3, released=3
          assertEquals(ContinueWithTestTracker.allocations, List(3), "Should have allocated for iteration 3")
          assertEquals(ContinueWithTestTracker.releases, List(3), "Should have released for iteration 3")

          // Store args and run second iteration
          await(storeArgs(backing, workflowId, scala.concurrent.ExecutionContext.global))
          // Use resume with activityOffset = argCount so activities start after stored args
          val ctx2 = WorkflowSessionRunner.RunContext.resume(workflowId, metadata.argCount, metadata.argCount)
          val result2 = await(runner.run(nextWorkflow(), ctx2).map(_.toOption.get))

          result2 match
            case WorkflowSessionResult.ContinueAs(metadata2, storeArgs2, nextWorkflow2) =>
              // Second iteration: allocated=3,2, released=3,2
              assertEquals(ContinueWithTestTracker.allocations, List(3, 2), "Should have allocated for iterations 3 and 2")
              assertEquals(ContinueWithTestTracker.releases, List(3, 2), "Should have released for iterations 3 and 2")

              // Store args and run third iteration
              await(storeArgs2(backing, workflowId, scala.concurrent.ExecutionContext.global))
              val ctx3 = WorkflowSessionRunner.RunContext.resume(workflowId, metadata2.argCount, metadata2.argCount)
              val result3 = await(runner.run(nextWorkflow2(), ctx3).map(_.toOption.get))

              result3 match
                case WorkflowSessionResult.Completed(_, value) =>
                  // Third (final) iteration: allocated=3,2,1, released=3,2,1
                  assertEquals(value, "work-1")
                  assertEquals(ContinueWithTestTracker.allocations, List(3, 2, 1), "Should have allocated for all iterations")
                  assertEquals(ContinueWithTestTracker.releases, List(3, 2, 1), "Should have released for all iterations")
                case other =>
                  fail(s"Expected Completed on third iteration, got $other")

            case other =>
              fail(s"Expected ContinueAs on second iteration, got $other")

        case other =>
          fail(s"Expected ContinueAs on first iteration, got $other")
    }
  }

  // Test for DurableEphemeralResource.derived with AutoCloseable
  // When a class extends AutoCloseable and derives DurableEphemeralResource,
  // the derived instance should have close() as the release function

  class CloseableResource extends AutoCloseable derives DurableEphemeralResource:
    var closed = false
    def doWork(): String = "closeable-work"
    override def close(): Unit = closed = true

  test("derives DurableEphemeralResource on Closeable type calls close() automatically") {
    val workflowId = WorkflowId("closeable-derived-test")
    val resource = new CloseableResource

    val workflow = async[Durable] {
      // The derived DurableEphemeralResource for CloseableResource has release = _.close()
      val r: CloseableResource = resource
      r.doWork()
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "closeable-work")
          assert(resource.closed, "Closeable resource should have close() called automatically via DurableEphemeralResource.derived")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  // Also test that non-Closeable derived does NOT wrap in resource
  class NonCloseableService derives DurableEphemeral:
    def doWork(): String = "service-work"

  test("derives DurableEphemeral on non-Closeable type does NOT wrap in resource") {
    val workflowId = WorkflowId("non-closeable-derived-test")

    // This should compile and work - the derived DurableEphemeral is just a marker
    // and should NOT cause resource wrapping (no release method)
    val workflow = async[Durable] {
      val service: NonCloseableService = new NonCloseableService
      service.doWork()
    }

    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)
    val result = async[Future] {
      await(runner.run(workflow, ctx).map(_.toOption.get))
    }

    async[Future] {
      val res = await(result)
      res match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "service-work")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }
