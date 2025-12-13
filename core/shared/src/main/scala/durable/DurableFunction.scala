package durable

/**
 * Base trait for serializable workflow definitions.
 *
 * DurableFunction enables workflow definitions to be stored and restored:
 * - Function identified by name (derived via DurableFunctionName)
 * - Arguments serialized via DurableStorage
 * - On restore: lookup function by name, deserialize args, recreate Durable[R]
 *
 * Implementations must be objects (not classes) for reliable lookup by name.
 * Use `derives DurableFunctionName` and override `functionName` to enable auto-registration.
 *
 * Example:
 * {{{
 * object PaymentWorkflow extends DurableFunction1[String, Payment] derives DurableFunctionName:
 *   override val functionName = DurableFunctionName.of[PaymentWorkflow.type]
 *
 *   override def apply(orderId: String): Durable[Payment] =
 *     for
 *       order <- Durable.activity { fetchOrder(orderId) }
 *       payment <- Durable.activity { processPayment(order) }
 *     yield payment
 * }}}
 */
sealed trait DurableFunction:
  /** Unique name for this function, used for serialization and registry lookup */
  def functionName: String

  /**
   * Force registration of this function. Call this after functionName is initialized.
   * Registration happens automatically when this method is called.
   */
  protected final lazy val _registered: Boolean = {
    DurableFunctionRegistry.global.registerByName(functionName, this)
    true
  }

/**
 * Workflow with no arguments.
 *
 * @tparam R result type (must have DurableStorage for caching)
 */
trait DurableFunction0[R] extends DurableFunction:
  def apply()(using storageR: DurableStorage[R]): Durable[R]

/**
 * Workflow with one argument.
 *
 * @tparam T1 first argument type (must have DurableStorage for serialization)
 * @tparam R result type (must have DurableStorage for caching)
 */
trait DurableFunction1[T1, R] extends DurableFunction:
  def apply(t1: T1)(using storageT1: DurableStorage[T1], storageR: DurableStorage[R]): Durable[R]

/**
 * Workflow with two arguments.
 *
 * @tparam T1 first argument type (must have DurableStorage for serialization)
 * @tparam T2 second argument type (must have DurableStorage for serialization)
 * @tparam R result type (must have DurableStorage for caching)
 */
trait DurableFunction2[T1, T2, R] extends DurableFunction:
  def apply(t1: T1, t2: T2)(using storageT1: DurableStorage[T1], storageT2: DurableStorage[T2], storageR: DurableStorage[R]): Durable[R]

/**
 * Workflow with three arguments.
 *
 * @tparam T1 first argument type (must have DurableStorage for serialization)
 * @tparam T2 second argument type (must have DurableStorage for serialization)
 * @tparam T3 third argument type (must have DurableStorage for serialization)
 * @tparam R result type (must have DurableStorage for caching)
 */
trait DurableFunction3[T1, T2, T3, R] extends DurableFunction:
  def apply(t1: T1, t2: T2, t3: T3)(using storageT1: DurableStorage[T1], storageT2: DurableStorage[T2], storageT3: DurableStorage[T3], storageR: DurableStorage[R]): Durable[R]
