package durable

import munit.FunSuite
import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration.*
import java.time.Instant

import durable.engine.{WorkflowMetadata, WorkflowRecord}

class LazyLoadingTest extends FunSuite {

  given ExecutionContext = ExecutionContext.global

  // Test workflow that waits for an event
  object EventWaitingWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(input: String)(using MemoryBackingStore): Durable[String] =
      given DurableEventName[String] = DurableEventName("test-event")
      for
        _ <- Durable.activity(Future.successful(s"started: $input"))
        event <- Durable.awaitEvent[String, MemoryBackingStore]
      yield s"received: $event"

  // Test workflow that waits for a timer
  object TimerWaitingWorkflow extends DurableFunction1[Long, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(delayMs: Long)(using s: MemoryBackingStore): Durable[String] =
      given DurableStorage[TimeReached, MemoryBackingStore] = s.forType[TimeReached]
      for
        _ <- Durable.activity(Future.successful("started"))
        _ <- Durable.sleep(delayMs.millis)
      yield "timer fired"

  // Simple workflow that completes immediately
  object SimpleWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)
    def apply(input: String)(using MemoryBackingStore): Durable[String] =
      Durable.activity(Future.successful(s"done: $input"))

  private def createEngine(
    storage: MemoryBackingStore,
    timerHorizon: FiniteDuration = 10.minutes,
    cacheTtl: FiniteDuration = 30.minutes
  ): WorkflowEngine[MemoryBackingStore] =
    given MemoryBackingStore = storage
    val config = WorkflowEngineConfig(
      timerHorizon = timerHorizon,
      timerSweepInterval = 1.hour, // Disable automatic sweep for tests
      cacheTtl = cacheTtl,
      cacheEvictionInterval = 1.hour // Disable automatic eviction for tests
    )
    WorkflowEngine(storage, config)

  // ===== Recovery Tests =====

  test("recovery does not load workflows waiting only for events") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val engine1 = createEngine(storage)

    // Start workflow, let it suspend waiting for event
    val workflowId = Await.result(engine1.start(EventWaitingWorkflow, Tuple1("test")), 5.seconds)
    Thread.sleep(100)

