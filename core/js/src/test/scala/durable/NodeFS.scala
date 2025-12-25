package durable

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * Node.js `fs` module facade for file system operations.
 */
@js.native
@JSImport("fs", JSImport.Namespace)
object NodeFS extends js.Object:
  def mkdirSync(path: String, options: js.Object): Unit = js.native
  def writeFileSync(path: String, data: String): Unit = js.native
  def readFileSync(path: String, encoding: String): String = js.native
  def existsSync(path: String): Boolean = js.native
  def readdirSync(path: String): js.Array[String] = js.native
  def rmSync(path: String, options: js.Object): Unit = js.native
  def statSync(path: String): NodeFSStats = js.native
  def mkdtempSync(prefix: String): String = js.native

/**
 * Node.js fs.Stats interface.
 */
@js.native
trait NodeFSStats extends js.Object:
  def isDirectory(): Boolean = js.native
  def isFile(): Boolean = js.native

/**
 * Node.js `path` module facade for path manipulation.
 */
@js.native
@JSImport("path", JSImport.Namespace)
object NodePath extends js.Object:
  def join(paths: String*): String = js.native
  def resolve(paths: String*): String = js.native
  def dirname(path: String): String = js.native
  def basename(path: String): String = js.native

/**
 * Node.js `os` module facade.
 */
@js.native
@JSImport("os", JSImport.Namespace)
object NodeOS extends js.Object:
  def tmpdir(): String = js.native

/**
 * Node.js `child_process` module facade for spawning processes.
 */
@js.native
@JSImport("child_process", JSImport.Namespace)
object NodeChildProcess extends js.Object:
  def spawnSync(command: String, args: js.Array[String], options: js.Object): SpawnSyncResult = js.native
  def execSync(command: String, options: js.Object): String = js.native

/**
 * Result from spawnSync.
 */
@js.native
trait SpawnSyncResult extends js.Object:
  val status: js.UndefOr[Int] = js.native
  val stdout: js.Any = js.native
  val stderr: js.Any = js.native
  val error: js.UndefOr[js.Error] = js.native

/**
 * Node.js `process` global object facade.
 */
@js.native
@JSGlobal("process")
object NodeProcess extends js.Object:
  def cwd(): String = js.native
  val env: js.Dictionary[String] = js.native
  def exit(code: Int): Nothing = js.native

/**
 * Node.js `url` module facade for file URL handling.
 */
@js.native
@JSImport("url", JSImport.Namespace)
object NodeUrl extends js.Object:
  def pathToFileURL(path: String): NodeURLObject = js.native
  def fileURLToPath(url: String): String = js.native

@js.native
trait NodeURLObject extends js.Object:
  val href: String = js.native

/**
 * Helper object for creating common JS options.
 */
object NodeFSOptions:
  def mkdirRecursive: js.Object =
    js.Dynamic.literal(recursive = true)

  def rmRecursive: js.Object =
    js.Dynamic.literal(recursive = true, force = true)

  def spawnOptions(cwd: String): js.Object =
    js.Dynamic.literal(
      cwd = cwd,
      encoding = "utf-8",
      stdio = js.Array("pipe", "pipe", "pipe")
    )
