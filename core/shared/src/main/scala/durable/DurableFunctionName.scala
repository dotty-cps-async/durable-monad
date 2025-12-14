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

  /** Helper to get name from derived given */
  inline def of[F <: DurableFunction[?, ?, ?]](using fn: DurableFunctionName[F]): String = fn.name

  /**
   * Get name and register the function in the global registry.
   * Requires storage typeclass instances in scope.
   */
  inline def ofAndRegister[Args <: Tuple, R, S <: DurableStorageBackend](
    f: DurableFunction[Args, R, S]
  )(using
    fn: DurableFunctionName[f.type],
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): String =
    val name = fn.name
    val record = FunctionRecord(f, argsStorage, resultStorage)
    DurableFunctionRegistry.global.register(name, record)
    name

  /** Derive name from object's fully qualified type name using macro */
  inline def derived[F <: DurableFunction[?, ?, ?]]: DurableFunctionName[F] =
    ${ derivedImpl[F] }

  private def derivedImpl[F <: DurableFunction[?, ?, ?]: Type](using Quotes): Expr[DurableFunctionName[F]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[F]
    // Get full name, remove trailing $ for objects
    val fullName = tpe.typeSymbol.fullName.stripSuffix("$")
    '{ apply(${ Expr(fullName) }) }