    // Verify suspended
    val status1 = Await.result(engine1.queryStatus(workflowId), 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))

    // Shutdown and create new engine
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage)

    // Recover
    val report = Await.result(engine2.recover(), 5.seconds)

    // With lazy loading, workflow waiting for events should NOT be loaded
    assertEquals(report.totalLoaded, 0)
    assertEquals(report.nearTimersLoaded, 0)
    assertEquals(report.runningResumed, 0)

    // But queryStatus should still work (loads from storage)
    val status2 = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Suspended))

    Await.result(engine2.shutdown(), 5.seconds)
  }

  test("recovery loads workflows with timers within horizon") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    // Use short timer horizon
    val engine1 = createEngine(storage, timerHorizon = 1.hour)

    // Start workflow with timer in 5 minutes (within 1 hour horizon)
    val workflowId = Await.result(
      engine1.start(TimerWaitingWorkflow, Tuple1(5.minutes.toMillis)),
      5.seconds
    )
    Thread.sleep(100)

    // Verify suspended
    val status1 = Await.result(engine1.queryStatus(workflowId), 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))

    // Shutdown and create new engine
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage, timerHorizon = 1.hour)

    // Recover
    val report = Await.result(engine2.recover(), 5.seconds)

    // Workflow with timer within horizon should be loaded
    assertEquals(report.nearTimersLoaded, 1)
    assertEquals(report.totalLoaded, 1)

    Await.result(engine2.shutdown(), 5.seconds)
  }

  test("recovery does not load workflows with timers outside horizon") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    // Use very short timer horizon (1 second)
    val engine1 = createEngine(storage, timerHorizon = 1.second)

    // Start workflow with timer in 1 hour (outside 1 second horizon)
    val workflowId = Await.result(
      engine1.start(TimerWaitingWorkflow, Tuple1(1.hour.toMillis)),
      5.seconds
    )
    Thread.sleep(100)

    // Shutdown and create new engine
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage, timerHorizon = 1.second)

    // Recover
    val report = Await.result(engine2.recover(), 5.seconds)

    // Workflow with timer outside horizon should NOT be loaded
    assertEquals(report.nearTimersLoaded, 0)
    assertEquals(report.totalLoaded, 0)

    Await.result(engine2.shutdown(), 5.seconds)
  }

  // ===== On-Demand Loading Tests =====

  test("sendEventTo loads workflow on-demand and delivers event") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-event")

    val engine1 = createEngine(storage)

    // Start workflow, let it suspend
    val workflowId = Await.result(engine1.start(EventWaitingWorkflow, Tuple1("test")), 5.seconds)
    Thread.sleep(100)

    // Shutdown and create new engine
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage)

    // Recover - workflow not loaded (waiting for event, no timer)
    val report = Await.result(engine2.recover(), 5.seconds)
    assertEquals(report.totalLoaded, 0)

    // Send event - should load workflow on-demand and deliver
    Await.result(engine2.sendEventTo(workflowId, "hello"), 5.seconds)
    Thread.sleep(100)

    // Workflow should now be completed
    val status = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))

    // Verify result
    val result = Await.result(engine2.queryResult[String](workflowId), 5.seconds)
    assertEquals(result, Some("received: hello"))

    Await.result(engine2.shutdown(), 5.seconds)
  }

  test("sendEventBroadcast loads waiting workflows from storage") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-event")

    val engine1 = createEngine(storage)

    // Start workflow waiting for event
    val workflowId = Await.result(engine1.start(EventWaitingWorkflow, Tuple1("wf1")), 5.seconds)
    Thread.sleep(100)

    // Shutdown and create new engine
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage)

    // Recover - no workflows loaded (waiting for events)
    val report = Await.result(engine2.recover(), 5.seconds)
    assertEquals(report.totalLoaded, 0)

    // Broadcast event - should query storage and load waiting workflow
    Await.result(engine2.sendEventBroadcast("broadcast-msg"), 5.seconds)
    Thread.sleep(200)

    // Workflow should have received the event
    val status = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))

    Await.result(engine2.shutdown(), 5.seconds)
  }

  // ===== Storage Query Tests =====

  test("storage queries return correct workflows") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val engine = createEngine(storage, timerHorizon = 1.hour)

    // Start workflow waiting for event
    val eventWf = Await.result(engine.start(EventWaitingWorkflow, Tuple1("event-wf")), 5.seconds)

    // Start workflow with timer
    val timerWf = Await.result(
      engine.start(TimerWaitingWorkflow, Tuple1(5.minutes.toMillis)),
      5.seconds
    )

    // Start workflow that completes
    val simpleWf = Await.result(engine.start(SimpleWorkflow, Tuple1("simple")), 5.seconds)

    Thread.sleep(100)

    // Test listWorkflowsByStatus
    val suspended = Await.result(storage.listWorkflowsByStatus(WorkflowStatus.Suspended), 5.seconds)
    assertEquals(suspended.size, 2) // eventWf and timerWf
    assert(suspended.exists(_.id == eventWf))
    assert(suspended.exists(_.id == timerWf))

    val succeeded = Await.result(storage.listWorkflowsByStatus(WorkflowStatus.Succeeded), 5.seconds)
    assertEquals(succeeded.size, 1)
    assertEquals(succeeded.head.id, simpleWf)

    // Test listWorkflowsWaitingForEvent
    val waitingForEvent = Await.result(storage.listWorkflowsWaitingForEvent("test-event"), 5.seconds)
    assertEquals(waitingForEvent.size, 1)
    assertEquals(waitingForEvent.head.id, eventWf)

    // Test listWorkflowsWithTimerBefore
    val deadline = Instant.now().plus(java.time.Duration.ofHours(1))
    val withTimer = Await.result(storage.listWorkflowsWithTimerBefore(deadline), 5.seconds)
    assertEquals(withTimer.size, 1)
    assertEquals(withTimer.head.id, timerWf)

    Await.result(engine.shutdown(), 5.seconds)
  }

  test("loadWorkflowRecord returns full record with wait conditions") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val engine = createEngine(storage)

    // Start workflow waiting for event
    val workflowId = Await.result(engine.start(EventWaitingWorkflow, Tuple1("test")), 5.seconds)
    Thread.sleep(100)

    // Load full record from storage
    val recordOpt = Await.result(storage.loadWorkflowRecord(workflowId), 5.seconds)
    assert(recordOpt.isDefined, "Record should exist")

    val record = recordOpt.get
    assertEquals(record.id, workflowId)
    assertEquals(record.status, WorkflowStatus.Suspended)
    assert(record.waitingForEvents.contains("test-event"), "Should have wait condition for event")

    Await.result(engine.shutdown(), 5.seconds)
  }

  // ===== Mixed Scenario Tests =====

  test("mixed workflows: only relevant ones loaded on recovery") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val engine1 = createEngine(storage, timerHorizon = 10.minutes)

    // Start workflow waiting for event (should NOT be loaded)
    val eventWf = Await.result(engine1.start(EventWaitingWorkflow, Tuple1("event-wf")), 5.seconds)

    // Start workflow with near timer (should be loaded)
    val nearTimerWf = Await.result(
      engine1.start(TimerWaitingWorkflow, Tuple1(1.minute.toMillis)),
      5.seconds
    )

    // Start workflow with far timer (should NOT be loaded)
    val farTimerWf = Await.result(
      engine1.start(TimerWaitingWorkflow, Tuple1(1.hour.toMillis)),
      5.seconds
    )

    Thread.sleep(100)

    // Shutdown and recover
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage, timerHorizon = 10.minutes)
    val report = Await.result(engine2.recover(), 5.seconds)

    // Only the near-timer workflow should be loaded
    assertEquals(report.nearTimersLoaded, 1)
    assertEquals(report.totalLoaded, 1)

    // All workflows should still be queryable (via storage)
    assertEquals(Await.result(engine2.queryStatus(eventWf), 5.seconds), Some(WorkflowStatus.Suspended))
    assertEquals(Await.result(engine2.queryStatus(nearTimerWf), 5.seconds), Some(WorkflowStatus.Suspended))
    assertEquals(Await.result(engine2.queryStatus(farTimerWf), 5.seconds), Some(WorkflowStatus.Suspended))

    Await.result(engine2.shutdown(), 5.seconds)
  }

  test("workflow loaded on-demand can complete successfully") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-event")

    val engine1 = createEngine(storage)

    // Start workflow
    val workflowId = Await.result(engine1.start(EventWaitingWorkflow, Tuple1("test")), 5.seconds)
    Thread.sleep(100)

    // Shutdown, recover (workflow not loaded), send event
    Await.result(engine1.shutdown(), 5.seconds)
    val engine2 = createEngine(storage)
    Await.result(engine2.recover(), 5.seconds)

    // Workflow not in memory but can still receive event
    Await.result(engine2.sendEventTo(workflowId, "value1"), 5.seconds)
    Thread.sleep(100)

    // Verify completed successfully
    val status = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))

    val result = Await.result(engine2.queryResult[String](workflowId), 5.seconds)
    assertEquals(result, Some("received: value1"))

    Await.result(engine2.shutdown(), 5.seconds)
  }

  test("sendEventTo to non-existent workflow fails") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-event")

    val engine = createEngine(storage)
    Await.result(engine.recover(), 5.seconds)

    val result = engine.sendEventTo(WorkflowId("non-existent"), "hello")

    intercept[WorkflowNotFoundException] {
      Await.result(result, 5.seconds)
    }

    Await.result(engine.shutdown(), 5.seconds)
  }

  test("sendEventTo to completed workflow fails") {
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    given DurableEventName[String] = DurableEventName("test-event")

    val engine = createEngine(storage)

    // Start and complete workflow
    val workflowId = Await.result(engine.start(SimpleWorkflow, Tuple1("test")), 5.seconds)
    Thread.sleep(100)

    val status = Await.result(engine.queryStatus(workflowId), 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))

    // Try to send event to completed workflow
    val result = engine.sendEventTo(workflowId, "hello")

    intercept[WorkflowTerminatedException] {
      Await.result(result, 5.seconds)
    }

    Await.result(engine.shutdown(), 5.seconds)
  }
}
