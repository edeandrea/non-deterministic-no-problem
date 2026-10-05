# Spike Results: quarkus-langchain4j-agentic (task 01)

Throwaway spike for #216. All evidence comes from WireMock-stubbed runs in a separate worktree, or from the
library sources jars at the versions listed below. Nothing here is merged or pushed.

## Executive summary

Versions: quarkus-langchain4j 1.14.1, langchain4j-agentic 1.20.2-beta30, Quarkus 3.40.1, Java 25.
All 32 spike tests pass.

1. **Verdict: feasible, with workarounds.** A static `@HumanInTheLoop` agent returning `SuspendedResponse`, a
   DB-backed `AgenticScopeStore`, memory id = Message-ID, and resume via REST after a genuine Quarkus restart all
   work. The resume does not call completed agents again (Q9–Q11).
2. **`@Internal` API:** production needs only `SuspendedResponse` and `DefaultAgenticScope`. The
   `StateJsonCodecFactory` SPI is an optional extra, needed only to put `java.time` values in the scope (Q13).
3. **Every AI agent must opt out** with
   `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`.
   Otherwise it gets the policy Easy RAG augmentor and a memory shared across all runs under id `"default"`, and
   one customer's email leaks into the next prompt. The root's `@MemoryId` does not separate them (Q6, Q19).
4. **Every entry agent must be injected somewhere in `src/main`.** Build-time output-key validation fails for
   parameters that are not user-provided otherwise (Q3).
5. **Root shape:** `process(@MemoryId String messageId, …)`, with the root interface extending
   `AgenticScopeAccess`. Catch `AgenticSystemSuspendedException`; a `ResultWithAgenticScope` return type is optional
   (Q10).
6. **Store registration:** `AgenticScopePersister.setStore(cdiStore)` in a `StartupEvent` observer whose priority is
   lower than any observer that calls an agent. Reset it to `null` on `ShutdownEvent` (same-JVM restarts).
   - A root first invoked before `setStore` never persists (Q12, Q18).
   - The store is JVM-global, so ephemeral roots write to it too (Q14).
7. **Re-invocation overwrites the scope** with whatever arguments are passed. Read the originals back with
   `scope.readState("email")` before resuming (Q11c).
8. **Scope values must be JSON-safe.** No `LocalDate` or `Optional`: `toJson` fails and so does every checkpoint.
   Carry dates as ISO strings.
   - Allowlist types that appear in no agent signature (e.g. the review decision) with `allowDeserializationType`
     at startup (Q13).
9. **Type the review output as `Object`** in consuming `@Agent` parameters; typed `@ActivationCondition`s are fine.
   Never use `async = true` on the HITL agent (Q9, Q15).
10. **Evict manually** (`evictAgenticScope(messageId)`) on every non-suspended ending and after the final resume.
    Rows are never deleted otherwise, and the in-memory copy stays until eviction, so run one replica (Q14).
11. **Store saves use `requiringNew`.** Scope checkpoints commit independently of the claim transaction, so they
    are not atomic with claim updates (Q12).
12. **Tracing needs a caller span.** Without one, each leaf agent and each store statement is its own trace, and
    composite agents produce no span. Wrap each email in a CONSUMER root span with
    `gen_ai.operation.name=invoke_agent` (Q7, Q16a/b).
13. **AgentListener:** a CDI `AgentListener` bean only sees AI leaf agents. For the full tree, use a static
    `@AgentListenerSupplier` on the root, with `inheritedBySubagents()=true`.
    - It sees composite, HITL and suspend events, and can emit correctly parented `invoke_agent <name>` spans.
    - It must end the composite spans left open on suspension (Q16c).
14. **Parallel agents need `@ParallelExecutor static Executor executor()`** returning
    `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`. With it, parallel sub-agents join the root
    trace and MDC `traceId`/`spanId` reach their log lines (Q17).
15. **Langfuse typing:** Langfuse types observations from `gen_ai.operation.name`: `chat` → GENERATION,
    `execute_tool` → TOOL, `invoke_agent` → AGENT. `langchain4j.aiservices.*` spans have no type of their own and
    become SPAN ancestors (Q16d).
16. **The review resume runs in a new trace**, with a span link to the stored intake trace context (Q16c).
17. **`MonitoredAgent` cap is incomplete.** `setMaxRetainedSessions(0)` at startup stops retention of completed
    runs. **Suspended runs stay in `ongoingExecutions`** until resumed, and forever if evicted without a resume.
    This needs a user decision (Q18).
18. **Each leaf agent interface should belong to exactly one root.** Leaf agents are CDI singletons shared between
    roots, so roots attach their listeners to them and keep extending their agent ids (Q16c).
19. **Tests:** sub-agent `@InjectMock` needs its own test profile (Q2). The restart test needs a database that
    survives a Quarkus restart; the test Postgres dev service is recreated (Q11b).

## Environment

- **Main repo at spike start:** `main` @ `c39dadb` ("Track .tasks planning docs for claim-intake roadmap").
  `git status --porcelain` was ` M .gitignore` and `?? AGENTS.md`.
- **Worktree:** `/Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-agentic`, branch
  `spike/agentic-hitl` (local only, created from `main` @ `c39dadb`).
  - Commits: wave A `eaff920`, wave B `f6906aa`, wave C `64b63b6` (final).
  - The worktree has since been removed; the branch is kept.
- **Java:** Temurin 25.0.4+7 (aarch64). Container runtime: Podman 6.0.2 via `/var/run/docker.sock`.
- **Versions (from `./mvnw dependency:tree`):**
  - `io.quarkiverse.langchain4j:quarkus-langchain4j-bom` **1.14.1**. This is the latest stable on Maven Central;
    the metadata lists `… 1.13.3, 1.14.0.CR2, 1.14.0.CR3, 1.14.0, 1.14.1`.
  - `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic` **1.14.1**
  - `dev.langchain4j:langchain4j-agentic` **1.20.2-beta30** (still a beta)
  - `dev.langchain4j:langchain4j` / `langchain4j-core` **1.20.2**. `langchain4j-guardrails` is 1.20.2-beta30.
  - `io.opentelemetry:opentelemetry-sdk-testing` **1.62.0** (test scope, BOM-managed)
  - Quarkus platform 3.40.1 (unchanged)
- **Fixes the BOM bump needed:** none. `./mvnw -B clean test-compile -Pollama` and
  `OPENAI_API_KEY=change-me COHERE_API_KEY=change-me ./mvnw -B clean test-compile` both gave `BUILD SUCCESS`
  with 1.14.1 and the agentic extension added next to `quarkus-langchain4j-chat-scopes-websocket`.
  - The app also boots in `@QuarkusTest` with both extensions present.
  - Only compilation and the spike tests were run. The existing test suite was not run against 1.14.1.
- **Spike code:** `src/test/java/org/parasol/spike/` (worktree).
  - Agents declared in **test** sources are discovered at build time, so `src/main` code is not needed.
  - Tests: `SpikeAgenticTests` (12), `SpikeInjectMockTests` (1), `SpikeInjectMockRootTests` (1). All 14 green:
    `./mvnw -B test -Dtest='org.parasol.spike.*Tests' -Dquarkus.http.test-port=0 -Dquarkus.http.test-ssl-port=0`.
  - `SpikeTestProfile` setup:
    - stubs all three API keys
    - points the default OpenAI model (model name `default-model`) at `…/v1`
    - points a named model `claim-intake` (model name `claim-intake-model`) at a **different** path, `…/claim-intake/v1`
    - sets `easy-rag.ingestion-strategy=OFF`, so only query-time embedding calls show up
    - sets `quarkus.langfuse.otel.enabled=false`, and turns off Langfuse init and session scoring
- **Sources cited:** downloaded `-sources.jar`s into `$TMPDIR/spike-src/`. Paths below are relative to each jar.

## Results

### Q1 `@ModelName` picks the named model

**Answer:** Yes. An `@Agent` method annotated `@ModelName("claim-intake")` sends its request to the
`claim-intake` model's base URL and model name. An agent without the annotation uses the default model.

**Evidence:**
- Test: `SpikeAgenticTests.q1ModelNameSelectsNamedModel`.
  - The categoriser's request went to `/claim-intake/v1/chat/completions` with `"model" : "claim-intake-model"`.
  - `SpikeDefaultModelAgent` (no annotation) went to `/v1/chat/completions` with `"model" : "default-model"`.
- Source:
  - `AgenticProcessor.java:152` (`extractModelName`) and `:794-798`: the model is resolved as a
    `FromBeanWithName(chatModelName)` CDI bean.
  - `AgenticProcessor.java:871` requests the named chat-model bean.
  - `:154-158` rejects `@ModelName` combined with `@ChatModelSupplier` at build time.

**Implication for #216:** A dedicated `quarkus.langchain4j.claim-intake.*` model configuration works. Tests can
prove which model was used by giving it its own WireMock path.

### Q2 `@InjectMock` on an agent interface

**Answer:** Yes. It works on a leaf agent and on a root workflow. A mocked leaf agent is also the instance the
enclosing workflow calls.

**Evidence:**
- `SpikeInjectMockRootTests.q2InjectMockOnRootWorkflow`: `@InjectMock SpikeIntakeWorkflow` returns the stubbed
  value, and `verify` passes.
