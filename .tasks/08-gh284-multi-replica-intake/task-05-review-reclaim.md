# Task 05: A New Leader Reclaims Every Orphaned Review

**Type:** Feature (failover)

## Goal

When a leader dies (or the only application instance restarts, now that the database survives it), every claim left in `Pending Review`
gets a live run waiting for its decision again, and a decision already POSTed for the dead run reaches the new one.
Flow's own restore no longer starts anything. Fixes **mechanism 6**, and closes mechanism 2's failover window.

## What to Do

### 1. Flow's restore goes off

`quarkus.flow.persistence.auto-restore: false` at root, with a comment saying why. In Flow 1.3.0 it restarts nothing:
its scan builds each persisted run as a `WorkflowPersistenceInstance`, which registers itself in `activeInstances` in
`PENDING`, and then skips it as "already active" (task 01, Q1). What it does leave is a `PENDING` stand-in for every
persisted run, which `SupersedeStep`'s `activeInstance(oldRunId)` then finds, and whose `cancel()` silently returns
`false`. quarkiverse/quarkus-flow#1116 (unreleased, 1.4.0) makes it start every persisted run, so after a Flow upgrade
it would replay from the first incomplete task (mail sent twice) on every application instance, since they share the app ID. Leave `scan-interval` unset.

### 2. `ReviewReclaimer` (`org.parasol.intake.workflow.review`)

On `LeadershipAcquired`, on the managed executor (not the event's thread):
- Find every `ClaimIntakeState` whose claim is `Pending Review` and whose `reviewRunId` or `decidingRunId` names a run
  that isn't hosted in this JVM (task 03's check: `activeInstance` filtered to a non-`PENDING` status; never
  `PersistenceInstanceReader.find`).
- For each one, hand the review over through `ReviewRecoverer`'s existing take-over: a new `IntakeRun.resumesReview`
  run, registered as the review run before it starts, under the claim's optimistic lock. Generalize `recover` to take
  the orphaned run id rather than a faulted run's input: the conversation id already comes from `ClaimIntakeState`.
- **A pending decision:** if `decidingRunId` named the dead run (the reviewer had decided, and the signal never got
  delivered), re-address the signal to the new run (`IntakeRunSignal.retarget`) and keep the claim *being decided*
  by the new run (`decidingRunId = newRunId`, not back to open). Once the new run waits, the relay delivers the decision.
  The reviewer's work isn't lost.
- Retire the dead run's persisted Flow instance, so task 03's database check (the newest task's `next_position` is
  `waitReview`) can't be fooled by it. Flow has no API for a run this JVM doesn't host (`cancel()` on the `PENDING`
  stand-in returns `false`), so it's a direct `DELETE` (task 01, Q1):
  `delete from workflow_instance_entity where application_id = ? and instance_id = ?` (cascades to `task_info_entity`,
  `task_metadata_entity`, `workflow_metadata_entity`) and `delete from hash_mapping_info_entity where instance = ?`.
  Check whether a dead `listen` leaves `cloud_event_entity` rows (`FlowRows.cloudEventRows`), and delete those too if so.
  Do it in the reclaim's transaction, after the claim's lock is won.
- Metrics: count reclaims through `claim.intake.failures{stage=…, outcome=recovered}`, or a new
  `claim.intake.reviews.reclaimed` counter. Decide which reads better next to the existing `ReviewRecoverer` metrics.

### 3. Tests (bare `%test` group, `SINGLE` mode)

Simulate a dead leader in one JVM: create a waiting review through `IntakeScenario`, then make its run disappear from
this JVM, and fire `LeadershipAcquired`. Removing the live instance from `activeInstances` by reflection (as task 01
did) leaves its `listen` consumer registered, so it could still take the decision; prefer a `ClaimIntakeState` naming a
run whose Flow rows exist but that has no live instance (cancel the real run, then write its rows back, or insert them
from a copy). Then:
- A new run waits, it's the claim's `reviewRunId`, and a decision through the REST endpoint completes the claim.
- With a decision already claimed (`decidingRunId` = the dead run, signal pending), the new run applies **that**
  decision with no second POST.
- A review whose run is alive in this JVM isn't touched.
- Two `LeadershipAcquired` events in a row don't start two runs for one claim (the optimistic lock).
- The dead run's Flow rows are gone afterwards, and no `PENDING` instance is left in `activeInstances`.

## Files/Areas

- `src/main/resources/application.yml`
- `src/main/java/org/parasol/intake/workflow/review/ReviewReclaimer.java` (new), `ReviewRecoverer.java`
- `IntakeRunSignal` (task 03), `IntakeFailureListener` (if `recover`'s signature changes)
- `IntakeScenario` (test), a new `ReviewReclaimerTests`
- `CLAUDE.md` (*A claim's review always has a live run*, the package table), `docs/design/email-claim-intake.md`,
  `docs/email-claim-intake-demo.md` (a restart no longer loses a waiting review)

## Key Points

- **Turning auto-restore off is also a single-replica fix.** Once task 02 keeps the database, a plain restart would
  otherwise restore runs and replay their side effects.
- A reclaimed run starts at `waitReview`. It never re-sends the review reply or re-files the email, which is why
  reclaim is safe where Flow's replay isn't.
- `CLAUDE.md` → *the per-sender lock covers a woken review run*: a reclaimed run has no sender slot. Check that the
  dispatcher's `senderByInstance` handles a run it never started (it does for `ReviewRecoverer`'s runs today; confirm
  and say so).

## Done When

- [x] `quarkus.flow.persistence.auto-restore` is `false`, and a restart doesn't start any persisted run.
- [x] The four tests pass under `-Pollama`, along with the existing `ReviewRecoverer` and decision-fault tests.
- [x] Docs no longer say a restart loses a waiting review.
