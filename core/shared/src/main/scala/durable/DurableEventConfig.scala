package durable

import scala.concurrent.duration.Duration

/**
 * Delivery mode for events - determines which events a workflow sees.
 */
enum DeliveryMode:
  /** Only events sent after workflow started waiting */
  case AfterWait
  /** Events sent after workflow was created */
  case AfterCreate
  /** All events including historical */
  case All

/**
 * Configuration for event delivery semantics.
 *
 * @param expireAfter Optional duration after which events expire and are discarded
 * @param deliveryMode When events become visible to waiting workflows
 * @param consumeOnRead If true, event is removed after one workflow reads it (queue semantics).
 *                      If false, event is delivered to all waiters (broadcast/topic semantics).
 */
case class EventConfig(
  expireAfter: Option[Duration] = None,
  deliveryMode: DeliveryMode = DeliveryMode.AfterWait,
  consumeOnRead: Boolean = true
)

object EventConfig:
  /** Default configuration: no expiry, after-wait delivery, consume on read */
  val default: EventConfig = EventConfig()

/**
 * Typeclass providing event configuration for type E.
 *
 * Can be derived automatically with defaults:
 *   case class OrderCreated(id: String) derives DurableEventName, DurableEventConfig
 *
 * Or provided explicitly to override defaults:
 *   given DurableEventConfig[PaymentReceived] with
 *     override def expireAfter = Some(24.hours)
 *     override def consumeOnRead = false
 */
trait DurableEventConfig[E]:
  /** Optional duration after which events expire */
  def expireAfter: Option[Duration] = None

  /** When events become visible to waiting workflows */
  def deliveryMode: DeliveryMode = DeliveryMode.AfterWait

  /** If true, event is consumed (queue). If false, broadcast to all waiters. */
  def consumeOnRead: Boolean = true

  /** Get full config as case class */
  def config: EventConfig = EventConfig(expireAfter, deliveryMode, consumeOnRead)

object DurableEventConfig extends LowPriorityDurableEventConfig:
  /** Create config with custom settings */
  def apply[E](
    expire: Option[Duration] = None,
    delivery: DeliveryMode = DeliveryMode.AfterWait,
    consume: Boolean = true
  ): DurableEventConfig[E] = new DurableEventConfig[E]:
    override def expireAfter: Option[Duration] = expire
    override def deliveryMode: DeliveryMode = delivery
    override def consumeOnRead: Boolean = consume

  /** Get config for type E */
  def configOf[E](using ec: DurableEventConfig[E]): EventConfig = ec.config

/**
 * Low-priority default config for any event type.
 * User-provided instances take precedence.
 */
trait LowPriorityDurableEventConfig:
  /** Default configuration for any event type */
  given defaultEventConfig[E]: DurableEventConfig[E] = new DurableEventConfig[E] {}
