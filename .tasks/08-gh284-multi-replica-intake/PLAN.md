# Issue 8: Make the Email Intake Safe to Run With More Than One Replica — Task Execution Plan

## Your Mission

The email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216)) only works with
**one** replica, and nothing enforces it beyond the OpenShift extension's default `replicas: 1`. Lift that: any number
of `parasol-app` pods can run, and every claim email, review decision and failover still does the right thing.

The shape is **active/passive for the intake, active/active for everything else.** One pod, the *intake leader*
(Kubernetes Lease), runs the IMAP watcher, the dispatcher and every `ClaimIntakeFlow` run. Every pod serves the UI, the
REST API (review decisions included) and the chat bot. A decision POSTed to any pod reaches the run through a
database-backed command table. A new leader takes over what a dead one left: the reviews it was waiting on and the
emails it was processing. The database now survives restarts (Flyway), which is what makes any of that possible.

**GitHub issue:** [#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284). The issue lists four
mechanisms and four options; planning found three more mechanisms (see *The seven mechanisms* below).
**Plan File:** `.tasks/08-gh284-multi-replica-intake/PLAN.md`
**Tasks Directory:** `.tasks/08-gh284-multi-replica-intake/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/08-gh284-multi-replica-intake/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- Make sure it compiles under both AI wirings: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run the smallest test selection that covers the change; the full `verify` runs once, in task 09.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [ ] [task-01-spike.md](task-01-spike.md): Spike the five unknowns (Flow restore and migrations, the Lease against a mock API server, a second JVM, a dead run's persisted data)
- [ ] [task-02-flyway.md](task-02-flyway.md): Replace `drop-and-create` with Flyway: app baseline, seed migration, Flow's own migrations, `validate`
- [ ] [task-03-run-commands.md](task-03-run-commands.md): Deliver review decisions through a database command table, to whichever pod hosts the run
- [ ] [task-04-intake-leadership.md](task-04-intake-leadership.md): Elect one intake leader with a Kubernetes Lease; only it watches, dispatches and runs
- [ ] [task-05-review-reclaim.md](task-05-review-reclaim.md): Turn off Flow's auto-restore; a new leader reclaims every orphaned review
- [ ] [task-06-processing-recovery.md](task-06-processing-recovery.md): Recover emails a dead leader left in Processing without duplicating or dropping a reply
- [ ] [task-07-openshift.md](task-07-openshift.md): OpenShift wiring: kubernetes-client, RBAC for Leases, `POD_NAME`, leadership mode
- [ ] [task-08-two-jvm-e2e.md](task-08-two-jvm-e2e.md): A two-JVM end-to-end test: follower decision, leader failover, recovered email
- [ ] [task-09-docs-and-verification.md](task-09-docs-and-verification.md): Documentation, diagrams, full verification and the PR

> **Branch and PR (user, 2026-11-10):** one branch off `main`, **`gh284-multi-replica-intake`** (created with this
> plan as its first commit; every task commits there), and one PR into `main`. Its body ends `Closes #284`, and its
> title has no closing keyword. Suggested title: *Make the email intake safe to run with more than one replica*.
>
> **Status (2026-11-10):** planned only. Nothing is implemented; the next agent starts at task 01.

---

## Shared Context

### Overview

**Why active/passive and not active/active.** Mechanisms 2 and 3 (the in-JVM cancel lookup, and the per-sender queue
driven by a status listener that only hears local runs) disappear once every run lives on one pod. Making them
cluster-wide (a cross-pod cancel, and a database-backed per-sender queue with a cluster-wide status feed) is the large,
risky part of active/active. Flow 1.3.0 offers none of it: no cross-pod event delivery, no cancel of a non-local
instance, and its messaging bridge gives each event to *one* consumer by default. The intake is mail-bound and
low-volume, so one leader has more than enough throughput. Every pod still serves the parts that see user traffic.

**What "the leader hosts every run" buys, mechanism by mechanism:**

| No. | Mechanism | Fixed by | Task |
|---|---|---|---|
| 1 | `ReviewDecisionPublisher` → Flow's in-memory publisher: a decision POSTed to the wrong pod is a silent `202` | The decision is written to `intake_run_command` in the transaction that claims the review; the pod hosting the run delivers it | 03 |
| 2 | `SupersedeStep`'s in-JVM `activeInstance(oldRunId)` | Co-location: the superseding run and the waiting run are both on the leader. A miss now logs at WARN, and reclaim covers it | 04, 05 |
| 3 | `ClaimIntakeDispatcher`'s in-memory per-sender queues + local status listener | Co-location: only the leader dispatches, and it hosts every run | 04 |
| 4 | `ClaimsInboxWatcher` on every pod + process-local mailbox lock | Only the leader watches | 04 |
| 5 | **New.** `%prod`/`%openshift` `drop-and-create` + `import.sql`: every pod boot wipes the shared database | Flyway, Hibernate `validate` | 02 |
| 6 | **New.** All pods share Flow's app ID `parasol-app`, so Flow's startup auto-restore on pod B restarts pod A's waiting runs (duplicates) | Auto-restore off; app-level reclaim on leadership | 05 |
| 7 | **New.** The watcher's "hand over what's left in Processing" runs on every pod's first connection, and steals what another pod is processing; the duplicate check then *files* it out from under that pod | Only the leader watches; a recovered email is no longer treated as a duplicate | 04, 06 |

Already safe across pods, unchanged: the `ClaimCorrespondence.messageId` unique constraint, the optimistic lock on
`ClaimIntakeState`, Flow's database-backed run persistence.

### Key Decisions (user, 2026-11-10)

- **Active/passive intake** rather than active/active, or enforcing one replica.
- **Kubernetes Lease** for leader election (fabric8 `LeaderElector` through `quarkus-kubernetes-client`), not a Postgres
  advisory lock and not `quarkus-flow-durable-kubernetes`. Off-cluster (dev mode, the test suite) the pod is always the
  leader. The trade-offs were presented: the advisory lock needs no RBAC and runs the same everywhere, but its crash
  failover depends on TCP keepalive. The Lease has a bounded failover and is the standard Kubernetes pattern, but needs
  RBAC and a mock API server in tests.
- **A database command table** for cross-pod decisions, not Kafka, and not LISTEN/NOTIFY.
- **Flyway** replaces `drop-and-create`: application state now survives restarts. This **reverses** the earlier
  "No durable state across restarts (user decision)" gotcha in `CLAUDE.md`.
- **A new leader reclaims** orphaned reviews (through `ReviewRecoverer`) and the emails left in Processing. Loss is not
  accepted.
- **An email whose outcome was applied before its run died** resumes at *reply → file* only if no `OUTBOUND`
  correspondence row answers it; otherwise it's just filed. That needs outbound rows to record which email they answer
  (`in_reply_to`).
- **OpenShift `replicas` stays `1`** by default. The comment changes from "must be one" to "safe to scale".
- **A two-JVM end-to-end test** proves a decision POSTed to the follower, leader failover and recovery.
- **One branch, one PR into `main`.**

### Decisions made in planning (not asked; revisit at a task review if wrong)

- **Supersede's cancel stays in-process.** The user picked the command table "for decisions and cancels", but with
  active/passive the superseding run and the run it cancels are always on the same pod (the leader). A cancel command
  would be dead code. The table's `kind` column leaves room for a `CANCEL` if a review ever shows the need. A miss
  (`activeInstance` empty) moves from DEBUG to WARN, because after task 05 it means a review that reclaim should already
  have covered.
- **Losing the Lease ends the process** (`Quarkus.asyncExit`), the same fencing `quarkus-flow-durable-kubernetes` uses.
  A pod that lost leadership but kept its runs in memory would compete with the new leader's reclaimed runs; a restart
  makes it a clean follower. Kubernetes restarts it.
- **Flow's auto-restore goes off,** and recovery is the app's own. Flow's restore replays a run from its first
  incomplete task, which would send mail twice. It would also restore on *every* pod, since they share an app ID. A
  `listen` restored after failover isn't verified in 1.3.0 either (quarkiverse/quarkus-flow#1110, for timed waits, is
  fixed only in the unreleased 1.4.0). `ReviewRecoverer` already does the right thing for reviews and is tested.
