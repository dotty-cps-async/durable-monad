package durable.engine

import durable.*

/**
 * Fused operations that combine multiple ops into one for efficiency.
 */
enum FusedOp:
  case Single(op: CoordinatorOp[?])
  case BatchDeliverEvents(workflowId: WorkflowId, events: Seq[CoordinatorOp.SendTargetedEvent])
  case SuspendWithImmediateEvent(suspend: CoordinatorOp.SuspendAndCheckPending, event: CoordinatorOp.SendBroadcastEvent)

/**
 * Analyze batch of operations and fuse where possible.
 *
 * Fusion optimizations:
 * 1. Multiple targeted events to same suspended workflow -> batch delivery
 * 2. Suspend + immediate pending event -> direct delivery (skip suspend state)
 */
object CoordinatorOpFusion:
  /**
   * Analyze batch of operations and fuse where possible.
   * Returns sequence of FusedOp that maintains semantics but may combine operations.
   */
  def fuse(ops: Seq[CoordinatorOp[?]]): Seq[FusedOp] =
    if ops.size <= 1 then
      ops.map(FusedOp.Single(_))
    else
      // Group by target workflow for potential fusion
      val grouped = ops.groupBy(targetWorkflow)
      grouped.flatMap { case (workflowIdOpt, opsForWorkflow) =>
        fuseForWorkflow(workflowIdOpt, opsForWorkflow)
      }.toSeq

  private def targetWorkflow(op: CoordinatorOp[?]): Option[WorkflowId] =
    op match
      case CoordinatorOp.RegisterWorkflow(id, _) => Some(id)
      case CoordinatorOp.RegisterRunner(id, _) => Some(id)
      case CoordinatorOp.RegisterTimer(id, _) => Some(id)
      case CoordinatorOp.SuspendAndCheckPending(id, _, _, _) => Some(id)
      case CoordinatorOp.SendBroadcastEvent(_, _, _, _, _) => None
      case CoordinatorOp.SendTargetedEvent(id, _, _, _, _, _, _) => Some(id)
      case CoordinatorOp.HandleTimerFired(id, _, _, _) => Some(id)
      case CoordinatorOp.MarkFinished(id) => Some(id)
      case CoordinatorOp.CancelWorkflow(id) => Some(id)
      case CoordinatorOp.UpdateForContinueAs(id, _) => Some(id)
      case CoordinatorOp.RecoverWorkflows(_) => None
      case CoordinatorOp.CancelAllTimers() => None
      case CoordinatorOp.Shutdown() => None
      // Lazy loading operations
      case CoordinatorOp.EnsureLoaded(id) => Some(id)
      case CoordinatorOp.EvictFromCache(_) => None
      case CoordinatorOp.EvictByTtl(_) => None
      case CoordinatorOp.TouchWorkflow(id) => Some(id)

  private def fuseForWorkflow(workflowIdOpt: Option[WorkflowId], ops: Seq[CoordinatorOp[?]]): Seq[FusedOp] =
    workflowIdOpt match
      case Some(workflowId) =>
        // Try to batch multiple targeted events to same workflow
        val targetedEvents = ops.collect { case e: CoordinatorOp.SendTargetedEvent if e.targetWorkflowId == workflowId => e }
        if targetedEvents.size > 1 then
          val nonEvents = ops.filterNot(targetedEvents.contains)
          nonEvents.map(FusedOp.Single(_)) :+ FusedOp.BatchDeliverEvents(workflowId, targetedEvents)
        else
          ops.map(FusedOp.Single(_))

      case None =>
        // Check for Suspend + immediate broadcast event pattern
        val suspends = ops.collect { case s: CoordinatorOp.SuspendAndCheckPending => s }
        val broadcasts = ops.collect { case b: CoordinatorOp.SendBroadcastEvent => b }

        if suspends.size == 1 && broadcasts.size == 1 then
          val suspend = suspends.head
          val broadcast = broadcasts.head
          if suspend.condition.eventNames.contains(broadcast.eventName) then
            val others = ops.filterNot(op => op == suspend || op == broadcast)
            others.map(FusedOp.Single(_)) :+ FusedOp.SuspendWithImmediateEvent(suspend, broadcast)
          else
            ops.map(FusedOp.Single(_))
        else
          ops.map(FusedOp.Single(_))
