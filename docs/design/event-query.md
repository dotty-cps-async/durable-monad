# Event Query Design

## Overview

Workflows often need to wait for multiple types of events and react based on which one arrives first. The Event Query DSL provides a type-safe way to express "wait for any of these conditions" with proper union types for the result.

## Motivation

Current state: Workflows can wait for a single event via `WaitCondition.Event`:

```scala
val event = await(Event[PaymentReceived].receive)
```

Desired state: Wait for multiple conditions with pattern matching on the result:

```scala
(Event[PaymentReceived] | Event[OrderCancelled] | Timer(1.minute)).receive.await match
  case payment: PaymentReceived => handlePayment(payment).await
  case cancel: OrderCancelled => handleCancellation(cancel).await
  case tick: TimerFired => handleTimeout(tick).await
```

## Design Goals

1. **Type Safety**: Result type is a union of all possible event types
2. **Composable DSL**: Use `|` operator to combine conditions
3. **Minimal Engine Changes**: Extend existing WaitCondition model
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

  /** Create query for timer */
  def timer(duration: FiniteDuration): EventQuery[TimerFired] =
    TimerQuery(duration)

  def timerUntil(instant: Instant): EventQuery[TimerFired] =
    TimerAtQuery(instant)

  /** Create query for workflow completion */
  def workflow[R](workflowId: WorkflowId): EventQuery[WorkflowResult[R]] =
    WorkflowCompletion(workflowId)
```

### Convenience Aliases

```scala
/** Timer query builder - alternative entry point */
object Timer:
  def apply(duration: FiniteDuration): EventQuery[TimerFired] =
    EventQuery.timer(duration)

  def at(instant: Instant): EventQuery[TimerFired] =
    EventQuery.timerUntil(instant)

/** Workflow completion query builder */
object Workflow:
  def apply[R](workflowId: WorkflowId): EventQuery[WorkflowResult[R]] =
    EventQuery.workflow(workflowId)
```

### Result Wrapper Types

```scala
/** Result when a timer fires in an EventQuery */
case class TimerFired(scheduledAt: Instant, firedAt: Instant)
```

### Workflow Done

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
  case _: TimerFired => ...
```

### Enhanced WaitCondition

```scala
enum WaitCondition[A, S <: DurableStorageBackend]:
  // Existing cases...
  case Event[E, S <: DurableStorageBackend](...)
  case Timer[S <: DurableStorageBackend](...)

  /** Wait for any workflow to complete */
  case Workflow[R, S <: DurableStorageBackend](
    workflowId: WorkflowId,
    storage: DurableStorage[WorkflowResult[R], S]
  ) extends WaitCondition[WorkflowResult[R], S]

  /** Wait for any of multiple conditions - first match wins */
  case AnyOf[A, S <: DurableStorageBackend](
    conditions: List[QueryCondition[?, S]],
    storage: DurableStorage[A, S]
  ) extends WaitCondition[A, S]

/**
 * A single condition within an EventQuery.
 * Captures the condition type and how to transform it to the union result type.
 */
case class QueryCondition[T, S <: DurableStorageBackend](
  /** The underlying wait condition */
  condition: WaitCondition[T, S],
  /** Transform the condition result to the union type */
  transform: T => Any,
  /** Index in the query for identification */
  index: Int
)
```

## DSL Implementation

### Implementation

