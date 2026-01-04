package durable.ce3

import scala.concurrent.Future

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import durable.*
import durable.engine.{WorkflowSessionRunner, WorkflowSessionResult}

/**
 * Tests for WorkflowSessionRunner[IO] - the IO-based interpreter for Durable Free Monad.
 */
class IOWorkflowRunnerTest extends CatsEffectSuite:

  import MemoryBackingStore.given
  private val runner = WorkflowRunnerIO.apply

  def withStorage[A](f: MemoryBackingStore ?=> IO[A]): IO[A] =
    given backing: MemoryBackingStore = MemoryBackingStore()
    f

  test("run pure value with IO runner") {
    withStorage {
      val workflowId = WorkflowId("io-test-1")
      val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = Durable.pure[Int](42)
      runner.run(workflow, ctx).map(_.toOption.get).map { result =>
        assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      }
    }
  }

  test("run flatMap with IO runner") {
    withStorage {
      val workflowId = WorkflowId("io-test-2")
      val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = for
        a <- Durable.pure[Int](10)
        b <- Durable.pure[Int](32)
      yield a + b

      runner.run(workflow, ctx).map(_.toOption.get).map { result =>
        assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      }
    }
  }

  test("run Future activity - executes and caches") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-test-3")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(42)
    }

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 1)
      assertEquals(backing.size, 1)
    }
  }

  test("run Future activity - replays from cache") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    backing.put(WorkflowId("io-test-4"), 0, Right(42))

    val workflowId = WorkflowId("io-test-4")
    val ctx = WorkflowSessionRunner.RunContext.resume(workflowId, 1, 0)

    var executeCount = 0
    val workflow = Durable.activity {
      executeCount += 1
      Future.successful(999)
    }

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 0)
    }
  }

  test("run multiple activities in sequence") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-test-5")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var executeCount = 0
    val workflow = for
      a <- Durable.activity { executeCount += 1; Future.successful(10) }
      b <- Durable.activity { executeCount += 1; Future.successful(20) }
      c <- Durable.activity { executeCount += 1; Future.successful(12) }
    yield a + b + c

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
      assertEquals(executeCount, 3)
      assertEquals(backing.size, 3)
    }
  }

  test("run suspend on event") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    given DurableEventName[String] = DurableEventName("waiting for signal")
    val workflowId = WorkflowId("io-test-6")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = for
      a <- Durable.pure[Int](10)
      _ <- Durable.awaitEvent[String, MemoryBackingStore]
      b <- Durable.pure[Int](32)
    yield a + b

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assert(result.isInstanceOf[WorkflowSessionResult.Suspended[?]])
      val suspended = result.asInstanceOf[WorkflowSessionResult.Suspended[?]]
      assert(suspended.condition.hasEvent("waiting for signal"))
    }
  }

  test("run error") {
    withStorage {
      val workflowId = WorkflowId("io-test-7")
      val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

      val workflow = for
        a <- Durable.pure[Int](10)
        _ <- Durable.failed[Int](RuntimeException("test error"))
        b <- Durable.pure[Int](32)
      yield a + b

      runner.run(workflow, ctx).map(_.toOption.get).map { result =>
        assert(result.isInstanceOf[WorkflowSessionResult.Failed])
        val failed = result.asInstanceOf[WorkflowSessionResult.Failed]
        assertEquals(failed.error.originalMessage, "test error")
      }
    }
  }

  test("IO runner handles Future activities correctly") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-test-8")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = for
      a <- Durable.activity { Future.successful(10) }
      b <- Durable.activity { Future.successful(32) }
    yield a + b

    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
    }
  }

  test("WorkflowRunnerIO.run convenience method works") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-test-9")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    val workflow = Durable.pure[Int](42)

    WorkflowRunnerIO.run(workflow, ctx).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(ctx.workflowId, 42))
    }
  }

