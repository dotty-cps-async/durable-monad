package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*

/**
 * Example isolated Scala script that processes data.
 * Demonstrates passing complex data structures to/from VM context.
 */
object DataProcessor:

  /**
   * Entry point called by ScalaVM.
   * Expects context with: items (Array of objects with 'value' field)
   * Returns: object with sum, avg, max, min
   */
  @JSExportTopLevel("dataProcessor")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[js.Dynamic]]

    println(s"[DataProcessor] Processing ${items.length} items")

    val values = items.map(_.value.asInstanceOf[Double]).toSeq

    if values.isEmpty then
      js.Dynamic.literal(
        sum = 0,
        avg = 0,
        max = 0,
        min = 0,
        count = 0
      )
    else
      val sum = values.sum
      val avg = sum / values.length
      val max = values.max
      val min = values.min

      println(s"[DataProcessor] Results: sum=$sum, avg=$avg, max=$max, min=$min")

      js.Dynamic.literal(
        sum = sum,
        avg = avg,
        max = max,
        min = min,
        count = values.length
      )
