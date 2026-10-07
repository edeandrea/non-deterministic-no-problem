# Spike Results: quarkus-flow (task 01b)

> **STATUS: COMPLETE. Verdict: ADOPT quarkus-flow for the #216 review gate.**
>
> Every decision criterion is met **empirically**, not on API evidence: the gate passes at Quarkus 3.40.1 /
> quarkus-langchain4j 1.14.1 with no downgrade, A1–A3 and B6 all work, and C8 has a real answer. 14 spike
> tests pass under **both** Ollama profiles.
>
> Nothing is merged or pushed. No production file was modified.

## Environment

- **Main repo at spike start:** `main` @ `8ed467d`, later `7134a9b`. `git status --porcelain` was
  `?? .junie/` and `?? CODE_STANDARDS.md` throughout.
- **Worktree:** `/Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow`, branch
  `spike/flow-hitl` (local only, from `main` @ `8ed467d`). Commits: `c3ab343` (pom patch), `7306867`
  (spike package), `d953680` (Dev UI evidence). **Never pushed.**
- **Java:** Temurin 25.0.4+7. Container runtime: Podman 6.0.2 via `/var/run/docker.sock`.
- **Versions:** Flow **1.1.3** (latest release; **no 1.2.0 final exists** — latest prerelease is
  `1.2.0.CR3`), serverlessworkflow **7.32.1.Final**, quarkus-langchain4j **1.14.1**, langchain4j-agentic
  **1.20.2-beta30**, Quarkus **3.40.1**.
- **Date:** 2026-10-06.

### The pom patch applied in the worktree

`quarkus.platform.version` (3.40.1) and `quarkus.langchain4j.version` (1.14.1) were deliberately left
untouched — that is the whole point of the gate.

```xml
<quarkus.flow.version>1.1.3</quarkus.flow.version>
```

```xml
<!-- after the quarkus-langchain4j-chat-scopes-websocket dependency -->
<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-agentic</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow</artifactId>
  <version>${quarkus.flow.version}</version>
</dependency>
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow-langchain4j</artifactId>
  <version>${quarkus.flow.version}</version>
</dependency>
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow-jpa</artifactId>
  <version>${quarkus.flow.version}</version>
</dependency>
<!-- added mid-spike: OTel is a SEPARATE extension, see D12/D13 -->
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow-opentelemetry</artifactId>
  <version>${quarkus.flow.version}</version>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk-testing</artifactId>
  <scope>test</scope>
</dependency>
```

### Spike code

`src/test/java/org/parasol/spike/flow/` in the worktree. 14 tests, all green:

| Class | Covers |
|---|---|
| `SpikeFlowGateTests` (2) | Gate close-out: boot + run with `@SequenceAgent` present |
| `SpikeFlowHitlTests` (3) | A1, A2, A3, C8, C9, C10 |
| `SpikeFlowSubflowCheckpointTests` (1) | **A4** (the pivotal one) |
| `SpikeFlowPersistenceTests` (4) | B5, B7, E19 |
| `SpikeFlowTracingTests` (1) | D11, D12, D13, D15 |
| `SpikeFlowMetricsTests` (1) | D16 |
| `SpikeRestartPhase1Tests` / `SpikeRestartPhase2Tests` (2) | **B6** — two separate JVMs |

Supporting: `SpikeIntakeAgent` (`@SequenceAgent` root), `SpikeTriageAgent` / `SpikeSummaryAgent` (AI
leaves), `SpikeReviewFlow` (the hand-written `Flow`), `SpikeFlowTestProfile`, `SpikeRestartTestProfile`,
`SpikeSpanExporter`, `SpikeStubs`, `SpikeRestartHandoff`, and the `SpikeIntakeRequest` /
`SpikeReviewRequired` / `SpikeReviewDecision` / `SpikeOutcome` records.

---

## Step 0 — the gate: **PASSED (complete)**

### Versions available (Maven Central, 2026-10-06)

All Flow artifacts publish identical version lines. Latest **release** `1.1.3`; latest **prerelease**
`1.2.0.CR3`. **There is no `1.2.0` final.**

### Version skew confirmed — and harmless

From `quarkus-flow-parent`'s pom at each tag:

| Flow version | `quarkus.version` | `io.quarkiverse.langchain4j.version` | `io.serverlessworkflow.version` |
|---|---|---|---|
| 1.1.3 | 3.39.0 | 1.13.3 | 7.32.1.Final |
| 1.2.0.CR3 | 3.39.0 | 1.13.3 | 7.34.0.Final |

This project is on 3.40.1 / 1.14.1, so the skew is real. **Maven's BOM import wins**, so nothing is
dragged back to 1.13.3 (`dependency:tree` confirms `langchain4j-agentic 1.20.2-beta30` survives).

The coupling is narrow: `quarkus-flow-langchain4j-deployment` scans Jandex itself
(`Lc4jAnnotationScannerUtil`) and references exactly **one** build item, `DetectedAiAgentBuildItem` —
**API-identical** between 1.13.3 and 1.14.1 (`javap -p` on both) — plus one runtime class,
`AgenticSystemTopology`. Its `quarkus-langchain4j-ollama-deployment` dependency is **test**-scoped, so it
does not leak into this app.

### What closed the gate

The earlier `package` run proved nothing about the agentic path, because no `@SequenceAgent` was present.
Adding one and booting a `@QuarkusTest` executes it. Result: **`BUILD SUCCESS`, both tests pass**, and the
build log shows the translation actually happening:

```
Flow: Registered WorkflowDefinition beans
| org.parasol.spike.flow.GeneratedSpikeIntakeAgentFlow |
Registering workflow org-parasol-spike-flow:spike-intake-agent:0.0.1
Task 'triage-0'    started … pos=do/0/triage-0
Task 'summarize-1' started … pos=do/1/summarize-1
```

**One gotcha, mine not Flow's:** the spike agents carry no `@ModelName`, so they use the *unnamed default*
model. Under `-Pollama` both the ollama and openai extensions are installed, and the default provider is
then ambiguous — augmentation fails with:

> `A ChatModel or StreamingChatModel bean was requested, but since there are multiple available providers,
> the 'quarkus.langchain4j.chat-model.provider' needs to be set to one of the available options
> (ollama,openai).`

The project's own config names providers for `parasol-chat`, `generate-email`, `politeness` and
`embedding-model`, but **not** for the unnamed default. Any new WireMock-backed profile that uses the
default model must add `quarkus.langchain4j.chat-model.provider=openai`. This extends the existing
"a mocking profile must pin the provider" rule in CLAUDE.md to the *unnamed* model.

---

## Results

### A1 — `function(...)` + `emitJson` / `listen` / `switchWhenOrElse` in one task list: **YES**

`SpikeReviewFlow` composes exactly the shape the design needs, and the docs never show:

```java
workflow("spike-review").tasks(tasks(
  function("runIntake", this::runIntake, SpikeIntakeRequest.class, SpikeReviewRequired.class),
  emitJson("emitReviewRequired", REVIEW_REQUIRED, SpikeReviewRequired.class),
  listen("waitHumanReview",
    toOne(consumed(REVIEW_DONE).dataAs(SpikeReviewDecision.class, SpikeReviewFlow::isForThisClaim)).first()),
  switchWhenOrElse("routeDecision", SpikeReviewDecision::approved, "approveClaim", "rejectClaim",
    SpikeReviewDecision.class),
  function("approveClaim", SpikeReviewFlow::approve, …).then(FlowDirectiveEnum.END),
  function("rejectClaim",  SpikeReviewFlow::reject,  …).then(FlowDirectiveEnum.END)
)).build()
```

- An `@Inject`ed `@SequenceAgent` bean invoked as `this::runIntake` works; the descriptor can close over
  the injected agent.
