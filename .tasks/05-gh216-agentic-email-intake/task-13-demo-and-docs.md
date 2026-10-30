# Task 13: Demo Guide and Documentation

**Type:** Code Modification

**Issue:** [#262](https://github.com/edeandrea/non-deterministic-no-problem/issues/262)

## Goal

The demo can be run from a written guide, and every project document describes the email intake accurately.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b, ahead of the platform. Document what 1.2.0 fixed, not the
workarounds it replaced, and the override in `pom.xml` with its removal condition. Check `PLAN.md` → Execution Steps →
2a first.

**quarkus-flow 1.3.0 (2026-10-26):** adopted in task 09b, ahead of the platform, superseding the 1.2.0 override. It
fixes [quarkiverse/quarkus-flow#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096) upstream
([#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095)), and task 09b deleted the app's own workaround for it
(`AgenticWorkflowRuns`, `FailedAgenticSystemListener`, `ClaimsMailboxAgent.endFlowRunsOnFailure()`). Document what
1.3.0 fixed, not the workaround it replaced.

**Tasks 12b and 12c run first** (user decision, 2026-10-30): document the extraction contract as 12b leaves it (the claim
summary stands in for the incident description; no separately extracted `description`) and the Documents tab's
correspondence transcript as 12c builds it. The review panel's buttons are **Submit claim for processing** and, once an
item is asked, **Request more information** (task 12 found this file still said "Ready for processing").

## What to Do

- Write a demo guide (e.g. `docs/email-claim-intake-demo.md`) with sample emails to paste into Roundcube. Say to log in as a
  seeded claimant (`marty.mcfly@email.com`, …), not an arbitrary address: those identities carry the claimant's name (#237), so
  the email goes out as `Marty McFly <marty.mcfly@email.com>` and the replies greet "Marty McFly". Any other address sends as
  a bare address and is greeted "Customer":
  - a complete new claim (`Pending Review`, then the claims processor clicks **Submit claim for processing** →
    `In Process`; also show **Request more information** → `Pending Information` → reply → `Pending Review`)
  - a claim missing its location (reply → `Pending Review` → Submit claim for processing → `In Process`)
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
  - Its Observability section said the agentic adapter is "proven before implementation starts". *(Reworded in the
    task 11 PR (2026-10-29): task 11 built no adapter, because Flow 1.3.0 carries the context; its closing line now says
    quarkus-flow is 1.3.0, pinned ahead of the platform.)* Still to do here: the same two lines in the #216 issue body.
- Update `CLAUDE.md`:
  - **Architecture:** the `org.parasol.intake` package; the intake as one quarkus-flow workflow (`ClaimIntakeFlow`)
    with the agentic root as one step; the non-waiting watcher and starter (parallel runs, per-sender order, failure listener); `resolveClaim` and the which-claim reply; the review as the workflow's `listen` + `switch`
    steps; and `ai.scoring.conversation` (core + chat/flow/agentic adapters, `ConversationEndedEvent`)
  - **REST:** `POST /api/db/claims/{id}/review-decision` (asynchronous, 202; no authentication, it's a demo app;
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
    - **task 08 findings** (the full list is in `CLAUDE.md` → Gotchas → *The email intake workflow*): Flow's `tryCatch`
      only catches a `WorkflowException`; block on the mailer only off the caller's thread (`IntakeReplySender`); Panache
      statics don't work through method references; the per-sender lock also covers a woken review run; the intake's
      bookkeeping lives in `ClaimIntakeState`, never on `Claim`
    - agent span names (`langchain4j.aiservices.<SimpleClassName>.<method>`; the dataset-name invariant, D11)
    - **quarkus-flow:** `quarkus-flow-langchain4j` and `-opentelemetry` are Preview; no `@ParallelExecutor` (Flow
      forks on the `ManagedExecutor` and, since 1.2.0, carries the OTel context; before 1.2.0 one broke every run,
      quarkus-flow#1057); Flow 1.3.0 is pinned ahead of the platform by a BOM override in `pom.xml` (task 06b, bumped
      to 1.3.0 by task 09b), removed once a platform pins it; every terminal `switch` branch needs
      `.then(FlowDirectiveEnum.END)`; pass ids, not payloads, in workflow data; the "is it waiting?" check uses
      `PersistenceInstanceReader`, never the raw status column; `quarkus.application.name` is part of Flow's table
      keys; **an agent that threw used to leave its generated agentic run unended (1.2.0)** (worker thread blocked,
      Flow rows kept) — fixed upstream in 1.3.0 (quarkus-flow#1095); task 09b deleted the app's own workaround
      (`AgenticWorkflowRuns` / `FailedAgenticSystemListener`) now that it's no longer needed
    - agent outputs hold no `LocalDate`/`Optional` (ISO strings), unless task 05's round-trip test relaxed it
    - **no durable state across restarts** (user decision): a restart wipes the database and GreenMail, so a waiting
      review doesn't survive it
    - **single replica:** superseding a waiting review uses `activeInstance(id)`, which only sees this JVM; a reply
      during review and a decision are serialized by optimistic locking on the claim (`@Version`)
    - **observability workarounds with exit conditions:** the quarkus#54354 `ThreadContextProvider` guard (delete when
      quarkusio/quarkus#56805 ships), and `FlowSpanTypingProcessor` (delete with quarkus-flow#1122); task 11 built no
      Flow or agentic adapter (Flow 1.3.0 carries the context); the agentic-run-on-failure workaround is already gone
      (task 09b, quarkus-flow 1.3.0)
  - **Observability** (task 11): one claim = one Langfuse session via `gen_ai.conversation.id` (baggage, entered once
    by the starter); no Flow task proxy was needed; the trace shape; typed Flow spans; the
    persistence-span decision; the `claim.intake.*` metrics; the `%test` Langfuse span export switch
    (`quarkus.langfuse.otel.enabled`, re-enabled only in `LangfuseSessionScoringServiceTests`); both Dev UIs (agentic:
    topology and per-run agent detail; Flow: the workflow and the review wait) and that `MonitoredAgent`
    keeps finished sessions only in dev mode
- Update `README.md` ("What it demonstrates": agentic workflows inside a quarkus-flow workflow, and human-in-the-loop
  review; REST endpoints, including `review-decision`; prerequisites; the single-replica requirement; the claim
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
