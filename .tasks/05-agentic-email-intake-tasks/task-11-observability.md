# Task 11: Observability for the Intake Workflow

**Type:** Code Modification

## Goal

Every email the intake processes produces one connected trace, with log lines that carry its trace id
and business metrics. The traces go to LGTM and Langfuse, and the logs and metrics go to LGTM.
Reviewer decisions link back to the trace of the run they resume. All the traces for one claim (each email, and the
review decision) stay separate but share one `gen_ai.conversation.id`, so Langfuse shows the claim as one session.
Tests assert all of this.

## What to Do

- **Root span per email** (opened by `ClaimEmailProcessor`, task 08):
  - A span `claim-intake process` per message (`setNoParent()`, kind `CONSUMER`), current for the whole run.
  - **Attributes:**
    - `gen_ai.operation.name=invoke_agent`. Without a `gen_ai.*` attribute, Langfuse's `AI_ONLY` filter drops the
      span; with it, Langfuse types the root as AGENT (spike Q16d). Optionally `langfuse.observation.type=chain`.
    - the email classification, the claim number (if any), the resulting status, the reply template
    - the `Message-ID` as a span attribute only, never as a metric tag
  - **Errors:** record exceptions and set the status. A suspended run (`AgenticSystemSuspendedException`, caught by
    the processor) is a normal outcome, not an error.
  - **Trace context:** stored on the claim (`intakeTraceparent`) whenever the run pauses for review (task 08).
- **Spans for every agent** (gap 3; spike Q16c):
  - A static **`@AgentListenerSupplier`** on the root `ClaimsMailboxAgent` returns an `IntakeAgentListener` whose
    `inheritedBySubagents()` returns `true`. It is **not** a CDI `AgentListener` bean: a CDI listener is only wired
    into AI leaf agents, with before/after callbacks only.
  - In `beforeAgentInvocation` it starts (and makes current) an `invoke_agent <agent name>` span with
    `gen_ai.operation.name=invoke_agent` and `gen_ai.agent.name`; in `afterAgentInvocation` /
    `onAgentInvocationError` it ends it (recording the error). A per-thread stack of `(span, scope)` is enough:
    before and after of one invocation run on the same thread, parallel leaves included.
  - It covers the root, the composites (`@SequenceAgent`, `@ConditionalAgent`, `@ParallelAgent`), the non-LLM agents
    (router, unmatched-email agent, `ClaimReviewAgent`, the decision router) and the AI leaves.
  - **On suspension** (`onAgenticSystemSuspended`, raised once per composite level), add a span event and **end
    every span still open** for that run: the enclosing composites get no "after" callback.
  - Leaf AI services keep their own `langchain4j.aiservices.<Iface>.<method>` and `completion <model>` spans
    (`gen_ai.operation.name=chat` → GENERATION; tools `execute_tool` → TOOL). Don't duplicate them.
  - Metrics that only concern AI leaves may use a CDI `AgentListener`; the span tree must not.
- **Context propagation for `@ParallelAgent`:** every `@ParallelAgent` declares the static `@ParallelExecutor`
  returning `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` (task 05; spike Q17). No global
  `ExecutorProvider`. Verify the parallel sub-agents and their log lines stay in the root trace.
- **Mail spans:** wrap IMAP fetch/move and SMTP send (the reply sender) in spans of kind `CLIENT`, with
  `messaging`/`server.address` attributes. Never put addresses or email bodies on spans.
- **Linking the resumed run:**
  - `ClaimReviewService.decide` (task 07) starts a span `claim-intake review-decision` (SERVER, in a **new** trace).
  - It carries a **span link** to the stored `intakeTraceparent` (`SpanContext.createFromRemoteParent`), plus the decision as an attribute.
  - The resumed agent spans nest under it (only the agents that run again get spans).
