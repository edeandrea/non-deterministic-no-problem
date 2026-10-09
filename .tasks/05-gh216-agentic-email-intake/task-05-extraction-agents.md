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

**Check first: quarkus-flow 1.2.0** (`PLAN.md` → Execution Steps → 2a; expected ~2026-10-09). If it's out, re-run the
reproducers and re-adjust this task and the earlier ones before building. Here: #1057 is fixed on `main` (PR #1068), so `@ParallelExecutor` works again in 1.2.0. Whether this workflow wants one at all is a decision for the user (Flow already runs the fork on the `ManagedExecutor`).
*2026-10-09 note (task 06b): 1.2.0 was released and adopted. #1057 is fixed, but `ClaimExtractionWorkflow` still has
no `@ParallelExecutor` (Flow forks on the `ManagedExecutor` and #1065 carries the OTel context); its Javadoc now says so.*

**Pre-flight findings (2026-10-08):**
- **Guardrail reprompts keep the full prompt under Quarkus; nothing special is needed.** Plain LangChain4j 1.20.2–1.22.0
  drops the conversation on a retry or reprompt when the AI service has no chat memory (a reprompt sends only the
  reprompt text; a retry throws `messages cannot be null or empty`). Filed as
  [langchain4j#6626](https://github.com/langchain4j/langchain4j/issues/6626), fix PR
  [langchain4j#6627](https://github.com/langchain4j/langchain4j/pull/6627), reproducer in
  [edeandrea/langchain4j-reproducers](https://github.com/edeandrea/langchain4j-reproducers). **Quarkus LangChain4j
  1.14.1 isn't affected:** it hands the guardrails its own per-call `LocalCommittableChatMemory`. A WireMock probe
  (local branch `spike/reprompt-check`, `RepromptProbeTests`) showed a plain `@RegisterAiService` and an `@Agent`, both
  with `NoChatMemoryProviderSupplier`, resend system + user + rejected answer (+ the reprompt) on the second request.
  So the stateless agents can use ordinary reprompts, and the guardrails below need no self-contained prompts.
- **Put the date rules inside `IncidentDetailsAgent`'s JSON guardrail, not in a second guardrail after it.** A
  `JsonExtractorOutputGuardrail` success is a rewrite, and a rewrite blocks any later guardrail's reprompt
  ("Retry or reprompt is not allowed after a rewritten output"; the cause of #228, see PR #229).

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
  that returns the set of missing items *(built as `ClaimExtractionRules.findMissingItems`, taking any
  `Collection<MissingItem>`; renamed in the task 05 review)*:
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

- `src/main/java/org/parasol/intake/agent/extraction/` and `src/main/java/org/parasol/intake/model/` (new)
- `src/test/java/org/parasol/intake/agent/extraction/` and `src/test/java/org/parasol/intake/model/` (new)

## Key Points

- Bodies sent to the LLM are capped at `IntakeConfig`'s max characters (the stored body is not truncated).
  *(Task 05 review: the caller caps them, in task 08's helper that builds the combined correspondence; see the
  Outcome.)*
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
  (a label or a constant name). `findMissingItems` returns a `Set` of it; the reply templates list the items in the
  set's iteration order.

## Done When

- [x] Every extraction agent carries both opt-outs and `@ModelName("claim-intake")`; requests carry one user message and no RAG content.
- [x] `ClaimExtractionWorkflow` returns a JSON-safe `ClaimExtraction` (ISO-string dates, no `Optional`), and declares **no** `@ParallelExecutor`.
- [x] `missingInformation` (`findMissingItems`) exists and accounts for reviewer-ticked items and blank replies.
- [x] The JSON, length and date guardrails are in place.
- [x] All the unit and WireMock tests listed above pass under `-Pollama`, including the empty-reply test.
  *(2026-10-09, local `clean verify`: all 42 intake agent and model tests pass under default, `-Pollama` and
  `-Pollama-openai`. The only failures are the 10 Playwright UI tests, plus `NotificationServiceTests` under default,
  which also fail on an untouched `HEAD` in this environment.)*

## Outcome

Built 2026-10-08 on branch `gh216/05-extraction-agents`, committed as `454711f`; in review as draft
[#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235) into `gh216-email-intake`, **shared with
task 06** (user decision, 2026-10-09): task 06 is a second commit on this branch, and the PR is squash-merged once 06
lands.

- **Packages** (user decision, 2026-10-09: split by agent group, not by layer, so the intake doesn't end up with one
  package of 20+ classes after task 06):
  - `org.parasol.intake.model`: `IncidentDetails`, `ClaimExtraction`, the values Flow persists and task 08 reads.
    Task 06 adds `IntakeOutcome`, `MatchedClaim` and `EmailType` here.
  - `org.parasol.intake.agent.extraction`: everything behind `ClaimExtractionWorkflow`. The guardrails stay here, not
    in their own package (user decision): they share `IsoValues` and the fallback exception with the workflow, and a
    separate package would make all of them `public` for two classes.
  - `org.parasol.intake.agent` is left for task 06's root agent and router, with its triage agents in
    `org.parasol.intake.agent.triage`.
  - `MissingItem` and `IntakeClaimStatus` stay in `org.parasol.intake` (task 04's merged code uses them).

- **`ClaimExtractionWorkflow`** is a declarative `@ParallelAgent` over `ClaimSummaryAgent`, `ClaimSentimentAgent` and
  `IncidentDetailsAgent`, with a static `@Output` that builds the `ClaimExtraction`. No `@ParallelExecutor`.
  - **Inputs:** `extractClaim(correspondence, extractedSoFar, requestedItems, sentDate)`. The sub-agents read them
    from the agentic scope by parameter name. Only `IncidentDetailsAgent` takes the last three; the summary and
    sentiment agents get the correspondence alone.
  - **`requestedItems` is a `Collection<MissingItem>`**, not a `Set` (review decision): the prompt only reads it, and
    the agentic scope passes a value through only when it's an instance of the declared type
    (`AgentUtil.adaptValueToType`), so a `Collection` also accepts a value Flow's checkpoint rehydrates as a `List`
    (not verified; task 08 is the first run under Flow).
  - **`extractedSoFar` is never `null`:** the agentic scope treats a `null` input as missing and throws
    `MissingArgumentException`. A new claim passes an all-`null` `IncidentDetails`.
  - **`ClaimExtractionWorkflowEntryPoint`** (package-private, `@Unremovable`) injects the workflow in `src/main`, as
    this task's Key Points foresaw. Without it the build fails with "No agent provides an output key named
    'correspondence'": quarkus-langchain4j only treats a parameter as caller-provided when the agent interface is
    injected somewhere, and a test's `@Inject` doesn't count for `package`. Without `@Unremovable`, ArC removes the
    unused bean and its injection point with it. **Remove it in task 06**, once the workflow is nested under
    `ClaimsMailboxAgent`.
- **`@ModelName("claim-intake")` sits on each `@Agent` method, not on the interface.** quarkus-langchain4j only reads it
  from the agent method (`AgenticProcessor.extractModelName`); on the type it's ignored and the agent silently uses
  the default model. The workflow test pins a distinct `model-name` so this can't regress unnoticed.
  - **Each agent also sets `@RegisterAiService(modelName = "claim-intake")`.** The core extension
    (`AiServicesProcessor.chatModelName`) reads only that attribute when it registers the AI service; without it the
    build requests the default chat model, which fails under `-Pollama` ("multiple available providers ... ollama,
    openai") before any test runs.
- **Method names are verb-first** (review decision, per `CODE_STANDARDS.md`): `extractClaim`,
  `summarizeCorrespondence`, `assessClaimantSentiment`, `extractIncidentDetails`, `combineAgentOutputs` (`@Output`),
  `treatUncorrectedDatesAsMissing` (`@ErrorHandler`), `findMissingItems`, `IsoValues.parseDate`/`parseTime`. Done
  before task 11 on purpose: an agent's method name is in its span and Langfuse dataset name
  (`langchain4j.aiservices.ClaimSummaryAgent.summarizeCorrespondence`), so a later rename would split that history.
- **The category list in the prompt is generated from `ClaimCategory`** (review decision): `ExtractionPromptExtensions`,
  a package-private `@TemplateExtension(namespace = "extraction")`, supplies `{extraction:claimCategories}` and
  `{extraction:otherCategory}`. The domain enum has no Qute annotation (`@TemplateEnum` or `@TemplateData(target = …)`
  would give every template the same `ClaimCategory:` binding); each template binds the enum its own way.
  `theDetailsPromptListsEveryClaimCategoryFromTheEnum` checks the rendered prompt.
- **Guardrails** (package-private CDI beans, named `…OutputGuardrail` like the rest of the repo's guardrails):
  - `MaxLengthOutputGuardrail` (summary and sentiment): over 5000 characters → reprompt for a shorter text.
  - `IncidentDetailsOutputGuardrail` extends `JsonExtractorOutputGuardrail<IncidentDetails>` and holds the date rules too
    (a JSON-extractor success is a rewrite, which blocks a later guardrail's reprompt; #228). It checks: valid JSON
    (fenced JSON is extracted), a valid ISO `incidentDate` not after the sent date, a valid ISO `incidentTime`.
    Several date/time problems are reprompted in one message. A missing or malformed `sentDate` skips only the
    future-date check. **Malformed JSON gets the base class's reprompt** (`invokeInvalidJson`, review decision): the
    AI service already appends the JSON schema generated from `IncidentDetails` to the user message, and a reprompt
    resends it, so a hand-written schema would only drift from the record.
  - **Ordinary reprompts** (pre-flight finding): `anInvalidDateIsRepromptedAndTheCorrectedAnswerIsUsed` shows the
    reprompt resends system + user + rejected answer + reprompt.
- **"Still invalid after the retry limit → treat it as missing"** (the task's date rule). Every date/time rejection
  attaches an `InvalidIncidentDetailsException` carrying the answer minus the bad fields; it's the failure's cause,
  the one slot that survives into the `OutputGuardrailException` thrown after the last retry. The workflow's
  `@ErrorHandler` finds it in the cause chain and returns that fallback as the agent's result, so the date (or time)
  is simply missing. **Malformed JSON has no fallback** and still fails the run (spike Q4); so does an over-long
  summary or sentiment. The retry limit is quarkus-langchain4j's default (`quarkus.langchain4j.guardrails.max-retries`,
  3); it isn't set here.
- **`ClaimExtractionRules.findMissingItems`:** each absent core field (a blank string; an incident date that isn't a
  valid ISO date; a `null` category; `OTHER` counts), plus every reviewer-ticked item not in `answeredItems`, or every
  ticked item when the reply is blank. Returns an unmodifiable set in `MissingItem` declaration order, the order the
  reply templates list items in. `requestedByReviewer` is required (pass an empty collection), any `Collection`
  (duplicates collapse). Incident time is never missing.
- **The correspondence cap moved to task 08** (review decision): no agent uses it, it's part of building the text
  task 08 hands to the agents, and its first name (`ClaimCorrespondence`) clashed with task 08's planned entity. The
  rules and tests it had are recorded in task 08's `runAgents` step.
- **ISO strings stay** (`IncidentDetails` has no `java.time`): the round-trip test covers the strings, enums and set
  through the Quarkus `ObjectMapper`. It doesn't test `LocalDate`, so the rule isn't relaxed. `IsoValues` is the one
  place the strings are parsed.

**Tests** (`src/test/java/org/parasol/intake/agent/extraction/`, plus the round trip in `.../intake/model/`):
- Unit: `ClaimExtractionRulesTests` (each absent field, `OTHER` vs `null`, ticked items answered/unanswered, ticked
  items as a `List` with a duplicate, blank reply, order), `IncidentDetailsOutputGuardrailTests` (valid, fenced,
  malformed, invalid/future date, the sent date itself, invalid time, both at once, null fields, missing/malformed
  sent date, and each fallback), `MaxLengthOutputGuardrailTests` (5000 and 5001), `ClaimExtractionWorkflowOutputTests`
  (`@Output`).
- `ClaimExtractionValueRoundTripTests`: both records through the Quarkus `ObjectMapper`.
- `ClaimExtractionWorkflowTests` (WireMock, stubs on the last message only): complete extraction; missing fields; a
  relative date with the sent date reaching only the details agent; `OTHER`; a reply on a pending claim over the
  combined correspondence, with the fields so far and the requested items in the prompt; every `ClaimCategory` in the
  prompt; the empty reply after ticked items; a reprompted date; a date never corrected → missing; malformed JSON
  never corrected → the run fails.
  Every request is system + one user message (no history, no RAG), and **parallelism is measured on WireMock's own
  timings:** the last request arrives before the first is answered (750 ms stubs).
