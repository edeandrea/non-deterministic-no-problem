# Task 10: Review API and UI

**Type:** Code Modification

## Goal

A claims processor can see the new statuses, and can do the final completeness look-over of a
`Pending Review` claim from the claim detail page: either confirm it's ready, or request more
information from the customer. The decision resumes the paused workflow.

## What to Do

- **REST** (Jakarta REST annotations, plural kebab-case path):
  - `POST /api/db/claims/{id}/review-decisions`, with body
    `{ "outcome": "READY" | "NEEDS_INFORMATION", "missingItems": [ ... ], "version": <claim version> }`.
    The `version` is the claim version the reviewer saw (optimistic locking, task 07).
  - Validate the body with Bean Validation: `NEEDS_INFORMATION` needs at least one item, and `READY` must have none (a class-level constraint).
  - Pass the decision to `ClaimReviewService.decide` (task 07), which:
    1. claims the review under the optimistic lock
    2. loads the scope (`getAgenticScope(reviewRunId)`) and checks `review:<reviewRunId>` is pending
    3. calls `completePendingResponse`
    4. re-invokes the root with the **original arguments read back from the scope** (`readState`)
    5. applies the outcome (task 08) and evicts the scope

    On success, return the updated claim.
  - Errors use RFC 9457 Problem Details:
    - 404 for an unknown claim, **or when the claim's scope is missing** (`getAgenticScope` returns `null`)
    - 409 when the claim isn't `Pending Review`, no review is pending in the scope, a decision was already
      made, or the **optimistic lock was lost** (a stale `version`, or a customer reply superseded the review first)
    - 400 for an invalid body
  - The missing items (`MissingItem` enum: incident description, incident date, location, category)
    are exposed with display labels so the UI can draw the checklist.
  - Expose the claim's `version` in the claim DTO so the UI can send it back. (#214 introduced that DTO,
    `org.parasol.claim.rest.ClaimDetails`, mapped by `ClaimMapper`: add the field to the record. New internal `Claim`
    columns stay out of the API unless they're added there.)
- **Tracing:** the resume runs in a **new trace**: a `claim-intake review-decision` SERVER span with a span link to
  the intake trace context stored on the claim (`intakeTraceparent`, task 08). Task 11 adds the attributes and tests.
  `decide` reads the claim's `intakeConversationId` (task 08) and makes it current as `gen_ai.conversation.id` baggage before
  the review-decision span starts, so the decision joins the claim's conversation (task 11).
- **UI:**
  - `ClaimsList.tsx` and `ClaimDetail.tsx`: add label colours, and status-filter options, for `Pending Information` and `Pending Review`.
  - `ClaimDetail.tsx`: when the status is `Pending Review`, show a review panel in place of the disabled Edit button, with:
    - **Ready for processing**
    - **Request more information**: one checkbox per missing item, plus a submit button

    After a decision, reload the claim. On a 409, show a short message ("this claim changed; reloaded") and reload.
- **Tests:**
  - **REST** (GreenMail-backed; delete created claims afterwards):
    - `READY` moves the claim to `In Process`, sends exactly one thank-you email, and leaves no scope row
    - `NEEDS_INFORMATION` moves it to `Pending Information`, the email lists exactly the ticked items, and no scope row is left
    - 404 for an unknown claim and for a `Pending Review` claim whose scope row is gone
    - 409 for a claim not in review, a second decision, a stale `version`, and a decision after a superseding reply
    - 400 for each invalid body
    - the resumed run is in a new trace with a link to the stored intake trace context
  - **Playwright:**
    - the labels and filters for both new statuses
    - the review panel shows only for `Pending Review`
    - clicking Ready changes the status
    - requesting two items changes the status to `Pending Information`

## Files/Areas

- `src/main/java/org/parasol/intake/review/rest/ClaimReviewResource.java` (new), plus request/response records
- `src/main/webui/src/app/components/ClaimsList/ClaimsList.tsx`, `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`
- `src/test/java/org/parasol/intake/review/rest/`, `src/test/java/org/parasol/ui/`

## Key Points

- The API has no authentication (it's a demo app). Note this in the docs.
- Keep the existing statuses (`New`, `In Process`, `Processed`, `Denied`) unchanged.
- The review panel replaces the disabled Edit button only for `Pending Review` claims. Other claims render exactly as they do today.
- Needs more information **ends** the resumed run at `Pending Information`; there is no re-suspension. The customer's next email starts a new run.
- No transaction is open while the root is re-invoked.

## Done When

- [ ] The review endpoint exists, is validated, and returns Problem Details for every error case (404 missing scope, 409 no pending review / lost lock). `/q/openapi` lists it.
- [ ] A decision completes the pending response, re-invokes the root with the arguments read from the scope, and evicts the scope.
- [ ] The resume span links to the intake trace.
- [ ] The review-decision trace carries the claim's `intakeConversationId` as `gen_ai.conversation.id`.
- [ ] Both new statuses have labels and filters, and the review panel drives both decisions.
- [ ] All the REST and Playwright tests listed above pass, and the frontend builds.
