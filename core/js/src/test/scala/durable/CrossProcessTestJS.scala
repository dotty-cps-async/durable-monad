package durable

import munit.FunSuite

import scala.scalajs.js
import scala.concurrent.duration.*

/**
 * Integration test for cross-process workflow persistence (JS version).
 *
 * Tests that:
 * 1. Process A can start a workflow, run until suspension, and exit
 * 2. Process B can restore the workflow from storage and continue
 * 3. Auto-registration works correctly across process boundaries
 *
 * This test spawns separate Node.js processes to simulate cross-process
 * workflow restoration, mirroring the JVM CrossProcessTest.
 */
class CrossProcessTestJS extends FunSuite:

  // Longer timeout for process spawning
  override val munitTimeout = Duration(60, "s")

  test("workflow persists across process restart (JS)") {
    val projectRoot = NodeProcess.cwd()
    val scalaVersion = "3.3.7" // Match build.sbt

    // Create temp directory for storage
    val tempDir = NodeFS.mkdtempSync(NodePath.join(NodeOS.tmpdir(), "durable-js-test-"))
    val workflowId = s"test-js-${System.currentTimeMillis()}"

    try
      // Path to compiled test JS module (ESM format)
      val compiledJsPath = NodePath.join(
        projectRoot,
        "core", "js", "target", s"scala-$scalaVersion",
        "durable-monad-core-test-fastopt", "main.js"
      )

      // Path to process scripts (in test resources, copied to test-classes)
      val testClassesPath = NodePath.join(
        projectRoot,
        "core", "js", "target", s"scala-$scalaVersion", "test-classes"
      )
      val processAPath = NodePath.join(testClassesPath, "processA.mjs")
      val processBPath = NodePath.join(testClassesPath, "processB.mjs")

      // If resource files don't exist in test-classes, copy them from source
      val srcResourcesPath = NodePath.join(
        projectRoot,
        "core", "js", "src", "test", "resources"
      )
      if !NodeFS.existsSync(processAPath) then
        ensureResourcesCopied(srcResourcesPath, testClassesPath)

      // Verify compiled JS exists
      assert(NodeFS.existsSync(compiledJsPath), s"Compiled JS not found at: $compiledJsPath")

      // Convert to file:// URL for ES module import
      val compiledJsUrl = NodeUrl.pathToFileURL(compiledJsPath).href

      println(s"=== Cross-Process Test (JS) ===")
      println(s"Temp dir: $tempDir")
      println(s"Workflow ID: $workflowId")
      println(s"Compiled JS: $compiledJsPath")

      // Run Process A - starts workflow, suspends
      println(s"\nStarting Process A...")
      val processAResult = NodeChildProcess.spawnSync(
        "node",
        js.Array(processAPath, compiledJsUrl, tempDir, workflowId, "test-input"),
        NodeFSOptions.spawnOptions(projectRoot)
      )

      val processAStdout = processAResult.stdout.asInstanceOf[String]
      val processAStderr = processAResult.stderr.asInstanceOf[String]
      val processAStatus = processAResult.status.getOrElse(-1)

      println(s"Process A stdout:\n$processAStdout")
      if processAStderr.nonEmpty then
        println(s"Process A stderr:\n$processAStderr")

      assertEquals(processAStatus, 0, s"Process A failed with status $processAStatus:\n$processAStderr")

      // Verify Process A created metadata
      val storage = JsonFileStorageJS(tempDir)
      val metadataAfterA = storage.loadMetadata(WorkflowId(workflowId))
      assert(metadataAfterA.isDefined, "Metadata should exist after Process A")
      assertEquals(metadataAfterA.get.status, JsonWorkflowStatusJS.Suspended)
      assertEquals(metadataAfterA.get.functionName, "durable.CrossProcessWorkflowJS")

      // Verify activity was cached
      val activityFile = NodePath.join(tempDir, workflowId, "activity-0.json")
      assert(NodeFS.existsSync(activityFile), "First activity should be cached")

      // Run Process B - restores and continues
      println(s"\nStarting Process B...")
      val processBResult = NodeChildProcess.spawnSync(
        "node",
        js.Array(processBPath, compiledJsUrl, tempDir, workflowId, "continue!"),
        NodeFSOptions.spawnOptions(projectRoot)
      )

      val processBStdout = processBResult.stdout.asInstanceOf[String]
      val processBStderr = processBResult.stderr.asInstanceOf[String]
      val processBStatus = processBResult.status.getOrElse(-1)

      println(s"Process B stdout:\n$processBStdout")
      if processBStderr.nonEmpty then
        println(s"Process B stderr:\n$processBStderr")

      assertEquals(processBStatus, 0, s"Process B failed with status $processBStatus:\n$processBStderr")

      // Verify Process B completed the workflow
      val metadataAfterB = storage.loadMetadata(WorkflowId(workflowId))
      assert(metadataAfterB.isDefined, "Metadata should exist after Process B")
      assertEquals(metadataAfterB.get.status, JsonWorkflowStatusJS.Completed)

      // Verify second activity was cached
      val activity2File = NodePath.join(tempDir, workflowId, "activity-2.json")
      assert(NodeFS.existsSync(activity2File), "Second activity should be cached")

      println("\n=== Cross-process test passed! ===")

    finally
      // Cleanup
      if NodeFS.existsSync(tempDir) then
        NodeFS.rmSync(tempDir, NodeFSOptions.rmRecursive)
  }

  test("registry lookup works for restored workflow (JS)") {
    import durable.JsonFileStorageJS.given
    import durable.runtime.DurableFunctionRegistry

    // Force workflow registration
    val _ = CrossProcessWorkflowJS.functionName

    // Verify it's in the registry
    val lookup = DurableFunctionRegistry.global.lookup("durable.CrossProcessWorkflowJS")
    assert(lookup.isDefined, "CrossProcessWorkflowJS should be in registry")
    assertEquals(lookup.get.function.functionName.value, "durable.CrossProcessWorkflowJS")

    // Create temp directory for storage
    val tempDir = NodeFS.mkdtempSync(NodePath.join(NodeOS.tmpdir(), "durable-registry-test-"))
    val storage = JsonFileStorageJS(tempDir)
    given JsonFileStorageJS = storage
    given [T: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec]: DurableStorage[T, JsonFileStorageJS] = storage.forType[T]

    try
      // Verify we can cast and call it
      val function = lookup.get.function.asInstanceOf[DurableFunction1[String, String, JsonFileStorageJS]]
      val workflow = function.apply("test")
      assert(workflow.isInstanceOf[Durable[String]], "Should return Durable[String]")
    finally
      // Cleanup
      storage.clearAll()
  }

  private def ensureResourcesCopied(srcDir: String, destDir: String): Unit =
    if !NodeFS.existsSync(destDir) then
      NodeFS.mkdirSync(destDir, NodeFSOptions.mkdirRecursive)

    // Copy processA.mjs
    val processASrc = NodePath.join(srcDir, "processA.mjs")
    val processADest = NodePath.join(destDir, "processA.mjs")
    if NodeFS.existsSync(processASrc) && !NodeFS.existsSync(processADest) then
      val content = NodeFS.readFileSync(processASrc, "utf-8")
      NodeFS.writeFileSync(processADest, content)

    // Copy processB.mjs
    val processBSrc = NodePath.join(srcDir, "processB.mjs")
    val processBDest = NodePath.join(destDir, "processB.mjs")
    if NodeFS.existsSync(processBSrc) && !NodeFS.existsSync(processBDest) then
      val content = NodeFS.readFileSync(processBSrc, "utf-8")
      NodeFS.writeFileSync(processBDest, content)
