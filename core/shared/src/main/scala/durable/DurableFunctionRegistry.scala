package durable

/**
 * Registry for looking up DurableFunction by name.
 * Functions auto-register when their object initializes (via functionName access).
 *
 * Platform-specific implementations provide thread-safety where needed:
 * - JVM/Native: thread-safe using ConcurrentHashMap or synchronized
 * - JS: simple Map (single-threaded)
 */
trait DurableFunctionRegistry:
  def registerByName(name: String, f: DurableFunction): Unit

  def lookup(name: String): Option[DurableFunction]

  def lookupTyped[F <: DurableFunction](name: String): Option[F] =
    lookup(name).map(_.asInstanceOf[F])

  /** All registered function names */
  def registeredNames: Set[String]

  /** Number of registered functions */
  def size: Int

object DurableFunctionRegistry extends DurableFunctionRegistryPlatform:
  /** Global default registry - platform-specific implementation */
  val global: DurableFunctionRegistry = createRegistry()
