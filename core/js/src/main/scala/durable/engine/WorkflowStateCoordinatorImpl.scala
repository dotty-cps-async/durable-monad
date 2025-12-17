package durable.engine

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}

import durable.*

/**
 * JS implementation of WorkflowStateCoordinator.
 *
 * Since JavaScript is single-threaded, operations execute immediately
 * without needing coordination. All operations complete synchronously
 * wrapped in Future.successful.
 */
class WorkflowStateCoordinatorImpl(using ec: ExecutionContext) extends WorkflowStateCoordinator:

  // Internal state - safe because JS is single-threaded
  private val activeMap = mutable.Map[WorkflowId, WorkflowRecord]()
  private val runnersMap = mutable.Map[WorkflowId, Future[WorkflowSessionResult[?]]]()
  private val timersMap = mutable.Map[WorkflowId, TimerHandle]()

  // === Registration ===

  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit] =
    activeMap.put(id, record)
    Future.successful(())

  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit] =
    runnersMap.put(id, runner)
    Future.successful(())

  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit] =
    timersMap.put(id, handle)
    Future.successful(())

  // === State Transitions ===

  def markFinished(id: WorkflowId): Future[Unit] =
    runnersMap.remove(id)
    activeMap.remove(id)
    Future.successful(())

  def markSuspended(id: WorkflowId, activityIndex: Int, condition: EventQuery.Combined[?, ?]): Future[Unit] =
    runnersMap.remove(id)
    activeMap.get(id).foreach { record =>
      activeMap.put(id, record.copy(
        metadata = record.metadata.copy(activityIndex = activityIndex),
        status = WorkflowStatus.Suspended,
        waitingForEvents = condition.eventNames,
        waitingForTimer = condition.timerAt.map(_._1),
        waitingForWorkflows = condition.workflows.keySet,
        updatedAt = Instant.now()
      ))
    }
    Future.successful(())

  def markResumed(id: WorkflowId, newActivityIndex: Int): Future[Option[WorkflowRecord]] =
    // Cancel any pending timer
    timersMap.remove(id).foreach(_.cancel())

    val result = activeMap.get(id).map { record =>
      val updated = record.copy(
        metadata = record.metadata.copy(activityIndex = newActivityIndex),
        status = WorkflowStatus.Running,
        updatedAt = Instant.now()
      ).clearWaitConditions
      activeMap.put(id, updated)
      record // Return original record
    }
    Future.successful(result)

  def updateForContinueAs(id: WorkflowId, metadata: WorkflowMetadata): Future[Unit] =
    activeMap.get(id).foreach { record =>
      activeMap.put(id, record.copy(
        metadata = metadata,
        status = WorkflowStatus.Running,
        updatedAt = Instant.now()
      ).clearWaitConditions)
    }
    Future.successful(())

  // === Queries with Actions ===

  def findWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]] =
    val result = activeMap.values.filter { record =>
      record.status == WorkflowStatus.Suspended &&
      record.isWaitingForEvent(eventName)
    }.toSeq
    Future.successful(result)

  def getAndRemoveTimer(id: WorkflowId): Future[Option[WorkflowRecord]] =
    timersMap.remove(id)
    val result = activeMap.get(id).filter(_.status == WorkflowStatus.Suspended)
    Future.successful(result)

  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]] =
    val result = activeMap.get(id).filter { r =>
      r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended
    }.map { record =>
      timersMap.remove(id).foreach(_.cancel())
      activeMap.remove(id)
      runnersMap.remove(id)
      record
    }
    Future.successful(result)

  // === Bulk Operations ===

  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit] =
    records.foreach { record =>
      activeMap.put(record.id, record)
    }
    Future.successful(())

  def cancelAllTimers(): Future[Seq[TimerHandle]] =
    val handles = timersMap.values.toSeq
    handles.foreach(_.cancel())
    timersMap.clear()
    Future.successful(handles)

  // === Read-Only Queries ===

  def getActive(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id)

  // === Lifecycle ===

  def shutdown(): Future[Unit] =
    Future.successful(())
