# Task 12b: Consolidate Incident Description Into the Claim Summary

**Type:** Code Modification

**Issue:** [#266](https://github.com/edeandrea/non-deterministic-no-problem/issues/266)

## Goal

Two separate AI agents independently produce overlapping text about a claim, and only one of them is ever
persisted:

- `IncidentDetailsAgent` extracts its own `description` (`IncidentDetails.description`) — used *only* for the
  completeness check (`ClaimExtractionRules.isAbsent(INCIDENT_DESCRIPTION, ...)` checks `details.description()` for
  blankness) and then **discarded**. Nothing ever persists it.
- `ClaimSummaryAgent` separately extracts `summary` (`ClaimExtraction.summary()`), and that's what actually lands in
  `claim.summary` (`ClaimFields.merge`).
- `ClaimFields.extractedSoFar()` already treats them as interchangeable when rebuilding context for a re-extraction
  pass — it feeds `claim.summary` back in as `IncidentDetails.description`, with the comment *"A claim stores no
  description, so its summary stands in for it."*

Found while designing task 10's reworked review UI: the mock-up's "Description" field reads/writes `claim.summary`
(there's no `description` column on `Claim` at all), which is what surfaced that two agents are independently
guessing at the same thing today, with one answer thrown away. **Required before task 13** (user decision, 2026-10-30;
it was task 16, "for the future", until then): it changes the extraction contract and every intake test fixture, which
task 13 documents and task 14 verifies. It runs before task 12c, so 12c's new tests are written against the final fixtures.

## What to Do

*Revised 2026-10-30 by the user decisions below: the issue's "check `summary()` for blankness" is replaced by a flag.*

- Replace `IncidentDetails.description` (text) with `Boolean incidentDescribed`: the details agent judges **whether**
  the correspondence says what happened; `ClaimSummaryAgent` stays the only agent that writes **what** happened.
- `ClaimExtractionRules.isAbsent`'s `INCIDENT_DESCRIPTION` case checks the flag (anything but `TRUE` is missing).
- `IncidentDetailsAgent`'s prompt asks for the flag instead of a description.
- `ClaimFields.extractedSoFar` stops shuttling `claim.summary` into `IncidentDetails`; it derives the flag from the
  claim's requested items instead.
- Every test's mocked LLM JSON across the intake suite swaps the `description` key for `incidentDescribed`
  (`COMPLETE_DETAILS` and the `details(...)` helper in `IntakeFlowTestBase`, and anything built on them, including the
  Playwright `ClaimReviewPanelPageTests` and `RoundcubeClaimIntakeE2ETests` (task 12), which carry their own copies).
- Update the design doc and `CLAUDE.md` wherever they describe `description` as an extracted field.
- `MissingItem.INCIDENT_DESCRIPTION` and its label stay as they are (decision 2).

## User Decisions (2026-10-30)

1. **The completeness signal is a flag on the details agent, not a blank summary.** `ClaimSummaryAgent` returns free
   text and is never blank with a real model: even for "Something happened, help" it writes something like "The
   customer reports an incident but gives no details." A blank-summary check would therefore never flag
   `INCIDENT_DESCRIPTION` outside mocked tests. Rejected alternatives: a sentinel answer from the summary agent (`NONE`;
   relies on the model hitting an exact token, and the sentinel must never reach `claim.summary`), and the literal
   blank check (makes the item reviewer-only in practice). The flag is JSON-safe, parsed by the existing
   `JsonExtractorOutputGuardrail`, and nothing extracted is thrown away any more.
2. **`MissingItem.INCIDENT_DESCRIPTION` and the label "incident description" stay.** The customer-facing
   missing-information email still asks for an incident description, and the review UI, REST payloads
   (`ReviewDecisionRequest.description`, `ReviewedFields.description` → `claim.summary`) and templates don't change.

## Implementation Plan

### Before starting

- Run PLAN.md steps 2a–2c (upstream checks) and log them in PLAN.md's findings log.
- Branch `gh216/12b-description-into-summary` off `gh216-email-intake` (which now carries task 12, `59e3b28`, #278).
- Read `CODE_STANDARDS.md` (symlinked outside the repo; the planning agent couldn't read it) and `CLAUDE.md`.

### Design: `incidentDescribed` is a nullable `Boolean`

- **Nullable, not primitive**, matching the other fields' "null = unstated": `IncidentDetails.empty()` (a new claim)
  must render as `unknown` in the prompt's "Details extracted from earlier emails" block. A primitive `false` there,
  next to "keep them unless the correspondence corrects them", would invite the model to keep `false`.
- **Absent = anything but `TRUE`** (`null` or `false`), so a model that omits the key asks the customer rather than
  silently passing the claim.
- **Its meaning, stated in the prompt:** `true` when the correspondence says what happened in the incident itself (e.g.
  "my car hit a post"), `false` when it only says that something happened or that the customer wants to claim. If we
  asked for `INCIDENT_DESCRIPTION` and the newest reply explains what happened, it's `true` and the item goes in
  `answeredItems`, the same as every other requested item.

### 1. Model (`org.parasol.intake.model`)

- **`IncidentDetails`:** replace `String description` with `Boolean incidentDescribed` (first component, so call sites
  change in place); update the Javadoc `@param`. `empty()` stays all-`null`.
- `ClaimExtraction` doesn't change (`summary` is still required and is still what's persisted).

### 2. Extraction (`org.parasol.intake.agent.extraction`)

- **`IncidentDetailsAgent`'s user message:**
  - Earlier-details block: `- incidentDescribed: {extractedSoFar.incidentDescribed ?: 'unknown'}` instead of the
    description line. Qute's `?:` only replaces `null`/unresolved, so `false` renders as `false`; a test pins it
    (section 5).
  - Return-fields list: `- incidentDescribed: true if the correspondence says what happened in the incident, false if
    it only says that something happened or that a claim is being made` instead of `- description: what happened`.
  - System message and the rest of the prompt stay as they are.
- **`ClaimExtractionRules`:**
  - `isAbsent`: `case INCIDENT_DESCRIPTION -> !Boolean.TRUE.equals(details.incidentDescribed())`. The signature doesn't
    change: `details` is enough (the issue expected it to need the whole `ClaimExtraction`; with the flag it doesn't).
  - `findMissingItems`' Javadoc: the core-field bullet says the incident description counts as present only when the
    details agent says the correspondence describes what happened.
- **`IncidentDetailsOutputGuardrail.clearInvalidFields`:** copies `incidentDescribed()` instead of `description()`.
- `ClaimSummaryAgent`, `ClaimExtractionWorkflow` (its `@Output` and `@ErrorHandler`) and `InvalidIncidentDetailsException`
  don't change beyond what compiles through.

### 3. Re-extraction context (`org.parasol.intake.workflow`)

- **`ClaimFields.extractedSoFar(Claim claim, Set<MissingItem> requestedItems)`:** the claim's summary always exists once
  a claim does (the summary agent never answers blank), so it can't tell whether the description was missing. The
  claim's requested items can: we asked for `INCIDENT_DESCRIPTION` exactly when it was missing (or a reviewer wanted
  more of it).
  - `FALSE` when `requestedItems` contains `INCIDENT_DESCRIPTION`;
  - otherwise `TRUE` when `claim.summary` isn't blank (it was judged present, or a reviewer wrote one), else `null`.
  - The class and method Javadoc drop "its summary stands in for it".
- **`AgentStep.snapshot`:** computes the requested items once and passes them to both `extractedSoFar` and `History`.
- **`ClaimFields.merge`** doesn't change: `claim.summary` still comes from `extraction.summary()`.

### 4. Persisted runs

A run waiting in `Pending Review` keeps its `IntakeState` (with the outcome's `ClaimExtraction` → `IncidentDetails`)
in Flow's PostgreSQL tables. Add an `IntakeJsonRoundTripTests` case that reads an old-shape `IncidentDetails` (with
`"description"` and no `"incidentDescribed"`) and gets `incidentDescribed == null`. If Flow's mapper fails on the
unknown property, add `@JsonIgnoreProperties(ignoreUnknown = true)` to `IncidentDetails` (and say so in the Outcome).
Same reasoning as task 11b's nullable `IntakeRun.receivedAt`.

### 5. Tests

- **Shared fixtures:**
  - `IntakeFlowTestBase.details(...)`: the first parameter becomes `Boolean described`, rendered as
    `"incidentDescribed":true|false|null`. `COMPLETE_DETAILS` passes `true`. Every caller that passed text passes
    `true`; every caller that passed `null` passes `null` (or `false` where the test is about a reply that doesn't
    answer).
  - `RoundcubeClaimIntakeE2ETests.details(...)` and `ClaimReviewPanelPageTests`' JSON: `"incidentDescribed":true`.
  - Inline JSON in `ClaimExtractionWorkflowTests`, `IncidentDetailsOutputGuardrailTests`, `ClaimsMailboxAgentTests`;
    hand-built `IncidentDetails` in `EmailRouterTests`, `IntakeOutcomeRoundTripTests`, `IntakeJsonRoundTripTests`,
    `ClaimExtractionValueRoundTripTests`, `ClaimExtractionWorkflowOutputTests`, `FailedAgenticSystemTests`.
- **`ClaimExtractionRulesTests`:** `withDescription(String)` becomes `withDescribed(Boolean)`. `null` and `false` are
  both `INCIDENT_DESCRIPTION`; `true` isn't. The reviewer-requested cases don't change.
- **`ClaimFieldsTests`** (new, plain unit test): `extractedSoFar` gives `FALSE` when `INCIDENT_DESCRIPTION` was
  requested (even with a summary), `TRUE` with a summary and no such request, and `null` with neither.
- **`ClaimExtractionWorkflowTests`** (asserts the rendered prompt): a new claim renders `incidentDescribed: unknown`,
  a pending claim that was asked for the description renders `incidentDescribed: false` (pins Qute's `?:` on a
  `Boolean`), and the return-fields list no longer mentions `description`.
- **`IncidentDetailsOutputGuardrailTests`:** a missing `incidentDescribed` key parses as `null`; the date-fallback
  case keeps the flag.
- **`ClaimIntakeNewClaimTests`:** `detailsWithout(INCIDENT_DESCRIPTION)` uses `false`;
  `everyMissingItemIsAskedFor...` still expects all four items in the reply.
- **`ClaimIntakePendingClaimTests`:** the existing reply that answers `INCIDENT_DESCRIPTION` passes `true`; add one
  where the reply doesn't explain what happened (`false`, not answered), so the item stays requested and is asked for
  again.

### 6. Documentation

- `docs/design/email-claim-intake.md` (step 4's required details, line ~47, and the extraction bullet, line ~115) and
  `docs/design/claim-intake-agents.puml` (the `IncidentDetailsAgent` box: `description` → `incidentDescribed`); then
  re-render the PNG with `docs/render-diagrams.sh`.
- `CLAUDE.md`: wherever the intake's extraction or `ClaimExtractionRules` is described, say that the summary is the
  only incident text and the details agent only flags whether one was given.
- `PLAN.md`: mark task 12 done (#278, `59e3b28`) if it isn't already, then task 12b on completion, with a 1–2 sentence
  outcome under Shared Context.
- GitHub issue #266's body still says "check `extraction.summary()` for blankness": update it to match decision 1.

### 7. Verification

- `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- The `org.parasol.intake.**` suite plus `ClaimReviewPanelPageTests` and `RoundcubeClaimIntakeE2ETests` under
  `-Pollama` and `-Pollama-openai`, then a full `clean verify`; record the totals in the Outcome.
- `grep` that no `description()` call on `IncidentDetails` and no `"description":` key in intake fixtures remains.
- **Real-model check** (the stubbed suite can't test the prompt's meaning): in dev mode, send "Something happened,
  please help" (expects a missing-information email listing the incident description) and "My car hit a lamp post on
  Elm Street yesterday" (expects it not listed).

### Out of scope (noted, not changed)

- `ClaimFields.merge` lets a re-extraction's summary overwrite a summary the reviewer edited in the review UI (after
  Needs More Information, the customer's reply triggers a re-extraction and `extraction.summary()` wins). That's
  current behavior, unchanged by this task; raise it in the review findings.

## Files/Areas

- `src/main/java/org/parasol/intake/model/IncidentDetails.java`
- `src/main/java/org/parasol/intake/agent/extraction/` (`IncidentDetailsAgent`, `ClaimExtractionRules`,
  `IncidentDetailsOutputGuardrail`)
- `src/main/java/org/parasol/intake/workflow/outcome/ClaimFields.java`, `src/main/java/org/parasol/intake/workflow/AgentStep.java`
- `src/test/java/org/parasol/intake/IntakeFlowTestBase.java` (`COMPLETE_DETAILS`, `details(...)`) and every test built
  on them across `org.parasol.intake.**`, plus the hand-built fixtures listed in section 5
- `src/test/java/org/parasol/intake/workflow/outcome/ClaimFieldsTests.java` (new)
- `src/test/java/org/parasol/ui/ClaimReviewPanelPageTests.java`, `src/test/java/org/parasol/ui/RoundcubeClaimIntakeE2ETests.java`
- `docs/design/email-claim-intake.md`, `docs/design/claim-intake-agents.puml` (+ PNG), `CLAUDE.md`, `PLAN.md`

## Key Points

- `IncidentDetails.description` has no other consumer today besides the one completeness-check branch and the
  guardrail's fallback copy — confirmed by reading `ClaimExtractionRules.isAbsent`, `ClaimFields` and
  `IncidentDetailsOutputGuardrail` directly, not assumed.
- This doesn't change anything about task 10's UI design: the "Description" field there already reads/writes
  `claim.summary` either way (there is, and after this task still is, no `description` column on `Claim`).
- The flag judges presence only; the text is the summary's job. Don't let the flag leak into `Claim` or the review API.

## Done When

- [x] `IncidentDetails.description` is replaced by `Boolean incidentDescribed`; `IncidentDetailsAgent` asks for the flag
- [x] `ClaimExtractionRules` treats anything but `incidentDescribed == TRUE` as `INCIDENT_DESCRIPTION` missing
- [x] `ClaimFields.extractedSoFar` derives the flag from the requested items and no longer copies the summary
- [x] An old-shape `IncidentDetails` (with `description`) still reads from persisted run state
- [ ] Every affected test fixture across `org.parasol.intake.**` and the two Playwright tests is updated and the suite
  passes under `-Pollama` and `-Pollama-openai`
- [ ] The real-model dev-mode check behaves as described in section 7
- [ ] The design doc (and diagram), `CLAUDE.md`, `PLAN.md` and issue #266's body are updated

## Outcome

Built on `gh216/12b-description-into-summary` as planned (decisions 1 and 2 above); no deviation from the plan.

- **Built:** `IncidentDetails.description` is now `Boolean incidentDescribed` (nullable, so a new claim renders `unknown`).
  `ClaimExtractionRules.isAbsent` treats anything but `TRUE` as `INCIDENT_DESCRIPTION` missing. `IncidentDetailsAgent`
  asks for the flag. `ClaimFields.extractedSoFar(claim, requestedItems)` derives it (`FALSE` if the description was
  requested, else `TRUE` with a summary, else `null`) and no longer copies the summary; `AgentStep` computes the
  requested items once for both it and `History`. Fixtures across the intake suite and the two Playwright classes use
  the new key. Design doc, diagram (PNG re-rendered) and `CLAUDE.md` updated.
- **Old persisted shape:** `IntakeJsonRoundTripTests.anIncidentDetailsPersistedBeforeTheFlagStillReads` reads an
  old-shape `IncidentDetails` through the injected Quarkus `ObjectMapper` with no `@JsonIgnoreProperties`. That's not
  necessarily the mapper Flow uses to read its persisted state, so this is only partly proven; a waiting run from before
  this change is the case it would matter for.
- **Out of scope, noted:** `ClaimFields.merge` lets a re-extraction's summary overwrite a summary the reviewer edited.
  Unchanged by this task.

### Verification

| Check | Result |
|---|---|
| 13 intake classes (121 tests) under `-Pollama` | 0 failures, 0 skipped |
| `ClaimReviewPanelPageTests` + `RoundcubeClaimIntakeE2ETests` under `-Pollama` | 9 run, 3 failures, 6 errors (Playwright timeouts); the same 9 fail on clean `HEAD` (`59e3b28`) in a baseline worktree, so they aren't caused by this change |
| `-Pollama-openai` | **Not run locally** (user decision 2026-10-30: left to CI) |
| Full `clean verify` | **Not run locally**; CI is the check |
| Real-model dev-mode check | **Not run** |
| IntelliJ lint of 8 changed files | nothing on changed lines (known `@Inject` false positive and pre-existing warnings only) |

Two earlier `-Pollama-openai` attempts hit Testcontainers start failures (`localhost:2375 failed to respond`) and
skipped 61 and 54 of 121 tests, 0 failures among those that ran; they prove nothing for that profile.
