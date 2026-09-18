# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**"Non-Deterministic? No Problem!"** is a demo application (Parasol Insurance) that shows how to
test and continuously evaluate non-deterministic AI systems.

It is a **single Quarkus application** (`org.parasol:parasol-app`, Java 25) with a React/PatternFly
frontend served via Quinoa. There are **no sub-modules** — one `pom.xml` at the root.

The Java source is split into two top-level packages representing two distinct concerns:

| Package | Concern |
|---|---|
| `org.parasol` | The insurance claims business application (claims REST API, AI chat bot, email notification, guardrails) |
| `ai.scoring` | The reusable AI-quality layer (Langfuse integration, session scoring, drift detection) |

Supporting docs:
- `README.md` — architecture image, Ollama profiles, Langfuse integration notes
- `langfuse-evaluation.md` — **the** design document: the three-tier evaluation strategy, Langfuse
  platform/language gaps, and the workarounds implemented here. Read this before touching anything
  under `ai.scoring.langfuse`.
- `docs/*.puml` — PlantUML architecture and sequence diagrams

## Commands

All Maven commands run from the repository root via the wrapper.

```bash
# Dev mode (OpenAI, default) — requires OPENAI_API_KEY (and COHERE_API_KEY for judge/sentiment)
./mvnw quarkus:dev

# Dev mode against a local Ollama
./mvnw -Pollama quarkus:dev

# Dev mode against Ollama via its OpenAI-compatible endpoint
./mvnw -Pollama-openai quarkus:dev

# Unit tests
./mvnw test

# A single test class
./mvnw test -Dtest=PolitenessOutputGuardrailTests

# Full verify (unit + integration tests)
./mvnw verify

# Exercise the drift-detection tests (otherwise skipped — see Testing below).
# Needs a reachable Langfuse with populated datasets.
./mvnw verify -Dquarkus.test.profile=drift

# Build, skipping tests
./mvnw package -DskipTests

# Run the built app outside dev mode
java -Dquarkus.profile=ollama,prod -jar target/quarkus-app/quarkus-run.jar
```

### Frontend (from `src/main/webui/`)

```bash
npm test        # Jest
npm run build   # production build into dist/
```

Quinoa builds the frontend as part of the Maven build — you rarely need to run npm directly.

### CI

`.github/workflows/simple-build-test.yml` runs `./mvnw -B clean verify` on Java 25 across the
`ollama` and `ollama-openai` profiles. CI has no real OpenAI/Cohere/Gemini credentials, so **any new
test must pass under the Ollama profiles**.

## Architecture

### Business application — `org.parasol`

**AI services** (Quarkus LangChain4j `@RegisterAiService`):

- `ClaimService` — the chat bot. `@ChatScoped` (session-scoped conversation), exposed as a chat
  route (`@ChatRoute("chat")` / `@DefaultChatRoute`) over the websocket chat-routes endpoint
  `/_chat/routes` provided by `quarkus-langchain4j-chat-scopes-websocket`. Uses RAG over
  `src/main/resources/policies/policy-info.pdf` (Easy RAG, embeddings reused via
  `easy-rag-embeddings.json`) and a `@ToolBox(NotificationService.class)`. Annotated with
  `@OutputGuardrails(DriftDetectionOutputGuardrail.class)`, which is a no-op unless
  `interaction-mode` is `DRIFT_DETECTION`.
- `GenerateEmailService` — generates a `{subject, body}` `Email` record; four output guardrails.
- `PolitenessService` — AI-backed politeness check used by `PolitenessOutputGuardrail`.

**Output guardrails** on `GenerateEmailService` (all extend `GenerateEmailOutputGuardrail`):
`EmailContainsRequiredInformationOutputGuardrail`, `EmailStartsAppropriatelyOutputGuardrail`,
`EmailEndsAppropriatelyOutputGuardrail`, `PolitenessOutputGuardrail`.

**Email flow:** the chat bot calls the `NotificationService.updateClaimStatus` tool → updates the
`Claim` Panache entity → `GenerateEmailService` produces the email → sent via Quarkus Mailer
(Mailpit dev service in dev/test).

**REST:** `ClaimResource` — `GET /api/db/claims`, `GET /api/db/claims/{id}` (Panache entities on
PostgreSQL).

