package other

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

// Import durable.* should bring in the preprocessor given
import durable.*
import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Test that verifies the preprocessor works from an external package
 * with just `import durable.*` (no explicit DurableCpsPreprocessor import).
 */
class PreprocessorExternalPackageTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given
  private val runner = WorkflowSessionRunner.forFuture

  test("preprocessor works from external package with import durable.*") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("external-package-test"))

    val computeCount = TestCounter()
    val workflow = async[Durable] {
      val x = {
        computeCount.increment()
        42
      }
      x + 1
    }

    // First run - should compute
    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 43))
      assertEquals(computeCount.get, 1)

      // Second run - should replay from cache (preprocessor wrapped val as activity)
      // Resume from index 1 (after the first activity)
      computeCount.reset()
      val ctx2 = WorkflowSessionRunner.RunContext.resume(WorkflowId("external-package-test"), 1, 0)
      runner.run(workflow, ctx2).map(_.toOption.get).map { result2 =>
        assertEquals(result2, WorkflowSessionResult.Completed(ctx.workflowId, 43))
        assertEquals(computeCount.get, 0, "Should not recompute on replay - preprocessor should have wrapped val as activity")
      }
    }.flatten
  }

  test("continueWith works from external package") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    object ExternalCountdown extends DurableFunction1[Int, Int, MemoryBackingStore] derives DurableFunctionName:
      override val functionName = DurableFunction.register(this)

      def apply(count: Int)(using MemoryBackingStore): Durable[Int] = async[Durable] {
        if count <= 0 then count
        else await(continueWith(count - 1))
      }

    val workflow = ExternalCountdown(3)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("external-countdown"))

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assert(metadata.functionName.contains("ExternalCountdown"))
        case other =>
          fail(s"Expected ContinueAs, got $other")
    }
  }
