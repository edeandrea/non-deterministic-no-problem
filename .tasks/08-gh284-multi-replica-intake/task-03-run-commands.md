# Task 03: Deliver Review Decisions Through a Database Command Table

**Type:** Feature (cross-pod delivery)

## Goal

A review decision POSTed to **any** pod reaches the run waiting for it, wherever that run lives. The `202` means "durably
queued", not "published into this JVM and hoped for the best". Fixes **mechanism 1**, and turns F2b's theoretical gap
into a retried delivery. This task works with one pod too, so it lands before leadership.

## What to Do

### 1. The table: `V<n>__intake_run_command.sql` + an entity

`IntakeRunCommand` (`org.parasol.intake.model`, table `intake_run_command`): `id`, `run_id` (not null, indexed),
`claim_id` (foreign key to `claims`, `on delete cascade`), `kind` (enum, `REVIEW_DECIDED` only for now), `payload`
(the `ReviewDecided` as JSON, `text`/`jsonb`), `created_at`, `delivered_at` (null until delivered), `attempts`. Active
Record finders: pending for a set of run ids, oldest pending age, and re-address (`retarget(claimId, oldRunId,
newRunId)`, used by task 05).

### 2. Write it with the claim

In `ClaimReviewService`, the command is inserted in the **same transaction** that moves `reviewRunId` to
`decidingRunId` (`claimReview`). The claim and the command commit together, or neither does. On `ensureWaiting`'s
failure path, the compensating `restoreReview` also deletes the command.

**`ensureWaiting` stays a precondition, but it can no longer use `PersistenceInstanceReader`** (spike-results.md Q1):
- Flow persists `status = null` for a waiting run, and `find` on a run this JVM doesn't host returns a
  `WorkflowPersistenceInstance` in `PENDING`. Every decision POSTed to a follower would be a `409`.
- `find` also registers that `PENDING` instance in the definition's `activeInstances`, where it stays.
- Reading a run that has completed a task twice (one waiting a third time, after two decision faults) throws Flow's
  `Duplicate key do/16/waitReview`, which is a `500` from today's single-pod endpoint.

Replace it with a database-only read of the run's newest `task_info_entity` row: the run is waiting (or about to be)
when that row's `next_position` is `waitReview`'s JSON pointer (read it from the definition, don't hard-code
`do/16/waitReview`). No instance row means "not found". Put it in one small bean in `org.parasol.intake.workflow`
(native SQL, like `FlowRows` in the tests), and use it in `isReadyForDecision` too. Drop the
`PersistenceInstanceReader` injection, and don't call `find` anywhere in the app.

### 3. The relay: `RunCommandRelay` (`org.parasol.intake.workflow.command`, a new sub-package)

On every pod:
- Right after `submit` commits, it tries once at once: if the run is hosted in this JVM, it publishes through
  `ReviewDecisionPublisher`. That keeps today's single-pod latency. "Hosted here" is
  `activeInstance(runId).filter(run -> run.status() != WorkflowStatus.PENDING)`: a `PENDING` instance is a stand-in
  Flow left behind, not a run.
- A `@Scheduled(every = "${parasol.intake.commands.poll-interval}", concurrentExecution = SKIP)` job reads the pending
  commands whose `run_id` is active **in this JVM**, and publishes each one.
- A command is **delivered** once the run has taken it: the run left `WAITING`, or `decidingRunId` no longer names it.
  That's the same condition `IntakeScenario.publishUntilWoken` uses (read it, `src/test/java/org/parasol/intake/IntakeScenario.java:357`).
  Until then each poll republishes. A duplicate is harmless: `ClaimIntakeFlow.isForThisRun` checks the run id and
  `isDecisionFor`, and after a restore-and-wait-again `decidingRunId` is null.
- Delivered rows are deleted (or kept with `delivered_at` and purged after a retention period; decide).

### 4. Config and metrics

- `IntakeConfig`: `parasol.intake.commands.poll-interval` (default `1s`), in the README config table and `CLAUDE.md`'s
  `parasol.intake.*` table.
- Gauge `claim.intake.commands.pending` and timer `claim.intake.command.delivery` (`kind` tag only; never a run or claim
  id), through `IntakeActivity` events like every other intake meter. The gauge is leader-only after task 04.

### 5. Tests

`IntakeScenario.publishUntilWoken` either becomes a thin wrapper over the relay or stays as the workflow-level helper
for tests that bypass REST. Decide, and say which in the test's Javadoc. Add, in the bare `%test` group:
- A decision submitted while the run is still on its way to `waitReview` is delivered once it waits (the F2b shape,
  which now has a test).
- A command for a run not hosted in this JVM stays pending, and isn't lost.
- A failed `ensureWaiting` leaves no command behind.
- A rollback of the claim transaction leaves no command behind.
- A decision POSTed while the run waits for the third time (two injected decision faults) is accepted and applied.
  Today that's a `500` (Flow's duplicate-key read).
- `GET /review` and a decision both work for a run this JVM doesn't host (simulate it with a `ClaimIntakeState` naming
  a run whose rows exist but that isn't in `activeInstances`): no `PENDING` instance is left in `activeInstances`.

## Files/Areas

- `src/main/java/org/parasol/intake/model/IntakeRunCommand.java` (new), `…/workflow/command/RunCommandRelay.java` (new)
- `src/main/java/org/parasol/intake/review/ClaimReviewService.java` (its F2b Javadoc paragraph is rewritten),
  `ReviewDecisionPublisher.java` (its Javadoc: now the relay's local leg)
- `src/main/resources/db/migration/V<n>__intake_run_command.sql`
- `IntakeConfig`, `IntakeActivity`, `IntakeMetrics`
- `ClaimReviewResourceTests`, `IntakeScenario`, a new `RunCommandRelayTests`
- `CLAUDE.md` (the review endpoints paragraph, *the F2b race*, the config table, the package table),
  `docs/design/email-claim-intake.md`

## Key Points

- **Cancels are not commands** (PLAN.md → *Decisions made in planning*). Keep `kind` so one can be added.
- Prefer `@Transactional` methods to `QuarkusTransaction`, and keep the relay's transactional part non-private
  (`CLAUDE.md` → *Gotchas*, the email intake workflow).
- The command carries ids and the decision, never bytes: the same rule as Flow's own data.
- An exception thrown only to be mapped needs no stack trace; one that faults the relay keeps it (`CLAUDE.md` →
  *Conventions*).

## Done When

- [ ] A decision commits with its command; a failed check leaves neither.
- [ ] The new tests and `ClaimReviewResourceTests` pass under `-Pollama`, along with the intake review tests
      (`ClaimIntake*Review*Tests` or the classes that cover the decision steps).
- [ ] `ClaimReviewService`'s Javadoc no longer argues that F2b can't happen; it says the relay retries.
- [ ] The config key and the two meters are documented.
