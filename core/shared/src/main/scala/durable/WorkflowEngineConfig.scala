package durable

import com.github.rssh.appcontext.*

/**
 * Configuration for WorkflowEngine.
 *
 * @param runConfig Configuration passed to WorkflowSessionRunner for each workflow
 * @param appContext Application context cache for environment resources.
 *                   Shared across all workflows in this engine.
 *                   Create fresh on engine restart to get fresh resources.
 */
case class WorkflowEngineConfig(
  runConfig: RunConfig = RunConfig.default,
  appContext: AppContext.Cache = AppContext.newCache
)

object WorkflowEngineConfig:
  val default: WorkflowEngineConfig = WorkflowEngineConfig()

/**
 * Report from engine recovery process.
 *
 * @param activeWorkflows Total active workflows found
 * @param resumedSuspended Number of suspended workflows resumed
 * @param resumedRunning Number of running workflows resumed (were interrupted)
 */
case class RecoveryReport(
  activeWorkflows: Int,
  resumedSuspended: Int,
  resumedRunning: Int
)
