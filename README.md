# durable-monad

A Scala library for writing fault-tolerant, long-running workflows as ordinary code.

## Why?

Your process crashes. The server restarts. The network fails mid-request. With durable workflows, your code picks up exactly where it left off—no manual checkpointing, no complex state machines.

```scala
import durable.*
import cps.*
import scala.concurrent.duration.*

// Event: customer requests cancellation
case class CancelOrder(reason: String) derives DurableEventName

// Order fulfillment that survives crashes and handles cancellation
object OrderWorkflow extends DurableFunction1[OrderId, OrderResult, MemoryBackingStore]
    derives DurableFunctionName:
  import MemoryBackingStore.given
  override val functionName = DurableFunction.register(this)

  def apply(orderId: OrderId)(using MemoryBackingStore): Durable[OrderResult] =
    async[Durable] {
      // Each step is automatically persisted and won't re-execute on replay
      val payment = paymentService.charge(orderId).await
      val inventory = inventoryService.reserve(orderId).await

      // Wait 2 days for shipping window—but allow cancellation
      val result = await((Event[CancelOrder] | TimeReached.after(2.days)).receive)

      result match
        case CancelOrder(reason) =>
          // Customer cancelled—Loss covered by compensation logic
          paymentService.refund(orderId).await
          inventoryService.release(orderId).await
          OrderResult.Cancelled(reason)

        case _: TimeReached =>
          // Shipping window reached—proceed with delivery
          val shipment = shippingService.ship(orderId).await
          OrderResult.Shipped(shipment.trackingId)
    }
```

**What happens on crash?** Activities (`charge`, `refund`, `ship`) execute once—replay returns cached results. Timers and event subscriptions persist—a 2-day wait continues from where it was. If a `CancelOrder` event arrives (via `engine.sendEventTo(workflowId, CancelOrder("changed mind"))`), the workflow wakes up and handles it.

## Installation

```scala
libraryDependencies += "io.github.dotty-cps-async" %%% "durable-monad-core" % "0.1.0"
```

## Documentation

- [Quick Start](docs/quick-start.md) — Birthday reminder workflow with events and timers
- [User Manual](docs/user-manual.md) — Complete API reference
- [How It Works](docs/blogpost/durable-execution-at-home.md) — The free monad approach explained
- [Related Projects](docs/references.md) — Temporal, Restate, Azure Durable Functions, and more

## Key Features

- **Durable timers**: `Durable.sleep(30.days)` survives restarts
- **Event handling**: Wait for external signals with `Event[E].receive`
- **Automatic replay**: Failed processes resume from last checkpoint
- **Cross-platform**: JVM, Scala.js, Scala Native
- **No external dependencies**: Runs embedded, no separate server required

## License

Apache 2.0
