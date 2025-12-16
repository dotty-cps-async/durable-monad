package durable

import scala.collection.mutable
import scala.concurrent.Future

/**
 * JS platform implementation using mutable.HashMap (single-threaded).
 */
trait WorkflowEngineStatePlatform:
  def apply(): WorkflowEngineState =
    new WorkflowEngineState(
      activeMap = mutable.HashMap.empty[WorkflowId, WorkflowRecord],
      runnersMap = mutable.HashMap.empty[WorkflowId, Future[WorkflowSessionResult[?]]],
      timersMap = mutable.HashMap.empty[WorkflowId, TimerHandle]
    )