- `.then(FlowDirectiveEnum.END)` is needed on both switch branches, otherwise `approveClaim` falls through
  into `rejectClaim`. **`FlowDirectiveEnum` is in `io.serverlessworkflow.api.types`**, not `…impl`.

### A2 — default fallback broker, no messaging module: **YES**

No `EventConsumer`/`EventPublisher` bean and no `quarkus-flow-messaging` on the classpath. Boot log:

```
Flow: No EventConsumer bean found; using default fallback.
Flow: No EventPublisher beans found; using default fallback.
```

The HITL tests wake instances through it, so the fallback genuinely serves `listen`.

### A3 — in-process `publish(...)` wakes it, completed tasks are skipped: **YES**

`workflowApplication.eventPublishers().iterator().next().publish(cloudEvent)`. `SpikeReviewFlow`'s intake
task body increments a counter and the two leaf agents each hit WireMock once, so both sides are measured:

- Before publish: `INTAKE_INVOCATIONS == 1`, 2 `/v1/chat/completions` requests.
- After publish and completion: **still** `1` and **still** `2`.

So the completed `runIntake` is not re-executed on resume. (Note the docs never actually claim this — the
earlier correction stands — but it is now measured.)

### A4 — crash *inside* the agentic subflow: **RESUMABLE. The subflow is independently checkpointed.**

**This is the pivotal question, and the answer is the better of the two.** It was also the one place my
first hypothesis was wrong, so it is worth being precise about the method.

Sampling `task_info_entity` *after* the subflow finishes proves nothing — Flow does not retain completed
instances, so empty tables are expected either way. `SpikeFlowSubflowCheckpointTests` therefore stalls the
**second** leaf agent with a WireMock scenario (`withFixedDelay(15_000)`) and samples the tables while the
first has already returned. Mid-subflow:

```
workflow_instance_entity:
  01M49T1S3Z… spike-review         (parent, blocked in runIntake)
  01M49T1S44… spike-intake-agent   (the generated @SequenceAgent flow)

task_info_entity:
  workflow_instance_id=01M49T1S44…  json_pointer=do/0/triage-0  next_position=do/1/summarize-1
```

So the `@SequenceAgent` is **not** an opaque single task. It runs as its own persisted workflow instance,
concurrently with the parent, and **checkpoints per sub-agent**: the completed first leaf is on disk with
the second leaf recorded as the next position. The state needed to resume *inside* the subflow — rather
than re-running all its LLM calls — exists.

This resolves the docs' self-contradiction: *both* halves are true. The `@SequenceAgent` becomes a real
linear sequence of `call` tasks (its own workflow), **and** the calling workflow invokes it as one task
through the CDI proxy. The parent's `runIntake` is one checkpoint; the child's sub-agents are separate
checkpoints in a separate instance.

**One caveat, not chased:** the parent–child link on *restore* was not exercised. B6 restarts from a parent
suspended at `waitHumanReview` (child already finished), not from a crash mid-subflow. Whether auto-restore
reattaches a half-finished child to its parent's pending `runIntake` is **open** — see Open Questions.

### B5 — JPA entities join the app's persistence unit: **YES**

`quarkus-flow-jpa` rides the app's own datasource; no named persistence unit. `workflow_instance_entity`,
`task_info_entity` and `cloud_event_entity` are created by the app's existing schema management and are
queryable through the injected `EntityManager`. `Installed features` lists
`flow-persistence-common, flow-persistence-jpa`.

### B6 — survives a genuine Quarkus restart: **YES**

Two **separate JVMs** (two surefire invocations) against an externally managed Postgres on port 55432,
because the test Postgres dev service is recreated per run.

- **Phase 1** (`drop-and-create`): starts a review, waits for `WAITING`, asserts 2 chat completions and
  `INTAKE_INVOCATIONS == 1`, writes claim id + instance id to `target/spike-flow-restart.txt`. JVM exits.
- Between phases, the row is verifiably still there:
  ```
   instance_id                | workflow_name
   01M49V7C0XSSJQAJBYDZ26HSQH | spike-review
  ```
- **Phase 2** (`none`, so nothing is dropped): a new JVM boots, `FlowPersistenceRestore` auto-restores,
  `PersistenceInstanceReader.find(...)` reports `WAITING`, a publish wakes it, it completes, and the row
  disappears. Crucially: **`INTAKE_INVOCATIONS == 0` and 0 `/v1/chat/completions` in the new JVM** — the
  work done before the restart was not redone.

**This required overriding schema management**, which is the spike's standing note made concrete: no
current profile would let this work, because they all wipe the schema on boot.

### B7 — `application_id` derivation: **`quarkus.application.name`**

`WorkflowApplicationCreator:140`:

```java
ConfigProvider.getConfig().getOptionalValue("quarkus.application.name", String.class).ifPresent(builder::withId);
```

Observed as `parasol-app` (the artifactId) in the boot log and in the `application_id` column of all three
tables. **`application_id` is part of the primary key of all three tables**, so renaming the application —
or deploying the same workflows under a different name — orphans every suspended instance: auto-restore
will not find them. Worth pinning explicitly rather than inheriting the artifactId.

### C8 — synchronous "is instance X waiting?": **YES, and CDI-injectable**

The open item is closed: `JpaInstanceReader` is `@ApplicationScoped` with `@Inject` field injection, and
`JpaPersistenceHandlerProducer` has `@ApplicationScoped @Produces` methods. `@Inject
PersistenceInstanceReader` works directly, asserted in `SpikeFlowHitlTests`:

```java
persistenceReader.find(reviewFlow.definition(), instance.id())   // Optional<WorkflowInstance>
  .get().status()                                                // WorkflowStatus.WAITING
```

`WorkflowStatus` is `PENDING, RUNNING, WAITING, COMPLETED, FAULTED, CANCELLED, SUSPENDED`. The `404`/`409`
review contract is preservable.

**One sharp edge:** the `status` **column** is `NULL` for a waiting instance (verified by `psql` after
phase 1). The live status comes from the reconstructed instance, not the column — so do **not** build the
`409` check on a raw `select status`.

### C9 — terminate/cancel a waiting instance: **YES**

`WorkflowInstance.cancel()` returns `true` synchronously and the status becomes `CANCELLED`. The `toAny`
two-event workaround is **not** required for "a customer reply supersedes the waiting review".

### C10 — correlate on the business key: **YES**

`consumed(type).dataAs(SpikeReviewDecision.class, (decision, ctx) -> …)` reading the claim id back from
`ctx.instanceData().input()`. Asserted negatively as well as positively: a decision carrying a **different**
`claimId` is published, the future stays incomplete and the instance stays `WAITING`; only the matching one
wakes it. `Claim.reviewRunId` stays meaningful.

### D11 — the dataset-name invariant: **SAFE**

Both leaf AI services still produce `langchain4j.aiservices.<SimpleClassName>.<method>`:

```
langchain4j.aiservices.SpikeTriageAgent.triage
langchain4j.aiservices.SpikeSummaryAgent.summarize
```

and `AiServiceDatasetSpanProcessor` still stamps and cascades
`langfuse.dataset.name` / `ai.service.class` / `ai.service.method` onto them and their descendants. Pinned
by assertion, because this degrades **silently** (drift detection returns `success` on
`SampleLoadException`, so a broken name looks like a pass).

### D12 / D13 / D15 — **I got this wrong first; corrected**

**Correction.** My first measurement found zero OTel spans from Flow and I concluded Flow emits none. That
was wrong: **OpenTelemetry is a separate extension, `io.quarkiverse.flow:quarkus-flow-opentelemetry`**,
which I had not added. (`quarkus-flow`'s own `tracing.html` is a *different*, log/MDC-based feature:
`quarkus.flow.tracing.enabled`, MDC keys `quarkus.flow.instanceId` / `event` / `task` / `taskPos`, and
`X-Flow-Instance-Id` / `X-Flow-Task-Id` outbound headers — correlation, not spans. The two pages are easy
to conflate.) With the extension added, the picture is much better:

