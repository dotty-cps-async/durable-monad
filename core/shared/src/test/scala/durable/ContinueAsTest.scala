package durable

import munit.FunSuite
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global

class ContinueAsTest extends FunSuite:

  // Test workflows using unified DurableFunction[Args, R, S]
  import MemoryBackingStore.given

  object CounterWorkflow extends DurableFunction1[Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(count: Int)(using MemoryBackingStore): Durable[Int] =
      if count <= 0 then
        Durable.pure(count)
      else
        Durable.continueAs(this)(Tuple1(count - 1))

  object SwitchWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(input: String)(using MemoryBackingStore): Durable[String] =
      Durable.pure(s"Switched to: $input")

  object TransitionWorkflow extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[String] =
      if n > 0 then
        // Transition to a different workflow using continueAs
        Durable.continueAs(SwitchWorkflow)(Tuple1(s"from-$n"))
      else
        Durable.pure("stayed")

  test("continueAs returns ContinueAs result") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = CounterWorkflow(3)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-continue-as-1"))

    val result = WorkflowSessionRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
        assertEquals(metadata.functionName, "durable.ContinueAsTest.CounterWorkflow")
        assertEquals(metadata.argCount, 1)
        assertEquals(metadata.activityIndex, 1)
      case other =>
        fail(s"Expected ContinueAs, got $other")
  }

  test("continueAs to different workflow") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = TransitionWorkflow(5)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-transition-1"))

    val result = WorkflowSessionRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
        assertEquals(metadata.functionName, "durable.ContinueAsTest.SwitchWorkflow")
        assertEquals(metadata.argCount, 1)
      case other =>
        fail(s"Expected ContinueAs, got $other")
  }

  test("storeArgs stores argument correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = CounterWorkflow(5)
    val workflowId = WorkflowId("test-store-args-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    for
      result <- WorkflowSessionRunner.run(workflow, ctx)
      _ <- result match
        case WorkflowSessionResult.ContinueAs(_, storeArgs, _) =>
          for
            _ <- storeArgs(backing, workflowId, global)
            stored <- backing.forType[Int].retrieveStep(backing, workflowId, 0)
          yield assertEquals(stored, Some(Right(4))) // 5 - 1 = 4
        case other =>
          Future.failed(AssertionError(s"Expected ContinueAs, got $other"))
    yield ()
  }

  test("workflow completes when count reaches zero") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = CounterWorkflow(0)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-complete-1"))

    val result = WorkflowSessionRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowSessionResult.Completed(_, value) =>
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
    assertEquals(statuses.length, 5)
    assert(statuses.contains(WorkflowStatus.Running))
    assert(statuses.contains(WorkflowStatus.Suspended))
    assert(statuses.contains(WorkflowStatus.Succeeded))
    assert(statuses.contains(WorkflowStatus.Failed))
    assert(statuses.contains(WorkflowStatus.Cancelled))
  }

  test("TupleDurableStorage stores tuple elements") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val tupleStorage = summon[TupleDurableStorage[(String, Int), MemoryBackingStore]]
    val workflowId = WorkflowId("test-tuple-storage")

    for
      _ <- tupleStorage.storeAll(backing, workflowId, 0, ("hello", 42))
      storedString <- backing.forType[String].retrieveStep(backing, workflowId, 0)
      storedInt <- backing.forType[Int].retrieveStep(backing, workflowId, 1)
    yield
      assertEquals(storedString, Some(Right("hello")))
      assertEquals(storedInt, Some(Right(42)))
  }

  // ==========================================================================
  // Tests for continueWith helper method and preprocessor integration
  // ==========================================================================

  import cps.*

  /**
   * Example: Countdown workflow using continueWith with preprocessor.
   * Each iteration is a new workflow run, preventing unbounded history growth.
   */
  object CountdownWithPreprocessor extends DurableFunction1[Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(count: Int)(using MemoryBackingStore): Durable[Int] = async[Durable] {
      if count <= 0 then
        count
      else
        // Using extension syntax - no Tuple1 wrapping needed
        // await is required because continueWith returns Durable[R]
        await(continueWith(count - 1))
    }

  test("continueWith with preprocessor - countdown pattern") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = CountdownWithPreprocessor(3)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-countdown-preprocessor"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assertEquals(metadata.functionName, "durable.ContinueAsTest.CountdownWithPreprocessor")
          assertEquals(metadata.argCount, 1)
        case other =>
          fail(s"Expected ContinueAs, got $other")
    }
  }

  test("continueWith with preprocessor - completes at zero") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = CountdownWithPreprocessor(0)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-countdown-zero"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, 0)
        case other =>
          fail(s"Expected Completed(0), got $other")
    }
  }

  /**
   * Example: Accumulator workflow - demonstrates loop with state.
   * Sums numbers from n down to 0 using continueWith.
   */
  object AccumulatorWorkflow extends DurableFunction2[Int, Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(count: Int, acc: Int)(using MemoryBackingStore): Durable[Int] = async[Durable] {
      if count <= 0 then
        acc
      else
        // Using extension syntax for 2 args
        await(continueWith(count - 1, acc + count))
    }

  test("continueWith with loop accumulator pattern") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Start with (3, 0) - should accumulate 3 + 2 + 1 = 6
    val workflow = AccumulatorWorkflow(3, 0)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-accumulator"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, nextWorkflow) =>
          assertEquals(metadata.functionName, "durable.ContinueAsTest.AccumulatorWorkflow")
          assertEquals(metadata.argCount, 2)
          // nextWorkflow() should return the next iteration
          assert(nextWorkflow().isInstanceOf[Durable[?]])
        case other =>
          fail(s"Expected ContinueAs, got $other")
    }
  }

  /**
   * Example: Workflow with activity before continueWith.
   * Shows that activities are cached before the loop continues.
   */
  object ProcessAndContinue extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[String] = async[Durable] {
      // This activity is cached before continuing
      val processed = s"step-$n"
      if n <= 0 then
        processed
      else
        await(continueWith(n - 1))
    }

  test("continueWith after activity in preprocessor") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = ProcessAndContinue(2)
    val workflowId = WorkflowId("test-process-continue")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, storeArgs, _) =>
          // Verify activities were cached (preprocessor wraps multiple vals)
          // Expected: args extraction, processed string, condition
          assert(backing.size > 0, "Expected some activities to be cached")
          // Verify ContinueAs has correct metadata
          assertEquals(metadata.argCount, 1)
        case other =>
          fail(s"Expected ContinueAs, got $other")
    }
  }

  test("continueWith completes when condition is false") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = ProcessAndContinue(0)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-process-complete"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "step-0")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  /**
   * Example: Using base continueWith with explicit Tuple.
   * Shows the non-extension syntax.
   */
  object ExplicitTupleWorkflow extends DurableFunction1[Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[Int] =
      if n <= 0 then Durable.pure(n)
      else
        // Using base method with explicit Tuple1
        this.continueWith(Tuple1(n - 1))

  test("continueWith with explicit Tuple syntax") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = ExplicitTupleWorkflow(2)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-explicit-tuple"))

    val result = WorkflowSessionRunner.run(workflow, ctx).value.get.get

    result match
      case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
        assertEquals(metadata.functionName, "durable.ContinueAsTest.ExplicitTupleWorkflow")
      case other =>
        fail(s"Expected ContinueAs, got $other")
  }

  // ==========================================================================
  // Tests for continueAs extension syntax (switching to different workflow)
  // ==========================================================================

  /**
   * Example: Workflow that transitions to another using extension syntax.
   * Shows the cleaner OtherWorkflow.continueAs(arg) pattern.
   */
  object HandoffWorkflow extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[String] = async[Durable] {
      if n > 0 then
        // Extension syntax: target.continueAs(arg)
        await(SwitchWorkflow.continueAs(s"handoff-$n"))
      else
        "no-handoff"
    }

  test("continueAs extension syntax - transition to different workflow") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = HandoffWorkflow(5)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-handoff"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assertEquals(metadata.functionName, "durable.ContinueAsTest.SwitchWorkflow")
          assertEquals(metadata.argCount, 1)
        case other =>
          fail(s"Expected ContinueAs to SwitchWorkflow, got $other")
    }
  }

  test("continueAs extension syntax - completes when no transition") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = HandoffWorkflow(0)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-no-handoff"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, "no-handoff")
        case other =>
          fail(s"Expected Completed, got $other")
    }
  }

  /**
   * Example: Multi-step state machine using continueAs.
   * Shows workflow transitions with different argument types.
   */
  object StateA extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[String] = async[Durable] {
      if n > 5 then
        await(StateB.continueAs(s"value-$n", n))
      else
        s"stopped-at-$n"
    }

  object StateB extends DurableFunction2[String, Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(label: String, count: Int)(using MemoryBackingStore): Durable[String] =
      Durable.pure(s"$label-completed-$count")

  test("continueAs with different arity workflows") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = StateA(10)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-state-machine"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assertEquals(metadata.functionName, "durable.ContinueAsTest.StateB")
          assertEquals(metadata.argCount, 2)
        case other =>
          fail(s"Expected ContinueAs to StateB, got $other")
    }
  }

  /**
   * Example: Using the trait's continueAs method directly.
   * Shows that continueAs is available on DurableFunction trait itself.
   */
  object TraitMethodWorkflow extends DurableFunction1[Int, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[String] =
      if n > 0 then
        // Using the trait method directly: this.continueAs(target)(args)
        this.continueAs(SwitchWorkflow)(Tuple1(s"trait-method-$n"))
      else
        Durable.pure("no-transition")

  test("continueAs trait method - direct usage") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = TraitMethodWorkflow(3)
    val ctx = WorkflowSessionRunner.RunContext.fresh(WorkflowId("test-trait-method"))

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assertEquals(metadata.functionName, "durable.ContinueAsTest.SwitchWorkflow")
          assertEquals(metadata.argCount, 1)
        case other =>
          fail(s"Expected ContinueAs to SwitchWorkflow, got $other")
    }
  }
