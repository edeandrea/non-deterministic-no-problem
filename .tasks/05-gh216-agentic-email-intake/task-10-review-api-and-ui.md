# Task 10: Review API and UI

**Type:** Code Modification

**Issue:** [#258](https://github.com/edeandrea/non-deterministic-no-problem/issues/258)

## Goal

A claims processor can see the new statuses, and can do the final completeness look-over of a `Pending Review`
claim from the claim detail page: either confirm it's ready, or request more information from the customer. The
decision wakes the claim's waiting workflow run (task 08), which applies it.

*Rewritten after the Flow verdict (task 01b = ADOPT, option b1).* There's no `ClaimReviewService` resume, no
`readState` replay and no eviction. The endpoint checks that the run is waiting and **publishes a decision event**,
so it's **asynchronous**: it returns once the event is published, not once the claim has changed.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b. Nothing here depends on it directly. Check `PLAN.md` →
Execution Steps → 2a first.

## Progress (2026-10-27): build-order steps 1–5 done (UI rebuilt; see the end of this section)

**Backend for the reworked panel is built and tested; the UI (steps 3–5) is not.** The user asked for server-side
validation before either action proceeds, since a `Pending Review` claim can now be edited: at minimum that the claim is
`Pending Review`, plus whatever else fits. What's in place:

- **`POST .../review-decision` now carries `fields`** (`description` = `summary`, `incident_date`, `incident_time`,
  `location`, `category`): **the whole form, not a patch.** Every `MissingItem` must have a value **or** be in
  `missing_items`, never both, never neither. The edits ride in `ReviewDecision.fields` (`ReviewedFields`, domain) and
  `ReviewDecisionSteps` sets them **in the same transaction as the status change** (after its existing re-check that the
  claim is still `Pending Review` and being decided by that run). Applying them in `ClaimReviewService.claimReview`
  instead was rejected: a failed run check would then need to compensate the edits too, and a decision step that faults
  would leave edits on a claim that's back in review.
- **Checks, in order, before anything is published** (nothing changes on any failure; tests assert the review stays open
  at the same version):
  - `400` (Bean Validation, `@ValidReviewDecision` + field constraints): outcome/items pairing; value-xor-asked per item;
    `incident_time` only with `incident_date`; `incident_date` `@PastOrPresent`; `description` ≤ 5000 (the column);
    `location` ≤ 1000; blank text = no value; **`version` now a required `Long`** — a primitive turned an omitted version
    into `0`, which a fresh `ClaimIntakeState` can match, skipping the lock. Unreadable JSON (bad date, unknown category,
    malformed) is a Problem Details `400` too, via `@ServerExceptionMapper`s **scoped to `ClaimReviewResource`** (Quarkus
    REST wraps all but `MismatchedInputException` in a bare `WebApplicationException(400)`; a global mapper for that would
    change every endpoint).
  - `404` unknown claim → `409` not `Pending Review` / no open review / decision in flight → `409` stale version (the
    open-review check now runs **before** the version check, so a closed review says so rather than "changed") → **`422`**
    an incident date before the policy's `inceptionDate` (every intake claim has one, generated before the extracted
    incident date) → `404`/`409` run gone / not waiting.
- **`POST /api/db/claims/{id}/sentiment`** (`{version}` → `200 {sentiment}`; the path the
  original sketch used), `ClaimSentimentReassessor`: same open-review + version check
  (`ReviewGuard`, shared with the decision) before the model call, the call **outside any transaction**, then the same
  check again under `PESSIMISTIC_WRITE` on the `ClaimIntakeState` row before storing — a reply or decision landing during
  the call wins (`409`, result discarded). Blank or failed model answer → `502`, sentiment unchanged. Doesn't bump the
  version, so the reviewer's pending decision stays valid. **Not** required: the run being `WAITING` (it isn't touched).
- **Bridge, to be deleted in step 3:** the superseded `ReviewPanel.tsx` now sends the claim's current values as `fields`
  (minus checked items, `reviewedFields`), so the branch stays green (`ClaimReviewPanelPageTests` 4/4).
- **Tests:** `ClaimReviewResourceTests` 16/16 (two clean runs) — new: reviewed values set with `READY`, kept/asked split with
  `NEEDS_INFORMATION`, every `400`, the `422`, and five sentiment tests (success, each 404/409/400, 502, a decision during
  the call); `ValidReviewDecisionTests` (rewritten against `Validator.problems`), `ReviewedFieldsTests` (new),
  `IntakeJsonRoundTripTests` (round-trips fields), `ClaimIntakeReviewTests` unchanged and green.

