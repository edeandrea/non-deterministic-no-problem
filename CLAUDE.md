# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

"Non-Deterministic? No Problem!" is a demo application (Parasol Insurance) showing how to test non-deterministic AI systems. It consists of two independent Quarkus applications:

- **`parasol-app/`** — The main insurance claims application with an AI chat bot
- **`ai-scorer/`** — A separate scoring service that evaluates AI interaction quality

Both share a contract defined in **`openapi/ai-interactions.yml`**.

## Commands

All Maven commands must be run from within each project's directory.

### parasol-app

```bash
cd parasol-app

# Dev mode (OpenAI, default) — requires OPENAI_API_KEY env var
./mvnw quarkus:dev

# Dev mode with Ollama (local LLM)
./mvnw -Pollama quarkus:dev

# Dev mode with Ollama via OpenAI-compatible endpoint
./mvnw -Pollama-openai quarkus:dev

# Run unit tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=PolitenessOutputGuardrailTests

# Run integration tests
./mvnw verify

# Build (skip tests)
./mvnw package -DskipTests
```

### ai-scorer

```bash
cd ai-scorer

# Dev mode — requires COHERE_API_KEY and GEMINI_API_KEY env vars
./mvnw quarkus:dev

# Run unit tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=InteractionScorerTests

# Run integration tests
./mvnw verify
```

### Frontend only (from `parasol-app/src/main/webui/`)

```bash
npm test        # Jest tests
npm run build   # Production build
```

## Architecture

### parasol-app (port 8080)

**AI Services** (Quarkus LangChain4j `@RegisterAiService`):
- `ClaimService` — session-scoped chat bot answering questions about a specific claim; uses RAG over policy PDFs from the classpath, with a `@ToolBox(NotificationService.class)` that can update claim status and trigger email
- `GenerateEmailService` — generates claim status notification emails as a JSON `{"subject", "body"}` structure; uses multiple output guardrails
- `PolitenessService` — an AI-powered guardrail check that evaluates politeness of an email body (model name: `politeness`)

**Model names** configured in `application.yml`: `parasol-chat`, `generate-email`, `politeness`. Each resolves to OpenAI or Ollama depending on the active Maven profile.

**REST & WebSocket endpoints**:
- `ClaimResource` — `GET /api/db/claims`, `GET /api/db/claims/{id}` (Panache entities backed by PostgreSQL)
- `ClaimWebsocketChatBot` — WebSocket at `/ws/query`; receives `ClaimBotQuery`, returns `ClaimBotQueryResponse`

**Email flow**: The chat bot calls `NotificationService` (a LangChain4j tool) when users ask to update claim status. `NotificationService` updates the DB, calls `GenerateEmailService` to produce a JSON email, and sends it via Quarkus Mailer (Mailpit in dev/test).

**Output Guardrails** on `GenerateEmailService` (all extend `GenerateEmailOutputGuardrail` → `JsonExtractorOutputGuardrail<Email>`):
- `EmailContainsRequiredInformationOutputGuardrail`
- `EmailStartsAppropriatelyOutputGuardrail`
- `EmailEndsAppropriatelyOutputGuardrail`
- `PolitenessOutputGuardrail` — delegates to `PolitenessService` for a second AI call

**AI Interaction Scoring** (`ai.scoring` package in parasol-app):
- `InteractionPublisher` observes `AiServiceStartedEvent` / `AiServiceCompletedEvent` CDI events from LangChain4j and forwards them to the `ai-scorer` service via a generated REST client
- Two modes (configured via `quarkus.aiscoring.interaction-mode` in `application.yml`):
  - `NORMAL` — fire-and-forget on a background thread
  - `RESCORE` — synchronous; throws `RescoreBelowThresholdException` if the score falls below the configured threshold
- The REST client is generated at build time from `openapi/ai-interactions.yml` into `ai.scoring.scorer` package

**Observability**: OpenTelemetry + Micrometer. Dev mode uses the LGTM dev service (Grafana/Loki/Tempo/Mimir). `InteractionObservabilityInterceptor` (bound via `@InteractionObserved`) adds custom spans and token-usage counters (`parasol.llm.token.{input,output,total}.count`).

**RAG**: `src/main/resources/policies/policy-info.pdf` is embedded at startup using Easy RAG with embedding reuse.

**Frontend**: React + TypeScript in `src/main/webui/src/`, served by Quarkus Quinoa (built to `dist/`). SPA routing is enabled.

### ai-scorer (port 8888)

A standalone Quarkus service that receives interaction events from `parasol-app` and scores them.

**REST endpoint**: Implements the `AiApi` interface generated from `openapi/ai-interactions.yml`. Runs on virtual threads (`@RunOnVirtualThread`).
- `POST /ai/interactions/events` — receives started/completed interaction events, stores them, and scores completed interactions
- `GET /ai/interactions` — query stored interactions by `applicationName`, `interfaceName`, `methodName`, date range
- `GET /ai/interactions/{uuid}` — get a single interaction by ID

**Scoring pipeline**:
1. `InteractionResource` receives an `InteractionEvent` and delegates to `InteractionService`
2. `InteractionService` stores events in PostgreSQL (via `InteractionEventRepository`), correlates started+completed pairs into `Interaction` records, then calls `InteractionScorer`
3. `InteractionScorer` scores interactions using one of two strategies (configurable via `ai.scoring.scoring-strategy`):
   - `ai-judge` — uses `InteractionEvaluator` with `AiJudgeStrategy` backed by **Google Gemini** (`gemini-2.5-flash`, model name: `judge`)
   - `semantic-similarity` — uses `SemanticSimilarityStrategy` with the local **BGE-small-en-v1.5** embedding model; threshold configurable
4. **NORMAL** mode: scores asynchronously, stores result. **RESCORE** mode: scores synchronously, returns score in the HTTP response so `parasol-app` can gate on it.

**Scoring model**: Cohere `rerank-v4.0-fast` (via `quarkus-langchain4j-cohere`) is used as the `ScoringModel` in `NORMAL` mode.

**Observability**: `InteractionScoringInterceptor` (bound via `@InteractionScored`) adds OTel spans and Micrometer gauges/counters for scored interactions (`interaction.scored`, `interaction.rescored`).

**MapStruct** mappers are used extensively to convert between domain objects and generated API model classes.

### Shared Contract

`openapi/ai-interactions.yml` defines the API between the two services:
- `parasol-app` uses `quarkus-openapi-generator` to generate a **client** (`ai.scoring.scorer` package)
- `ai-scorer` uses the standard `openapi-generator-maven-plugin` to generate the **server interface** (`ai.scoring.api.AiApi`)

### Key Configuration

| App | Required Env Vars |
|-----|------------------|
| `parasol-app` (default) | `OPENAI_API_KEY` |
| `parasol-app` (ollama) | none |
| `ai-scorer` | `COHERE_API_KEY`, `GEMINI_API_KEY` |

### Testing Approach

**parasol-app**:
- Guardrail unit tests use `@QuarkusTest` + `@InjectMock`/`@InjectSpy` + `dev.langchain4j.test.guardrail.GuardrailAssertions`
- REST tests use REST Assured; WebSocket tests in `ClaimWebsocketChatBotTests`
- E2E tests use Playwright (`org.parasol.ui` package)
- AI model calls are mocked via LangChain4j WireMock dev services during tests

**ai-scorer**:
- `InteractionScorerTests`, `AIJudgeScorerTests`, `SemanticSimilarityScorerTests` test scoring logic directly
- AI model calls (Cohere, Gemini) are mocked via WireMock (`src/test/resources/wiremock/`) in test profile
- Observability is disabled in test profile for both apps
