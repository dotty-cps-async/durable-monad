package durable

import scala.concurrent.ExecutionContext
import java.time.Instant
import durable.runtime.Scheduler
import durable.engine.WorkflowStateCoordinatorImpl
import durable.engine.coordinator.{WorkflowEngineImpl => CoordinatorWorkflowEngineImpl}

/**
 * JS platform factory for WorkflowEngine.
 *
 * Uses a WorkflowStateCoordinatorImpl. Since JavaScript is single-threaded,
 * operations execute immediately without needing synchronization.
 */
trait WorkflowEnginePlatform:
  def create[S <: DurableStorageBackend](
    storage: S,
    config: WorkflowEngineConfig
  )(using ExecutionContext, DurableStorage[TimeReached, S]): WorkflowEngine[S] =
    val stateCoordinator = new WorkflowStateCoordinatorImpl()
    new CoordinatorWorkflowEngineImpl[S](
      storage,
      stateCoordinator,
      summon[DurableStorage[TimeReached, S]],
      config,
      Scheduler.default
    )