- **Conversation grouping (user decision):** every interaction for one claim stays a **separate trace**, and all of
  them are grouped under one conversation with the OTel GenAI attribute `gen_ai.conversation.id`. Langfuse maps it to
  the trace's session id (langfuse/langfuse#7738; `langfuse.session.id`/`session.id` would take precedence, so don't set them).
  - **The value is a per-claim conversation id, a UUID, not the claim number:**
    - The claim number doesn't exist yet when the first email's spans start (generated on insert, #213), and the
      attribute is copied onto spans **at span start**, so it can't be added afterwards.
    - A UUID keeps PII out of the attribute and matches the chat (chat-scope UUIDs).
    - The processor mints it at the start of processing any email with **no matched claim** (also a wrong sender, or
      a seeded claim with no `intakeConversationId`; task 08).
    - If the run creates a claim, the UUID is persisted on the claim (new column `intakeConversationId`,
      `intake_conversation_id`). It's **nullable** and intake-only: claims not created by the intake (the seeded
      claims) have none, so a follow-up on one mints a fresh id.
    - Follow-ups (matched claim) and the reviewer's decision read it from the claim and reuse it (tasks 07, 08).
    - Emails that never create a claim (not a claim, no matching claim, policy rejected) keep their freshly minted
      id, which simply groups a single trace.
    - The claim number stays a span attribute on the root span, for searching.
  - **Mechanism: OTel baggage, as in the chat.** `ai.scoring.conversation.ConversationalBaggageHandler` puts
    `gen_ai.conversation.id` into `Baggage.current()` and makes that `Context` current, and quarkus-langfuse's
    `LangfuseSpanProcessor.onStart` copies it from the parent context's baggage onto every span. For the intake:
    - Before the root span `claim-intake process` starts, and before the `claim-intake review-decision` span starts,
      make a `Context` carrying the baggage entry current (try-with-resources; closed when the run ends).
    - Baggage lives in the OTel `Context`, so the `@ParallelExecutor`'s `Context.taskWrapping(...)` carries it onto
      the parallel sub-agent threads too.
    - The chat's `ConversationalBaggageHandler` is driven by chat-scope events, so the intake doesn't reuse it; add a
      small intake helper (e.g. `IntakeConversationContext`) instead.
  - **Project-owned span processor:** a CDI `SpanProcessor` bean (e.g. `ConversationIdSpanProcessor`) copies the
    baggage `gen_ai.conversation.id` onto each span at start, if present. It's needed because:
    - `%test` sets `quarkus.langfuse.otel.enabled: false`, which removes `LangfuseSpanProcessor`, so nothing else
      would copy it and the tests couldn't assert it
    - Tempo/LGTM shouldn't depend on the Langfuse processor being present

    It writes the same value as the Langfuse processor, so there's no conflict. It also applies to the existing chat
    spans (same value as the Langfuse processor already writes there), which is harmless.
  - **The span link stays:** the review-decision trace still links to the exact paused run's trace (`intakeTraceparent`).
  - `gen_ai.conversation.id` alone doesn't make a span an AI span for Langfuse's `AI_ONLY` filter
    (`FilteringAISpanExporter.isGenAiSpan` ignores it), so custom root spans keep `gen_ai.operation.name=invoke_agent`.
- **Business metrics:** add an `IntakeMetrics` bean (Micrometer `MeterRegistry`, programmatic):

  | Type | Name | Tags |
  |---|---|---|
  | counter | `claim.intake.emails` | `outcome` (classification, plus `skipped_auto_reply`, `duplicate`, `failed`) |
  | timer | `claim.intake.processing` | `outcome` |
  | counter | `claim.intake.claim.status.transitions` | `status` |
  | counter | `claim.intake.replies` | `template` |
  | counter | `claim.intake.review.decisions` | `decision` |
  | timer | `claim.intake.review.wait` | — (time from entering `Pending Review` to the decision) |
  | gauge | `claim.intake.reviews.pending` | — (counted in its own transaction, or cached and refreshed on changes) |
  | counter | `claim.intake.failures` | `stage` (`fetch`, `agent`, `persist`, `reply`, `move`) and `exception` class simple name |
  | gauge | `claim.intake.watcher.connected` | — |
  | counter | `claim.intake.watcher.reconnects` | — |

  Never use claim numbers, `Message-ID`s or email addresses as tags (unbounded number of series).