**Frontend:** React + TypeScript + PatternFly in `src/main/webui/src/app/`, SPA routing enabled.

### AI quality layer — `ai.scoring`

Configuration is driven by `ScoringConfig` (`quarkus.aiscoring.*`) and `LangfuseConfig`
(`quarkus.aiscoring.langfuse.*`), both `@ConfigMapping` interfaces.

`InteractionMode` (default `NORMAL`) selects whether tier 3 is armed:

- `NORMAL` — `DriftDetectionOutputGuardrail.validate` returns `success()` immediately.
- `DRIFT_DETECTION` — the guardrail actually evaluates and can fail the response.

Tiers 1 and 2 are independent of this switch; they are controlled by the
`quarkus.aiscoring.langfuse.evaluation.*` flags instead.

The three evaluation tiers (see `langfuse-evaluation.md` for the full rationale):

1. **Per-trace** — native Langfuse LLM-as-a-Judge. `LangfuseEvaluationInitializer` programmatically
   creates the Langfuse model, LLM connection, score config, evaluator, and evaluation rule on a
   `StartupEvent`, gated on `initialize-on-startup`. It uses the `LangfuseOperations` layer
   (`createIfAbsent` / `upsert` / `findByName`) from quarkus-langfuse, dropping to the raw
   `langfuse.api()` client only for the evaluator update call, which the operations layer does not
   cover.
2. **Session-level** — Langfuse has no session evaluation target, so this is custom code.
   `ConversationalBaggageHandler` observes `ChatScopeStarted/Activated/Deactivated/Ended`,
   propagates the conversation id as OTel baggage (`gen_ai.conversation.id`), and on session end
   hands off to `SessionScoringService` on a background executor.
   `LangfuseSessionScoringService` polls Langfuse until the session's observations are ingested
   (OTel flush + async ClickHouse ingest), reconstructs `ConversationExchange`s, asks
   `SessionSentimentService` for a `SessionSentiment`, and posts the score back with
   `langfuse.scores().create(...)` — deliberately the synchronous tree, because the score must land
   before `scoreSession` ends the enclosing `ComputeSessionScore` span. The observation query is a
   typed `ObservationFilter` (`sessionId` + `fields("core,basic,io")`) handed to
   `async().observations().matching(filter).findAll()`; `findAll()` pages through *all* matching
   observations, so a session is no longer capped at one page. That call is wrapped in
   `Uni.createFrom().deferred(...)`, which is load-bearing rather than stylistic: it is the retried
   step of the polling pipeline and must re-query on every re-subscription, otherwise each retry
   replays the first (empty) response and the session silently goes unscored. It also records the
   exchanges as a Langfuse dataset, gated on `create-dataset-on-session-close`: dataset names are
   deduplicated and created sequentially through `async().datasets().createIfAbsent(...)` (not
   atomic, so concurrent creation of the same name would race it against itself), then the dataset
   items are created in parallel through `async().datasetItems().create(...)`.
3. **Drift detection** — `DriftDetectionOutputGuardrail` runs the quarkus-langchain4j evaluation
   framework (`Evaluation.withSamples(<datasetName>)`) and returns a `fatal` result carrying a
   `DriftDetectionException` when the score falls below `quarkus.aiscoring.threshold`.
   `DriftDetectionChatRouteExceptionHandler` turns that into a `DRIFT DETECTED!!!` chat-route error
   instead of a stack trace.

   Two pieces plug into that framework:
   - `LangfuseDatasetSampleLoader` (`SampleLoader<String>`) supplies the samples. It is **not**
     called directly — it is discovered via `ServiceLoader` through
     `src/main/resources/META-INF/services/io.quarkiverse.langchain4j.testing.evaluation.SampleLoader`,
     grabs `LangfuseOperations` via `CDI.current()`, checks the dataset exists with
     `datasets().findByName(...)` in `supports`, and reads the items with
     `datasetItems().matching(DatasetItemFilter...).streamAll()` — no hand-written page arithmetic.
     Two non-obvious constraints: `DatasetItemFilter` has no status criterion, so the
     `DatasetStatus.ACTIVE` filter necessarily stays client-side; and `streamAll()` is lazy, so the
     terminal `.toList()` has to run *inside* the `try` or the `LangfuseNotFoundException`-to-empty-list
     behaviour silently stops working.
   - `Evaluator` (`EvaluationStrategy<String>`) scores them by delegating to the `EvaluatorAgent`
     AI service (the `judge` model). It is the only `EvaluationStrategy` bean in the project —
     despite `quarkus-langchain4j-testing-evaluation-semantic-similarity` being on the classpath,
     nothing currently wires up a semantic-similarity strategy.

