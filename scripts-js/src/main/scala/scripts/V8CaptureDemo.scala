package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.special.debugger

/**
 * Demo of V8 Inspector capture from Scala.js.
 * The script just emits debugger statements - the external wrapper captures locals.
 */
object V8CaptureDemo:

  @JSExportTopLevel("v8CaptureDemo")
  def main(context: js.Dynamic): js.Dynamic =
    println("=== V8 Capture Demo (Scala.js) ===")
    println("This script uses debugger() statements at checkpoints.")
    println("Run with run-v8-capture.mjs to capture locals.")

    // Run a simple workflow with debugger statements
    val result = workflow()

    js.Dynamic.literal(
      status = "completed",
      result = result
    )

  def workflow(): Int =
    var a = 10
    var b = 20

    println(s"\nBefore first checkpoint: a=$a, b=$b")
    debugger() // Checkpoint 1 - capture {a, b}

    val c = a + b
    println(s"After first checkpoint: c=$c (a+b)")
    debugger() // Checkpoint 2 - capture {a, b, c}

    val d = c * 2
    println(s"After second checkpoint: d=$d (c*2)")
    debugger() // Checkpoint 3 - capture {a, b, c, d}

    d
