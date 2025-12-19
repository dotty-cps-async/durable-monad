package durable.engine.coordinator

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import java.time.{Instant, Duration}

import durable.*
import durable.engine.{WorkflowStateCoordinator, TestHooks, TimerHandle}
import durable.runtime.Scheduler

/**
 * WorkflowEngine implementation using WorkflowStateCoordinator.
 * 
 * @param storage The storage backend
 * @param stateCoordinator Manages workflow state with serialized access
 * @param timeReachedStorage Storage for TimeReached values (needed for timer support)
 * @param config Engine configuration
 * @param scheduler Platform scheduler for timers
 * @param hooks Test hooks for instrumentation (no-op by default)
 */
class WorkflowEngineImpl[S <: DurableStorageBackend](
  val storage: S,
  stateCoordinator: WorkflowStateCoordinator,
  timeReachedStorage: DurableStorage[TimeReached, S],
  config: WorkflowEngineConfig,
  scheduler: Scheduler,
  hooks: TestHooks = TestHooks.NoOp
)(using ec: ExecutionContext) extends WorkflowEngine[S]:

  // Start a new workflow
  def start[Args <: Tuple, R](
    function: DurableFunction[Args, R, S],
    args: Args,
    workflowId: Option[WorkflowId] = None
  ): Future[WorkflowId] =
    val argsStorage = function.argsStorage
    val resultStorage = function.resultStorage
    val id = workflowId.getOrElse(WorkflowId(java.util.UUID.randomUUID().toString))
    val metadata = WorkflowMetadata(
      functionName = function.functionName.value,
      argCount = argsStorage.size,
      activityIndex = argsStorage.size
    )

    for
      _ <- argsStorage.storeAll(storage, id, 0, args)
      _ <- storage.saveWorkflowMetadata(id, metadata, WorkflowStatus.Running)
      now = Instant.now()
      record = WorkflowRecord(id, metadata, WorkflowStatus.Running, Set.empty, None, Set.empty, None, now, now)
      _ <- stateCoordinator.registerWorkflow(id, record)
      workflow = function.applyTupled(args)(using storage)
      _ <- runWorkflow(id, workflow, metadata.activityIndex, metadata.activityIndex, resultStorage)
    yield id

  // Run workflow and handle result
  private def runWorkflow[A](
    workflowId: WorkflowId,
    workflow: Durable[A],
    resumeFromIndex: Int,
    activityOffset: Int,
    resultStorage: DurableStorage[A, S]
  ): Future[Unit] =
    val ctx = RunContext(workflowId, storage, resumeFromIndex, activityOffset, config.runConfig)
    val runnerFuture = WorkflowSessionRunner.run(workflow, ctx)
    stateCoordinator.registerRunner(workflowId, runnerFuture.asInstanceOf[Future[WorkflowSessionResult[?]]])

    runnerFuture.flatMap {
      case WorkflowSessionResult.Completed(_, value) =>
        handleCompleted(workflowId, value, resultStorage)

      case WorkflowSessionResult.Suspended(snapshot, condition) =>
        handleSuspended(workflowId, snapshot, condition)

      case WorkflowSessionResult.Failed(_, error) =>
        handleFailed(workflowId, error)

      case ca: WorkflowSessionResult.ContinueAs[A] @unchecked =>
        handleContinueAs(workflowId, ca, resultStorage)
    }.recover { case e =>
      handleFailed(workflowId, e)
    }.map(_ => ())

  // Process pending events when workflow terminates
  // Routes each pending event based on its DeadLetterPolicy
  private def processWorkflowPendingEventsOnTermination(workflowId: WorkflowId, terminatedStatus: WorkflowStatus): Future[Unit] =
    val terminatedAt = Instant.now()
    storage.loadAllWorkflowPendingEvents(workflowId).flatMap { pendingEvents =>
      Future.traverse(pendingEvents) { pending =>
        pending.onTargetTerminated match
          case DeadLetterPolicy.Discard =>
            // Just discard - do nothing
            Future.successful(())
          case DeadLetterPolicy.MoveToBroadcast =>
            // Move to broadcast queue
            storage.savePendingEvent(pending.eventName, pending.eventId, pending.value, pending.timestamp)
          case DeadLetterPolicy.MoveToDeadLetter =>
            // Create dead event and save to dead letter queue
            val deadEvent = DeadEvent(
              eventId = pending.eventId,
              eventName = pending.eventName,
              value = pending.value,
              originalTarget = workflowId,
              targetTerminatedAt = terminatedAt,
              targetStatus = terminatedStatus,
              timestamp = pending.timestamp
            )
            storage.saveDeadEvent(pending.eventName, deadEvent)
      }.flatMap { _ =>
        // Clear all pending events for this workflow
        storage.clearWorkflowPendingEvents(workflowId)
      }
    }

  private def handleCompleted[A](workflowId: WorkflowId, value: A, resultStorage: DurableStorage[A, S]): Future[Unit] =
    for
      _ <- stateCoordinator.markFinished(workflowId)
      _ <- resultStorage.storeResult(storage, workflowId, value)
      _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Succeeded)
      _ <- processWorkflowPendingEventsOnTermination(workflowId, WorkflowStatus.Succeeded)
    yield ()

  private def handleSuspended(
    workflowId: WorkflowId,
    snapshot: DurableSnapshot,
    condition: EventQuery.Combined[?, ?]
  ): Future[Unit] =
    val activityIndex = snapshot.activityIndex

    for
      _ <- stateCoordinator.markSuspended(workflowId, activityIndex, condition)
      _ <- hooks.yieldPoint("handleSuspended.afterRegister")
      pendingResult <- checkPendingEventsInternal(workflowId, condition, activityIndex)
      _ <- hooks.yieldPoint("handleSuspended.afterCheckPending")
      _ <- pendingResult match
        case Some(_) =>
          // Pending event was delivered - workflow resumed
          Future.successful(())
        case None =>
          // No pending event - persist suspended state
          stateCoordinator.getActive(workflowId) match
            case Some(record) =>
              for
                _ <- storage.saveWorkflowMetadata(workflowId, record.metadata, WorkflowStatus.Suspended)
                _ <- storage.updateWorkflowStatusAndCondition(
                  workflowId, WorkflowStatus.Suspended,
                  condition.eventNames, condition.timerAt.map(_._1), condition.workflows.keySet
                )
                _ <- registerTimer(workflowId, condition, activityIndex)
              yield ()
            case None =>
              Future.failed(new RuntimeException(s"Workflow $workflowId not found in active state"))
    yield ()

  // Check for pending events (targeted events first, then broadcast events)
  private def checkPendingEventsInternal(
    workflowId: WorkflowId,
    condition: EventQuery.Combined[?, ?],
    activityIndex: Int
  ): Future[Option[Unit]] =
    val eventChecks = condition.events.toSeq.map { case (eventName, eventStorage) =>
      // Check workflow-specific (targeted) events first
      storage.loadWorkflowPendingEvents(workflowId, eventName).flatMap { targetedPending =>
        targetedPending.headOption match
          case Some(pendingEvent) =>
            val typedStorage = eventStorage.asInstanceOf[DurableStorage[?, S]]
            deliverTargetedPendingEventInternal(workflowId, eventName, pendingEvent, typedStorage, activityIndex)
              .map(_ => Some(()))
          case None =>
            // Fall back to broadcast pending events
            storage.loadPendingEvents(eventName).flatMap { broadcastPending =>
              broadcastPending.headOption match
                case Some(pendingEvent) =>
                  val typedStorage = eventStorage.asInstanceOf[DurableStorage[?, S]]
                  deliverBroadcastPendingEventInternal(workflowId, eventName, pendingEvent, typedStorage, activityIndex)
                    .map(_ => Some(()))
                case None =>
                  Future.successful(None)
            }
      }
    }
    Future.sequence(eventChecks).map(_.flatten.headOption)

  // Deliver a targeted pending event (from workflow-specific queue)
  private def deliverTargetedPendingEventInternal(
    workflowId: WorkflowId,
    eventName: String,
    pendingEvent: PendingEvent[?],
    eventStorage: DurableStorage[?, S],
    activityIndex: Int
  ): Future[Unit] =
    for
      _ <- storage.removeWorkflowPendingEvent(workflowId, eventName, pendingEvent.eventId)
      _ <- storage.storeWinningCondition(workflowId, activityIndex, SingleEvent(eventName))
      typedStorage = eventStorage.asInstanceOf[DurableStorage[Any, S]]
      _ <- typedStorage.storeStep(storage, workflowId, activityIndex, pendingEvent.value)
      recordOpt <- stateCoordinator.markResumed(workflowId, activityIndex + 1)
      _ <- recordOpt match
        case Some(record) =>
          for
            _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Running)
            _ <- recreateAndResumeInternal(workflowId, record, activityIndex + 1)
          yield ()
        case None =>
          Future.failed(new RuntimeException(s"Workflow $workflowId not found"))
    yield ()

  // Deliver a broadcast pending event (from shared queue)
  private def deliverBroadcastPendingEventInternal(
    workflowId: WorkflowId,
    eventName: String,
    pendingEvent: PendingEvent[?],
    eventStorage: DurableStorage[?, S],
    activityIndex: Int
  ): Future[Unit] =
    for
      _ <- storage.removePendingEvent(eventName, pendingEvent.eventId)
      _ <- storage.storeWinningCondition(workflowId, activityIndex, SingleEvent(eventName))
      typedStorage = eventStorage.asInstanceOf[DurableStorage[Any, S]]
      _ <- typedStorage.storeStep(storage, workflowId, activityIndex, pendingEvent.value)
      recordOpt <- stateCoordinator.markResumed(workflowId, activityIndex + 1)
      _ <- recordOpt match
        case Some(record) =>
          for
            _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Running)
            _ <- recreateAndResumeInternal(workflowId, record, activityIndex + 1)
          yield ()
        case None =>
          Future.failed(new RuntimeException(s"Workflow $workflowId not found"))
    yield ()

  private def handleFailed(workflowId: WorkflowId, error: Throwable): Future[Unit] =
    for
      _ <- stateCoordinator.markFinished(workflowId)
      _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Failed)
      _ <- processWorkflowPendingEventsOnTermination(workflowId, WorkflowStatus.Failed)
    yield ()

  private def handleContinueAs[A](workflowId: WorkflowId, ca: WorkflowSessionResult.ContinueAs[A], resultStorage: DurableStorage[A, S]): Future[Unit] =
    for
      _ <- storage.clear(workflowId)
      _ <- ca.storeArgs(storage, workflowId, ec)
      _ <- storage.saveWorkflowMetadata(workflowId, ca.metadata, WorkflowStatus.Running)
      _ <- stateCoordinator.updateForContinueAs(workflowId, ca.metadata)
      _ <- runWorkflowInternal(workflowId, ca.workflow(), ca.metadata.activityIndex, ca.metadata.activityIndex, resultStorage)
    yield ()

  // Internal: Run workflow (called after state already updated)
  private def runWorkflowInternal[A](
    workflowId: WorkflowId,
    workflow: Durable[A],
    resumeFromIndex: Int,
    activityOffset: Int,
    resultStorage: DurableStorage[A, S]
  ): Future[Unit] =
    val ctx = RunContext(workflowId, storage, resumeFromIndex, activityOffset, config.runConfig)
    val runnerFuture = WorkflowSessionRunner.run(workflow, ctx)
    stateCoordinator.registerRunner(workflowId, runnerFuture.asInstanceOf[Future[WorkflowSessionResult[?]]])

    runnerFuture.flatMap {
      case WorkflowSessionResult.Completed(_, value) =>
        handleCompletedInternal(workflowId, value, resultStorage)

      case WorkflowSessionResult.Suspended(snapshot, condition) =>
        handleSuspendedInternal(workflowId, snapshot, condition)

      case WorkflowSessionResult.Failed(_, error) =>
        handleFailedInternal(workflowId, error)

      case ca: WorkflowSessionResult.ContinueAs[A] @unchecked =>
        handleContinueAsInternal(workflowId, ca, resultStorage)
    }.recover { case e =>
      handleFailedInternal(workflowId, e)
    }.map(_ => ())

  // Internal handlers (for resumed workflows)
  private def handleCompletedInternal[A](workflowId: WorkflowId, value: A, resultStorage: DurableStorage[A, S]): Future[Unit] =
    for
      _ <- stateCoordinator.markFinished(workflowId)
      _ <- resultStorage.storeResult(storage, workflowId, value)
      _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Succeeded)
      _ <- processWorkflowPendingEventsOnTermination(workflowId, WorkflowStatus.Succeeded)
    yield ()

  private def handleSuspendedInternal(
    workflowId: WorkflowId,
    snapshot: DurableSnapshot,
    condition: EventQuery.Combined[?, ?]
  ): Future[Unit] =
    val activityIndex = snapshot.activityIndex

    for
      _ <- stateCoordinator.markSuspended(workflowId, activityIndex, condition)
      pendingResult <- checkPendingEventsInternal(workflowId, condition, activityIndex)
      _ <- pendingResult match
        case Some(_) =>
          Future.successful(())
        case None =>
          stateCoordinator.getActive(workflowId) match
            case Some(record) =>
              for
                _ <- storage.saveWorkflowMetadata(workflowId, record.metadata, WorkflowStatus.Suspended)
                _ <- storage.updateWorkflowStatusAndCondition(
                  workflowId, WorkflowStatus.Suspended,
                  condition.eventNames, condition.timerAt.map(_._1), condition.workflows.keySet
                )
                _ <- registerTimer(workflowId, condition, activityIndex)
              yield ()
            case None =>
              Future.failed(new RuntimeException(s"Workflow $workflowId not found in active state"))
    yield ()

  private def handleFailedInternal(workflowId: WorkflowId, error: Throwable): Future[Unit] =
    for
      _ <- stateCoordinator.markFinished(workflowId)
      _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Failed)
      _ <- processWorkflowPendingEventsOnTermination(workflowId, WorkflowStatus.Failed)
    yield ()

  private def handleContinueAsInternal[A](workflowId: WorkflowId, ca: WorkflowSessionResult.ContinueAs[A], resultStorage: DurableStorage[A, S]): Future[Unit] =
    for
      _ <- storage.clear(workflowId)
      _ <- ca.storeArgs(storage, workflowId, ec)
      _ <- storage.saveWorkflowMetadata(workflowId, ca.metadata, WorkflowStatus.Running)
      _ <- stateCoordinator.updateForContinueAs(workflowId, ca.metadata)
      _ <- runWorkflowInternal(workflowId, ca.workflow(), ca.metadata.activityIndex, ca.metadata.activityIndex, resultStorage)
    yield ()

  // Register timer for workflow
  private def registerTimer(
    workflowId: WorkflowId,
    condition: EventQuery.Combined[?, ?],
    activityIndex: Int
  ): Future[Unit] =
    condition.timerAt match
      case Some((wakeAt, _)) =>
        scheduleTimer(workflowId, wakeAt, activityIndex)
      case None =>
        Future.successful(())

  // Schedule timer
  private def scheduleTimer(workflowId: WorkflowId, wakeAt: Instant, activityIndex: Int): Future[Unit] =
    val now = Instant.now()
    val delay = Duration.between(now, wakeAt)
    val delayMillis = if delay.isNegative then 0L else delay.toMillis

    val handle = new CancellableTimerHandle

    for
      _ <- stateCoordinator.registerTimer(workflowId, handle)
      _ = scheduler.schedule(scala.concurrent.duration.Duration(delayMillis, "ms")) {
        if !handle.isCancelled then
          resumeFromTimer(workflowId, wakeAt, activityIndex)
        else
          Future.successful(())
      }
    yield ()

  // Resume workflow after timer fires
  private def resumeFromTimer(workflowId: WorkflowId, wakeAt: Instant, activityIndex: Int): Future[Unit] =
    for
      recordOpt <- stateCoordinator.getAndRemoveTimer(workflowId)
      _ <- recordOpt match
        case Some(record) =>
          val firedAt = Instant.now()
          val timeReached = TimeReached(scheduledAt = wakeAt, firedAt = firedAt)
          for
            _ <- storage.storeWinningCondition(workflowId, activityIndex, TimerInstant(wakeAt))
            _ <- timeReachedStorage.storeStep(storage, workflowId, activityIndex, timeReached)
            resumedOpt <- stateCoordinator.markResumed(workflowId, activityIndex + 1)
            _ <- resumedOpt match
              case Some(_) =>
                for
                  _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Running)
                  _ <- recreateAndResumeInternal(workflowId, record, activityIndex + 1)
                yield ()
              case None =>
                Future.successful(())
          yield ()
        case None =>
          Future.successful(())
    yield ()

  // Recreate and resume workflow
  private def recreateAndResumeInternal(
    workflowId: WorkflowId,
    record: WorkflowRecord,
    resumeFromIndex: Int
  ): Future[Unit] =
    DurableFunctionRegistry.global.lookup(record.metadata.functionName) match
      case Some(funcRecord) =>
        val function = funcRecord.functionTyped[Tuple, Any, S]
        val resultStorage = function.resultStorage

        function.recreateFromStorage(workflowId, storage).flatMap {
          case Some(workflow) =>
            runWorkflowInternal(workflowId, workflow, resumeFromIndex, record.metadata.argCount, resultStorage)
          case None =>
            Future.failed(new RuntimeException(
              s"Failed to load args for workflow $workflowId (function: ${record.metadata.functionName})"
            ))
        }
      case None =>
        Future.failed(new RuntimeException(
          s"Function not registered: ${record.metadata.functionName}"
        ))

  // Send an event to a specific workflow by ID
  def sendEventTo[E](targetWorkflowId: WorkflowId, event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S],
    eventConfig: DurableEventConfig[E] = DurableEventConfig.defaultEventConfig[E]
  ): Future[Unit] =
    val name = eventName.name
    val eventId = EventId.generate()
    val timestamp = Instant.now()
    val policy = eventConfig.onTargetTerminated

    // Check active state first
    stateCoordinator.getActive(targetWorkflowId) match
      case Some(record) =>
        // Workflow is active (Running or Suspended)
        if record.status.isTerminal then
          Future.failed(WorkflowTerminatedException(targetWorkflowId, record.status))
        else if record.status == WorkflowStatus.Suspended && record.isWaitingForEvent(name) then
          // Deliver immediately
          val activityIndex = record.metadata.activityIndex
          for
            _ <- storage.storeWinningCondition(targetWorkflowId, activityIndex, SingleEvent(name))
            _ <- eventStorage.storeStep(storage, targetWorkflowId, activityIndex, event)
            resumedOpt <- stateCoordinator.markResumed(targetWorkflowId, activityIndex + 1)
            _ <- resumedOpt match
              case Some(_) =>
                for
                  _ <- storage.updateWorkflowStatus(targetWorkflowId, WorkflowStatus.Running)
                  _ <- recreateAndResumeInternal(targetWorkflowId, record, activityIndex + 1)
                yield ()
              case None =>
                Future.successful(())
          yield ()
        else
          // Running or waiting for different condition - queue for later with policy
          storage.saveWorkflowPendingEvent(targetWorkflowId, name, eventId, event, timestamp, policy)

      case None =>
        // Not in active state - check storage for terminal/non-existent
        storage.loadWorkflowMetadata(targetWorkflowId).flatMap {
          case Some((_, status)) if status.isTerminal =>
            Future.failed(WorkflowTerminatedException(targetWorkflowId, status))
          case Some(_) =>
            // Workflow exists but not in active state (race condition?) - queue anyway
            storage.saveWorkflowPendingEvent(targetWorkflowId, name, eventId, event, timestamp, policy)
          case None =>
            Future.failed(WorkflowNotFoundException(targetWorkflowId))
        }

  // Broadcast an event to workflows waiting for this event type
  def sendEventBroadcast[E](event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Unit] =
    val name = eventName.name
    val eventId = EventId.generate()
    val timestamp = Instant.now()

    for
      _ <- hooks.yieldPoint("sendEventBroadcast.beforeCheck")
      waitingWorkflows <- stateCoordinator.findWaitingForEvent(name)
      _ <- hooks.yieldPoint("sendEventBroadcast.afterCheckWaiting")
      _ <- if waitingWorkflows.isEmpty then
        storage.savePendingEvent(name, eventId, event, timestamp)
      else
        val target = waitingWorkflows.head
        val activityIndex = target.metadata.activityIndex
        for
          _ <- storage.storeWinningCondition(target.id, activityIndex, SingleEvent(name))
          _ <- eventStorage.storeStep(storage, target.id, activityIndex, event)
          resumedOpt <- stateCoordinator.markResumed(target.id, activityIndex + 1)
          _ <- resumedOpt match
            case Some(_) =>
              for
                _ <- storage.updateWorkflowStatus(target.id, WorkflowStatus.Running)
                _ <- recreateAndResumeInternal(target.id, target, activityIndex + 1)
              yield ()
            case None =>
              Future.successful(())
        yield ()
    yield ()

  // Query workflow status (read-only)
  def queryStatus(workflowId: WorkflowId): Future[Option[WorkflowStatus]] =
    stateCoordinator.getActive(workflowId) match
      case Some(record) => Future.successful(Some(record.status))
      case None =>
        storage.loadWorkflowMetadata(workflowId).map(_.map(_._2))

  // Query workflow result (read-only)
  def queryResult[A](workflowId: WorkflowId)(using resultStorage: DurableStorage[A, S]): Future[Option[A]] =
    queryStatus(workflowId).flatMap {
      case Some(WorkflowStatus.Succeeded) =>
        resultStorage.retrieveResult(storage, workflowId)
      case _ =>
        Future.successful(None)
    }

  // Cancel a workflow
  def cancel(workflowId: WorkflowId): Future[Boolean] =
    for
      recordOpt <- stateCoordinator.cancelWorkflow(workflowId)
      result <- recordOpt match
        case Some(_) =>
          for
            _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Cancelled)
            _ <- processWorkflowPendingEventsOnTermination(workflowId, WorkflowStatus.Cancelled)
          yield true
        case None =>
          Future.successful(false)
    yield result

  // Recover workflows on startup
  def recover(): Future[RecoveryReport] =
    for
      workflows <- storage.listActiveWorkflows()
      (suspended, running) = workflows.partition(_.status == WorkflowStatus.Suspended)
      _ <- stateCoordinator.recoverWorkflows(workflows)
      // Re-register timers for suspended workflows
      _ <- Future.traverse(suspended) { record =>
        record.waitingForTimer match
          case Some(wakeAt) =>
            scheduleTimer(record.id, wakeAt, record.metadata.activityIndex)
          case None =>
            Future.successful(())
      }
      // Resume running workflows (were interrupted)
      _ <- Future.traverse(running) { record =>
        recreateAndResumeInternal(record.id, record, record.metadata.activityIndex)
          .recover { case _ => () }
      }
    yield RecoveryReport(
      activeWorkflows = workflows.size,
      resumedSuspended = suspended.size,
      resumedRunning = running.size
    )

  // Shutdown
  def shutdown(): Future[Unit] =
    for
      _ <- stateCoordinator.cancelAllTimers()
      _ <- stateCoordinator.shutdown()
    yield ()

  // Dead letter management

  def queryDeadLetters[E](using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Seq[DeadEvent[E]]] =
    storage.loadDeadEvents(eventName.name).map { events =>
      events.map(_.asInstanceOf[DeadEvent[E]])
    }

  def replayDeadLetter(eventId: EventId): Future[Boolean] =
    storage.loadDeadEventById(eventId).flatMap {
      case Some((eventName, deadEvent)) =>
        for
          _ <- storage.removeDeadEvent(eventName, eventId)
          _ <- storage.savePendingEvent(eventName, eventId, deadEvent.value, deadEvent.timestamp)
        yield true
      case None =>
        Future.successful(false)
    }

  def replayDeadLetterTo(eventId: EventId, targetWorkflowId: WorkflowId): Future[Unit] =
    storage.loadDeadEventById(eventId).flatMap {
      case Some((eventName, deadEvent)) =>
        for
          _ <- storage.removeDeadEvent(eventName, eventId)
          _ <- storage.saveWorkflowPendingEvent(targetWorkflowId, eventName, eventId, deadEvent.value, deadEvent.timestamp)
        yield ()
      case None =>
        Future.failed(new RuntimeException(s"Dead event not found: ${eventId.value}"))
    }

  def removeDeadLetter(eventId: EventId): Future[Boolean] =
    storage.loadDeadEventById(eventId).flatMap {
      case Some((eventName, _)) =>
        storage.removeDeadEvent(eventName, eventId).map(_ => true)
      case None =>
        Future.successful(false)
    }

/**
 * Cancellable timer handle implementation.
 */
private class CancellableTimerHandle extends TimerHandle:
  @volatile private var _cancelled = false
  def cancel(): Unit = _cancelled = true
  def isCancelled: Boolean = _cancelled
