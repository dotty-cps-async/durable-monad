# Cats-Effect Integration Design

## Overview

This document describes the design for integrating durable-monad with cats-effect.

The key insight: **interpret workflows directly with a generic runner, switch runners dynamically when a "bigger" effect is encountered**.

```
WorkflowEngine (Future-based coordination)
       │
       └── Start interpretation with WorkflowSessionRunner[Future]
           │
           ├── Activity[Future] or Activity[CpsIdentity] → handle in Future
           │
           └── Activity[IO] encountered → returns Left(NeedsBiggerRunner)
               │
               └── Engine switches to WorkflowSessionRunner[IO], resumes
```

## Design Principles

1. **Single AST**: `Durable[A]` with `EffectTag[F]` in activities
2. **Generic Runner**: `WorkflowSessionRunner[G[_]]` parameterized over effect type
3. **Dynamic runner switching**: Start with Future runner, upgrade to IO when needed
4. **Effect widening**: When F1 > F (F1 can accept F), switch to F1's runner
5. **CpsAsyncMonad boundary**: Uses dotty-cps-async type classes for effect operations
6. **Engine stays Future-based**: Coordination, storage, timers use Future

## Why This Design?

The fundamental asymmetry:

```
Future → IO   ✓ easy (IO.fromFuture)
IO → Future   ✗ hard (needs IORuntime, unsafe at each call)
```

Effect hierarchy:
```
IO > Future > CpsIdentity

WorkflowSessionRunner[IO]:     handles IO, Future (lifted), CpsIdentity (lifted)
WorkflowSessionRunner[Future]: handles Future, CpsIdentity (lifted)
                               cannot handle IO!
```

When FutureRunner encounters Activity[IO]:
- Future runner can't convert IO → Future
- But IO runner CAN convert Future → IO
- Returns `Left(NeedsBiggerRunner)`, engine switches to IO runner

## Part 1: EffectTag - Target-Centric Conversion

Each `EffectTag[G]` knows how to convert FROM source effects to G.

**Key insight**: The target effect defines what sources it can accept:
- `futureTag.canAccept(cpsIdentityTag)` → true
- `futureTag.canAccept(ioTag)` → false
- `ioTag.canAccept(futureTag)` → true (IO is "bigger")

```scala
// core/shared/src/main/scala/durable/runtime/EffectTag.scala

trait EffectTag[G[_]]:
  def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, G]]
  def canAccept[F[_]](source: EffectTag[F]): Boolean = conversionFrom(source).isDefined

object EffectTag:
  given cpsIdentityTag: EffectTag[CpsIdentity] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, CpsIdentity]] =
      source match
        case _: cpsIdentityTag.type => Some(identityConversion)
        case _ => None  // CpsIdentity can only accept CpsIdentity

  given futureTag: EffectTag[Future] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, Future]] =
      source match
        case _: cpsIdentityTag.type => Some(cpsIdentityToFutureConversion)
        case _: futureTag.type => Some(identityConversion)
        case _ => None  // Future can't accept IO
```

## Part 2: Generic WorkflowSessionRunner

The runner is parameterized over effect type G[_] with CpsAsyncMonad[G].

```scala
// core/shared/src/main/scala/durable/engine/WorkflowSessionRunner.scala

/**
 * Generic interpreter for Durable workflows.
 *
 * Returns G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]:
 * - Right(result): Workflow completed/suspended/failed
 * - Left(needsBigger): Need to switch to a bigger runner
 */
class WorkflowSessionRunner[G[_]](
  val targetTag: EffectTag[G]
)(using val monad: CpsAsyncMonad[G]):

  /**
   * Lift a Future[A] into G[A].
   * Uses monad.adoptCallbackStyle to convert callback-based Future to G.
   */
  def liftFuture[A](fa: Future[A]): G[A] =
    monad.adoptCallbackStyle[A](callback =>
      fa.onComplete(callback)(ExecutionContext.parasitic)
    )

  /**
   * Run a workflow to completion or suspension.
   */
  def run[A](
    workflow: Durable[A],
    ctx: RunContext
  )(using S <: DurableStorageBackend, backend: S): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]

  /**
   * Resume from saved state (after runner switch).
   */
  def resumeFrom[A](savedState: RunnerState): G[Either[NeedsBiggerRunner, WorkflowSessionResult[A]]]

object WorkflowSessionRunner:
  /** Create a Future-based runner */
  def forFuture(using ExecutionContext): WorkflowSessionRunner[Future] =
    import cps.monads.FutureAsyncMonad
    new WorkflowSessionRunner[Future](EffectTag.futureTag)
```

