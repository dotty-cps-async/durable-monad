package durable.engine

import java.util.concurrent.{Executors, ExecutorService}
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, Promise, ExecutionContext}

import durable.*

/**
 * JVM implementation of WorkflowStateCoordinator.
 *
 * Uses a single-threaded ExecutorService to serialize all state operations,
 * preventing race conditions between concurrent operations.
 */
class WorkflowStateCoordinatorImpl(using ec: ExecutionContext) extends WorkflowStateCoordinator:

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

  private def execute[T](op: => T): Future[T] =
    val promise = Promise[T]()
    executor.execute { () =>
      try
        promise.success(op)
      catch
        case e: Throwable =>
          promise.failure(e)
    }
    promise.future

  // === Registration ===

  def registerWorkflow(id: WorkflowId, record: WorkflowRecord): Future[Unit] =
    execute {
      activeMap.put(id, record)
    }

  def registerRunner(id: WorkflowId, runner: Future[WorkflowSessionResult[?]]): Future[Unit] =
    execute {
      runnersMap.put(id, runner)
    }

  def registerTimer(id: WorkflowId, handle: TimerHandle): Future[Unit] =
    execute {
      timersMap.put(id, handle)
    }

  // === State Transitions ===

  def markFinished(id: WorkflowId): Future[Unit] =
    execute {
      runnersMap.remove(id)
      activeMap.remove(id)
    }

  def markSuspended(id: WorkflowId, activityIndex: Int, condition: EventQuery.Combined[?, ?]): Future[Unit] =
    execute {
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
    }

  def markResumed(id: WorkflowId, newActivityIndex: Int): Future[Option[WorkflowRecord]] =
    execute {
      // Cancel any pending timer
      timersMap.remove(id).foreach(_.cancel())

      activeMap.get(id).map { record =>
        val updated = record.copy(
          metadata = record.metadata.copy(activityIndex = newActivityIndex),
          status = WorkflowStatus.Running,
          updatedAt = Instant.now()
        ).clearWaitConditions
        activeMap.put(id, updated)
        record // Return original record (caller may need old activityIndex)
      }
    }

  def updateForContinueAs(id: WorkflowId, metadata: WorkflowMetadata): Future[Unit] =
    execute {
      activeMap.get(id).foreach { record =>
        activeMap.put(id, record.copy(
          metadata = metadata,
          status = WorkflowStatus.Running,
          updatedAt = Instant.now()
        ).clearWaitConditions)
      }
    }

  // === Queries with Actions ===

  def findWaitingForEvent(eventName: String): Future[Seq[WorkflowRecord]] =
    execute {
      activeMap.values.filter { record =>
        record.status == WorkflowStatus.Suspended &&
        record.isWaitingForEvent(eventName)
      }.toSeq
    }

  def getAndRemoveTimer(id: WorkflowId): Future[Option[WorkflowRecord]] =
    execute {
      timersMap.remove(id)
      activeMap.get(id).filter(_.status == WorkflowStatus.Suspended)
    }

  def cancelWorkflow(id: WorkflowId): Future[Option[WorkflowRecord]] =
    execute {
      activeMap.get(id).filter { r =>
        r.status == WorkflowStatus.Running || r.status == WorkflowStatus.Suspended
      }.map { record =>
        timersMap.remove(id).foreach(_.cancel())
        activeMap.remove(id)
        runnersMap.remove(id)
        record
      }
    }

  // === Bulk Operations ===

  def recoverWorkflows(records: Seq[WorkflowRecord]): Future[Unit] =
    execute {
      records.foreach { record =>
        activeMap.put(record.id, record)
      }
    }

  def cancelAllTimers(): Future[Seq[TimerHandle]] =
    execute {
      val handles = timersMap.values.toSeq
      handles.foreach(_.cancel())
      timersMap.clear()
      handles
    }

  // === Read-Only Queries ===

  def getActive(id: WorkflowId): Option[WorkflowRecord] =
    activeMap.get(id) // Eventually consistent read

  // === Lifecycle ===

  def shutdown(): Future[Unit] =
    Future {
      executor.shutdown()
    }
