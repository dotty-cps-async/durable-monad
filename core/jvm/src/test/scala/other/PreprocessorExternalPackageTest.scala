package other

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

// Import durable.* should bring in the preprocessor given
import durable.*

/**
 * Test that verifies the preprocessor works from an external package
 * with just `import durable.*` (no explicit DurableCpsPreprocessor import).
 */
class PreprocessorExternalPackageTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  test("preprocessor works from external package with import durable.*") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = RunContext.fresh(WorkflowId("external-package-test"))

    var computeCount = 0
    val workflow = async[Durable] {
      val x = {
        computeCount += 1
        42
      }
      x + 1
    }

    // First run - should compute
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(43))
      assertEquals(computeCount, 1)

      // Second run - should replay from cache (preprocessor wrapped val as activity)
      // Resume from index 1 (after the first activity)
      computeCount = 0
      val ctx2 = RunContext.resume(WorkflowId("external-package-test"), 1)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(43))
        assertEquals(computeCount, 0, "Should not recompute on replay - preprocessor should have wrapped val as activity")
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
    val ctx = RunContext.fresh(WorkflowId("external-countdown"))

    WorkflowRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowResult.ContinueAs(metadata, _, _) =>
          assert(metadata.functionName.contains("ExternalCountdown"))
        case other =>
          fail(s"Expected ContinueAs, got $other")
    }
  }