**Requirement for step 3 (user, 2026-10-27): the review controls exist only for `Pending Review`.** The "Ask customer"
checkboxes, the inline edit controls, the submit button and the sentiment "Regenerate" button are **not rendered at all**
(not just disabled) for any other status. Every other status shows the plain read-only claim, and `Pending Information`
gets its read-only requested-items section. Keep both gates the superseded panel has: `ClaimDetail` mounts the review
section only when `claim.status === 'Pending Review'`, **and** the section renders nothing unless `GET .../review` says
`open`, which is the server's live check (no decision in flight, run actually `WAITING`). After a decision, the claim
re-fetch (new status) removes the controls. The server already refuses both actions outside an open review (`409`), so
the UI gate is presentation, not the guard. **Step 5's Playwright tests must assert the controls are absent** for:
- a seeded claim (no intake state)
- a `Pending Information` claim (which shows its requested items read-only instead)
- an `In Process` claim
- the same claim right after each decision has been applied

Today's test covers only the first of these.

**Open for the user:** location's 1000-character limit is a judgment call (the column is unbounded); `422` vs `409` for
the inception-date rule (chose `422` so the UI's `isConflict` "claim changed; reloaded" path doesn't fire for it).

**UI rebuilt (steps 3–5, 2026-10-27).** `ReviewPanel` now replaces the Summary tab's read-only details while a review is open (so nothing is shown twice): banner, editable
Date and time / Category / Description / Location each with an "Ask customer" checkbox (a checked field is disabled and
sent in `missing_items`, not `fields`), read-only Sentiment with Regenerate (spinner), and one submit button that reads
"Submit claim for processing" or "Request more information". 422/502 show the problem `detail` inline and keep the review
open; 409 takes the existing "reloaded" path. `PendingInformation` lists what was asked for a `Pending Information` claim,
with no actions. The controls render only for `Pending Review` (mount gate in `ClaimDetail` plus `open` from the server).
The Summary tab now shows the category. `ClaimReviewPanelPageTests` rewritten (visibility absence cases, edit, ask, Regenerate).

## Resume Here (2026-10-26)

The backend (`ClaimReviewService`, `ClaimReviewResource`, Problem Details, tests) is done and merged-ready — see
Outcome below. **What's left is the UI**, redesigned end to end in a review conversation with the user after the
original checklist-style panel was built and shown to them. Nothing below is implemented yet. Read this whole
section before touching code; it supersedes the UI bullets further down, which are kept only as a short pointer here.

**Why the rework.** The built version squeezed two bare buttons into the header row's disabled-Edit-button slot, with
no indication the claim needed attention, and the "Request more information" checklist showed abstract labels
("category") with no indication of the claim's *current* value — nothing for the reviewer to actually judge. Walking
through the mock-ups surfaced three separate problems, resolved as follows.

**1. `Pending Information` claims show nothing about what's pending today.** A new dedicated section, between the
claim's header row and its tabs (not inside the header row), read-only, listing `ClaimReviewState.requestedItems` —
no actions, nothing editable, since the customer (not the agent) is who answers this.

