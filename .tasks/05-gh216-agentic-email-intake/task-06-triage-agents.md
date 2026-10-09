# Task 06: Triage Agents

**Type:** Code Modification

> **Branch and PR (user decision, 2026-10-09):** no branch of its own. Build this as a **second commit on
> `gh216/05-extraction-agents`**, in task 05's draft [#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235).
> Mark the PR ready once this commit lands; it's squash-merged as one commit for both tasks.

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
*2026-10-09 note (task 06b): 1.2.0 was released and adopted; nothing in this task changes for it.*

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

- [x] `ClaimsMailboxAgent` returns every `IntakeOutcome` variant for the matching input, and the matched claim always wins over the label.
  *(`ClaimsMailboxAgentTests`: all five variants; `aMatchedPendingClaimIsUpdatedWhateverTheClassifierSays`,
  `aMatchedInProcessClaimClassifiedAsANewClaimGetsAStatusReply`.)*
- [x] Routing uses typed, mutually exclusive, side-effect-free `@ActivationCondition`s; the unmatched not-a-claim / follow-up outcomes come from a non-LLM agent.
  *(`EmailRouterTests`, 25 cases; `UnmatchedEmailAgentTests`; the unmatched WireMock tests make one model call each.)*
- [x] Every AI agent carries both opt-outs, and every leaf belongs to `ClaimsMailboxAgent` only.
  *(`ClaimExtractionWorkflowEntryPoint` is deleted; `ClaimsMailboxAgentEntryPoint` injects the only root.)*
- [x] No agent or tool writes to the database or sends mail.
  *(The status tool reads only its `InvocationParameters`; no agent injects a repository, mailer or entity.)*
- [x] All the unit and WireMock tests listed above pass under `-Pollama`.
  *(2026-10-09: the 96 intake agent and model tests pass under default, `-Pollama` and `-Pollama-openai`.)*

## Outcome

