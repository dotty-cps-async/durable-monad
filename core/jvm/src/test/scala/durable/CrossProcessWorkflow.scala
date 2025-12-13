package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import durable.JsonFileStorage.given

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*
import java.nio.file.{Files, Path, Paths}

/**
 * Test workflow for cross-process persistence testing.
 * Does an activity, then suspends waiting for an event.
 */
object CrossProcessWorkflow extends DurableFunction1[String, String] derives DurableFunctionName:
  override val functionName = DurableFunctionName.ofAndRegister(this)

  override def apply(input: String)(using storageT1: DurableStorage[String], storageR: DurableStorage[String]): Durable[String] =
    given DurableStorage[String] = storageR  // re-export for Durable.activity
    for
      // First activity - will be cached
      step1 <- Durable.activity {
        Future.successful(s"Processed: $input")
      }
      // Suspend - waiting for external event
      _ <- Durable.suspend(WaitCondition.Event[String]("continue-signal"))
      // Second activity - only runs after resume
      step2 <- Durable.activity {
        Future.successful(s"$step1 - completed!")
      }
    yield step2

/**
 * Process A: Starts workflow, runs until suspension, saves state.
 *
 * Usage: ProcessA <storageDir> <workflowId> <input>
 */
object ProcessA:
  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val storageDir = Paths.get(args(0))
    val workflowId = WorkflowId(args(1))
    val input = args(2)

    val storage = JsonFileStorage(storageDir)
    given [T: JsonValueCodec]: DurableStorage[T] = storage.forType[T]

    // Force workflow registration by accessing it
    val _ = CrossProcessWorkflow.functionName

    println(s"[ProcessA] Starting workflow $workflowId with input: $input")

    // Create and run workflow
    val workflow = CrossProcessWorkflow.apply(input)
    val ctx = RunContext.fresh(workflowId)

    val result = Await.result(WorkflowRunner.run(workflow, ctx), 30.seconds)

    result match
      case WorkflowResult.Suspended(snapshot, condition) =>
        println(s"[ProcessA] Workflow suspended at index ${snapshot.activityIndex}")
        println(s"[ProcessA] Waiting for: $condition")

        // Save metadata for Process B
        storage.storeMetadata(workflowId, WorkflowMetadata(
          functionName = CrossProcessWorkflow.functionName,
          argTypes = List("java.lang.String"),
          argsJson = writeToString(input),
          activityIndex = snapshot.activityIndex,
          status = WorkflowStatus.Suspended
        ))

        println(s"[ProcessA] Metadata saved. Exiting.")

      case WorkflowResult.Completed(value) =>
        println(s"[ProcessA] Workflow completed unexpectedly: $value")
        sys.exit(1)

      case WorkflowResult.Failed(error) =>
        println(s"[ProcessA] Workflow failed: ${error.getMessage}")
        sys.exit(1)

/**
 * Process B: Restores workflow from storage and continues after providing event.
 *
 * Usage: ProcessB <storageDir> <workflowId> <eventValue>
 */
object ProcessB:
  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val storageDir = Paths.get(args(0))
    val workflowId = WorkflowId(args(1))
    val eventValue = args(2)

    val storage = JsonFileStorage(storageDir)
    given [T: JsonValueCodec]: DurableStorage[T] = storage.forType[T]

    // Force workflow registration by accessing it
    val _ = CrossProcessWorkflow.functionName

    println(s"[ProcessB] Loading workflow $workflowId")

    // Load metadata
    val metadata = storage.loadMetadata(workflowId).getOrElse {
      println(s"[ProcessB] No metadata found for workflow $workflowId")
      sys.exit(1)
      throw new RuntimeException("unreachable")
    }

    println(s"[ProcessB] Found workflow: ${metadata.functionName} at index ${metadata.activityIndex}")

    // Lookup function in registry
    val function = DurableFunctionRegistry.global.lookup(metadata.functionName).getOrElse {
      println(s"[ProcessB] Function not found in registry: ${metadata.functionName}")
      println(s"[ProcessB] Registered functions: ${DurableFunctionRegistry.global.registeredNames}")
      sys.exit(1)
      throw new RuntimeException("unreachable")
    }

    println(s"[ProcessB] Found function in registry: ${function.functionName}")

    // Deserialize args and recreate workflow
    val input = readFromString[String](metadata.argsJson)
    val workflow = function.asInstanceOf[DurableFunction1[String, String]].apply(input)

    // Store the event value at the suspend point index
    // This simulates the event being delivered
    println(s"[ProcessB] Storing event value '$eventValue' at index ${metadata.activityIndex}")
    Await.result(
      summon[DurableStorage[String]].store(workflowId, metadata.activityIndex, eventValue),
      5.seconds
    )

    // Resume from saved index + 1 (past the suspend point)
    val ctx = RunContext(workflowId, resumeFromIndex = metadata.activityIndex + 1)

    println(s"[ProcessB] Resuming workflow from index ${metadata.activityIndex + 1}")

    val result = Await.result(WorkflowRunner.run(workflow, ctx), 30.seconds)

    result match
      case WorkflowResult.Completed(value) =>
        println(s"[ProcessB] Workflow completed: $value")

        // Update metadata
        storage.storeMetadata(workflowId, metadata.copy(
          status = WorkflowStatus.Completed
        ))

      case WorkflowResult.Suspended(snapshot, condition) =>
        println(s"[ProcessB] Workflow suspended again at index ${snapshot.activityIndex}")
        sys.exit(1)

      case WorkflowResult.Failed(error) =>
        println(s"[ProcessB] Workflow failed: ${error.getMessage}")
        error.printStackTrace()
        sys.exit(1)