- `SpikeInjectMockTests.q2InjectMockOnAgentInterface`: the test uses `@InjectMock SpikeCategorizerAgent`
  (returns `OTHER`) and then calls the real `SpikeIntakeWorkflow`.
  - Log: `Q2 direct=OTHER reply=Thanks for your claim categorizeLlmCalls=0 activation=[isAuto(OTHER), isOther(OTHER)]`.
  - So the workflow used the mock: no categorise LLM call was made, and the condition saw `OTHER`, although the
    email said "AUTO".
- Source:
  - `AgenticProcessor.java:826`: every agent is a synthetic `@ApplicationScoped` bean.
  - `AgenticRecorder.java:172-201` (`QuarkusSubAgentResolver`): sub-agents are resolved with
    `Arc.container().select(subAgentClass)` and then `ClientProxy.unwrap`.

**Caveat (not tested):** the unwrapped sub-agent instance is captured once, when the root agent is built.
- If a root workflow is built while a mock is installed, that mock reference may stay baked into the root for
  the rest of the app's life.
- The spike gave the sub-agent-mock test its own `@TestProfile` (`SpikeInjectMockProfile`), which forces a fresh
  app.

**Implication for #216:** Workflow tests can mock individual extraction or triage agents. Put sub-agent-mock tests
under a dedicated test profile to avoid stale references.

### Q3 Nesting: `@SequenceAgent` → `@ConditionalAgent` → `@ParallelAgent` (+ `@Output`)

**Answer:** Yes. Each agent's `outputKey` value is visible to later agents, to `@ActivationCondition` methods and
to the `@Output` combiner.

**Evidence:**
- The topology is
  `SpikeIntakeWorkflow` (sequence: categoriser → router → reply)
  → `SpikeRouterWorkflow` (conditional: `SpikeExtractionWorkflow` when AUTO, `SpikeOtherAgent` when OTHER)
  → `SpikeExtractionWorkflow` (parallel: details agent + policy-number agent, with a static `@Output` combine).
- `q3NestedWorkflowAutoBranch`:
  - activation calls were `[isAuto(AUTO), isOther(AUTO)]`
  - the combiner got the typed record and the string:
    `details=SpikeClaimDetails[claimantName=Jane Doe, incidentDate=2026-01-02, …];policy=POL-123`
  - the reply agent's prompt contained `category=AUTO` and the combined `extraction`
  - `[[other]]` was never called
- `q3NestedWorkflowOtherBranch`:
  - activation calls were `[isAuto(OTHER), isOther(OTHER)]`
  - no details or policy calls were made, and the combiner was not called
  - the reply prompt contained `extraction=not an auto claim`
- **Every** `@ActivationCondition` is evaluated, not only the first match. A conditional agent without its own
  `outputKey` works when both branches write the same key (`extraction`).
- **Build-time trap (found by the spike):** `AgenticProcessor#validateAgenticParameterTypes`
  (`AgenticProcessor.java:1401-1444`) fails augmentation with
  `No agent provides an output key named 'question' …` when two things are both true:
  1. an agent parameter matches no `outputKey`, and
  2. the agent has no CDI injection point anywhere in the app.

  "User-provided" keys are collected only from injection points (`AgenticProcessor.java:1352-1361`). A test class
  that doesn't inject a standalone agent therefore broke the build for **every** test. The workaround is
  `SpikeAgentEntryPoints`, a bean that constructor-injects each standalone agent.

**Implication for #216:** The planned topology works as written.
- Every top-level entry agent must be injected somewhere in `src/main` (the processor or watcher will do that).
- Activation conditions must be cheap and side-effect free, because all of them run.

### Q4 Enum and record structured output

**Answer:** Yes. An enum return value and a record return value (including a `LocalDate` field) are both parsed.

**Evidence:**
- Test: `q4EnumAndRecordStructuredOutput` returned `OTHER` and
  `SpikeClaimDetails[claimantName=Jane Doe, incidentDate=2026-01-02, description=Rear-ended at a light]`.
- The format is passed as **prompt text**, not `response_format`:
  - enum: `You must answer strictly with one of these enums:\nAUTO\nOTHER`
  - record: `You must answer strictly in the following JSON format: {"claimantName": (type: string),
    "incidentDate": (type: date string (2023-12-31)), …}`
  - the request body held only `model`, `messages`, `presence_penalty` and `frequency_penalty`
- A non-JSON reply fails with `OutputParsingException: Failed to parse "POL-123" … into SpikeClaimDetails`
  (seen in the first run). That exception propagates as an `AgentInvocationException` through the parallel,
  conditional and sequence layers.

**Implication for #216:** Records and enums can be the agent contract.
- Parse failures abort the whole workflow, so a JSON-extracting output guardrail (e.g. the existing
  `JsonExtractorOutputGuardrail` pattern) or an error handler is needed on extraction agents.

### Q5 `@OutputGuardrails` on agents; CDI vs reflection; reprompt

**Answer:** Yes on all three points:
- the guardrail runs
- it is a **CDI bean**: an `@ApplicationScoped` guardrail with a constructor-injected dependency works
- `reprompt` retries the agent call

**Evidence:**
- Test: `q5OutputGuardrailRunsAndReprompts`.
  - `SpikeOutputGuardrail` (constructor-injects `SpikeGuardrailCounter`) saw `[BAD answer, GOOD answer]`, and the
    agent returned `GOOD answer`.
  - The second LLM request held user `[[guarded]] …`, assistant `BAD answer`, user
    `SPIKE-REPROMPT please answer GOOD`.
- Source, how the guardrail is created:
  - `AgentBuilder.java:310-322` passes the annotation classes to `AiServices.outputGuardrailClasses`.
  - `GuardrailServiceBuilder.java:217` calls `ClassInstanceLoader.getClassInstance`.
  - `ClassInstanceLoader.java:28-29` looks up the `ClassInstanceFactory` via `ServiceLoader`.
  - `QuarkusClassInstanceFactory.java:9-11` returns `CDI.current().select(clazz).get()`.

**Implication for #216:** Existing guardrail patterns (constructor-injected, `@ApplicationScoped`) can be reused on
agents unchanged.

### Q6 Easy RAG auto-attachment

**Answer:** Yes, it is attached automatically, and so is a shared default chat memory (see below).
- The tested opt-outs that work:
  - `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, …)` on the agent interface
  - a pass-through `@RetrievalAugmentorSupplier`
- Returning `null` from `@RetrievalAugmentorSupplier` does **not** opt out.

**Evidence:**
- `q6EasyRagIsAttachedToAgentsByDefault`: a plain agent with no RAG annotations made **1** `/v1/embeddings` call.
  An opt-in agent (`@RetrievalAugmentorSupplier` returning the `@CdiBean RetrievalAugmentor`) brought the count
  to 2.
- Q7's span trees show a `POST /embeddings` span under **every** leaf agent.
- `q6OptOutCandidates`: embedding deltas were pass-through augmentor **0**, `null` supplier **1**.
- `q6RegisterAiServiceOptOut`: an `@Agent` interface that also carries
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class,
  chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)` made **0** embedding calls, with message
  counts `[1, 1]`.
- Source, why the augmentor is attached:
  1. `AgenticProcessor.java:480-484` registers every agent annotation as "implies AI service".
  2. `AiServicesProcessor.java:474` (core deployment) gives each implied service the default
     `BeanIfExistsRetrievalAugmentorSupplier`, and `:1243-1248` adds an `Instance<RetrievalAugmentor>` injection
     point.
  3. At runtime, `AgentBuilder.java:185` calls `AiServiceContext.create(agentClass)`.
     `AiServiceContext.java:75-79` delegates to `QuarkusAiServiceContextFactory.java:13-21`, which returns **the
     CDI `QuarkusAiServiceContext` bean of the implied declarative service**, with the augmentor already set.
  4. `AgentBuilder.java:205-206` overrides it only when the supplier returns non-null.
- Source, opt-outs:
  - `AiServicesProcessor.java:478-479`: `NoRetrievalAugmentorSupplier` makes the supplier `null`, so no augmentor
    is set.
  - `RegisterAiService.java:183` (`retrievalAugmentor`) and `:151` (`chatMemoryProviderSupplier`): default values.

**Chat memory (unasked, high impact):** the same implied-service mechanism gives every agent the default
`ChatMemoryProvider` bean (`RegisterAiService.java:151`).
- Without `@MemoryId` and outside a request context, the memory id falls back to `"default"`
  (`AiServiceMethodImplementationSupport.java:1307-1331`), so **all invocations of an agent share one
  conversation**.
- Test `memorySharedAcrossInvocations`: two categorise calls for different customers sent 1 and then **3**
  messages. The second request contained the first customer's email and the `AUTO` answer.
- Opt-outs that work:
  - a discarding `@ChatMemoryProviderSupplier` (`SpikeNoMemoryAgent`: `[1, 1]`)
  - `chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class` (above)
- This is how the issue was found: in the first run the `[[categorize]]` WireMock stubs matched on earlier history,
  so the wrong stub answered.

**Implication for #216 (design must address):** intake agents must opt out of policy RAG **and** of the shared
memory, or customer A's email leaks into customer B's prompt.
- Recommended: put `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class,
  chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)` on every intake agent interface, or design an
  explicit per-email `@MemoryId`.
- WireMock stubs in intake tests should match on the last message
  (`matchingJsonPath("$.messages[-1:].content", …)`).
- Still to check in wave B: whether `@MemoryId` (needed for durable HITL) re-enables a per-id memory, and how that
  interacts with the "agents with chat memory can't be a sub-agent" rule (`AgentInvocationHandler.java:248`).

### Q7 OpenTelemetry span names

**Answer:** Partly.
- Leaf agents produce `langchain4j.aiservices.<Iface>.<method>` spans, the same convention as `@RegisterAiService`.
- Composite agents (sequence, conditional, parallel, `@Output`) produce **no** span.
- `@ParallelAgent` sub-agents lose the trace context and become separate root traces.

**Evidence:** `q7SpanNamesWithParent`. The test wraps `intakeWorkflow.process(…)` in a manual `spike-root` span.
Spans were captured by the `SpikeSpanCapture` CDI `SpanProcessor` (`SimpleSpanProcessor` + `InMemorySpanExporter`).
Grouped by trace, with parents in brackets:

```
trace 91c9f99b  spike-root (INTERNAL, root)
  ├─ langchain4j.aiservices.SpikeCategorizerAgent.categorize (INTERNAL) [spike-root]
  │    ├─ POST /embeddings (CLIENT)
  │    └─ completion claim-intake-model (INTERNAL) ─ POST /chat/completions (CLIENT)
  └─ langchain4j.aiservices.SpikeReplyAgent.reply (INTERNAL) [spike-root]
       ├─ POST /embeddings (CLIENT)
       └─ completion claim-intake-model (INTERNAL) ─ POST /chat/completions (CLIENT)
