# Issue 7: Triage the Unrecognized Configuration Keys Reported at Startup — Task Execution Plan

## Your Mission

Every boot logs a batch of `Unrecognized configuration key "…" was provided; it will be ignored` warnings — **35
distinct keys** across a full `verify`, plus a 36th that only appears in CI. Most are benign, but two are real: one
setting has never applied because of a one-word typo, and one names an extension that isn't on the classpath. Because
the warning fires for ~33 keys that are genuinely fine, the two real ones are invisible.

The target is **not** "document the noise". It is **zero unrecognized-key warnings on a clean boot**, under both CI
profiles, so that a warning appearing in future is by itself a signal that something is wrong. Every one of the 36 keys
resolves to fixed, removed or relocated — none to "expected warning".

**GitHub issue:** [#290](https://github.com/edeandrea/non-deterministic-no-problem/issues/290) (related to #218, which
found the micrometer key and deliberately left it alone). Body in
`.tasks/github-issues/issue-10-unrecognized-config-keys.md`, superseded by the decisions below.
**Plan File:** `.tasks/07-gh290-unrecognized-config-keys/PLAN.md`
**Tasks Directory:** `.tasks/07-gh290-unrecognized-config-keys/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/07-gh290-unrecognized-config-keys/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- Most of this is YAML. Where Java changes, make sure it compiles: `./mvnw -B clean test-compile -Pollama` and
  `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [x] [task-01-dead-keys-and-ci-flag.md](task-01-dead-keys-and-ci-flag.md): Delete the two dead keys and scope the CI Ollama Dev Service flag to the `ollama` leg
- [x] [task-02-review-state-wire-tests.md](task-02-review-state-wire-tests.md): Close the `ClaimReviewState` wire-shape coverage gap, before anything can move it
- [x] [task-03-jackson-key.md](task-03-jackson-key.md): Correct the Jackson optimization key and keep it on
- [x] [task-04-ollama-block.md](task-04-ollama-block.md): Move the Ollama block into `%ollama` behind `parasol.models.ollama.*`
- [x] [task-05-aiscoring-rename.md](task-05-aiscoring-rename.md): Rename `quarkus.aiscoring.*` to `aiscoring.*`
- [x] [task-06-docs-and-verification.md](task-06-docs-and-verification.md): Documentation, the re-rendered diagram, verification and the PR

> **Branch and PR: open, for the user to decide.** The precedent from #218 is one branch off `main` and one PR into
> `main` whose body ends `Closes #290`, with no closing keyword in the title. Suggested title: *Account for every
> unrecognized configuration key reported at startup*. Confirm before branching.

---

## Shared Context

### Task 01 outcome (2026-11-03)

Deleted `quarkus.micrometer.export.otlp.*` from `%drift` and `%test`, and `quarkus.dev-ui.cors.enabled` from the root
block, in `application.yml`. In `simple-build-test.yml`, the `-Dquarkus.langchain4j.ollama.devservices.enabled=false`
flag now comes from a step-level `OLLAMA_DEVSERVICES_FLAG` env var set to the flag only when `matrix.ai-provider ==
'ollama'` (empty string otherwise), interpolated into the `run:` command; the leading comment explaining the shared
Ollama service container now also notes the flag is ollama-leg-only. A full `./mvnw -B clean verify -Pollama` was
kicked off to confirm the Done When items but not watched to completion in this session — rerun it (and
`-Pollama-openai`) before task 06's final verification to confirm the key count dropped as expected.

### Task 02 outcome (2026-11-03)

Added `jsonFieldNames` and `requestedItemsOmittedWhenNothingIsRequested` to `ClaimReviewResourceTests`, both reading raw
JSON (not `.as(ClaimReviewState.class)`). Verified `ClaimReviewState.requestedItems` is non-empty if and only if the
claim is `Pending Information` (`ApplyOutcomeStep.setReviewState` / `ReviewDecisionSteps`), so an **open** review
(`Pending Review`) always omits `requested_items` via `non-empty` inclusion — the "open" and "closed-fixture"
(`NEVER_REVIEWED_CLAIM_ID`) cases turn out to have the *same* key set. `jsonFieldNames` therefore pins the fuller shape
(`status`, `version`, `open`, `requested_items`, `available_items`) via the `Pending Information` scenario already used
by `needsInformationDecisionMovesTheClaimToPendingInformationListingExactlyTheTickedItems`, and also asserts both
`requested_items` and `available_items` carry `MissingItem`'s `@JsonValue` labels, not constant names (one test, to
avoid a second Flow run). `requestedItemsOmittedWhenNothingIsRequested` pins the 4-key omitted-field shape against the
cheap `NEVER_REVIEWED_CLAIM_ID` fixture.

