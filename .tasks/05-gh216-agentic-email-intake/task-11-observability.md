# Task 11: Observability for the Intake Workflow (Flow and Agentic Adapters)

**Type:** Code Modification

## Goal

Every span of a claim's intake runs carries the claim's `gen_ai.conversation.id`, so Langfuse shows the claim as one
session. Flow's spans are typed so Langfuse renders them, log lines carry the trace id, and the business metrics
exist. Traces go to LGTM and Langfuse; logs and metrics go to LGTM. Tests assert all of this.

*Rewritten after the Flow verdict and follow-up spikes 2–4 (`spike-results-flow.md`).* This task builds the
**quarkus-flow** and **LangChain4j agentic** adapters on task 10b's generic conversation core. Gone:
- the synthetic `CONSUMER` root span (Flow's `workflow.execute` / `task.execute` spans replace it)
- the `@AgentListenerSupplier` span tree
- `@ParallelExecutor` (quarkus-flow#1057)
- the separate review-decision trace and its span link: the decision steps run inside the claim's own run (D15)

**What one run looks like today** (quarkus-flow#1056, F4): several traces, not one. These are the main workflow, one
per generated agent sub-workflow, and one per AI-service call, plus one per Flow persistence statement. Joining the
trace trees is upstream's fix. This task makes them **one conversation**, not one trace.

## The conversation id

*Unchanged from the merged design.*
- **A per-claim UUID, not the claim number.** The claim number doesn't exist when the first email's spans start
  (generated on insert, #213), and the attribute is copied onto spans **at span start**. A UUID also keeps PII out of
  the attribute, and matches the chat's chat-scope UUIDs.
- The starter mints it for any email with no matched claim (also a wrong sender, or a seeded claim with none) and
  enters it with `ConversationContext.callIn` (task 08). A run that creates a claim persists it as
  `intakeConversationId`, and follow-ups and the decision reuse it. Emails that never create a claim keep their fresh
  id, which groups just their own spans.
- Langfuse maps `gen_ai.conversation.id` to the session id (langfuse/langfuse#7738). `langfuse.session.id` and
  `session.id` would take precedence, so don't set them.
- `gen_ai.conversation.id` alone doesn't make a span an AI span for Langfuse's `AI_ONLY` filter
  (`FilteringAISpanExporter.isGenAiSpan` ignores it); see the Flow span typing below.

## What to Do

- **Flow adapter** (`ai.scoring.conversation.flow`; depends on the core and quarkus-flow, never on the intake):
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
- **The gap: the generated agent sub-workflows.** `FlowPlanner.firstAction` starts each one with
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
  `ConversationContext.callIn(...)` (task 08).
- **Mail spans:** wrap IMAP fetch/move and SMTP send in `CLIENT` spans with `messaging`/`server.address` attributes, in
  `ClaimsMailbox` and the reply sender. Never put addresses or email bodies on spans.
- **Flow persistence spans:** about 58 separate root traces per run (`INSERT/UPDATE/DELETE/SELECT` on Flow's tables),
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
    calls under the sub-workflows, and the mail spans. The sub-workflows' own Flow spans are the accepted gap: pin it
    exactly (those spans carry **no** id), so the test fails (and gets updated) once #1056 is fixed.
  - **8 concurrent runs:** each id appears on exactly its own run's spans, and no trace mixes ids. That's the quarkus#54354
    regression end to end, and the proof for the agentic adapter.
  - a claim's first email, a follow-up and the review decision share one id; a different claim gets a different id; a
    not-a-claim email gets its own id, and nothing is persisted
  - `workflow.execute` / `task.execute` spans carry `gen_ai.operation.name=invoke_agent`
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
- Don't assert on a cancelled run's `workflow.execute` span: quarkus-flow#1058 drops it.
- Grafana panels for these metrics are #218, not this task.
- Each leaf agent belongs to one root, so the agentic listener only ever sees the intake's agents.

## Done When

- [ ] The Flow adapter's task proxy makes every call task's span current; no step contains observability code.
- [ ] The agentic adapter is proven by the concurrent test, or the alternatives were presented to the user.
- [ ] The sub-workflow span gap is handled by the option the user chose, and a test pins it.
- [ ] Every intake span carries the claim's `gen_ai.conversation.id`, and the concurrent test shows no mixing.
- [ ] Flow spans are typed for Langfuse; the persistence-span decision is made and applied.
- [ ] Every `IntakeMetrics` meter exists and increments as specified; log lines carry the trace id.
- [ ] The root extends `MonitoredAgent`; outside dev mode retention is 0 (launch-mode check at startup); the nothing-retained-after-wait test passes.
- [ ] Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`, which still passes.
- [ ] All the tests listed above pass under `-Pollama`.
