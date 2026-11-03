# Task 05: Rename `quarkus.aiscoring.*` to `aiscoring.*`

**Type:** Code and Configuration Modification

## Goal

The AI-quality layer's configuration lives under `aiscoring.*` instead of `quarkus.aiscoring.*`, every reader of those
keys follows, and the last 11 unrecognized-key warnings are gone — leaving a clean boot.

## What to Do

### 1. The two `@ConfigMapping` prefixes

- `src/main/java/ai/scoring/config/ScoringConfig.java:8` → `@ConfigMapping(prefix = "aiscoring")`
- `src/main/java/ai/scoring/langfuse/config/LangfuseConfig.java:9` → `@ConfigMapping(prefix = "aiscoring.langfuse")`

Nothing else in Java references these keys as strings. The 11 leaf keys are unchanged below the prefix:
`interaction-mode`, `threshold`, and `langfuse.evaluation.{initialize-on-startup, gemini.{model-name, api-key},
session.{create-dataset-on-session-close, score-session, otel-flush-wait-time, observation-poll-interval,
observation-max-wait-time, dataset-creation-max-wait-time}}`.

### 2. `src/main/resources/application.yml` — four nodes, each changing depth

Each `aiscoring:` node currently sits **inside** that profile's `quarkus:` map and must become a **sibling of
`quarkus:`** under the profile key:

| Line | Profile | Contents |
|---|---|---|
| 20 | `%drift` | `interaction-mode: drift-detection` |
| 56 | `%langfuse-ocp` | `langfuse.evaluation.session.score-session: false` |
| 322 | `%ollama` | `langfuse.evaluation.session.score-session: false`, `langfuse.evaluation.initialize-on-startup: false` |
| 435 | `%test` | same two as `%ollama` |

Note `%ollama` already has a top-level `parasol:` sibling of `quarkus:` (`:318-320`), so the shape to copy is right
there.

### 3. The rest of the readers

- `src/main/kubernetes/dependencies.yml:10` — the `parasol-app-config` ConfigMap key
  `quarkus.aiscoring.langfuse.evaluation.initialize-on-startup` → `aiscoring.langfuse.evaluation.initialize-on-startup`.
- `src/test/java/org/parasol/testing/LangfuseExportTestProfile.java:31-35` — five `Map.entry` keys.
- `src/test/java/ai/scoring/it/DriftDetectionTest.java:24` — `@EnabledIfConfig(named = "aiscoring.interaction-mode", …)`.

### 4. The diagram path, which is wrong today

`docs/continuous-scoring-sequence.puml:103` reads
`quarkus.aiscoring.langfuse.session.otel-flush-wait-time` — it is **missing the `.evaluation.` segment**, independently
of this rename. Fix the whole path to `aiscoring.langfuse.evaluation.otel-flush-wait-time`. Task 06 re-renders the PNG.

### 5. Finish with a sweep

```bash
grep -rn "quarkus\.aiscoring" . --exclude-dir=.git --exclude-dir=target --exclude-dir=node_modules
```

The only acceptable remaining hits are historical records that are deliberately not rewritten:
`.tasks/github-issues/issue-10-unrecognized-config-keys.md` (the issue body, annotated not rewritten),
`.tasks/doc-accuracy-review/FINDINGS.md`, and this task directory. Docs under `README.md`, `CLAUDE.md` and
`langfuse-evaluation.md` are task 06's job — if you prefer to do them here, do them *all* here and say so in
`PLAN.md`; what must not happen is a half-renamed doc set.

## Files/Areas

- `src/main/java/ai/scoring/config/ScoringConfig.java`
- `src/main/java/ai/scoring/langfuse/config/LangfuseConfig.java`
- `src/main/resources/application.yml` (four nodes, re-indented)
- `src/main/kubernetes/dependencies.yml`
- `src/test/java/org/parasol/testing/LangfuseExportTestProfile.java`
- `src/test/java/ai/scoring/it/DriftDetectionTest.java`
- `docs/continuous-scoring-sequence.puml`

## Key Points

- **Why `aiscoring.*` and not `parasol.aiscoring.*`** (user decision, recorded in `PLAN.md`): `ai.scoring` is
  documented as a reusable AI-quality layer ready to be extracted, so a Parasol-specific prefix would work against
  that. Either choice silences the warnings — only the `quarkus.` namespace is checked — so the tiebreaker is what the
  layer is *for*.
- **Re-indentation is the real risk.** A mis-indented YAML node is not a parse error, just a key nobody reads. The
  cheapest proof is behavioral, not visual: `%test` sets `initialize-on-startup: false` and `score-session: false`, so
  if the `%test` node lands in the wrong place the defaults (`true`/`true`) apply and `LangfuseEvaluationInitializer`
  starts trying to reach Gemini during tests. A green `verify` that doesn't log Langfuse initialization is the signal.
- **`DriftDetectionTest` is gated off in CI** (it needs the `drift` profile plus real OpenAI *and* Cohere keys), so the
  compiler is the only check on that line. Get it right by inspection — a stale key there turns a
  `@EnabledIfConfig` into a permanently-false condition, which looks exactly like the intended skip.
- **`LangfuseExportTestProfile` is a real `@TestProfile`** backing `LangfuseSessionScoringServiceTests` and
  `IntakeLangfuseDeliveryTests`; those two tests exercising Langfuse for real are the proof the rename landed on the
  runtime side.
- **The ConfigMap key change is untested locally.** It reaches the app through
  `quarkus.openshift.env.configmaps: parasol-app-config` (`application.yml:541`) and only matters on a cluster — it is
  on the Manual Checks list in `PLAN.md`, not in this task's Done When.
- `deploy-to-openshift.sh`, the webui and `compose-devservices.yml` mention none of these keys; confirmed by grep, no
  change needed.

## Done When

- [ ] Both `@ConfigMapping` prefixes are `aiscoring` / `aiscoring.langfuse`.
- [ ] All four `application.yml` nodes are siblings of `quarkus:`, not children.
- [ ] `dependencies.yml`, `LangfuseExportTestProfile` and `DriftDetectionTest` are updated.
- [ ] `docs/continuous-scoring-sequence.puml:103` names the full, correct path.
- [ ] The grep sweep returns only the historical-record files listed above.
- [ ] `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai` are both green, and the boot logs
      show Langfuse initialization and session scoring **off** under `%test` (i.e. the `%test` node is being read).
- [ ] **The acceptance check for the whole issue:** the unrecognized-key grep returns **zero** hits on both legs.