**`SentimentAssessment` decision:** given the cheap treatment — added a raw-key assertion
(`containsExactly("sentiment")`) into the existing `sentimentIsAssessedAgainAndStoredWithoutTouchingTheReview` test
rather than a new test, since it already has an open-review fixture and the single key is trivial.

`./mvnw test -Dtest=ClaimReviewResourceTests` passed (exit 0) against the unmodified Jackson key.

### Task 03 outcome (2026-11-03)

Fixed the spelling: `application.yml` now reads `enable-reflection-free-serializers: true`. `./mvnw package -DskipTests`
confirms generation actually happened: `target/quarkus-app/quarkus/generated-bytecode.jar` contains
`$quarkusjacksonserializer` classes for exactly the six expected types (`ClaimDetails`, `ClaimImageMetadata`,
`CorrespondenceMessage`, `ClaimReviewState`, `SentimentAssessment`, plus each package's `SerializedStrings` helper), and
no `ProblemDetail` serializer, as predicted.

**Correction to this task's own expectation:** no deserializer (`$quarkusjacksondeserializer`) is generated for
`ReviewDecisionRequest`, its nested `Fields`, or `SentimentAssessmentRequest` — and, per the vendored Quarkus source
checkout at `~/workspaces/quarkus/quarkus` (`JacksonDeserializerFactory.shouldGenerateCodeFor`, 3.40.1.1 bytecode
verified directly via `javap`), **no record type ever gets one in this Quarkus version**:
`JacksonDeserializerFactory` overrides `shouldGenerateCodeFor` to additionally require
`ClassInfo.hasNoArgsConstructor()`, which a Java record never has (only its canonical constructor). This is unconditional
— not caused by `@Valid`/Bean Validation annotations (confirmed `isUnknownAnnotation` only flags unrecognized
`com.fasterxml.jackson.*` and specific `jakarta.persistence.*` annotations, never `jakarta.validation.*`) and not
specific to the compact constructors in `ReviewDecisionRequest`/`Fields` (`SentimentAssessmentRequest` is a plain
one-field record with no compact constructor and still gets nothing). Every request-body record keeps deserializing
through reflective Jackson exactly as before this task — unaffected by the flag, serialization-only in practice for this
codebase since every REST input DTO here is a record. Noting this for task 06's documentation: don't imply the
optimization covers deserialization at all, not just `ProblemDetail`.

All of task 03's targeted test classes passed (`ClaimResourceTests`, `ClaimImageResourceTests`,
`ClaimCorrespondenceResourceTests`, `ClaimRetrievalHasNoIntakeBookkeepingTests`, `ClaimReviewResourceTests`,
`IntakeJsonRoundTripTests`, `IntakeOutcomeRoundTripTests`, `ClaimExtractionValueRoundTripTests` — exit 0), and a full
`package` build succeeded end-to-end (confirmed via its console log: `BUILD SUCCESS`, a complete intake workflow run
through `waitReview`/`applyReady`/`markInProcess` to `claim-intake` `COMPLETED`). `ClaimReviewPanelPageTests` and
`RoundcubeClaimIntakeE2ETests` (Playwright, failsafe/integration-test phase) are deferred to the single end-of-plan
`./mvnw -B clean verify` run (task 06), per the user's request to run the full build only once, at the end.

### Task 04 outcome (2026-11-03)

Added `parasol.models.ollama.{chat-model-id,email-model-id,embedding-model-id}` at root (with the moved comments), moved
the whole root `quarkus.langchain4j.ollama.*` node into `'%ollama'` → `quarkus.langchain4j.ollama.*`, and repointed all
five `%ollama-openai` interpolations from `${quarkus.langchain4j.ollama....model-id}` to
`${parasol.models.ollama.*-model-id}`.