- **Logs:**
  - Every intake log line written while a span is current carries `traceId`/`spanId` in the MDC.
  - Check that OTel log export picks them up outside `%test`.
  - If the console format doesn't show the trace id, decide with the user whether to add `%X{traceId}`, and record the decision.
- **`MonitoredAgent` is dev-only (user decision after spike Q18).** Prod has **no** agent monitor at all.
  - Why: if the root extends `MonitoredAgent`, an `AgentMonitor` exists in every mode and keeps full inputs
    (raw email) and outputs in memory. `setMaxRetainedSessions(0)` only stops retention of completed runs;
    suspended runs stay in `ongoingExecutions` until resumed, and a review that's evicted without a resume
    (superseded, policy-rejected, abandoned) leaks forever. So the old "`@UnlessBuildProfile("dev")` bean calling
    `setMaxRetainedSessions(0)`" approach is **dropped**.
  - **The mechanism is still to be designed here**, and must respect **one root per leaf** (a second, dev-only
    root sharing the leaf agents is not allowed: shared leaves leak listeners and get their ids mangled).
    Investigate first, then present the options to the user before implementing. Candidates to evaluate:
    - not extending `MonitoredAgent`, and adding an `AgentMonitor` to the root's listener supplier only when
      `LaunchMode.current() == DEVELOPMENT` (check whether `AgentMonitor` can be registered that way, and whether
      the Dev UI Topology and Executions pages still find it)
    - a dev-only source set or build-time switch that changes the root interface
    - no monitor in any mode (drop the Dev UI pages for the intake), if neither works
  - Record the chosen mechanism in `PLAN.md`. Whatever is chosen, prove that outside dev mode no `AgentMonitor`
    exists for the intake root.
- **Langfuse:**
  - Intake LLM calls **are** scored by the tier-1 Langfuse judge. That was the user's decision, so don't change the evaluation rule.
  - Turn off Langfuse span export in tests: set `'%test'.quarkus.langfuse.otel.enabled: false` in `application.yml`
    (build-time; it removes only the `LangfuseSpanProcessor`, so the SDK, the dev service and `LangfuseOperations` keep working).
  - Re-enable it with `quarkus.langfuse.otel.enabled=true` in `LangfuseSessionScoringServiceTests`' `SessionScoringTestProfile`,
    the only test that needs exported spans.
  - With the processor off, the project-owned `ConversationIdSpanProcessor` (Conversation grouping, above) still copies
    `gen_ai.conversation.id` from baggage onto spans, so tests can assert it.
  - Observation types come from `gen_ai.operation.name` on the Langfuse server: `invoke_agent` → AGENT, `chat` →
    GENERATION, `execute_tool` → TOOL. JDBC scope-store spans carry no `gen_ai.*` and reach LGTM only.
