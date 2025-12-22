package durable

/**
 * Helper class for counting executions in tests without using var assignments.
 * Method calls like counter.increment() are allowed in durable workflows,
 * unlike direct assignments (counter += 1).
 */
class TestCounter:
  private var _count = 0
  def increment(): Unit = _count += 1
  def get: Int = _count
  def reset(): Unit = _count = 0
  def value: Int = _count
