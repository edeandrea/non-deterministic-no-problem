# Task 06: Recover Emails a Dead Leader Left in Processing

**Type:** Feature (failover) + Schema Modification

## Goal

An email whose run died before it was filed is neither dropped as a "duplicate" nor answered twice. It runs again,
resumes at its reply, or is just filed, depending on how far its run got. Closes the rest of **mechanism 7**, which
task 02's persistent database makes reachable even with one replica.

## The rule (user, 2026-11-10)

For each email the new leader finds in `Processing` (`ClaimsInboxWatcher.recoverOnce`), look up its `INBOUND`
`ClaimCorrespondence` row by `Message-ID`:

| Row | What it means | What happens |
|---|---|---|
| none | never accepted | the normal path (`submit`), as today |
| no claim linked | accepted, nothing applied (`ApplyOutcomeStep` links the claim in its own transaction) | run again from the start, **bypassing** the duplicate check |
| claim linked, and an `OUTBOUND` row has `in_reply_to` = this `Message-ID` | applied **and** answered; only filing was left | file it, and fire `EmailHandled` once |
| claim linked, no such `OUTBOUND` row | applied, reply not recorded | **resume at reply → file**: a new run variant |

The residual gap is accepted, and should be stated in the code: a pod that dies between the SMTP send and
`ReplyRecorder`'s commit (or a recording that failed with only a WARN) leaves no `OUTBOUND` row, so the customer gets
that reply twice.

## What to Do

### 1. `in_reply_to` on outbound rows: `V<n>__correspondence_in_reply_to.sql`

`ClaimCorrespondence.inReplyTo` (nullable, indexed). `ReplyRecorder` sets it from the `ThreadingHeaders` the reply was
sent with (the customer's `Message-ID`; check every send path that passes a claim number). Inbound rows leave it null.
`ClaimMapper`/`CorrespondenceMessage` don't expose it (`ClaimCorrespondenceResourceTests.jsonFieldNames` must not
change).

### 2. The recovery path in the dispatcher

`ClaimIntakeDispatcher.resubmit(InboundEmail)` (or a `RecoveredEmail` event the watcher fires instead of
`EmailReceived` for the Processing hand-over) applies the rule above, keeping per-sender order: recovered emails queue
ahead of anything the INBOX brings, as today.

### 3. *Resume at reply* as a run variant

Like `IntakeRun.resumesReview`, an `IntakeRun.resumesReply` routes `initialize` → the right `reply*` step → `file` →
(`waitReview` if the claim is `Pending Review`). The step needs the dead run's `IntakeState` after `applyOutcome`. Task 01 (Q5) found it's
fully readable from Flow's persisted data: the dead run's newest `task_info_entity` row's model, read through
`JpaInstanceOperations.readWorkflowInfo` or natively, turns back into a complete `IntakeState` with
`model.as(IntakeState.class)` (claim id, outcome, policy number, missing items, `needsReview`, `receivedAt`). Nothing has
to be rebuilt from `Claim`/`ClaimIntakeState`. Read it **before** the dead run's rows are retired (task 05's `DELETE`),
then retire them. Use the same newest-row native read as task 03, so there's one code path. If the dead run was the
claim's review run, this resumed run **is** the new review run. Coordinate with task 05's reclaim, so a claim doesn't
get two (decide which owns it: recommended, `resumesReply` takes the review and reclaim skips a claim whose email is
being resumed).

### 4. Tests (bare `%test` group)

One per row of the table, built by putting a real email in `Processing` with the database already in that state
(through `IntakeScenario`'s fixtures), then firing `LeadershipAcquired`:
- none → a claim and one reply.
- no claim linked → one claim (not two) and one reply.
- claim + outbound → filed; no new reply; `claim.intake.emails` counts it once.
- claim, no outbound → exactly one reply with the right template and claim number, then filed; a `Pending Review`
  claim ends up with one live review run.

## Files/Areas

- `src/main/resources/db/migration/V<n>__correspondence_in_reply_to.sql`
- `ClaimCorrespondence`, `ReplyRecorder`, `IntakeReplySender` (if the threading id must reach the recorder)
- `ClaimsInboxWatcher`, `ClaimIntakeDispatcher`, `IntakeRun`, `ClaimIntakeFlow` (the `routeMatch` switch),
  `ClaimMatchingSteps` or `ReplySteps`
- Tests: `IntakeReplySenderTests` (the recorded `in_reply_to`), a new `ProcessingRecoveryTests`
- `CLAUDE.md` (*Where the email is decides what its sender hears*, `ClaimCorrespondence`, `IntakeRun`),
  `docs/design/email-claim-intake.md`, `docs/claim-intake-flow.puml` (the new route; re-render in task 09)

## Key Points

- **The flow files an email only after it has replied** (`CLAUDE.md` → *Gotchas*): that's why "still in Processing"
  means "maybe not answered". Don't change that order.
- `EmailHandled` must still fire exactly once per email: the existing `IntakeMetricsTests` assertion on
  `claim.intake.emails` is the guard. Add the recovered cases to it.
- `ClaimIntakeFlow` is the only place with side effects. The new route is a `switchCase` entry, not logic in the
  dispatcher.
- A new `ClaimIntakeFlow` task changes the workflow definition. Check that the Flow Dev UI still draws it (the demo
  guide relies on it).

## Done When

- [ ] Each of the four rows has a passing test under `-Pollama`, and the existing intake workflow tests still pass.
- [ ] Outbound rows record `in_reply_to`; the correspondence API's JSON keys are unchanged.
- [ ] The residual double-reply window is documented in the code and in `CLAUDE.md`.
