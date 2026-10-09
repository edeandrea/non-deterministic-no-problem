# Task 10b: Generic Conversation Context Core

**Type:** Code Modification (existing `main` code: the chat's session scoring path changes)

## Goal

The "conversation" concept (which interaction belongs to which conversation, and what happens when one ends)
is application-agnostic, so it's built as our own framework-neutral package, `ai.scoring.conversation`, ready to be
extracted later (user decision, 2026-10-07). The chat moves onto it with no behaviour change, and the intake
(tasks 08, 11) uses the same core. Observability never mixes with business or workflow logic: framework
lifecycles feed the core through adapters, and reactions (session scoring) are CDI event observers.

Runs **before task 11**, which adds the quarkus-flow and LangChain4j agentic adapters on top of this core.
Evidence for every design choice here: `spike-results-flow.md` → *Follow-up spikes 2–4*.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b. The core doesn't change. #1065 is in it, so task 11 expects
to drop its Flow task proxy and agentic adapter: once task 11 has proven that, check which core hooks it still needs
(`ConversationContext.callIn` from the starter, at least). Check `PLAN.md` → Execution Steps → 2a first.

## What to Do

- **Core** (`ai.scoring.conversation`, depends on the OTel API, CDI and MicroProfile Context Propagation only;
  no Quarkus, LangChain4j, chat-scope or Flow types):
  - **`ConversationContext`**: the one API for "this code belongs to conversation X", carried as **OTel baggage**
    under `gen_ai.conversation.id` (the carrier decision, user 2026-10-07):
    - `Optional<String> currentId()`
    - `Scope enter(String conversationId)`: makes a context carrying the baggage entry current
    - `<T> T callIn(String conversationId, Callable<T>)` / `void runIn(String conversationId, Runnable)`
  - **`ConversationIdSpanProcessor`**: a CDI `SpanProcessor` bean that copies the baggage entry onto every span at
    `onStart` (from the parent context passed to `onStart`, not `Context.current()`). Needed because `%test`
    turns the Langfuse processor off, and Tempo/LGTM shouldn't depend on it. It writes the same value
    quarkus-langfuse's `LangfuseSpanProcessor` does, so the two never conflict.
  - **Lifecycle events** (records): `ConversationEndedEvent(String conversationId)`. Add `ConversationStartedEvent` only
    if something consumes it. Firing is a core helper (e.g. `ConversationEvents.ended(id)`) that calls `fireAsync` and
    **logs any failure from the returned `CompletionStage`**: an exception in an async observer surfaces nowhere
    else, so a broken consumer would otherwise fail silently.
  - **Workaround for [quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354)**: one class, e.g.
    `OtelContextLeakGuard implements ThreadContextProvider`, registered in
    `src/main/resources/META-INF/services/org.eclipse.microprofile.context.spi.ThreadContextProvider`. It's the class
    proven in follow-up spike 4 (`Spike5OtelContextLeakGuard` on `spike/flow-baggage-guard`):
    - its own context type (SmallRye rejects a second `"OpenTelemetry"` type)
    - `currentContext()` records `QuarkusContextStorage.INSTANCE.current()` and the submitting thread
    - `endContext()` attaches `Context.root()` only when it's on another thread, there's no Vert.x context, and the
      current context is still the captured one (`==`)
    - `clearedContext()` is a no-op
    - the Javadoc links quarkus#54354 and quarkusio/quarkus#56805 and says **delete this when the fix ships** (4.0, or
      the 3.40 backport)

    This is the **one** core class that touches a Quarkus type (`QuarkusContextStorage`). Keep it in its own
    sub-package (e.g. `ai.scoring.conversation.quarkus`) so it's obvious it stays behind if the core is extracted.
- **Chat adapter** (`ai.scoring.conversation.chat`): move `ConversationalBaggageHandler` here, onto the core:
  - activate/deactivate through `ConversationContext` (same baggage key, same behaviour)
  - on `ChatScopeEnded`, fire `ConversationEndedEvent`; it **no longer depends on `SessionScoringService`**
