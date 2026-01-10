package durable

import scala.concurrent.duration.{FiniteDuration, DurationInt}

import com.github.rssh.appcontext.*
import durable.engine.{ConfigSource, WorkflowSessionRunner}

/**
 * Configuration for WorkflowEngine.
 *
 * @param runConfig Configuration passed to WorkflowSessionRunner for each workflow
 * @param appContextCache Application context cache for environment resources.
 *                        Shared across all workflows in this engine.
 *                        Create fresh on engine restart to get fresh resources.
 * @param configSource Source for external configuration (database URLs, API keys, etc.)
 *                     Workflows can access config via Durable.configRaw("section").
 * @param timerHorizon How far ahead to schedule timers on startup and during sweep.
 *                     Only suspended workflows with timers within this horizon are loaded.
 * @param timerSweepInterval How often to check storage for upcoming timers.
 * @param cacheTtl Time-to-live for in-memory workflow cache. Workflows not accessed
 *                 within this period are evicted from memory (state stays in storage).
 * @param cacheEvictionInterval How often to run cache eviction sweep.
 */
case class WorkflowEngineConfig(
  runConfig: WorkflowSessionRunner.RunConfig = WorkflowSessionRunner.RunConfig.default,
  appContextCache: AppContext.Cache = AppContext.newCache,
  configSource: ConfigSource = ConfigSource.empty,
  // Periodic wakeup settings (lazy loading always enabled)
  timerHorizon: FiniteDuration = 10.minutes,
  timerSweepInterval: FiniteDuration = 5.minutes,
  cacheTtl: FiniteDuration = 30.minutes,
  cacheEvictionInterval: FiniteDuration = 5.minutes
)

object WorkflowEngineConfig:
  val default: WorkflowEngineConfig = WorkflowEngineConfig()

/**
 * Report from engine recovery process.
 *
 * @param runningResumed Number of running workflows resumed (were interrupted by crash)
 * @param nearTimersLoaded Number of suspended workflows loaded due to near-term timers
 * @param pendingEventsDelivered Number of pending broadcast events delivered
 * @param totalLoaded Total workflows loaded into memory
 */
case class RecoveryReport(
  runningResumed: Int,
  nearTimersLoaded: Int,
  pendingEventsDelivered: Int,
  totalLoaded: Int
):
  // Compatibility accessors for old field names
  def activeWorkflows: Int = totalLoaded
  def resumedSuspended: Int = nearTimersLoaded
  def resumedRunning: Int = runningResumed

object RecoveryReport:
  /** Legacy factory for backward compatibility */
  def apply(activeWorkflows: Int, resumedSuspended: Int, resumedRunning: Int): RecoveryReport =
    new RecoveryReport(
      runningResumed = resumedRunning,
      nearTimersLoaded = resumedSuspended,
      pendingEventsDelivered = 0,
      totalLoaded = activeWorkflows
    )
