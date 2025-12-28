package durable.examples.quickstart

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration.*
import java.time.{LocalDate, MonthDay}

import cps.*
import cps.monads.{*, given}
import munit.FunSuite

import durable.*

// Data types
case class Friend(name: String, birthday: MonthDay)

// Event: request to stop reminders
case class StopReminders(reason: String)
object StopReminders:
  given DurableEventName[StopReminders] = DurableEventName("stop-reminders")

// Calculate days until next occurrence of this birthday
def daysUntilBirthday(birthday: MonthDay, from: LocalDate = LocalDate.now()): Long =
  val thisYear = birthday.atYear(from.getYear)
  val nextBirthday = if thisYear.isAfter(from) || thisYear.isEqual(from) then thisYear
                     else birthday.atYear(from.getYear + 1)
  java.time.temporal.ChronoUnit.DAYS.between(from, nextBirthday)

// The workflow: monitor one friend's birthday, handle stop events
object BirthdayReminderWorkflow
    extends DurableFunction1[Friend, String, MemoryBackingStore]
    derives DurableFunctionName:

  import MemoryBackingStore.given

  override val functionName = DurableFunction.register(this)

  def apply(friend: Friend)(using MemoryBackingStore): Durable[String] =
    async[Durable] {
      // Calculate days until birthday (cached activity)
      val daysToWait = daysUntilBirthday(friend.birthday) - 1  // remind day before

      // Wait for either: birthday approaches OR stop event received
      val waitResult = await(
        (TimeReached.after(daysToWait.days) | Event[StopReminders]).receive
      )

      waitResult match
        case _: TimeReached =>
          // Birthday is tomorrow - send reminder
          val message = s"Reminder: ${friend.name}'s birthday is tomorrow!"
          println(message)

          // Wait one more day, then loop for next year
          Durable.sleep(1.day).await
          await(continueWith(Tuple1(friend)))

        case StopReminders(reason) =>
          // Stop event received - end workflow
          s"Stopped reminders for ${friend.name}: $reason"
    }

class BirthdayReminderTest extends FunSuite:
  given ExecutionContext = ExecutionContext.global

  import MemoryBackingStore.given

  override val munitTimeout = Duration(30, "s")

  test("birthday reminder workflow can be started and stopped with event") {
    // Set up storage backend
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    // Create workflow engine
    val engine = WorkflowEngine(storage)

    // Create a friend with birthday far in the future
    val friend = Friend("Alice", MonthDay.of(12, 25))

    // Start workflow
    val workflowId = Await.result(
      engine.start(BirthdayReminderWorkflow, Tuple1(friend)),
      5.seconds
    )
    println(s"Started reminder for ${friend.name}: ${workflowId.value}")

    // Wait for workflow to suspend (waiting for timer or event)
    Thread.sleep(200)

    // Verify workflow is suspended
    val status1 = Await.result(engine.queryStatus(workflowId), 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))

    // Send stop event
    val sendResult = Await.result(
      engine.sendEventTo(workflowId, StopReminders("Test completed")),
      5.seconds
    )
    println(s"Sent stop event, result: $sendResult")

    // Wait for workflow to complete
    Thread.sleep(200)

    // Verify workflow completed
    val status2 = Await.result(engine.queryStatus(workflowId), 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Succeeded))

    // Cleanup
    Await.result(engine.shutdown(), 5.seconds)
  }

  test("birthday reminder workflow recovers after engine restart") {
    // Set up storage backend (shared between engine instances)
    val storage = MemoryBackingStore()
    given MemoryBackingStore = storage
    given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

    // Create first engine
    val engine1 = WorkflowEngine(storage)

    // Create a friend
    val friend = Friend("Bob", MonthDay.of(7, 4))

    // Start workflow
    val workflowId = Await.result(
      engine1.start(BirthdayReminderWorkflow, Tuple1(friend)),
      5.seconds
    )
    println(s"Engine1: Started reminder for ${friend.name}: ${workflowId.value}")

    // Wait for workflow to suspend
    Thread.sleep(200)

    // Verify suspended
    val status1 = Await.result(engine1.queryStatus(workflowId), 5.seconds)
    assertEquals(status1, Some(WorkflowStatus.Suspended))

    // Shutdown first engine
    Await.result(engine1.shutdown(), 5.seconds)
    println("Engine1: Shutdown complete")

    // Create second engine with same storage
    val engine2 = WorkflowEngine(storage)

    // Recover workflows
    val report = Await.result(engine2.recover(), 10.seconds)
    println(s"Engine2: Recovered ${report.activeWorkflows} workflows")
    println(s"  - ${report.resumedSuspended} suspended")
    println(s"  - ${report.resumedRunning} running")

    assertEquals(report.activeWorkflows, 1)
    assertEquals(report.resumedSuspended, 1)

    // Verify workflow is still suspended
    val status2 = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status2, Some(WorkflowStatus.Suspended))

    // Send stop event via recovered engine
    Await.result(
      engine2.sendEventTo(workflowId, StopReminders("Recovery test completed")),
      5.seconds
    )

    // Wait for completion
    Thread.sleep(200)

    // Verify completed
    val status3 = Await.result(engine2.queryStatus(workflowId), 5.seconds)
    assertEquals(status3, Some(WorkflowStatus.Succeeded))

    // Cleanup
    Await.result(engine2.shutdown(), 5.seconds)
  }
