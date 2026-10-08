# Task 13: Demo Guide and Documentation

**Type:** Code Modification

## Goal

The demo can be run from a written guide, and every project document describes the email intake accurately.

## What to Do

- Write a demo guide (e.g. `docs/email-claim-intake-demo.md`) with sample emails to paste into Roundcube:
  - a complete new claim (`Pending Review`, then the claims processor clicks **Ready for processing** →
    `In Process`; also show **Request more information** → `Pending Information` → reply → `Pending Review`)
  - a claim missing its location (reply → `Pending Review` → Ready → `In Process`)
  - a follow-up on Marty's claim (status question)
  - a policy-number conflict
  - a not-a-claim email (newsletter)

  For each, list what to show: Roundcube, the `claims@` folders, the claims list and review panel, and Langfuse.
  Include the steps "open the claim's **session** in Langfuse" (one session per claim, several traces; explain why:
  quarkus-flow#1056) and "open the intake workflow in the Flow Dev UI" (the Workflows card draws the intake as a
  typed, branching diagram; task 01b, E17). Cover both local dev mode and the cluster.
  - **Live-demo caveat:** editing a `Flow` bean in dev mode can throw `IncompatibleClassChangeError`; restart dev mode.
- Update the design doc `docs/design/email-claim-intake.md` (merged in PR #220, revised for quarkus-flow in PR #231): change its
  "Status: proposed, for review before implementation" line to say the design is accepted and implemented
  (linking #216), and fix anything the implementation changed (e.g. the incident time, if still extracted; see
  `PLAN.md` → Caveats). Re-render its diagrams if they change.
  - Its Observability section says the agentic adapter is
    "proven before implementation starts": follow-up spike 5 proved it, so reword it (and the same line in the #216
    issue body) to match what task 11 built, including whether quarkus-flow#1065 replaced the adapter.
- Update `CLAUDE.md`:
  - **Architecture:** the `org.parasol.intake` package; the intake as one quarkus-flow workflow (`ClaimIntakeFlow`)
    with the agentic root as one step; the non-waiting watcher and starter (parallel runs, per-sender order, failure listener); `resolveClaim` and the which-claim reply; the review as the workflow's `listen` + `switch`
    steps; and `ai.scoring.conversation` (core + chat/flow/agentic adapters, `ConversationEndedEvent`)
  - **REST:** `POST /api/db/claims/{id}/review-decisions` (asynchronous, 202; no authentication, it's a demo app;
    404 missing run, 409 not waiting / being decided / lost lock)
  - **Statuses:** intake uses `Pending Information`, `Pending Review` and `In Process`, never `New`
  - **Models table:** add `claim-intake`
  - **Configuration:** the `parasol.intake.*` keys and the profiles where intake is off
  - **Testing:** the intake test layers and the Roundcube E2E test; WireMock stubs match the last message only;
    sub-agent `@InjectMock` tests use a dedicated profile; decisions in tests re-publish until the run leaves
    `WAITING` (F2b); tests assert no Flow rows remain
  - **Gotchas:**
    - no transactions across agent calls (including resume); `Message-ID` idempotency; tests must delete claims
    - **stateless agents:** every AI agent carries
      `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`,
      otherwise it silently gets the policy Easy RAG retriever and a chat memory shared across all runs; claim
      history is passed explicitly
    - every entry agent must be injected in `src/main`; each leaf agent belongs to exactly one root
    - agent span names (`langchain4j.aiservices.<SimpleClassName>.<method>`; the dataset-name invariant, D11)
    - **quarkus-flow:** `quarkus-flow-langchain4j` and `-opentelemetry` are Preview; **no `@ParallelExecutor`**
      (quarkus-flow#1057); every terminal `switch` branch needs `.then(FlowDirectiveEnum.END)`; pass ids, not
      payloads, in workflow data; the "is it waiting?" check uses `PersistenceInstanceReader`, never the raw status
      column; `quarkus.application.name` is part of Flow's table keys; a cancelled run loses its `workflow.execute`
      span (quarkus-flow#1058)
    - agent outputs hold no `LocalDate`/`Optional` (ISO strings), unless task 05's round-trip test relaxed it
    - **no durable state across restarts** (user decision): a restart wipes the database and GreenMail, so a waiting
      review doesn't survive it
    - **single replica:** superseding a waiting review uses `activeInstance(id)`, which only sees this JVM; a reply
      during review and a decision are serialised by optimistic locking on the claim (`@Version`)
    - **observability workarounds with exit conditions:** the quarkus#54354 `ThreadContextProvider` guard (delete when
      quarkusio/quarkus#56805 ships) and the agentic adapter (delete when quarkus-flow#1056 is fixed)
  - **Observability** (task 11): one claim = one Langfuse session via `gen_ai.conversation.id` (baggage, entered once
    by the starter); the Flow task proxy; the trace shape and its known gaps (#1056); typed Flow spans; the
    persistence-span decision; the `claim.intake.*` metrics; the `%test` Langfuse span export switch
    (`quarkus.langfuse.otel.enabled`, re-enabled only in `LangfuseSessionScoringServiceTests`); both Dev UIs (agentic:
    topology and per-run agent detail; Flow: the workflow and the review wait) and that `MonitoredAgent`
    keeps finished sessions only in dev mode
- Update `README.md` ("What it demonstrates": agentic workflows inside a quarkus-flow workflow, and human-in-the-loop
  review; REST endpoints, including `review-decisions`; prerequisites; the single-replica requirement; the claim
  session in Langfuse, `claim.intake.*` metrics and the Flow Dev UI) and `docs/application-flow.puml` (add the intake
  flow, including the review step). Re-render with `./docs/render-diagrams.sh`.
- Check `images/arch.png` and `langfuse-evaluation.md` for statements the feature makes stale. Report them rather than pixel-editing the raster.
- Check the Kubernetes manifests (`dependencies.yml` / app deployment) keep the app at one replica, and document it.

## Files/Areas

- `docs/email-claim-intake-demo.md` (new), `docs/design/email-claim-intake.md` (status line), `CLAUDE.md`, `README.md`,
  `docs/application-flow.puml` (+ PNG)

## Key Points

- Verify every statement against the implemented code. Leave `AGENTS.md` and `conversation-export.md` untouched.

## Done When

- [ ] The demo guide exists and covers all five scenarios, including the review step, for dev mode and the cluster.
- [ ] The design doc's status line says accepted/implemented, and the doc matches the code.
- [ ] `CLAUDE.md` and `README.md` describe the intake workflow, the human review step, the `claim-intake` model,
  the review endpoint, the configuration keys, the conversation core and intake observability, the agent opt-outs,
  the single-replica requirement and the quarkus-flow gotchas, and the diagram (with the review step) is re-rendered.
