# Issue 1: Dependency and Version Upgrades — Task Execution Plan

## Your Mission

Bring quarkus-langchain4j and every other dependency, plugin, container image, runtime pin and CI
action to its latest **stable** release, before any feature work starts. This is the first of five
issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/01-gh212-dependency-upgrades/PLAN.md`
**Tasks Directory:** `.tasks/01-gh212-dependency-upgrades/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/01-gh212-dependency-upgrades/task-XX-*.md`:
- **Goal**: what you're trying to achieve
- **Key Points**: things to watch out for
- **Done When**: the acceptance criteria

### 3. Execute the Task
- Make the changes, following the global rules in `AGENTS.md` (coding style, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task**, not later.
- Check every Done When item.

### 4. Update This Plan
- Mark the task complete in `## Task Plan`.
- Add a 1–2 sentence outcome summary under `## Shared Context`.
- Record only decisions that affect later tasks.

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

- [x] [task-01-version-inventory.md](task-01-version-inventory.md): Version inventory (current vs latest stable)
- [x] [task-02-quarkus-langchain4j-bom.md](task-02-quarkus-langchain4j-bom.md): Bump the quarkus-langchain4j BOM
- [x] [task-03-remaining-maven-versions.md](task-03-remaining-maven-versions.md): Bump remaining Maven dependencies and plugins
- [x] [task-04-images-and-runtime-pins.md](task-04-images-and-runtime-pins.md): Bump container images, runtime pins and CI actions
- [x] [task-05-documentation.md](task-05-documentation.md): Update documentation for the version bumps
- [x] [task-06-verification.md](task-06-verification.md): Verify the upgrade

---

## Shared Context

### Overview
This issue upgrades versions only; there are no feature changes. It goes first so the agentic module
(issue 5) arrives at its latest version and upgrade risk stays separate from feature risk.

### Project Context
- `pom.xml` holds the version properties: `quarkus.platform.version`, `quarkus.langchain4j.version`,
  `quarkus.langfuse.version`, `quarkus.quinoa.version`, `quarkus.playwright.version`,
  `quarkus.wiremock.version`, `quarkus.mailpit.version`, and the plugin versions.
- `src/main/kubernetes/dependencies.yml` defines the cluster dependencies (PostgreSQL, LGTM, Mailpit),
  applied by `deploy-to-openshift.sh`.
- `.github/workflows/simple-build-test.yml` is CI: `./mvnw -B clean verify -P{ollama,ollama-openai}`
  on Java 25, with only `OPENAI_API_KEY=change-me`.
- `CLAUDE.md` is the project context file; `README.md` is the user-facing overview.

### Key Decisions
- Latest **stable** only. Pre-releases (Beta/CR/M/alpha/rc) are recorded but not adopted.
- Mailpit pins are left alone; Mailpit is removed in issue 4.
- npm packages in `src/main/webui/package.json` are out of scope.
- Every task updates the docs it affects, per the documentation policy in `AGENTS.md`.
- **Inventory decisions (user, after task 01):**
  1. Node/npm: stay on LTS. Node goes to the latest 24.x LTS. npm stays on the line bundled with that Node LTS, which is 11.x, not 12.
  2. PostgreSQL: keep the floating `18` tag. No pin, no PVC action.
  3. maven-dependency-plugin: pin it explicitly to 3.11.0.
  4. Langfuse Helm chart: pin it to 2.1.3 in `deploy-to-openshift.sh`.
  5. cert-manager (commented-out line): bump to v1.21.2.
  6. PlantUML jar (`docs/render-diagrams.sh`): bump to 1.2026.8 and re-render the diagrams.
  7. `.github/dependabot.yml`: leave it as is. Don't add the docker or npm ecosystems.
