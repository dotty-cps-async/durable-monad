package durable

/**
 * Record stored in the registry for each DurableFunction.
 * Contains function and storage typeclass instances for recreation.
 *
 * The storage typeclasses are pure (stateless) - they can be used with any backend instance.
 */
case class FunctionRecord(
  function: DurableFunction[?, ?, ?],
  argsStorage: TupleDurableStorage[?, ?],
  resultStorage: DurableStorage[?, ?]
):
  /** Get typed args storage (unsafe cast) */
  def argsStorageTyped[Args <: Tuple, S <: DurableStorageBackend]: TupleDurableStorage[Args, S] =
    argsStorage.asInstanceOf[TupleDurableStorage[Args, S]]

  /** Get typed result storage (unsafe cast) */
  def resultStorageTyped[R, S <: DurableStorageBackend]: DurableStorage[R, S] =
    resultStorage.asInstanceOf[DurableStorage[R, S]]

  /** Get typed function (unsafe cast) */
  def functionTyped[Args <: Tuple, R, S <: DurableStorageBackend]: DurableFunction[Args, R, S] =
    function.asInstanceOf[DurableFunction[Args, R, S]]

/**
 * Registry for looking up DurableFunction by name.
 * Functions auto-register when their object initializes (via functionName access).
 *
 * Stores FunctionRecord containing function + storage typeclass instances.
 * Storage typeclasses are pure (stateless) - they can be summoned at registration time
 * without a backend instance and used with any backend instance at runtime.
 *
 * Platform-specific implementations provide thread-safety where needed:
 * - JVM/Native: thread-safe using ConcurrentHashMap or synchronized
 * - JS: simple Map (single-threaded)
 */
trait DurableFunctionRegistry:
  /** Register a function with its storage typeclasses */
  def register(name: String, record: FunctionRecord): Unit

  /** Lookup function record by name */
  def lookup(name: String): Option[FunctionRecord]

  /** Lookup typed function (convenience - unsafe cast) */
  def lookupTyped[Args <: Tuple, R, S <: DurableStorageBackend](name: String): Option[DurableFunction[Args, R, S]] =
    lookup(name).map(_.function.asInstanceOf[DurableFunction[Args, R, S]])

  /** All registered function names */
  def registeredNames: Set[String]

  /** Number of registered functions */
  def size: Int

object DurableFunctionRegistry extends DurableFunctionRegistryPlatform:
  /** Global default registry - platform-specific implementation */
  val global: DurableFunctionRegistry = createRegistry()
