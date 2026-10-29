# Task 11: Observability for the Intake Workflow (Flow and Agentic Adapters)

**Type:** Code Modification

**Issue:** [#260](https://github.com/edeandrea/non-deterministic-no-problem/issues/260)

## Goal

Every span of a claim's intake runs carries the claim's `gen_ai.conversation.id`, so Langfuse shows the claim as one
session. Flow's spans are typed so Langfuse renders them, log lines carry the trace id, and the business metrics
exist. Traces go to LGTM and Langfuse; logs and metrics go to LGTM. Tests assert local span/log/metric behavior and actual
Langfuse ingestion/session grouping. **Scope clarified during T8 review:** LGTM backend delivery is not asserted by this
suite; Tempo/Loki/Prometheus delivery remains unverified. The Langfuse Dev Services test targets the custom `AI_ONLY`
filtering and span-typing/session behavior, not standard LGTM exporter wiring.

*Rewritten after the Flow verdict and follow-up spikes 2–4 (`spike-results-flow.md`).* This task builds the
**quarkus-flow** and **LangChain4j agentic** adapters on task 10b's generic conversation core. Gone:
- the synthetic `CONSUMER` root span (Flow's `workflow.execute` / `task.execute` spans replace it)
- the `@AgentListenerSupplier` span tree
- `@ParallelExecutor` (quarkus-flow#1057, fixed in 1.2.0; still not wanted: Flow carries the context itself since #1065)
- the separate review-decision trace and its span link: the decision steps run inside the claim's own run (D15)

**What one run looked like on Flow 1.1.3** (quarkus-flow#1056, F4): several traces, not one. These are the main
workflow, one per generated agent sub-workflow, and one per AI-service call, plus one per Flow persistence statement.

**Check first (2026-10-09): quarkus-flow 1.2.0 is adopted (task 06b), and it contains
[#1065](https://github.com/quarkiverse/quarkus-flow/pull/1065), the #1056 fix.** It makes the task span current in
every task body (`OTelTaskSpanProxy`, on by default, `quarkus.flow.otel.task-span-current`), carries the caller's
context into the generated agent sub-workflows (`FlowPlanner.firstAction`), and activates the requesting task's
context on the agent's thread (`FlowAgentContextListener`). It propagates the whole OTel `Context`, so baggage should
ride along. The #1056 reproducer passes on 1.2.0 (one run: 24 spans in 1 trace, against 6 traces on 1.1.3).
- **Proven (2026-10-27, Flow 1.3.0 probe):** `IntakeSpanPropagationTests` with 8 concurrent runs proved that baggage
  `gen_ai.conversation.id` reaches **every** span of a run out of the box with **zero adapters**:
  - `FLOW_RUN`: 100% own id (104 of 104 in concurrent batch, 57 of 57 in claim/decision test, 4 of 4 after wake-up)
  - `FLOW_SUB_WORKFLOW`: 100% own id (104 of 104 in concurrent batch, 46 of 46 in claim/decision test)
  - `AI_UNDER_SUB_WORKFLOW`: 100% own id (96 of 96 in concurrent batch, 39 of 39 in claim/decision test)
  - `JDBC_PARENTED`: 100% own id (1417 of 1417 in concurrent batch, 649 of 649 in claim/decision test, 68 of 68 after wake-up)
  - zero missing ids, zero foreign ids, zero stale ids, zero mixed traces across all tests
  - Result: both the Flow `CallableTaskProxyBuilder` and the agentic `AgentListener` adapter are redundant and dropped.
- The log trace id step is unaffected: #1013 (Flow lifecycle logs with the trace) is still open and not in 1.2.0.

## Current Status & Resumption Guide (as of 2026-10-29)

All deliverables are built and in the working tree for the task 11 PR. One item is open: a default-profile `IntakeSpanPropagationTests`
failure seen on the user's machine, whose cause is not found (see "Open" below). The independent review in
[task-11-observability-review.md](task-11-observability-review.md) is fully addressed.

### User Decisions Made
1. **Adapter Gate:** Probe, then stop. User confirmed: *"We should drop both adapters but keep the test as a regression"*.
2. **Flow Persistence Spans:** Keep and document (no sampler, no JDBC telemetry disabling).
3. **Console Log Format:** Leave format unchanged (MDC `traceId` verified in tests; LGTM gets traces via OTel export).

### Deliverables Completed
1. **Span Capture (`TestSpans` & `SpanCensus`):**
   - Replaced old `WorkflowSpans` with `TestSpans` (`src/test/java/org/parasol/intake/TestSpans.java`), a capture that is off by default and started and stopped by `IntakeFlowTestBase`, with `ofRun(runId)`, `ofTrace(traceId)`, and `byConversation(conversationId)`.
   - Implemented `SpanCensus` (`src/test/java/org/parasol/intake/observability/SpanCensus.java`) to categorize spans by kind (`FLOW_RUN`, `FLOW_SUB_WORKFLOW`, `AI_FROM_STEP`, `AI_UNDER_SUB_WORKFLOW`, `JDBC_PARENTED`, `JDBC_ROOT`, `MAIL`, `OTHER`) and id status (`OWN`, `MISSING`, `FOREIGN`, `STALE`, `UNKNOWN`, `OUTSIDE_RUN_TRACE`).
2. **Probe & Regression Suite (`IntakeSpanPropagationTests`):**
   - Verified two batches of 8 concurrent new-claim runs with zero mixed traces and zero missing/foreign/stale ids.
   - Verified that a claim's first email, follow-up, and review wake-up share a single conversation ID, while other claims and non-claim emails get their own distinct IDs.
   - Verified that canceled and faulted runs have their `workflow.execute` spans exported with the conversation ID, with no `gen_ai.operation.name` (the run's own workflow isn't an agent; the generated agentic workflows are `invoke_agent`), and that a faulted run records its exception.
   - Verified that a failed nested agent ends and records its failure at all three generated workflow levels (`claims-mailbox-agent`, `email-router`, `claim-extraction-workflow`).
3. **Flow Span Typing (`FlowSpanTypingProcessor`):**
   - Implemented `FlowSpanTypingProcessor` (`src/main/java/ai/scoring/conversation/flow/FlowSpanTypingProcessor.java`) stamping `gen_ai.operation.name=invoke_agent` on the `workflow.execute` span of a workflow generated for an agentic interface. `LangfuseFlowSpanTypingProcessor` (`ai.scoring.langfuse.otel`) types `task.execute` spans as `chain`. See "Typing Flow's spans" below.
   - Unit tests in `FlowSpanTypingProcessorTests` and `LangfuseFlowSpanTypingProcessorTests`; layering rules checked by `ConversationLayeringTests`.

4. **Mailbox Concurrency Fix:**
   - `withFolder` in `ClaimsMailbox` (`src/main/java/org/parasol/intake/mailbox/ClaimsMailbox.java`) now uses a fair `ReentrantReadWriteLock`: concurrent reads are allowed, while mutations exclude reads and other mutations. The preceding exclusive policy was introduced after concurrent GreenMail moves stalled in multi-run batches.
   - The follow-up [GreenMail concurrency investigation](task-11-mailbox-concurrency-investigation.md) reproduced distinct-message move failures and read/expunge failures without adequate exclusion. Exclusive and read/write policies each passed 100 repeated rounds; moves-only exclusion and a two-permit semaphore failed. The investigation itself did not change production locking; the original intermittent stall's exact root cause remains open.
   - Final hardened diagnostic against the current `folderLock`: 89 passes and one inconclusive invocation across default, `ollama`, and `ollama-openai`; all six final unsafe-policy controls failed as expected. The diagnostic remains opt-in, not an automatic CI guard. See the investigation's final adaptation verification.
   - Recommendation, not an additional user decision: retain the current fair read/write policy rather than introduce `StampedLock`. Its optimistic mode cannot protect a live IMAP fetch from concurrent expunge; ordinary stamped read/write locking offers no demonstrated benefit and loses fairness and reentrancy. This is API reasoning, not a measured `StampedLock` comparison.
5. **Mail spans, metrics and failure attribution:** `@MailClientSpan` and `MailClientSpanInterceptor` (IMAP and SMTP `CLIENT` spans, no PII; a checked exception also ends the span in error); the OTel context wrapped onto the reply thread; `IntakeActivity` events published through `IntakeActivities` (logs and swallows an observer's failure, so telemetry can't change what the intake does) and observed by `IntakeMetrics`. `EmailHandled` is counted once, when the email is filed. `claim.intake.failures` carries `stage` and `outcome` (`fatal` or `recovered`). The stage comes from the exception: `MailboxMoveException` (`move`), `ReplyFailedException` (`reply`), `ProcessingEmailMissingException` (`fetch`); the package rules cover the rest.
6. **Retention and test switches:** `ClaimsMailboxAgent extends MonitoredAgent`; `AgentMonitorConfigurer` sets retention to 0 outside dev mode. *(Commented out 2026-10-29 until langchain4j/langchain4j#6885 and #6913 are released; see the open item's resolution below.)* `%test` leaves the Langfuse span processor out; `SessionScoringTestProfile` and `LangfuseDeliveryProfile` turn it back on (merged into `LangfuseExportTestProfile` by #273).
7. **Langfuse delivery test (`IntakeLangfuseDeliveryTests`):** real Langfuse Dev Services ingestion for the custom `AI_ONLY` filtering, AGENT/CHAIN typing and session grouping across two exports. Tempo, Loki and Prometheus delivery is not covered.
8. **Inbox watcher lifecycle lock (2026-10-29):** `ClaimsInboxWatcher.start()` and `stop()` no longer use `synchronized`; both take a private `ReentrantLock` (`lifecycle`). `stop()` holds it while it waits for the in-flight step, so a `start()` can't begin a session while the previous one drains. Behavior is unchanged. Only `ClaimsInboxWatcherTests` (16 tests) was run after this change; no test starts and stops the watcher concurrently.

### Open
- **Default-profile `IntakeSpanPropagationTests` failure on the user's machine. Cause found 2026-10-29, see "Resolved" below this item.** In the first batch of 8 concurrent runs, 72 spans
  (the three parallel branches' model calls and `langchain4j.aiservices.*` spans for 8 runs) have no conversation id and sit in traces of their
  own; everything else keeps its id. The same 3 failures and 1 error appeared in 3 of 4 default-profile full runs on the user's machine
  (`user-verify.log`, `-2`, `-4`) and in none of the others: a default-profile full run there with the embeddings cache ignored (`-3`), a
  `-Pollama` full run (the class passes), a two-class run (`IntakeMetricsTests`, `IntakeSpanPropagationTests`), and four default-profile
  runs on this side (targeted twice, a wider package run, and a full `clean verify`, with stub keys and a random test port).
  The class order and the boot before the class were the same in a failing and a passing full run. Ruled out by evidence: class order, the
  Langfuse-enabled boot (it runs after this class), `quarkus.langfuse.otel.enabled`, the embeddings cache (`reuse-embeddings.enabled=false`
  only avoids a separate `Length of vector` error from a stale `easy-rag-embeddings.json`), the HTTP port, and `NODE_ENV`.
  **Untested lead:** SmallRye Context Propagation 2.4.0 keeps its providers in a `HashSet`, so the order in which the OTel provider and
  `OtelContextLeakGuard` run on the fork's threads could differ per boot. The guard's comment claims either order is fine; that isn't verified
  for the parallel fork. Testing it means logging the order at boot and running the class without the guard's `META-INF/services` entry.
  Not pursued yet; whether to is the user's call.
  - **Resolved (2026-10-29): not context propagation, the agent monitor.** It failed in CI too (#270's run on `858c71f`, ollama-openai job only).
    Flow runs the parallel extraction agents on LangChain4j's executor (`PlannerBasedInvocationHandler.parallelExecution`) and activates the
    run's OTel context on their threads from `FlowAgentContextListener.beforeAgentInvocation`. The `AgentMonitor` that `extends MonitoredAgent`
    adds sits in the same `ComposedAgentListener`, throws `No ongoing parent invocation found for agent ID: extractClaim` for those agents on
    every run (langchain4j/langchain4j#6885), and the composed listener stops at the first listener that throws. Its listeners are a
    `HashSet`, so the order is fixed for a boot and differs between boots, which is why some boots failed and others didn't. The 72 spans are
    8 runs × 3 agents × 3 spans. The SmallRye Context Propagation lead above was the wrong `HashSet`. Filed upstream as
    langchain4j/langchain4j#6913, with PR #6914 (notify every listener, then rethrow). Until both fixes are released, `extends MonitoredAgent`,
    `AgentMonitorConfigurer` and its test are commented out (user decision 2026-10-29: comment out, don't delete); `PLAN.md` 2c item 6 tracks
    restoring them.


## The conversation id

*Unchanged from the merged design.*
- **A per-claim UUID, not the claim number.** The claim number doesn't exist when the first email's spans start
  (generated on insert, #213), and the attribute is copied onto spans **at span start**. A UUID also keeps PII out of
  the attribute, and matches the chat's chat-scope UUIDs.
- The starter mints it for any email with no matched claim (also a wrong sender, or a seeded claim with none) and
  enters it with `ConversationContext.runIn` (task 08). A run that creates a claim persists it as
  `ClaimIntakeState.intakeConversationId` (not on `Claim`), and follow-ups and the decision reuse it. Emails that never create a claim keep their fresh
  id, which groups just their own spans.
- Langfuse maps `gen_ai.conversation.id` to the session id (langfuse/langfuse#7738). `langfuse.session.id` and
  `session.id` would take precedence, so don't set them.
- `gen_ai.conversation.id` alone doesn't make a span an AI span for Langfuse's `AI_ONLY` filter
  (`FilteringAISpanExporter.isGenAiSpan` ignores it); see the Flow span typing below.

## What to Do

*The Flow and agentic adapters below, and the gap they were meant to close, are superseded by the "Proven (2026-10-27)" result above: Flow 1.3.0 carries the conversation id onto every span with no adapter. They stay as the record of the original plan.*

- **Flow adapter** *(superseded: the context adapter wasn't built; only the span typing below was)* (`ai.scoring.conversation.flow`; depends on the core and quarkus-flow, never on the intake):
  - A `WorkflowApplicationBuilderCustomizer` bean (`io.quarkiverse.flow.recorders`) registering a
    `CallableTaskProxyBuilder` (`builder.withCallableProxy(...)`). It wraps **every** call task, and makes Flow's task
    span current with its parent context around `delegate.apply(...)`:
    `task.getStartSpan().storeInContext(task.getParentContext())`, looked up through
    `WorkflowInstrumentationContext.getWorkflowInstrumentationContext(instanceData).getTaskInstanceContext(...)`.
    Proven in follow-up spike 3 (`Spike4TaskContextProxy`: all 54 call tasks wrapped, traces 56 → 40).
    - Anything a step calls (AI services, persistence, mail, logs) then joins the run's trace and carries the run's
      baggage, **with no code in any step**.
  - A `SpanProcessor` stamping `gen_ai.operation.name=invoke_agent` on `workflow.execute` / `task.execute` spans.
    Without a `gen_ai.*` attribute, Langfuse's `AI_ONLY` filter drops them; with it, Langfuse types them AGENT.
    Optionally add `langfuse.observation.type=chain` on workflow spans.
- **The gap: the generated agent sub-workflows.** *(Superseded: closed by Flow 1.2.0/1.3.0 and measured at 100% own id; the agentic adapter was dropped.)* `FlowPlanner.firstAction` starts each one with
  `CompletableFuture.supplyAsync(instance::start)` and passes no context. Their Flow spans and the AI calls under them
  carry **no** conversation id. That's missing, not wrong: spike 3 measured 0 foreign ids. The real fix is upstream
  (quarkus-flow#1056: capture the context in `firstAction`).
  - **Agentic adapter** (`ai.scoring.conversation.agentic`; depends on the core and langchain4j-agentic only): a CDI
    `AgentListener` bean, which reaches the AI leaves (spike Q16c), so it covers the AI calls under the sub-workflows.
    - In `beforeAgentInvocation`: if an id is current (`ConversationContext.currentId()`) and the agentic scope has
      none, write it to the scope under a reserved key. If none is current, enter the one from the scope.
    - Close the scope in `afterAgentInvocation` / `onAgentInvocationError`, on a per-thread stack. LangChain4j calls
      both on the same thread, parallel branches included (spike 2).
    - **Proven by follow-up spike 5** (`Spike5AgenticConversationAdapter`): 8 concurrent id'd runs plus no-id runs.
      Every AI call under the sub-workflows is stamped (32 of 32), with 0 mixing, 0 foreign ids, and 0 stale ids in a
      following no-id batch. It relies on the first leaf (the classifier) running where the run's context is current.
      That held even without the task proxy, because `runAgents` calls the root on the task thread.
    - It writes only that one key, and never reads business state.
  - **The sub-workflows' own Flow spans: accept the gap until #1056 ships, and document it.** That's 7 per run: the
    two generated `workflow.execute` spans and their `task.execute` spans. Their AI and HTTP spans *are* stamped.
    The listener option (enter the scope's id at a low priority, close it at a high one) was **invalidated** in
    spike 5. Flow runs each listener priority group as a separate future stage, so enter and exit ran on different
    threads (42 of 42), and other runs' ids leaked onto spans.
  - **Delete the agentic adapter** once #1056's fix makes baggage reach the sub-workflows. The concurrent test shows it.
- **No intake code in either adapter.** The intake's only observability-related line is the starter's
  `ConversationContext.runIn(...)` (task 08).
- **Mail spans:** wrap IMAP fetch/move and SMTP send in `CLIENT` spans with `messaging`/`server.address` attributes, in
  `ClaimsMailbox` and the reply sender. Never put addresses or email bodies on spans.
  - **`IntakeReplySender` runs every send on `ForkJoinPool.commonPool()`** *(superseded: it now runs on a new virtual thread with the OTel context wrapped onto it)* (task 08: the reactive mailer stalls when it's
    blocked on a thread with a captured Vert.x context). That thread has no OTel context, so a send span started on the
    caller's thread wouldn't parent the SMTP work and the baggage id wouldn't reach it. Carry the context across
    explicitly (`Context.current().wrap(runnable)`) and check it still avoids the stall.
- **Flow persistence spans** *(decided: keep and document, no sampler)*: about 58 separate root traces per run (`INSERT/UPDATE/DELETE/SELECT` on Flow's tables),
  noise in Tempo and Langfuse. Find the cheapest way to drop them: e.g. a sampler or exporter filter on those statement
  names, or a JDBC-telemetry switch for Flow's datasource if one exists. Present it to the user.
- **Business metrics:** an `IntakeMetrics` bean (Micrometer `MeterRegistry`, programmatic). Flow already provides
  `quarkus.flow.workflow.started.total` / `.completed.total` / `.duration` (D16), so don't duplicate run counts or
  durations:

  | Type | Name | Tags |
  |---|---|---|
  | counter | `claim.intake.emails` | `outcome` (classification, plus `skipped_auto_reply`, `duplicate`, `failed`) |
  | counter | `claim.intake.claim.status.transitions` | `status` |
  | counter | `claim.intake.replies` | `template` |
  | counter | `claim.intake.review.decisions` | `decision` |
  | timer | `claim.intake.review.wait` | — (from entering `Pending Review` to the decision) |
  | gauge | `claim.intake.reviews.pending` | — (Flow has no waiting gauge; count `Pending Review` claims) |
  | counter | `claim.intake.failures` | `stage` (`fetch`, `agent`, `persist`, `reply`, `move`) and `exception` simple name |
  | gauge | `claim.intake.watcher.connected` | — |
  | counter | `claim.intake.watcher.reconnects` | — |

  Increment them through `IntakeMetrics` from the existing steps, the starter and the watcher. Never tag with claim
  numbers, `Message-ID`s, addresses or conversation ids (unbounded series).
- **Logs:** intake log lines written while a span is current carry `traceId`/`spanId` in the MDC. The task proxy makes
  that true inside steps. Check OTel log export outside `%test`. If the console format doesn't show the trace id,
  decide with the user whether to add `%X{traceId}`, and record the decision.
- **`MonitoredAgent`: keep it, alongside Flow's Dev UI** (user decision 2026-10-07, after spike 5's screenshots).
  - The root (`ClaimsMailboxAgent`) extends `MonitoredAgent`. The agentic Dev UI shows what Flow's can't: the branch
    taken, inputs and outputs, tokens, and tool calls with their arguments and results.
    - *From task 06:* it doesn't extend it yet; adding `extends MonitoredAgent` and the retention observer is this
      task's.
  - The old dev-only decision was driven by suspended `@HumanInTheLoop` runs staying in `ongoingExecutions`. Under
    Flow the agent call ends inside `runAgents`, before the run waits. Spike 5 measured 0 ongoing with 3 runs waiting
    for review.
  - **Retention: keep finished sessions only in dev mode** (user decision 2026-10-07).
    - **Why:** `AgentMonitor` keeps the last 100 sessions per outcome by default (`DEFAULT_MAX_RETAINED_SESSIONS`).
      Each email is its own session (the root has no `@MemoryId`), and each one holds the correspondence text the
      agents received. Only the agentic Dev UI reads the monitor, and that exists only in dev mode. Langfuse covers
      prod.
    - **How:** a startup observer (`@Observes StartupEvent`) on the intake side. When
      `LaunchMode.current() != LaunchMode.DEVELOPMENT`, it calls `agentMonitor().setMaxRetainedSessions(0)` on the
      root. Dev mode keeps the default of 100. Executions still sit in `ongoingExecutions` while a run is in flight,
      and are dropped when it ends.
    - **Why not a bean switch:** the monitor isn't a CDI bean. LangChain4j creates it itself whenever the root
      interface extends `MonitoredAgent` (`AbstractServiceBuilder`), and that's fixed at compile time. A second,
      dev-only root would break one root per leaf. A plain `AgentMonitor` listener bean wouldn't show in the Dev UI,
      which only registers roots that are `instanceof MonitoredAgent` (`AgenticRecorder`).
    - **Why launch mode, not profile:** dev runs don't activate the `dev` profile. The pom sets `quarkus.profile`
      (e.g. `ollama,prod`), and spike 5's `quarkus:dev` logged `Profiles prod,ollama activated. Live Coding
      activated.` So `@IfBuildProfile("dev")` or `%dev:` config would be wrong there.
  - Respect one root per leaf.
  - **Test** (test mode, so retention is 0): after a run that reaches the review wait,
    `ongoingExecutions()`, `successfulExecutions()` and `failedExecutions()` are all empty.
- **Langfuse:**
  - Intake LLM calls **are** scored by the tier-1 judge (user decision); don't change the evaluation rule.
  - Set `'%test'.quarkus.langfuse.otel.enabled: false` in `application.yml` (build-time; it removes only
    `LangfuseSpanProcessor`, so the SDK, the dev service and `LangfuseOperations` keep working). Re-enable it in
    `LangfuseSessionScoringServiceTests`' `SessionScoringTestProfile`, the only test that needs exported spans. Task
    10b's `ConversationIdSpanProcessor` keeps the id assertable in tests.
  - **Session scoring for intake conversations stays off:** nothing in the intake fires `ConversationEndedEvent`
    (PLAN.md, Observability). The session scorer assumes the chat's shape.
- **Tests** (an in-memory `SpanExporter` behind a test-scope `SpanProcessor` bean, reset in `@BeforeEach`; filter to the
  test's own traces, because other test classes share the exporter):
  - **Every span** of a run carries its conversation id: Flow workflow and task spans, the AI calls from steps, the AI
    calls under the sub-workflows, and the mail spans. *(Superseded: there is no accepted gap, the sub-workflows' own Flow spans carry the id too.)*
  - **8 concurrent runs:** each id appears on exactly its own run's spans, and no trace mixes ids. That's the quarkus#54354
    regression end to end, and the proof for the agentic adapter.
  - a claim's first email, a follow-up and the review decision share one id; a different claim gets a different id; a
    not-a-claim email gets its own id, and nothing is persisted
  - the agentic `workflow.execute` spans carry `gen_ai.operation.name=invoke_agent`, the run's own workflow span carries none, and `task.execute` spans are typed `chain`
  - a faulted run records the exception on its spans
  - log lines from inside a step carry the run's trace id (a captured log handler)
  - **metrics:** the counters and timers increment with the expected tags for a complete claim, an incomplete claim, a
    not-a-claim email, an auto-reply skip, a duplicate, a failure and a review decision. The gauge reflects the
    database, and the conversation id never appears as a tag.
  - with the default test config, `LangfuseSpanProcessor` is absent, and `LangfuseSessionScoringServiceTests` passes

## Files/Areas

- `src/main/java/ai/scoring/conversation/flow/`, `src/main/java/ai/scoring/conversation/agentic/` (new adapters)
- `src/main/java/org/parasol/intake/observability/IntakeMetrics.java` (new); metric calls in the steps, starter and watcher
- `src/main/java/org/parasol/intake/mailbox/` and the reply sender (mail spans)
- `src/main/resources/application.yml` (`%test` Langfuse switch); `LangfuseSessionScoringServiceTests` (profile override)
- `src/test/java/ai/scoring/conversation/flow/`, `…/agentic/`, `src/test/java/org/parasol/intake/observability/`

## Key Points

- Adapters translate one framework's lifecycle into the core and nothing else. The core never imports them, and they
  never import each other or the intake. Task 10b's layering test covers them.
- `%drift` disables the OTel SDK entirely, so there are no spans there. That's expected; don't "fix" it.
- Langfuse receives traces only. Logs and metrics go to LGTM over OTLP (`%openshift`: `http://lgtm:4318`).
- A canceled run's `workflow.execute` span is exported since Flow 1.2.0 (quarkus-flow#1058): assert it, and that it
  carries the run's conversation id.
- Grafana panels for these metrics are #218, not this task.
- The reply templates are the `IntakeTemplates` methods (task 04: `receivedFinalReview`, `claimInProcess`,
  `missingInformation`, `stillMissingInformation`, `processingProblem`, `policyInconsistency`, `notAClaim`,
  `noMatchingClaim`, `whichClaim`), plus the AI-written status answer sent as text. Still open: the
  `claim.intake.replies` `template` values, and where the counter lives given "no observability code in any step".
- Each leaf agent belongs to one root, so the agentic listener only ever sees the intake's agents.
- When an agent throws, quarkus-flow 1.3.0's own fix (quarkiverse/quarkus-flow#1095; adopted in task 09b, which deleted
  the app's workaround) makes every generated agentic run of that invocation **fault**: each level should then export a
  faulted `workflow.execute` span (not verified yet). Check what it logs for each — task 08's deprecated workaround
  logged one "Workflow failed" ERROR per run from `FlowPlanner`; confirm whether 1.3.0's own fix logs at the same level
  or lower.

## Done When

- [x] The Flow adapter's task proxy: dropped (user decision 2026-10-27 after probe showed Flow 1.3.0 carries context natively); no step contains observability code.
- [x] The agentic adapter: dropped (user decision 2026-10-27 after probe showed 100% propagation to AI calls under sub-workflows); concurrent test kept as regression.
- [x] The sub-workflow span gap: 0 gap found on Flow 1.3.0 (all sub-workflow spans carry id); pinned by `IntakeSpanPropagationTests`.
- [x] Every intake span carries the claim's `gen_ai.conversation.id`, and the concurrent test shows no mixing.
- [x] Flow spans are typed for Langfuse (`FlowSpanTypingProcessor`); the persistence-span decision is made (keep and document) and applied.
- [x] Every `IntakeMetrics` meter exists and increments as specified; log lines carry the trace id.
- [x] The root extends `MonitoredAgent`; outside dev mode retention is 0 (launch-mode check at startup); the nothing-retained-after-wait test passes.
- [x] Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`, which still passes.
- [x] All the tests listed above pass under `-Pollama`: full `clean verify`, 558 tests, 0 failures, 0 errors, 2 skipped (drift detection and the opt-in mailbox concurrency diagnostic); frontend Jest: 70 passed, 1 skipped. Verified 2026-10-28, before the #160 workaround removal (below).
- [ ] The same after the #160 removal and the review fixes, on the user's machine, `-Pollama`, 2026-10-29: 558 tests, 1 failure, 2 skipped, 46:36. `IntakeSpanPropagationTests` and every other class in this task passed; the failure is the Playwright UI test `ClaimReviewPanelPageTests.submittingTheEditedClaimMovesItInProcessAndRemovesTheControls` (waiting for "In Process"), which the plan's notes list among the UI classes that fail locally and pass in CI. Not checked against the base commit. Open until CI runs it.
- [ ] Default profile: `IntakeSpanPropagationTests` passes on the user's machine. It failed in 3 of 4 full runs there and passed on every run on this side; see "Open" above. Open until the cause is found or the user decides otherwise.

## Outcome

- No adapter was needed: Flow 1.3.0 and the agentic sub-workflows already carry the OTel context (probe, `IntakeSpanPropagationTests`).
- Added `FlowSpanTypingProcessor` and `LangfuseFlowSpanTypingProcessor` (see "Typing Flow's spans"), `@MailClientSpan` (IMAP and SMTP `CLIENT` spans, no PII), the context wrap on the reply thread, `IntakeActivity` events observed by `IntakeMetrics`, fired through
  the dispatcher, `AgentStep`, `ApplyOutcomeStep`, `ReviewDecisionSteps`, `IntakeFailureHandler`, `ClaimsInboxWatcher` and `IntakeReplySender`, and
  `ClaimIntakeState.reviewOpenedAt` for the review-wait timer.
- `ClaimsMailboxAgent extends MonitoredAgent`; `AgentMonitorConfigurer` (in `org.parasol.intake.agent`) sets retention to 0 outside dev mode.
- `%test` leaves the Langfuse span processor out; `SessionScoringTestProfile` turns it back on.
- Flow's persistence spans (about 58 root traces per run) are kept and not sampled.
- `failures{stage}` is derived from the cause chain: the mailbox and reply sender throw their own exceptions (`MailboxMoveException`, `ReplyFailedException`) and the dispatcher throws `ProcessingEmailMissingException`, so `move`, `reply` and `fetch` name the operation that failed; `agent` and `persist` come from the exception's package, else `unknown`. The `outcome` tag is `fatal` (the run faulted) or `recovered` (a review decision that couldn't be applied, or an acknowledgment that couldn't be sent).
- `claim.intake.emails` counts terminal outcomes once per email (in `FileEmailStep`), including the wrong-sender, resolver-UNSURE and policy-rejection routes.
- Review fixes (see [task-11-observability-review.md](task-11-observability-review.md)): style and line length, typed labels (`IntakeClaimStatus`, `ReviewOutcome`), package-private `IntakeMetrics`, exact-delta failure tests, stronger propagation, tracing and fault assertions, and a bounded span-capture lifecycle.
- `ClaimsInboxWatcher` lifecycle lock: a private `ReentrantLock` instead of `synchronized` methods (2026-10-29).
- Upstream re-check (2026-10-29): see PLAN.md → Execution Steps → 2a–2c findings log. No workaround can be removed yet.
- Not run here: `DriftDetectionTests` (needs real keys); run it manually.

## Typing Flow's spans

**The original issue (before quarkus-langfuse 0.9.1).** Flow's `workflow.execute` and `task.execute` spans carry no `gen_ai.*` attribute. The older `AI_ONLY` filter retained only spans with such an attribute (or their ancestors in the same batch). Since 0.9.1 it also retains `langfuse.observation.*` attributes directly. Langfuse types spans from `langfuse.observation.type`, then `gen_ai.operation.name`; generated agentic workflows still need the standard agent typing described below.

**What should be typed.**
- The generated agentic workflows (`ClaimsMailboxAgent`, `ClaimExtractionWorkflow` and the agent sub-workflows) are agents: `gen_ai.operation.name=invoke_agent`.
- The run's own workflow, `claim-intake`, is a plain Java DSL workflow that calls agents from one step. It isn't an agent.
- A `task.execute` span is a step, usually plain Java. It is a Langfuse `chain`, which isn't a standard GenAI operation, so it is Langfuse-specific.

**Alternatives for telling agentic workflows apart.**
1. *Every `workflow.execute` span* (the first version): wrong for `claim-intake`.
2. *Match by workflow name or namespace:* needs a configured list, or relies on Flow's naming of generated classes (package becomes namespace, class name becomes name).
3. *The workflow definition's metadata* (`io.quarkiverse.flow.invoker.kind`): a span doesn't carry it, so it would need a `WorkflowExecutionListener` that marks agentic runs, plus a processor that reads the mark, a registry cleaned up when runs end, and Flow's `internal` `WorkflowInvocationMetadata`. **Checked (Flow 1.3.0), and ruled out:** the ordering works (a listener's `priority()` below the default 1000 runs in an earlier group, and Flow finishes a group before the next, so it runs before the OTel listener creates the span), but `AgenticFlow` sets the metadata only in dev mode (`invokerMethodName()` is overridden by Gizmo for DevUI only), so it's absent in tests and prod.
4. *The parent span* (the second version): a `workflow.execute` span whose parent is a `task.execute` span is a nested workflow. It tests "nested", not "agentic", so a plain nested workflow would be typed as an agent.
5. *The agent interface, like `AiServiceAttributes`:* Flow names a generated workflow after the interface in kebab case (`flow.workflow.namespace` = `org-parasol-intake-agent`, `flow.workflow.name` = `claims-mailbox-agent`, both on the span). The processor rebuilds `<package>.<Agent>` from them and checks for a declarative agentic annotation on a method (`@SequenceAgent`, `@ParallelAgent`, `@ParallelMapperAgent`, `@ConditionalAgent`, `@LoopAgent`, `@PlannerAgent`, `@SupervisorAgent`), cached per workflow.

**Path chosen: 5, as a stand-in.** It is exact for declaratively built agents (annotation check), needs no shared state or ordering, and treats a top-level agentic run the same as a nested one. Its weaknesses: it relies on Flow's naming (a rename silently stops the stamping, which `FlowSpanTypingProcessorTests` and `IntakeSpanPropagationTests` would catch), and it doesn't see agents built programmatically. `IntakeSpanPropagationTests` pins the result (the `claim-intake` spans carry no operation name, the agentic ones are `invoke_agent`).

**This is a workaround, documented as one in the processor's class comment.** It also stamps `gen_ai.agent.name` (from `flow.workflow.name`), so a backend can label the agent. Its costs: it depends on Flow's kebab-case naming and uses reflection (no good for a native image). Kept for now, to be deleted when #1122 lands.

**Upstream.** [quarkiverse/quarkus-flow#1122](https://github.com/quarkiverse/quarkus-flow/issues/1122) asks Flow to stamp `invoke_agent` and `gen_ai.agent.name` on agentic workflow spans. It notes that the `invoker.kind` metadata is set in dev mode only, so Flow would have to mark agentic workflows in every mode. When it lands, delete the name-based lookup (and with it `FlowSpanTypingProcessor`, if Flow then does all of it).

**The synthetic `chain` operation name was removed with quarkus-langfuse 0.9.1 (#160).** `task.execute` now sets only `langfuse.observation.type=chain`; the `AI_ONLY` filter retains it directly. Unit and propagation assertions require that typing and no `gen_ai.operation.name`. Post-removal tests were not completed: the user stopped the run and requested no further tests. The earlier full Ollama pass predates this removal.

**Where the code lives.** `FlowSpanTypingProcessor` (`ai.scoring.conversation.flow`) holds only what is standard and general. Anything backend-specific, `chain` and `langfuse.observation.type` for `task.execute`, is `LangfuseFlowSpanTypingProcessor` (`ai.scoring.langfuse.otel`). Attribute keys and the `invoke_agent` value come from the OpenTelemetry semantic-conventions constants (`GenAiIncubatingAttributes`) wherever one exists; `chain` has none.
