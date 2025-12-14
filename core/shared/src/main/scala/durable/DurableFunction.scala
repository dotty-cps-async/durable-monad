package durable

import scala.concurrent.{Future, ExecutionContext}

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
 * @tparam S storage backend type - concrete for each workflow implementation
 *
 * Example:
 * {{{
 * object PaymentWorkflow extends DurableFunction[Tuple1[String], Payment, MyBackend] derives DurableFunctionName:
 *   override val functionName = DurableFunctionName.ofAndRegister(this)
 *
 *   override def apply(args: Tuple1[String])(using
 *     MyBackend, TupleDurableStorage[Tuple1[String], MyBackend], DurableStorage[Payment, MyBackend]
 *   ): Durable[Payment] =
 *     val Tuple1(orderId) = args
 *     for
 *       order <- Durable.activity { fetchOrder(orderId) }
 *       payment <- Durable.activity { processPayment(order) }
 *     yield payment
 * }}}
 */
trait DurableFunction[Args <: Tuple, R, S <: DurableStorageBackend]:
  /** Unique name for this function, used for serialization and registry lookup */
  def functionName: String

  /** Apply the function to arguments */
  def apply(args: Args)(using
    backend: S,
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  /**
   * Recreate workflow from stored arguments.
   * Used by engine to resume/recover workflows.
   *
   * @param workflowId The workflow ID to load args from
   * @param backend Storage backend instance
   * @param argsStorage Storage typeclass for tuple args
   * @param resultStorage Storage typeclass for result
   * @return Future containing the recreated workflow, or None if args not found
   */
  def recreateFromStorage(
    workflowId: WorkflowId,
    backend: S
  )(using
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S],
    ec: ExecutionContext
  ): Future[Option[Durable[R]]] =
    argsStorage.retrieveAll(backend, workflowId, 0).map { argsOpt =>
      argsOpt.map(args => apply(args)(using backend, argsStorage, resultStorage))
    }

  /**
   * Register this function with its storage typeclass instances.
   * Call this during functionName initialization, with storage givens in scope.
   * Takes name explicitly since functionName may not be assigned yet.
   */
  protected final def registerWith(name: String)(using
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Unit =
    val record = FunctionRecord(this, argsStorage, resultStorage)
    DurableFunctionRegistry.global.register(name, record)

/** Type aliases for common arities (for convenience) */
type DurableFunction0[R, S <: DurableStorageBackend] = DurableFunction[EmptyTuple, R, S]
type DurableFunction1[T1, R, S <: DurableStorageBackend] = DurableFunction[Tuple1[T1], R, S]
type DurableFunction2[T1, T2, R, S <: DurableStorageBackend] = DurableFunction[(T1, T2), R, S]
type DurableFunction3[T1, T2, T3, R, S <: DurableStorageBackend] = DurableFunction[(T1, T2, T3), R, S]
