# Task 04: Bump Container Images, Runtime Pins and CI Actions

**Type:** Code Modification

## Goal

Update container images, base images, the Node/npm pins and GitHub Actions to the latest stable
versions recorded in the version inventory.

## What to Do

- `src/main/kubernetes/dependencies.yml`: bump `postgres` and `grafana/otel-lgtm`. Leave `axllent/mailpit` (removed in issue 4).
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