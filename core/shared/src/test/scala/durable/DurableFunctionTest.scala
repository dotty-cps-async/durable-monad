package durable

import scala.concurrent.{Future, ExecutionContext}
import munit.FunSuite

/**
 * Tests for DurableFunction - serializable workflow definitions.
 */
class DurableFunctionTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  // Provide storage via given (MemoryBackingStore pattern)
  val backing: MemoryBackingStore = MemoryBackingStore()
  given MemoryBackingStore = backing
  given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

  // Test workflow definitions using unified DurableFunction[Args, R]
  object SimpleWorkflow extends DurableFunction[EmptyTuple, Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply[S <: DurableStorageBackend](args: EmptyTuple)(using
      S, TupleDurableStorage[EmptyTuple, S], DurableStorage[Int, S]
    ): Durable[Int] =
      Durable.pure(42)

  object GreetingWorkflow extends DurableFunction[Tuple1[String], String] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply[S <: DurableStorageBackend](args: Tuple1[String])(using
      S, TupleDurableStorage[Tuple1[String], S], DurableStorage[String, S]
    ): Durable[String] =
      val Tuple1(name) = args
      Durable.pure(s"Hello, $name!")

  object AddWorkflow extends DurableFunction[(Int, Int), Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply[S <: DurableStorageBackend](args: (Int, Int))(using
      S, TupleDurableStorage[(Int, Int), S], DurableStorage[Int, S]
    ): Durable[Int] =
      val (a, b) = args
      Durable.pure(a + b)

  object SumThreeWorkflow extends DurableFunction[(Int, Int, Int), Int] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    override def apply[S <: DurableStorageBackend](args: (Int, Int, Int))(using
      S, TupleDurableStorage[(Int, Int, Int), S], DurableStorage[Int, S]
    ): Durable[Int] =
      val (a, b, c) = args
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

    val lookupResult = DurableFunctionRegistry.global.lookupTyped[(Int, Int, Int), Int](
      "durable.DurableFunctionTest.SumThreeWorkflow"
    )
    assert(lookupResult.isDefined)
    // Can call apply with correct types
    val durable = lookupResult.get.apply((1, 2, 3))
    assert(durable.isInstanceOf[Durable[Int]])
  }

  test("DurableFunctionRegistry returns None for unknown function") {
    val lookupResult = DurableFunctionRegistry.global.lookup("com.example.NonExistentWorkflow")
    assert(lookupResult.isEmpty)
  }

  test("DurableFunction0 apply returns Durable") {
    val durable = SimpleWorkflow.apply(EmptyTuple)
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction1 apply returns Durable") {
    val durable = GreetingWorkflow.apply(Tuple1("World"))
    durable match
      case Durable.Pure("Hello, World!") => () // ok
      case _ => fail("Expected Pure(\"Hello, World!\")")
  }

  test("DurableFunction2 apply returns Durable") {
    val durable = AddWorkflow.apply((10, 32))
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction3 apply returns Durable") {
    val durable = SumThreeWorkflow.apply((10, 20, 12))
    durable match
      case Durable.Pure(42) => () // ok
      case _ => fail("Expected Pure(42)")
  }

  test("DurableFunction workflow can be run") {
    val workflow = GreetingWorkflow.apply(Tuple1("Claude"))
    val ctx = RunContext.fresh(WorkflowId("test-fn-1"))

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed("Hello, Claude!"))
    }
  }

  test("DurableFunction with activity is cached") {
    var executeCount = 0

    object ActivityWorkflow extends DurableFunction[Tuple1[String], String] derives DurableFunctionName:
      override val functionName = DurableFunctionName.ofAndRegister(this)

      override def apply[S <: DurableStorageBackend](args: Tuple1[String])(using
        S, TupleDurableStorage[Tuple1[String], S], DurableStorage[String, S]
      ): Durable[String] =
        val Tuple1(input) = args
        Durable.activity {
          executeCount += 1
          Future.successful(s"Processed: $input")
        }

    val workflow = ActivityWorkflow.apply(Tuple1("test"))
    val ctx = RunContext.fresh(WorkflowId("test-fn-2"))

    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed("Processed: test"))
      assertEquals(executeCount, 1)
    }
  }