## Part 3: NeedsBiggerRunner Signal

When a runner encounters an effect it can't handle, it returns `Left(NeedsBiggerRunner)`:

```scala
/**
 * Signal that the runner needs to switch to a "bigger" effect.
 */
case class NeedsBiggerRunner(
  activityTag: EffectTag[?],
  state: RunnerState
)

/**
 * Saved interpreter state for runner switching.
 */
case class RunnerState(
  currentNode: Durable[?],
  stack: List[StackFrame],
  activityIndex: Int,
  ctx: RunContext
)
```

## Part 4: Activity Handling

When processing an Activity, the runner checks effect compatibility:

```scala
private def handleActivity[A, F[_], S <: DurableStorageBackend](
  activity: Durable.Activity[F, A, S]
): G[Either[NeedsBiggerRunner, A]] =
  targetTag.conversionFrom(activity.tag) match
    case Some(conversion) =>
      // Can handle - convert and execute
      val computeG: G[A] = conversion(activity.compute())
      monad.map(computeG)(Right(_))

    case None =>
      // Can't handle - check if activity's effect is "bigger"
      if activity.tag.canAccept(targetTag) then
        // Activity's effect is bigger (e.g., IO when we're Future)
        // Return signal to switch to bigger runner
        monad.pure(Left(NeedsBiggerRunner(activity.tag, savedState)))
      else
        // Incompatible effects - error
        monad.error(IncompatibleEffectError(...))
```

## Part 5: IOEffectTag (durable-ce3)

IO can accept all standard effects:

```scala
// durable-ce3/shared/src/main/scala/durable/ce3/IOEffectTag.scala

given cpsIdentityToIOConversion: CpsMonadConversion[CpsIdentity, IO] with
  def apply[T](ft: T): IO[T] = IO.pure(ft)

given futureToIOConversion: CpsMonadConversion[Future, IO] with
  def apply[T](ft: Future[T]): IO[T] = IO.fromFuture(IO.pure(ft))

object IOEffectTag:
  given ioTag: EffectTag[IO] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, IO]] =
      source match
        case _: EffectTag.cpsIdentityTag.type =>
          Some(cpsIdentityToIOConversion)
        case _: EffectTag.futureTag.type =>
          Some(futureToIOConversion)
        case _: ioTag.type =>
          Some(identityConversion)
        case _ => None
```

## Part 6: WorkflowRunnerIO Factory

```scala
// durable-ce3/shared/src/main/scala/durable/ce3/WorkflowRunnerIO.scala

object WorkflowRunnerIO:
  /**
   * Create an IO-based runner.
   * Uses CpsAsyncMonad[IO] from dotty-cps-async-cats-effect.
   */
  def apply(using runtime: IORuntime): WorkflowSessionRunner[IO] =
    import cps.monads.catsEffect.{*, given}
    new WorkflowSessionRunner[IO](IOEffectTag.ioTag)

  /**
   * Run a workflow in IO.
   * Since IO can handle all effects, this never returns Left(NeedsBiggerRunner).
   */
  def run[A](
    workflow: Durable[A],
    ctx: RunContext
  )(using IORuntime, S <: DurableStorageBackend, backend: S): IO[WorkflowSessionResult[A]] =
    val runner = apply
    runner.run(workflow, ctx).flatMap {
      case Right(result) => IO.pure(result)
      case Left(needsBigger) =>
        IO.raiseError(RuntimeException(s"Unexpected: IORunner cannot handle ${needsBigger.activityTag}"))
    }
```

