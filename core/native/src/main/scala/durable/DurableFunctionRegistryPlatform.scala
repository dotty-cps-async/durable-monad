package durable

import scala.collection.concurrent.TrieMap

/**
 * Native-specific DurableFunctionRegistry implementation.
 * Uses TrieMap for thread-safe operations (pure Scala, works on Native).
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val records = TrieMap.empty[String, FunctionRecord]

    def register(name: String, record: FunctionRecord): Unit =
      records.putIfAbsent(name, record)

    def lookup(name: String): Option[FunctionRecord] =
      records.get(name)

    def registeredNames: Set[String] =
      records.keySet.toSet

    def size: Int = records.size