```scala
/** Descriptor for a condition - storage-independent, used for combining */
enum ConditionDescriptor:
  case EventDesc(eventName: String, index: Int)
  case TimerDesc(duration: Option[FiniteDuration], instant: Option[Instant], index: Int)
  case WorkflowDesc(workflowId: WorkflowId, index: Int)

  def reindex(offset: Int): ConditionDescriptor = this match
    case EventDesc(n, i) => EventDesc(n, i + offset)
    case TimerDesc(d, inst, i) => TimerDesc(d, inst, i + offset)
    case WorkflowDesc(w, i) => WorkflowDesc(w, i + offset)

/**
 * EventQuery sealed hierarchy - cases are package-private.
 * Users interact via Event[], Timer(), Workflow() entry points.
 */
sealed trait EventQuery[A]:
  def |[B](other: EventQuery[B]): EventQuery[A | B]
  def receive[S <: DurableStorageBackend](using backend: S): Durable[A]

  // Internal: condition descriptors for combining
  protected def descriptors: List[ConditionDescriptor]

object EventQuery:
  /** Single event query */
  case class SingleEvent[E](eventName: String) extends EventQuery[E]:
    protected def descriptors = List(ConditionDescriptor.EventDesc(eventName, 0))

    def |[B](other: EventQuery[B]): EventQuery[E | B] =
      Combined(this.descriptors ++ other.descriptors.map(_.reindex(1)))

    def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[E, S]): Durable[E] =
      Durable.suspend(WaitCondition.Event(eventName, storage))

  /** Timer query (by duration) */
  case class TimerDuration(duration: FiniteDuration) extends EventQuery[TimerFired]:
    protected def descriptors = List(ConditionDescriptor.TimerDesc(Some(duration), None, 0))

    def |[B](other: EventQuery[B]): EventQuery[TimerFired | B] =
      Combined(this.descriptors ++ other.descriptors.map(_.reindex(1)))

    def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[TimerFired, S]): Durable[TimerFired] =
      val wakeAt = Instant.now().plusMillis(duration.toMillis)
      Durable.sleepUntil(wakeAt)(using summon[DurableStorage[Instant, S]])
        .map(fired => TimerFired(wakeAt, fired))

  /** Timer query (by instant) */
  case class TimerInstant(instant: Instant) extends EventQuery[TimerFired]:
    protected def descriptors = List(ConditionDescriptor.TimerDesc(None, Some(instant), 0))

    def |[B](other: EventQuery[B]): EventQuery[TimerFired | B] =
      Combined(this.descriptors ++ other.descriptors.map(_.reindex(1)))

    def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[TimerFired, S]): Durable[TimerFired] =
      Durable.sleepUntil(instant)(using summon[DurableStorage[Instant, S]])
        .map(fired => TimerFired(instant, fired))

  /** Workflow completion query */
  case class WorkflowCompletion[R](targetId: WorkflowId) extends EventQuery[WorkflowResult[R]]:
    protected def descriptors = List(ConditionDescriptor.WorkflowDesc(targetId, 0))

    def |[B](other: EventQuery[B]): EventQuery[WorkflowResult[R] | B] =
      Combined(this.descriptors ++ other.descriptors.map(_.reindex(1)))

    def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[WorkflowResult[R], S]): Durable[WorkflowResult[R]] =
      Durable.suspend(WaitCondition.Workflow(targetId, storage))

  /** Combined query (result of |) */
  case class Combined[A](protected val descriptors: List[ConditionDescriptor]) extends EventQuery[A]:
    def |[B](other: EventQuery[B]): EventQuery[A | B] =
      val offset = descriptors.size
      Combined(descriptors ++ other.descriptors.map(_.reindex(offset)))

    def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[A, S]): Durable[A] =
      val conditions = resolveConditions[S]()
      Durable.suspend(WaitCondition.AnyOf(conditions, storage))

    private def resolveConditions[S <: DurableStorageBackend]()(using backend: S): List[QueryCondition[?, S]] =
      descriptors.map {
        case ConditionDescriptor.EventDesc(name, idx) =>
          QueryCondition(WaitCondition.Event(name, ???), identity, idx)
        case ConditionDescriptor.TimerDesc(dur, inst, idx) =>
          val wakeAt = inst.getOrElse(Instant.now().plusMillis(dur.get.toMillis))
          QueryCondition(WaitCondition.Timer(wakeAt, summon[DurableStorage[Instant, S]]),
            (i: Instant) => TimerFired(wakeAt, i), idx)
        case ConditionDescriptor.WorkflowDesc(workflowId, idx) =>
          QueryCondition(WaitCondition.Workflow(workflowId, ???), identity, idx)
      }
```

