package durable

import scala.concurrent.{Future, ExecutionContext}

import durable.runtime.{DurableFunctionRegistry, FunctionRecord}

/**
 * Opaque type for registered function names.
 * Can only be created via DurableFunction.register, ensuring all functions are registered.
 */
opaque type RegisteredDurableFunctionName = String

object RegisteredDurableFunctionName:
  /** Get the underlying function name string */
  extension (name: RegisteredDurableFunctionName)
    def value: String = name

  /** Internal: create from string (only callable from within durable package) */
  private[durable] def apply(name: String): RegisteredDurableFunctionName = name

/**
 * Unified trait for serializable workflow definitions.
 *
 * DurableFunction enables workflow definitions to be stored and restored:
 * - Function identified by name (derived via DurableFunctionName)
 * - Arguments serialized via TupleDurableStorage
 * - On restore: lookup function by name, deserialize args, recreate Durable[R]
 *
 * Implementations must be objects (not classes) for reliable lookup by name.
 * Use `derives DurableFunctionName` and call `DurableFunction.register(this)` to register.
 *
 * Storage typeclasses are captured as trait context parameters at definition time,
 * so only the backend instance needs to be passed at invocation time.
 *
 * @tparam Args tuple of argument types (EmptyTuple for no args, Tuple1[T] for one arg, etc.)
 * @tparam R result type (must have DurableStorage for caching)
 * @tparam S storage backend type - concrete for each workflow implementation
 *
 * Example:
 * {{{
 * import MyBackend.given  // provides storage typeclasses
 *
 * object PaymentWorkflow extends DurableFunction1[String, Payment, MyBackend] derives DurableFunctionName:
 *   override val functionName = DurableFunction.register(this)
 *
 *   override def apply(orderId: String)(using MyBackend): Durable[Payment] =
 *     for
 *       order <- Durable.activity { fetchOrder(orderId) }
 *       payment <- Durable.activity { processPayment(order) }
 *     yield payment
 * }}}
 */
trait DurableFunction[Args <: Tuple, R, S <: DurableStorageBackend](using
  val argsStorage: TupleDurableStorage[Args, S],
  val resultStorage: DurableStorage[R, S]
):
  /** Unique name for this function, used for serialization and registry lookup */
  def functionName: RegisteredDurableFunctionName

  /** Apply the function to arguments as a tuple */
  def applyTupled(args: Args)(using storageBackend: S): Durable[R]

  /**
   * Recreate workflow from stored arguments.
   * Used by engine to resume/recover workflows.
   *
   * @param workflowId The workflow ID to load args from
   * @param storageBackend Storage backend instance
   * @return Future containing the recreated workflow, or None if args not found
   */
  def recreateFromStorage(
    workflowId: WorkflowId,
    storageBackend: S
  )(using ec: ExecutionContext): Future[Option[Durable[R]]] =
    argsStorage.retrieveAll(storageBackend, workflowId, 0).map { argsOpt =>
      argsOpt.map(args => applyTupled(args)(using storageBackend))
    }

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
   *   override val functionName = DurableFunction.register(this)
   *
   *   def apply(count: Int)(using S): Durable[Int] = async[Durable] {
   *     if count <= 0 then
   *       count
   *     else
   *       await(sleep(1.minute))
   *       continueWith(count - 1)
   *   }
   * }}}
   */
  final def continueWith(newArgs: Args)(using storageBackend: S): Durable[R] =
    Durable.continueAs(this)(newArgs)

  /**
   * Continue as a different workflow with new arguments.
   * Clears activity storage and transitions to the target workflow.
   *
   * This enables workflow transitions - switching from one workflow to another.
   * Unlike `continueWith` (which continues as the same workflow), `continueAs`
   * allows transitioning to a completely different workflow.
   *
   * Example:
   * {{{
   * object ProcessingWorkflow extends DurableFunction1[Int, String, S] derives DurableFunctionName:
   *   override val functionName = DurableFunction.register(this)
   *
   *   def apply(n: Int)(using S): Durable[String] = async[Durable] {
   *     if n > threshold then
   *       await(continueAs(CompletionWorkflow)(Tuple1(s"result-$n")))
   *     else
   *       await(continueWith(n + 1))  // continue as self
   *   }
   * }}}
   *
   * @param target The target DurableFunction to continue as
   * @param newArgs Arguments tuple for the target workflow
   * @return Durable[R2] - the result type of the target workflow
   */
  final def continueAs[Args2 <: Tuple, R2](
    target: DurableFunction[Args2, R2, S]
  )(newArgs: Args2)(using storageBackend: S): Durable[R2] =
    Durable.continueAs(target)(newArgs)

/** Trait for 0-argument durable functions */
trait DurableFunction0[R, S <: DurableStorageBackend](using
  argsStorage: TupleDurableStorage[EmptyTuple, S],
  resultStorage: DurableStorage[R, S]
) extends DurableFunction[EmptyTuple, R, S]:
  /** Apply the function with no arguments */
  def apply()(using storageBackend: S): Durable[R]

  override final def applyTupled(args: EmptyTuple)(using storageBackend: S): Durable[R] = apply()

/** Trait for 1-argument durable functions */
trait DurableFunction1[T1, R, S <: DurableStorageBackend](using
  argsStorage: TupleDurableStorage[Tuple1[T1], S],
  resultStorage: DurableStorage[R, S]
) extends DurableFunction[Tuple1[T1], R, S]:
  /** Apply the function with one argument */
  def apply(arg: T1)(using storageBackend: S): Durable[R]

  override final def applyTupled(args: Tuple1[T1])(using storageBackend: S): Durable[R] =
    val Tuple1(arg) = args
    apply(arg)

