# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**"Non-Deterministic? No Problem!"** is a demo application (Parasol Insurance) that shows how to
test and continuously evaluate non-deterministic AI systems.

It is a **single Quarkus application** (`org.parasol:parasol-app`, Java 25, Quarkus 3.40.1) with a React/PatternFly
frontend served via Quinoa. There are **no sub-modules** — one `pom.xml` at the root.

The Java source is split into two top-level packages representing two distinct concerns:

| Package | Concern |
|---|---|
| `org.parasol` | The insurance claims business application (claims REST API, AI chat bot, email notification, guardrails) |
| `ai.scoring` | The reusable AI-quality layer (Langfuse integration, session scoring, drift detection) |

`org.parasol` is organised **by domain first, then by layer** inside each domain:

| Package | Contents |
|---|---|
| `org.parasol.claim.model` | `Claim` (Panache entity, table `claims`), `ClaimImage` (table `claim_images`), `ClaimImageKind`, `ClaimImageContentType`, `ClaimCategory`, claim-number generation, the domain exceptions (`ClaimNotFoundException`, `ClaimImageNotFoundException`, `UnknownClaimCategoryException`, `UnsupportedClaimImageContentTypeException`) |
| `org.parasol.claim.rest` | `ClaimResource` (`/api/db/claims`), `ClaimImageResource` (`/api/db/claims/{id}/images`), the `ClaimDetails` / `ClaimImageMetadata` DTOs + `ClaimMapper` (MapStruct), `ClaimExceptionMappings` (Problem Details) |
| `org.parasol.claim.seed` | `ClaimImageSeeder` (attaches the sample images to the six seeded claims) |
| `org.parasol.chat.ai` | `ClaimService` (the chat-bot AI service) |
| `org.parasol.chat.model` | `ClaimBotQuery`, `ClaimBotQueryResponse` |
| `org.parasol.notification.service` | `NotificationService` (the `updateClaimStatus` `@Tool`) |
| `org.parasol.notification.ai` | `GenerateEmailService` |
| `org.parasol.notification.model` | `Email`, `ClaimInfo` |
| `org.parasol.notification.guardrail` | The four email output guardrails, their `GenerateEmailOutputGuardrail` base, `PolitenessService`, `StringUtils` |
| `org.parasol.intake` | The email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216), being built): `IntakeConfig` (`parasol.intake.*`) |
| `org.parasol.intake.mailbox` | `ClaimsMailbox` (the `claims@parasol.com` IMAP mailbox: list, find by `Message-ID`, move between `MailFolder`s), `InboundEmail` and its attachment records, the package-private MIME parser, HTML-to-text and quoted-text helpers |

Dependencies point one way: `chat` → `notification` → `claim`, and `intake` → `claim`. New features get their own
domain package with the same kind of layer sub-packages. Test packages mirror main, plus the test-only `org.parasol.ui` for Playwright
and `org.parasol.testing.mail` for the GreenMail helper.

Supporting docs:
- `README.md` — build/run instructions, Ollama profiles, Langfuse integration notes
- `langfuse-evaluation.md` — **the** design document: the three-tier evaluation strategy, Langfuse
  platform/language gaps, and the workarounds implemented here. Read this before touching anything
  under `ai.scoring.langfuse`.
- `docs/*.puml` — three PlantUML diagrams: `application-flow.puml` (the business flow —
  chat → tool → email), `continuous-scoring-architecture.puml` (single-container component view of
  the evaluation layer) and `continuous-scoring-sequence.puml` (the tier-2 session-scoring
  sequence).
- `docs/design/email-claim-intake.md` — the **proposed** design for the email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216)):
  workflow, claim states and agent topology, with three diagrams (`docs/design/claim-intake-*.puml`).
  None of it is implemented yet; don't describe it as existing code.
- `images/arch.png` — the hand-drawn overview of the business flow, framed as "Code I write" vs
  "Is this code?", embedded at the top of README.md's Architecture section with
  `docs/application-flow.png` below it as the detailed complement. It is a **source-less raster**
  (no `.excalidraw`/`.drawio` original) that has already been pixel-edited — a white rectangle
  painted over a now-removed "Input Guardrails" box describing a component that does not exist (all
  seven guardrails here are output-side). Changing it means pixel editing or a full redraw, not
  editing a source file, and this machine has no Pillow, numpy or ImageMagick — the last edit
  needed a hand-rolled Python PNG codec.

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

**Every dev-mode run starts the mail stack** from `compose-devservices.yml` (Compose Dev Services):
GreenMail (SMTP/IMAP/REST API on random host ports) and the Roundcube webmail on
http://localhost:8000 (log in as any address, any password; README "Reading the emails" lists the seeded claimants'
mailboxes and how to trigger a status email). That holds under the default, `-Pollama`
and `-Pollama-openai` profiles alike. Dev Services follow the dev *launch mode*, not the config profile,
and the Ollama Maven profiles run `quarkus:dev` as `<ai>,prod`, so `quarkus.compose.devservices.profiles:
webmail` (which enables Roundcube) and `quarkus.mailer.mock: false` / `host: localhost` sit at root level
in `application.yml` rather than in `%dev`. Tests get GreenMail only: `%test` blanks the Compose profile, and
surefire/failsafe also set `quarkus.compose.devservices.profiles=none`, because a test whose config profile doesn't
include `test` (`DriftTestProfile` returns `drift`, `-Dquarkus.test.profile=drift`) would otherwise start Roundcube on
port 8000.
GreenMail keeps mail in memory, so restarting dev mode (or a test run) empties every mailbox.

### Diagrams

```bash
# Re-render every docs/*.puml and docs/design/*.puml to a sibling PNG (pinned PlantUML, needs graphviz `dot`)
./docs/render-diagrams.sh
```

