# Persistence Model (Blocking, unified) — #57

> Status: living document. Created for issue #57 (carved out of the #55 review).
> Audience: anyone adding or modifying persistence code in `aster-api`.
>
> **#57 resolved (2026-07):** the app now uses a **single blocking persistence
> stack**. The only reactive-persistence island (audit `inbox`: `InboxEvent` +
> `InboxGuard`) was converted to blocking; `quarkus-hibernate-reactive-panache` +
> `quarkus-reactive-pg-client` and the reactive datasource pool were **removed**.
> The two-pool connection budget (96 → 48 per 6 pods) is gone. This document now
> describes the unified blocking model + the transaction/thread rules that still
> matter (they caused real bugs even within one stack).

## TL;DR — the rules

**Blocking persistence only; never reach it from an event-loop (I/O) thread.**

- Persistence uses Hibernate ORM/JDBC Panache (`io.quarkus.hibernate.orm.panache`),
  `jakarta.transaction.@Transactional` (JTA), and `QuarkusTransaction`. It must run on
  **worker threads** (`@Blocking`, `@Scheduled`, or an explicit `ExecutorService`).
- A method may still *return* `Uni`/`Multi` for REST/GraphQL shape — but its DB work
  must run on a worker thread (via `@Blocking` on the endpoint, or
  `runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` around the blocking body).
- **There is no reactive persistence.** Do not add `io.quarkus.hibernate.reactive.panache`,
  `@WithTransaction`, `Panache.withTransaction`, or a reactive datasource. `quarkus-vertx`
  / vertx web-client remain for HTTP only (LLM, telemetry) — not for DB.

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

The old two-pool connection budget (`quarkus.datasource.jdbc.max-size` **and**
`quarkus.datasource.reactive.max-size`, both `8`, "6 pod × (jdbc+reactive) < 100")
was the *symptom* of running both stacks at once. #57 removed the reactive pool;
the default datasource is now a single blocking JDBC pool (`8/pod` → `48` for 6 pods,
~48% of PG `max_connections=100`). See `application.properties` connection-budget note.

## Subsystem map (all blocking)

| Subsystem | How it runs | Persistence APIs |
|---|---|---|
| **Workflow** (`io.aster.api.workflow.*`) — scheduler, event store, timers, runtime | `@Scheduled` / `ExecutorService` worker threads | Hibernate ORM Panache, `@Transactional`, native `FOR UPDATE SKIP LOCKED` |
| **Outbox** (`io.aster.audit.outbox.GenericOutboxScheduler`, `AnomalyActionScheduler`) | `@Scheduled` worker thread; one `QuarkusTransaction.requiringNew()` per event; `executeEvent(...).await().atMost(30s)` blocks the worker | Hibernate ORM Panache, `@Transactional`, `QuarkusTransaction` |
| **Audit / anomaly orchestration** (`AnomalyWorkflowService`, `AnomalyActionExecutor`) | Reached only from the outbox worker (blocking `.await()`) and from `@Blocking` REST handlers | Hibernate ORM Panache, `@Transactional` |
| **Audit REST** (`PolicyAnalyticsResource`) | Handlers are `@Blocking` (worker thread); they *return* `Uni` for shape only | Hibernate ORM Panache |
| **Evaluate / GraphQL** (policy evaluation, GraphQL API) | Return `Uni` but offload blocking DB work to a worker pool (`PolicyManagementService`/`PolicyEvaluationService` use `runSubscriptionOn(getDefaultWorkerPool())`; the cache-hit hot path does **zero** DB) | Hibernate ORM Panache, offloaded |
| **Inbox idempotency** (`InboxGuard`) | `tryAcquireBlocking(...)` `@Transactional` (blocking JDBC `INSERT ... ON CONFLICT`); `tryAcquire(...)` returns `Uni` but offloads the blocking call via `runSubscriptionOn(worker)` for event-loop GraphQL callers; `scheduledCleanup()` `@Transactional void` | Hibernate ORM Panache (`EntityManager` native SQL) |

`InboxGuard.tryAcquire` is the canonical bridge pattern: expose a `Uni` for
event-loop callers, but back it with a blocking `@Transactional` core offloaded to
a worker pool — never a single `@Transactional` method that returns a *deferred* `Uni`
(the JTA transaction closes before the deferred body runs).

## Rules for new code

1. **Everything is blocking.** All persistence is Hibernate ORM Panache +
   `@Transactional`. Do not introduce reactive persistence (Hibernate Reactive,
   `@WithTransaction`, a reactive datasource).
2. **Blocking method → return a plain value (or `void`), annotate `@Transactional`,
   and ensure the caller is on a worker thread** (`@Blocking` on the REST/GraphQL
   method, a `@Scheduled` method, or an explicit executor). Do **not** annotate a
   method `@Transactional` and have it return a `Uni` whose body defers work.
3. **Event-loop endpoint that returns `Uni` and needs DB** → add `@Blocking` (moves
   the whole method to a worker) or wrap the blocking body in
   `Uni.createFrom().item(supplier).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`.
   Never call ORM Panache (`Entity.findById`, `Entity.persist()`, `EntityManager`)
   directly on the event loop (`InboxGuard.tryAcquire` is the reference).
4. **Bridging blocking work into a reactive chain**: wrap it in
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

## Unification: done (#57)

The service was unified to **all-blocking** (option 2 of the original plan):
evaluate/GraphQL already used ORM Panache offloaded to worker threads (they never
used reactive persistence — the earlier doc mis-described them), and the sole
reactive island (`inbox`) was converted to blocking. `quarkus-hibernate-reactive-panache`
+ `quarkus-reactive-pg-client` and the reactive datasource are removed. One pool.

Remaining follow-ups (separate issues, not persistence-model mixing):
- **#119** — outbox long-transaction boundary (`GenericOutboxScheduler` wraps
  `executeEvent(...).await()` in one `requiringNew()`; splitting the RUNNING/DONE
  status transactions from the handler-execution transaction shrinks the long tx).
- Optional hardening: an ArchUnit build-time check forbidding reactive-persistence
  imports (`io.quarkus.hibernate.reactive.panache`, `@WithTransaction`) to prevent
  re-introducing the reactive stack.

Any change here must be validated against the `@QuarkusTest`/IT suite
(Docker/Testcontainers), which is CI-authoritative and cannot run in the offline
dev sandbox.
