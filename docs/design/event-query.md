# Event Query Design

## Overview

Workflows often need to wait for multiple types of events and react based on which one arrives first. The Event Query DSL provides a type-safe way to express "wait for any of these conditions" with proper union types for the result.

## Motivation

Current state: Workflows can wait for a single event:

```scala
val event = await(Event[PaymentReceived].receive)
```

Desired state: Wait for multiple conditions with pattern matching on the result:

```scala
(Event[PaymentReceived] | Event[OrderCancelled] | TimeReached.after(1.minute)).receive.await match
  case payment: PaymentReceived => handlePayment(payment).await
  case cancel: OrderCancelled => handleCancellation(cancel).await
  case timeout: TimeReached => handleTimeout(timeout).await
```

## Design Goals

1. **Type Safety**: Result type is a union of all possible event types
2. **Composable DSL**: Use `|` operator to combine conditions
3. **Unified Model**: Single `EventQuery` type for all wait conditions
4. **Pattern Match Friendly**: Result types support idiomatic pattern matching

## Changes from previous implementation

The `SingleEventQuery[A]` DSL type replaces the existing `EventBuilder[E]`. The API remains unchanged for single-event waits:

```scala
// Before (EventBuilder)
val event = await(Event[PaymentReceived].receive)

// After (SingleEventQuery) - same syntax, same behavior
val event = await(Event[PaymentReceived].receive)
```

The only difference is that `Event[E]` now returns `SingleEvent[E]` (a `SingleEventQuery[E]`) instead of `EventBuilder[E]`, enabling composition via `|`.

## Core Types

### Two-Layer Design

- **DSL types** (`SingleEventQuery[A]`): Storage-free, user-facing syntax
- **Resolved types** (`EventQuery.Combined[A, S]`): Hold collected storages, go into `Durable.Suspend`

```scala
/**
 * Resolved query type - holds storages, used in Durable.Suspend.
 * Only Combined exists at runtime after | or .receive.
 */
sealed trait EventQuery[A]

/**
 * DSL types - storage-free, composable via | operator.
 * Storage is collected from context when | or .receive is called.
 */
sealed trait SingleEventQuery[A]

/**
 * Event object - entry point for event queries.
 * Returns storage-free DSL type.
 */
object Event:
  /** Create query for event type E (storage-free) */
  def apply[E](using eventName: DurableEventName[E]): SingleEvent[E] =
    SingleEvent(eventName.name)
```

The `|` operator and `.receive` method collect storage from context - see EventQuery Hierarchy section.

### Result Types as Query Builders

The result types themselves serve as query builders - the type you match on is the type you use to build the query. Factories return storage-free DSL types:

```scala
/** Result when a timer fires - also serves as query builder */
case class TimeReached(scheduledAt: Instant, firedAt: Instant)

object TimeReached:
  /** Create timer query (storage-free) */
  def after(duration: FiniteDuration): TimerDuration =
    TimerDuration(duration)

  def at(instant: Instant): TimerInstant =
    TimerInstant(instant)

/** Result when waiting for workflow completion - also serves as query builder */
object WorkflowDone:
  /** Create workflow completion query (storage-free) */
  def apply[R](workflowId: WorkflowId): WorkflowCompletion[R] =
    WorkflowCompletion(workflowId)
```

Storage is collected when using `|` or `.receive` - see EventQuery Hierarchy section.

### WorkflowResult

`WorkflowResult[A]` is a sealed trait for terminal workflow states. `WorkflowSessionResult.Completed` and `WorkflowSessionResult.Failed` extend it:

```scala
/** Terminal workflow state - success or failure */
sealed trait WorkflowResult[+A]:
  def workflowId: WorkflowId

enum WorkflowSessionResult[+A]:
  case Completed[A](workflowId: WorkflowId, value: A) extends WorkflowSessionResult[A] with WorkflowResult[A]
  case Failed(workflowId: WorkflowId, error: ReplayedException) extends WorkflowSessionResult[Nothing] with WorkflowResult[Nothing]
  case Suspended[S <: DurableStorageBackend](...) extends WorkflowSessionResult[Nothing]
  case ContinueAs[A](...) extends WorkflowSessionResult[A]
```

