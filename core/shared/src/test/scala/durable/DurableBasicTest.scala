package durable

import munit.FunSuite

/**
 * Tests for Durable Free Monad structure.
 * These test the data structure, not execution (which requires a runner).
 */
class DurableBasicTest extends FunSuite:

  // Provide storage via given (MemoryBackingStore pattern)
  import MemoryBackingStore.given
  val backing: MemoryBackingStore = MemoryBackingStore()
  given MemoryBackingStore = backing

  test("pure value") {
    val durable: Durable[Int] = Durable.pure(42)

    durable match
      case Durable.Pure(v) => assertEquals(v, 42)
      case _ => fail("Expected Pure")
  }

  test("map creates FlatMap with Pure") {
    val durable = Durable.pure[Int](21).map(_ * 2)

    durable match
      case Durable.FlatMap(Durable.Pure(21), _) => () // ok
      case _ => fail("Expected FlatMap(Pure(21), ...)")
  }

  test("flatMap creates FlatMap") {
    val durable = for
      a <- Durable.pure[Int](10)
      b <- Durable.pure[Int](32)
    yield a + b

    // Should be FlatMap(Pure(10), f) where f(10) = FlatMap(Pure(32), g)
    durable match
      case Durable.FlatMap(Durable.Pure(10), _) => () // ok
      case _ => fail("Expected FlatMap(Pure(10), ...)")
  }

  test("local creates LocalComputation node") {
    var computed = false
    val durable = Durable.local[Int] { _ =>
      computed = true
      42
    }

    // LocalComputation is lazy - compute should not be called yet
    assertEquals(computed, false)

    durable match
      case Durable.LocalComputation(_) => () // ok
      case _ => fail("Expected LocalComputation")
  }

  test("activity creates Activity node") {
    import scala.concurrent.Future
    var computed = false
    val durable = Durable.activity {
      computed = true
      Future.successful(42)
    }

    // Activity is lazy - compute should not be called yet
    assertEquals(computed, false)

    durable match
      case Durable.Activity(_, _, _) => () // ok - now has 3 fields (compute, storage, retryPolicy)
      case _ => fail("Expected Activity(...)")
  }

  test("activitySync creates Activity node") {
    var computed = false
    val durable = Durable.activitySync {
      computed = true
      42
    }

    // Activity is lazy - compute should not be called yet
    assertEquals(computed, false)

    durable match
      case Durable.Activity(_, _, _) => () // ok - now has 3 fields (compute, storage, retryPolicy)
      case _ => fail("Expected Activity(...)")
  }

  test("suspend creates Suspend node") {
    given DurableEventName[String] = DurableEventName("test-event")
    val durable = Durable.awaitEvent[String, MemoryBackingStore]

    durable match
      case Durable.Suspend(combined) if combined.hasEvent("test-event") => () // ok - storage in Combined
      case _ => fail("Expected Suspend with Combined condition containing test-event")
  }

  test("error creates Error node") {
    val error = RuntimeException("test error")
    val durable = Durable.failed[Int](error)

    durable match
      case Durable.Error(e) => assertEquals(e.getMessage, "test error")
      case _ => fail("Expected Error")
  }

  test("complex workflow builds correct structure") {
    import scala.concurrent.Future
    given DurableEventName[String] = DurableEventName("wait")
    val durable = for
      a <- Durable.activity(Future.successful(10))
      b <- Durable.activity(Future.successful(20))
      _ <- Durable.awaitEvent[String, MemoryBackingStore]
      c <- Durable.activity(Future.successful(12))
    yield a + b + c

    // Just verify it compiles and creates a FlatMap chain
    durable match
      case Durable.FlatMap(Durable.Activity(_, _, _), _) => () // ok
      case _ => fail("Expected FlatMap(Activity(...), ...)")
  }
