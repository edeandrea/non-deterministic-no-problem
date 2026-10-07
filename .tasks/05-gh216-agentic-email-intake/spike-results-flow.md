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
3. **Durable state needs a schema strategy.** B6 only works because the spike overrode schema management.
   See the standing note.
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
3. **D14** — `fork` OTel/MDC propagation, if a parallel branch is introduced.
4. **Concurrency.** Every spike test ran one instance at a time. Dataset/instance creation under concurrent
   intake is untested, and `idempotency-correlation.html` explicitly says singleton behaviour is the
   application's job ("a database lock or lease").
5. **1.2.0 final.** Re-check before committing; only `1.2.0.CR3` exists today.
6. **The three trace islands** — needs a decision, options on record in
   [§ Open issue: the three trace islands](#open-issue-the-three-trace-islands). Legibility, not
   correctness: no scoring tier and not the dataset-name invariant is affected. Start with the one-line
   diagnostic.

---

## Standing note: durable state is wiped on boot in every profile

`%prod` and `%openshift` set `schema-management.strategy: drop-and-create`, and dev/test take the Dev
Services default. **Any durable-state table is wiped on boot** — so "the paused run survives restarts"
(design doc step 8) does not hold in *any* current profile, for the merged design's scope table or for
Flow's three tables.

This is now demonstrated, not theorised: B6 **only** passes because `SpikeRestartTestProfile` overrides the
strategy, and the first attempt (`update` against a virgin external database) failed outright with
`relation "claims" does not exist`. Adopting Flow does not create this problem and does not fix it; it adds
three more tables to the same drop. **Raise on #216 independently of this verdict** — a real review gate
needs a persistent schema and a migration story (Flow's docs recommend Flyway, and ship DDL for
PostgreSQL among others).

---

## Reproducing

```bash
cd /Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow   # worktree removed; recreate from the branch
export OPENAI_API_KEY=change-me COHERE_API_KEY=change-me

# the 12 CI-shaped tests, both legs
./mvnw -B -Pollama        test -Dtest='SpikeFlow*Tests' -Dquarkus.quinoa.enabled=false
./mvnw -B -Pollama-openai test -Dtest='SpikeFlow*Tests' -Dquarkus.quinoa.enabled=false

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
