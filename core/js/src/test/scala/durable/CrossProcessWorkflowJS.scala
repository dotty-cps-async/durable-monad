package durable

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import durable.JsonFileStorageJS.given

import scala.concurrent.{Future, ExecutionContext}
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}
import durable.runtime.DurableFunctionRegistry

/**
 * Test workflow for cross-process persistence testing (JS version).
 * Does an activity, then suspends waiting for an event.
 * Uses JsonFileStorageJS as the concrete backend type.
 */
object CrossProcessWorkflowJS extends DurableFunction1[String, String, JsonFileStorageJS] derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  override def apply(input: String)(using JsonFileStorageJS): Durable[String] =
    // Event name for this workflow
    given DurableEventName[String] = DurableEventName("continue-signal")
    for
      // First activity - will be cached
      step1 <- Durable.activity {
        Future.successful(s"Processed: $input")
      }
      // Suspend - waiting for external event (uses resultStorage since event is String)
      _ <- Durable.awaitEvent[String, JsonFileStorageJS]
      // Second activity - only runs after resume
      step2 <- Durable.activity {
        Future.successful(s"$step1 - completed!")
      }
    yield step2

/**
 * Result type for process entry points.
 */
@js.native
trait ProcessResult extends js.Object:
  val success: Boolean = js.native
  val message: String = js.native
  val activityIndex: js.UndefOr[Int] = js.native

object ProcessResult:
  def success(message: String, activityIndex: Int = -1): js.Object =
    if activityIndex >= 0 then
      js.Dynamic.literal(success = true, message = message, activityIndex = activityIndex)
    else
      js.Dynamic.literal(success = true, message = message)

  def failure(message: String): js.Object =
    js.Dynamic.literal(success = false, message = message)

/**
 * Process A entry point: Starts workflow, runs until suspension, saves state.
 *
 * @param storageDir Base directory for JSON file storage
 * @param workflowIdStr Workflow ID string
 * @param input Input string for the workflow
 * @return Promise with ProcessResult
 */
@JSExportTopLevel("runProcessA")
def runProcessA(storageDir: String, workflowIdStr: String, input: String): js.Promise[js.Object] =
  import scala.scalajs.js.JSConverters.*

  val storage = JsonFileStorageJS(storageDir)
  given JsonFileStorageJS = storage
  given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorageJS] = storage.forType[T]

  // Force workflow registration by accessing it
  val _ = CrossProcessWorkflowJS.functionName

  println(s"[ProcessA] Starting workflow $workflowIdStr with input: $input")

  val workflowId = WorkflowId(workflowIdStr)
  val workflow = CrossProcessWorkflowJS.apply(input)
  val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

  WorkflowSessionRunner.run(workflow, ctx).map { result =>
    result match
      case WorkflowSessionResult.Suspended(snapshot, condition) =>
        println(s"[ProcessA] Workflow suspended at index ${snapshot.activityIndex}")
        println(s"[ProcessA] Waiting for: $condition")

        // Save metadata for Process B
        storage.storeMetadata(workflowId, JsonWorkflowMetadataJS(
          functionName = CrossProcessWorkflowJS.functionName.value,
          argTypes = List("java.lang.String"),
          argsJson = writeToString(input)(using JsonFileStorageJS.given_JsonValueCodec_String),
          activityIndex = snapshot.activityIndex,
          status = JsonWorkflowStatusJS.Suspended
        ))

        println(s"[ProcessA] Metadata saved. Exiting.")
        ProcessResult.success("Suspended", snapshot.activityIndex)

      case WorkflowSessionResult.Completed(_, value) =>
        println(s"[ProcessA] Workflow completed unexpectedly: $value")
        ProcessResult.failure(s"Workflow completed unexpectedly: $value")

      case WorkflowSessionResult.Failed(_, error) =>
        println(s"[ProcessA] Workflow failed: ${error.getMessage}")
        ProcessResult.failure(s"Workflow failed: ${error.getMessage}")

      case WorkflowSessionResult.ContinueAs(_, _, _) =>
        println(s"[ProcessA] Workflow requested continueAs unexpectedly")
        ProcessResult.failure("Workflow requested continueAs unexpectedly")
  }.recover { case e =>
    println(s"[ProcessA] Error: ${e.getMessage}")
    ProcessResult.failure(s"Error: ${e.getMessage}")
  }.toJSPromise

