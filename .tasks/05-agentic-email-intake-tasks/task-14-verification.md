# Task 14: Verify the Email Intake

**Type:** Verification

## Goal

Confirm the feature compiles and its test suites pass with stub keys, get an independent review across
code, tests and docs, and hand the real-key and live-demo checks to the user.

## What to Do

- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run `OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`. Every intake test and the Roundcube E2E must pass.
  Report pre-existing secret-dependent failures separately.
- Have a **fresh** reviewer check:
  - each rule in task 08 has a test
  - no agent has side effects
  - no transaction is open across an LLM call, including when a workflow resumes
  - every customer-submission case gets a reply (the "Every submission gets a reply" list in `PLAN.md`)
  - no agentic scope rows leak (in the app or in tests)
  - the outcome of the human-review decision gate (task 01/07) is recorded in `PLAN.md`
  - the policy-inconsistency email leaks nothing
  - auto-reply loop protection
  - one connected trace per email, including across `@ParallelAgent`
  - the review-decision span links to the original trace
  - no high-cardinality metric tags (claim numbers, `Message-ID`s, addresses)
  - Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`
  - `MonitoredAgent` is capped outside dev
  - docs match the code

  Re-review after fixes.
- Give the user:
  - `./mvnw -B clean verify` with real keys
  - the dev-mode demo walk-through from the demo guide
  - the cluster deployment check

## Files/Areas

- Whole repository

## Key Points

- Locally, skip `-Pollama-openai` (CI covers it). The E2E must still be CI-safe under both profiles.

## Done When

- [ ] Both `test-compile` runs succeed, and the `-Pollama` verify passes apart from documented secret-dependent failures.
- [ ] The fresh review has no unresolved BLOCKER or MAJOR findings.
- [ ] `PLAN.md` lists the user's remaining manual checks.