**Two problems found and fixed while verifying with real dev-mode boots (not just `verify`), both load-bearing:**

1. **A YAML indentation bug in the move itself.** The moved `ollama:` node landed at the same indentation as
   `langchain4j:` (both children of `quarkus:`) instead of nested inside it — i.e. it silently became
   `quarkus.ollama.*`, not `quarkus.langchain4j.ollama.*`. This is exactly the failure mode `PLAN.md`'s Caveats section
   warned about for task 05's `aiscoring:` move ("a mis-indented node is not an error, just a key nobody reads") — it
   just hit task 04 first. Caught only because `./mvnw -Pollama quarkus:dev` was smoke-tested per the task's own "Key
   Points" advice; a `grep` for the old key alone (first Done-When item) would have passed even with the bug, since
   `quarkus.langchain4j.ollama...` genuinely no longer appears anywhere — it's `quarkus.ollama...` that showed up as a
   *new* unrecognized-key family instead. Fixed by re-indenting the whole node one level deeper, under `langchain4j:`,
   sibling to `openai:`.
2. **A genuine provider ambiguity for `session-sentiment`/`judge`, unmasked by the move.** With the Ollama block at an
   unprofiled root (the pre-task-04 state), `-Pollama quarkus:dev` booted fine. Once it moved inside `'%ollama'`
   (indentation fixed), boot failed: `io.quarkus.runtime.configuration.ConfigurationException: ... the
   'quarkus.langchain4j.session-sentiment.chat-model.provider' needs to be set to one of the available options
   (ollama,openai)` (same for `judge`). Verified by bisection (`git stash`/`pop` against the original file, same
   command, same flags): the original boots clean, the moved-but-unpinned version fails deterministically and
   immediately (before any dev service starts), every time. Root cause not fully traced through
   quarkus-langchain4j's `BeansProcessor.selectProvider`, but the fix is unambiguous and matches the already-documented
   intent (CLAUDE.md -> Models: "session-sentiment and judge keep provider: openai ... they are not moved to Ollama"):
   added explicit `quarkus.langchain4j.session-sentiment.chat-model.provider: openai` and the same for `judge` inside
   `'%ollama'`, next to the five existing `provider: ollama` pins. This was implicit-by-omission before (and happened
   to work by accident while the config lived outside any profile); now that both the `openai` and `ollama` named
   configs for these two models live in the same active profile, it has to be explicit.

**Verification (smoke boots, not yet the deferred full `verify`):** `./mvnw -Pollama quarkus:dev` and
`./mvnw -Pollama-openai quarkus:dev` (both run with `-Dquarkus.langfuse.devservices.enabled=false` and stubbed
Langfuse coordinates, to skip the slow/flaky Langfuse Dev Service stack while smoke-testing config correctness; LGTM
also disabled) both reach `Listening on: http://localhost:8080` with **zero** `Unrecognized configuration key`
warnings outside the 11 `aiscoring` ones. `-Pollama`'s dev-seed startup shows real Ollama calls using `qwen3:4b` for
`claim-intake` (confirming the `email-model-id` interpolation); `-Pollama-openai`'s shows `granite4:micro` for every
chat model (confirming the `chat-model-id` interpolation and the documented "no `think` equivalent" design). A
targeted `./mvnw test -Dtest=ClaimReviewResourceTests,IntakeExtensionsTests,ClaimsMailboxAgentTests` (bare `%test`
group, unaffected by any of this since `%test` wins over `%ollama` and pins its own providers) passed, exit 0. The two
full `./mvnw -B clean verify` runs (both profiles) are deferred to task 06 per the user's request.

### Task 05 outcome (2026-11-03)

