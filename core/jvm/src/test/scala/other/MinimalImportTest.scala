package other

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

// Minimal imports - only what's needed
import durable.Durable
import durable.MemoryBackingStore
import durable.WorkflowId
import durable.TestCounter
import durable.engine.WorkflowSessionRunner
import durable.engine.WorkflowSessionRunner.RunContext
import durable.engine.WorkflowSessionResult

/**
 * Test that verifies the preprocessor works with minimal imports.
 * Uses `import durable.Durable` instead of `import durable.*`.
 */
class MinimalImportTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given
  private val runner = WorkflowSessionRunner.forFuture

  test("preprocessor works with import durable.Durable (not durable.*)") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("minimal-import-test"))

    val computeCount = TestCounter()
    val workflow = async[Durable] {
      val x = {
        computeCount.increment()
        42
      }
      x + 1
    }

    // First run - should compute and cache
    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 43))
      assertEquals(computeCount.get, 1)
      // Verify preprocessor cached the val as activity
      assert(backing.size > 0, "Preprocessor should create activities")
    }
  }
