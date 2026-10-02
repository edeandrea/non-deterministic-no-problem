# Task 01: Agentic Module Spike

**Type:** Exploration

## Goal

Prove, with throwaway tests, every `quarkus-langchain4j-agentic` behaviour this issue relies on, and
record the answers before the design (task 02) is written and before any feature code exists.

## What to Do

- **Where and when it runs:** now, before issue 1 (#212) and before the design. The results shape the design.
  - Create a throwaway local branch (e.g. `spike/agentic-hitl`) from `main`, checked out in a **separate git
    worktree**, so the user's working tree is untouched.
  - In that worktree only, bump `quarkus.langchain4j.version` to the latest stable release (1.14.1 at
    planning time; re-check before starting) and add `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic`.
    Confirm the build still succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
  - Nothing from the spike is merged or pushed. The agentic dependency is added for real in task 03
    (intake configuration), on top of issue 1.
- In a scratch package, using WireMock-stubbed LLM responses, verify:
  1. `@ModelName("claim-intake")` on an `@Agent` method picks the named model.
  2. `@InjectMock` works on an agent interface (the docs say agents are `@ApplicationScoped` beans).
  3. Nesting works: `@SequenceAgent` → `@ConditionalAgent` → `@ParallelAgent`. Values written by one
     agent's `outputKey` are visible to later agents and `@ActivationCondition` methods.
  4. An agent can return an enum and a record (structured output).
  5. An `@OutputGuardrails` annotation on an agent method runs. Check whether guardrail instances are
     CDI beans (constructor injection works) or reflection-created.
  6. Whether Easy RAG's retrieval augmentor is attached to agents automatically. If it is, find how to
     opt out (e.g. `@RetrievalAugmentorSupplier` returning none). The intake agents must not get policy RAG.
  7. The OpenTelemetry span names agents produce. Compare them with the
     `langchain4j.aiservices.<Interface>.<method>` convention used by `AiServiceDatasetSpanProcessor`
     and `DriftDetectionOutputGuardrail`, and record the impact (drift detection for agents is out of scope).
  8. `@Tool` methods on a CDI bean, used through `@ToolBox` on an agent, run with the expected arguments.
  9. The exact declarative API for a **static** `@HumanInTheLoop` method to return a `SuspendedResponse`,
     in the langchain4j-agentic version the spike uses (expected 1.20.2-beta30 with quarkus-langchain4j 1.14.1).
     Confirm Quarkus' build-time validation accepts it.
  10. A suspension inside a nested `@SequenceAgent`/`@ConditionalAgent` propagates to the root. Record
      whether the Quarkus agent proxy returns `ResultWithAgenticScope.suspended()` or throws
      `AgenticSystemSuspendedException`.
  11. Resume after clearing the in-memory registry, with a custom store: completed agents are not
      called again (WireMock request counts). Does the re-invocation need the original method
      arguments, and if so, how can they be reconstructed?
  12. The `AgenticScopeStore` method signatures; how and when the store is registered
      (`AgenticScopePersister.setStore` vs ServiceLoader); which thread calls it; and whether it can
      use Hibernate through `QuarkusTransaction.requiringNew()`.
  13. Serialization: the JSON codec handles records, enums, `LocalDate` and `Optional`; how the
      deserialization allowlist is registered.
  14. `evictAgenticScope` deletes from the store, and evicting a missing key is safe.
  15. Confirm `async = true` must not be combined with suspension.
  16. On the running app, the span tree for one email: leaf spans `langchain4j.aiservices.<Agent>.<method>`;
      whether a CDI `AgentListener` fires for composite and non-AI agents (including the HITL agent); how
      Langfuse types these observations.
  17. Whether a `@ParallelExecutor` returning `Context.taskWrapping(executor)` keeps the parallel sub-agents
      in the root trace, or a global `ExecutorProvider` is needed.
  18. The earliest startup hook that runs before any agent is built (for `AgenticScopePersister.setStore`)
      and before the first `MonitoredAgent` use.
- Record every answer, with the evidence (test name, observed output, versions used), in
  `.tasks/05-agentic-email-intake-tasks/spike-results.md`, and summarise the answers in `PLAN.md` →
  Shared Context → Spike Results. The task 02 design cites them as evidence.
- Remove the worktree afterwards. Keep the local branch, unpushed, for reference.

## Files/Areas

- A separate git worktree on a throwaway local branch (`pom.xml` version bump and agentic dependency, scratch test package); never merged or pushed
- `.tasks/05-agentic-email-intake-tasks/spike-results.md` (new), `PLAN.md` → Spike Results

## Key Points

- The project's LLM-mocking pattern is a `QuarkusTestProfile` that points model `base-url`s at the
  WireMock dev service, plus `@ConnectWireMock` stubs registered in `@BeforeEach`. There's no
  `src/test/resources`. Follow it.
- If an assumption fails (e.g. nesting or guardrails), record the workaround, and update the later task files before they're executed.

## Done When

- [ ] `spike-results.md` answers all eighteen questions with evidence, and `PLAN.md` Spike Results summarises them.
- [ ] `PLAN.md` records a feasibility verdict for suspend/resume through Quarkus. If it isn't feasible, stop and ask the user (task 07's decision gate).
- [ ] The worktree is removed; the local spike branch is kept and was never pushed.
- [ ] The user's working tree and `main` are unchanged.
