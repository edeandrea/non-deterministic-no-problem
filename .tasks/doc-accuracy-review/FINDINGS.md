# Verified Architecture Baseline

Source of truth for rewriting diagrams/docs. Every statement below was read out of
`src/main/java`, `src/main/resources/application.yml`, `pom.xml` or `src/main/webui`.
Single Maven module `org.parasol:parasol-app` (Java 25, Quarkus 3.39.3,
quarkus-langchain4j 1.13.1, quarkus-langfuse 0.7.2). No submodules, no REST hop between
business app and scorer — everything is in-process.

> NOTE: There are **no input guardrails anywhere** in the codebase. Any diagram box
> labelled "Input Guardrails" is fiction.

## 1. Business flow (`org.parasol`)

### Entry points
- `org.parasol.resources.ClaimResource` — plain Jakarta REST, `@Path("/api/db/claims")`,
  `@Produces(APPLICATION_JSON)`. Two endpoints: `GET /api/db/claims` -> `Claim.listAll()`
  and `GET /api/db/claims/{id}` -> `Claim.findById(id)`. No POST/PUT/DELETE, no chat endpoint.
- Chat is **not** REST. `src/main/webui/.../Chat/Chat.tsx` opens a WebSocket to
  `<backend>/_chat/routes` (derived from `config.backend_api_url`, `http`->`ws`, `/api` stripped)
  using the `ChatScopesClient` shipped by `quarkus-langchain4j-chat-scopes-websocket`,
  and connects to route `"chat"`. It sends `{ query: { claimId, query, claim, inceptionDate } }`,
  i.e. the `ClaimBotQuery` record.

### `Claim` entity
`org.parasol.model.claim.Claim extends PanacheEntity`, `@Entity @Table(name="claims")`,
`@JsonNaming(SnakeCaseStrategy.class)`. Public fields: `claimNumber, category, policyNumber,
inceptionDate (LocalDate), clientName, subject, body(5000), summary(5000), location,
time (@Column "claim_time"), sentiment(5000), emailAddress, status`.

### `ClaimService` (`org.parasol.ai.ClaimService`)
```java
@RegisterAiService(modelName = "parasol-chat")
@ChatScoped
@OutputGuardrails(DriftDetectionOutputGuardrail.class)
public interface ClaimService {
  @SystemMessage(...) @UserMessage(...)
  @DefaultChatRoute @ChatRoute("chat")
  @ToolBox(NotificationService.class)
  String chat(ClaimBotQuery query);
}
```
- Model name `parasol-chat`; RAG is **on** — it is the only AI service that does *not* set
  `retrievalAugmentor = NoRetrievalAugmentorSupplier.class`, so the Easy-RAG augmentor applies
  (`quarkus.langchain4j.easy-rag.path: policies`, classpath, `reuse-embeddings.enabled: true`,
  embedding model `quarkus.langchain4j.embedding-model.provider: openai`).
- Toolbox: `NotificationService` (single tool class).
- Guardrail: `DriftDetectionOutputGuardrail` (class-level, so applies to `chat`).
- `Multi<String> chat(...)` streaming variant exists only as a commented-out line — dead.
- `@ChatScoped` is what makes `ChatScopeStarted/Activated/Deactivated/Ended` CDI events fire,
  which is the tier-2 trigger.

### `NotificationService` (`@ApplicationScoped`)
One tool method:
```java
@Tool("Update the status of a claim. ... Do not decide on your own to update the status.")
@WithSpan("NotificationService.updateClaimStatus")
String updateClaimStatus(@SpanAttribute("arg.claimId") long claimId,
                         @SpanAttribute("arg.status") String status)
```
Flow: reject status shorter than 3 chars (`INVALID_STATUS`) -> `updateStatus` ->
`updateStatusIfFound` -> `sendEmail`.
- Transaction workaround: `QuarkusTransaction.joiningExisting().call(...)` around
  `Claim.findByIdOptional` + capitalise-first-letter + `Claim.persist`. Needed because the tool
  executes inside the AI service invocation, not inside a container-managed transaction.
  `quarkus.transaction-manager.default-transaction-timeout: 3m` backs the long LLM call.
