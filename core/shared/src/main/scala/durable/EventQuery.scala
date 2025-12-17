package durable

import scala.concurrent.duration.FiniteDuration
import java.time.Instant

/**
 * Result when a timer fires.
 *
 * @param scheduledAt The instant when the timer was scheduled to fire
 * @param firedAt The actual instant when the timer fired
 */
case class TimeReached(scheduledAt: Instant, firedAt: Instant)

/**
 * A composable query for waiting on one or more conditions.
 * Type parameter A is the result type (union type when combined).
 *
 * Single condition: Event[PaymentReceived]
 * Combined: Event[PaymentReceived] | Event[OrderCancelled] | TimeReached.after(1.minute)
 *
 * Usage:
 * {{{
 * val result = await((Event[PaymentReceived] | TimeReached.after(30.seconds)).receive)
 * result match
 *   case payment: PaymentReceived => handlePayment(payment)
 *   case timeout: TimeReached => handleTimeout()
 * }}}
 */
sealed trait EventQuery[A]

object EventQuery:
  /**
   * Combined query - the unified resolved representation.
   * Single conditions are Combined with one entry.
   * All queries that go into Durable.Suspend are Combined.
   *
   * @param events Map of eventName -> storage for event conditions
   * @param timerAt Optional (instant, storage) for timer condition
   * @param workflows Map of workflowId -> storage for workflow completion conditions
   */
  case class Combined[A, S <: DurableStorageBackend](
    events: Map[String, DurableStorage[?, S]],
    timerAt: Option[(Instant, DurableStorage[TimeReached, S])],
    workflows: Map[WorkflowId, DurableStorage[WorkflowResult[?], S]]
  ) extends EventQuery[A]:
    /** Check if this is a multi-condition query (needs first-wins handling) */
    def isMultiCondition: Boolean =
      events.size + timerAt.size + workflows.size > 1

    /** Get all event names this query is waiting for */
    def eventNames: Set[String] = events.keySet

    /** Check if waiting for a specific event */
    def hasEvent(name: String): Boolean = events.contains(name)

    /** Check if waiting for timer */
    def hasTimer: Boolean = timerAt.isDefined

    /** Get storage for the winning condition (for replay) */
    def storageForCondition(winning: SingleEventQuery[?]): Option[DurableStorage[?, S]] =
      winning match
        case SingleEvent(name) => events.get(name)
        case _: TimerDuration | _: TimerInstant => timerAt.map(_._2)
        case WorkflowCompletion(id) => workflows.get(id)

/**
 * DSL types for building queries. These don't have storage until .receive or | is called.
 * Note: `receive` is defined on each concrete type with appropriate storage bounds,
 * not on the trait (different types need different storage parameters).
 */
sealed trait SingleEventQuery[A]

/**
 * Single event query - wait for a specific event type.
 */
case class SingleEvent[E](eventName: String) extends SingleEventQuery[E]:
  def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[E, S]): Durable[E] =
    // Single event becomes Combined with one entry
    Durable.Suspend(EventQuery.Combined[E, S](
      events = Map(eventName -> storage),
      timerAt = None,
      workflows = Map.empty
    ))

  def |[B, S <: DurableStorageBackend](other: SingleEventQuery[B])(using
    backend: S,
    selfStorage: DurableStorage[E, S],
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[E | B, S] =
    val selfEntry = (eventName, selfStorage.asInstanceOf[DurableStorage[?, S]])
    other match
      case single: SingleEvent[?] =>
        CombinedBuilder[E | B, S](
          events = Map(eventName -> selfStorage, single.eventName -> otherStorage),
          timerAt = None,
          workflows = Map.empty
        )
      case timer: TimerDuration =>
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        CombinedBuilder[E | B, S](
          events = Map(eventName -> selfStorage),
          timerAt = Some((Instant.now().plusMillis(timer.duration.toMillis), timerStorage)),
          workflows = Map.empty
        )
      case timer: TimerInstant =>
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        CombinedBuilder[E | B, S](
          events = Map(eventName -> selfStorage),
          timerAt = Some((timer.instant, timerStorage)),
          workflows = Map.empty
        )
      case wf: WorkflowCompletion[?] =>
        val wfStorage = otherStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]]
        CombinedBuilder[E | B, S](
          events = Map(eventName -> selfStorage),
          timerAt = None,
          workflows = Map(wf.targetId -> wfStorage)
        )

