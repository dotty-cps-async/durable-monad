package durable

/**
 * Configuration for WorkflowEngine.
 *
 * @param runConfig Configuration passed to WorkflowSessionRunner for each workflow
 */
case class WorkflowEngineConfig(
  runConfig: RunConfig = RunConfig.default
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
