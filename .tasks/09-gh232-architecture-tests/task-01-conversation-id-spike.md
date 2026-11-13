# Task 01: Spike: Attach the Conversation Id from `ai.scoring`, With No Intake Code

**Type:** Spike (throwaway code; findings only)

## Goal

Find out, with evidence, whether an `ai.scoring.conversation.flow` adapter can give every span of an intake run its
claim's conversation id **without the intake referencing `ai.scoring` or OTel baggage**. Write the answer to
`spike-results.md` in this directory, then stop for a decision with the user. No production code lands. Throwaway code
lives on a scratch branch and is deleted.

## Background

Today the intake enters the conversation itself:
- `ClaimIntakeDispatcher.startRun` wraps the instance creation and `instance.start()` in
  `ConversationContext.runIn(run.conversationId(), …)`;
- `ReviewRecoverer.handOver` does the same with `callIn`.

That puts `gen_ai.conversation.id` in OTel baggage before Flow captures the context. Flow 1.3.0 carries the captured
context across its task threads and into the Flow-generated agentic sub-workflows. `IntakeSpanPropagationTests` pins
that, two concurrent runs included. Everything that reads the id reads it from baggage:
- `ai.scoring.conversation.ConversationIdSpanProcessor` copies it onto every span;
- quarkus-langfuse's `LangfuseSpanProcessor.onStart` reads it for the session id.

The chat side already works the way we want: `ai.scoring.conversation.chat.ConversationalBaggageHandler` observes
chat-scope events, and the app never calls `ConversationContext`. This spike looks for the Flow equivalent.

## What to Do

Answer each question with the class or method that proves it (Flow 1.3.0 / serverlessworkflow sdk-java 7.35.3 sources,
or a scratch test).

### 1. Where does Flow capture the OTel context, and can an adapter get in first?

The capture is `io.quarkiverse.flow.internal.FlowContextPropagator` (SPI, `capture()` → `Snapshot.activate()`),
implemented by `quarkus-flow-opentelemetry`'s `OTelFlowContextPropagator`. Find exactly when `capture()` runs relative to
`instance(…)`, `start()` and the listeners. Try the candidates in this order:

1. **`io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener.onWorkflowStarted`.** Which thread does it run on,
   and does it run before the capture? An adapter can't leave a `Scope` open across calls, so a listener only works if
   Flow captures *after* it, on the same thread. Check `OTelWorkflowExecutionListener` too: it opens `workflow.execute`.
2. **`io.serverlessworkflow.impl.WorkflowDecorator`.** What `T` does Flow decorate, how is a decorator registered, and
   is any decoration point before the capture?
3. **Wrapping or replacing `FlowContextPropagator`.** It's in an `internal` package. Acceptable **only** as a documented
   workaround with an upstream quarkus-flow issue asking for a public hook, the precedent being `FlowSpanTypingProcessor`
   and quarkiverse/quarkus-flow#1122.
4. **No baggage at all.** A listener records run → conversation id, and a span processor stamps every span of that run.
   Check:
   - whether every span of a run (tasks, agents, LLM calls, JDBC, the agentic sub-workflows) can be tied to the run, for
     example by trace id, and whether two concurrent runs ever share a trace;
   - whether Langfuse still groups a claim's traces into one session from the span attribute alone, without
     `LangfuseSpanProcessor`'s baggage read.

   `IntakeLangfuseDeliveryTests` asserts one session across two traces.

### 2. What is the contract with the app?

- Can the adapter read `conversationId` from the run's input (`WorkflowInstanceData.input()`, a `WorkflowModel`), and
  only for runs that carry one? Every Flow run passes through the adapter, including the agentic ones and any other
  workflow added later.
- Does Flow let the caller attach **instance metadata** at `instance(…)` time (`WorkflowInstanceData.metadata()` /
  `findMetadata`)? If so, compare it with the input convention: which is clearer, and which is less likely to change?
- Whichever wins, the convention has to be documented in one place (the adapter's Javadoc and `CLAUDE.md`).

### 3. Does it cover every way a run starts or resumes?

- `ClaimIntakeDispatcher.startRun`, the normal path and `IntakeRun.resumingReply`.
- `ReviewRecoverer.handOver` (a faulted run's review handed to a new run; `IntakeRun.resumingReview`).
- `ReviewReclaimer` on startup, through `ReviewRecoverer.reclaim`.
- A run woken from `waitReview` by a reviewer's decision. That runs on another thread after a `listen`; check the id is
  still there, as it is today.
- The decision republish loop (`ClaimReviewService`) doesn't start runs, so it's out of scope, but confirm.

### 4. Prove it on the real tests

On the scratch branch, remove `ConversationContext` from `ClaimIntakeDispatcher` and `ReviewRecoverer` and wire in the
candidate. Then:
- `IntakeSpanPropagationTests` and `IntakeTracingTests` (bare `%test` group) pass unchanged, two concurrent runs
  included;
- `IntakeLangfuseDeliveryTests` (`LangfuseExportTestProfile`) passes unchanged.

## Files/Areas

- Flow 1.3.0 (`quarkus-flow`, `quarkus-flow-opentelemetry`) and `serverlessworkflow-impl-core` 7.35.3: read the
  sources from `~/.m2`.
- `src/main/java/org/parasol/intake/dispatch/ClaimIntakeDispatcher.java` (`startRun`) and
  `src/main/java/org/parasol/intake/workflow/review/ReviewRecoverer.java` (`handOver`).
- `src/main/java/ai/scoring/conversation/ConversationContext.java`, `ConversationIdSpanProcessor.java`, and
  `ai/scoring/conversation/flow/FlowSpanTypingProcessor.java` (the existing Flow adapter, and its documented reliance on
  Flow's naming).
- `src/test/java/org/parasol/intake/observability/IntakeSpanPropagationTests.java`, `IntakeTracingTests.java` and
  `IntakeLangfuseDeliveryTests.java`.

## Key Points

- **The decision this spike feeds is the user's.** If only candidate 3 works, or none does, stop and bring the evidence
  back. Don't fall back to an allow-listed `ConversationContext` without asking.
- **The core stays framework-free.** Whatever is built goes in `ai.scoring.conversation.flow`, the adapter package,
  never in `ai.scoring.conversation` itself (`ConversationLayeringTests`).
- **Flow's own persistence emits about 58 root traces per run**, which carry no conversation id today. Keep it that way:
  don't spread the id onto traces that aren't the run's.
- **Prefer a public hook over `internal`.** The user contributes to quarkus-flow (#1095), so a missing public hook is
  something to file upstream, with the issue text drafted in `spike-results.md`.

## Done When

- [ ] `spike-results.md` answers sections 1–4, each with evidence (class/method, or a scratch-test result).
- [ ] It recommends one mechanism, with the contract (input field or metadata) and its known limits.
- [ ] If the recommendation relies on an `internal` API, it includes a draft upstream issue.
- [ ] The scratch branch is deleted; nothing lands on the feature branch.
- [ ] The user has chosen how task 02 proceeds; record the decision under Key Decisions in `PLAN.md`.
