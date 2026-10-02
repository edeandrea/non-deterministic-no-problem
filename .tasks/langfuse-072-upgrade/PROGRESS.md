# quarkus-langfuse 0.6.0 -> 0.7.2 upgrade

Stop before commit — user reviews first.

## Verified facts
- 0.7.2 is latest on Maven Central. Baselines Quarkus 3.33.2 / langchain4j 1.13.1.
  Project is on Quarkus 3.39.2 / langchain4j 1.13.1 -> compatible.
- Extension compiles at release 17; project at 25. No conflict.
- LangfuseOperations grew 6 -> 17 domains in 0.7.1 ("Add eleven domains", PR #109).

## Newly available replacements for raw api() usage
| Current raw api() call | 0.7.2 replacement |
|---|---|
| LangfuseSessionScoringService.fetchSessionObservations (hand-built JSON filter) | observations().matching(ObservationFilter) — typed sessionId() + fields() |
| LangfuseSessionScoringService.saveScore | scores().create(CreateScoreRequest) -> CreateScoreResponse |
| LangfuseSessionScoringService.createDatasetItem | async().datasetItems().create(CreateDatasetItemRequest) |
| LangfuseDatasetSampleLoader.getDatasetItems (manual page math) | datasetItems().matching(DatasetItemFilter).streamAll() |

## Constraints that must NOT be "fixed"
- No `update` for evaluators. LangfuseEvaluationInitializer.updateEvaluatorModel stays on
  api().evaluators().evaluatorsUpdate(...). `update` exists on exactly one domain
  (annotation queue items).
- DatasetItemFilter has NO status criterion (only datasetName, sourceTraceId,
  sourceObservationId, version). DatasetStatus.ACTIVE filtering stays client-side.
- Do not adopt prompts/comments/annotation queues/blob storage/experiments — no use case.

## Baseline (task 01) — DONE
- 0.7.2 COMPILES CLEAN. Zero binary incompatibilities vs 0.6.0.
- Build command is `OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`.
  `-Dquarkus.profile=ollama` is NOT a substitute for the Maven `-Pollama` profile.
- Baseline is RED BEFORE the bump. Control run on 0.6.0 = identical:
  `Tests run: 68, Failures: 0, Errors: 3, Skipped: 1`. Same 3 tests, same messages.
- CORRECTED: these 3 are NOT bugs and NOT "pre-existing failures". They are an artifact of
  the agent lacking the user's real API keys. 1536 = OpenAI embedding dims, 1024 = local
  ollama embedding model; without OPENAI_API_KEY docs and queries get embedded by different
  models, hence the CosineSimilarity mismatch. The user sets real secrets when building
  manually. DO NOT propose fixing these, and do not treat a red build as a signal.
  Only COMPILATION success is a trustworthy signal from agent-run builds.
- Environment-dependent failures (expected when run without user secrets):
  - DriftDetectionChatRouteExceptionHandlerTests.driftIsDeliveredToTheClientAsAnError
  - LangfuseSessionScoringServiceTests.canFetchObservationsBySessionId
  - NotificationServiceTests.emailSendsWhenUserExists
  First two: IllegalArgumentException vector 1536 vs 1024 in Easy RAG CosineSimilarity.
- Passing and usable as regression signal: LangfuseDatasetSampleLoaderTests (5/5),
  DriftDetectionOutputGuardrailTests (3/3), AiServiceDatasetSpanProcessorTests (5/5).

## VERIFICATION GAP
`LangfuseSessionScoringServiceTests.canFetchObservationsBySessionId` is the test that would
cover task 02, and it is already red for an unrelated RAG reason. So task 02 has NO green
test guarding it. Task 08 must confirm it still fails with the SAME RAG error and not a new
Langfuse one. Flag to user.

## Tasks
- [x] 01 bump to 0.7.2, green baseline, no code changes
- [~] 02 observations -> ObservationFilter    (agent b7069715, w/ 03+04)
- [~] 03 scores().create                      (same agent as 02)
- [~] 04 datasetItems().create                (same agent as 02)
- [x] 05 sample loader streamAll()            DONE, verified by reading the file myself.
      97 -> 78 lines. Deleted fetchPage, buildGetDatasetItemsRequest, getActiveDatasetItems.
      getDatasetItems now returns List<DatasetItem> (was Stream) with .toList() INSIDE the try.
      ACTIVE filter client-side as required. supports() untouched.
- [ ] 06 LangfuseApiException.getServerMessage() error reporting
- [ ] 07 docs (CLAUDE.md 122/148/254/279, README.md 12)
- [ ] 08 full verify (ollama + default profiles)
- [ ] memory: update langfuse-operations-layer-boundary (six-domain claim now stale)
- [ ] memory: ci-ollama-profile-constraint / local-verification-skip-ollama-openai imply
      ollama needs no keys; reality is OPENAI_API_KEY=change-me IS required and
      -Dquarkus.profile=ollama != -Pollama. Correct after user review.

## Two laziness traps called out to the subagents
1. fetchSessionObservations sits in a retry loop -> its Uni must re-issue the request on each
   re-subscription (deferred), else the poll spins on a stale empty result and sessions
   silently go unscored.
2. Sample loader: streamAll() is lazy, but the existing LangfuseNotFoundException try/catch
   only covered an EAGER first-page fetch. Terminal op must be pulled inside the try or the
   404-as-empty behaviour is lost.

## fields(...) typo — RESOLVED, user decided
Verified against Langfuse's own OpenAPI spec (/api/public/v2/observations, "Field Selection"):
valid groups are core, basic, time, io, metadata, model, usage, prompt, metrics,
trace_context. There is NO `meta` group -> `core,basic,io,meta` silently requested a
non-existent group. Not rejected with 400 because the param is free-form text and the
extension passes it through unvalidated by design.
Harmless today: isCompleteExchange uses startTime/input/output and ConversationExchange uses
parentObservationId — all in core + io, which ARE correctly requested. Nothing reads metadata.
USER DECISION: change to "core,basic,io" (drop it) — matches what the code actually consumes.
Still TODO: apply after agent b7069715 finishes editing that file.
