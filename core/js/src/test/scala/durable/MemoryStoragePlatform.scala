package durable

import scala.collection.mutable

/**
 * JS platform implementation using mutable.HashMap (single-threaded, no concurrency needed).
 */
trait MemoryBackingStorePlatform:
  def apply(): MemoryBackingStore =
    new MemoryBackingStore(
      activityStore = mutable.HashMap.empty[(WorkflowId, Int), Either[StoredFailure, Any]],
      workflowRecords = mutable.HashMap.empty[WorkflowId, WorkflowRecord],
      pendingEvents = mutable.HashMap.empty[String, mutable.ArrayBuffer[PendingEvent[Any]]]
    )
