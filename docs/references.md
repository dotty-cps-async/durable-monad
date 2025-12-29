# Durable Execution: Related Solutions and References

Here are some (not all) frameworks that provide durable execution:

| Framework | Approach |
|-----------|----------|
| [Temporal](https://temporal.io/) | Annotation-based interfaces (`@WorkflowInterface`/`@ActivityInterface`). Dedicated server. |
| [Restate](https://www.restate.dev/) | Handler-based (`restate.workflow({ handlers: ... })`). Lightweight, cloud-native. |
| [Azure Durable Functions](https://learn.microsoft.com/en-us/azure/azure-functions/durable/) | Orchestrator/activity pattern. Serverless, Azure-integrated. |
| [AWS Step Functions](https://aws.amazon.com/step-functions/) | State machine with JSON/YAML (Amazon States Language). Serverless, AWS-integrated. |
| [Cloudflare Workflows](https://developers.cloudflare.com/workflows/) | Step-based (`WorkflowStep`). Edge-native, Cloudflare-integrated. |
| [DBOS](https://www.dbos.dev/) | Database-oriented, transactions built-in. Postgres-backed. |
| [Resonate HQ](https://www.resonatehq.io/) | Distributed async/await with durable promises. Closest in spirit to durable-monad. |
| [Inngest](https://www.inngest.com/) | Step functions for serverless. Event-driven. |
| [Golem Cloud](https://www.golem.cloud/) | WebAssembly snapshotting (not replay). Compiles existing code to WASM. |
| [ICP/Motoko](https://internetcomputer.org/docs/motoko/home) | Orthogonal persistence. WebAssembly, blockchain-specific. |
| [workflows4s](https://github.com/business4s/workflows4s) | DSL with event-sourced interpretation. BPMN diagram generation. |
| durable-monad | Async/await syntax — write regular code, preprocessor handles durability. |

## Prior Art

- [GemStone/S](https://gemtalksystems.com/) — persistent Smalltalk with ACID transactions
- [Racket stateless servlets](https://docs.racket-lang.org/web-server/stateless.html) — serializable continuations for web applications

## Articles

### Foundational

- Hector Garcia-Molina and Kenneth Salem. 1987. [Sagas](https://dl.acm.org/doi/10.1145/38713.38742). SIGMOD '87: Proceedings of the 1987 ACM SIGMOD international conference on Management of data, pages 249-259. ([PDF](https://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf))

- Malcolm Atkinson, Peter Bailey, Ken Chisholm, Paul Cockshott, and Ron Morrison. 1983. [An Approach to Persistent Programming](https://academic.oup.com/comjnl/article/26/4/360/377375). The Computer Journal, Volume 26, Issue 4, pages 360–365.

- Alan Dearle, Graham Kirby, and Ron Morrison. 2009. [Orthogonal Persistence Revisited](https://link.springer.com/chapter/10.1007/978-3-642-14681-7_1). SOFSEM 2010: Theory and Practice of Computer Science. ([PDF](https://archive.cs.st-andrews.ac.uk/papers/download/DKM09a.pdf))

### Durable Execution

- Sebastian Burckhardt, Chris Gillum, David Justo, Konstantinos Kallas, Connor McMahon, and Christopher S. Meiklejohn. 2021. [Durable Functions: Semantics for Stateful Serverless](https://dl.acm.org/doi/10.1145/3485510). Proceedings of the ACM on Programming Languages, Volume 5, OOPSLA. ([PDF](https://www.microsoft.com/en-us/research/wp-content/uploads/2021/10/DF-Semantics-Final.pdf))

- Sebastian Burckhardt, Badrish Chandramouli, Chris Gillum, David Justo, Konstantinos Kallas, Connor McMahon, and Christopher S. Meiklejohn. 2022. [Netherite: Efficient Execution of Serverless Workflows](https://dl.acm.org/doi/10.14778/3529337.3529344). Proceedings of the VLDB Endowment, Volume 15, Issue 8. ([PDF](https://www.vldb.org/pvldb/vol15/p1591-burckhardt.pdf))

- Athinagoras Skiadopoulos, Qian Li, Peter Kraft, et al. 2022. [DBOS: A DBMS-oriented Operating System](https://dl.acm.org/doi/10.14778/3485450.3485454). Proceedings of the VLDB Endowment, Volume 15, Issue 1. ([PDF](https://vldb.org/pvldb/vol15/p21-skiadopoulos.pdf))

### Technical Blog Posts

- [Building a Modern Durable Execution Engine from First Principles](https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles) — Restate
- [The Emerging Landscape of Durable Computing](https://www.golem.cloud/post/the-emerging-landscape-of-durable-computing) — Golem Cloud
- [Workflow Engine Design Principles](https://temporal.io/blog/workflow-engine-principles) — Temporal
