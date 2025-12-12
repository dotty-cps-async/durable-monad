package example.nodejs

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Node.js VM module facade for Scala.js */
@js.native
@JSImport("node:vm", JSImport.Namespace)
object VM extends js.Object:

  /** Creates a contextified object for use as global scope */
  def createContext(contextObject: js.Object = js.native, options: js.UndefOr[CreateContextOptions] = js.undefined): js.Object = js.native

  /** Checks if an object has been contextified */
  def isContext(obj: js.Object): Boolean = js.native

  /** Compiles and runs code within a contextified object's scope */
  def runInContext(code: String, contextifiedObject: js.Object, options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native

  /** Creates context, contextifies object, and runs code in one step */
  def runInNewContext(code: String, contextObject: js.UndefOr[js.Object] = js.undefined, options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native

  /** Runs code in current global context (not isolated) */
  def runInThisContext(code: String, options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native

  /** Compiles code as a function with parameters */
  def compileFunction(code: String, params: js.UndefOr[js.Array[String]] = js.undefined, options: js.UndefOr[CompileFunctionOptions] = js.undefined): js.Function = js.native


/** Options for vm.createContext */
trait CreateContextOptions extends js.Object:
  val name: js.UndefOr[String] = js.undefined
  val origin: js.UndefOr[String] = js.undefined
  val codeGeneration: js.UndefOr[CodeGenerationOptions] = js.undefined
  val microtaskMode: js.UndefOr[String] = js.undefined

trait CodeGenerationOptions extends js.Object:
  val strings: js.UndefOr[Boolean] = js.undefined
  val wasm: js.UndefOr[Boolean] = js.undefined

/** Options for running scripts */
trait RunOptions extends js.Object:
  val filename: js.UndefOr[String] = js.undefined
  val lineOffset: js.UndefOr[Int] = js.undefined
  val columnOffset: js.UndefOr[Int] = js.undefined
  val displayErrors: js.UndefOr[Boolean] = js.undefined
  val timeout: js.UndefOr[Int] = js.undefined
  val breakOnSigint: js.UndefOr[Boolean] = js.undefined

trait CompileFunctionOptions extends js.Object:
  val filename: js.UndefOr[String] = js.undefined
  val lineOffset: js.UndefOr[Int] = js.undefined
  val columnOffset: js.UndefOr[Int] = js.undefined
  val parsingContext: js.UndefOr[js.Object] = js.undefined


/** vm.Script class - precompiled script for multiple executions */
@js.native
@JSImport("node:vm", "Script")
class Script(code: String, options: js.UndefOr[ScriptOptions] = js.undefined) extends js.Object:
  def runInContext(contextifiedObject: js.Object, options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native
  def runInNewContext(contextObject: js.UndefOr[js.Object] = js.undefined, options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native
  def runInThisContext(options: js.UndefOr[RunOptions] = js.undefined): js.Any = js.native
  def createCachedData(): js.typedarray.Uint8Array = js.native
  val sourceMapURL: js.UndefOr[String] = js.native

trait ScriptOptions extends js.Object:
  val filename: js.UndefOr[String] = js.undefined
  val lineOffset: js.UndefOr[Int] = js.undefined
  val columnOffset: js.UndefOr[Int] = js.undefined
  val cachedData: js.UndefOr[js.typedarray.Uint8Array] = js.undefined
  val produceCachedData: js.UndefOr[Boolean] = js.undefined
