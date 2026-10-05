# Task 04: Bump Container Images, Runtime Pins and CI Actions

**Type:** Code Modification

## Goal

Update container images, base images, the Node/npm pins and GitHub Actions to the latest stable
versions recorded in the version inventory.

## What to Do

- Concrete changes from the inventory and the user's decisions:
  - `grafana/otel-lgtm` 0.11.0 → 0.35.0 (`dependencies.yml:161`). Check its release notes for port, env var or volume-path changes that affect `dependencies.yml`.
  - `postgres`: **keep** the floating `18` tag (user decision).
  - Quinoa `node-version` 24.15.0 → latest 24.x LTS, re-verified at execution time. `npm-version` goes to the latest 11.x (11.21.0 at inventory time), staying on LTS and never npm 12.
  - `clickhouse-operator-helm` 0.0.5 → 0.0.8 (`deploy-to-openshift.sh:25`).
  - **pin** the Langfuse Helm chart to 2.1.3 (`deploy-to-openshift.sh:48`).
  - cert-manager (commented-out line, `deploy-to-openshift.sh:12`) v1.20.2 → v1.21.2.
  - PlantUML jar 1.2026.0 → 1.2026.8 (`docs/render-diagrams.sh:12`). Re-render `docs/*.puml` and compare the PNG dimensions with the old renders.
  - UBI images, Dockerfile bases and CI actions are already latest: keep. Leave `.github/dependabot.yml` alone (user decision).
- `src/main/kubernetes/dependencies.yml`: leave `axllent/mailpit` (removed in issue 4).
- `src/main/resources/application.yml`: bump `quarkus.openshift.base-jvm-image`, and the Quinoa
  `node-version` / `npm-version` if newer LTS-compatible releases exist.
- `src/main/docker/Dockerfile.jvm` and `Dockerfile.native`: bump the `FROM` base images.
- `.github/workflows/*.yml`: bump action versions if Dependabot hasn't already.
- `deploy-to-openshift.sh` / `langfuse-helm.values.yml`: bump Helm chart and image versions only if
  the inventory marks them "bump", and note any version the Langfuse chart requires.

## Files/Areas

- `src/main/kubernetes/dependencies.yml`, `src/main/resources/application.yml`
- `src/main/docker/Dockerfile.jvm`, `src/main/docker/Dockerfile.native`
- `.github/workflows/simple-build-test.yml`, `deploy-to-openshift.sh`, `langfuse-helm.values.yml`
- `docs/render-diagrams.sh`, `docs/*.png`

## Key Points

- **PostgreSQL major upgrades:** `dependencies.yml` mounts a persistent volume (`db-data-pvc`). A data
  directory created by an older major version won't start under a newer one. The `%prod`/`%openshift`
  profiles drop and recreate the schema, but the data directory itself still has to be compatible.
  Flag any major bump in `PLAN.md` so the user can wipe the PVC.
- **Node:** stay on an LTS line. Quinoa installs Node itself, so a bad pin breaks the frontend build. Verify with `./mvnw -B clean package -DskipTests`.
- Cluster deployment can't be verified by an agent. Hand it to the user.

## Done When

- [ ] Every image, runtime and action row the inventory marks "bump" is applied.
- [ ] `./mvnw -B clean package -DskipTests -Pollama` succeeds, including the Quinoa frontend build.
- [ ] Any major-version bump needing manual cluster action (e.g. a PostgreSQL PVC wipe) is called out in `PLAN.md` Caveats.