/**
 * Timer by duration - wait for a duration to elapse.
 */
case class TimerDuration(duration: FiniteDuration) extends SingleEventQuery[TimeReached]:
  def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[TimeReached, S]): Durable[TimeReached] =
    val wakeAt = Instant.now().plusMillis(duration.toMillis)
    Durable.Suspend(EventQuery.Combined[TimeReached, S](
      events = Map.empty,
      timerAt = Some((wakeAt, storage)),
      workflows = Map.empty
    ))

  def |[B, S <: DurableStorageBackend](other: SingleEventQuery[B])(using
    backend: S,
    selfStorage: DurableStorage[TimeReached, S],
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[TimeReached | B, S] =
    val wakeAt = Instant.now().plusMillis(duration.toMillis)
    other match
      case single: SingleEvent[?] =>
        CombinedBuilder[TimeReached | B, S](
          events = Map(single.eventName -> otherStorage),
          timerAt = Some((wakeAt, selfStorage)),
          workflows = Map.empty
        )
      case timer: TimerDuration =>
        // Keep earliest timer
        val otherWakeAt = Instant.now().plusMillis(timer.duration.toMillis)
        val earliest = if wakeAt.isBefore(otherWakeAt) then (wakeAt, selfStorage) else (otherWakeAt, selfStorage)
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some(earliest),
          workflows = Map.empty
        )
      case timer: TimerInstant =>
        val earliest = if wakeAt.isBefore(timer.instant) then (wakeAt, selfStorage) else (timer.instant, selfStorage)
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some(earliest),
          workflows = Map.empty
        )
      case wf: WorkflowCompletion[?] =>
        val wfStorage = otherStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]]
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some((wakeAt, selfStorage)),
          workflows = Map(wf.targetId -> wfStorage)
        )

/**
 * Timer by instant - wait until a specific instant.
 */
case class TimerInstant(instant: Instant) extends SingleEventQuery[TimeReached]:
  def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[TimeReached, S]): Durable[TimeReached] =
    Durable.Suspend(EventQuery.Combined[TimeReached, S](
      events = Map.empty,
      timerAt = Some((instant, storage)),
      workflows = Map.empty
    ))

  def |[B, S <: DurableStorageBackend](other: SingleEventQuery[B])(using
    backend: S,
    selfStorage: DurableStorage[TimeReached, S],
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[TimeReached | B, S] =
    other match
      case single: SingleEvent[?] =>
        CombinedBuilder[TimeReached | B, S](
          events = Map(single.eventName -> otherStorage),
          timerAt = Some((instant, selfStorage)),
          workflows = Map.empty
        )
      case timer: TimerDuration =>
        val otherWakeAt = Instant.now().plusMillis(timer.duration.toMillis)
        val earliest = if instant.isBefore(otherWakeAt) then (instant, selfStorage) else (otherWakeAt, selfStorage)
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some(earliest),
          workflows = Map.empty
        )
      case timer: TimerInstant =>
        val earliest = if instant.isBefore(timer.instant) then (instant, selfStorage) else (timer.instant, selfStorage)
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some(earliest),
          workflows = Map.empty
        )
      case wf: WorkflowCompletion[?] =>
        val wfStorage = otherStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]]
        CombinedBuilder[TimeReached | B, S](
          events = Map.empty,
          timerAt = Some((instant, selfStorage)),
          workflows = Map(wf.targetId -> wfStorage)
        )

/**
 * Workflow completion - wait for a workflow to complete.
 */
