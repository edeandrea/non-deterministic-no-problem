# Task 14: Verify the Email Intake

**Type:** Verification

**Issue:** [#263](https://github.com/edeandrea/non-deterministic-no-problem/issues/263)

## Goal

Close out #216. Confirm the whole feature compiles and its suites pass with stub keys, get an independent review
across code, tests and docs, record what only the user can check, and open the final
`gh216-email-intake` → `main` pull request.

This is the last task. Tasks 01–13 are merged into the feature branch (task 13 as
[#285](https://github.com/edeandrea/non-deterministic-no-problem/pull/285)); nothing else is outstanding.

**quarkus-flow 1.3.0 (2026-10-26):** adopted in task 09b, ahead of the platform, superseding the 1.2.0 override (task
06b) and fixing [#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096) upstream
([#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095)) — task 09b deleted the app's own workaround for it.
Here: re-check #1056, #1057 and #1058 against the Flow version the app then pins (the reproducers, with the version
set in their `pom.xml`), and whether a platform now pins Flow ≥ 1.3.0 so the override can go (`PLAN.md` → Execution
Steps → 2a).

## Decisions

Taken with the user while planning this task (2026-11-02):

1. **The local full run is `-Pollama`**, the same leg CI runs and the one tasks 12b, 12c and 13 used successfully.
   The earlier Key Point claiming a plain `-Pollama` full run "fails to boot with an ambiguous `ChatModel` provider"
   is **stale and deleted** — task 12c recorded 617 tests, 0 failures on it. `-Pollama-openai` stays with CI.
2. **A test is written only where coverage is genuinely absent.** Where an equivalent already exists, this task
   records *"already covered by X"* rather than adding a duplicate. No new `@TestProfile` unless unavoidable: each
   one is another full application boot.
3. **`main` is merged into `gh216-email-intake` before the verify run**, so the tree that is verified and reviewed is
   the tree that merges.

## What to Do

### Phase 0 — Standing checks and branch prep

1. Run the standing per-task upstream checks (`PLAN.md` → Execution Steps **2a**, **2b**, **2c.1–2c.6**) and add a
   dated entry at the top of the findings log. Act only on what has **released**, not merely merged — the step's own
   bar. At the last check (2026-11-02) all six workarounds stay; 2c.6's
   [langchain4j#6914](https://github.com/langchain4j/langchain4j/pull/6914) merged that day but is in no release.
2. `git fetch`, then **merge `origin/main` into `gh216-email-intake` and push**. No PR is needed — the `main` ruleset
   doesn't cover that direction. Two commits come across:
   - the quarkus-langfuse 0.9.1 bump ([#283](https://github.com/edeandrea/non-deterministic-no-problem/pull/283)) —
     the branch is already on 0.9.1, so expect a no-op;
   - [#274](https://github.com/edeandrea/non-deterministic-no-problem/pull/274) "Quiet the Jest suite", which the
     branch already carries as the #275 port. **Expect a conflict** in `src/main/webui/jest.setup.ts`,
     `jest.config.js` or `package.json`. Confirm the two are functionally equivalent, then resolve to the branch's
     port.
3. Branch `gh216/14-verification` off the updated feature branch for this task's own commits.

### Phase 1 — Compile and the full suite

1. `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
2. `NODE_ENV=development OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`.
   The baseline to beat is task 12c's: **617 tests, 0 failures, 2 skipped** (`DriftDetectionTests`,
   `ClaimsMailboxConcurrencyTests`), Jest 79 passed. Record the exact numbers in the Outcome, and report any
   pre-existing secret-dependent failure separately.
3. `-Pollama-openai` is left to CI, which was green on both legs for PR #285 (~36 minutes each).

### Phase 2 — Close only the genuinely missing tests

A code audit during planning (2026-11-02) settled which of the task's four required checks are real gaps. The
verdicts are recorded here so this doesn't get re-derived:

| Required check | Verdict | Action |
|---|---|---|
| **Sub-agent `@InjectMock` under its own profile** (a sub-agent instance may be bound when the root is built — spike Q2) | **Vacuously satisfied.** No test mocks or spies any of the ten intake sub-agents; every one runs as a real bean against WireMock. Every existing `@InjectSpy` in the intake suite targets a step, sender or handler bean, never an agent. | No test. Add one line to `StubbedModelsTestProfile`'s Javadoc (and CLAUDE.md → Testing) so a future sub-agent mock knows it needs its own profile. |
| **Full agent path on every build** | **Already covered.** The 15 classes extending `IntakeFlowTestBase` drive `IntakeScenario.submit` → the real `ClaimIntakeDispatcher` → the real `ClaimIntakeFlow` → the real `ClaimsMailboxAgent`, with WireMock as the LLM; `awaitRunSpans` pins the generated workflow names (`claims-mailbox-agent`, `email-router`, `claim-extraction-workflow`). `RoundcubeClaimIntakeE2ETests` adds the browser-to-mailbox path. No test mocks the root agent anywhere. | Record as covered. Write nothing. |
| **No leaked Flow rows** | **Genuine gap.** The assertions are per-run or per-class only. `IntakeScenario.instanceRows()` filters to `workflow_name = ClaimIntakeFlow.NAME`, so a leaked **generated** agentic run is invisible to every zero check; `cloud_event_entity` is never counted at all; the only `@AfterEach`-level check is in `RoundcubeClaimIntakeE2ETests`. | Add `allInstanceRows()` and `cloudEventRows()` to `IntakeScenario`, and assert all three Flow tables are empty at the end of `IntakeFlowTestBase.cleanUpIntake()` (`@AfterEach`). That covers all 15 flow classes with **no new profile**. |
| **The conversation id is a UUID with no PII** | **Gap the earlier draft didn't name.** Production generates it in `ClaimMatcher` (`UUID.randomUUID()` when none is stored); no test asserts the format. Existing tests only assert `isNotBlank()` or reuse of a literal. | Add one assertion to `ClaimMatcherTests` (already the conversation-id test class, no new profile): a new id matches the UUID regex and contains neither the sender address, the claim number nor the `Message-ID`. |

**Caveat on the Flow-row assertion.** `FailedAgenticSystemTests` and `IntakeSpanPropagationTests` assert
`agenticRunIds()` `isSubsetOf(runsBefore)` rather than zero, which hints at a non-zero baseline of generated rows.
If the new absolute-zero assertion fails, **triage the leak before weakening the assertion**; fall back to "no growth
across the test" only with the baseline explained in the Outcome.

**No restart test:** durable state across restarts isn't a requirement (user decision, 2026-10-07).

### Phase 3 — Fresh independent review

Follow the task 11 precedent (`task-11-observability-review.md`): a reviewer with no part in building this writes
`.tasks/05-gh216-agentic-email-intake/task-14-review.md` with severity-rated findings (P0–P3) and checkboxes.
**Re-review after fixes.** The scope is the whole `gh216-email-intake` → `main` diff, not just this task's changes.

**Settled in planning — confirm, don't re-derive.** Each of these was checked against the code on 2026-11-02:

- no agent has side effects, and `ClaimStatusTools` touches no database at all (its one `@Tool` reads `MatchedClaim`
  out of the agentic scope);
- all six intake `@RegisterAiService` interfaces carry **both** statelessness opt-outs, with `@ModelName` on the
  `@Agent` method matching `@RegisterAiService(modelName = …)`; `ClaimResolver` correctly carries no `@ModelName`,
  having no `@Agent` method;
- no transaction is open across an agent, IMAP or SMTP call anywhere in `workflow/**` or `dispatch/**`; every
  `@Transactional` there is a narrow `REQUIRES_NEW` database-only helper;
- no step contains observability code — every one publishes through `IntakeActivities`;
- the `policyInconsistency` and `noMatchingClaim` templates disclose nothing, and the no-claim-number
  `sendTemplateReply` overload keeps `[CLM…]` out of the subject (the other half of that guarantee);
- every `claim.intake.*` metric tag value comes from an enum or a small fixed set — see R3 for the one exception;
- `ConversationLayeringTests` and
  `IntakeMetricsTests.meterTagsHaveOnlyTheirDeclaredKeysAndBoundedValuesAndNoIdentifiers` exist and do what the
  checklist asks of them.

**Open findings the reviewer must settle**, all surfaced while planning this task:

- **R1 — a committed decision whose email fails leaves the customer with nothing.**
  `ReviewDecisionSteps.sendAfterCommit` swallows the send failure (an ERROR log plus an `IntakeFailed(…, RECOVERED)`
  counter), and `restoreReview` returning `END` has the same shape. It is a deliberate trade — a retry would
  double-send — but `PLAN.md`'s *"Every submission gets a reply"* invariant doesn't record the exception. Decide:
  fix it, or write the exception into the invariant.
- **R2 — `supersede` → requeue has no flow-level reply guarantee.** It relies on the dispatcher re-dispatching up to
  six times and then falling to `processingProblem`. Nothing in the flow guarantees it. Acceptable, or does the
  flow need its own terminal reply?
- **R3 — the `exception` metric tag is `getClass().getSimpleName()`**, not a fixed set, while `IntakeMetrics`'s
  Javadoc claims fixed sets. Bounded by the classpath and never PII, but reword the Javadoc or allow-list the value.
- **R4 — "the starter's `ConversationContext.callIn` is the intake's only one" is false as written.** `runIn` is
  called in `ClaimIntakeDispatcher` **and** `ReviewRecoverer`, and `callIn` has no `src/main` caller at all. Reword
  to "one entry per run start", here and anywhere else the claim appears.
- **R5 — every `@Transactional` step helper relies on ArC self-invocation.** Making one `private` or `final`
  silently drops the transaction with no compile error. Only `IntakeMetrics` comments say so; the step classes
  (`AgentStep`, `PolicyCheckStep`, `ReviewDecisionSteps`, `SupersedeStep`, `ApplyOutcomeStep`, `ReplySteps`,
  `ClaimMatchingSteps`, `CorrespondenceRecorder`, `ReviewRecoverer`) don't. Add the note.
- **R6 — `IntakeScenario.instanceRows()` name-filters**, so "no leaked Flow rows" read stronger than it was.
  Phase 2 fixes this; the reviewer confirms the fix.

**Still unverified — the reviewer's own work:**

- each rule in task 08 has a test;
- every entry agent is injected in `src/main`, and every leaf belongs to exactly one root;
- the matched claim wins; one "no matching claim" template; reviewer-checked items gate completeness;
- the policy check runs after the agents and creates nothing on rejection;
- a reply during review cancels the waiting run, and no Flow rows leak (in the app or in tests);
- a reply during review and a decision are serialized by optimistic locking (race test);
- auto-reply loop protection;
- every intake span carries the claim's `gen_ai.conversation.id`, with no mixing under concurrency; any remaining
  gap is the one documented in task 11;
- Langfuse span export is off in tests, except `LangfuseSessionScoringServiceTests` and `IntakeLangfuseDeliveryTests`;
- `MonitoredAgent` follows the decision recorded in task 11;
- both workarounds (the quarkus#54354 guard, the agentic adapter) carry their delete conditions;
- the quarkus-flow decisions (task 01b and the follow-up spikes) are recorded in `PLAN.md`, with upstream status
  re-checked for the pinned Flow version (#1056, #1057, #1058) and Quarkus (quarkus#54354);
- docs match the code, and the design doc's status line is current.

### Phase 4 — `PLAN.md` close-out

Mark task 14 complete with an Outcome, and add a **"Remaining manual checks for the user"** section listing what
only the user can run:

- `./mvnw -B clean verify` with real OpenAI **and** Cohere keys — the chat suites and
  `LangfuseSessionScoringServiceTests` are the only proof that task 10b changed nothing for the chat;
- `./mvnw verify -Dquarkus.test.profile=drift` with real keys (`DriftDetectionTests`; CI can never run it);
- the dev-mode demo walk-through from `docs/email-claim-intake-demo.md`;
- the cluster deployment check, single replica;
- optionally `ClaimsMailboxConcurrencyTests`, which is opt-in behind its own system property.

Restate, don't re-litigate, the known accepted gaps: `images/arch.png` (a source-less raster), no durable state
across restarts, and the single-replica requirement with its follow-up
[#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284).

### Phase 5 — The final pull request

- `gh216-email-intake` → `main` (see `PLAN.md` → Task Plan → *Branches and pull requests*). The title carries **no**
  closing keyword; the body links every task PR (#233, #234, #235, #236, #240, #245, #264, #267, #268, #270, #273,
  #275, #276, #278, #279, #280, #282, #285) and closes #216 together with its sub-issues.
- **Merged with a merge commit** (user decision) so each reviewed task stays its own commit on `main`. The `main`
  ruleset allows only squash, so the user flips the ruleset — and the repository setting — for this one PR and back
  afterwards. **Leave the merge to the user.**
- After the merge: remove `gh216-email-intake` from the `pull_request` trigger in
  `.github/workflows/simple-build-test.yml`, and delete the branch.

## Files/Areas

- Whole repository (verification and review).
- Written by this task: `.tasks/05-gh216-agentic-email-intake/task-14-review.md` (new), `PLAN.md`, this file,
  plus the Phase 2 test changes in `src/test/java/org/parasol/intake/IntakeScenario.java`,
  `IntakeFlowTestBase.java`, `dispatch/ClaimMatcherTests.java` and `org/parasol/testing/StubbedModelsTestProfile.java`.

## Key Points

- **`NODE_ENV=production` in the shell breaks Quinoa's test step** (Jest is missing); use `development`.
- **The feature branch has never been CI-tested as a whole.** CI runs on PRs *into* it, never on pushes to it, so
  the final PR is the first full-branch CI run — which is why `main` is merged in before the local verify
  (Decision 3), not after.
- CI's `paths-ignore: '**/*.md'` means a docs-only PR gets no run. The final PR carries code, so both legs run.
- Locally, skip `-Pollama-openai` (CI covers it). The E2E must still be CI-safe under both profiles.
- The suites that need a real OpenAI key (the chat and `LangfuseSessionScoringServiceTests`) are the user's to run.

## Done When

- [x] `main` is merged into `gh216-email-intake` and pushed (`e399684`). The Jest conflict never materialized (the two
      are byte-identical); the one real conflict was in `src/main/webui/README.md`, resolved to the branch's port.
- [x] Both `test-compile` runs succeed. The authoritative run is the user's **default profile with real keys: BUILD
      SUCCESS, 637 tests, 0 failures, 0 errors, 2 skipped** (Jest 82); `-Pollama` was BUILD SUCCESS at 636/0/0/2 before
      the D-plus change, with the intake suite re-run green at 395/0 after it. No secret-dependent failure.
- [x] Both assertions exist and pass across the whole suite; the two already-covered checks are recorded as covered,
      with the review's independent confirmation as evidence.
- [x] `task-14-review.md` exists, has no P0/P1 finding, and records a resolution for each of R1–R6.
- [x] `PLAN.md` marks task 14 complete and carries the *Remaining manual checks for the user* section.
- [ ] The final PR is open, links every task PR, and is left for the user to merge. **Not done:** the user asked to
      stop before committing, so the branch is uncommitted and the PR body is drafted but unopened.

## Outcome

**Done, 2026-11-02**, on `gh216/14-verification` off `gh216-email-intake` @ `e399684`. No P0/P1 finding; two P2
defects found by the review, both fixed here with regression tests.

### Phase 0 — standing checks and branch prep

- **Upstream: nothing new has shipped, every workaround stays.** The full entry is in `PLAN.md`'s findings log.
  Briefly: the platform's `quarkus-flow-bom` still pins Flow 1.1.3 (so the 1.3.0 override stays, and since the pinned
  Flow version didn't change the reproducers needed no re-run); quarkus-flow#1111 is closed for the unreleased 1.4.0;
  quarkus#57879 is open; quarkus-langchain4j#2951 is merged but unreleased (1.14.2 is still the latest stable);
  quarkus-flow#1122 is open; and **langchain4j#6914 merged 2026-11-02 07:32Z** but is in no release — 1.23.0 predates
  it and that day's 1.19.5 / 1.11.12 / 1.5.4 are maintenance-branch releases — so `MonitoredAgent` stays commented out.
- **`main` merged into `gh216-email-intake` and pushed** as `e399684`. The expected Jest conflict did **not** happen:
  `jest.setup.ts`, `jest.config.js` and `package.json` are byte-identical between the branch's #275 port and main's
  #274, so git merged them cleanly. The one real conflict was a sentence in `src/main/webui/README.md`, resolved to
  the branch's wording (which names `UiTestProfile` rather than Quinoa's `EnableAndRunTests` profile).

### Phase 1 — compile and the full suite

- `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`: both pass.
- **The default profile with real OpenAI and Cohere keys** (`NODE_ENV=development ./mvnw -B clean verify`, run by the
  user on the final tree, 2026-11-02 17:08): **BUILD SUCCESS — 637 tests, 0 failures, 0 errors, 2 skipped**, Jest **82
  passed**. The two skips are the expected `DriftDetectionTests` and `ClaimsMailboxConcurrencyTests`. This is the
  authoritative run: it is the only leg that exercises the real models, and it covers everything in this task except
  the final `ReviewRecovery` → `ReviewHandover` rename, which is compile-verified and referenced by no test. The
  suites that exist to prove this leg works all passed — `ClaimWebsocketChatBotTests`, `LangfuseSessionScoringServiceTests`
  against a real Langfuse (91 s), and `NotificationServiceTests` at 10/10 with nothing skipped, so
  `emailSendsWhenUserExists` (the one test that drives the real `GenerateEmailService` through all four output
  guardrails) passed. **That also closes manual check 1** in `PLAN.md`'s *Remaining manual checks for the user*.
- `NODE_ENV=development OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`: **BUILD SUCCESS — 636 tests, 0
  failures, 0 errors, 2 skipped**, Jest **82 passed** (5 suites). All five Playwright classes ran. Against task 12c's
  baseline of 617/0/2 that is +19 tests: this task's new ones plus the suite growth already on the branch. This run
  predates the D-plus change; the intake suite alone was re-run green on the final tree (`org.parasol.intake.**`, 395
  tests, 0 failures), and the default-profile run above covers the rest. A full `-Pollama` run on the final tree was
  not repeated — CI runs that leg on the pull request.
- **No secret-dependent failure, and two local-environment artifacts that are not code problems.** One run of the
  `-Pollama` verify died in `ClaimImagesPageTests` with `Failed to create driver … posix_spawn failed, error: 0 (none)`
  — Playwright failing to install its driver, a local JVM/temp-dir problem. It did not recur, and the UI classes pass
  both in isolation (23 tests) and in a full run. A later `-Pollama` run reported `BUILD FAILURE` with *"104 critical
  issues during test discovery … Could not load class"*: that was two Maven builds sharing one `target/` directory,
  one `clean`ing while the other was discovering tests. Neither is a suite defect; don't chase either from the logs.
- `-Pollama-openai` left to CI, as planned.

### Phase 2 — the genuinely missing tests

| Required check | What was done |
|---|---|
| Sub-agent `@InjectMock` under its own profile | **Vacuously satisfied, no test written.** Nothing mocks an agent. The constraint, and why it needs its own profile, is now in `StubbedModelsTestProfile`'s Javadoc and `CLAUDE.md` → Testing. |
| Full agent path on every build | **Already covered, nothing written.** Confirmed independently by the review: zero mocks or spies on any agent interface; the only spies are step/sender/handler beans. |
| No leaked Flow rows | **Gap closed.** A new test-only `FlowRows` bean owns all eight queries against Flow's three tables — and is now the only place an `EntityManager` is wired into the intake's test helpers, `IntakeScenario` having been reduced to delegation (user request). `IntakeFlowTestBase.cleanUpIntake()` asserts all three tables are empty after every test, covering all 15 flow classes with no new profile. **The absolute-zero assertion passes**, so it was kept rather than weakened to a delta. |
| The conversation id is a UUID with no PII | **Gap closed**, folded into `ClaimMatcherTests.aSeededClaimWithNoStoredConversationGetsANewRandomUuid`. The reviewer pointed out the first draft's `doesNotContain(...)` could not fail once `matches(UUID)` had passed, so only the load-bearing assertion remains, with a comment on why the shape settles it. |

### Phase 3 — the review

`task-14-review.md`, written from three independent reviewers working disjoint dimensions (production code, tests,
docs-vs-code), none of which built the feature. **No P0 or P1.** R1–R6 each have a recorded resolution.

Fixed here, with regression tests:
- **`ClaimIntakeDispatcher.startRun` released the wrong queue holder** when starting a run threw, wedging that
  sender's queue forever with no reply and no failure counter. The new `ClaimIntakeDispatcherStartFailureTests` was
  verified to fail without the fix.
- **A faulted run whose email was already filed still told the customer their email couldn't be processed**, even
  after `ReviewRecoverer` had handed the review to a new run. Fixed by the rule that **where the email is decides what
  its sender hears**: the flow files an email only after replying about it, so one that has left `Processing` has been
  answered and a faulted run then records the failure and nothing else. That covers every post-filing fault, not just
  this one, and removes the double `EmailHandled` count. The recovery outcome is kept purely to tag the metric
  `recovered` vs `fatal` (a `ReviewHandover` enum, not a boolean, per the repo's own rule). Two existing tests had
  asserted the buggy behavior and now assert the correct one.

Also fixed: the R1 invariant exception is written into the design doc, `PLAN.md` and `ReviewDecisionSteps`; the R3
tag-cardinality Javadocs are corrected (three tags were wrong, not one); the R4 `callIn`/`runIn` claim is reworded
(and the R1/CIDQ-2 fix incidentally gave `callIn` its first production caller); the R5 self-invocation note is on all
sixteen self-invoked `@Transactional` methods. Nine P2 documentation-drift items and seven nits are fixed, including
a wrong step order in `claim-intake-flow.puml` and a now-false "no input guardrails" caption — both diagrams
re-rendered and checked.

Deferred, recorded in the review rather than fixed: four correctness-adjacent P3s (an unbounded `requeues` map, two
NPE-on-vanished-claim lookups, two default-locale `toLowerCase()` calls, a narrow `MailLoopGuard`), a set of style
P3s, and the suggestion — worth doing with [#232](https://github.com/edeandrea/non-deterministic-no-problem/issues/232)
— to replace both the R5 comments and the no-agent-mock prose with a class-file test.

### Phase 4 — `PLAN.md`

Task 14 marked complete, and a new **Remaining manual checks for the user** section lists the five things only the
user can run, with the three accepted gaps restated.

### Phase 5 — the final pull request

Prepared and **left for the user to open and merge**, at the user's request to stop before committing.