Usage:
```scala
await(query.receive) match
  case WorkflowSessionResult.Completed(_, result) => ...
  case WorkflowSessionResult.Failed(_, error) => ...
  case _: TimeReached => ...
```

## EventQuery Hierarchy

Two-layer design: DSL types (storage-free) and resolved types (with storage).

### DSL Types (Storage-Free)

User-facing syntax types have no storage parameter. Storage is collected by `|` and `.receive`:

```scala
/** DSL types for building queries - no storage parameter */
sealed trait SingleEventQuery[A]

case class SingleEvent[E](eventName: String) extends SingleEventQuery[E]
case class TimerDuration(duration: FiniteDuration) extends SingleEventQuery[TimeReached]
case class TimerInstant(instant: Instant) extends SingleEventQuery[TimeReached]
case class WorkflowCompletion[R](targetId: WorkflowId) extends SingleEventQuery[WorkflowResult[R]]
```

### Storage Collection via `|` and `.receive`

The `|` operator and `.receive` method require storage context and create resolved types:

```scala
/** Extension methods that collect storage from context */
extension [E](self: SingleEvent[E])
  def |[B, S <: DurableStorageBackend](other: SingleEventQuery[B])(using
    selfStorage: DurableStorage[E, S],
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[E | B, S]

  def receive[S <: DurableStorageBackend](using
    storage: DurableStorage[E, S]
  ): Durable[E]
```

### Resolved Types (With Storage)

`CombinedBuilder` and `EventQuery.Combined` hold collected storages:

```scala
/** Builder accumulating storages - created by | operator */
case class CombinedBuilder[A, S <: DurableStorageBackend](
  events: Map[String, DurableStorage[?, S]],
  timerAt: Option[(Instant, DurableStorage[TimeReached, S])],
  workflows: Map[WorkflowId, DurableStorage[WorkflowResult[?], S]]
):
  def |[B](other: SingleEventQuery[B])(using DurableStorage[B, S]): CombinedBuilder[A | B, S]
  def receive(using S): Durable[A]

object EventQuery:
  /** Combined is the resolved representation that goes into Durable.Suspend */
  case class Combined[A, S <: DurableStorageBackend](
    events: Map[String, DurableStorage[?, S]],
    timerAt: Option[(Instant, DurableStorage[TimeReached, S])],
    workflows: Map[WorkflowId, DurableStorage[WorkflowResult[?], S]]
  ) extends EventQuery[A]
```

The `|` operator builds `Combined`:
- Events: merged into map by name
- Timers: only the minimum instant is kept (first to fire wins)
- Workflows: merged into map by ID

When multiple conditions fire simultaneously, the first one wins. No wrapper type needed - just returns the value directly.

## Type System Considerations

### Union Type Result

The result type of a combined query is a union type:

```scala
val query: EventQuery[PaymentReceived | OrderCancelled | TimeReached] =
  Event[PaymentReceived] | Event[OrderCancelled] | TimeReached.after(1.minute)
```

This allows exhaustive pattern matching:

```scala
query.receive.await match
  case p: PaymentReceived => ...
  case c: OrderCancelled => ...
  case t: TimeReached => ...
```

### Storage for Union Types

Each condition type has its own codec for serialization. The stored value is the actual result type (event payload, `TimeReached`, `WorkflowResult`).

## WorkflowEngine Changes

When a workflow suspends with `Combined`, register waiters for all sub-conditions. When any condition fires:
1. Store the result value directly
2. Cancel other pending conditions
3. Resume workflow

**Implementation note**: For non-broadcast events, use atomic operations (e.g., `AtomicReference`) to prevent race conditions when multiple conditions fire simultaneously. Only one condition should win and consume the query.

