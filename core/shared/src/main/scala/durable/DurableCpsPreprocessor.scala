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
 *   import cpsembed.syntax.appcontext.*
 *
 *   given MemoryBackingStore = AppContext[MemoryBackingStore]
 *   given [T]: DurableStorage[T] = summon[MemoryBackingStore].forType[T]
 *   async[Durable] {
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
   * CpsPreprocessor for Durable monad.
   * Context type is DurableCpsContext.
   * DurableStorage[T] is resolved via normal given resolution.
   */
  given durablePreprocessor[C <: Durable.DurableCpsContext]: CpsPreprocessor[Durable, C] with
    transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
      ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }
