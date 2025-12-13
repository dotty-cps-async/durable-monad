package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

/**
 * Tests for DurableFunction - serializable workflow definitions.
 */
class DurableFunctionTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  // Provide storage via given (MemoryBackingStore pattern)
  given backing: MemoryBackingStore = MemoryBackingStore()
  given [T]: DurableStorage[T] = backing.forType[T]

  // Test workflow definitions - use ofAndRegister to get name and register in one call
  object SimpleWorkflow extends DurableFunction0[Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply()(using storageR: DurableStorage[Int]): Durable[Int] =
      Durable.pure(42)

  object GreetingWorkflow extends DurableFunction1[String, String] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply(name: String)(using storageT1: DurableStorage[String], storageR: DurableStorage[String]): Durable[String] =
      Durable.pure(s"Hello, $name!")

  object AddWorkflow extends DurableFunction2[Int, Int, Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply(a: Int, b: Int)(using storageT1: DurableStorage[Int], storageT2: DurableStorage[Int], storageR: DurableStorage[Int]): Durable[Int] =
      Durable.pure(a + b)

  object SumThreeWorkflow extends DurableFunction3[Int, Int, Int, Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply(a: Int, b: Int, c: Int)(using storageT1: DurableStorage[Int], storageT2: DurableStorage[Int], storageT3: DurableStorage[Int], storageR: DurableStorage[Int]): Durable[Int] =
      Durable.pure(a + b + c)

  test("DurableFunctionName derived with full package name") {
    // Full name includes package path
    assertEquals(SimpleWorkflow.functionName, "durable.DurableFunctionTest.SimpleWorkflow")
    assertEquals(GreetingWorkflow.functionName, "durable.DurableFunctionTest.GreetingWorkflow")
    assertEquals(AddWorkflow.functionName, "durable.DurableFunctionTest.AddWorkflow")
    assertEquals(SumThreeWorkflow.functionName, "durable.DurableFunctionTest.SumThreeWorkflow")
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
    assert(lookupResult.get eq AddWorkflow)
  }

  test("DurableFunctionRegistry lookupTyped returns typed function") {
    // Force initialization
    val _ = SumThreeWorkflow.functionName

    val lookupResult = DurableFunctionRegistry.global.lookupTyped[DurableFunction3[Int, Int, Int, Int]](
      "durable.DurableFunctionTest.SumThreeWorkflow"
    )
    assert(lookupResult.isDefined)
    // Can call apply with correct types
    val durable = lookupResult.get.apply(1, 2, 3)
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
    val ctx = RunContext.fresh(WorkflowId("test-fn-1"))

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed("Hello, Claude!"))
    }
  }

  test("DurableFunction with activity is cached") {
    var executeCount = 0

    object ActivityWorkflow extends DurableFunction1[String, String] derives DurableFunctionName:
      override val functionName = DurableFunctionName.ofAndRegister(this)

      override def apply(input: String)(using storageT1: DurableStorage[String], storageR: DurableStorage[String]): Durable[String] =
        given DurableStorage[String] = storageR  // re-export for Durable.activity
        Durable.activity {
          executeCount += 1
          Future.successful(s"Processed: $input")
        }

    val workflow = ActivityWorkflow.apply("test")
    val ctx = RunContext.fresh(WorkflowId("test-fn-2"))

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed("Processed: test"))
      assertEquals(executeCount, 1)
    }
  }