trace 8fc3bb76  langchain4j.aiservices.SpikeDetailsAgent.extractDetails (INTERNAL, root!)
       ├─ POST /embeddings (CLIENT)
       └─ completion claim-intake-model (INTERNAL) ─ POST /chat/completions (CLIENT)
trace 4dcfd617  langchain4j.aiservices.SpikePolicyNumberAgent.extractPolicyNumber (INTERNAL, root!)
       ├─ POST /embeddings (CLIENT)
       └─ completion claim-intake-model (INTERNAL) ─ POST /chat/completions (CLIENT)
```

- The test logged `ai-service span in root trace?` as
  `{categorize=true, reply=true, extractDetails=false, extractPolicyNumber=false}`.
- `q7SpanNamesWithoutParent`: without a caller span, **each of the 4 leaf agents is its own trace**. Nothing links
  one email's workflow.
- No span is named after `SpikeIntakeWorkflow`, `SpikeRouterWorkflow` or `SpikeExtractionWorkflow`.
- Leaf root spans carry no `gen_ai.*` attributes. Those are on the `completion …` and `langchain4j.tools.*` children.
- Source:
  - `QuarkusAiServicesFactory.java:118-150`: leaf agents get the generated Quarkus AI-service implementation
    (hence the span).
  - `langchain4j-core DefaultExecutorProvider.java:27` / `VirtualThreadUtils`: the default executor runs on
    virtual threads, with no OTel context wrapping.

**Implication for #216:**
- `AiServiceDatasetSpanProcessor` will tag leaf agents by name, so datasets would be
  `langchain4j.aiservices.<Agent>.<method>` (drift detection is out of scope).
- The intake processor **must** open its own root span per email, with a `gen_ai.*` attribute so the Langfuse
  `AI_ONLY` filter keeps it.
- Parallel sub-agents need context propagation: Q17, wave C (`@ParallelExecutor` + `Context.taskWrapping`).
- Easy RAG adds a `POST /embeddings` span under every agent unless the agent opts out (Q6).

### Q8 `@Tool` on a CDI bean via `@ToolBox`

**Answer:** Yes. A `@Tool` method on an `@ApplicationScoped` bean, attached with `@ToolBox(SpikePolicyTools.class)`,
ran with the arguments the model asked for, and the agent returned the final answer.

**Evidence:**
- Test: `q8ToolBoxToolRunsWithArguments`.
  - WireMock returned `tool_calls: lookupPolicy({"policyNumber":"POL-123"})`, then a final message.
  - Tool calls recorded: `[POL-123]`; the answer was `The policy POL-123 is active.`
  - The first request advertised `"name" : "lookupPolicy"`.
- Span tree, all in one trace:
  `langchain4j.aiservices.SpikeToolAgent.checkPolicy` → `completion claim-intake-model` (×2) and
  `langchain4j.tools.lookupPolicy`.
  - The tool span carries `gen_ai.tool.name`, `gen_ai.tool.call.arguments` and `gen_ai.tool.call.result`.
- Source:
  - `AgenticRecorder.java:236-250`: tool beans are resolved with `Arc.container().select(toolClass).get()`, so they
    are real CDI beans (interceptors and injection apply).
  - `AgenticProcessor.java:620-652`: build-time `@ToolBox` collection, which rejects combining it with
    `@ToolsSupplier`.

**Implication for #216:** Lookup tools (policy, claim and so on) can be ordinary CDI beans shared with existing code.
Tool arguments and results show up in traces, so take care with PII in tool payloads.

## Wave B: human-in-the-loop suspend/resume and persistence (Q9–Q15)

Wave B commit: `f6906aa` on `spike/agentic-hitl`.
- New tests: `SpikeHitlTests` (11), `SpikeHitlRestartPart1Tests` (1) and `SpikeHitlRestartPart2Tests` (1).
- Together with wave A that is 27 tests, all green in one Maven run.
- Every wave-B AI agent opts out of Easy RAG and the shared memory with
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`.

The test topology, mirroring the planned intake:

```
SpikeReviewWorkflow  (@SequenceAgent root, @MemoryId String runId, extends AgenticScopeAccess)
 ├─ SpikeHitlClassifier            (AI, outputKey category)
 └─ SpikeHitlRouter                (@ConditionalAgent)
     ├─ [AUTO]  SpikeHitlAutoFlow  (@SequenceAgent)
     │           ├─ SpikeHitlExtraction (@ParallelAgent: summary + policyNumber AI agents)
     │           ├─ SpikeReviewGate     (static @HumanInTheLoop → SuspendedResponse, outputKey decision)
     │           └─ SpikeDecisionRouter (@ConditionalAgent on decision: READY agent | NEEDS_INFO agent)
     └─ [OTHER] SpikeHitlOtherAgent (AI)
```

- The store is `SpikeDbAgenticScopeStore`, a CDI bean backed by the Panache entity `SpikeAgenticScopeEntity`.
  - Each row holds `id = agentId|memoryId`, `agentId`, `memoryId`, `scopeJson text` and `updatedAt`.
  - Every method runs in `QuarkusTransaction.requiringNew()`.
  - It is registered by `SpikeStoreRegistrar`, a `@Observes @Priority(1) StartupEvent` observer that calls
    `AgenticScopePersister.setStore(store)`.

### Feasibility verdict: Yes, with workarounds

The #216 design can be built through Quarkus:
- a static HITL agent returning `SuspendedResponse`
- a DB-backed store
- memory id = Message-ID
- resume days later, after a restart, through REST

A genuine Quarkus restart was tested: a new application class loader and new agent beans, with scope data taken
only from the store. The run resumed, and none of the already-completed AI agents was called again (Q11b).

The design must take these workarounds on board:
1. **Register the store before the first invocation of each root.** Call `AgenticScopePersister.setStore(cdiStore)`
   from a `StartupEvent` observer.
   - A root's registry captures the store the first time that root is invoked, and keeps it for the life of the
     JVM (Q12).
   - The store is JVM-global, so it applies to **every** agentic root. Even ephemeral roots (no `@MemoryId`)
     write to and delete from it on each run (Q14).
2. **Re-invoke with the original arguments.** On every call, the root method's arguments overwrite the same-named
   scope keys. Read them back from the persisted scope (`scope.readState("email")`) or rebuild them from the stored
   claim or email. Passing `null` fails with `MissingArgumentException` (Q11c).
3. **Keep scope values JSON-friendly.**
   - The upstream codec has no `java.time` or `Optional` support, so a record with a `LocalDate` field makes
     `toJson` throw. That would break the checkpoint (Q13).
   - Either keep `LocalDate` and `Optional` out of every agent output and the review decision, or plug in a codec
     through the `@Internal` `StateJsonCodecFactory` SPI. That fixes `LocalDate`; `Optional` still failed.
   - Types that don't appear in any agent signature (e.g. the review-decision record) must be allowlisted at
     startup with `AgenticScopeSerializer.allowDeserializationType(..)`.
4. **Type the HITL output key as `Object`.** It takes the HITL method's declared return type (`Object`), so every
   *agent* that consumes it must declare the parameter as `Object`. `@ActivationCondition` methods may use the real
   type (Q9).
5. **Evict explicitly.** Persistent scopes are never deleted automatically: not after a run that never suspends,
   and not after a resumed run completes. Call `evictAgenticScope(messageId)` when the run finishes (Q14).
   - `getAgenticScope(unknownId)` returns `null`, so the REST resume endpoint must handle that.
