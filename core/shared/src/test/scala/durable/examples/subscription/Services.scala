package durable.examples.subscription

import scala.concurrent.Future

/** Domain type representing a subscription */
case class SubscriptionId(value: String)

/** Billing result for a single billing cycle */
enum BillingResult:
  case Success(invoiceId: String, amount: BigDecimal)
  case Failed(reason: String)
  case Cancelled

/** Final result of the subscription workflow after max retries or cancellation */
enum SubscriptionResult:
  case Active(totalBilled: BigDecimal, cyclesCompleted: Int)
  case FailedAfterRetries(lastError: String, cyclesCompleted: Int)
  case Cancelled(cyclesCompleted: Int)

/**
 * Billing service for processing subscription payments.
 * Tracks payments for test verification.
 */
class BillingService:
  var processedPayments: List[(SubscriptionId, BigDecimal)] = Nil
  var failNextPayment: Boolean = false
  var subscriptionPrices: Map[SubscriptionId, BigDecimal] = Map.empty

  def chargeSubscription(subscriptionId: SubscriptionId): Future[BillingResult] =
    if failNextPayment then
      failNextPayment = false
      Future.successful(BillingResult.Failed("Payment declined"))
    else
      val amount = subscriptionPrices.getOrElse(subscriptionId, BigDecimal(9.99))
      processedPayments = (subscriptionId, amount) :: processedPayments
      val invoiceId = s"INV-${subscriptionId.value}-${processedPayments.size}"
      Future.successful(BillingResult.Success(invoiceId, amount))

/**
 * Notification service for sending billing-related emails.
 * Tracks notifications for test verification.
 */
class NotificationService:
  var invoicesSent: List[(SubscriptionId, String)] = Nil
  var failureNotificationsSent: List[(SubscriptionId, String)] = Nil
  var cancellationsSent: List[SubscriptionId] = Nil

  def sendInvoice(subscriptionId: SubscriptionId, invoiceId: String): Future[Unit] =
    invoicesSent = (subscriptionId, invoiceId) :: invoicesSent
    Future.successful(())

  def sendPaymentFailedNotice(subscriptionId: SubscriptionId, reason: String): Future[Unit] =
    failureNotificationsSent = (subscriptionId, reason) :: failureNotificationsSent
    Future.successful(())

  def sendCancellationNotice(subscriptionId: SubscriptionId): Future[Unit] =
    cancellationsSent = subscriptionId :: cancellationsSent
    Future.successful(())

/**
 * Subscription status service for checking if subscription is active.
 * Allows tests to configure cancellation scenarios.
 */
class SubscriptionStatusService:
  var cancelledSubscriptions: Set[SubscriptionId] = Set.empty

  def isActive(subscriptionId: SubscriptionId): Boolean =
    !cancelledSubscriptions.contains(subscriptionId)
