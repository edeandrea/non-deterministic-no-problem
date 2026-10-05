# Task 06: Verify the Data-Model Rework

**Type:** Verification

## Goal

Confirm the rework compiles, the claim and UI tests pass where secrets allow, and an independent
reviewer finds no gaps between code, seed data and docs.

## What to Do

- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run the claim-related suites with `-Pollama` and stub keys:
  - the new entity/generator tests
  - `ClaimResourceTests`, `ClaimsListPageTests`, `ClaimsDetailPageTests`
  - `NotificationServiceTests` (status update on the reworked entity)

  Report secret-dependent failures as such.
- Have a **fresh** reviewer check:
  - entity ↔ `import.sql` ↔ UI field names
  - every incident date after its inception date
  - no assertion on absolute claim numbers
  - docs match the code

  Re-review after fixes.
- Give the user the commands for a full `./mvnw -B clean verify` with real keys, and a manual check:
  open the claims list, filter by "Other", and open a claim detail page.

## Files/Areas

- Whole repository

## Key Points

- `ClaimsListPageTests` expects exactly 6 claims. A failure there usually means another test leaked a claim.

## Done When

- [ ] Both `test-compile` runs succeed, and the listed suites pass (or fail only for documented secret reasons).
- [ ] The fresh review has no unresolved BLOCKER or MAJOR findings.