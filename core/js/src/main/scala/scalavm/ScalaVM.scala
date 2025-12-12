package example.scalavm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import example.nodejs.{VM, Script}

/**
 * Facility to run precompiled Scala.js modules in isolated Node.js VM contexts.
 *
 * This allows running Scala code with:
 * - Isolated global scope (no access to main app globals)
 * - Controlled context sharing (only what you pass in)
 * - Timeout support
 * - Memory isolation
 */
object ScalaVM:

  /** Node.js fs module for file operations */
  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  object fs extends js.Object:
    def readFileSync(path: String, encoding: String): String = js.native
    def writeFileSync(path: String, data: String): Unit = js.native
    def mkdirSync(path: String, options: js.Object): Unit = js.native
    def unlinkSync(path: String): Unit = js.native
    def existsSync(path: String): Boolean = js.native

  /** Node.js path module */
  @js.native
  @JSImport("node:path", JSImport.Namespace)
  private object nodePath extends js.Object:
    def dirname(path: String): String = js.native

  /**
   * Options for running a script in VM context.
   */
  case class RunOptions(
    timeout: Option[Int] = None,
    filename: Option[String] = None,
    contextName: Option[String] = None
  )

  /**
   * Runs a precompiled Scala.js script in an isolated VM context.
   *
   * @param scriptPath Path to the compiled .js file
   * @param entryPoint Name of the exported function to call (e.g., "counterScript")
   * @param context Object with values to share with the script
   * @param options Execution options (timeout, etc.)
   * @return The result from the script's entry point function
   */
  def runScript(
    scriptPath: String,
    entryPoint: String,
    context: js.Object = js.Dynamic.literal(),
    options: RunOptions = RunOptions()
  ): js.Any =
    // Read the compiled script
    val scriptCode = fs.readFileSync(scriptPath, "utf-8")

    // Create the VM context with provided values and required globals
    val vmContext = js.Dynamic.literal(
      // User-provided context
      __context__ = context,
      // Required globals for Scala.js runtime
      console = js.Dynamic.global.console,
      setTimeout = js.Dynamic.global.setTimeout,
      clearTimeout = js.Dynamic.global.clearTimeout,
      setInterval = js.Dynamic.global.setInterval,
      clearInterval = js.Dynamic.global.clearInterval,
      Buffer = js.Dynamic.global.Buffer,
      process = js.Dynamic.global.process,
      // Result placeholder
      __result__ = null
    )

    // Create context options
    val ctxOptions = js.Dynamic.literal()
    options.contextName.foreach(n => ctxOptions.name = n)

    VM.createContext(vmContext, ctxOptions.asInstanceOf[js.UndefOr[example.nodejs.CreateContextOptions]])

    // Wrap script to capture the exported function result
    val wrappedCode = s"""
      |// Scala.js compiled code
      |$scriptCode
      |
      |// Call the entry point function
      |if (typeof $entryPoint === 'function') {
      |  __result__ = $entryPoint(__context__);
      |} else {
      |  throw new Error('Entry point "$entryPoint" not found or not a function');
      |}
    """.stripMargin

    // Run options
    val runOpts = js.Dynamic.literal(
      displayErrors = true
    )
    options.timeout.foreach(t => runOpts.timeout = t)
    options.filename.foreach(f => runOpts.filename = f)

    // Execute in isolated context
    VM.runInContext(wrappedCode, vmContext, runOpts.asInstanceOf[js.UndefOr[example.nodejs.RunOptions]])

    // Return the result
    vmContext.__result__

  /**
   * Creates a reusable script runner for a precompiled Scala.js module.
   * More efficient when running the same script multiple times.
   *
   * @param scriptPath Path to the compiled .js file
   * @param entryPoint Name of the exported function to call
   */
  def createRunner(scriptPath: String, entryPoint: String): ScriptRunner =
    val scriptCode = fs.readFileSync(scriptPath, "utf-8")
    new ScriptRunner(scriptCode, scriptPath, entryPoint)

  /**
   * Dumps a context object to a JSON file.
   * Useful for capturing/persisting state during script execution.
   *
   * @param context The context object to dump
   * @param filePath Path to write the JSON file
   * @param pretty Whether to format JSON with indentation (default: true)
   */
  def dumpContext(context: js.Object, filePath: String, pretty: Boolean = true): Unit =
    val json = if pretty then
      js.JSON.stringify(context, null.asInstanceOf[js.Array[js.Any]], 2)
    else
      js.JSON.stringify(context)

    // Ensure directory exists
    try
      fs.mkdirSync(nodePath.dirname(filePath), js.Dynamic.literal(recursive = true))
    catch
      case _: js.JavaScriptException => // Directory might already exist

    fs.writeFileSync(filePath, json)

  /**
   * Loads a context from a JSON file.
   *
   * @param filePath Path to the JSON file
   * @return The parsed context object
   */
  def loadContext(filePath: String): js.Dynamic =
    val json = fs.readFileSync(filePath, "utf-8")
    js.JSON.parse(json)

  /**
   * Creates a dumpContext function that can be passed into VM context.
   * This allows scripts running inside VM to dump their context.
   *
   * @param baseDir Base directory for dump files
   * @return A JS function that scripts can call to dump context
   */
  def createDumpFunction(baseDir: String = "."): js.Function2[js.Object, String, Unit] =
    (context: js.Object, filename: String) =>
      val filePath = s"$baseDir/$filename"
      dumpContext(context, filePath)

  /**
   * Checks if a durable state file exists.
   */
  def stateExists(filePath: String): Boolean =
    try
      fs.readFileSync(filePath, "utf-8")
      true
    catch
      case _: js.JavaScriptException => false

  /**
   * Loads a durable snapshot from file.
   */
  def loadSnapshot(filePath: String): DurableSnapshot =
    loadContext(filePath).asInstanceOf[DurableSnapshot]

  /**
   * Saves a durable snapshot to file.
   */
  def saveSnapshot(snapshot: DurableSnapshot, filePath: String): Unit =
    dumpContext(snapshot.asInstanceOf[js.Object], filePath)

  /**
   * Deletes a state file.
   */
  def deleteState(filePath: String): Unit =
    try
      fs.unlinkSync(filePath)
    catch
      case _: js.JavaScriptException => // File might not exist


