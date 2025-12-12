package scripts

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*

/**
 * Example script using cats-effect.
 * Demonstrates that external libraries work in isolated VM contexts.
 */
object CatsEffectScript:

  @JSExportTopLevel("catsEffectScript")
  def main(context: js.Dynamic): js.Any =
    val items = context.items.asInstanceOf[js.Array[Int]].toSeq

    // Use cats-effect IO for pure functional programming
    val program: IO[Int] = for
      _ <- IO.println(s"[CatsEffectScript] Processing ${items.length} items with cats-effect IO")
      results <- items.traverse { item =>
        IO.println(s"[CatsEffectScript] Processing item: $item") *>
        IO.pure(item * 2)
      }
      sum <- IO.pure(results.sum)
      _ <- IO.println(s"[CatsEffectScript] Sum of doubled values: $sum")
    yield sum

    // On JS, we need to use unsafeRunAndForget or handle via callback
    // For synchronous result, we use a mutable variable with unsafeRunAsync
    var result: Int = 0
    program.unsafeRunAsync {
      case Right(value) => result = value
      case Left(error) => throw error
    }
    // Note: This works because IO with println and pure is synchronous
    result