**D13 — Flow gives a real span tree for free.** One trace contains:

```
workflow.execute spike-review                      (flow.workflow.name/instance.id/namespace/version, flow.application.id)
├── task.execute runIntake            flow.task.type=call_function
├── task.execute emitReviewRequired   flow.task.type=emit,   flow.task.emit.event.type=org.parasol.claim.review.required
├── task.execute waitHumanReview      flow.task.type=listen
├── task.execute routeDecision        flow.task.type=switch
└── task.execute approveClaim         flow.task.type=call_function
```

That **is** the root task 01 had to fake with a synthetic CONSUMER span, and it is richer. It removes the
need for the hand-rolled caller span *over the Flow tasks*.

**But two gaps remain, both pinned by assertions:**

1. **The agentic subflow starts its own trace.** `workflow.execute spike-intake-agent` has
   `parentSpanId=0000000000000000` and a different trace id from `task.execute runIntake`.
2. **The LangChain4j AI-service spans are still orphan roots.**
   `langchain4j.aiservices.SpikeTriageAgent.triage` has no parent and a different trace id from
   `task.execute triage-0` — the Flow task span for the very same sub-agent.

So one intake run still produces **three disconnected trace islands**: the parent workflow, the agentic
subflow, and each AI-service call. Flow's own tree is excellent; it just is not joined to langchain4j's.