**Dataset naming — the critical invariant.** The dataset name is the LangChain4j AI service span
name verbatim: `langchain4j.aiservices.<AiServiceClassName>.<methodName>`.

- **Write side:** `AiServiceDatasetSpanProcessor` (an OTel `SpanProcessor`) stamps
  `langfuse.dataset.name`, `ai.service.class`, and `ai.service.method` onto the AI service root span
  and cascades them to every descendant (tool executions, model generations).
- **Read side (tier 2):** `ConversationExchange` resolves the name with a five-step fallback —
  attributes on the observation → attributes on an ancestor → name of the nearest
  `langchain4j.aiservices.*` ancestor → trace name → the observation's own name. It also probes
  three different metadata shapes (`langfuse.dataset.name`, `attributes.langfuse.dataset.name`, and
  a nested `attributes` map) because Langfuse's OTLP ingestion has changed shape across releases.
- **Read side (tier 3):** `DriftDetectionOutputGuardrail` rebuilds the name from the LangChain4j
  `InvocationContext` (simple interface name + method name).

All sides share `AiServiceAttributes.AI_SERVICES_PREFIX` — **if you change the naming on one side,
change it on the others or drift detection silently finds no samples** (it returns `success` on
`SampleLoadException`, so a broken name looks like a pass, not a failure).

### Models

Model names are configured in `src/main/resources/application.yml` and resolve per profile:

| Model name | Used by | Default (OpenAI profile) |
|---|---|---|
| `parasol-chat` | `ClaimService` | `gpt-5-mini` |
| `generate-email` | `GenerateEmailService` | `gpt-5-mini` |
| `politeness` | `PolitenessService` | `gpt-5-mini` |
| `session-sentiment` | `SessionSentimentService` | Cohere `command-r7b-12-2024` via its OpenAI-compatible endpoint |
| `judge` | `EvaluatorAgent` (drift detection) | Cohere `command-r7b-12-2024` via its OpenAI-compatible endpoint |

Note the Cohere models are reached through the **OpenAI** extension pointed at
`https://api.cohere.ai/compatibility/v1` with `COHERE_API_KEY` — there is no Cohere-specific
extension in the build.

There are **two separate judges**, which is easy to confuse:
- The in-app `judge` model above, driving `EvaluatorAgent` → `Evaluator` for tier-3 drift detection.
- The Langfuse-side LLM-as-a-Judge evaluator for tier 1, which runs inside Langfuse using **Google
  Gemini** (`gemini-2.5-flash`, `LlmAdapter.GOOGLE_AI_STUDIO`) configured by
  `LangfuseEvaluationInitializer` from `LangfuseConfig.Evaluation.Gemini`.

Embeddings use the OpenAI embedding model by default (`quarkus.langchain4j.embedding-model`).

Under `-Pollama` / `-Pollama-openai` every chat model maps to `llama3.2:latest`, embeddings to
`snowflake-arctic-embed`, the OpenAI/Cohere API keys are stubbed to `changeme`, and Langfuse
session scoring + startup initialization are disabled.

### Configuration profiles

`src/main/resources/application.yml` is the single config source. Notable profiles:

| Profile | Purpose |
|---|---|
| `dev` | LGTM dev service, Mailpit, PostgreSQL dev service, Langfuse dev service |
| `test` | Observability + Langfuse init/session scoring disabled |
| `ollama`, `ollama-openai` | Local Ollama; Langfuse session scoring/init disabled |
| `drift` | Parent `langfuse-ocp`; sets `quarkus.aiscoring.interaction-mode: drift-detection` |
| `langfuse-ocp` | Points at a deployed Langfuse instance; disables OTLP export and LGTM |
| `instana` | Exports OTLP to Instana instead of LGTM |
| `prod`, `openshift` | Schema drop-and-create + `import.sql`; OpenShift deployment config |

### Environment variables

