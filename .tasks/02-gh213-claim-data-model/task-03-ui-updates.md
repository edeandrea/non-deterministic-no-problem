# Task 03: Update the UI for the New Claim Fields

**Type:** Code Modification

## Goal

The claims list and claim detail pages display the new fields correctly, and the Playwright tests
cover them.

## What to Do

- `ClaimDetail.tsx`: the "Date and time" section renders `incident_date`, plus `incident_time` when
  present, in a readable format. Show 'Not processed yet' when both are absent.
- `ClaimsList.tsx`: add an "Other" option to the category filter, next to "Single vehicle", "Multiple
  vehicle" and "Theft". Filtering compares the display label coming from the API.
- Update the Playwright tests:
  - `ClaimsListPageTests`: the category cell equals the enum's display label.
  - `ClaimsDetailPageTests`: assert the rendered incident date (and time) of the claim under test.

## Files/Areas

- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`
- `src/main/webui/src/app/components/ClaimsList/ClaimsList.tsx`
- `src/test/java/org/parasol/ui/ClaimsListPageTests.java`, `src/test/java/org/parasol/ui/ClaimsDetailPageTests.java`

## Key Points

- The JSON property names come from the snake_case naming strategy on `Claim`.
- `Chat.tsx` sends `inceptionDate` to the chat, not the incident date. Leave that as is.
- Playwright tests use the Quinoa test profile `QuinoaTestProfiles.EnableAndRunTests` (which also runs the Jest suite) and a container runtime.

## Done When

- [x] The detail page shows the incident date/time for a seeded claim, verified by an updated Playwright test.
- [x] The category filter offers "Other", and the list test asserts category labels.
- [x] `./mvnw -B clean package -DskipTests -Pollama` builds the frontend without errors.
