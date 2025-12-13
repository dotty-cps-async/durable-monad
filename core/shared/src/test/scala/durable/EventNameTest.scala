package durable

import munit.FunSuite

/**
 * Tests for DurableEventName typeclass derivation.
 */
class DurableEventNameTest extends FunSuite:

  // Test case class with derives
  case class PaymentReceived(orderId: String, amount: BigDecimal) derives DurableEventName

  // Test sealed trait with derives
  sealed trait OrderEvent derives DurableEventName
  case class OrderPlaced(orderId: String) extends OrderEvent
  case class OrderShipped(orderId: String) extends OrderEvent

  test("DurableEventName derived from case class") {
    val name = DurableEventName.nameOf[PaymentReceived]
    assertEquals(name, "PaymentReceived")
  }

  test("DurableEventName derived from sealed trait") {
    val name = DurableEventName.nameOf[OrderEvent]
    assertEquals(name, "OrderEvent")
  }

  test("DurableEventName.queueId builds correct workflow ID") {
    val queueId = DurableEventName.queueId[PaymentReceived]
    assertEquals(queueId, WorkflowId("event-queue-PaymentReceived"))
  }

  test("DurableEventName can be provided explicitly") {
    given DurableEventName[String] = DurableEventName("custom-string-event")
    val name = DurableEventName.nameOf[String]
    assertEquals(name, "custom-string-event")
  }

  test("DurableEventName explicit overrides derived") {
    // Even though PaymentReceived derives DurableEventName, explicit given takes precedence
    given DurableEventName[PaymentReceived] = DurableEventName("payment-v2")
    val name = DurableEventName.nameOf[PaymentReceived]
    assertEquals(name, "payment-v2")
  }
