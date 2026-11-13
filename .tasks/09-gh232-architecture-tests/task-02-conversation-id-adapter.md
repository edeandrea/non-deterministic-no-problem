# Task 02: Build the Flow Adapter and Take `ConversationContext` Out of the Intake

**Type:** Refactoring + Feature

## Goal

Every span of an intake run still carries its claim's conversation id, but now the `ai.scoring.conversation.flow`
adapter chosen in task 01 attaches it. The intake has **no** reference to `ai.scoring` and none to
`io.opentelemetry.api.baggage`.

## What to Do

1. **Design first.** Present the adapter class(es) and every changed intake file in full, and wait for approval.
2. **The adapter** goes in `ai.scoring.conversation.flow`, next to `FlowSpanTypingProcessor`.
   - It uses the mechanism and contract task 01 settled.
   - Its Javadoc states the contract (for example "a run whose input has a string `conversationId`"), the Flow
     behavior it relies on, and what happens to runs without one: nothing.
   - If it relies on an `internal` Flow API, follow the `FlowSpanTypingProcessor` precedent: a documented workaround, a
     linked upstream issue, and a "delete when" note.
3. **The intake:**
   - remove `ConversationContext` from `ClaimIntakeDispatcher` (constructor, field, the `runIn` around the run start)
     and from `ReviewRecoverer` (`callIn` in `handOver`);
   - keep the code inside those blocks unchanged;
   - keep `conversationId` on `IntakeRun`, since it's the contract now. Say so where `IntakeRun` documents it.
4. **Tests:**
   - `ClaimIntakeDispatcherStartFailureTests` builds `new ConversationContext()`, so drop it.
   - `TestSpans` statically imports `ConversationContext.CONVERSATION_ID_KEY`. Test code isn't held to rules 1–3, but
     prefer `GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID` there, so the intake's tests don't depend on
     `ai.scoring` either.
   - Add adapter tests in `src/test/java/ai/scoring/conversation/flow/`, following `FlowSpanTypingProcessorTests`.
     Cover a run with a conversation id, a run without one, and two concurrent runs that don't mix.
5. **`ConversationContext`'s API.** The chat adapter still uses `currentId`/`enter`. Decide whether `runIn`/`callIn`
   are still used anywhere. If not, remove them, or keep them as public API with a reason, and record the call in
   `PLAN.md`.

## Files/Areas

- `src/main/java/ai/scoring/conversation/flow/` (new adapter)
- `src/main/java/org/parasol/intake/dispatch/ClaimIntakeDispatcher.java`
- `src/main/java/org/parasol/intake/workflow/review/ReviewRecoverer.java`
- `src/main/java/org/parasol/intake/workflow/IntakeRun.java` (Javadoc only)
- `src/main/java/ai/scoring/conversation/ConversationContext.java` (maybe)
- `src/test/java/org/parasol/intake/dispatch/ClaimIntakeDispatcherStartFailureTests.java`, `src/test/java/org/parasol/intake/TestSpans.java`
- `src/test/java/ai/scoring/conversation/flow/` (new tests)
- Docs: `CLAUDE.md` → *Conversation core*. Its "Task 11 needed no adapter" and "Adapters: `chat`" are no longer true, and
  the package table's `ai.scoring` notes need updating. Also `docs/design/email-claim-intake.md` and any diagram that
  shows the intake entering a conversation.

## Key Points

- **The guards are the existing tests, unchanged.** `IntakeSpanPropagationTests` (two concurrent runs included),
  `IntakeTracingTests` and `IntakeLangfuseDeliveryTests` must pass without edits to their assertions. Editing an
  assertion to make it pass is a finding to raise, not a fix.
- **`ConversationLayeringTests` must still pass.** The adapter may reference Flow; the core may not, and adapters may
  not reference each other.
- **No new `@TestProfile`.** The adapter tests either fit the bare `%test` group or are plain unit tests.
- Log through `Log` in a CDI bean, and through a JBoss `Logger` field in a non-bean (the user's convention, enforced in
  task 07).

## Done When

- [ ] `grep -rn "ai.scoring\|io.opentelemetry.api.baggage" src/main/java/org/parasol/intake` finds nothing.
- [ ] `IntakeSpanPropagationTests`, `IntakeTracingTests` and `IntakeLangfuseDeliveryTests` are green with their
      assertions untouched.
- [ ] The adapter has its own tests, including concurrent runs.
- [ ] `ConversationLayeringTests` is green.
- [ ] `CLAUDE.md` and the intake design doc describe the adapter and the `conversationId` contract.
- [ ] `./mvnw -B clean verify -Pollama` is green.
