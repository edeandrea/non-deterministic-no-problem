TITLE: Clean up the Grafana AI dashboard
## Summary

`src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` ("AI Dashboard", uid `few3cxgl3sf`) mostly charts
metrics that no longer exist. Its AI row uses `parasol_llm_*`, `scorer_*` and `interaction_scored_latest_score`,
whose emitters were removed along with the old scorer architecture (commits `5d31ba1`, `f782cae`). Its GraphQL
row charts an extension the app doesn't use. Only the JVM, HTTP and Agroal (datasource) panels still work.
The dashboard is also loaded only in dev mode: the cluster deployment never provisions it.

Replace the dead panels with the AI metrics the app actually emits, and provision the dashboard on the cluster too.

Related to the roadmap in #217. #216 adds intake metrics (`claim.intake.*`); panels for those are added here
once #216 has landed (if this issue lands first, track them as a follow-up here).

## Current state

- **Datasources:** Prometheus (Mimir) only. No Tempo or Loki panels.
- **Dead:** the AI row and the GraphQL row:
  - "Total AI Cost", "Number of AI invocations"
  - "Real-time scoring", "Avg Guardrail executions / interaction"
  - "LLM interaction failures", "Token Counts", "Tool invocations"
- **Working:** the JVM, HTTP (`http_server_requests_*`) and Agroal panels.
- **Loading:**
  - dev: the LGTM dev service copies every `META-INF/grafana/grafana-dashboard-*.json` into the container.
  - cluster: `src/main/kubernetes/dependencies.yml` runs `grafana/otel-lgtm` with only a `/data` volume. Nothing provisions the dashboard.
- **App metrics:** none of the app's own code (`src/main/java`) registers Micrometer metrics. All AI metrics come from quarkus-langchain4j.

## Scope

- **Remove** the AI row and the GraphQL row.
- **Add AI panels** built on metrics that are actually emitted. Expected exported names are listed below; confirm each one in Mimir before using it:

  | Panel | Metric | Labels |
  |---|---|---|
  | AI service calls (rate, errors) | `langchain4j_aiservices_counted_total` | `aiservice`, `method`, `result`, `exception` |
  | AI service latency (p50/p95) | `langchain4j_aiservices_timed_*` | `aiservice`, `method` |
  | Token usage (input/output, per model / AI service) | `gen_ai_client_token_usage_total` | `gen_ai_token_type`, request/response model, `ai_service_*` |
  | Model call latency | `gen_ai_client_operation_duration_*` | model |
  | Estimated cost | `gen_ai_client_estimated_cost_total` | `currency` (may not appear for every model) |
  | Guardrail outcomes and latency | `guardrail_invoked_total`, `guardrail_timed_*` | `outcome` |

- **Tool invocations:** no metric exists. Either a Tempo (TraceQL) panel on `langchain4j.tools.*` spans, or drop the panel.
- **Metric naming:** check which names actually arrive. The app has both `quarkus-micrometer-registry-prometheus` and `quarkus-micrometer-opentelemetry`, so the Prometheus scrape (`_seconds` units) and OTLP export (`_milliseconds` units) may both send data. Use one naming consistently and document which.
- **Cluster:** provision the dashboard (e.g. a ConfigMap mounted into Grafana's provisioning directory) in `dependencies.yml`. Confirm that the `grafana/otel-lgtm` image bumped in #212 supports this.
- **Docs:** describe the dashboard, what each panel shows, and how to open it in dev and on the cluster (`CLAUDE.md` observability section, `README.md`).

## Verification

- **Dev:** run `./mvnw quarkus:dev`, chat with the assistant and trigger a status-update email, then confirm every panel shows data (no "No data" panels).
- **Cluster:** after `deploy-to-openshift.sh`, the dashboard appears in Grafana and the panels show data.
- **No stale names:** a search for `parasol_llm`, `scorer_` and `interaction_scored` across the repository finds nothing.

## Tasks

- [ ] Inventory the metric names actually exported (dev Mimir query)
- [ ] Replace the AI panels; remove the GraphQL row
- [ ] Provision the dashboard on the cluster
- [ ] Update the docs
- [ ] Verify in dev; maintainer verifies on the cluster