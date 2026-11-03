# Task 01: Delete the Two Dead Keys and Scope the CI Flag

**Type:** Configuration Modification

## Goal

`quarkus.micrometer.export.otlp.*` and `quarkus.dev-ui.cors.enabled` are gone from `application.yml`, the CI Ollama
Dev Service flag is passed only on the leg where the extension exists, and nothing behaves differently anywhere. Three
of the 36 keys accounted for, with no runtime risk.

## What to Do

### 1. `quarkus.micrometer.export.otlp.enabled` — delete both occurrences

- `src/main/resources/application.yml:32-35`, inside `%drift`:
  ```yaml
      micrometer:
        export:
          otlp:
            enabled: false
  ```
- `src/main/resources/application.yml:454-457`, inside `%test`, where the value is the self-referencing
  `"${%test.quarkus.observability.enabled}"`.

Delete the whole `micrometer:` node in each — it has no other children. Leave the adjacent `observability.enabled:
false` and `quarkus.otel.exporter.otlp.enabled: false` (`:25` and `:445`) exactly as they are: those are the switches
that actually do the work.

### 2. `quarkus.dev-ui.cors.enabled` — delete

`src/main/resources/application.yml:232-234`:
```yaml
  dev-ui:
    cors:
      enabled: true
```
Delete the whole `dev-ui:` node. **Do not touch** `quarkus.http.cors` directly above it (`:228-231`) — that is a
different, valid, load-bearing key. Also do not confuse this with `quarkus.datasource.dev-ui.allow-sql` under `%dev`
(around `:382`), which is a different root and stays.

### 3. The CI flag

`.github/workflows/simple-build-test.yml:85-89` currently ends every build with
`-Dquarkus.langchain4j.ollama.devservices.enabled=false`, on both matrix legs. Make it apply only when
`matrix.ai-provider == 'ollama'`. Either a conditional expression in the `run:` block or a small `env:`/step-level
guard is fine; pick whichever reads better against the surrounding style and keep the command a single readable
`./mvnw -B clean verify …` invocation.

Keep the existing explanatory comment at lines 44-47 accurate — it currently explains why one service container serves
both legs and why the Dev Service is disabled; it should now also make clear the flag is only needed on the leg that
has the extension.

## Files/Areas

- `src/main/resources/application.yml` (three deletions)
- `.github/workflows/simple-build-test.yml` (the flag and its comment)

## Key Points

- **`quarkus-micrometer-registry-otlp` is not a dependency.** `pom.xml` declares
  `quarkus-micrometer-registry-prometheus` (`:177-180`) and `quarkus-micrometer-opentelemetry` (`:186-189`). The latter
  is the bridge actually in use and has **no runtime config of its own** — it force-sets `quarkus.otel.metrics.enabled`
  and `quarkus.otel.logs.enabled` at priority 1. Deleting these keys cannot change behavior because nothing ever read
  them. #218's PLAN.md reached the same conclusion and deliberately deferred the deletion to this issue.
- **`quarkus.dev-ui.cors.enabled` defaults to `true`.** Verified in
  `quarkus-devui-deployment-3.40.1.1.jar`'s `META-INF/quarkus-config-doc/quarkus-config-model.json`:
  `"defaultValue" : "true"`, `sourceType: io.quarkus.devui.deployment.DevUIConfig.Cors`, `phase: BUILD_TIME`. Setting
  it to `true` is a no-op.
- **Don't relocate either key to `%dev` as an alternative.** Dev Services and the Dev UI follow the dev *launch mode*,
  not the config profile, and `-Pollama` / `-Pollama-openai` run `quarkus:dev` as `<ai>,prod` — so a `%dev` block
  wouldn't apply there. This is the same reason the mailer and Compose keys sit at root level (`CLAUDE.md` →
  *Commands*).
- **The flag does nothing on the `ollama-openai` leg.** `%ollama-openai` points its `base-url` at
  `http://localhost:11434/v1` explicitly and never goes through the Ollama extension's client, so there is no Dev
  Service to disable there.
- No Java changes, no test changes, no doc changes in this task. `CLAUDE.md`'s "only CI-specific config" sentence is
  updated in task 06, once the whole shape is settled.

## Done When

- [ ] `grep -n "micrometer" src/main/resources/application.yml` returns nothing.
- [ ] `grep -n "dev-ui" src/main/resources/application.yml` returns only the `quarkus.datasource.dev-ui.allow-sql`
      line under `%dev`.
- [ ] `.github/workflows/simple-build-test.yml` passes
      `-Dquarkus.langchain4j.ollama.devservices.enabled=false` on the `ollama` leg only, and its comment reads
      correctly.
- [ ] `./mvnw -B clean verify -Pollama` is green, and the unrecognized-key list from the baseline grep in `PLAN.md` has
      shrunk by exactly `micrometer.export.otlp.enabled` and `dev-ui.cors.enabled` (from 14 distinct keys to 12).
- [ ] The YAML still parses — a boot is proof enough, but confirm the `%drift` and `%test` blocks kept their remaining
      children at the right indentation.