### Persistence

`EventQuery.Combined` must be serializable for recovery. Each `SingleEventQuery` is serialized with its type and parameters. On recovery, waiters are re-registered for all sub-conditions.

## Usage Examples

### Basic Multi-Event Wait

```scala
object PaymentWorkflow extends DurableFunction1[OrderId, PaymentResult, S]:
  def apply(orderId: OrderId)(using S): Durable[PaymentResult] = async[Durable] {
    val order = loadOrder(orderId)

    // Wait for payment or cancellation or timeout
    val result = await(
      (Event[PaymentReceived] | Event[OrderCancelled] | TimeReached.after(30.minutes)).receive
    )

    result match
      case payment: PaymentReceived =>
        processPayment(payment)
        PaymentResult.Success(payment.transactionId)

      case _: OrderCancelled =>
        PaymentResult.Cancelled

      case _: TimeReached =>
        await(sendReminder(orderId))
        // Recurse with continueWith to wait again
        await(continueWith(orderId))
  }
```

### Workflow Coordination

```scala
// validationWorkflowId could come from anywhere - started earlier, passed via event, etc.
val query =
  Event[ApprovalReceived] | WorkflowDone[ValidationResult](validationWorkflowId) | TimeReached.after(24.hours)

await(query.receive) match
  case approval: ApprovalReceived => ...
  case WorkflowSessionResult.Completed(_, result) => handleValidation(result)
  case WorkflowSessionResult.Failed(_, error) => handleValidationError(error)
  case _: TimeReached => ...
```

### Dynamic Query Building

```scala
def buildQuery(options: QueryOptions): EventQuery[?] =
  var query: EventQuery[?] = Event[BaseEvent]

  if options.includeTimeout then
    query = query | TimeReached.after(options.timeout)

  if options.awaitApproval then
    query = query | Event[Approval]

  query
```

## Implementation Status

All phases completed:

1. **Phase 1: Core Types** - DONE
   - `EventQuery[A]` and `SingleEventQuery[A]` traits defined in `EventQuery.scala`
   - `TimeReached` result type with query builder methods
   - `WaitCondition` removed, using `EventQuery.Combined` everywhere

2. **Phase 2: DSL Implementation** - DONE
   - `SingleEvent[E]`, `TimerDuration`, `TimerInstant`, `WorkflowCompletion[R]` implemented
   - `CombinedBuilder[A, S]` with `|` operator for composing queries
   - `TimeReached.after/at` and `WorkflowDone[R]` query builders

3. **Phase 3: Engine Support** - DONE
   - `WorkflowEngineImpl` updated to work with `EventQuery.Combined`
   - `registerWaiter` handles Combined queries (timer registration)
   - `sendEvent` stores winning condition and cancels timer on first-wins
   - `resumeFromTimer` stores winning condition

4. **Phase 4: Persistence** - DONE
   - `storeWinningCondition`/`retrieveWinningCondition` methods in `DurableStorageBackend`
   - `SingleEventQuery[?]` used as type-safe winning condition key
   - `storageForCondition` method on `Combined` for replay lookup
   - Recovery re-registers waiters using persisted condition info

5. **Phase 5: Testing** - DONE
   - DSL composition tests in `CombinedEventQueryTest`
   - Engine integration tests passing
   - Cross-process recovery tests passing

## Design Decisions

1. **Exhaustiveness checking**: Rely on Scala 3's built-in union type exhaustiveness. Additional safety checks can be added later if needed.

2. **Priority**: No explicit priority. First delivered wins. Simple and deterministic.

3. **Batching (AND semantics)**: Out of scope. Only OR (`|`) supported initially. AND can be achieved with sequential awaits.

4. **Event buffering**: No special buffering. Workflow suspends atomically with `receive`. Existing pending queue behavior applies.

5. **Cancellation semantics**: When one condition fires, only unregister waiters for other conditions. Pending events remain in queue (not owned by query, may be consumed by other workflows).