| Variable | When it is needed |
|---|---|
| `OPENAI_API_KEY` | Default profile — `parasol-chat`, `generate-email`, `politeness`, embeddings |
| `COHERE_API_KEY` | Default profile — `session-sentiment` and `judge` |
| `GEMINI_API_KEY` | Only when `LangfuseEvaluationInitializer` runs (`initialize-on-startup`); it throws `IllegalStateException` if absent |

Under `-Pollama` / `-Pollama-openai` none of these are required.

### Observability

OpenTelemetry + Micrometer. Dev mode uses the LGTM dev service (Grafana/Loki/Tempo/Mimir).
Traces are also exported to Langfuse via the `quarkus-langfuse` extension.

## Testing

Test layout mirrors main: `src/test/java/org/parasol/...` and `src/test/java/ai/scoring/...`.

- `@QuarkusTest` + `@InjectMock`/`@InjectSpy` + `dev.langchain4j.test.guardrail.GuardrailAssertions`
  for guardrail tests.
- REST Assured for REST; the websocket chat-routes client (`WebsocketChatRoutes.newClient(...)`)
  for chat tests (`ClaimWebsocketChatBotTests`).
- Playwright E2E tests in `org.parasol.ui`, extending the `@WithPlaywright` `PlaywrightTests` base
  class (records video to `target/playwright`).
- `ai.scoring.it.DriftDetectionTests` is annotated with the custom meta-annotation
  `@DriftDetectionTest`, which combines `@QuarkusTest` with
  `@EnabledIfApplicationProperty(named = "quarkus.aiscoring.interaction-mode", matches = "drift-detection")`
  — the supporting JUnit condition classes live in `src/test/java/io/quarkus/test/junit/`. These
  tests are skipped unless the `drift` profile is active.
- **Mocking LLM calls:** the Quarkus WireMock dev service (`@ConnectWireMock` + an injected
  `WireMock`). A `QuarkusTestProfile` repoints every relevant `quarkus.langchain4j.openai.*.base-url`
  (including `parasol-chat`, `session-sentiment`, `judge`) at
  `http://localhost:${quarkus.wiremock.devservices.port}/v1`, then stubs the
  OpenAI-compatible `/chat/completions` endpoint. Stubs are registered programmatically in
  `@BeforeEach` — there is no `src/test/resources` directory.
- **Langfuse is not mocked.** `DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests`
  and `LangfuseSessionScoringServiceTests` inject the real `LangfuseOperations` against the Langfuse
  dev service, creating and tearing down real datasets/items around each test. Their fixtures and
  assertions still go through `api()` (`api().datasetItems()`, `api().observations()`,
  `api().scoresV3()`) — that is test-fixture code that predates the operations layer covering those
  domains, not a gap in the layer.
- AssertJ + Awaitility; Mockito is attached as a `-javaagent` in the surefire/failsafe config.

## Conventions

Beyond the global Java/Quarkus style rules in `AGENTS.md`, this repo specifically uses:

- **Tabs for indentation**, tab width 2, max line length 180 (see `.editorconfig`). Note
  `insert_final_newline = false`.
- `io.quarkus.logging.Log` static methods (`Log.debugf`, `Log.warnf`) — no `Logger` fields.
- `@ConfigMapping` interfaces with `@WithDefault` — not `@ConfigProperty` fields.
- Constructor injection in `ai.scoring`; `org.parasol.ai.NotificationService` still uses `@Inject`
  fields (legacy — prefer constructor injection for new code).
- Hand-written fluent builders (e.g. `DriftDetectionException.builder()`), records for value types.
- `Optional` chains over null checks and guard-clause early returns.

## Gotchas

- **Older revisions of this file described a two-module layout** (`parasol-app/` + `ai-scorer/`)
  communicating over a REST contract in `openapi/ai-interactions.yml`, with an
  `InteractionPublisher`/`InteractionScorer` pipeline and a `RESCORE` interaction mode. **None of
  that exists any more** — it collapsed into this single app scoring against Langfuse in-process,
  and `InteractionMode` is now `NORMAL`/`DRIFT_DETECTION`. Do not reintroduce references to it.
