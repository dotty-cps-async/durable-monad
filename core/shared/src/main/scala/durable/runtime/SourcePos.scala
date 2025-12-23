package durable.runtime

/**
 * Source position for tracing - captures where an activity was created.
 * Used for debugging preprocessor transformations and activity execution.
 *
 * @param file Source file name
 * @param line Line number (1-based)
 */
case class SourcePos(file: String, line: Int):
  override def toString: String = s"$file:$line"

object SourcePos:
  val unknown: SourcePos = SourcePos("<unknown>", 0)
