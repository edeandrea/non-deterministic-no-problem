# Task 04: Direct Publish With a Bounded Retry (No Signal Table)

**Type:** Removal / Behavior change

## Goal

A reviewer's decision is published directly after the transaction that claims the review commits, and republished until the run takes it. No `intake_run_signal` table, no relay, no poll.
Replaces `d93e271` (task 03 of the first plan). Depends on task 03 (so the config and `IntakeMetrics` are already free of leadership).

## Behavior

`ClaimReviewService.submit(claimId, version, decision)`:

1. **One transaction** (`claimReview`, `REQUIRES_NEW`): the existing checks (claim exists, review open, version, the reviewed values fit), then the run must be **ready and hosted here**:
   `ClaimIntakeState.isReadyForDecision()` (the app-recorded wait) **and** `flow.definition().activeInstance(runId)` is `WAITING` in this JVM. Otherwise `ReviewRunNotWaitingException` (`409`);
   this is what a run that died with the last JVM looks like, until reclaim gives the claim a new one. Then `decidingRunId = reviewRunId`, `reviewRunId = null`. It returns the `ReviewDecided`.
2. **After the commit**, up to `parasol.intake.review.max-publish-attempts` (default `20`): while the claim's `decidingRunId` still names the run, publish through `ReviewDecisionPublisher`, then
   park `parasol.intake.review.publish-interval` (default `250ms`; `LockSupport.parkNanos`, virtual-thread friendly, never throws). A publish failure is logged and retried. A run's `WAITING` status becomes
   visible a couple of statements before its `listen` registers a consumer, so a first publish can be missed; this loop is what closes that gap in production (it was `IntakeScenario.publishUntilWoken` in tests).
3. **Taken** means `decidingRunId` no longer names the run: the decision steps clear it when they apply the decision, and when they fault and put the claim back in review.
   A duplicate publish is harmless: `isForThisRun` checks the run id and the claim's state again.
4. **Never taken:** `restoreReview` (compensating `REQUIRES_NEW`; only if `decidingRunId` still names the run: `reviewRunId = runId`, `decidingRunId = null`), then `ReviewRunNotWaitingException`
   (`409`). The reviewer decides again. The endpoint stays `202 Accepted` on success (it now returns once the run has taken the decision, not once the workflow has applied it).
5. `isReadyForDecision(state)` (used by `GET /review`) is `state.isReadyForDecision() && hostedAndWaiting(state.reviewRunId)`.

Constructor: `ClaimReviewService(ClaimIntakeFlow flow, ReviewDecisionPublisher publisher, IntakeConfig config)`. Add `IntakeConfig.review()` with `Review { publishInterval (250ms), maxPublishAttempts (20, @Min(1)) }`;
document both keys in the README config table and `CLAUDE.md`.

## What to Do

### 1. Delete

- `IntakeRunSignal`, `org.parasol.intake.workflow.signal.RunSignalRelay`, `V3__intake_run_signal.sql`, `IntakeConfig.Signals` / `signals()` / `parasol.intake.signals.*`, `RunSignalRelayTests`.
- `IntakeActivity.RunSignalDelivered`; in `IntakeMetrics`: `onRunSignalDelivered`, `SIGNAL_DELIVERY_BUCKETS`, the `signals.pending` gauge and its `Locale`/`IntakeRunSignal` imports if unused.
- Dashboard panels for `claim.intake.signals.pending` and `claim.intake.signal.delivery`; their `IntakeMeterSchema` entries; the `signals.pending` name in `IntakeMetricsTests`.
- `IntakeScenario`: `signalCount`, `queueDecisionFor`, the relay constructor parameter, `IntakeRunSignal.deleteAll()`.

### 2. Rewrite `ClaimReviewService` as above. Update `ReviewDecisionPublisher`'s Javadoc ("the local leg" wording goes), `ReviewRunNotWaitingException`'s message if needed.

### 3. `ReviewRecoverer`: drop `keepDecision` and `retarget`

`takeOver` always sets `reviewRunId = newRunId` and `decidingRunId = null`: a decision claimed for a run that's gone is **not** kept (it lived in memory); the review is open again for the new run.
`reclaim` and `recover` both pass through the same path. Remove the `ObjectMapper` constructor parameter if nothing else uses it.

### 4. Renumber the migrations

`V4__review_waiting_run.sql` → `V3__…`, `V5__correspondence_recovery.sql` → `V4__…`. (Unmerged branch; say in the README that a database that applied the old numbers is recreated once.)
Update every reference to `V4`/`V5` in code comments and docs.

### 5. Tests

- `ClaimReviewResourceTests`:
  - `aDecisionForAVanishedRunIsQueuedForReclaim` becomes a `409` (`ReviewRunNotWaitingException`) and the claim is left in review, nothing "being decided".
  - The `signalCount()` assertions go.
- New `ClaimReviewServiceTests` (bare `%test` group, real flow): (a) the decision is republished until taken when the first publish is missed (`@InjectSpy ReviewDecisionPublisher` skipping the first N publishes; set `publish-interval` small via the test config only if
  the `%test` default is too slow); (b) a decision nobody takes restores the review and fails with `409` (publisher spied to never publish, `max-publish-attempts` reached); (c) `restoreReview` doesn't undo a decision the run took meanwhile.
  Mind the *no `@InjectMock` of an intake agent* rule; the publisher is not an agent.
- `ReviewReclaimerTests`: `aDecisionAlreadyMadeForTheRunThatIsGoneIsAppliedByTheNewRunWithNoSecondDecision` becomes "…the review is open again for the new run, and a fresh decision is applied"; drop the `signalCount` wait.
- `IntakeMetricsTests`, `IntakeMeterSchema`, `GrafanaDashboardTests`: green without the signal meters.

## Done When

- [x] `grep -rn "IntakeRunSignal\|RunSignalRelay\|signal" src` finds only unrelated hits (e.g. `Signal` in a library name).
- [x] A decision POSTed while a run is waiting is applied; one POSTed for a run that isn't hosted here is a `409` and leaves the claim `Pending Review`, ready for a new run.
- [x] `ClaimReviewResourceTests`, `ClaimReviewServiceTests`, `ReviewReclaimerTests`, `ClaimIntakeReviewTests` (the decision-path workflow tests), `IntakeMetricsTests` and `GrafanaDashboardTests` pass under `-Pollama`.
- [x] `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` pass; Flyway starts from an empty database with `V1`–`V4`.
