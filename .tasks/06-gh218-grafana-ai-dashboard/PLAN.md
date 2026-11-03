# Issue 6: Retire the AI Grafana Dashboard — Task Execution Plan

## Your Mission

Delete `src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` and say so in the docs. The dashboard charts
metrics that no longer exist (`parasol_llm_*`, `scorer_*`, `interaction_scored_latest_score`, `mp_graphql_seconds_*`),
and everything else in it duplicates the four dashboards the LGTM dev service already provisions. Nothing replaces it
here: the one custom dashboard this app will carry — the intake meters plus an AI row — is built and provisioned in
#277.

**GitHub issue:** [#218](https://github.com/edeandrea/non-deterministic-no-problem/issues/218) (sub-issue of #217,
blocked by #212 and #216, both closed; blocks #277). Body in
`.tasks/github-issues/issue-06-grafana-dashboard.md`, superseded by the decisions below.
**Plan File:** `.tasks/06-gh218-grafana-ai-dashboard/PLAN.md`
**Tasks Directory:** `.tasks/06-gh218-grafana-ai-dashboard/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/06-gh218-grafana-ai-dashboard/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- No Java changes are expected. If any slip in, make sure it compiles: `./mvnw -B clean test-compile -Pollama` and
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

- [x] [task-01-delete-dashboard.md](task-01-delete-dashboard.md): Delete the dashboard and check dev mode's built-in dashboards still cover it
- [x] [task-02-docs-and-verification.md](task-02-docs-and-verification.md): Documentation, the handoff to #277, verification and the PR (GitHub comments and PR still pending user confirmation)

> **Branch and PR (user decision, 2026-11-03):** both tasks on one branch, `gh218/grafana-dashboard`, off **`main`**,
> and one PR **into `main`** ending `Closes #218` in its body. The title must carry no closing keyword — suggested:
> *Delete the stale AI Grafana dashboard*.

---

## Shared Context

### Overview

`src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` is 4,832 lines / 125,216 bytes, `uid` `few3cxgl3sf`,
title "AI Dashboard", schema version 41, saved by Grafana 11.6. Its 45 panels break down as:

- **7 AI panels — all dead.** `Total AI Cost`, `Number of AI invocations`, `Real-time scoring`,
  `Avg Guardrail executions / interaction`, `LLM interaction failures`, `Token Counts`, `Number of tool invocations`.
  They query `parasol_llm_*`, `scorer_*` and `interaction_scored_latest_score`, whose emitters were removed with the old
  scorer architecture (`5d31ba1`, `f782cae`), and hard-code pre-repackaging interface names
  (`org.parasol.ai.GenerateEmailService`, `org.parasol.ai.ClaimService`, `org.parasol.ai.guardrail.PolitenessService`).
  None of those strings appear anywhere in `src/`. `Total AI Cost` also hard-codes OpenAI prices for
  `gpt-5-mini-2025-08-07` and `gpt-4o-mini-2024-07-18`.
- **4 GraphQL panels — all dead.** `mp_graphql_seconds_*`, in a collapsed row; no GraphQL extension is on the classpath.
- **34 JVM / HTTP / Agroal panels — redundant.** `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`, `jvm_buffer_*`,
  `jvm_classes_loaded`, `process_*`, `system_*`, `http_server_requests_milliseconds_*`, `agroal_*`. The LGTM dev service
  already provisions four built-in dashboards covering the same ground (see *How dashboards load*).

The file contains **zero** occurrences of `claim.intake`, `gen_ai`, `langchain4j_aiservices` or `quarkus_flow` — nothing
the app emits today. It also carries cosmetic debt: the row title typo "HTTP Edpoints", two pairs of identically titled
panels ("Direct Buffers", "Mapped Buffers"), and `gridPos.y` gaps left by panels deleted long ago.

### How dashboards load (verified 2026-11-03)

- **Dev:** `io.quarkus.observability.testcontainers.GrafanaUtils` (`quarkus-observability-testcontainers` 3.40.1.1)
  scans the runtime classpath for `META-INF/grafana/grafana-dashboard-*.json` — the prefix and the `.json` suffix are
  both mandatory — and appends one provider entry per file to the built-in `DASHBOARDS_CONFIG`, naming the dashboard
  from the filename (so `grafana-dashboard-ai.json` shows up as "Ai"). `LgtmContainer` then copies each file to
  `/otel-lgtm/<filename>` and writes the merged YAML to
  `/otel-lgtm/grafana/conf/provisioning/dashboards/grafana-dashboards.yaml`. It also copies the four built-in Quarkus
  dashboards ("Quarkus Micrometer OpenTelemetry", "Quarkus Micrometer OTLP registry", "Quarkus Micrometer Prometheus
  registry", "Quarkus OpenTelemetry Logging") and writes `/otel-lgtm/prometheus.yaml`, whose scrape config supplies the
  `job` label the deleted dashboard's `$instance` variable read.
- **Cluster: nothing provisions dashboards.** `quarkus-observability-devservices-lgtm` is `provided` scope
  (`pom.xml:181-185`), so `META-INF/grafana/` is a dev-mode-only mechanism, and the `grafana-lgtm` Deployment
  (`src/main/kubernetes/dependencies.yml:136-199`) mounts only `/data` and `/var/tempo`.

### Key Decisions

- **#218 is a pure deletion** (user decision, 2026-11-03): delete the file and update the docs. No replacement
  dashboard is built here. This answers the maintainer's own question on the issue ("Does it make sense to just delete
  the dashboard completely?").
- **Cluster provisioning moves to #277** (user decision, 2026-11-03): with nothing left to provision, wiring a ConfigMap
  in #218 would ship plumbing for zero dashboards. #277 builds the dashboard and provisions it in dev **and** on the
  cluster in one go. #277's body says #218 owns provisioning, so that has to be corrected there (task 02).
- **The AI panels are folded into #277** (user decision, 2026-11-03): #277's dashboard gets an AI row (token usage,
  model latency, estimated cost, AI service calls and errors, guardrail outcomes) next to the intake meters, so the app
  ends up with **one** custom dashboard rather than two. #277's body already lists those panels.
- **`quarkus-observability-devservices-lgtm` stays**: it is what provisions the four built-in dashboards that now carry
  the JVM, HTTP and Agroal view.

### Findings handed to #277

Recorded here so #277 does not have to re-derive them.

**Provisioning recipe for the cluster.** Follow the `roundcube-identities` pattern at `deploy-to-openshift.sh:83-88`:
`oc create configmap <name> --from-file=<key>=<repo-relative path> … --dry-run=client -o yaml | oc apply -f -`, run
from the repo root **before** `oc apply -f src/main/kubernetes/dependencies.yml` (line 89). Mount the dashboard JSON
into `grafana-lgtm` at `/otel-lgtm/grafana/conf/provisioning/dashboards/custom/` with a provider YAML at
`/otel-lgtm/grafana/conf/provisioning/dashboards/custom.yaml` (`apiVersion: 1`, `type: file`,
`options.path: /otel-lgtm/grafana/conf/provisioning/dashboards/custom`, `foldersFromFilesStructure: false`), per the
`grafana/docker-otel-lgtm` README. Note `/otel-lgtm/...` is not under the `/data` PVC mount, so the mount is independent
of it. A 125 KB dashboard is well inside the ~1 MiB ConfigMap limit. `dependencies.yml` must carry a comment saying it
is incomplete without the ConfigMap, as the Roundcube block does at lines 280-281.

**Grafana version skew.** Dev pins `docker.io/grafana/otel-lgtm:0.24.0` (Grafana 12), hard-coded in
`quarkus-observability-devservices-common` 3.40.1.1 and not overridden in this repo; the cluster pins
`grafana/otel-lgtm:0.38.1` (Grafana 13) at `dependencies.yml:158`. A dashboard verified in dev is **not** thereby
verified on the cluster. `CLAUDE.md:697-698` already records the mismatch.

**Two export paths, two naming schemes.** Both `quarkus-micrometer-registry-prometheus` (`pom.xml:179`, scrape
endpoint, `_seconds` units) and `quarkus-micrometer-opentelemetry` (`pom.xml:188`, OTLP push, `_milliseconds` units)
are on the classpath, so meters arrive under two names. #277 must pick one and document which. The deleted dashboard
used the OTLP millisecond names (`http_server_requests_milliseconds_sum`, `jvm_gc_pause_milliseconds_count`, …).

**The app's own meters.** `org.parasol.intake.observability.IntakeMetrics` (`@Startup`, package-private) is the **only**
meter registrar in `src/main/java`, and there is no `@Timed`/`@Counted` anywhere in the repo. Its 23 meters:

| Meter | Type | Tags |
|---|---|---|
| `claim.intake.emails` | counter | `outcome` |
| `claim.intake.email.latency` | timer | `outcome` |
| `claim.intake.email.size` | summary, bytes | — |
| `claim.intake.attachments` | counter | `result` (`kept`/`skipped`), `reason` (`none`/`not_an_image`/`too_large`/`too_many`/`empty`) |
| `claim.intake.matcher.result` | counter | `result` |
| `claim.intake.classifications` | counter | `email_type`, `matched_claim` |
| `claim.intake.extraction.completeness` | summary (0..1) | — |
| `claim.intake.missing.items` | counter | `item` |
| `claim.intake.claim.emails` | summary | — |
| `claim.intake.claim.time.to.complete` | timer | — |
| `claim.intake.mail.client.duration` | timer | `protocol`, `operation`, `outcome` |
| `claim.intake.mailbox.lock.wait` | timer | `mode` |
| `claim.intake.replies` | counter | `template` |
| `claim.intake.failures` | counter | `stage`, `exception`, `outcome` |
| `claim.intake.claim.status.transitions` | counter | `status` |
| `claim.intake.review.decisions` | counter | `decision` |
| `claim.intake.review.overrides` | counter | `agreement` |
| `claim.intake.review.wait` | timer | — |
| `claim.intake.reviews.pending` | gauge | — |
| `claim.intake.review.oldest.pending` | gauge, seconds | — |
| `claim.intake.inbox.depth` | gauge | — |
| `claim.intake.watcher.connected` | gauge | — |
| `claim.intake.watcher.reconnects` | counter | — |

Tag values come from small enums in the same package, lower-cased: `EmailOutcome` (`new_claim`, `claim_update`,
`status_question`, `not_a_claim`, `no_matching_claim`, `needs_clarification`, `policy_rejected`, `skipped_auto_reply`,
`duplicate`, `failed`), `MatcherResult` (`matched`, `wrong_sender`, `no_match`, `resolved_existing`,
`resolved_new_incident`, `unsure`), `MatchedClaimKind` (`none`, `pending`, `other`), `FailureStage` (`move`, `fetch`,
`agent`, `persist`, `reply`, `unknown`), `FailureOutcome` (`fatal`, `recovered`), `ReviewAgreement` (`agreed`,
`overridden`), `MailboxLockMode` (`read`, `write`). Two that are **not** enum-derived: `exception` on `failures` is an
exception simple name, and `status` on `claim.status.transitions` is the human label — `Pending Information`,
`Pending Review`, `In Process`, spaces and capitals included.

**Library meters, confirmed by reading the jars** (so #277 charts names that exist):

- quarkus-langchain4j 1.14.2 AI services: `langchain4j.aiservices.timed` (timer) and `langchain4j.aiservices.counted`
  (counter), tagged `aiservice` (simple interface name) and `method`, plus `result` (`success`/`failure`) and
  `exception` on the counter. Every AI service is timed by default.
- quarkus-langchain4j 1.14.2 chat models: `gen_ai.client.token.usage` (counter),
  `gen_ai.client.token.usage.distribution` (summary), `gen_ai.client.operation.duration` (timer) and
  `gen_ai.client.estimated_cost` (counter). Tags: `gen_ai.operation.name=chat`, `gen_ai.token.type`
  (`input`/`output`/`cache_read`/`cache_creation`), `currency`, and from `buildTags`: `gen_ai.request.model`,
  `gen_ai.response.model`, `gen_ai.provider.name`, `ai_service.class_name`, `ai_service.method_name`, `error.type`
  (each literally `none` when unavailable, so the tag keys are stable).
- quarkus-langchain4j 1.14.2 guardrails: `guardrail.invoked` (counter) and `guardrail.timed` (timer, percentiles 0.75 /
  0.95 / 0.99), tagged `aiservice`, `operation`, `guardrail`, `guardrail.type` (`INPUT`/`OUTPUT`), `outcome`; plus
  `tool-guardrail.invoked` / `tool-guardrail.timed` — **note the hyphen** — with an extra `tool.name`.
- quarkus-flow 1.3.0 (prefix `quarkus.flow`, auto-enabled with Micrometer present, no config set in this repo):
  `workflow.started.total`, `workflow.completed.total`, `workflow.faulted.total` (extra tag `errorType`),
  `workflow.cancelled.total`, `workflow.duration`, `task.started.total`, `task.completed.total`, `task.failed.total`,
  `task.retries.total`, `task.duration`, and the gauges `instance.running`, `instance.waiting`, `instance.suspended`.
  Tags are `workflow`, `workflowVersion` and, on task meters, `task`. The `fault.tolerance.*` entries in the
  `FlowMetrics` enum are **declared but not emitted** in 1.3.0 — don't chart them.
- `quarkus-flow-opentelemetry` 1.3.0 is tracing only; it emits no meters.

### Caveats & Problems

- Deleting the file empties `src/main/resources/META-INF/grafana/`, so the directory disappears from git. #277 recreates
  it. The dev service handles an empty scan result: `findGrafanaDashboards()` simply returns nothing and the built-in
  config still applies.
- **Dead config, deliberately not touched here:** `quarkus.micrometer.export.otlp.*` (`application.yml:32-35` in
  `%drift`, `430-433` in `%test`) is the config root of `quarkus-micrometer-registry-otlp`, which is **not** on the
  classpath. The bridge actually in use, `quarkus-micrometer-opentelemetry`, has no runtime config of its own — it force
  sets `quarkus.otel.metrics.enabled` / `quarkus.otel.logs.enabled` at priority 1 and turns the OTel HTTP-server and JVM
  instrumentation off. The real off switch in those profiles is `quarkus.otel.exporter.otlp.enabled: false`, which both
  already set. **Filed as [#290](https://github.com/edeandrea/non-deterministic-no-problem/issues/290)** (2026-11-03,
  body in `.tasks/github-issues/issue-10-unrecognized-config-keys.md`), together with the other unrecognized
  configuration keys the boot reports — including a real typo,
  `quarkus.rest.jackson.optimization.enable-reflection-free-serialization` (`application.yml:242`), where the declared
  key is `…-serializers`, so that optimization has never applied. **None of it belongs in #218**, which stays a pure
  deletion.
  - **Done in #290** (2026-11-03): both dead keys deleted, the Jackson spelling fixed and kept on, plus the rest of
    that issue's scope (the Ollama block moved into `%ollama`, `aiscoring.*` renamed out of the `quarkus.` namespace).
    See `.tasks/07-gh290-unrecognized-config-keys/PLAN.md`.
- **Doc contradiction to settle in task 02:** `.tasks/github-issues/issue-06-grafana-dashboard.md:17,31` says
  "Prometheus (Mimir)" / "confirm each one in Mimir"; `CLAUDE.md:778` says the `grafana/otel-lgtm` image ships
  Prometheus, not Mimir, despite the "M". CLAUDE.md is right.
- Neither README.md nor CLAUDE.md currently documents the dashboard at all — there is no Grafana URL, dashboard name or
  port in either — so task 02 is adding a statement, not correcting one.

### Outcomes (2026-11-03)

- **Task 01.** `grafana-dashboard-ai.json` deleted via `git rm`, emptying and removing `META-INF/grafana/`. A repo-wide
  grep for `parasol_llm`, `scorer_`, `interaction_scored`, `mp_graphql`, `few3cxgl3sf`, `grafana-dashboard` and
  `AI Dashboard` found no hits outside `.tasks/` planning/tracking docs (the task plan's own files, the roadmap,
  `issue-06-grafana-dashboard.md`, `issue-08-repackage-by-domain.md`, `github-issues/README.md` and
  `doc-accuracy-review/FINDINGS.md` — all historical record, left untouched beyond the required annotation of
  `issue-06`). Dev-mode check done via the Grafana HTTP API (no browser tooling available in this session) against a
  `quarkus:dev` boot on port 8090: `GET /api/search?type=dash-db` lists only the four built-in dashboards ("Quarkus
  Micrometer OpenTelemetry", "Quarkus Micrometer OTLP registry", "Quarkus Micrometer Prometheus registry", "Quarkus
  OpenTelemetry Logging") plus a "Quarkus Flow" dashboard bundled by the `quarkus-flow` extension's own jar (unrelated
  to this issue, unaffected by the deletion) — no "AI Dashboard"/`few3cxgl3sf`. The "Quarkus Micrometer OpenTelemetry"
  dashboard's panel list confirms JVM (heap, GC, threads, classloading), HTTP endpoint and JDBC/Agroal coverage
  survives. `GET /q/metrics` after a couple of requests showed `http_server_requests_seconds_*` populated, confirming
  data actually flows into that dashboard's datasource. Dev log carried no Grafana provisioning error and no
  `Adding custom Grafana dashboard config: grafana-dashboard-ai.json` line. `./mvnw -B clean verify -Pollama` was
  green (see task 02 totals below — run twice, once truncated for a quick sanity check and once in full).
- **Task 02.** `CLAUDE.md` *Observability* and `README.md` *Observability* both updated with the no-custom-dashboard
  statement, the four built-in dashboard names, the dev-only `META-INF/grafana/grafana-dashboard-*.json` mechanism,
  and the pointer to #277. `docs/*.puml` checked — `continuous-scoring-architecture.puml:111` names Grafana
  generically, no change needed. `.tasks/claim-intake-roadmap.md` re-read and still reads correctly (it was already
  updated by the prior planning session). `issue-06-grafana-dashboard.md` got the superseded-2026-11-03 note at the
  top, body left intact below it. **Verification totals:** `./mvnw -B clean verify -Pollama` — BUILD SUCCESS, 637
  tests, 0 failures, 0 errors, 2 skipped. `./mvnw -B clean verify -Pollama-openai` — BUILD SUCCESS, 637 tests, 0
  failures, 0 errors, 3 skipped (the extra skip is `NotificationServiceTests.emailSendsWhenUserExists`, gated off this
  leg per CLAUDE.md's Testing section). **GitHub comments on #277/#218 and the PR are pending the user's review of the
  exact text before anything is posted.**

### Manual Checks (for the user)

- **Cluster:** after the next `deploy-to-openshift.sh`, Grafana still comes up and its four datasources resolve. Nothing
  in this issue touches the cluster, so this is a sanity check only.
