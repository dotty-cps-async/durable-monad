package durable

import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.sys.process.*
import scala.concurrent.duration.*

/**
 * Integration test for cross-process workflow persistence.
 *
 * Tests that:
 * 1. Process A can start a workflow, run until suspension, and exit
 * 2. Process B can restore the workflow from storage and continue
 * 3. Auto-registration works correctly across process boundaries
 */
class CrossProcessTest extends FunSuite:

  // Longer timeout for process spawning
  override val munitTimeout = Duration(60, "s")

  test("workflow persists across process restart") {
    // Create temp directory for storage
    val tempDir = Files.createTempDirectory("durable-test-")
    val workflowId = s"test-${System.currentTimeMillis()}"

    try
      // Get classpath for spawning JVM processes
      val classpath = System.getProperty("java.class.path")
      val javaHome = System.getProperty("java.home")
      val javaBin = s"$javaHome/bin/java"

      // Run Process A - starts workflow, suspends
      println(s"Starting Process A...")
      val processAResult = Process(Seq(
        javaBin,
        "-cp", classpath,
        "durable.ProcessA",
        tempDir.toString,
        workflowId,
        "test-input"
      )).!!

      println(s"Process A output:\n$processAResult")

      // Verify Process A created metadata
      val storage = JsonFileStorage(tempDir)
      val metadataAfterA = storage.loadMetadata(WorkflowId(workflowId))
      assert(metadataAfterA.isDefined, "Metadata should exist after Process A")
      assertEquals(metadataAfterA.get.status, JsonWorkflowStatus.Suspended)
      assertEquals(metadataAfterA.get.functionName, "durable.CrossProcessWorkflow")

      // Verify activity was cached
      val activityFile = tempDir.resolve(workflowId).resolve("activity-0.json")
      assert(Files.exists(activityFile), "First activity should be cached")

      // Run Process B - restores and continues
      println(s"Starting Process B...")
      val processBOutput = new StringBuilder
      val processBLogger = ProcessLogger(
        line => { processBOutput.append(line).append("\n"); println(s"[B] $line") },
        line => { processBOutput.append(line).append("\n"); println(s"[B ERR] $line") }
      )
      val processBExitCode = Process(Seq(
        javaBin,
        "-cp", classpath,
        "durable.ProcessB",
        tempDir.toString,
        workflowId,
        "continue!"
      )).!(processBLogger)

      val processBResult = processBOutput.toString
      assertEquals(processBExitCode, 0, s"Process B failed:\n$processBResult")

      println(s"Process B output:\n$processBResult")

      // Verify Process B completed the workflow
      val metadataAfterB = storage.loadMetadata(WorkflowId(workflowId))
      assert(metadataAfterB.isDefined, "Metadata should exist after Process B")
      assertEquals(metadataAfterB.get.status, JsonWorkflowStatus.Completed)

      // Verify second activity was cached
      val activity2File = tempDir.resolve(workflowId).resolve("activity-2.json")
      assert(Files.exists(activity2File), "Second activity should be cached")

      println("Cross-process test passed!")

    finally
      // Cleanup
      if Files.exists(tempDir) then
        Files.walk(tempDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete(_))
  }

  test("registry lookup works for restored workflow") {
    // This test verifies that DurableFunctionRegistry auto-registration works
    import durable.JsonFileStorage.given

    // Force workflow registration
    val _ = CrossProcessWorkflow.functionName

    // Verify it's in the registry
    val lookup = DurableFunctionRegistry.global.lookup("durable.CrossProcessWorkflow")
    assert(lookup.isDefined, "CrossProcessWorkflow should be in registry")
    assertEquals(lookup.get.function.functionName.value, "durable.CrossProcessWorkflow")

    // Provide storage for workflow creation
    val tempDir = Files.createTempDirectory("durable-registry-test-")
    val storage = JsonFileStorage(tempDir)
    given JsonFileStorage = storage
    given [T: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec]: DurableStorage[T, JsonFileStorage] = storage.forType[T]

    try
      // Verify we can cast and call it
      val function = lookup.get.function.asInstanceOf[DurableFunction1[String, String, JsonFileStorage]]
      val workflow = function.apply("test")
      assert(workflow.isInstanceOf[Durable[String]], "Should return Durable[String]")
    finally
      // Cleanup
      storage.clearAll()
  }
