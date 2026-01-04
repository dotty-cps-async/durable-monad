package durable.examples.subscription

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.Instant

import com.github.rssh.appcontext.*
import durable.*
import durable.engine.{ConfigSource, WorkflowSessionRunner, WorkflowSessionResult}

class SubscriptionBillingTest extends FunSuite:
  import MemoryBackingStore.given
  private val runner = WorkflowSessionRunner.forFuture

  test("first billing cycle charges and suspends for 30 days") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services and pre-populate AppContext.Cache
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-1")
    val workflowId = WorkflowId("billing-1")
    val workflow = SubscriptionBillingWorkflow(subscriptionId, BigDecimal(0), 0)

    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      // Should suspend at 30-day sleep
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]], s"Expected Suspended, got $result")
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasTimer, "Should be waiting for timer")
      // Payment should have been processed
      assert(billingService.processedPayments.exists(_._1 == subscriptionId), "Payment should be processed")
      // Invoice should have been sent
      assert(notificationService.invoicesSent.exists(_._1 == subscriptionId), "Invoice should be sent")
    }
  }

  test("continues to next billing cycle after 30 days") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-2")
    val workflowId = WorkflowId("billing-2")

    // Pre-populate backing store to simulate completed first billing cycle
    // Activity indices (preprocessor caches vals and conditions):
    // 0: if condition (!statusService.isActive) = false (subscription is active)
    // 1: chargeSubscription (BillingResult.Success)
    // 2: sendInvoice (Unit)
    // 3: newTotal (BigDecimal) - pure computation but still cached
    // 4: newCycles (Int) - pure computation but still cached
    // 5: sleep (TimeReached)
    val now = Instant.now()
    backing.put(workflowId, 0, Right(false)) // !isActive = false (subscription active, take else branch)
    backing.put(workflowId, 1, Right(BillingResult.Success("INV-1", BigDecimal(9.99))))
    backing.put(workflowId, 2, Right(()))
    backing.put(workflowId, 3, Right(BigDecimal(9.99))) // newTotal = 0 + 9.99
    backing.put(workflowId, 4, Right(1)) // newCycles = 0 + 1
    backing.put(workflowId, 5, Right(TimeReached(now, now)))

    val workflow = SubscriptionBillingWorkflow(subscriptionId, BigDecimal(0), 0)
    val ctx = WorkflowSessionRunner.RunContext.resume(
      workflowId, 6, 0,
      config = WorkflowSessionRunner.RunConfig.default,
      configSource = ConfigSource.empty,
      appContextCache = appContextCache
    )

    backing.storeWinningCondition(workflowId, 5, TimerInstant(now)).flatMap { _ =>
      runner.run(workflow, ctx).map(_.toOption.get).map { result =>
        result match
          case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
            assertEquals(metadata.functionName, "durable.examples.subscription.SubscriptionBillingWorkflow")
            assertEquals(metadata.argCount, 3)
          case other =>
            fail(s"Expected ContinueAs, got $other")
      }
    }
  }

  test("transitions to retry workflow on payment failure") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    billingService.failNextPayment = true // Force payment to fail
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-3")
    val workflowId = WorkflowId("billing-3")
    val workflow = SubscriptionBillingWorkflow(subscriptionId, BigDecimal(0), 0)

    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      result match
        case WorkflowSessionResult.ContinueAs(metadata, _, _) =>
          assertEquals(metadata.functionName, "durable.examples.subscription.SubscriptionRetryWorkflow")
          assertEquals(metadata.argCount, 4)
          // Failure notification should have been sent
          assert(notificationService.failureNotificationsSent.exists(_._1 == subscriptionId),
            "Failure notification should be sent")
        case other =>
          fail(s"Expected ContinueAs to SubscriptionRetryWorkflow, got $other")
    }
  }

  test("cancels subscription when marked as cancelled") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val subscriptionId = SubscriptionId("sub-4")
    statusService.cancelledSubscriptions = Set(subscriptionId) // Mark as cancelled
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val workflowId = WorkflowId("billing-4")
    val workflow = SubscriptionBillingWorkflow(subscriptionId, BigDecimal(50.00), 5)

    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, SubscriptionResult.Cancelled(5))
          assert(notificationService.cancellationsSent.contains(subscriptionId),
            "Cancellation notification should be sent")
        case other =>
          fail(s"Expected Completed with Cancelled, got $other")
    }
  }

  test("retry workflow suspends waiting for daily retry") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-5")
    val workflowId = WorkflowId("retry-1")

    // Start a fresh retry workflow (not resuming)
    val workflow = SubscriptionRetryWorkflow(subscriptionId, BigDecimal(10.00), 1, 1)
    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      result match
        case WorkflowSessionResult.Suspended(_, condition) =>
          // Should suspend waiting for 1-day retry delay
          assert(condition.hasTimer, "Should be waiting for timer")
        case other =>
          fail(s"Expected Suspended for 1-day retry, got $other")
    }
  }

  test("retry workflow retries payment after sleep and succeeds") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-7")
    val workflowId = WorkflowId("retry-3")

    // Pre-populate backing store to simulate:
    // Activity indices for SubscriptionRetryWorkflow (retryCount=1, not > MaxPaymentRetries):
    // 0: if condition (retryCount > MaxPaymentRetries) = false
    // 1: sleep (TimeReached) - 1-day backoff
    val now = Instant.now()
    backing.put(workflowId, 0, Right(false)) // retryCount <= MaxPaymentRetries
    backing.put(workflowId, 1, Right(TimeReached(now, now)))

    val workflow = SubscriptionRetryWorkflow(subscriptionId, BigDecimal(10.00), 1, 1)
    val ctx = WorkflowSessionRunner.RunContext.resume(
      workflowId, 2, 0,
      config = WorkflowSessionRunner.RunConfig.default,
      configSource = ConfigSource.empty,
      appContextCache = appContextCache
    )

    backing.storeWinningCondition(workflowId, 1, TimerInstant(now)).flatMap { _ =>
      runner.run(workflow, ctx).map(_.toOption.get).map { result =>
        result match
          case WorkflowSessionResult.Suspended(_, condition) =>
            // After successful retry, should suspend waiting for remaining billing period
            assert(condition.hasTimer, "Should be waiting for remaining billing period timer")
            // Invoice should have been sent
            assert(notificationService.invoicesSent.exists(_._1 == subscriptionId), "Invoice should be sent")
          case other =>
            fail(s"Expected Suspended for remaining billing period, got $other")
      }
    }
  }

  test("retry workflow fails after max retries") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val billingService = new BillingService
    val notificationService = new NotificationService
    val statusService = new SubscriptionStatusService
    val appContextCache = AppContext.newCache
    appContextCache.put[BillingService](billingService)
    appContextCache.put[NotificationService](notificationService)
    appContextCache.put[SubscriptionStatusService](statusService)

    val subscriptionId = SubscriptionId("sub-6")
    val workflowId = WorkflowId("retry-2")

    // Start retry workflow with retryCount > MaxPaymentRetries
    val workflow = SubscriptionRetryWorkflow(subscriptionId, BigDecimal(30.00), 3, 4)
    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          value match
            case SubscriptionResult.FailedAfterRetries(_, cyclesCompleted) =>
              assertEquals(cyclesCompleted, 3)
            case other =>
              fail(s"Expected FailedAfterRetries, got $other")
        case other =>
          fail(s"Expected Completed with FailedAfterRetries, got $other")
    }
  }