6. **Expect the in-memory copy to linger.** The registry also keeps every persistent scope in memory until it is
   evicted. The only public way to drop that copy also deletes the row.
   - That is fine with one replica.
   - With several replicas, a pod could resume from a stale in-memory copy.
   - In-process, only the `@Internal` `AgenticScopeRegistry.clearInMemory()` clears it (Q11b).
7. **Do side effects outside the HITL method.** The method is static, so it gets no CDI, and it runs exactly once.
   The intake processor should catch the suspension and do the follow-up there: set the claim status to
   `Pending Review` and send the "final review" email.

**`@Internal` APIs needed beyond `SuspendedResponse` and `DefaultAgenticScope`:**
- In production code: **none**. The `AgenticScopeStore` SPI only uses the public `AgenticScopeKey`, the public
  `AgenticScopeSerializer`, and the `@Internal` `DefaultAgenticScope`.
- Optional: `StateJsonCodecFactory` / `Json.JsonCodec` / `TypeAllowlist`, only to get `java.time` into scope values.
- Tests only: `AgenticScopeOwner.registry().clearInMemory()`, to simulate a crash without restarting.

### Q9 Declarative static `@HumanInTheLoop` returning `SuspendedResponse`

**Answer:** Yes. A `public static` method on a plain class, declared to return `Object`, works. Quarkus build-time
validation accepts it. Parameters are resolved from the scope by `@V` name, and an `AgenticScope` parameter is
injected.

**Evidence:** `SpikeReviewGate`, exercised by every `SpikeHitlTests` suspend test.

```java
public class SpikeReviewGate {
    @HumanInTheLoop(outputKey = "decision", description = "Final human completeness review")
    public static Object review(@V("summary") String summary, @V("policyNumber") String policyNumber, AgenticScope scope) {
        return new SuspendedResponse<SpikeReviewDecision>("review:%s".formatted(scope.memoryId()));
    }
}
```

- Observed call: `review(summary=Rear-end collision at a light, policy=POL-777, memoryId=q10Plain…)`.
  - The parallel agents' outputs reached the method, and the memory id was available to build a stable
    response id.
  - The pending id was `review:<runId>`.
- Source:
  - `AgenticServices.java:835-858` (`createHumanInTheLoopAgent`) invokes the static method with
    `agentInvocationArguments(scope, method)`.
  - `AgenticProcessor.java:352-363` (`validateHumanInTheLoop`) checks only that the method is static.
  - `AgenticProcessor.java:1232-1248`: a class (not an interface) carrying agent annotations is treated as a non-AI
    agent, so no chat model is needed.
- **Build-time trap:** a consuming agent with `@V("decision") SpikeReviewDecision decision` failed augmentation:
  `Parameter no.1 of method 'needsInfo' of class 'org.parasol.spike.SpikeHitlNeedsInfoAgent' was expected to be of type 'java.lang.Object'`.
  - The cause: output-key types are taken from the declared return type (`AgenticProcessor.java:1385-1394`) and
    enforced on `@Agent` parameters (`:1401-1444`).
  - The fix: declare the parameter as `Object`.
  - `@ActivationCondition static boolean isReady(@V("decision") SpikeReviewDecision d)` **is** accepted. Activation
    conditions are not part of that check (`:1402-1405`).

**Implication for #216:** The HITL agent stays tiny and side-effect free. It derives the response id from
`scope.memoryId()` (the Message-ID), so the REST endpoint can compute the id without reading the scope first. Routing
on the decision belongs in `@ActivationCondition`s, which can be typed.

### Q10 Suspension inside a nested workflow propagates to the root

**Answer:** Yes, through sequence → conditional → sequence (with a parallel step before the HITL agent). Both root
return styles work:
- a plain `String` root **throws** `AgenticSystemSuspendedException`
- a `ResultWithAgenticScope<String>` root **returns** `suspended() == true` with `result() == null`

**Evidence:**
- `q10PlainRootThrowsSuspendedException`:
  - threw `dev.langchain4j.agentic.scope.AgenticSystemSuspendedException: Agentic system suspended: awaiting responses for [review:<runId>]`
  - `scope().pendingResponseIds()` was `[review:<runId>]`
  - LLM counts were classify 1, summary 1, policy 1, ready 0, needs-info 0
  - store calls: `load`, `save` ×3, all on the caller thread
  - the exception is **not** wrapped in `AgentInvocationException` (`AgentInvoker.java:52-53` rethrows it)
- `q10q11aResultRootSuspendsAndResumesViaCallback`: `suspended=true result=null pending=[review:<runId>]`.
- Source:
  - `PlannerBasedInvocationHandler.java:232-238`: a root returning `ResultWithAgenticScope` gets
    `new ResultWithAgenticScope<>(scope, null, true, resumeCallback)`; every other root throws.
  - `AgentExecutor.java:85-89`: nested agents turn the exception into `planner.onSubagentSuspended()`, which
    checkpoints and bubbles it up.

**Implication for #216:** Either style works. The plain style plus `catch (AgenticSystemSuspendedException e)` is the
simplest fit for a processor that also has to handle real failures. `ResultWithAgenticScope` only adds an in-JVM resume
callback, and that callback is useless after a restart (Q11).

### Q11 Resume (same JVM, after a restart, and which arguments)

**a. Same JVM. Answer: Yes, both ways.** Agents that already completed are not called again, and the human's value
drives the later `@ConditionalAgent`.
- **Re-invocation:**
  1. `workflow.getAgenticScope(runId).pendingResponseIds()` → `[review:<runId>]`
  2. `scope.completePendingResponse(id, new SpikeReviewDecision(NEEDS_INFO, "photos missing"))` → `true`
  3. `workflow.process(runId, EMAIL)` → `"NEEDS-INFO reply"`

  Test `q11aSameJvmResumeByReinvocation`: LLM counts went from
  `{classify=1, summary=1, policy=1, ready=0, needs-info=0}` at suspension to
  `{classify=1, summary=1, policy=1, ready=0, needs-info=1}` after the resume. The HITL method ran once in total.
- **Callback:** `suspended.completePendingResponse(new SpikeReviewDecision(READY, …))` returned
  `suspended=false result=READY reply` (test `q10q11aResultRootSuspendsAndResumesViaCallback`).
  - `ResultWithAgenticScope.completePendingResponse` throws `No resume callback available. After a crash/restart, use
    AgenticScope.completePendingResponse() and re-invoke the agent method directly.` when there is no callback.
- **API:**
  - `AgenticScope.pendingResponseIds()` returns a `Set<String>` of ids that are not yet done.
  - `completePendingResponse(String id, Object value)` returns a `boolean`. It also writes `value` under the HITL
    output key (`DefaultAgenticScope.java:463-486`).
- **How completed agents are skipped:** each planner stores `__planner_state_<agentId>` with a cursor and
  `__completedAgents` in the scope (`PlannerBasedInvocationHandler.java:416-424, 521-560`; visible in the Q13 JSON).

**b. After a crash or restart. Answer: Yes.**
- **In-process crash:** only possible through `@Internal` API:
  `((AgenticScopeOwner) ClientProxy.unwrap(workflow)).registry().clearInMemory()`, the same trick as upstream
  `SuspensionResumeIT.declarativeSuspendCrashAndResume`. `evictAgenticScope` would delete the row as well.
  - Afterwards `getAgenticScope` triggered a store `load`, and the resume returned `READY reply` with
    classify/summary/policy still at 1 (`q11bInProcessCrashViaInternalRegistry`).
- **Genuine restart:** `SpikeHitlRestartPart1Tests` (profile A) suspends. `SpikeHitlRestartPart2Tests` (profile B, a
  separate Quarkus application in the same Maven run) resumes.
  - The class loaders were `… SpikeRestartProfileA (QuarkusTest) restart no:0@6e38e95` and then
    `… SpikeRestartProfileB … @1cccf90d`.
  - Part 2 logged `pending=[review:restart-…] emailFromScope=AUTO-EMAIL restart scenario, policy POL-777
    result=READY reply LLM counts={classify=0, summary=0, policy=0, ready=1}`.
  - Store calls in part 2 were `load`, then `save` ×2. The READY prompt contained the original email and the summary
    that came out of the persisted scope.
  - After `evictAgenticScope`, the row was gone.
  - **Test-infra caveat:** the Postgres dev service is **recreated** when the profile changes, so in part 2
    `rowBeforeCopy=false`. The first attempt failed with `getAgenticScope(runId) == null`. The test therefore
    carries the row's JSON across in a JVM system property and re-inserts it before resuming, standing in for a
    shared production database. Everything else (agents, registry, store bean, class loader) is fresh.

**c. Arguments. Answer: the re-invocation needs real argument values, and they overwrite the persisted ones.**
- `writeAgenticScope` writes every named root argument into the scope **before** the planner loop, on every call
  (`PlannerBasedInvocationHandler.java:207-209, 596-611`).
- `q11cDifferentArgumentsOverwriteScopeState`: resuming with `"B-DIFFERENT email text"` produced a READY prompt
  containing `email=B-DIFFERENT email text`, and afterwards the scope's `email` was `B-DIFFERENT email text`.
- `q11cNullArgumentOnResume`: `null` removes the key (`DefaultAgenticScope.java:152-160`), and the run fails with
  `MissingArgumentException: Missing argument: email` before any agent runs.
