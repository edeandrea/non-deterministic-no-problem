# Task 06: Verify the Upgrade

**Type:** Verification

## Goal

Confirm the upgraded build compiles under both Maven profiles. Get an independent review of the
changes, and hand the secret-dependent checks to the user with exact commands.

## What to Do

- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Optionally run `OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`. Report results while
  separating real failures from secret-dependent ones (embedding-dimension mismatch, guardrail
  rewritten-output errors, Langfuse-key failures).
- Have a **fresh** reviewer (no shared context with the implementer) check the diff:
  - every inventory "bump" row is applied, and nothing marked "skip" was touched
  - no doc still states an old version
  - there are no internal contradictions between docs
  Re-review after any fix.
- Give the user the exact commands to run with real keys:
  - `./mvnw -B clean verify` (default profile). This is the run that exercises the Langfuse-backed tests.
  - `./mvnw -B clean verify -Pollama`
  - a manual smoke test in `quarkus:dev`: open a claim, chat, and ask to update a claim status so an email is sent.

## Files/Areas

- Whole repository (read-only, except fixes the review finds)

## Key Points

- Locally, skip `-Pollama-openai`. CI still runs it.
- Only compilation is a trustworthy signal from an agent-run build. Don't chase secret-dependent failures.

## Done When

- [ ] Both `test-compile` runs succeed.
- [ ] The fresh review reports no unresolved BLOCKER or MAJOR findings.
- [ ] `PLAN.md` lists the commands and manual smoke steps the user must run.