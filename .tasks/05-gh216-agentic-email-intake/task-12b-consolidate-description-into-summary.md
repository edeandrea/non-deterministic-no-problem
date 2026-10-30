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

- Drop `IncidentDetails.description` as its own extracted field.
- `ClaimExtractionRules.isAbsent`'s `INCIDENT_DESCRIPTION` case checks `extraction.summary()` for blankness instead
  of `details.description()` — this needs the whole `ClaimExtraction`, not just `details`, so `findMissingItems`'s
  signature (or `isAbsent`'s) changes.
- `IncidentDetailsAgent`'s prompt no longer asks for a description.
- `ClaimFields.extractedSoFar`/`merge` simplify once there's no separate slot to shuttle between `Claim` and
  `IncidentDetails`.
- Every test's mocked LLM JSON across the intake suite needs the `description` key dropped from the incident-details
  stub, and the separate summary stub made the one that matters for completeness (`COMPLETE_DETAILS` and the
  `details(...)` helper in `IntakeFlowTestBase`, and anything built on them, including the Playwright
  `ClaimReviewPanelPageTests` and `RoundcubeClaimIntakeE2ETests` (task 12), which carry their own copies).
- Update the design doc and `CLAUDE.md` wherever they describe the four required details as "description, incident
  date, location, category" with `description` as its own extracted field.
- Decide whether `MissingItem.INCIDENT_DESCRIPTION`'s name/label should change too, now that what it actually checks
  is the summary, not a separately-extracted description. (Leaning toward leaving the enum/label alone — "incident
  description" is still what a customer-facing missing-information email should ask for, even if the check now reads
  the summary.)

## Files/Areas

- `src/main/java/org/parasol/intake/model/IncidentDetails.java`
- `src/main/java/org/parasol/intake/agent/extraction/ClaimExtractionRules.java`, `IncidentDetailsAgent.java`
- `src/main/java/org/parasol/intake/workflow/outcome/ClaimFields.java`
- `src/test/java/org/parasol/intake/IntakeFlowTestBase.java` (`COMPLETE_DETAILS`, `details(...)`) and every test built
  on them across `org.parasol.intake.**`
- `docs/design/email-claim-intake.md`, `CLAUDE.md`

## Key Points

- `IncidentDetails.description` has no other consumer today besides the one completeness-check branch — confirmed by
  reading `ClaimExtractionRules.isAbsent` and `ClaimFields` directly, not assumed.
- This doesn't change anything about task 10's UI design: the "Description" field there already reads/writes
  `claim.summary` either way (there is, and after this task still is, no `description` column on `Claim`).

## Done When

- [ ] `IncidentDetails.description` is gone; `IncidentDetailsAgent` no longer extracts it
- [ ] `ClaimExtractionRules` checks the summary for the `INCIDENT_DESCRIPTION` completeness case
- [ ] `ClaimFields.extractedSoFar`/`merge` are simplified accordingly
- [ ] Every affected test fixture across `org.parasol.intake.**` is updated and the suite passes
- [ ] The design doc and `CLAUDE.md` are updated
