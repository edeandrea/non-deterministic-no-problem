# Task 04: The Two Dashboards and the Test That Keeps the Intake One Complete

**Type:** Implementation

## Goal

"Parasol Claim Intake" and "Parasol AI" exist in `src/main/resources/META-INF/grafana/`, dev provisions both
automatically (finding 7), every panel shows data after task 02's traffic, and a test fails the build if an intake
meter has no panel.

## What to Do

### 1. `grafana-dashboard-claim-intake.json` (uid `parasol-claim-intake`)

Authored for Grafana 13, datasource uid `prometheus`, series names from task 02's inventory. Rows:

- **What came in** — emails by outcome, replies by template, failures by stage and outcome, claim status transitions,
  review decisions.
- **Pipeline health** — email latency p50/p95 by outcome, inbox depth, attachments kept/skipped by reason, email size.
- **Agent and routing quality** — classifications by email type and matched claim, matcher result, extraction
  completeness next to missing items by item.
- **Review loop** — pending reviews, oldest pending, review wait p50/p95, time to complete, emails per claim, the
  override agreement rate.
- **Mail and operations** — mail client duration by protocol/operation/outcome, mailbox lock wait by mode, watcher
  connected, watcher reconnects.
- **Workflow** — Flow's started/completed/faulted for `claim-intake`, workflow duration p50/p95, waiting instances.

A dashboard link to `/d/parasol-ai` with `var-ai_service=` once per intake agent (`ClaimsMailboxAgent`'s topology:
`EmailClassifierAgent`, `ClaimFollowUpAgent`, `ClaimResolver`, `ClaimSummaryAgent`, `ClaimSentimentAgent`,
`IncidentDetailsAgent` — spelled as task 02 recorded them).

### 2. `grafana-dashboard-ai.json` (uid `parasol-ai`)

Variables `$ai_service` (multi, include All, default All) and `$model`, driven as task 02 decided. Panels: tokens by
type and model, call latency p50/p95 by model, estimated cost (if task 02 found it), AI service calls and errors by
service and method, guardrail invocations and outcomes, guardrail latency, tool guardrails. A link back to
`/d/parasol-claim-intake`.

### 3. Panel conventions (both)

- `rate()`/`increase()` over `$__rate_interval` for counters; last value for gauges; `histogram_quantile` over
  `sum by (le, …) (rate(…_bucket[$__rate_interval]))` for p50/p95.
- A panel description for each, in plain words. Say that `inbox.depth` is empty (`NaN`) until the first measurement
  and `review.oldest.pending` is `0` when nothing is pending.
- Never group by a label outside the meter schema (no claim numbers, addresses or `Message-ID`s exist, keep it so).
- Fixed `uid`s, unique panel ids, no `id` at the top level, no hard-coded datasource names.

### 4. Tests

- **`IntakeMeterSchema`** (test, `org.parasol.intake.observability`): the meter → tag keys map moved out of
  `IntakeMetricsTests` (around lines 732–760), which then uses it.
- **`GrafanaDashboardTests`** (plain JUnit, no Quarkus; Jackson over the two classpath resources):
  - every `IntakeMeterSchema` meter appears, OTLP-translated, in some intake panel's query;
  - every panel datasource in both dashboards has uid `prometheus`;
  - panel ids are unique per dashboard; the uids are `parasol-claim-intake` and `parasol-ai`;
  - each dashboard's link points at the other's uid.

### 5. Look at it

In dev with task 02's traffic, every panel shows data. Screenshot both dashboards for the PR.

## Files/Areas

- `src/main/resources/META-INF/grafana/grafana-dashboard-claim-intake.json` (new)
- `src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` (new; the name #218 deleted, new content)
- `src/test/java/org/parasol/intake/observability/IntakeMeterSchema.java` (new)
- `src/test/java/org/parasol/intake/observability/IntakeMetricsTests.java`
- `src/test/java/…/GrafanaDashboardTests.java` (new; pick the package that owns the resources)

## Key Points

- Build the JSON in Grafana's UI if that's quicker, then export ("Export for sharing externally" **off**, so the
  datasource uid stays `prometheus` rather than becoming `${DS_…}`), strip the top-level `id` and `version`, and
  format it.
- Don't touch Flow's bundled dashboard or the four Quarkus built-ins.
- `GrafanaUtils` names a dev dashboard from the file name; the title inside the JSON is what the UI shows.
- Keep each dashboard to one screen per row; collapse rows below the first two if it gets long.

## Done When

- [ ] Both JSON files exist, provisioned in dev, every panel showing data.
- [ ] The cross-links work both ways, and the intake one lands pre-filtered.
- [ ] `IntakeMeterSchema` is shared by `IntakeMetricsTests` and `GrafanaDashboardTests`.
- [ ] `GrafanaDashboardTests` passes, and fails when a meter is removed from the intake dashboard (try it once).
- [ ] Screenshots saved for the PR.
