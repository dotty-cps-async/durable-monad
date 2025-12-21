package durable

import com.github.rssh.appcontext.*
import durable.engine.ConfigSource

/**
 * Configuration for WorkflowEngine.
 *
 * @param runConfig Configuration passed to WorkflowSessionRunner for each workflow
 * @param appContext Application context cache for environment resources.
 *                   Shared across all workflows in this engine.
 *                   Create fresh on engine restart to get fresh resources.
 * @param configSource Source for external configuration (database URLs, API keys, etc.)
 *                     Workflows can access config via Durable.configRaw("section").
 */
case class WorkflowEngineConfig(
  runConfig: WorkflowSessionRunner.RunConfig = WorkflowSessionRunner.RunConfig.default,
  appContext: AppContext.Cache = AppContext.newCache,
  configSource: ConfigSource = ConfigSource.empty
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
