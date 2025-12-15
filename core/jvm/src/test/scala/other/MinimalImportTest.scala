package other

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite
import cps.*

// Minimal imports - only what's needed
import durable.Durable
import durable.MemoryBackingStore
import durable.WorkflowRunner
import durable.RunContext
import durable.WorkflowId
import durable.WorkflowResult

/**
 * Test that verifies the preprocessor works with minimal imports.
 * Uses `import durable.Durable` instead of `import durable.*`.
 */
class MinimalImportTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  test("preprocessor works with import durable.Durable (not durable.*)") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val ctx = RunContext.fresh(WorkflowId("minimal-import-test"))

    var computeCount = 0
    val workflow = async[Durable] {
      val x = {
        computeCount += 1
        42
      }
      x + 1
    }

    // First run - should compute and cache
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(43))
      assertEquals(computeCount, 1)
      // Verify preprocessor cached the val as activity
      assert(backing.size > 0, "Preprocessor should create activities")
    }
  }
