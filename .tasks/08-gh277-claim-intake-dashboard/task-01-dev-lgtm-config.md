# Task 01: Pin the Dev LGTM Image and Turn Off the Accidental Scrape

**Type:** Configuration

## Goal

Dev runs the same otel-lgtm (and so the same Grafana 13) as the cluster, and receives each meter once — by OTLP push
only — so a dashboard query reads the same in dev and on the cluster. `/q/metrics` keeps answering.

## What to Do

### 1. `application.yml` — two root-level keys

Under the root `quarkus:` node (not `%dev`: Dev Services follow the dev *launch mode*, and the Ollama Maven profiles run
`quarkus:dev` as `<ai>,prod`, the same reason the mailer and Compose keys sit at root level):

```yaml
quarkus:
  observability:
    lgtm:
      # The cluster's image (src/main/kubernetes/dependencies.yml): change both together
      image-name: docker.io/grafana/otel-lgtm:0.38.1
      # Metrics reach LGTM by OTLP push only; without this the dev service also scrapes /q/metrics and every series arrives twice
      force-scraping: false
```

Merge into an existing `quarkus.observability` node if there is one — don't create a second one. Check the
indentation (`CLAUDE.md` → *The zero-unrecognized-keys invariant*: a mis-indented node is a silent sibling, not an
error).

### 2. `dependencies.yml` — the other side of "change both together"

Add a comment above `image: grafana/otel-lgtm:0.38.1` (`src/main/kubernetes/dependencies.yml:158`) pointing back at
`quarkus.observability.lgtm.image-name` in `application.yml`.

### 3. Verify in a dev boot

Run `./mvnw quarkus:dev` (any AI profile; no model traffic is needed for this task) and check:

- The LGTM container is `grafana/otel-lgtm:0.38.1`, and Grafana's `/api/health` reports 13.x.
- Grafana still lists the four Quarkus built-in dashboards and Flow's "Quarkus Flow" (finding 6: 0.38.1 must still
  read the provisioning file the dev service overwrites).
- Prometheus's `/api/v1/targets` (through Grafana's datasource proxy, `/api/datasources/proxy/uid/prometheus/api/v1/targets`)
  has no scrape job for the app.
- An intake or JVM metric still arrives (e.g. `jvm_memory_used_bytes` via the OTLP path), and only once per label set.
- `curl localhost:8080/q/metrics` still answers.
- The startup log has no `Unrecognized configuration key`.

## Files/Areas

- `src/main/resources/application.yml`
- `src/main/kubernetes/dependencies.yml` (comment only)

## Key Points

- Both keys are build time, on the root `@ConfigMapping(prefix = "quarkus.observability")`, so they are recognized.
- Don't remove `quarkus-micrometer-registry-prometheus` (Q6).
- `%drift` and `%langfuse-ocp` turn LGTM off; these keys don't change that.
- Flow's bundled dashboard going empty in dev is expected (finding 4), not a regression to fix.

## Done When

- [x] Both keys are at root level in `application.yml`, with comments.
- [x] `dependencies.yml`'s image line points back at `application.yml`.
- [x] Dev boot: Grafana 13.x, built-in and Flow dashboards provisioned, no app scrape target, `/q/metrics` answers.
- [x] Metrics arrive once per label set.
- [x] No `Unrecognized configuration key` in the dev boot log.
