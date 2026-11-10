# Task 05: A New Leader Reclaims Every Orphaned Review

**Type:** Feature (failover)

## Goal

When a leader dies (or the only pod restarts, now that the database survives it), every claim left in `Pending Review`
gets a live run waiting for its decision again, and a decision already POSTed for the dead run reaches the new one.
Flow's own restore no longer starts anything. Fixes **mechanism 6**, and closes mechanism 2's failover window.

## What to Do

### 1. Flow's restore goes off

`quarkus.flow.persistence.auto-restore: false` at root, with a comment saying why (it replays from the first incomplete
task, which sends mail twice, and every pod shares the app ID). Leave `scan-interval` unset.

### 2. `ReviewReclaimer` (`org.parasol.intake.workflow.review`)

On `LeadershipAcquired`, on the managed executor (not the event's thread):
- Find every `ClaimIntakeState` whose claim is `Pending Review` and whose `reviewRunId` or `decidingRunId` names a run
  that isn't active in this JVM.
- For each one, hand the review over through `ReviewRecoverer`'s existing take-over: a new `IntakeRun.resumesReview`
  run, registered as the review run before it starts, under the claim's optimistic lock. Generalize `recover` to take
  the orphaned run id rather than a faulted run's input: the conversation id already comes from `ClaimIntakeState`.
- **A pending decision:** if `decidingRunId` named the dead run (the reviewer had decided, and the command never got
  delivered), re-address the command to the new run (`IntakeRunCommand.retarget`) and keep the claim *being decided*
  by the new run (`decidingRunId = newRunId`, not back to open). Once the new run waits, the relay delivers the decision.
  The reviewer's work isn't lost.
- Retire the dead run's persisted Flow instance the way task 01 found to be safe, so `ClaimReviewService.ensureWaiting`
  and `isReadyForDecision` can't be fooled by a stale `WAITING` row. If there's no safe way, document why it doesn't
  matter (both read the *current* `reviewRunId`).
- Metrics: count reclaims through `claim.intake.failures{stage=…, outcome=recovered}`, or a new
  `claim.intake.reviews.reclaimed` counter. Decide which reads better next to the existing `ReviewRecoverer` metrics.

### 3. Tests (bare `%test` group, `SINGLE` mode)

Simulate a dead leader in one JVM: create a waiting review through `IntakeScenario`, then make its run disappear from
this JVM (cancel it in memory without touching the database, or write a `ClaimIntakeState` that names a run id that
never existed), and fire `LeadershipAcquired`. Then:
- A new run waits, it's the claim's `reviewRunId`, and a decision through the REST endpoint completes the claim.
- With a decision already claimed (`decidingRunId` = the dead run, command pending), the new run applies **that**
  decision with no second POST.
- A review whose run is alive in this JVM isn't touched.
- Two `LeadershipAcquired` events in a row don't start two runs for one claim (the optimistic lock).

## Files/Areas

- `src/main/resources/application.yml`
- `src/main/java/org/parasol/intake/workflow/review/ReviewReclaimer.java` (new), `ReviewRecoverer.java`
- `IntakeRunCommand` (task 03), `IntakeFailureListener` (if `recover`'s signature changes)
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

- [ ] `quarkus.flow.persistence.auto-restore` is `false`, and a restart doesn't start any persisted run.
- [ ] The four tests pass under `-Pollama`, along with the existing `ReviewRecoverer` and decision-fault tests.
- [ ] Docs no longer say a restart loses a waiting review.
