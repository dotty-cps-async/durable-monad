# User Manual

## Overview

durable-monad is a Scala library for building monadic durable workflows. It provides replay-based execution where activities are cached and workflows can survive process restarts.

## Core Concepts

### The Durable Monad

`Durable[A]` is a free monad representing a durable computation. It describes what to do, not how to do it. The actual execution is handled by `WorkflowSessionRunner`.

```scala
import durable.*
import cps.*
import cps.monads.{*, given}

val workflow: Durable[Int] = async[Durable] {
  val x = compute()     // Cached activity
  val y = compute2()    // Cached activity
  x + y
}
```

### Workflow Functions

Workflows are defined as objects extending `DurableFunction`:

```scala
object MyWorkflow extends DurableFunction1[Input, Output, MyBackend]
    derives DurableFunctionName:

  override val functionName = DurableFunction.register(this)

  def apply(input: Input)(using MyBackend): Durable[Output] =
    async[Durable] {
      // workflow logic
    }
```

Variants by arity: `DurableFunction0`, `DurableFunction1`, `DurableFunction2`, etc.

### Storage Backends

All cached values require a `DurableStorage[T, S]` typeclass instance. The library provides `MemoryBackingStore` for in-memory storage:

```scala
import durable.memory.MemoryBackingStore

given backend: MemoryBackingStore = MemoryBackingStore()
given [T]: DurableStorage[T, MemoryBackingStore] = backend.forType[T]
```

For production, implement your own `DurableStorageBackend` (e.g., backed by PostgreSQL, Redis, etc.).

## Operations

### Activities (Outbound - Cached)

Activities are external operations that get cached. The preprocessor automatically wraps value definitions:

```scala
async[Durable] {
  val result = httpGet(url)        // Auto-wrapped as activity
  val data = dbQuery(sql)          // Auto-wrapped as activity
  process(result, data)
}
```

You can also explicitly create activities:

```scala
// Synchronous activity
Durable.activitySync[String] {
  expensiveComputation()
}

// Async activity (Future)
Durable.activity[String] {
  Future { httpCall() }
}
```

### Timers (Durable Sleep)

```scala
async[Durable] {
  Durable.sleep(1.hour).await      // Workflow suspends for 1 hour
  Durable.sleep(30.days).await     // Can wait for days/months
}
```

### Events

Receive events sent to the workflow:

```scala
async[Durable] {
  // Wait for a specific event type
  val payment = Durable.awaitEvent[PaymentReceived].await

  // Wait for multiple event types with timeout
  val result = await((Event[Approval] | Event[Rejection] | TimeReached.after(24.hours)).receive)

  result match
    case approval: Approval => processApproval(approval)
    case rejection: Rejection => processRejection(rejection)
    case _: TimeReached => handleTimeout()
}
```

Send events from outside:

```scala
// Targeted event to specific workflow
engine.sendEventTo(workflowId, PaymentReceived(amount))

// Broadcast event to all waiting workflows
engine.sendEventBroadcast(InventoryUpdate(itemId, quantity))
```

### Looping with continueWith

Since mutable variables are not allowed, use `continueWith` for loops:

```scala
object SubscriptionWorkflow
    extends DurableFunction3[SubscriptionId, BigDecimal, Int, SubscriptionResult, Backend]:

  def apply(subscriptionId: SubscriptionId, totalBilled: BigDecimal, cyclesCompleted: Int)
           (using Backend): Durable[SubscriptionResult] =
    async[Durable] {
      billingService.charge(subscriptionId).await match
        case BillingResult.Success(amount) =>
          Durable.sleep(30.days).await
          // Loop: restart with updated arguments (clears activity history)
          await(continueWith(subscriptionId, totalBilled + amount, cyclesCompleted + 1))

        case BillingResult.Cancelled =>
          SubscriptionResult.Completed(totalBilled, cyclesCompleted)
    }
```

### Switching Workflows with continueAs

Transition to a different workflow:

```scala
async[Durable] {
  if needsRetry then
    await(RetryWorkflow.continueAs(retryArgs))
  else
    normalResult
}
```

## Context and Environment

### Accessing Context

