# Task 03: Bump Remaining Maven Dependencies and Plugins

**Type:** Code Modification

## Goal

Bring every other Maven dependency and plugin version in `pom.xml` to the latest stable release
recorded in the version inventory.

## What to Do

- Update each `pom.xml` property and inline plugin version whose inventory row says "bump":
  - Quarkus platform, quarkus-langfuse, quinoa, playwright, wiremock
  - assertj, the compiler, surefire and failsafe plugins
  - `os-maven-plugin`, `maven-dependency-plugin`
- Concrete bumps from the inventory:
  - Quinoa 2.9.0 → 2.9.2
  - quarkus-playwright 2.3.8 → 2.3.10
  - **pin** `maven-dependency-plugin` to 3.11.0 (currently unpinned; user decision)
  - Maven distribution 3.9.16 → 3.10.0 in `.mvn/wrapper/maven-wrapper.properties`. The wrapper itself (3.3.4) is already the latest.
- Every other Maven pin is already at latest stable (keep).
- Skip the Mailpit versions; Mailpit is removed in issue 4.
- If quarkus-langfuse has a newer release, read its release notes for changes to the
  `LangfuseOperations` layer before bumping. `ai.scoring` depends on it heavily.
- Re-run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` after the bumps,
  and fix any errors.

## Files/Areas

- `pom.xml`, `.mvn/wrapper/maven-wrapper.properties`
- Java sources only where a bump forces a change

## Key Points

- Bump the Quarkus platform only to a **stable** release (no `.Beta`/`.CR`). If the latest stable
  platform is already pinned, leave it.
- Keep `maven.compiler.release` at `25`. CI builds only Java 25.
- Make one logical change per commit if the agent commits (the user's commit rules in `AGENTS.md`
  forbid closing keywords in the first line).

## Done When

- [ ] Every row the inventory marks "bump" for a Maven artifact or plugin is applied in `pom.xml`.
- [ ] `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` both succeed.
- [ ] `PLAN.md` records any bump that was deferred, and why.