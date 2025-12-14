package durable

import scala.collection.mutable

/**
 * JS-specific DurableFunctionRegistry implementation.
 * Uses simple mutable.Map - JS is single-threaded so no synchronization needed.
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val records = mutable.Map.empty[String, FunctionRecord]

    def register(name: String, record: FunctionRecord): Unit =
      if !records.contains(name) then
        records.put(name, record)

    def lookup(name: String): Option[FunctionRecord] =
      records.get(name)

    def registeredNames: Set[String] =
      records.keySet.toSet

    def size: Int = records.size
