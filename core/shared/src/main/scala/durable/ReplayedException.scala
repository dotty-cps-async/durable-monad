package durable

import scala.reflect.ClassTag
import scala.util.Try

/**
 * Represents a stored failure for deterministic replay.
 * Contains only easily serializable fields (strings).
 *
 * @param className Fully qualified class name of the original exception
 * @param message The exception message
 * @param stackTrace Optional stack trace as string (available on JVM, may be limited on JS/Native)
 */
case class StoredFailure(
  className: String,
  message: String,
  stackTrace: Option[String] = None
)

object StoredFailure:
  /** Create StoredFailure from a Throwable */
  def fromThrowable(e: Throwable): StoredFailure =
    StoredFailure(
      className = e.getClass.getName,
      message = Option(e.getMessage).getOrElse(""),
      stackTrace = captureStackTrace(e)
    )

  /** Safely capture stack trace - may not be available on all platforms */
  private def captureStackTrace(e: Throwable): Option[String] =
    Try {
      val trace = e.getStackTrace
      if trace != null && trace.nonEmpty then
        Some(trace.map(_.toString).mkString("\n"))
      else
        None
    }.getOrElse(None)

/**
 * Exception wrapper used during replay when the original exception
 * cannot be reconstructed.
 *
 * On replay, if an activity previously failed, this exception is thrown
 * with the stored failure information. Catch blocks are transformed by
 * the preprocessor to match both the original exception type and this wrapper.
 *
 * @param stored The stored failure information
 */
class ReplayedException(val stored: StoredFailure)
    extends Exception(s"${stored.className}: ${stored.message}"):

  /** The fully qualified class name of the original exception */
  def originalClassName: String = stored.className

  /** The message from the original exception */
  def originalMessage: String = stored.message

  /** The stack trace from the original exception (if stored) */
  def originalStackTrace: Option[String] = stored.stackTrace

object ReplayedException:
  /** Create a ReplayedException from a Throwable */
  def apply(e: Throwable): ReplayedException =
    new ReplayedException(StoredFailure.fromThrowable(e))

  /** Create a ReplayedException from StoredFailure */
  def apply(stored: StoredFailure): ReplayedException =
    new ReplayedException(stored)

  /**
   * Extractor for pattern matching on original exception type.
   * Used by the preprocessor to transform catch blocks.
   *
   * Usage in transformed catch:
   *   case e @ (_: SocketException | ReplayedException.Of[SocketException](_)) => ...
   */
  class Of[T <: Throwable](using ct: ClassTag[T]):
    def unapply(e: Throwable): Option[ReplayedException] = e match
      case re: ReplayedException if re.originalClassName == ct.runtimeClass.getName => Some(re)
      case _ => None

  object Of:
    def apply[T <: Throwable](using ct: ClassTag[T]): Of[T] = new Of[T]

  /**
   * Check if throwable matches a type (either directly or as ReplayedException).
   * Useful for when user needs to check type without pattern matching.
   */
  def matches[T <: Throwable](e: Throwable)(using ct: ClassTag[T]): Boolean =
    ct.runtimeClass.isInstance(e) || (e match
      case re: ReplayedException => re.originalClassName == ct.runtimeClass.getName
      case _ => false
    )

  /**
   * Get the effective message from either original or replayed exception.
   */
  def messageOf(e: Throwable): String = e match
    case re: ReplayedException => re.originalMessage
    case other => Option(other.getMessage).getOrElse("")

  /**
   * Get the effective class name from either original or replayed exception.
   */
  def classNameOf(e: Throwable): String = e match
    case re: ReplayedException => re.originalClassName
    case other => other.getClass.getName
