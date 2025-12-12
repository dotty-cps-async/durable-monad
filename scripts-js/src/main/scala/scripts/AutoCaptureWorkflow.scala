package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import cps.*
import example.durable.*

/**
 * Workflow with automatic V8 capture.
 *
 * No Durable.step needed! Just use await(Durable.suspend) to mark checkpoints.
 * V8 Inspector captures ALL locals at each suspend point.
 */
object AutoCaptureWorkflow:

  def workflow(items: Seq[Int], isResume: Boolean): Durable[Int] = async[Durable] {
    println(s"[AutoCapture] Starting with ${items.length} items")

    val config = items.length * 10
    val multiplier = 2

    await(Durable.suspend)  // Checkpoint 1

    val firstResult = items.take(items.length / 2).sum * config
    println(s"[AutoCapture] firstResult = $firstResult")

    val intermediate = firstResult / multiplier

    await(Durable.suspend)  // Checkpoint 2 - captures {config, multiplier, firstResult, intermediate}

    // Check if we should shutdown (isResume is passed from runner)
    if !isResume then
      println("[AutoCapture] First run - shutting down")
      await(Durable.shutdown)
      -1
    else
      println("[AutoCapture] Resumed - continuing")

      val secondResult = items.drop(items.length / 2).sum + intermediate
      println(s"[AutoCapture] secondResult = $secondResult")

      await(Durable.suspend)  // Checkpoint 3

      val total = firstResult + secondResult
      println(s"[AutoCapture] total = $total")
      total
  }

  @JSExportTopLevel("autoCaptureWorkflow")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[Int]].toSeq
    // V8 runner tells us if this is a resume
    val isResume = !js.isUndefined(context.isResume) && context.isResume.asInstanceOf[Boolean]

    println(s"[AutoCapture] ${if isResume then "RESUME" else "FRESH"} execution")

    val result = Durable.execute(workflow(items, isResume))

    result match
      case Right(value) =>
        js.Dynamic.literal(status = "completed", result = value)
      case Left(snapshot) =>
        js.Dynamic.literal(status = "shutdown", snapshot = snapshot.toJS)