/**
 * Reusable runner for executing a precompiled script multiple times.
 */
class ScriptRunner private[scalavm](scriptCode: String, scriptPath: String, entryPoint: String):
  private val wrappedCode = s"""
    |$scriptCode
    |if (typeof $entryPoint === 'function') {
    |  __result__ = $entryPoint(__context__);
    |} else {
    |  throw new Error('Entry point "$entryPoint" not found');
    |}
  """.stripMargin

  private val compiledScript = new Script(wrappedCode, js.Dynamic.literal(
    filename = scriptPath
  ).asInstanceOf[js.UndefOr[example.nodejs.ScriptOptions]])

  /**
   * Run the script with the given context.
   */
  def run(context: js.Object = js.Dynamic.literal(), timeout: Option[Int] = None): js.Any =
    val vmContext = js.Dynamic.literal(
      __context__ = context,
      console = js.Dynamic.global.console,
      setTimeout = js.Dynamic.global.setTimeout,
      clearTimeout = js.Dynamic.global.clearTimeout,
      setInterval = js.Dynamic.global.setInterval,
      clearInterval = js.Dynamic.global.clearInterval,
      Buffer = js.Dynamic.global.Buffer,
      process = js.Dynamic.global.process,
      __result__ = null
    )

    VM.createContext(vmContext)

    val runOpts = js.Dynamic.literal(displayErrors = true)
    timeout.foreach(t => runOpts.timeout = t)

    compiledScript.runInContext(vmContext, runOpts.asInstanceOf[js.UndefOr[example.nodejs.RunOptions]])
    vmContext.__result__