/**
 * Process B entry point: Restores workflow from storage and continues after providing event.
 *
 * @param storageDir Base directory for JSON file storage
 * @param workflowIdStr Workflow ID string
 * @param eventValue Event value to deliver
 * @return Promise with ProcessResult
 */
@JSExportTopLevel("runProcessB")
def runProcessB(storageDir: String, workflowIdStr: String, eventValue: String): js.Promise[js.Object] =
  import scala.scalajs.js.JSConverters.*

  val storage = JsonFileStorageJS(storageDir)
  given JsonFileStorageJS = storage
  given [T: JsonValueCodec]: DurableStorage[T, JsonFileStorageJS] = storage.forType[T]

  // Force workflow registration by accessing it
  val _ = CrossProcessWorkflowJS.functionName

  val workflowId = WorkflowId(workflowIdStr)
  println(s"[ProcessB] Loading workflow $workflowId")

  // Load metadata and lookup function, handling errors
  val resultFuture = storage.loadMetadata(workflowId) match
    case None =>
      println(s"[ProcessB] No metadata found for workflow $workflowId")
      Future.successful(ProcessResult.failure(s"No metadata found for workflow $workflowId"))

    case Some(metadata) =>
      println(s"[ProcessB] Found workflow: ${metadata.functionName} at index ${metadata.activityIndex}")

      DurableFunctionRegistry.global.lookup(metadata.functionName) match
        case None =>
          println(s"[ProcessB] Function not found in registry: ${metadata.functionName}")
          println(s"[ProcessB] Registered functions: ${DurableFunctionRegistry.global.registeredNames}")
          Future.successful(ProcessResult.failure(s"Function not found in registry: ${metadata.functionName}"))

        case Some(function) =>
          println(s"[ProcessB] Found function in registry: ${function.function.functionName.value}")

          // Deserialize args and recreate workflow
          val input = readFromString[String](metadata.argsJson)(using JsonFileStorageJS.given_JsonValueCodec_String)
          val workflow = function.function.asInstanceOf[DurableFunction1[String, String, JsonFileStorageJS]].apply(input)

          // Store the event value at the suspend point index
          println(s"[ProcessB] Storing event value '$eventValue' at index ${metadata.activityIndex}")

          // Store winning condition first (required for replay), then store event value
          val storeFuture = for
            _ <- storage.storeWinningCondition(workflowId, metadata.activityIndex, SingleEvent("continue-signal"))
            _ <- storage.forType[String].storeStep(storage, workflowId, metadata.activityIndex, eventValue)
          yield ()

          storeFuture.flatMap { _ =>
            // Resume from saved index + 1 (past the suspend point)
            val ctx = WorkflowSessionRunner.RunContext.resume(workflowId, metadata.activityIndex + 1, 0)

            println(s"[ProcessB] Resuming workflow from index ${metadata.activityIndex + 1}")

            WorkflowSessionRunner.run(workflow, ctx).map { result =>
              result match
                case WorkflowSessionResult.Completed(_, value) =>
                  println(s"[ProcessB] Workflow completed: $value")

                  // Update metadata
                  storage.storeMetadata(workflowId, metadata.copy(
                    status = JsonWorkflowStatusJS.Completed
                  ))

                  ProcessResult.success(s"Completed: $value")

                case WorkflowSessionResult.Suspended(snapshot, condition) =>
                  println(s"[ProcessB] Workflow suspended again at index ${snapshot.activityIndex}")
                  ProcessResult.failure(s"Workflow suspended again at index ${snapshot.activityIndex}")

                case WorkflowSessionResult.Failed(_, error) =>
                  println(s"[ProcessB] Workflow failed: ${error.getMessage}")
                  error.printStackTrace()
                  ProcessResult.failure(s"Workflow failed: ${error.getMessage}")

                case WorkflowSessionResult.ContinueAs(_, _, _) =>
                  println(s"[ProcessB] Workflow requested continueAs unexpectedly")
                  ProcessResult.failure("Workflow requested continueAs unexpectedly")
            }
          }

  resultFuture.recover { case e =>
    println(s"[ProcessB] Error: ${e.getMessage}")
    e.printStackTrace()
    ProcessResult.failure(s"Error: ${e.getMessage}")
  }.toJSPromise