Built 2026-10-09 as the second commit on `gh216/05-extraction-agents`, in
[#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235) with task 05 (user decision, 2026-10-09).
quarkus-flow 1.2.0 isn't out (latest 1.2.0.CR3). Of its issues, #1040 closed 2026-10-08, and #1056 and #1058 closed
2026-10-09 (fixed for the 1.2.0 milestone, via #1065 and #1059); #1013 is still open. Nothing in this task changes
for it: none of those touch the triage agents, and the workarounds stay until a release is usable.

- **Packages:** `intake.agent` holds the root `ClaimsMailboxAgent`, `EmailRouter` and the package-private `EmailRoute`;
  `intake.agent.triage` holds the classifier, follow-up agent, `ClaimStatusTools`, `UnmatchedEmailAgent` and
  `ClaimResolver`; `intake.model` gains `IntakeOutcome`, `MatchedClaim`, `EmailType`, `PendingClaim` and
  `ClaimResolution`.
- **`ClaimsMailboxAgent.process(correspondence, matchedClaim, extractedSoFar, requestedItems, sentDate,
  invocationParameters)`**, a `@SequenceAgent` (classifier → router). No `@MemoryId`, no `MonitoredAgent` yet (task 11
  adds it, with the dev-only retention setting). The names match `ClaimExtractionWorkflow`'s, so the nested workflow
  reads them from the scope.
- **No matched claim is `MatchedClaim.none()`, not `null`** (deviation from "`null` when there's no match"): the
  agentic scope throws `MissingArgumentException` for a `null` input, as task 05 found for `extractedSoFar`. Its
  `isMatched()`/`isPending()` are `@JsonIgnore`d, so a checkpoint holds only the two strings (round-trip tested).
- **One routing rule, three conditions.** `EmailRoute.route(matchedClaim, emailType)` decides; each
  `@ActivationCondition` is `route(...) == X`, so exactly one is true for any input by construction (every condition is
  evaluated, spike Q3). `EmailRouterTests` covers every matched status (both pending ones, the seeded `New`, `In Process`,
  `Processed`, `Denied`, and case) × every `EmailType`.
- **The router's `@Output` reads the agentic scope** rather than one parameter per branch output: only one branch runs,
  and the others' keys would throw `MissingArgumentException`. It turns the extraction into `PendingClaimUpdate` for a
  matched claim and `NewClaim` otherwise.
- **The extraction's "uncorrected date → missing" recovery moved to the root too.** A nested workflow runs in the root's
  agentic scope, and the scope's error handler is the root's (`PlannerBasedInvocationHandler.currentAgenticScope`), so
  `ClaimExtractionWorkflow`'s own `@ErrorHandler` doesn't apply under the root. `ClaimsMailboxAgent`'s `@ErrorHandler`
  delegates to it; `aDateTheModelNeverCorrectsIsStillTreatedAsMissingUnderTheRoot` covers it.
- **The status tool takes no argument from the model** (deviation: the task said "scoped to the matched claim" without
  a mechanism). `ClaimStatusTools.findClaimStatus(InvocationParameters)` reads the matched claim from the invocation
  parameters, which the agentic scope passes to `ClaimFollowUpAgent` from its execution context, never as a prompt
  variable. Its tool schema has no properties (`theStatusToolTakesNoArgumentFromTheModel`), and an email asking for
  another claim still gets the matched one (`aFollowUpOnAClaimPastTheIntakeIsAnsweredFromTheMatchedClaimOnly`). It reads
  the status the claim was matched with, not the database: the intake already loaded the claim, and this way the tool
  can't touch anything.
  - **quarkus-langchain4j 1.14.1 gap:** its build check (`AgenticProcessor#validateAgenticParameterTypes`) skips
    `AgenticScope` and `@MemoryId` parameters but not `InvocationParameters`, so the follow-up agent's parameter failed
    the build ("No agent provides an output key named 'invocationParameters'"). The root therefore takes an
    `InvocationParameters` argument too, always `ClaimStatusTools.scopedTo(matchedClaim)` (comment in the code). Filed
    as [quarkiverse/quarkus-langchain4j#2950](https://github.com/quarkiverse/quarkus-langchain4j/issues/2950), with a
    reproducer in [edeandrea/quarkus-langchain4j-reproducers](https://github.com/edeandrea/quarkus-langchain4j-reproducers)
    and the fix in [#2951](https://github.com/quarkiverse/quarkus-langchain4j/pull/2951). Built locally, the fix lets a
    root fill the parameters itself in a `@BeforeCall` (`agenticScope.writeExecutionContext(...)`) and the sub-agent's
    tool receives them, so once it's released the root can drop the argument.
- **`ClaimFollowUpAgent` writes the whole reply body**, greeting and sign-off included, because `sendTextReply` adds
  neither. The sign-off reads `parasol.claims-department.*` through Qute's `config:` namespace, like the other emails.
- **The enum meanings come from the enums, not the prompts** (review decision, 2026-10-09). Quarkus already appends
  LangChain4j's format instructions to the user message, built from the return type, so the prompts list no values:
  - `EmailType`'s constants carry LangChain4j's `@Description`; `EnumOutputParser` sends them as
    `NEW_CLAIM - the email reports …` (`theClassifierRequestListsEveryEmailTypeWithItsDescriptionFromTheEnum`). The
    "no Qute binding on a domain enum" rule from task 05 doesn't apply: `EmailType` exists only as the classifier's
    answer, and the description is LangChain4j's, not a template binding.
  - `ClaimResolution.Kind`'s meanings sit in a `@Description` on the record's `kind` field: `PojoOutputParser` prints
    field descriptions and the constant names, but no per-constant descriptions
    (`theRequestCarriesEveryKindWithWhatItMeansFromTheRecord`). `theKindDescriptionSaysWhenToUseEveryKind` fails if a new
    `Kind` isn't explained there. `@Description` targets `FIELD`/`TYPE`, so on a record component it lands on the field,
    which is what the parser reads.
- **`ClaimResolver`** is a plain `@RegisterAiService` (no `@Agent`, so `modelName` on the annotation is all it needs).
  It takes `PendingClaim`s (number, summary, requested items) and returns a `ClaimResolution`.
  `ClaimResolution.limitToOfferedClaims` turns an `EXISTING` answer for a claim that wasn't offered into `UNSURE`; task
  08's `resolveClaim` step must call it (`anInventedClaimNumberIsRejected`).
- **`ClaimsMailboxAgentEntryPoint`** (package-private, `@Unremovable`) replaces `ClaimExtractionWorkflowEntryPoint`, for
  the same build-validation reason; task 08 deletes it once the intake workflow injects the root.
- **Tests:** the WireMock profile moved out of `ClaimExtractionWorkflowTests` into `IntakeAgentsTestProfile`, so the
  three WireMock classes share one app boot, plus a small `ChatStubs` helper (stubs on the last message, tool-call
  stubs). Every WireMock request is checked to be system + one user message, on the pinned model name.
- **`IntakeOutcome` has no `permits` clause** (review decision): its variants are nested in the same file, so Java infers
  it, and a third copy of the five names would add nothing. The list that can drift is `@JsonSubTypes` (a variant
  missing from it compiles, then fails when Flow reads a checkpoint back), so `IntakeOutcomeTests` checks it against
  `getPermittedSubclasses()`. Seen failing once with `NoMatchingClaim` removed from `@JsonSubTypes`.