- **How to rebuild them:** read the values back from the persisted scope after loading it
  (`(String) scope.readState("email")`, as `SpikeHitlRestartPart2Tests` does), or from the stored claim or email.

**Implication for #216:**
- The review REST endpoint needs to:
  1. load the scope by Message-ID (404 if `null`)
  2. check that `review:<messageId>` is pending (409 if not)
  3. complete it with the decision
  4. re-invoke the root with the arguments read from the scope
  5. evict on completion
- Keep the root signature minimal (`@MemoryId String messageId` plus the email payload), so the arguments are
  trivial to rebuild.

### Q12 `AgenticScopeStore`: signatures, registration, threads, transactions

**Answer:**
- **Signatures** (`scope/AgenticScopeStore.java`):
  - `boolean save(AgenticScopeKey key, DefaultAgenticScope agenticScope)`
  - `Optional<DefaultAgenticScope> load(AgenticScopeKey key)`
  - `boolean delete(AgenticScopeKey key)`
  - `Set<AgenticScopeKey> getAllKeys()`
  - `AgenticScopeKey` is a public `record(String agentId, Object memoryId)`. `agentId` is the root interface's
    FQCN, e.g. `org.parasol.spike.SpikeReviewWorkflow`.
- **Registration:** call `AgenticScopePersister.setStore(store)`, a static setter, from a CDI startup observer.
  - The alternative, ServiceLoader `META-INF/services/dev.langchain4j.agentic.scope.AgenticScopeStore`, runs from
    the `AgenticScopePersister` enum constructor and creates the store **reflectively**, so a Panache/CDI store
    can't be used that way.
- **Ordering:** works if the store is set at any point before a root's **first invocation**. Building the bean early
  does no harm.
- **Threads:** the caller's thread, which can be a virtual thread.
- **Transactions:** `QuarkusTransaction.requiringNew()` works, both inside a caller's transaction and on a virtual
  thread.

**Evidence:**
- Source:
  - `AgenticScopePersister.java`: `INSTANCE` constructor → `setStore(loadStore())` via `ServiceLoader`.
  - `AgenticScopeRegistry.java:26-29` reads `AgenticScopePersister.store` in its constructor.
  - The registry is created **lazily, on the first root call**: `PlannerBasedInvocationHandler.java:200-205`
    (`agenticScopeRegistry.compareAndSet(null, new AgenticScopeRegistry(type.getName()))`).
- `q12StoreIsCapturedWhenRegistryIsCreated`:
  - A root first **invoked** while the store was `null` made **0** store calls, even after the store was restored
    for its second call.
  - A second root, **built** (`ClientProxy.unwrap`) while the store was `null` but first invoked after `setStore`,
    persisted normally (`load`, `save` ×3).
- `SpikeStoreRegistrar` logged `store registered on thread main` before any test ran, in all five Quarkus apps.
- `q12StoreFromCallerTransactionAndVirtualThread`:
  - **Caller transaction:** calls inside `QuarkusTransaction.requiringNew().run(() -> workflow.process(..))` logged
    `thread=main virtual=false callerTxActive=true`. The outer transaction rolled back (the suspension exception
    escaped it), yet the row was still present, because `requiringNew` committed on its own.
  - **Virtual thread:** calls from `Thread.ofVirtual()` logged `thread= virtual=true callerTxActive=false`.
    Panache worked, and the row was present.
  - Observed per suspended run: `load` (scope lookup), `save` (scope created), `save` (checkpoint after the
    root-level classifier), `save` (checkpoint on suspend).
  - All calls ran on the caller's thread. None came from the parallel sub-agents' executor.

**Implication for #216:**
- Put the store in a `@Startup`/`StartupEvent` observer, ahead of the IMAP watcher and the REST endpoints.
- Never call `setStore(null)`. A root first invoked during that window never persists, for the rest of the JVM's life.
- Each store method should run in `requiringNew`, so that scope checkpoints survive a rollback of the processor's
  own claim transaction. The design should decide whether that is wanted, or whether claim and scope updates must
  be atomic. They cannot be with this SPI.
- Rows are about 3.4–4.7 KB per email in the spike, and include the raw email text (PII).

### Q13 Serialization of scope values

**Answer:** Partly.
- These round-trip: enums, `Set<Enum>`, `String`, and records of strings and enums.
- **`LocalDate` and `Optional` fail on `toJson`**, either as top-level values or as record fields.
- Records that appear in an agent method signature are allowlisted automatically by Quarkus. Other domain types
  need `AgenticScopeSerializer.allowDeserializationType(..)` or `allowDeserializationPackagePrefix(..)`.

**Evidence (`q13SerializationOfScopeValues`):**

| Value written to scope | `toJson` | `fromJson` without registration | after `allowDeserializationType` |
|---|---|---|---|
| `SpikeReviewOutcome` (enum) | OK | OK (`Enum` is always allowed) | OK |
| `Set.of(PHOTOS, POLICY_NUMBER)` | OK | OK (`java.util.` prefix allowed) | OK |
| `SpikeReviewDecision(enum, String)`, not in any agent signature | OK | **`UnserializableAgenticScopeException`** (below) | OK, `equal=true` |
| `SpikeSignatureRecord(String, enum)`, returned by an agent | OK | OK (Quarkus registered it) | OK |
| `LocalDate.of(2026,1,2)` | **fails**: `InvalidDefinitionException: Type id handling not implemented for type java.lang.Object` | – | – |
| `SpikeClaimDetails(…, LocalDate incidentDate, …)` | **fails**: ``Java 8 date/time type `java.time.LocalDate` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"`` | – | – |
| `Optional.of("note")` | **fails** (same as `LocalDate`) | – | – |
| `SpikeRichDecision(enum, Set<Enum>, Optional<String>, LocalDate)` | **fails**: ``Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default`` | – | – |

- The exact exception without the allowlist:
  `dev.langchain4j.agentic.scope.UnserializableAgenticScopeException: Failed to deserialize AgenticScope from JSON. The type 'org.parasol.spike.SpikeReviewDecision' is not allowed for deserialization. To fix this, register the type before deserialization occurs by calling: AgenticScopeSerializer.allowDeserializationType(org.parasol.spike.SpikeReviewDecision.class) or register its package prefix: AgenticScopeSerializer.allowDeserializationPackagePrefix("org.parasol.spike.")`.
  - Its cause is `JsonTypeNotAllowedException`.
  - `toJson` never fails for this. Only `load` after a restart does, so the problem stays hidden until resume time.
- **Why the allowlist behaves this way:**
  - `TypeAllowlist` always allows `java.util.`, `java.math.`, `dev.langchain4j.data.*` and subtypes of
    `Number`/`String`/`Boolean`/`Character`/`Enum`.
  - `DefaultAgenticScopeJsonCodec.java:36-42` adds the agentic internals.
  - Quarkus adds every non-JDK type found in agent method signatures at RUNTIME_INIT
    (`AgenticProcessor.java:537-587` → `AgenticRecorder.java:89-100`, which also sets the class loader).
- **Why `java.time` fails:** the codec mapper is `JacksonChatMessageJsonCodec.chatMessageJsonMapperBuilder()` plus
  `activateDefaultTyping` (`JacksonStateJsonCodec.java:12-20`). It has no JSR-310 or JDK8 modules, and Quarkus
  registers no `StateJsonCodecFactory`.
- **Workaround probe:** `SpikeStateJsonCodecFactory`, registered via
  `META-INF/services/dev.langchain4j.spi.json.StateJsonCodecFactory`, is an `@Internal` SPI that adds
  `JavaTimeModule` and `Jdk8Module`.
  - With it, `LocalDate` (top level) and `SpikeClaimDetails` round-tripped, `equal=true`.
  - `Optional`, top level or in a record, **still failed**.
- **Sample persisted JSON** (suspended run, trimmed). The full row is about 3.4 KB, mostly `agentInvocations` and
  `context`, which carry every prompt and response:

```json
{"memoryId":"q10PlainRootThrowsSuspendedException-…","kind":"PERSISTENT",
 "state":["java.util.concurrent.ConcurrentHashMap",{
   "email":"AUTO-EMAIL my car was rear-ended, policy POL-777",
   "@MemoryId":"q10PlainRootThrowsSuspendedException-…",
   "category":["org.parasol.spike.SpikeCategory","AUTO"],
   "summary":"Rear-end collision at a light","policyNumber":"POL-777",
   "decision":["dev.langchain4j.agentic.internal.SuspendedResponse",{"responseId":"review:q10PlainRootThrowsSuspendedException-…"}],
   "__planner_state_process":["java.util.HashMap",{"cursor":1,"__completedAgents":["java.util.ArrayList",["classify$0"]]}],
   "__planner_state_run$0$1":["java.util.HashMap",{"cursor":2,"__completedAgents":["java.util.ArrayList",["extract$0$0$1","review$1$0$1"]]}]}],
 "agentInvocations":["java.util.Collections$SynchronizedRandomAccessList",[{"agentType":"org.parasol.spike.SpikeHitlClassifier","agentName":"classify","agentId":"classify$0","input":["java.util.HashMap",{"email":"…"}],"output":["org.parasol.spike.SpikeCategory","AUTO"]}, …]],
 "context":[ …user/AI chat messages of every agent… ]}
```