- Work happens on branch `issue-212-dependency-upgrades` (PR #219).

### Caveats & Problems
- Agent builds don't have the user's real API keys. Only compilation is a trustworthy signal; the full
  `verify` with real keys is handed to the user.
- A drift-detection breakage from span or invocation naming changes would be silent (see task 02).
- A PostgreSQL major-version bump may need the cluster's `db-data-pvc` wiped by hand.

### Task Outcomes
- **Task 01 (inventory):** No #212 work existed on `main` (`c39dadb`). Only the Quarkus platform (3.40.1) was already at latest. Open Dependabot PRs #209 (Quinoa 2.9.1), #210 (ql4j BOM 1.14.0) and #211 (Maven 3.10.0) are superseded by this issue; close them when it lands. Spike worktree `../non-deterministic-no-problem-spike-agentic` (`spike/agentic-hitl`) exists. Leave it alone.

- **Task 02 (ql4j BOM 1.13.1 → 1.14.1):** The only edit is `pom.xml:14`, on branch `issue-212-dependency-upgrades` (uncommitted).
  - Both profiles compile with no new deprecations.
  - `dependency:tree`: one version per artifact. langchain4j is 1.20.2 (beta 1.20.2-beta30) and ql4j is 1.14.1; quarkus-langfuse 0.7.2 brings in no langchain4j artifacts.
  - The span name is unchanged (`AiServicesProcessor.java:3064` at the 1.14.1 tag) and `InvocationContext` still has `interfaceName()`/`methodName()`, so drift detection is safe.
  - No config keys were renamed or removed.
  - Behaviour changes:
    - (#2763) The OpenAI client no longer sends temperature/top-p unless they're configured. This affects `session-sentiment` (no temperature set, `application.yml:117-121`) and top-p on every OpenAI-client model.
    - (#2805) String tool results are now passed through raw instead of JSON-quoted (`NotificationService.updateClaimStatus`). Harmless.
  - No doc mentions ql4j/langchain4j versions, so no doc changes were needed.
  - **User decision: restore the pre-1.14 sampling behaviour.** All five OpenAI-client chat models (`parasol-chat`, `generate-email`, `politeness`, `session-sentiment`, `judge`) now set temperature/top-p explicitly in `application.yml:97-134`.
    - Added `top-p: 1` to all five, plus `temperature: 1` on `session-sentiment`. `judge` keeps temperature 0.
    - These match the 1.13.1 `ChatModelConfig` defaults: temperature 1.0, top-p 1.0. Presence and frequency penalties are unchanged and are still sent as 0.
    - `CLAUDE.md`'s Models section notes why the explicit values must stay.
    - Both profiles compile.
- **Task 03 (remaining Maven):**
  - **Bumps:** Quinoa 2.9.2 (`pom.xml:17`), quarkus-playwright 2.3.10 (`:16`; the bundled Playwright stays 1.61.0, so the browser cache is unchanged), Maven 3.10.0 (`maven-wrapper.properties:3`, no checksum line).
  - **New property:** `dependency-plugin.version` 3.11.0 (`:10`), used at `:299`.
  - **Why pinning mattered:** the Maven 3.10.0 super-POM no longer has `pluginManagement`, so unpinned plugins would float to their latest versions. Only the `properties` goal is used (the mockito `-javaagent`), and it still resolves.
  - **Builds:** both test-compiles and `package -DskipTests -Pollama` (Quinoa frontend built) pass, with the same warning set as on 3.9.16. No doc changes were needed.
  - **Watch in task 06:** Maven 3.10 switched to a breadth-first classpath order. If tests show classpath-order oddities, `-Daether.system.dependencyVisitor=preOrder` restores the old order.
- **Task 04 (images/runtime/deploy):**
  - **Bumps:**
    - otel-lgtm 0.35.0 (`dependencies.yml:161`). No manifest change: ports, `/data`, provisioning paths and no `USER` are all unchanged.
    - Node 24.21.0 / npm 11.21.0 (`application.yml:203-204`). Quinoa actually used them.
    - cert-manager v1.21.2 (commented out, `deploy-to-openshift.sh:12`).
    - clickhouse-operator 0.0.8 (`:25`).
    - Langfuse chart pinned `--version 2.1.3` (`:48-50`).
    - PlantUML 1.2026.8 (`docs/render-diagrams.sh:12`).
  - **PlantUML fallout:**
    - Added `-DPLANTUML_LIMIT_SIZE=8192` to fix a silent 4096 px crop on the architecture PNG.
    - The `ParticipantPadding` skinparam moved to a `<style>` block in `continuous-scoring-sequence.puml` (otherwise a deprecation banner gets baked into the PNG).
    - All 3 PNGs were re-rendered and checked visually: OK.
  - **Docs:** `src/main/webui/README.md` (Node/npm example).
  - **Verification:**
    - The ollama `package` and the default test-compile pass.
    - `kubectl` dry-run couldn't run (stale cluster context); `yq` parsed the manifest instead.
    - `bash -n` / `zsh -n` pass.
    - `helm template` Langfuse 2.1.3 with our values renders, given `--api-versions clickhouse.com/v1alpha1/ClickHouseCluster`.
  - **Manual cluster caveats, for the user:**
    1. If the deployed Langfuse release is still on chart v1, `helm upgrade` to 2.1.3 is blocked by the chart's v1 guard, and the move needs `examples/upgrade-v1-to-v2`.
    2. The clickhouse-operator 0.0.8 CRDs may be too large for a Helm-applied update. Fallback: apply `clickhouse-operator-crds.yaml` with `--server-side --force-conflicts`. Its chart values were renamed `enable` → `enabled`; we don't override any.
    3. otel-lgtm 0.11 → 0.35 on the existing `/data` PVC is untested. 0.35 also writes `/etc/lgtm/mcp.json` after ready; on a non-root UID this should only log an error (inferred).
  - **Not pinned:** the LGTM dev service image (Quarkus default `grafana/otel-lgtm:0.24.0`).
- **Task 05 (docs):**
  - Repo-wide sweep: none of the old version strings remain in scope.
  - Fixed `CLAUDE.md:10`: Quarkus 3.39.3 → 3.40.1.
  - Fixed a pre-existing error in `README.md:77` and `CLAUDE.md:299-300`. They called LGTM "Grafana/Loki/Tempo/Mimir", but the otel-lgtm image ships Prometheus, not Mimir (`docker/` at v0.24.0 and v0.35.0). The `.puml` already said Prometheus.
  - No deployment doc exists, so the cluster-upgrade caveats from task 04 live only here and in the final hand-off.
  - Javadoc: nothing version-specific.
- **Task 06 (verification):**
  - **Fresh review:** 0 BLOCKER, 0 MAJOR. All 12 inventory rows were applied exactly, and the keep/skip items were untouched. Two findings, both fixed:
    - MINOR: `ParticipantPadding` in the `<style>` block was a silent no-op in 1.2026.8. It was replaced with `sequenceDiagram { participant { Margin 0 20 } }`, taken from PlantUML commit `d79fb00`. `continuous-scoring-sequence.png` is now 2723×2182, wider spacing, no banner.
    - NIT: `CLAUDE.md:234` now scopes the explicit temperature/top-p claim to "base (non-profile)" blocks.
  - **Clean test data:** delete `easy-rag-embeddings.json` before any test run (user instruction). A stale reuse-embeddings cache from another embedding model corrupts the results.
  - **Ollama verify** (`OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`, run after deleting the cache): `Tests run: 68, Failures: 0, Errors: 2, Skipped: 1`. No vector mismatch; `DriftDetectionChatRouteExceptionHandlerTests` passes. Both errors also fail on `main`, so they aren't caused by the upgrade:
    - `LangfuseSessionScoringServiceTests.canFetchObservationsBySessionId`: llama3.2 makes a spontaneous `updateClaimStatus` tool call, producing an extra GENERATION.
    - `NotificationServiceTests.emailSendsWhenUserExists`: the guardrail "rewritten output" error.
    - No unknown-key warnings for `top-p`/`temperature`.
  - **First-attempt Quinoa flake:** `dr-surge.js` hit ENOENT on `src/main/webui/dist/index.html` once in 3 runs. Possibly a race between Quinoa moving `dist` and the surge step; Quinoa 2.9.2's PR #1144 changed build timing. Unproven; watch CI.
  - **New 1.14.1 log line, BENIGN:** "Found 2 implementations of DefaultMemoryIdProvider … ignoring WebSocketConnectionDefaultMemoryIdProvider".
    - The new `warnIfAmbiguous` in langchain4j-core 1.20.2 `ServiceHelper` logs it. Quarkus still uses all providers in its own priority order.
    - `ClaimService` (`@ChatScoped`) still gets its memory id from the per-service `ChatScopeDefaultMemoryIdProvider`, so isolation is unchanged.
    - Possible gap for later: no memory-isolation test exists, because `ClaimWebsocketChatBotTests` mocks `ClaimService`.
    - Correction: the warning names the wrong winner. `WebSocketConnectionDefaultMemoryIdProvider` (priority 0) is tried first; RequestScope (100) is the fallback.
    - Filed upstream, cross-linked: quarkiverse/quarkus-langchain4j#2910 and langchain4j/langchain4j#6574.
  - **Re-review of the fixes:** clean, one NIT (`CLAUDE.md:234` line length).

### User Hand-off (real keys, run by the user)
Delete `easy-rag-embeddings.json` (project root, gitignored) before each run and whenever switching between the OpenAI and ollama providers.
1. `rm -f easy-rag-embeddings.json && ./mvnw -B clean verify`: the default profile, real OpenAI and Langfuse-backed tests.
2. `rm -f easy-rag-embeddings.json && ./mvnw -B clean verify -Pollama`
3. `rm -f easy-rag-embeddings.json && ./mvnw quarkus:dev`: open a claim, chat, and ask to update the claim status so an email is sent.

Cluster deploy caveats (when you next run `deploy-to-openshift.sh`):
- The Langfuse chart v1 → 2.1.3 upgrade guard.
- clickhouse-operator 0.0.8 CRD size (fall back to `--server-side --force-conflicts`).
- otel-lgtm 0.11 → 0.35 on the existing `/data` PVC.

When this lands: close Dependabot PRs #209, #210 and #211.

### PR
- **PR:** https://github.com/edeandrea/non-deterministic-no-problem/pull/219, labelled `dependencies`, squash auto-merge enabled. Signed-off commits:
  - `1769aa1`, `312dbe0`, `8a9cacb`, `5cfdcb5`, `6b6e646`: the upgrade itself.
  - `150b9f9`: the Easy RAG test fix.
  - A final commit with this plan folder.
- **BLOCKED:** ruleset 3005185 requires Java 21 checks that the Java-25-only matrix never produces.
- **Root cause found (not an upgrade regression):** the real-key `UnsatisfiedLinkError` plus the WireMock `/v1/embeddings` 404 at boot.
  - `EasyRagIngestor` is byte-identical between 1.13.1 and 1.14.1, and `main` fails identically.
  - The two WireMock test profiles (`DriftDetectionChatRouteExceptionHandlerTests`, `LangfuseSessionScoringServiceTests`) send boot-time Easy RAG ingestion to WireMock before the `@BeforeEach` stubs exist. The failed boot writes no cache, so every restart re-ingests and reloads DJL's JNI library (upstream #823).
  - These tests only passed before because a leftover `easy-rag-embeddings.json` was present.
  - `-Pollama-openai` CI on `main` has been red from this since at least 2026-09-14.
- **Fix (user chose option 2), commit `150b9f9`:** `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF` in those two test profiles, plus comments, a `CLAUDE.md` Testing bullet, and a cache note in `CLAUDE.md`/`README.md`.
  - Default profile, both classes from a clean state: 2/2 pass, no cache written, no `UnsatisfiedLinkError`.
  - Under `-Pollama`, `LangfuseSessionScoringServiceTests` changed from "2 GENERATIONs" to "observations empty". The test has a hard-coded 15 s await at ~L182, so this is probably ClickHouse ingest timing.
  - The A/B comparison was aborted: about 30 orphaned dev-service containers had exhausted the podman VM. **User decision:** skip the A/B; the user checks this test under `-Pollama` themselves.
  - **Fresh review:** OFF is correct and the docs are accurate. Its findings were NOT applied (the user chose to commit as is):
    - MAJOR: under `-Pollama-openai` both `KeysTestProfile`s still ingest at boot against an Ollama that isn't running.
    - MINOR: the stub comments assume the OpenAI provider.
    - NITs: "project root" should be "working directory"; the dimension isn't needed with an empty store.
    - Pre-existing: `CLAUDE.md:330-331` says every WireMock profile redirects `session-sentiment`/`judge`, but `DriftHandlerTestProfile` doesn't.
    - Pre-existing on `main`: `NotificationServiceTests`/`ClaimsDetailPageTests` lack key stubs under `-Pollama-openai`.
  - **The user's real-key runs passed.** Committed `150b9f9` and pushed to PR #219. The PR body's Verification section is updated, and #212's Verify item is ticked.
  - **PR state:** BEHIND main (`ec1ca14`, #220) and still BLOCKED by the Java 21 required checks. Squash auto-merge is still enabled.

### Merged
- **2026-10-02:** PR #219 was rebased onto `ec1ca14` (#220) by the user and squash-merged as `2575c10` on `main`, with an admin bypass before CI finished. The user dropped the Java 21 required checks from ruleset 3005185.
- **Post-merge:** commit `c3491b8` on the local branch `rerender-design-diagrams-plantuml-1.2026.8` re-renders the three #220 design PNGs with PlantUML 1.2026.8. Dimensions changed by ≤ 14 px; no banners, no crops. It is not pushed yet.

### Follow-ups (not in this PR)
**Closed out (user, 2026-10-02):** none of the items below are being pursued. The user considers them irrelevant now. The Dependabot PRs closed themselves once #219 landed.
- ~~Ruleset 3005185: drop the `jvm-build-test-21-*` required checks~~ (done by the user).
- Close the superseded Dependabot PRs #209, #210 and #211.
- `README.md:52` says "The three PlantUML diagrams", but `docs/` now has 6 `.puml` files (3 of them in `docs/design/`).
- `-Pollama-openai` CI:
  - add `ingestion-strategy=OFF` to the two `KeysTestProfile`s (`DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests`);
  - add key stubs to `NotificationServiceTests`/`ClaimsDetailPageTests`.
- Review NITs:
  - the stub comments assume the OpenAI provider;
  - "project root" should be "working directory" for the cache file;
  - `CLAUDE.md:330-331` claims every WireMock profile redirects `session-sentiment`/`judge`.
- Suggest on upstream #823 that easy-rag declare `ai.djl.huggingface:tokenizers` parent-first.

### Version Inventory
Verified from Maven Central `maven-metadata.xml`, the Docker Hub v2 API, `skopeo list-tags` (Red Hat, Quay, GHCR), GitHub releases, the langfuse-k8s `index.yaml`, `nodejs.org/dist/index.json` and npm dist-tags.

| Item | Location | Current | Latest stable | Action | Notes |
|---|---|---|---|---|---|
| quarkus.platform (BOM + plugin) | pom.xml:20 | 3.40.1 | 3.40.1 | keep | 4.0.0.Beta1 is a pre-release |
| quarkus-langchain4j-bom | pom.xml:14 | 1.13.1 | 1.14.1 | bump | 1.14.0.CR2/CR3 are pre-releases; supersedes PR #210 |
| quarkus-langfuse | pom.xml:21 | 0.7.2 | 0.7.2 | keep | |
| quinoa (+ testing) | pom.xml:16 | 2.9.0 | 2.9.2 | bump | supersedes PR #209 |
| quarkus-playwright | pom.xml:15 | 2.3.8 | 2.3.10 | bump | |
| quarkus-wiremock (+ test) | pom.xml:17 | 1.7.0 | 1.7.0 | keep | |
| assertj-core | pom.xml:8 | 3.27.7 | 3.27.7 | keep | 4.0.0-M1 is a pre-release |
| maven-compiler-plugin | pom.xml:9 | 3.16.0 | 3.16.0 | keep | 4.0.0-beta-5 is a pre-release |
| surefire + failsafe | pom.xml:22 | 3.6.0 | 3.6.0 | keep | |
| os-maven-plugin | pom.xml:231 | 1.7.1 | 1.7.1 | keep | |
| maven-dependency-plugin | pom.xml:296 | unpinned (3.7.0 via the Maven 3.9.16 super-POM) | 3.11.0 | pin (user) | the Maven 3.10.0 super-POM has no default for it |
| quarkus-mailpit (+ testing) | pom.xml:23 | 2.1.2 | 2.1.2 | skip | removed in #215 |
| Maven distribution | .mvn/wrapper/maven-wrapper.properties:3 | 3.9.16 | 3.10.0 | bump | 4.0.0-rc-7 is a pre-release; supersedes PR #211 |
| Maven wrapper | maven-wrapper.properties:1 | 3.3.4 | 3.3.4 | keep | |
| postgres | src/main/kubernetes/dependencies.yml:89 | `18` (floating) | 18.6 | keep `18` (user) | 19 betas are pre-releases |
| grafana/otel-lgtm | dependencies.yml:161 | 0.11.0 | 0.35.0 | bump | big 0.x jump |
| axllent/mailpit | dependencies.yml:209 | v1.24.2 | v1.31.3 | skip | removed in #215 |
| ubi10/openjdk-25 | application.yml:361 | 1.24 | 1.24 | keep | |
| ubi10/openjdk-25-runtime | Dockerfile.jvm:78 | 1.24 | 1.24 | keep | |
| ubi10-quarkus-micro-image | Dockerfile.native:22 | 2.0 | 2.0 | keep | |
| Quinoa node-version | application.yml:195 | 24.15.0 | 24.21.0 (LTS) | bump | 26.x is Current, not LTS |
| Quinoa npm-version | application.yml:196 | 11.12.1 | 11.21.0 | bump | stays on 11.x, the LTS line (user); npm 12 is a new major |
| actions/checkout | simple-build-test.yml:43 | v7 | v7.0.1 | keep | the major tag floats |
| actions/setup-java | simple-build-test.yml:46 | v6 | v6.0.1 | keep | the major tag floats; java 25 |
| dependabot/fetch-metadata | dependabot-automerge.yml:19 | v3 | v3.1.0 | keep | the major tag floats |
| clickhouse-operator-helm | deploy-to-openshift.sh:25 | 0.0.5 | 0.0.8 | bump | 0.0.9-N tags are dev builds |
| cert-manager (commented out) | deploy-to-openshift.sh:12 | v1.20.2 | v1.21.2 | bump (user) | inactive code |
| langfuse helm chart | deploy-to-openshift.sh:48 | unpinned | 2.1.3 | pin (user) | |
| PlantUML jar | docs/render-diagrams.sh:12 | 1.2026.0 | 1.2026.8 | bump (user) | added to scope by the user |
| npm packages | src/main/webui/package.json | — | — | skip | out of scope |

**quarkus-langchain4j 1.14.1 details:**
- **langchain4j:** core 1.20.2, up from 1.19.0; beta artifacts at 1.20.2-beta30.
- **Quarkus baseline:** 3.33.3.1. The app runs 3.40.1; that mismatch already existed with 1.13.1. The 3.40.1 platform certifies ql4j 1.13.3, but the app doesn't import the platform ql4j BOM, so there's no conflict.
- **BOM:** still manages openai, ollama, chat-scopes-websocket, easy-rag, testing-evaluation-core/-ai-judge/-semantic-similarity and agentic.
- **quarkus-langfuse 0.7.2:** baselines Quarkus 3.33.2 / ql4j 1.13.1, with `quarkus-langchain4j-core` optional. Task 02 confirmed convergence: one version per artifact.

`.github/dependabot.yml` covers only `maven` and `github-actions`. It doesn't cover docker or npm.
