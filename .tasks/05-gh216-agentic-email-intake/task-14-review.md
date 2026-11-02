# Task 14 — Independent Review of the Email Claim Intake

**Scope:** the whole `gh216-email-intake` → `main` diff (349 files, ~29,500 insertions), reviewed on 2026-11-02 at
`gh216/14-verification`, which is `gh216-email-intake` @ `e399684` plus this task's own changes.

**Method.** Three reviewers with no part in building the feature worked disjoint dimensions in parallel, each reading
the code rather than the prose, and each told to report only what it could falsify:

| Dimension | Scope |
|---|---|
| 1 | Production Java under `org.parasol.intake.**`, plus `ClaimCorrespondence` and `ClaimImage` — correctness, concurrency, transactions, error handling, standards. Settled R1–R5. |
| 2 | The test suite (`org.parasol.intake.**`, `org.parasol.testing.**`, `org.parasol.ui.**`) — is it evidence the feature works, or does it only look like evidence? |
| 3 | All markdown and diagrams, plus `org.parasol.intake.observability` and `ai.scoring.conversation` — drift between what the docs claim and what the code does. |

Severity: **P0** blocker · **P1** major · **P2** correctness or material gap · **P3** standards or maintainability.

## Verdict

**No P0 or P1 finding.** The feature is sound: no agent has side effects, every intake AI service carries both
statelessness opt-outs, no transaction is held across an agent, IMAP or SMTP call, no step touches a meter, the reply
templates disclose nothing, every rule in task 08 maps to a test that drives the real `ClaimIntakeFlow`, no test mocks
any agent, and the `claim.intake.*` meter list in `CLAUDE.md` is exactly the 23 meters `IntakeMetrics` registers.

Two genuine defects were found, both in failure paths, both now **fixed with regression tests** (CIDQ-1, CIDQ-2). The
documentation drift the third reviewer found is **fixed**. R1–R6 each have a recorded resolution below. The remaining
findings are P3 and are accepted or deferred, with reasons.

## Fixed in this task

- [x] **CIDQ-1 — P2: `ClaimIntakeDispatcher.startRun` released the wrong queue holder, wedging a sender forever.**
  `ClaimIntakeDispatcher.java:188-208` moved the sender's state to `working(instance.id())` before starting the run,
  but the catch always released `SenderState.STARTING`. `SenderState.release` only clears `working` when it equals the
  holder, so after that transition the catch was a no-op: the run never reaches a `WorkflowStatus`, so nothing else
  ever released the sender, `senderByInstance` kept a dangling entry, and every later email from that address queued
  forever with no reply and no failure counter.
  **Fix:** the current holder is tracked in an `AtomicReference` (`STARTING`, then the run's id) and the catch releases
  that, and removes the `senderByInstance` entry.
  **Test:** `ClaimIntakeDispatcherStartFailureTests` (a plain Mockito test — the throw has to come from
  `WorkflowInstance.start()`). Verified to fail without the fix and pass with it.

- [x] **CIDQ-2 — P2: a faulted run whose email was already filed still told the customer "we couldn't process your
  email" — even after the review had been recovered.** `IntakeFailureHandler.handle` deliberately fell back to the
  `PROCESSED` folder and sent `processingProblem` for an already-filed email, so after a review run faulted post-wait
  the customer got a failure notice about an email that *was* processed, while the claim was healthy and a new run was
  already waiting for the reviewer — and the email was re-counted as `claim.intake.emails{outcome=failed}`.
  **Fix (user decision, 2026-11-02: "option D-plus", chosen over a narrower fix keyed on review ownership):**
  **where the email is decides what its sender hears.** The flow files an email only after it has replied about it —
  every branch is `reply → file`, checked against `ClaimIntakeFlow` — so an email that has left `Processing` has
  already been answered. `handle` now acts only on an email still in `Processing`: that one is moved to `Failed`,
  answered and counted; for anything else the fault is recorded and nothing else happens. That is a structural
  property of the flow rather than an inference about who owns the review, so it covers the whole class of
  post-filing faults instead of this one instance, and it removes the double `EmailHandled` that `CLAUDE.md` used to
  document as a known wart.
  **The recovery outcome survives, for the metric only.** `ReviewRecoverer.recover` returns a `ReviewHandover` enum
  (`NOT_NEEDED` / `DONE` / `ALREADY_ELSEWHERE`; an enum rather than a `boolean`, per the repo's "a value that only picks
  the next branch is an enum" rule — and named for the thing rather than the activity, since `CLAUDE.md` bans a
  `…Recovery` type name and this project had already renamed one away on 2026-10-19), and `IntakeFailureListener` turns it into the `outcome` tag —
  `recovered` when something picked the claim up, `fatal` when nothing did, so a rising `fatal` means claims are
  faulting with nothing left handling them. Two questions (what the customer hears, how the failure is counted), two
  inputs, no overlap. `handOver` now uses `ConversationContext.callIn`, which until then had no `src/main` caller.
  **Tests:** `ClaimIntakeReviewTests.aFaultOutsideTheDecisionCatchHandsTheReviewToANewRun`, which previously *asserted
  the bug* (it expected a second reply containing "problem"), now asserts the customer gets only their original
  acknowledgment; plus `IntakeMetricsTests.anEmailThatWasAlreadyFiledIsNeitherCountedNorAnsweredAgainWhenItsRunFails`
  (which previously asserted the double count) and `aRecoveredReviewCountsTheFaultAsRecoveredRatherThanFatal`.

- [x] **CONVID-TAUTOLOGY — P3: the new conversation-id assertion was half dead.** `doesNotContain(OWNER, number,
  MESSAGE_ID)` cannot fail once `matches(UUID)` has passed, and it duplicated the neighbouring test.
  **Fix:** folded into `aSeededClaimWithNoStoredConversationGetsANewRandomUuid`, keeping only the load-bearing UUID
  assertion with a comment saying why the shape alone settles the no-PII question.

- [x] **R6 / FLOWROWS — the "no leaked Flow rows" check was weaker than it read.** `IntakeScenario.instanceRows()`
  filters to `workflow_name = ClaimIntakeFlow.NAME`, so a leaked *generated* agentic run was invisible to every zero
  check, and `cloud_event_entity` was never counted.
  **Fix:** a new test-only `FlowRows` bean owns all eight queries against Flow's three tables (and is now the only
  place an `EntityManager` is wired into the intake's test helpers — `IntakeScenario` delegates and no longer holds
  native SQL). `IntakeFlowTestBase.cleanUpIntake()` asserts all three tables are empty after every test, covering all
  15 flow classes with no new profile.

