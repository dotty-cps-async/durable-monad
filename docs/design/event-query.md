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
5. **Backward Compatible**: Existing `Event[E].receive` syntax continues to work

## Backward Compatibility

The unified `EventQuery[A]` replaces the existing `EventBuilder[E]`. The API remains unchanged for single-event waits:

```scala
// Before (EventBuilder)
val event = await(Event[PaymentReceived].receive)

// After (EventQuery) - same syntax, same behavior
val event = await(Event[PaymentReceived].receive)
```

The only difference is that `Event[E]` now returns `EventQuery[E]` instead of `EventBuilder[E]`, enabling composition via `|`.

## Core Types

### EventQuery[A] - Unified Query Builder

A single type that handles both single-condition and multi-condition waits. This replaces the existing `EventBuilder` - a single event is just a query with one condition.

```scala
/**
 * A composable query for waiting on one or more conditions.
 * Type parameter A is the result type (union type when combined).
 *
 * Single condition: Event[PaymentReceived].receive
 * Combined: (Event[PaymentReceived] | Event[OrderCancelled]).receive
 */
sealed trait EventQuery[A]:
  /** Combine with another query using OR semantics */
  def |[B](other: EventQuery[B]): EventQuery[A | B]

  /** Create Durable that suspends until any condition is met */
  def receive[S <: DurableStorageBackend](using backend: S): Durable[A]

/**
 * Event object - entry point for event queries.
 * Replaces EventBuilder with unified EventQuery.
 */
object Event:
  /** Create query for event type E */
  def apply[E](using eventName: DurableEventName[E]): EventQuery[E] =
    EventQuery.event(eventName.name)

object EventQuery:
  /** Create query from event name */
  def event[E](eventName: String): EventQuery[E] =
    SingleEvent(eventName)
```

### Result Types as Query Builders

The result types themselves serve as query builders - the type you match on is the type you use to build the query:

```scala
/** Result when a timer fires - also serves as query builder */
case class TimeReached(scheduledAt: Instant, firedAt: Instant)

object TimeReached:
  def after(duration: FiniteDuration): EventQuery[TimeReached] =
    EventQuery.TimerDuration(duration)

  def at(instant: Instant): EventQuery[TimeReached] =
    EventQuery.TimerInstant(instant)

/** Result when waiting for workflow completion - also serves as query builder */
object WorkflowDone:
  def apply[R](workflowId: WorkflowId): EventQuery[WorkflowResult[R]] =
    EventQuery.WorkflowCompletion(workflowId)
```

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

`EventQuery` replaces `WaitCondition` as the unified type for all wait conditions:

```scala
sealed trait EventQuery[A]:
  def |[B](other: EventQuery[B]): EventQuery[A | B]
  def receive[S <: DurableStorageBackend](using S): Durable[A]

/** Single condition - can be combined with | */
sealed trait SingleEventQuery[A] extends EventQuery[A]

object EventQuery:
  case class SingleEvent[E](eventName: String) extends SingleEventQuery[E]
  case class TimerDuration(duration: FiniteDuration) extends SingleEventQuery[TimeReached]
  case class TimerInstant(instant: Instant) extends SingleEventQuery[TimeReached]
  case class WorkflowCompletion[R](targetId: WorkflowId) extends SingleEventQuery[WorkflowResult[R]]

  /** Combined query - optimized internal representation */
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

## Implementation Plan

1. **Phase 1: Core Types**
   - Define `EventQuery[A]` and `SingleEventQuery[A]` traits
   - Define `TimeReached` result type with query builder methods
   - Remove `WaitCondition`, use `EventQuery` everywhere

2. **Phase 2: DSL Implementation**
   - Implement `SingleEvent[E]`, `TimerDuration`, `TimerInstant`, `WorkflowCompletion[R]`
   - Implement `Combined[A]` with `|` operator
   - Add `TimeReached.after/at` and `WorkflowDone[R]` query builders

3. **Phase 3: Engine Support**
   - Update engine to work with `EventQuery` instead of `WaitCondition`
   - Register waiters for all conditions in `Combined`
   - On first match: store result, cancel others, resume

4. **Phase 4: Persistence**
   - Serialize `SingleEventQuery` instances for recovery
   - Re-register waiters on recovery

5. **Phase 5: Testing**
   - DSL composition and type inference
   - Multi-condition workflows
   - Cleanup on first match
   - Recovery tests

## Design Decisions

1. **Exhaustiveness checking**: Rely on Scala 3's built-in union type exhaustiveness. Additional safety checks can be added later if needed.

2. **Priority**: No explicit priority. First delivered wins. Simple and deterministic.

3. **Batching (AND semantics)**: Out of scope. Only OR (`|`) supported initially. AND can be achieved with sequential awaits.

4. **Event buffering**: No special buffering. Workflow suspends atomically with `receive`. Existing pending queue behavior applies.

5. **Cancellation semantics**: When one condition fires, only unregister waiters for other conditions. Pending events remain in queue (not owned by query, may be consumed by other workflows).
