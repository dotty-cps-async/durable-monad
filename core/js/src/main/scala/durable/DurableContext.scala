package example.durable

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.collection.mutable

/**
 * Execution context for durable computations.
 * Tracks step counter, caches results, and manages replay.
 *
 * The replay-based execution model:
 * 1. First run: execute each step, cache results
 * 2. On shutdown: save step index + cached results
 * 3. On resume: replay from beginning, return cached results until reaching saved step
 * 4. Continue actual execution from saved step
 */
class DurableContext private (
  private var stepIndex: Int,
  private var resumeFromStep: Int,
  private val cachedResults: mutable.Map[Int, js.Any],
  private var _isShutdown: Boolean,
  private var _shutdownData: Option[js.Dynamic]
):

  /** Current step being executed */
  def currentStep: Int = stepIndex

  /** Whether we're replaying cached results */
  def isReplaying: Boolean = stepIndex < resumeFromStep

  /** Whether this is a resumed execution (vs fresh start) */
  def isResumed: Boolean = resumeFromStep > 0

  /** Whether shutdown was requested */
  def isShutdown: Boolean = _isShutdown

  /** Data saved at shutdown */
  def shutdownData: Option[js.Dynamic] = _shutdownData

  /**
   * Execute a step. If replaying, returns cached result.
   * Otherwise executes and caches.
   */
  def executeStep[A](compute: => A)(using serializer: DurableSerializer[A]): A =
    val step = stepIndex
    stepIndex += 1

    if step < resumeFromStep then
      // Replaying - return cached result
      val cached = cachedResults.getOrElse(step,
        throw new RuntimeException(s"Missing cached result for step $step during replay"))
      serializer.deserialize(cached)
    else
      // Actual execution
      val result = compute
      cachedResults(step) = serializer.serialize(result)
      result

  /**
   * Request shutdown. Saves current state.
   */
  def shutdown(): Unit =
    _isShutdown = true

  /**
   * Create a checkpoint without stopping.
   */
  def checkpoint(): Unit =
    // Just ensures current state is cached (already done by executeStep)
    ()

  /**
   * Serialize context state for persistence.
   */
  def toSnapshot: DurableContextSnapshot =
    DurableContextSnapshot(
      stepIndex = stepIndex,
      cachedResults = cachedResults.toMap
    )

object DurableContext:
  /** Create a fresh context for new execution */
  def fresh(): DurableContext =
    new DurableContext(
      stepIndex = 0,
      resumeFromStep = 0,
      cachedResults = mutable.Map.empty,
      _isShutdown = false,
      _shutdownData = None
    )

  /** Create a context for resuming from snapshot */
  def fromSnapshot(snapshot: DurableContextSnapshot): DurableContext =
    new DurableContext(
      stepIndex = 0,
      resumeFromStep = snapshot.stepIndex,
      cachedResults = mutable.Map.from(snapshot.cachedResults),
      _isShutdown = false,
      _shutdownData = None
    )

/**
 * Serializable snapshot of DurableContext state.
 */
case class DurableContextSnapshot(
  stepIndex: Int,
  cachedResults: Map[Int, js.Any]
):
  /** Convert to JS object for JSON serialization */
  def toJS: js.Dynamic =
    js.Dynamic.literal(
      stepIndex = stepIndex,
      cachedResults = js.Dictionary(cachedResults.map { case (k, v) => k.toString -> v }.toSeq*)
    )

object DurableContextSnapshot:
  /** Parse from JS object */
  def fromJS(obj: js.Dynamic): DurableContextSnapshot =
    val stepIndex = obj.stepIndex.asInstanceOf[Int]
    val resultsDict = obj.cachedResults.asInstanceOf[js.Dictionary[js.Any]]
    val cachedResults = resultsDict.toMap.map { case (k, v) => k.toInt -> v }
    DurableContextSnapshot(stepIndex, cachedResults)