- **The quarkus-langfuse operations layer (`LangfuseOperations`) covers broadly, but not
  everything.** Prefer it (`createIfAbsent`, `upsert`, `findByName`/`findByProvider`, `matching`,
  `streamAll`) wherever it exists — it replaced the hand-rolled lookup-then-create helpers and
  pagination loops this app used to carry. The residual gaps that still force `langfuse.api()`:
  - **No `update` on any domain except annotation queue items.** That is precisely why
    `LangfuseEvaluationInitializer.updateEvaluatorModel` stays on
    `api().evaluators().evaluatorsUpdate(...)` — it is not an oversight waiting to be tidied up.
  - **Traces and sessions are not covered, deliberately.** They remain reachable only via `api()`.
    Per the Langfuse upstream documentation these endpoints are deprecated, with a Langfuse Cloud
    removal date of 16 November 2026 — that date is not asserted anywhere in the extension sources,
    so treat it as an upstream claim to re-check rather than a verified project fact.
  - **`/api/public/unstable/` endpoints (dashboards, dashboard widgets) are excluded** as a
    standing project rule.
- **Log Langfuse failures with `LangfuseApiException.getStatusCode()`/`getServerMessage()`, not
  `getMessage()`** — `getMessage()` carries the entire raw JSON response body behind a
  `Langfuse API error (404): ` prefix. The broad trailing `catch (Exception)` blocks in
  `LangfuseEvaluationInitializer` are intentional: they run inside `onStartup(@Observes StartupEvent)`,
  where an escaping exception aborts application boot.
- **The observation `fields` parameter is unvalidated free-form text** — an unknown field group is
  silently ignored, not rejected with a 400 — which is how the earlier `meta` typo survived so long
  (the group is spelled `metadata`). `LangfuseSessionScoringService.fetchSessionObservations`
  requests `"core,basic,io,metadata"`, which is exactly what the code consumes: `core`/`basic` carry
  `startTime` and `parentObservationId` (`isCompleteExchange`, `ConversationExchange.hierarchyOf`),
  `io` carries input/output (`isCompleteExchange`, `ConversationExchange.from`), and `metadata`
  feeds steps 1-2 of `ConversationExchange.resolveDatasetName`, which read
  `observation.getMetadata()` and fall back to the span-name-based step 3 when metadata is absent.
  Note `io` does **not** include metadata — that is the separate `metadata` group.
- `conversation-export.md` (1700+ lines) is a raw transcript of the design conversation behind
  `langfuse-evaluation.md`. It is a historical artifact, not documentation — don't treat it as
  spec and don't try to keep it in sync.
- Langfuse online evaluators historically had to be created by hand in the UI; this repo creates
  them via the Langfuse API in `LangfuseEvaluationInitializer`. Langfuse's own `LANGFUSE_INIT_*`
  env vars do **not** cover evaluators.
- Session scoring is inherently racy against Langfuse's async ingest. The wait/poll knobs live
  under `quarkus.aiscoring.langfuse.evaluation.session` (`otel-flush-wait-time`,
  `observation-poll-interval`, `observation-max-wait-time`, `dataset-creation-max-wait-time`).
  If session scores go missing, tune
  these before suspecting the scorer.
- `NotificationService.sendEmail` deliberately moves the mailer onto `ForkJoinPool.commonPool()`
  with a 15s timeout — the reactive mailer blocks the calling (tool-execution) thread and deadlocks
  otherwise. Likewise `updateStatusIfFound` uses `QuarkusTransaction.joiningExisting()` because the
  tool runs inside the AI service's invocation context.
- The `langfuse-ocp` profile in `application.yml` contains a **committed Langfuse public and secret
  key** pointing at a demo OpenShift cluster. There is a `.gitleaks.toml` at the root. Do not copy
  this pattern for new credentials.
- **Two different score scales meet in `DriftDetectionOutputGuardrail`.**
  `quarkus.aiscoring.threshold` defaults to `0.75` (a 0–1 fraction), but `EvaluationReport.score()`
  is `100.0 * passed / total` — a **pass-rate percentage**, not an average of the judge's
  confidence. The guardrail divides by `100.0` to reconcile them. Consequently the per-sample
  `EvaluatorResult.score()` returned by `EvaluatorAgent` never reaches the threshold comparison
  directly; only its boolean `verdict` (via `EvaluationResult.passed()`) moves the needle.