**Implication for #216:**
- **Inference, not run end to end:** an extraction agent that returns a record with a `LocalDate` (e.g.
  `incidentDate`) would make every checkpoint `save` throw.
- To avoid that:
  - either keep scope values free of `java.time`/`Optional` (carry dates as ISO `String`s in agent DTOs and convert
    at the claim boundary)
  - or own an `@Internal` `StateJsonCodecFactory`
- Allowlist the review-decision type (and any other type not in an agent signature) at startup.
- Add a startup self-test that round-trips a sample scope.

### Q14 Eviction and leftover scopes

**Answer:**
- `evictAgenticScope(runId)` deletes the row and returns `true`.
- Evicting again, or evicting an id that never existed, returns `false` without throwing.
- Persistent scopes **stay in the store** after a run that never suspends, and after a resumed run completes, so
  eviction must be explicit.
- Ephemeral roots (no `@MemoryId`) also hit the store: one `save`, then one `delete`, per run.

**Evidence:**
- `q14EvictionAndLeftoverScopes`:
  - the OTHER branch (never suspends) returned `OTHER reply / row present: true`
  - evict, then row present, evict again, evict a missing id: `true -> false / false / false`
  - wave A's ephemeral `SpikeIntakeWorkflow` produced
    `[save org.parasol.spike.SpikeIntakeWorkflow|<uuid> …, delete org.parasol.spike.SpikeIntakeWorkflow|<uuid> …]`
- `q11aSameJvmResumeByReinvocation`: after the resumed run completed, `present (4723 chars)`.
- `SpikeHitlRestartPart2Tests`: `evicted=true row after evict=false`.
- Source:
  - `AgenticScopeRegistry.java:71-82` (`evict`: removes from memory, then `store.delete`; returns
    `delete || removed`)
  - `DefaultAgenticScope.java:243-259` (`rootCallEnded`: EPHEMERAL → evict, PERSISTENT → flush only)
  - `AgenticScopeRegistry.java:54-69` (every create, ephemeral included, calls `update` → `store.save`)

**Implication for #216:**
- The processor must `evictAgenticScope(messageId)` whenever a run ends without suspending: complete, ignored,
  duplicate or failed. The review flow must do the same after the final resume.
- Plan a janitor for orphaned rows, e.g. claims that were closed manually.
- Because the store is global, every agentic call anywhere in the app (ephemeral ones included) costs two DB writes.
  Either keep other agent systems ephemeral-free, or have the store skip agent ids it doesn't own. `save` may return
  `false`.

### Q15 `async = true` combined with suspension

**Answer:** Confirmed: don't combine them. The suspension isn't detected when the async HITL step runs, so the next
agent runs on the unresolved `SuspendedResponse`. The root only suspends at the end, and on resume that downstream
agent runs **again**.

**Evidence:**
- `q15AsyncHitlWithSuspendedResponse` uses `SpikeAsyncReviewGate` (`async = true`) followed by
  `SpikeAsyncAfterAgent`.
- The HITL ran on `VirtualThread[#355]/runnable@ForkJoinPool-1-worker-3`.
- The downstream agent first saw `SuspendedResponse(<pending:async-review>)`. The root then threw
  `AgenticSystemSuspendedException … [async-review]`.
- After `completePendingResponse("async-review", "APPROVED")` and a re-invocation, the result was
  `after:String(APPROVED)`. The downstream agent's calls were
  `[SuspendedResponse(<pending:async-review>), String(APPROVED)]`: it ran twice, once with garbage.
- Source:
  - `AgentExecutor.java:117-131`: async wraps the call in an `AsyncResponse`, so nothing is thrown at the step.
  - `PlannerBasedInvocationHandler.java:431-434, 449-450`: `hasSuspendedResponses` is only checked between steps and
    after the loop.

**Implication for #216:** The HITL agent must use the default `async = false`. Any `async = true` agent placed before
it in the same sequence would be fine. Agents placed after an async HITL would act on placeholder data.

### Changes needed in the #216 task files (from wave B)

- **Task 03 (config/mailbox):**
  - register the DB store with `AgenticScopePersister.setStore` from a `StartupEvent` observer
  - add the `agentic_scope` table and entity (key = `agentId|messageId`, `text` JSON, `updatedAt`)
  - allowlist decision types at startup
- **Task 05 (extraction agents):** agent output types stored in the scope must not contain `LocalDate`/`Optional`
  (use ISO strings), or the task adds an `@Internal` `StateJsonCodecFactory` with `JavaTimeModule`. Opt every agent
  out of RAG and memory (wave A).
- **Task 07 (human review step):**
  - static `@HumanInTheLoop` method returning `Object` (`new SuspendedResponse<>("review:" + scope.memoryId())`)
  - consumers take `Object`; route in typed `@ActivationCondition`s
  - never `async = true`
- **Task 08 (processor):**
  - root method `process(@MemoryId String messageId, …)` with the root interface extending `AgenticScopeAccess`
  - catch `AgenticSystemSuspendedException` → `Pending Review` plus the email
  - evict on every non-suspended ending
  - keep arguments rebuildable
- **Task 10 (review API):**
  - `POST …/review-decisions` → `getAgenticScope(messageId)` (404 on `null`)
  - check the pending id (409)
  - `completePendingResponse`
  - re-invoke with the arguments read from the scope
  - evict on completion; handle a re-suspension (NEEDS_INFO loops)
- **Task 14 (verification):** the restart test needs a database that survives a profile change. The test Postgres
  dev service doesn't, so either use a shared dev-service or compose DB, or carry the row over the way the spike does.
- **Deployment note:** run a single replica, or accept the stale in-memory cache risk (verdict item 6).

## Wave C: observability, startup order and chat memory (Q16–Q19)

New test class: `SpikeObservabilityTests` (5 tests).
- **Workflows:**
  - `SpikeReviewWorkflow` (wave B, unchanged)
  - `SpikeObservedWorkflow`: same topology, plus an `@AgentListenerSupplier` span listener on the root and an
    `@ParallelExecutor` on the extraction step
  - `SpikeMemoryWorkflow`: a `@MemoryId` root whose only sub-agent keeps the implied chat memory
- **Startup probes:** `SpikeStartupProbes` and `SpikeStartupLateBean`, plus the startup-call roots
  (`SpikeStartupHazard/Early/LateWorkflow`).
- **`MonitoredAgent` probes:** `SpikeMonitoredWorkflow`, `SpikeMonitoredDefaultWorkflow` and
  `SpikeMonitoredReviewWorkflow`.
- Spans are captured by wave A's `SpikeSpanCapture` (`SimpleSpanProcessor` + `InMemorySpanExporter`).

### Q16 Span tree, `AgentListener` and Langfuse typing

#### a. No caller span

**Answer:** One email run of the wave-B workflow (stopped at the review suspension) is scattered over **10 traces**:
- one per leaf agent (classify, summary, policy)
- one per scope-store JDBC statement

Nothing links them, and no span exists for any composite agent.

**Evidence:** `q16aSpanTreeWithoutCallerSpan`, trace id → span count:

```
{4e737181=4, 21f2e81c=4, a7386c39=4, 30c90f83=1, 6cbc2ee6=1, 1e8036ce=1, 41405717=1, b72ac7f2=1, e068059c=1, a7f58764=1}
```

Leaf traces, each a root of its own (`kind` INTERNAL unless noted):

```
4e737181  [invoke_agent classify] → langchain4j.aiservices.SpikeHitlClassifier.classify → completion claim-intake-model (gen_ai.operation.name=chat) → POST /chat/completions (CLIENT)
21f2e81c  [invoke_agent summarise] → langchain4j.aiservices.SpikeHitlSummaryAgent.summarise → completion … → POST …
a7386c39  [invoke_agent extractPolicy] → langchain4j.aiservices.SpikeHitlPolicyAgent.extractPolicy → completion … → POST …
single-span traces: SELECT/INSERT/UPDATE quarkus.spike_agentic_scope (CLIENT, parent -)
```

The bracketed `invoke_agent` spans are an unplanned side effect, explained under c below. This workflow declares no
listener.

#### b. With a caller root span

**Answer:** Partly.
- These nest under `claim-intake process` (CONSUMER, `gen_ai.operation.name=invoke_agent`): the leaf agents the
  caller thread runs, and the scope-store JDBC spans.
- **The `@ParallelAgent` sub-agents don't nest.** That is confirmed; each starts its own trace.

**Evidence:** `q16bSpanTreeWithCallerRootSpan` asserted `ai-service span in root trace?` =
`{SpikeHitlClassifier.classify=true, SpikeHitlSummaryAgent.summarise=false, SpikeHitlPolicyAgent.extractPolicy=false}`.

#### c. `AgentListener`

**Answer:** Two different hooks, with very different coverage:
- **CDI `@ApplicationScoped AgentListener` bean:** registered automatically, but **only on AI leaf agents**. It gets
  only `beforeAgentInvocation`/`afterAgentInvocation` for those. It never sees composite agents, non-AI agents or
  the HITL agent, and never gets `afterAgenticScopeCreated` or `onAgenticSystemSuspended`.
- **Listener returned from a static `@AgentListenerSupplier` on the root, with `inheritedBySubagents() == true`:**
  fires for **every** agent (root, sequence, conditional, parallel, AI leaves, the HITL agent), and also gets
  `afterAgenticScopeCreated` and `onAgenticSystemSuspended`.
