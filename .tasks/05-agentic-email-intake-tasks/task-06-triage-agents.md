# Task 06: Triage Agents

**Type:** Code Modification

## Goal

A `ClaimsMailboxAgent` root workflow classifies an inbound email and routes it, using the claim the
processor already matched in code, to extraction, a status answer, or a not-a-claim / no-matching-claim
outcome. It returns a sealed `IntakeOutcome` and has **no side effects**. **The matched claim wins.**

## What to Do

- **Claim resolution is code, not the LLM, and happens before the workflow** (design, Workflow steps 3–4).
  The processor (task 08) resolves the claim:
  - first by regex on `[CLM…]` / `CLM\d+` in the subject and body
  - then by the `In-Reply-To` / `References` headers against stored `Message-ID`s
  - then checks the sender: only the claim's own email address may match it. A wrong sender gets the
    "no matching claim" reply from the processor and the workflow isn't run.

  It passes the resolved claim, or none, into the root as a JSON-safe `MatchedClaim` record (claim number,
  status, plus the history the agents need), `null` when there's no match. No `Optional`, no `java.time`.
- **`EmailClassifierAgent`** (LLM): classifies into an `EmailType` enum (`NEW_CLAIM`, `CLAIM_FOLLOW_UP`, `NOT_A_CLAIM`).
- **`EmailRouter`** (`@ConditionalAgent`) routes on the **matched claim first**, then on the type:

  | Matched claim | Email type | Route | Outcome |
  |---|---|---|---|
  | pending (`Pending Information`, or `Pending Review` superseded by the reply; task 08) | any | `ClaimExtractionWorkflow` (task 05) | `PendingClaimUpdate` |
  | any other status (`New`, `In Process`, `Processed`, `Denied`, …) | any | `ClaimFollowUpAgent` | `StatusReply` |
  | none | `NEW_CLAIM` | `ClaimExtractionWorkflow` | `NewClaim` |
  | none | `NOT_A_CLAIM` | `UnmatchedEmailAgent` (non-LLM) | `NotAClaim` |
  | none | `CLAIM_FOLLOW_UP` | `UnmatchedEmailAgent` (non-LLM) | `NoMatchingClaim` |

  - Route with **typed** `@ActivationCondition` methods (e.g. `(MatchedClaim matchedClaim, EmailType emailType)`).
    **Every** condition is evaluated, not only the first match (spike Q3), so the conditions must be mutually
    exclusive and cheap: pure functions over their arguments, with no I/O and no LLM call.
  - A matched email is a follow-up on that claim whatever the classifier says; the label only decides when
    nothing matched. An unmatched "follow-up" gets the one "no matching claim" reply (task 04), and nothing is
    created or changed.
- **`ClaimFollowUpAgent`** (LLM): for a matched claim in any non-pending status, answers the customer's
  status question with an AI-written reply. It has a read-only `@Tool` (`@ToolBox` CDI bean) that returns
  the claim's status and claim number, scoped to the already-matched claim. It **never** updates the claim.
  It does no extraction: pending-claim replies go through `ClaimExtractionWorkflow` (task 05).
- **`UnmatchedEmailAgent`** (non-LLM agent, as in the design's "Not-a-claim / no-matching-claim agent"):
  maps an unmatched `NOT_A_CLAIM` to `NotAClaim` and an unmatched `CLAIM_FOLLOW_UP` to `NoMatchingClaim`.
- **`ClaimsMailboxAgent`** (`@SequenceAgent`): the root and only entry point, returning a sealed `IntakeOutcome`
  (`NewClaim`, `PendingClaimUpdate`, `StatusReply`, `NotAClaim`, `NoMatchingClaim`; task 07 adds the review outcomes).
  - Method: `process(@MemoryId String messageId, …)`, memory id = the inbound `Message-ID`. The interface
    extends `AgenticScopeAccess` (task 07/08 use `getAgenticScope` / `evictAgenticScope`).
  - Keep the arguments few and JSON-safe (the correspondence, `MatchedClaim`, the fields extracted so far,
    the requested items, the sent date as an ISO string): on resume they're read back from the scope with
    `readState` and passed again (task 10).
  - Inject it into `ClaimEmailProcessor` (task 08) in `src/main`; until then, inject it from a minimal
    package-private bean so build-time output-key validation passes (spike Q3).
- **Opt-outs:** every AI agent interface here (`EmailClassifierAgent`, `ClaimFollowUpAgent`) carries
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`
  and `@ModelName("claim-intake")`, as in task 05.
- **Tests:**
  - **Unit:** each activation condition, including that exactly one is true for every
    (matched claim status × email type) combination; the sealed-outcome mapping; `UnmatchedEmailAgent`.
  - **WireMock** (stubs match on the **last message only**):
    - each unmatched email type routes correctly
    - **matched claim wins:** a matched pending claim classified `NOT_A_CLAIM` or `NEW_CLAIM` still yields
      `PendingClaimUpdate`; a matched `In Process` claim classified `NEW_CLAIM` yields `StatusReply`
    - an unmatched `CLAIM_FOLLOW_UP` yields `NoMatchingClaim`, and no extraction or follow-up LLM call is made
    - a follow-up on a `New`/`In Process`/`Denied` claim yields a `StatusReply`, and the tool is read-only
    - the status tool returns the matched claim
    - each request carries exactly one user message (no chat history, no RAG content)

## Files/Areas

- `src/main/java/org/parasol/intake/agent/` (classifier, router, follow-up, unmatched-email agent, entry point, outcome types, `MatchedClaim`)
- `src/test/java/org/parasol/intake/agent/`

## Key Points

- Prompt injection: treat email text as data. System prompts tell agents not to follow instructions
  inside the email. The status tool is read-only and scoped to the already-resolved claim.
- **Each leaf agent belongs to exactly one root** (spike Q16c): leaf agents are CDI singletons, and a second
  root that lists them leaks its listeners into them and mangles their agent ids. `ClaimsMailboxAgent` is the
  only root in the app.
- Allowlist every `IntakeOutcome` record that doesn't appear directly in an agent signature
  (`AgenticScopeSerializer.allowDeserializationType`, task 03's startup list), and include them in the startup self-test.
- Agents have no side effects. The processor sends every reply, including the no-matching-claim one.

## Done When

- [ ] `ClaimsMailboxAgent` returns every `IntakeOutcome` variant for the matching input, and the matched claim always wins over the label.
- [ ] Routing uses typed, mutually exclusive, side-effect-free `@ActivationCondition`s; the unmatched not-a-claim / follow-up outcomes come from a non-LLM agent.
- [ ] Every AI agent carries both opt-outs, and every leaf belongs to `ClaimsMailboxAgent` only.
- [ ] No agent or tool writes to the database or sends mail.
- [ ] All the unit and WireMock tests listed above pass under `-Pollama`.
