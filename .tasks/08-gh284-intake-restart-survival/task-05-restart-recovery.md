# Task 05: Restart Recovery for a Single Instance

**Type:** Verification / Adaptation

## Goal

After the application restarts (a pod restart, a live reload that rebuilds the beans, a crash), every claim in `Pending Review` has a live run again, and every email left in `Processing`
is either run again from the start, resumed at *reply → file*, or only filed: never dropped, never answered twice. This keeps the behavior of the committed reclaim (`3edb028`) and
Processing recovery (`2fd7d20`) and adapts them to the removal of leadership and signals. Depends on tasks 03 and 04, which do most of the editing; this task proves the result and fixes the leftovers.

## What to Do

### 1. Confirm the wiring

- `ReviewReclaimer` runs on `StartupEvent` on the `@VirtualThreads` executor, skips a claim whose latest inbound email is still in `Processing` (that one is the Processing recovery's), and calls `ReviewRecoverer.reclaim`.
- `ClaimsInboxWatcher` hands over what is in `Processing` as `EmailReceived(email, isRecovery=true)` on its first connection after a start; `ClaimIntakeDispatcher.recover` asks `ProcessingRecovery` how far the run got from the app's own tables only (never Flow's).
- `quarkus.flow.persistence.auto-restore: false` and its comment ("replays from the first incomplete task and would send mail twice") still read correctly.
- After filing an answered email the dispatcher still sweeps `reclaim()` for a claim that waits for review (it covers a claim whose run was resumed rather than reclaimed).

### 2. Adapt what is left

- Wording: "a previous leader" / "the new leader" → "a previous JVM" / "the restarted application" in `ReviewReclaimer`, `ReviewRecoverer`, `ProcessingRecovery`, `EmailReceived`, `ClaimIntakeDispatcher`, `IntakeReplySender`/`ReplyRecorder` Javadoc, and `application.yml` comments.
- A claim whose review decision was claimed for the gone run (`decidingRunId` set) goes back to open for the new run (task 04); confirm the reviewer's panel shows it as open (`GET /review` → `open: true` once the new run waits) and that nothing is left "being decided".
- Keep the accepted residual gap, and say so in the docs: an instance that dies between the SMTP send and `ReplyRecorder`'s commit leaves no `OUTBOUND` row, so the customer gets that reply twice.

### 3. Tests

Run the existing ones; add only what's missing:

- `ReviewReclaimerTests` (new run takes a fresh decision; a live run is untouched; reclaiming twice starts one run) and `ProcessingRecoveryTests` pass.
- One test that proves **a decision claimed for a run that is gone is not left stuck**: claim the review for a run, cancel the run, reclaim, assert `decidingRunId` is `null`, `reviewRunId` names the new run and a new decision applies
  (this is the adapted `aDecisionAlreadyMade…` test from task 04; don't duplicate it here, just confirm it exists).

## Done When

- [x] `grep -rn -i "previous leader\|new leader\|the leader" src` finds nothing.
- [x] `ReviewReclaimerTests`, `ProcessingRecoveryTests`, `ClaimsInboxWatcherTests` and the intake workflow tests pass under `-Pollama`.
- [ ] A manual `-Pollama quarkus:dev` smoke: send a claim that goes to `Pending Review`, restart dev mode (the Dev Services database survives a live reload), and the claim's review has a new waiting run and accepts a decision. (not done: not run in this session / awaiting your approval)
