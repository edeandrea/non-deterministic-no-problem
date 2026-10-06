# Spike Results: quarkus-flow (task 01b)

> **STATUS: IN PROGRESS — SUSPENDED MID-SPIKE.** The gate (Step 0) is **passed** on the evidence
> gathered so far, and the API surface needed to answer every remaining question has been mapped.
>
> - **Unproven — nothing empirical yet:** A1, A3, A4, B5–B7, D11–D16, E17–E19. **A1, A3, A4 and B6 are
>   the load-bearing ones** (they are the functional half of the decision criteria).
> - **Provisionally answered on API evidence, pending one CDI check:** C8, C9, C10 — the three questions
>   flagged as possible blockers for the `404`/`409` review contract. All three look answerable in Flow's
>   favour; one check (is `PersistenceInstanceReader` CDI-injectable in `quarkus-flow-jpa`?) closes them.
> - **No verdict exists.** Producing it is part of finishing this task.
>
> See [§ Resuming this spike](#resuming-this-spike) for exact instructions to continue.
> Nothing in the spike worktree is merged or pushed. No production file was modified.

## Environment

- **Main repo at spike start:** `main` @ `8ed467d` ("Replace Mailpit with GreenMail and the Roundcube
  webmail (#230)"). `git status --porcelain` was `?? .junie/` and `?? CODE_STANDARDS.md` (the latter a
  symlink to the dotfiles standards).
- **Worktree:** `/Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow`, branch
  `spike/flow-hitl` (local only, created from `main` @ `8ed467d`). Pom patch committed as
  `c3ab343`. Never pushed.
- **Java:** Temurin 25. Container runtime: Podman via `/var/run/docker.sock`.
- **Date:** 2026-10-06.

### The pom patch applied in the worktree

One property plus four dependencies. **`quarkus.platform.version` (3.40.1) and
`quarkus.langchain4j.version` (1.14.1) were deliberately left untouched** — that is the whole point of
the gate.

```xml
<quarkus.flow.version>1.1.3</quarkus.flow.version>
```

```xml
<!-- inserted immediately after the quarkus-langchain4j-chat-scopes-websocket dependency -->
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
```

`quarkus-langchain4j-agentic` is included because `quarkus-flow-langchain4j`'s build-time compiler only
fires when an agentic annotation is present; without it the gate would prove nothing.

---

## Step 0 — the gate: **PASSED** (with one caveat on completeness)

### Versions available (checked against Maven Central metadata, 2026-10-06)

All three Flow artifacts publish identical version lines. Latest **release** is `1.1.3`; latest
**prerelease** is `1.2.0.CR3`. **There is no `1.2.0` final** — the plan's "re-check for 1.2.0 final"
resolves to "not released yet".

```
… 1.1.1.CR1, 1.1.1, 1.1.2, 1.1.3, 1.2.0.CR1, 1.2.0.CR2, 1.2.0.CR3
```

### Version skew confirmed (the risk being priced)

From `quarkus-flow-parent`'s own pom at each tag:

| Flow version | `quarkus.version` | `io.quarkiverse.langchain4j.version` | `io.serverlessworkflow.version` |
|---|---|---|---|
| 1.1.3 | 3.39.0 | 1.13.3 | 7.32.1.Final |
| 1.2.0.CR3 | 3.39.0 | 1.13.3 | 7.34.0.Final |

This project is on Quarkus **3.40.1** / quarkus-langchain4j **1.14.1**. So the skew is real, and
identical for both Flow versions.

### Gate result 1 — resolution and compilation: `BUILD SUCCESS`

```bash
cd /Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow
OPENAI_API_KEY=change-me COHERE_API_KEY=change-me ./mvnw -B -Pollama clean test-compile -DskipTests
```

`BUILD SUCCESS` in 9.8s. Only pre-existing unchecked-operation notes
(`LangfuseDatasetSampleLoader`, `DriftDetectionOutputGuardrailTests`). No dependency convergence errors.

### Gate result 2 — augmentation: `BUILD SUCCESS`

`test-compile` is **not** sufficient: Quarkus augmentation runs at `package` / `@QuarkusTest` boot, which
is where a build-time compiler reading another extension's build items would actually break.

```bash
OPENAI_API_KEY=change-me COHERE_API_KEY=change-me \
  ./mvnw -B -Pollama package -DskipTests -Dquarkus.quinoa.enabled=false
```

`BUILD SUCCESS`. The full Quarkus build ran with all three Flow extensions plus the agentic extension
present at 3.40.1/1.14.1.

> **Caveat — the one piece of the gate still open.** This augmentation ran with **no `@SequenceAgent` in
> the sources**, so `FlowLangChain4jProcessor` had no `DetectedAiAgentBuildItem` to consume and its
> agentic-translation path did not execute. The static analysis below shows why that path is very likely
> safe, but it has **not** been executed. Finishing the gate = adding one `@SequenceAgent` and booting a
> `@QuarkusTest` (see § Resuming).

### Dependency tree — no downgrade forced

```
io.quarkiverse.langchain4j:quarkus-langchain4j-agentic:jar:1.14.1:compile
  \- dev.langchain4j:langchain4j-agentic:jar:1.20.2-beta30:compile
io.quarkiverse.flow:quarkus-flow:jar:1.1.3:compile
  +- io.serverlessworkflow:serverlessworkflow-api:jar:7.32.1.Final:compile
  +- io.serverlessworkflow:serverlessworkflow-impl-core:jar:7.32.1.Final:compile
  +- … (impl-http, impl-openapi, impl-jq, impl-model, impl-lifecycle-events,
  |     fluent-spec, impl-jackson-jwt, impl-function)
io.quarkiverse.flow:quarkus-flow-langchain4j:jar:1.1.3:compile
io.quarkiverse.flow:quarkus-flow-jpa:jar:1.1.3:compile
  +- io.serverlessworkflow:serverlessworkflow-persistence-api:jar:7.32.1.Final:compile
  \- io.quarkiverse.flow:quarkus-flow-persistence-common:jar:1.1.3:compile
```

Flow 1.1.3 coexists with the project's own quarkus-langchain4j **1.14.1** and langchain4j-agentic
**1.20.2-beta30**. Maven's BOM import wins over Flow's `${io.quarkiverse.langchain4j.version}`, so
**nothing is dragged back to 1.13.3**.

### Why the skew is low-risk — the coupling is one build item

Decompiled `quarkus-flow-langchain4j-deployment-1.1.3.jar`. It contains 15 classes, all in
`io.quarkiverse.flow.langchain4j.deployment`:

```
FlowLangChain4jProcessor, AgenticTopologyMapper, AgenticWorkflowBlueprint,
FlowAgenticWorkflowBuildItem, GizmoAgentFlowsHelper, JandexMethodInputJsonSchema,
Lc4jAnnotationScannerUtil, Lc4jAnnotations, AgentIdConstants,
LoopMetadata, ConditionalMetadata, PredicateMetadata, package-info
```

It scans Jandex for the annotations **itself** (`Lc4jAnnotationScannerUtil`, `Lc4jAnnotations`) rather
than consuming quarkus-langchain4j's build items broadly. Across the whole deployment jar, the only
quarkus-langchain4j / langchain4j types referenced are:

| Referenced type | Kind |
|---|---|
| `io.quarkiverse.langchain4j.agentic.deployment.DetectedAiAgentBuildItem` | build item (exactly one) |
| `dev.langchain4j.agentic.planner.AgenticSystemTopology` | runtime class |

**`DetectedAiAgentBuildItem` is API-identical between 1.13.3 and 1.14.1** — verified by `javap -p` on
both jars. Same seven fields, same constructor arity and order, same eight public methods, same static
`allIfaces`:

```java
public final class DetectedAiAgentBuildItem extends MultiBuildItem {
  private final ClassInfo iface;
  private final List<MethodInfo> agenticMethods;
  private final MethodInfo chatModelSupplier;
  private final String modelName;
  private final List<MethodInfo> mcpToolBoxMethods;
  private final List<MethodInfo> toolBoxMethods;
  private final List<MethodInfo> skillsMethods;
  public DetectedAiAgentBuildItem(ClassInfo, List<MethodInfo>, MethodInfo, String,
                                  List<MethodInfo>, List<MethodInfo>, List<MethodInfo>);
  public ClassInfo getIface();
  public List<MethodInfo> getAgenticMethods();
  public MethodInfo getChatModelSupplier();
  public String getModelName();
  public List<MethodInfo> getMcpToolBoxMethods();
  public List<MethodInfo> getToolBoxMethods();
  public List<MethodInfo> getSkillsMethods();
  public static Set<ClassInfo> allIfaces(Collection<DetectedAiAgentBuildItem>);
}
```

**Still unverified:** `dev.langchain4j.agentic.planner.AgenticSystemTopology` across the
langchain4j-agentic versions (1.20.2-beta30 here; Flow 1.1.3 compiled against whatever qlc4j 1.13.3
managed). It is a **beta** `dev.langchain4j` class, so it is the likeliest remaining break point. A
mismatch would surface as `NoSuchMethodError`/`NoClassDefFoundError` **at augmentation**, which is
exactly what the one-`@SequenceAgent` boot test would catch.

### Scope check — the Ollama deployment dep does not leak

`quarkus-flow-langchain4j-deployment`'s own dependency scopes:

| Dependency | Scope |
|---|---|
| `io.quarkiverse.flow:quarkus-flow-langchain4j` | compile |
| `io.quarkus:quarkus-arc-deployment` | compile |
| `io.quarkiverse.flow:quarkus-flow-deployment` | compile |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic-deployment` | compile |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama-deployment` | **test** |
| `io.quarkus:quarkus-junit-internal`, `assertj-core`, `quarkus-playwright`, `quarkus-rest-jackson-deployment` | test |

So adding `quarkus-flow-langchain4j` does **not** pull the Ollama extension into this app — relevant
because the default (OpenAI) profile does not otherwise have it.

---

## Pre-settled research (carried from the plan; not re-verified here)

| Claim | Status |
|---|---|
| Flow's annotation compiler supports `@SequenceAgent`, `@ParallelAgent`, `@LoopAgent`, `@ConditionalAgent` | Confirmed |
| `@HumanInTheLoop` is **not** supported by that compiler | Confirmed (zero occurrences in the repo) |
| Flow does **not** implement `AgenticScopeStore` / `AgenticScopePersister` | Confirmed (zero occurrences) |
| HITL is instead DSL-based: `emitJson` → `listen` → `switchWhenOrElse` | Confirmed |
| **Quarkus Messaging is NOT required**; `injectEventConsumers` logs *"No EventConsumer bean found; using default fallback"* | Confirmed |
| A waiting instance can be woken by an **in-process** publish (`app.eventPublishers().iterator().next().publish(...)`) | Confirmed (`ListenUntilCurrentTest`) |
| `toAny` exists (multi-event `listen` filter) | Confirmed |
| SmallRye **in-memory connector** works with the messaging bridge, preserving CloudEvent metadata | Confirmed |
| Four correlation strategies: `extensionByInstanceId`, `dataByInstanceId`, `dataAs(Class, predicate)`, `dataFields(...)` | Confirmed |
| `quarkus-flow-jpa` rides the app's datasource; creates `workflow_instance_entity`, `task_info_entity`, `cloud_event_entity` | Confirmed |
| Flow does **not** retain completed instances | Confirmed (`AgenticWorkflowWithJpaPersistenceIT` javadoc) |
| On restore, Flow starts a **new span linked** (not parented) to the original trace | Confirmed (OTel ADR) |
| `quarkus-flow-langchain4j` is **Preview** (no backward-compat guarantee) | Confirmed |

### Two corrections to that table, found while reading the docs directly

1. **"never re-executes completed tasks" is NOT in `persistence.html`.** The page only says execution
   resumes *"from its last recorded checkpoint"*, and that state is written *"every time a task completes
   or pauses"*. It makes no claim about skipping completed tasks. **This makes question A4 more
   load-bearing, not less** — the skip behaviour is now unsourced as well as contradicted.
2. **Neither `persistence.html` nor `idempotency-correlation.html` documents any query or
   cancel/terminate API** for waiting instances. `idempotency-correlation.html` explicitly says that for
   singleton behaviour *"you must implement it yourself, for example with a database lock or lease."*
   Questions C8 and C9 are therefore code-level questions. **The API inventory below largely answers
   them anyway** — see § C8/C9.

### Other doc facts worth keeping

- **`agent(...)` vs `function(...)`:** the docs use `agent(name, fn, ResultType.class)` for a plain
  `@RegisterAiService` agent, and `function(name, fn, ResultType.class)` for an **annotated agentic
  pattern bean** injected via `@Inject`. Both exist in `FlowDSL` (signatures below). The intake root is
  an annotated pattern bean → `function(...)`, as the plan assumed.
- **HITL sample, verbatim from the docs** (the composition question A1 is about whether this can follow a
  `function(...)` task in the same list):
  ```java
  emitJson("org.acme.email.review.required", CriticAgentReview.class),
  listen("waitHumanReview", toOne("org.acme.newsletter.review.done").first()),
  switchWhenOrElse(
    (HumanReview h) -> ReviewStatus.NEEDS_REVISION.equals(h.status()),
    "draftAgent", "sendNewsletter", HumanReview.class)
  ```
- **A4's contradiction, as the docs actually read:** build time "generates a standalone
  `WorkflowDefinition` for each annotated method" and `@ParallelAgent` produces "a fork-join style
  workflow where each branch represents one of the sub-agents" — i.e. real workflow structure. But the
  *calling* workflow invokes the injected bean as **one task** ("call that entire pattern as a single
  task"). Whether runtime dispatch goes through the CDI proxy or the generated per-agent call tasks is
  **not asserted anywhere on the page**. Unanswered by docs; settle empirically.
- **Persistence config:** tables are auto-created via `quarkus.hibernate-orm.database.generation=update`
  ("convenient for development and testing"); production guidance is **Flyway** with
  `src/main/resources/db/migration/V1__create_tables.sql`. DDL is shipped for H2, MySQL, PostgreSQL,
  Oracle, MSSQL. JPA "must also configure a Quarkus JDBC driver and datasource" — implying reuse of the
  app's own datasource; **no dedicated/named persistence unit is mentioned** (relevant to B5).
  `task_info_entity` has FK `fk_task_workflow_instance` → `workflow_instance_entity` on composite
  `(application_id, instance_id)`. `application_id` is part of the PK of all three tables, but **how it
  is derived is never explained** (B7 stays open).
- Exactly **one** of `quarkus-flow-redis` / `quarkus-flow-jpa` / `quarkus-flow-mvstore` should be present.
- **Correlation for the business key (C10):** `dataAs(Class, predicate)` is the documented fit, and the
  docs' own example correlates on a business key:
  ```java
  listen("waitApproval",
    toOne(consumed("org.acme.order.approval.done")
      .dataAs(Order.class, (order, wfCtx, taskCtx) -> {
        Order current = (Order) wfCtx.currentData();
        return order.orderId().equals(current.orderId());
      })))
  ```
  The docs also warn: use a **business key** as the idempotency key, *not* the workflow instance id,
  because separate instances may touch the same entity. `X-Flow-Instance-Id` is auto-added and is
  "useful for correlation and tracing, but should not be used as a deduplication key". Instance id is a
  ULID from `ctx.instanceData().id()` (e.g. `01K9GDCXJVN89V0N4CWVG40R7C`). Keeping both — instance id as
  the routing key, claim id / `Message-ID` as the business key — is the documented pattern and fits
  `Claim.reviewRunId`.

---

## API inventory (gathered by `javap` — the expensive part; reuse this when resuming)

### `io.quarkiverse.flow.Flow` / `Flowable` — how a workflow bean is declared and started

```java
public abstract class Flow implements Flowable {
  protected WorkflowDefinition definition;
  protected void init();
  public abstract Workflow descriptor();          // you implement this
  public final WorkflowDefinition definition();
  public WorkflowInstance instance();
  public WorkflowInstance instance(Object input);
  public Uni<WorkflowModel> startInstance(Object input);
  public Uni<WorkflowModel> startInstance();
}

public interface Flowable {
  Workflow descriptor();
  default WorkflowDefinitionId id();
  default String identifier();
  WorkflowDefinition definition();
}
```

### `io.quarkiverse.flow.dsl.FlowDSL` — the task constructors needed for A1

```java
// invoke an annotated agentic pattern bean as one task
static <T,R> FuncCallStep<T,R> function(String, Function<T,R>, Class<T>, Class<R>);
static <T,R> FuncCallStep<T,R> function(String, Function<T,R>, Class<T>);
static <T,R> FuncCallStep<T,R> function(String, SerializableFunction<T,R>);
static <T,R> FuncCallStep<T,R> function(Function<T,R>, Class<T>, Class<R>);

// invoke a plain @RegisterAiService agent
static <T,R> FuncCallStep<T,R> agent(String, UniqueIdBiFunction<T,R>, Class<T>);
static <T,R> FuncCallStep<T,R> agent(String, UniqueIdBiFunction<T,R>);

// emit the review-required event
static <T> EmitStep emitJson(String type, Class<T>);
static <T> EmitStep emitJson(String name, String type, Class<T>);

// wait for the decision
static ListenStep listen(FuncListenSpec);
static ListenStep listen(String name, FuncListenSpec);
static FuncListenSpec toOne(String);
static FuncListenSpec toOne(FuncEventFilterSpec);
static FuncListenSpec toAny(String...);          // the C9 two-event workaround
static FuncListenSpec toAll(String...);

// branch on the decision
static <T> FuncTaskConfigurer switchWhenOrElse(Predicate<T>, String then, String orElse, Class<T>);
static <T> FuncTaskConfigurer switchWhenOrElse(String name, Predicate<T>, String, String, Class<T>);
static <T> FuncTaskConfigurer switchWhenOrElse(Predicate<T>, String, FlowDirectiveEnum, Class<T>);
static <T> FuncTaskConfigurer switchWhen(Predicate<T>, String, Class<T>);
static FuncTaskConfigurer switchCase(String, Consumer<FuncSwitchTaskBuilder>);
static <T> SwitchCaseSpec<T> caseOf(Predicate<T>, Class<T>);
static Consumer<FuncSwitchTaskBuilder> cases(SwitchCaseConfigurer...);
static FuncTaskConfigurer caseDefault(String);

// structure / misc
static Consumer<FuncTaskItemListBuilder> tasks(FuncTaskConfigurer...);
static FuncTaskConfigurer subflow(String, Consumer<WorkflowTaskBuilder>);
static <T,V> FuncTaskConfigurer forEach(...);    // several overloads
static FuncTaskConfigurer tryCatch(...), raise(...), wait(Consumer<TimeoutBuilder>);
static Consumer<TimeoutBuilder> timeoutDays/Hours/Minutes/Seconds/Millis(int);
static Consumer<ScheduleBuilder> every/cron/after/on(...);
```

`switchWhenOrElse` has **16 overloads** (predicate/serializable-predicate × named/unnamed ×
`String`/`FlowDirectiveEnum` orElse × with/without `Class<T>`). Pick the
`(String name, Predicate<T>, String then, String orElse, Class<T>)` one for a named, typed branch.

### In-process resume (A2/A3) — `WorkflowApplication` + `EventPublisher`

```java
public class WorkflowApplication {
  public Collection<EventPublisher> eventPublishers();
  public EventConsumer eventConsumer();
  public Map<WorkflowDefinitionId, WorkflowDefinition> workflowDefinitions();
  public WorkflowDefinition workflowDefinition(Workflow);
  public WorkflowInstanceIdFactory idFactory();
  public boolean isLifeCycleCEPublishingEnabled();
  public boolean isStatusChangePublishingEnabled();
}

public interface EventPublisher extends AutoCloseable {
  CompletableFuture<Void> publish(CloudEvent);
  default void publishLifeCycle(CloudEvent);
}
```

So the resume is `app.eventPublishers().iterator().next().publish(cloudEvent)` — matching
`ListenUntilCurrentTest`. Note `eventPublishers()` returns a **`Collection`**, so a REST resource must
pick one (or iterate); there is no single-publisher accessor.

### C8 — querying a waiting instance: **the API exists**

```java
public interface WorkflowInstanceData {
  String id();
  Instant startedAt();
  Instant completedAt();
  WorkflowModel input();
  WorkflowStatus status();          // <- the C8 answer
  WorkflowModel context();
  <T> Optional<T> findMetadata(String, Class<T>);
}

public enum WorkflowStatus {
  PENDING, RUNNING, WAITING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
}
```

`WAITING` is a first-class status, and `status()` is synchronous. Lookup by id comes from the
persistence reader:

```java
public interface PersistenceInstanceReader extends AutoCloseable {
  default Stream<WorkflowInstance> scanAll(WorkflowDefinition);
  Stream<WorkflowInstance> scanAll(WorkflowDefinition, String);
  Optional<WorkflowInstance> find(WorkflowDefinition, String instanceId);   // <- by id
}

public interface PersistenceInstanceOperations extends CorrelationOperations {
  Stream<PersistenceWorkflowInfo> scanAll(String, WorkflowDefinition);
  Optional<PersistenceWorkflowInfo> readWorkflowInfo(WorkflowDefinition, String);
  void writeInstanceData/writeRetryTask/writeCompletedTask/writeStatus(...);
  void removeProcessInstance(WorkflowContextData);
  void clearStatus(WorkflowContextData);
}

public record PersistenceWorkflowInfo(
  String id, Instant startedAt, WorkflowModel input, WorkflowStatus status,
  Map<String, PersistenceTaskInfo> tasks) { }   // <- tasks map = "waiting at task Y"
```

**Provisional C8 answer:** `PersistenceInstanceReader.find(definition, instanceId)` →
`WorkflowInstance.status() == WAITING`, and `PersistenceWorkflowInfo.tasks()` keyed by task name gives
the "waiting at task `waitHumanReview`" half. **Open:** whether `PersistenceInstanceReader` is an
injectable CDI bean in quarkus-flow-jpa (it is a plain interface in `serverlessworkflow-persistence-api`;
`DefaultPersistenceInstanceReader` is the impl). That is the single thing to verify to close C8.

### C9 — cancel/terminate: **the API exists**

```java
public interface WorkflowInstance extends WorkflowInstanceData {
  CompletableFuture<WorkflowModel> start();
  WorkflowModel output();
  <T> T outputAs(Class<T>);
  boolean suspend();
  boolean cancel();                               // <- the C9 answer
  boolean resume();
  default CompletableFuture<Boolean> suspendFuture();
  default CompletableFuture<Boolean> cancelFuture();
  default CompletableFuture<Boolean> resumeFuture();
  <T> T addMetadataIfAbsent(String, Supplier<T>);
  void removeMetadata(String);
}
```

**Provisional C9 answer:** `cancel()` is synchronous and returns `boolean`, so "a customer reply
supersedes the waiting review" does **not** need the `toAny` two-event workaround — *provided* the
instance can be resolved via the reader (same open item as C8). The `toAny` fallback stays documented in
case the reader is not reachable. Also note `WorkflowStatus.CANCELLED` exists, which bears on E19
(whether cancelled/terminated instances leave rows — `removeProcessInstance` exists on
`PersistenceInstanceOperations`, suggesting they are deleted).

### D16 — Micrometer metrics appear to be built in

`quarkus-flow` runtime ships:

```
io.quarkiverse.flow.metrics.FlowMetrics
io.quarkiverse.flow.metrics.MicrometerExecutionListener
io.quarkiverse.flow.config.FlowMetricsConfig
io.quarkiverse.flow.tracing.TraceLoggerExecutionListener
io.quarkiverse.flow.config.FlowTracingConfig
io.quarkiverse.flow.config.FlowDevUIConfig / FlowDevUIBackendConfig
io.quarkiverse.flow.structuredlogging.StructuredLoggingListener / EventFormatter
io.quarkiverse.flow.health.WorkflowEngineHealthCheck
```

This repo already has Micrometer on the classpath, so `MicrometerExecutionListener` plus
`FlowMetricsConfig` is the concrete thing to compare against task 11's planned metrics. Not yet
enumerated (names/tags unknown) — that is the remaining D16 work.

---

## Questions — current state

| # | Question | State |
|---|---|---|
| **Gate** | Augments at 3.40.1/1.14.1 without downgrade | **PASSED**, except the `@SequenceAgent`-present path |
| A1 | `function(...)` + `emitJson`/`listen`/`switchWhenOrElse` in one task list | **Open.** All signatures exist and are compatible on paper; composition unproven |
| A2 | `listen` served by default fallback broker, no messaging module | Pre-settled as yes; not re-run here |
| A3 | In-process `publish(...)` wakes it; completed tasks skipped | **Open.** `publish` API confirmed; skip behaviour unproven **and now unsourced** (see correction 1) |
| A4 | Crash *inside* the agentic subflow — resumable or whole task re-run? | **Open. Pivotal.** Docs contradictory and silent |
| B5 | JPA entities join the app's persistence unit / schema management | **Open.** Docs imply app datasource, no named PU; needs `database.generation` check |
| B6 | Review survives a genuine Quarkus restart | **Open** |
| B7 | `auto-restore` with an unpinned application id; how derived | **Open.** Docs never explain derivation |
| C8 | Synchronous "is instance X waiting at task Y?" | **Provisionally YES** — `PersistenceInstanceReader.find` + `status()==WAITING` + `PersistenceWorkflowInfo.tasks()`. Open: is the reader CDI-injectable? |
| C9 | Terminate/cancel a waiting instance | **Provisionally YES** — `WorkflowInstance.cancel()`. Same open item |
| C10 | Correlate on business key, not `flowinstanceid` | **Provisionally YES** — `dataAs(Class, predicate)`, documented example matches |
| D11 | Span tree; `langchain4j.aiservices.<Agent>.<method>` leaf naming survives | **Open.** Guards the dataset-name invariant |
| D12 | `gen_ai.*` on Flow task spans; Langfuse typing | **Open.** Expect untyped; see note below |
| D13 | Does Flow tracing remove the synthetic CONSUMER span + `@AgentListenerSupplier`? | **Open** |
| D14 | `fork` propagates OTel context + MDC (is `@ParallelExecutor` still needed?) | **Open** |
| D15 | Resume is a genuinely linked span | Pre-settled by the ADR; not verified empirically |
| D16 | Free Micrometer metrics vs task 11 | **Partially** — classes identified, names/tags not enumerated |
| E17 | Dev UI: graph, instance state, *waiting* instance, trace drill-down (+ screenshot) | **Open** |
| E18 | `@QuarkusTest` start → assert waiting → publish → assert outcome, both Ollama profiles | **Open** |
| E19 | Terminated instances leave no rows | **Open.** `removeProcessInstance` exists, suggesting yes |

### Note on D12 / cost #3 (Langfuse observation typing)

Per the user: they **own the `quarkus-langfuse` extension**, and the `ai.scoring` package is explicitly a
staging area for logic that should be generalized and contributed upstream (to Flow, langchain4j, or
quarkus-langfuse). So "Flow task spans carry workflow semantics, not GenAI semconv, and would render as
untyped SPAN ancestors" is **not a blocker to price** — it is a fix the user can own, in a package that
already exists for exactly this, with `AiServiceDatasetSpanProcessor` as the in-repo precedent for
stamping attributes onto spans the app does not own.

Reframe D12/D13 as **"where does the fix live"**: an `ai.scoring` `SpanProcessor` stamping
`gen_ai.operation.name=invoke_agent` onto Flow task spans, with a path to contributing it to Flow (or to
Langfuse's own typing rules). The leaf AI-service spans are unaffected either way, so the dataset-name
invariant (`langchain4j.aiservices.<SimpleClassName>.<method>`) is safe regardless — **but D11 must still
confirm it empirically**, because the invariant silently degrades (drift detection returns `success` on
`SampleLoadException`, so a broken name looks like a pass).

---

## Verdict

**None yet.** The gate has passed everything tested so far, and C8/C9/C10 — the three questions the plan
flagged as possible blockers for the `404`/`409` review contract — all look answerable in Flow's favour
on API evidence. But A1, A3, A4 and B6 (the functional core of the decision criteria) are unproven, so no
recommendation can honestly be made yet.

**The decision criteria, restated:** recommend Flow only if the gate passes at 3.40.1/1.14.1 with no
downgrade **and** A1–A3 and B6 work **and** C8 has an answer or the `404`/`409` contract survives another
way. On current evidence the gate and C8 are satisfied; A1–A3, A4 and B6 remain.

---

## Resuming this spike

### Current filesystem state

| Thing | State |
|---|---|
| `main` / primary working tree | **Unchanged.** Only pre-existing `?? .junie/`, `?? CODE_STANDARDS.md` |
| Worktree `…-spike-flow`, branch `spike/flow-hitl` | **Exists**, clean; pom patch committed as `c3ab343`, never pushed |
| Spike test sources | **None written yet** |
| `target/` in the worktree | Contains a successful `package` build |

### Environment preconditions (a cold agent cannot infer these)

- **Stub API keys are sufficient.** Export `OPENAI_API_KEY=change-me COHERE_API_KEY=change-me`. Under
  `-Pollama` the profile stubs them anyway, and `%test` sets `initialize-on-startup: false` /
  `score-session: false`, so the Langfuse initializer and session scorer are inert — **no real
  `COHERE_API_KEY` or `GEMINI_API_KEY` is needed for the build**. (This is the same reason CI passes with
  only a stubbed key.)
- **Podman must be running.** The Dev Services this app boots — PostgreSQL, WireMock, Langfuse, plus the
  GreenMail/Roundcube Compose stack — all need the container runtime. It reaches Podman through
  `/var/run/docker.sock`.
- **Pass `-Dquarkus.quinoa.enabled=false` on gate builds** to skip the npm/Jest frontend leg. It is pure
  cost for this spike, and a failing Jest test would otherwise fail a `@QuarkusTest` boot.
- **Delete `easy-rag-embeddings.json` when switching between the default and Ollama profiles.** OpenAI
  (1536-dimension) and `snowflake-arctic-embed` (1024-dimension) vectors are incompatible and
  `reuse-embeddings` will reload the stale file. Spike test profiles should set
  `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF` anyway, which sidesteps it.
- **Any WireMock-backed profile must pin the provider**, not just the base URL:
  `quarkus.langchain4j.parasol-chat.chat-model.provider=openai` and
  `quarkus.langchain4j.embedding-model.provider=openai`. Under `-Pollama` the request otherwise never
  goes near the OpenAI client and the stub is silently bypassed, leaving the test on a real model.

### To continue

1. **Finish the gate (highest value, ~15 min).** In the worktree, add a minimal `@SequenceAgent` root
   with two trivial AI leaf agents in `src/test/java/org/parasol/spike/flow/`, plus a `@QuarkusTest` that
   just boots. This forces `FlowLangChain4jProcessor` to consume `DetectedAiAgentBuildItem` and to touch
   `AgenticSystemTopology` — the only two coupling points, and the one remaining gate risk. Remember the
   leaf agents need
   `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`
   (task 01 Q6/Q19) and a test profile that stubs the three API keys, sets
   `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF`, pins
   `quarkus.langchain4j.parasol-chat.chat-model.provider=openai` and
   `quarkus.langchain4j.embedding-model.provider=openai`, and disables Langfuse init/session scoring.
2. **A1 next** — add the Flow workflow (`extends Flow`, implement `descriptor()`) composing
   `function(...)` → `emitJson` → `listen(toOne(...))` → `switchWhenOrElse(...)`. Use the signatures in
   the API inventory above; they are copied from `javap` and are correct for 1.1.3.
3. **A3** — `@Inject WorkflowApplication`, then
   `app.eventPublishers().iterator().next().publish(ce)`; count WireMock requests before/after to prove
   completed tasks are skipped.
4. **A4** — the pivotal one. Kill the JVM between two sub-agents, restart, inspect `task_info_entity`.
5. **C8 close-out** — check whether `PersistenceInstanceReader` / `PersistenceInstanceStore` are
   injectable CDI beans in `quarkus-flow-jpa` (unzip
   `~/.m2/repository/io/quarkiverse/flow/quarkus-flow-jpa/1.1.3/` and look for producers / `@ApplicationScoped`).
   That one check closes C8, C9 and E19 together.
6. **B5** — the project currently uses `drop-and-create` in `%prod`/`%openshift` and Dev Services
   defaults elsewhere; see the standing note below.

### When the spike is done

`PLAN.md`'s Execution Steps mark these **MANDATORY**, and they apply to this task:

1. **Record the verdict** in `PLAN.md` → *Flow Spike Results (task 01b)* (step 4), replacing the
   IN-PROGRESS header, and tick the Done When boxes in `task-01b-flow-spike.md`.
2. **Await the user's approval** before moving on (step 5).
3. **Re-assess the remaining task list** — split/merge/remove/reorder/add (step 6).
4. **Present the findings even if nothing needs to change**, and wait for approval (step 7).

Then, depending on the verdict:

- **Adopt Flow** → tasks **03** (drop the scope store), **07** (rewrite), **08** (policy-check ordering),
  **10** (async decision) and **11** (observability) all change, and
  `docs/design/email-claim-intake.md` + `docs/design/claim-intake-agents.puml` **go back through the
  review gate** — the merged design describes the LangChain4j-native approach.
- **Reject Flow** → keep the merged design and file the upstream ask: **Flow implementing
  `AgenticScopeStore` / registering via `AgenticScopePersister`**. That would replace only
  `DatabaseAgenticScopeStore` while leaving the design's shape, the static `@HumanInTheLoop`, the
  synchronous `409` and the ordered processor rules untouched — the highest-leverage request.

Either way, remove the worktree and keep the branch (see § Teardown).

### Do not

- **Do not re-verify the pre-settled research table** above — that work is done, and two corrections to it
  are already recorded.
- **Do not push `spike/flow-hitl`.** It is throwaway and stays local, matching task 01's
  `spike/agentic-hitl` precedent. The pom patch is reproduced verbatim in this document, so the branch is
  reproducible rather than precious.
- **Do not modify production code.** This task's only outputs are findings and a recommendation.
- **Do not start task 03** (or write task 07) before the verdict lands — pre-empting the scope-store
  decision is the entire reason this spike exists.
- **Do not relax `EmailEndsAppropriatelyOutputGuardrail`** or any guardrail to make a spike test pass.

### Useful commands

```bash
# the gate
cd /Users/edeandre/workspaces/demos/non-deterministic-no-problem-spike-flow
OPENAI_API_KEY=change-me COHERE_API_KEY=change-me ./mvnw -B -Pollama clean test-compile -DskipTests
OPENAI_API_KEY=change-me COHERE_API_KEY=change-me ./mvnw -B -Pollama package -DskipTests -Dquarkus.quinoa.enabled=false

# spike tests, both Ollama legs (CI requires both)
./mvnw verify -Pollama
./mvnw verify -Pollama-openai

# Dev UI inspection for E17
./mvnw -Pollama quarkus:dev   # then http://localhost:8080/q/dev

# re-inspect the APIs (jars already in ~/.m2)
unzip -q ~/.m2/repository/io/quarkiverse/flow/quarkus-flow/1.1.3/quarkus-flow-1.1.3.jar -d /tmp/flowrt
javap -p -cp /tmp/flowrt io.quarkiverse.flow.dsl.FlowDSL
```

### Teardown when done

```bash
cd /Users/edeandre/workspaces/demos/non-deterministic-no-problem
git worktree remove ../non-deterministic-no-problem-spike-flow   # add --force if dirty
git branch   # spike/flow-hitl is KEPT locally, never pushed
```

---

## Standing note (not a task item): durable state is wiped on boot in every profile

`%prod` and `%openshift` set `schema-management.strategy: drop-and-create`, and dev/test take the Dev
Services default. Any durable-state table is therefore wiped on boot — **the merged design's agentic
scope table included**. So "the paused run survives restarts" (design doc step 8) does not hold in any
current profile. Flow would not change this; it would add three more tables to the same drop. Worth
raising on #216 independently of this spike's verdict.
