package durable

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
 * JVM platform implementation using TrieMap for thread-safe concurrent access.
 */
trait MemoryBackingStorePlatform:
  def apply(): MemoryBackingStore =
    new MemoryBackingStore(
      activityStore = TrieMap.empty[(WorkflowId, Int), Either[StoredFailure, Any]],
      workflowRecords = TrieMap.empty[WorkflowId, WorkflowRecord],
      pendingEvents = TrieMap.empty[String, mutable.ArrayBuffer[PendingEvent[Any]]],
      workflowPendingEvents = TrieMap.empty[WorkflowId, mutable.Map[String, mutable.ArrayBuffer[PendingEvent[Any]]]],
      deadLetterEvents = TrieMap.empty[String, mutable.ArrayBuffer[DeadEvent[Any]]],
      resultStore = TrieMap.empty[WorkflowId, Any],
      winningConditions = TrieMap.empty[(WorkflowId, Int), SingleEventQuery[?]]
    )
