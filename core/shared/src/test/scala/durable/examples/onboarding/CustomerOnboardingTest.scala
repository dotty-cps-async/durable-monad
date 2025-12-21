package durable.examples.onboarding

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import java.time.Instant

import com.github.rssh.appcontext.*
import durable.*
import durable.engine.{ConfigSource, WorkflowSessionRunner, WorkflowSessionResult}
import scala.concurrent.Await
import scala.concurrent.duration._

class CustomerOnboardingTest extends FunSuite:
  import MemoryBackingStore.given

  test("sends welcome email and suspends waiting for 2 days") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services and pre-populate AppContext.Cache
    val emailService = new EmailService
    val activityService = new UserActivityService
    val appContextCache = AppContext.newCache
    appContextCache.put[EmailService](emailService)
    appContextCache.put[UserActivityService](activityService)

    val customerId = CustomerId("cust-1")
    val workflowId = WorkflowId("onboard-1")
    val workflow = CustomerOnboardingWorkflow(customerId)

    // Pass pre-populated cache to RunContext
    val ctx = WorkflowSessionRunner.RunContext.fresh(
      workflowId,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      // Should suspend at sleep
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]], s"Expected Suspended, got $result")
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasTimer, "Should be waiting for timer")
      // Welcome email should have been sent
      assert(emailService.welcomeEmailsSent.contains(customerId), "Welcome email should be sent")
      assert(emailService.reminderEmailsSent.isEmpty, "Reminder should not be sent yet")
    }
  }

  test("sends reminder when user is inactive after 2 days") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val emailService = new EmailService
    val activityService = new UserActivityService
    val appContextCache = AppContext.newCache
    appContextCache.put[EmailService](emailService)
    appContextCache.put[UserActivityService](activityService)

    val customerId = CustomerId("cust-2")
    val workflowId = WorkflowId("onboard-2")

    // Pre-populate backing store to simulate completed activities before sleep
    // AppContext.asyncGet uses LocalComputation (no activity index consumed)
    // Index 0: sendWelcomeEmail result
    // Index 1: sleep (Suspend) - needs both winning condition and value
    val now = Instant.now()
    backing.put(workflowId, 0, Right(())) // sendWelcomeEmail completed
    // For Suspend replay: store winning condition AND value
    Await.result(backing.storeWinningCondition(workflowId, 1, TimerInstant(now)), 1.second)
    backing.put(workflowId, 1, Right(TimeReached(now, now))) // sleep completed

    // activityService.activeUsers is empty -> user inactive
    val workflow = CustomerOnboardingWorkflow(customerId)
    val ctx = WorkflowSessionRunner.RunContext.resume(
      workflowId, 2,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, OnboardingResult.ReminderSent)
          assert(emailService.reminderEmailsSent.contains(customerId), "Reminder should be sent")
        case other =>
          fail(s"Expected Completed with ReminderSent, got $other")
    }
  }

  test("completes without reminder when user is active") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // Create test services
    val emailService = new EmailService
    val activityService = new UserActivityService
    activityService.activeUsers = Set(CustomerId("cust-3")) // Mark customer as active
    val appContextCache = AppContext.newCache
    appContextCache.put[EmailService](emailService)
    appContextCache.put[UserActivityService](activityService)

    val customerId = CustomerId("cust-3")
    val workflowId = WorkflowId("onboard-3")

    // Pre-populate backing store to simulate completed activities before sleep
    // AppContext.asyncGet uses LocalComputation (no activity index consumed)
    // Index 0: sendWelcomeEmail result
    // Index 1: sleep (Suspend) - needs both winning condition and value
    val now = Instant.now()
    backing.put(workflowId, 0, Right(())) // sendWelcomeEmail completed
    // For Suspend replay: store winning condition AND value
    Await.result(backing.storeWinningCondition(workflowId, 1, TimerInstant(now)), 1.second)
    backing.put(workflowId, 1, Right(TimeReached(now, now))) // sleep completed

    val workflow = CustomerOnboardingWorkflow(customerId)
    val ctx = WorkflowSessionRunner.RunContext.resume(
      workflowId, 2,
      config = WorkflowSessionRunner.RunConfig.default,
      appContextCache = appContextCache,
      configSource = ConfigSource.empty
    )

    WorkflowSessionRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowSessionResult.Completed(_, value) =>
          assertEquals(value, OnboardingResult.ActiveUser)
          assert(emailService.reminderEmailsSent.isEmpty, "Reminder should NOT be sent for active user")
        case other =>
          fail(s"Expected Completed with ActiveUser, got $other")
    }
  }
