package durable.engine

import java.util.concurrent.{Executors, ExecutorService}
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, Promise, ExecutionContext, Await}
import scala.concurrent.duration.Duration

import durable.*

/**
 * JVM implementation of WorkflowStateCoordinator.
 *
 * Uses a single-threaded ExecutorService to serialize all state operations,
 * preventing race conditions between concurrent operations.
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

  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "workflow-coordinator")
      t.setDaemon(true)
      t
    }

  private val storageTimeout = Duration(30, "seconds")

  private def awaitStorage[T](f: Future[T]): T =
    Await.result(f, storageTimeout)

  /**
   * Submit operation to coordinator queue.
   * Single entry point - all operations go through here.
   */
  def submit[R](op: CoordinatorOp[R]): Future[R] =
    val promise = Promise[R]()
    executor.execute { () =>
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
    executor.execute { () =>
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

      case CoordinatorOp.SendBroadcastEvent(eventName, event, eventId, timestamp, eventStorage) =>
        executeSendBroadcastEvent(eventName, event, eventId, timestamp, eventStorage)

      case CoordinatorOp.SendTargetedEvent(targetId, eventName, event, eventId, timestamp, policy, eventStorage) =>
        executeSendTargetedEvent(targetId, eventName, event, eventId, timestamp, policy, eventStorage)

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
        executor.shutdown()
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
        // Deliver first event, queue rest
        activeMap.get(workflowId).filter(_.status == WorkflowStatus.Suspended) match
          case Some(record) =>
            val first = events.head
            val activityIndex = record.metadata.activityIndex

            // Deliver first event
            awaitStorage(storage.deliverEvent(
              workflowId,
              activityIndex,
              SingleEvent(first.eventName),
              first.event,
              first.eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
            ))
            updateInMemoryAfterDeliver(workflowId, activityIndex)

            // Queue the rest as targeted events
            events.tail.foreach { e =>
              awaitStorage(storage.saveWorkflowPendingEvent(
                workflowId, e.eventName, e.eventId, e.event, e.timestamp, e.policy
              ))
            }
            SendResult.Delivered(workflowId)

          case None =>
            // Queue all as targeted events
            events.foreach { e =>
              awaitStorage(storage.saveWorkflowPendingEvent(
                e.targetWorkflowId, e.eventName, e.eventId, e.event, e.timestamp, e.policy
              ))
            }
            SendResult.Queued(events.head.eventId)

      case FusedOp.SuspendWithImmediateEvent(suspend, event) =>
        // Skip suspend state, deliver directly
        val record = activeMap.getOrElse(suspend.id, throw new RuntimeException("Workflow not found"))
        awaitStorage(storage.deliverEvent(
          suspend.id,
          suspend.activityIndex,
          SingleEvent(event.eventName),
          event.event,
          event.eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(suspend.id, suspend.activityIndex)
        SuspendResult.Delivered(record, event.eventName)

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
  ): SuspendResult =
    // Step 1: Update in-memory state to Suspended
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

    // Step 2: Check for pending events (targeted first, then broadcast)
    val pendingEvent = findPendingEvent(id, condition)

    pendingEvent match
      case Some((eventName, pending, isTargeted)) =>
        // Step 3a: Deliver pending event (single atomic storage op)
        awaitStorage(storage.deliverPendingEvent(
          id, activityIndex, SingleEvent(eventName), pending.value,
          eventStorages(eventName).asInstanceOf[DurableStorage[Any, DurableStorageBackend]],
          pending.eventId, eventName, isTargeted
        ))
        updateInMemoryAfterDeliver(id, activityIndex)
        SuspendResult.Delivered(updated, eventName)

      case None =>
        // Step 3b: No pending event - persist suspended state
        awaitStorage(storage.suspendWorkflow(
          id, updated.metadata, condition.eventNames,
          condition.timerAt.map(_._1), condition.workflows.keySet
        ))
        SuspendResult.Suspended

  private def findPendingEvent(
    id: WorkflowId,
    condition: EventQuery.Combined[?, ?]
  ): Option[(String, PendingEvent[?], Boolean)] =
    // Check targeted events first, then broadcast
    condition.eventNames.iterator.flatMap { eventName =>
      awaitStorage(storage.loadWorkflowPendingEvents(id, eventName))
        .headOption.map(p => (eventName, p, true))
    }.nextOption().orElse {
      condition.eventNames.iterator.flatMap { eventName =>
        awaitStorage(storage.loadPendingEvents(eventName))
          .headOption.map(p => (eventName, p, false))
      }.nextOption()
    }

  private def executeSendBroadcastEvent(
    eventName: String,
    event: Any,
    eventId: EventId,
    timestamp: Instant,
    eventStorage: DurableStorage[?, ?]
  ): SendResult =
    val waiting = activeMap.values.filter { r =>
      r.status == WorkflowStatus.Suspended && r.isWaitingForEvent(eventName)
    }.toSeq

    if waiting.isEmpty then
      awaitStorage(storage.savePendingEvent(eventName, eventId, event, timestamp))
      SendResult.Queued(eventId)
    else
      val target = waiting.head
      val activityIndex = target.metadata.activityIndex
      awaitStorage(storage.deliverEvent(
        target.id, activityIndex, SingleEvent(eventName), event,
        eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
      ))
      updateInMemoryAfterDeliver(target.id, activityIndex)
      SendResult.Delivered(target.id)

  private def executeSendTargetedEvent(
    targetWorkflowId: WorkflowId,
    eventName: String,
    event: Any,
    eventId: EventId,
    timestamp: Instant,
    policy: DeadLetterPolicy,
    eventStorage: DurableStorage[?, ?]
  ): SendResult =
    activeMap.get(targetWorkflowId) match
      case Some(record) if record.status.isTerminal =>
        SendResult.TargetTerminated(record.status)

      case Some(record) if record.status == WorkflowStatus.Suspended && record.isWaitingForEvent(eventName) =>
        val activityIndex = record.metadata.activityIndex
        awaitStorage(storage.deliverEvent(
          targetWorkflowId, activityIndex, SingleEvent(eventName), event,
          eventStorage.asInstanceOf[DurableStorage[Any, DurableStorageBackend]]
        ))
        updateInMemoryAfterDeliver(targetWorkflowId, activityIndex)
        SendResult.Delivered(targetWorkflowId)

      case Some(_) =>
        awaitStorage(storage.saveWorkflowPendingEvent(targetWorkflowId, eventName, eventId, event, timestamp, policy))
        SendResult.Queued(eventId)

      case None =>
        awaitStorage(storage.loadWorkflowMetadata(targetWorkflowId)) match
          case Some((_, status)) if status.isTerminal => SendResult.TargetTerminated(status)
          case Some(_) =>
            awaitStorage(storage.saveWorkflowPendingEvent(targetWorkflowId, eventName, eventId, event, timestamp, policy))
            SendResult.Queued(eventId)
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
    // Check if already in memory
    activeMap.get(workflowId) match
      case Some(record) =>
        // Already loaded - just update lastAccessedAt
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
            // Not found or terminal - don't load
            None

  private def executeEvictFromCache(workflowIds: Seq[WorkflowId]): Int =
    var evicted = 0
    workflowIds.foreach { id =>
      activeMap.get(id) match
        case Some(record) if record.status == WorkflowStatus.Suspended =>
          // Only evict suspended workflows
          // Don't evict if there's an active runner or scheduled timer
          if !runnersMap.contains(id) && !timersMap.contains(id) then
            activeMap.remove(id)
            evicted += 1
        case _ =>
          // Running/terminal or not in memory - skip
          ()
    }
    evicted

  private def executeEvictByTtl(notAccessedSince: Instant): Int =
    // Find suspended workflows not accessed since threshold
    val toEvict = activeMap.values.filter { r =>
      r.status == WorkflowStatus.Suspended &&
      r.lastAccessedAt.isBefore(notAccessedSince) &&
      !runnersMap.contains(r.id) &&
      !timersMap.contains(r.id)  // Don't evict if timer is scheduled
    }.map(_.id).toSeq

    toEvict.foreach(activeMap.remove)
    toEvict.size

  private def executeTouchWorkflow(workflowId: WorkflowId): Unit =
    activeMap.updateWith(workflowId)(_.map(_.copy(lastAccessedAt = Instant.now())))
