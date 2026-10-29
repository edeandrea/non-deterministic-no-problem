# Task 11b: More Business and Operational Metrics for the Intake

**Type:** Code Modification

**Issue:** [#271](https://github.com/edeandrea/non-deterministic-no-problem/issues/271)

## Goal

Task 11 ([#260](https://github.com/edeandrea/non-deterministic-no-problem/issues/260)) added the `claim.intake.*` meters
that say what came in and what became of it. This task adds the ones that say **how long it took, how much is waiting, how
well the agents did, and what it cost**.

*Added 2026-10-29 after task 11's metrics were built. Not required for #216 to land; sequenced after task 11, and it does
not block tasks 12 to 14.*

**Which candidates to build is to be decided by the user.** The list below is evidence and recommendations only.

## Existing meters (task 11, `IntakeMetrics`)

| Meter | Type | Tags |
|---|---|---|
| `claim.intake.emails` | counter | `outcome` (`EmailOutcome`) |
| `claim.intake.replies` | counter | `template` |
| `claim.intake.failures` | counter | `stage`, `exception`, `outcome` |
| `claim.intake.claim.status.transitions` | counter | `status` |
| `claim.intake.review.decisions` | counter | `decision` |
| `claim.intake.review.wait` | timer | none |
| `claim.intake.reviews.pending` | gauge | none |
| `claim.intake.watcher.connected` | gauge | none |
| `claim.intake.watcher.reconnects` | counter | none |

Already provided elsewhere, so not duplicated: `quarkus.flow.workflow.started.total` / `.completed.total` / `.duration`
(Flow), and token usage, model-call latency, estimated cost and guardrail outcomes (quarkus-langchain4j,
see [issue #218's](https://github.com/edeandrea/non-deterministic-no-problem/issues/218) panel list).

## Candidates

Each is one more `IntakeActivity` event fired through `IntakeActivities` plus one observer in `IntakeMetrics`, unless the
"Source" column says otherwise. The intake code never touches a meter. Every tag has a small fixed set of values; never a
claim number, `Message-ID`, address or conversation id.

### Pipeline health

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 1 | `claim.intake.email.latency` | timer, `outcome` | the email's sent date or IMAP internal date, to when it is filed | What the claimant waits; Flow's duration only covers the run |
| 2 | `claim.intake.inbox.depth` | gauge | IMAP `STATUS` on INBOX (a mailbox read, so it needs the `ClaimsMailbox` lock) | Backlog when the watcher is slow or down; `watcher.connected` says the connection is up, not that work isn't piling up |
| 3 | `claim.intake.attachments` | counter, `result` (`accepted`, `skipped`) and, for skips, `reason` (`SkippedAttachment.Reason`) | the parsed `InboundEmail` | Images dropped for size or type are invisible today |
| 4 | `claim.intake.email.size` | distribution summary (bytes) | the parsed message | Do big emails correlate with slow runs or failures? |

### Agent and routing quality

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 5 | `claim.intake.route` | counter, `route` (`EmailRoute`: `extract`, `follow_up`, `unmatched`) and `email_type` (`EmailType`) | `EmailRouter` decision | The routing mix; a shift is an early drift signal |
| 6 | `claim.intake.matcher.result` | counter, `result` (matched, unsure, no match, wrong sender) | `ClaimMatcher` / `ClaimIntakeDispatcher` | A rising unsure rate points to a worse resolver |
| 7 | `claim.intake.extraction.completeness` | summary, share of required fields filled (0 to 1) | the extraction result, against `MissingItem` | Fits the "non-deterministic" theme better than pass/fail counts |
| 8 | `claim.intake.missing.items` | counter, `item` (`MissingItem`) | `FileEmailStep` / the missing-information reply | What claimants most often leave out |
| 9 | `claim.intake.followups.per.claim` | distribution summary | correspondence count when a claim becomes complete | Several round trips mean the missing-information reply isn't clear |

### Review loop

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 10 | `claim.intake.review.oldest.pending` | gauge, seconds | `min(ClaimIntakeState.reviewOpenedAt)` over pending claims | A stuck claim shows even when the `reviews.pending` count stays flat |
| 11 | `claim.intake.claim.time.to.complete` | timer, `status` | first inbound `ClaimCorrespondence` to a terminal status | The business-level SLA figure |
| 12 | review overrides | counter, `agreement` (`agreed`, `overridden`) | needs the agent's recommendation stored next to the reviewer's decision; check the model first, it may not be kept | How far the agent can be trusted |

### Cost and AI

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 13 | `claim.intake.run.tokens` (or estimated cost) | distribution summary, `token_type` | sum per run when the run finishes; **not** a per-claim tag (unbounded series) | Spend per intake run. The same number could go on the run's span |
| 14 | `claim.intake.replies.generated` | counter | AI-written status answers, counted apart from template replies (`ReplySent.template` already separates them, check before adding) | The reply type with the highest risk of wrong content |

### Mail and operations

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 15 | `claim.intake.mail.client.duration` | timer, `protocol` (`imap`, `smtp`), `operation` | `MailClientSpanInterceptor` already wraps these calls | Cheap, and the spans already exist |
| 16 | `claim.intake.mailbox.lock.wait` | timer, `mode` (`read`, `write`) | the fair read/write lock in `ClaimsMailbox` | GreenMail stalled on concurrent moves before; shows contention directly |
| 17 | `claim.intake.loop.guard.skips` | counter | `MailLoopGuard` | Auto-replies and loops skipped. `emails{outcome=skipped_auto_reply}` may already cover it; check first |

## Recommendation (not a decision)

Start with 5, 6, 11, 10 and 13: together they cover drift (routing mix, matcher result), the business outcome (time to
complete), a stuck-work signal (oldest pending) and spend. 15 and 16 are cheap but optional. Check 12, 14 and 17 against
the model and the existing meters before counting them in; each may already be covered or impossible without new data.

## What to Do (once the user picks the set)

- For each chosen candidate, add the `IntakeActivity` record, fire it where the fact is known (through
  `IntakeActivities`, the same way the existing events are), and observe it in `IntakeMetrics`.
- Use `AFTER_SUCCESS` observation only where the fact depends on a committed transaction (as `ClaimStatusChanged` does).
- A gauge that reads the database follows `pendingReviewsOrNaN()`: `REQUIRES_NEW`, no query after shutdown, removed on
  `ShutdownEvent`.
- Tests in `IntakeMetricsTests`' style: exact deltas with the expected tags, and no conversation id or claim number ever
  appears as a tag.
- Update the `CLAUDE.md` observability section and the meter table above.

## Files/Areas

- `src/main/java/org/parasol/intake/observability/` (`IntakeActivity`, `IntakeMetrics`, event types)
- The firing sites for the chosen candidates (`EmailRouter`, `ClaimIntakeDispatcher`, `FileEmailStep`, `ClaimsMailbox`, ...)
- `src/test/java/org/parasol/intake/observability/`

## Key Points

- Never mix metrics with workflow logic: steps fire an event, `IntakeMetrics` owns every meter.
- Flow and quarkus-langchain4j already export run counts, durations, tokens and guardrail data. Don't duplicate them.
- Grafana panels are #218, not this task.
- Check first whether a newer quarkus-langchain4j or Flow release already exports a candidate (for example per-run
  token usage).

## Done When

- [ ] The user has chosen the set of candidates to build.
- [ ] Each chosen meter exists, increments with the expected tags, and has a test that asserts the exact delta.
- [ ] No claim number, `Message-ID`, address or conversation id appears as a tag.
- [ ] `CLAUDE.md` and the meter table in this file are updated.
- [ ] Full `clean verify` under the CI profiles, with the real totals recorded here.

## Outcome

Not started.
