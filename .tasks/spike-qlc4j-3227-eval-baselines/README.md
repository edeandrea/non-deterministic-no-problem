# Spike: Live Langfuse Samples + quarkus-langchain4j#3227 Baselines

**Date:** 2026-11-24. **Status:** throwaway spike, kept for reference. **Not for merging:** `pom.xml` points the
`quarkus-langchain4j-testing-evaluation-*` artifacts at a locally built `999-SNAPSHOT`.

## Why

[quarkiverse/quarkus-langchain4j#3227](https://github.com/quarkiverse/quarkus-langchain4j/pull/3227) ("Introduce baseline
comparison in the evaluation framework", branch `eval-baseline`, tested at `1ec36ce`) adds `isNotWorseThanBaseline()`: a
run is compared sample by sample with an accepted baseline, a pending result goes to `target/evaluation-baselines`, and
the `langchain4j-eval` CLI reviews, accepts or rejects it.

It asks the same question as this app's tier 3 (see [`langfuse-evaluation.md`](../../langfuse-evaluation.md)): did a
change make things worse? The PR's samples come from a hand-written YAML file; this app's come from real sessions,
recorded as Langfuse datasets by tier 2. The spike tried the combination: **samples stay live in Langfuse, and the
accepted result is the PR's reviewed baseline.**

## What's on this branch

| Path | What |
|---|---|
| `pom.xml` | `testing-evaluation-{core,ai-judge,semantic-similarity}` pinned to `999-SNAPSHOT`, plus `testing-evaluation-junit5` (test) |
| `src/main/java/ai/scoring/langfuse/evaluation/LangfuseDatasetSampleLoader.java` | Sample name `getId()` (was `getDatasetId()`, which named every sample the same); the `ClaimBotQuery` fields parsed out of the recorded prompt into `Parameters`; an optional `spike.dataset-version` pin through `DatasetItemFilter.version(...)` |
| `src/test/java/ai/scoring/it/BaselineSpikeTests.java` | `@QuarkusTest @Evaluate`: `@SampleLocation("langchain4j.aiservices.ClaimService.chat")` → replay over `/_chat/routes` → blocking `SemanticSimilarityStrategy(appEmbeddingModel, 0.80)` + advisory `toAdvisory(Evaluator)` → `isNotWorseThanBaseline("ClaimService.chat")`. Its own `SpikeProfile` (real Ollama models, judge on Ollama, the dev-mode Langfuse) |
| `src/test/resources/evaluation-baselines/ClaimService.chat.json` | The baseline accepted after run 1 |
| `.tasks/spike-qlc4j-3227-eval-baselines/scripts/` | `ChatSession.java` (JBang: one chat session over the websocket, which records a live sample), `make_runs.py` (two Langfuse v4 experiments as OTLP spans), `Shot.java` (JBang + Playwright: Langfuse UI screenshots), `Render.java` (terminal transcript → PNG) |
| `screenshots/`, `transcripts/` | Langfuse UI and CLI review screenshots; the raw CLI review transcripts |
| `share/live-samples-meet-baselines.html` (+ `img/`) | The write-up shared with the PR's author |
| `issue-310-comment.md` | The decision posted on [#310](https://github.com/edeandrea/non-deterministic-no-problem/issues/310#issuecomment-7142941263): AI services record their inputs/outputs on their span |

## Findings

### Langfuse 4.70.1 (dev-mode Dev Service, `-Pollama`)

1. **Live samples get recorded.** Five chat sessions over `/_chat/routes` produced five `ACTIVE` items in
   `langchain4j.aiservices.ClaimService.chat`, with `score-session` off (`createDatasets` runs before that filter).
2. **An item's input is the whole generation prompt,** not the AI service call: system message, rendered user message, then
   the RAG excerpt, as one string. Replaying through `ClaimService` needs the `ClaimBotQuery` parsed back out. (Now
   Key Decision 8 of #310.)
3. **Dataset versioning works.**
   - `GET /api/public/dataset-items?datasetName=…&version=<ISO>` returns the dataset as it was then.
   - An edit keeps the item id and stamps a new per-item `version`.
   - An archived item disappears from the latest API read, but the UI still lists it as "Archived".
   - quarkus-langfuse 0.9.1's `DatasetItemFilter.Builder.version(OffsetDateTime)` does pinned reads.
4. **v4 runs in "events_only" mode.**
   - `GET /api/public/datasets/{name}/runs` answers "not available … in Langfuse v4 events_only mode".
   - An experiment is OTel spans carrying `langfuse.experiment.{id,name,dataset.id,item.id,item.version,item.expected_output,
     item.root_observation_id,metadata}` (attribute names read from the Langfuse image).
   - Experiments can be read through `GET /api/public/experiments` (needs `fromStartTime`) and `/experiment-items`. There's no REST create.
   - The legacy `POST /dataset-run-items` still answers 200, but nothing it writes shows up as an experiment.
5. **The baseline is a URL parameter only.**
   - The compare view is `/experiments/results?baseline=<expId>&c=<expId>`.
   - The experiments API is identical before and after choosing a baseline, and the OpenAPI spec has no baseline field.
   - This matches Langfuse's CI/CD docs: the approval and gate policy live in your repository.
6. **The compare view already shows the PR's idea:** items paired across runs, per-item deltas, and a summary
   (`screenshots/06-compare-baseline-A.png`).

### The PR over live samples

All runs: 4 live samples (one hand-corrected in Langfuse), `trials = 2`, chat `granite4:micro`, embeddings
`snowflake-arctic-embed`, judge `granite4:micro`, all on local Ollama.

| Run | What changed | Result |
|---|---|---|
| 1 | No baseline | Failed: "No baseline yet for ClaimService.chat, 4 samples pending review". CLI `review` → `d` → `a` wrote a 967-byte summary baseline. |
| 2 | Nothing | Passed, `unchanged`: mean 0.0. The judge's 1-of-2 split on the curated item reproduced exactly. |
| 3 | Prompt: one-word answers | Failed: −100 points, all 4 regressed. The advisory judge dropped only 12.5 points. Rejected. |
| 4a | 1 new live item recorded, read pinned to the old version | Passed, `unchanged`: still 4 samples. |
| 4b | Same, read at latest | 5 samples, the new one "new pending review". **Failed on noise:** one trial at similarity 0.7898 vs 0.80 is −12.5 points, with CI −37 to +12. Rejected. |

1. **They compose with no glue beyond the loader.** `@SampleLocation` resolves through the `SampleLoader` SPI. Live item ids
   make stable sample names.
2. **Pinning the dataset version is what makes live data gateable,** but the baseline file doesn't record the version it
   was measured on.
3. **Small datasets plus pass/fail scoring make the mean gate flaky.** One flip is 100 / (samples × trials) points.
4. **A small LLM judge is a weak blocker.** It passed one-word answers, and its explanations described details those
   answers don't contain. Advisory is the right place for it.
5. **Strategy names leak runtime classes:** `Evaluator_ClientProxy (advisory)`.
6. **The accepted result belongs in the repo,** which is what both the PR and Langfuse's own guidance do.

Suggestions sent upstream are in `share/live-samples-meet-baselines.html`.

## Reproducing

1. **Build the PR's evaluation modules** into `~/.m2` as `999-SNAPSHOT`:

   ```bash
   git fetch upstream pull/3227/head:pr-3227
   git worktree add ../qlc4j-pr-3227 pr-3227
   cd ../qlc4j-pr-3227
   ./mvnw -B install -DskipTests -Dno-format -Denforcer.skip -pl testing/evaluation/evaluation-review,testing/evaluation/evaluation-core,testing/evaluation/evaluation-strategies/semantic-similarity,testing/evaluation/evaluation-strategies/ai-judge,testing/evaluation/evaluation-junit5,testing/evaluation/evaluation-cli -am
   ```

2. **Run dev mode with Ollama** (`./mvnw -Pollama quarkus:dev`). Move `easy-rag-embeddings.json` aside first if it holds
   OpenAI vectors (1536 vs 1024 dimensions).
3. **Record live samples:** `jbang scripts/ChatSession.java <claimId> <inceptionDate> "<question>" < claim-summary.txt`, one
   session per call (closing the websocket ends the chat scope, which records the dataset items).
4. **Point the test at the dev Langfuse.** Its URL is the `langfuse/langfuse` container's mapped port 3000. The secret key
   is `LANGFUSE_INIT_PROJECT_SECRET_KEY` in that container's environment, and the public key is `quarkus`. Then:

   ```bash
   LF_URL=http://localhost:<port> LF_SECRET=<secret> SPIKE_DATASET_VERSION=<ISO timestamp, or empty for latest> \
     ./mvnw -B -Pollama test -Dtest=BaselineSpikeTests
   ```

5. **Review:** `java -jar <qlc4j-pr-3227>/testing/evaluation/evaluation-cli/target/quarkus-langchain4j-eval-cli-999-SNAPSHOT-runner.jar review`
   (interactive: `d` shows the responses, `a` accepts, `r` rejects).
