package testmonad

import scala.quoted.*
import cps.*

/**
 * Simple preprocessor macro that transforms val definitions.
 * Adds a marker to prove it was called.
 */
object TestPreprocessorMacro:

  // Runtime flag set by preprocessor
  @volatile var preprocessorWasCalled = false

  def impl[A: Type, C <: TestMonad.TestContext: Type](
    body: Expr[A],
    ctx: Expr[C]
  )(using Quotes): Expr[A] =
    import quotes.reflect.*

    // Print at compile time to show macro is running
    report.info(s"TestPreprocessorMacro: preprocessing body of type ${Type.show[A]}")

    // Generate code that sets flag at runtime + evaluates body
    '{
      testmonad.TestPreprocessorMacro.preprocessorWasCalled = true
      $body
    }