- **DB-count gauges** (`claim.intake.reviews.pending`, `.review.oldest.pending`, `.inbox.depth`) are measured by the
  leader only, so a `sum` across pods isn't N times the truth. A new `claim.intake.leader` gauge (1/0 per pod) shows who
  leads.
- **The decision relay retries until the run wakes,** which moves `IntakeScenario.publishUntilWoken`'s loop into
  production code. That also closes F2b (`ClaimReviewService`'s Javadoc) for real, instead of arguing it can't happen.

### Caveats & Problems

- **Every new `@TestProfile` is a full application boot** (`CLAUDE.md` → *Testing*). Only task 08 adds one (a 4th
  boot), because the two-JVM test needs Lease mode against a mock API server. Leadership unit tests (task 04) use the
  fabric8 mock server in plain JUnit, with no Quarkus boot. Don't add `@WithKubernetesTestServer` to an existing group:
  it's a test resource, and it would split that group's reload key.
- **`-Pollama`/`-Pollama-openai` run dev mode as `<ai>,prod`**, so a `%dev` or `%prod` key doesn't mean what it looks
  like (`CLAUDE.md` → *Commands*). That matters for Flyway's `clean-at-start` (task 02): it must never be on under
  `%prod`, and dev mode under the Ollama profiles is `prod`.
- **The claim-number sequence and column default live in DDL that Hibernate generates today**
  (`ClaimNumberGenerator`'s `ExportableProducer`, `@ColumnDefault`). The Flyway baseline must carry them verbatim
  (`start with 1000000 increment by 1009 maxvalue 99999999`), or claim numbers silently change.
- **GreenMail stays in memory.** After task 02 an app restart keeps the database but, on OpenShift, a GreenMail restart
  still empties every mailbox. Document it; it isn't in scope.
- **`DevClaimEmailSeeder` re-sends its two sample claims on every start.** That was harmless while each boot wiped the
  database; with a persistent dev database (live reload keeps the Dev Services container) it piles up claims. Task 02
  decides.
- **`ClaimImageSeeder` races itself** when two pods boot at once (`hasImage` then `store`). Task 02 adds a unique
  constraint and makes the insert idempotent.
- **`LangfuseEvaluationInitializer` runs on every pod.** `createIfAbsent` isn't atomic (`CLAUDE.md` → *AI quality
  layer*), so two pods booting together could race. That's out of scope: note it in the docs, don't fix it.
- **The chat bot is per-pod already** (`@ChatScoped` over a websocket bound to one connection), so it needs nothing.
- **Single-replica mentions are in at least six places:** `application.yml` (`%openshift` comment),
  `ClaimsInboxWatcher`'s Javadoc, `README.md` → *Single replica*, `docs/design/email-claim-intake.md`,
  `docs/email-claim-intake-demo.md`, and `CLAUDE.md` (the *intake only works correctly with one replica* gotcha).
  Task 09 rewrites all of them; earlier tasks fix the Javadoc of what they touch.

### Research (2026-11-10, quarkus-flow 1.3.0 tag, commit `77f6316`)

- **`quarkus-flow-durable-kubernetes`** gives each pod a stable Flow app ID by binding it to a pool-member Lease. A
  replacement pod inherits a dead pod's ID, and with it the dead pod's runs, through `FlowPersistenceRestore`. It
  doesn't deliver events across pods, cancel a non-local run, or elect a leader for app beans. It needs RBAC for
  `leases`, `pods`, `deployments` and `replicasets`, plus `POD_NAME`/`POD_NAMESPACE`. It's bypassed in dev/test. Not
  chosen; it's the alternative if active/active is ever wanted.
- **`FlowPersistenceRestore`** restores, on `WorkflowApplicationReadyEvent`, every persisted instance with this pod's
  app ID that isn't active locally, by calling `instance.start()`. Config: `quarkus.flow.persistence.auto-restore`
  (startup), `quarkus.flow.persistence.scan-interval` (periodic, off by default, since 1.2.0).
- **Flow's messaging bridge** (`flow-in`/`flow-out`) dispatches only to `listen` tasks registered in the consuming JVM.
  With a shared consumer group, one pod gets each event.
- **`quarkus-flow-db-migration`** ships Flow's six JPA tables as versioned Flyway scripts, under
  `db/flow-migration/runtime/postgresql`. The docs recommend a named `flow-runtime` datasource pointing at the same
  database, with its own history table (`flyway_flow_runtime_history`), plus `schema-management.strategy=validate` and
  `CamelCaseToUnderscoresNamingStrategy`. The app already uses that strategy (`application.yml:218`).
- Related upstream: quarkiverse/quarkus-flow#903 (`durable-jpa`) and #904 (`durable-redis`), both open, "Future".
