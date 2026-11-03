# Task 01: Delete the AI Dashboard

**Type:** Code Modification

## Goal

`src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` is gone, nothing in the repo still references it or the
metrics it charted, and dev mode's Grafana still shows the JVM, HTTP and Agroal data through the LGTM dev service's
built-in dashboards.

## What to Do

- `git rm src/main/resources/META-INF/grafana/grafana-dashboard-ai.json`. That empties
  `src/main/resources/META-INF/grafana/`, so the directory goes with it; that is expected.
- Confirm nothing else references it. Grep the repo (excluding `target/`, `node_modules/`, `.quinoa/`, `.git/`) for
  `parasol_llm`, `scorer_`, `interaction_scored`, `mp_graphql`, `few3cxgl3sf`, `grafana-dashboard` and `AI Dashboard`.
  The **only** expected remaining hits are in `.tasks/github-issues/issue-06-grafana-dashboard.md`, which task 02
  annotates rather than rewrites.
- **Dev-mode check.** Run `./mvnw quarkus:dev`, open the Grafana URL the dev service logs (`grafana.endpoint=…`, or the
  Dev UI extensions card), chat with the assistant and trigger a status-update email so there is traffic, then confirm:
  - the four built-in dashboards are listed ("Quarkus Micrometer OpenTelemetry", "Quarkus Micrometer OTLP registry",
    "Quarkus Micrometer Prometheus registry", "Quarkus OpenTelemetry Logging");
  - "Quarkus Micrometer OpenTelemetry" shows JVM, HTTP and Agroal data — i.e. nothing of value was lost;
  - no "AI Dashboard" (or "Ai") remains;
  - the LGTM container log carries no Grafana provisioning error, and the boot log no longer logs
    `Adding custom Grafana dashboard config: …`.
- Run `./mvnw -B clean verify -Pollama` once, to confirm nothing in the build or the tests read the file.

## Files/Areas

- `src/main/resources/META-INF/grafana/grafana-dashboard-ai.json` (deleted; the parent directory goes with it)

## Key Points

- **Keep `quarkus-observability-devservices-lgtm`** (`pom.xml:181-185`). It is `provided` scope and it is what
  provisions the four built-in dashboards that now carry the JVM/HTTP/Agroal view. Removing it would take them too.
- **Don't touch `application.yml`'s observability config** in this task — including the dead
  `quarkus.micrometer.export.otlp.*` keys (`PLAN.md` → *Caveats & Problems*), which are a separate cleanup to raise with
  the maintainer.
- No replacement dashboard is added here. The intake dashboard, its AI row and cluster provisioning are all #277.
- Nothing in `src/test` reads the dashboard, so no test changes are expected. Don't add one — the dev-mode check is the
  verification.

## Done When

- [ ] `grafana-dashboard-ai.json` is deleted.
- [ ] No reference to it or to `parasol_llm` / `scorer_` / `interaction_scored` / `mp_graphql` survives outside
      `.tasks/github-issues/issue-06-grafana-dashboard.md`.
- [ ] The dev-mode check passes: four built-in dashboards, JVM/HTTP/Agroal data present, no "AI Dashboard", no
      provisioning error.
- [ ] `./mvnw -B clean verify -Pollama` is green.
