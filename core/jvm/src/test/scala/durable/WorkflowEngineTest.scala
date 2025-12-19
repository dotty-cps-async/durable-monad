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

  import MemoryBackingStore.given

  // Test workflow: simple computation
  object SimpleWorkflow extends DurableFunction1[Int, Int, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(n: Int)(using MemoryBackingStore): Durable[Int] =
      Durable.activity(Future.successful(n * 2))

  // Test workflow: suspends on event (uses String as event type, so resultStorage works)
  object EventWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(input: String)(using MemoryBackingStore): Durable[String] =
      // Use resultStorage for event since event type is String
      given DurableEventName[String] = DurableEventName("test-signal")
      for
        step1 <- Durable.activity(Future.successful(s"processed: $input"))
        signal <- Durable.awaitEvent[String, MemoryBackingStore]
        result <- Durable.activity(Future.successful(s"$step1 - $signal"))
      yield result

  // Test workflow: suspends on timer, returns wake time
  object TimerWorkflow extends DurableFunction1[Long, Instant, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(delayMs: Long)(using MemoryBackingStore): Durable[Instant] =
      // resultStorage is DurableStorage[Instant, MemoryBackingStore], which is what sleepUntil needs
      Durable.sleepUntil(Instant.now().plusMillis(delayMs))

  // Test workflow: fails
  object FailingWorkflow extends DurableFunction0[String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply()(using MemoryBackingStore): Durable[String] =
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
      stored <- storage.forType[Int].retrieveStep(storage, workflowId, 0)
    yield stored

    val stored = Await.result(result, 5.seconds)
    assertEquals(stored, Some(Right(42)))
  }

  test("sendEvent wakes suspended workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    // EventWorkflow awaits String events with name "test-signal"
    given DurableEventName[String] = DurableEventName("test-signal")

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("hello"))
      // Wait for suspension
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      // Send String event (matching what EventWorkflow awaits)
      _ <- engine.sendEventBroadcast("world")
      // Wait for completion
      _ <- Future(Thread.sleep(200))
      status2 <- engine.queryStatus(workflowId)
    yield (status1, status2)

    val (status1, status2) = Await.result(result, 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))
    assertEquals(status2, Some(WorkflowStatus.Succeeded))
  }

  test("queryResult returns result for completed workflow") {
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

  test("queryResult returns None for pending workflow") {
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

  test("recover restores suspended workflows") {
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

  // ===== sendEventTo tests =====

  test("sendEventTo wakes suspended workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-signal")

    val result = for
      workflowId <- engine.start(EventWorkflow, Tuple1("hello"))
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      // Send event to specific workflow
      _ <- engine.sendEventTo(workflowId, "targeted-world")
      _ <- Future(Thread.sleep(200))
      status2 <- engine.queryStatus(workflowId)
    yield (status1, status2)

    val (status1, status2) = Await.result(result, 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))
    assertEquals(status2, Some(WorkflowStatus.Succeeded))
  }

  test("sendEventTo throws WorkflowNotFoundException for non-existent workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-signal")

    val nonExistentId = WorkflowId("non-existent-id")
    val result = engine.sendEventTo(nonExistentId, "test-event")

    val exception = intercept[WorkflowNotFoundException] {
      Await.result(result, 5.seconds)
    }
    assertEquals(exception.workflowId, nonExistentId)
  }

  test("sendEventTo throws WorkflowTerminatedException for completed workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-signal")

    val result = for
      workflowId <- engine.start(SimpleWorkflow, Tuple1(21))
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
      _ = assertEquals(status, Some(WorkflowStatus.Succeeded))
      // Try to send event to completed workflow
      sendResult <- engine.sendEventTo(workflowId, "too-late")
    yield sendResult

    val exception = intercept[WorkflowTerminatedException] {
      Await.result(result, 5.seconds)
    }
    assertEquals(exception.status, WorkflowStatus.Succeeded)
  }

  test("sendEventTo queues event when workflow is not waiting for it") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-signal")

    val result = for
      // Start workflow on timer (not waiting for event)
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))  // 5 second timer
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      _ = assertEquals(status1, Some(WorkflowStatus.Suspended))
      // Send event - should be queued since workflow is waiting for timer, not event
      _ <- engine.sendEventTo(workflowId, "queued-event")
      // Event should be queued, not delivered - workflow still suspended on timer
      status2 <- engine.queryStatus(workflowId)
    yield status2

    val status2 = Await.result(result, 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Suspended))
  }

  // ===== Dead letter handling tests =====

  // Event type with MoveToDeadLetter policy for testing
  case class CriticalAlert(message: String)
  object CriticalAlert:
    given DurableEventName[CriticalAlert] = DurableEventName("critical-alert")
    given DurableEventConfig[CriticalAlert] with
      override def onTargetTerminated = DeadLetterPolicy.MoveToDeadLetter

  // Event type with MoveToBroadcast policy for testing
  case class RecoverableEvent(data: String)
  object RecoverableEvent:
    given DurableEventName[RecoverableEvent] = DurableEventName("recoverable-event")
    given DurableEventConfig[RecoverableEvent] with
      override def onTargetTerminated = DeadLetterPolicy.MoveToBroadcast

  test("queued event is discarded by default when workflow is cancelled") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-signal")

    val result = for
      // Use TimerWorkflow so it stays suspended
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      _ = assertEquals(status1, Some(WorkflowStatus.Suspended))
      // Queue an event (workflow is suspended waiting for timer, not event)
      _ <- engine.sendEventTo(workflowId, "will-be-discarded")
      // Cancel the workflow to trigger termination processing
      _ <- engine.cancel(workflowId)
      status2 <- engine.queryStatus(workflowId)
      // Default policy is Discard - event should be gone
      deadLetters <- engine.queryDeadLetters[String]
    yield (status2, deadLetters)

    val (status2, deadLetters) = Await.result(result, 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Cancelled))
    assertEquals(deadLetters.size, 0)  // Event was discarded
  }

  test("queued event moves to dead letter queue when workflow is cancelled with MoveToDeadLetter policy") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import CriticalAlert.given

    val result = for
      // Use TimerWorkflow so it stays suspended
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))
      _ <- Future(Thread.sleep(100))
      status1 <- engine.queryStatus(workflowId)
      _ = assertEquals(status1, Some(WorkflowStatus.Suspended))
      // Queue an event with MoveToDeadLetter policy
      _ <- engine.sendEventTo(workflowId, CriticalAlert("important message"))
      // Cancel the workflow to trigger dead letter processing
      _ <- engine.cancel(workflowId)
      status2 <- engine.queryStatus(workflowId)
      deadLetters <- engine.queryDeadLetters[CriticalAlert]
    yield (status2, deadLetters, workflowId)

    val (status2, deadLetters, workflowId) = Await.result(result, 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Cancelled))
    assertEquals(deadLetters.size, 1)
    assertEquals(deadLetters.head.value.message, "important message")
    assertEquals(deadLetters.head.originalTarget, workflowId)
    assertEquals(deadLetters.head.targetStatus, WorkflowStatus.Cancelled)
  }

  test("removeDeadLetter removes event from dead letter queue") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import CriticalAlert.given

    val result = for
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))
      _ <- Future(Thread.sleep(100))
      _ <- engine.sendEventTo(workflowId, CriticalAlert("to-remove"))
      _ <- engine.cancel(workflowId)
      deadLetters1 <- engine.queryDeadLetters[CriticalAlert]
      _ = assertEquals(deadLetters1.size, 1)
      eventId = deadLetters1.head.eventId
      removed <- engine.removeDeadLetter(eventId)
      deadLetters2 <- engine.queryDeadLetters[CriticalAlert]
    yield (removed, deadLetters2)

    val (removed, deadLetters2) = Await.result(result, 5.seconds)
    assertEquals(removed, true)
    assertEquals(deadLetters2.size, 0)
  }

  test("replayDeadLetter moves event to broadcast queue") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import CriticalAlert.given

    val result = for
      workflowId <- engine.start(TimerWorkflow, Tuple1(5000L))
      _ <- Future(Thread.sleep(100))
      _ <- engine.sendEventTo(workflowId, CriticalAlert("to-replay"))
      _ <- engine.cancel(workflowId)
      deadLetters1 <- engine.queryDeadLetters[CriticalAlert]
      _ = assertEquals(deadLetters1.size, 1)
      eventId = deadLetters1.head.eventId
      replayed <- engine.replayDeadLetter(eventId)
      deadLetters2 <- engine.queryDeadLetters[CriticalAlert]
    yield (replayed, deadLetters2)

    val (replayed, deadLetters2) = Await.result(result, 5.seconds)
    assertEquals(replayed, true)
    assertEquals(deadLetters2.size, 0)  // Event was removed from dead letter queue
  }
