package durable.examples.onboarding

import scala.concurrent.duration._
import cps.*

import com.github.rssh.appcontext.*
import durable.*
import durable.MemoryBackingStore.given
import durable.engine.WorkflowSessionRunner

/** Result of the customer onboarding workflow */
enum OnboardingResult:
  case ActiveUser
  case ReminderSent

/**
 * The Customer Onboarding Workflow.
 *
 * Classic workflow pattern:
 * 1. Send welcome email
 * 2. Wait 2 days
 * 3. Check if customer used the app
 * 4. If not, send a reminder
 */
object CustomerOnboardingWorkflow extends DurableFunction1[CustomerId, OnboardingResult, MemoryBackingStore]
    derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  def apply(customerId: CustomerId)(using MemoryBackingStore): Durable[OnboardingResult] =
    async[Durable] {
      // Get services via Durable.appContext - uses cache from RunContext
      val emailService = Durable.appContext[EmailService].await
      val activityService = Durable.appContext[UserActivityService].await

      // Step 1: Send welcome email
      emailService.sendWelcomeEmail(customerId).await

      // Step 2: Wait 2 days
      Durable.sleep(2.days).await

      // Step 3: Check if customer used the app, send reminder if not
      if !activityService.hasUsedApp(customerId) then
        emailService.sendReminderEmail(customerId).await
        OnboardingResult.ReminderSent
      else
        OnboardingResult.ActiveUser
    }

  // AppContextAsyncProvider[Durable, T] - derived from RunContext (has cache + config)
  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, EmailService] with
    def get: Durable[EmailService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[EmailService].getOrElse {
          // Can check ctx.configSource for test/prod mode
          new EmailService
        }
      }

  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, UserActivityService] with
    def get: Durable[UserActivityService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[UserActivityService].getOrElse {
          new UserActivityService
        }
      }
