package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Example isolated Scala script that calls back to the host.
 * Demonstrates bidirectional communication between VM and host.
 */
object CallbackScript:

  /**
   * Entry point called by ScalaVM.
   * Expects context with: onProgress (callback function), totalSteps (Int)
   * Returns: "completed"
   */
  @JSExportTopLevel("callbackScript")
  def main(context: js.Dynamic): js.Any =
    val totalSteps = context.totalSteps.asInstanceOf[js.UndefOr[Int]].getOrElse(5)
    val onProgress = context.onProgress.asInstanceOf[js.Function2[Int, Int, Unit]]

    println(s"[CallbackScript] Starting $totalSteps steps with progress callback")

    for step <- 1 to totalSteps do
      // Simulate some work
      val result = (1 to 1000).sum

      // Call back to host with progress
      onProgress(step, totalSteps)
      println(s"[CallbackScript] Completed step $step/$totalSteps")

    "completed"
