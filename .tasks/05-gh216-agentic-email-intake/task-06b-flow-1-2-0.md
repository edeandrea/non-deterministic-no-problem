# Task 06b: Adopt quarkus-flow 1.2.0 Ahead of the Platform

**Type:** Dependency upgrade

> **Branch and PR (user decision, 2026-10-09):** branch `gh216/flow-1.2.0` off `gh216-email-intake`, its own PR into
> the feature branch, squash-merged like the other tasks. It lands **before task 08**, so task 08's diff holds only
> task 08.

## Goal

The app runs on quarkus-flow **1.2.0**, which fixes all three upstream bugs the spikes filed
([#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056),
[#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057),
[#1058](https://github.com/quarkiverse/quarkus-flow/issues/1058)), even though the Quarkus platform (3.40.1) still pins
1.1.3. The plan and the remaining tasks describe 1.2.0, not the workarounds it replaces.

*Added 2026-10-09, when 1.2.0 was released (`PLAN.md` → Execution Steps → 2a, Caveats → quarkus-flow 1.2.0).*

## What to Do

- **`pom.xml`:** a `quarkus.flow.version` property (`1.2.0`) and an `io.quarkiverse.flow:quarkus-flow-bom:${quarkus.flow.version}`
  import **after** `quarkus-bom` and the quarkus-langchain4j BOM, and **before** the platform's `quarkus-flow-bom`
  (Maven's first import wins, so it overrides the platform's Flow entries). A comment says why it's there, what it
  changes, and when to remove it. The four Flow dependencies stay versionless.
- **Check the override's real effect** (effective-POM `<dependencyManagement>` and resolved test-scope dependencies,
  before vs after): which managed versions change, what's new, and that no `io.quarkus` version moves.
- **Re-run the reproducers** against 1.2.0, with the version set in their `pom.xml` (a `-D` override doesn't reach
  Quarkus's test bootstrap), and 1.1.3 as the control.
- **Code:** only `ClaimExtractionWorkflow`'s Javadoc names #1057 as the reason for having no `@ParallelExecutor`;
  reword it. Don't add one (see Key Points).
- **Plan and task files:** Execution Step 2a now watches for a platform that pins Flow ≥ 1.2.0; Caveats get the
  2026-10-09 state; tasks 08, 11, 13 and 14 drop or rewrite what 1.2.0 fixed. The merged task files (03, 05, 06) keep
  their Outcomes; task 03 gets a dated note.
- **Run the full CI suite** (`clean verify`, with CI's exact flags) under `-Pollama`, `-Pollama-openai` and default.

## Key Points

- **No `@ParallelExecutor` on `ClaimExtractionWorkflow`, even though #1068 makes it work.** Flow already runs the
  fork on Quarkus's `ManagedExecutor`, and #1065 carries the caller's OTel context onto the agent threads, which was
  the only reason to want one (`Context.taskWrapping`). It'd add a pool to size and nothing to show for it.
- **quarkus-langchain4j#2950 is unrelated** to Flow and still open (fix #2951 unmerged), so `ClaimsMailboxAgent` keeps
  its `invocationParameters` argument.
- **The quarkus#54354 guard (task 10b) stays:** it's a Quarkus bug, and #56805 is in no 3.40 release yet.
- **Flow 1.2.0's periodic re-scan** (#1064, `quarkus.flow.persistence.scan-interval`) is off by default. Don't set it:
  a restart wipes the database anyway (no durable state, user decision 2026-10-07).

## Done When

- [x] `pom.xml` imports `io.quarkiverse.flow:quarkus-flow-bom:${quarkus.flow.version}` (1.2.0) ahead of the platform's
  Flow BOM, with a comment giving the reason, the changed versions and the removal condition; `dependency:tree` shows
  every Flow module on 1.2.0 and quarkus-langchain4j on 1.14.1.
- [x] The reproducers fail on 1.1.3 and pass on 1.2.0, with the loaded versions checked.
- [x] `ClaimExtractionWorkflow`'s Javadoc no longer gives #1057 as the reason for having no `@ParallelExecutor`.
- [x] `PLAN.md` (2a, Caveats, Task Plan, Mission) and tasks 03, 08, 11, 13 and 14 describe 1.2.0.
- [x] `clean verify` passes under `-Pollama`, `-Pollama-openai` and default, apart from failures that also fail on the
  base commit. *(The 10 Playwright failures fail identically on `1b232cc`; the default-profile error needs a real
  OpenAI key. See the Outcome.)*

## Outcome

Built 2026-10-09 on branch `gh216/flow-1.2.0`; in review as
[#236](https://github.com/edeandrea/non-deterministic-no-problem/pull/236) into `gh216-email-intake`.

- **The override** (`pom.xml`): the Quarkiverse Flow BOM is imported third, after `quarkus-bom` and the
  quarkus-langchain4j BOM, and before the platform's `quarkus-flow-bom`. Effective-POM diff, before → after
  (2510 → 2558 managed entries):
  - **changed:** all 29 `io.quarkiverse.flow` entries 1.1.3 → 1.2.0; 31 `io.serverlessworkflow` entries 7.32.1.Final →
    7.35.3.Final; `cloudevents-core` / `-json-jackson` 3.0.0 → 5.0.0; `h2-mvstore` 2.4.240 → 2.5.252; `classgraph`
    4.8.194 → 4.8.196. **No `io.quarkus` version changes.**
  - **new:** 48 entries the platform doesn't manage, mostly from the BOM's Quarkus 3.39.0 parent (32 `io.vertx`, 5
    `io.fabric8`, two Quarkus platform descriptors, wiremock, assertj, slf4j-simple). None of them is a dependency of
    the app.
  - **resolved test-scope dependencies** (562 → 568): the Flow, serverlessworkflow and cloudevents bumps above, plus
    `quarkus-scheduler` (+ `-api`, `-common`, `-kotlin`, `-spi`) and `cron-utils` 9.2.1, which
    `quarkus-flow-persistence-common` 1.2.0 needs for its re-scan job.
  - `dependency:tree`: quarkus-flow, -langchain4j, -jpa, -opentelemetry and persistence-common on 1.2.0;
    quarkus-langchain4j 1.14.1; Quarkus 3.40.1.
- **Reproducers** ([edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) at
  `1d4d3a5`, unchanged): 1.1.3 → 5 of 5 fail (exit 1); 1.2.0 → 5 of 5 pass (exit 0). The first 1.2.0 attempt passed
  `-Dquarkus.flow.version=1.2.0` on the command line and still failed: Quarkus's bootstrap model showed the 1.1.3
  runtime and deployment jars. Setting the version in the reproducers' `pom.xml` fixed it (now in `PLAN.md` → 2a).
- **Flow status of the extensions is unchanged:** `quarkus-flow` and `-jpa` are stable, `-langchain4j` and
  `-opentelemetry` are still Preview.
- **Tests** (2026-10-09, CI's exact `clean verify` flags with stub keys, plus `-Dquarkus.http.test-port=0`; the shell's
  `NODE_ENV` unset):

  | Profile | Run | Failures | Errors | Skipped | Intake classes (22) |
  |---|---|---|---|---|---|
  | `-Pollama` | 315 | 10 | 0 | 1 | 146 run, 0 failed |
  | `-Pollama-openai` | 315 | 10 | 0 | 2 | 146 run, 0 failed |
  | default | 315 | 10 | 1 | 1 | 146 run, 0 failed |

  - **The 10 failures are the same three Playwright UI classes on every profile** (`ClaimImagesPageTests` 2,
    `ClaimsDetailPageTests` 4, `ClaimsListPageTests` 4; e.g. `correctTable`: "Expected size: 6 but was: 0"). On the base
    commit (`1b232cc`, a throwaway worktree, `-Pollama`, the same three classes) the **same 10 test names** fail, and
    they pass in #235's CI (both jobs green). A local-environment problem, not this change; CI is the check for them.
  - **The default-profile error** is `NotificationServiceTests.emailSendsWhenUserExists`, which calls the real OpenAI
    API with the stub key (`AuthenticationException: Incorrect API key provided: change-me`). CI doesn't run the
    default profile.
