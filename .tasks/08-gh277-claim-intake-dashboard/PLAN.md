# Issue 8: Add Grafana Dashboards for the Email Intake and the AI Services — Task Execution Plan

## Your Mission

The app ships no custom Grafana dashboard: #218 deleted the stale `grafana-dashboard-ai.json` and handed both cluster
provisioning and the AI panels to this issue. The intake records 23 `claim.intake.*` meters (tasks 11 and 11b of #216)
that nobody can see without writing PromQL by hand.

The target is **two dashboards, provisioned in dev and on the cluster, that read the same series in both places**:

- **"Parasol Claim Intake"** — every `claim.intake.*` meter, plus a row of Flow's workflow meters for `claim-intake`.
- **"Parasol AI"** — every AI service in the app: tokens, latency, estimated cost, AI service calls and errors, and
  guardrails.

Getting there also means making dev and the cluster agree on the series (dev scrapes by accident and double counts),
on the Grafana version, and on histogram buckets that make p95 meaningful.

**GitHub issue:** [#277](https://github.com/edeandrea/non-deterministic-no-problem/issues/277) (follows #218, whose
`.tasks/06-gh218-grafana-ai-dashboard/PLAN.md` → *Findings handed to #277* holds the library meter inventory and the
first cut of the cluster provisioning recipe).
**Plan File:** `.tasks/08-gh277-claim-intake-dashboard/PLAN.md`
**Tasks Directory:** `.tasks/08-gh277-claim-intake-dashboard/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/08-gh277-claim-intake-dashboard/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- Where Java changes, make sure it compiles under both AI profiles: `./mvnw -B clean test-compile -Pollama` and
  `./mvnw -B clean test-compile -Pollama-openai`.
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

- [x] [task-01-dev-lgtm-config.md](task-01-dev-lgtm-config.md): Pin the dev LGTM image to the cluster's and turn off the dev service's accidental scrape
- [x] [task-02-metric-inventory.md](task-02-metric-inventory.md): Record the exact series names, labels and units every panel will query
- [x] [task-03-histogram-buckets.md](task-03-histogram-buckets.md): Give the intake's timers and summaries explicit buckets
- [x] [task-04-dashboards.md](task-04-dashboards.md): The two dashboards and the test that keeps the intake one complete
- [ ] [task-05-cluster-provisioning.md](task-05-cluster-provisioning.md): Provision both dashboards on OpenShift
- [x] [task-06-docs-verification-pr.md](task-06-docs-verification-pr.md): Documentation, verification and the PR

> **Branch and PR (decided 2026-11-09):** `gh277/claim-intake-dashboard` off `main`, one PR into `main`. No closing
> keyword in the title; the body ends `Closes #277`. Suggested title: *Add Grafana dashboards for the email intake and
> the AI services*.

---

## Shared Context

### Overview

Metrics leave the app one way: Micrometer → the OpenTelemetry bridge (`quarkus-micrometer-opentelemetry`) → OTLP push
to the collector inside `grafana/otel-lgtm` → Prometheus. In dev that's the LGTM dev service's OTLP port; on the
cluster it's `lgtm:4318`. **The dashboards query the OTLP-translated names**, so they work the same in both places.

Two dashboards rather than one, because they answer different questions for different people: "is the intake pipeline
healthy and are its agents routing well" versus "what do the models cost and how often do the guardrails fire". The AI
meters are app-wide, not intake-specific, and each dashboard stays small enough to read on one screen.

| | Parasol Claim Intake | Parasol AI |
|---|---|---|
| File | `src/main/resources/META-INF/grafana/grafana-dashboard-claim-intake.json` | `src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` |
| uid | `parasol-claim-intake` | `parasol-ai` |
| Meters | the 23 `claim.intake.*`, Flow's `quarkus.flow.*` for `workflow="claim-intake"` | `gen_ai.client.*`, `langchain4j.aiservices.*`, `guardrail.*`, `tool-guardrail.*` |
| Variables | — | `$ai_service` (multi, default All), `$model` |
| Links | → AI, with `var-ai_service` preset to the intake agents | → Claim Intake |

### Key Decisions (user, 2026-11-09)

- **Q1 — Explicit histogram buckets: yes.** The intake's timers and summaries get `serviceLevelObjectives(...)`
  (task 03), with tests. Without them p95 is meaningless (finding 3).
- **Q2 — The AI dashboard covers every AI service**, not just the intake agents: the chat bot, email generation,
  politeness, the judge, session sentiment and the intake agents, behind an `$ai_service` multi-select defaulting to
  All. The chat bot and email generator are the demo's other half; hiding them would be odd. The intake dashboard links
  to it **pre-filtered to the intake agents** (`var-ai_service=…` repeated per agent in the link URL).
- **Q3 — Pin dev's otel-lgtm to the cluster's version**, `docker.io/grafana/otel-lgtm:0.38.1` (Grafana 13), through
  `quarkus.observability.lgtm.image-name` at root level. Author the dashboards for Grafana 13 only. The tag then lives
  in two places (`application.yml`, `src/main/kubernetes/dependencies.yml`), documented as "change both together", like
  the mail stack's images.
- **Q4 — Two dashboards**, cross-linked both ways (table above).
- **Q5 — Branch and PR** as noted under *Task Plan*.
- **Q6 — Keep `/q/metrics`, turn the dev scrape off** with `quarkus.observability.lgtm.force-scraping: false` at root
  level. `quarkus-micrometer-registry-prometheus` stays.

### Findings (verified 2026-11-09, against `main` at `f4eeff7`)

1. **OTLP push is the only path needed.** Nothing has to scrape: the bridge pushes every meter, on the cluster and in
   dev alike.
2. **Dev also scrapes, by accident, and double counts.**
   - `quarkus-micrometer-registry-prometheus` brings `micrometer-registry-prometheus-simpleclient`, which contains
     `io.micrometer.prometheus.PrometheusMeterRegistry`.
   - That class is in the LGTM dev service's `LgtmResource.SCRAPING_REGISTRIES` (Quarkus 3.40,
     `LgtmResource.isScrapingRequired`), so the dev service adds a Prometheus scrape job for the app's `/q/metrics`.
   - Its opt-out, `catalog.hasMicrometerOtlp()`, only looks for `io.micrometer.registry.otlp.OtlpMeterRegistry`
     (`micrometer-registry-otlp`). We push through the OTel bridge instead, so the opt-out doesn't fire.
   - Result: in dev every counter arrives twice — pushed and scraped — under the same Prometheus name
     (`claim_intake_emails_total`), so `sum by (outcome)` reads double. The cluster never scrapes.
   - Nothing in the repo reads `/q/metrics`: no test (they use `SimpleMeterRegistry`), script, doc or Kubernetes
     annotation.
   - `force-scraping: false` (Q6) removes the scrape job and keeps the endpoint. Then dev and the cluster see the same
     series and no query needs a dedup selector.
