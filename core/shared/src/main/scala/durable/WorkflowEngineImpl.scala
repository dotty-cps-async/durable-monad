package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import java.time.{Instant, Duration}
import durable.runtime.Scheduler

/**
 * Implementation of WorkflowEngine.
 *
 * State mutations are sequenced through Future chaining.
 * Uses platform-specific Scheduler for timer scheduling.
 *
 * @param storage The storage backend
 * @param instantStorage Storage for Instant values (needed for timer support)
 * @param config Engine configuration
 * @param scheduler Platform scheduler for timers
 */
class WorkflowEngineImpl[S <: DurableStorageBackend](
  val storage: S,
  instantStorage: DurableStorage[Instant, S],
  config: WorkflowEngineConfig,
  scheduler: Scheduler
)(using ec: ExecutionContext) extends WorkflowEngine[S]:

  private val state = WorkflowEngineState()

  // Start a new workflow
  def start[Args <: Tuple, R](
    function: DurableFunction[Args, R, S],
    args: Args,
    workflowId: Option[WorkflowId] = None
  )(using
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Future[WorkflowId] =
    val id = workflowId.getOrElse(WorkflowId(java.util.UUID.randomUUID().toString))
    val metadata = WorkflowMetadata(
      functionName = function.functionName,
      argCount = argsStorage.size,
      activityIndex = argsStorage.size  // activities start after args
    )

    for
      // Store args at indices 0..argCount-1
      _ <- argsStorage.storeAll(storage, id, 0, args)
      // Save workflow metadata
      _ <- storage.saveWorkflowMetadata(id, metadata, WorkflowStatus.Running)
      // Create workflow record
      now = Instant.now()
      record = WorkflowRecord(id, metadata, WorkflowStatus.Running, None, None, now, now)
      _ = state.putActive(id, record)
      // Create and run workflow
      workflow = function.apply(args)(using storage, argsStorage, resultStorage)
      // For fresh run: activityOffset = argCount, resumeFromIndex = argCount (nothing to replay)
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
    val runnerFuture = WorkflowRunner.run(workflow, ctx)
    state.putRunner(workflowId, runnerFuture.asInstanceOf[Future[WorkflowResult[?]]])

    runnerFuture.flatMap {
      case WorkflowResult.Completed(value) =>
        handleCompleted(workflowId, value, resultStorage)

      case WorkflowResult.Suspended(snapshot, condition) =>
        handleSuspended(workflowId, snapshot, condition)

      case WorkflowResult.Failed(error) =>
        handleFailed(workflowId, error)

      case ca: WorkflowResult.ContinueAs[A] =>
        handleContinueAs(workflowId, ca, resultStorage)
    }.recover { case e =>
      // Unexpected error in runner
      handleFailed(workflowId, e)
    }.map(_ => ())

  private def handleCompleted[A](workflowId: WorkflowId, value: A, resultStorage: DurableStorage[A, S]): Future[Unit] =
    state.removeRunner(workflowId)
    state.removeActive(workflowId)
    for
      _ <- resultStorage.storeResult(storage, workflowId, value)
      _ <- storage.updateWorkflowStatus(workflowId, WorkflowStatus.Succeeded)
    yield ()

  private def handleSuspended(
    workflowId: WorkflowId,
    snapshot: DurableSnapshot,
    condition: WaitCondition[?, ?]
  ): Future[Unit] =
    state.removeRunner(workflowId)
    // Update both status and activityIndex when suspending
    state.updateActive(workflowId, record => record.copy(
      metadata = record.metadata.copy(activityIndex = snapshot.activityIndex),
      status = WorkflowStatus.Suspended,
      waitCondition = Some(condition),
      updatedAt = Instant.now()
    ))
    // Persist the updated activityIndex
    state.getActive(workflowId) match
      case Some(record) =>
        storage.saveWorkflowMetadata(workflowId, record.metadata, WorkflowStatus.Suspended).flatMap { _ =>
          storage.updateWorkflowStatusAndCondition(workflowId, WorkflowStatus.Suspended, Some(condition)).flatMap { _ =>
            registerWaiter(workflowId, condition, snapshot.activityIndex)
          }
        }
      case None =>
        Future.failed(new RuntimeException(s"Workflow $workflowId not found in active state"))

  private def handleFailed(workflowId: WorkflowId, error: Throwable): Future[Unit] =
    state.removeRunner(workflowId)
    state.removeActive(workflowId)
    storage.updateWorkflowStatus(workflowId, WorkflowStatus.Failed)

  private def handleContinueAs[A](workflowId: WorkflowId, ca: WorkflowResult.ContinueAs[A], resultStorage: DurableStorage[A, S]): Future[Unit] =
    for
      _ <- storage.clear(workflowId)
      _ <- ca.storeArgs(storage, workflowId, ec)
      _ <- storage.saveWorkflowMetadata(workflowId, ca.metadata, WorkflowStatus.Running)
      _ = state.updateActive(workflowId, _.copy(
        metadata = ca.metadata,
        status = WorkflowStatus.Running,
        waitCondition = None,
        updatedAt = Instant.now()
      ))
      // ContinueAs: storage cleared, new args stored, start fresh
      _ <- runWorkflow(workflowId, ca.workflow(), ca.metadata.activityIndex, ca.metadata.activityIndex, resultStorage)
    yield ()

  // Register waiter for a condition
  private def registerWaiter(
    workflowId: WorkflowId,
    condition: WaitCondition[?, ?],
    activityIndex: Int
  ): Future[Unit] =
    condition match
      case WaitCondition.Timer(wakeAt, _) =>
        scheduleTimer(workflowId, wakeAt, activityIndex)
        Future.successful(())

      case WaitCondition.Event(eventName, _) =>
        // Workflow waits for event - will be delivered via sendEvent
        Future.successful(())

      case WaitCondition.ChildWorkflow(childId, _, _) =>
        // Child completion handled when child workflow completes
        Future.successful(())

  // Schedule a timer to wake the workflow
  private def scheduleTimer(workflowId: WorkflowId, wakeAt: Instant, activityIndex: Int): Unit =
    val now = Instant.now()
    val delay = Duration.between(now, wakeAt)
    val delayMillis = if delay.isNegative then 0L else delay.toMillis

    val handle = new CancellableTimerHandle

    state.putTimer(workflowId, handle)

    scheduler.schedule(scala.concurrent.duration.Duration(delayMillis, "ms")) {
      if !handle.isCancelled then
        resumeFromTimer(workflowId, wakeAt, activityIndex)
      else
        Future.successful(())
    }

  // Resume workflow after timer fires
  private def resumeFromTimer(workflowId: WorkflowId, wakeAt: Instant, activityIndex: Int): Future[Unit] =
    state.removeTimer(workflowId)
    state.getActive(workflowId) match
      case Some(record) if record.status == WorkflowStatus.Suspended =>
        val wakeTime = Instant.now()
        // Store wake time using typed storage
        instantStorage.storeStep(storage, workflowId, activityIndex, wakeTime).flatMap { _ =>
          // Update state and mark for resume
          state.updateActive(workflowId, _.copy(
            metadata = record.metadata.copy(activityIndex = activityIndex + 1),
            status = WorkflowStatus.Running,
            waitCondition = None,
            updatedAt = Instant.now()
          ))
          storage.updateWorkflowStatus(workflowId, WorkflowStatus.Running).flatMap { _ =>
            // Recreate and resume workflow
            recreateAndResume(workflowId, record, activityIndex + 1)
          }
        }
      case _ =>
        Future.successful(())

  // Recreate workflow from function registry and resume
  private def recreateAndResume(
    workflowId: WorkflowId,
    record: WorkflowRecord,
    resumeFromIndex: Int
  ): Future[Unit] =
    DurableFunctionRegistry.global.lookup(record.metadata.functionName) match
      case Some(funcRecord) =>
        // Get typed storage instances from registry record
        val argsStorage = funcRecord.argsStorageTyped[Tuple, S]
        val resultStorage = funcRecord.resultStorageTyped[Any, S]
        val function = funcRecord.functionTyped[Tuple, Any, S]

        // Recreate workflow from stored args
        function.recreateFromStorage(workflowId, storage)(using argsStorage, resultStorage, ec).flatMap {
          case Some(workflow) =>
            // Run from resume point
            runWorkflow(workflowId, workflow, resumeFromIndex, record.metadata.argCount, resultStorage)
          case None =>
            Future.failed(new RuntimeException(
              s"Failed to load args for workflow $workflowId (function: ${record.metadata.functionName})"
            ))
        }
      case None =>
        Future.failed(new RuntimeException(
          s"Function not registered: ${record.metadata.functionName}"
        ))

  // Send an event to waiting workflows
  def sendEvent[E](event: E)(using
    eventName: DurableEventName[E],
    eventStorage: DurableStorage[E, S]
  ): Future[Unit] =
    val name = eventName.name
    val eventId = EventId.generate()
    val timestamp = Instant.now()

    // Find workflows waiting for this event
    val waitingWorkflows = state.activeWorkflows.filter { record =>
      record.status == WorkflowStatus.Suspended &&
      record.waitCondition.exists {
        case WaitCondition.Event(n, _) => n == name
        case _ => false
      }
    }.toSeq

    if waitingWorkflows.isEmpty then
      // No workflow waiting - store as pending event
      storage.savePendingEvent(name, eventId, event, timestamp)
    else
      // Deliver to first waiting workflow
      val target = waitingWorkflows.head
      val activityIndex = target.metadata.activityIndex

      // Store event using typed storage
      eventStorage.storeStep(storage, target.id, activityIndex, event).flatMap { _ =>
        // Update state
        state.updateActive(target.id, _.copy(
          metadata = target.metadata.copy(activityIndex = activityIndex + 1),
          status = WorkflowStatus.Running,
          waitCondition = None,
          updatedAt = Instant.now()
        ))
        storage.updateWorkflowStatus(target.id, WorkflowStatus.Running).flatMap { _ =>
          recreateAndResume(target.id, target, activityIndex + 1)
        }
      }

  // Query workflow status
  def queryStatus(workflowId: WorkflowId): Future[Option[WorkflowStatus]] =
    state.getActive(workflowId) match
      case Some(record) => Future.successful(Some(record.status))
      case None =>
        storage.loadWorkflowMetadata(workflowId).map(_.map(_._2))

  // Query workflow result
  def queryResult[A](workflowId: WorkflowId)(using resultStorage: DurableStorage[A, S]): Future[Option[A]] =
    queryStatus(workflowId).flatMap {
      case Some(WorkflowStatus.Succeeded) =>
        resultStorage.retrieveResult(storage, workflowId)
      case _ =>
        Future.successful(None)
    }

  // Cancel a workflow
  def cancel(workflowId: WorkflowId): Future[Boolean] =
    state.getActive(workflowId) match
      case Some(record) if record.status == WorkflowStatus.Running || record.status == WorkflowStatus.Suspended =>
        state.removeTimer(workflowId).foreach(_.cancel())
        state.removeActive(workflowId)
        state.removeRunner(workflowId)
        storage.updateWorkflowStatus(workflowId, WorkflowStatus.Cancelled).map(_ => true)
      case _ =>
        Future.successful(false)

  // Recover workflows on startup
  def recover(): Future[RecoveryReport] =
    storage.listActiveWorkflows().flatMap { workflows =>
      val (suspended, running) = workflows.partition(_.status == WorkflowStatus.Suspended)

      // Cache all active workflows in memory
      workflows.foreach(r => state.putActive(r.id, r))

      // Re-register waiters for suspended workflows
      val suspendedRecovery = Future.traverse(suspended) { record =>
        record.waitCondition match
          case Some(condition) =>
            registerWaiter(record.id, condition, record.metadata.activityIndex)
          case None =>
            Future.successful(())
      }

      // Resume running workflows (were interrupted)
      val runningRecovery = Future.traverse(running) { record =>
        recreateAndResume(record.id, record, record.metadata.activityIndex)
          .recover { case _ => () }
      }

      for
        _ <- suspendedRecovery
        _ <- runningRecovery
      yield RecoveryReport(
        activeWorkflows = workflows.size,
        resumedSuspended = suspended.size,
        resumedRunning = running.size
      )
    }

  // Shutdown
  def shutdown(): Future[Unit] =
    state.activeWorkflows.foreach { record =>
      state.removeTimer(record.id).foreach(_.cancel())
    }
    Future.successful(())

/**
 * Cancellable timer handle implementation.
 */
private class CancellableTimerHandle extends TimerHandle:
  @volatile private var _cancelled = false
  def cancel(): Unit = _cancelled = true
  def isCancelled: Boolean = _cancelled
