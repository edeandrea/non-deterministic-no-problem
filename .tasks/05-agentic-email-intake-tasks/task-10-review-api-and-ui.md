# Task 10: Review API and UI

**Type:** Code Modification

## Goal

A claims processor can see the new statuses, and can do the final review of a `Pending Review` claim
from the claim detail page: either confirm it's ready, or request more information from the customer.

## What to Do

- **REST** (Jakarta REST annotations, plural kebab-case path):
  - `POST /api/db/claims/{id}/review-decisions`, with body `{ "outcome": "READY" | "NEEDS_INFORMATION", "missingItems": [ ... ] }`.
  - Validate the body with Bean Validation: `NEEDS_INFORMATION` needs at least one item, and `READY` must have none (a class-level constraint).
  - Pass the decision to `ClaimReviewService.decide` (task 07). On success, return the updated claim.
  - Errors use RFC 9457 Problem Details:
    - 404 for an unknown claim
    - 409 when the claim isn't `Pending Review`, or a decision was already made
    - 400 for an invalid body
  - The missing items (`MissingItem` enum: incident description, incident date, location, category)
    are exposed with display labels so the UI can draw the checklist.
- **UI:**
  - `ClaimsList.tsx` and `ClaimDetail.tsx`: add label colours, and status-filter options, for `Pending Information` and `Pending Review`.
  - `ClaimDetail.tsx`: when the status is `Pending Review`, show a review panel in place of the disabled Edit button, with:
    - **Ready for processing**
    - **Request more information**: one checkbox per missing item, plus a submit button

    After a decision, reload the claim.
- **Tests:**
  - **REST** (GreenMail-backed; delete created claims afterwards):
    - `READY` moves the claim to `In Process` and sends exactly one thank-you email
    - `NEEDS_INFORMATION` moves it to `Pending Information`, and the email lists exactly the ticked items
    - each 409, 400 and 404 case
  - **Playwright:**
    - the labels and filters for both new statuses
    - the review panel shows only for `Pending Review`
    - clicking Ready changes the status
    - requesting two items changes the status to `Pending Information`

## Files/Areas

- `src/main/java/org/parasol/resources/ClaimReviewResource.java` (new), plus request/response records
- `src/main/webui/src/app/components/ClaimsList/ClaimsList.tsx`, `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`
- `src/test/java/org/parasol/resources/`, `src/test/java/org/parasol/ui/`

## Key Points

- The API has no authentication (it's a demo app). Note this in the docs.
- Keep the existing statuses (`New`, `In Process`, `Processed`, `Denied`) unchanged.
- The review panel replaces the disabled Edit button only for `Pending Review` claims. Other claims render exactly as they do today.

## Done When

- [ ] The review endpoint exists, is validated, and returns Problem Details for every error case. `/q/openapi` lists it.
- [ ] Both new statuses have labels and filters, and the review panel drives both decisions.
- [ ] All the REST and Playwright tests listed above pass, and the frontend builds.