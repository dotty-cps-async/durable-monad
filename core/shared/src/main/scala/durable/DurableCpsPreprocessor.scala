package durable

import cps.*

/**
 * CpsPreprocessor instance for Durable monad.
 *
 * Automatically wraps val definitions with activity calls
 * for replay-based execution.
 *
 * Usage:
 *   import durable.DurableCpsPreprocessor.given
 *
 *   async[[A] =>> Durable[A, MyStorage]] {
 *     val x = compute()        // automatically wrapped with activity
 *     val y = await(external)  // await expressions preserved
 *     x + y
 *   }
 *
 * Transformation:
 *   val x = expr  →  val x = await(ctx.activitySync { expr })
 */
object DurableCpsPreprocessor:

  /**
   * CpsPreprocessor for Durable with fixed storage type S.
   * Context type is DurableCpsContext[S].
   */
  given durablePreprocessor[S, C <: Durable.DurableCpsContext[S]]: CpsPreprocessor[[A] =>> Durable[A, S], C] with
    transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
      ${ DurablePreprocessor.impl[A, S, C]('body, 'ctx) }
