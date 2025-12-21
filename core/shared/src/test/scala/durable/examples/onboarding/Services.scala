package durable.examples.onboarding

import scala.concurrent.Future

/** Domain type representing a customer */
case class CustomerId(value: String)

/**
 * Toy email service for testing.
 * Tracks sent emails via mutable state for test verification.
 */
class EmailService:
  var welcomeEmailsSent: List[CustomerId] = Nil
  var reminderEmailsSent: List[CustomerId] = Nil

  def sendWelcomeEmail(customerId: CustomerId): Future[Unit] =
    welcomeEmailsSent = customerId :: welcomeEmailsSent
    Future.successful(())

  def sendReminderEmail(customerId: CustomerId): Future[Unit] =
    reminderEmailsSent = customerId :: reminderEmailsSent
    Future.successful(())

/**
 * Toy user activity service for testing.
 * Configurable set of active users for different test scenarios.
 */
class UserActivityService:
  var activeUsers: Set[CustomerId] = Set.empty

  def hasUsedApp(customerId: CustomerId): Boolean =
    activeUsers.contains(customerId)
