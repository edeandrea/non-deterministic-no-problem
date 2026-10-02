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
- Skip the Mailpit versions; Mailpit is removed in issue 4.
- If quarkus-langfuse has a newer release, read its release notes for changes to the
  `LangfuseOperations` layer before bumping. `ai.scoring` depends on it heavily.
- Re-run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` after the bumps,
  and fix any errors.

## Files/Areas

- `pom.xml`
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