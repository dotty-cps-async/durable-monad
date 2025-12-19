package durable

import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import cps.*

/**
 * Test for CpsMonadConversion[Future, Durable] with transparent inline apply.
 * Verifies that .await on Future works inside async[Durable].
 */
class MonadConversionTest extends FunSuite:

  import MemoryBackingStore.given
  given backend: MemoryBackingStore = MemoryBackingStore()
  // CpsMonadConversion[Future, Durable] is provided by MemoryBackingStore companion

  test("Future.await inside async[Durable] compiles and creates correct structure") {
    val future = Future.successful(42)

    val durable: Durable[Int] = async[Durable] {
      val x = future.await
      x + 1
    }

    // Should create FlatMap(Activity(...), ...)
    durable match
      case Durable.FlatMap(Durable.Activity(_, _, _), _) => () // ok
      case other => fail(s"Expected FlatMap(Activity(...), ...), got: $other")
  }

  test("multiple Future.await creates sequential activities") {
    val f1 = Future.successful(10)
    val f2 = Future.successful(20)

    val durable: Durable[Int] = async[Durable] {
      val x = f1.await
      val y = f2.await
      x + y
    }

    // Should be nested FlatMaps with Activities
    durable match
      case Durable.FlatMap(Durable.Activity(_, _, _), _) => () // ok - first layer
      case other => fail(s"Expected FlatMap structure, got: $other")
  }

  test("Future.await runs and caches correctly with runner") {
    import scala.concurrent.Await
    import scala.concurrent.duration._

    var callCount = 0
    def makeFuture(): Future[Int] = {
      callCount += 1
      Future.successful(42)
    }

    val workflowId = WorkflowId("test-future-await")
    val freshBackend = MemoryBackingStore()
    given MemoryBackingStore = freshBackend

    val durable: Durable[Int] = async[Durable] {
      val x = makeFuture().await
      x + 1
    }

    val ctx = RunContext.fresh(workflowId)(using freshBackend)
    val result = Await.result(
      WorkflowSessionRunner.run(durable, ctx),
      5.seconds
    )

    result match
      case WorkflowSessionResult.Completed(_, value) =>
        assertEquals(value, 43)
        assertEquals(callCount, 1, "Future should be called once")
        // Verify cached at index 0
        assert(freshBackend.get(workflowId, 0).isDefined, "Should cache at index 0")
      case other => fail(s"Expected Completed, got: $other")
  }

  test("Future.await replays cached RESULT with lazy Future creation") {
    import scala.concurrent.Await
    import scala.concurrent.duration._

    // NOTE: With preprocessor transformation, Future is wrapped in a thunk/lambda.
    // This means Future creation is deferred until the activity actually runs.
    // On replay, the cached result is used and the Future is never created.

    var futureCreationCount = 0
    var futureExecutionCount = 0

    def makeFuture(): Future[Int] = {
      futureCreationCount += 1
      Future {
        futureExecutionCount += 1
        42
      }
    }

    val workflowId = WorkflowId("test-future-replay")
    val freshBackend = MemoryBackingStore()
    given MemoryBackingStore = freshBackend

    // Pre-populate cache at index 0 with value 100
    freshBackend.put(workflowId, 0, Right(100))

    val durable: Durable[Int] = async[Durable] {
      val x = makeFuture().await
      x + 1
    }

    // With lazy creation, no Future should be created during tree building
    val creationBeforeRun = futureCreationCount
    assertEquals(creationBeforeRun, 0, "Future NOT created when building tree (lazy)")

    // Resume from index 1 (after the cached activity)
    val ctx = RunContext.resume(workflowId, 1)(using freshBackend)
    val result = Await.result(
      WorkflowSessionRunner.run(durable, ctx),
      5.seconds
    )

    result match
      case WorkflowSessionResult.Completed(_, value) =>
        // Should use cached value 100 + 1 = 101
        assertEquals(value, 101, "Should use cached value 100 + 1")
        // Future creation should STILL be 0 because we replayed from cache
        assertEquals(futureCreationCount, 0, "Future not created during replay")
        // No execution during replay
        assertEquals(futureExecutionCount, 0, "No execution during replay")
      case other => fail(s"Expected Completed, got: $other")
  }
