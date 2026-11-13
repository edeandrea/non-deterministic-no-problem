# Task 01 Spike Results: Attaching the Conversation Id from `ai.scoring`

**Date:** 2026-11-13, against `gh232-architecture-tests` at `3883ce8` (quarkus-flow 1.3.0, serverlessworkflow sdk-java
7.35.3.Final, quarkus-langfuse 0.9.1).

## Answer

**Yes.** An `ai.scoring.conversation.flow` adapter can give every span of an intake run its conversation id with no
`ai.scoring` or baggage code in the intake, **using public SPIs only**. No `internal` API is needed, so no upstream
issue is required.

Two mechanisms passed the real tests unchanged. The recommendation is the first:

| | Mechanism | Baggage inside steps? | Result |
|---|---|---|---|
| **A (recommended)** | A `CallableTaskProxyBuilder` puts the id in baggage around each call task's body, and a span processor stamps Flow's own spans by `flow.workflow.instance.id` | Yes | All three test classes pass |
| B | No baggage: a span processor stamps every span of the run's trace | No | All three test classes pass |

## 1. Where Flow captures the OTel context

There is **no single capture at `instance(…)` or `start()`** to get in front of. The root run's context comes from two
places in `quarkus-flow-opentelemetry`:

- **`OTelWorkflowExecutionListener.onWorkflowStarted`** reads `Context.current()` on the thread that called `start()`,
  starts `workflow.create` and `workflow.execute` from it, and keeps it as the run's `parentContext`
  (`InstrumentationContext`). Every `task.execute` span is built from that stored context (`doTaskStartedOrRetried`).
  Today's baggage rides in it, which is why wrapping `start()` in `ConversationContext.runIn` works.
- **`OTelTaskSpanProxy`** (a `CallableTaskProxyBuilder`, priority 100) makes `taskSpan.storeInContext(parentContext)`
  current around `CallableTask.apply` for `call: Java` tasks. That is how a step's AI, JDBC and mail spans, and the
  agentic sub-workflows it starts, join the run's trace.

`start()` runs synchronously on the caller's thread (`WorkflowMutableInstance.start` → `LifecycleEventsUtils.publishEvent`
→ `WorkflowExecutionListenerAdapter`, which calls a plain listener inline). Listeners run in ascending `priority()`
order (`WorkflowApplication.Builder` sorts them; the default is 1000, which the OTel listener uses).

The candidates, in the task's order:

1. **`WorkflowExecutionListener.onWorkflowStarted`: rejected from the sources, not run.** A listener with a lower
   priority does run before the OTel listener on the same thread, but the only way to change what the OTel listener
   reads is to leave a `Scope` open when the callback returns and close it from a later callback. Nothing guarantees the
   later callback runs on that thread (a `WorkflowExecutionCompletableListener` may complete on another one), and a
   leaked scope puts one run's id on a pool thread. A listener is still useful for **recording** which instance has
   which id (used in A and B).
2. **`WorkflowDecorator`: not applicable.** Its only use in sdk-java 7.35.3 is `EmittedEventDecorator extends
   WorkflowDecorator<CloudEventBuilder>`: it decorates emitted CloudEvents, nothing else.
3. **`FlowContextPropagator`: not needed, and it wouldn't be enough.** Only `FlowPlanner` (quarkus-flow-langchain4j)
   calls it, for the hop from a task to a generated agentic workflow. The root run never passes through it.
4. **No baggage (B): works.** See below.
5. **Found while reading: `io.serverlessworkflow.impl.executors.CallableTaskProxyBuilder` (A).** A public sdk-java SPI
   loaded with `ServiceLoader`, the same one quarkus-flow-opentelemetry and Flow's fault tolerance use. Proxies wrap in
   ascending priority order (`CallTaskExecutorBuilder.buildInstance`), so one with a priority below 100 is innermost: it
   runs inside the OTel proxy's scope, adds the baggage to the context that proxy made current, and closes its own scope
   when `apply` returns. It's a properly scoped `try`, with nothing left open.

