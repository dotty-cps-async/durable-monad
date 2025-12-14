package durable

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * JVM-specific DurableFunctionRegistry implementation.
 * Uses ConcurrentHashMap for thread-safe operations.
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val functions = new ConcurrentHashMap[String, DurableFunction[?, ?]]()

    def registerByName(name: String, f: DurableFunction[?, ?]): Unit =
      functions.putIfAbsent(name, f)

    def lookup(name: String): Option[DurableFunction[?, ?]] =
      Option(functions.get(name))

    def registeredNames: Set[String] =
      functions.keySet().asScala.toSet

    def size: Int = functions.size()
