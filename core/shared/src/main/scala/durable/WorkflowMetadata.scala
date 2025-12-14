package durable

/**
 * Metadata for a workflow instance - used for persistence and restart.
 *
 * Args are stored separately via DurableStorage at indices 0..argCount-1.
 * Activity results start at index argCount.
 *
 * Status is managed separately by the engine, not part of this metadata.
 *
 * @param functionName Fully qualified name of the DurableFunction (for registry lookup)
 * @param argCount Number of arguments (stored at indices 0..argCount-1)
 * @param activityIndex Current activity index (resume point)
 */
case class WorkflowMetadata(
  functionName: String,
  argCount: Int,
  activityIndex: Int
)

/**
 * Workflow execution status - managed by the engine.
 */
enum WorkflowStatus:
  case Running
  case Suspended
  case Completed
  case Failed
