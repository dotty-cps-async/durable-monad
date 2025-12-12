package example

import scala.scalajs.js

/**
 * Package object with convenient exports for durable computations.
 */
package object durable:
  export Durable.{pure, step, shutdown, isShutdown, isReplaying, isResumed, currentStep, execute, resume}
  export Durable.given
  export DurableSerializer.given