## Type System Considerations

### Union Type Result

The result type of a combined query is a union type:

```scala
val query: EventQuery[PaymentReceived | OrderCancelled | TimerFired] =
  Event[PaymentReceived] | Event[OrderCancelled] | Timer(1.minute)
```

This allows exhaustive pattern matching:

```scala
query.receive.await match
  case p: PaymentReceived => ...
  case c: OrderCancelled => ...
  case t: TimerFired => ...
```

### Storage for Union Types

The `WaitCondition.AnyOf` needs storage for the union result type. Options:

**Option A: Individual Storage per Condition**
Each condition stores its result in its own type, and on resume we know which one fired.

```scala
case class AnyOf[A, S <: DurableStorageBackend](
  conditions: List[QueryCondition[?, S]],
  // Storage for an envelope that tracks which condition fired
  storage: DurableStorage[QueryResult[A], S]
)

case class QueryResult[A](
  conditionIndex: Int,
  value: A  // Already transformed to union type
)
```

**Option B: Type-Erased Storage with Runtime Cast**
Store the value as `Any` with the condition index, cast on resume.

**Recommendation**: Option A with `QueryResult` envelope provides type safety at storage boundaries.

### DurableStorage for Union Types

Need to derive/provide storage for union types. Approaches:

**Option 1: Sealed Trait Wrapper**
Wrap in a sealed hierarchy for easier serialization:

```scala
sealed trait QueryResultValue
case class EventValue[E](event: E) extends QueryResultValue
case class TimerValue(fired: TimerFired) extends QueryResultValue
case class WorkflowValue[R](done: WorkflowResult[R]) extends QueryResultValue
```

**Option 2: Codec Derivation for Union Types**
Derive JSON codec that handles union types via discriminator field.

**Option 3: Store Condition Index + Raw Bytes**
Store `(conditionIndex: Int, bytes: Array[Byte])` and use condition-specific codec on read.

**Recommendation**: Option 3 is most flexible - each condition uses its own codec, unified at storage level.

## WorkflowEngine Changes

### Handling AnyOf Conditions

When a workflow suspends with `WaitCondition.AnyOf`:

```scala
private def registerWaiter(
  workflowId: WorkflowId,
  condition: WaitCondition[?, ?],
  activityIndex: Int
): Future[Unit] =
  condition match
    // Existing cases...

    case WaitCondition.AnyOf(conditions, _) =>
      // Register for all sub-conditions
      val registrations = conditions.map { qc =>
        registerQueryCondition(workflowId, qc, activityIndex)
      }
      Future.sequence(registrations).map(_ => ())

private def registerQueryCondition(
  workflowId: WorkflowId,
  qc: QueryCondition[?, ?],
  activityIndex: Int
): Future[Unit] =
  qc.condition match
    case WaitCondition.Event(name, _) =>
      state.addEventWaiter(name, workflowId, qc.index)
      Future.successful(())
    case WaitCondition.Timer(wakeAt, _) =>
      scheduleTimerForQuery(workflowId, wakeAt, activityIndex, qc.index)
      Future.successful(())
    case WaitCondition.Workflow(targetId, _) =>
      state.addWorkflowWaiter(targetId, workflowId, qc.index)
      Future.successful(())
```

### Event Delivery with Queries

When an event arrives, check if any waiting workflow has an `AnyOf` that matches:

```scala
def sendEvent[E](event: E)(using eventName: DurableEventName[E], ...): Future[Unit] =
  val name = eventName.name

  // Find workflows waiting for this event (including AnyOf)
  val waiters = state.findEventWaiters(name)

  waiters.headOption match
    case Some(waiter) =>
      // Determine if single event or AnyOf
      state.getActive(waiter.workflowId).flatMap { record =>
        record.waitCondition match
          case Some(WaitCondition.Event(_, storage)) =>
            // Simple case - single event wait
            deliverSingleEvent(waiter.workflowId, event, storage)

          case Some(WaitCondition.AnyOf(conditions, storage)) =>
            // Query case - deliver via QueryResult
            val qc = conditions(waiter.conditionIndex)
            val transformed = qc.transform(event)
            val result = QueryResult(waiter.conditionIndex, transformed)
            deliverQueryResult(waiter.workflowId, result, storage, conditions)

          case _ => Future.successful(())
      }
    case None =>
      storage.savePendingEvent(name, EventId.generate(), event, Instant.now())
```