Renamed both `@ConfigMapping` prefixes (`ScoringConfig` → `aiscoring`, `LangfuseConfig` → `aiscoring.langfuse`), and
moved all four `application.yml` `aiscoring:` nodes (`%drift`, `%langfuse-ocp`, `%ollama`, `%test`) out from under each
profile's `quarkus:` map to be a sibling of it, re-indenting their children two spaces shallower each (verified
precisely per node, not just by boot). Updated the three other readers:
`src/main/kubernetes/dependencies.yml`'s ConfigMap key, `LangfuseExportTestProfile`'s five `Map.entry` keys, and
`DriftDetectionTest`'s `@EnabledIfConfig` name. Fixed `docs/continuous-scoring-sequence.puml:103`'s pre-existing wrong
path (missing `.evaluation.`) to `aiscoring.langfuse.evaluation.session.otel-flush-wait-time` — task 06 re-renders the
PNG. Left the `README.md`/`CLAUDE.md`/`langfuse-evaluation.md` prose mentions for task 06, as the task file allows.

**Verification:** `./mvnw -B clean test-compile` is clean. A targeted
`./mvnw test -Dtest=LangfuseSessionScoringServiceTests,IntakeLangfuseDeliveryTests,DriftDetectionOutputGuardrailTests`
(real Langfuse Dev Services, `LangfuseExportTestProfile` and the bare `%test` group) passed, exit 0 — proof the rename
reads correctly on both the `LangfuseExportTestProfile` override path and the plain `%test` stubbing path.
`./mvnw -Pollama quarkus:dev` (same stubbed-Langfuse-coordinates smoke-boot technique as task 04) booted clean with
**zero** `Unrecognized configuration key` warnings at all — the acceptance check for the whole issue, confirmed on this
leg. **The `-Pollama-openai` leg's zero-warnings check is deferred to the PR's CI build** (user's call, to avoid
re-running the full local smoke-boot a third time) rather than a local `quarkus:dev` boot; task 06 should confirm it
from the CI run before treating the issue as fully closed. The grep sweep for `quarkus.aiscoring` across the repo
(excluding `target`/`node_modules`) returns only the three expected buckets: the historical issue body
(`.tasks/github-issues/issue-10-unrecognized-config-keys.md`), `.tasks/doc-accuracy-review/FINDINGS.md`, and this task
directory's own files — plus `README.md`/`CLAUDE.md`/`langfuse-evaluation.md`, deliberately deferred to task 06.

### Task 06 outcome (2026-11-03)

Added the *zero-unrecognized-keys invariant* section to `CLAUDE.md` (before *Configuration profiles*), recording the
three structural rules (app config outside `quarkus.`, profile-scoped extension keys inside their profile, and
double-check indentation with a real boot) rather than a list of expected warnings. Updated every `quarkus.aiscoring.*`
reference in `CLAUDE.md`, `README.md` and `langfuse-evaluation.md` to `aiscoring.*` (simple prefix substitution,
re-read each site to confirm it still reads correctly). Updated `CLAUDE.md`'s *Models* section with the
`parasol.models.ollama.*` indirection and the now-explicit `session-sentiment`/`judge` provider pins, the *CI* section's
sentence about the Dev Service flag being leg-specific, and added a *Testing* bullet for `ClaimReviewResourceTests`'
new raw-key tests and the Jackson reflection-free-serializer behavior (application-wide registration, generated only
from JAX-RS return/POST-PUT-PATCH-parameter types, no record ever gets a deserializer, `ProblemDetail` never gets a
serializer). Added a superseded-note to `issue-10-unrecognized-config-keys.md` and a one-line "done in #290"
annotation to `.tasks/06-gh218-grafana-ai-dashboard/PLAN.md`. Re-rendered all diagrams with
`./docs/render-diagrams.sh`; only `continuous-scoring-sequence.png` changed, as expected — every other PNG is
byte-identical, so no renderer-version drift to flag.

