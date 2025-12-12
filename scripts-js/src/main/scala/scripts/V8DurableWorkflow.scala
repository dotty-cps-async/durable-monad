package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.special.debugger
import cps.*
import example.durable.*

/**
 * Durable workflow with V8 auto-capture.
 *
 * Key difference from MonadicWorkflow:
 * - Uses `debugger()` at step boundaries for V8 Inspector to capture locals
 * - External controller (run-v8-workflow.mjs) handles capture/restore
 * - On resume, locals are automatically injected by V8 setVariableValue
 *
 * This demonstrates the "hybrid" approach:
 * - CPS transform provides step boundaries (each flatMap)
 * - V8 Inspector captures ALL locals at those boundaries
 * - No need to explicitly serialize intermediate values
 */
object V8DurableWorkflow:

  /**
   * Custom step that triggers V8 capture.
   * The external controller will:
   * - On fresh run: capture all locals at the debugger point
   * - On resume: restore saved locals before continuing
   */
  inline def v8Step[A](compute: => A)(using serializer: DurableSerializer[A]): Durable[A] =
    Durable { ctx =>
      // Emit debugger before executing - V8 captures locals here
      debugger()

      // The actual step execution (with caching for replays)
      ctx.executeStep(compute)
    }

  /**
   * Workflow demonstrating V8 auto-capture.
   *
   * Notice: We use regular vals for intermediate results.
   * V8 will capture them automatically - no need for explicit steps!
   */
  def workflow(items: Seq[Int]): Durable[Int] = async[Durable] {
    println(s"[V8Workflow] Starting with ${items.length} items")

    // Local variable - will be auto-captured by V8
    val config = items.length * 10
    println(s"[V8Workflow] Config computed: $config")

    // Step 1: First computation (uses v8Step for capture point)
    val firstResult = await(v8Step {
      println("[V8Workflow] Step 1: Computing first result")
      items.take(items.length / 2).sum * config
    })

    // Another local - will be captured at next v8Step
    val intermediate = firstResult / 2
    println(s"[V8Workflow] After step 1: firstResult=$firstResult, intermediate=$intermediate")

    // Check shutdown condition
    val resumed = await(Durable.isResumed)
    val shouldContinue = if !resumed then
      println("[V8Workflow] Simulating shutdown")
      await(Durable.shutdown)
      false
    else
      true

    if !shouldContinue then
      -1
    else
      // Step 2: Second computation
      val secondResult = await(v8Step {
        println("[V8Workflow] Step 2: Computing second result")
        items.drop(items.length / 2).sum + intermediate
      })
      println(s"[V8Workflow] After step 2: secondResult=$secondResult")

      // Step 3: Final computation
      val total = await(v8Step {
        println("[V8Workflow] Step 3: Final computation")
        firstResult + secondResult
      })

      println(s"[V8Workflow] Final total: $total")
      total
  }

  @JSExportTopLevel("v8DurableWorkflow")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[Int]].toSeq

    // Check if we're resuming
    val snapshotOpt: Option[DurableContextSnapshot] =
      if js.isUndefined(context.snapshot) || context.snapshot == null then
        None
      else
        Some(DurableContextSnapshot.fromJS(context.snapshot))

    // Run or resume
    val result = snapshotOpt match
      case None =>
        println("[V8Workflow] Fresh execution")
        Durable.execute(workflow(items))
      case Some(snap) =>
        println(s"[V8Workflow] Resuming from step ${snap.stepIndex}")
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
