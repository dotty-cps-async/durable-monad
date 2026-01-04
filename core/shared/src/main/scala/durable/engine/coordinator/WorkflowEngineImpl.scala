package durable.engine.coordinator

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import java.time.{Instant, Duration}

import durable.*
import durable.engine.{WorkflowStateCoordinator, TestHooks, TimerHandle, CoordinatorOp, SuspendResult, SendResult, WorkflowSessionRunner, WorkflowSessionResult, WorkflowMetadata, WorkflowRecord}
import durable.runtime.{Scheduler, DurableFunctionRegistry}
import com.github.rssh.appcontext.*

/**
 * WorkflowEngine implementation using WorkflowStateCoordinator.
 *
 * Uses the coordinator's submit method with CoordinatorOp to ensure
 * atomic check-then-act sequences and prevent race conditions.
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

  // Future-based runner (will be extended for dynamic runner switching later)
  private val futureRunner = WorkflowSessionRunner.forFuture

  // Run workflow and handle result
  private def runWorkflow[A](
    workflowId: WorkflowId,
    workflow: Durable[A],
    resumeFromIndex: Int,
    activityOffset: Int,
    resultStorage: DurableStorage[A, S]
  ): Future[Unit] =
    val ctx = WorkflowSessionRunner.RunContext(workflowId, storage, config.appContextCache, config.configSource, resumeFromIndex, activityOffset, config.runConfig)
    val runnerFuture = futureRunner.run(workflow, ctx)

    // Wrap for coordinator registration (ignoring Left case for now)
    val resultFuture = runnerFuture.flatMap {
      case Right(result) => Future.successful(result)
      case Left(needsBigger) =>
        // TODO: Dynamic runner switching - switch to IORunner when available
        Future.failed(new RuntimeException(
          s"Workflow requires effect ${needsBigger.activityTag} which is not supported by FutureRunner. " +
          "Add durable-ce3 dependency for IO support."
        ))
    }
    stateCoordinator.registerRunner(workflowId, resultFuture.asInstanceOf[Future[WorkflowSessionResult[?]]])

    resultFuture.flatMap {
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
    snapshot: WorkflowSessionRunner.DurableSnapshot,
    condition: EventQuery.Combined[?, ?]
  ): Future[Unit] =
    val activityIndex = snapshot.activityIndex
    val eventStorages = condition.events.view.mapValues(_.asInstanceOf[DurableStorage[?, ?]]).toMap

    for
      _ <- hooks.yieldPoint("handleSuspended.beforeSubmit")
      result <- stateCoordinator.submit(CoordinatorOp.SuspendAndCheckPending(
        workflowId, activityIndex, condition, eventStorages
      ))
      _ <- hooks.yieldPoint("handleSuspended.afterSubmit")
      _ <- result match
        case SuspendResult.Delivered(record, _) =>
          // Pending event was delivered - resume workflow
          recreateAndResumeInternal(workflowId, record, activityIndex + 1)
        case SuspendResult.Suspended =>
          // No pending event - register timer if needed
          registerTimer(workflowId, condition, activityIndex)
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
    val ctx = WorkflowSessionRunner.RunContext(workflowId, storage, config.appContextCache, config.configSource, resumeFromIndex, activityOffset, config.runConfig)
    val runnerFuture = futureRunner.run(workflow, ctx)

    // Wrap for coordinator registration (ignoring Left case for now)
    val resultFuture = runnerFuture.flatMap {
      case Right(result) => Future.successful(result)
      case Left(needsBigger) =>
        // TODO: Dynamic runner switching - switch to IORunner when available
        Future.failed(new RuntimeException(
          s"Workflow requires effect ${needsBigger.activityTag} which is not supported by FutureRunner. " +
          "Add durable-ce3 dependency for IO support."
        ))
    }
    stateCoordinator.registerRunner(workflowId, resultFuture.asInstanceOf[Future[WorkflowSessionResult[?]]])

    resultFuture.flatMap {
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
    snapshot: WorkflowSessionRunner.DurableSnapshot,
    condition: EventQuery.Combined[?, ?]
  ): Future[Unit] =
    val activityIndex = snapshot.activityIndex
    val eventStorages = condition.events.view.mapValues(_.asInstanceOf[DurableStorage[?, ?]]).toMap

    for
      result <- stateCoordinator.submit(CoordinatorOp.SuspendAndCheckPending(
        workflowId, activityIndex, condition, eventStorages
      ))
      _ <- result match
        case SuspendResult.Delivered(record, _) =>
          recreateAndResumeInternal(workflowId, record, activityIndex + 1)
        case SuspendResult.Suspended =>
          registerTimer(workflowId, condition, activityIndex)
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
      recordOpt <- stateCoordinator.submit(CoordinatorOp.HandleTimerFired(
        workflowId, wakeAt, activityIndex, timeReachedStorage.asInstanceOf[DurableStorage[TimeReached, ?]]
      ))
      _ <- recordOpt match
        case Some(record) =>
          recreateAndResumeInternal(workflowId, record, activityIndex + 1)
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

    for
      result <- stateCoordinator.submit(CoordinatorOp.SendTargetedEvent(
        targetWorkflowId, name, event, eventId, timestamp, policy,
        eventStorage.asInstanceOf[DurableStorage[?, ?]]
      ))
      _ <- result match
        case SendResult.Delivered(targetId) =>
          stateCoordinator.getActive(targetId) match
            case Some(record) =>
              recreateAndResumeInternal(targetId, record, record.metadata.activityIndex)
            case None =>
              Future.successful(())
        case SendResult.Queued(_) =>
          Future.successful(())
        case SendResult.TargetTerminated(status) =>
          Future.failed(WorkflowTerminatedException(targetWorkflowId, status))
        case SendResult.TargetNotFound =>
          Future.failed(WorkflowNotFoundException(targetWorkflowId))
    yield ()

  // Broadcast an event to workflows waiting for this event type
  def sendEventBroadcast[E](event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Unit] =
    val name = eventName.name
    val eventId = EventId.generate()
    val timestamp = Instant.now()

    for
      _ <- hooks.yieldPoint("sendEventBroadcast.beforeSubmit")
      result <- stateCoordinator.submit(CoordinatorOp.SendBroadcastEvent(
        name, event, eventId, timestamp, eventStorage.asInstanceOf[DurableStorage[?, ?]]
      ))
      _ <- hooks.yieldPoint("sendEventBroadcast.afterSubmit")
      _ <- result match
        case SendResult.Delivered(targetId) =>
          stateCoordinator.getActive(targetId) match
            case Some(record) =>
              recreateAndResumeInternal(targetId, record, record.metadata.activityIndex)
            case None =>
              Future.successful(())
        case SendResult.Queued(_) =>
          Future.successful(())
        case _ =>
          Future.successful(())
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

      // Fix partially-completed deliveries
      fixed <- Future.traverse(workflows) { record =>
        if record.status == WorkflowStatus.Suspended then
          storage.retrieveWinningCondition(record.id, record.metadata.activityIndex).flatMap {
            case Some(_) =>
              // Event was stored but status not updated - fix it
              storage.updateWorkflowStatus(record.id, WorkflowStatus.Running).map { _ =>
                record.copy(
                  status = WorkflowStatus.Running,
                  metadata = record.metadata.copy(activityIndex = record.metadata.activityIndex + 1)
                )
              }
            case None =>
              Future.successful(record)
          }
        else
          Future.successful(record)
      }

      (suspended, running) = fixed.partition(_.status == WorkflowStatus.Suspended)
      _ <- stateCoordinator.recoverWorkflows(fixed)
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
      activeWorkflows = fixed.size,
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
