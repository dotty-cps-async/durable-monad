package durable.examples.subscription

import scala.concurrent.duration._
import cps.*

import com.github.rssh.appcontext.*
import durable.*
import durable.MemoryBackingStore.given
import durable.engine.WorkflowSessionRunner

/**
 * Monthly Subscription Billing Workflow - "The 30-Day Sleep" Pattern
 *
 * Demonstrates long-running workflows using continueWith instead of loops:
 * 1. Charge the subscription
 * 2. Send invoice notification
 * 3. Sleep for 30 days
 * 4. Use continueWith to start next billing cycle (clears history)
 *
 * Each billing cycle becomes a new workflow iteration, preventing
 * unbounded history growth that would occur with a traditional loop.
 *
 * State carried between cycles:
 * - subscriptionId: identifies the subscription
 * - totalBilled: accumulated billing amount
 * - cyclesCompleted: number of successful billing cycles
 */
object SubscriptionBillingWorkflow
    extends DurableFunction3[SubscriptionId, BigDecimal, Int, SubscriptionResult, MemoryBackingStore]
    derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  /** Maximum number of consecutive payment failures before giving up */
  val MaxPaymentRetries = 3

  def apply(subscriptionId: SubscriptionId, totalBilled: BigDecimal, cyclesCompleted: Int)(
    using MemoryBackingStore
  ): Durable[SubscriptionResult] =
    async[Durable] {
      // Get services via Durable.appContext - uses cache from RunContext
      val billingService = Durable.appContext[BillingService].await
      val notificationService = Durable.appContext[NotificationService].await
      val statusService = Durable.appContext[SubscriptionStatusService].await

      // Check if subscription is still active
      if !statusService.isActive(subscriptionId) then
        notificationService.sendCancellationNotice(subscriptionId).await
        SubscriptionResult.Cancelled(cyclesCompleted)
      else
        // Attempt to charge the subscription
        billingService.chargeSubscription(subscriptionId).await match
          case BillingResult.Success(invoiceId, amount) =>
            // Payment succeeded - send invoice and schedule next billing
            notificationService.sendInvoice(subscriptionId, invoiceId).await

            val newTotal = totalBilled + amount
            val newCycles = cyclesCompleted + 1

            // Sleep for 30 days (the famous "30-Day Sleep")
            Durable.sleep(30.days).await

            // Continue as new workflow iteration with updated state
            // This clears the activity history, preventing unbounded growth
            await(continueWith(subscriptionId, newTotal, newCycles))

          case BillingResult.Failed(reason) =>
            // Payment failed - notify and try retry workflow
            notificationService.sendPaymentFailedNotice(subscriptionId, reason).await

            // Transition to retry workflow to handle retries with daily attempts
            await(SubscriptionRetryWorkflow.continueAs(subscriptionId, totalBilled, cyclesCompleted, 1))

          case BillingResult.Cancelled =>
            notificationService.sendCancellationNotice(subscriptionId).await
            SubscriptionResult.Cancelled(cyclesCompleted)
    }

  // AppContextAsyncProvider[Durable, T] - derived from RunContext (has cache + config)
  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, BillingService] with
    def get: Durable[BillingService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[BillingService].getOrElse {
          new BillingService
        }
      }

  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, NotificationService] with
    def get: Durable[NotificationService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[NotificationService].getOrElse {
          new NotificationService
        }
      }

  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, SubscriptionStatusService] with
    def get: Durable[SubscriptionStatusService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[SubscriptionStatusService].getOrElse {
          new SubscriptionStatusService
        }
      }

/**
 * Retry workflow for handling payment failures.
 * Retries once per day (plus jitter) until max retries exceeded.
 *
 * State:
 * - subscriptionId: identifies the subscription
 * - totalBilled: accumulated billing amount (preserved across retries)
 * - cyclesCompleted: successful cycles (preserved across retries)
 * - retryCount: current retry attempt
 */
object SubscriptionRetryWorkflow
    extends DurableFunction4[SubscriptionId, BigDecimal, Int, Int, SubscriptionResult, MemoryBackingStore]
    derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  import SubscriptionBillingWorkflow.{MaxPaymentRetries, given}

  def apply(
    subscriptionId: SubscriptionId,
    totalBilled: BigDecimal,
    cyclesCompleted: Int,
    retryCount: Int
  )(using MemoryBackingStore): Durable[SubscriptionResult] =
    async[Durable] {
      val billingService = Durable.appContext[BillingService].await
      val notificationService = Durable.appContext[NotificationService].await

      if retryCount > MaxPaymentRetries then
        // Exceeded max retries - subscription failed
        SubscriptionResult.FailedAfterRetries(s"Payment failed after $MaxPaymentRetries attempts", cyclesCompleted)
      else
        // Wait 1 day before retry (jitter can be added via activity-level randomization)
        Durable.sleep(1.day).await

        // Retry the payment
        billingService.chargeSubscription(subscriptionId).await match
          case BillingResult.Success(invoiceId, amount) =>
            // Retry succeeded - send invoice and return to normal billing cycle
            notificationService.sendInvoice(subscriptionId, invoiceId).await

            val newTotal = totalBilled + amount
            val newCycles = cyclesCompleted + 1

            // Sleep remaining days until next billing (30 - days spent retrying)
            val daysSpentRetrying = retryCount
            val remainingDays = 30 - daysSpentRetrying
            if remainingDays > 0 then
              Durable.sleep(remainingDays.days).await

            // Return to main billing workflow
            await(SubscriptionBillingWorkflow.continueAs(subscriptionId, newTotal, newCycles))

          case BillingResult.Failed(reason) =>
            // Still failing - notify and try again tomorrow
            notificationService.sendPaymentFailedNotice(subscriptionId, reason).await
            await(continueWith(subscriptionId, totalBilled, cyclesCompleted, retryCount + 1))

          case BillingResult.Cancelled =>
            notificationService.sendCancellationNotice(subscriptionId).await
            SubscriptionResult.Cancelled(cyclesCompleted)
    }
