# Task 02: Bump the quarkus-langchain4j BOM

**Type:** Code Modification

## Goal

Move `quarkus.langchain4j.version` in `pom.xml` to the latest stable release recorded in the version
inventory, and fix any compile or behaviour breakage it causes.

## What to Do

- Update `<quarkus.langchain4j.version>` in `pom.xml`.
- Confirm that every quarkus-langchain4j artifact the build uses is still managed by the new BOM:
  - `quarkus-langchain4j-openai`
  - `quarkus-langchain4j-ollama` (inside the `ollama` profile)
  - `quarkus-langchain4j-chat-scopes-websocket`
  - `quarkus-langchain4j-easy-rag`
  - `quarkus-langchain4j-testing-evaluation-core`, `-ai-judge`, `-semantic-similarity`
- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` (default profile), and fix every compiler error.
- Review the release notes / GitHub releases between the old and new versions for breaking changes
  affecting what this app uses:
  - guardrails: `JsonExtractorOutputGuardrail`, `OutputGuardrailResult`, `reprompt`/`fatal`
  - chat scopes (`@ChatScoped`, `@ChatRoute`, `LocalChatRoutes`, `WebsocketChatRoutes`)
  - `@ToolBox`, Easy RAG reuse-embeddings, the evaluation framework (`EvaluationReport`, `SampleLoader`)
- Check that the langchain4j version brought in by `quarkus-langfuse` 0.7.2 converges with the new one:
  `./mvnw dependency:tree -Dincludes=dev.langchain4j` must show one version per artifact.

## Files/Areas

- `pom.xml` — version property
- `src/main/java/**`, `src/test/java/**` — only where the new version forces changes

## Key Points

- `quarkus-langfuse` 0.7.2 baselines quarkus-langchain4j 1.13.1. Being *newer* than an extension's
  baseline is normally fine, but confirm it with the dependency tree instead of assuming.
- `DriftDetectionOutputGuardrail` builds Langfuse dataset names from the `InvocationContext`
  (`langchain4j.aiservices.<Interface>.<method>`). If the new version changes AI service span or
  invocation naming, drift detection breaks **silently**, because the guardrail returns success on a
  `SampleLoadException`. Check the span name format in the release notes.
- Follow the repo conventions in `AGENTS.md` (tabs, AssertJ, constructor injection, etc.).
- Agent-run builds don't have the user's real API keys. Treat compilation as the trustworthy signal,
  and report test failures caused by missing keys as such (e.g. the 1536-vs-1024 embedding-dimension error).

## Done When

- [ ] `pom.xml` pins the latest stable quarkus-langchain4j version from the inventory.
- [ ] `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` both succeed.
- [ ] `dependency:tree` shows a single version for each `dev.langchain4j` artifact.
- [ ] Every breaking change found in the release notes that affects this app is listed in `PLAN.md` Shared Context, with how it was handled.