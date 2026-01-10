package durable.engine

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}

import cps.*
import cps.monads.FutureAsyncMonad

import durable.*

/**
 * JS implementation of WorkflowStateCoordinator.
 *
 * JavaScript is single-threaded, so in-memory operations don't need locks.
 * However, storage operations are async and must be properly chained to
 * ensure atomicity - we can't return until storage completes.
 *
 * Uses dotty-cps-async for clean async/await syntax that compiles to
 * proper Future chaining.
 */
class WorkflowStateCoordinatorImpl(
  storage: DurableStorageBackend
)(using ec: ExecutionContext) extends WorkflowStateCoordinator:

  // Internal state - safe because JS is single-threaded
  private val activeMap = mutable.Map[WorkflowId, WorkflowRecord]()
  private val runnersMap = mutable.Map[WorkflowId, Future[WorkflowSessionResult[?]]]()
  private val timersMap = mutable.Map[WorkflowId, TimerHandle]()

  /**
   * Submit operation to coordinator.
   * Returns Future that completes when operation (including storage) is done.
   */
  def submit[R](op: CoordinatorOp[R]): Future[R] =
    executeOp(op)

  /**
   * Submit batch - execute sequentially.
   */
  def submitBatch(ops: Seq[CoordinatorOp[?]]): Future[Seq[?]] = async[Future] {
    val results = mutable.ArrayBuffer[Any]()
    for op <- ops do
      results += await(executeOp(op))
    results.toSeq
  }

  /**
   * Read-only query (eventually consistent).
   */
  def getActive(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id)

  /**
   * Execute operation - returns Future that completes when done.
   */
  private def executeOp[R](op: CoordinatorOp[R]): Future[R] =
    op match
      case CoordinatorOp.RegisterWorkflow(id, record) =>
        activeMap.put(id, record)
        Future.successful(())

      case CoordinatorOp.RegisterRunner(id, runner) =>
        runnersMap.put(id, runner)
        Future.successful(())

      case CoordinatorOp.RegisterTimer(id, handle) =>
        timersMap.put(id, handle)
        Future.successful(())

      case CoordinatorOp.SuspendAndCheckPending(id, activityIndex, condition, eventStorages) =>
        executeSuspendAndCheckPending(id, activityIndex, condition, eventStorages)

      case CoordinatorOp.SendBroadcastEvent(eventName, event, eventId, timestamp, eventStorage) =>
        executeSendBroadcastEvent(eventName, event, eventId, timestamp, eventStorage)

      case CoordinatorOp.SendTargetedEvent(targetId, eventName, event, eventId, timestamp, policy, eventStorage) =>
        executeSendTargetedEvent(targetId, eventName, event, eventId, timestamp, policy, eventStorage)

      case CoordinatorOp.HandleTimerFired(workflowId, wakeAt, activityIndex, timeReachedStorage) =>
        executeHandleTimerFired(workflowId, wakeAt, activityIndex, timeReachedStorage)

      case CoordinatorOp.MarkFinished(id) =>
        runnersMap.remove(id)
        activeMap.remove(id)
        Future.successful(())

      case CoordinatorOp.CancelWorkflow(id) =>
        Future.successful(executeCancelWorkflow(id))

      case CoordinatorOp.UpdateForContinueAs(id, metadata) =>
        activeMap.get(id).foreach { record =>
          activeMap.put(id, record.copy(
            metadata = metadata,
            status = WorkflowStatus.Running,
            updatedAt = Instant.now()
          ).clearWaitConditions)
        }
        Future.successful(())

      case CoordinatorOp.RecoverWorkflows(records) =>
        records.foreach(r => activeMap.put(r.id, r))
        Future.successful(())

      case CoordinatorOp.CancelAllTimers() =>
        val handles = timersMap.values.toSeq
        handles.foreach(_.cancel())
        timersMap.clear()
        Future.successful(handles)

      case CoordinatorOp.Shutdown() =>
        Future.successful(())

      case CoordinatorOp.EnsureLoaded(workflowId) =>
        executeEnsureLoaded(workflowId)

      case CoordinatorOp.EvictFromCache(workflowIds) =>
        Future.successful(executeEvictFromCache(workflowIds))

      case CoordinatorOp.EvictByTtl(notAccessedSince) =>
        Future.successful(executeEvictByTtl(notAccessedSince))

      case CoordinatorOp.TouchWorkflow(workflowId) =>
        executeTouchWorkflow(workflowId)
        Future.successful(())

  private def updateInMemoryAfterDeliver(workflowId: WorkflowId, activityIndex: Int): Unit =
    timersMap.remove(workflowId).foreach(_.cancel())
    activeMap.updateWith(workflowId)(_.map(r => r.copy(
      metadata = r.metadata.copy(activityIndex = activityIndex + 1),
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    ).clearWaitConditions))

  // === Individual operation implementations ===

  private def executeSuspendAndCheckPending(
    id: WorkflowId,
    activityIndex: Int,
    condition: EventQuery.Combined[?, ?],
    eventStorages: Map[String, DurableStorage[?, ?]]
  ): Future[SuspendResult] = async[Future] {
    runnersMap.remove(id)
    val record = activeMap.getOrElse(id, throw new RuntimeException(s"Workflow $id not found"))

    val updated = record.copy(
      metadata = record.metadata.copy(activityIndex = activityIndex),
      status = WorkflowStatus.Suspended,
      waitingForEvents = condition.eventNames,
      waitingForTimer = condition.timerAt.map(_._1),
      waitingForWorkflows = condition.workflows.keySet,
      updatedAt = Instant.now()
    )
    activeMap.put(id, updated)

    val pendingEvent = await(findPendingEvent(id, condition))

    pendingEvent match
      case Some((eventName, pending, isTargeted)) =>
        await(storage.deliverPendingEvent(
          id, activityIndex, SingleEvent(eventName), pending.value,
          eventStorages(eventName).asInstanceOf[DurableStorage[Any, DurableStorageBackend]],
          pending.eventId, eventName, isTargeted
        ))
        updateInMemoryAfterDeliver(id, activityIndex)
        SuspendResult.Delivered(updated, eventName)

      case None =>
        await(storage.suspendWorkflow(
          id, updated.metadata, condition.eventNames,
          condition.timerAt.map(_._1), condition.workflows.keySet
        ))
        SuspendResult.Suspended
  }

  private def findPendingEvent(
    id: WorkflowId,
    condition: EventQuery.Combined[?, ?]
  ): Future[Option[(String, PendingEvent[?], Boolean)]] = async[Future] {
    val eventNames = condition.eventNames.toSeq

    // Check targeted events first
    var result: Option[(String, PendingEvent[?], Boolean)] = None
    var i = 0
    while i < eventNames.size && result.isEmpty do
      val name = eventNames(i)
      val events = await(storage.loadWorkflowPendingEvents(id, name))
      if events.nonEmpty then
        result = Some((name, events.head, true))
      i += 1

    // Then check broadcast events
    i = 0
    while i < eventNames.size && result.isEmpty do
      val name = eventNames(i)
      val events = await(storage.loadPendingEvents(name))
      if events.nonEmpty then
        result = Some((name, events.head, false))
      i += 1

    result
  }

  private def executeSendBroadcastEvent(
    eventName: String,
    event: Any,
    eventId: EventId,
    timestamp: Instant,
    eventStorage: DurableStorage[?, ?]
  ): Future[SendResult] = async[Future] {
    val waiting = activeMap.values.filter { r =>
      r.status == WorkflowStatus.Suspended && r.isWaitingForEvent(eventName)
    }.toSeq

    if waiting.isEmpty then
      await(storage.savePendingEvent(eventName, eventId, event, timestamp))
      SendResult.Queued(eventId)
    else
      val target = waiting.head
      val activityIndex = target.metadata.activityIndex
      await(storage.deliverEvent(
        target.id, activityIndex, SingleEvent(eventName), event,
        eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
      ))
      updateInMemoryAfterDeliver(target.id, activityIndex)
      SendResult.Delivered(target.id)
  }

  private def executeSendTargetedEvent(
    targetWorkflowId: WorkflowId,
    eventName: String,
    event: Any,
    eventId: EventId,
    timestamp: Instant,
    policy: DeadLetterPolicy,
    eventStorage: DurableStorage[?, ?]
  ): Future[SendResult] = async[Future] {
    activeMap.get(targetWorkflowId) match
      case Some(record) if record.status.isTerminal =>
        SendResult.TargetTerminated(record.status)

      case Some(record) if record.status == WorkflowStatus.Suspended && record.isWaitingForEvent(eventName) =>
        val activityIndex = record.metadata.activityIndex
        await(storage.deliverEvent(
          targetWorkflowId, activityIndex, SingleEvent(eventName), event,
          eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(targetWorkflowId, activityIndex)
        SendResult.Delivered(targetWorkflowId)

      case Some(_) =>
        await(storage.saveWorkflowPendingEvent(targetWorkflowId, eventName, eventId, event, timestamp, policy))
        SendResult.Queued(eventId)

      case None =>
        SendResult.TargetNotFound
  }

  private def executeHandleTimerFired(
    workflowId: WorkflowId,
    wakeAt: Instant,
    activityIndex: Int,
    timeReachedStorage: DurableStorage[TimeReached, ?]
  ): Future[Option[WorkflowRecord]] = async[Future] {
    timersMap.remove(workflowId)
    activeMap.get(workflowId).filter(_.status == WorkflowStatus.Suspended) match
      case Some(record) =>
        val timeReached = TimeReached(scheduledAt = wakeAt, firedAt = Instant.now())
        await(storage.deliverTimer(
          workflowId, activityIndex, wakeAt, timeReached,
          timeReachedStorage.asInstanceOf[DurableStorage[TimeReached, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(workflowId, activityIndex)
        Some(record)
      case None =>
        None
  }

  private def executeCancelWorkflow(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id).filter(r => r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended).map { record =>
      timersMap.remove(id).foreach(_.cancel())
      activeMap.remove(id)
      runnersMap.remove(id)
      record
    }

  // === Lazy Loading Operations ===

  private def executeEnsureLoaded(workflowId: WorkflowId): Future[Option[WorkflowRecord]] = async[Future] {
    activeMap.get(workflowId) match
      case Some(record) =>
        val updated = record.copy(lastAccessedAt = Instant.now())
        activeMap.put(workflowId, updated)
        Some(updated)
      case None =>
        // Load full record from storage (includes wait conditions)
        await(storage.loadWorkflowRecord(workflowId)) match
          case Some(record) if !record.status.isTerminal =>
            val updated = record.copy(lastAccessedAt = Instant.now())
            activeMap.put(workflowId, updated)
            Some(updated)
          case _ =>
            None
  }

  private def executeEvictFromCache(workflowIds: Seq[WorkflowId]): Int =
    var evicted = 0
    workflowIds.foreach { id =>
      activeMap.get(id) match
        case Some(record) if record.status == WorkflowStatus.Suspended =>
          if !runnersMap.contains(id) && !timersMap.contains(id) then
            activeMap.remove(id)
            evicted += 1
        case _ => ()
    }
    evicted

  private def executeEvictByTtl(notAccessedSince: Instant): Int =
    val toEvict = activeMap.values.filter { r =>
      r.status == WorkflowStatus.Suspended &&
      r.lastAccessedAt.isBefore(notAccessedSince) &&
      !runnersMap.contains(r.id) &&
      !timersMap.contains(r.id)
    }.map(_.id).toSeq

    toEvict.foreach(activeMap.remove)
    toEvict.size

  private def executeTouchWorkflow(workflowId: WorkflowId): Unit =
    activeMap.updateWith(workflowId)(_.map(_.copy(lastAccessedAt = Instant.now())))
