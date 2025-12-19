package durable

import munit.FunSuite
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*
import java.time.Instant

/**
 * Tests for Combined EventQuery - waiting on multiple conditions.
 */
class CombinedEventQueryTest extends FunSuite:
  given ExecutionContext = ExecutionContext.global

  import MemoryBackingStore.given

  // Test event types
  case class PaymentReceived(amount: Double)
  object PaymentReceived:
    given DurableEventName[PaymentReceived] = DurableEventName("payment-received")

  case class OrderCancelled(reason: String)
  object OrderCancelled:
    given DurableEventName[OrderCancelled] = DurableEventName("order-cancelled")

  // ============================================
  // DSL Composition Tests
  // ============================================

  test("Event | Event creates Combined query") {
    given backend: MemoryBackingStore = MemoryBackingStore()
    val query = Event[PaymentReceived] | Event[OrderCancelled]
    assert(query.isInstanceOf[CombinedBuilder[?, ?]], "Expected CombinedBuilder")
  }

  test("Event | TimeReached creates Combined query") {
    given backend: MemoryBackingStore = MemoryBackingStore()
    val query = Event[PaymentReceived] | TimeReached.after(1.minute)
    assert(query.isInstanceOf[CombinedBuilder[?, ?]], "Expected CombinedBuilder")
  }

  test("Multiple | operations accumulate conditions") {
    given backend: MemoryBackingStore = MemoryBackingStore()
    val query = Event[PaymentReceived] | Event[OrderCancelled] | TimeReached.after(1.minute)
    assert(query.isInstanceOf[CombinedBuilder[?, ?]], "Expected CombinedBuilder")
    // Should have 2 events and 1 timer
  }

  // ============================================
  // Engine Integration Tests
  // ============================================

  // Test workflow: wait for payment OR cancellation
  object TwoEventWorkflow extends DurableFunction1[String, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(orderId: String)(using MemoryBackingStore): Durable[String] =
      import PaymentReceived.given
      import OrderCancelled.given
      for
        result <- (Event[PaymentReceived] | Event[OrderCancelled]).receive
      yield result match
        case p: PaymentReceived => s"paid:${p.amount}"
        case c: OrderCancelled => s"cancelled:${c.reason}"

  // Test workflow: wait for event OR timeout
  object EventOrTimeoutWorkflow extends DurableFunction1[Long, String, MemoryBackingStore] derives DurableFunctionName:
    override val functionName = DurableFunction.register(this)

    def apply(timeoutMs: Long)(using MemoryBackingStore): Durable[String] =
      import PaymentReceived.given
      for
        result <- (Event[PaymentReceived] | TimeReached.after(timeoutMs.millis)).receive
      yield result match
        case p: PaymentReceived => s"paid:${p.amount}"
        case t: TimeReached => "timeout"

  def createEngine(): (MemoryBackingStore, WorkflowEngine[MemoryBackingStore]) =
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    val engine = WorkflowEngine(storage)
    (storage, engine)

  test("Combined query suspends workflow") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(TwoEventWorkflow, Tuple1("order-1"))
      _ <- Future(Thread.sleep(100))
      status <- engine.queryStatus(workflowId)
    yield status

    val status = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Suspended))
  }

  test("Combined query wakes on first event") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import PaymentReceived.given

    val result = for
      workflowId <- engine.start(TwoEventWorkflow, Tuple1("order-1"))
      _ <- Future(Thread.sleep(100))  // Wait for suspension
      _ <- engine.sendEventBroadcast(PaymentReceived(99.99))
      _ <- Future(Thread.sleep(100))  // Wait for completion
      status <- engine.queryStatus(workflowId)
      queryResult <- engine.queryResult[String](workflowId)
    yield (status, queryResult)

    val (status, queryResult) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
    assertEquals(queryResult, Some("paid:99.99"))
  }

  test("Combined query wakes on second event type") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import OrderCancelled.given

    val result = for
      workflowId <- engine.start(TwoEventWorkflow, Tuple1("order-1"))
      _ <- Future(Thread.sleep(100))  // Wait for suspension
      _ <- engine.sendEventBroadcast(OrderCancelled("user requested"))
      _ <- Future(Thread.sleep(100))  // Wait for completion
      status <- engine.queryStatus(workflowId)
      queryResult <- engine.queryResult[String](workflowId)
    yield (status, queryResult)

    val (status, queryResult) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
    assertEquals(queryResult, Some("cancelled:user requested"))
  }

  test("Combined query wakes on timeout when no event") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    val result = for
      workflowId <- engine.start(EventOrTimeoutWorkflow, Tuple1(50L))  // 50ms timeout
      _ <- Future(Thread.sleep(200))  // Wait for timeout
      status <- engine.queryStatus(workflowId)
      queryResult <- engine.queryResult[String](workflowId)
    yield (status, queryResult)

    val (status, queryResult) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
    assertEquals(queryResult, Some("timeout"))
  }

  test("Combined query cancels timer when event fires first") {
    val (storage, engine) = createEngine()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]
    import PaymentReceived.given

    val result = for
      workflowId <- engine.start(EventOrTimeoutWorkflow, Tuple1(5000L))  // 5s timeout
      _ <- Future(Thread.sleep(100))  // Wait for suspension
      _ <- engine.sendEventBroadcast(PaymentReceived(50.00))  // Event arrives before timeout
      _ <- Future(Thread.sleep(100))  // Wait for completion
      status <- engine.queryStatus(workflowId)
      queryResult <- engine.queryResult[String](workflowId)
    yield (status, queryResult)

    val (status, queryResult) = Await.result(result, 5.seconds)
    assertEquals(status, Some(WorkflowStatus.Succeeded))
    assertEquals(queryResult, Some("paid:50.0"))
  }
