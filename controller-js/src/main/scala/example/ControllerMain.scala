package example

import scala.scalajs.js

object ControllerMain:
  def main(args: Array[String]): Unit =
    val processArgs = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]]
    // Skip "node" and script path
    val cmdArgs = processArgs.jsSlice(2)
    Controller.main(cmdArgs)