### Frontend (from `src/main/webui/`)

```bash
npm test        # Jest
npm run build   # production build into dist/
```

Quinoa builds the frontend as part of the Maven build — you rarely need to run npm directly.

The Jest suite also runs inside the Maven build. The Playwright test classes use
`@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)` (`quarkus.quinoa.run-tests=true`), so Quinoa runs
`npm test` before building the UI, and a failing Jest test fails the `@QuarkusTest` boot. Jest needs the
`src/main/webui/__mocks__/` stubs for CSS and asset imports, and maps deep
`@patternfly/react-icons/dist/esm/...` imports to the CommonJS `dist/js/...` build. `jest.config.js` doesn't
transform `node_modules`, so Jest can't load the ES-module build. `app.test.tsx` renders the app under jsdom with
no backend, so the axios `GET …/api/db/claims` errors in the Maven/Quinoa output are expected on a green run. The
snapshot is taken synchronously right after `render(<App />)`, before the claims request settles, so the committed
snapshot always shows an empty claims table; claim rendering is covered by the Playwright tests, not Jest.

### CI

`.github/workflows/simple-build-test.yml` runs `./mvnw -B clean verify` on Java 25 across the
`ollama` and `ollama-openai` profiles. CI has no real OpenAI/Cohere/Gemini credentials, so **any new
test must pass under the Ollama profiles**. It runs on pushes to `main` and on pull requests into `main` or into
`gh216-email-intake`, the feature branch the #216 task PRs merge into. Remove that branch from the trigger once it
merges.

**CI supplies Ollama as a GitHub service container,** `ollama/ollama` on `localhost:11434`, with
`granite4:micro` and `snowflake-arctic-embed` pulled in a step before the build. Both profiles reach
it: `%ollama-openai` points there explicitly, and `%ollama` falls back to the Ollama extension's
default `base-url` because the workflow passes
`-Dquarkus.langchain4j.ollama.devservices.enabled=false`. That flag is the **only** CI-specific
config — everything else, including the model id, is in `application.yml`, so local runs and CI
exercise the same thing.

Don't re-enable the Dev Service in CI. It recreates its container once per augmentation (ten times
in a full `verify`) and shuts the previous one down, so an app whose build-time config captured an
earlier container's ephemeral port boots against a dead address. That was a `Connection refused` at
startup for every `@QuarkusTest` without a Dev-Service-free profile.

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

**Output guardrails** on `GenerateEmailService` (all extend `GenerateEmailOutputGuardrail`, itself a
`dev.langchain4j.guardrails.JsonExtractorOutputGuardrail<Email>`):
`EmailContainsRequiredInformationOutputGuardrail`, `EmailStartsAppropriatelyOutputGuardrail`,
`EmailEndsAppropriatelyOutputGuardrail`, `PolitenessOutputGuardrail`.

