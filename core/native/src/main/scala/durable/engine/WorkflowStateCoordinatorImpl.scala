package durable.engine

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, Promise, ExecutionContext, Await}
import scala.concurrent.duration.Duration

import durable.*

/**
 * Native implementation of WorkflowStateCoordinator.
 *
 * Uses a dedicated background thread with a blocking queue to serialize
 * all state operations, preventing race conditions.
 *
 * All state-changing operations (both in-memory and storage) go through
 * the coordinator's single thread, using blocking storage calls to ensure
 * atomicity of check-then-act sequences.
 */
class WorkflowStateCoordinatorImpl(
  storage: DurableStorageBackend
)(using ec: ExecutionContext) extends WorkflowStateCoordinator:

  // Internal state - only accessed from coordinator thread
  private val activeMap = mutable.Map[WorkflowId, WorkflowRecord]()
  private val runnersMap = mutable.Map[WorkflowId, Future[WorkflowSessionResult[?]]]()
  private val timersMap = mutable.Map[WorkflowId, TimerHandle]()

  private val queue = new java.util.concurrent.LinkedBlockingQueue[Runnable]()
  @volatile private var shutdownFlag = false

  private val coordinatorThread = new Thread {
    override def run(): Unit =
      while !shutdownFlag do
        try
          val runnable = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
          if runnable != null then
            runnable.run()
        catch
          case _: InterruptedException => ()
  }
  coordinatorThread.setName("workflow-coordinator")
  coordinatorThread.setDaemon(true)
  coordinatorThread.start()

  private val storageTimeout = Duration(30, "seconds")

  private def awaitStorage[T](f: Future[T]): T =
    Await.result(f, storageTimeout)

  /**
   * Submit operation to coordinator queue.
   * Single entry point - all operations go through here.
   */
  def submit[R](op: CoordinatorOp[R]): Future[R] =
    val promise = Promise[R]()
    queue.put { () =>
      try
        val result = executeOp(op)
        promise.success(result)
      catch
        case e: Throwable => promise.failure(e)
    }
    promise.future

  /**
   * Submit batch with potential fusion.
   */
  def submitBatch(ops: Seq[CoordinatorOp[?]]): Future[Seq[?]] =
    val promise = Promise[Seq[?]]()
    queue.put { () =>
      try
        val fused = CoordinatorOpFusion.fuse(ops)
        val results = fused.map(executeFusedOp)
        promise.success(results)
      catch
        case e: Throwable => promise.failure(e)
    }
    promise.future

  /**
   * Read-only query (eventually consistent, no queue).
   */
  def getActive(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id)

  /**
   * Pattern match on operation and execute.
   * All execution happens in coordinator thread.
   */
  private def executeOp[R](op: CoordinatorOp[R]): R =
    op match
      case CoordinatorOp.RegisterWorkflow(id, record) =>
        activeMap.put(id, record)
        ()

      case CoordinatorOp.RegisterRunner(id, runner) =>
        runnersMap.put(id, runner)
        ()

      case CoordinatorOp.RegisterTimer(id, handle) =>
        timersMap.put(id, handle)
        ()

      case CoordinatorOp.SuspendAndCheckPending(id, activityIndex, condition, eventStorages) =>
        executeSuspendAndCheckPending(id, activityIndex, condition, eventStorages)

      case e: CoordinatorOp.SendBroadcastEvent[e] =>
        executeSendBroadcastEvent(e)

      case e: CoordinatorOp.SendTargetedEvent[e] =>
        executeSendTargetedEvent(e)

      case CoordinatorOp.HandleTimerFired(workflowId, wakeAt, activityIndex, timeReachedStorage) =>
        executeHandleTimerFired(workflowId, wakeAt, activityIndex, timeReachedStorage)

      case CoordinatorOp.MarkFinished(id) =>
        runnersMap.remove(id)
        activeMap.remove(id)
        ()

      case CoordinatorOp.CancelWorkflow(id) =>
        executeCancelWorkflow(id)

      case CoordinatorOp.UpdateForContinueAs(id, metadata) =>
        activeMap.get(id).foreach { record =>
          activeMap.put(id, record.copy(
            metadata = metadata,
            status = WorkflowStatus.Running,
            updatedAt = Instant.now()
          ).clearWaitConditions)
        }
        ()

      case CoordinatorOp.RecoverWorkflows(records) =>
        records.foreach(r => activeMap.put(r.id, r))
        ()

      case CoordinatorOp.CancelAllTimers() =>
        val handles = timersMap.values.toSeq
        handles.foreach(_.cancel())
        timersMap.clear()
        handles

      case CoordinatorOp.Shutdown() =>
        shutdownFlag = true
        coordinatorThread.interrupt()
        ()

      case CoordinatorOp.EnsureLoaded(workflowId) =>
        executeEnsureLoaded(workflowId)

      case CoordinatorOp.EvictFromCache(workflowIds) =>
        executeEvictFromCache(workflowIds)

      case CoordinatorOp.EvictByTtl(notAccessedSince) =>
        executeEvictByTtl(notAccessedSince)

      case CoordinatorOp.TouchWorkflow(workflowId) =>
        executeTouchWorkflow(workflowId)

  /**
   * Execute fused operation.
   */
  private def executeFusedOp(fused: FusedOp): Any =
    fused match
      case FusedOp.Single(op) =>
        executeOp(op)

      case FusedOp.BatchDeliverEvents(workflowId, events) =>
        activeMap.get(workflowId).filter(_.status == WorkflowStatus.Suspended) match
          case Some(record) =>
            val first = events.head
            val activityIndex = record.metadata.activityIndex

            // Deliver first event
            deliverEventTyped(workflowId, activityIndex, first)
            updateInMemoryAfterDeliver(workflowId, activityIndex)

            // Queue the rest as targeted events
            events.tail.foreach { e =>
              saveWorkflowPendingEventTyped(workflowId, e)
            }
            SendResult.Delivered(workflowId)

          case None =>
            // Queue all as targeted events
            events.foreach { e =>
              saveWorkflowPendingEventTyped(e.targetWorkflowId, e)
            }
            SendResult.Queued(events.head.eventId)

      case FusedOp.SuspendWithImmediateEvent(suspend, event) =>
        val record = activeMap.getOrElse(suspend.id, throw new RuntimeException("Workflow not found"))
        deliverBroadcastEventTyped(suspend.id, suspend.activityIndex, event)
        updateInMemoryAfterDeliver(suspend.id, suspend.activityIndex)
        SuspendResult.Delivered(record, event.eventName)

  private def updateInMemoryAfterDeliver(workflowId: WorkflowId, activityIndex: Int): Unit =
    timersMap.remove(workflowId).foreach(_.cancel())
    activeMap.updateWith(workflowId)(_.map(r => r.copy(
      metadata = r.metadata.copy(activityIndex = activityIndex + 1),
      status = WorkflowStatus.Running,
      updatedAt = Instant.now()
    ).clearWaitConditions))

  // Typed helper methods to preserve type safety when working with existential types

  private def saveWorkflowPendingEventTyped[E](workflowId: WorkflowId, e: CoordinatorOp.SendTargetedEvent[E]): Unit =
    awaitStorage(storage.saveWorkflowPendingEvent(
      workflowId, e.eventName, e.eventId, e.event, e.timestamp, e.policy
    )(using e.eventStorage))

  private def deliverEventTyped[E](workflowId: WorkflowId, activityIndex: Int, e: CoordinatorOp.SendTargetedEvent[E]): Unit =
    awaitStorage(storage.deliverEvent(
      workflowId, activityIndex, SingleEvent(e.eventName), e.event,
      e.eventStorage.asInstanceOf[DurableStorage[E, DurableStorageBackend]]
    ))

  private def deliverBroadcastEventTyped[E](workflowId: WorkflowId, activityIndex: Int, e: CoordinatorOp.SendBroadcastEvent[E]): Unit =
    awaitStorage(storage.deliverEvent(
      workflowId, activityIndex, SingleEvent(e.eventName), e.event,
      e.eventStorage.asInstanceOf[DurableStorage[E, DurableStorageBackend]]
    ))

  // === Individual operation implementations ===

  private def executeSuspendAndCheckPending(
    id: WorkflowId,
    activityIndex: Int,
    condition: EventQuery.Combined[?, ?],
    eventStorages: Map[String, DurableStorage[?, ?]]
  ): SuspendResult =
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

    val pendingEvent = findPendingEvent(id, condition)

    pendingEvent match
      case Some((eventName, pending, isTargeted)) =>
        awaitStorage(storage.deliverPendingEvent(
          id, activityIndex, SingleEvent(eventName), pending.value,
          eventStorages(eventName).asInstanceOf[DurableStorage[Any, DurableStorageBackend]],
          pending.eventId, eventName, isTargeted
        ))
        updateInMemoryAfterDeliver(id, activityIndex)
        SuspendResult.Delivered(updated, eventName)

      case None =>
        awaitStorage(storage.suspendWorkflow(
          id, updated.metadata, condition.eventNames,
          condition.timerAt.map(_._1), condition.workflows.keySet
        ))
        SuspendResult.Suspended

  private def findPendingEvent(
    id: WorkflowId,
    condition: EventQuery.Combined[?, ?]
  ): Option[(String, PendingEvent[?], Boolean)] =
    condition.eventNames.iterator.flatMap { eventName =>
      awaitStorage(storage.loadWorkflowPendingEvents(id, eventName))
        .headOption.map(p => (eventName, p, true))
    }.nextOption().orElse {
      condition.eventNames.iterator.flatMap { eventName =>
        awaitStorage(storage.loadPendingEvents(eventName))
          .headOption.map(p => (eventName, p, false))
      }.nextOption()
    }

  private def executeSendBroadcastEvent[E](op: CoordinatorOp.SendBroadcastEvent[E]): SendResult =
    val waiting = activeMap.values.filter { r =>
      r.status == WorkflowStatus.Suspended && r.isWaitingForEvent(op.eventName)
    }.toSeq

    if waiting.isEmpty then
      awaitStorage(storage.savePendingEvent(op.eventName, op.eventId, op.event, op.timestamp)(using op.eventStorage))
      SendResult.Queued(op.eventId)
    else
      val target = waiting.head
      val activityIndex = target.metadata.activityIndex
      awaitStorage(storage.deliverEvent(
        target.id, activityIndex, SingleEvent(op.eventName), op.event,
        op.eventStorage.asInstanceOf[DurableStorage[E, DurableStorageBackend]]
      ))
      updateInMemoryAfterDeliver(target.id, activityIndex)
      SendResult.Delivered(target.id)

  private def executeSendTargetedEvent[E](op: CoordinatorOp.SendTargetedEvent[E]): SendResult =
    activeMap.get(op.targetWorkflowId) match
      case Some(record) if record.status.isTerminal =>
        SendResult.TargetTerminated(record.status)

      case Some(record) if record.status == WorkflowStatus.Suspended && record.isWaitingForEvent(op.eventName) =>
        val activityIndex = record.metadata.activityIndex
        awaitStorage(storage.deliverEvent(
          op.targetWorkflowId, activityIndex, SingleEvent(op.eventName), op.event,
          op.eventStorage.asInstanceOf[DurableStorage[E, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(op.targetWorkflowId, activityIndex)
        SendResult.Delivered(op.targetWorkflowId)

      case Some(_) =>
        awaitStorage(storage.saveWorkflowPendingEvent(op.targetWorkflowId, op.eventName, op.eventId, op.event, op.timestamp, op.policy)(using op.eventStorage))
        SendResult.Queued(op.eventId)

      case None =>
        awaitStorage(storage.loadWorkflowMetadata(op.targetWorkflowId)) match
          case Some((_, status)) if status.isTerminal => SendResult.TargetTerminated(status)
          case Some(_) =>
            awaitStorage(storage.saveWorkflowPendingEvent(op.targetWorkflowId, op.eventName, op.eventId, op.event, op.timestamp, op.policy)(using op.eventStorage))
            SendResult.Queued(op.eventId)
          case None => SendResult.TargetNotFound

  private def executeHandleTimerFired(
    workflowId: WorkflowId,
    wakeAt: Instant,
    activityIndex: Int,
    timeReachedStorage: DurableStorage[TimeReached, ?]
  ): Option[WorkflowRecord] =
    timersMap.remove(workflowId)
    activeMap.get(workflowId).filter(_.status == WorkflowStatus.Suspended) match
      case Some(record) =>
        val timeReached = TimeReached(scheduledAt = wakeAt, firedAt = Instant.now())
        awaitStorage(storage.deliverTimer(
          workflowId, activityIndex, wakeAt, timeReached,
          timeReachedStorage.asInstanceOf[DurableStorage[TimeReached, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(workflowId, activityIndex)
        Some(record)
      case None => None

  private def executeCancelWorkflow(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id).filter(r => r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended).map { record =>
      timersMap.remove(id).foreach(_.cancel())
      activeMap.remove(id)
      runnersMap.remove(id)
      record
    }

  // === Lazy Loading Operations ===

  private def executeEnsureLoaded(workflowId: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(workflowId) match
      case Some(record) =>
        val updated = record.copy(lastAccessedAt = Instant.now())
        activeMap.put(workflowId, updated)
        Some(updated)
      case None =>
        // Load full record from storage (includes wait conditions)
        awaitStorage(storage.loadWorkflowRecord(workflowId)) match
          case Some(record) if !record.status.isTerminal =>
            val updated = record.copy(lastAccessedAt = Instant.now())
            activeMap.put(workflowId, updated)
            Some(updated)
          case _ =>
            None

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
