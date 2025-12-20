# Ephemeral Resources

## Problem

Some values in workflows are **ephemeral** - they cannot be serialized and must be acquired fresh on each run:
- File handles
- Database transactions
- Network connections

Unlike activities (cached in journal), these need **bracket semantics**: acquire, use, release.

## Solution: DurableEphemeral[R]

Simple typeclass marking a type as needing cleanup:

```scala
trait DurableEphemeral[R]:
  def release(r: R): Unit

object DurableEphemeral:
  def apply[R](releaseFn: R => Unit): DurableEphemeral[R]
```

## Preprocessor Auto-Detection

When a val has `DurableEphemeral[R]` in scope, the preprocessor wraps the rest of the block in a bracket:

```scala
given DurableEphemeral[FileHandle] = DurableEphemeral(_.close())

async[Durable] {
  val file: FileHandle = openFile("data.txt")  // Has DurableEphemeral
  val content = file.read()                     // Activity - cached
  content
}  // file.close() called automatically
```

Transforms to:
```scala
await(Durable.withResourceSync(openFile("data.txt"), _.close()) { file =>
  val content = await(activitySync { file.read() })
  content
})
```

Key points:
- User's expression (`openFile(...)`) is used for acquisition
- Release function comes from the typeclass
- Rest of block becomes the `use` function

## Explicit Bracket

For one-off cases without a typeclass:

```scala
await(Durable.withResource(
  acquire = openFile("data.csv"),
  release = _.close()
) { file =>
  Durable.activitySync { processFile(file) }
})
```

## Runtime Behavior

| Phase | Resource | Activities |
|-------|----------|------------|
| Initial run | Acquired fresh | Executed, results cached |
| Resume/replay | Acquired fresh | Return cached results |
| Failure/suspend | Released | State preserved |

## ADT Representation

```scala
case WithSessionResource[R, B](
  acquire: WorkflowSessionRunner.RunContext => R,
  release: R => Unit,
  use: R => Durable[B]
) extends Durable[B]
```

Not journaled - executes on every run.
