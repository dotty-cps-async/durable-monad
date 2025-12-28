# Quick Start

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.dotty-cps-async" %%% "durable-monad-core" % "0.1.0"
```

For Scala.js or Scala Native, use the `%%%` cross-version operator as shown above.

## Basic Example: Birthday Reminder

A workflow that reminds you about friends' birthdays. It reads from a file, waits until the day before each birthday, and sends a reminder.

```scala
import durable.*
import cps.*
import cps.monads.{*, given}
import scala.concurrent.duration.*
import java.time.{LocalDate, MonthDay}
import scala.io.Source

// Data types
case class Friend(name: String, birthday: MonthDay)

// Parse birthdays from file: "Alice,03-15" (name, MM-DD)
def loadBirthdays(filename: String): List[Friend] =
  Source.fromFile(filename).getLines().map { line =>
    val Array(name, date) = line.split(",")
    val Array(month, day) = date.split("-").map(_.toInt)
    Friend(name, MonthDay.of(month, day))
  }.toList

// Calculate days until next occurrence of this birthday
def daysUntilBirthday(birthday: MonthDay, from: LocalDate = LocalDate.now()): Long =
  val thisYear = birthday.atYear(from.getYear)
  val nextBirthday = if thisYear.isAfter(from) then thisYear
                     else birthday.atYear(from.getYear + 1)
  java.time.temporal.ChronoUnit.DAYS.between(from, nextBirthday)

// Event: request to stop reminders
case class StopReminders(reason: String)
given DurableEventName[StopReminders] = DurableEventName("stop-reminders")

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
          println(s"Reminder: ${friend.name}'s birthday is tomorrow!")

          // Wait one more day, then loop for next year
          Durable.sleep(1.day).await
          await(continueWith(Tuple1(friend)))

        case StopReminders(reason) =>
          // Stop event received - end workflow
          s"Stopped reminders for ${friend.name}: $reason"
    }
```

## Running the Workflow

```scala
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
given ExecutionContext = ExecutionContext.global

@main def runBirthdayReminders(): Unit =
  // Set up storage backend
  val storage = MemoryBackingStore()
  given MemoryBackingStore = storage
  given [T]: DurableStorage[T, MemoryBackingStore] = storage.forType[T]

  // Create workflow engine
  val engine = WorkflowEngine(storage)

  // Load birthdays from file and start a workflow for each friend
  val friends = loadBirthdays("birthdays.txt")
  // birthdays.txt contains:
  //   Alice,03-15
  //   Bob,07-22
  //   Carol,12-25

  friends.foreach { friend =>
    val workflowId = Await.result(
      engine.start(BirthdayReminderWorkflow, Tuple1(friend)),
      5.seconds
    )
    println(s"Started reminder for ${friend.name}: ${workflowId.value}")
  }

  // The workflows now run durably:
  // - Each friend gets their own long-running workflow
  // - Waits days/months until the birthday approaches
  // - If process restarts, workflows resume from where they left off
  // - Reminders won't be sent twice (activities are cached)

  // To stop reminders for a specific friend, send an event:
  // engine.sendEventTo(aliceWorkflowId, StopReminders("No longer friends"))
```

## Restarting the Engine

`MemoryBackingStore` is in-memory only (for testing). For real persistence, use `MemoryWithJsonBackupStorage` which saves state to a JSON file:

```scala
import java.nio.file.Path
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.github.plokhotnyuk.jsoniter_scala.core.*

// JSON codec for your types
given JsonValueCodec[Friend] = JsonCodecMaker.make
given JsonValueCodec[StopReminders] = JsonCodecMaker.make

@main def startWithPersistence(): Unit =
  // Use persistent storage backed by JSON file
  val storage = MemoryWithJsonBackupStorage(Path.of("workflows.json"))
  storage.restore()  // Load previous state if exists

  given MemoryWithJsonBackupStorage = storage
  given [T: JsonValueCodec]: DurableStorage[T, MemoryWithJsonBackupStorage] = storage.forType[T]

  val engine = WorkflowEngine(storage)

  // Recover any previously active workflows
  val report = Await.result(engine.recover(), 10.seconds)
  println(s"Recovered ${report.activeWorkflows} workflows")
  println(s"  - ${report.resumedSuspended} suspended (waiting for timer/event)")
  println(s"  - ${report.resumedRunning} running (resumed execution)")

  // Start new workflows...
  val friend = Friend("Alice", MonthDay.of(12, 25))
  val workflowId = Await.result(
    engine.start(BirthdayReminderWorkflow, Tuple1(friend)),
    5.seconds
  )

  // On shutdown, save state
  Runtime.getRuntime.addShutdownHook(Thread(() => storage.shutdown()))
```

## Key Concepts

1. **Workflows** are defined as `DurableFunction` objects with `async[Durable]` blocks
2. **Activities** (external calls like HTTP, DB) are automatically cached
3. **Timers** use `Durable.sleep()` for durable waiting
4. **Events** can be received with `Durable.awaitEvent[E]`
5. **Loops** use `continueWith` to restart with new arguments (clears history)

## Next Steps

- See the [User Manual](user-manual.md) for detailed API documentation
- Check out [examples](https://github.com/dotty-cps-async/durable-monad/tree/main/core/shared/src/test/scala/durable/examples) in the repository
