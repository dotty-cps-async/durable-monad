package durable

import munit.FunSuite
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.file.{Files, Path}
import scala.sys.process.*
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.compiletime

/**
 * Integration test for WorkflowEngine across process restarts.
 *
 * Tests that:
 * 1. Process A starts a workflow via WorkflowEngine, waits for suspension, shuts down
 * 2. Process B recovers the workflow, sends event, queries result
 */
class WorkflowEngineIntegrationTest extends FunSuite:
  override val munitTimeout = Duration(60, "s")

  test("workflow engine persists and recovers across process restart") {
    val tempDir = Files.createTempDirectory("workflow-engine-test-")
    val backupFile = tempDir.resolve("storage.json")
    val workflowId = s"engine-test-${System.currentTimeMillis()}"

    try
      val classpath = System.getProperty("java.class.path")
      val javaHome = System.getProperty("java.home")
      val javaBin = s"$javaHome/bin/java"

      // Run Process A - starts workflow via engine, waits for suspension
      println(s"Starting WorkflowEngine Process A...")
      val processAResult = Process(Seq(
        javaBin,
        "-cp", classpath,
        "durable.WorkflowEngineProcessA",
        backupFile.toString,
        workflowId,
        "test-input-value"
      )).!!

      println(s"Process A output:\n$processAResult")

      // Verify backup file was created
      assert(Files.exists(backupFile), "Backup file should exist after Process A")

      // Run Process B - recovers, sends event, queries result
      println(s"Starting WorkflowEngine Process B...")
      val processBOutput = new StringBuilder
      val processBLogger = ProcessLogger(
        line => { processBOutput.append(line).append("\n"); println(s"[B] $line") },
        line => { processBOutput.append(line).append("\n"); println(s"[B ERR] $line") }
      )
      val processBExitCode = Process(Seq(
        javaBin,
        "-cp", classpath,
        "durable.WorkflowEngineProcessB",
        backupFile.toString,
        workflowId,
        "event-data-123"
      )).!(processBLogger)

      val processBResult = processBOutput.toString
      assertEquals(processBExitCode, 0, s"Process B failed:\n$processBResult")

      println(s"Process B output:\n$processBResult")
      println("WorkflowEngine integration test passed!")

    finally
      // Cleanup
      if Files.exists(tempDir) then
        Files.walk(tempDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete(_))
  }


// Custom event type for the test - demonstrates custom events work
case class TestEventPayload(value: String)
object TestEventPayload:
  given DurableEventName[TestEventPayload] = DurableEventName("engine-test-event")
  given JsonValueCodec[TestEventPayload] = JsonCodecMaker.make

/**
 * Test workflow for engine integration test with custom event type.
 * Uses MemoryWithJsonBackupStorage as concrete backend - allows custom event storage.
 */
// Import givens at outer scope so trait context parameters can resolve
import MemoryWithJsonBackupStorage.given

object EngineTestWorkflow extends DurableFunction1[String, String, MemoryWithJsonBackupStorage] derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  def apply(input: String)(using MemoryWithJsonBackupStorage): Durable[String] =
    import TestEventPayload.given
    for
      processed <- Durable.activity(Future.successful(s"processed:$input"))
      event <- Durable.awaitEvent[TestEventPayload, MemoryWithJsonBackupStorage]
      result <- Durable.activity(Future.successful(s"$processed|received:${event.value}"))
    yield result


/**
 * Process A: Start workflow via WorkflowEngine, wait for suspension, shutdown.
 *
 * Usage: WorkflowEngineProcessA <backupFile> <workflowId> <input>
 */
