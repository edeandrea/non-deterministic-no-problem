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

  For each, list what to show: Roundcube, the `claims@` folders, the claims list and review panel, Langfuse traces.
  Include the step "open the intake trace in Grafana/Tempo and Langfuse". Cover both local dev mode and the cluster.
- Update the design doc `docs/design/email-claim-intake.md` (merged in PR #220): change its
  "Status: proposed, for review before implementation" line to say the design is accepted and implemented
  (linking #216), and fix anything the implementation changed (e.g. the incident time, if still extracted; see
  `PLAN.md` → Caveats). Re-render its diagrams if they change.
- Update `CLAUDE.md`:
  - **Architecture:** the `org.parasol.intake` package and the agent structure, including the human
    review step (`@HumanInTheLoop` suspend/resume, `DatabaseAgenticScopeStore`, `ClaimReviewService`)
  - **REST:** `POST /api/db/claims/{id}/review-decisions` (no authentication; it's a demo app; 404 missing scope, 409 lost lock)
  - **Statuses:** intake uses `Pending Information`, `Pending Review` and `In Process`, never `New`
  - **Models table:** add `claim-intake`
  - **Configuration:** the `parasol.intake.*` keys and the profiles where intake is off
  - **Testing:** the intake test layers and the Roundcube E2E test; WireMock stubs match the last message only;
    sub-agent `@InjectMock` tests use a dedicated profile; the restart test's shared database
  - **Gotchas:**
    - no transactions across agent calls (including resume); `Message-ID` idempotency; tests must delete claims
    - **stateless agents:** every AI agent carries
      `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`,
      otherwise it silently gets the policy Easy RAG retriever and a chat memory shared across all runs; claim
      history is passed explicitly
    - every entry agent must be injected in `src/main`; each leaf agent belongs to exactly one root
    - agent span names (from spike results 7 and 16)
    - the HITL/persistence API is beta, with two internal touch points (`SuspendedResponse`, the `@Internal`
      `DefaultAgenticScope` in the store SPI)
    - **store lifecycle:** the JVM-global store is registered by a lowest-priority `StartupEvent` observer, reset on
      `ShutdownEvent`, and checked by a startup round-trip self-test; no agent calls in startup observers below it
    - scope values hold no `LocalDate`/`Optional` (ISO strings); types outside agent signatures are allowlisted
    - scopes must be evicted manually on every ending (no rows may leak); the `@HumanInTheLoop` method must be
      static and return `Object`; no `async = true`; resume re-invokes with the arguments read from the scope
    - **single replica:** each scope is cached in memory until eviction, so a second replica could resume from a
      stale copy; a reply during review and a decision are serialised by optimistic locking on the claim (`@Version`)
  - **Observability** (task 11): the intake trace shape (root `claim-intake process` span, `invoke_agent` spans from
    the root `@AgentListenerSupplier`, mail spans, the linked review-decision span); the `@ParallelExecutor`; the
    `claim.intake.*` metrics; the `%test` Langfuse span export switch (`quarkus.langfuse.otel.enabled`, re-enabled
    only in `LangfuseSessionScoringServiceTests`); `MonitoredAgent` is dev-only (the mechanism chosen in task 11),
    with no monitor in prod
- Update `README.md` ("What it demonstrates": agentic workflows and human-in-the-loop review; REST
  endpoints, including `review-decisions`; prerequisites; the single-replica requirement; the intake trace,
  `claim.intake.*` metrics and the dev-only agent monitor Dev UI pages) and `docs/application-flow.puml` (add the
  intake flow, including the review step). Re-render with `./docs/render-diagrams.sh`.
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
- [ ] `CLAUDE.md` and `README.md` describe the intake, the human review step, the `claim-intake` model,
  the review endpoint, the configuration keys, the intake observability, the agent opt-outs, the single-replica
  requirement and the store lifecycle, and the diagram (with the review step) is re-rendered.
