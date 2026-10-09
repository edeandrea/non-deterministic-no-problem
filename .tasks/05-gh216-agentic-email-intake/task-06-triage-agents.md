# Task 06: Triage Agents

**Type:** Code Modification

> **Branch and PR (user decision, 2026-10-09):** no branch of its own. Build this as a **second commit on
> `gh216/05-extraction-agents`**, in task 05's draft PR. Mark the PR ready once this commit lands; it's squash-merged
> as one commit for both tasks.

## Goal

A `ClaimsMailboxAgent` root workflow classifies an inbound email and routes it, using the claim the
intake starter already matched in code, to extraction, a status answer, or a not-a-claim / no-matching-claim
outcome. It returns a sealed `IntakeOutcome` and has **no side effects**. **The matched claim wins.**

*Updated after the Flow verdict (task 01b, option b1):* the root is invoked as **one step** of the intake workflow
(task 08), not by a processor that catches a suspension. Flow translates each composite into its own generated
sub-workflow, and the review pause lives in the outer workflow, so the agents never suspend and nothing here touches
an agentic scope store.

**Check first: quarkus-flow 1.2.0** (`PLAN.md` → Execution Steps → 2a; expected ~2026-10-09). If it's out, re-run the
reproducers and re-adjust this task and the earlier ones before building. Nothing here depends on it directly; the trace shape of the generated sub-workflows changes with #1065 (task 11).

## What to Do

- **Claim resolution is code, not the LLM, and happens before the workflow** (design, Workflow steps 3–4).
  The starter (task 08) resolves the claim:
  - first by regex on `[CLM…]` / `CLM\d+` in the subject and body
  - then by the `In-Reply-To` / `References` headers against stored `Message-ID`s
  - then checks the sender: only the claim's own email address may match it. A wrong sender gets the
    "no matching claim" reply from the workflow's `replyNoMatchingClaim` step, and the agents aren't run.

  It passes the resolved claim, or none, into the root as a JSON-safe `MatchedClaim` record (claim number,
  status, plus the history the agents need), `null` when there's no match. No `Optional`, no `java.time`.
- **`ClaimResolver`** (LLM, `@RegisterAiService` with both opt-outs and `@ModelName("claim-intake")`; **not** an
  agent and not part of `ClaimsMailboxAgent`, so the proven topology is unchanged): given the email and the sender's
  pending claims (claim number, short summary, requested items), returns `ClaimResolution` (`EXISTING` + claim number,
  `NEW_INCIDENT`, or `UNSURE`). Called by task 08's `resolveClaim` step, which accepts `EXISTING` only for a number in
  the list it passed (anything else → `UNSURE`). Unit tests with WireMock: picks the right claim of two; a new
  incident; unsure; an invented claim number is rejected by the step.
