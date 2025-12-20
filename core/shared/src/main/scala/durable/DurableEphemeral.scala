package durable

/**
 * Typeclass for ephemeral (non-cacheable) resources that need lifecycle management.
 *
 * Unlike values with `DurableStorage[T, S]` which are cached in the workflow journal,
 * values with `DurableEphemeral[T]` are:
 *   - NOT cached (acquired fresh on each run/resume)
 *   - Released at end of scope (bracket pattern)
 *
 * When the preprocessor detects a val with type R that has DurableEphemeral[R],
 * it wraps the rest of the block in a bracket that calls release at the end.
 *
 * For getting resources from context, use `Durable.env[R]` with `AppContextProvider[R]`
 * (orthogonal concern).
 *
 * Use cases:
 *   - File handles: release closes the file
 *   - Transactions: release rolls back if not committed
 *   - Network connections: release closes the connection
 *
 * Example:
 * {{{
 * given DurableEphemeral[FileHandle] = DurableEphemeral(_.close())
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
trait DurableEphemeral[R]:
  /**
   * Release the resource.
   * Called at end of scope (success, failure, or suspension).
   */
  def release(r: R): Unit

object DurableEphemeral:
  /**
   * Create a DurableEphemeral from a release function.
   */
  def apply[R](releaseFn: R => Unit): DurableEphemeral[R] =
    new DurableEphemeral[R]:
      def release(r: R): Unit = releaseFn(r)
