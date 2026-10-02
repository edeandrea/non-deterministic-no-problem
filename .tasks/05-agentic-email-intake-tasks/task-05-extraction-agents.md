# Task 05: Claim Extraction Agents

**Type:** Code Modification

## Goal

A `ClaimExtractionWorkflow` (`@ParallelAgent`) turns an email into structured claim details, a summary
and a sentiment, with length limits enforced by guardrails.

## What to Do

- Create the sub-agents (model `claim-intake`, no RAG; see spike result 6):
  - **`ClaimSummaryAgent`:** a factual summary, at most 5000 characters.
  - **`ClaimSentimentAgent`:** the claimant's sentiment, at most 5000 characters.
  - **`IncidentDetailsAgent`:** returns an `IncidentDetails` record:
    - `description` (nullable)
    - `incidentDate` (`LocalDate`, nullable)
    - `incidentTime` (`LocalTime`, nullable)
    - `location` (nullable)
    - `category` (`ClaimCategory` or null)
    - `policyNumber` (nullable, only if stated in the email)

    The agent gets the email's sent date so it can resolve relative dates ("last night").
    `OTHER` means the incident was described but fits no category; `null` means it can't be told.
- Combine them with `ClaimExtractionWorkflow` (`@ParallelAgent` + `@Output`) into a `ClaimExtraction` record.
- Add output guardrail(s):
  - **Length:** summary and sentiment over 5000 characters → reprompt asking for a shorter text.
  - **Dates:** an incident date in the future is rejected (reprompt). If it still fails after the
    retry limit, treat the date as missing.
- Add a pure function `missingInformation(ClaimExtraction)` that returns the set of missing items
  (description, date, location, category). `OTHER` is not missing.
- **Tests:**
  - **Unit:** `@Output` combination; `missingInformation` for each combination, including `OTHER`
    vs null; the length guardrail at 5000 and 5001 characters; the future-date rule.
  - **WireMock agent tests:** complete extraction; extraction with missing fields; a relative date
    resolved against the sent date; a category that maps to `OTHER`.

## Files/Areas

- `src/main/java/org/parasol/intake/agent/` (new)
- `src/test/java/org/parasol/intake/agent/` (new)

## Key Points

- Bodies sent to the LLM are capped at `IntakeConfig`'s max characters (the stored body is not truncated).
- If spike result 5 showed guardrails don't run on agents, put the length and date checks in the
  processor (task 08) instead, and update this task's Done When.
- The LLM is mocked in every test. CI only has a stub key.

## Done When

- [ ] `ClaimExtractionWorkflow` returns a `ClaimExtraction`, and `missingInformation` exists.
- [ ] The guardrails (or the documented fallback) enforce the length and future-date rules.
- [ ] All the unit and WireMock tests listed above pass under `-Pollama`.