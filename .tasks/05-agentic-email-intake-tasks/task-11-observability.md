# Task 11: Observability for the Intake Workflow

**Type:** Code Modification

## Goal

Every email the intake processes produces one connected trace, with log lines that carry its trace id
and business metrics. The traces go to LGTM and Langfuse, and the logs and metrics go to LGTM.
Reviewer decisions link back to the trace of the run they resume. Tests assert all of this.

## What to Do

- **Root span per email:**
  - The watcher (task 09) starts a span `claim-intake process` for each message (`setNoParent()`,
    kind `CONSUMER`) and makes it current for the whole run.
  - **Attributes:**
    - `gen_ai.operation.name=invoke_agent`. Without a `gen_ai.*` attribute, Langfuse's `AI_ONLY` filter can drop the span.
    - the email classification, the claim number (if any), the resulting status, the reply template
    - the `Message-ID` as a span attribute only, never as a metric tag
  - **Errors:** record exceptions and set the status. A suspended run (`ResultWithAgenticScope.suspended()`) is a normal outcome, not an error.
  - **Trace context:** store it on the claim (new column, e.g. `intakeTraceparent`) whenever the run suspends for review.
- **Spans for orchestration agents:**
  - Add a CDI `AgentListener` bean (Quarkus registers it on every agent). It creates `invoke_agent <agent name>`
    child spans for composite agents (`@SequenceAgent`, `@ConditionalAgent`, `@ParallelAgent`) and non-AI agents
    (including `ClaimReviewAgent`), and records errors.
  - Suspension becomes a span event (`onAgenticSystemSuspended`), not an error.
  - Leaf `@Agent` interfaces already get `langchain4j.aiservices.<Agent>.<method>` and `completion <model>` spans. Don't duplicate them.
- **Context propagation for `@ParallelAgent`:**
  - Supply an executor that carries the trace context (`@ParallelExecutor` returning `Context.taskWrapping(executor)`, or a
    global `ExecutorProvider` if the spike in task 01 recorded that). `ExecutorProvider` is `@Experimental` upstream.
  - Without this, each parallel sub-agent's spans end up in a separate trace.
- **Mail spans:** wrap IMAP fetch/move and SMTP send (the reply sender) in spans of kind `CLIENT`, with
  `messaging`/`server.address` attributes. Never put addresses or email bodies on spans.
- **Linking the resumed run:**
  - `ClaimReviewService.decide` (task 07) starts a span `claim-intake review-decision`.
  - It carries a **span link** to the stored `intakeTraceparent` (`SpanContext.createFromRemoteParent`), plus the decision as an attribute.
  - The resumed agent spans nest under it.
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
- **Dev UI agent monitor:**
  - The root intake agent interface extends `MonitoredAgent`, which feeds the Dev UI's Topology and Executions pages.
  - It also records in prod, keeping full inputs and outputs in memory. So add an `@UnlessBuildProfile("dev")`
    startup bean that calls `agentMonitor().setMaxRetainedSessions(0)`, and confirm that it actually stops retention.
- **Langfuse:**
  - Intake LLM calls **are** scored by the tier-1 Langfuse judge. That was the user's decision, so don't change the evaluation rule.
  - Turn off Langfuse span export in tests: set `'%test'.quarkus.langfuse.otel.enabled: false` in `application.yml`
    (build-time; it removes only the `LangfuseSpanProcessor`, so the SDK, the dev service and `LangfuseOperations` keep working).
  - Re-enable it with `quarkus.langfuse.otel.enabled=true` in `LangfuseSessionScoringServiceTests`' `SessionScoringTestProfile`,
    the only test that needs exported spans.
  - Side effect to note: with the processor off, `gen_ai.conversation.id` isn't copied from baggage onto spans in tests.
- **Tests** (test-scope `io.opentelemetry:opentelemetry-sdk-testing`, managed by the Quarkus BOM):
  - Register a test-scope CDI `SpanProcessor` bean, `SimpleSpanProcessor.create(InMemorySpanExporter)`. Every
    `SpanProcessor` bean is added to the tracer provider. Reset it in `@BeforeEach`.
  - **Spans:**
    - one email produces exactly one trace containing the root span, the composite agent spans, the leaf AI-service spans and the mail spans
    - the three parallel sub-agents share the root's trace id
    - a failed run records the exception and an error status
    - a suspended run has a suspension event and no error status
    - the review-decision span links to the stored trace context
  - **Metrics:** the counters and timers increment with the expected tags for:
    - a complete claim, an incomplete claim, a not-a-claim email
    - an auto-reply skip, a duplicate, a failure
    - a review decision
  - The pending-review gauge reflects the database.
  - **Logs:** log lines from inside a run carry the root trace id (a captured log handler).
  - **`MonitoredAgent`:** outside dev, the monitor keeps no sessions.
  - **Langfuse:** with the default test config, `LangfuseSpanProcessor` is absent, and `LangfuseSessionScoringServiceTests` still passes.

## Files/Areas

- `src/main/java/org/parasol/intake/observability/` (new: `IntakeMetrics`, `IntakeAgentListener`, the context-propagating executor, span helpers)
- `src/main/java/org/parasol/intake/` (watcher, processor and reply sender instrumentation)
- `src/main/java/org/parasol/intake/review/ClaimReviewService.java` (review span + link)
- `src/main/java/org/parasol/model/claim/Claim.java` (`intakeTraceparent`)
- `src/main/resources/application.yml` (`%test` Langfuse switch)
- `src/test/java/ai/scoring/langfuse/session/LangfuseSessionScoringServiceTests.java` (profile override)
- `src/test/java/org/parasol/intake/observability/` (new tests), `pom.xml` (`opentelemetry-sdk-testing`, test scope)

## Key Points

- `%drift` disables the OTel SDK entirely (`quarkus.otel.sdk.disabled: true`), so no spans exist there. That's expected; don't "fix" it.
- Langfuse receives traces only. Logs and metrics go to LGTM over OTLP (`%openshift`: `http://lgtm:4318`).
- Grafana panels for these metrics are part of the separate "Clean up the Grafana AI dashboard" issue, not this task.
- Keep instrumentation out of the agents themselves (they stay side-effect free). The processor, listener and services own it.

## Done When

- [ ] One email yields one connected trace (root, composite agents, leaf AI services, mail), including across `@ParallelAgent`.
- [ ] Resuming a review produces a span linked to the original trace.
- [ ] Every `IntakeMetrics` meter exists and increments as specified.
- [ ] Intake log lines carry the trace id.
- [ ] Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`, which still passes.
- [ ] `MonitoredAgent` feeds the Dev UI in dev and keeps nothing outside dev.
- [ ] All the tests listed above pass under `-Pollama`.