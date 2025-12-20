# Environment Access (Dependency Injection)

## Problem

Workflows need access to shared services (databases, HTTP clients) but these cannot be serialized into the workflow journal.

## Solution: AppContext Integration

Use `scala-appcontext` for dependency injection. Resources are cached globally and accessed fresh on each workflow run.

## Durable.env[R]

Get a resource from AppContext:

```scala
given AppContextProvider[Database] = AppContextProvider.of(Database.connect(url))

async[Durable] {
  val db = await(Durable.env[Database])  // From AppContext cache
  db.query("SELECT ...")                  // Activity - cached
}
```

- Resource is cached in `AppContext.Cache` (process lifetime)
- Same instance returned within a workflow run
- Fresh lookup on each run/resume

## Architecture

```
┌─────────────────────────────────────────┐
│           WorkflowEngine                 │
│                                          │
│  AppContext.Cache ◄── created on start  │
│         │                                │
│         ▼                                │
│  ┌─────────────┐                        │
│  │ WorkflowSessionRunner.RunContext  │                        │
│  │ + appContext│                        │
│  └──────┬──────┘                        │
│         │                                │
│         ▼                                │
│  Durable.env[Database]                  │
│    └─► ctx.appContext.getOrCreate[R]    │
└─────────────────────────────────────────┘
```

## Configuration

```scala
// Engine configuration includes AppContext
case class WorkflowEngineConfig(
  runConfig: WorkflowSessionRunner.RunConfig = WorkflowSessionRunner.RunConfig.default,
  appContext: AppContext.Cache = AppContext.newCache
)

// Create engine with providers
given AppContextProvider[Database] = ...
given AppContextProvider[HttpClient] = ...

val engine = WorkflowEngine(storage, config)
```

## On Process Restart

```scala
// Fresh AppContext on restart
val freshContext = AppContext.newCache
val engine = WorkflowEngine(storage, config.copy(appContext = freshContext))
engine.recover()  // Workflows resume with fresh resources
```

## Difference from Resources

| Aspect | `Durable.env[R]` | `DurableEphemeral[R]` |
|--------|------------------|----------------------|
| Scope | Process lifetime | Block scope |
| Release | None (cached) | At end of block |
| Use case | Shared services | Transactions, files |