### Cleanup on First Match

When any condition in `AnyOf` fires, cancel/cleanup other registrations:

```scala
private def deliverQueryResult[A, S <: DurableStorageBackend](
  workflowId: WorkflowId,
  result: QueryResult[A],
  storage: DurableStorage[QueryResult[A], S],
  conditions: List[QueryCondition[?, S]]
): Future[Unit] =
  // Cancel other conditions
  conditions.zipWithIndex.foreach { case (qc, idx) =>
    if idx != result.conditionIndex then
      cancelQueryCondition(workflowId, qc)
  }

  // Store result and resume
  val activityIndex = state.getActive(workflowId).map(_.metadata.activityIndex).getOrElse(0)
  for
    _ <- storage.storeStep(storage, workflowId, activityIndex, result)
    _ <- resumeWorkflow(workflowId, activityIndex + 1)
  yield ()

private def cancelQueryCondition(workflowId: WorkflowId, qc: QueryCondition[?, ?]): Unit =
  qc.condition match
    case WaitCondition.Timer(_, _) =>
      state.removeTimer(workflowId)
    case WaitCondition.Event(name, _) =>
      state.removeEventWaiter(name, workflowId)
    case WaitCondition.Workflow(targetId, _) =>
      state.removeWorkflowWaiter(targetId, workflowId)
```

## State Management

### Enhanced WorkflowEngineState

```scala
class WorkflowEngineState:
  // Existing...

  // Event waiters: eventName -> List[(workflowId, conditionIndex)]
  private val eventWaiters: Map[String, List[(WorkflowId, Int)]]

  // Workflow completion waiters: targetWorkflowId -> List[(waiterWorkflowId, conditionIndex)]
  private val workflowWaiters: Map[WorkflowId, List[(WorkflowId, Int)]]

  def addEventWaiter(eventName: String, workflowId: WorkflowId, conditionIndex: Int): Unit
  def findEventWaiters(eventName: String): List[EventWaiter]
  def removeEventWaiter(eventName: String, workflowId: WorkflowId): Unit

  def addWorkflowWaiter(targetId: WorkflowId, waiterId: WorkflowId, conditionIndex: Int): Unit
  def findWorkflowWaiters(targetId: WorkflowId): List[WorkflowWaiter]
  def removeWorkflowWaiter(targetId: WorkflowId, waiterId: WorkflowId): Unit
```

### Persistence

The `WaitCondition.AnyOf` must be serializable for recovery:

```scala
// In DurableStorageBackend
def updateWorkflowStatusAndCondition(
  workflowId: WorkflowId,
  status: WorkflowStatus,
  condition: Option[WaitCondition[?, ?]]
): Future[Unit]

// Serialization for AnyOf
case class SerializedAnyOf(
  conditions: List[SerializedQueryCondition]
)

case class SerializedQueryCondition(
  conditionType: String,  // "event", "timer", "child"
  eventName: Option[String],
  wakeAt: Option[Instant],
  childId: Option[WorkflowId],
  resultType: Option[String],
  index: Int
)
```

## Usage Examples

### Basic Multi-Event Wait

```scala
object PaymentWorkflow extends DurableFunction1[OrderId, PaymentResult, S]:
  def apply(orderId: OrderId)(using S): Durable[PaymentResult] = async[Durable] {
    val order = loadOrder(orderId)

    // Wait for payment or cancellation or timeout
    val result = await(
      (Event[PaymentReceived] | Event[OrderCancelled] | Timer(30.minutes)).receive
    )

    result match
      case payment: PaymentReceived =>
        processPayment(payment)
        PaymentResult.Success(payment.transactionId)

      case _: OrderCancelled =>
        PaymentResult.Cancelled

      case _: TimerFired =>
        await(sendReminder(orderId))
        // Recurse with continueWith to wait again
        await(continueWith(orderId))
  }
```

