package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import cps.*
import example.durable.*

/**
 * Example workflow using the Durable monad with async/await syntax.
 *
 * This demonstrates the replay-based execution model:
 * - Each `await(Durable.step(...))` is a checkpoint
 * - On shutdown, state is saved (step number + cached results)
 * - On resume, cached results are returned until reaching the saved step
 * - Then actual execution continues
 */
object MonadicWorkflow:

  /**
   * Define the workflow as a Durable computation.
   * Each step is cached and will be replayed on resume.
   */
  def workflow(items: Seq[Int]): Durable[Int] = async[Durable] {
    println(s"[MonadicWorkflow] Starting with ${items.length} items")

    // Step 1: Process first half
    val firstHalf = await(Durable.step {
      println("[MonadicWorkflow] Step 1: Processing first half")
      items.take(items.length / 2).map(_ * 2)
    })
    println(s"[MonadicWorkflow] First half done: $firstHalf")

    // Check if we should shutdown (simulated after first step)
    // On fresh execution: shutdown after step 1
    // On resumed execution: skip shutdown (we already passed this point)
    val step = await(Durable.currentStep)
    val resumed = await(Durable.isResumed)
    println(s"[MonadicWorkflow] After step 1: currentStep=$step, resumed=$resumed")

    // Use a helper to conditionally continue or stop
    val shouldContinue = if step == 1 && !resumed then
      println("[MonadicWorkflow] Simulating shutdown after step 1")
      await(Durable.shutdown)
      false
    else
      true

    if !shouldContinue then
      -1 // Dummy return value when shutting down
    else
      // Step 2: Process second half
      val secondHalf = await(Durable.step {
        println("[MonadicWorkflow] Step 2: Processing second half")
        items.drop(items.length / 2).map(_ * 2)
      })
      println(s"[MonadicWorkflow] Second half done: $secondHalf")

      // Step 3: Combine results
      val total = await(Durable.step {
        println("[MonadicWorkflow] Step 3: Combining results")
        (firstHalf ++ secondHalf).sum
      })

      println(s"[MonadicWorkflow] Total: $total")
      total
  }

  /**
   * Entry point for running from ScalaVM.
   * Context should have:
   *   - items: Array of Int
   *   - snapshot (optional): Previous snapshot for resumption
   */
  @JSExportTopLevel("monadicWorkflow")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[Int]].toSeq

    // Check if we're resuming
    val snapshotOpt: Option[DurableContextSnapshot] =
      if js.isUndefined(context.snapshot) || context.snapshot == null then
        None
      else
        Some(DurableContextSnapshot.fromJS(context.snapshot))

    // Run or resume the workflow
    val result = snapshotOpt match
      case None =>
        println("[MonadicWorkflow] Fresh execution")
        Durable.execute(workflow(items))
      case Some(snap) =>
        println(s"[MonadicWorkflow] Resuming from step ${snap.stepIndex}")
        Durable.resume(workflow(items), snap)

    // Return result
    result match
      case Right(value) =>
        js.Dynamic.literal(
          status = "completed",
          result = value
        )
      case Left(snapshot) =>
        js.Dynamic.literal(
          status = "shutdown",
          snapshot = snapshot.toJS
        )
