package durable

import scala.compiletime.summonFrom

/**
 * Marker trait for ephemeral types that should not be cached in workflow journal.
 *
 * Values with `DurableEphemeral[T]` are acquired fresh on each run/resume.
 * This is used for types that cannot be serialized (connections, file handles, etc).
 *
 * For types that need cleanup/release, use `DurableEphemeralResource[T]` instead.
 *
 * When using `derives DurableEphemeral`:
 *   - If the type extends `AutoCloseable`, returns `DurableEphemeralResource` with auto-close
 *   - Otherwise, returns plain `DurableEphemeral` (no resource wrapping)
 *
 * @tparam R Resource type
 */
trait DurableEphemeral[R]

/**
 * Default implementation of DurableEphemeral marker trait.
 */
class DurableEphemeralImpl[R] extends DurableEphemeral[R]

/**
 * Ephemeral resource with lifecycle management (bracket pattern).
 *
 * Unlike plain `DurableEphemeral[T]` which is just a marker,
 * `DurableEphemeralResource[T]` indicates the resource needs cleanup.
 *
 * When the preprocessor detects a val with type R that has `DurableEphemeralResource[R]`,
 * it wraps the rest of the block in a bracket that calls release at the end.
 *
 * Use cases:
 *   - File handles: release closes the file
 *   - Transactions: release rolls back if not committed
 *   - Network connections: release closes the connection
 *
 * Example:
 * {{{
 * given DurableEphemeralResource[FileHandle] = DurableEphemeralResource(_.close())
 *
 * async[Durable] {
 *   val file: FileHandle = openFile("data.txt")  // Auto-detected, released at scope end
 *   val content = file.read()                     // Activity - cached
 *   content
 * }  // file.close() called automatically
 * }}}
 *
 * @tparam R Resource type
 */
trait DurableEphemeralResource[R] extends DurableEphemeral[R]:
  /**
   * Release the resource.
   * Called at end of scope (success, failure, or suspension).
   */
  def release(r: R): Unit

/**
 * DurableEphemeralResource implementation for AutoCloseable types.
 */
class DurableEphemeralResourceAutoCloseable[R <: AutoCloseable] extends DurableEphemeralResource[R]:
  def release(r: R): Unit = r.close()

/**
 * DurableEphemeralResource implementation with custom release function.
 */
class DurableEphemeralResourceImpl[R](releaseFn: R => Unit) extends DurableEphemeralResource[R]:
  def release(r: R): Unit = releaseFn(r)

object DurableEphemeral:
  /**
   * Derive a DurableEphemeral for a type.
   *
   * If the type extends `AutoCloseable`, returns `DurableEphemeralResource`
   * with automatic close. Otherwise, returns plain `DurableEphemeral` marker.
   *
   * Example:
   * {{{
   * class MyService derives DurableEphemeral:
   *   def doSomething(): Unit = ???
   * // Returns DurableEphemeral[MyService] - no resource wrapping
   *
   * class MyFileHandle extends AutoCloseable derives DurableEphemeral:
   *   def close(): Unit = ???
   * // Returns DurableEphemeralResource[MyFileHandle] - wrapped with auto-close
   * }}}
   */
  transparent inline def derived[T]: DurableEphemeral[T] =
    summonFrom {
      case _: (T <:< AutoCloseable) =>
        new DurableEphemeralResourceImpl[T](r => r.asInstanceOf[AutoCloseable].close())
      case _ =>
        new DurableEphemeralImpl[T]
    }

object DurableEphemeralResource:
  /**
   * Create a DurableEphemeralResource from a release function.
   */
  def apply[R](releaseFn: R => Unit): DurableEphemeralResource[R] =
    new DurableEphemeralResourceImpl[R](releaseFn)

  /**
   * Derive a DurableEphemeralResource for AutoCloseable types.
   * Use `derives DurableEphemeralResource` on classes that extend AutoCloseable.
   *
   * Example:
   * {{{
   * class MyFileHandle extends AutoCloseable derives DurableEphemeralResource:
   *   def close(): Unit = ???
   * // close() is called automatically at end of scope
   * }}}
   */
  inline def derived[T <: AutoCloseable]: DurableEphemeralResource[T] =
    new DurableEphemeralResourceAutoCloseable[T]
