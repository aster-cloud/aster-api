# Persistence Model Boundary (Blocking vs Reactive)

> Status: living document. Created for issue #57 (carved out of the #55 review).
> Audience: anyone adding or modifying persistence code in `aster-api`.

## TL;DR — the one rule

**Pick one persistence model per subsystem, and never reach blocking persistence
from an event-loop (I/O) thread.**

- Blocking subsystems use Hibernate ORM/JDBC Panache (`io.quarkus.hibernate.orm.panache`),
  `jakarta.transaction.@Transactional` (JTA), and `QuarkusTransaction`. They must run on
  **worker threads** (`@Blocking`, `@Scheduled`, or an explicit `ExecutorService`).
- Reactive subsystems use Hibernate Reactive Panache
  (`io.quarkus.hibernate.reactive.panache`), `@WithTransaction` /
  `Panache.withTransaction`, and return `Uni`/`Multi`. They run on the event loop.

The service deliberately ships **both** stacks (see
`build.gradle`: `quarkus-hibernate-orm-panache` + `quarkus-jdbc-postgresql` **and**
`quarkus-hibernate-reactive-panache` + `quarkus-reactive-pg-client`). A full
unification is out of scope; this document defines the contract so the two models
do not bleed into each other.

## Why this matters

Two failure modes the review flagged:

1. **`@Transactional` on a method that returns/feeds a `Uni`/`Multi`.**
   The JTA transaction opens when the method is *invoked* and commits/closes when
   the method *returns the `Uni` object* — i.e. **before** any deferred
   continuation runs. If real persistence work is deferred into
   `Uni.createFrom().item(supplier)`, `.onItem().transformToUni(...)`, etc., that
   work executes at **subscription time**, outside the JTA transaction/Session
   that `@Transactional` set up. Symptoms: writes silently outside a transaction,
   `LazyInitializationException`, "no session" errors, or a timeout that JTA can
   never honor.

   - Note the **eager** exception: `return Uni.createFrom().item(value)` where
     `value` was already computed *synchronously inside the `@Transactional`
     body* is fine — the transaction did cover the real work; the `Uni` is just a
     wrapper. The danger is only the **deferred** form (a `Supplier`/lambda).

2. **Blocking Panache reached from an event-loop thread.**
   A blocking JDBC call on the event loop stalls the I/O thread, starves all other
   requests, and (in dev/test) trips Quarkus's blocking-call detector. Anything
   that ends up calling Hibernate ORM Panache must first hop to a worker thread.

