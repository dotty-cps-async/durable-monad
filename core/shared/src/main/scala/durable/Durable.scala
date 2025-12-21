package durable

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Success, Failure}
import java.time.Instant
import cps.*
import com.github.rssh.appcontext.*
import durable.engine.{ConfigSource, WorkflowSessionRunner, WorkflowMetadata}

/**
 * Durable[A] - A Monad describing a durable computation.
 *
 * Type parameters:
 *   A - the result type
 *
 * Each Activity captures a DurableStorage[A, S] instance for caching.
 *
 * This is a data structure describing the computation, not executing it.
 * The runner (interpreter) handles execution, async operations, and storage.
 *
 * Usage with async/await:
 *   import MemoryBackingStore.given
 *   given backing: MemoryBackingStore = MemoryBackingStore()
 *   async[Durable] {
 *     val a = await(activity1)
 *     await(sleep(1.hour))        // suspends here
 *     val b = await(activity2(a))
 *     b
 *   }
 */
enum Durable[A]:
  /** Lift a value */
  case Pure(value: A)

  /** Sequencing */
  case FlatMap[A, B](fa: Durable[A], f: A => Durable[B]) extends Durable[B]

  /** Error */
  case Error(error: Throwable)

  /** Local computation - sync, deterministic, no caching needed. Has access to context. */
  case LocalComputation(compute: WorkflowSessionRunner.RunContext => A)

  /**
   * Activity (outbound operation).
   * Created during monad construction (by preprocessor or user code).
   * Index is assigned at runtime by the interpreter.
   *
   * On interpretation:
   *   - Runner assigns index (runtime counter)
   *   - Checks storage: if cached, returns cached value
   *   - If not cached: executes compute with retry policy, caches result, returns
   *   - Retries on recoverable failures according to retryPolicy
   *
   * Storage and retryPolicy are captured at creation time via given resolution.
   */
  case Activity[A, S <: DurableStorageBackend](
    compute: () => Future[A],
    storage: DurableStorage[A, S],
    retryPolicy: RetryPolicy
  ) extends Durable[A]

  /**
   * Suspend for external input - timer, event, child workflow, or combined.
   * All conditions are represented as EventQuery.Combined.
   * Storage is captured in the Combined for replay.
   */
  case Suspend[A, S <: DurableStorageBackend](
    condition: EventQuery.Combined[A, S]
  ) extends Durable[A]

  /** Try/catch semantics - handles both success and failure of fa */
  case FlatMapTry[A, B](fa: Durable[A], f: Try[A] => Durable[B]) extends Durable[B]

  /**
   * Continue as a new workflow - clears storage and restarts with new args.
   * Used for looping patterns since workflows are immutable.
   *
   * @param metadata New workflow metadata (functionName, argCount, activityIndex=argCount)
   * @param storeArgs Closure to store new args - takes (backend, workflowId, ec)
   * @param workflow Thunk to create the new workflow (lazy to avoid infinite recursion)
   */
  case ContinueAs[A](
    metadata: WorkflowMetadata,
    storeArgs: (DurableStorageBackend, WorkflowId, ExecutionContext) => Future[Unit],
    workflow: () => Durable[A]
  ) extends Durable[A]

  /**
   * Async activity - returns F[T] immediately, runs in parallel with subsequent code.
   * Created during monad construction (by preprocessor or user code).
   * Index is assigned at runtime by the interpreter.
   *
   * On interpretation:
   *   - Runner assigns index (runtime counter)
   *   - Delegates to DurableAsync.wrap which handles:
   *     - Cache check (replay) or execution with retry (fresh run)
   *     - Caching result when F completes
   *   - Returns F[T] immediately (parallel execution preserved)
   *
   * Storage, retryPolicy, and wrapper are captured at creation time via given resolution.
   */
  case AsyncActivity[F[_], T, S <: DurableStorageBackend](
    compute: () => F[T],
    storage: DurableStorage[T, S],
    retryPolicy: RetryPolicy,
    wrapper: DurableAsync[F]
  ) extends Durable[F[T]]

  /**
   * Resource acquisition with bracket semantics - NOT journaled.
   *
   * This case handles both environment access and scoped resources through a unified pattern.
   * The key insight: all resource patterns are acquire/use/release with different scoping:
   *   - `env[R]`: acquire from AppContext cache, no release, use = pure(r)
   *   - `withResource`: custom acquire/release, scoped use
   *   - `withEnv`: acquire/release from provider, scoped use
   *
   * Resource acquisition is NOT cached in the workflow journal because:
   *   - Resources are ephemeral (connections, clients) and cannot be serialized
   *   - On resume, resources must be acquired fresh
   *   - Only the results of operations USING resources (activities) are cached
   *
   * @tparam R Resource type
   * @tparam B Result type from using the resource
   * @param acquire Function to acquire the resource (has access to RunContext for appContext)
   * @param release Function to release the resource after use
   * @param use Function that uses the resource and returns a Durable workflow
   */
  case WithSessionResource[R, B](
    acquire: WorkflowSessionRunner.RunContext => R,
    release: R => Unit,
    use: R => Durable[B]
  ) extends Durable[B]

  def map[B](f: A => B): Durable[B] =
    Durable.FlatMap(this, (a: A) => Durable.Pure(f(a)))

  def flatMap[B](f: A => Durable[B]): Durable[B] =
    Durable.FlatMap(this, f)


