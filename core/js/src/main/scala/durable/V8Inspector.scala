package example.durable

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Scala.js facade for Node.js Inspector module.
 * Used for capturing and restoring local variables at debugger breakpoints.
 */

@js.native
@JSImport("node:inspector", JSImport.Namespace)
object InspectorModule extends js.Object:
  @js.native
  class Session() extends js.Object:
    def connect(): Unit = js.native
    def disconnect(): Unit = js.native
    def post(method: String): Unit = js.native
    def post(method: String, params: js.Object): Unit = js.native
    def post(method: String, callback: js.Function2[js.Error, js.Dynamic, Unit]): Unit = js.native
    def post(method: String, params: js.Object, callback: js.Function2[js.Error, js.Dynamic, Unit]): Unit = js.native
    def on(event: String, listener: js.Function1[js.Dynamic, Unit]): Unit = js.native
    def removeListener(event: String, listener: js.Function1[js.Dynamic, Unit]): Unit = js.native

/**
 * High-level API for V8 Inspector operations.
 */
class V8LocalsCapture:
  private val session = new InspectorModule.Session()
  private var enabled = false
  private var pauseHandler: js.Function1[js.Dynamic, Unit] = null

  def enable(): Unit =
    if !enabled then
      session.connect()
      session.post("Debugger.enable")
      session.post("Runtime.enable")
      enabled = true

  def disable(): Unit =
    if enabled then
      session.disconnect()
      enabled = false

  /**
   * Set handler for Debugger.paused events.
   * Handler receives the pause event with callFrames.
   */
  def onPause(handler: V8PauseEvent => Unit): Unit =
    if pauseHandler != null then
      session.removeListener("Debugger.paused", pauseHandler)

    pauseHandler = (event: js.Dynamic) => handler(V8PauseEvent(event))
    session.on("Debugger.paused", pauseHandler)

  /**
   * Capture local variables from a call frame.
   * Returns a map of variable names to their values.
   */
  def captureLocals(frame: V8CallFrame, callback: Either[String, Map[String, js.Any]] => Unit): Unit =
    val localScope = frame.localScope
    localScope match
      case None =>
        callback(Right(Map.empty))
      case Some(scope) =>
        session.post("Runtime.getProperties",
          js.Dynamic.literal(
            objectId = scope.objectId,
            ownProperties = true
          ).asInstanceOf[js.Object],
          (err: js.Error, props: js.Dynamic) => {
            if err != null then
              callback(Left(err.message))
            else
              val result = props.result.asInstanceOf[js.Array[js.Dynamic]]
              val locals = scala.collection.mutable.Map[String, js.Any]()

              result.foreach { prop =>
                val name = prop.name.asInstanceOf[String]
                val value = prop.value.asInstanceOf[js.UndefOr[js.Dynamic]]
                value.foreach { v =>
                  val valType = v.`type`.asInstanceOf[String]
                  if valType != "function" then
                    locals(name) = js.Dynamic.literal(
                      value = v.value,
                      `type` = valType,
                      subtype = v.subtype,
                      description = v.description
                    )
                }
              }

              callback(Right(locals.toMap))
          }
        )

  /**
   * Restore local variables to a call frame.
   */
  def restoreLocals(frame: V8CallFrame, locals: Map[String, js.Any], callback: Option[String] => Unit): Unit =
    if locals.isEmpty then
      callback(None)
      return

    var completed = 0
    val errors = scala.collection.mutable.ArrayBuffer[String]()
    val total = locals.size

    locals.foreach { case (name, info) =>
      val infoObj = info.asInstanceOf[js.Dynamic]
      val value = infoObj.value
      val valType = infoObj.`type`.asInstanceOf[js.UndefOr[String]].getOrElse("undefined")

      val newValue: js.Dynamic =
        if valType == "undefined" then
          js.Dynamic.literal(unserializableValue = "undefined")
        else if value == null then
          js.Dynamic.literal(value = null)
        else
          js.Dynamic.literal(value = value)

      session.post("Debugger.setVariableValue",
        js.Dynamic.literal(
          scopeNumber = 0,
          variableName = name,
          newValue = newValue,
          callFrameId = frame.callFrameId
        ).asInstanceOf[js.Object],
        (err: js.Error, _: js.Dynamic) => {
          if err != null then
            errors += s"$name: ${err.message}"

          completed += 1
          if completed == total then
            if errors.isEmpty then callback(None)
            else callback(Some(errors.mkString(", ")))
        }
      )
    }

  /**
   * Resume debugger execution.
   */
  def resume(): Unit =
    session.post("Debugger.resume")

/**
 * Wrapper for V8 pause event.
 */
class V8PauseEvent(private val event: js.Dynamic):
  lazy val callFrames: Seq[V8CallFrame] =
    val frames = event.params.callFrames.asInstanceOf[js.Array[js.Dynamic]]
    frames.toSeq.map(f => V8CallFrame(f))

  def topFrame: V8CallFrame = callFrames.head

/**
 * Wrapper for V8 call frame.
 */
class V8CallFrame(private val frame: js.Dynamic):
  def callFrameId: String = frame.callFrameId.asInstanceOf[String]
  def functionName: String = frame.functionName.asInstanceOf[String]
  def lineNumber: Int = frame.location.lineNumber.asInstanceOf[Int]

  lazy val scopeChain: Seq[V8Scope] =
    val scopes = frame.scopeChain.asInstanceOf[js.Array[js.Dynamic]]
    scopes.toSeq.map(s => V8Scope(s))

  def localScope: Option[V8Scope] =
    scopeChain.find(_.scopeType == "local")

/**
 * Wrapper for V8 scope.
 */
class V8Scope(private val scope: js.Dynamic):
  def scopeType: String = scope.`type`.asInstanceOf[String]
  def objectId: String = scope.`object`.objectId.asInstanceOf[String]
