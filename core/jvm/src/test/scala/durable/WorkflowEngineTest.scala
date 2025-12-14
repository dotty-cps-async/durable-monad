package durable

import munit.FunSuite
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*
import java.time.Instant

/**
 * Tests for WorkflowEngine.
 *
 * Uses MemoryBackingStore for fast in-memory testing.
 */
class WorkflowEngineTest extends FunSuite:
  given ExecutionContext = ExecutionContext.global

  // Test workflow: simple computation
  object SimpleWorkflow extends DurableFunction[Tuple1[Int], Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    def apply(args: Tuple1[Int])(using
      backend: MemoryBackingStore,
      argsStorage: TupleDurableStorage[Tuple1[Int], MemoryBackingStore],
      resultStorage: DurableStorage[Int, MemoryBackingStore]
    ): Durable[Int] =
      val Tuple1(n) = args
      Durable.activity(Future.successful(n * 2))

  // Test workflow: suspends on event (uses String as event type, so resultStorage works)
  object EventWorkflow extends DurableFunction[Tuple1[String], String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    def apply(args: Tuple1[String])(using
      backend: MemoryBackingStore,
      argsStorage: TupleDurableStorage[Tuple1[String], MemoryBackingStore],
      resultStorage: DurableStorage[String, MemoryBackingStore]
    ): Durable[String] =
      // Use resultStorage for event since event type is String
      given DurableEventName[String] = DurableEventName("test-signal")
      val Tuple1(input) = args
      for
        step1 <- Durable.activity(Future.successful(s"processed: $input"))
        signal <- Durable.awaitEvent[String, MemoryBackingStore]
        result <- Durable.activity(Future.successful(s"$step1 - $signal"))
      yield result

  // Test workflow: suspends on timer, returns wake time
  object TimerWorkflow extends DurableFunction[Tuple1[Long], Instant, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    def apply(args: Tuple1[Long])(using
      backend: MemoryBackingStore,
      argsStorage: TupleDurableStorage[Tuple1[Long], MemoryBackingStore],
      resultStorage: DurableStorage[Instant, MemoryBackingStore]
    ): Durable[Instant] =
      val Tuple1(delayMs) = args
      // resultStorage is DurableStorage[Instant, MemoryBackingStore], which is what sleepUntil needs
      Durable.sleepUntil(Instant.now().plusMillis(delayMs))

  // Test workflow: fails
  object FailingWorkflow extends DurableFunction[EmptyTuple, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunctionName.ofAndRegister(this)

    def apply(args: EmptyTuple)(using
      backend: MemoryBackingStore,
      argsStorage: TupleDurableStorage[EmptyTuple, MemoryBackingStore],
      resultStorage: DurableStorage[String, MemoryBackingStore]
    ): Durable[String] =
      Durable.activity(Future.failed(new RuntimeException("intentional failure")))

  // Event type for testing
  case class TestSignal(value: String)
  given DurableEventName[TestSignal] = DurableEventName("test-signal")

  def createEngine(): (MemoryBackingStore, WorkflowEngine[MemoryBackingStore]) =
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    val engine = WorkflowEngine(storage)
    (storage, engine)

  test("start simple workflow and query status") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(SimpleWorkflow, Tuple1(21))
      // Give workflow time to complete
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
    yield (workflowId, status)

    val (id, status) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
  }

  test("workflow suspends on event") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("hello"))
      // Give workflow time to reach suspension
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
    yield (workflowId, status)

    val (id, status) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Suspended))
  }

  test("cancel running workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("test"))
      // Wait for suspension
      _ <- Future(Thread.sleep(100))
      cancelled <- engine.cancel(workflowId)
      status <- engine.queryStatus(workflowId)
    yield (cancelled, status)

    val (cancelled, status) = Await.result(result, 5.seconds)
    assertEquals(cancelled, true)
    assertEquals(status, Some(WorkflowStatus.Cancelled))
  }

  test("failing workflow has Failed status") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(FailingWorkflow, EmptyTuple)
      // Give workflow time to fail
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
    yield status

    val status = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Failed))
  }

  test("workflow suspends on timer") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))  // 5 second delay
      // Give workflow time to reach suspension
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
    yield status

    val status = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Suspended))
  }

  test("custom workflowId is used") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val customId = WorkflowId("my-custom-id-123")
    val result = for
      workflowId <- engine.start(SimpleWorkflow, Tuple1(5), Some(customId))
    yield workflowId

    val id = Await.result(result, 5.seconds)
    assertEquals(id, customId)
  }

  test("workflow args are stored at indices 0..argCount-1") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(SimpleWorkflow, Tuple1(42))
      // Check that arg was stored at index 0
      stored <- storage.forType[Int].retrieve(storage, workflowId, 0)
    yield stored

    val stored = Await.result(result, 5.seconds)
    assertEquals(stored, Some(Right(42)))
  }

  // Tests that require TODO implementations - marked pending

  test("sendEvent wakes suspended workflow".ignore) {
    // TODO: Requires recreateAndResume implementation
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("hello"))
      // Wait for suspension
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      // Send event
      _ <- engine.sendEvent(TestSignal("world"))
      // Wait for completion
      _ <- Future(Thread.sleep(200))
      status2 <- engine.queryStatus(workflowId)
    yield (status1, status2)

    val (status1, status2) = Await.result(result, 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))
    assertEquals(status2, Some(WorkflowStatus.Succeeded))
  }

  test("queryResult returns result for completed workflow".ignore) {
    // TODO: Requires queryResult implementation
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(SimpleWorkflow, Tuple1(21))
      // Wait for completion
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
      queryResult <- engine.queryResult[Int](workflowId)
    yield (status, queryResult)

    val (status, queryResult) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
    assertEquals(queryResult, Some(42))  // 21 * 2
  }

  test("queryResult returns None for pending workflow".ignore) {
    // TODO: Requires queryResult implementation
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("test"))
      // Wait for suspension (not completion)
      _ <- Future(Thread.sleep(100))
      queryResult <- engine.queryResult[String](workflowId)
    yield queryResult

    val queryResult = Await.result(result, 5.seconds)
    assertEquals(queryResult, None)
  }

  test("recover restores suspended workflows".ignore) {
    // TODO: Requires full implementation
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val engine1 = WorkflowEngine(storage)

    // Start workflow, let it suspend
    val workflowId = Await.result(engine1.start(EventWorkflow, Tuple1("recover-test")), 5.seconds)
    Thread.sleep(100)

    // Shutdown engine1
    Await.result(engine1.shutdown(), 5.seconds)

    // Create new engine and recover
    val engine2 = WorkflowEngine(storage)
    val report = Await.result(engine2.recover(), 5.seconds)

    assertEquals(report.activeWorkflows, 1)
    assertEquals(report.resumedSuspended, 1)

    // Verify workflow status is restored
    val status = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Suspended))
  }