```scala
async[Durable] {
  val wfId = Durable.workflowId.await           // Current workflow ID
  val ctx = Durable.runContext.await            // Full run context
  val config = Durable.configRaw("db").await    // Configuration value
}
```

### Environment Services

Use `appContext` for dependency injection:

```scala
async[Durable] {
  val emailService = Durable.appContext[EmailService].await
  val dbService = Durable.appContext[DatabaseService].await

  emailService.send(email).await
}
```

## Ephemeral Resources

Resources that should not be cached (connections, file handles) are marked with `DurableEphemeral`:

```scala
given DurableEphemeral[FileHandle] with {}

async[Durable] {
  // file is re-acquired on each replay, properly closed at end
  val file = openFile("data.csv")
  val content = file.read()  // Activity - cached
}
```

Explicit resource management:

```scala
await(Durable.withResource(
  acquire = openConnection(),
  release = _.close()
) { conn =>
  Durable.activitySync { conn.query("SELECT ...") }
})
```

## Retry Policy

Configure retry behavior for activities:

```scala
val policy = RetryPolicy(
  maxAttempts = 5,
  initialBackoff = 100.millis,
  maxBackoff = 5.minutes,
  backoffMultiplier = 2.0,
  jitterFactor = 0.1,
  isRecoverable = {
    case _: TimeoutException => true
    case _: NetworkException => true
    case _ => false
  }
)

Durable.activity[String](policy) {
  Future { unreliableHttpCall() }
}
```

Predefined policies:
- `RetryPolicy.default` — 3 attempts with exponential backoff
- `RetryPolicy.noRetry` — Fail immediately

## Workflow Engine

### Creating an Engine

```scala
val engine = WorkflowEnginePlatform.create[MemoryBackingStore](
  WorkflowEngineConfig(
    appContext = AppContext.newCache,
    configSource = ConfigSource.fromMap(Map(
      "database.url" -> "jdbc:postgresql://localhost/mydb"
    ))
  )
)
```

### Starting Workflows

```scala
// For DurableFunction1 - wrap single arg in Tuple1
val workflowId = engine.start(MyWorkflow, Tuple1(input))

// For DurableFunction2
val workflowId = engine.start(MyWorkflow2, (arg1, arg2))
```

### Querying Status and Result

```scala
// Query status
engine.queryStatus(workflowId).map {
  case Some(WorkflowStatus.Running) => println("In progress")
  case Some(WorkflowStatus.Suspended) => println("Waiting for event/timer")
  case Some(WorkflowStatus.Succeeded) => println("Completed successfully")
  case Some(WorkflowStatus.Failed) => println("Failed")
  case Some(WorkflowStatus.Cancelled) => println("Cancelled")
  case None => println("Workflow not found")
}

// Query result (for completed workflows)
engine.queryResult(workflowId).map {
  case Some(result) => println(s"Result: $result")
  case None => println("No result yet")
}
```

### Cancelling Workflows

```scala
engine.cancel(workflowId)  // Returns Future[Boolean]
```

## Multi-Platform Support

The library supports JVM, Scala.js, and Scala Native:

| Platform | Storage | Timer | Notes |
|----------|---------|-------|-------|
| JVM | TrieMap | ScheduledExecutorService | Full concurrent support |
| JS | mutable.Map | setTimeout | Single-threaded |
| Native | TrieMap | Background thread | Thread-safe |

## Error Handling

### Retry Exhaustion

When an activity exceeds retry attempts:

```scala
async[Durable] {
  try
    riskyOperation().await
  catch
    case e: MaxRetriesExceededException =>
      // Handle exhausted retries
      fallback()
}
```

### Non-Recoverable Errors

Mark exceptions as non-recoverable to fail immediately:

```scala
class ValidationError(msg: String) extends Exception(msg) with NonRecoverableException
```

## Limitations

1. **Idempotency** — External endpoints must be idempotent or use outbox pattern
2. **Ephemeral consistency** — `DurableEphemeral` values must be consistent across replays
3. **Versioning** — Changing workflow logic while in-flight requires care
4. **No mutable variables** — Use `continueWith` for loops

See the [blog post](blogpost/durable-execution-at-home.md) for detailed discussion of limitations.
