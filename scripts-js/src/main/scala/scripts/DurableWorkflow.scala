package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Example durable workflow that processes items in steps.
 * Can be shutdown and resumed from any step.
 *
 * Context expected:
 * - initial: { items: Array, ... } - on fresh start
 * - resumeFrom: DurableSnapshot - when resuming
 * - shutdown: Function(data) - call to save state and stop
 * - checkpoint: Function(data) - call to save progress without stopping
 * - isResume: Boolean
 */
object DurableWorkflow:

  @JSExportTopLevel("durableWorkflow")
  def main(context: js.Dynamic): js.Any =
    val isResume = context.isResume.asInstanceOf[Boolean]
    val shutdown = context.shutdown.asInstanceOf[js.Function1[js.Dynamic, Unit]]
    val checkpoint = context.checkpoint.asInstanceOf[js.Function1[js.Dynamic, Unit]]

    // Get or initialize state
    val (items, startStep, processedResults) = if isResume then
      val snapshot = context.resumeFrom
      val data = snapshot.data
      val items = data.items.asInstanceOf[js.Array[Int]]
      val step = snapshot.metadata.step.asInstanceOf[Int]
      val results = data.processedResults.asInstanceOf[js.Array[Int]]
      println(s"[DurableWorkflow] Resuming from step $step with ${results.length} processed items")
      (items, step, results)
    else
      val initial = context.initial
      val items = initial.items.asInstanceOf[js.Array[Int]]
      println(s"[DurableWorkflow] Starting fresh with ${items.length} items")
      (items, 0, js.Array[Int]())

    val totalSteps = items.length

    // Process items starting from startStep
    var currentStep = startStep
    var results = processedResults

    while currentStep < totalSteps do
      val item = items(currentStep)

      // Simulate work
      println(s"[DurableWorkflow] Step ${currentStep + 1}/$totalSteps: Processing item $item")
      val processed = item * 2
      results.push(processed)

      currentStep += 1

      // Checkpoint every 2 steps
      if currentStep % 2 == 0 && currentStep < totalSteps then
        println(s"[DurableWorkflow] Checkpoint at step $currentStep")
        checkpoint(js.Dynamic.literal(
          step = currentStep,
          totalSteps = totalSteps,
          items = items,
          processedResults = results
        ))

      // Simulate: shutdown after step 3 on first run (for demo)
      // In real usage, this could be triggered by a signal handler
      if currentStep == 3 && !isResume then
        println(s"[DurableWorkflow] Simulating shutdown at step $currentStep")
        shutdown(js.Dynamic.literal(
          step = currentStep,
          totalSteps = totalSteps,
          items = items,
          processedResults = results
        ))
        return js.Dynamic.literal(
          status = "shutdown",
          step = currentStep
        )

    // Completed all steps
    val sum = results.toArray.sum
    println(s"[DurableWorkflow] Completed! Sum of processed items: $sum")

    js.Dynamic.literal(
      status = "completed",
      results = results,
      sum = sum
    )
