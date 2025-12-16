package durable

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Native platform implementation using TrieMap for thread-safe concurrent access.
 */
trait WorkflowEngineStatePlatform:
  def apply(): WorkflowEngineState =
    new WorkflowEngineState(
      activeMap = TrieMap.empty[WorkflowId, WorkflowRecord],
      runnersMap = TrieMap.empty[WorkflowId, Future[WorkflowSessionResult[?]]],
      timersMap = TrieMap.empty[WorkflowId, TimerHandle]
    )
