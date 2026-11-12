# Issue 8: Make the Email Intake Survive Restarts (Single Replica) — Task Execution Plan

## Your Mission

The email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216)) only works with **one**
replica, and its state didn't survive a restart (the schema was dropped and recreated on every boot, and a waiting review's run lived
only in memory).

**Re-scoped (user, 2026-11-12).** This started as "make the intake safe to run with more than one replica" (active/passive, a Kubernetes
Lease, a database signal table). That is more machinery than a demo app needs, and a production system would more likely put a
message broker in front of it. **Single replica stays the supported topology and the limitation is documented.** What this branch keeps is
what a single replica still benefits from: a database that survives restarts (Flyway), a review that gets a live run again after
a restart, an email left in `Processing` that is recovered rather than dropped or answered twice, and the `ClaimIntakeFlow` cleanup.

**GitHub issue:** [#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284) (retitled and re-scoped, see *Issue and PR* below).
**Plan File:** `.tasks/08-gh284-intake-restart-survival/PLAN.md`
**Tasks Directory:** `.tasks/08-gh284-intake-restart-survival/`
**Branch:** `gh284-multi-replica-intake` (one branch, one PR into `main`).

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/08-gh284-intake-restart-survival/task-XX-*.md`: Goal, What to Do, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md` (no early returns, one statement per line, US English, constructor injection, AssertJ only).
- Make sure it compiles under both AI wirings: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run the smallest test selection that covers the change; the full `verify` runs once, in task 06.
- **Update every affected document in the same task.**
- Check every Done When item.
- **Never commit until the user has reviewed the changes and approved.** Never edit files while the user is asking for a plan.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Task Outcomes, and record decisions that affect later tasks.

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

- [x] [task-01-spike.md](task-01-spike.md): Spike the unknowns (Flow restore and migrations, the Lease, a second JVM, a dead run's persisted data). Only the Flow and Flyway findings still apply
- [x] [task-02-flyway.md](task-02-flyway.md): Replace `drop-and-create` with Flyway: app baseline, seed migration, Flow's own migrations, `validate`
- [x] [task-03-remove-leadership.md](task-03-remove-leadership.md): Remove the Kubernetes Lease leader election and its OpenShift wiring; everything starts on `StartupEvent` again
- [x] [task-04-direct-publish-retry.md](task-04-direct-publish-retry.md): Replace the signal table with a direct publish after commit and a bounded retry until the run takes the decision
- [x] [task-05-restart-recovery.md](task-05-restart-recovery.md): Keep and adapt review reclaim and Processing recovery for a single instance that restarts
- [x] [task-06-docs-and-verification.md](task-06-docs-and-verification.md): Documentation, diagrams, full verification and the PR

Dropped from the earlier plan: the two-JVM end-to-end test (old task 08) and the OpenShift Lease/RBAC wiring (old task 07, folded into task 03).

> **History:** the first plan had nine tasks. Tasks 03–07 of it (signal table, Lease election, reclaim, Processing recovery, OpenShift wiring) are committed on
> this branch (`d93e271`, `a6ac331`, `3edb028`, `2fd7d20`, `e149dd7`); tasks 03–05 above rework them. Task 08 (two-JVM test) was started and its
> uncommitted edits were reverted.

### Issue and PR (user, 2026-11-12)

Issue #284 was retitled *Make the email intake survive restarts (single replica)* and its body got an "Update (2026-11-12)" section above the original text. The PR ends `Closes #284`.

---

## Shared Context

### The mechanisms that still make it single-replica (documented, not fixed)

| No. | Mechanism | Why it needs one instance |
|---|---|---|
| 1 | `ReviewDecisionPublisher` publishes through Flow's in-memory event publisher | A decision POSTed to an instance that doesn't host the waiting run is never delivered (now a 409 when the run isn't hosted and waiting *here*, instead of a silent `202`) |
| 2 | `SupersedeStep`'s in-JVM `activeInstance(oldRunId)` | A reply handled by another instance can't cancel the run waiting on this one |
| 3 | `ClaimIntakeDispatcher`'s in-memory per-sender queues and local status listener | Per-sender ordering holds only among the emails one instance sees |
| 4 | `ClaimsInboxWatcher` and `ClaimsMailbox`'s process-local lock | Two watchers race for the same message |

Lifting that would take a leader (a Lease) so only one instance watches and hosts runs, plus a way to get a decision to that instance (a database
outbox, or a message broker). That was built on this branch and removed again as too much for a demo.

### Key Decisions

- **Single replica is the supported topology (user, 2026-11-12).** Document the limitation; don't build more.
- **Flyway stays (user).** Application state survives restarts. This **reverses** the old "No durable state across restarts" gotcha.
- **The `ClaimIntakeFlow` cleanup stays (user):** name constants and `step`/`replyThenFile`/`decision` helpers (task names and order unchanged).
- **No signal table (user, 2026-11-12).** A reviewer's decision is published directly after the transaction that claims the review commits, with a
  bounded retry until the run takes it. Configurable under `parasol.intake.review.*`: `publish-interval` (default `250ms`) and `max-publish-attempts` (default `20`).
- **No leader election, no Kubernetes client, no Lease RBAC (user, 2026-11-12).** `SINGLE` was the only mode that ever ran outside a mock; it goes with the rest.
- **Flow is a black box: the app never reads or writes its tables.** "Is the run waiting?" is the app's own `ClaimIntakeState.reviewWaitingRunId`
  (`awaitReview` task), and "is it hosted here?" is `activeInstance(id)` filtered to `WAITING`. Never call `PersistenceInstanceReader.find` for an intake run.
- **Flow's auto-restore stays off** (`quarkus.flow.persistence.auto-restore: false`): it replays a run from its first incomplete task and would send mail twice.
  Recovery is the app's own (task 05).
- **A restart gives every orphaned review a live run, and recovers every email left in `Processing`;** loss isn't accepted, and neither is a second reply.
  A reviewer's decision that was claimed for a run that is now gone is **not** preserved (it lived in memory); the review goes back to open and the reviewer decides again.

### What stays from the committed work

- Task 02: Flyway (`V1` baseline, `V2` seed, Flow's tables on `flow-runtime`), `ClaimImageSeeder`'s advisory lock, `DevClaimEmailSeeder`'s guard.
- The app-recorded wait (`ClaimIntakeState.reviewWaitingRunId`, `awaitReview`), `ReviewRecoverer`/`ReviewReclaimer`, `ProcessingRecovery`, `EmailReceived.isRecovery`,
  `IntakeRun.resumesReply`/`resumesReview`, `ClaimCorrespondence` outbound rows with `in_reply_to`, the `claim.intake.reviews.reclaimed` counter.
- `@VirtualThreads ExecutorService` instead of `ManagedExecutor`; `ClaimResolver` `@ApplicationScoped`; the `ClaimIntakeFlow` cleanup; the merged dashboards.

### What goes

- `org.parasol.intake.leadership` (`IntakeLeadership`, `LeadershipAcquired/Lost`, `ConfiguredIntakeLeadership`, `KubernetesLeaseLeadership`) and its test; `quarkus-kubernetes-client`
  and `kubernetes-server-mock` in `pom.xml`; `parasol.intake.leadership.*`; the `%openshift` Lease RBAC and `kubernetes-client.generate-rbac`.
- `IntakeRunSignal`, `RunSignalRelay` (`…workflow.signal`), `V3__intake_run_signal.sql`, `parasol.intake.signals.*`, `RunSignalRelayTests`, `IntakeScenario.signalCount/queueDecisionFor`.
- Meters `claim.intake.leader`, `claim.intake.signals.pending`, `claim.intake.signal.delivery` and their dashboard panels and `IntakeMeterSchema` entries.
- The follower behavior: `NaN` DB-count gauges, the `submit` follower WARN, the `SupersedeStep` WARN-on-miss (back to DEBUG).

### Caveats & Problems

- **Every new `@TestProfile` is a full application boot** (`CLAUDE.md` → *Testing*). This plan adds none; the bare `%test` group stays at three boots total.
- **`-Pollama`/`-Pollama-openai` run dev mode as `<ai>,prod`**, so a `%dev` or `%prod` key doesn't mean what it looks like. Flyway's `clean-at-start` must never be on under `%prod`.
- **The claim-number sequence and column default live in `V1`** (`start with 1000000 increment by 1009 maxvalue 99999999`).
- **GreenMail stays in memory.** An app restart keeps the database, but on OpenShift a GreenMail restart still empties every mailbox. Document it.
- **Migration numbers:** the branch is unmerged, so task 04 renumbers `V4__review_waiting_run.sql` to `V3` and `V5__correspondence_recovery.sql` to `V4` rather
  than leave a gap. A local dev or OpenShift database that already applied the old numbers must be recreated once (say so in the README).
- **`LangfuseEvaluationInitializer` races with itself** only with several instances; out of scope.
- **Single-replica mentions** are in `application.yml` (`%openshift` comment), `ClaimsInboxWatcher`'s Javadoc, `README.md`, `docs/design/email-claim-intake.md`,
  `docs/email-claim-intake-demo.md` and `CLAUDE.md`. Task 06 sweeps them.

### Task Outcomes

- **Task 01 (spike):** answered the Flow-restore, migration and second-JVM questions; evidence in [spike-results.md](spike-results.md). Its Lease findings no longer apply.
  Still binding: another JVM can't read `WAITING` for a run it doesn't host, so the app records the wait itself; never use `PersistenceInstanceReader.find` for an intake run;
  Flow's `flow-runtime` named Flyway configuration and `baseline-on-migrate`/`baseline-version: 0` on both.
- **Task 02 (Flyway):** `V1__baseline.sql` and `V2__seed_claims.sql` replace `drop-and-create` and `import.sql`; Flow's tables come from `quarkus-flow-db-migration` on `flow-runtime`.
  `%test` cleans on `flow-runtime` only (it migrates first). The planned unique constraint on `claim_images (claim_id, kind, file_name)` was dropped (the intake can store two same-named
  attachments), so `ClaimImageSeeder` takes a Postgres advisory lock; `DevClaimEmailSeeder` sends only while no claim beyond the six seeded exists.

- **Task 03 (remove leadership):** `org.parasol.intake.leadership`, `quarkus-kubernetes-client`, the fabric8 mock, the `%openshift` Lease RBAC and `parasol.intake.leadership.*` are gone; the watcher and reclaimer start on `StartupEvent`; the leader gauge and follower `NaN` behavior are removed.
- **Task 04 (direct publish):** `IntakeRunSignal`, `RunSignalRelay`, `V3__intake_run_signal.sql` and the signal meters/panels are gone. `ClaimReviewService` claims the review, requires the run to be waiting in this JVM (409 otherwise), publishes after commit and republishes until `decidingRunId` clears (`parasol.intake.review.*`), restoring the review and answering 409 if never taken. `ReviewRecoverer.takeOver` no longer keeps a claimed decision. Migrations renumbered to `V3`/`V4`. New `ClaimReviewServiceTests`.
- **Task 05 (restart recovery):** wording and the reclaim test adapted (`aDecisionClaimedForTheRunThatIsGoneIsNotLeftStuck…`); no code change needed beyond tasks 03 and 04.
- **Task 06 (docs):** `CLAUDE.md`, README (*Single replica*, *Surviving a restart*, config table), design doc, demo doc, roadmap and the spike banner updated. No diagram changed (they never showed the signal table). The full `verify` and the PR are left to the user (nothing is committed).

### Research kept from the first plan (2026-11-10, quarkus-flow 1.3.0)

- **`FlowPersistenceRestore`** restores, on `WorkflowApplicationReadyEvent`, every persisted instance with this instance's app ID that isn't active locally, by calling `instance.start()`
  (`quarkus.flow.persistence.auto-restore`). In 1.3.0 it actually starts nothing and leaves a `PENDING` zombie per persisted run (quarkiverse/quarkus-flow#1116, unreleased, makes it start every
  persisted run). Off is required either way.
- **`quarkus-flow-db-migration`** ships Flow's six tables as versioned Flyway scripts under `db/flow-migration/runtime/postgresql`, recommended on a named `flow-runtime` datasource with its own
  history table (`flyway_flow_runtime_history`).