- Threading workaround, verbatim comment: *"Need to move this to another thread because the
  mailer blocks the thread...which causes deadlock"* — `mailer.send(email)
  .runSubscriptionOn(ForkJoinPool.commonPool()).await().atMost(Duration.ofSeconds(15))`.
- Returns a sentence back to the LLM: `NOTIFICATION_SUCCESS` / `NOTIFICATION_NO_CLAIMANT_FOUND`.
- From `noreply@parasol.com`, uses `io.quarkus.mailer.reactive.ReactiveMailer`.

### `GenerateEmailService`
`@RegisterAiService(modelName = "generate-email", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)`.
Input `ClaimInfo(clientName, claimNumber, claimStatus)`, output `Email(subject, body)` record.
System message demands raw JSON (`JSON_STRUCTURE`) and a fixed `EMAIL_ENDING` signature block;
user message forces `"Dear {{claimInfo.clientName}},"` opening.
Four output guardrails in declaration order:
`EmailContainsRequiredInformationOutputGuardrail`, `EmailStartsAppropriatelyOutputGuardrail`,
`EmailEndsAppropriatelyOutputGuardrail`, `PolitenessOutputGuardrail`.

### Guardrails under `org/parasol/ai/guardrail/`
- `GenerateEmailOutputGuardrail` (abstract) extends
  `dev.langchain4j.guardrails.JsonExtractorOutputGuardrail<Email>`; overrides
  `getInvalidJsonMessage`/`getInvalidJsonReprompt` (reprompts with `JSON_STRUCTURE`).
  All four concrete email guardrails extend it and are `@ApplicationScoped`.
- `EmailContainsRequiredInformationOutputGuardrail` — overrides **both**
  `validate(OutputGuardrailRequest)` (reads `claimInfo` from `request.requestParams().variables()`,
  checks body contains client name (case-sensitive), claim number and status (case-insensitive via
  `StringUtils.containsIgnoreCase`)) and `validate(AiMessage)` (subject/body non-blank).
- `EmailStartsAppropriatelyOutputGuardrail` — body must `startsWith("Dear ")`.
- `EmailEndsAppropriatelyOutputGuardrail` — body must `endsWith(GenerateEmailService.EMAIL_ENDING)`.
- `PolitenessOutputGuardrail` — constructor-injects `PolitenessService` and calls
  `isPolite(email.body())`; reprompts if false.
- `PolitenessService` — `@RegisterAiService(modelName = "politeness", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)`,
  `boolean isPolite(@UserMessage String query)`, few-shot system prompt returning a bare boolean.
- `StringUtils` — case-insensitive `contains` helper.

> NOTE: `PolitenessService` is an **LLM call made from inside a guardrail**, i.e. the email path
> can fan out to a second model per validation attempt. Diagrams must show this edge.

### End-to-end sequence (chat message -> email sent)
1. Browser `Chat.tsx` -> WebSocket `/_chat/routes`, route `"chat"`, payload `ClaimBotQuery`.
2. `quarkus-langchain4j-chat-scopes-websocket` opens the chat scope -> `ChatScopeStarted`/
   `ChatScopeActivated` observed by `ConversationalBaggageHandler` (puts
   `gen_ai.conversation.id` into OTel Baggage).
3. `ClaimService.chat(query)` invoked. Easy-RAG augments the prompt from `policies/`.
   `AiServiceDatasetSpanProcessor` stamps `langfuse.dataset.name = langchain4j.aiservices.ClaimService.chat`
   on the root span and cascades to children.
4. Model (`parasol-chat`) may emit a tool call -> `NotificationService.updateClaimStatus`.
5. `updateClaimStatus` -> `updateStatusIfFound` (join existing tx, persist new status) ->
   `sendEmail`.
6. `sendEmail` -> `GenerateEmailService.generateEmail(ClaimInfo)` -> four output guardrails, one of
   which calls `PolitenessService.isPolite` (another LLM call); failures reprompt the model.
7. `ReactiveMailer.send` on `ForkJoinPool.commonPool()`, 15 s cap -> SMTP (Mailpit in dev/test).
8. Tool result string returns to the model, which produces the final chat answer.
9. `DriftDetectionOutputGuardrail` validates the `ClaimService.chat` output (no-op unless
   `interaction-mode = DRIFT_DETECTION`).
10. Answer streams back over the WebSocket. On scope close `ChatScopeEnded` fires -> tier 2.

## 2. Evaluation layer (`ai.scoring`)

Config roots: `ScoringConfig` (`@ConfigMapping(prefix = "quarkus.aiscoring")`, `interactionMode`
default `NORMAL`, `threshold` default `0.75`) and `LangfuseConfig`
(`@ConfigMapping(prefix = "quarkus.aiscoring.langfuse")`).
`InteractionMode` has exactly two constants: `NORMAL`, `DRIFT_DETECTION`.

### Tier 1 — startup provisioning: `LangfuseEvaluationInitializer`
`@Singleton`, constructor takes `LangfuseConfig` + `io.quarkiverse.langfuse.api.LangfuseOperations`.
`void onStartup(@Observes StartupEvent)` runs only if
`quarkus.aiscoring.langfuse.evaluation.initialize-on-startup` (default `true`). Order:
1. `getOrCreateSessionSentimentScoreConfig()` — `scoreConfigs().createIfAbsent` a CATEGORICAL score
   config named `session-sentiment` (`SessionSentiment.SCORE_NAME`) with categories built from the
   `Sentiment` enum (POSITIVE 1.0 / NEUTRAL 0.5 / NEGATIVE 0.0).
2. `getOrRegisterCohereModelDefinition()` — `models().createIfAbsent` a pricing definition
   `command-r7b`, match pattern `(?i)^(command-r7b)(-.+)?$`, TOKENS, input 4e-8 / output 1.5e-7.
3. `getOrCreateGeminiLlmConnection()` -> `handleEvaluator(...)` -> `getOrCreateEvaluationRule(...)`:
   - `llmConnections().findByProvider("google-ai-studio")` or `upsert` with
     `quarkus.aiscoring.langfuse.evaluation.gemini.api-key` (default `${GEMINI_API_KEY:}`) and
     `...gemini.model-name` (default `gemini-2.5-flash`). Missing key -> `IllegalStateException`,
     deliberately rethrown (aborts boot); API errors are caught and logged.
   - `handleEvaluator` first ensures a NUMERIC (0..1) score config named
     `"Continuous Evaluation Evaluator"`, then `findExistingEvaluator()` (by name, case-sensitive,
     filtered to LLM-as-a-judge) -> `ensureEvaluatorModel` (re-points model config if it drifted)
     or `createEvaluator` (LLM-as-a-judge with the relevance `PROMPT`, variable mapping
     `query<-INPUT`, `generation<-OUTPUT`, numeric 0..1 output definition).
   - `createEvaluationRule` — `evaluationRules().createIfAbsent`, name `"Continuous Evaluation
     Evaluator"`, `enabled=true`, `sampling=1.0`, two NONE_OF filters: `environment` not in
     (`langfuse-llm-as-a-judge`, `llm-as-judge`) — i.e. the judge must not score itself — and
     `type` not in (`SPAN`, `EVENT`) so only generations are scored.

