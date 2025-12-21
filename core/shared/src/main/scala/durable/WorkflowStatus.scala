package durable

/**
 * Workflow execution status - managed by the engine.
 *
 * Terminal states:
 *   - Succeeded: finished with result value
 *   - Failed: finished with error/exception
 *   - Cancelled: externally cancelled
 */
enum WorkflowStatus:
  case Running
  case Suspended
  case Succeeded   // finished with result
  case Failed      // finished with error
  case Cancelled   // externally cancelled

  /** Check if this is a terminal state (no more transitions possible) */
  def isTerminal: Boolean = this match
    case Succeeded | Failed | Cancelled => true
    case Running | Suspended => false
