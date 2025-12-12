package example.scalavm

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Runner for durable scripts that can be stopped and resumed.
 *
 * Durable scripts must export two functions:
 * - `{entryPoint}` - main entry point, receives context with optional `resumeFrom` snapshot
 * - `{entryPoint}Shutdown` - called to gracefully shutdown, should return state to save
 *
 * @param scriptPath Path to compiled script
 * @param entryPoint Base name for script functions
 * @param stateDir Directory to store state files
 */
class DurableRunner(
  scriptPath: String,
  entryPoint: String,
  stateDir: String = "state/durable"
):
  private val scriptId = s"$entryPoint-${System.currentTimeMillis()}"
  private val stateFile = s"$stateDir/$entryPoint.json"
  private var currentSnapshot: Option[DurableSnapshot] = None

  /**
   * Start or resume the script.
   * If a saved state exists, resumes from it. Otherwise starts fresh.
   *
   * @param initialContext Context to pass on fresh start
   * @return Script result or snapshot if shutdown
   */
  def startOrResume(initialContext: js.Dynamic = js.Dynamic.literal()): DurableSnapshot =
    val hasState = ScalaVM.stateExists(stateFile)

    if hasState then
      resume()
    else
      start(initialContext)

  /**
   * Start fresh execution.
   */
  def start(initialContext: js.Dynamic = js.Dynamic.literal()): DurableSnapshot =
    println(s"[DurableRunner] Starting fresh: $entryPoint")

    // Create shutdown function that script can call
    val shutdownFn: js.Function1[js.Dynamic, Unit] = (data: js.Dynamic) =>
      val snapshot = DurableSnapshot.create(
        scriptId = scriptId,
        entryPoint = entryPoint,
        status = DurableState.Shutdown,
        step = data.step.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
        data = data,
        totalSteps = data.totalSteps.asInstanceOf[js.UndefOr[Int]]
      )
      ScalaVM.saveSnapshot(snapshot, stateFile)
      currentSnapshot = Some(snapshot)
      println(s"[DurableRunner] Shutdown state saved at step ${snapshot.metadata.step}")

    // Create checkpoint function for periodic saves
    val checkpointFn: js.Function1[js.Dynamic, Unit] = (data: js.Dynamic) =>
      val snapshot = DurableSnapshot.create(
        scriptId = scriptId,
        entryPoint = entryPoint,
        status = DurableState.Running,
        step = data.step.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
        data = data,
        totalSteps = data.totalSteps.asInstanceOf[js.UndefOr[Int]]
      )
      ScalaVM.saveSnapshot(snapshot, stateFile)
      currentSnapshot = Some(snapshot)

    val context = js.Dynamic.literal(
      initial = initialContext,
      resumeFrom = null,
      shutdown = shutdownFn,
      checkpoint = checkpointFn,
      isResume = false
    )

    runScript(context)

  /**
   * Resume from saved state.
   */
  def resume(): DurableSnapshot =
    val saved = ScalaVM.loadSnapshot(stateFile)
    println(s"[DurableRunner] Resuming ${saved.metadata.entryPoint} from step ${saved.metadata.step}")

    // Create shutdown function
    val shutdownFn: js.Function1[js.Dynamic, Unit] = (data: js.Dynamic) =>
      val snapshot = DurableSnapshot.update(
        previous = saved,
        status = DurableState.Shutdown,
        step = data.step.asInstanceOf[js.UndefOr[Int]].getOrElse(saved.metadata.step),
        data = data
      )
      ScalaVM.saveSnapshot(snapshot, stateFile)
      currentSnapshot = Some(snapshot)
      println(s"[DurableRunner] Shutdown state saved at step ${snapshot.metadata.step}")

    // Create checkpoint function
    val checkpointFn: js.Function1[js.Dynamic, Unit] = (data: js.Dynamic) =>
      val snapshot = DurableSnapshot.update(
        previous = saved,
        status = DurableState.Running,
        step = data.step.asInstanceOf[js.UndefOr[Int]].getOrElse(saved.metadata.step),
        data = data
      )
      ScalaVM.saveSnapshot(snapshot, stateFile)
      currentSnapshot = Some(snapshot)

    val context = js.Dynamic.literal(
      initial = null,
      resumeFrom = saved,
      shutdown = shutdownFn,
      checkpoint = checkpointFn,
      isResume = true
    )

    runScript(context)

  private def runScript(context: js.Dynamic): DurableSnapshot =
    val result = ScalaVM.runScript(
      scriptPath = scriptPath,
      entryPoint = entryPoint,
      context = context.asInstanceOf[js.Object]
    )

    // Check if script completed or was shutdown
    currentSnapshot match
      case Some(snap) if snap.metadata.status == DurableState.Shutdown =>
        snap
      case _ =>
        // Script completed normally
        val finalSnapshot = DurableSnapshot.create(
          scriptId = scriptId,
          entryPoint = entryPoint,
          status = DurableState.Completed,
          step = -1,  // Completed
          data = result.asInstanceOf[js.Dynamic],
          totalSteps = js.undefined
        )
        // Remove state file on completion
        clearState()
        finalSnapshot

  /**
   * Clear saved state (call after successful completion).
   */
  def clearState(): Unit =
    ScalaVM.deleteState(stateFile)
    println(s"[DurableRunner] State cleared: $stateFile")

  /**
   * Get current state file path.
   */
  def getStateFile: String = stateFile

  /**
   * Check if there's saved state to resume from.
   */
  def hasSavedState: Boolean = ScalaVM.stateExists(stateFile)