> NOTE: Tier 1 provisions Langfuse *server-side* objects. The relevance judging itself runs
> **inside Langfuse using Gemini**, not in the app. The app never calls Gemini.

### Tier 2 — session scoring (async, after the conversation ends)
Participants: `ConversationalBaggageHandler` -> `SessionScoringService` (interface, single method
`void scoreSession(String conversationId)`) -> `LangfuseSessionScoringService` -> `ConversationExchange`
-> `SessionSentimentService` (+ `SessionSentimentGuardrail`, `SessionSentiment`).

Sequence:
1. `ConversationalBaggageHandler` (`@ApplicationScoped`) observes four CDI events from
   `io.quarkiverse.langchain4j.chatscopes`: `ChatScopeStarted` (registers the id in an internal
   `ConversationTracker` unless baggage already carries one), `ChatScopeActivated` (pushes
   `gen_ai.conversation.id` into OTel `Baggage` and keeps the `Scope`), `ChatScopeDeactivated`
   (closes the baggage scope), `ChatScopeEnded`.
2. On `ChatScopeEnded`: tracker entry removed, then the thread handoff —
   `Infrastructure.getDefaultExecutor().execute(() -> sessionScoringService.scoreSession(id))`.
   Everything below runs on that background worker thread.
3. `LangfuseSessionScoringService.scoreSession` starts an INTERNAL span `"ComputeSessionScore"`
   via the injected OTel `Tracer`, makes it current, calls `fetchAndScoreSession`, ends it in
   `finally`.
