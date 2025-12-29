# We have Durable Execution at home.

Durable execution is on the rise — pioneered by Azure Durable Functions, now engines like Temporal and Restate are gaining traction for managing long-running workflows.
When something becomes popular, we can build a monad for it.

Let's start with the famous example: a reminder email during customer onboarding. After the customer is registered, we wait 2 days, then send a reminder if the customer hasn't used our app.

We want the following code to work:

```scala
object CustomerOnboardingWorkflow extends DurableFunction1[CustomerId, OnboardingResult, MemoryBackingStore]
    derives DurableFunctionName:
  override val functionName = DurableFunction.register(this)

  def apply(customerId: CustomerId)(using MemoryBackingStore): Durable[OnboardingResult] =
    async[Durable] {
      
      val emailService = Durable.appContext[EmailService].await
      val activityService = Durable.appContext[UserActivityService].await

      // Step 1: Send welcome email
      emailService.sendWelcomeEmail(customerId).await

      // Step 2: Wait 2 days
      Durable.sleep(2.days).await

      // Step 3: Check if customer used the app, send reminder if not
      if !activityService.hasUsedApp(customerId) then
        emailService.sendReminderEmail(customerId).await
        OnboardingResult.ReminderSent
      else
        OnboardingResult.ActiveUser
    }
```


