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

    // Ensure workflow is loaded before sending event (lazy loading)
    val ensureLoaded: Future[Unit] =
      if stateCoordinator.getActive(targetWorkflowId).isEmpty then
        stateCoordinator.submit(CoordinatorOp.EnsureLoaded(targetWorkflowId)).map(_ => ())
      else
        Future.successful(())

    for
      _ <- ensureLoaded
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

    // Query storage for ALL waiting workflows (lazy loading)
    val loadWaitingWorkflows: Future[Unit] =
      for
        storageWaiting <- storage.listWorkflowsWaitingForEvent(name)
        // Load any workflows not already in memory
        notInMemory = storageWaiting.filter(w => stateCoordinator.getActive(w.id).isEmpty)
        _ <- stateCoordinator.recoverWorkflows(notInMemory)
      yield ()

    for
      _ <- hooks.yieldPoint("sendEventBroadcast.beforeSubmit")
      // Load waiting workflows from storage
      _ <- loadWaitingWorkflows
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

  // Recover workflows on startup (lazy loading - only loads what's needed)
  def recover(): Future[RecoveryReport] =
    for
      // 1. Load RUNNING workflows eagerly (were interrupted by crash)
      running <- storage.listWorkflowsByStatus(WorkflowStatus.Running)
      fixedRunning <- Future.traverse(running)(fixPartialDelivery)
      _ <- stateCoordinator.recoverWorkflows(fixedRunning)
      _ <- Future.traverse(fixedRunning) { record =>
        recreateAndResumeInternal(record.id, record, record.metadata.activityIndex)
          .recover { case _ => () }
      }

      // 2. Load suspended workflows with timers within horizon
      timerHorizonJava = java.time.Duration.ofMillis(config.timerHorizon.toMillis)
      timerDeadline = Instant.now().plus(timerHorizonJava)
      nearTimers <- storage.listWorkflowsWithTimerBefore(timerDeadline)
      fixedTimers <- Future.traverse(nearTimers)(fixPartialDelivery)
      // Filter out any that are now Running (after fix)
      suspendedTimers = fixedTimers.filter(_.status == WorkflowStatus.Suspended)
      _ <- stateCoordinator.recoverWorkflows(suspendedTimers)
      _ <- Future.traverse(suspendedTimers) { record =>
        record.waitingForTimer match
          case Some(wakeAt) =>
            scheduleTimer(record.id, wakeAt, record.metadata.activityIndex)
          case None =>
            Future.successful(())
      }

      // 3. Load workflows waiting for pending broadcast events
      // Note: We don't deliver here - pending events will be checked when
      // workflows resume via SuspendAndCheckPending
      pendingEvents <- storage.listAllPendingBroadcastEvents()
      pendingEventNames = pendingEvents.map(_._1).distinct
      workflowsWaitingForEvents <- Future.traverse(pendingEventNames) { eventName =>
        storage.listWorkflowsWaitingForEvent(eventName)
      }.map(_.flatten.distinctBy(_.id))
      // Load these workflows into memory so they can receive events
      _ <- stateCoordinator.recoverWorkflows(workflowsWaitingForEvents)

      // 4. Start periodic sweeps (timers + cache eviction)
      _ = startTimerSweep()
      _ = startCacheEviction()
    yield RecoveryReport(
      runningResumed = fixedRunning.size,
      nearTimersLoaded = suspendedTimers.size,
      pendingEventsDelivered = pendingEvents.size,  // Events available, not necessarily delivered yet
      totalLoaded = fixedRunning.size + suspendedTimers.size + workflowsWaitingForEvents.size
    )

  // Fix partially-completed event delivery
  private def fixPartialDelivery(record: WorkflowRecord): Future[WorkflowRecord] =
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

  // Periodic timer sweep - loads upcoming timers
  private def startTimerSweep(): Unit =
    val sweepIntervalMs = config.timerSweepInterval.toMillis
    scheduler.schedule(scala.concurrent.duration.Duration(sweepIntervalMs, "ms")) {
      runTimerSweep().flatMap { _ =>
        // Reschedule
        Future.successful(startTimerSweep())
      }
    }

  private def runTimerSweep(): Future[Unit] =
    val timerHorizonJava = java.time.Duration.ofMillis(config.timerHorizon.toMillis)
    val deadline = Instant.now().plus(timerHorizonJava)
    for
      workflows <- storage.listWorkflowsWithTimerBefore(deadline)
      // Filter out workflows already in memory
      unscheduled = workflows.filter(w => stateCoordinator.getActive(w.id).isEmpty)
      _ <- stateCoordinator.recoverWorkflows(unscheduled)
      _ <- Future.traverse(unscheduled) { w =>
        w.waitingForTimer match
          case Some(wakeAt) =>
            scheduleTimer(w.id, wakeAt, w.metadata.activityIndex)
          case None =>
            Future.successful(())
      }
    yield ()

  // Periodic cache eviction
  private def startCacheEviction(): Unit =
    val evictionIntervalMs = config.cacheEvictionInterval.toMillis
    scheduler.schedule(scala.concurrent.duration.Duration(evictionIntervalMs, "ms")) {
      runCacheEviction().flatMap { _ =>
        // Reschedule
        Future.successful(startCacheEviction())
      }
    }

  private def runCacheEviction(): Future[Unit] =
    val ttlJava = java.time.Duration.ofMillis(config.cacheTtl.toMillis)
    val threshold = Instant.now().minus(ttlJava)
    // Let coordinator handle the filtering since it has access to activeMap
    stateCoordinator.submit(CoordinatorOp.EvictByTtl(threshold)).map(_ => ())

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