4. `awaitSessionObservations(conversationId)` — Mutiny pipeline collapsed with `await()`:
   delay `otel-flush-wait-time` (5s) -> `fetchSessionObservations` (`langfuse.async().observations()
   .matching(ObservationFilter.sessionId(id).fields("core,basic,io,metadata")).findAll()`, wrapped in
   `Uni.createFrom().deferred(...)` so each retry re-queries) -> if no observation has both input and
   output, fail with the private `ObservationsNotReadyException` -> `retry().withBackOff(interval,
   interval)` (constant 2s) `.expireIn(observation-max-wait-time` 30s`)` -> specific recovery to
   `List.of()` with a "Gave up waiting" warning -> **general** `onFailure().recoverWithItem(...)`
   after it -> terminal `await().atMost(flushWait + maxWait + 5s)` guarded by
   `catch (io.smallrye.mutiny.TimeoutException)`.
5. `ConversationExchange.hierarchyOf(observations)` indexes observations by id; each observation
   with startTime+input+output is sorted by startTime and mapped via `ConversationExchange.from`.
6. If `create-dataset-on-session-close` (default true): `createDatasets` — distinct dataset names
   created **sequentially** (`Multi...concatenate()`) with `datasets().createIfAbsent`, then all
   dataset items created **in parallel** (`Uni.join().all(...).andFailFast()`), bounded by
   `dataset-creation-max-wait-time` (30s). Each `CreateDatasetItemRequest` carries
   `input`, `expectedOutput = exchange.output()`, `sourceTraceId`, and metadata
   `session_id/trace_id/trace_name/dataset_name`. All failures logged and swallowed.
7. If `score-session` (default true): `SessionSentimentService.evaluate(exchanges)` —
   `@RegisterAiService(modelName = "session-sentiment", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)`,
   `@ApplicationScoped`, Qute-templated user message looping over exchanges,
   `@OutputGuardrails(SessionSentimentGuardrail.class)`, returns `SessionSentiment(sentiment, reasoning)`.
8. `saveScore` — synchronous `langfuse.scores().create(...)` with `sessionId`, name
   `session-sentiment`, CATEGORICAL value = sentiment label, comment = reasoning. Deliberately
   synchronous so the score lands before `ComputeSessionScore` ends.

> NOTE: the expected output recorded into the dataset is **the model's own previous answer**
> (`exchange.output()`). Tier 3 therefore measures drift against past behaviour, not a curated
> golden answer.

### Tier 3 — drift detection (inline, guardrail-driven)
- `DriftDetectionOutputGuardrail` (`@ApplicationScoped`, `implements OutputGuardrail`), injected
  with `ScoringConfig` + `EvaluationStrategy<String>`. Returns `success()` immediately unless
  `interactionMode() == DRIFT_DETECTION`. Otherwise builds
  `Evaluation.<String>builder().withConcurrency(clamp(cpus-2,1,cpus)).withSamples(sampleSetName)
  .evaluate(params -> request.responseFromLLM().aiMessage().text()).using(strategy).run()`.
  `sampleSetName` = `AI_SERVICES_PREFIX + simpleInterfaceName + "." + methodName` from the
  LangChain4j `InvocationContext`. Compares `evaluation.score() / 100.0` against
  `quarkus.aiscoring.threshold`; below -> `fatal(...)` carrying a `DriftDetectionException`
  (builder-based, holds sampleSetName/score/threshold). `SampleLoadException` -> `successWith(msg)`.
- `LangfuseDatasetSampleLoader implements SampleLoader<String>` — **not** referenced from Java;
  discovered via `src/main/resources/META-INF/services/io.quarkiverse.langchain4j.testing.evaluation.SampleLoader`
  (single line: `ai.scoring.langfuse.evaluation.LangfuseDatasetSampleLoader`). Gets Langfuse via
  `CDI.current().select(LangfuseOperations.class).get()`. `supports()` = non-blank name that
  `datasets().findByName` resolves; `load()` streams `datasetItems()`, filters
  `status == DatasetStatus.ACTIVE` client-side, maps to `EvaluationSample` (`input` parameter,
  expectedOutput); `priority() == 100`; `LangfuseNotFoundException` -> empty `Samples`.