- **Tests** (test-scope `io.opentelemetry:opentelemetry-sdk-testing`, managed by the Quarkus BOM):
  - Register a test-scope CDI `SpanProcessor` bean, `SimpleSpanProcessor.create(InMemorySpanExporter)`. Every
    `SpanProcessor` bean is added to the tracer provider. Reset it in `@BeforeEach`.
  - **Spans:**
    - one email produces exactly one trace containing the root span, the `invoke_agent` spans for every composite,
      non-LLM and AI agent, the leaf AI-service spans and the mail spans
    - the three parallel sub-agents share the root's trace id
    - a failed run records the exception and an error status
    - a suspended run has a suspension event, no error status, and **no span left open**
    - the review-decision span is in a new trace and links to the stored trace context
  - **Conversation grouping** (with the Langfuse processor off, as in `%test`):
    - every span of an email's trace carries the claim's conversation id, including the parallel sub-agent spans
    - two emails for the same claim (first email + follow-up) and the review decision produce **separate traces**
      with the **same** `gen_ai.conversation.id`
    - an email for a different claim gets a different id
    - a not-a-claim email gets its own id, and nothing is persisted
    - the conversation id never appears as a metric tag
  - **Metrics:** the counters and timers increment with the expected tags for:
    - a complete claim, an incomplete claim, a not-a-claim email
    - an auto-reply skip, a duplicate, a failure
    - a review decision
  - The pending-review gauge reflects the database.
  - **Logs:** log lines from inside a run, including the parallel sub-agents, carry the root trace id (a captured log handler).
  - **`MonitoredAgent`:** outside dev, no `AgentMonitor` exists for the intake root (and nothing is retained after a suspended run).
  - **Langfuse:** with the default test config, `LangfuseSpanProcessor` is absent, and `LangfuseSessionScoringServiceTests` still passes.

## Files/Areas

- `src/main/java/org/parasol/intake/observability/` (new: `IntakeMetrics`, `IntakeAgentListener` and its supplier, span helpers,
  `ConversationIdSpanProcessor`, and the intake conversation-baggage helper)
- `src/main/java/org/parasol/intake/` (watcher, processor and reply sender instrumentation)
- `src/main/java/org/parasol/intake/agent/ClaimsMailboxAgent.java` (`@AgentListenerSupplier`; the dev-only monitor mechanism)
- `src/main/java/org/parasol/intake/review/ClaimReviewService.java` (review span + link)
- `src/main/java/org/parasol/claim/model/Claim.java` (`intakeTraceparent` and `intakeConversationId`, if not already added in task 08)
- `src/main/resources/application.yml` (`%test` Langfuse switch)
- `src/test/java/ai/scoring/langfuse/session/LangfuseSessionScoringServiceTests.java` (profile override)
- `src/test/java/org/parasol/intake/observability/` (new tests), `pom.xml` (`opentelemetry-sdk-testing`, test scope)

## Key Points

- `%drift` disables the OTel SDK entirely (`quarkus.otel.sdk.disabled: true`), so no spans exist there. That's expected; don't "fix" it.
- Langfuse receives traces only. Logs and metrics go to LGTM over OTLP (`%openshift`: `http://lgtm:4318`).
- Grafana panels for these metrics are part of #218 ("Clean up the Grafana AI dashboard"), not this task.
- Keep instrumentation out of the agents themselves (they stay side-effect free). The processor, listener and services own it.
- The listener supplier is inherited by every sub-agent of `ClaimsMailboxAgent`; since each leaf belongs to that root
  only, no other agentic system picks it up.
- Tier-2 session scoring doesn't run for intake conversations (out of scope): `LangfuseSessionScoringService` is
  triggered only by `ChatScopeEnded`, and its prompt is chat-sentiment oriented. The tier-1 judge still scores every
  intake LLM call.

## Done When

- [ ] One email yields one connected trace (root, `invoke_agent` spans for every agent, leaf AI services, mail), including across `@ParallelAgent`.
- [ ] The span listener comes from a root `@AgentListenerSupplier` with `inheritedBySubagents()=true`, and ends every open span on suspension.
- [ ] Resuming a review produces a span in a new trace, linked to the original trace.
- [ ] All traces for one claim share one `gen_ai.conversation.id` (one Langfuse session), while staying separate traces.
- [ ] Every `IntakeMetrics` meter exists and increments as specified.
- [ ] Intake log lines carry the trace id.
- [ ] Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`, which still passes.
- [ ] The dev-only `MonitoredAgent` mechanism is agreed with the user and recorded in `PLAN.md`; outside dev no agent monitor exists.
- [ ] All the tests listed above pass under `-Pollama`.