3. **No intake timer or summary has buckets.** The OTel bridge (`opentelemetry-micrometer-1.5` 2.31.1,
   `setExplicitBucketsIfConfigured`) only exports explicit boundaries when the meter configures them, and otherwise
   falls back to the SDK's default millisecond boundaries (0 … 10 000 ms). So `review.wait`, `claim.time.to.complete`
   and `email.latency` (minutes to hours) all land in `+Inf`, and `extraction.completeness` (0..1) all lands in the
   first bucket.
4. **Flow bundles its own dashboard**, "Quarkus Flow" (uid `qflow`,
   `quarkus-flow-1.3.0.jar!/META-INF/grafana/grafana-dashboard-quarkus-flow.json`), which dev provisions automatically.
   It queries the **scrape** names, so it is empty on the cluster today and goes empty in dev once the scrape is off.
   Not ours to fix; the intake dashboard's Flow row replaces it for `claim-intake`. Leave it, and the four Quarkus
   built-in dashboards, alone.
5. **The Prometheus datasource uid is `prometheus`** in otel-lgtm (`grafana-datasources.yaml`). Flow's dashboard
   relies on it too.
6. **Grafana version skew:** dev runs otel-lgtm 0.24.0 (Grafana 12, the Quarkus 3.40 default), the cluster 0.38.1
   (Grafana 13). Q3 removes it. 0.38.1 still reads
   `/otel-lgtm/grafana/conf/provisioning/dashboards/grafana-dashboards.yaml`, the file the dev service overwrites, so
   dev provisioning should keep working (task 01 verifies).
