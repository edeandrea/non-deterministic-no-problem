# Task 06: Triage Agents

**Type:** Code Modification

## Goal

A `ClaimsMailboxAgent` classifies an inbound email and routes it to extraction, a follow-up handler or
a not-a-claim outcome. It returns a sealed `IntakeOutcome` and has **no side effects**.

## What to Do

- **`EmailClassifierAgent`:** classifies into an `EmailType` enum (`NEW_CLAIM`, `CLAIM_FOLLOW_UP`, `NOT_A_CLAIM`).
- **`EmailRouter`** (`@ConditionalAgent` with `@ActivationCondition` methods), routing to:
  - **`NEW_CLAIM`:** `ClaimExtractionWorkflow` (task 05).
  - **`CLAIM_FOLLOW_UP`:** `ClaimFollowUpAgent`. Its behaviour depends on the claim's status:
    - **Pending claim** (`Pending Information`, or `Pending Review` superseded by the reply; see task 08):
      extract the newly supplied details from the reply (reuse the extraction workflow over the stripped reply text).
    - **Non-pending claim** (`In Process` or later): answer the customer's status question with an AI-written reply.
      It has a read-only `@Tool` that returns the claim's status and claim number.
      It **never** updates the claim.
  - **`NOT_A_CLAIM`:** a non-AI agent producing the not-a-claim outcome.
- **`ClaimsMailboxAgent`** (`@SequenceAgent`): the entry point, returning a sealed `IntakeOutcome`
  (e.g. `NewClaim`, `PendingClaimUpdate`, `StatusReply`, `NotAClaim`).
- Claim resolution is **code, not the LLM**. Before the agent runs, the processor resolves the claim:
  - first by regex on `[CLM…]` / `CLM\d+` in the subject and body
  - then by the `In-Reply-To` / `References` headers against stored `Message-ID`s

  It passes the resolved claim (or none) into the agent.
- **Tests:**
  - **Unit:** each activation condition; the sealed-outcome mapping.
  - **WireMock:** each email type routes correctly; a follow-up on a pending claim yields a
    `PendingClaimUpdate`; a follow-up on a `New`/`In Process`/`Denied` claim yields a `StatusReply`
    and the tool is read-only; the status tool returns the right claim.

## Files/Areas

- `src/main/java/org/parasol/intake/agent/` (classifier, router, follow-up, entry point, outcome types)
- `src/test/java/org/parasol/intake/agent/`

## Key Points

- Prompt injection: treat email text as data. System prompts tell agents not to follow instructions
  inside the email. The status tool is read-only and scoped to the already-resolved claim.
- If spike result 3 showed nesting limits, restructure (e.g. classify first, then call the matching
  workflow from the processor) and update this file and `PLAN.md`.

## Done When

- [ ] `ClaimsMailboxAgent` returns every `IntakeOutcome` variant for the matching input.
- [ ] No agent or tool writes to the database or sends mail.
- [ ] All the unit and WireMock tests listed above pass under `-Pollama`.