**D12 — Flow task spans carry `flow.*` only, never `gen_ai.*`.** Langfuse types observations from
`gen_ai.operation.name`, so `workflow.execute` / `task.execute` render as untyped SPAN ancestors. This is
cost #3 exactly as predicted, and per the plan it is a "where does the fix live" question, not a blocker.
The attribute stamping is an `ai.scoring` `SpanProcessor` job, with `AiServiceDatasetSpanProcessor` as the
in-repo precedent. **The trace joining is not** — see
[§ Open issue: the three trace islands](#open-issue-the-three-trace-islands).

**D15 — the resume is *not* a linked span, at least in-JVM.** `task.execute waitHumanReview` is in the
**same** trace as its workflow span and has **zero** links. The pre-settled "on restore, Flow starts a new
span linked to the original trace" was not observed for an in-JVM publish. Not re-checked across the
restart — see Open Questions.

### D14 — `fork` OTel/MDC propagation: **not tested**

The design's review gate does not use `fork`, and `@ParallelAgent` was out of scope here. `flow.task.type`
does include `fork`, and Flow binds `QuarkusManagedExecutorServiceFactory`, so context propagation is
plausible — but unverified. Carry into task 11 if a parallel branch is actually introduced.

### D16 — free Micrometer metrics

Three meters, tagged by workflow and version, for **both** the parent and the agentic subflow:

```
quarkus.flow.workflow.started.total   [workflow=spike-review,       workflowVersion=0.0.1]
quarkus.flow.workflow.completed.total [workflow=spike-review,       workflowVersion=0.0.1]
quarkus.flow.workflow.duration        [workflow=spike-review,       workflowVersion=0.0.1]
… and the same three for workflow=spike-intake-agent
```

These overlap the per-run counters planned in task 11. There is **no per-task meter** and no
waiting/suspended gauge, so "how many reviews are currently waiting" still needs the app's own metric.

### E17 — Dev UI: **strong demo value** (screenshots captured)

`spike-evidence/devui-flow-workflows.png` and `spike-evidence/devui-flow-diagram.png` in the worktree.

A **Workflows** card (icon `diagram-project`) listing name / namespace / version / description with two
actions: view and execute. The view renders an automatic **Flow Diagram** with typed, colour-coded nodes
and real branching — for the HITL pipeline it draws exactly:

```
( start ) → runIntake (CALL, java) → emitReviewRequired (EMIT) → waitHumanReview (LISTEN)
          → routeDecision (SWITCH) ──switch-item-0──→ approveClaim (CALL) ──┐
                                   └──default────────→ rejectClaim  (CALL) ──┴→ ( end )
```

The execute action posts input against a generated JSON schema (`getInputSchema` / `executeWorkflow`), and
`WorkflowRPCService` has an explicit `RESULT_WITH_AGENTIC_SCOPE_CLASS` sanitizer, so agentic results are
handled deliberately. `FlowInstance` (instanceId, status, start/end time, error code) backs an instance
list via `ManagementLifecycleRPCService`.

**For a demo about making non-deterministic systems legible, a generated picture of the agent pipeline with
a visible human-review wait state is a real asset.**

Two caveats:
- The diagram only shows workflows in **main** sources; the spike's live in test sources. The screenshot
  was taken with a temporary main-source `Flow`, **since deleted** (`git diff main -- src/main` is empty).
- **Dev-mode live reload of a `Flow` bean breaks it.** Editing the class produced
  `IncompatibleClassChangeError: class …_ClientProxy overrides final method io.quarkiverse.flow.Flow.definition()`.
  A clean restart boots fine, so it is a reload artifact, not a real limitation — but it is a sharp edge for
  live demos.

### E18 — `@QuarkusTest` under both Ollama profiles: **YES**

The 12 non-restart tests pass under **both** legs — `./mvnw -Pollama test` and `./mvnw -Pollama-openai test`
— so a CI-compatible test shape exists.

```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0   (-Pollama)
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0   (-Pollama-openai)
```

The two restart tests are **excluded** from that count on purpose: they need an externally managed Postgres
and two separate JVM invocations, so they are not CI-shaped as written.

### E19 — terminated instances leave no rows: **YES**

Asserted for both endings, and confirmed out-of-band with `psql` after the JVM exited:

| | `workflow_instance_entity` | `task_info_entity` | `cloud_event_entity` |
|---|---|---|---|
| after completion | 0 | 0 | 0 |
| after `cancel()` | 0 | 0 | 0 |

Task 08's "no leaked rows" assertions have a direct equivalent.

---

## Verdict: **ADOPT**

**The decision criteria, restated and checked:**

| Criterion | Result |
|---|---|
| Gate passes at 3.40.1 / 1.14.1 with no downgrade | **PASS** (complete, with `@SequenceAgent` present) |
| A1 — the composition works | **PASS** |
| A2 — `listen` served without a messaging module | **PASS** |
| A3 — publish wakes it, completed tasks skipped | **PASS** |
| B6 — survives a genuine restart | **PASS** |
| C8 — synchronous "waiting?" query, or the contract survives another way | **PASS** (CDI-injectable reader) |

A4, the pivotal non-criterion, came out **better** than the merged design needs: the agentic subflow is
independently persisted and checkpointed per sub-agent.

**What adopting deletes**, as the task anticipated: `DatabaseAgenticScopeStore` + its entity, the
`AgenticScopePersister.setStore` registrar, the startup round-trip self-test, the `ShutdownEvent` reset, the
`allowDeserializationType` allowlist, the hand-rolled replay in `ClaimReviewService`, **and both
internal-API touch points** (`SuspendedResponse`, `DefaultAgenticScope`). It also replaces the synthetic
CONSUMER span and much of spike items 12–18.

**What adopting costs:**

1. **Preview status.** `quarkus-flow-langchain4j` is Preview; `quarkus-flow-opentelemetry` is Preview and
   "best suited for straight-through workflow executions". Traded against two narrow `@Internal` langchain4j
   APIs that are themselves on beta artifacts.
2. **Observability plumbing is still ours.** Flow's tree is good but is not joined to langchain4j's spans,
   and carries no `gen_ai.*`. The `gen_ai.*` stamping is an `ai.scoring` `SpanProcessor`; **the joining is
   not, and cannot be** — see [§ Open issue: the three trace islands](#open-issue-the-three-trace-islands)
   for why and for the ranked options. Softer than it looks — no scoring tier is affected, the user owns
   `quarkus-langfuse`, `ai.scoring` is the staging area, and `AiServiceDatasetSpanProcessor` is the
   precedent — but it is work, not a freebie.
3. ~~**Durable state needs a schema strategy.**~~ Withdrawn: durable state across restarts isn't a
   requirement (user decision). See *Durable state across restarts: not a requirement*.
4. **`application_id` = `quarkus.application.name`** is load-bearing and should be pinned explicitly.

---

## Open issue: the three trace islands

**Status: needs to be addressed, not yet decided.** Captured here so the options are on record; the
decision belongs with the task-11 rework.

### What is actually broken

With `quarkus-flow-opentelemetry` added, one intake run emits three disconnected traces instead of one:

| # | Island | Root |
|---|---|---|
| 1 | `workflow.execute spike-review` + its five `task.execute <name>` children | correct, one trace |
| 2 | `workflow.execute spike-intake-agent` + `task.execute triage-0` / `summarize-1` | **orphan root** |
| 3 | `langchain4j.aiservices.<Agent>.<method>` + its `completion …` + `POST /chat/completions` | **orphan root**, one per AI call |

Each island is internally well formed. Only two *joins* are missing, and they are the same shape — a Flow
task span invoking something that starts a fresh root instead of continuing the current context:

- `task.execute runIntake` → `workflow.execute spike-intake-agent`
- `task.execute triage-0` → `langchain4j.aiservices.SpikeTriageAgent.triage`

### Severity: legibility, not correctness

Checked against what this repo actually depends on — **nothing breaks**:

| Consumer | Depends on | Affected? |
|---|---|---|
| Tier 1 (Langfuse LLM-as-judge) | generations, filtered `type NOT IN (SPAN, EVENT)`; `completion …` spans are still correctly parented under their AI-service span | **No** |
| Tier 2 (session scoring) | `sessionId` queries, not trace trees; `ConversationExchange.resolveDatasetName` already has a five-step fallback | **No** |
| Tier 3 (drift detection) | rebuilds the dataset name from `InvocationContext` | **No** |
| Dataset-name invariant | `langchain4j.aiservices.<SimpleClassName>.<method>` | **No** — verified safe (D11) |

So this is a **demo/legibility problem**: a trace that fragments into three pieces in Langfuse and Grafana
undercuts a demo whose thesis is making non-deterministic systems legible. Price it accordingly — it is
not a blocker and should not be treated as one.

### Correction: a `SpanProcessor` cannot do the joining

An earlier note in this file and in `PLAN.md` said an `ai.scoring` `SpanProcessor` should "join the trace
islands". **That is unimplementable as written.** `SpanProcessor.onStart(Context, ReadWriteSpan)` can add
attributes — which is exactly what `AiServiceDatasetSpanProcessor` does — but parent span id and trace id
are fixed at span creation and immutable afterwards; there is no `setParent`. Only the `gen_ai.*` stamping
half of that note is real.

### Diagnose before choosing

The two broken joins may have different causes, and the fix differs:

- **(a) Context not current** — Flow creates the task span but the task body runs without it current. The
  logs show task bodies on `executor-thread-1/2` and `ForkJoinPool.commonPool-worker-11`, so a thread hop
  is plausible.
- **(b) Workflow spans are roots by design** — Flow may deliberately start every `workflow.execute` with
  no parent. The docs' "currently best suited for straight-through workflow executions" hints at this.

**Not verified either way.** One decisive line inside the `function(...)` body settles it (~10 min):

```java
Log.infof("in task body, current span = %s", Span.current().getSpanContext().getSpanId());
```

Compare against the `task.execute runIntake` span id. Match → cause (b). Invalid or different → cause (a).
Run this before picking an option below.

### Options, ranked

1. **One app-owned root span per inbound email** *(most likely to work)*. Start an INTERNAL/CONSUMER span,
   make it current, start the Flow instance inside it — the task 01 approach. **This file previously said
   Flow "removes the need" for that span; on this evidence that was too strong.** Flow removes the need for
   a hand-rolled span *over the Flow tasks*, not for a root over the whole intake. Only works if Flow
   honours an existing current context, i.e. if the diagnostic says cause (a).
2. **Group in Langfuse instead of in OTel.** Stamp a shared `gen_ai.conversation.id` / session id across
   all three islands and let Langfuse **sessions** group them. The repo already propagates conversation id
   as OTel baggage (`ConversationalBaggageHandler`) and tier 2 already reconstructs conversations from
   sessions rather than trace trees. Sidesteps parenting entirely using machinery we already own. Best
   considered **alongside** option 1, not instead of it — it is what makes the Langfuse view coherent even
   if the OTel tree stays imperfect.
3. **Capture and re-enter the task context ourselves.** A `WorkflowExecutionListener` captures
   `Context.current()` on `TaskStartedEvent` keyed by `(instanceId, taskPos)`; the task body then wraps the
   agent call in `try (var scope = ctx.makeCurrent())`. Entirely in our code, no upstream dependency, but a
   workaround with real foot-guns. Fallback only.
4. **Link instead of parent.** Accept three traces and connect them with span links. Links can be set at
   creation; adding them post-hoc depends on the OTel API version (the BOM here is 1.62, so `Span.addLink`
   is probably available — **verify, do not assume**). A consolation prize: clickable relationships, not
   one tree.
5. **Upstream to quarkus-flow** *(do this regardless of which local fix lands)*. Two asks: honour an
   existing current context when starting a workflow span, and propagate context into task execution. Flow
   is Preview, the user owns `quarkus-langfuse`, and `ai.scoring` is an explicit staging area for logic to
   be generalized upstream — so this fits the stated path better than it would in most repos.

### The `gen_ai.*` half is separable and easy

Independent of the joining work: an `ai.scoring` `SpanProcessor` stamping
`gen_ai.operation.name=invoke_agent` onto `workflow.execute` / `task.execute` spans, so Langfuse types them
AGENT instead of rendering them as untyped SPAN ancestors. Low risk, isolated, and the single highest
visual return. `AiServiceDatasetSpanProcessor` is the precedent.

### Suggested order

1. Ship the `gen_ai.*` stamping (cheap, isolated, immediate improvement).
2. Run the one-line diagnostic.
3. Option 1 if context is honoured, option 2 if it is not — very likely both.
4. File the upstream issue (option 5) either way.

Options 3 and 4 are fallbacks.

---

## Open questions (not blockers; carry forward)

1. **Parent–child reattach on restore.** If the JVM dies *mid-subflow*, does auto-restore resume the child
   and reconnect it to the parent's pending `runIntake`, or does the parent re-run the whole task? A4 proves
   the checkpoint exists; B6 did not exercise this path. **Decide before relying on fine-grained resume** —
   worst case is the merged design's behaviour (re-run the task), which is acceptable.
2. **D15 across a restart.** Is auto-restore a linked span, as the ADR claims? Unobservable in-JVM.
3. **D14** — partly answered by the follow-up (F4): Flow's own fork task spans nest correctly; the leaf
   AI-service spans inside a fork are roots, as everywhere else. MDC was not checked.
4. **Concurrency.** Every spike test ran one instance at a time. Dataset/instance creation under concurrent
   intake is untested, and `idempotency-correlation.html` explicitly says singleton behaviour is the
   application's job ("a database lock or lease").
5. **1.2.0 final.** Re-check before committing; only `1.2.0.CR3` exists today.
6. **The three trace islands** — filed upstream as quarkus-flow#1056 (see F4); the local decision is open, options on record in
   [§ Open issue: the three trace islands](#open-issue-the-three-trace-islands). Legibility, not
   correctness: no scoring tier and not the dataset-name invariant is affected. Start with the one-line
   diagnostic.

---

## Upstream issues filed

Three quarkus-flow bugs found by these spikes are filed upstream. Each has a standalone runnable reproducer in
[edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) (Java 21+, no API keys, no
network, no containers). All three reproduce on **1.1.3 and 1.2.0.CR3**.

| Issue | Bug | Found in |
|---|---|---|
| [quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056) | Task spans are never current in task bodies, and generated agentic sub-workflows always start a new trace (the trace islands) | D13, F4 |
| [quarkus-flow#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057) | `@ParallelExecutor` on a `@ParallelAgent` throws `UnsupportedOperationException` on first use | F5 |
| [quarkus-flow#1058](https://github.com/quarkiverse/quarkus-flow/issues/1058) | `cancel()` on a waiting instance loses the workflow's `workflow.execute` span | F3 |

The standalone reproducer sharpened #1058: it loses the span on **10 of 10** cancels, not 4 of 5 as these spikes logged, so
it's systematic, not a race.

---

## Follow-up spike 2: the conversation id on every span (task 11's grouping)

**STATUS: COMPLETE. Feasible without baggage** (whether baggage could work too is open; see below). Every span of an intake run, across all of Flow's thread
hops, carries the run's `gen_ai.conversation.id`, with no leaking between 8 concurrent runs. Only Flow's own
persistence spans are left without one. Branch `spike/flow-conversation-id` (local, unpushed), commit `66db1e7`.
20 tests (task 01b's, the b1 follow-up's and these 2) pass under both `-Pollama` and `-Pollama-openai`.

**Why this was needed:** quarkus-flow#1056 splits one run into separate traces, so the plan's fallback was to
group them in Langfuse by `gen_ai.conversation.id` (Langfuse maps it to the session id). But the plan stamped it
from OTel baggage, and baggage is lost at exactly the hops that split the trace.

**Baggage is worse than lost: it leaks.** The first version made the id current as baggage before starting each
run. One run at a time was perfect. With 8 concurrent runs, Flow's persistence spans (which start as roots on
pool threads) carried **other runs' ids** (one id on 85 of them, another on 8) or none, so some pool thread was
left with stale baggage. **(Diagnosed in follow-up spike 3: Quarkus bug quarkus#54354, fixed by quarkus PR #56805, not in 3.40.1.)** The quarkus-langfuse span processor also stamps the id from baggage, so the same
mis-attribution would reach Langfuse. **The cause is not established.** That version's own `AgentListener` made
baggage current in `beforeAgentInvocation` and closed it in `after`, so the leak may be the spike's own doing
rather than Flow's. Properly propagated context (captured on submit, restored and cleared after) shouldn't leak, so
"don't use baggage" is not a proven conclusion; the data approach below is simply the one that's proven to work.

**What works** (`Spike3ConversationIdSpanProcessor`, `Spike3ConversationFlowListener`,
`Spike3ConversationAgentListener`, `Spike3StepContext`). The id travels as data, never as ambient context:

1. **Our workflow's input carries the id.** A Flow `WorkflowExecutionListener` (priority 0, so it runs before
   quarkus-flow-opentelemetry's) maps each workflow instance id to the conversation id at `onWorkflowStarted`.
   It reads it from our input record, or, for a **generated agentic sub-workflow**, from the agentic scope that
   is its input. It removes the entry on completed/failed/cancelled.
2. **The agentic root takes the id as an argument** (`process(@V("conversationId") …, …)`), so it's in the
   agentic scope. A CDI `AgentListener` bean pushes it onto a per-thread stack in `beforeAgentInvocation` and
   pops it in `after`/`onError`. LangChain4j calls both on the same thread as the agent call, including the
   parallel branches, which run on virtual threads.
3. **A `SpanProcessor` stamps the id in `onStart`:** from the in-process parent span (cascade, like
   `AiServiceDatasetSpanProcessor`), else from the instance map via `flow.workflow.instance.id` (Flow's own
   spans), else from the agent-call stack (the root `langchain4j.aiservices.*` span of each agent call).
4. **Our own steps** run their body inside the task-08 helper (Flow's task span made current), so an AI service
   called directly from a step (the status answer) is parented under the step and inherits the id by cascade.

**Measured** (one run = 22 non-persistence spans: 3 Flow workflows, 7 tasks, 4 AI-service calls with their
`completion` and HTTP spans):
- one run: all 22 carry the id;
- 8 concurrent runs: exactly 22 spans per id, for each of the 8 ids, and no trace mixes ids;
- Flow's persistence spans (~58 per run: `INSERT/UPDATE/DELETE/SELECT` on `workflow_instance_entity` /
  `task_info_entity`) get **no** id, because they are roots with neither a parent nor a Flow instance attribute.

**What this does and doesn't fix:**
- **Langfuse:** one session per claim becomes possible, assuming Langfuse maps the attribute to the session id
  as planned. Not checked against a live Langfuse here (span export is off in tests).
- **Trace trees:** still split (#1056). A run is still several traces, plus one trace root per persistence
  statement. That's noise in Tempo and Langfuse. Turning off JDBC tracing for Flow's tables, or dropping those
  spans, is for task 11 to decide.
- **The agent listener's stack** is a ThreadLocal. It's balanced by construction (it pushes on every call and
  pops on after/error), but it's the one piece that should get a test of its own in task 11.
- **Chat baggage:** the chat's `ConversationalBaggageHandler` still uses baggage, and quarkus-langfuse stamps
  from it. If that baggage ever leaks onto a Flow pool thread, an intake span could get a chat id. Not observed,
  not tested; worth one assertion in task 11.

---

## Follow-up spike 3: why baggage leaked (the carrier decision)

**STATUS: COMPLETE. The leak is a Quarkus bug, already fixed upstream; baggage is the right carrier once the fix
ships.** Branch `spike/flow-baggage` (local, unpushed), commit `c28e09d`, test `Spike4BaggageTests`.

**The leak isn't Flow's and wasn't the spike's.** A test with no Flow at all reproduces it: submit 32 tasks with
distinct baggage to the injected `ManagedExecutor` (each sees its own id, 32/32), then submit tasks from a caller
with **no** baggage. Those tasks see **earlier tasks' ids** on the threads that ran them. The common pool doesn't
(always `null`). Submitting from an explicit `Context.root()` doesn't either.

**Cause: [quarkusio/quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354)** ("Leak context propagation
when using the quarkus-opentelemetry extension", open). In 3.40.1, `OpenTelemetryMpContextPropagationProvider`
attaches the captured context in `begin()`, but `endContext()` only restores the thread's previous context when
that previous context had a **recording span**. A pool thread starts with no span, so nothing is restored, and the
task's context, baggage included, stays on the thread for the next task. Its `clearedContext` is a no-op, too.

**Fix: [quarkusio/quarkus#56805](https://github.com/quarkusio/quarkus/pull/56805)** ("Fix clearedContext in
OpenTelemetryMpContextPropagationProvider"), merged to `main` 2026-09-22, milestone **4.0.0.Beta1**, labelled
`triage/backport-3.40`. **Not in 3.40.1**, and not on the `3.40` branch yet.

**Verified:** shadowing that one class with the verbatim upstream version on the test classpath (spike only):

| | 3.40.1 as shipped | with the #56805 fix |
|---|---|---|
| ManagedExecutor tasks submitted with no baggage | see earlier tasks' ids | all `null` |
| second batch of 8 runs: spans carrying a first-batch id | **81 / 118** (two runs), all Flow persistence | **0** |

Same on `-Pollama` and `-Pollama-openai`.

**What baggage alone covers (with the fix), set only where a run starts, no other context code:** 8 concurrent
runs, **0** traces mixing ids, **0** foreign ids. Each run's main workflow, its steps and an AI service called from
a step carry the id (9 of the run's 22 spans), and so do most of Flow's persistence spans of the main workflow.
**Missing:** the generated agentic sub-workflows and everything under them (classify, the parallel fork, the two
extraction agents and their HTTP spans). That's the `supplyAsync` hop in `FlowPlanner` from
[quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056), which runs on the common pool with
no propagation. It's context going missing, not leaking.

**A `CallableTaskProxyBuilder` (Flow's per-call-task hook, registered through a
`WorkflowApplicationBuilderCustomizer` bean) can make each task's context current with no code in the steps.** It
wrapped all 54 call tasks and cut traces from 56 to 40. But it can't reach the sub-workflows, for the same
`supplyAsync` reason. **So the Flow adapter's real job is that one hop**, and the clean place to fix it is upstream
(#1056).

**Decision input for the generic design:**
- **Carrier: baggage.** It's the OTel standard, crosses HTTP and messaging, and needs no framework knowledge. It's
  correct once the Quarkus propagation fix ships.
- **Until then:** either take the 3.40 backport when it lands, wait for 4.0, or work around it. A root-restoring
  executor in the Flow adapter would fix Flow's own pool. The data-carrying approach from spike 2 also works, but
  it's the one that needs framework-specific code everywhere.
- **The Flow adapter's remaining job:** carry the context across `FlowPlanner`'s `supplyAsync` (needs #1056 or a
  workaround), and optionally the call-task proxy for our own steps.

**Decided (user, 2026-10-07):** baggage is the carrier, the data approach is dropped, and quarkus#54354 is worked
around until the fix ships. The workaround has to sit at the context-propagation level, in the generic core: the
leaked ids above were all on persistence spans, which run on quarkus-flow-jpa's own `ManagedExecutor`, so a wrapper
around Flow's executor alone wouldn't have caught them. The workaround is proven in follow-up spike 4.

---

## Follow-up spike 4: the quarkus#54354 workaround

**STATUS: COMPLETE. Works on 3.40.1 without overriding any Quarkus class.** Branch `spike/flow-baggage-guard`
(local, unpushed), class `Spike5OtelContextLeakGuard`, tests in `Spike4BaggageTests`.

**How it works.** MicroProfile Context Propagation lets an app add its own `ThreadContextProvider` through
`META-INF/services`, as long as its context type is unique. (SmallRye rejects a second `"OpenTelemetry"` provider, so
it can't replace Quarkus's.) This one captures nothing. It records the OTel context that was current on the
submitting thread, and when the task ends on a worker thread it checks whether that exact context is still current.
If so, nothing restored the thread, so it attaches `Context.root()`.

**Why the order of providers doesn't matter.** SmallRye ends providers in reverse order of a `HashSet`, so the order
isn't defined. Both orders are safe:
- *Quarkus's provider ends first:* either it restored the previous context (current ≠ captured, the guard does
  nothing) or it didn't (current = captured, the guard resets).
- *The guard ends first:* current = captured, so it resets. Quarkus's provider then restores a previous context that
  had a recording span, or does nothing.

**What it leaves alone:**
- tasks run inline on the submitting thread (it compares threads);
- Vert.x duplicated contexts, where Quarkus keeps the OTel context per request and the bug doesn't apply;
- a thread with its own span and baggage that runs a contextual task inline. Tested: the thread keeps both.

**Measured** (3.40.1, `-Pollama` and `-Pollama-openai`, the same on both):

| | 3.40.1 | 3.40.1 + guard | 3.40.1 + upstream fix (spike 3) |
|---|---|---|---|
| `ManagedExecutor` tasks submitted with no baggage | see earlier tasks' ids | all `null` (3 of 3 repeats) | all `null` |
| second batch of 8 runs: spans carrying a first-batch id | 81 / 118 | **0** | 0 |
| 8 concurrent runs: traces mixing ids | 0 | 0 | 0 |

It reset about 100 worker threads per 128 tasks, which is how often the leak happens in this test.

**No side effects on the rest of the app (with a gap).** The full `./mvnw test` on the default profile (what CI runs),
with and without the guard, gave the **same result**: 198 tests, 0 failures, 4 errors, 96 skipped, with the same
classes failing. None of it is the guard:
- `ClaimWebsocketChatBotTests`, `ClaimImagesPageTests` and the boot-failed skips call the real OpenAI with the stubbed
  key (401).
- `SpikeRestartPhase1/2Tests` need an external Postgres on port 55432 (task 01b's restart spike).

**The gap:** the suites that were skipped for lack of a real OpenAI key, including the chat paths that use baggage
today, did **not** run with the guard. Building the core task means running them with a real key.

**Delete it when** quarkusio/quarkus#56805 ships (4.0, or the 3.40 backport). Spike 3's
`managedExecutorPropagationIsClean` is the regression test: on a fixed Quarkus it passes without the guard.

---

## Follow-up spike 5: the remaining unknowns before implementation

**STATUS: COMPLETE.** Branch `spike/flow-followup5` (local, unpushed): commit `ab86d12` (Dev UI screenshots and the
dev-mode demo classes in `src/main/java/org/parasol/spike/devui/`), commit `0962dd4` (tests). 19 tests
(`Spike5AgenticAdapterTests`, `Spike5RouterAndOrderTests`, plus `Spike4BaggageTests` and `Spike2IntakeFlowTests`
as regression) pass under `-Pollama` and `-Pollama-openai`. `Spike5SubWorkflowListenerTests` runs on its own, because
the option it measures leaks context into the JVM.

### Q1: the agentic adapter carries the id into the generated sub-workflows' AI calls: **YES**

`Spike5AgenticConversationAdapter` is task 11's adapter as specified:
- **before:** if an id is current and the scope has none, write it under `ai.scoring.conversation.id`. If no id is
  current, enter the scope's id as baggage.
- **after/error:** close the scope from a per-thread stack.

The run is 8 concurrent runs with an id, interleaved with 4 runs with **no** id, under the leak guard. Each run is 12
AI-service calls in total, with spans counted per kind:

| | AI-service spans stamped (of 48) | spans per id'd run | traces mixing ids | foreign ids |
|---|---|---|---|---|
| baseline (baggage only) | 16 (classify + direct only) | 9 | 0 | 0 |
| adapter | **32** (all of the 8 id'd runs) | 15 | 0 | 0 |
| adapter + task proxy | 32 | 15 | 0 | 0 |

- **No-id runs stay clean.** All 4 no-id runs carry no id on any span, in all variants.
- **No leaks across batches.** In a second batch of 8 no-id runs after an id'd batch, **0 of 432** spans are stamped.
- **Balanced.** Every after matched a before (`unbalanced=0`). The adapter wrote to the scope 8 times (once per run,
  at the classifier) and entered from the scope 16 times (the two parallel extraction leaves on their own threads).
- **Still unstamped:** the generated sub-workflows' own Flow spans (`workflow.execute spike3-intake-agents`,
  `spike3-extraction-workflow`, and their `task.execute` spans, 7 per run). Their AI calls and HTTP spans are stamped.
  So Langfuse sessions get every LLM call; only Flow's bookkeeping spans inside the agent step are missing.

**Task 11 option 2 (a Flow listener entering the scope's id for those spans): INVALIDATED.**
- Flow runs each listener priority group as a separate future stage
  (`LifecycleEventsUtils.publishEvent`, `thenCompose`).
- So a priority-0 "enter" and a priority-2000 "exit" for the same instance ran on **different threads**, 42 of 42
  times (e.g. `ForkJoinPool.commonPool-worker-15` → `executor-thread-17`).
- The scope is never closed on the thread that opened it. 20–49 spans carried **another run's id**, and 16–139
  stamped spans in a following no-id batch carried earlier ids.
- Remaining choice: accept the gap until #1056 ships (option 1).

### Q2: two emails from the same sender run in order: **YES**

`Spike5IntakeStarter` is task 08's per-sender queue. It's a CDI `WorkflowExecutionListener` that releases the sender on
`WAITING`/`COMPLETED`/`CANCELLED`/`FAULTED`, and the run is registered as working before `start()`. The first email
holds its agent step for 3 s.

```
starter: submit:first, started:first, submit:second, queued:second, submit:other, started:other,
         released:WAITING, released:WAITING, started:second, released:WAITING
steps:   start:other, start:first, end:other, end:first, start:second, end:second
```

- **The second email waits.** It's queued (the address is matched case-insensitively, `JANE@` = `jane@`) and starts
  only after the first run reaches the review wait.
- **Other senders aren't held up.** A different sender ran alongside.
- **A waiting run doesn't block its sender.** A third email from the same sender, while the first two wait for
  review, started at once.
- Supersede/cancel itself is F3.

### Q3: `MonitoredAgent` under Flow, and the two Dev UIs: **keep both** (user decision, 2026-10-07)

Screenshots and an index are in `spike5-screenshots/` on the spike branch. They were taken in `quarkus:dev` with
`granite4:micro`: 7 emails, all three router branches, one tool call.

| | Agentic Dev UI (`MonitoredAgent`) | Flow Dev UI (1.1.3) |
|---|---|---|
| Shape | Topology: sequence, router branches **labelled with their conditions**, parallel fork, state-key data flow | The business workflow: steps, switches, the review `listen`. The agent step's generated sub-workflows are opaque `CALL`s, and the 3-way router is drawn as a **straight chain** |
| Per run | Executions: every agent, its input and output, tokens, a timeline, **tool calls with arguments and result** | **None.** `ManagementLifecycleRPCService` lists instances server-side, but no shipped Dev UI JS calls it |
| Other | Testing page | Start a workflow from an input form |

- **`MonitoredAgent` works on a Flow-translated root.** 4 successful executions, 0 ongoing.
- **Memory: no leak in this design.** With 3 runs still waiting for review, `ongoingExecutions` was **0**. The agent
  call finishes before the Flow run waits, so the leak that drove the old dev-only decision (suspended
  `@HumanInTheLoop` runs) is gone.
- **Prod:** neither UI exists outside dev mode. Langfuse is the prod view.

### Q4: the three-way `EmailRouter` under Flow's translation: **YES**

`Spike5EmailRouter` routes to:
- the parallel extraction (2 LLM agents);
- `Spike5FollowUpAgent`, with `@ToolBox(Spike5ClaimStatusTools)`;
- `Spike5UnmatchedEmailAgent`, a plain Java `@Agent` with no LLM.

| email | branch | run |
|---|---|---|
| new claim | parallel extraction (details 1, summary 1) | `WAITING` (review) |
| status question on `CLM-1042` (In Process) | follow-up agent: tool request, `claimStatus`, then the answer (2 LLM calls, 1 tool call) | `COMPLETED` |
| newsletter | Java agent | `COMPLETED` |
| status question, no claim | Java agent (no matching claim) | `COMPLETED` |

The tool ran with the run's conversation id current, and its `langchain4j.tools.claimStatus` span and the follow-up
agent's span are stamped.

---

## Durable state across restarts: not a requirement (user decision)

`%prod` and `%openshift` set `schema-management.strategy: drop-and-create`, and dev/test take the Dev
Services default, so every boot wipes Flow's three tables along with everything else. B6 only passed
because `SpikeRestartTestProfile` overrode the strategy.

**This is intended, not a gap** (user decision, 2026-10-07). This is a demo app, and every restart already
reseeds the claims from `import.sql` and empties GreenMail's in-memory mailboxes. A waiting review that
survived a restart would point at a claim and an email thread that no longer exist, so losing it with
everything else is the consistent behaviour. **No Flyway, no schema strategy, no separate issue.**
Consequences:

- `quarkus-flow-jpa` stays: it creates its tables under the existing schema management at no cost, every
  spike test ran with it, and `PersistenceInstanceReader` backs the review's `404`/`409` check.
- The design doc's step 8 ("survives restarts") is corrected to "kept in PostgreSQL while the app runs".
- Task 14's restart test (external Postgres, two JVMs) is dropped: B6 already proved the Flow guarantee.
- Pinning `quarkus.application.name` is nice-to-have (one line), not load-bearing.

---

## Follow-up spike: the whole intake as one workflow (option b1)

**STATUS: COMPLETE. Option b1 is feasible**, with one design change (no `@ParallelExecutor`) and one
small engine bug to work around.

The user chose to model the **whole intake** as one Flow workflow (b), with the agentic root kept as a
single task (b1), rather than wrapping only the review in Flow (a). This spike checks the four parts of
b1 that task 01b never exercised.

- **Branch:** `spike/flow-workflow` (local, unpushed), from `spike/flow-hitl`, worktree
  `…-spike-flow`. Commit `a7a2a62`. Same versions as above.
- **Code:** `src/test/java/org/parasol/spike/flow/Spike2*`. `Spike2IntakeFlowTests` (6 tests).
- **Result:** 18 tests (the 12 CI-shaped ones from task 01b plus these 6) pass under **both** `-Pollama`
  and `-Pollama-openai`.

`Spike2IntakeFlow` is a cut-down b1 intake:

```
supersede → runAgents → routeOutcome ─ not a claim ─→ replyNotAClaim → END
   (cancel         │                └─ claim ──────→ markPendingReview → waitReview (LISTEN)
    old run)       │                                   → routeDecision → markInProcess | markPendingInformation → END
                   └─ Spike2IntakeAgents: @SequenceAgent(classifier, @ConditionalAgent(@ParallelAgent(details, summary), notAClaim))
```

### F1 — the design's real topology as one Flow task: **YES**

Sequence → conditional → parallel, the same nesting as `ClaimsMailboxAgent` → `EmailRouter` →
`ClaimExtractionWorkflow`. Flow's compiler turns **each** composite into its own generated workflow
(`spike2-intake-agents`, `spike2-router-workflow`, `spike2-extraction-workflow`). The parallel one is a
real Flow `fork` (`do/0/parallel/branch/0/extractDetails-0` and `…/1/summarize-1` start in the same
millisecond on different threads).

Measured by per-agent WireMock markers (`[[classify]]` etc.):
- claim email: classifier 1, details 1, summary 1, not-a-claim 0. Then `Pending Review` and a wait.
- not-a-claim email: classifier 1, not-a-claim 1, details 0, summary 0. The run ends with no wait.
- resume after the decision: **zero** further LLM calls (3 in total).

So `@ActivationCondition` routing works under Flow's translation, including a composite (the parallel
agent) as a branch.

### F5 — `@ParallelExecutor` is **not supported** under Flow: **design change**

Found while checking F1, because the design puts a context-propagating `@ParallelExecutor` on
`ClaimExtractionWorkflow` (task 01 Q17). With one present, **every** run fails:

```
CreationException: Error creating synthetic bean […]:
  UnsupportedOperationException: Changing the default WorkflowApplication executor is not supported at this time.
```

The cause is `FlowParallelAgentService.executor(Executor)`, which throws unconditionally. It's raised
lazily, when the root bean is first created, so the build and boot both succeed and the failure only
shows up on the first email. (Seen under `-Pollama-openai`; the class was then reverted.)

**Consequence:** drop `@ParallelExecutor` from the design. Flow runs fork branches on its own executor
(Quarkus's `ManagedExecutor`, via `QuarkusManagedExecutorServiceFactory`). The annotation's only job was
keeping the OTel context inside the parallel branches, and under Flow the leaf AI-service spans are roots
whatever the executor (F4), so nothing is lost. Filed upstream as [quarkiverse/quarkus-flow#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057) (the failure is lazy).

### F2 — the watcher handoff: **YES**, with a CDI listener

A one-email-at-a-time watcher can't wait for a review that takes days. `Spike2HandoffListener`, a CDI
`WorkflowExecutionListener` bean (Flow binds it automatically, via
`WorkflowApplicationCreator.injectCustomListeners`), completes a per-instance future on the first
`onWorkflowStatusChanged` to `WAITING`, `COMPLETED`, `CANCELLED` or `FAULTED`. The claim run hands off at
`WAITING` while its own completion is still open. The not-a-claim run hands off at `COMPLETED`.

**F2b — the "WAITING" signal is slightly early.** `ListenExecutor.internalExecute` sets `WAITING`
*before* `buildInfo` registers its event consumer. In the first test run, the very first workflow in a
fresh JVM published its decision the moment the handoff fired, and the decision was lost (the run never
woke; a 30 s timeout). That fits the source, but I didn't prove it was the cause. A dedicated test then
published immediately on `WAITING` 10 times per profile and lost **0 of 10**, so at worst it's a narrow
race. That doesn't matter for production, where a human clicks
much later. But it matters for tests, and for any automated decision. The tests now use
`publishUntilWoken` (re-publish every 250 ms until the run leaves `WAITING`; the `listen` consumes one
event, and extra copies are ignored).

### F3 — a reply supersedes the waiting review, from inside the new run: **YES**

The new run's first task calls `definition().activeInstance(oldId).map(WorkflowInstance::cancel)`. Cancel
returns `true`, the old run becomes `CANCELLED`, its `waitReview` task is cancelled, and its completion
future completes exceptionally. Afterwards:

- the old run leaves **0** instance and **0** task rows (E19 holds for a cancel from inside a task too);
- one decision for the claim reaches **only** the live run. Both runs correlate on the same claim id, so
  this proves the cancelled run's event registration is really gone;
- side effects are exactly `Pending Review`, `Pending Review`, `Pending Information`.

**Two cautions:**
- `activeInstance(id)` only finds runs **in this JVM**. That's fine under the "durable state isn't a
  requirement" decision. Otherwise, `PersistenceInstanceReader.find` would be the cross-restart route.
- **Engine bug in `cancel()`, not specific to cancelling from a task:** in each full run, 4 of the 5
  cancels (including task 01b's, which cancel from the test thread) log `No instrumentation context was
  found`, and the cancelled run's `workflow.execute` span is **never exported**; its
  `task.execute waitReview` span is. (The standalone reproducer loses it 10 of 10 times.) Likely cause, from the source: `cancel()` cancels the `listen` future,
  the instance's `whenComplete(cleanUp)` closes and clears the instance metadata (closing
  `WorkflowInstrumentationContext` ends the open task spans), and only then does `onWorkflowCancelled`
  reach the OTel listener, which finds no context and returns without ending the workflow span. It's
  cosmetic (one missing span per superseded run); filed upstream as [quarkiverse/quarkus-flow#1058](https://github.com/quarkiverse/quarkus-flow/issues/1058).

**One unexplained failure, not reproduced.** In the first test run, the cancelled run's own instance
row (queried by its id) was still there 10 s after the cancel. In the three later runs (`-Pollama` twice,
`-Pollama-openai` once), the per-instance row dump shows 0 instance and 0 task rows 3 s after the cancel.
I don't know what differed. Treat it as open: task 08's "no rows left after a supersede" test is the
place to catch it if it's real.

### F4 — the trace-island diagnostic: **cause (a), plus structural cause (b)**

The one-line diagnostic, inside the `runAgents` task body:

```
current-span-in-task-body = 0000000000000000   (invalid: no span is current)
flow-task-span            = 093fc698f58c7268   (task.execute runAgents exists, on the same thread)
```

**So Flow creates the task span but never makes it current while the task body runs** (cause (a)). It
has nothing to do with threads: the body runs on `executor-thread-2` either way. The Flow source shows
why. `OTelWorkflowExecutionListener` starts spans from lifecycle events and keeps them in instance
metadata, and nothing ever calls `makeCurrent()`.

**And re-entering Flow's span from the task body works for the first hop.** `quarkus-flow-opentelemetry`
exposes the span through public static accessors
(`WorkflowInstrumentationContext.getWorkflowInstrumentationContext(instanceData)
.getTaskInstanceContext(jsonPointer, iteration, retryAttempt).getStartSpan()`). Wrapping the agent call in
`taskSpan.storeInContext(Context.current()).makeCurrent()`:

```
plain:      langchain4j.aiservices.Spike2ClassifierAgent.classify   parent=0000000000000000  (own trace)
reentered:  langchain4j.aiservices.Spike2ClassifierAgent.classify   parent=task.execute runAgents  (same trace)
```

**But the generated subflows are always roots** (cause (b)), and nothing in the task body can reach
them. `workflow.execute spike2-intake-agents`, `…-router-workflow` and `…-extraction-workflow` each start
a new trace, even when re-entered. `FlowPlanner.firstAction` starts each one with
`CompletableFuture.supplyAsync(instance::start)`, a thread hop with no context. And only the first
leaf (`classify`) joins, because it runs on the re-entered calling thread. The later leaves are driven
by the subflow's planner. One b1 claim run therefore produces **seven** separate traces: the main
workflow, three generated subflows, and one per AI-service call. Re-entering brings that down to **six**.
(Inside each subflow the Flow task spans are correctly nested, including the fork:
`task.execute summarize-1` → `task.execute parallel`. That partly answers D14.)

**Consequence for task 11:** option 1 (an app-owned root span) only reaches the first hop, and option 3
(re-entering the context) is the same thing. **Option 2, grouping by `gen_ai.conversation.id` in Langfuse,
is the one that covers every island**, because it's an attribute, not parenting. The project's own
`ConversationIdSpanProcessor` (already planned in task 11) stamps it from baggage. That needs baggage
to be current on every thread, which is exactly what fails here, so the processor has to read it from
somewhere that survives the hops. That's not designed or tested yet; it's the main open question for
task 11. Option 5 (upstream: make task spans current while the task body runs, and propagate context into
the generated subflows) is filed as
[quarkiverse/quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056), checked against
`main` @ `9ba5347` (the relevant classes are unchanged from 1.1.3).

---

## Reproducing

```bash
cd /Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow   # worktree removed; recreate from the branch
export OPENAI_API_KEY=change-me COHERE_API_KEY=change-me

# the 12 CI-shaped tests, both legs (branch spike/flow-hitl)
./mvnw -B -Pollama        test -Dtest='SpikeFlow*Tests' -Dquarkus.quinoa.enabled=false
./mvnw -B -Pollama-openai test -Dtest='SpikeFlow*Tests' -Dquarkus.quinoa.enabled=false

# the b1 follow-up (branch spike/flow-workflow): 18 tests, both legs
./mvnw -B -Pollama        test -Dtest='SpikeFlow*Tests,Spike2*Tests' -Dquarkus.quinoa.enabled=false
./mvnw -B -Pollama-openai test -Dtest='SpikeFlow*Tests,Spike2*Tests' -Dquarkus.quinoa.enabled=false

# B6 needs an external Postgres and two JVMs
podman run -d --name spike-flow-pg -p 55432:5432 \
  -e POSTGRES_USER=parasol -e POSTGRES_PASSWORD=parasol -e POSTGRES_DB=parasol docker.io/library/postgres:17
./mvnw -B -Pollama test -Dtest='SpikeRestartPhase1Tests' -Dquarkus.quinoa.enabled=false
./mvnw -B -Pollama test -Dtest='SpikeRestartPhase2Tests' -Dquarkus.quinoa.enabled=false
podman rm -f spike-flow-pg

# E17
./mvnw -Pollama quarkus:dev -Dquarkus.quinoa.enabled=false   # http://localhost:8080/q/dev-ui/quarkus-flow/workflows
```

To restore the worktree: `git worktree add ../non-deterministic-no-problem-spike-flow spike/flow-hitl`.
