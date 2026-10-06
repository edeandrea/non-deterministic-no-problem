# Task 01b: quarkus-flow Spike

**Type:** Exploration (throwaway, timeboxed)

## Goal

Decide, on evidence rather than docs, whether [quarkus-flow](https://docs.quarkiverse.io/quarkus-flow/dev/index.html)
should replace the durable human-in-the-loop machinery in the merged #216 design, **before** task 07 is
executed and ideally before task 03 commits to a scope store.

This task changes **no production code**. Its only outputs are spike findings and a recommendation.

## Background

The merged design (PR #220) implements the review gate with `quarkus-langchain4j-agentic`:

- a static `@HumanInTheLoop` `ClaimReviewAgent` returning `SuspendedResponse`
- a custom `DatabaseAgenticScopeStore` registered via `AgenticScopePersister.setStore`
- resume by re-running the root with the same `Message-ID`, relying on completed agents being skipped
- **two acknowledged internal-API touch points:** `SuspendedResponse` (`dev.langchain4j.agentic.internal`)
  and the `@Internal` `DefaultAgenticScope`

Flow would replace exactly the parts that are workarounds. Adopting it would delete:
`DatabaseAgenticScopeStore` + its entity, the `AgenticScopePersister.setStore` registrar, the startup
round-trip self-test, the `ShutdownEvent` reset, the `allowDeserializationType` allowlist, the hand-rolled
replay in `ClaimReviewService`, **and both internal-API touch points** — most of task 03's scope-store
deliverable and much of task 07. It would also replace the hand-rolled observability of spike items 12–18.

**Already settled by research — do not re-verify.** See `spike-results-flow.md` → "Pre-settled research"
for the full table (supported annotations, `@HumanInTheLoop` unsupported, no `AgenticScopeStore` in Flow,
messaging not required, in-process publish works, `toAny`, the four correlation strategies, JPA tables,
completed instances not retained, linked resume spans, Preview status, version skew).

## What to Do

> **Start here — this task is already in progress and suspended.** The spike worktree
> `../non-deterministic-no-problem-spike-flow` and branch `spike/flow-hitl` **already exist on this
> machine**, with the pom patch committed as `c3ab343` (local only, never pushed). Step 0 (the gate) has
> **passed** everything tested so far; C8–C10 are provisionally answered on API evidence.
> Go to [`spike-results-flow.md`](spike-results-flow.md) → *Resuming this spike* and begin at its step 1:
> finish the gate by adding one `@SequenceAgent` and booting a `@QuarkusTest` (~15 min). Read the
> *Environment preconditions* and *Do not* subsections there before running anything.

- **Where and when it runs:** now, before task 07, ideally before task 03.
  - Throwaway local branch in a **separate git worktree**, never merged, never pushed. `main` and the
    working tree untouched; no production file modified.
- **Step 0 — the gate (stop here if it fails).** Add `io.quarkiverse.flow:quarkus-flow`,
  `quarkus-flow-langchain4j` and `quarkus-flow-jpa` at the latest release (1.1.3; re-check for 1.2.0
  final) **without** touching `quarkus.platform.version` (3.40.1) or `quarkus.langchain4j.version`
  (1.14.1), then augment.
  - **Augments cleanly** → continue.
  - **Fails** → record the exact augmentation error, try `1.2.0.CR3`, and only then evaluate a 1.13.3
    downgrade. A required downgrade is a **recommend-against** outcome on its own: it reverts #212 and
    invalidates `spike-results.md`. Stop and report rather than proceeding.
  - Note `./mvnw test-compile` alone is **not** the gate — it proves resolution and compilation only.
    Quarkus augmentation runs at `package`/`@QuarkusTest` boot, and the `quarkus-flow-langchain4j`
    build-time compiler only fires when an agentic annotation is actually present.
- Answer the questions below in a scratch package, with WireMock-stubbed LLM responses, following the
  project's existing mocking pattern.

### A. Topology and suspend/resume
1. Can an existing `@SequenceAgent` bean be invoked as a Flow task via `function(agent::method, T.class)`
   and be followed by `emitJson` → `listen` → `switchWhenOrElse` in the same `.tasks(...)` list? (The
   docs show both halves but never this composition.)
2. Does the review wait work with **no** `EventConsumer`/`EventPublisher` bean and no messaging module —
   i.e. does the engine's default fallback broker serve `listen`?
3. Resume by in-process `publish(...)` from a REST resource: does the instance wake, and are the
   already-completed tasks skipped (assert via WireMock request counts, as task 01 did)?
4. With the agentic subflow as one Flow task, is a crash *inside* it resumable, or does auto-restore
   re-run the whole task (all four LLM calls)? **Pivotal:** the docs contradict themselves — the
   translation section says a `@SequenceAgent` becomes "a linear sequence of standard `call` tasks", the
   runtime section says execution goes "through the CDI proxy". Settle it by killing the JVM between two
   sub-agents and inspecting `task_info_entity`.

### B. Persistence and durability
5. Do `quarkus-flow-jpa`'s entities join the app's persistence unit, and does Hibernate create them under
   the current schema management, or is the shipped DDL required?
6. Genuine Quarkus restart: does a review suspended before the restart resume after it (the task 01
   restart test, re-run against Flow)?
7. Does `auto-restore` behave correctly when the application ID is not pinned, and how is it derived?

### C. Correlation and the review contract
8. Is there an in-process, synchronous way to ask "is instance X waiting at task `waitHumanReview`?" —
   needed for the `404`/`409` contract before publishing.
9. Is there a **terminate/cancel** API for a waiting instance? Needed for "a customer reply supersedes the
   waiting review". If not, verify the `toAny` two-event workaround (a `claim.review.superseded` event
   routed to an end state) and record that it is asynchronous.
10. Correlate on the business key (claim id / `Message-ID`) via `dataFields(...)` or
    `dataAs(Class, predicate)` rather than `flowinstanceid`, so `Claim.reviewRunId` stays meaningful.

### D. Observability (compare directly against `spike-results.md` items 12–18)
11. What spans does one intake run produce, and does the leaf `langchain4j.aiservices.<Agent>.<method>`
    naming survive unchanged? (Guards the dataset-name invariant in `AiServiceDatasetSpanProcessor` /
    `DriftDetectionOutputGuardrail`.)
12. Do Flow task spans carry any `gen_ai.*` attributes, and how does Langfuse type them? If untyped,
    decide whether to stamp `gen_ai.operation.name=invoke_agent` or accept untyped ancestors.
13. Does Flow's per-task tracing remove the need for the synthetic caller CONSUMER span and the
    `@AgentListenerSupplier`?
14. Does Flow's `fork` propagate OTel context and MDC to branches — i.e. is `@ParallelExecutor` with
    `Context.taskWrapping` still needed (spike item 14 / Q17)?
15. Is the resume genuinely a linked span, as the ADR specifies?
16. What Micrometer metrics appear for free, and do they overlap the metrics planned in task 11?

### E. Dev UI and testing
17. What does the Dev UI show for this pipeline — graph, instance state, a *waiting* instance, trace
    drill-down? Capture a screenshot for the demo-value judgement.
18. Can a `@QuarkusTest` start a workflow, assert it is waiting, publish the decision and assert the
    outcome, with the LLM WireMock-stubbed — under **both** Ollama profiles, as CI requires?
19. Confirm terminated instances leave no rows (per the IT javadoc), so task 08's "no leaked rows"
    assertions have an equivalent.

- Record every answer with evidence (test name, observed output, versions) in
  `spike-results-flow.md`, and summarise with a **verdict for task 07** in `PLAN.md` → Shared Context.
- Remove the worktree afterwards. Keep the local branch, unpushed, for reference.

## Files/Areas

- A separate git worktree on a throwaway local branch (`pom.xml` flow dependencies, scratch test
  package); never merged or pushed
- `.tasks/05-gh216-agentic-email-intake/spike-results-flow.md` (new)
- `.tasks/05-gh216-agentic-email-intake/PLAN.md` → Shared Context (Flow Spike Results + verdict)

## Key Points

- **Costs to price, not discover:** (1) the version skew gate; (2) Preview status, traded against two
  narrow internal APIs already proven by 32 spike tests and quarantined behind three named classes;
  (3) Langfuse observation typing; (4) the synchronous review contract (`404`/`409` before publishing).
- **Cost (3) is softer than it looks:** the user owns the `quarkus-langfuse` extension, and `ai.scoring`
  is an explicit staging area for logic that should eventually be generalized upstream (contributed to
  Flow, langchain4j, or quarkus-langfuse). Attribute/observability gaps therefore have both a home and an
  upstream path, and `AiServiceDatasetSpanProcessor` is the in-repo precedent for stamping attributes
  onto spans the app does not own. Treat Q12/Q13 as "where does the fix live", not "is this a blocker".
- The project's LLM-mocking pattern is a `QuarkusTestProfile` pointing model `base-url`s at the WireMock
  dev service, plus `@ConnectWireMock` stubs registered in `@BeforeEach`. A mocking profile must **also**
  pin `chat-model.provider=openai`, or `-Pollama` routes around the stub.
- Flow has **no** dedicated test harness or assertion library (no `quarkus-flow-test`, no
  `WorkflowAssert`). Workflows are plain CDI beans; this is ergonomics, not a provided test API.

## Decision Criteria

Recommend Flow only if **all** hold: the gate passes at 3.40.1/1.14.1 with no downgrade; questions 1–3
and 6 work; and either question 8 has an answer or the `404`/`409` contract can be preserved another way.

Otherwise keep the merged design and file an upstream issue for the highest-leverage ask — **Flow
implementing `AgenticScopeStore` / registering via `AgenticScopePersister`**, which would let LangChain4j
keep owning suspend/resume and replace only `DatabaseAgenticScopeStore`, leaving the merged design's
shape, the static `@HumanInTheLoop`, the synchronous `409` and the ordered processor rules untouched.

## Done When

- [ ] `spike-results-flow.md` answers every question with evidence, or records the gate failure.
- [ ] `PLAN.md` carries a Flow verdict for task 07.
- [ ] If the verdict is "adopt": a note of what changes in task 03 (drop the scope store), task 07
      (rewrite), task 08 (policy-check ordering), task 10 (async decision) and task 11 (observability),
      plus the fact that `docs/design/email-claim-intake.md` and `docs/design/claim-intake-agents.puml`
      go back through the review gate.
- [ ] The worktree is removed; the throwaway branch is kept locally and was never pushed.
- [ ] `main` and the working tree are unchanged; no production file was modified.