- `Evaluator` (`@ApplicationScoped implements EvaluationStrategy<String>`) — the **only** strategy
  bean. Delegates to `EvaluatorAgent.isResponseCorrect(input, output, expectedOutput)` and maps
  `EvaluatorResult(verdict, score, reasoning)` to `EvaluationResult`.
- `EvaluatorAgent` — `@RegisterAiService(modelName = "judge", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)`,
  `@ApplicationScoped`, class-level `@SystemMessage` (strict evaluator, explicit scoring bands),
  `@OutputGuardrails(EvaluatorResultOutputGuardrail.class)`.
- `DriftDetectionChatRouteExceptionHandler` — plain class with a static
  `@ChatRouteExceptionHandler handleDriftException(OutputGuardrailException, ChatRouteContext)`.
  Scans `ex.result().failures()` for a `DriftDetectionException` cause and replies
  `ctx.response().error("DRIFT DETECTED!!!\n\n<message>")` — so drift surfaces to the browser as a
  chat error, not a stack trace.

> NOTE: despite `quarkus-langchain4j-testing-evaluation-semantic-similarity` being on the
> classpath, **no semantic-similarity strategy is wired up** and there is no strategy-selection
> config property. `Evaluator` (AI judge) is the only path.

> NOTE: `EvaluationReport.score()` is a pass-rate **percentage** (0–100) over boolean verdicts,
> while `quarkus.aiscoring.threshold` is a 0–1 fraction — hence the `/100.0`. The judge's numeric
> `score` never reaches the threshold comparison; only `verdict` does.

### Dataset naming invariant
- Write side: `AiServiceDatasetSpanProcessor` (`@Singleton`, package-private, OTel `SpanProcessor`,
  `isStartRequired()==true`, `isEndRequired()==false`). `onStart`: if the span name starts with
  `langchain4j.aiservices.` it stamps `langfuse.dataset.name` (= the span name **verbatim**),
  `ai.service.class`, `ai.service.method`; otherwise it copies those attributes from the immediate
  parent span, so they cascade to tool and generation spans.
- Keys live in `AiServiceAttributes`: `AI_SERVICES_PREFIX = "langchain4j.aiservices."`,
  `DATASET_NAME = langfuse.dataset.name`, `AI_SERVICE_CLASS = ai.service.class`,
  `AI_SERVICE_METHOD = ai.service.method`.
- Read side, tier 2: `ConversationExchange.resolveDatasetName` — five-step fallback: own metadata ->
  ancestor metadata -> name of the nearest `langchain4j.aiservices.*` ancestor -> trace name ->
  own name -> `"default"`. Metadata is probed in three shapes (flat, `attributes.`-prefixed, nested
  `attributes` map) and can be recomposed from class+method.
- Read side, tier 3: `DriftDetectionOutputGuardrail.getAIServiceName` rebuilds the same string from
  `InvocationContext`. All three sides must stay in lock-step; a mismatch fails silently.

### The two `JsonExtractorOutputGuardrail` subclasses (verified via `@OutputGuardrails`)
- `ai.scoring.langfuse.session.SessionSentimentGuardrail` extends
  `JsonExtractorOutputGuardrail<SessionSentiment>` — referenced in
  `@OutputGuardrails(SessionSentimentGuardrail.class)` on `SessionSentimentService.evaluate`.
- `ai.scoring.langfuse.evaluation.EvaluatorResultOutputGuardrail` extends
  `JsonExtractorOutputGuardrail<EvaluatorResult>` — referenced in
  `@OutputGuardrails(EvaluatorResultOutputGuardrail.class)` on `EvaluatorAgent.isResponseCorrect`.
Both are 12-line `@ApplicationScoped` classes whose only body is `super(<Record>.class)`; they are
deserialization insurance for models that wrap JSON in prose, not business validation.

> NOTE: guardrail count is **seven** classes total: four `GenerateEmail*` (via the abstract
> `GenerateEmailOutputGuardrail`, itself a JSON extractor), `DriftDetectionOutputGuardrail`,
> `SessionSentimentGuardrail`, `EvaluatorResultOutputGuardrail`. Zero input guardrails.

## 3. External systems

