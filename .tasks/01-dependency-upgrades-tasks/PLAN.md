# Issue 1: Dependency and Version Upgrades — Task Execution Plan

## Your Mission

Bring quarkus-langchain4j and every other dependency, plugin, container image, runtime pin and CI
action to its latest **stable** release, before any feature work starts. This is the first of five
issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/01-dependency-upgrades-tasks/PLAN.md`
**Tasks Directory:** `.tasks/01-dependency-upgrades-tasks/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/01-dependency-upgrades-tasks/task-XX-*.md`:
- **Goal**: what you're trying to achieve
- **Key Points**: things to watch out for
- **Done When**: the acceptance criteria

### 3. Execute the Task
- Make the changes, following the global rules in `AGENTS.md` (coding style, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task**, not later.
- Check every Done When item.

### 4. Update This Plan
- Mark the task complete in `## Task Plan`.
- Add a 1–2 sentence outcome summary under `## Shared Context`.
- Record only decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [ ] [task-01-version-inventory.md](task-01-version-inventory.md): Version inventory (current vs latest stable)
- [ ] [task-02-quarkus-langchain4j-bom.md](task-02-quarkus-langchain4j-bom.md): Bump the quarkus-langchain4j BOM
- [ ] [task-03-remaining-maven-versions.md](task-03-remaining-maven-versions.md): Bump remaining Maven dependencies and plugins
- [ ] [task-04-images-and-runtime-pins.md](task-04-images-and-runtime-pins.md): Bump container images, runtime pins and CI actions
- [ ] [task-05-documentation.md](task-05-documentation.md): Update documentation for the version bumps
- [ ] [task-06-verification.md](task-06-verification.md): Verify the upgrade

---

## Shared Context

### Overview
This issue upgrades versions only; there are no feature changes. It goes first so the agentic module
(issue 5) arrives at its latest version and upgrade risk stays separate from feature risk.

### Project Context
- `pom.xml` holds the version properties: `quarkus.platform.version`, `quarkus.langchain4j.version`,
  `quarkus.langfuse.version`, `quarkus.quinoa.version`, `quarkus.playwright.version`,
  `quarkus.wiremock.version`, `quarkus.mailpit.version`, and the plugin versions.
- `src/main/kubernetes/dependencies.yml` defines the cluster dependencies (PostgreSQL, LGTM, Mailpit),
  applied by `deploy-to-openshift.sh`.
- `.github/workflows/simple-build-test.yml` is CI: `./mvnw -B clean verify -P{ollama,ollama-openai}`
  on Java 25, with only `OPENAI_API_KEY=change-me`.
- `CLAUDE.md` is the project context file; `README.md` is the user-facing overview.

### Key Decisions
- Latest **stable** only. Pre-releases (Beta/CR/M/alpha/rc) are recorded but not adopted.
- Mailpit pins are left alone; Mailpit is removed in issue 4.
- npm packages in `src/main/webui/package.json` are out of scope.
- Every task updates the docs it affects, per the documentation policy in `AGENTS.md`.

### Caveats & Problems
- Agent builds don't have the user's real API keys. Only compilation is a trustworthy signal; the full
  `verify` with real keys is handed to the user.
- A drift-detection breakage from span or invocation naming changes would be silent (see task 02).
- A PostgreSQL major-version bump may need the cluster's `db-data-pvc` wiped by hand.

### Version Inventory
_(Filled in by task 01.)_