The two-pool connection budgeting in `application.properties` (`quarkus.datasource.jdbc.max-size`
and `quarkus.datasource.reactive.max-size`, both `8`, with the note "PG
max_connections=100, 6 pod × (jdbc+reactive) must be < 100") is the *symptom* of
running both stacks at once — it is not the disease.

## Subsystem map

| Subsystem | Model | How it runs | Persistence APIs |
|---|---|---|---|
| **Workflow** (`io.aster.api.workflow.*`) — scheduler, event store, timers, runtime | **Blocking** | `@Scheduled` / `ExecutorService` worker threads | Hibernate ORM Panache, `@Transactional`, native `FOR UPDATE SKIP LOCKED` |
| **Outbox** (`io.aster.audit.outbox.GenericOutboxScheduler`, `AnomalyActionScheduler`) | **Blocking** | `@Scheduled` worker thread; one `QuarkusTransaction.requiringNew()` per event; `executeEvent(...).await().atMost(30s)` blocks the worker | Hibernate ORM Panache, `@Transactional`, `QuarkusTransaction` |
| **Audit / anomaly orchestration** (`AnomalyWorkflowService`, `AnomalyActionExecutor`) | **Blocking** | Reached only from the outbox worker (blocking `.await()`) and from `@Blocking` REST handlers | Hibernate ORM Panache, `@Transactional` |
| **Audit REST** (`PolicyAnalyticsResource`) | **Blocking** | Handlers are `@Blocking` (worker thread); they *return* `Uni` for shape only | Hibernate ORM Panache |
| **Evaluate / GraphQL** (policy evaluation, GraphQL API) | **Reactive** | Event loop | Hibernate Reactive Panache, `@WithTransaction` |
| **Inbox idempotency** (`InboxGuard`) | **Both, by entry point** | `tryAcquireBlocking(...)` `@Transactional` for `@Blocking` callers; `tryAcquire(...)` / `scheduledCleanup()` `@WithTransaction` for reactive callers | ORM for blocking path, Reactive for reactive path |

`InboxGuard` is the canonical example of a component that *intentionally* exposes
**two separate methods** — one per model — instead of mixing them in one method.
When a component must serve both worlds, follow that pattern: separate entry
points, never a single `@Transactional` method that returns a deferred `Uni`.

## Rules for new code

1. **Decide the subsystem's model first.** If you are in `io.aster.api.workflow.*`,
   the outbox, or audit orchestration, you are **blocking**. If you are in
   evaluate/GraphQL, you are **reactive**.
2. **Blocking method → return a plain value (or `void`), annotate `@Transactional`,
   and ensure the caller is on a worker thread** (`@Blocking` on the REST/GraphQL
   method, a `@Scheduled` method, or an explicit executor). Do **not** annotate a
   method `@Transactional` and have it return a `Uni` whose body defers work.
3. **Reactive method → return `Uni`/`Multi`, use `@WithTransaction` (or
   `Panache.withTransaction(...)`), and use Hibernate Reactive Panache.** Never
   call ORM Panache (`Entity.findById`, `Entity.persist()`, `EntityManager`) from
   here.
4. **Bridging blocking work into a reactive chain** (rare): wrap it in
   `Uni.createFrom().item(supplier)` **and** `.runSubscriptionOn(...)` /
   `@Blocking` so the blocking work executes on a worker thread, and keep each
   blocking unit in its own `@Transactional` helper method. Do not put
   `@Transactional` on the wrapper itself — it will not cover the deferred body.
5. **Mind blocking budgets across boundaries.** The outbox enforces
   `await().atMost(30s)` per event (`GenericOutboxScheduler.OUTBOX_EVENT_TIMEOUT`).
   Any blocking work it drives must complete well within that, or fail fast and be
   retried via the FAILED path — do not set inner timeouts that exceed the outer
   budget.

## #57 fix applied: `WorkflowSchedulerService.replayWorkflow`

`replayWorkflow(UUID)` was `@Transactional` **and** returned a `Uni` whose body
deferred the real work — `processWorkflow(...)` plus a final
`WorkflowStateEntity.findByWorkflowId(...)` re-query — into
`Uni.createFrom().item(supplier)`, with a 5-minute timeout. The JTA transaction
opened by `@Transactional` committed when the method returned the `Uni`, so:

- the deferred `findByWorkflowId` re-query ran **outside any transaction/Session**;
- the 5-minute timeout could not be honored by JTA and conflicted with the
  outbox's 30s `await().atMost(...)` budget (replay via the outbox path could
  never complete).

Fix (kept fully within the blocking model — no reactive Session introduced):

- Removed `@Transactional` from the `Uni`-returning wrapper (it never covered the
  deferred body).
- Moved validation + status reset into `prepareReplay(UUID)` (`@Transactional`).
- `processWorkflow(...)` keeps its own `@Transactional`.
- Final re-query moved into `reloadState(UUID)` (`@Transactional`).

All blocking work now runs inside the supplier at subscription time. Because the
sole caller (`AnomalyActionExecutor` via the outbox) subscribes *blockingly*
(`.await()`) on a worker thread that already holds an active transaction, the
`@Transactional` (REQUIRED) helpers join that transaction correctly instead of
running unscoped. Failure semantics are unchanged: `IllegalArgumentException`
(not found) and `IllegalStateException` (missing `clock_times`) still surface as
`Uni` failures.

## Recommended full unification (human follow-up, out of scope for #57)

A complete fix would collapse to **one** stack. Options, in rough order of
preference:

1. **All-reactive.** Port the workflow scheduler, outbox, and audit orchestration
   to Hibernate Reactive + `@WithTransaction`, drop `quarkus-hibernate-orm-panache`
   and `quarkus-jdbc-postgresql`, and run a single reactive pool. Biggest win on
   the connection budget, but the workflow scheduler leans on
   `FOR UPDATE SKIP LOCKED` and `ExecutorService`-driven polling that would need
   careful reactive re-modelling; the IT suite (Testcontainers) is required to
   validate determinism/replay/crash-recovery.
2. **All-blocking.** Port evaluate/GraphQL to ORM Panache on worker threads
   (`@Blocking`), drop the reactive stack and pool. Simpler mental model and one
   pool, at the cost of worker-thread throughput for the hot evaluate path.
3. **Status quo + guardrails (this issue).** Keep both stacks but enforce the
   boundary by convention + the targeted fix above, and consider an
   ArchUnit/Quarkus build-time check that forbids ORM Panache types in
   reactive packages and vice versa.

Whichever direction is chosen, it must be validated against the `@QuarkusTest`/IT
suite (Docker/Testcontainers), which is CI-authoritative and cannot run in the
offline dev sandbox.
