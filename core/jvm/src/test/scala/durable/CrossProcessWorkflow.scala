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
 * Uses JsonFileStorage as the concrete backend type.
 */
object CrossProcessWorkflow extends DurableFunction[Tuple1[String], String, JsonFileStorage] derives DurableFunctionName:
  import JsonFileStorage.given

  override val functionName: String = DurableFunctionName.ofAndRegister(this)

  override def apply(args: Tuple1[String])(using
    backend: JsonFileStorage,
    argsStorage: TupleDurableStorage[Tuple1[String], JsonFileStorage],
    resultStorage: DurableStorage[String, JsonFileStorage]
  ): Durable[String] =
    // Event name for this workflow
    given DurableEventName[String] = DurableEventName("continue-signal")
    val Tuple1(input) = args
    for
      // First activity - will be cached
      step1 <- Durable.activity {
        Future.successful(s"Processed: $input")
      }
      // Suspend - waiting for external event (uses resultStorage since event is String)
      _ <- Durable.awaitEvent[String, JsonFileStorage]
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
    given JsonFileStorage = storage
    given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorage] = storage.forType[T]

    // Force workflow registration by accessing it
    val _ = CrossProcessWorkflow.functionName

    println(s"[ProcessA] Starting workflow $workflowId with input: $input")

    // Create and run workflow
    val workflow = CrossProcessWorkflow.apply(Tuple1(input))
    val ctx = RunContext.fresh(workflowId)

    val result = Await.result(WorkflowRunner.run(workflow, ctx), 30.seconds)

    result match
      case WorkflowResult.Suspended(snapshot, condition) =>
        println(s"[ProcessA] Workflow suspended at index ${snapshot.activityIndex}")
        println(s"[ProcessA] Waiting for: $condition")

        // Save metadata for Process B
        storage.storeMetadata(workflowId, JsonWorkflowMetadata(
          functionName = CrossProcessWorkflow.functionName,
          argTypes = List("java.lang.String"),
          argsJson = writeToString(input)(using JsonFileStorage.given_JsonValueCodec_String),
          activityIndex = snapshot.activityIndex,
          status = JsonWorkflowStatus.Suspended
        ))

        println(s"[ProcessA] Metadata saved. Exiting.")

      case WorkflowResult.Completed(value) =>
        println(s"[ProcessA] Workflow completed unexpectedly: $value")
        sys.exit(1)

      case WorkflowResult.Failed(error) =>
        println(s"[ProcessA] Workflow failed: ${error.getMessage}")
        sys.exit(1)

      case WorkflowResult.ContinueAs(_, _, _) =>
        println(s"[ProcessA] Workflow requested continueAs unexpectedly")
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
    given JsonFileStorage = storage
    given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorage] = storage.forType[T]

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

    println(s"[ProcessB] Found function in registry: ${function.function.functionName}")

    // Deserialize args and recreate workflow
    val input = readFromString[String](metadata.argsJson)(using JsonFileStorage.given_JsonValueCodec_String)
    val workflow = function.function.asInstanceOf[DurableFunction[Tuple1[String], String, JsonFileStorage]].apply(Tuple1(input))

    // Store the event value at the suspend point index
    // This simulates the event being delivered
    println(s"[ProcessB] Storing event value '$eventValue' at index ${metadata.activityIndex}")
    Await.result(
      storage.forType[String].store(storage, workflowId, metadata.activityIndex, eventValue),
      5.seconds
    )

    // Resume from saved index + 1 (past the suspend point)
    val ctx = RunContext.resume(workflowId, metadata.activityIndex + 1)

    println(s"[ProcessB] Resuming workflow from index ${metadata.activityIndex + 1}")

    val result = Await.result(WorkflowRunner.run(workflow, ctx), 30.seconds)

    result match
      case WorkflowResult.Completed(value) =>
        println(s"[ProcessB] Workflow completed: $value")

        // Update metadata
        storage.storeMetadata(workflowId, metadata.copy(
          status = JsonWorkflowStatus.Completed
        ))

      case WorkflowResult.Suspended(snapshot, condition) =>
        println(s"[ProcessB] Workflow suspended again at index ${snapshot.activityIndex}")
        sys.exit(1)

      case WorkflowResult.Failed(error) =>
        println(s"[ProcessB] Workflow failed: ${error.getMessage}")
        error.printStackTrace()
        sys.exit(1)

      case WorkflowResult.ContinueAs(_, _, _) =>
        println(s"[ProcessB] Workflow requested continueAs unexpectedly")
        sys.exit(1)