object Durable:
  /** Lift a pure value */
  def pure[A](a: A): Durable[A] =
    Pure(a)

  /** Lift an error */
  def failed[A](e: Throwable): Durable[A] =
    Error(e)

  /** Local computation - sync, deterministic, no caching */
  def local[A](compute: WorkflowSessionRunner.RunContext => A): Durable[A] =
    LocalComputation(compute)

  // === Context access methods (shorthand for summon[DurableContext].xxx) ===

  /**
   * Access the full RunContext inside async[Durable] blocks.
   * NOT cached - fresh on each access during replay.
   */
  def runContext(using ctx: DurableContext): Durable[WorkflowSessionRunner.RunContext] =
    ctx.runContext

  /**
   * Access the workflow ID inside async[Durable] blocks.
   * NOT cached - fresh on each access during replay.
   */
  def workflowId(using ctx: DurableContext): Durable[WorkflowId] =
    ctx.workflowId

  /**
   * Access the storage backend inside async[Durable] blocks.
   * NOT cached - fresh on each access during replay.
   */
  def backend(using ctx: DurableContext): Durable[DurableStorageBackend] =
    ctx.backend

  /**
   * Access raw configuration for a section inside async[Durable] blocks.
   * NOT cached - configuration is always read fresh from ConfigSource.
   *
   * @param section Configuration section name (e.g., "database", "api.client")
   * @return Durable[Option[String]] - Some(config) if found, None otherwise
   */
  def configRaw(section: String)(using ctx: DurableContext): Durable[Option[String]] =
    ctx.configRaw(section)

  /**
   * Generic context accessor inside async[Durable] blocks.
   * NOT cached - fresh on each access during replay.
   */
  def context[A](f: WorkflowSessionRunner.RunContext => A)(using ctx: DurableContext): Durable[A] =
    ctx.context(f)

  // === Tagless-final support ===

  /**
   * Provides RunContext via the tagless-final pattern.
   * Uses LocalComputation so it's NOT cached - fresh on each access.
   *
   * Enables context-aware services using InAppContext:
   * {{{
   * trait MyLogger[F[_]: InAppContext[(WorkflowSessionRunner.RunContext *: EmptyTuple)]] {
   *   def log(msg: String): F[Unit]
   * }
   *
   * // Usage in workflow:
   * async[Durable] {
   *   val ctx = await(AppContext.asyncGet[Durable, WorkflowSessionRunner.RunContext])
   *   // or via InAppContext.get[Durable, WorkflowSessionRunner.RunContext]
   * }
   * }}}
   */
  given durableRunContextProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext] with
    def get: Durable[WorkflowSessionRunner.RunContext] = LocalComputation(ctx => ctx)

  /**
   * Get a resource from AppContext cache.
   *
   * Resources accessed via `env` are:
   *   - Cached globally in AppContext (shared across workflows)
   *   - Not released (no cleanup needed for pooled/global resources)
   *   - Acquired fresh on each workflow start/resume (not journaled)
   *
   * Example:
   * {{{
   * async[Durable] {
   *   val db = await(Durable.env[Database])  // From global pool
   *   db.query("SELECT ...")                  // Activity - cached
   * }
   * }}}
   *
   * @tparam R Resource type (must have AppContextProvider instance)
   * @return Durable workflow that yields the resource
   */
  inline def env[R](using provider: AppContextProvider[R]): Durable[R] =
    WithSessionResource[R, R](
      acquire = ctx => ctx.appContextCache.getOrCreate[R](provider.get),
      release = _ => (),  // No release for cached resources
      use = r => Durable.pure(r)
    )

  /**
   * Scoped resource with explicit acquire/release (bracket pattern).
   *
   * The resource is:
   *   - Acquired at the start of the bracket
   *   - Released at the end (success or failure)
   *   - NOT journaled (ephemeral)
   *
   * Example:
   * {{{
   * async[Durable] {
   *   await(Durable.withResource(
   *     acquire = openFile("data.csv"),
   *     release = _.close()
   *   ) { file =>
   *     Durable.activitySync { processFile(file) }
   *   })
   * }
   * }}}
   *
   * @tparam R Resource type
   * @tparam A Result type
   * @param acquire Function to acquire the resource
   * @param release Function to release the resource
   * @param use Function that uses the resource and returns a Durable workflow
   * @return Durable workflow that manages the resource lifecycle
   */
  def withResource[R, A](acquire: => R, release: R => Unit)(use: R => Durable[A]): Durable[A] =
    WithSessionResource[R, A](
      acquire = _ => acquire,
      release = release,
      use = use
    )

  /**
   * Sync version of withResource - use function returns A, not Durable[A].
   * Used by preprocessor when transforming ephemeral resource vals.
   *
   * When used inside async[Durable] blocks with await calls in the use function,
   * dotty-cps-async will automatically substitute this with withResourceSync_async.
   */
  def withResourceSync[R, A](acquire: => R, release: R => Unit)(use: R => A): Durable[A] =
    WithSessionResource[R, A](
      acquire = _ => acquire,
      release = release,
      use = r => Durable.pure(use(r))
    )

  /**
   * Async version of withResourceSync - called by dotty-cps-async when use function contains awaits.
   * The signature matches CPS-transformed parameters:
   *   - acquire: => R becomes () => Durable[R]
   *   - release: R => Unit becomes R => Durable[Unit]
   *   - use: R => A becomes R => Durable[A]
   *
   * Uses FlatMapTry to ensure release is called on both success and failure (bracket semantics).
   */
  def withResourceSync_async[R, A](acquire: () => Durable[R], release: R => Durable[Unit])(use: R => Durable[A]): Durable[A] =
    import scala.util.{Try, Success, Failure}
    acquire().flatMap { r =>
      FlatMapTry[A, A](
        use(r),
        (result: Try[A]) => result match {
          case Success(a) => release(r).map(_ => a)
          case Failure(e) => release(r).flatMap(_ => Durable.failed(e))
        }
      )
    }

  /**
   * Create an activity - async operation that will be cached.
   * Index is assigned at runtime by the interpreter.
   * Storage is captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activity[A, S <: DurableStorageBackend](compute: => Future[A], policy: RetryPolicy = RetryPolicy.default)
                 (using storage: DurableStorage[A, S]): Durable[A] =
    Activity(() => compute, storage, policy)

  /**
   * Create an activity from a synchronous computation.
   * Convenience method that wraps the result in Future.successful.
   * Storage is captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activitySync[A, S <: DurableStorageBackend](compute: => A, policy: RetryPolicy = RetryPolicy.default)
                     (using storage: DurableStorage[A, S]): Durable[A] =
    Activity(() => Future.fromTry(scala.util.Try(compute)), storage, policy)

  /**
   * Create an async activity - returns F[T] immediately, runs in parallel.
   * The result T is cached when F completes.
   * Storage and wrapper are captured via given resolution.
   * Policy defaults to RetryPolicy.default.
   */
  def activityAsync[F[_], T, S <: DurableStorageBackend](compute: => F[T], policy: RetryPolicy = RetryPolicy.default)
                      (using wrapper: DurableAsync[F], storage: DurableStorage[T, S]): Durable[F[T]] =
    AsyncActivity(() => compute, storage, policy, wrapper)

  /** Suspend the workflow with a combined query (condition already contains storage) */
  def suspend[A, S <: DurableStorageBackend](condition: EventQuery.Combined[A, S]): Durable[A] =
    Suspend(condition)

  /** Sleep until a specific instant, returns actual wake time */
  def sleepUntil[S <: DurableStorageBackend](wakeAt: Instant)(using storage: DurableStorage[TimeReached, S]): Durable[Instant] =
    Suspend(EventQuery.Combined[TimeReached, S](
      events = Map.empty,
      timerAt = Some((wakeAt, storage)),
      workflows = Map.empty
    )).map(_.firedAt)

  /** Sleep for a duration, returns actual wake time */
  def sleep[S <: DurableStorageBackend](duration: FiniteDuration)(using storage: DurableStorage[TimeReached, S]): Durable[Instant] =
    sleepUntil(Instant.now().plusMillis(duration.toMillis))

  /** Wait for a broadcast event of type E (requires explicit backend type) */
  def awaitEvent[E, S <: DurableStorageBackend](using eventName: DurableEventName[E], storage: DurableStorage[E, S]): Durable[E] =
    Suspend(EventQuery.Combined[E, S](
      events = Map(eventName.name -> storage),
      timerAt = None,
      workflows = Map.empty
    ))

  /**
   * Continue as a different workflow with the given arguments.
   * Clears activity storage, stores new args, and restarts with the target workflow.
   *
   * This enables workflow transitions - switching from one workflow to another,
   * or continuing as the same workflow with new arguments (for loops).
   *
   * Example:
   * {{{
   * object WorkflowA extends DurableFunction1[Int, String, S] derives DurableFunctionName:
   *   def apply(n: Int)(using S): Durable[String] = async[Durable] {
   *     if n > 10 then
   *       await(WorkflowB.continueAs(s"value-$n"))  // extension syntax
   *     else
   *       "done"
   *   }
   * }}}
   *
   * @param target The target DurableFunction to continue as
   * @param args Tuple of arguments for the target workflow
   * @return Durable[R] - the result type of the target workflow
   */
  def continueAs[Args <: Tuple, R, S <: DurableStorageBackend](
    target: DurableFunction[Args, R, S]
  )(args: Args)(using storageBackend: S): Durable[R] =
    val argsStorage = target.argsStorage
    val argCount = argsStorage.size
    val metadata = WorkflowMetadata(target.functionName.value, argCount, argCount)
    val storeArgsFn = (backend: DurableStorageBackend, wfId: WorkflowId, ec: ExecutionContext) =>
      given ExecutionContext = ec
      argsStorage.storeAll(backend.asInstanceOf[S], wfId, 0, args)
    ContinueAs(metadata, storeArgsFn, () => target.applyTupled(args))

  /**
   * CpsMonadContext for Durable - provides context for async/await.
   * Also provides activity methods for the preprocessor to wrap val definitions.
   * Additionally provides context access methods for accessing RunContext at runtime.
   */
  class DurableContext extends CpsTryMonadContext[[A] =>> Durable[A]]:
    def monad: CpsTryMonad[[A] =>> Durable[A]] = durableCpsTryMonad

    /**
     * Create an activity from an async computation.
     * Used by preprocessor to wrap val definitions.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activity[A, S <: DurableStorageBackend](compute: => Future[A], policy: RetryPolicy)
                   (using storage: DurableStorage[A, S]): Durable[A] =
      Durable.Activity(() => compute, storage, policy)

    /**
     * Create an activity from a synchronous computation.
     * Wraps the result in Future.successful.
     * Used by preprocessor to wrap val definitions.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activitySync[A, S <: DurableStorageBackend](compute: => A, policy: RetryPolicy)
                       (using storage: DurableStorage[A, S]): Durable[A] =
      Durable.Activity(() => Future.fromTry(scala.util.Try(compute)), storage, policy)

    /**
     * Async variant of activitySync for dotty-cps-async.
     * Called when there's an await inside the activitySync block.
     * The by-name `=> A` becomes `() => Durable[A]` after CPS transformation.
     */
    def activitySync_async[A, S <: DurableStorageBackend](compute: () => Durable[A], policy: RetryPolicy)
                             (using storage: DurableStorage[A, S]): Durable[A] =
      compute()

    /**
     * Async variant of activity for dotty-cps-async.
     * Called when there's an await inside the activity block.
     * The by-name `=> Future[A]` becomes `() => Durable[Future[A]]` after CPS transformation.
     */
    def activity_async[A, S <: DurableStorageBackend](compute: () => Durable[Future[A]], policy: RetryPolicy)
                         (using storage: DurableStorage[A, S]): Durable[A] =
      compute().flatMap(fut => Durable.Activity(() => fut, storage, policy))

    /**
     * Create an async activity from an effect F[T].
     * Used by preprocessor to wrap val definitions of type F[T].
     * Returns F[T] immediately, runs in parallel with subsequent code.
     * Takes explicit policy parameter - preprocessor passes RetryPolicy.default.
     */
    def activityAsync[F[_], T, S <: DurableStorageBackend](compute: => F[T], policy: RetryPolicy)
                        (using wrapper: DurableAsync[F], storage: DurableStorage[T, S]): Durable[F[T]] =
      Durable.AsyncActivity(() => compute, storage, policy, wrapper)

    // === Context access methods (NOT cached - uses LocalComputation) ===

    /**
     * Access the full RunContext at runtime.
     * NOT cached - fresh on each access during replay.
     */
    def runContext: Durable[WorkflowSessionRunner.RunContext] =
      Durable.LocalComputation(ctx => ctx)

    /**
     * Access the workflow ID at runtime.
     * NOT cached - fresh on each access during replay.
     */
    def workflowId: Durable[WorkflowId] =
      Durable.LocalComputation(_.workflowId)

    /**
     * Access the storage backend at runtime.
     * NOT cached - fresh on each access during replay.
     */
    def backend: Durable[DurableStorageBackend] =
      Durable.LocalComputation(_.backend)

    /**
     * Access the AppContext cache at runtime.
     * NOT cached - fresh on each access during replay.
     */
    def appContextCache: Durable[AppContext.Cache] =
      Durable.LocalComputation(_.appContextCache)

    /**
     * Access raw configuration for a section.
     * NOT cached - configuration is always read fresh from ConfigSource.
     *
     * @param section Configuration section name (e.g., "database", "api.client")
     * @return Durable[Option[String]] - Some(config) if found, None otherwise
     */
    def configRaw(section: String): Durable[Option[String]] =
      Durable.LocalComputation(ctx => ctx.configSource.getRaw(section))

    /**
     * Generic context accessor.
     * NOT cached - fresh on each access during replay.
     */
    def context[A](f: WorkflowSessionRunner.RunContext => A): Durable[A] =
      Durable.LocalComputation(f)

  /**
   * CpsTryMonad instance for async/await syntax.
   */
  given durableCpsTryMonad: CpsTryMonad[[A] =>> Durable[A]] with
    type Context = DurableContext

    def pure[A](a: A): Durable[A] = Durable.pure(a)

    def map[A, B](fa: Durable[A])(f: A => B): Durable[B] =
      fa.map(f)

    def flatMap[A, B](fa: Durable[A])(f: A => Durable[B]): Durable[B] =
      fa.flatMap(f)

    def error[A](e: Throwable): Durable[A] =
      Durable.failed(e)

    def flatMapTry[A, B](fa: Durable[A])(f: Try[A] => Durable[B]): Durable[B] =
      FlatMapTry(fa, f)

    def apply[A](op: Context => Durable[A]): Durable[A] =
      op(new DurableContext)

  /**
   * CpsPreprocessor for Durable monad - available via companion object.
   * Automatically wraps val definitions with activity calls for replay-based execution.
   */
  given durablePreprocessor[C <: DurableContext]: CpsPreprocessor[Durable, C] with
    transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
      ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

  /**
   * Formal conversion for any F[_] that can convert to Future.
   * Passes type-checking; actual transform done by preprocessor.
   * Uses inline + compiletime.error for compile-time failure if not transformed.
   *
   * Note: For await(Durable[T]), dotty-cps-async's identityConversion handles it
   * because there's no CpsMonadConversion[Durable, Future].
   */
  given durableFormalConversion[F[_], S <: DurableStorageBackend](using
      backend: S,
      toFuture: CpsMonadConversion[F, Future]
  ): CpsMonadConversion[F, Durable] with
    def apply[T](fa: F[T]): Durable[T] =
      throw RuntimeException(
        "F[_].await should be transformed by DurablePreprocessor. " +
        "This indicates the preprocessor failed to intercept the await call."
      )


