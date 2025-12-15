package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}
import java.io.IOException
import munit.FunSuite
import cps.*

import durable.runtime.Scheduler

/**
 * Tests for try/catch behavior in Durable workflows.
 */
class TryCatchTest extends FunSuite:

  given ExecutionContext = ExecutionContext.global
  import MemoryBackingStore.given

  // Use immediate scheduler for fast tests
  val testConfig = RunConfig(scheduler = Scheduler.immediate)

  test("try/catch catches error from activity") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        await(Durable.failed[Int](RuntimeException("boom")))
      } catch {
        case e: RuntimeException => 42
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-1"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(42))
    }
  }

  test("try/catch catches error from failing activity computation") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        await(Durable.activity(Future.failed(RuntimeException("activity failed")), RetryPolicy.noRetry))
      } catch {
        case e: RuntimeException => -1
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-2"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(-1))
    }
  }

  test("try/catch passes through success") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        await(Durable.pure(100))
      } catch {
        case e: RuntimeException => -1
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-3"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(100))
    }
  }

  test("uncaught error propagates to workflow failure") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // IOException is NOT a RuntimeException, so it won't be caught
    val workflow = async[Durable] {
      try {
        await(Durable.failed[Int](IOException("io error")))
      } catch {
        case e: RuntimeException => -1  // Only catches RuntimeException, not IOException
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-4"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowResult.Failed(e) =>
          assert(e.isInstanceOf[IOException])
          assertEquals(e.getMessage, "io error")
        case other =>
          fail(s"Expected Failed, got $other")
    }
  }

  test("nested try/catch - inner catches") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        try {
          await(Durable.failed[Int](RuntimeException("inner")))
        } catch {
          case e: RuntimeException => 10
        }
      } catch {
        case e: RuntimeException => 20
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-5"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(10))  // Inner catch handles it
    }
  }

  test("nested try/catch - outer catches when inner doesn't match") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    // IOException is NOT a RuntimeException, so inner catch won't match
    val workflow = async[Durable] {
      try {
        try {
          await(Durable.failed[Int](IOException("io error")))
        } catch {
          case e: RuntimeException => 10  // Won't match IOException
        }
      } catch {
        case e: IOException => 20
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-6"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(20))  // Outer catch handles it
    }
  }

  test("error in catch handler propagates") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        await(Durable.failed[Int](RuntimeException("original")))
      } catch {
        case e: RuntimeException =>
          throw IllegalStateException("in catch")
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-7"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      result match
        case WorkflowResult.Failed(e) =>
          assert(e.isInstanceOf[IllegalStateException])
          assertEquals(e.getMessage, "in catch")
        case other =>
          fail(s"Expected Failed, got $other")
    }
  }

  test("try/catch with activity - success is cached") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    var callCount = 0
    val workflow = async[Durable] {
      try {
        val x = await(Durable.activitySync {
          callCount += 1
          callCount * 10
        })
        x + 1
      } catch {
        case e: RuntimeException => -1
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-8"), testConfig)
    WorkflowRunner.run(workflow, ctx).flatMap { result =>
      assertEquals(result, WorkflowResult.Completed(11))
      assertEquals(callCount, 1)

      // Replay should use cached value - resumeFromIndex=1 means index 0 is replayed from cache
      val ctx2 = RunContext.resume(WorkflowId("try-catch-8"), 1, testConfig)
      WorkflowRunner.run(workflow, ctx2).map { result2 =>
        assertEquals(result2, WorkflowResult.Completed(11))
        assertEquals(callCount, 1)  // Not called again
      }
    }
  }

  test("error from local computation is caught") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val workflow = async[Durable] {
      try {
        await(Durable.local[Int](_ => throw RuntimeException("local failed")))
      } catch {
        case e: RuntimeException => 99
      }
    }

    val ctx = RunContext.fresh(WorkflowId("try-catch-9"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(99))
    }
  }

  test("FlatMapTry structure is created correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val inner = Durable.pure(42)
    val handler: Try[Int] => Durable[Int] = {
      case Success(v) => Durable.pure(v + 1)
      case Failure(e) => Durable.pure(-1)
    }

    val workflow = Durable.FlatMapTry(inner, handler)

    val ctx = RunContext.fresh(WorkflowId("try-catch-10"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(43))
    }
  }

  test("FlatMapTry handles failure correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()

    val inner = Durable.failed[Int](RuntimeException("test error"))
    val handler: Try[Int] => Durable[Int] = {
      case Success(v) => Durable.pure(v + 1)
      case Failure(e) => Durable.pure(-1)
    }

    val workflow = Durable.FlatMapTry(inner, handler)

    val ctx = RunContext.fresh(WorkflowId("try-catch-11"), testConfig)
    WorkflowRunner.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowResult.Completed(-1))
    }
  }