| System | Talks to it | Config | Profiles |
|---|---|---|---|
| **PostgreSQL** | `Claim` (Panache) via `ClaimResource`, `NotificationService` | `quarkus-jdbc-postgresql` + `quarkus-hibernate-orm-panache`; `hibernate-orm.physical-naming-strategy: CamelCaseToUnderscoresNamingStrategy`; `%prod`/`%openshift`: `sql-load-script: import.sql`, `schema-management.strategy: drop-and-create` | all; Dev Services in dev/test (no explicit JDBC URL in `application.yml`) |
| **Langfuse** | `LangfuseEvaluationInitializer`, `LangfuseSessionScoringService`, `LangfuseDatasetSampleLoader` — all through `LangfuseOperations` (`quarkus-langfuse` 0.7.2) + OTLP trace export | `quarkus.langfuse.environment: dev`; `%dev,test` log-requests/responses/pretty-print; `%langfuse-ocp` sets `base-url`, `public-key`, `secret-key` and `devservices.enabled: false` | all; Langfuse **Dev Service** in dev/test, remote under `%langfuse-ocp`/`%drift` |
| **OpenAI** | `ClaimService` (`parasol-chat`), `GenerateEmailService` (`generate-email`), `PolitenessService` (`politeness`), Easy-RAG embedding model | `quarkus.langchain4j.openai.api-key: ${OPENAI_API_KEY}`; per-service `model-name: gpt-5-mini`, `temperature: 1`, `timeout: 600s`; `quarkus.langchain4j.parasol-chat.chat-model.provider: openai`, `embedding-model.provider: openai` | default profile; replaced by Ollama under `%ollama` |
| **Cohere** (OpenAI-compatible) | `SessionSentimentService` (`session-sentiment`), `EvaluatorAgent` (`judge`) | `quarkus.langchain4j.openai.{session-sentiment,judge}.base-url: https://api.cohere.ai/compatibility/v1`, `api-key: ${COHERE_API_KEY}`, `model-name: command-r7b-12-2024` (judge `temperature: 0`) | default profile; `%ollama` stubs the keys to `changeme` and points these at Ollama |
| **Google Gemini** | **Not called by the app.** Registered as a Langfuse `LlmConnection` (`google-ai-studio`) by `LangfuseEvaluationInitializer`; Langfuse itself runs the LLM-as-a-judge relevance evaluator | `quarkus.aiscoring.langfuse.evaluation.gemini.model-name` (`gemini-2.5-flash`), `.api-key` (`${GEMINI_API_KEY:}`) | wherever `initialize-on-startup` is true — i.e. **not** `%ollama`, **not** `%test` |
| **LGTM / Grafana** (OTel collector, Prometheus, Tempo) | `quarkus-opentelemetry`, `quarkus-micrometer-registry-prometheus`, `quarkus-micrometer-opentelemetry`; dashboard `META-INF/grafana/grafana-dashboard-ai.json` | `quarkus.otel.logs.enabled`, `otel.metrics.enabled`, `datasource.metrics.enabled`, `datasource.jdbc.telemetry` | `quarkus-observability-devservices-lgtm` (scope `provided`) in dev/test; disabled in `%test`, `%instana`, `%langfuse-ocp` |
| **Mailpit (SMTP)** | `NotificationService` via `ReactiveMailer` | `quarkus-mailpit` + `quarkus-mailer`; `quarkus.mailer.tls: false`; `%dev`: `mailer.mock: false` | Dev Service in dev/test; real SMTP in prod/openshift |
| **Ollama** | `parasol-chat`, `generate-email`, `politeness`, `session-sentiment`, `judge`, embedding model | `%ollama`: providers switched to `ollama`, `llama3.2:latest` (embeddings `snowflake-arctic-embed`), plus `score-session: false` and `initialize-on-startup: false`. `%ollama-openai`: keeps the OpenAI client but points `base-url` at `http://localhost:11434/v1` | Maven profiles `-Dollama` (adds `quarkus-langchain4j-ollama`) and `-Dollama-openai` |
| **Instana** (OTLP) | OTel exporter only | `%instana`: `otel.exporter.otlp.endpoint`/`headers`/`protocol`, LGTM disabled | `%instana` only |
| **OpenShift** | build/deploy target | `%openshift`: parent profile `prod`, `container-image.builder: openshift`, route with edge TLS, connects-to `non-deterministic-db,lgtm,mailpit,langfuse-web` | `%openshift` |

Other notable profiles: `%drift` — parent `langfuse-ocp`, sets
`quarkus.aiscoring.interaction-mode: drift-detection` (the only place tier 3 is switched on).

