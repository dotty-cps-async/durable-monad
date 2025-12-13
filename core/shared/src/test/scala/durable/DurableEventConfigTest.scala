package durable

import munit.FunSuite
import scala.concurrent.duration.*

/**
 * Tests for DurableEventConfig typeclass.
 */
class DurableEventConfigTest extends FunSuite:

  // Test events
  case class OrderCreated(id: String)
  case class PaymentReceived(amount: BigDecimal)
  case class NotificationSent(message: String)

  test("default config is provided for any type") {
    // No explicit config needed - low-priority default is used
    val config = DurableEventConfig.configOf[OrderCreated]
    assertEquals(config.expireAfter, None)
    assertEquals(config.deliveryMode, DeliveryMode.AfterWait)
    assertEquals(config.consumeOnRead, true)
  }

  test("explicit config overrides default") {
    given DurableEventConfig[PaymentReceived] with
      override def expireAfter = Some(24.hours)
      override def consumeOnRead = false

    val config = DurableEventConfig.configOf[PaymentReceived]
    assertEquals(config.expireAfter, Some(24.hours))
    assertEquals(config.deliveryMode, DeliveryMode.AfterWait)  // still default
    assertEquals(config.consumeOnRead, false)
  }

  test("config can be created with apply") {
    given DurableEventConfig[NotificationSent] = DurableEventConfig[NotificationSent](
      expire = Some(1.hour),
      delivery = DeliveryMode.AfterCreate,
      consume = false
    )

    val config = DurableEventConfig.configOf[NotificationSent]
    assertEquals(config.expireAfter, Some(1.hour))
    assertEquals(config.deliveryMode, DeliveryMode.AfterCreate)
    assertEquals(config.consumeOnRead, false)
  }

  test("DeliveryMode enum has all expected values") {
    val modes = DeliveryMode.values
    assertEquals(modes.length, 3)
    assert(modes.contains(DeliveryMode.AfterWait))
    assert(modes.contains(DeliveryMode.AfterCreate))
    assert(modes.contains(DeliveryMode.All))
  }

  test("EventConfig case class has correct defaults") {
    val config = EventConfig()
    assertEquals(config.expireAfter, None)
    assertEquals(config.deliveryMode, DeliveryMode.AfterWait)
    assertEquals(config.consumeOnRead, true)
  }

  test("EventConfig.default equals EventConfig()") {
    assertEquals(EventConfig.default, EventConfig())
  }

  test("config method returns EventConfig case class") {
    given DurableEventConfig[String] with
      override def expireAfter = Some(5.minutes)
      override def deliveryMode = DeliveryMode.All

    val config: EventConfig = summon[DurableEventConfig[String]].config
    assertEquals(config, EventConfig(
      expireAfter = Some(5.minutes),
      deliveryMode = DeliveryMode.All,
      consumeOnRead = true
    ))
  }
