package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Example isolated Scala script that manipulates a counter.
 * This script runs in a VM context with no access to main app globals.
 */
object CounterScript:

  /**
   * Entry point called by ScalaVM.
   * Expects context with: initialValue (Int)
   * Returns: final counter value
   */
  @JSExportTopLevel("counterScript")
  def main(context: js.Dynamic): js.Any =
    val initial = context.initialValue.asInstanceOf[Int]
    val times = context.times.asInstanceOf[js.UndefOr[Int]].getOrElse(10)

    println(s"[CounterScript] Starting with initial=$initial, times=$times")

    var counter = initial
    for i <- 1 to times do
      counter = counter * 2
      println(s"[CounterScript] Iteration $i: counter = $counter")

    println(s"[CounterScript] Final result: $counter")
    counter