> NOTE: `%langfuse-ocp` disables the OTLP exporter entirely (`quarkus.otel.exporter.otlp.enabled:
> false`) and turns session scoring off, so under `%drift` only tier 1 + tier 3 are live.

## 4. Diagram-ready component inventory

**User-facing entry points**
- React/PatternFly UI (`src/main/webui`, served by Quinoa; `enable-spa-routing`)
- WebSocket chat endpoint `/_chat/routes` (provided by `quarkus-langchain4j-chat-scopes-websocket`; route id `chat`)
- `ClaimResource` — `GET /api/db/claims`, `GET /api/db/claims/{id}`
- Swagger UI / OpenAPI (`smallrye-openapi`, `swagger-ui.always-include: true`), SmallRye Health

**Business AI services & domain**
- `ClaimService` (`parasol-chat`, `@ChatScoped`, Easy-RAG over `policies/`, `@ToolBox(NotificationService)`)
- `NotificationService` (tool `updateClaimStatus`; DB update + email)
- `GenerateEmailService` (`generate-email`) -> `Email`
- `PolitenessService` (`politeness`, called from a guardrail)
- `Claim` Panache entity, `ClaimBotQuery`, `ClaimInfo`, `Email`
- `ClaimBotQueryResponse` — declared but not referenced by any Java code (legacy REST/SSE shape)

**Guardrails (all output-only)**
- `GenerateEmailOutputGuardrail` (abstract, JSON extractor for `Email`)
- `EmailContainsRequiredInformationOutputGuardrail`
- `EmailStartsAppropriatelyOutputGuardrail`
- `EmailEndsAppropriatelyOutputGuardrail`
- `PolitenessOutputGuardrail`
- `DriftDetectionOutputGuardrail` (active only when `interaction-mode = DRIFT_DETECTION`, i.e. `%drift`)
- `SessionSentimentGuardrail`, `EvaluatorResultOutputGuardrail` (JSON extractors)

**Evaluation layer (`ai.scoring`)**
- Tier 1: `LangfuseEvaluationInitializer` (StartupEvent; skipped when `initialize-on-startup: false` — `%ollama`, `%test`)
- Tier 2: `ConversationalBaggageHandler`, `SessionScoringService` (interface),
  `LangfuseSessionScoringService`, `ConversationExchange`, `SessionSentimentService`,
  `SessionSentiment` (+ `Sentiment` enum). Disabled where `score-session: false`
  (`%ollama`, `%test`, `%langfuse-ocp`/`%drift`)
- Tier 3: `DriftDetectionOutputGuardrail`, `LangfuseDatasetSampleLoader` (ServiceLoader),
  `Evaluator`, `EvaluatorAgent`, `EvaluatorResult`, `DriftDetectionException`,
  `DriftDetectionChatRouteExceptionHandler` — effective only under `%drift`
- Telemetry plumbing: `AiServiceDatasetSpanProcessor`, `AiServiceAttributes`
- Config: `ScoringConfig`, `InteractionMode`, `LangfuseConfig` (+ `Evaluation`, `Session`, `Gemini`)

**External systems**
- PostgreSQL (all profiles)
- Langfuse (all profiles; Dev Service in dev/test, remote under `%langfuse-ocp`/`%drift`)
- OpenAI (default profile)
- Cohere via OpenAI-compatible endpoint (default profile)
- Google Gemini — *inside Langfuse only*, never called by the app
- LGTM/Grafana (dev/test Dev Service; off in `%test`, `%instana`, `%langfuse-ocp`)
- Mailpit SMTP (dev/test Dev Service)
- Ollama (`-Dollama` / `-Dollama-openai` Maven profiles only)
- Instana OTLP (`%instana` only)

**Confirmed dead / absent (do not draw)**
- Any second container, "Scoring Service", "Interaction & Scoring Database", REST hop on port 8888
- `InteractionPublisher` / `InteractionScorer` / `RESCORE` mode — no such classes or enum constant
- Input guardrails — none exist
- Semantic-similarity evaluation strategy — dependency present, nothing wired
- `Multi<String> chat(...)` streaming variant — commented out in `ClaimService`
- `application.yml` has a `%dev,test` log category `ai.scoring.events.api` for a package that does not exist