### Mechanism A, as spiked

Three small classes in `ai.scoring.conversation.flow`, plus one `META-INF/services` entry:

- **A task proxy** (`CallableTaskProxyBuilder`, priority 50, no CDI): reads the id from
  `workflowContext.instanceData()` (the input in the first run, instance metadata in the second); if there is one, wraps `delegate.apply(…)` in a scope whose context is
  `Context.current()` plus the baggage entry. Runs without an id are passed straight through.
- **A listener** (`@ApplicationScoped WorkflowExecutionListener`, priority 0; Flow binds such beans itself, as it does
  `ClaimIntakeDispatcher`): on `onWorkflowStarted` records instance id → conversation id, before the OTel listener
  starts `workflow.create`; removes it on completed, failed and canceled.
- **A span processor**: on `onStart`, a span whose `flow.workflow.instance.id` is a recorded instance gets
  `gen_ai.conversation.id`. This covers `workflow.create`, `workflow.execute` and every `task.execute` of the run,
  which the OTel listener builds from its stored context and so never see the proxy's baggage.

Everything else is unchanged: `ConversationIdSpanProcessor` and quarkus-langfuse's `LangfuseSpanProcessor.onStart` keep
reading the baggage for the spans started inside a step. The agentic sub-workflows need nothing: they start inside a
task body, so the OTel listener captures a context that already has the baggage.

### Mechanism B, as spiked

The same listener and processor, no proxy. The processor also records trace id → conversation id when it stamps a Flow
span, and stamps every later span of that trace.

- **Every span of a run can be tied to it by trace id:** each run's spans are in one trace, and no trace held two ids
  (`IntakeSpanPropagationTests`' census assertions, eight concurrent runs included).
- **Langfuse groups by the span attribute alone:** `LangfuseSpanProcessor.onStart` only copies the baggage entry to
  `gen_ai.conversation.id` and delegates; the session comes from that attribute on the exported span.
  `IntakeLangfuseDeliveryTests` (one session across two traces) passes with no baggage anywhere.

### Why A over B

- B stamps by trace, so it's only right while a run owns its trace. A run started inside another trace (from an HTTP
  request, say) would put its id on that request's later spans, and two runs in one trace would share the first one's
  id. The intake doesn't do that today; a reusable library can't assume it.
- B needs a trace map with no natural end: spans of a trace can start after its run has ended.
- With B, `ConversationContext.currentId()` is empty inside a step, and nothing propagates on outgoing calls. A keeps
  the baggage contract that the chat side and quarkus-langfuse already use.

## 2. The contract with the app

- **First spiked, then replaced: the run's input carries a `conversationId`.** `WorkflowInstanceData.input()` is a `WorkflowModel`; started in this
  JVM, `asJavaObject()` is the `IntakeRun` record itself. The spike read `asMap().get("conversationId")` first and fell
  back to the record component of that name. A run whose input has neither (the agentic sub-workflows, whose input is
  the agentic scope, or any other workflow) is left alone: the eight-run test's "ids outside run traces" and "mixed
  traces" assertions pass.
- **Instance metadata is the reflection-free contract, and the one chosen (user, 2026-11-13).**
  `WorkflowDefinition.instance(Object input)` takes no metadata, but the public `WorkflowInstance` interface it returns
  has `addMetadataIfAbsent(String key, Supplier<T>)`, and `WorkflowInstanceData.findMetadata(key, String.class)` reads
  it back. No cast is needed; an earlier version of this file said one was. The app would add the id between
  `instance(run)` and `start()`, and the adapter would read it with a typed call. Metadata lives in memory and is
  cleared when the run ends (`WorkflowMutableInstance.cleanUp`), which is enough: the adapter only needs it while the
  run is alive, and a run replaced after a restart is a new instance.
  **Proven in a second scratch run:** with the adapter reading only `findMetadata("gen_ai.conversation.id", String.class)`
  and the two start paths each calling `instance.addMetadataIfAbsent("gen_ai.conversation.id", …)`,
  `IntakeSpanPropagationTests` (4), `IntakeTracingTests` (2), `IntakeLangfuseDeliveryTests` (1) and
  `ClaimIntakeDispatcherStartFailureTests` (1) all pass.