**None of them may report success by rewriting the output** — that was [#228](https://github.com/edeandrea/non-deterministic-no-problem/issues/228).
`JsonExtractorOutputGuardrail.validate` returns `successWith(json, value)` unconditionally, which is a
rewrite, and `OutputGuardrailExecutor.handleFatalResult` refuses any retry or reprompt once something
earlier in the chain has rewritten (`"Retry or reprompt is not allowed after a rewritten output"`). With
four of them chained, only the *first* could ever reprompt. So the base deliberately does **not** use the
inherited `validate`: it exposes `extractEmail(AiMessage)` (parse, no rewrite) and `invalidJson(AiMessage)`,
and each guardrail returns `success()` or `reprompt(...)`. `super.validate(...)` is still inherited and
still rewrites — don't call it. `EmailOutputGuardrailChainTests` is the guard; it drives the real
`OutputGuardrailExecutor`, and every `guardrailSuccess` unit test asserts plain `Result.SUCCESS` rather
than `SUCCESS_WITH_RESULT`.

Nothing here needs the rewrite: Quarkus and LangChain4j **already** extract JSON from surrounding prose on
the AI service return path (`PojoOutputParser` → `JsonParsingUtils.extractAndParseJson`, plus
`QuarkusJsonCodecFactory`'s own first-`{`-to-last-`}` regex). What `JsonExtractorOutputGuardrail` still
buys here is its deliberately strict plain `ObjectMapper` and a reprompt — rather than an escaping
`OutputParsingException` — when the output can't be parsed.

The project has **seven guardrail classes in total and they are all output guardrails** — the four
email ones above plus `DriftDetectionOutputGuardrail`, `SessionSentimentGuardrail` and
`EvaluatorResultOutputGuardrail`. There are **zero input guardrails**.

**Email flow:** the chat bot calls the `NotificationService.updateClaimStatus` tool → updates the
`Claim` Panache entity → `GenerateEmailService` produces the email → sent via Quarkus Mailer
(GreenMail from `compose-devservices.yml` in dev/test; readable in Roundcube in dev mode). On OpenShift both run
from `src/main/kubernetes/dependencies.yml` (see OpenShift deployment).

**REST:** the REST layer never serializes entities. Resources hold no queries; they call the Active Record methods
and map to DTO records with `ClaimMapper`.
- `ClaimResource`: `GET /api/db/claims`, `GET /api/db/claims/{id}` return `ClaimDetails`. A missing claim is a
  Problem Details `404` (the resource throws `ClaimNotFoundException` when `findByIdOptional` is empty); before #214 it
  was an empty `204`.
  The UI's claim detail page shows "Claim not found" for it (`ClaimImagesPageTests.unknownClaimSaysTheClaimDoesNotExist`).
- `ClaimImageResource`: `GET /api/db/claims/{id}/images` (`ClaimImageMetadata` list, oldest first) and
  `GET /api/db/claims/{id}/images/{imageId}` (the bytes, with the stored content type and
  `X-Content-Type-Options: nosniff`).
- **The DTOs are the API contract.** `ClaimDetails` lists every field the UI reads, in snake_case
  (`@JsonNaming` on the record; `category` is the label via `ClaimCategory`'s `@JsonValue`; nulls are omitted by the
  global `serialization-inclusion: non-empty`). A new `Claim` column (e.g. #216's `reviewRunId`, `intakeTraceparent`,
  `intakeConversationId`, `version`) stays out of the API until it's added to the DTO; `ClaimResourceTests.jsonFieldNames`
  pins the exact set of JSON keys.
- **Errors:** domain exceptions are mapped once, by `ClaimExceptionMappings` (Quarkus `@ServerExceptionMapper`), to RFC
  9457 Problem Details (`application/problem+json`, `type: about:blank`, `instance` = request path).
  `ClaimNotFoundException` and `ClaimImageNotFoundException` are `404`. An image that exists but belongs to another
  claim is the same `404` as an unknown image.

**The `Claim` entity** (`org.parasol.claim.model`, table `claims`; serialized only through `ClaimDetails`):
- **`id`** is the Panache numeric primary key (`claims_seq`). REST paths, UI routes, `ClaimBotQuery.claimId`
  and the `NotificationService` tool all use it.
- **`claimNumber`** is a separate `@NaturalId` (`CLM` + 8 digits, unique, not updatable). PostgreSQL
  generates it on insert:
  - **The column default** is `@ColumnDefault(ClaimNumberGenerator.COLUMN_DEFAULT)`, which is
    `'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')`.
  - **`@ClaimNumber`** is a `@ValueGenerationType` meta-annotation for `ClaimNumberGenerator`, which is an
    `OnExecutionGenerator` + `ExportableProducer`. It leaves the column out of the `INSERT`, reads the value
    back with `insert … returning claim_number`, and registers `claim_number_seq` (`start with 1000000
    increment by 1009 maxvalue 99999999`) with Hibernate's schema management.
  - **The first numbers** are `CLM01000000`, `CLM01001009`, `CLM01002018`, …; capacity is about 98,000 claims.
  - **Lookup:** `Claim.findByClaimNumber(String)` returns `Optional<Claim>` via `session.find(..., KeyType.NATURAL)`.
- **`category`** is a `ClaimCategory` enum (`SINGLE_VEHICLE`, `MULTIPLE_VEHICLE`, `THEFT`, `OTHER`).
  - **Database:** stored as the constant name (`@Enumerated(STRING)`, so Hibernate adds a CHECK constraint).
  - **JSON:** written as the display label ("Single vehicle", …, "Other") via `@JsonValue`.
  - **Input:** `ClaimCategory.fromValue` (`@JsonCreator`) accepts a label or a constant name, ignoring case,
    and throws `UnknownClaimCategoryException` otherwise.
- **`incidentDate`** (`LocalDate`) and **`incidentTime`** (`LocalTime`, nullable) replaced the old free-text
  `time` / `claim_time`. `ZonedDateTime` was rejected because claim emails rarely state a time zone.
  The UI formats them with `src/main/webui/src/app/utils/formatIncident.ts`.
- **Sizes:** `subject`, `body` and `location` are unbounded `text` (`Length.LONG32`; not `@Lob`, which
  maps to `oid` on PostgreSQL). `summary` and `sentiment` stay `length = 5000`. `status` is free text.

**Seed data:** `src/main/resources/import.sql` seeds six claims (ids 1–6) and ends with
`ALTER SEQUENCE claims_seq RESTART WITH 7`.
- **It omits `claim_number`,** so the column default numbers the rows in insert order (`CLM01000000` … `CLM01005045`).
- **It uses the enum constant names** for `category`.
- Dev/test load it by default; `%prod` / `%openshift` load it via `sql-load-script` under drop-and-create.
- `ClaimImageSeeder` attaches the 12 JPEGs in `src/main/resources/seed/claim-images/` to the six sample claims,
  matched by their explicit ids (1–6), not their generated claim numbers. See Gotchas.

**Claim images** (`ClaimImage`, table `claim_images`):
- **Persistence is Active Record, like `Claim`:** `listForClaim(claimId)` (throws `ClaimNotFoundException`),
  `findForClaim(claimId, imageId)` (returns `Optional`; always matches claim **and** image id, so another claim's
  image is empty; `ClaimImageResource` throws `ClaimImageNotFoundException` for the 404),
  `hasImage(claim, kind, fileName)` and `store(claim, kind, fileName, contentType, data)`. #216's intake stores
  attachments through `store`. Image ids are table-wide `PanacheEntity` ids.
- **`kind`** is `ORIGINAL` (customer photo) or `PROCESSED` (annotated damage image). New claims never get processed
  images.
- **`contentType` is an allow-list,** the `ClaimImageContentType` enum (JPEG, PNG, GIF, WebP; stored as the constant name,
  JSON and `Content-Type` use the media type). Resolve an outside media type with `ClaimImageContentType.find` /
  `fromMediaType` (accepts aliases like `image/jpg` and ignores parameters). Never widen it to `image/*`: the endpoint
  serves the bytes back on the app's own origin, so `image/svg+xml` or `text/html` would be stored XSS.
- **`data`** is `byte[]` with `@Column(length = Length.LONG32)`, which is `bytea` on PostgreSQL (`@Lob` would be an
  `oid` large object; `ClaimImageTests` checks the column type). It's `@Basic(fetch = LAZY)` (Hibernate bytecode
  enhancement), so listing images never reads the bytes.
- **Validation:** `@NotNull` / `@NotBlank` / `@NotEmpty` fail the flush with a `ConstraintViolationException` (empty
  data, blank file name).
- **The `claim_id` foreign key is `on delete cascade`** (`@OnDelete`) and indexed, so deleting a claim deletes its
  images; tests only need `Claim.deleteById`.
- **Image URLs are root-relative** (`/api/db/claims/{id}/images/{imageId}`), built by `ClaimMapper` from the
  resource's `@Path`s. `%openshift` terminates TLS at the route and doesn't enable proxy forwarding, so a URL built
  from the request would come out as `http://`. The UI resolves it against `backend_api_url`'s origin.
- **`ClaimMapper`** is MapStruct (`mapstruct-processor` on the compiler's `annotationProcessorPaths`) with the
  `JAKARTA_CDI` component model, one mapper for the claim aggregate (`Claim` → `ClaimDetails`, `ClaimImage` →
  `ClaimImageMetadata`). `unmappedTargetPolicy = ERROR` fails the build if a DTO field has no source; unmapped entity
  fields are ignored on purpose. The image mapping only reads metadata fields, so it never triggers the lazy `data`
  load.

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
   cover. `createEvaluationRule` installs two `NONE_OF` filters on the rule: `environment` not in
   (`langfuse-llm-as-a-judge`, `llm-as-judge`) — which is what stops the judge from scoring its own
   output — and `type` not in (`SPAN`, `EVENT`), so only generations get scored.
2. **Session-level** — Langfuse has no session evaluation target, so this is custom code.
   `ConversationalBaggageHandler` observes `ChatScopeStarted/Activated/Deactivated/Ended`,
   propagates the conversation id as OTel baggage (`gen_ai.conversation.id`), and on session end
   hands off to `SessionScoringService` on a background executor.
   `LangfuseSessionScoringService` polls Langfuse until the session's observations are ingested
   (OTel flush + async ClickHouse ingest), reconstructs `ConversationExchange`s, asks
   `SessionSentimentService` for a `SessionSentiment` (that AI service carries
   `@OutputGuardrails(SessionSentimentGuardrail.class)` — an `@ApplicationScoped`
   `JsonExtractorOutputGuardrail<SessionSentiment>` in `ai.scoring.langfuse.session` that exists
   purely as deserialization insurance for models that wrap their JSON in prose), and posts the
   score back with
   `langfuse.scores().create(...)` — deliberately the synchronous tree, because the score must land
   before `scoreSession` ends the enclosing `ComputeSessionScore` span. The observation query is a
   typed `ObservationFilter` (`sessionId` + `fields("core,basic,io,metadata")`) handed to
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
     AI service (the `judge` model), whose `isResponseCorrect` method carries
     `@OutputGuardrails(EvaluatorResultOutputGuardrail.class)` — an `@ApplicationScoped`
     `JsonExtractorOutputGuardrail<EvaluatorResult>` in `ai.scoring.langfuse.evaluation`, again just
     deserialization insurance rather than business validation. `Evaluator` is the only
     `EvaluationStrategy` bean in the project —
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
| `claim-intake` | The email intake agents (#216, being built; configured, no agent uses it yet) | `gpt-5-mini` |

Note the Cohere models are reached through the **OpenAI** extension pointed at
`https://api.cohere.ai/compatibility/v1` with `COHERE_API_KEY` — there is no Cohere-specific
extension in the build.

Every base (non-profile) `quarkus.langchain4j.openai.<name>.chat-model` block sets `temperature` **and** `top-p`
explicitly. That is deliberate. Since quarkus-langchain4j 1.14 the OpenAI extension leaves both
out of the request unless they are configured, so the provider's own default applies. The
explicit values keep the pre-1.14 wire behaviour (temperature `1.0` unless overridden, top-p `1.0`).
Don't delete the `top-p: 1` lines because they look redundant. The `%ollama-openai` overrides
inherit these values, because they only repoint `base-url` and `model-name`.

There are **two separate judges**, which is easy to confuse:
- The in-app `judge` model above, driving `EvaluatorAgent` → `Evaluator` for tier-3 drift detection.
- The Langfuse-side LLM-as-a-Judge evaluator for tier 1, which runs inside Langfuse using **Google
  Gemini** (`gemini-2.5-flash`, `LlmAdapter.GOOGLE_AI_STUDIO`) configured by
  `LangfuseEvaluationInitializer` from `LangfuseConfig.Evaluation.Gemini`.

Embeddings use the OpenAI embedding model by default (`quarkus.langchain4j.embedding-model`).
Because `reuse-embeddings` is enabled, the ingested vectors are cached in `easy-rag-embeddings.json`
(project root, gitignored) and reloaded on every restart. OpenAI (1536-dimension) and
`snowflake-arctic-embed` (1024-dimension) vectors are incompatible, so delete that file when
switching between the default and the Ollama profiles.

The two Ollama profiles are **not** equivalent:
- `%ollama` switches `parasol-chat`, `generate-email`, `politeness`, `claim-intake` and the embedding model to
  `provider: ollama` (`granite4:micro`, embeddings `snowflake-arctic-embed`) — **except
  `generate-email` (and `claim-intake`, which matches it), which use `qwen3:4b` with `model-options.think: false`**,
  because it is the
  only model measured to reproduce `EMAIL_ENDING` verbatim for
  `EmailEndsAppropriatelyOutputGuardrail`, and qwen3 reasons by default (~6k tokens per email
  versus ~140 with thinking off). Note `%ollama-openai` does **not** inherit this: all four of its chat
  `model-name`s interpolate from `quarkus.langchain4j.ollama.parasol-chat.chat-model.model-id`, not
  from their own, so changing `generate-email`'s id moves only the Ollama-native leg. That is
  deliberate — `model-options.think` has no equivalent on the OpenAI-compatible endpoint. It also
  stubs
  `quarkus.langchain4j.openai.api-key` plus the `session-sentiment` and `judge` API keys to
  `changeme`, and disables Langfuse session scoring + startup initialization. Note
  `session-sentiment` and `judge` keep `provider: openai` pointed at the Cohere-compatible
  endpoint — they are *not* moved to Ollama, they are just given a dummy key.
- `%ollama-openai` only repoints the OpenAI client's `base-url` at `http://localhost:11434/v1` for
  `parasol-chat`, `generate-email`, `politeness`, `claim-intake` and the embedding model. It stubs **no** API keys
  and does **not** disable session scoring or startup initialization.

### Configuration profiles

`src/main/resources/application.yml` is the single config source. Notable profiles:

| Profile | Purpose |
|---|---|
| `dev` | Only `datasource.dev-ui.allow-sql`. The LGTM, PostgreSQL and Langfuse dev services do run in dev mode, but they come from the extensions' own Dev Services defaults, not from this profile block. The GreenMail/Roundcube mail stack and `mailer.mock: false` are root-level config (see Commands) |
| `test` | Observability + Langfuse init/session scoring disabled; `compose.devservices.profiles` blanked so Roundcube doesn't start |
| `ollama` | Local Ollama via the Ollama extension; OpenAI/Cohere keys stubbed to `changeme`; Langfuse session scoring + startup init disabled |
| `ollama-openai` | Local Ollama via the OpenAI client (`base-url: http://localhost:11434/v1`); no key stubs, no `quarkus.aiscoring` overrides |
| `drift` | Parent `langfuse-ocp`; sets `quarkus.aiscoring.interaction-mode: drift-detection` |
| `langfuse-ocp` | Points at a deployed Langfuse instance; disables OTLP export and LGTM |
| `instana` | Exports OTLP to Instana instead of LGTM |
| `prod`, `openshift` | Schema drop-and-create + `import.sql`; OpenShift deployment config |

### OpenShift deployment

`deploy-to-openshift.sh` installs Langfuse (Helm), applies `src/main/kubernetes/dependencies.yml`, then builds and
deploys the app with `-Dquarkus.profile=openshift`. It ends by printing the Roundcube Route URL.

- **`dependencies.yml`** holds PostgreSQL, LGTM and the mail stack, plus the `parasol-app-config` ConfigMap the app
  reads (`quarkus.mailer.host: greenmail`, port `3025`).
- **The mail stack mirrors `compose-devservices.yml`:** same images and tags, same environment variables. Change
  both together.
  - **GreenMail** is internal only (ClusterIP Service for SMTP 3025, IMAP 3143 and the API on 8080; no Route). It keeps
    mail in memory, so a pod restart loses every mailbox. That's deliberate: GreenMail has no persistent store
    (`greenmail.preload.dir` only loads `.eml` files at startup), and claim data lives in PostgreSQL anyway.
  - **Roundcube** is exposed through an edge-TLS Route; log in as any address with any password. It runs under the
    restricted SCC (arbitrary UID) only because `ROUNDCUBEMAIL_DB_DIR=/tmp/roundcube-db` moves its SQLite DB off the
    `www-data`-owned `/var/roundcube`. There is no volume, so sessions and settings are lost on restart.
- The app's `app.openshift.io/connects-to` annotation (`%openshift` in `application.yml`) names **Deployments**, not
  Services: `non-deterministic-db,grafana-lgtm,greenmail,langfuse-web` (the LGTM Service is `lgtm`, its Deployment
  `grafana-lgtm`). Roundcube's Deployment carries its own `connects-to: greenmail`.

### Environment variables

| Variable | When it is needed |
|---|---|
| `OPENAI_API_KEY` | Default profile — `parasol-chat`, `generate-email`, `politeness`, `claim-intake`, embeddings |
| `COHERE_API_KEY` | Default profile — `session-sentiment` and `judge` |
| `GEMINI_API_KEY` | Only when `LangfuseEvaluationInitializer` runs (`initialize-on-startup`); it throws `IllegalStateException` if absent |

Under `-Pollama` none of these are required — the OpenAI and Cohere keys are stubbed to `changeme`
and `LangfuseEvaluationInitializer` is switched off.

Under `-Pollama-openai` nothing is stubbed, but which keys you actually need depends on the goal,
because the Maven profile sets **two different Quarkus profiles**:
- `quarkus:dev` and the packaged app run under `quarkus.profile=ollama-openai,prod`, so
  `COHERE_API_KEY` is needed for `session-sentiment`/`judge` and `GEMINI_API_KEY` is needed because
  startup initialization stays enabled.
- `./mvnw test` / `./mvnw verify` run under `quarkus.test.profile=ollama-openai,test` (forced on
  surefire and failsafe), and `%test` sets `initialize-on-startup: false` and `score-session: false`
  — so the initializer and session scorer are inert and **neither `COHERE_API_KEY` nor
  `GEMINI_API_KEY` is required for the build**. That is why CI passes with only a stubbed
  `OPENAI_API_KEY`.

### Observability

OpenTelemetry + Micrometer. Dev mode uses the LGTM dev service (Grafana/Loki/Tempo/Prometheus).
The `grafana/otel-lgtm` image ships Prometheus as its metrics store, not Mimir, despite the "M" in LGTM.
Traces are also exported to Langfuse via the `quarkus-langfuse` extension.

## Testing

Test layout mirrors main: `src/test/java/org/parasol/...` and `src/test/java/ai/scoring/...`, plus the test-only
`org.parasol.testing.mail` (GreenMail helper) and `org.parasol.ui` (Playwright).

- `@QuarkusTest` + `@InjectMock`/`@InjectSpy` + `dev.langchain4j.test.guardrail.GuardrailAssertions`
  for guardrail tests.
- REST Assured for REST; the websocket chat-routes client (`WebsocketChatRoutes.newClient(...)`)
  for chat tests (`ClaimWebsocketChatBotTests`).
- Playwright E2E tests in `org.parasol.ui`, extending the `@WithPlaywright` `PlaywrightTests` base
  class (records video to `target/playwright`). They use Quinoa's `EnableAndRunTests` profile, which
  also runs the frontend Jest suite (see Frontend above).
- `ai.scoring.it.DriftDetectionTests` is annotated with the custom meta-annotation
  `ai.scoring.it.DriftDetectionTest`, which combines `@QuarkusTest` with **four** independent
  enablement conditions, each carrying its own `disabledReason`:
  `@EnabledIfConfig(named = "quarkus.aiscoring.interaction-mode", matches = "drift-detection")`
  (`io.quarkus.test.junit.condition.EnabledIfConfig`, which ships with Quarkus itself — not
  `@EnabledIfApplicationProperty`, and there is no local copy of the condition classes),
  `@EnabledIfSystemProperty(named = "quarkus.profile", matches = "drift")`,
  `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")` and
  `@EnabledIfEnvironmentVariable(named = "COHERE_API_KEY", matches = ".+")`. The annotation also
  nests `DriftTestProfile implements QuarkusTestProfile`, which returns the `drift` config profile.
  Practical consequence: these tests need the `drift` profile **and** real OpenAI *and* Cohere keys,
  so they can never run in CI, which only supplies a stubbed `OPENAI_API_KEY=change-me`.
- `NotificationServiceTests.emailSendsWhenUserExists` is `@DisabledIfSystemProperty(named =
  "quarkus.test.profile", matches = ".*ollama-openai.*")` — it runs everywhere **except** the
  `%ollama-openai` leg. It is the only test that drives the real `GenerateEmailService` through all
  four output guardrails end to end. **The gate is model capability, not the #228 guardrail bug,
  which is fixed** (`EmailOutputGuardrailChainTests` covers that).
  `EmailEndsAppropriatelyOutputGuardrail` requires the body to end with
  `GenerateEmailService.EMAIL_ENDING` **verbatim**, and most small models reflow it — `granite4:micro`
  collapses the three disclaimer lines onto one and adds a space after `"Sincerely,"`, so the
  guardrail reprompts until max-retries. Measured identical for `llama3.2:latest`, `ministral-3:3b`
  and `qwen2.5:3b`. `qwen3:4b` is the one that reproduces it (5/5 runs), which is why
  `generate-email` uses it under `%ollama` — see the Models section. `%ollama-openai` can't follow:
  it reaches Ollama over the OpenAI-compatible endpoint, where `model-options.think` doesn't exist,
  and a thinking qwen3 spends ~6k tokens per email (minutes on a 4 vCPU runner). `qwen3:4b-instruct`
  was tried and rejected: its 256K context blows the test's 5-minute transaction budget even at
  `num-ctx=8192`. **Don't re-enable the last leg by relaxing the guardrail** — the system prompt and
  the reprompt both say "EXACTLY as it appears below", and enforcing that is the point of the demo.
- **Mocking LLM calls:** the Quarkus WireMock dev service (`@ConnectWireMock` + an injected
  `WireMock`). A `QuarkusTestProfile` repoints every relevant `quarkus.langchain4j.openai.*.base-url`
  (including `parasol-chat`, `session-sentiment`, `judge`) at
  `http://localhost:${quarkus.wiremock.devservices.port}/v1`, then stubs the
  OpenAI-compatible `/chat/completions` endpoint. Stubs are registered programmatically in
  `@BeforeEach`; there are no WireMock mapping files. (`src/test/resources` only holds
  `seed/claim-images/empty-for-tests.jpg`, an empty file for `ClaimImageSeederTests`; WireMock reads only its
  `mappings/` and `__files/` subdirectories, so it's unaffected.)
- **A WireMock-backed profile must also pin the provider.** Redirecting `...openai.*.base-url` is
  not enough: `%ollama` sets `quarkus.langchain4j.parasol-chat.chat-model.provider: ollama` (and the
  same for `embedding-model`), so under `-Pollama` the request never goes near the OpenAI client and
  the stub is silently bypassed — the test then runs against a real model and inherits its
  non-determinism. Every mocking profile therefore also sets
  `quarkus.langchain4j.parasol-chat.chat-model.provider=openai` and
  `quarkus.langchain4j.embedding-model.provider=openai`. They look redundant under the default
  profile; they are not. This is what made `LangfuseSessionScoringServiceTests` flaky — llama3.2
  decided to call `updateClaimStatus`, which produced a second `GENERATION`.
- **Easy RAG in WireMock-backed profiles:** a profile that repoints the default
  `quarkus.langchain4j.openai.base-url` at WireMock (`DriftDetectionChatRouteExceptionHandlerTests`,
  `LangfuseSessionScoringServiceTests`) also sets `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF`.
  Otherwise Easy RAG ingests the policy documents at boot, calling `/v1/embeddings` before the
  `@BeforeEach` stubs exist. With `OFF` the in-memory store starts empty and `easy-rag-embeddings.json`
  is neither read nor written; queries are still embedded at chat time, so those tests keep a
  1536-dimension `/v1/embeddings` stub.
- **Easy RAG in stub-key profiles:** the `KeysTestProfile`s (`DriftDetectionOutputGuardrailTests`,
  `LangfuseDatasetSampleLoaderTests`) set `ingestion-strategy=OFF` too. Under the default profile, boot-time
  ingestion would call the real OpenAI embeddings API with the `changeme` stub key and fail startup with a
  401 (`AuthenticationException`); under `-Pollama-openai` it would call an Ollama that may not be running.
  Under the default profile it only ever passed when a cached `easy-rag-embeddings.json` let `reuse-embeddings`
  skip ingestion.
  Neither test uses RAG.
- **Langfuse is not mocked.** `DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests`
  and `LangfuseSessionScoringServiceTests` inject the real `LangfuseOperations` against the Langfuse
  dev service, creating and tearing down real datasets/items around each test. Their fixtures and
  assertions still go through `api()` (`api().datasetItems()`, `api().observations()`,
  `api().scoresV3()`) — that is test-fixture code that predates the operations layer covering those
  domains, not a gap in the layer.
- AssertJ + Awaitility; Mockito is attached as a `-javaagent` in the surefire/failsafe config.
- **Mail tests read the real GreenMail** from `compose-devservices.yml` (every test run starts it) through the
  injectable `org.parasol.testing.mail.GreenMailMailbox` bean. There is no mail mock.
  - **Reading is IMAP** (Angus Mail, version from the Quarkus BOM; compile scope since `ClaimsMailbox` uses it too):
    `messages(address)` reads one INBOX, `awaitMessage(address, atMost)` polls with Awaitility and returns the first message, and `allMessages()` reads
    every known user's INBOX. Messages come back as the `ReceivedEmail` record (`from`, `to`, `subject`, decoded
    `body` with `\r\n` line endings). Plain-text mail only: anything else throws `MailboxAccessException`.
  - **Purging, listing and deleting users is the GreenMail REST API** (`POST /api/mail/purge`, `GET /api/user`,
    `DELETE /api/user/{address}`), because IMAP only sees one mailbox at a time. The API returns messages only as raw MIME, which is why reading doesn't use it.
  - **Auth is disabled,** so any password logs in and an unknown address is just an empty INBOX (no error). IMAP
    logins and deliveries create users; purging removes mail, not users or folders. `deleteUser(address)` removes the
    user with its folders too, which is how `ClaimsMailboxTests` starts every test with only an empty claims INBOX.
  - Host and ports are `@ConfigProperty` constructor parameters: `quarkus.mailer.host`, plus `parasol.mail.imap-port`
    and `parasol.mail.greenmail-api-port`, which the Compose labels map in. Test code injects config properties
    directly; the `@ConfigMapping` convention is for main code.
  - **Purge in `@BeforeEach`,** not `@AfterEach`, so mail from other test classes can't leak into "no email sent"
    assertions (`NotificationServiceTests.assertNoEmailSent` checks `allMessages()` is empty).
  - `GreenMailMailboxTests` covers the helper with the app's own `Mailer`, no LLM: mailbox isolation and purge.
- **Claims mailbox tests** (`org.parasol.intake.mailbox`): `ClaimsMailboxTests` sends real MIME messages over SMTP with
  Jakarta Mail (the app's `Mailer` can't build multipart/alternative or set arbitrary headers) and reads them back
  through `ClaimsMailbox`: plain text, HTML-only, alternative, image and skipped attachments, auto-reply headers, quoted
  text, find by `Message-ID` and moving between folders. `QuotedTextTests` and `HtmlTextTests` are plain unit tests.
  `IntakeExtensionsTests` checks the app boots with the agentic and quarkus-flow extensions and Flow's three tables
  exist. They share `org.parasol.intake.IntakeTestProfile` (stub keys, Easy RAG ingestion off).
- **Claim image tests:** `ClaimImageTests` (entity, `@TestTransaction`), `ClaimImageResourceTests` (REST and Problem
  Details), `ClaimImageContentTypeTests` (allow-list, no Quarkus), `ClaimImageSeederTests` and the Playwright
  `ClaimImagesPageTests`.
  - Fixtures that the endpoints must see are committed with `QuarkusTransaction.requiringNew()` and removed with
    `Claim.deleteById` (cascades to images). `@TestTransaction` doesn't work for these: REST Assured calls the endpoint
    over HTTP, on another thread with its own transaction, so it can't see the test's uncommitted rows (verified: a
    claim persisted and flushed in a `@TestTransaction` is a `404` to the endpoint). `@TestTransaction` is fine for
    tests that only call the model directly (`ClaimImageTests`, `ClaimTests`).
  - Seeder tests that insert or delete images do it on a claim of their own, never on the seeded claims that other
    classes (e.g. `ClaimImagesPageTests`) rely on.
  - **REST tests compare responses as the DTO / `ProblemDetail` records** (`.as(...)` / `getObject(...)` then
    `isEqualTo`), not as `Map`s. A record round-trip can't see the wire format, since the same `@JsonNaming` serializes
    and deserializes, so each class keeps small JSON-level tests for that: the exact key set (`jsonFieldNames`,
    `metadataJsonFieldNames`), the category label and an omitted null `incident_time`.
  - Call Panache statics through a lambda in `assertThatThrownBy(() -> ClaimImage.flush())`: a method reference
    (`ClaimImage::flush`) bypasses Panache's enhancement and throws "did you forget to annotate your entity with
    @Entity?".

## Conventions

Beyond the global Java/Quarkus style rules in `CODE_STANDARDS.md`, this repo specifically uses:

- **Tabs for indentation**, tab width 2, max line length 180 (see `.editorconfig`). Note
  `insert_final_newline = false`.
- `io.quarkus.logging.Log` static methods (`Log.debugf`, `Log.warnf`) — no `Logger` fields.
- `@ConfigMapping` interfaces with `@WithDefault` — not `@ConfigProperty` fields.
- Constructor injection in `ai.scoring`; `org.parasol.notification.service.NotificationService` still uses `@Inject`
  fields (legacy — prefer constructor injection for new code).
- Hand-written fluent builders (e.g. `DriftDetectionException.builder()`), records for value types.
- **Moving a class to another package is safe; renaming an AI service interface or method is not.**
  Langfuse dataset names are the AI service span name, `langchain4j.aiservices.<SimpleClassName>.<method>`.
  Quarkus LangChain4j uses the simple interface name, `AiServiceDatasetSpanProcessor` and `ConversationExchange`
  copy the span name, and `DriftDetectionOutputGuardrail` rebuilds it from the simple name. A rename
  orphans the recorded datasets, and drift detection then passes silently.
- `Optional` chains over null checks and guard-clause early returns.

## Gotchas

- **Older revisions of this file described a two-module layout** (`parasol-app/` + `ai-scorer/`)
  communicating over a REST contract in `openapi/ai-interactions.yml`, with an
  `InteractionPublisher`/`InteractionScorer` pipeline and a `RESCORE` interaction mode. **None of
  that exists any more** — it collapsed into this single app scoring against Langfuse in-process,
  and `InteractionMode` is now `NORMAL`/`DRIFT_DETECTION`. Do not reintroduce references to it.
- **Tiers 2 and 3 are mutually exclusive in practice.** `%drift` is the only profile that sets
  `quarkus.aiscoring.interaction-mode: drift-detection`, and it declares `langfuse-ocp` as its
  parent profile — which sets `score-session: false` and disables the OTLP exporter. So whenever
  tier 3 is armed, tier-2 session scoring is off; only tiers 1 and 3 are live under `%drift`.
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
- **Never set `claim_number` on a claim that gets persisted: not in Java, SQL or seeds.** The only exceptions
  are tests that deliberately exercise the constraint and clean up after themselves (see `ClaimTests`);
  unpersisted fixtures such as the `PanacheMock` claim in `ClaimResourceTests` are fine.
  - A value set on a new `Claim` is ignored.
  - Changing it on a managed claim fails the whole flush (`HibernateException: An immutable natural
    identifier … was altered`), so other changes in that transaction are lost too.
  - In raw SQL, an explicit `NULL` violates NOT NULL (it isn't "use the default"), and a hand-picked value
    can collide with a future `nextval`. Omit the column or write `DEFAULT`.
- **Claim numbers have gaps and depend on insert order.** A rolled-back insert still uses up its `nextval`,
  and tests advance the sequence too.
  - Tests must assert only the format, uniqueness, or the 1009 step between two claims they created
    themselves, never an absolute number.
  - Tests must delete or roll back the claims they create. `ClaimsListPageTests` expects exactly six.
  - Reordering or inserting seed rows renumbers the later seeds, so look claim numbers up instead of
    hard-coding them.
- **The number exists only after the insert is flushed.** `persist()` defers the insert, because Panache ids
  come from a pooled sequence. Use `persistAndFlush()` when the number is needed in the same transaction.
- **`ClaimImageSeeder` runs on every start and re-inserts any missing configured image.**
  - Every profile currently recreates the schema on each boot (`%prod` / `%openshift` set `drop-and-create`; dev/test
    get it from Dev Services), so in practice each boot inserts all 12 ("Processed 12 seed claim images: 12 inserted,
    0 already present, 0 claim not found, 0 file missing/unreadable/empty"). The re-insert-missing path matters for a
    persistent schema, and `ClaimImageSeederTests` covers it by calling `seed` directly; a second run inserts nothing.
  - **It matches the sample claims by their explicit ids in `import.sql`** (claim numbers are generated, so they can't
    be listed). Keep `SEED_IMAGES` aligned if the seed claims change.
  - **Seed data never fails startup.** An image whose claim doesn't exist, or whose resource file is missing,
    unreadable or empty, is logged (WARN, with the reason) and skipped, and the rest are still seeded. A file is read
    only when it's about to be inserted.
  - `seed` does the work and returns the count of each outcome as a `SeedResult`; the `StartupEvent` observer logs it
    as one INFO summary. Tests assert the same `SeedResult`, which is how they check "already present" and "claim not
    found" (those leave nothing in the database).
  - Empty files are skipped before `store`: `ClaimImage.data` is `@NotEmpty`, and a violation at flush would roll
    back the whole run.
  - It runs in one transaction from a `StartupEvent` observer, so an unexpected database error still aborts boot.
- **The claim-number sequence can't be a `@SequenceGenerator`** (spike-verified on Hibernate 7.4.9).
  - **A lone class-level one takes over the `PanacheEntity` id.** This follows the JPA 3.2 default-generator rule:
    there's no `claims_seq`, and ids come from the claim-number sequence.
  - **With `PanacheEntityBase` and two generators,** the unused one is never created, so `CREATE TABLE` fails
    on the column default.
  - That's why `ClaimNumberGenerator` registers the sequence itself via `ExportableProducer`.
  - **`@ColumnDefault` can't move onto `@ClaimNumber`:** it's `@Target({FIELD, METHOD})`, and Hibernate reads
    it only directly from the field.
- **`lpad` would silently truncate claim numbers past 8 digits.** The sequence's `maxvalue 99999999` turns
  that into a `nextval: reached maximum value` error instead of wrong, colliding numbers. Don't drop it.
- **`Claim` inserts aren't JDBC-batched.** Hibernate disables batching for entities with insert-generated
  values. Claims arrive one at a time, so this doesn't matter.
- **`ClaimTests` logs two expected WARNs on success.** `ARJUNA012125` with a stack trace comes from
  `changingClaimNumberFailsTheFlush`, and `HHH000247` / `23505 duplicate key` from
  `duplicateClaimNumberIsRejected`. Neither is a failure.
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