- **`EmailClassifierAgent`** (LLM): classifies into an `EmailType` enum (`NEW_CLAIM`, `CLAIM_FOLLOW_UP`, `NOT_A_CLAIM`).
- **`EmailRouter`** (`@ConditionalAgent`) routes on the **matched claim first**, then on the type:

  | Matched claim | Email type | Route | Outcome |
  |---|---|---|---|
  | pending (`Pending Information`, or `Pending Review` superseded by the reply; task 08) | any | `ClaimExtractionWorkflow` (task 05) | `PendingClaimUpdate` |
  | any other status (`New`, `In Process`, `Processed`, `Denied`, …) | any | `ClaimFollowUpAgent` | `StatusReply` |
  | none | `NEW_CLAIM` | `ClaimExtractionWorkflow` | `NewClaim` |
  | none | `NOT_A_CLAIM` | `UnmatchedEmailAgent` (non-LLM) | `NotAClaim` |
  | none | `CLAIM_FOLLOW_UP` | `UnmatchedEmailAgent` (non-LLM) | `NoMatchingClaim` |

  - The three-way router, with a non-LLM branch and a tool-calling branch, is proven under Flow by follow-up spike 5
    before this task starts.
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
  (`NewClaim`, `PendingClaimUpdate`, `StatusReply`, `NotAClaim`, `NoMatchingClaim`).
  - Method: `process(…)` with the inputs below. **No `@MemoryId` and no `AgenticScopeAccess`**: there's no
    suspension or resume inside the agents any more, and the review is the outer workflow's job (task 08). If
    the build requires a memory id for a `@SequenceAgent`, use the inbound `Message-ID`.
  - Keep the arguments few and JSON-safe (the correspondence, `MatchedClaim`, the fields extracted so far,
    the requested items, the sent date as an ISO string). Flow checkpoints them with the agentic scope after
    every sub-agent.
  - Inject it into the intake workflow bean (task 08) in `src/main`; until then, inject it from a minimal
    package-private bean so build-time output-key validation passes (spike Q3).
    - *From task 05 (2026-10-08):* that bean must be **`@Unremovable`**, or ArC drops it as unused and the validation
      fails again (see `ClaimExtractionWorkflowEntryPoint`). **Delete `ClaimExtractionWorkflowEntryPoint` in this
      task**, once `ClaimExtractionWorkflow` is a sub-agent of `ClaimsMailboxAgent`, so it doesn't stay a second root.
  - *From task 05:* `ClaimExtractionWorkflow.extractClaim(correspondence, extractedSoFar, requestedItems, sentDate)`
    reads those four from the scope, so the root needs parameters with **exactly those names** (`IncidentDetails
    extractedSoFar`, never `null`: a new claim passes an all-`null` one; `Collection<MissingItem> requestedItems`, the
    same type, since the scope passes a value through only when it's an instance of the declared type). Its
    `@ErrorHandler` treats a never-corrected date as missing; a root `@ErrorHandler` would replace it for the agents
    under the root, so check that nesting keeps it (or move the same recovery to the root).
  - The review outcomes (`ReviewReady`, `ReviewNeedsInformation`) are **not** agent outcomes any more: the
    reviewer's decision is routed by the outer workflow's `switch` (task 08).
- **Opt-outs:** every AI agent interface here (`EmailClassifierAgent`, `ClaimFollowUpAgent`) carries
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`
  and `@ModelName("claim-intake")`, as in task 05. *(Task 05: put `@ModelName` on the `@Agent` method, since on the
  interface it's silently ignored, and also set `@RegisterAiService(modelName = "claim-intake")`, or `-Pollama` fails
  the build asking for an ambiguous default chat model. Import the nested `NoRetrievalAugmentorSupplier` /
  `NoChatMemoryProviderSupplier` rather than qualifying them. Name agent methods verb-first, e.g.
  `classifyEmail`: the method name ends up in span and Langfuse dataset names.)*
- **Tests:**
  - **Unit:** each activation condition, including that exactly one is true for every
    (matched claim status × email type) combination; the sealed-outcome mapping; `UnmatchedEmailAgent`; every
    `IntakeOutcome` variant round-trips through the Quarkus `ObjectMapper`.
  - **WireMock** (stubs match on the **last message only**):
    - each unmatched email type routes correctly
    - **matched claim wins:** a matched pending claim classified `NOT_A_CLAIM` or `NEW_CLAIM` still yields
      `PendingClaimUpdate`; a matched `In Process` claim classified `NEW_CLAIM` yields `StatusReply`
    - an unmatched `CLAIM_FOLLOW_UP` yields `NoMatchingClaim`, and no extraction or follow-up LLM call is made
    - a follow-up on a `New`/`In Process`/`Denied` claim yields a `StatusReply`, and the tool is read-only
    - the status tool returns the matched claim
    - each request carries exactly one user message (no chat history, no RAG content)

## Files/Areas

- `src/main/java/org/parasol/intake/agent/`: the root `ClaimsMailboxAgent` and `EmailRouter`
- `src/main/java/org/parasol/intake/agent/triage/`: the classifier, follow-up agent and its status tool,
  unmatched-email agent and `ClaimResolver`
- `src/main/java/org/parasol/intake/model/`: `IntakeOutcome` and its variants, `MatchedClaim`, `EmailType` (next to
  task 05's `IncidentDetails` / `ClaimExtraction`)
- the matching test packages
- *(Package layout decided in task 05, 2026-10-09: one package per agent group, records in `intake.model`.)*

## Key Points

- Prompt injection: treat email text as data. System prompts tell agents not to follow instructions
  inside the email. The status tool is read-only and scoped to the already-resolved claim.
- **Each leaf agent belongs to exactly one root** (spike Q16c): leaf agents are CDI singletons, and a second
  root that lists them leaks its listeners into them and mangles their agent ids. `ClaimsMailboxAgent` is the
  only root in the app.
- Every `IntakeOutcome` variant must round-trip through the Quarkus `ObjectMapper` (Flow persists step data with
  it): use a Jackson-polymorphic sealed interface (`@JsonTypeInfo`/`@JsonSubTypes`) and test the round trip.
- Agents have no side effects. The intake workflow's steps send every reply, including the no-matching-claim one.
- **The status answer is sent as plain text** with `IntakeReplySender.sendTextReply` (task 04). The sender adds the
  subject prefix, threading headers and `Auto-Submitted`, but no greeting or sign-off: whatever the body holds is
  what the customer gets.

## Done When

- [ ] `ClaimsMailboxAgent` returns every `IntakeOutcome` variant for the matching input, and the matched claim always wins over the label.
- [ ] Routing uses typed, mutually exclusive, side-effect-free `@ActivationCondition`s; the unmatched not-a-claim / follow-up outcomes come from a non-LLM agent.
- [ ] Every AI agent carries both opt-outs, and every leaf belongs to `ClaimsMailboxAgent` only.
- [ ] No agent or tool writes to the database or sends mail.
- [ ] All the unit and WireMock tests listed above pass under `-Pollama`.