- [x] **The documentation drift** found by dimension 3 — nine P2 items and seven P3 nits — is fixed; see *Documentation*
  below.

## Resolutions of R1–R6

- [x] **R1 — a committed decision whose email fails leaves the customer with nothing. Resolution: document the
  exception, do not retry** (user decision, 2026-11-02). The trade is sound — the send has no idempotency key
  (`IntakeReplySender` mints a fresh `Message-ID` per call), so a retry would answer twice. What was missing was the
  record. Now written into `docs/design/email-claim-intake.md`'s "Every submission gets a reply" invariant, into
  `PLAN.md`'s matching line, and into `ReviewDecisionSteps`'s class Javadoc, each naming the counter that records it
  (`claim.intake.failures{stage=reply,outcome=recovered}`) and saying plainly that the customer gets nothing. The
  `restoreReview → END` branch, where the decision was not even applied, is covered by the same wording.

- [x] **R2 — `supersede` → requeue has no flow-level reply guarantee. Resolution: accepted, no flow-level reply.**
  Confirmed that the guarantee lives in the dispatcher: requeue to the front of the sender's queue, a doubling-delay
  retry, then `IntakeFailureHandler.handle` after `MAX_REQUEUES`, which does send `processingProblem`. A flow-level
  terminal reply would answer an email that is about to be answered properly by the run that re-evaluates it. The real
  exposure was CIDQ-1, which would have stranded a requeued email silently — now fixed.

- [x] **R3 — the `exception` metric tag is not a fixed set. Resolution: reword the Javadoc, do not allow-list.** The
  claim was false for three tags, not one: `exception`, `template` and `operation`. All three are bounded by the code
  and none can carry PII, so the safety claim held and the cardinality claim did not. Allow-listing `exception` would
  collapse unknown classes into `other` and destroy the tag's only diagnostic value, and `FailureStage` already
  supplies the bounded dimension. `IntakeMetrics` and `IntakeActivity` now say that every tag value is bounded and
  comes from the code — an enum constant, a template name, a mail operation or an exception class name — that only
  `exception` is open-ended, and that dashboards should group by `stage`.