- **Session scoring reacts to the event:** one `@ObservesAsync ConversationEndedEvent` observer (e.g.
  `SessionScoringListener`, next to `SessionScoringService` in `ai.scoring.evaluation`) calls
  `SessionScoringService.scoreSession`. That replaces the `Infrastructure.getDefaultExecutor()` hand-off; the async
  observer runs on a worker thread, so the blocking poll in `LangfuseSessionScoringService` is still fine.
  - Keep `score-session` gating where it is (`LangfuseSessionScoringService`).
  - Today only the chat fires the event. The intake firing it is a later decision (PLAN.md, Observability): the
    session scorer reconstructs exchanges assuming the chat's shape.
- **Layering rule, enforced by a test:** the core never imports an adapter, and adapters never import each other.
  Use a plain reflection/classpath test or ArchUnit if it's already available; don't add a dependency just for this.
- **Docs:** update `CLAUDE.md` (Tier 2 description), `docs/continuous-scoring-architecture.puml`,
  `docs/continuous-scoring-sequence.puml` and `docs/application-flow.puml` (re-render), and `README.md` if it names
  the handler.
- **Tests:**
  - `ConversationContext`: `enter`/`callIn` set and restore the baggage; nested ids restore the outer one
  - `ConversationIdSpanProcessor`: a span started inside `callIn` carries the id; one started outside carries none
  - **leak guard** (spike 3's probe as a regression test): submit tasks with distinct baggage to the injected
    `ManagedExecutor`, then tasks from a caller with none; the second batch sees **no** id. Plus: a thread with its
    own span and baggage keeps both after running a contextual task inline. *(On a fixed Quarkus this test passes
    without the guard; that's the signal to delete it.)*
  - concurrent: 8 `callIn` runs on the `ManagedExecutor`, every span carries exactly its own id
  - chat adapter: a chat-scope end fires exactly one `ConversationEndedEvent` with the scope id
  - scoring listener: the event triggers `scoreSession` once (mocked `SessionScoringService`); a throwing scorer is logged,
    not lost
  - layering test
  - **existing suites unchanged:** `LangfuseSessionScoringServiceTests` and the chat tests pass. These need a real
    OpenAI key (they were skipped in spike 4's full-suite run), so the user runs them: list them in `PLAN.md`.

## Files/Areas

- `src/main/java/ai/scoring/conversation/` (core), `…/conversation/chat/` (moved handler),
  `…/conversation/quarkus/` (the leak guard)
- `src/main/resources/META-INF/services/org.eclipse.microprofile.context.spi.ThreadContextProvider` (new)
- `src/main/java/ai/scoring/evaluation/` (the scoring listener)
- `src/test/java/ai/scoring/conversation/` (new)
- `pom.xml` (`io.opentelemetry:opentelemetry-sdk-testing`, test scope, if not already added)
- `CLAUDE.md`, `docs/continuous-scoring-*.puml`, `docs/application-flow.puml`, `README.md`

## Key Points

- **No behaviour change for the chat**: same baggage key, same scoring trigger, same `score-session` gating.
- Langfuse span export stays off in `%test`; the project's span processor is what makes the id assertable.
- The core is generic: no intake, claim or Flow vocabulary in it.
- The leak guard is a stopgap with an exit condition, not part of the library's design.

## Done When

- [ ] `ConversationContext`, `ConversationIdSpanProcessor`, `ConversationEndedEvent` and the async firing helper exist in `ai.scoring.conversation`, depending on no adapter.
- [ ] The quarkus#54354 guard is registered, documented with its delete condition, and its regression test passes.
- [ ] `ConversationalBaggageHandler` lives in the chat adapter, uses the core, fires `ConversationEndedEvent`, and no longer references `SessionScoringService`.
- [ ] Session scoring runs from a single `@ObservesAsync ConversationEndedEvent` observer; failures are logged.
- [ ] The layering test passes; the docs and diagrams match.
- [ ] All the tests listed above pass under `-Pollama`; `PLAN.md` lists the real-key suites for the user to run.