## Part 7: Engine Integration

The WorkflowEngine handles runner switching:

```scala
// In WorkflowEngineImpl

private val futureRunner = WorkflowSessionRunner.forFuture

private def runWorkflow[A](workflow: Durable[A], ctx: RunContext): Future[WorkflowSessionResult[A]] =
  futureRunner.run(workflow, ctx).flatMap {
    case Right(result) => Future.successful(result)
    case Left(needsBigger) =>
      // Need IO runner - requires durable-ce3 dependency
      Future.failed(RuntimeException(
        s"Workflow requires effect ${needsBigger.activityTag} which is not supported. " +
        "Add durable-ce3 dependency for IO support."
      ))
  }
```

## Summary

```
┌────────────────────────────────────────────────────────────────────┐
│                    WorkflowEngine (Future-based)                    │
│                                                                    │
│  1. Receive Durable[A] (with EffectTag in activities)              │
│  2. Start interpretation with WorkflowSessionRunner[Future]        │
│  3. If Activity[IO] encountered:                                   │
│     a. Runner returns Left(NeedsBiggerRunner(ioTag, state))        │
│     b. Engine switches to WorkflowSessionRunner[IO]                │
│     c. IO runner resumes from state, lifts pending Futures to IO   │
│  4. Runner returns G[Either[NeedsBiggerRunner, Result]]            │
│  5. Handle result (Completed/Suspended/Failed/ContinueAs)          │
└────────────────────────────────────────────────────────────────────┘

Effect Widening Flow:
  WorkflowSessionRunner[Future]      WorkflowSessionRunner[IO]
       │                                      │
  Activity[Future] ✓                          │
  Activity[CpsIdentity] ✓                     │
  Activity[IO] ✗ ────Left(NeedsBiggerRunner)─►│ Resume here
       │                                 Activity[IO] ✓
       │                                 Activity[Future] ✓ (lifted)
       │                                 Activity[CpsIdentity] ✓ (lifted)
```

## Module Structure

```
durable-monad/
├── core/shared/src/main/scala/durable/
│   ├── Durable.scala              # AST with EffectTag in activities
│   ├── runtime/
│   │   └── EffectTag.scala        # Effect tagging (CpsIdentity, Future)
│   ├── engine/
│   │   └── WorkflowSessionRunner.scala  # Generic runner + NeedsBiggerRunner
│   └── ...
│
└── durable-ce3/shared/src/main/scala/durable/ce3/
    ├── IOEffectTag.scala          # IO effect tag with conversions
    ├── IODurableAsync.scala       # DurableAsync[IO] implementation
    └── WorkflowRunnerIO.scala     # Factory for WorkflowSessionRunner[IO]
```

## build.sbt

```scala
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    name := "durable-monad-core",
    libraryDependencies ++= Seq(
      "io.github.dotty-cps-async" %%% "dotty-cps-async" % cpsAsyncVersion,
      // NO cats-effect dependency
    )
  )

lazy val durableCe3 = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("durable-ce3"))
  .dependsOn(core)
  .settings(
    name := "durable-monad-ce3",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.5.7",
      "io.github.dotty-cps-async" %%% "cps-async-connect-cats-effect" % "1.0.0",
    )
  )
```

## Key Differences from Initial Design

1. **No separate EffectRunner trait** - `WorkflowSessionRunner[G]` is the runner
2. **No callback-based API** - Returns `G[Either[NeedsBiggerRunner, Result]]` directly
3. **Uses CpsAsyncMonad** - From dotty-cps-async for monad operations
4. **No Dispatcher required** - CpsAsyncMonad[IO] handles IO execution
5. **Factory pattern** - `WorkflowRunnerIO.apply` creates IO runners