7. **Dev provisioning is automatic:** `io.quarkus.observability.testcontainers.GrafanaUtils` provisions every
   `META-INF/grafana/grafana-dashboard-*.json` on the classpath, named after the file. Nothing to configure.
8. **Cluster provisioning recipe:**
   - A `grafana-dashboards` ConfigMap, created by `deploy-to-openshift.sh` from the two JSON files (the
     `roundcube-identities` pattern: one copy of the files, no duplication in YAML).
   - An inline provider ConfigMap in `dependencies.yml` (`apiVersion: 1`, a `file` provider, `options.path` at the
     directory below, `foldersFromFilesStructure: false`), mounted with `subPath` at
     `/otel-lgtm/grafana/conf/provisioning/dashboards/custom.yaml`.
   - The dashboard ConfigMap mounted as a **directory** (no `subPath`) at
     `/otel-lgtm/grafana/conf/provisioning/dashboards/custom/`, so a re-applied ConfigMap propagates into the pod and
     Grafana's file provider picks it up without a restart.
9. **The intake meter schema is already pinned** in `IntakeMetricsTests` (the meter → tag keys map, around lines
   732–760). Extracted into a shared fixture, it lets a plain JUnit test assert that every `claim.intake.*` meter
   appears in some panel query, so a new meter without a panel fails the build.

### Task outcomes

