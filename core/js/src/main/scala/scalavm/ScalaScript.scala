package example.scalavm

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Base trait for Scala scripts that can run in isolated VM contexts.
 *
 * Scripts implementing this trait will be compiled separately and can be
 * loaded into Node.js VM contexts at runtime.
 */
trait ScalaScript:
  /**
   * Main entry point for the script.
   * @param context The shared context object passed from the host
   * @return Result value that will be returned to the host
   */
  def run(context: js.Dynamic): js.Any


/**
 * Metadata for a compiled Scala script module.
 */
case class ScriptModule(
  name: String,
  path: String
)
