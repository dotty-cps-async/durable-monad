package durable.runtime

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * JVM-specific DurableFunctionRegistry implementation.
 * Uses ConcurrentHashMap for thread-safe operations.
 */
trait DurableFunctionRegistryPlatform:
  def createRegistry(): DurableFunctionRegistry = new DurableFunctionRegistry:
    private val records = new ConcurrentHashMap[String, FunctionRecord]()

    def register(name: String, record: FunctionRecord): Unit =
      records.putIfAbsent(name, record)

    def lookup(name: String): Option[FunctionRecord] =
      Option(records.get(name))

    def registeredNames: Set[String] =
      records.keySet().asScala.toSet

    def size: Int = records.size()