- An `invoke_agent <name>` span started (and made current) in `before` and ended in `after` builds a correctly
  parented tree.

**Callbacks available in 1.20.2-beta30** (`observability/AgentListener.java`, all `default`):

| Callback | Gets | Notes |
|---|---|---|
| `void beforeAgentInvocation(AgentRequest r)` | `record AgentRequest(AgenticScope agenticScope, AgentInstance agent, Map<String,Object> inputs)` | `agentName()`, `agentId()` |
| `void afterAgentInvocation(AgentResponse r)` | `record AgentResponse(AgenticScope, AgentInstance, Map inputs, Object output, ChatRequest chatRequest, ChatResponse chatResponse)` | chat request/response are only set for AI agents |
| `void onAgentInvocationError(AgentInvocationError e)` | `record AgentInvocationError(AgenticScope, AgentInstance, Map inputs, Throwable error)` | |
| `void afterAgenticScopeCreated(AgenticScope s)` | the scope | |
| `void beforeAgenticScopeDestroyed(AgenticScope s)` | the scope | |
| `void onAgenticSystemSuspended(AgenticScope s)` | the scope | |
| `void beforeAgentToolExecution(BeforeAgentToolExecution t)` | | |
| `void afterAgentToolExecution(AfterAgentToolExecution t)` | | |
| `boolean inheritedBySubagents()` | | default `false` |

- The memory id comes from `agenticScope().memoryId()`; the agent type from `agent().type()`.
- There is **no per-invocation id**. `agentId()` is a structural id, e.g. `classify$0`.

**Evidence:**
- **CDI bean** `SpikeCdiRecordingListener`, plain workflow:
  `before classify … thread=main`, `after classify chatResponse=true`, `before summarise … thread=virtual`,
  `before extractPolicy … thread=virtual`, then two afters. Nothing for `process`/`route`/`run`/`extract`/`review`,
  and no scope or suspend events.
  - Source: `AgenticRecorder.java:213-268`. CDI listeners are added in the `QuarkusAgenticContextConsumer`, which
    upstream only invokes for `AgentBuilder`, i.e. AI agents (`DeclarativeUtil.java:187`). The composite builders
    (`AgenticServices.java:462-560`) don't go through it.
- **Supplied listener** `SpikeSpanAgentListener` on `SpikeObservedWorkflow`, events in order:
  1. `scopeCreated`
  2. before: `process` (SpikeObservedWorkflow), `classify`; after: `classify`
  3. before: `route` (SpikeObservedRouter), `run` (SpikeObservedAutoFlow), `extract` (SpikeObservedExtraction)
  4. before: `extractPolicy`, `summarise`, both `thread=virtual`; after: `summarise`, `extractPolicy`, `extract`
  5. before: `review` (SpikeReviewGate), then after: `review`
  6. `suspended pending=[review:…]` **×3**
- Source:
  - `buildListener` (`DeclarativeUtil.java:258-264`) runs for every composite type via `buildAgentFeatures`.
  - Inheritance: `PlannerBasedInvocationHandler.java:338-360` / `NonAiAgentInstance.java:109-113`.
  - The suspend event is raised once per composite level: `PlannerBasedInvocationHandler.java:232-238`.
- **Suspension leaves spans open.** No `after` fires for the three composites above the gate (`process`, `route`,
  `run`).
  - The listener had to end **3** spans itself (`spans left open at suspension and ended manually: 3`).
  - In production the listener should end everything still open in its first `onAgenticSystemSuspended` call
    (untested).
- **Thread hops are safe.** `before` and `after` of one invocation always ran on the same thread, so a per-thread
  stack of `(span, scope)` is enough. That includes the parallel leaves on their virtual threads.
- **Span tree for the intake run** (`q16cq17ObservedWorkflowWithListenerSpansAndPropagatingExecutor`): everything
  is in one trace, `1f60a8a5`.

```
claim-intake process (CONSUMER, gen_ai.operation.name=invoke_agent)
 ├─ SELECT/SELECT/INSERT spike_agentic_scope            (scope load + create)
 └─ invoke_agent process        [SpikeObservedWorkflow, ended by suspension]
     ├─ invoke_agent classify → langchain4j.aiservices.SpikeHitlClassifier.classify → completion … → POST /chat/completions
     ├─ SELECT/UPDATE spike_agentic_scope                 (checkpoint)
     └─ invoke_agent route      [SpikeObservedRouter, ended by suspension]
         └─ invoke_agent run    [SpikeObservedAutoFlow, ended by suspension]
             ├─ invoke_agent extract  [SpikeObservedExtraction]
             │   ├─ invoke_agent summarise → langchain4j.aiservices.SpikeHitlSummaryAgent.summarise → completion … → POST
             │   └─ invoke_agent extractPolicy → langchain4j.aiservices.SpikeHitlPolicyAgent.extractPolicy → completion … → POST
             ├─ invoke_agent review   [SpikeReviewGate, HITL]
             └─ SELECT/UPDATE spike_agentic_scope         (checkpoint on suspend)
```

- **Resume in a new trace.** A `claim-review decision` span (SERVER) with a **span link** to the intake root
  produced `invoke_agent process → route → run → route (SpikeDecisionRouter) → ready →
  langchain4j.aiservices.SpikeHitlReadyAgent.ready → completion`.
  - On resume, `before` fires only for the agents that actually run again. Classify, extract and review are skipped.
  - The link was recorded: `ImmutableLinkData{spanContext=…traceId=1f60a8a5…}`.

**Trap: shared leaf agents leak listeners and get their ids mangled.**
- Quarkus resolves each leaf sub-agent as **one CDI singleton** shared by every root that lists it
  (`AgenticRecorder.java:172-201`).
- Every root that adopts a leaf mutates it:
  - `setParent` appends `"$" + index` to the leaf's id (`PlannerBasedInvocationHandler.java:100-101` →
    `AgentExecutor.java:240-246`)
  - and attaches the root's inherited listener (`registerInheritedParentListener`)
- Observed: the plain `SpikeReviewWorkflow`, which declares **no** listener, produced the leaf `invoke_agent`
  spans shown in a. The leaf ids had grown to `summarise$0$0$0$1$0$0$0$1$0$0$0$1` (three roots share that leaf).
- **Inference, not tested:** the completed-agent ids saved for a **parallel** step (`__completedAgents`) could stop
  matching after a restart if a different set of roots is built. Today's design never suspends inside a parallel
  step, so this is latent.

#### d. How Langfuse types these observations (source only, not run)

- **quarkus-langfuse 0.7.2 never sets an observation type.**
  - `LangfuseAttributeEnrichingSpanExporter.java:53-72` only adds `langfuse.environment` and copies `gen_ai.prompt`
    / `gen_ai.completion` into `langfuse.trace.input` / `langfuse.trace.output`.
  - `FilteringAISpanExporter.java:56-83` keeps spans that have any `gen_ai.*` attribute other than
    `gen_ai.conversation.id`, plus their ancestors **in the same batch**.
- **The Langfuse server decides the type.** Source: `langfuse/langfuse` `v4.50.0`,
  `packages/shared/src/server/otel/ObservationTypeMapper.ts:166-275, 455-507`. The dev service runs the floating
  `langfuse/langfuse:4` tag. The first mapper that matches wins:
  1. `langfuse.observation.type`: `span|generation|event|embedding|agent|tool|chain|retriever|guardrail|evaluator`
  2. `openinference.span.kind`
  3. `gen_ai.operation.name`:
     - `chat`/`completion`/`text_completion`/`generate_content`/`generate` → GENERATION
     - `embeddings` → EMBEDDING
     - **`invoke_agent`/`create_agent` → AGENT**
     - `execute_tool` → TOOL
  4. Priority 10: a model attribute (`gen_ai.request.model`, `gen_ai.response.model`, …) → GENERATION
  5. Otherwise SPAN.
- **What quarkus-langchain4j emits:**
  - `completion <model>` sets `gen_ai.operation.name=chat` (`SpanChatModelListener.java:55-56`) → GENERATION
  - `langchain4j.tools.*` sets `execute_tool` (`ToolSpanWrapper.java:49-52`) → TOOL
  - `langchain4j.aiservices.*` has no `gen_ai.*` attributes (`SpanWrapper.java:30`) → SPAN. It is exported only as
    an ancestor.
  - Listener spans carrying `gen_ai.operation.name=invoke_agent` (plus `gen_ai.agent.name`) → AGENT. The root span
    with the same attribute → AGENT; set `langfuse.observation.type=chain` on it if a CHAIN looks better.
  - JDBC scope-store spans carry no `gen_ai.*` attributes, so they are dropped from Langfuse but still go to LGTM.

**Implication for #216:**
- One root span per email (CONSUMER) with `gen_ai.operation.name=invoke_agent`. That keeps it in Langfuse.
- An `@AgentListenerSupplier` listener on the root interface, with `inheritedBySubagents()=true`, emits
  `invoke_agent <name>` AGENT spans for every agent. It must end the composite spans left open when the run
  suspends.
- A CDI `AgentListener` bean only covers AI leaves, so it suits metrics, not the span tree.
- The review resume runs in a new trace, with a span link to the intake trace.
- Each leaf agent interface should belong to exactly one root agentic system.

