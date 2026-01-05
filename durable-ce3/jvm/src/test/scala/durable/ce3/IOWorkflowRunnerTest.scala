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

  test("FutureRunner returns NeedsBiggerRunner for IO activity, IORunner completes") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import durable.engine.NeedsBiggerRunner

    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-switch-test-1")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    // Create a workflow with an IO activity (requires IORunner)
    var ioExecuted = false
    val ioActivity = Durable.Activity[IO, Int, MemoryBackingStore](
      compute = () => IO { ioExecuted = true; 42 },
      tag = IOEffectTag.ioTag,
      storage = summon[DurableStorage[Int, MemoryBackingStore]],
      retryPolicy = RetryPolicy.default
    )

    val workflow = for
      a <- Durable.pure[Int](10)
      b <- ioActivity
    yield a + b

    // Run with FutureRunner - should return Left(NeedsBiggerRunner)
    val futureRunner = WorkflowSessionRunner.forFuture

    IO.fromFuture(IO(futureRunner.run(workflow, ctx))).flatMap { result =>
      assert(result.isLeft, s"Expected Left(NeedsBiggerRunner), got $result")
      val needsBigger = result.swap.toOption.get
      assertEquals(needsBigger.activityTag, IOEffectTag.ioTag)
      assert(!ioExecuted, "IO activity should not have executed yet")

      // Resume with IORunner from the saved state
      val ioRunner = WorkflowRunnerIO.apply
      ioRunner.resumeFrom(needsBigger.state).map { resumeResult =>
        assert(resumeResult.isRight, s"Expected Right(result), got $resumeResult")
        val sessionResult = resumeResult.toOption.get
        assert(sessionResult.isInstanceOf[WorkflowSessionResult.Completed[?]])
        val completed = sessionResult.asInstanceOf[WorkflowSessionResult.Completed[Int]]
        assertEquals(completed.value, 52)
        assert(ioExecuted, "IO activity should have executed")
      }
    }
  }

  test("FutureRunner handles Future activities, switches to IO only when needed") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import durable.engine.NeedsBiggerRunner

    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-switch-test-2")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var futureExecuted = false
    var ioExecuted = false

    // Workflow: Future activity -> IO activity -> Future activity
    val workflow = for
      a <- Durable.activity { futureExecuted = true; Future.successful(10) }
      b <- Durable.Activity[IO, Int, MemoryBackingStore](
        compute = () => IO { ioExecuted = true; 20 },
        tag = IOEffectTag.ioTag,
        storage = summon[DurableStorage[Int, MemoryBackingStore]],
        retryPolicy = RetryPolicy.default
      )
      c <- Durable.activity { Future.successful(12) }
    yield a + b + c

    val futureRunner = WorkflowSessionRunner.forFuture

    IO.fromFuture(IO(futureRunner.run(workflow, ctx))).flatMap { result =>
      // Future activity should have executed, then hit IO activity
      assert(futureExecuted, "Future activity should have executed before hitting IO")
      assert(!ioExecuted, "IO activity should not have executed by FutureRunner")
      assert(result.isLeft, s"Expected Left(NeedsBiggerRunner), got $result")

      val needsBigger = result.swap.toOption.get
      assertEquals(needsBigger.activityTag, IOEffectTag.ioTag)
      // Activity index should be 1 (after Future activity at index 0)
      assertEquals(needsBigger.state.activityIndex, 1)

      // Resume with IORunner
      val ioRunner = WorkflowRunnerIO.apply
      ioRunner.resumeFrom(needsBigger.state).map { resumeResult =>
        assert(resumeResult.isRight)
        val completed = resumeResult.toOption.get.asInstanceOf[WorkflowSessionResult.Completed[Int]]
        assertEquals(completed.value, 42)
        assert(ioExecuted, "IO activity should have executed")
      }
    }
  }

  test("IORunner handles mixed Future and IO activities without switching") {
    given backing: MemoryBackingStore = MemoryBackingStore()
    val workflowId = WorkflowId("io-mixed-test")
    val ctx = WorkflowSessionRunner.RunContext.fresh(workflowId)

    var futureCount = 0
    var ioCount = 0

    val workflow = for
      a <- Durable.activity { futureCount += 1; Future.successful(10) }
      b <- Durable.Activity[IO, Int, MemoryBackingStore](
        compute = () => IO { ioCount += 1; 20 },
        tag = IOEffectTag.ioTag,
        storage = summon[DurableStorage[Int, MemoryBackingStore]],
        retryPolicy = RetryPolicy.default
      )
      c <- Durable.activity { futureCount += 1; Future.successful(12) }
    yield a + b + c

    // IORunner can handle everything - no switching needed
    runner.run(workflow, ctx).map(_.toOption.get).map { result =>
      assertEquals(result, WorkflowSessionResult.Completed(workflowId, 42))
      assertEquals(futureCount, 2)
      assertEquals(ioCount, 1)
    }
  }