- [x] **R4 — "the starter's `ConversationContext.callIn` is the intake's only one" is false. Resolution: reworded.**
  The claim appeared only in the planning files, not in any source comment. `task-08-intake-processor.md` and
  `task-10b-conversation-core.md` now say the intake enters a conversation once per run start, in two places:
  `ClaimIntakeDispatcher.startRun` for a new email (`runIn`) and `ReviewRecoverer.handOver` for a run taking over a
  faulted run's review. The CIDQ-2 fix incidentally gave `callIn` its first production caller.

- [x] **R5 — every `@Transactional` step helper relies on ArC self-invocation. Resolution: note added.** No such method
  is `private` or `final` today, so nothing is broken; the hazard is that tightening one silently drops the
  transaction with no compile error. The note
  `// Non-private and non-final, so the transaction interceptor applies to the call from this class`
  is now on the sixteen self-invoked methods across the eleven classes that have one (`AgentStep`,
  `ClaimMatchingSteps`, `PolicyCheckStep`, `ApplyOutcomeStep`, `ReplySteps`, `SupersedeStep`, `ReviewDecisionSteps`,
  `ReviewRecoverer`, `CorrespondenceRecorder`, `ClaimReviewService`, `ClaimSentimentReassessor`), matching the wording
  already in `IntakeMetrics` and `IntakeReplySender`. **Deferred, recorded as worth doing:** a class-file test (the
  repo already reads class files in `ConversationLayeringTests`) asserting no `@Transactional` or `@MailClientSpan`
  method under `org.parasol.intake` is `private`, `final` or `static` would turn the comment into a build failure.
  That belongs with [#232](https://github.com/edeandrea/non-deterministic-no-problem/issues/232) (Bring in ArchUnit).

- [x] **R6 — `IntakeScenario.instanceRows()` name-filters.** Fixed; see *Fixed in this task* above.

## Confirmed covered, with evidence

The four checks the task required, and the rest of dimension 2's list:

- [x] **Every rule of task 08 has a test.** The reviewer mapped each rule in `task-08-intake-processor.md` to an
  integration test driving the real flow against Compose GreenMail and WireMock. No unmapped rule.
- [x] **The full agent path runs on every build.** No `@InjectMock` or `@InjectSpy` on any agent interface anywhere;
  the only spies are step, sender and handler beans (`ReviewDecisionSteps`, `IntakeReplySender`, `ApplyOutcomeStep`,
  `IntakeFailureHandler`, `ClaimsMailbox`, `ClaimReviewService`). The 15 classes extending `IntakeFlowTestBase` drive
  `IntakeScenario.submit` → the real dispatcher → the real `ClaimIntakeFlow` → the real `ClaimsMailboxAgent`, and
  `awaitRunSpans` pins the generated workflow names. `RoundcubeClaimIntakeE2ETests` adds the browser-to-mailbox path.
- [x] **Sub-agent `@InjectMock` under its own profile — vacuously satisfied.** Nothing mocks a sub-agent, so the
  spike's stale-reference hazard can't bite. The constraint is now written in `StubbedModelsTestProfile`'s Javadoc and
  in `CLAUDE.md` → Testing, so the next person to try it knows it needs its own profile and another boot.
  Dimension 2 notes this is prose, not enforcement (NO-AGENT-MOCK-UNENFORCED, below).
- [x] **No leaked Flow rows.** Now an absolute-zero assertion on all three Flow tables after every test in all 15
  classes, passing — see CIDQ/R6 above and the Outcome in the task file.
- [x] **The conversation id is a random UUID with no PII** — `ClaimMatcherTests`.
- [x] **A reply during review and a decision are serialized by optimistic locking.** A genuine latched race exists
  (`ClaimReviewResourceTests:419`) plus two more concurrency tests and full sequential 409/422/404 coverage.
- [x] **Every intake span carries the claim's `gen_ai.conversation.id`, with no mixing under concurrency.** Genuinely
  proven: 8 overlapping runs, with the overlap itself asserted (`IntakeSpanPropagationTests:291-295`) and strict
  per-trace id checks.
- [x] **Langfuse export is off in tests** except the two `LangfuseExportTestProfile` classes, asserted by
  `AgentMonitorConfigurerTests`.
- [x] **`MonitoredAgent` follows the task 11 decision** — commented out in all three places, restore condition recorded
  in each and in `CLAUDE.md`.
- [x] **Profile discipline intact:** 4 profiles / 8 boots; this task added no profile and no class-level test resource.
- [x] **Both observability workarounds carry accurate delete conditions** in their Javadoc (`OtelContextLeakGuard` →
  quarkus#54354/#56805/#57879; `FlowSpanTypingProcessor` → quarkus-flow#1122).
- [x] **`ConversationLayeringTests` enforces what the docs claim it enforces.**
- [x] **The `claim.intake.*` meter list in `CLAUDE.md` is exactly the 23 meters registered** — none documented but
  missing, none registered but undocumented.

## Documentation — fixed

- [x] **The guardrail inventory was wrong in three places.** The app has **nine** output guardrail classes (#216 added
  `IncidentDetailsOutputGuardrail` and `MaxLengthOutputGuardrail`) and **one tool input guardrail**
  (`IntakeCompleteToolInputGuardrail`). `README.md` said "seven … all output side — this app has no input guardrails",
  `CLAUDE.md` enumerated seven, and `docs/claim-chat-flow.puml` captioned the guardrail box "there are NO input
  guardrails" on the very tool-call edge the new guardrail guards. All three corrected; the chat diagram now shows
  `IntakeCompleteToolInputGuardrail` in the tool-call chain and is re-rendered.
- [x] **`docs/claim-intake-flow.puml` drew `SupersedeStep` before `AgentStep`.** The flow runs runAgents → policyCheck
  → supersede → applyOutcome, and the order is load-bearing (a policy-rejected reply must not cancel a review). The
  diagram is corrected and re-rendered.
- [x] **Three docs cited quarkus-flow#1056 as a live cause of multi-trace runs.** It was closed 2026-10-09 and fixed in
  Flow 1.2.0; the app pins 1.3.0. `README.md`, `docs/design/email-claim-intake.md` and
  `docs/email-claim-intake-demo.md` now give the real reason (Flow's persistence emits ~58 root traces per run, kept
  and unsampled). `PLAN.md`'s workaround list also named a non-existent "agentic conversation adapter (delete when
  #1056 is fixed)" as one of the two observability workarounds; it now names `FlowSpanTypingProcessor` and #1122.
- [x] **`src/main/webui/README.md` still described the Documents tab as an image gallery.** It renders the
  `Correspondence` transcript; the README now says so, lists `correspondence.test.ts`, and names the review endpoints
  the UI calls.
- [x] **`PLAN.md` said task 13 was unmerged and task 14 not started**, and still recorded the superseded
  "`MonitoredAgent`: the root extends it, in every profile" decision as current. Both corrected, the latter marked
  superseded with its restore condition rather than deleted.
- [x] **Seven P3 nits:** the `dev` profile row (it also sets `parasol.dev-seed.enabled` and `watcher.idle-refresh`),
  the tag-enum list (missing `FailureStage` and `FailureOutcome`), the conversation core's dependency list (it uses
  JBoss Logging; MicroProfile Context Propagation belongs to the `quarkus` sub-package), the #1111 wording ("re-enable
  once addressed" → closed for the unreleased 1.4.0, and the bar is "fixed means released"), two "being built" labels
  for a feature that is built, `cancelled` → `canceled` in `claim-intake-states.puml`, the seed-photo count, and
  `ConversationContext`'s `var ignored` → `var _`.

## Accepted or deferred (P3)

None of these changes behavior and none blocks the merge. Recorded so they are not rediscovered.

**Correctness-adjacent, worth a follow-up issue:**

- [ ] **An unstarted Flow instance is never discarded** (`ReviewRecoverer.handOver` when the take-over loses,
  `ClaimIntakeDispatcher.startRun` when `start()` throws). `Flow.instance(...)` registers the instance in Flow's
  in-memory `activeInstances` map at construction; the entry is removed only when a run finishes. It is **not** a Flow
  *row* leak — nothing is written to the database until `start()` — which is why the new three-table zero assertion
  can't see it. Neither id is ever persisted, so nothing looks either up and the functional impact is nil; it is a
  leaked map entry per occurrence, and each occurrence needs a lost race or an infrastructure failure.
  **Not fixable here:** `cancel()` is a no-op on a `PENDING` instance (`TaskExecutorHelper.isActive` covers only
  `RUNNING`/`WAITING`/`SUSPENDED`) and `WorkflowDefinition.removeInstance` is package-private, so adding a `cancel()`
  call would look like a fix and do nothing. Filed upstream as
  [open-workflow-specification/sdk-java#1816](https://github.com/open-workflow-specification/sdk-java/issues/1816);
  both sites carry a comment pointing at it (user decision, 2026-11-02: document and file, don't work around).

- [ ] **CIDQ-3 — `ClaimIntakeDispatcher.requeues` entries are never removed on the success path.** An email requeued
  1–6 times and then handled leaves its `Message-ID` → count entry for the life of the JVM. Bounded by distinct
  requeued emails per run of the app; a demo never notices, a long-lived process would.
- [ ] **CIDQ-4 — two Panache lookups can NPE instead of treating a vanished claim as unmatched.**
  `ClaimMatchingSteps.java:61-63` uses `Stream.ofNullable(...).map(...).findFirst()`, and `Stream.findFirst()` throws
  on a `null` element rather than yielding an empty `Optional`; `SupersedeStep.java:83,86` dereferences `findById`
  unguarded. Reachable only if a claim is deleted mid-run, which nothing in the app does. Both also conflict with
  "Optional over null"; `AgentStep.snapshot` already has the safe shape.
- [ ] **CIDQ-5 — two tag values use the default-locale `toLowerCase()`** (`IntakeMetrics.java:176`,
  `MailClientSpanInterceptor.java:69`), so a Turkish default locale would emit `needs_ınformatıon` and `ımap`. Every
  sibling call already passes `Locale.ROOT`.
- [ ] **CIDQ-10 — `MailLoopGuard` recognizes only `Auto-Submitted` and `Precedence`.** A bounce or a responder setting
  neither is answered. Acceptable for a demo; the remote side's own loop guard is the backstop.

**Style and maintainability:** CIDQ-6 (`ReviewDecisionSteps.snapshot` clears `reviewOpenedAt`, making the call order
invisibly load-bearing), CIDQ-7 (`PolicyCheckStep` uses `default ->` over a sealed `IntakeOutcome`, so a new variant
would silently skip the policy check where every sibling switch breaks the build), CIDQ-8 (inline fully-qualified
names), CIDQ-9 (`ReplySteps.ClaimView.asClaim()` fabricates a detached `Claim` just to read two strings), CIDQ-11
(`IntakeMetrics.gauges` is non-final with a dead initializer), CIDQ-12 (`IntStream.forEach` for side effects; an
`orElseGet` that can yield `null`), CIDQ-13 (two `public` methods that are only self-invoked).

**Test-suite observations:** NO-AGENT-MOCK-UNENFORCED (the no-agent-mock rule is prose, not a class-file test —
same remedy as R5), FLOWROWS-SCOPE (the new zero check runs after `cleanUp()` and is unscoped, so in principle a row
left by one of the seven same-boot classes that don't extend the base would be charged to the next base test; kept as
an absolute zero because it passes across the whole suite, per the task file's instruction to triage rather than weaken),
FLOWROWS-CLOUDEVENT (`cloud_event_entity` is structurally always 0 for the intake, so that third count is a guard
against a future change rather than a count expected to move — `FlowRows`'s Javadoc now says exactly that),
RACE-ONE-BRANCH (one side of the latched race is effectively dead, covered sequentially elsewhere), WINDOWED-NEGATIVES
and NAME-VS-ASSERT (three "not yet" assertions prove their point only within a 1–2 s window, and
`everyEndedOrCanceledRunLeavesNoFlowRows` never cancels anything), AWAIT-REPLIES-SETTLE (a 500 ms settle could miss a
late duplicate reply), AWAIT-UNTIL-MSG (one `until(==)` that should be `untilAsserted` for a better failure message),
WATCHER-BOOT-PATH (only the disabled branch of `ClaimsInboxWatcher.onStart` is tested).

**Refuted.** Dimension 2 raised FLOWROWS-UNRUN (P2) — that the new Flow-row assertion had never been observed passing,
because its own two attempts died on local port collisions. It has since run green across all 15 classes twice: a
targeted `org.parasol.intake.**` run (395 tests on the final tree) and the user's full default-profile verify with real
keys (637 tests, 0 failures).

**Out of scope by standing decision, not findings:** `images/arch.png` (a source-less raster), no durable state across
restarts, and the single-replica requirement ([#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284)).