- **The convention needs documenting in one place:** the adapter's Javadoc and `CLAUDE.md`. It is a runtime coupling
  no ArchUnit rule sees (already listed under Caveats in `PLAN.md`).

## 3. Every way a run starts or resumes

| Path | Covered? | Why |
|---|---|---|
| `ClaimIntakeDispatcher.startRun`, normal and `IntakeRun.resumingReply` | Yes | `flow.instance(run)` + `instance.start()`; the listener fires inside `start()`, the proxy reads the instance's id at every task |
| `ReviewRecoverer.handOver` (`resumingReview`) | Yes | Same two calls; `aFaultedRunRecordsItsExceptionAndCarriesItsId` covers the replacement run |
| `ReviewReclaimer` → `ReviewRecoverer.reclaim` | Yes | Goes through `handOver` |
| A run woken from `waitReview` | Yes | The decision steps are call tasks, so the proxy applies on whatever thread wakes them; the test's after-wake-up assertions pass |
| `ClaimReviewService` republish loop | Out of scope, confirmed | It only publishes events; `instance(` and `start()` appear nowhere outside the dispatcher and `ReviewRecoverer` |

## 4. Proof on the real tests

On a scratch branch (deleted), `ConversationContext` was removed from `ClaimIntakeDispatcher` and `ReviewRecoverer`
(and from `ClaimIntakeDispatcherStartFailureTests`' constructor call, the only test change). Run with
`./mvnw test -Pollama-openai` and stub keys, as CI does:

| Run | `IntakeSpanPropagationTests` (4) | `IntakeTracingTests` (2) | `IntakeLangfuseDeliveryTests` (1) |
|---|---|---|---|
| A: proxy + instance stamping | pass | pass | pass |
| B: trace stamping, no proxy | pass | pass | pass |
| Control: A without the proxy | **4 fail** | **1 fails** | not run |
| Control: A without the processor | **4 fail** | not run | not run |

The two controls show the tests really depend on each half of A: without the proxy the AI, JDBC and mail spans lose
the id ("spans in a run's trace without exactly its id", "mail spans in the run's trace"); without the processor Flow's
own spans do. `ConversationLayeringTests` also passes with the adapter in place.

## Recommendation

Build **mechanism A** in task 02, with the instance-metadata contract (no reflection).

Known limits, for task 02 to settle or document:

- **The proxy's priority is tied to `OTelTaskSpanProxy.PRIORITY` (100, package-private).** It must stay below it; above
  it, the OTel proxy's context replaces the baggage. Document it, and the propagation tests fail if Flow changes it.
- **The metadata key is a string both sides agree on** (`gen_ai.conversation.id`). The intake can't import
  `ai.scoring`'s constant, so it holds its own, and a run started without the call silently gets no id. Keep the call
  in one place in the intake, and document the key on both sides.
- **`ServiceLoader` registration** (`META-INF/services/io.serverlessworkflow.impl.executors.CallableTaskProxyBuilder`)
  was proven in tests only. sdk-java loads it with the thread context class loader; check it once in dev mode.
- **The instance map** is cleared on completed, failed and canceled. An instance that is created but never started
  never enters it.
- **Spans started outside a task body and without Flow's instance attribute** (work a listener does after a run
  faults, for example) get no id. That is the same as today.
- **Optional upstream idea, not a blocker:** a public quarkus-flow hook to contribute to the context a run is started
  with would turn the proxy and the processor into one mechanism.

The spike's diff is not kept in the repository. Task 02 writes the classes fresh, to the project's standards.