[full example with service definitions](https://github.com/dotty-cps-async/durable-monad/tree/main/core/shared/src/test/scala/durable/examples/onboarding)


## How this can be done?

There are theoretically two methods for durable executions: replay-based and snapshot-based.

- With a replay-based approach, we wrap the computations into a cache, and when computation reaches a suspension point, we wait for some event or time elapses, and we stop computation. Then, when an external signal wakes up our computation, we will replay our monad up to the suspension point from the cached trace. (Temporal, Restate, Azure Durable Functions, AWS Step Functions, DBOS, Resonate HQ, Inngest, workflows4s, durable-monad)
- With a snapshot-based approach, we rely on runtime, which allows us to make a snapshot of a running process and then restore it. (Golem Cloud, ICP/Motoko — both WebAssembly-based)

Most durable execution engines today use replay semantics. Widely available industrial language runtimes do not support persistent snapshotting. JVM world is not an exception; there is a CRaC (Coordinated Restore at Checkpoint) project in OpenJDK [https://openjdk.org/projects/crac/], but it is still at a relatively early stage.

See also [related solutions and references](https://github.com/dotty-cps-async/durable-monad/blob/main/docs/references.md).

### Preprocessing

 Ok, so first step - to wrap each statement into cacheable steps.    
 Latest version of dotty-cps-async allows to specify preprocessor for monadic brackets as follows: 

```Scala

object Durable:
 
  /**
   * Automatically wraps val definitions with activity calls for replay-based execution.
   */
  given durablePreprocessor[C <: DurableContext]: CpsPreprocessor[Durable, C] with
    transparent inline def preprocess[A](inline body: A, inline ctx: C): A =
      ${ DurablePreprocessor.impl[A, C]('body, 'ctx) }

  ...

 ```

It will transform code inside our monad brackets in something like:

```
def apply(customerId: CustomerId)(using MemoryBackingStore): Durable[OnboardingResult] =
    async[Durable] {
      // Val RHSes with .await on Durable[T] - unchanged (identity conversion)
      val emailService = Durable.appContext[EmailService].await
      val activityService = Durable.appContext[UserActivityService].await

      // converts Future[Unit].await → await(Activity(...))
      await(Durable.Activity[Unit, MemoryBackingStore](
        () => emailService.sendWelcomeEmail(customerId),
        summon[DurableStorage[Unit, MemoryBackingStore]],
        RetryPolicy.default,
        SourcePos("CustomerOnboardingWorkflow.scala", 36)
      ))

      // sleep returns Durable[Instant] - unchanged
      Durable.sleep(2.days).await

      // If condition wrapped with activitySync
      if await(ctx.activitySync[Boolean, MemoryBackingStore](
        !activityService.hasUsedApp(customerId),
        RetryPolicy.default,
        SourcePos("CustomerOnboardingWorkflow.scala", 42)
      )) then
        // sendReminderEmail returns Future[Unit] - transformed to Activity
        await(Durable.Activity[Unit, MemoryBackingStore](
          () => emailService.sendReminderEmail(customerId),
          summon[DurableStorage[Unit, MemoryBackingStore]],
          RetryPolicy.default,
          SourcePos("CustomerOnboardingWorkflow.scala", 43)
        ))
        OnboardingResult.ReminderSent  // return position - not wrapped
      else
        OnboardingResult.ActiveUser    // return position - not wrapped
    }
```

Terminology: what's executed in DurableFunction is called a workflow, and steps are called activities.
Each activity during evaluation is rerun on recoverable failures and caches results.


### Classify values

Note that each activity, when created, requires the existence of a typeclass DurableStorage[T, S], where
T is return type,  S is DurableStorageBackend. We assume that developers will have a project-wide backend and implement the generation of appropriate typeclasses. Having project-specific storage makes a durable monad convenient to embed in applications.  For example, if you have domain instances mapped to a relational database, then you can use an immutable value User in a workflow and store it as userId, and make a join on retrieval.

Every value in the workflow should have DurableStorage[T,Store] to be cached, or DurableEphemeral[T] to be restored during first usage (like services in our examples). Also, we allow only immutable values.  

### Looping via restart

For loops we can restart workflow with different arguments with the help of `continueWith` and `continueAs` methods. `continueWith` restarts current durable function with new arguments, `continueAs` switches the workflow to run another durable function.

  So, checking the customer's monthly subscription will look as follows: 

```
def apply(subscriptionId: SubscriptionId, totalBilled: BigDecimal, cyclesCompleted: Int)(
    using MemoryBackingStore
  ): Durable[SubscriptionResult] =
    async[Durable] {
        billingService.chargeSubscription(subscriptionId).await match
          case BillingResult.Success(invoiceId, amount) =>
            notificationService.sendInvoice(subscriptionId, invoiceId).await
            Durable.sleep(30.days).await
            await(continueWith(subscriptionId, totalBilled+amount, cyclesCompleted+1))
          case BillingResult.Failed(reason) =>
            notificationService.sendPaymentFailedNotice(subscriptionId, reason).await
            await(SubscriptionRetryWorkflow.continueAs(subscriptionId, totalBilled, cyclesCompleted, 1))
          case BillingResult.Cancelled =>
            notificationService.sendCancellationNotice(subscriptionId).await
            SubscriptionResult.Cancelled(cyclesCompleted)
    }
```

Note that we can interpret the set of arguments during looping as a state and think about our durable function as an actor.

### Interpretation

Internally, Durable is a free monad whose algebra includes Activity, Suspend, and LocalComputation operations.  Interpretation is straightforward—the runner takes a trace and monadic value, producing an updated trace and partial result (usually a suspended value).

```Scala
enum Durable[A]:
  case Pure(value: A)
  case FlatMap[A, B](fa: Durable[A], f: A => Durable[B]) extends Durable[B]
  case Error(error: Throwable)

  // Sync computation with optional caching
  case LocalComputation[A, S <: DurableStorageBackend](
    compute: RunContext => A,
    storage: Option[DurableStorage[A, S]]
  ) extends Durable[A]

  // Async operation - cached and retried
  case Activity[A, S <: DurableStorageBackend](
    compute: () => Future[A],
    storage: DurableStorage[A, S],
    retryPolicy: RetryPolicy
  ) extends Durable[A]

  // Wait for timer or external event
  case Suspend[A, S <: DurableStorageBackend](
    condition: EventQuery.Combined[A, S]
  ) extends Durable[A]

  // Restart workflow with new arguments (for looping)
  case ContinueAs[A](
    metadata: WorkflowMetadata,
    storeArgs: (Backend, WorkflowId, ExecutionContext) => Future[Unit],
    workflow: () => Durable[A]
  ) extends Durable[A]

  // ... plus FlatMapTry, FlatMapCached, AsyncActivity, WithSessionResource
```

The runner maintains an activity index and walks through the monadic structure. On each operation:
- Activity: check storage at current index—if cached, return stored value; otherwise execute with retries, cache, increment index
- LocalComputation: execute immediately; cache if storage provided, otherwise re-execute on replay (for ephemeral resources)
- Suspend: return control to the engine with a wait condition and continuation
- ContinueAs: clear activity storage and restart with new arguments
- and so on. 

On resume, cached activities return instantly, and execution continues from the suspension point.

### Workflow Engine

The next non-trivial part is the workflow engine, which manages events and recreates durable functions.
Durable functions are instantiated from the object name — during engine startup they're registered in a global registry. 
Environmental entities (such as services) can be received by type-driven dependency injection.  
Configurations and shared AppContext we can keep in WorkflowEngine and pass to dependency resolver, using app-context tagless free facilities:

```
  // AppContextAsyncProvider[Durable, T] - derived from RunContext (has cache + config)
  given (using runCtxProvider: AppContextAsyncProvider[Durable, WorkflowSessionRunner.RunContext]):
      AppContextAsyncProvider[Durable, EmailService] with
    def get: Durable[EmailService] =
      runCtxProvider.get.map { ctx =>
        ctx.appContextCache.get[EmailService].getOrElse {
          // Can check ctx.configSource for test/prod mode
          new EmailService
        }
      }
``` 

Besides time, we can work with custom durable events. 
For example,  below is workflow of auction which process bid events until timeout:

```
def apply(
    item: AuctionItem,
    endTime: Instant,
    currentHighBid: Option[Bid],
    bidCount: Int
  )(using MemoryBackingStore): Durable[AuctionResult] =
    async[Durable] {

      val result = await((Event[Bid] | TimeReached.at(endTime)).receive)

      result match
        case bid: Bid =>
          val newHighBid = 
              if currentHighBid.forall(_ < bid.amount) then Some(bid) else currentHighBid
          await(continueWith(item, endTime, newHighBid, bidCount + 1))

        case _: TimeReached =>
          currentHighBid match
            case None =>
              AuctionResult.NoBids
            case Some(bid) =>
              if bid.amount >= item.reservePrice then
                 AuctionResult.Sold(bid.winnerId, bid.amount, bidCount)
              else
                 AuctionResult.ReserveNotMet(amount, item.reservePrice)
    }

```

Events are stored in the same user-supplied backend storage. To prevent race conditions, state is managed via coordinator. Currently we sequence all updates in one thread, which limits scalability, but helps us avoid diving too fast into the wonderful world of multithreading debugging.
For high-volume workloads, you'll probably want to implement sharding and operation fusion — we accept patches ;)

## Model Limitations

### Idempotency

The most problematic part is the external world. If an external endpoint is not idempotent, we can have duplicated side effects during replays. 
Imagine that an email service sends an email during activity execution, but the activity fails after that — during replay the email will be sent again. 
To avoid this, you can use the outbox pattern (see https://microservices.io/patterns/data/transactional-outbox.html), or implement activities as idempotent operations.

### Ephemeral consistency

Values marked as `DurableEphemeral` are re-acquired on each replay. 
They must return consistent resources (e.g., same service instance, same connection pool), otherwise behavior may differ between runs. 
Note that cached calls of `Random()` or `System.currentTimeMillis()` are fine — once cached, replay returns the same result, making them effectively deterministic.

### Versioning

Changing workflow logic while workflows are in-flight requires care. 
Old traces may not match new code paths, causing replay failures. 
One workaround is deploying each new version on a separate container with its own database, letting old workflows drain before decommissioning.

## Building in the era of AI

This project is also an experiment in vibe-coding: most of the code was not written by hand, but produced by LLM (mostly Claude Code with Opus-4.5) in spec-driven development.

The main bottleneck is review (same as with humans). Most of my writing was not Scala but text files — brainstorming ideas moved to design docs and then to implementation. You can find some design overviews in docs/design.

In short, heavy LLM-assisted software engineering is possible. It's nontrivial — requires discipline — but eventually more powerful than traditional craft. Sometimes I feel like a road worker with a shovel seeing an excavator for the first time.

---

The library is available at [github.com/dotty-cps-async/durable-monad](https://github.com/dotty-cps-async/durable-monad).
