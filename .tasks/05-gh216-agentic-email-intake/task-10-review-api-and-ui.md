# Task 10: Review API and UI

**Type:** Code Modification

## Goal

A claims processor can see the new statuses, and can do the final completeness look-over of a `Pending Review`
claim from the claim detail page: either confirm it's ready, or request more information from the customer. The
decision wakes the claim's waiting workflow run (task 08), which applies it.

*Rewritten after the Flow verdict (task 01b = ADOPT, option b1).* There's no `ClaimReviewService` resume, no
`readState` replay and no eviction. The endpoint checks that the run is waiting and **publishes a decision event**,
so it's **asynchronous**: it returns once the event is published, not once the claim has changed.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b. Nothing here depends on it directly. Check `PLAN.md` →
Execution Steps → 2a first.

## What to Do

- **REST** (Jakarta REST annotations, plural kebab-case path):
  - `POST /api/db/claims/{id}/review-decisions`, with body
    `{ "outcome": "READY" | "NEEDS_INFORMATION", "missingItems": [ ... ], "version": <claim version> }`.
    The `version` is the claim version the reviewer saw (optimistic locking, `@Version` on `Claim`).
  - Validate the body with Bean Validation: `NEEDS_INFORMATION` needs at least one item, and `READY` must have none (a
    class-level constraint).
  - A `ClaimReviewService.submit(claimId, expectedVersion, ReviewDecision)` does, in order:
    1. **Claim the review**, in a `requiringNew()` transaction under the optimistic lock: the claim must be
       `Pending Review`, have a `reviewRunId`, and still be at the reviewer's version. Move `reviewRunId` to
       `decidingRunId` and commit (bumping the version). This is what serialises a decision against a customer reply
       that supersedes the review (task 08): whichever commits first wins.
    2. **Check the run is waiting:** `@Inject PersistenceInstanceReader` →
       `find(intakeFlow.definition(), runId)` (C8). Missing → 404. Present but not `WAITING` → 409. **Don't use a raw
       `select status`**: that column is `NULL` for a waiting instance.
    3. **Publish** the `REVIEW_DECIDED` CloudEvent, with the claim id and the `ReviewDecision` as its data. Task 08's
       `listen` correlates on the claim id.
    4. Return **202 Accepted** with the claim (still `Pending Review`, now being decided).
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
  - Expose the claim's `version` in the claim DTO so the UI can send it back. (#214 introduced that DTO,
    `org.parasol.claim.rest.ClaimDetails`, mapped by `ClaimMapper`: add the field to the record. New internal `Claim`
    columns stay out of the API unless they're added there.)
- **Observability:** nothing here. The decision steps run inside the claim's own run, so their spans carry the run's
  conversation id (task 11). No separate decision trace and no span link: the run's decision steps are already in its
  spans.
- **UI:**
  - `ClaimsList.tsx` and `ClaimDetail.tsx`: add label colours, and status-filter options, for `Pending Information` and
    `Pending Review`.
  - `ClaimDetail.tsx`: when the status is `Pending Review`, show a review panel in place of the disabled Edit button, with:
    - **Ready for processing**
    - **Request more information**: one checkbox per missing item, plus a submit button
  - After a decision, show "decision sent" and reload until the status changes (bounded, e.g. every second for 10 s),
    because the endpoint is asynchronous. On a 409, show a short message ("this claim changed; reloaded") and reload.
- **Tests:**
  - **REST** (GreenMail-backed; delete created claims afterwards):
    - `READY` → 202, then the claim reaches `In Process`, exactly one thank-you email is sent, and the run's Flow rows
      are gone
    - `NEEDS_INFORMATION` → 202, then `Pending Information`, the email lists exactly the ticked items, and no Flow rows
      are left
    - 404 for an unknown claim and for a `Pending Review` claim whose run is gone
    - 409 for a claim not in review, a second decision while the first is being applied, a stale `version`, and a
      decision after a superseding reply
    - a failed step-2 check leaves the claim's `reviewRunId` as it was
    - **race (moved here from task 08, user decision 2026-10-09):** a customer reply and a decision on the same
      `Pending Review` claim, run concurrently (latched): exactly one wins. A losing decision gets the 409 and sends no
      email; a losing reply is handled under the claim's new status. (Task 08 covers the reply's side on its own.)
    - 400 for each invalid body
    - decisions published right as the run reaches `WAITING` aren't lost: the test re-publishes until the run leaves
      `WAITING` (F2b: `WAITING` is set just before the `listen` registers its consumer)
  - **Playwright:**
    - the labels and filters for both new statuses
    - the review panel shows only for `Pending Review`
    - clicking Ready changes the status (after the reload)
    - requesting two items changes the status to `Pending Information`

## Files/Areas

- `src/main/java/org/parasol/intake/review/`: `ClaimReviewService`, `rest/ClaimReviewResource.java` (new), request/response records
- `src/main/java/org/parasol/claim/model/Claim.java` (`decidingRunId`), `org.parasol.claim.rest.ClaimDetails` (`version`)
- `src/main/webui/src/app/components/ClaimsList/ClaimsList.tsx`, `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`
- `src/test/java/org/parasol/intake/review/rest/`, `src/test/java/org/parasol/ui/`

## Key Points

- The API has no authentication (it's a demo app). Note this in the docs.
- Keep the existing statuses (`New`, `In Process`, `Processed`, `Denied`) unchanged.
- `MissingItem` and `IntakeClaimStatus` exist (`org.parasol.intake`, task 04). The checklist labels are
  `MissingItem.label()`, which is also its JSON form (`@JsonValue` / `@JsonCreator`).
- The review panel replaces the disabled Edit button only for `Pending Review` claims. Other claims render exactly as
  they do today.
- Needs more information **ends** the run at `Pending Information`; the customer's next email starts a new run.
- **F2b only matters to automated callers.** A human clicks long after `WAITING`; tests and any scripted decider must
  re-publish until the run leaves `WAITING` (the `listen` consumes one event and ignores extra copies).

## Done When

- [ ] The review endpoint exists, is validated, returns 202 on success and Problem Details for every error case (404 missing run, 409 not waiting / being decided / lost lock). `/q/openapi` lists it.
- [ ] A decision claims the review under the lock, checks the run through `PersistenceInstanceReader`, and publishes the decision event; the run applies it.
- [ ] A reply and a decision are serialized; the losing reply is handled under the claim's new status, and the concurrent race test passes.
- [ ] Both new statuses have labels and filters, and the review panel drives both decisions.
- [ ] All the REST and Playwright tests listed above pass, and the frontend builds.
