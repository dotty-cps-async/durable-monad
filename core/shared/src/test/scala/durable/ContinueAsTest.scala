package durable

import munit.FunSuite
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global

class ContinueAsTest extends FunSuite:

  // Test workflows using unified DurableFunction[Args, R]
  object CounterWorkflow extends DurableFunction[Tuple1[Int], Int] derives DurableFunctionName:
    override val functionName: String = DurableFunctionName.ofAndRegister(this)

    def apply[S <: DurableStorageBackend](args: Tuple1[Int])(using
      backend: S,
      argsStorage: TupleDurableStorage[Tuple1[Int], S],
      resultStorage: DurableStorage[Int, S]
    ): Durable[Int] =
      val Tuple1(count) = args
      if count <= 0 then
        Durable.pure(count)
      else
        val newCount = count - 1
        Durable.continueAs(functionName, Tuple1(newCount), apply(Tuple1(newCount)))

  object SwitchWorkflow extends DurableFunction[Tuple1[String], String] derives DurableFunctionName:
    override val functionName: String = DurableFunctionName.ofAndRegister(this)

    def apply[S <: DurableStorageBackend](args: Tuple1[String])(using
      backend: S,
      argsStorage: TupleDurableStorage[Tuple1[String], S],
      resultStorage: DurableStorage[String, S]
    ): Durable[String] =
      val Tuple1(input) = args
      Durable.pure(s"Switched to: $input")

  object TransitionWorkflow extends DurableFunction[Tuple1[Int], String] derives DurableFunctionName:
    override val functionName: String = DurableFunctionName.ofAndRegister(this)

    def apply[S <: DurableStorageBackend](args: Tuple1[Int])(using
      backend: S,
      argsStorage: TupleDurableStorage[Tuple1[Int], S],
      resultStorage: DurableStorage[String, S]
    ): Durable[String] =
      val Tuple1(n) = args
      if n > 0 then
        val newArg = s"from-$n"
        // TupleDurableStorage[Tuple1[String], S] is derived automatically from DurableStorage[String, S]
        Durable.continueAs(SwitchWorkflow.functionName, Tuple1(newArg), SwitchWorkflow(Tuple1(newArg)))
      else
        Durable.pure("stayed")

  test("continueAs returns ContinueAs result") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val workflow = CounterWorkflow(Tuple1(3))
    val ctx = RunContext.fresh(WorkflowId("test-continue-as-1"))

    val result = WorkflowRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowResult.ContinueAs(metadata, _, _, _) =>
        assertEquals(metadata.functionName, "durable.ContinueAsTest.CounterWorkflow")
        assertEquals(metadata.argCount, 1)
        assertEquals(metadata.activityIndex, 1)
      case other =>
        fail(s"Expected ContinueAs, got $other")
  }

  test("continueAs to different workflow") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val workflow = TransitionWorkflow(Tuple1(5))
    val ctx = RunContext.fresh(WorkflowId("test-transition-1"))

    val result = WorkflowRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowResult.ContinueAs(metadata, _, _, _) =>
        assertEquals(metadata.functionName, "durable.ContinueAsTest.SwitchWorkflow")
        assertEquals(metadata.argCount, 1)
      case other =>
        fail(s"Expected ContinueAs, got $other")
  }

  test("storeArgs stores argument correctly") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val workflow = CounterWorkflow(Tuple1(5))
    val workflowId = WorkflowId("test-store-args-1")
    val ctx = RunContext.fresh(workflowId)

    for
      result <- WorkflowRunner.run(workflow, ctx)
      _ <- result match
        case WorkflowResult.ContinueAs(_, storeArgs, _, _) =>
          for
            _ <- storeArgs(workflowId, global)
            stored <- backing.forType[Int].retrieve(workflowId, 0)
          yield assertEquals(stored, Some(Right(4))) // 5 - 1 = 4
        case other =>
          Future.failed(AssertionError(s"Expected ContinueAs, got $other"))
    yield ()
  }

  test("workflow completes when count reaches zero") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val workflow = CounterWorkflow(Tuple1(0))
    val ctx = RunContext.fresh(WorkflowId("test-complete-1"))

    val result = WorkflowRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowResult.Completed(value) =>
        assertEquals(value, 0)
      case other =>
        fail(s"Expected Completed, got $other")
  }

  test("WorkflowMetadata has correct structure") {
    val metadata = WorkflowMetadata("test.Workflow", 2, 5)
    assertEquals(metadata.functionName, "test.Workflow")
    assertEquals(metadata.argCount, 2)
    assertEquals(metadata.activityIndex, 5)
  }

  test("WorkflowStatus enum has correct values") {
    val statuses = WorkflowStatus.values
    assertEquals(statuses.length, 4)
    assert(statuses.contains(WorkflowStatus.Running))
    assert(statuses.contains(WorkflowStatus.Suspended))
    assert(statuses.contains(WorkflowStatus.Completed))
    assert(statuses.contains(WorkflowStatus.Failed))
  }

  test("TupleDurableStorage stores tuple elements") {
    val backing = MemoryBackingStore()
    given MemoryBackingStore = backing
    given [T]: DurableStorage[T, MemoryBackingStore] = backing.forType[T]

    val tupleStorage = summon[TupleDurableStorage[(String, Int), MemoryBackingStore]]
    val workflowId = WorkflowId("test-tuple-storage")

    for
      _ <- tupleStorage.storeAll(workflowId, 0, ("hello", 42))
      storedString <- backing.forType[String].retrieve(workflowId, 0)
      storedInt <- backing.forType[Int].retrieve(workflowId, 1)
    yield
      assertEquals(storedString, Some(Right("hello")))
      assertEquals(storedInt, Some(Right(42)))
  }
