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
- Update `CLAUDE.md`:
  - **Architecture:** the `org.parasol.intake` package and the agent structure, including the human
    review step (`@HumanInTheLoop` suspend/resume, `DatabaseAgenticScopeStore`, `ClaimReviewService`)
  - **REST:** `POST /api/db/claims/{id}/review-decisions` (no authentication; it's a demo app)
  - **Statuses:** intake uses `Pending Information`, `Pending Review` and `In Process`, never `New`
  - **Models table:** add `claim-intake`
  - **Configuration:** the `parasol.intake.*` keys and the profiles where intake is off
  - **Testing:** the intake test layers and the Roundcube E2E test
  - **Gotchas:** no transactions across LLM calls (including resume); `Message-ID` idempotency; tests
    must delete claims; agent span names (from spike results 7 and 16); the HITL/persistence API is beta,
    with two internal touch points (`SuspendedResponse`, the `@Internal` `DefaultAgenticScope` in the store SPI),
    and `AgenticScopePersister.setStore` must run before any agent is built; the scope store is a JVM-global static that tests must restore; scopes must be
    evicted manually (no rows may leak); the `@HumanInTheLoop` method must be static; no `async = true`
  - **Observability** (task 11): the intake trace shape (root `claim-intake process` span, agent spans,
    mail spans, the linked review-decision span); the `claim.intake.*` metrics; the `%test` Langfuse span
    export switch (`quarkus.langfuse.otel.enabled`, re-enabled only in `LangfuseSessionScoringServiceTests`);
    the `MonitoredAgent` Dev UI pages (Topology, Executions), capped outside dev
- Update `README.md` ("What it demonstrates": agentic workflows and human-in-the-loop review; REST
  endpoints, including `review-decisions`; prerequisites; the intake trace, `claim.intake.*` metrics and
  `MonitoredAgent` Dev UI pages) and `docs/application-flow.puml` (add the
  intake flow, including the review step). Re-render with `./docs/render-diagrams.sh`.
- Check `images/arch.png` and `langfuse-evaluation.md` for statements the feature makes stale. Report them rather than pixel-editing the raster.

## Files/Areas

- `docs/email-claim-intake-demo.md` (new), `CLAUDE.md`, `README.md`, `docs/application-flow.puml` (+ PNG)

## Key Points

- Verify every statement against the implemented code. Leave `AGENTS.md` and `conversation-export.md` untouched.

## Done When

- [ ] The demo guide exists and covers all five scenarios, including the review step, for dev mode and the cluster.
- [ ] `CLAUDE.md` and `README.md` describe the intake, the human review step, the `claim-intake` model,
  the review endpoint, the configuration keys and the intake observability, and the diagram (with the review step) is re-rendered.