**Repo-wide sweep (excluding `.git`/`target`/`node_modules`):** `quarkus.aiscoring`, `enable-reflection-free-serialization`,
`micrometer.export.otlp` and `quarkus.dev-ui.cors` all now hit only inside `.tasks/` — task plan files for this and
earlier issues (including this plan's own tables, which quote the original key names on purpose), the historical
`issue-10` body, and `.tasks/06-gh218-grafana-ai-dashboard/`'s own history. No hits in `CLAUDE.md`, `README.md`,
`langfuse-evaluation.md`, `src/`, or `docs/*.puml`.

**Verification — changed scope from the task file, by explicit user instruction (twice, mid-task):** do not run the
full `./mvnw -B clean verify` locally for either profile; watch the PR's CI build instead. What stands in as local
evidence:
- `-Pollama quarkus:dev` (task 05's smoke-boot) reached `Listening on: http://localhost:8080` with **zero**
  `Unrecognized configuration key` warnings of any kind — the acceptance check for the whole issue, confirmed on that
  leg.
- Targeted test runs across tasks 02-05 (`ClaimReviewResourceTests`, `ClaimResourceTests`, `ClaimImageResourceTests`,
  `ClaimCorrespondenceResourceTests`, `ClaimRetrievalHasNoIntakeBookkeepingTests`, `IntakeJsonRoundTripTests`,
  `IntakeOutcomeRoundTripTests`, `ClaimExtractionValueRoundTripTests`, `IntakeExtensionsTests`,
  `ClaimsMailboxAgentTests`, `LangfuseSessionScoringServiceTests`, `IntakeLangfuseDeliveryTests`,
  `DriftDetectionOutputGuardrailTests`) all passed, exit 0.
- `./mvnw package -DskipTests` (task 03) confirmed the generated reflection-free serializer classes.
- `./mvnw -B clean test-compile` (task 05) is clean.
- **Not locally verified:** the `-Pollama-openai` leg's boot/unrecognized-key count, and both legs' full `verify`
  totals (test counts, before/after key counts). **These come from the PR's CI run** — check both matrix legs (`ollama`,
  `ollama-openai`) are green and that neither leg's console log contains `Unrecognized configuration key` before
  treating #290 as closed. The baseline (35/36 keys, pre-task-01) is already recorded above in *The 36 keys* table and
  *Baseline*; there is no new before/after local measurement to add beyond what's already there, since the final
  measurement is CI's.

### Overview

Quarkus builds its known-key model for the `quarkus.` namespace from **extension metadata**
(`META-INF/quarkus-config-doc/quarkus-config-model.json` in each `*-deployment` jar). Anything under `quarkus.` that no
extension on the classpath declares is reported by
`io.quarkus.runtime.configuration.ConfigDiagnostic.unknown(String)`. Three consequences drive this whole issue:

1. An **application** `@ConfigMapping` placed under `quarkus.` is always "unrecognized", however well it works.
2. A key belonging to an extension that is only a dependency **under a Maven profile** is "unrecognized" on every other
   profile, if the key itself sits at root level in `application.yml`.
3. Keys outside the `quarkus.` namespace (`parasol.*`, and after task 05 `aiscoring.*`) are **never checked**, so they
   never warn.

There is **no suppression switch**. The core config model declares only `quarkus.config.fixed-at-build-time` and
`quarkus.config.sources.system-only`; nothing turns the warning off short of muting the `io.quarkus.config` logger,
which would hide future real typos. Fixing the keys is the only route to a quiet boot.

### The 36 keys (verified 2026-11-03)

| Count | Keys | Cause | Resolution | Task |
|---|---|---|---|---|
| 21 | `quarkus.langchain4j.ollama.*` | Block sits at root level (`application.yml:162-204`); `quarkus-langchain4j-ollama` is only a dependency under the `ollama` Maven profile (`pom.xml:410`) | Move into `%ollama`; neutral indirection keys for the two ids `%ollama-openai` interpolates | 04 |
| 11 | `quarkus.aiscoring.*` | App `@ConfigMapping`s under the `quarkus.` namespace | Rename the prefix to `aiscoring.*` | 05 |
| 1 | `quarkus.rest.jackson.optimization.enable-reflection-free-serialization` | Typo — the declared key is `…-serializers` | Correct the spelling, keep it `true` | 02, 03 |
| 1 | `quarkus.micrometer.export.otlp.enabled` | Config root of `quarkus-micrometer-registry-otlp`, not a dependency | Delete from `%drift` and `%test` | 01 |
| 1 | `quarkus.dev-ui.cors.enabled` | Declared in `quarkus-devui-deployment`, dev-mode only | Delete — the extension's own default is already `true` | 01 |
| 1 | `-Dquarkus.langchain4j.ollama.devservices.enabled=false` | Passed on both CI matrix legs; the extension is absent on `ollama-openai` | Scope the flag to the `ollama` leg | 01 |

The 21 + 11 arithmetic checks out exactly: the root Ollama block has 21 leaf keys, and `ScoringConfig` (2) +
`LangfuseConfig` (9) declare 11 — including defaults never written in YAML, such as `threshold` and
`gemini.model-name`, which the mapping materializes.

### Baseline (measured 2026-11-03, before any change)

The last `-Pollama` build in `target/surefire-reports` reports **14** distinct keys — the 11 `aiscoring` ones plus
`dev-ui.cors.enabled`, `micrometer.export.otlp.enabled` and the Jackson typo. The 21 Ollama keys are absent there
precisely because `-Pollama` is the one profile where that extension **is** on the classpath. A default-profile or
`-Pollama-openai` run is where the full 35 appear. Capture both before/after:

```bash
grep -rhoE 'Unrecognized configuration key "[^"]+"' target/surefire-reports target/failsafe-reports \
  | sed -E 's/.*key "([^"]+)".*/\1/' | sort -u
```

### What the Jackson optimization actually does (verified against `quarkus-rest-jackson` 3.40.1.1)

The declared key is `quarkus.rest.jackson.optimization.enable-reflection-free-serializers`
(`JacksonOptimizationConfig#enableReflectionFreeSerializers`, `@WithDefault("false")`). Checked against every
`quarkus-rest-jackson-deployment` jar in the local `.m2` from 3.30.6 to 999-SNAPSHOT: the `-serializers` spelling is the
only one that has ever existed. So the optimization has **never** been on here.

Turning it on is low risk, for four reasons established by reading the generator:

- `JacksonCodeGenerator.SUPPORTED_JACKSON_ANNOTATIONS` covers **every** annotation this repo uses — `@JsonNaming`,
  `@JsonValue`, `@JsonCreator`, `@JsonIgnore`, `@JsonTypeInfo`, `@JsonSubTypes`. There is no
  `@JsonSerialize`/`@JsonDeserialize`, no custom `StdSerializer`, no `ObjectMapperCustomizer` anywhere in
  `src/main/java`.
- An unsupported annotation makes the generator **skip** the class ("Skipping generation of reflection-free Jackson
  serializer for class %s because it contains the unsupported Jackson annotation %s", debug level) and fall back to
  reflection. It degrades; it does not emit wrong output.
- `@JsonNaming` (`JacksonCodeGenerator.getNamingStrategy`), `@JsonValue`
  (`JacksonSerializerFactory.serializeJsonValue`) and the global inclusion
  (`JacksonMapperUtil.SerializationInclude.decode(object, serializerProvider)`, which reads the provider's own config)
  are all honored by the generated bytecode. `quarkus.jackson.serialization-inclusion: non-empty`
  (`application.yml:244-245`) is load-bearing — it is what omits `incident_time` and an empty `images` list.
- A `java.time` field with no `@JsonFormat` falls through `JacksonSerializerFactory`'s format-shape chain to
  `writeFieldValue`, which delegates to the serializer provider. `LocalDate`/`LocalTime`/`Instant` keep JavaTimeModule
  behavior and stay ISO-8601.

Two facts that widen the picture without blocking it:

- **The registration is application-wide, not REST-only.** `GeneratedSerializersRegister implements
  io.quarkus.jackson.ObjectMapperCustomizer` and installs a `SimpleModule` on the CDI `ObjectMapper`. In practice
  nothing changes for Flow: generation is seeded only from JAX-RS method return types and POST/PUT/PATCH parameter
  types (`ResteasyReactiveJacksonProcessor.handleEndpointParams`), transitively through their field types — and
  `IntakeOutcome`, `IntakeState`, `IntakeRun` and `ReviewDecision` are reachable from none of them. The round-trip
  tests are the guard, not a prediction.
- **`ProblemDetail` gets no generated serializer.** Every mapper returns `Response` with the entity typed only at
  runtime, so there is no static return type to generate from and the error path keeps going through reflective
  Jackson. The optimization simply does not cover `application/problem+json`. Say so rather than implying parity.

### Key Decisions (user, 2026-11-03)

- **Jackson key: correct the spelling and keep it `true`.** Not deleted, not corrected-but-disabled.
- **`aiscoring.*`, not `parasol.aiscoring.*`.** `ai.scoring` is documented as a reusable AI-quality layer ready to be
  extracted (`CLAUDE.md` → *Conversation core* makes the same point about `ai.scoring.conversation`), so a
  Parasol-specific prefix would work against that. A bare prefix is outside the `quarkus.` namespace either way, which
  is all that is needed to silence the 11 warnings.
- **Move the Ollama block into `%ollama`** rather than documenting 21 expected warnings.
- **Scope the CI flag to the `ollama` leg** rather than leaving it.

### Caveats & Problems

- **`%ollama-openai` interpolates out of the Ollama block**, which is why task 04 cannot be a straight move. Five lines
  (`application.yml:362, 366, 370, 374, 376`) read
  `quarkus.langchain4j.ollama.parasol-chat.chat-model.model-id` and `…embedding-model.model-id`. They need a home
  outside a profile-scoped config root — hence `parasol.models.ollama.*`, the same indirection the file already
  documents at `application.yml:307-314` for `parasol.models.provider` / `base-url`.
- **The four `aiscoring:` YAML nodes change depth.** At `application.yml:20` (`%drift`), `:56` (`%langfuse-ocp`), `:322`
  (`%ollama`) and `:435` (`%test`) they currently sit **inside** that profile's `quarkus:` map, and must become a
  **sibling of `quarkus:`** under the profile key. Easy to get silently wrong in YAML — a mis-indented node is not an
  error, just a key nobody reads.
- **`ClaimReviewState` and `SentimentAssessment` have no raw-key test.** `ClaimReviewResourceTests` reads the review
  through `.as(ClaimReviewState.class)` (`:474-483`), the symmetric round-trip the other resource tests explicitly warn
  against — the same `@JsonNaming` serializes and deserializes, so it cannot see a naming or label change. The frontend
  reads those keys literally (`ReviewPanel.tsx:12-13`, `PendingInformation.tsx:20`). That gap is closed in task 02,
  **before** task 03 touches the Jackson key, so the tests are a genuine before/after guard rather than a snapshot of
  whatever the new code does.
- **Don't move `quarkus.dev-ui.cors.enabled` to `%dev` instead of deleting it.** Under `-Pollama` / `-Pollama-openai`,
  dev mode runs as `<ai>,prod`, so `%dev` would not be active — the same trap the mailer and Compose keys sit at root
  level to avoid (`CLAUDE.md` → *Commands*). Deleting is correct because the extension's own default is already `true`
  (verified in `quarkus-devui-deployment-3.40.1.1.jar`'s config model: `"defaultValue" : "true"`, `phase: BUILD_TIME`).
- **`docs/continuous-scoring-sequence.puml:103` is already wrong today**, independently of this issue: it says
  `quarkus.aiscoring.langfuse.session.otel-flush-wait-time`, missing the `.evaluation.` segment. Task 05 fixes the whole
  path, not just the prefix, and task 06 re-renders the PNG.
- **`CLAUDE.md` claims the Dev Service flag is "the **only** CI-specific config".** Task 01 makes it leg-specific, so
  that sentence needs adjusting in task 06 to stay true.
- **The `%test` micrometer key is a self-reference**, `"${%test.quarkus.observability.enabled}"`
  (`application.yml:457`), pointing at the key four lines above it. It goes with the rest of the block.
- **Line numbers drift.** Every number here was read on 2026-11-03 against the current `main`
  (`d47c0db`). Re-grep rather than trusting an offset after an earlier task has edited the file.

### Manual Checks (for the user)

- **Cluster:** `src/main/kubernetes/dependencies.yml:10` carries the renamed ConfigMap key, consumed through
  `quarkus.openshift.env.configmaps: parasol-app-config` (`application.yml:541`). Nothing else on the cluster side
  changes, and `deploy-to-openshift.sh` never mentions the key, but the next `deploy-to-openshift.sh` is where the
  rename is proven in anger.