- **Task 01 (2026-11-09):** root-level `quarkus.observability.lgtm.image-name` (0.38.1) and `force-scraping: false` added, with a pointer comment in `dependencies.yml`. Verified in an `-Pollama` dev boot: Grafana 13.2.4, five dashboards (four built-ins plus Flow's) provisioned, no Prometheus scrape targets, `jvm_memory_used_bytes` arrives via OTLP, `/q/metrics` returns 200, no unrecognized keys.
- **Task 02 (2026-11-09):** series inventory recorded in *Series inventory (task 02)* below. Surprises: `gen_ai_*` has no AI service label (always `none`), guardrails use fully qualified service names, timers are in milliseconds, no estimated cost under Ollama.
- **Task 03 (2026-11-09):** all eight meters (five timers, three summaries) have `serviceLevelObjectives` from constants in `IntakeMetrics`; `IntakeMetricsTests.everyTimerAndSummaryExportsItsExplicitBuckets` pins them, and the schema test now ignores `SimpleMeterRegistry`'s `*.histogram` per-bucket gauges. Verified in dev: `le` values match in the exported unit (ms for timers, bytes for size).
- **Task 04 (2026-11-09):** both dashboards written (`META-INF/grafana/grafana-dashboard-{claim-intake,ai}.json`, generated once by a throwaway script, now maintained as plain JSON), provisioned in dev and every panel query evaluated against dev Prometheus: all valid; empty only where nothing happened (failures, missing items, reconnects, faulted runs, AI errors, tool guardrails) or Ollama has no price (estimated cost). `IntakeMeterSchema` is shared by `IntakeMetricsTests` and the new `GrafanaDashboardTests` (fails when a meter loses its panel; tried once). Not done: screenshots (no browser access from the agent), `gen_ai_client_estimated_cost_total` and `tool_guardrail_invoked_total` names are inferred, not seen. The Ollama classifier needed a hand-sent email to get a Pending Review claim.
- **Task 06 (2026-11-09):** `CLAUDE.md`, `README.md` and the demo guide describe the dashboards. Full `verify` could not finish locally under podman (the VM saturated; `NotificationServiceTests.emailSendsWhenUserExists` also failed once on a live Ollama answer); the maintainer runs it with real keys. PR opened from `gh277/claim-intake-dashboard`.

### Series inventory (task 02, 2026-11-09, `-Pollama` dev boot, otel-lgtm 0.38.1, Prometheus datasource uid `prometheus`)

Every series carries `job="parasol-app"`, `service_name="parasol-app"` and `service_version`; no panel needs them. Each series
arrived **once per label set** (scrape off held). A counter or histogram family appears only after its first increment, so
queries must tolerate an absent series (`or vector(0)` where a zero reads better than "No data").

**Naming rules (all confirmed):** counters `_total`; timers `<name>_milliseconds_{bucket,count,sum}` plus
`<name>_max_milliseconds` (gauge); summaries `<name>_<unit>_{bucket,count,sum}` (`claim_intake_email_size_bytes_*`; the
unitless ones are `claim_intake_extraction_completeness_*` and `claim_intake_claim_emails_*`); gauges keep their name
(`claim_intake_review_oldest_pending_seconds`). **Timers are in milliseconds**, not seconds: divide by 1000 or set the
panel unit to `ms`. Dots and hyphens become `_`.

| Family | Series (label names) |
|---|---|
| Intake counters | `claim_intake_emails_total{outcome}`, `_replies_total{template}`, `_failures_total{stage,exception,outcome}`\*, `_claim_status_transitions_total{status}`, `_review_decisions_total{decision}`, `_review_overrides_total{agreement}`, `_attachments_total{result,reason}`, `_classifications_total{email_type,matched_claim}`, `_matcher_result_total{result}`, `_missing_items_total{item}`\*, `_watcher_reconnects_total`\* |
| Intake timers | `claim_intake_review_wait_milliseconds_*`, `_claim_time_to_complete_milliseconds_*`, `_email_latency_milliseconds_*{outcome}`, `_mail_client_duration_milliseconds_*{protocol,operation,outcome}`, `_mailbox_lock_wait_milliseconds_*{mode}` |
| Intake summaries | `claim_intake_email_size_bytes_*`, `_extraction_completeness_*`, `_claim_emails_*` (no labels) |
| Intake gauges | `claim_intake_reviews_pending`, `_watcher_connected`, `_inbox_depth`, `_review_oldest_pending_seconds` (no labels) |
| Flow | `quarkus_flow_workflow_{started,completed}_total{workflow,workflowVersion}`, `quarkus_flow_workflow_duration_milliseconds_*`, `quarkus_flow_task_{started,completed}_total` and `_task_duration_milliseconds_*{task,…}`, gauges `quarkus_flow_instance_{running,waiting,suspended}`. `faulted`/`cancelled` appear only after one happens |
| gen_ai | `gen_ai_client_token_usage_total` and `_token_usage_distribution_{count,sum}` (`gen_ai_token_type`=`input`/`output`), `gen_ai_client_operation_duration_milliseconds_*`; labels `gen_ai_request_model`, `gen_ai_response_model`, `gen_ai_provider_name`, `gen_ai_operation_name`, `error_type`, `ai_service_class_name`, `ai_service_method_name` |
| AI services | `langchain4j_aiservices_counted_total{aiservice,method,result,exception}`, `langchain4j_aiservices_timed_milliseconds_*{aiservice,method}` |
| Guardrails | `guardrail_invoked_total` and `guardrail_timed_milliseconds_*{aiservice,guardrail,guardrail_type,operation,outcome}`, plus `guardrail_timed_percentile_milliseconds{…,phi}` |

\* Not seen in this run (never incremented); the name follows the rules above and the schema in `IntakeMetricsTests`.

Observed label values: `workflow` = `claim-intake`, `claim-extraction-workflow`, `claims-mailbox-agent`, `email-router`; `emails.outcome` =
`new_claim`, `no_matching_claim`; `review.overrides.agreement` = `overridden`; `review.decisions.decision` = `ready`;
`status` = `In Process`, `Pending Review`; `replies.template` = `claimInProcess`, `noMatchingClaim`, `receivedFinalReview`.

**Findings and decisions for task 04**

- **`gen_ai_*` carries no AI service.** `ai_service_class_name` and `ai_service_method_name` are always `none` (the OTel
  context isn't current where the model call records its meter). Token usage, model latency and cost therefore break down
  **by `gen_ai_request_model` only**; they cannot follow `$ai_service`. (Intake-only token figures aren't possible from these
  meters; Langfuse has them per claim.)
- **`$ai_service` drives `langchain4j_aiservices_*` (simple names, `aiservice="ClaimSummaryAgent"`) and `guardrail_*`
  (fully qualified, `aiservice="org.parasol.intake.agent.extraction.ClaimSummaryAgent"`).** Decision: the variable holds
  simple names (label_values on `langchain4j_aiservices_counted_total`), and guardrail queries match
  `aiservice=~".*\\.(${ai_service:regex})"`. Chat bot and email services appear once they are called.
- **`gen_ai_client_estimated_cost` did not arrive** under Ollama (no price). The panel stays and shows "No data" there; verify
  against OpenAI if wanted. `tool-guardrail.*` didn't arrive (no tool call in this run); the panel uses `tool_guardrail_*` and
  must be checked after a chat status update.
- **`guardrail.timed` percentiles do arrive**, as `guardrail_timed_percentile_milliseconds{phi="0.75|0.95|0.99"}`; use them
  for the guardrail latency panel, no histogram needed.
- The Ollama classifier sometimes labels a new claim a follow-up (`no_matching_claim`): a second dev boot was needed to get
  a `Pending Review` claim. Expect that when verifying.

### Library meters (from #218, to be confirmed by task 02)

See `.tasks/06-gh218-grafana-ai-dashboard/PLAN.md` → *Findings handed to #277*. In short:

- `langchain4j.aiservices.timed` / `.counted` — tags `aiservice`, `method`, `result`, `exception`.
- `gen_ai.client.token.usage` (also `.distribution`), `gen_ai.client.operation.duration`, `gen_ai.client.estimated_cost`
  — tags `gen_ai.token.type`, `gen_ai.request.model`, `ai_service.class_name`, `ai_service.method_name`, `error.type`,
  `currency`.
- `guardrail.invoked` / `guardrail.timed` (client-side percentiles 0.75/0.95/0.99), and `tool-guardrail.*` with a hyphen.
- Flow, prefix `quarkus.flow`: `workflow.started/completed/faulted/cancelled.total`, `workflow.duration`, `task.*`, and
  the gauges `instance.running/waiting/suspended` — tags `workflow`, `workflowVersion`, `task`.

### Caveats & Problems

- **Dev data needs a live model.** The intake agents have to answer for any intake meter to move. `DevClaimEmailSeeder`
  sends its two sample claims on every dev start, which gives most intake panels data; a review decision and a chat
  status update cover the rest.
- **Two AI-service label spellings.** `langchain4j_aiservices_*` carries `aiservice`, `gen_ai_*` carries
  `ai_service_class_name` (to be confirmed). One `$ai_service` variable has to drive both; task 02 settles how
  (the same values in both, or a regex over either).
- **Client-side percentiles may not survive OTLP.** `guardrail.timed` publishes 0.75/0.95/0.99 percentiles; whether the
  bridge exports them is unknown. If not, the guardrail latency panel uses whatever the bridge does export.
- **The root-level LGTM keys are build time** on the root `@ConfigMapping(prefix = "quarkus.observability")`, so they're
  recognized keys (the zero-unrecognized-keys invariant holds). They only matter when the LGTM dev service runs; the
  `drift` and `langfuse-ocp` profiles turn LGTM off, which these keys don't change.
- **No meter carries a claim number, address or `Message-ID`** (by design, `CLAUDE.md` → *Observability*), so no panel
  can leak one. Keep it that way: never group by a label that isn't in the meter schema.
- **Line numbers drift.** Numbers in the task files were read on 2026-11-09 against `main` at `f4eeff7`. Re-grep rather
  than trusting an offset after an earlier task has edited the file.

### Manual Checks (for the user)

- **Cluster:** the next `deploy-to-openshift.sh` is where cluster provisioning is proven: both dashboards appear in the
  Grafana Route the script now prints, and the intake one shows data after a claim email. Task 05 checks the mount
  layout locally with podman, but nobody on the agent side can reach the cluster.
