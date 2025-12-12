package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.special.debugger
import cps.*
import example.durable.*

/**
 * V8 Durable Workflow - Version 2
 *
 * Key insight: debugger() must be called in the workflow function's scope
 * to capture the workflow's locals, not inside helper functions.
 *
 * This version explicitly places debugger() statements in the workflow.
 */
object V8DurableWorkflow2:

  def workflow(items: Seq[Int]): Durable[Int] = async[Durable] {
    println(s"[V8Workflow2] Starting with ${items.length} items")

    // Local variables - will be captured by V8
    val config = items.length * 10
    println(s"[V8Workflow2] Config: $config")

    // V8 checkpoint BEFORE step 1
    debugger()

    // Step 1
    val firstResult = await(Durable.step {
      println("[V8Workflow2] Step 1: Computing first result")
      items.take(items.length / 2).sum * config
    })

    // Intermediate computation
    val intermediate = firstResult / 2
    println(s"[V8Workflow2] After step 1: firstResult=$firstResult, intermediate=$intermediate")

    // V8 checkpoint BEFORE step 2
    debugger()

    // Check if this is a resumed execution
    val resumed = await(Durable.isResumed)
    if !resumed then
      println("[V8Workflow2] First run - requesting shutdown")
      await(Durable.shutdown)
      -1
    else
      println("[V8Workflow2] Resumed - continuing")

      // Step 2
      val secondResult = await(Durable.step {
        println("[V8Workflow2] Step 2: Computing second result")
        items.drop(items.length / 2).sum + intermediate
      })
      println(s"[V8Workflow2] After step 2: secondResult=$secondResult")

      // V8 checkpoint BEFORE step 3
      debugger()

      // Step 3
      val total = await(Durable.step {
        println("[V8Workflow2] Step 3: Final computation")
        firstResult + secondResult
      })

      println(s"[V8Workflow2] Total: $total")
      total
  }

  @JSExportTopLevel("v8DurableWorkflow2")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[Int]].toSeq

    val snapshotOpt: Option[DurableContextSnapshot] =
      if js.isUndefined(context.snapshot) || context.snapshot == null then
        None
      else
        Some(DurableContextSnapshot.fromJS(context.snapshot))

    val result = snapshotOpt match
      case None =>
        println("[V8Workflow2] Fresh execution")
        Durable.execute(workflow(items))
      case Some(snap) =>
        println(s"[V8Workflow2] Resuming from step ${snap.stepIndex}")
        Durable.resume(workflow(items), snap)

    result match
      case Right(value) =>
        js.Dynamic.literal(status = "completed", result = value)
      case Left(snapshot) =>
        js.Dynamic.literal(status = "shutdown", snapshot = snapshot.toJS)
