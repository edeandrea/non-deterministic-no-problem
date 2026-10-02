TITLE: Upgrade quarkus-langchain4j and other dependencies to the latest stable versions
## Summary

Bring quarkus-langchain4j (currently `1.13.1`) and every other Maven dependency, plugin, container image,
runtime pin and CI action to its latest **stable** release. This goes first so the agentic module in
{{ISSUE_5}} is introduced at its latest version, and so upgrade risk stays separate from feature work.

Part of the email claim-intake roadmap: **this issue** → {{ISSUE_2}} claim data model → {{ISSUE_3}} claim
images → {{ISSUE_4}} GreenMail + Roundcube → {{ISSUE_5}} agentic email intake.

## Scope

- **`pom.xml`:** the version properties and inline plugin versions:
  - Quarkus platform, quarkus-langchain4j, quarkus-langfuse, Quinoa, Playwright, WireMock
  - AssertJ
  - the compiler/surefire/failsafe plugins, `os-maven-plugin`, `maven-dependency-plugin`
- **Container images:**
  - `src/main/kubernetes/dependencies.yml` (`postgres`, `grafana/otel-lgtm`)
  - `quarkus.openshift.base-jvm-image` in `application.yml`
  - the `FROM` lines in `src/main/docker/Dockerfile.jvm` and `Dockerfile.native`
- **Runtime pins:** Quinoa `node-version` / `npm-version` in `application.yml`.
- **CI:** GitHub Actions versions in `.github/workflows/*.yml`.
- **Deploy:** Helm chart and image versions in `deploy-to-openshift.sh` / `langfuse-helm.values.yml`, where applicable.

## Decisions

- **Latest stable only.** Beta / CR / M / alpha / rc releases are recorded but not adopted (e.g. Quarkus `4.0.0.Beta1`).
- **Mailpit pins are left alone** (`quarkus-mailpit*`, `axllent/mailpit`); Mailpit is removed in {{ISSUE_4}}.
- **npm packages** in `src/main/webui/package.json` are out of scope.
- **`maven.compiler.release` stays `25`.** CI builds Java 25 only.

## Risks and things to check

- **langchain4j convergence:** `quarkus-langfuse` 0.7.2 was built against quarkus-langchain4j 1.13.1. Confirm the dependency tree ends up with a single version
  of each `dev.langchain4j` artifact (`./mvnw dependency:tree -Dincludes=dev.langchain4j`).
- **Silent drift-detection breakage:** `DriftDetectionOutputGuardrail` builds Langfuse dataset names from AI-service invocation
  naming (`langchain4j.aiservices.<Interface>.<method>`). If the new version changes that naming, drift
  detection breaks **silently**, because the guardrail returns success on `SampleLoadException`. Check the release notes.
- **Release notes:** check them for breaking changes in what this app uses:
  - guardrails (`JsonExtractorOutputGuardrail`, `reprompt` / `fatal`)
  - chat scopes (`@ChatScoped`, `@ChatRoute`)
  - `@ToolBox`
  - Easy RAG reuse-embeddings
  - the evaluation framework (`EvaluationReport`, `SampleLoader`)
- **PostgreSQL major bump:** the cluster's `db-data-pvc` may need wiping.
- **Node:** must stay on an LTS line. Quinoa installs it, so a bad pin breaks the frontend build.

## Tasks

- [ ] **Version inventory:** every pin, with its current version, latest stable version and action (bump / keep / skip + reason)
- [ ] Bump the quarkus-langchain4j BOM and handle any breaking changes
- [ ] Bump the remaining Maven dependencies and plugins
- [ ] Bump container images, runtime pins and CI actions
- [ ] Update every doc that states a version (`README.md`, `CLAUDE.md`, `langfuse-evaluation.md`, `docs/`, `src/main/webui/README.md`)
- [ ] **Verify:**
  - `test-compile` under `-Pollama` and the default profile
  - an independent review
  - real-key `./mvnw verify` plus a dev-mode smoke test, run by the maintainer

## Acceptance criteria

- Every inventoried pin is at its latest stable version, or explicitly deferred with a reason.
- `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` succeed.
- The dependency tree has a single langchain4j version.
- No document still states an old version.
- `./mvnw -B clean verify` passes with real keys (default profile and `-Pollama`).
- The dev-mode smoke test passes: open a claim, chat, and ask for a status update so an email is sent.

_Latest stable versions at planning time (re-verify): quarkus-langchain4j-bom `1.14.1`, Quarkus `3.40.1`, quarkus-playwright `2.3.10`._