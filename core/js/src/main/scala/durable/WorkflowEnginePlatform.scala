package durable

import scala.concurrent.ExecutionContext
import java.time.Instant
import durable.runtime.Scheduler

/**
 * JS platform factory for WorkflowEngine.
 */
trait WorkflowEnginePlatform:
  def create[S <: DurableStorageBackend](
    storage: S,
    config: WorkflowEngineConfig
  )(using ExecutionContext, DurableStorage[Instant, S]): WorkflowEngine[S] =
    new WorkflowEngineImpl[S](storage, summon[DurableStorage[Instant, S]], config, Scheduler.default)
