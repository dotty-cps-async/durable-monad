package example.scalavm

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/**
 * State of a durable script execution.
 */
object DurableState:
  val Running = "running"
  val Completed = "completed"
  val Shutdown = "shutdown"  // Gracefully stopped, can resume
  val Failed = "failed"

/**
 * Metadata stored with durable script state.
 */
trait DurableMetadata extends js.Object:
  val scriptId: String
  val entryPoint: String
  val status: String
  val startedAt: String
  val updatedAt: String
  val step: Int
  val totalSteps: js.UndefOr[Int]

/**
 * Full durable state including metadata and user data.
 */
trait DurableSnapshot extends js.Object:
  val metadata: DurableMetadata
  val data: js.Dynamic

/**
 * Helper to create durable state objects.
 */
object DurableSnapshot:
  def create(
    scriptId: String,
    entryPoint: String,
    status: String,
    step: Int,
    data: js.Dynamic,
    totalSteps: js.UndefOr[Int] = js.undefined
  ): DurableSnapshot =
    val now = new js.Date().toISOString()
    js.Dynamic.literal(
      metadata = js.Dynamic.literal(
        scriptId = scriptId,
        entryPoint = entryPoint,
        status = status,
        startedAt = now,
        updatedAt = now,
        step = step,
        totalSteps = totalSteps
      ),
      data = data
    ).asInstanceOf[DurableSnapshot]

  def update(
    previous: DurableSnapshot,
    status: String,
    step: Int,
    data: js.Dynamic
  ): DurableSnapshot =
    js.Dynamic.literal(
      metadata = js.Dynamic.literal(
        scriptId = previous.metadata.scriptId,
        entryPoint = previous.metadata.entryPoint,
        status = status,
        startedAt = previous.metadata.startedAt,
        updatedAt = new js.Date().toISOString(),
        step = step,
        totalSteps = previous.metadata.totalSteps
      ),
      data = data
    ).asInstanceOf[DurableSnapshot]
