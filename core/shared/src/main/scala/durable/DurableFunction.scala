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
 * object PaymentWorkflow extends DurableFunction1[String, Payment, MyBackend] derives DurableFunctionName:
 *   override val functionName = DurableFunctionName.ofAndRegister(this)
 *
 *   override def apply(orderId: String)(using
 *     MyBackend, TupleDurableStorage[Tuple1[String], MyBackend], DurableStorage[Payment, MyBackend]
 *   ): Durable[Payment] =
 *     for
 *       order <- Durable.activity { fetchOrder(orderId) }
 *       payment <- Durable.activity { processPayment(order) }
 *     yield payment
 * }}}
 */
trait DurableFunction[Args <: Tuple, R, S <: DurableStorageBackend]:
  /** Unique name for this function, used for serialization and registry lookup */
  def functionName: String

  /** Apply the function to arguments as a tuple */
  def applyTupled(args: Args)(using
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
      argsOpt.map(args => applyTupled(args)(using backend, argsStorage, resultStorage))
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

  /**
   * Continue as a new invocation of this workflow with new arguments.
   * Clears activity storage and restarts with the new args.
   *
   * This is the recommended pattern for loops in durable workflows:
   * each iteration becomes a new workflow run, preventing unbounded
   * history growth.
   *
   * Example:
   * {{{
   * object CountdownWorkflow extends DurableFunction1[Int, Int, S] derives DurableFunctionName:
   *   override val functionName = DurableFunctionName.ofAndRegister(this)
   *
   *   def apply(count: Int)(using ...): Durable[Int] = async[Durable] {
   *     if count <= 0 then
   *       count
   *     else
   *       await(sleep(1.minute))
   *       continueWith(count - 1)
   *   }
   * }}}
   */
  final def continueWith(newArgs: Args)(using
    backend: S,
    argsStorage: TupleDurableStorage[Args, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R] =
    Durable.continueAs(functionName, newArgs, applyTupled(newArgs))

/** Trait for 0-argument durable functions */
trait DurableFunction0[R, S <: DurableStorageBackend] extends DurableFunction[EmptyTuple, R, S]:
  /** Apply the function with no arguments */
  def apply()(using
    backend: S,
    argsStorage: TupleDurableStorage[EmptyTuple, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  override final def applyTupled(args: EmptyTuple)(using
    backend: S,
    argsStorage: TupleDurableStorage[EmptyTuple, S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R] = apply()

/** Trait for 1-argument durable functions */
trait DurableFunction1[T1, R, S <: DurableStorageBackend] extends DurableFunction[Tuple1[T1], R, S]:
  /** Apply the function with one argument */
  def apply(arg: T1)(using
    backend: S,
    argsStorage: TupleDurableStorage[Tuple1[T1], S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  override final def applyTupled(args: Tuple1[T1])(using
    backend: S,
    argsStorage: TupleDurableStorage[Tuple1[T1], S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R] =
    val Tuple1(arg) = args
    apply(arg)

/** Trait for 2-argument durable functions */
trait DurableFunction2[T1, T2, R, S <: DurableStorageBackend] extends DurableFunction[(T1, T2), R, S]:
  /** Apply the function with two arguments */
  def apply(arg1: T1, arg2: T2)(using
    backend: S,
    argsStorage: TupleDurableStorage[(T1, T2), S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  override final def applyTupled(args: (T1, T2))(using
    backend: S,
    argsStorage: TupleDurableStorage[(T1, T2), S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R] =
    val (arg1, arg2) = args
    apply(arg1, arg2)

/** Trait for 3-argument durable functions */
trait DurableFunction3[T1, T2, T3, R, S <: DurableStorageBackend] extends DurableFunction[(T1, T2, T3), R, S]:
  /** Apply the function with three arguments */
  def apply(arg1: T1, arg2: T2, arg3: T3)(using
    backend: S,
    argsStorage: TupleDurableStorage[(T1, T2, T3), S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R]

  override final def applyTupled(args: (T1, T2, T3))(using
    backend: S,
    argsStorage: TupleDurableStorage[(T1, T2, T3), S],
    resultStorage: DurableStorage[R, S]
  ): Durable[R] =
    val (arg1, arg2, arg3) = args
    apply(arg1, arg2, arg3)

/**
 * Extension methods for DurableFunction with common arities.
 * Provides cleaner syntax without explicit Tuple wrapping.
 */
object DurableFunctionSyntax:
  extension [T1, R, S <: DurableStorageBackend](f: DurableFunction1[T1, R, S])
    /**
     * Continue with a single argument (no Tuple1 wrapping needed).
     *
     * Example:
     * {{{
     * import DurableFunctionSyntax.*
     *
     * object CountdownWorkflow extends DurableFunction1[Int, Int, S]:
     *   def apply(args: Tuple1[Int])(using ...): Durable[Int] = async[Durable] {
     *     val Tuple1(count) = args
     *     if count <= 0 then count
     *     else continueWith(count - 1)  // no Tuple1() needed
     *   }
     * }}}
     */
    def continueWith(arg: T1)(using
      backend: S,
      argsStorage: TupleDurableStorage[Tuple1[T1], S],
      resultStorage: DurableStorage[R, S]
    ): Durable[R] =
      f.continueWith(Tuple1(arg))

  extension [T1, T2, R, S <: DurableStorageBackend](f: DurableFunction2[T1, T2, R, S])
    /**
     * Continue with two arguments (no tuple wrapping needed).
     */
    def continueWith(arg1: T1, arg2: T2)(using
      backend: S,
      argsStorage: TupleDurableStorage[(T1, T2), S],
      resultStorage: DurableStorage[R, S]
    ): Durable[R] =
      f.continueWith((arg1, arg2))

  extension [T1, T2, T3, R, S <: DurableStorageBackend](f: DurableFunction3[T1, T2, T3, R, S])
    /**
     * Continue with three arguments (no tuple wrapping needed).
     */
    def continueWith(arg1: T1, arg2: T2, arg3: T3)(using
      backend: S,
      argsStorage: TupleDurableStorage[(T1, T2, T3), S],
      resultStorage: DurableStorage[R, S]
    ): Durable[R] =
      f.continueWith((arg1, arg2, arg3))
