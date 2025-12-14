package durable

import scala.collection.mutable

/**
 * JS-specific DurableFunctionRegistry implementation.
 * Uses simple mutable.Map - JS is single-threaded so no synchronization needed.
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val functions = mutable.Map.empty[String, DurableFunction[?, ?]]

    def registerByName(name: String, f: DurableFunction[?, ?]): Unit =
      if !functions.contains(name) then
        functions.put(name, f)

    def lookup(name: String): Option[DurableFunction[?, ?]] =
      functions.get(name)

    def registeredNames: Set[String] =
      functions.keySet.toSet

    def size: Int = functions.size