### Workflow Coordination

```scala
// validationWorkflowId could come from anywhere - started earlier, passed via event, etc.
val query =
  Event[ApprovalReceived] | Workflow[ValidationResult](validationWorkflowId) | Timer(24.hours)

await(query.receive) match
  case approval: ApprovalReceived => ...
  case WorkflowSessionResult.Completed(_, result) => handleValidation(result)
  case WorkflowSessionResult.Failed(_, error) => handleValidationError(error)
  case _: TimerFired => ...
```

### Dynamic Query Building

```scala
def buildQuery(options: QueryOptions): EventQuery[?] =
  var query: EventQuery[?] = Event[BaseEvent]

  if options.includeTimeout then
    query = query | Timer(options.timeout)

  if options.awaitApproval then
    query = query | Event[Approval]

  query
```

## Alternative: Simplified Design with Sealed Trait

If union types prove problematic, use an explicit sealed hierarchy:

```scala
sealed trait QueryResult[+A]
case class EventReceived[E](event: E) extends QueryResult[E]
case class TimerExpired(fired: TimerFired) extends QueryResult[Nothing]
case class WorkflowFinished[R](done: WorkflowResult[R]) extends QueryResult[R]

// Query builder produces QueryResult
trait EventQuery[A]:
  def receive[S <: DurableStorageBackend](using S): Durable[QueryResult[A]]

// Usage
await(query.receive) match
  case EventReceived(payment: PaymentReceived) => ...
  case EventReceived(cancel: OrderCancelled) => ...
  case TimerExpired(t) => ...
```

This trades some type precision for simpler implementation and serialization.

## Implementation Plan

1. **Phase 1: Core Types**
   - Replace `EventBuilder[E]` with unified `EventQuery[A]` sealed trait
   - Define `ConditionDescriptor` enum for storage-independent condition specs
   - Define result wrapper types (`TimerFired`, `WorkflowCompleted`)
   - Add `WaitCondition.AnyOf` case
   - Define `QueryCondition` for resolved conditions with storage

2. **Phase 2: DSL Implementation**
   - Implement `SingleEvent[E]` with `receive` and `|`
   - Implement `TimerQuery` and `TimerAtQuery`
   - Implement `ChildWorkflowQuery[R]`
   - Implement `Combined[A]` for composed queries
   - Update `Event` object to return `EventQuery[E]`
   - Add `Timer` and `ChildWorkflow` convenience objects

3. **Phase 3: Engine Support**
   - Extend `WorkflowEngineState` with multi-condition waiters (event, timer, child per workflow)
   - Implement `registerWaiter` for `AnyOf` - register all sub-conditions
   - Implement delivery logic - on first match, cancel others and resume
   - Update `sendEvent`, timer callback, and child completion to handle `AnyOf`

4. **Phase 4: Persistence**
   - Serialize `AnyOf` conditions (list of condition descriptors)
   - Store `QueryResult` with condition index + serialized value
   - Recovery logic: re-register all sub-conditions for suspended `AnyOf` waits

5. **Phase 5: Testing**
   - Unit tests for DSL composition and type inference
   - Integration tests for multi-event workflows
   - Tests for cleanup on first match
   - Recovery tests for `AnyOf` conditions
   - Backward compatibility tests for single-event waits

## Open Questions

1. **Exhaustiveness checking**: Can we provide compile-time warnings for non-exhaustive matches on query results?

2. **Priority**: Should conditions have priority when multiple fire simultaneously?

3. **Batching**: Should we support "wait for all" (AND) in addition to "wait for any" (OR)?

4. **Event buffering**: If an event arrives while building the query (between start and suspend), should it be delivered?

5. **Cancellation semantics**: When one condition fires, should pending events for other conditions be preserved or discarded?
