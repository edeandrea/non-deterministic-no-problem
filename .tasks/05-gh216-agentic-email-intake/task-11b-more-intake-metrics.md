# Task 11b: More Business and Operational Metrics for the Intake

**Type:** Code Modification

**Issue:** [#271](https://github.com/edeandrea/non-deterministic-no-problem/issues/271)

## Goal

Task 11 ([#260](https://github.com/edeandrea/non-deterministic-no-problem/issues/260), merged in [#270](https://github.com/edeandrea/non-deterministic-no-problem/pull/270)) added the `claim.intake.*` meters
that say what came in and what became of it. This task adds the ones that say **how long it took, how much is waiting, how
well the agents did, and what it cost**.

*Added 2026-10-29 after task 11's metrics were built. Not required for #216 to land; sequenced after task 11, and it does
not block tasks 12 to 14.*

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
| 12 | `claim.intake.review.overrides` | counter, `agreement` (`agreed`, `overridden`) | needs the agent's recommendation stored next to the reviewer's decision; check the model first, it may not be kept | How far the agent can be trusted |

### Cost and AI

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 13 | `claim.intake.run.tokens` | distribution summary, `token_type` | sum per run when the run finishes; **not** a per-claim tag (unbounded series) | Spend per intake run. The same number could go on the run's span |
| 14 | `claim.intake.replies.generated` | counter | AI-written status answers, counted apart from template replies (`ReplySent.template` already separates them, check before adding) | The reply type with the highest risk of wrong content |

### Mail and operations

| # | Meter (proposed) | Type, tags | Source | Why |
|---|---|---|---|---|
| 15 | `claim.intake.mail.client.duration` | timer, `protocol` (`imap`, `smtp`), `operation` | `MailClientSpanInterceptor` already wraps these calls | Cheap, and the spans already exist |
| 16 | `claim.intake.mailbox.lock.wait` | timer, `mode` (`read`, `write`) | the fair read/write lock in `ClaimsMailbox` | GreenMail stalled on concurrent moves before; shows contention directly |
| 17 | `claim.intake.loop.guard.skips` | counter | `MailLoopGuard` | Auto-replies and loops skipped. `emails{outcome=skipped_auto_reply}` may already cover it; check first |

## User Decisions (2026-10-29)

1. **Candidate selection:** build **#1–#4, #6–#12, #15, #16**, and replace #5 with a classifier-label counter (decision 5).
   One PR for the whole task.
2. **#14 (`claim.intake.replies.generated`) and #17 (`claim.intake.loop.guard.skips`):** skipped. Existing counters already
   cover both.
   - `IntakeReplySender` already publishes `new ReplySent("status_answer")` for AI-written status replies, counted on
     `claim.intake.replies{template="status_answer"}`.
   - `ClaimIntakeDispatcher.submit` already publishes `new EmailHandled(EmailOutcome.SKIPPED_AUTO_REPLY)` when
     `MailLoopGuard.shouldIgnore(...)` is true, counted on `claim.intake.emails{outcome="skipped_auto_reply"}`.
3. **#13 (`claim.intake.run.tokens`):** dropped. The proposed tracker keyed its token totals by conversation id, but that id
   belongs to the claim, not the run (`ClaimIntakeDispatcher` starts a reply's run in the claim's conversation). A reply that
   supersedes a waiting review run cancels that run, and the `CANCELLED` status would have flushed the reply run's totals
   mid-run. A run that never reached a waiting or terminal status would also have leaked its entry. Langfuse already sums
   tokens per session (one per claim) and per trace, and quarkus-langchain4j 1.14.2 exports
   `gen_ai.client.token.usage{gen_ai.token.type}`, so there is nothing to add.
4. **#12 (`claim.intake.review.overrides`):** computed in `ReviewDecisionSteps.applyReady` / `applyNeedsInformation`, before
   `ReviewedFields.applyTo` changes the claim, so the claim still holds the agents' values (plus any replies merged into
   it). `agreed` means `READY` with no field changed. `overridden` means `NEEDS_INFORMATION` (the agents judged the claim
   complete, and the reviewer disagreed) or any field changed. Text is stripped before comparing, so a whitespace-only edit
   isn't an override. The time is compared only when the reviewer gave a date, as `applyTo` applies it. #12 needs no
   schema change (#11 does, see decision 11).
5. **#5 replaced by `claim.intake.classifications{email_type, matched_claim}`:** `claim.intake.route` would have been
   redundant, because `emails{outcome}` already splits emails by new claim, update, status question, not a claim and no
   match. The classifier's label is new information, and `matched_claim` (`none`, `pending`, `other`) shows when the claim
   matched in code overrode the label (e.g. a reply to a pending claim labeled `not_a_claim`). Route plus label would lose
   that: `route=extract, email_type=new_claim` comes from both an unmatched new claim and a matched pending claim. The route
   follows from these two tags (`EmailRoute.route`). That's at most 9 series. `EmailRoute` stays package-private.
6. **#7 (`claim.intake.extraction.completeness`):** recorded for **new claims only**. For a reply, `findMissingItems` takes
   the claim's requested items into account, so the two numbers aren't comparable. #8 (`claim.intake.missing.items`) comes
   from the same event (`NewClaimExtracted`), so it is new claims only too.
7. **#9, renamed `claim.intake.claim.emails`:** recorded **once, on the `READY` decision** (the same place as #11). It counts
   the claim's inbound correspondence: the first email and every email later linked to the claim, status questions
   included (`ApplyOutcomeStep.linkToMatchedClaim`). That is why it isn't called "follow-ups". Recording it whenever a claim
   reaches `Pending Review` would have counted twice after a request-more-information round.
8. **#2 (`claim.intake.inbox.depth`):** not a live gauge. A gauge that reads IMAP would log in on every scrape, could block
   the scrape behind a move holding the write lock, and would pull `ClaimsMailbox` into `IntakeMetrics`, a package cycle,
   since mailbox already depends on observability. Instead, a `@Scheduled` job in the mailbox package measures the INBOX's
   message count and publishes it as an event, and the gauge reads the last value.
9. **#1 (`claim.intake.email.latency`):** measured from the IMAP received date (INTERNALDATE), not the `Date` header. The
   header is set by the sender's client, so it can be wrong or forged. Latency is recorded only where the email leaves the
   processing folder, so a review wait is never part of it:
   - **Filed:** the dispatcher puts the received time on `IntakeRun` (the workflow input), `IntakeState` carries it, and
     `FileEmailStep` records it.
   - **Failed:** `IntakeFailureHandler.handle` already reads the email from the processing folder, so it uses *that*
     email's `receivedDate()`, and records latency only when the email was still there. An email that was already filed
     (a run that faulted after its review wait, or a `resumesReview` run) was counted when it was filed. Taking the
     time from `IntakeRun` instead would have recorded the whole review wait for those runs. It would also have missed
     the dispatcher's failures: `startRun` can fail before it has the email, and the max-requeue path in `dispatch` has
     only the `Message-ID`.
   - The dispatcher reads the email from the processing folder, not the INBOX, so its INTERNALDATE is the copy's. RFC 3501
     says COPY *should* preserve it; a `ClaimsMailboxTests` case pins that GreenMail does.
10. **Requeued emails count per evaluation on `classifications` and `matcher.result`:** a run whose email `supersede` hands
    back has already run the classifier (and maybe the resolver), and the rerun runs them again. Both are counted, on
    purpose: these meters measure what the agents decided, and the LLM really did decide twice. `emails{outcome}` still
    counts the email once.
11. **#11 (`claim.intake.claim.time.to.complete`) starts at the first inbound email's received time**, not its `Date`
    header (the reason in decision 9). `ClaimCorrespondence` gets a non-null `receivedAt` column, set by
    `CorrespondenceRecorder` from `InboundEmail.receivedDate()`. The schema is regenerated on every boot, so there's no
    migration.

## Implementation Plan

### Rules for every change

- **No second constructor on a CDI bean.** ArC would need `@Inject` to choose between two parameterized constructors. Test
  code that builds `ClaimsMailbox`, `IntakeMetrics` or `MailClientSpanInterceptor` by hand passes what the new constructor
  takes. Its `IntakeActivities` comes from `IntakeActivityEvents.into(registry)`, or from a no-op one.
- **No default values that hide a missing tag value** (e.g. a `ReviewDecisionApplied` constructor defaulting to `agreed`).
  Each record has its canonical constructor only, and the callers are updated.
- Tests stay on `StubbedModelsTestProfile`: no new profile and no test-resource annotation on a test class.

### 1. Observability (`org.parasol.intake.observability`)

- **Enums** with `tag()` (`name().toLowerCase(Locale.ROOT)`): `MatcherResult` (`MATCHED`, `WRONG_SENDER`, `NO_MATCH`,
  `RESOLVED_EXISTING`, `RESOLVED_NEW_INCIDENT`, `UNSURE`), `ReviewAgreement` (`AGREED`, `OVERRIDDEN`) and `MailboxLockMode`
  (`READ`, `WRITE`), and `MatchedClaimKind` (`NONE`, `PENDING`, `OTHER`, with `of(MatchedClaim)` using `isMatched()` /
  `isPending()`).
- **`IntakeActivity`:**
  - `EmailHandled(EmailOutcome outcome, Optional<Duration> latency)`.
  - `EmailAccepted(long sizeBytes, int keptImages, List<SkippedAttachment.Reason> skippedReasons)`.
  - `EmailMatched(MatcherResult result)`.
  - `EmailClassified(EmailType emailType, MatchedClaimKind matchedClaim)`.
  - `NewClaimExtracted(double completeness, Set<MissingItem> missingItems)`.
  - `ReviewDecisionApplied(ReviewOutcome decision, ReviewAgreement agreement, Optional<Duration> waited)`.
  - `ClaimCompleted(Duration timeToComplete, long inboundEmails)`, a separate fact rather than `READY`-only fields on
    `ReviewDecisionApplied`, so no field is "present only for one decision".
  - `MailClientCompleted(MailClientSpan.Protocol protocol, String operation, boolean failed, Duration duration)`.
  - `MailboxLockAcquired(MailboxLockMode mode, Duration waited)`.
  - `InboxDepthMeasured(int messages)`.
- **`MailClientSpanInterceptor`:** takes `IntakeActivities` too. It times `context.proceed()` and, in `finally`, publishes
  `MailClientCompleted`, with `failed` set when the call threw.
- **`IntakeMetrics`** (still only a `MeterRegistry`):
  - Gauges, removed in `onShutdown` like the existing ones:
    - `claim.intake.inbox.depth`: an `AtomicInteger` set from `InboxDepthMeasured`, `NaN` until the first measurement.
    - `claim.intake.review.oldest.pending` (seconds): `ClaimIntakeState.oldestPendingReviewOpenedAt()` in a
      `REQUIRES_NEW` method, guarded by `stopped`, like `pendingReviewsOrNaN`. `0` when no review is pending. It counts
      the same claims as `reviews.pending` (status `Pending Review`).
  - Meters:
    - `claim.intake.email.latency{outcome}`: timer, clamped at `Duration.ZERO`.
    - `claim.intake.attachments{result="kept", reason="none"}` and `{result="skipped", reason=<reason>}`: counters.
      `kept` means kept when the email was parsed; images are stored only for a new claim or an update.
    - `claim.intake.email.size`: distribution summary, in bytes.
    - `claim.intake.classifications{email_type, matched_claim}`: counter, per evaluation (decision 10).
    - `claim.intake.matcher.result{result}`: counter, per evaluation (decision 10).
    - `claim.intake.extraction.completeness`: distribution summary, new claims only. `AFTER_SUCCESS`.
    - `claim.intake.missing.items{item}`: counter, from `NewClaimExtracted`. `AFTER_SUCCESS`.
    - `claim.intake.claim.emails`: distribution summary, from `ClaimCompleted` (decision 7). `AFTER_SUCCESS`.
    - `claim.intake.claim.time.to.complete`: timer with no tag (`READY` is the only completion), from `ClaimCompleted`:
      the first inbound email's received time to `In Process`. `AFTER_SUCCESS`.
    - `claim.intake.review.overrides{agreement}`: counter. `AFTER_SUCCESS`.
    - `claim.intake.mail.client.duration{protocol, operation, outcome}`: timer, with `outcome` `success` or `error`.
    - `claim.intake.mailbox.lock.wait{mode}`: timer.

### 2. Mailbox (`org.parasol.intake.mailbox`)

- **`InboundEmail`:** add `Instant receivedDate` (IMAP INTERNALDATE, falling back to `sentDate`) and `long sizeBytes`
  (`Message.getSize()` when it's above 0, otherwise the body plus attachment bytes). Update the 6 call sites; no extra
  constructor.
- **`MimeMessageParser`:** fills both fields.
- **`ClaimsMailbox`:** takes `IntakeActivities`. `withFolder` times `lock.lock()` and publishes `MailboxLockAcquired`.
  `inboxDepth()` reads the INBOX's message count through IMAP `STATUS`, using `getMessageCount` on a folder it doesn't open.
  It's a read, so it takes the read lock like every other read (reads share, moves are exclusive) and its wait is
  published like any other. It isn't a `@MailClientSpan` method, so the measurement adds no spans.
- **`InboxDepthMonitor`** (new, package-private): a `@Scheduled` job (`parasol.intake.metrics.inbox-depth-interval`, default
  30 s, in `IntakeConfig`) that publishes `InboxDepthMeasured`. It does nothing when `IntakeConfig.enabled()` is false,
  like the watcher and the dev seeder. Otherwise every test boot (`%test` turns the intake off) would log in to GreenMail
  every 30 s, even while `ClaimsMailboxTests` deletes and recreates the claims user. It uses
  `concurrentExecution = SKIP`, so a measurement stuck on the 10 s IMAP timeout can't pile up behind itself. A failure is
  logged at DEBUG and skipped.

### 3. Dispatch, agents and workflow steps

- **`ClaimIntakeDispatcher`:** publishes `EmailAccepted` once `recordIfNew` accepts the email. `SKIPPED_AUTO_REPLY` and
  `DUPLICATE` carry latency from `receivedDate`.
- **`IntakeRun`:** carries `receivedAt` (an ISO-8601 string, JSON-safe), set by the dispatcher from
  `InboundEmail.receivedDate()`. It's nullable (no `requireNonNull`), so a run persisted before this change still reads;
  `null` means no latency. `IntakeRun.resumingReview` copies it. **`IntakeState.start`** copies it too, for `FileEmailStep`
  (its only reader). `IntakeJsonRoundTripTests` covers both.
- **`CorrespondenceRecorder`:** sets `ClaimCorrespondence.receivedAt` from `InboundEmail.receivedDate()` (decision 11).
- **`IntakeFailureHandler`:** no new overload, and **`IntakeFailureListener`** doesn't change (decision 9). `handle` publishes
  `EmailHandled(FAILED)` exactly once, after its lookup. The latency comes from the email found in the processing folder,
  and is empty when the email wasn't there. If the lookup throws, the `catch` publishes it without latency.
  `handleWithoutMessageId(email)` takes the time from `email.receivedDate()`.
- **`ClaimMatchingSteps`:** becomes able to publish (it gets `IntakeActivities`; `initializeForEmail` stops being static).
  `initializeForEmail` publishes `MATCHED`, `WRONG_SENDER` or `NO_MATCH`. `NO_MATCH` is published only when no pending
  claims are offered to the resolver; otherwise `resolveClaim` publishes `RESOLVED_EXISTING`, `RESOLVED_NEW_INCIDENT` or
  `UNSURE`. A resumed review publishes nothing. A requeued email's rerun publishes again (decision 10).
- **`ClaimsMailboxAgent` / `AgentStep`:** the root currently returns only the `IntakeOutcome`, so `AgentStep` can't see the
  label. The root gets an `@Output` that reads `emailType` and `intakeOutcome` from the scope and returns a small
  `ClassifiedOutcome(EmailType, IntakeOutcome)` in `org.parasol.intake.model`. The root's `outputKey` changes from
  `intakeOutcome` (the router's key, which the `@Output` reads) to `classifiedOutcome`. Otherwise one scope key would hold
  two types. `AgentStep` publishes `EmailClassified(type, MatchedClaimKind.of(matched))` and keeps the outcome in
  `IntakeState` as before. Confirm first that 1.14.2 honors `@Output` on a `@SequenceAgent`, not only on a router. Then
  check that the build's output-key check and `ClaimsMailboxAgentTests` still pass.
- **`ApplyOutcomeStep`:** moves the `ClaimStatusChanged` publish into `persist` (the transaction), and publishes
  `NewClaimExtracted` from `applyNewClaim`, using `ClaimExtractionRules.completeness(missing)` = `(n − |missing|) / n`,
  with `n = MissingItem.values().length` (not a literal 4).
  Today the publish runs after the commit, with no transaction open, so `AFTER_SUCCESS` fires immediately. Inside the
  transaction, a rollback counts nothing.
- **`FileEmailStep`:** passes the latency from `IntakeState.receivedAt` to `EmailHandled`.
- **`ReviewedFields`:** `changes(Claim)`: whether any non-null reviewed field differs from the claim's value. Text is
  stripped before comparing, and the time is compared only when a date is given.
- **`ReviewDecisionSteps`:** `applyReady` / `applyNeedsInformation` compute the agreement before `applyTo`. `applyReady`
  also computes the time to complete from `ClaimCorrespondence.findFirstInboundReceivedAt(claim)` and the count from
  `ClaimCorrespondence.countInboundForClaim(claim)`, and publishes `ClaimCompleted`. Both publish `ClaimStatusChanged` and
  `ReviewDecisionApplied` inside the transaction. `recordDecision` and `Snapshot.reviewOpenedAt` move in with them.
- **`ClaimCorrespondence`:** a non-null `Instant receivedAt` column (decision 11), `countInboundForClaim(Claim)` and
  `findFirstInboundReceivedAt(Claim)`. **`ClaimIntakeState`:** `oldestPendingReviewOpenedAt()` (`Optional<Instant>`),
  over claims whose status is `Pending Review`, the set `Claim.countPendingReview` counts.

### 4. Tests and documentation

- **`IntakeActivityEvents`:** routes every new `IntakeActivity` to `IntakeMetrics`.
- **`IntakeMetricsTests`:** adds every new meter to `meterTagsHaveOnlyTheirDeclaredKeysAndBoundedValuesAndNoIdentifiers`,
  asserts the exact delta for each, and adds a rollback test for every new `AFTER_SUCCESS` observer. Its new scenarios
  include:
  - one per `MatcherResult` path;
  - a requeued email, which adds 2 to `classifications` and 1 to `emails` (decision 10);
  - a run that faults after its review wait, which records no `email.latency` (decision 9).
- **`ClaimsMailboxTests`:** `receivedDate` and `sizeBytes`, the same `receivedDate` read back after a move to the
  processing folder (decision 9), and `inboxDepth`.
- **`IntakeJsonRoundTripTests`:** `IntakeRun.receivedAt` and `IntakeState.receivedAt`, including a `null` one.
- **`ClaimsMailboxAgentTests`:** the root returns a `ClassifiedOutcome` carrying the classifier's label.
- **Unit tests:** `ReviewedFields.changes` (whitespace, time without a date), `completeness`, `MailClientSpanInterceptor`
  (`MailClientCompleted` on success and on error) and `InboxDepthMonitor` (a failure is skipped, and nothing is measured
  when the intake is off).
- **Docs:** `CLAUDE.md` (the metrics bullet, the `IntakeActivity` list, the mailbox and model package rows,
  `ClaimCorrespondence`'s fields, and `reviewOpenedAt`, which is no longer "read only by the review-wait timer"),
  `.tasks/05-gh216-agentic-email-intake/PLAN.md`, and this file's meter table and checklist.

## Files/Areas

- `src/main/java/org/parasol/intake/observability/` (`IntakeActivity`, `IntakeMetrics`, `MailClientSpanInterceptor`,
  `MatcherResult`, `ReviewAgreement`, `MailboxLockMode`, `MatchedClaimKind`)
- `src/main/java/org/parasol/intake/mailbox/` (`InboundEmail`, `MimeMessageParser`, `ClaimsMailbox`, `InboxDepthMonitor`)
- `src/main/java/org/parasol/intake/config/IntakeConfig.java`
- `src/main/java/org/parasol/intake/dispatch/` (`ClaimIntakeDispatcher`, `CorrespondenceRecorder`)
- `src/main/java/org/parasol/intake/failure/IntakeFailureHandler.java`
- `src/main/java/org/parasol/intake/agent/ClaimsMailboxAgent.java`, `src/main/java/org/parasol/intake/model/ClassifiedOutcome.java`
- `src/main/java/org/parasol/intake/agent/extraction/ClaimExtractionRules.java`
- `src/main/java/org/parasol/intake/workflow/` (`IntakeRun`, `IntakeState`, `ClaimMatchingSteps`, `AgentStep`,
  `mail/FileEmailStep`, `outcome/ApplyOutcomeStep`, `review/ReviewDecisionSteps`)
- `src/main/java/org/parasol/intake/review/ReviewedFields.java`
- `src/main/java/org/parasol/claim/model/ClaimCorrespondence.java`, `src/main/java/org/parasol/intake/model/ClaimIntakeState.java`
- `src/test/java/org/parasol/intake/observability/` (`IntakeActivityEvents`, `IntakeMetricsTests`,
  `MailClientSpanInterceptorTests`), `src/test/java/org/parasol/intake/mailbox/` (`ClaimsMailboxTests`,
  `ClaimsMailboxConcurrencyTests`), `IntakeJsonRoundTripTests`, `ClaimsMailboxAgentTests`
- The test files that build an `InboundEmail` by hand: `MailLoopGuardTests`, `ClaimMatcherTests`, `CustomerNameTests`,
  `SameCustomerRuleTests`

## Key Points

- Never mix metrics with workflow logic: steps fire an event, `IntakeMetrics` owns every meter.
- Flow and quarkus-langchain4j already export run counts, durations, tokens and guardrail data. Don't duplicate them.
- Grafana panels are #218, not this task.
- Check first whether a newer quarkus-langchain4j or Flow release already exports a candidate.

## Done When

- [x] The user has chosen the set of candidates to build.
- [x] Each chosen meter exists, increments with the expected tags, and has a test that asserts the exact delta.
- [x] No claim number, `Message-ID`, address or conversation id appears as a tag.
- [x] `CLAUDE.md` and the meter table in this file are updated.
- [x] Full `clean verify` under the CI profiles, with the real totals recorded here.

## Outcome

In progress (plan written 2026-10-29, revised the same day: #13 dropped, #5 replaced, #7 and #9 recorded once).

**Plan review (2026-10-29), before implementation.** The plan was checked against the code, and these changes came out
of it:
- **Failure-path latency:** taken from the email still in the processing folder, not from `IntakeRun`. A run that
  faulted after its review wait would otherwise have recorded the whole wait, and the dispatcher's failures don't always
  have the email (decision 9).
- **The received date:** must survive the move to the processing folder (a new test).
- **The root agent:** its `outputKey` is renamed, so it doesn't clash with the router's.
- **The inbox-depth job:** it takes the read lock (the candidates table and §2 contradicted each other), does nothing
  while the intake is off, and skips overlapping runs.
- **The events:** `ClaimCompleted` is split out of `ReviewDecisionApplied`.
- **Smaller fixes:** `completeness` uses `MissingItem.values().length`; #9 is renamed `claim.intake.claim.emails`; the
  oldest-pending gauge filters on the same status as `reviews.pending`; `IntakeRun.receivedAt` is nullable.
- **User decisions 10 and 11:** requeued emails are counted per evaluation, and #11 starts from a new
  `ClaimCorrespondence.receivedAt` column.

**Built (2026-10-29).** Full `./mvnw clean verify`, run one profile at a time:
- `-Pollama`: 591 tests, 0 failures, 0 errors, 2 skipped (the gated drift test and the stress test).
- `-Pollama-openai` (with stub `OPENAI_API_KEY`/`COHERE_API_KEY`/`GEMINI_API_KEY`, as CI sets): 591 tests, 0 failures, 0 errors, 3 skipped (the same two plus `NotificationServiceTests.emailSendsWhenUserExists`).
- `@Output` on the `@SequenceAgent` root works in quarkus-langchain4j 1.14.2 (`ClaimsMailboxAgentTests`).
- Nothing is committed.
