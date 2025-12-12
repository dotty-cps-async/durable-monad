package example

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import example.scalavm.{ScalaVM, DurableRunner, DurableState}

/**
 * Controller for running and resuming durable scripts.
 *
 * Usage:
 *   node controller.mjs run <scriptName>     - Start fresh or resume
 *   node controller.mjs resume <scriptName>  - Resume only (fail if no state)
 *   node controller.mjs status <scriptName>  - Check script status
 *   node controller.mjs clear <scriptName>   - Clear saved state
 */
object Controller:

  val scriptFile = "scripts/target/scala-3.3.7/scalajs-scripts-fastopt/main.js"
  val stateDir = "state/durable"

  def main(args: js.Array[String]): Unit =
    if args.length < 2 then
      printUsage()
      return

    val command = args(0)
    val scriptName = args(1)

    command match
      case "run" => runOrResume(scriptName)
      case "resume" => resumeOnly(scriptName)
      case "status" => showStatus(scriptName)
      case "clear" => clearState(scriptName)
      case _ =>
        println(s"Unknown command: $command")
        printUsage()

  def printUsage(): Unit =
    println("""
      |Durable Script Controller
      |
      |Usage:
      |  node controller.mjs run <scriptName>     - Start fresh or resume
      |  node controller.mjs resume <scriptName>  - Resume only (fail if no state)
      |  node controller.mjs status <scriptName>  - Check script status
      |  node controller.mjs clear <scriptName>   - Clear saved state
      |
      |Available scripts:
      |  durableWorkflow  - Callback-based durable workflow
      |  monadicWorkflow  - Monadic workflow using async/await
      |""".stripMargin)

  def runOrResume(scriptName: String): Unit =
    println(s"=== Running/Resuming: $scriptName ===")

    scriptName match
      case "monadicWorkflow" => runMonadicWorkflow()
      case _ => runCallbackWorkflow(scriptName)

  def runCallbackWorkflow(scriptName: String): Unit =
    val runner = new DurableRunner(scriptFile, scriptName, stateDir)

    val initialData = scriptName match
      case "durableWorkflow" =>
        js.Dynamic.literal(
          items = js.Array(10, 20, 30, 40, 50)
        )
      case _ =>
        js.Dynamic.literal()

    val result = runner.startOrResume(initialData)

    println()
    println(s"=== Result ===")
    println(s"Status: ${result.metadata.status}")
    if result.metadata.status == DurableState.Shutdown then
      println(s"Stopped at step: ${result.metadata.step}")
      println(s"State saved to: ${runner.getStateFile}")
      println("Run again to resume.")
    else if result.metadata.status == DurableState.Completed then
      println(s"Completed successfully!")
      println(s"Result: ${js.JSON.stringify(result.data)}")

  def runMonadicWorkflow(): Unit =
    val stateFile = s"$stateDir/monadicWorkflow.json"

    // Load previous snapshot if exists
    val prevSnapshot: js.UndefOr[js.Dynamic] =
      if ScalaVM.stateExists(stateFile) then
        ScalaVM.loadContext(stateFile)
      else
        js.undefined

    // Call the script
    val context = js.Dynamic.literal(
      items = js.Array(1, 2, 3, 4, 5, 6),
      snapshot = prevSnapshot
    )

    val result = ScalaVM.runScript(
      scriptPath = scriptFile,
      entryPoint = "monadicWorkflow",
      context = context
    ).asInstanceOf[js.Dynamic]

    println()
    println(s"=== Result ===")
    println(s"Status: ${result.status}")

    if result.status.asInstanceOf[String] == "shutdown" then
      // Save the snapshot
      ScalaVM.dumpContext(result.snapshot.asInstanceOf[js.Object], stateFile)
      println(s"State saved to: $stateFile")
      println("Run again to resume.")
    else
      // Completed - clear state
      ScalaVM.deleteState(stateFile)
      println(s"Result: ${result.result}")

  def resumeOnly(scriptName: String): Unit =
    println(s"=== Resuming: $scriptName ===")

    val runner = new DurableRunner(scriptFile, scriptName, stateDir)

    if !runner.hasSavedState then
      println(s"Error: No saved state for '$scriptName'")
      println("Use 'run' command to start fresh.")
      return

    val result = runner.resume()

    println()
    println(s"=== Result ===")
    println(s"Status: ${result.metadata.status}")

  def showStatus(scriptName: String): Unit =
    val stateFile = s"$stateDir/$scriptName.json"

    if !ScalaVM.stateExists(stateFile) then
      println(s"No saved state for '$scriptName'")
      return

    val snapshot = ScalaVM.loadSnapshot(stateFile)
    println(s"=== Status: $scriptName ===")
    println(s"Script ID: ${snapshot.metadata.scriptId}")
    println(s"Status: ${snapshot.metadata.status}")
    println(s"Step: ${snapshot.metadata.step}")
    snapshot.metadata.totalSteps.toOption.foreach(t => println(s"Total steps: $t"))
    println(s"Started: ${snapshot.metadata.startedAt}")
    println(s"Updated: ${snapshot.metadata.updatedAt}")

  def clearState(scriptName: String): Unit =
    val runner = new DurableRunner(scriptFile, scriptName, stateDir)
    if runner.hasSavedState then
      runner.clearState()
      println(s"State cleared for '$scriptName'")
    else
      println(s"No saved state for '$scriptName'")