### Q17 Context propagation for `@ParallelAgent`

**Answer:** Yes. This method on the parallel agent interface is accepted by Quarkus build validation:

```java
@ParallelExecutor
static Executor executor() {
    return Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
}
```

With it, both parallel sub-agents share the root trace and are parented under their parallel agent's span. The
global `@Experimental` `ExecutorProvider` was not needed and not tried.

**MDC: yes.** Log lines emitted inside the parallel sub-agents carry the root `traceId` and their own `spanId`. This
was captured with a test log handler, not inferred.

**Evidence:**
- `q16cq17ObservedWorkflowWithListenerSpansAndPropagatingExecutor`: `spans NOT in the root trace: []`. Without
  the executor (`q16b`), `summarise` and `extractPolicy` were separate traces.
- MDC lines, from a JBoss LogManager `Handler` on the root logger reading `ExtLogRecord.getMdcCopy()`:
  - `SPIKE-MDC inside agent summarise | thread= | traceId=1f60a8a574ca8e8afe7bf6c06bf42a8c spanId=ccb6d2b6cbc3fa08`
  - `SPIKE-MDC inside agent extractPolicy | thread= | traceId=1f60a8a574ca8e8afe7bf6c06bf42a8c spanId=837302e146f181ea`
  - The empty thread name means a virtual thread. Main-thread agents logged the same `traceId`.
- The CDI listener's `Span.current()` on the virtual threads was `otelTrace=1f60a8a5` with the executor and
  `00000000` without it.
- Source:
  - `DeclarativeUtil.java:244-249, 357-370` (static, no-arg, `Executor` return type), invoked when the agent is
    built (`ParallelAgentServiceImpl.java:45`, so one executor per parallel agent)
  - `PlannerBasedInvocationHandler.java:484-505` (`parallelExecution` uses that executor, else
    `DefaultExecutorProvider.getDefaultExecutor()`)
  - `AgenticProcessor.java:403-417` (build check)
  - MDC: `MDCEnabledContextStorage.attach` → `OpenTelemetryUtil.setMDCData` → `VertxMDC`, which falls back to an
    inheritable thread-local off the Vert.x threads (`VertxMDC.java:375-400`).

**Implication for #216:** Every `@ParallelAgent` interface declares this `@ParallelExecutor`. No global or
experimental SPI is needed.

### Q18 Earliest startup hook; `MonitoredAgent` cap

**Answer:**
- **Store registration:** a `StartupEvent` observer is enough **as long as it runs before every observer or
  `@Startup` bean that calls an agent root**.
  - Observers run in ascending `@Priority` order.
  - `@Startup` beans run at the default observer priority (`ObserverMethod.DEFAULT_PRIORITY` = 2500,
    `Startup.java:92`), after the registrar.
  - A root first invoked by an observer ordered **before** the registrar never persists, for the life of the JVM.
  - Recommendation: put the registrar in the store bean itself with a clearly-lowest priority, e.g.
    `@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE - 1000) StartupEvent`, and forbid agent calls in
    observers below it.
- **`MonitoredAgent`:**
  - The `AgentMonitor` is created when the root bean is built. That is lazily, before the first call, and it can be
    read in a startup observer.
  - `setMaxRetainedSessions(0)` at startup stops retention of **completed** runs.
  - It does **not** cover suspended runs: each suspended run stays in `ongoingExecutions` until it is resumed. A run
    that is evicted without being resumed stays there **forever**.

**Evidence (`q18StartupOrderingAndMonitor`):**
- Startup order:
  1. `hazard observer @Priority(-100) calls root`
  2. `registrar @Priority(-1) setStore`
  3. `early observer @Priority(0) calls root`
  4. `capMonitor observer @Priority(0)`
  5. `@Startup bean @PostConstruct calls root`
- Rows persisted for those roots: hazard `false`, early `true`, `@Startup` bean `true`. The hazard root made 0
  store calls afterwards too, which matches wave B's Q12.
- **Capped monitor:**
  - The monitor read in the startup observer before the first call (`AgentMonitor@3bbc8c82`) was the same instance
    later (`same instance=true`).
  - After 2 runs: `successful=0 allMemoryIds=0 ongoing=0`.
  - The uncapped default monitor: `successful=2 allMemoryIds=2`.
- **Suspended runs on a capped monitor** (`SpikeMonitoredReviewWorkflow`): `ongoing after 3 suspensions=3`, after
  resuming one `2`, **after evicting all three scopes still `2`**, successful `0`.
- Source:
  - Monitor creation: `AbstractServiceBuilder.java:170-180` and `AgentBuilder.java:230-234`, which create it if the
    interface extends `MonitoredAgent`.
  - `AgentMonitor.java:104-120, 131-143`: `finalizeExecution` only moves an execution that is `done` or errored.
  - `AgentMonitor` overrides neither `onAgenticSystemSuspended` nor `beforeAgenticScopeDestroyed`.

**Trap, found by the full-suite run: the store survives an app restart in the same JVM.**
- `AgenticScopePersister.store` is a static field in a library class. Library classes are loaded by the Quarkus
  **base** runtime class loader, which is kept across app restarts of the same test profile, and across dev-mode
  live reloads. The wave-B logs show the same `Base Runtime ClassLoader …@28737371` for both `SpikeTestProfile`
  starts.
- After a restart, a root called before the registrar runs therefore got the **previous** app's store bean.
- Observed (first full run, `SpikeInjectMockRootTests`, the second start of `SpikeTestProfile`): the hazard observer
  failed startup with `PropertyAccessException: Error accessing field [public java.lang.String
  org.parasol.spike.SpikeAgenticScopeEntity.id] by reflection …` (`Can not get java.lang.String field … on
  org.parasol.spike.SpikeAgenticScopeEntity`). The old store held entity classes from the old class loader.
- The fix: the registrar also observes `ShutdownEvent` and calls `setStore(null)`. With it, the next full run was
  green.

**Implication for #216:**
- The registrar needs a priority below anything that could call an agent, and must reset the store on
  `ShutdownEvent`.
- The IMAP watcher starts later (`@Startup` or a default-priority observer).

**This changes an earlier decision.** "`MonitoredAgent`, capped outside dev with `setMaxRetainedSessions(0)`" no
longer bounds memory. Every review that is abandoned (evicted without being resumed) leaks one `MonitoredExecution`,
which holds the raw email inputs. Options for the user:
- (a) accept the leak, which is bounded by the number of abandoned reviews
- (b) periodically drop stale `ongoingExecutions` entries (untested whether the returned map is live)
- (c) only extend `MonitoredAgent` on a dev-only root (needs a second root interface)

### Q19 Chat memory keying inside a `@MemoryId` workflow

**Answer:**
- **A sub-agent keeps using the shared `"default"` memory, not the root's memory id.** History builds up across
  runs with different memory ids.
- With `@RegisterAiService(chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)` (plus
  `retrievalAugmentor = NoRetrievalAugmentorSupplier.class`) on each sub-agent, every LLM call carries only the
  current message.

**Evidence (`q19ChatMemoryKeyingInsideWorkflow`):**
- `SpikeMemoryWorkflow` (root `@MemoryId`, sub-agent `SpikeCategorizerAgent` without the opt-out) ran with memory
  ids `a`, `b`, `a`. The categorise request bodies were:
  1. `[user "…first customer"]`
  2. `[user "…first customer", assistant "AUTO", user "…second customer"]`, i.e. run `b` saw run `a`'s email
  3. `[user "…first customer", assistant "AUTO", user "…second customer", assistant "AUTO", …]`, still
     accumulating
- Opted-out wave-B sub-agents over two runs for different customers: every classify, summary and policy request had
  exactly one message, `[{"role":"user", …}]`. There was no system message because none is configured, and no
  history.
- **Why:** the Quarkus-generated leaf implementation computes the memory id from the leaf method's own `@MemoryId`,
  then the `DefaultMemoryIdProvider` chain, then `"default"` (`AiServiceMethodImplementationSupport.java:1307-1331`).
  - The root's `@MemoryId` is not consulted.
  - The request-context provider (`RequestScopeStateDefaultMemoryIdProvider.java:19-29`) only gives a per-request
    id while a request context is active (e.g. the REST resume). The IMAP watcher thread would always get
    `"default"`.

**Implication for #216:** This confirms the user's decision that intake agents are stateless.
- Put both opt-outs on **every** AI leaf.
- Pass claim history explicitly as an input.
- Never rely on the root `@MemoryId` to separate conversations.

### Final branch state

- `spike/agentic-hitl` (local only, never pushed) has three commits on top of `main` `c39dadb`:
  - wave A `eaff920`
  - wave B `f6906aa`
  - wave C **`64b63b6`**: the final branch commit
- The final full run of `./mvnw -B test -Dtest='org.parasol.spike.*Tests'` (with
  `-Dquarkus.http.test-port=0 -Dquarkus.http.test-ssl-port=0`) had 32 tests and 0 failures, across 7 test classes
  and 6 Quarkus app starts.
- The worktree `/Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-agentic` was removed with
  `git worktree remove` after the wave C commit. The branch is kept.
- To inspect the code: `git log spike/agentic-hitl`, or `git worktree add <path> spike/agentic-hitl`.