object WorkflowEngineProcessA:
  import MemoryWithJsonBackupStorage.given

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val backupFile = args(0)
    val workflowId = WorkflowId(args(1))
    val input = args(2)

    println(s"[ProcessA] Starting with backupFile=$backupFile, workflowId=$workflowId, input=$input")

    // Create storage and engine
    val storage = MemoryWithJsonBackupStorage(backupFile)
    given MemoryWithJsonBackupStorage = storage

    // Force workflow registration (imports given storages from companion)
    val _ = EngineTestWorkflow.functionName

    val engine = WorkflowEngine(storage)

    // Start workflow
    println(s"[ProcessA] Starting workflow...")
    val startedId = Await.result(
      engine.start(EngineTestWorkflow, Tuple1(input), Some(workflowId)),
      10.seconds
    )
    println(s"[ProcessA] Workflow started with id: $startedId")

    // Wait for suspension
    var status: Option[WorkflowStatus] = None
    var attempts = 0
    while attempts < 50 && status != Some(WorkflowStatus.Suspended) do
      Thread.sleep(100)
      status = Await.result(engine.queryStatus(workflowId), 5.seconds)
      attempts += 1

    println(s"[ProcessA] Workflow status after ${attempts * 100}ms: $status")

    if status != Some(WorkflowStatus.Suspended) then
      println(s"[ProcessA] ERROR: Expected Suspended, got $status")
      sys.exit(1)

    // Shutdown engine and persist
    println(s"[ProcessA] Shutting down engine...")
    Await.result(engine.shutdown(), 5.seconds)

    // Save storage to JSON
    println(s"[ProcessA] Saving storage to $backupFile...")
    storage.shutdown()

    println(s"[ProcessA] Done. Workflow suspended and persisted.")


/**
 * Process B: Recover workflow, send event, query result.
 *
 * Usage: WorkflowEngineProcessB <backupFile> <workflowId> <eventData>
 */
object WorkflowEngineProcessB:
  import MemoryWithJsonBackupStorage.given

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val backupFile = args(0)
    val workflowId = WorkflowId(args(1))
    val eventData = args(2)

    println(s"[ProcessB] Starting with backupFile=$backupFile, workflowId=$workflowId, eventData=$eventData")

    // Create storage and restore from backup
    val storage = MemoryWithJsonBackupStorage(backupFile)
    println(s"[ProcessB] Restoring storage from $backupFile...")
    storage.restore()

    given MemoryWithJsonBackupStorage = storage

    // Force workflow registration (imports given storages from companion)
    val _ = EngineTestWorkflow.functionName

    val engine = WorkflowEngine(storage)

    // Recover workflows
    println(s"[ProcessB] Recovering workflows...")
    val report = Await.result(engine.recover(), 10.seconds)
    println(s"[ProcessB] Recovery report: active=${report.activeWorkflows}, suspended=${report.resumedSuspended}, running=${report.resumedRunning}")

    // Check workflow status before event
    val statusBefore = Await.result(engine.queryStatus(workflowId), 5.seconds)
    println(s"[ProcessB] Workflow status before event: $statusBefore")

    if statusBefore != Some(WorkflowStatus.Suspended) then
      println(s"[ProcessB] ERROR: Expected Suspended, got $statusBefore")
      sys.exit(1)

    // Send event - TestEventPayload type matches workflow's awaitEvent[TestEventPayload]
    import TestEventPayload.given
    println(s"[ProcessB] Sending event with data: $eventData")
    Await.result(engine.sendEventBroadcast(TestEventPayload(eventData)), 5.seconds)

    // Wait for completion
    var status: Option[WorkflowStatus] = None
    var attempts = 0
    while attempts < 50 && status != Some(WorkflowStatus.Succeeded) do
      Thread.sleep(100)
      status = Await.result(engine.queryStatus(workflowId), 5.seconds)
      attempts += 1

    println(s"[ProcessB] Workflow status after ${attempts * 100}ms: $status")

    if status != Some(WorkflowStatus.Succeeded) then
      println(s"[ProcessB] ERROR: Expected Succeeded, got $status")
      sys.exit(1)

    // Workflow completed successfully!
    // Note: queryResult is not implemented yet, but we verified:
    // 1. Process A started workflow and suspended
    // 2. Process B recovered, sent event, workflow completed
    println(s"[ProcessB] SUCCESS: Workflow completed with status Succeeded")

    // Shutdown
    Await.result(engine.shutdown(), 5.seconds)
    println(s"[ProcessB] Done.")