**2. `Pending Review` needs to be obviously actionable, and the reviewer needs to see current values, not abstract
labels.** The same section slot, but for `Pending Review`:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠  This claim is ready for your review.                                 │
└──────────────────────────────────────────────────────────────────────────┘
┌─ Summary │ Insurance │ Damages │ Witnesses │ Documents │ Comments ────────┐
│                                                                           │
│  📅  Date and time                                ☐ Ask customer         │
│      2026-10-07 at 3:30 PM                                               │
│                                                                           │
│  🏷  Category                                     ☐ Ask customer         │
│      Single vehicle                              ▾                      │
│                                                                           │
│  📋  Description                                  ☐ Ask customer         │
│      Car hit a post.                                                     │
│                                                                           │
│  📍  Location                                     ☐ Ask customer         │
│      Elm Street                                                          │
│                                                                           │
│  😊  Customer Sentiment                              [ Regenerate ]      │
│      Calm.                                                               │
│                                                                           │
│                                      [ Submit claim for processing ]     │
└───────────────────────────────────────────────────────────────────────────┘
```

**Field order:** Date and time, Category, Description, Location, then Sentiment (user's explicit order).

**Field mapping — verified against the actual entity, not assumed:**
- Date/time → `incidentDate`/`incidentTime`, Location → `location`, Category → `category`: all three already on
  `Claim` *and* `ClaimDetails` today. `category` is simply never rendered on `ClaimDetail.tsx` currently (it only
  appears in `ClaimsList.tsx`) — a UI gap, not a backend one.
- **Description → `claim.summary`, not a new field.** `Claim` has no `description` column at all.
  `ClaimFields`'s own Javadoc says so outright: *"A claim stores no description, so its summary stands in for it."*
  So the "Description" field reads and writes `summary`. No schema change.

**Each of these four fields gets two things:**
- **Inline editing** of the field's current value. This is new ground: nothing today lets a human write a corrected
  value directly onto `Claim` — the only way data changes today is a customer's reply triggering re-extraction. This
  reverses the "Out of scope: the reviewer editing extracted fields" line in PLAN.md's Key Decisions (updated).
- **An "Ask customer" toggle.** Checking it disables editing that field and excludes it from what's saved; on submit
  it becomes one of the claim's `requestedItems` instead.

**Sentiment is different again — not editable, and not part of the "ask customer" set.** It's the AI's own
assessment of tone, not something the customer stated, so neither "correct it yourself" (there's no ground truth
to type) nor "ask the customer" ("what's your sentiment?" doesn't make sense) fits. Instead: a standalone
**"Regenerate"** button, independent of the rest of the form and of Submit entirely:
- Clicking it fires immediately (not deferred to Submit) via its own new backend call.
- Shows a spinner in place of the button while in flight (`[ ⏳ Regenerating… ]`), then swaps back to
  `[ Regenerate ]` with the new value displayed once it returns.
- **Backend:** `ClaimSentimentAgent` is a `@RegisterAiService` interface, so it's already an injectable CDI bean —
  same pattern as `ClaimResolver` ("a plain AI service, outside the agent topology"). A new small endpoint (e.g.
  `POST /api/db/claims/{id}/sentiment`) injects it directly, calls
  `assessClaimantSentiment(correspondence)`, persists `claim.sentiment`, and returns the new value — a single LLM
  call, so a plain synchronous `200` response is simplest; no 202-and-poll needed, since this isn't racing a Flow
  run the way the review decision is.
  **The input is simpler than first assumed — verified, not just planned:** `claim.body` *is already* the full
  accumulated correspondence, not just the first email. `ApplyOutcomeStep.java:103` does
  `claim.body = CorrespondenceText.appendReply(claim.body, ...)` on every reply — the growing thread, each message
  under a dated separator, is what's persisted as the claim's own `body`. So no new correspondence-building is
  needed at all: cap it the same way the normal pipeline does
  (`CorrespondenceText.cap(claim.body, this.config.limits().maxBodyCharacters())`, reusing the existing
  `IntakeConfig`) and pass that straight to `assessClaimantSentiment`.
- Explicitly ruled out before landing on this: re-running the whole `ClaimExtractionWorkflow` (would also silently
  recompute description/date/location/category, clobbering whatever the reviewer just corrected in the same
  submission, since the three extraction agents run as one `@ParallelAgent`) and routing it through
  `ClaimIntakeFlow` (would re-trigger policy checks, a reply email, filing — none of which make sense for "redo one
  AI assessment").

**3. The submit button's label reflects the consequence.** One button, two labels depending on whether any
"Ask customer" box is checked:
- **Nothing checked** → **"Submit claim for processing"**. Persist any edits (summary/date/location/category) and
  move the claim to `In Process`. No customer contact — a human already vouched for everything.
- **Something checked** → **"Request more information"**. Persist the *other* fields' edits, the checked field(s)
  become `requestedItems`, claim → `Pending Information`, customer gets the existing missing-information email
  listing exactly those.

**Backend work this implies (done 2026-10-27, see Progress above):** `ReviewDecisionRequest`/`ClaimReviewService.submit` currently only
carry an outcome and which items are missing — they need to also carry the corrected field values for whichever
fields *weren't* toggled "ask customer," and `ClaimReviewService` needs to persist those onto `Claim` as part of
applying the decision. Work out this shape before touching `ReviewPanel.tsx`; the UI can't be built against an API
that doesn't exist yet.

**Build order suggested:** (1) extend the backend to accept and persist corrected values, with REST tests; (2) the
new standalone sentiment-regenerate endpoint, with its own tests; (3) `ReviewPanel.tsx`/`ClaimDetail.tsx` rebuilt
against the new shapes; (4) the new `Pending Information` read-only section; (5) Playwright tests rewritten for all
of the above (the existing `ClaimReviewPanelPageTests` assertions on the old checkbox-list panel will no longer
apply and need replacing, not just extending).

## What to Do

- **REST** (Jakarta REST annotations, plural kebab-case path):
  - `POST /api/db/claims/{id}/review-decision`, with body
    `{ "outcome": "READY" | "NEEDS_INFORMATION", "missingItems": [ ... ], "version": <review version> }`.
    The `version` is the version the reviewer saw (optimistic locking). **Task 08 moved it off `Claim`:** it's the `@Version`
    of the claim's `ClaimIntakeState` row (user decision, 2026-10-11: no intake bookkeeping on `Claim`, and none in claim
    retrieval: `ClaimRetrievalHasNoIntakeBookkeepingTests`). So `ClaimDetails` doesn't carry it. **Decide here, with the
    user:** the review UI needs the version (and the claim's requested items) from somewhere, so give the review resource
    its own read, e.g. `GET /api/db/claims/{id}/review` returning a `ClaimReviewState` record, rather than widening the claim DTO.
  - Validate the body with Bean Validation: `NEEDS_INFORMATION` needs at least one item, and `READY` must have none (a
    class-level constraint).
  - A `ClaimReviewService.submit(claimId, expectedVersion, ReviewDecision)` does, in order:
    1. **Claim the review**, in its own transaction (`@Transactional(REQUIRES_NEW)` on a method of its own: task 08's review decision prefers `@Transactional` to `QuarkusTransaction`) under the optimistic lock: the claim must be
       `Pending Review`, its `ClaimIntakeState` must have a `reviewRunId`, and still be at the reviewer's version. Move
       `reviewRunId` to `decidingRunId` (both on `ClaimIntakeState`) and commit (bumping the version). This is what serializes a decision against a customer reply
       that supersedes the review (task 08): whichever commits first wins.
    2. **Check the run is waiting:** `@Inject PersistenceInstanceReader` →
       `find(intakeFlow.definition(), runId)` (C8). Missing → 404. Present but not `WAITING` → 409. **Don't use a raw
       `select status`**: that column is `NULL` for a waiting instance.
    3. **Publish** the `REVIEW_DECIDED` CloudEvent through task 08's `ReviewDecisionPublisher`, with
       `ReviewDecided(claimId, runId, decision)` as its data, where `runId` is the id just moved into `decidingRunId`.
       Task 08's `listen` matches **both** the run id and the claim id (D6).
    4. Return **202 Accepted** with no body (see the `GET .../review` decision above: returning `ClaimDetails` here would
       mean constructing it by hand in `org.parasol.intake.review.rest`, bypassing `ClaimMapper`'s
       `unmappedTargetPolicy = ERROR` safety net — a new `ClaimDetails` field would then go silently unmapped here. The UI
       re-fetches the claim itself, since this call doesn't wait for it to change anyway).
  - If step 2 fails, put `reviewRunId` back in a compensating transaction, so the claim isn't left stuck in
    "being decided".
  - The decision steps clear `decidingRunId` when they apply the decision (task 08).
  - **A reply that loses the lock** (task 08's `supersede` finds `decidingRunId` set) ends without changes and goes back
    in the claim's queue; it's handled again once the decision is applied (e.g. `In Process` → status reply).
  - Errors use RFC 9457 Problem Details:
    - 404 for an unknown claim, or when the claim's run no longer exists
    - 409 when the claim isn't `Pending Review`, is already being decided, its run isn't `WAITING`, or the **optimistic
      lock was lost** (a stale `version`, or a customer reply superseded the review first)
    - 400 for an invalid body
  - The missing items (`MissingItem` enum: incident description, incident date, location, category) are exposed with
    display labels, so the UI can draw the checklist.
  - **Decided (2026-10-26): `GET /api/db/claims/{id}/review` returns a `ClaimReviewState`** (status, the
    `ClaimIntakeState` version, whether a review is open, its requested items, and every `MissingItem` for the UI's
    checklist). `ClaimDetails` is untouched, as the bullet above says — the earlier "expose the claim's `version` in the
    claim DTO" idea is dropped, since it would widen the claim API with intake bookkeeping
    (`ClaimRetrievalHasNoIntakeBookkeepingTests`).
- **Observability:** nothing here. The decision steps run inside the claim's own run, so their spans carry the run's
  conversation id (task 11). No separate decision trace and no span link: the run's decision steps are already in its
  spans.
- **UI — rewritten 2026-10-26 after walking the built version through with the user. See *Resume Here* below for the
  full, final design (banner text, field order, the three field-level mechanisms, button behavior); this bullet is
  kept short on purpose since Resume Here is the source of truth:**
  - `ClaimsList.tsx` and `ClaimDetail.tsx`: label colors and status-filter options for `Pending Information` and
    `Pending Review` — **done**, unaffected by the rework below.
  - `ClaimDetail.tsx`: a dedicated section between the header row and the tabs (not squeezed into the header's
    disabled-Edit-button slot, which wasn't prominent enough) — **not yet built**:
    - `Pending Information`: read-only, lists what's currently requested of the customer.
    - `Pending Review`: the claim's own extracted fields shown inline and editable (not an abstract checklist), each
      with a way to say "ask the customer instead"; a single submit button.
- **Tests:**
  - **REST** (GreenMail-backed; delete created claims afterwards):
    - `READY` → 202, then the claim reaches `In Process`, exactly one thank-you email is sent, and the run's Flow rows
      are gone
    - `NEEDS_INFORMATION` → 202, then `Pending Information`, the email lists exactly the checked items, and no Flow rows
      are left
    - 404 for an unknown claim and for a `Pending Review` claim whose run is gone
    - 409 for a claim not in review, a second decision while the first is being applied, a stale `version`, and a
      decision after a superseding reply
    - a failed step-2 check leaves the claim's `reviewRunId` as it was
    - **race (moved here from task 08, user decision 2026-10-09):** a customer reply and a decision on the same
      `Pending Review` claim, run concurrently (latched): exactly one wins. A losing decision gets the 409 and sends no
      email; a losing reply is handled under the claim's new status. (Task 08 covers the reply's side on its own.)
    - 400 for each invalid body
    - **F2b, resolved rather than tested (2026-10-26):** read `serverlessworkflow-impl-core`'s `ListenExecutor` and
      `InMemoryEvents` sources directly rather than guessing at a mitigation. `WAITING` does become visible a couple of
      statements before `listen` registers its consumer, but `InMemoryEvents.publish` always hops to an executor before
      reading the registered consumer — a hop that structurally outlasts two sequential method calls on the thread
      that's already running. Any real caller doing actual work in between (a transaction, (de)serialization, an HTTP
      round trip, all of which `ClaimReviewService.submit` does) can't observe the gap in practice; only a synthetic
      caller publishing back-to-back with nothing else could. `ClaimReviewService` doesn't retry or block for it — see
      its Javadoc and CLAUDE.md's REST section. No new test for this in this task: `ReviewDecisionPublisher`'s own
      republish-until-woken loop (`IntakeScenario.publishUntilWoken`) is already exercised by every decision in the
      merged `ClaimIntakeReviewTests` suite.
  - **Playwright:**
    - the labels and filters for both new statuses
    - the review panel shows only for `Pending Review`
    - clicking Ready changes the status (after the reload)
    - requesting two items changes the status to `Pending Information`

## Files/Areas

- `src/main/java/org/parasol/intake/review/`: `ClaimReviewService` and its four domain exceptions (`ReviewNotOpenException`,
  `ReviewLockLostException`, `ReviewRunNotFoundException`, `ReviewRunNotWaitingException`)
- `src/main/java/org/parasol/intake/review/rest/` (new): `ClaimReviewResource`, `ClaimReviewState`, `ReviewDecisionRequest`,
  `ValidReviewDecision` (the class-level Bean Validation constraint), `ReviewExceptionMappings`
- `src/main/java/org/parasol/problem/ProblemDetail.java` (new) — a shared `ProblemDetail` record, outside every
  domain package: RFC 9457 is a named standard with no domain vocabulary of its own, not generic REST plumbing, so it
  doesn't belong under one domain's `.rest` package (ruled out: a top-level `org.parasol.rest` bucket, which violates
  the domain-first package structure; also ruled out: duplicating the record per domain, once two domains needed it).
  Both `ClaimExceptionMappings` and `ReviewExceptionMappings` import it; `ClaimExceptionMappings`'s own nested record
  is gone, and the two existing claim REST tests' imports moved with it
- `src/main/java/org/parasol/intake/model/ClaimIntakeState.java`: new `isOpenForReview()` instance method (the cheap
  DB-bookkeeping check) and its Javadoc clarifying it's necessary but not sufficient — see `isReadyForDecision` below
- `ClaimReviewService.isReadyForDecision(ClaimIntakeState)`: the `GET .../review` read's `open` field checks this, not
  just `isOpenForReview()` — `reviewRunId` is set in `applyOutcome`, several steps before the run actually reaches
  `waitReview` and goes `WAITING` (it still sends the reply and files the email first), so a claim can read as
  bookkeeping-open while a decision would still be rejected with `ReviewRunNotWaitingException`. Caught in review;
  closes a real (not theoretical) gap, since the window is however long an SMTP send + IMAP move take, not nanoseconds
- Task 08 already adds `ClaimIntakeState` (`org.parasol.intake.model`: `reviewRunId`, `decidingRunId`, `@Version`, requested items), the `org.parasol.intake.review` event types and `ReviewDecisionPublisher`. `ClaimDetails` stays untouched
- `src/main/webui/src/app/components/ClaimsList/ClaimsList.tsx`, `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`,
  `src/main/webui/src/app/components/ClaimDetail/ReviewPanel.tsx` (new), `src/main/webui/src/app/utils/httpErrors.ts`
  (new `isConflict`)
- `src/test/java/org/parasol/intake/review/rest/ClaimReviewResourceTests.java`, `ValidReviewDecisionTests.java` (both new)
- `src/test/java/org/parasol/ui/ClaimReviewPanelPageTests.java`, `src/test/java/org/parasol/ui/IntakeUiTestProfile.java` (both new)
- `src/main/webui/src/app/utils/httpErrors.test.ts` (new `isConflict` cases); `src/main/webui/src/app/__snapshots__/app.test.tsx.snap` (updated for the two new filter options)

## Key Points

- The API has no authentication (it's a demo app). Note this in the docs.
- Keep the existing statuses (`New`, `In Process`, `Processed`, `Denied`) unchanged.
- `MissingItem` and `IntakeClaimStatus` exist (`org.parasol.intake`, task 04). The checklist labels are
  `MissingItem.label()`, which is also its JSON form (`@JsonValue` / `@JsonCreator`).
- **Superseded by the 2026-10-26 rework (see Resume Here):** the review UI is its own section between the header row
  and the tabs, not a replacement for the disabled Edit button — the header-row slot wasn't prominent enough. The
  disabled Edit button itself is untouched for every status.
- **`org.parasol.intake.review.rest` depends only on `org.parasol.claim.model`**, never `org.parasol.claim.rest`'s DTOs
  (CLAUDE.md's "Dependencies point one way... intake → claim" rule applies at the `.rest` layer too, not just `.model`).
  An early draft hand-built a `ClaimDetails` for the `202` body, bypassing `ClaimMapper`'s `unmappedTargetPolicy = ERROR`
  safety net — caught in review and replaced with no body at all (see the `What to Do` note above).
- **`GET .../review`'s `open` field is a live check, not just DB bookkeeping.** `ClaimIntakeState.isOpenForReview()`
  alone doesn't guarantee a decision would succeed (see `isReadyForDecision` in Files/Areas) — caught in review.
- **`ProblemDetail` lives in `org.parasol.problem`**, not a domain package and not a top-level `rest` bucket — see
  Files/Areas. If more RFC 9457-adjacent types are ever needed, they belong there too.
- **The Playwright review-panel tests needed a real `Pending Review` claim**, which only exists through a real
  `ClaimIntakeFlow` run (`ClaimReviewState.open` reflects actual DB + Flow state; a fabricated claim without one just
  renders nothing). `IntakeUiTestProfile` (`org.parasol.ui`) combines `IntakeTestProfile`'s overrides with Quinoa's
  `EnableAndRunTests` flags for `ClaimReviewPanelPageTests`, the one Playwright class that needs it; the status-label/filter
  test doesn't, and uses directly-persisted fixture claims.
- Needs more information **ends** the run at `Pending Information`; the customer's next email starts a new run.
- **F2b only matters to automated callers.** A human clicks long after `WAITING`; tests and any scripted decider must
  re-publish until the run leaves `WAITING` (the `listen` consumes one event and ignores extra copies).

## Done When

- [x] The review endpoint exists, is validated, returns 202 on success and Problem Details for every error case (404 missing run, 409 not waiting / being decided / lost lock). `/q/openapi` lists it.
- [x] A decision claims the review under the lock, checks the run through `PersistenceInstanceReader`, and publishes the decision event; the run applies it.
- [x] A reply and a decision are serialized; the losing reply is handled under the claim's new status, and the concurrent race test passes.
- [x] Both new statuses have labels and filters.
- [x] The decision accepts and validates the reviewed field values and the sentiment-assessment endpoint exists, both
  checking server-side that the claim is `Pending Review` with its review open at the reviewer's version (2026-10-27).
- [x] The review form (inline-editable fields, per-field "Ask customer" toggles, the dynamic submit label, the sentiment
  Regenerate action) drives both decisions, replaces the Summary tab's read-only details while a review is open, and is
  absent for every other status; a `Pending Information` claim shows a read-only list of what was asked (2026-10-27).
- [x] All the REST tests pass, and the Playwright tests were rewritten against the new design (visibility absence cases,
  edit persisted, ask-customer, Regenerate).
- [x] The frontend builds against the new design (`tsc`, Jest and the Quinoa build inside the Maven run).

**Intake-open rule (2026-10-27, beyond the original scope).** A claim in `Pending Review` or `Pending Information` hasn't
finished intake, so no action can be taken on it yet:
- `IntakeClaimStatus` moved to `org.parasol.claim.model` (so `notification` doesn't depend on `intake`) and gained `isIntakeOpen`.
- The chat bot's only action tool, `updateClaimStatus`, is gated twice: `IntakeCompleteToolInputGuardrail`
  (`@ToolInputGuardrails`; fails closed on an unreadable `claimId`) refuses before the tool runs, and the tool repeats the check in the transaction that writes
  (the authoritative one; the guardrail's read is separate). Read-only chat (RAG answers, discussion) is unaffected.
- The UI's shared `StatusLabel` shows the reason as a tooltip on both statuses, in the list and on the detail page.
  The status names were deliberately **not** renamed to "Pending Intake ...": a rename touches stored values, the enum,
  filters, colors, tests and docs for little gain over the tooltip.
- Dev only: `org.parasol.intake.dev.DevClaimEmailSeeder` mails two sample claims at startup so both statuses can be seen in the UI. The bean needs the build property
  `parasol.dev-seed.enabled=true` (`%dev`, and both Ollama Maven profiles' `quarkus:dev` system properties;
  `@IfBuildProfile("dev")` wouldn't match their `ollama,prod` profile) and acts in launch mode `DEVELOPMENT` only. Not run live yet.
- Also: `IntakeClaimStatus.isIntakeOpen` is case-insensitive and now backs `SupersedeStep` and `MatchedClaim.isPending()`
  (previously hand-rolled copies; `ClaimMatchingSteps.PENDING_STATUSES` stays, it's a query list).
- Tests: `NotificationServiceTests.noActionWhileIntakeIsNotComplete`, `IntakeCompleteToolInputGuardrailTests`, `ClaimReviewResourceTests.aReplyDuringTheAssessmentSupersedesTheReviewAndTheAssessmentIsDiscarded` (a reply during a sentiment reassessment wins, as a decision does), tooltip
  assertions in `ClaimReviewPanelPageTests`. **Not covered:** an end-to-end chat test showing the model receives the
  guardrail's refusal; whether the chat panel should be disabled or show a notice on these claims (it stays usable).

## Outcome

**Branch:** `gh216/10-review-api-and-ui` off `gh216-email-intake`.

**Backend.** `ClaimReviewService` (`org.parasol.intake.review`) claims a claim's review in its own
`@Transactional(REQUIRES_NEW)` method under the `ClaimIntakeState` optimistic lock (checking the reviewer's `version`
explicitly, since Hibernate's own check only compares against the loaded entity, not the client's claimed value, and
catching a concurrent `OptimisticLockException` the same way `SupersedeStep` does), confirms the run through `@Inject
PersistenceInstanceReader`, and publishes `ReviewDecided` through the existing `ReviewDecisionPublisher`. A failed
run-check (missing or not-waiting) restores `reviewRunId` in a compensating `REQUIRES_NEW` transaction. Four domain
exceptions (`ReviewNotOpenException`, `ReviewLockLostException`, `ReviewRunNotFoundException`,
`ReviewRunNotWaitingException`) map to 404/409 through `org.parasol.intake.review.rest.ReviewExceptionMappings`, reusing
a shared `org.parasol.problem.ProblemDetail` so the two mappers don't duplicate the RFC 9457 builder (see Files/Areas
for why that's its own package, not a domain's `.rest` package or a top-level `rest` bucket). `ClaimReviewResource`
exposes `GET .../review` (a new `ClaimReviewState` read: status, version, whether open,
requested items, every `MissingItem`) and `POST .../review-decision` (`ReviewDecisionRequest`, validated by a new
class-level `@ValidReviewDecision` Bean Validation constraint: items required for `NEEDS_INFORMATION`, none for `READY`).

**A boundary caught in review.** An early version of `decide` hand-built a `ClaimDetails` to return in the `202` body,
importing `org.parasol.claim.rest` from `org.parasol.intake.review.rest` — the first time anything in `intake` reached
into `claim.rest` rather than `claim.model`. Worse than the layering itself: it bypassed `ClaimMapper`'s
`unmappedTargetPolicy = ERROR`, the actual safety net against an unmapped `ClaimDetails` field, so a future field would
silently go stale here instead of failing the build. Fixed by not returning a body at all — the UI already re-fetches
the claim itself, since the endpoint is asynchronous.

**A race investigated with the actual upstream source, not timing guesses.** `ReviewDecisionPublisher` documents a
known gap (F2b): a run's `WAITING` status can become visible before its `listen` task registers a consumer for the
decision event. A first pass added a bounded sleep-and-retry loop to `ClaimReviewService` to paper over it; on request,
this was replaced by actually reading the `serverlessworkflow-impl-core` and `quarkus-flow` sources available locally.
`ListenExecutor.internalExecute` sets `WAITING` and then calls `buildInfo()`/`register()` on the next line, on the same
thread; `InMemoryEvents.publish` always schedules its delivery onto an executor before it reads the registered consumer.
That scheduling hop structurally outlasts two sequential method calls on a thread that's already running, so any real
caller doing actual work in between (a DB transaction, JSON (de)serialization, an HTTP round trip — everything
`ClaimReviewService.submit` does) cannot observe the gap in practice; only a synthetic caller publishing back-to-back
with nothing else could. `ClaimReviewService` therefore publishes once, with no retry, matching
`ReviewDecisionPublisher`'s plain documented contract; the finding is recorded in its Javadoc and CLAUDE.md's REST
section rather than worked around.

**Frontend — this version is superseded, see Resume Here.** `ReviewPanel.tsx` (new) fetches `GET .../review`, renders nothing unless the review is open, and posts
`READY` or `NEEDS_INFORMATION` with the version it read; `ClaimDetail.tsx` swaps it in for the disabled Edit button only
when `claim.status === 'Pending Review'`, and polls the claim (bounded, 1 s × 10) after any decision result — "Decision
sent" on success, "This claim changed; reloaded" on a 409 (`isConflict`, new in `httpErrors.ts`, mirroring `isNotFound`).
Both `ClaimsList.tsx` and `ClaimDetail.tsx` gained `orange`/`purple` label colors for `Pending Information`/`Pending
Review`; `ClaimsList.tsx` gained the two matching filter options. The Jest snapshot updated for the two new `<option>`
elements (no other diff); `npx tsc --noEmit` and the full Jest suite are both green.

*Review feedback (2026-10-27):* a 409 no longer polls. `ClaimDetail.tsx` reloads the claim once and bumps a revision that
is part of the `ReviewPanel`'s `key`, so the panel remounts, re-reads `GET .../review` (the current version) and
re-initializes its fields; only a submitted decision polls for the status change.
`ClaimReviewPanelPageTests.aConflictReloadsTheReviewSoTheNextSubmitUsesTheCurrentVersion` covers it. The incident date
input's `max` now comes from `toLocalIsoDate` (local time zone) instead of `toISOString()` (UTC).

*Other review feedback (2026-10-27):*
- Bean Validation `400`s name violations in snake_case without the `decide.request` / `assessSentiment.request` prefix
  (`ReviewExceptionMappings.describe`); `assertBadRequest` checks the prefix is absent.
- The exceptions mapped to a response build no stack trace (see CLAUDE.md, Conventions).
- `IntakeCompleteToolInputGuardrailTests` runs on `IntakeTestProfile`, so it boots without real keys.
- `ClaimReviewService.ensureFits` is now `if (incidentPrecedesPolicy(...)) throw`, instead of an `Optional` that threw when present.
- A `409` reloads the claim once and remounts `ReviewPanel` (`reviewRevision` in its `key`), so the next submit uses the
  current version; `ClaimReviewPanelPageTests.aConflictReloadsTheReviewSoTheNextSubmitUsesTheCurrentVersion` forces it.
  The date input's `max` uses `toLocalIsoDate` (local date, not UTC).
- **Dev-mode bug:** a reply's Qute `{config:…}` lookup failed with `ServiceConfigurationError: SmallRyeConfigFactory:
  QuarkusConfigFactory not a subtype`, because `IntakeReplySender` sent from a `ForkJoinPool.commonPool()` thread, whose
  context class loader is the JVM's, not Quarkus dev mode's. Fixed: the sender now uses `ReactiveMailer` for text and
  templated replies, awaits `quarkus.mailer.timeout` itself, and does so on a fresh virtual thread (it inherits the
  caller's class loader and has no Vert.x context). `@QuarkusTest`s can't show the error (flat class loading), so
  `IntakeReplySenderThreadTests` checks the cause and fails against `commonPool()`. Not yet confirmed in a real `quarkus:dev` run.
- **Open, awaiting the user:** a design-doc note on the intake-open rule and guardrail; a websocket chat test for the guard;
  whether to disable the chat panel on intake-open claims. A full `./mvnw verify` passed on 2026-10-27 (run by the user, after the review feedback above). Not yet run: `DevClaimEmailSeeder` live.

**Tests.** `ValidReviewDecisionTests` (`org.parasol.intake.review.rest`, plain JUnit, no `@QuarkusTest`) unit-tests the
`ValidReviewDecision.Validator` directly — all four outcome/items combinations plus the null-outcome and null-request
short circuits — rather than relying only on `ClaimReviewResourceTests`' invalid-body cases to exercise it through the
full REST round trip. `ClaimReviewResourceTests` (`org.parasol.intake.review.rest`, on the shared `IntakeTestProfile`, extending
`IntakeFlowTestBase`): both decisions end to end (thank-you/missing-information email, no Flow rows left), every 404/409
case including a concurrent reply-vs-decision race (`@InjectSpy` delaying `ClaimReviewService.submit` so both sides are
genuinely in flight), and the three invalid-body shapes — 10/10, run five times with no flakiness. `ClaimReviewPanelPageTests`
(`org.parasol.ui`, new `IntakeUiTestProfile` — `IntakeTestProfile` plus Quinoa's `EnableAndRunTests`, one more full boot,
needed because the panel only renders for a review `ClaimReviewState.open` reports as genuinely open, which only a real
waiting `ClaimIntakeFlow` run produces): the panel's absence for a seeded non-`Pending Review` claim, both decisions
actually changing a real claim's status through the real endpoint, and the status labels/filters (the one scenario here
that doesn't need a real run, using two directly-persisted fixture claims) — 4/4, run twice with no flakiness. The
existing `ClaimsListPageTests` and `ClaimImagesPageTests` Playwright suites still pass unchanged.

**`clean test-compile` and `clean compile`** both green under `-Pollama` and default. The full `clean verify` across
`-Pollama`/`-Pollama-openai`/default wasn't re-run in this session (see Verification in the task's plan); the targeted
suites above were run directly and repeatedly instead.

**UI reworked and re-implemented (2026-10-27).** The checklist panel described above was replaced by the form in *Resume
Here* (see Progress). Final shape: the form sits inside the Summary tab in place of the read-only details (so nothing is
shown twice), the decision carries the reviewed `fields`, `POST .../sentiment` regenerates the sentiment, and the server
validates every action. `ClaimReviewPanelPageTests` now has 5 tests (status labels/filters, controls absent for
`In Process`/`Pending Information`, submit with an edit, ask-customer, Regenerate); `ClaimReviewResourceTests` has 16.

Also surfaced in the same conversation and spun off as their own sub-issues, not part of this task:
- the Documents tab shows only the first email, with no transcript of the back-and-forth — [task
  12c](task-12c-claim-correspondence-transcript.md), issue [#265](https://github.com/edeandrea/non-deterministic-no-problem/issues/265)
- `IncidentDetailsAgent`'s `description` is extracted only to be discarded, while a separate `ClaimSummaryAgent`
  output is what's actually persisted as `claim.summary` — [task
  12b](task-12b-consolidate-description-into-summary.md), issue [#266](https://github.com/edeandrea/non-deterministic-no-problem/issues/266)
