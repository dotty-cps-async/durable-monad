package durable

import scala.deriving.Mirror
import scala.compiletime.constValue

/**
 * Typeclass providing the event channel name for type E.
 * Used to identify event queues for broadcast events.
 *
 * Can be derived automatically for case classes:
 *   case class PaymentReceived(orderId: String, amount: BigDecimal) derives DurableEventName
 *
 * Or provided explicitly:
 *   given DurableEventName[MyEvent] = DurableEventName("custom-event-name")
 */
trait DurableEventName[E]:
  def name: String

object DurableEventName:
  def apply[E](n: String): DurableEventName[E] = new DurableEventName[E]:
    def name: String = n

  /**
   * Derive DurableEventName from Mirror - uses the type's simple name.
   * Works for case classes and sealed traits.
   *
   * Note: Uses simple name (e.g., "PaymentReceived"), not fully qualified.
   * Ensure event names are unique across your application.
   */
  inline def derived[E](using m: Mirror.Of[E]): DurableEventName[E] =
    apply(constValue[m.MirroredLabel])

  /** Get the event name for type E */
  def nameOf[E](using en: DurableEventName[E]): String = en.name

  /** Build event queue workflow ID from event name */
  def queueId[E](using en: DurableEventName[E]): WorkflowId =
    WorkflowId(s"event-queue-${en.name}")
