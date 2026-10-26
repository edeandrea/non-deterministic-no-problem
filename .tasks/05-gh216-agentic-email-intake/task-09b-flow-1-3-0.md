# Task 09b: Adopt quarkus-flow 1.3.0, Delete the Agentic-Run Workaround and Bump the Container Images

**Type:** Dependency upgrade

**Issue:** [#257](https://github.com/edeandrea/non-deterministic-no-problem/issues/257)

> **Branch and PR (user decision, 2026-10-26):** branch `gh216/09b-flow-1.3.0` off `gh216-email-intake`, its own PR
> into the feature branch ending `Part of #216`, squash-merged like the other tasks. It lands **after task 09 and
> before task 10**, so task 10's diff holds only task 10.

## Goal

The app runs on quarkus-flow **1.3.0**, whose release notes include
[#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095) ("End generated agentic workflows when one of their
agents throws") — the upstream fix for
[#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096), the leak this repo has carried a workaround for
since 2026-10-19. The deprecated workaround in `org.parasol.intake.agent` is deleted and replaced by upstream, its
regression test is kept as a guard, and the three outstanding container-image bumps land with it.

*Added 2026-10-26, when 1.3.0 (released 2026-10-23) was found during the dependency survey. Task 08's Task Plan entry
recorded the leak on 2026-10-19: "found a quarkus-flow 1.2.0 leak when an agent throws (workaround in
`org.parasol.intake.agent`, upstream issue quarkiverse/quarkus-flow#1096, PR #1095)". This task is its exit.*

## What to Do

### quarkus-flow 1.3.0

- **Check first — the full 1.2.0 → 1.3.0 delta, not just #1095.** 1.3.0 carries changes that can bite:
  - **[#1077](https://github.com/quarkiverse/quarkus-flow/pull/1077) turns on workflow input schema validation by
    default** (`quarkus.flow.validation.input.enabled`, via `serverlessworkflow-impl-validation`). Confirm
    `ClaimIntakeFlow`'s `IntakeRun` input still passes, and that the generated agentic workflows do too. Prefer fixing
    the input over disabling the flag.
  - **[#1086](https://github.com/quarkiverse/quarkus-flow/pull/1086) consolidates the `quarkus.flow` config roots** per
    module/phase. Verified low-risk on 2026-10-26: a grep over `src/` finds **no `quarkus.flow.*` property set
    anywhere** (`application.yml` mentions Flow only in a comment about `application_id`). Re-run that grep rather than
    assuming it.
  - **[#1106](https://github.com/quarkiverse/quarkus-flow/pull/1106) / [#1105](https://github.com/quarkiverse/quarkus-flow/pull/1105)**
    change JQ expression-factory priority and native reflection registration;
    **[#1079](https://github.com/quarkiverse/quarkus-flow/pull/1079)**
    ([#1004](https://github.com/quarkiverse/quarkus-flow/issues/1004)) adds a synchronous runner interface;
    **[#1108](https://github.com/quarkiverse/quarkus-flow/pull/1108)**
    ([#1107](https://github.com/quarkiverse/quarkus-flow/issues/1107)) makes member lease renewal conditional on
    ownership. None is obviously ours — confirm via the suite.
  - **Effective-POM diff before vs after**, as task 06b did: which managed versions change, what is new, and that **no
    `io.quarkus` version moves**. Expect cloudevents 5.0.0 → 5.1.0 and the new `quarkus-flow-db-migration` modules
    appearing as managed entries (we don't depend on them).
  - **Re-check the other standing watch items** while here, per Caveats:
    [quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056) (task 11's agentic conversation
    adapter), [quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354) /
    [#56805](https://github.com/quarkusio/quarkus/pull/56805) (task 10b's `ThreadContextProvider` guard), and
    [quarkus-langchain4j#2950](https://github.com/quarkiverse/quarkus-langchain4j/issues/2950) /
    [#2951](https://github.com/quarkiverse/quarkus-langchain4j/pull/2951) (`ClaimsMailboxAgent`'s
    `invocationParameters`).
  - **Re-sweep Maven at implementation time.** Every `pom.xml` property was at its latest stable release on 2026-10-26
    with **zero open PRs** (`quarkus.flow.version` was the one stale entry, and it is this task), but Dependabot runs
    daily — fold in whatever it has opened by then rather than fighting it afterwards. Platform 4.0.0.Beta1 and
    quarkus-langchain4j 2.0.0.Beta1 are betas, not candidates.
- **`pom.xml`:** `<quarkus.flow.version>` (line 16) 1.2.0 → 1.3.0, and **rewrite the comment above the override**
  (lines ~43-49). It currently justifies the override with #1056/#1057/#1058 and lists 1.2.0's dependency deltas; it
  must name **#1095** as a reason too, give 1.3.0's deltas, and keep the removal condition (drop the override once a
  platform release pins Flow ≥ 1.3.0). The four Flow dependencies stay versionless, and the override stays positioned
  after `quarkus-bom` and the quarkus-langchain4j BOM and before the platform's `quarkus-flow-bom`.
  `quarkus.langchain4j.version` is already **1.14.2**, which is exactly what Flow 1.3.0's parent aligns to, so the
  upgrade needs no version reconciliation.
- **Delete the workaround** — all three pieces, each `@Deprecated` with this removal condition in its Javadoc:
  - `src/main/java/org/parasol/intake/agent/AgenticWorkflowRuns.java`
  - `src/main/java/org/parasol/intake/agent/FailedAgenticSystemListener.java`
  - `ClaimsMailboxAgent.endFlowRunsOnFailure()` (`src/main/java/org/parasol/intake/agent/ClaimsMailboxAgent.java:77-89`)
    with its now-unused `AgentListenerSupplier` / `AgentListener` imports
- **Keep the test as an upstream regression guard.**
  `src/test/java/org/parasol/intake/agent/FailedAgenticSystemListenerTests.java` asserts exactly the behavior #1095 now
  owns — a failed root invocation leaves no agentic Flow run and no thread blocked in `AgenticFlow.executeAgent`. It
  should **pass unchanged against 1.3.0 with the workaround deleted**, which is the cleanest possible proof the fix
  works. Rename it to drop the deleted class from its name (e.g. `FailedAgenticSystemTests`) and rewrite its Javadoc to
  credit upstream #1095 instead of `AgenticWorkflowRuns`. It keeps `IntakeTestProfile`, so no new application boot.
- **Re-check the known remaining leak.** CLAUDE.md records that
  `ClaimExtractionWorkflowTests.malformedJsonThatIsNeverFixedStillFailsTheRun` still leaks one run, because it calls the
  extraction workflow directly, without the root's listener. Upstream's fix lives in `FlowAgentContextListener`, not in
  a root-only listener, so **this leak may now be fixed too** — verify, and if so say so in CLAUDE.md rather than
  carrying the caveat forward.

### Container images

Three bumps, each in **two files that must change together** (CLAUDE.md: "The mail stack mirrors
`compose-devservices.yml`: same images and tags, same environment variables. Change both together."):

| Image | From → to | Locations |
|---|---|---|
| `greenmail/standalone` | 2.1.14 → **2.1.15** | `compose-devservices.yml:12`, `src/main/kubernetes/dependencies.yml:236` |
| `roundcube/roundcubemail` | 1.7.4-apache-nonroot → **1.7.5-apache-nonroot** | `compose-devservices.yml:24`, `src/main/kubernetes/dependencies.yml:302` |
| `grafana/otel-lgtm` | 0.35.0 → **0.38.1** | `src/main/kubernetes/dependencies.yml:158` (OpenShift only) |

- **Leave `postgres:18` alone** (`dependencies.yml:83`) — a floating major tag, currently resolving to 18.6. That is the
  intended behavior, not drift.
- **Leave the Helm chart pins alone** — clickhouse-operator `0.0.8` is pinned deliberately (CLAUDE.md:
  "Dirty-migration crash loops are **not** an operator version problem; don't go bumping it"), and the Langfuse chart
  `2.1.6` carries the `failureThreshold: 60` probe override that must not regress.
- **The otel-lgtm bump is the riskiest item and is OpenShift-only.** It crosses three minors, and dev mode is unaffected
  (the Quarkus LGTM Dev Service pins its own, older image). Two existing workarounds in that Deployment must be
  re-verified against 0.38.1:
  - `GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false` — it is a version-agnostic flag, not a set of version pins, so it should
    carry forward. Still confirm the four datasources appear in Explore's picker, and diagnose via `/api/plugins` and
    `/api/frontend/settings` (**not** `/api/datasources`, which lists them either way) if they don't. Set
    `ENABLE_LOGS_GRAFANA=true` to read Grafana's own log while checking.
  - The `/var/tempo` `emptyDir` for Tempo 3.x's shutdown marker — confirm a Tempo version change in 0.38.1 hasn't moved
    or removed it.
  - If 0.38.1 misbehaves on the cluster, **land the two mail-image bumps and hold LGTM back** rather than blocking the
    task; note it in the Outcome.
- **Verify the mail images in dev mode**, not just by building: `./mvnw quarkus:dev` starts the stack, so check
  Roundcube still logs in on http://localhost:8000 and the `parasol_identities` plugin still names the seeded claimants
  (the 1.7.5 image must still run the `entrypoint-tasks/post-setup/` hook that symlinks it).

### Everything else

- **Run the full CI suite** (`clean verify`, with CI's exact flags) under `-Pollama`, `-Pollama-openai` and default.
- **Update every affected document in the same task:**
  - **`CLAUDE.md`** — the `org.parasol.intake.agent` row of the package table (line 46) drops the workaround clause; the
    intake-agent-tests paragraph (line 732) uses the renamed test; the **Agentic gotchas** bullet (lines ~765-775) is
    rewritten from "here is the workaround" to "fixed upstream in quarkus-flow 1.3.0 (#1095)", keeping enough of the
    failure description to explain why the regression test exists, and resolving the `ClaimExtractionWorkflowTests` leak
    sentence per the check above. Also correct the stale **quarkus-langchain4j 1.14.1 → 1.14.2** mentions, and the
    otel-lgtm version in the OpenShift section if LGTM lands. `AGENTS.md` just points at `CLAUDE.md`, so it needs
    nothing.
  - **`PLAN.md`** — Execution Step 2a, Caveats, and this task's entry.
  - **`task-08-intake-processor.md`** (lines ~504, ~641-654, ~667) describes the workaround in its Outcome. Per the
    task-06b precedent, merged tasks **keep their Outcomes** and get a dated note instead ("superseded by task 09b,
    2026-10-26"); don't rewrite the history.
  - **`task-11-observability.md:194`** and **`task-13-demo-and-docs.md:69-71`** — both are unstarted tasks instructing
    the implementer to account for the workaround. Rewrite those instructions, since by then it won't exist.
  - **`task-14-verification.md`** — check for workaround references while there.
  - **`README.md`** — checked 2026-10-26: it names none of the three image tags. Re-check.

## Key Points

- **The reflection in `AgenticWorkflowRuns` is the whole reason to do this promptly.** It reads two private fields and a
  private record accessor from `FlowPlanner` (`currentExchanges`, `agentExchangeQueue`). Any Flow upgrade can silently
  degrade it to the `cancel()`-only fallback, which deletes the rows but leaves the worker thread blocked forever
  (`join()` ignores interrupts).
- **#1095 is the user's own PR**, so the upstream behavior is known to match what the workaround did — it does the same
  thing from `FlowAgentContextListener` instead of from a root-only `AgentListener`.
- **Don't set `quarkus.flow.persistence.scan-interval`** (task 06b's standing decision: a restart wipes the database
  anyway, user decision 2026-10-07).
- **Flow 1.3.0's new `quarkus-flow-db-migration` extensions are not ours** — don't adopt them here.
- **npm is out of scope** (user decision, 2026-10-26). The frontend is badly stale (React 18→19, PatternFly 5→6,
  eslint 8→10, jest 29→30, react-router-dom 5→7) and uncovered by Dependabot, but that is a frontend modernization in
  its own right and would break the Jest snapshot and likely the Playwright tests. `.github/dependabot.yml` is **not**
  changed.

## Done When

- [x] `pom.xml` is on Flow 1.3.0 with a rewritten comment giving the reason (including #1095), the changed versions and
  the removal condition; `dependency:tree` shows every Flow module on 1.3.0.
- [x] The effective-POM diff is recorded in the Outcome, and no `io.quarkus` version moved.
- [x] `AgenticWorkflowRuns`, `FailedAgenticSystemListener` and `ClaimsMailboxAgent.endFlowRunsOnFailure()` are gone, with
  their unused imports; no `@Deprecated` remains in `org.parasol.intake.agent`.
- [x] The renamed regression test passes against 1.3.0 with the workaround deleted.
- [x] The #1077 input-validation default is **not** harmless to the generated agentic workflows — addressed by turning
  `quarkus.flow.validation.input.enabled` off app-wide (`ClaimIntakeFlow`'s own input is unaffected either way); filed
  upstream as [quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111). See Outcome.
- [x] GreenMail and Roundcube are bumped **in both files**, and dev mode still logs into Roundcube with the claimant
  identities named.
- [x] otel-lgtm is on 0.38.1 with the datasource pickers verified on OpenShift — or held back, with the reason
  recorded. **Held back**: no OpenShift cluster available in this environment to verify against; see Outcome.
- [x] `PLAN.md`, `CLAUDE.md` and tasks 08, 11, 13 and 14 describe 1.3.0 and no workaround.
- [x] `clean verify` passes under `-Pollama`, `-Pollama-openai` and default, apart from failures that also fail on the
  base commit. `-Pollama`: 432/432, 0 failures. `-Pollama-openai`: 432/432, 0 failures (needs all three of CI's stub
  keys, not just `OPENAI_API_KEY` — see Outcome). Default profile: no real keys on this machine, so `clean test-compile`
  was run here (BUILD SUCCESS); the user ran the full `clean verify` with real keys separately and confirmed it passed.

## Outcome

**Branch:** `gh216/09b-flow-1.3.0` off `gh216-email-intake`.

**quarkus-flow 1.2.0 → 1.3.0.** `pom.xml`'s `quarkus.flow.version` bumped, override comment rewritten to name #1095
and 1.3.0's deltas. Effective-POM diff (`help:effective-pom` before/after): every `io.quarkiverse.flow:*` managed
version 1.2.0 → 1.3.0, cloudevents 5.0.0 → 5.1.0, two new unused `quarkus-flow-db-migration*`/`-deployment` managed
entries, and new `quarkus-flow-db-migration-quartz`/`-deployment` entries — nothing else changed, and no `io.quarkus`
version moved. `dependency:tree -Dincludes=io.quarkiverse.flow` confirms every Flow module resolves to 1.3.0.

**Workaround deleted.** `AgenticWorkflowRuns.java` and `FailedAgenticSystemListener.java` removed;
`ClaimsMailboxAgent.endFlowRunsOnFailure()` and its now-unused `AgentListenerSupplier`/`AgentListener` imports removed.
No `@Deprecated` remains in `org.parasol.intake.agent`. The regression test, renamed
`FailedAgenticSystemListenerTests` → `FailedAgenticSystemTests` with a rewritten Javadoc crediting upstream #1095,
passes unchanged (2/2) against 1.3.0 with the workaround gone. The known remaining leak
(`ClaimExtractionWorkflowTests.malformedJsonThatIsNeverFixedStillFailsTheRun`, which calls the extraction workflow
directly, without the root's listener) was checked with a throwaway diagnostic test (added, verified green, then
reverted — no diff left behind) and confirmed fixed too: `FlowAgentContextListener` sees every agent's failure, not
just the root's.

**A new regression: #1077's input-schema validation breaks every generated agentic workflow.** Running the full
`org.parasol.intake.**` suite surfaced `ClaimsMailboxAgentTests`, `ClaimExtractionWorkflowTests` and
`ClaimIntakeDispatcherTests` (downstream, via 60s `IntakeScenario` timeouts) failing with
`WorkflowError[... Schema validation errors, detail=required property 'category' not found ...]`.
`ClaimIntakeFlow`'s own `IntakeRun` input was unaffected — every `ClaimIntake*Tests` class passed.

Decompiled `quarkus-flow-langchain4j-deployment-1.3.0.jar`'s `JandexMethodInputJsonSchema#buildPojoSchema`: every
non-static field of every record type reachable from the agent topology is added to the schema's `required` array
unconditionally — no check for `java.util.Optional`, no `@Schema(nullable = true)`/`@JsonInclude` recognition,
nothing. Traced the validation path (`AgenticAwareWorkflowModel.convert(JsonNode.class)` →
`JsonUtils.fromValue(agenticScope.state())`) to confirm the document being validated is the live `AgenticScope` state
map, which simply has no entry for a variable nothing has written yet — not a `null`-valued entry, an absent key. Two
independent reasons this can't be fixed by changing our input:
1. The "missing" fields (`category`, `incidentDate`, `location`, `policyNumber`, …) are `ClaimExtractionWorkflow`'s
   own **outputs**, not available until a later step in the same run computes them. There's no legitimate value a
   caller could supply upfront.
2. `invocationParameters` is flagged missing too, even though every caller supplies it
   (`ClaimStatusTools.scopedTo(...)`) — `langchain4j-agentic` deliberately excludes `InvocationParameters`-typed
   parameters from `AgenticScope.state()` (execution context, never a prompt/data variable; the same exclusion
   `quarkus-langchain4j`'s own `AgenticProcessor#validateAgenticParameterTypes` makes for `AgenticScope`/`@MemoryId`
   parameters), and `JandexMethodInputJsonSchema` has no equivalent awareness.

Confirmed via the config-doc JSON that `quarkus.flow.validation.input.enabled` is a single global
`RUN_TIME`/`FlowRuntimeConfig` boolean with no per-workflow-id scoping, so there is no way to disable it only for the
generated agentic workflows while keeping it for `ClaimIntakeFlow`. Turned it off app-wide in `application.yml` (with
a comment explaining why), and removed the now-redundant per-test override that had briefly been added to
`IntakeTestProfile` while diagnosing this — the generated agentic workflows run against the real mailbox in
production too (`AgentStep` → `ClaimsMailboxAgent`), so a test-profile-only fix would have left production intake
broken on every email. Filed upstream as
[quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111) with the bytecode evidence,
the `InvocationParameters` case, and three suggested fixes; re-enable the flag once it's addressed there.

**Container images.** GreenMail `docker.io/greenmail/standalone:2.1.14` → `2.1.15` and Roundcube
`docker.io/roundcube/roundcubemail:1.7.4-apache-nonroot` → `1.7.5-apache-nonroot`, in both `compose-devservices.yml`
and `src/main/kubernetes/dependencies.yml`. Both tags confirmed to exist on Docker Hub and pulled successfully with
Podman before editing. Verified in dev mode (`./mvnw -Pollama quarkus:dev`): the app boots on Flow 1.3.0 (workflow
definitions warm up cleanly), GreenMail 2.1.15 and Roundcube 1.7.5 both start, the `parasol_identities` plugin's
`entrypoint-tasks/post-setup/10-parasol-identities-plugin.sh` hook still runs and symlinks the plugin
(`/var/www/html/plugins/parasol_identities -> /usr/local/share/parasol/plugins/parasol_identities`), and a real
Roundcube login (`marty.mcfly@email.com`) returns `302 Found` to `?_task=mail`. otel-lgtm's 0.35.0 → 0.38.1 bump is
OpenShift-only and not yet verified on a cluster — pending.

**Fix confirmed.** Re-ran `./mvnw test -Dtest='org.parasol.intake.**'` with the `application.yml` override in place:
255 tests, 0 failures, 1 error. `ClaimsMailboxAgentTests` (10/10), `ClaimExtractionWorkflowTests` (10/10) and
`ClaimIntakeDispatcherTests` (5/5) — the three classes the #1077 regression broke — all pass cleanly. The one error
(`IntakeJsonRoundTripTests.aMinimalIntakeStateRoundTrips`, with 4 more skipped in the same class) is pre-existing and
unrelated: that class has no `@TestProfile`, so it boots under the real default profile, which needs
`OPENAI_API_KEY`/`COHERE_API_KEY` this machine doesn't have (CLAUDE.md: "this machine has no real OpenAI/Cohere keys,
so the default profile only compiles here").

**`clean verify -Pollama`: 432 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS** (8:25 min).

**`clean verify -Pollama-openai`: 432 tests, 0 failures, 0 errors, 2 skipped, BUILD SUCCESS.** Needed all three of CI's
stub keys (`OPENAI_API_KEY`, `COHERE_API_KEY`, `GEMINI_API_KEY` = `change-me`), not just `OPENAI_API_KEY` as
CLAUDE.md's Environment variables section currently claims — a pre-existing doc inaccuracy, out of scope for this
task; flagged to the user separately rather than fixed here. `.github/workflows/simple-build-test.yml` sets all
three, which is the actually-correct recipe.

**Default profile: `clean test-compile` — BUILD SUCCESS** (this machine has no real OpenAI/Cohere keys, so the default
profile only compiles here, per CLAUDE.md). The user then ran the full `clean verify` under the default profile with
real keys separately and confirmed it passed.

**otel-lgtm 0.35.0 → 0.38.1: held back, not bumped.** This environment has no OpenShift cluster to verify the
datasource pickers against, and the task calls that risk assessment out explicitly ("the riskiest item ... OpenShift
only ... if 0.38.1 misbehaves on the cluster, land the two mail-image bumps and hold LGTM back"). Landing the two
mail-image bumps without the OpenShift verification the task requires for otel-lgtm would be inconsistent with its
own risk gate, so `src/main/kubernetes/dependencies.yml:158` stays on `grafana/otel-lgtm:0.35.0`. Needs the user (or a
session with real OpenShift access) to run `deploy-to-openshift.sh` with the bump and check Explore's datasource
picker, `GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false` still holding, and the `/var/tempo` emptyDir, before landing it.
