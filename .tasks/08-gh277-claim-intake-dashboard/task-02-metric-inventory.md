# Task 02: Record the Exact Series Every Panel Will Query

**Type:** Investigation (no code)

## Goal

A table in `PLAN.md` of every Prometheus series the two dashboards will query, as it actually arrives over OTLP:
metric name, label names, unit and type. Task 04 writes PromQL against this table, not against guesses.

## What to Do

### 1. Generate traffic in dev

With task 01's config in place, run `./mvnw quarkus:dev` with a live model (the default OpenAI profile, or `-Pollama`):

- `DevClaimEmailSeeder` mails its two sample claims at startup: a complete one (→ `Pending Review`) and one missing
  its date and place (→ `Pending Information`).
- Decide the `Pending Review` claim in the UI's review panel (Submit) — `review.decisions`, `review.overrides`,
  `review.wait`, `claim.time.to.complete`, `claim.emails`.
- Reply to the `Pending Information` claim from Roundcube (http://localhost:8000) — a follow-up run, the matcher.
- Ask the chat bot to change a claim's status — `generate-email`, `politeness`, the email guardrails, the tool input
  guardrail.

### 2. Query Prometheus

Through Grafana's datasource proxy (`/api/datasources/proxy/uid/prometheus/api/v1/…`): `label/__name__/values`, then
`series?match[]=…` for each family. Record:

- **Intake:** all 23 `claim.intake.*` meters — the counter suffix (`_total`), timer names and unit suffix
  (`_milliseconds_…` or `_seconds_…`), `_bucket`/`_sum`/`_count` for timers and summaries, gauge names, label names.
- **Flow:** `quarkus.flow.*` names and the `workflow` label's value for the intake (`claim-intake`?).
- **AI:** `gen_ai_client_*` and `langchain4j_aiservices_*` names and labels; whether `gen_ai_client_estimated_cost`
  is present, and for which models (Ollama models may have no price).
- **Guardrails:** `guardrail_*` and the `tool-guardrail` family's translated name (the hyphen becomes `_`?); whether
  `guardrail.timed`'s client-side percentiles are exported at all, and under what name.
- **The `$ai_service` question:** the values of `aiservice` on `langchain4j_aiservices_*` and of
  `ai_service_class_name` on `gen_ai_*` — simple names or fully qualified? Decide how one variable drives both (same
  values, or a regex) and record it as a decision.
- **Dedup:** each series appears once per label set (task 01's scrape-off held).
- **Resource attributes:** whether `job`/`service_name` is on every series and what value it has (a dashboard filter
  may want it).

### 3. Write it down

Add a *Series inventory (task 02)* section to `PLAN.md` with one table per family, plus the `$ai_service` decision.

## Files/Areas

- `.tasks/08-gh277-claim-intake-dashboard/PLAN.md` only.

## Key Points

- Histograms still have the default buckets here; task 03 changes the boundaries, not the names.
- Don't stop at "it's there": record the label names exactly, because PromQL is case and spelling sensitive.
- If a meter from #218's list doesn't exist, or a new one does, say so and flag it for task 04.

## Done When

- [ ] Every intake, Flow, AI and guardrail series the dashboards need is in the inventory, with labels and unit.
- [ ] The `$ai_service` decision is recorded.
- [ ] Whether `guardrail.timed`'s percentiles and `estimated_cost` arrive is recorded.
- [ ] Single arrival per label set confirmed.
