package durable

import scala.quoted.*

/**
 * Typeclass providing unique name for a DurableFunction.
 * Used for serialization and lookup in registry.
 *
 * Derivable via `derives DurableFunctionName` on object definitions.
 * Uses macro to extract the object's fully qualified type name at compile time.
 */
trait DurableFunctionName[F <: DurableFunction[?, ?, ?]]:
  def name: String

object DurableFunctionName:
  /** Create a DurableFunctionName with explicit name */
  def apply[F <: DurableFunction[?, ?, ?]](n: String): DurableFunctionName[F] =
    new DurableFunctionName[F]:
      def name: String = n

  /** Derive name from object's fully qualified type name using macro */
  inline def derived[F <: DurableFunction[?, ?, ?]]: DurableFunctionName[F] =
    ${ derivedImpl[F] }

  private def derivedImpl[F <: DurableFunction[?, ?, ?]: Type](using Quotes): Expr[DurableFunctionName[F]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[F]
    // Get full name, remove trailing $ for objects
    val fullName = tpe.typeSymbol.fullName.stripSuffix("$")
    '{ apply(${ Expr(fullName) }) }
