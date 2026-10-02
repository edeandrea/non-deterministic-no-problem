# Task 14: Verify the Email Intake

**Type:** Verification

## Goal

Confirm the feature compiles and its test suites pass with stub keys, get an independent review across
code, tests and docs, and hand the real-key and live-demo checks to the user.

## What to Do

- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run `OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`. Every intake test and the Roundcube E2E must pass.
  Report pre-existing secret-dependent failures separately.
- Make sure these verification tests exist and pass (add any that earlier tasks didn't):
  - **Restart test:** a run suspends under one `@TestProfile`, and a test under a **different** profile (a fresh
    Quarkus app) resumes it through `POST …/review-decisions`, without calling the earlier agents again. The test
    Postgres dev service is recreated when the profile changes, so use a database that survives the switch: a
    shared container or a fixed JDBC URL for both profiles (preferred), or carry the scope row across as the spike
    did (spike Q11b).
  - **Sub-agent mocks:** tests that `@InjectMock` a sub-agent run under their **own** test profile, because a
    sub-agent instance may be bound when the root is built (spike Q2).
  - **No leaked scope rows:** a test (or a suite-level check after all intake tests) asserts the agentic scope
    table is empty, apart from runs a test deliberately leaves waiting for review and then cleans up.
- Have a **fresh** reviewer check:
  - each rule in task 08 has a test
  - no agent has side effects
  - every AI agent carries both opt-outs, every entry agent is injected in `src/main`, and every leaf belongs to one root
  - no transaction is open across an agent call, including when a workflow resumes
  - every customer-submission case gets a reply (the "Every submission gets a reply" list in `PLAN.md`)
  - the matched claim wins; one "no matching claim" template; reviewer-ticked items gate completeness
  - the policy check runs after the agents and evicts the run state on rejection
  - the scope is evicted on every ending, and no agentic scope rows leak (in the app or in tests)
  - a reply during review and a decision are serialised by optimistic locking (race test)
  - the outcome of the human-review decision gate (task 01/07) is recorded in `PLAN.md`
  - the policy-inconsistency and no-matching-claim emails leak nothing
  - auto-reply loop protection
  - one connected trace per email, including across `@ParallelAgent`, with spans from the root `@AgentListenerSupplier`
  - the review-decision span links to the original trace
  - one claim = one `gen_ai.conversation.id` across its separate traces (each email and the review decision), and
    the id is a UUID with no PII (no claim number, `Message-ID` or address)
  - no high-cardinality metric tags (claim numbers, `Message-ID`s, addresses)
  - Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`
  - `MonitoredAgent` is dev-only: no agent monitor exists outside dev
  - docs match the code, and the design doc's status line is updated

  Re-review after fixes.
- Give the user:
  - `./mvnw -B clean verify` with real keys
  - the dev-mode demo walk-through from the demo guide
  - the cluster deployment check (single replica)

## Files/Areas

- Whole repository

## Key Points

- Locally, skip `-Pollama-openai` (CI covers it). The E2E must still be CI-safe under both profiles.

## Done When

- [ ] Both `test-compile` runs succeed, and the `-Pollama` verify passes apart from documented secret-dependent failures.
- [ ] The restart test (database surviving the profile switch), the dedicated sub-agent `@InjectMock` profile and the no-leaked-scope-rows test exist and pass.
- [ ] The fresh review has no unresolved BLOCKER or MAJOR findings.
- [ ] `PLAN.md` lists the user's remaining manual checks.
