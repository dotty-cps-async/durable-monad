package durable

import scala.collection.concurrent.TrieMap

/**
 * Native-specific DurableFunctionRegistry implementation.
 * Uses TrieMap for thread-safe operations (pure Scala, works on Native).
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val functions = TrieMap.empty[String, DurableFunction]

    def registerByName(name: String, f: DurableFunction): Unit =
      functions.putIfAbsent(name, f)

    def lookup(name: String): Option[DurableFunction] =
      functions.get(name)

    def registeredNames: Set[String] =
      functions.keySet.toSet

    def size: Int = functions.size
