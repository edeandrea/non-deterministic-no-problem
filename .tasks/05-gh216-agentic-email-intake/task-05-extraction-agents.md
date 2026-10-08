# Task 05: Claim Extraction Agents

**Type:** Code Modification

## Goal

A `ClaimExtractionWorkflow` (`@ParallelAgent`) turns a claim's whole correspondence into structured
claim details, a summary and a sentiment, with JSON parsing, length and date rules enforced by
guardrails. The same workflow handles a new claim email and every reply on a pending claim. The agents
are stateless: everything they need is passed in.

*Updated after the Flow verdict (task 01b, option b1):* the agent composition runs as one step of the intake
workflow (task 08), so `ClaimExtractionWorkflow` becomes a generated Flow sub-workflow whose two halves run as a
real `fork`. **No `@ParallelExecutor`** (it breaks every run under Flow, quarkus-flow#1057).

## What to Do

- Create the sub-agents (model `claim-intake`, `@ModelName("claim-intake")`). **Every agent interface** is
  annotated
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`.
  Without it, each agent silently gets the policy Easy RAG retriever and one chat memory shared across all
  runs (`"default"`), so one customer's email leaks into the next prompt; the root's `@MemoryId` doesn't
  change that (spike Q6, Q19).
  - **`ClaimSummaryAgent`:** a factual summary, at most 5000 characters.
  - **`ClaimSentimentAgent`:** the claimant's sentiment, at most 5000 characters.
  - **`IncidentDetailsAgent`:** returns an `IncidentDetails` record. Every field is a nullable `String`
    or enum; **no `LocalDate`, `LocalTime` or `Optional`**. Flow checkpoints the agentic scope after every
    sub-agent (task 01b, A4) with Quarkus's `ObjectMapper`, which probably handles `java.time`, but no spike proved
    it, and the old agentic codec didn't (spike Q13). Keep ISO strings as the safe default; the round-trip test below
    settles it, and the rule can be relaxed only if that test proves `LocalDate` survives:
    - `description`
    - `incidentDate`: ISO-8601 `yyyy-MM-dd`
    - `incidentTime`: ISO-8601 `HH:mm`, only if stated (optional; see Key Points)
    - `location`
    - `category` (`ClaimCategory` or null)
    - `policyNumber` (only if stated in the email)
    - `answeredItems`: the subset of the **requested items** (see inputs) that the customer's newest
      reply actually supplies (`Set<MissingItem>`, empty if none)

    `OTHER` means the incident was described but fits no category; `null` means it can't be told.
- **Inputs (explicit, no chat memory):** the intake workflow's `runAgents` step (task 08) passes the claim history on every run, and the
  agents read it from the scope:
  - the combined correspondence (the first email plus every reply, dated and separated; the newest
    reply marked as such), capped at `IntakeConfig`'s max characters
  - the fields extracted so far (as ISO strings)
  - the requested items: what we last asked the customer for, **including the items a reviewer ticked**
    under Needs more information
  - the newest email's sent date, so relative dates ("last night") resolve correctly

  Extraction re-runs over the whole thread each time. There is no separate follow-up extraction path:
  a reply on a pending claim goes through this same workflow (design, Workflow step 5).
- Combine them with `ClaimExtractionWorkflow` (`@ParallelAgent` + `@Output`) into a `ClaimExtraction` record
  (also JSON-safe: strings, enums and sets only).
  - **Do not declare a `@ParallelExecutor`.** Under Flow, `FlowParallelAgentService.executor` throws
    `UnsupportedOperationException` on the first run (build and boot pass;
    [quarkiverse/quarkus-flow#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057)). Flow runs the fork on
    Quarkus's `ManagedExecutor`. Keeping the run's conversation id across the fork is task 11's Flow adapter, not the
    agents' job.
- Add output guardrails (CDI beans; constructor injection works, spike Q5):
  - **JSON:** `IncidentDetailsAgent` gets a guardrail extending `JsonExtractorOutputGuardrail<IncidentDetails>`
    (the project's existing pattern, e.g. `SessionSentimentGuardrail`), so malformed or fenced JSON is
    extracted or reprompted. A parse failure that escapes the guardrail aborts the whole workflow (spike Q4).
  - **Length:** summary and sentiment over 5000 characters → reprompt asking for a shorter text.
  - **Dates:** an `incidentDate` that isn't a valid ISO date, or is in the future (relative to the sent
    date), is rejected (reprompt). If it still fails after the retry limit, treat the date as missing.
    The same applies to an invalid `incidentTime`.
- Add a pure function `missingInformation(ClaimExtraction, Set<MissingItem> requestedByReviewer, boolean replyIsBlank)`
  that returns the set of missing items:
  - any of description, date, location or category that is absent (`OTHER` is not missing)
  - **plus every reviewer-ticked item not in `answeredItems`** (gap 8): after Needs more information, the
    claim stays incomplete until the customer answers the ticked items, even though those fields
    already had values
  - a blank newest reply (after quote stripping) answers nothing, whatever the LLM says
- The workflow's `applyOutcome` step (task 08) converts the ISO strings to `LocalDate`/`LocalTime` when it persists the claim.
- **Tests:**
  - **Unit:**
    - `@Output` combination
    - `missingInformation` for each combination, including `OTHER` vs null, reviewer-ticked items
      answered vs not, and a blank reply with ticked items (stays missing)
    - the JSON guardrail (fenced JSON, malformed JSON → reprompt)
    - the length guardrail at 5000 and 5001 characters
    - the date rules (future date, invalid ISO string, invalid time)
    - an `IncidentDetails`/`ClaimExtraction` value round-trips through the injected Quarkus `ObjectMapper` (what
      Flow persists step data with)
  - **WireMock agent tests** (stubs match on the **last message only**; spike Q6):
    - complete extraction
    - extraction with missing fields
    - a relative date resolved against the sent date
    - a category that maps to `OTHER`
    - a reply on a pending claim, extracted over the combined correspondence
    - an **empty reply** after a reviewer ticked items: the ticked items stay missing
    - each request carries exactly one user message (no chat history, no RAG content)
    - the two halves really run in parallel: both requests reach WireMock before either response (a delayed stub)

## Files/Areas

- `src/main/java/org/parasol/intake/agent/` (new)
- `src/test/java/org/parasol/intake/agent/` (new)

## Key Points

- Bodies sent to the LLM are capped at `IntakeConfig`'s max characters (the stored body is not truncated).
- **Incident time:** now in the revised design too (optional, never required). Originally a deliberate difference: the design listed the extracted fields as
  description, date, location, category and stated policy number. This task also extracts the incident
  time when stated, because #213 adds an optional `incidentTime` to `Claim`. It is **not** a required
  detail, never makes a claim incomplete, and is never asked for. Recorded in `PLAN.md` → Caveats.
- **Each leaf agent belongs to exactly one root** (spike Q16c): these agents are only ever sub-agents of
  `ClaimsMailboxAgent` (task 06). Don't reuse them in another agentic system.
- Agent tests may call the agents directly (no workflow). The run inside Flow is covered by task 08.
- **Every entry agent must be injected in `src/main`** (spike Q3). Until task 06 nests this workflow under
  the root, `ClaimExtractionWorkflow` is an entry agent; if build validation fails on its inputs, inject it
  from a minimal package-private bean and remove that in task 06, so it doesn't become a second root.
- The LLM is mocked in every test. CI only has a stub key.
- **`MissingItem` already exists** (task 04): `org.parasol.intake.MissingItem`, with `label()` and `find` / `fromValue`
  (a label or a constant name). `missingInformation` returns a `Set` of it; the reply templates list the items in the
  set's iteration order.

## Done When

- [ ] Every extraction agent carries both opt-outs and `@ModelName("claim-intake")`; requests carry one user message and no RAG content.
- [ ] `ClaimExtractionWorkflow` returns a JSON-safe `ClaimExtraction` (ISO-string dates, no `Optional`), and declares **no** `@ParallelExecutor`.
- [ ] `missingInformation` exists and accounts for reviewer-ticked items and blank replies.
- [ ] The JSON, length and date guardrails are in place.
- [ ] All the unit and WireMock tests listed above pass under `-Pollama`, including the empty-reply test.
