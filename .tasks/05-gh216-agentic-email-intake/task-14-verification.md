# Task 14: Verify the Email Intake

**Type:** Verification

## Goal

Confirm the feature compiles and its test suites pass with stub keys, get an independent review across
code, tests and docs, and hand the real-key and live-demo checks to the user.

**Check first: quarkus-flow 1.2.0** (`PLAN.md` → Execution Steps → 2a; expected ~2026-10-09). If it's out, re-run the
reproducers and re-adjust this task and the earlier ones before building. Here: re-check #1056, #1057 and #1058 against the Flow version the app then pins.

## What to Do

- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- Run `OPENAI_API_KEY=change-me ./mvnw -B clean verify -Pollama`. Every intake test and the Roundcube E2E must pass.
  Report pre-existing secret-dependent failures separately.
- Make sure these verification tests exist and pass (add any that earlier tasks didn't):
  - **No restart test:** durable state across restarts isn't a requirement (user decision, 2026-10-07).
  - **Sub-agent mocks:** tests that `@InjectMock` a sub-agent run under their **own** test profile, because a
    sub-agent instance may be bound when the root is built (spike Q2).
  - **No leaked Flow rows:** a test (or a suite-level check after all intake tests) asserts Flow's instance and task
    tables are empty, apart from runs a test deliberately leaves waiting for review and then cancels.
  - **Full agent path on every build:** at least one test runs a real email through the starter, the workflow and the
    agents (WireMock LLM), not a mocked root, under both Ollama profiles.
- Have a **fresh** reviewer check:
  - each rule in task 08 has a test
  - no agent has side effects
  - every AI agent carries both opt-outs, every entry agent is injected in `src/main`, and every leaf belongs to one root
  - no transaction is open across an agent call
  - no step contains observability code; the starter's `ConversationContext.callIn` is the intake's only one
  - the conversation core depends on no adapter, and the adapters don't depend on each other (layering test)
  - every customer-submission case gets a reply (the "Every submission gets a reply" list in `PLAN.md`)
  - the matched claim wins; one "no matching claim" template; reviewer-ticked items gate completeness
  - the policy check runs after the agents and creates nothing on rejection
  - a reply during review cancels the waiting run, and no Flow rows leak (in the app or in tests)
  - a reply during review and a decision are serialised by optimistic locking (race test)
  - the quarkus-flow decisions (task 01b and the follow-up spikes) are recorded in `PLAN.md`, with the upstream issue
    status re-checked for the pinned Flow version (#1056, #1057, #1058) and Quarkus (quarkus#54354)
  - the policy-inconsistency and no-matching-claim emails leak nothing
  - auto-reply loop protection
  - every intake span carries the claim's `gen_ai.conversation.id`, with no mixing under concurrency; the id is a UUID
    with no PII (no claim number, `Message-ID` or address); any remaining gap is the one documented in task 11
  - no high-cardinality metric tags (claim numbers, `Message-ID`s, addresses)
  - Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`
  - `MonitoredAgent` follows the decision recorded in task 11
  - both workarounds (quarkus#54354 guard, agentic adapter) carry their delete conditions
  - docs match the code, and the design doc's status line is updated

  Re-review after fixes.
- Open the final PR, `gh216-email-intake` → `main` (see `PLAN.md` → Task Plan → Branches and pull requests): link every
  task PR, and leave the merge to the user (a merge commit, which needs the `main` ruleset changed for that one PR).
- Give the user:
  - `./mvnw -B clean verify` with real keys
  - the dev-mode demo walk-through from the demo guide
  - the cluster deployment check (single replica)

## Files/Areas

- Whole repository

## Key Points

- Locally, skip `-Pollama-openai` (CI covers it). The E2E must still be CI-safe under both profiles.
- Run the full suite on the profile CI uses (the default), not `-Pollama`: a plain `-Pollama` full run fails to boot
  with an ambiguous `ChatModel` provider. With `NODE_ENV=production` in the shell, Jest is missing and Quinoa's test
  step fails; use `NODE_ENV=development`.
- The suites that need a real OpenAI key (the chat and `LangfuseSessionScoringServiceTests`) are the user's to run;
  they're the only proof that task 10b changed nothing for the chat.

## Done When

- [ ] Both `test-compile` runs succeed, and the `-Pollama` verify passes apart from documented secret-dependent failures.
- [ ] The dedicated sub-agent `@InjectMock` profile, the no-leaked-Flow-rows test and the full-agent-path test exist and pass.
- [ ] The fresh review has no unresolved BLOCKER or MAJOR findings.
- [ ] `PLAN.md` lists the user's remaining manual checks.
