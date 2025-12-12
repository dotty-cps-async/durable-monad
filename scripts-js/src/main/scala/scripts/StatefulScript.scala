package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Example script that demonstrates context dumping for state persistence.
 * Can save and restore its state between runs.
 */
object StatefulScript:

  /**
   * Entry point called by ScalaVM.
   * Expects context with:
   *   - state: current state object (can be empty on first run)
   *   - dumpContext: function to save state (optional)
   *   - dumpFile: filename to save state to (optional)
   * Returns: updated state
   */
  @JSExportTopLevel("statefulScript")
  def main(context: js.Dynamic): js.Any =
    // Get or initialize state
    val state = if js.isUndefined(context.state) || context.state == null then
      js.Dynamic.literal(
        runCount = 0,
        history = js.Array[String](),
        lastRun = null
      )
    else
      context.state

    // Update state
    val runCount = state.runCount.asInstanceOf[Int] + 1
    state.runCount = runCount

    val timestamp = new js.Date().toISOString()
    state.lastRun = timestamp

    state.history.asInstanceOf[js.Array[String]].push(s"Run #$runCount at $timestamp")

    println(s"[StatefulScript] Run #$runCount")
    println(s"[StatefulScript] History has ${state.history.asInstanceOf[js.Array[String]].length} entries")

    // Dump state if dumpContext function is provided
    if !js.isUndefined(context.dumpContext) && !js.isUndefined(context.dumpFile) then
      val dumpFn = context.dumpContext.asInstanceOf[js.Function2[js.Object, String, Unit]]
      dumpFn(state.asInstanceOf[js.Object], context.dumpFile.asInstanceOf[String])
      println(s"[StatefulScript] State dumped to ${context.dumpFile}")

    state
