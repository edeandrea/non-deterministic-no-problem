# Task 01: Version Inventory

**Type:** Exploration

## Goal

Produce an exact inventory of every pinned version in the repository next to its latest **stable**
release, so the following tasks bump against verified numbers rather than assumptions.

## What to Do

- List every version pin and its current value:
  - `pom.xml` `<properties>` (quarkus platform, quarkus-langchain4j, quarkus-langfuse, quinoa,
    playwright, wiremock, assertj, compiler/surefire plugin, mailpit) and any plugin `<version>` set
    inline (e.g. `os-maven-plugin`, `maven-dependency-plugin`).
  - Container images: `src/main/kubernetes/dependencies.yml` (`postgres`, `grafana/otel-lgtm`,
    `axllent/mailpit`), `src/main/resources/application.yml` (`quarkus.openshift.base-jvm-image`),
    `src/main/docker/Dockerfile.jvm` and `Dockerfile.native` (`FROM` lines).
  - Runtime pins: `application.yml` Quinoa `node-version` / `npm-version`.
  - CI: `.github/workflows/*.yml` action versions.
  - Deploy: `deploy-to-openshift.sh` Helm chart versions (`clickhouse-operator`) and `langfuse-helm.values.yml` image tags, if any.
- Look up the latest stable release of each from its authoritative source:
  - Maven artifacts: `maven-metadata.xml` on Maven Central.
  - Container images: the Docker Hub / Quay / Red Hat registry tag lists.
  - GitHub Actions: their releases.
- Treat `Beta`, `CR`, `M`, `alpha` and `rc` releases as **not** stable. List them, but don't choose them.
- For quarkus-langchain4j, also record the versions it brings in (`langchain4j.version` in
  `quarkus-langchain4j-parent`, and `langchain4j.beta.version` in the matching `langchain4j-bom`),
  plus the Quarkus version its parent POM baselines.
- Record the results as a table in `PLAN.md` → `## Shared Context` → `### Version Inventory`.

## Files/Areas

- `pom.xml`, `src/main/kubernetes/dependencies.yml`, `src/main/resources/application.yml`
- `src/main/docker/Dockerfile.jvm`, `src/main/docker/Dockerfile.native`
- `.github/workflows/simple-build-test.yml`, `deploy-to-openshift.sh`, `langfuse-helm.values.yml`

## Key Points

- **Mailpit** (the `quarkus-mailpit` extensions and the `axllent/mailpit` image) is removed entirely in
  issue 4. Record it, but mark it "skip — removed in issue 4".
- **npm packages** in `src/main/webui/package.json` are out of scope (Dependabot doesn't track npm here).
  Note the gap; don't bump them.
- Snapshot from planning time, for orientation only — re-verify everything:
  - `quarkus-langchain4j-bom` 1.14.1
  - Quarkus platform 3.40.1 (4.0.0.Beta1 is a pre-release)
  - `quarkus-playwright` 2.3.10
- Use read-only lookups only. Don't change any file except `PLAN.md` in this task.

## Done When

- [ ] `PLAN.md` contains a table with columns: item, location (file:line), current, latest stable, action (bump / keep / skip + reason).
- [ ] Every pin in the files listed above has a row in that table.
- [ ] The quarkus-langchain4j row records the langchain4j core and beta versions it brings in, and the Quarkus version it targets.