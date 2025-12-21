package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}
import durable.runtime.DurableFunctionRegistry

/**
 * Tests for DurableFunction - serializable workflow definitions.
 */
class DurableFunctionTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  // Provide storage via given (MemoryBackingStore pattern)
  import MemoryBackingStore.given
  val backing: MemoryBackingStore = MemoryBackingStore()
  given MemoryBackingStore = backing

  // Test workflow definitions using unified DurableFunction[Args, R, S]
  object SimpleWorkflow extends DurableFunction0[Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    override def apply()(using MemoryBackingStore): Durable[Int] =
      Durable.pure(42)

  object GreetingWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    override def apply(name: String)(using MemoryBackingStore): Durable[String] =
      Durable.pure(s"Hello, $name!")

  object AddWorkflow extends DurableFunction2[Int, Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    override def apply(a: Int, b: Int)(using MemoryBackingStore): Durable[Int] =
      Durable.pure(a + b)

  object SumThreeWorkflow extends DurableFunction3[Int, Int, Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    override def apply(a: Int, b: Int, c: Int)(using MemoryBackingStore): Durable[Int] =
      Durable.pure(a + b + c)

  test("DurableFunctionName derived with full package name") {
    // Full name includes package path
    assertEquals(SimpleWorkflow.functionName.value, "durable.DurableFunctionTest.SimpleWorkflow")
    assertEquals(GreetingWorkflow.functionName.value, "durable.DurableFunctionTest.GreetingWorkflow")
    assertEquals(AddWorkflow.functionName.value, "durable.DurableFunctionTest.AddWorkflow")
    assertEquals(SumThreeWorkflow.functionName.value, "durable.DurableFunctionTest.SumThreeWorkflow")
  }

  test("DurableFunction auto-registers on object access") {
    // Accessing the object triggers initialization and registration
    val _ = SimpleWorkflow.functionName
    val _ = GreetingWorkflow.functionName

    assert(DurableFunctionRegistry.global.lookup("durable.DurableFunctionTest.SimpleWorkflow").isDefined)
    assert(DurableFunctionRegistry.global.lookup("durable.DurableFunctionTest.GreetingWorkflow").isDefined)
  }

  test("DurableFunctionRegistry lookup returns correct function") {
    // Force initialization
    val _ = AddWorkflow.functionName

    val lookupResult = DurableFunctionRegistry.global.lookup("durable.DurableFunctionTest.AddWorkflow")
    assert(lookupResult.isDefined)
    assert(lookupResult.get.function eq AddWorkflow)
  }

  test("DurableFunctionRegistry lookupTyped returns typed function") {
    // Force initialization
    val _ = SumThreeWorkflow.functionName

    val lookupResult = DurableFunctionRegistry.global.lookupTyped[(Int, Int, Int), Int, MemoryBackingStore](
      "durable.DurableFunctionTest.SumThreeWorkflow"
    )
    assert(lookupResult.isDefined)
    // Can call applyTupled with correct types
    val durable = lookupResult.get.applyTupled((1, 2, 3))
    assert(durable.isInstanceOf[Durable[Int]])
  }

  test("DurableFunctionRegistry returns None for unknown function") {
    val lookupResult = DurableFunctionRegistry.global.lookup("com.example.NonExistentWorkflow")
    assert(lookupResult.isEmpty)
  }

  test("DurableFunction0 apply returns Durable") {
    val durable = SimpleWorkflow.apply()
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction1 apply returns Durable") {
    val durable = GreetingWorkflow.apply("World")
    durable match
      case Durable.Pure("Hello, World!") => () // ok
      case _ => fail("Expected Pure(\"Hello, World!\")")
  }

  test("DurableFunction2 apply returns Durable") {
    val durable = AddWorkflow.apply(10, 32)
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction3 apply returns Durable") {
    val durable = SumThreeWorkflow.apply(10, 20, 12)
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction workflow can be run") {
    val workflow = GreetingWorkflow.apply("Claude")
    val workflowId = WorkflowId("test-fn-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, "Hello, Claude!"))
    }
  }

  test("DurableFunction with activity is cached") {
    var executeCount = 0

    object ActivityWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
      override val functionName = DurableFunction.register(this)

      override def apply(input: String)(using MemoryBackingStore): Durable[String] =
        Durable.activity {
          executeCount += 1
          Future.successful(s"Processed: $input")
        }

    val workflow = ActivityWorkflow.apply("test")
    val workflowId = WorkflowId("test-fn-2")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, "Processed: test"))
      assertEquals(executeCount, 1)
    }
  }