/** Trait for 2-argument durable functions */
trait DurableFunction2[T1, T2, R, S <: DurableStorageBackend](using
  argsStorage: TupleDurableStorage[(T1, T2), S],
  resultStorage: DurableStorage[R, S]
) extends DurableFunction[(T1, T2), R, S]:
  /** Apply the function with two arguments */
  def apply(arg1: T1, arg2: T2)(using storageBackend: S): Durable[R]

  override final def applyTupled(args: (T1, T2))(using storageBackend: S): Durable[R] =
    val (arg1, arg2) = args
    apply(arg1, arg2)

/** Trait for 3-argument durable functions */
trait DurableFunction3[T1, T2, T3, R, S <: DurableStorageBackend](using
  argsStorage: TupleDurableStorage[(T1, T2, T3), S],
  resultStorage: DurableStorage[R, S]
) extends DurableFunction[(T1, T2, T3), R, S]:
  /** Apply the function with three arguments */
  def apply(arg1: T1, arg2: T2, arg3: T3)(using storageBackend: S): Durable[R]

  override final def applyTupled(args: (T1, T2, T3))(using storageBackend: S): Durable[R] =
    val (arg1, arg2, arg3) = args
    apply(arg1, arg2, arg3)

/** Trait for 4-argument durable functions */
trait DurableFunction4[T1, T2, T3, T4, R, S <: DurableStorageBackend](using
  argsStorage: TupleDurableStorage[(T1, T2, T3, T4), S],
  resultStorage: DurableStorage[R, S]
) extends DurableFunction[(T1, T2, T3, T4), R, S]:
  /** Apply the function with four arguments */
  def apply(arg1: T1, arg2: T2, arg3: T3, arg4: T4)(using storageBackend: S): Durable[R]

  override final def applyTupled(args: (T1, T2, T3, T4))(using storageBackend: S): Durable[R] =
    val (arg1, arg2, arg3, arg4) = args
    apply(arg1, arg2, arg3, arg4)

/**
 * Companion object for DurableFunction providing registration and extension methods.
 */
object DurableFunction:
  /**
   * Register a DurableFunction and return its registered name.
   * This is the only way to create a RegisteredDurableFunctionName,
   * ensuring all functions are properly registered.
   *
   * The storage typeclasses are obtained from the trait's context parameters.
   *
   * Requires:
   * - DurableFunctionName typeclass instance (use `derives DurableFunctionName`)
   *
   * Example:
   * {{{
   * import MyBackend.given
   *
   * object MyWorkflow extends DurableFunction1[String, Int, MyBackend] derives DurableFunctionName:
   *   override val functionName = DurableFunction.register(this)
   *   // ...
   * }}}
   */
  inline def register[Args <: Tuple, R, S <: DurableStorageBackend](
    f: DurableFunction[Args, R, S]
  )(using
    fn: DurableFunctionName[f.type]
  ): RegisteredDurableFunctionName =
    val name = fn.name
    val record = FunctionRecord(f, f.argsStorage, f.resultStorage)
    DurableFunctionRegistry.global.register(name, record)
    RegisteredDurableFunctionName(name)

  // Extension methods for cleaner syntax without explicit Tuple wrapping

  extension [T1, R, S <: DurableStorageBackend](f: DurableFunction1[T1, R, S])
    /** Continue with a single argument (no Tuple1 wrapping needed). */
    def continueWith(arg: T1)(using storageBackend: S): Durable[R] =
      f.continueWith(Tuple1(arg))

    /** Continue as this workflow with a single argument. */
    def continueAs(arg: T1)(using storageBackend: S): Durable[R] =
      Durable.continueAs(f)(Tuple1(arg))

  extension [T1, T2, R, S <: DurableStorageBackend](f: DurableFunction2[T1, T2, R, S])
    /** Continue with two arguments (no tuple wrapping needed). */
    def continueWith(arg1: T1, arg2: T2)(using storageBackend: S): Durable[R] =
      f.continueWith((arg1, arg2))

    /** Continue as this workflow with two arguments. */
    def continueAs(arg1: T1, arg2: T2)(using storageBackend: S): Durable[R] =
      Durable.continueAs(f)((arg1, arg2))

  extension [T1, T2, T3, R, S <: DurableStorageBackend](f: DurableFunction3[T1, T2, T3, R, S])
    /** Continue with three arguments (no tuple wrapping needed). */
    def continueWith(arg1: T1, arg2: T2, arg3: T3)(using storageBackend: S): Durable[R] =
      f.continueWith((arg1, arg2, arg3))

    /** Continue as this workflow with three arguments. */
    def continueAs(arg1: T1, arg2: T2, arg3: T3)(using storageBackend: S): Durable[R] =
      Durable.continueAs(f)((arg1, arg2, arg3))

  extension [T1, T2, T3, T4, R, S <: DurableStorageBackend](f: DurableFunction4[T1, T2, T3, T4, R, S])
    /** Continue with four arguments (no tuple wrapping needed). */
    def continueWith(arg1: T1, arg2: T2, arg3: T3, arg4: T4)(using storageBackend: S): Durable[R] =
      f.continueWith((arg1, arg2, arg3, arg4))

    /** Continue as this workflow with four arguments. */
    def continueAs(arg1: T1, arg2: T2, arg3: T3, arg4: T4)(using storageBackend: S): Durable[R] =
      Durable.continueAs(f)((arg1, arg2, arg3, arg4))
