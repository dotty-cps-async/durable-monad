package durable

/**
 * Unified trait for serializable workflow definitions.
 *
 * DurableFunction enables workflow definitions to be stored and restored:
 * - Function identified by name (derived via DurableFunctionName)
 * - Arguments serialized via TupleDurableStorage
 * - On restore: lookup function by name, deserialize args, recreate Durable[R]
 *
 * Implementations must be objects (not classes) for reliable lookup by name.
 * Use `derives DurableFunctionName` and override `functionName` to enable auto-registration.
 *
 * @tparam Args tuple of argument types (EmptyTuple for no args, Tuple1[T] for one arg, etc.)
 * @tparam R result type (must have DurableStorage for caching)
 *
 * Example:
 * {{{
 * object PaymentWorkflow extends DurableFunction[Tuple1[String], Payment] derives DurableFunctionName:
 *   override val functionName = DurableFunctionName.ofAndRegister(this)
 *
 *   override def apply[S <: DurableStorageBackend](args: Tuple1[String])(using
 *     S, TupleDurableStorage[Tuple1[String], S], DurableStorage[Payment, S]
 *   ): Durable[Payment] =
 *     val Tuple1(orderId) = args
 *     for
 *       order <- Durable.activity { fetchOrder(orderId) }
 *       payment <- Durable.activity { processPayment(order) }
 *     yield payment
 * }}}
 */
trait DurableFunction[Args <: Tuple, R]:
  /** Unique name for this function, used for serialization and registry lookup */
  def functionName: String

  /** Apply the function to arguments */
  def apply[S <: DurableStorageBackend](args: Args)(using
    backend: S,
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  /**
   * Force registration of this function. Call this after functionName is initialized.
   * Registration happens automatically when this method is called.
   */
  protected final lazy val _registered: Boolean = {
    DurableFunctionRegistry.global.registerByName(functionName, this)
    true
  }

/** Type aliases for common arities (for convenience) */
type DurableFunction0[R] = DurableFunction[EmptyTuple, R]
type DurableFunction1[T1, R] = DurableFunction[Tuple1[T1], R]
type DurableFunction2[T1, T2, R] = DurableFunction[(T1, T2), R]
type DurableFunction3[T1, T2, T3, R] = DurableFunction[(T1, T2, T3), R]