case class WorkflowCompletion[R](targetId: WorkflowId) extends SingleEventQuery[WorkflowResult[R]]:
  def receive[S <: DurableStorageBackend](using backend: S, storage: DurableStorage[WorkflowResult[R], S]): Durable[WorkflowResult[R]] =
    Durable.Suspend(EventQuery.Combined[WorkflowResult[R], S](
      events = Map.empty,
      timerAt = None,
      workflows = Map(targetId -> storage.asInstanceOf[DurableStorage[WorkflowResult[?], S]])
    ))

  def |[B, S <: DurableStorageBackend](other: SingleEventQuery[B])(using
    backend: S,
    selfStorage: DurableStorage[WorkflowResult[R], S],
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[WorkflowResult[R] | B, S] =
    val selfEntry = (targetId, selfStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]])
    other match
      case single: SingleEvent[?] =>
        CombinedBuilder[WorkflowResult[R] | B, S](
          events = Map(single.eventName -> otherStorage),
          timerAt = None,
          workflows = Map(targetId -> selfStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]])
        )
      case timer: TimerDuration =>
        val wakeAt = Instant.now().plusMillis(timer.duration.toMillis)
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        CombinedBuilder[WorkflowResult[R] | B, S](
          events = Map.empty,
          timerAt = Some((wakeAt, timerStorage)),
          workflows = Map(targetId -> selfStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]])
        )
      case timer: TimerInstant =>
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        CombinedBuilder[WorkflowResult[R] | B, S](
          events = Map.empty,
          timerAt = Some((timer.instant, timerStorage)),
          workflows = Map(targetId -> selfStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]])
        )
      case wf: WorkflowCompletion[?] =>
        val wfStorage = otherStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]]
        CombinedBuilder[WorkflowResult[R] | B, S](
          events = Map.empty,
          timerAt = None,
          workflows = Map(targetId -> selfStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]], wf.targetId -> wfStorage)
        )

/**
 * Builder for combined queries - accumulates conditions with storage.
 * Created by | operator, provides .receive to create Durable.
 */
case class CombinedBuilder[A, S <: DurableStorageBackend](
  events: Map[String, DurableStorage[?, S]],
  timerAt: Option[(Instant, DurableStorage[TimeReached, S])],
  workflows: Map[WorkflowId, DurableStorage[WorkflowResult[?], S]]
):
  /** Add another condition */
  def |[B](other: SingleEventQuery[B])(using
    backend: S,
    otherStorage: DurableStorage[B, S]
  ): CombinedBuilder[A | B, S] =
    other match
      case single: SingleEvent[?] =>
        CombinedBuilder[A | B, S](
          events = events + (single.eventName -> otherStorage),
          timerAt = timerAt,
          workflows = workflows
        )
      case timer: TimerDuration =>
        val wakeAt = Instant.now().plusMillis(timer.duration.toMillis)
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        val newTimer = timerAt match
          case Some((existing, _)) if existing.isBefore(wakeAt) => timerAt
          case _ => Some((wakeAt, timerStorage))
        CombinedBuilder[A | B, S](events, newTimer, workflows)
      case timer: TimerInstant =>
        val timerStorage = otherStorage.asInstanceOf[DurableStorage[TimeReached, S]]
        val newTimer = timerAt match
          case Some((existing, _)) if existing.isBefore(timer.instant) => timerAt
          case _ => Some((timer.instant, timerStorage))
        CombinedBuilder[A | B, S](events, newTimer, workflows)
      case wf: WorkflowCompletion[?] =>
        val wfStorage = otherStorage.asInstanceOf[DurableStorage[WorkflowResult[?], S]]
        CombinedBuilder[A | B, S](events, timerAt, workflows + (wf.targetId -> wfStorage))

  /** Create Durable that suspends until any condition is met */
  def receive(using backend: S): Durable[A] =
    Durable.Suspend(EventQuery.Combined[A, S](events, timerAt, workflows))

/**
 * Query builder for timer-based waiting.
 *
 * Usage:
 * {{{
 * // Wait for a duration
 * val timeout = await(TimeReached.after(30.seconds).receive)
 *
 * // Wait until a specific instant
 * val deadline = await(TimeReached.at(futureInstant).receive)
 *
 * // Combined with events
 * val result = await((Event[MyEvent] | TimeReached.after(1.minute)).receive)
 * }}}
 */
object TimeReached:
  /** Create a query that waits for the specified duration */
  def after(duration: FiniteDuration): TimerDuration =
    TimerDuration(duration)

  /** Create a query that waits until the specified instant */
  def at(instant: Instant): TimerInstant =
    TimerInstant(instant)

/**
 * Query builder for workflow completion waiting.
 *
 * Usage:
 * {{{
 * val result = await(WorkflowDone[MyResult](childWorkflowId).receive)
 * result match
 *   case WorkflowResult.Completed(_, value) => handleSuccess(value)
 *   case WorkflowResult.Failed(_, error) => handleFailure(error)
 * }}}
 */
object WorkflowDone:
  /** Create a query that waits for the specified workflow to complete */
  def apply[R](workflowId: WorkflowId): WorkflowCompletion[R] =
    WorkflowCompletion(workflowId)
