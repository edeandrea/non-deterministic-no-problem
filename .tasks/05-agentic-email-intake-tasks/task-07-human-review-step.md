# Task 07: Human Review Step (`@HumanInTheLoop` + Database-Backed Scope Store)

**Type:** Code Modification

## Goal

When the agents judge a claim complete, the workflow pauses at a human review step. The pause uses
`@HumanInTheLoop` with a `SuspendedResponse`, and the workflow state is saved in PostgreSQL so the
pause survives restarts. A claims processor's decision resumes the workflow.

## What to Do

- **Scope storage:**
  - Add an `AgenticScopeRecord` entity: key = memory id (`String`), scope JSON (`text`), `updatedAt` (`Instant`).
  - Add `DatabaseAgenticScopeStore implements AgenticScopeStore`, with the method signatures the
    spike (task 01) recorded. Every call runs in its own transaction (`QuarkusTransaction.requiringNew()`),
    never inside the caller's transaction.
  - The SPI is `boolean save(AgenticScopeKey, DefaultAgenticScope)` / `Optional<DefaultAgenticScope> load(AgenticScopeKey)`
    (plus delete/key listing; confirm in the spike). Serialize with `AgenticScopeSerializer.toJson`/`fromJson`.
  - Register the CDI-managed store with `AgenticScopePersister.setStore(...)` (documented upstream; preferred over
    ServiceLoader, because a ServiceLoader-created store isn't a CDI bean). `AgenticScopeRegistry` reads the store in
    its constructor, so it must be set **before any agent is built**. The spike must confirm a startup hook that runs
    early enough. Also register the deserialization allowlist for the intake records and enums
    (`AgenticScopeSerializer.registerForDeserializationPackageOf(...)`).
- **Review agent:**
  - Add a `ClaimReviewAgent` with a **static** `@HumanInTheLoop` method (no `async`) and output key
    `reviewDecision`. It returns a `SuspendedResponse`, using the exact API the spike recorded.
  - The root agent method returns `ResultWithAgenticScope<IntakeOutcome>` and checks `suspended()`. Upstream
    recommends this over catching `AgenticSystemSuspendedException`. The root interface extends `AgenticScopeAccess`
    (for `getAgenticScope` / `evictAgenticScope`).
  - Add a `ReviewDecision` record: a `ReviewOutcome` enum (`READY` / `NEEDS_INFORMATION`) plus a
    `Set<MissingItem>` of the items the reviewer ticked.
- **Workflow wiring:**
  - On both the new-claim route and the pending-claim-update route, when extraction finds nothing
    missing, the sequence continues into `ClaimReviewAgent`. After review, a router turns `reviewDecision`
    into `ReviewReady` or `ReviewNeedsInformation` outcomes.
  - Incomplete claims never reach the review step.
- **Memory id:**
  - The root agent method takes `@MemoryId String runId`, set to the inbound email's `Message-ID` (unique, and already stored for idempotency).
  - The claim stores the `runId` of the run waiting for review (new column, e.g. `reviewRunId`).
  - The processor evicts the scope of every run that **didn't** suspend
    (`evictAgenticScope`). Persistent scopes are never deleted automatically.
- **`ClaimReviewService.decide(claimId, ReviewDecision)`** (used by task 10's REST API):
  - Reject the call (`ClaimNotInReviewException`, which maps to 409) unless the claim is `Pending Review` and has a `reviewRunId`.
  - Complete the pending response (`getAgenticScope(runId).completePendingResponse(...)`), then call
    the root agent again with the same `runId`. Supply whatever arguments the spike found are needed on resume.
  - Hand the resumed outcome to the processor's outcome method (task 08), evict the scope, and clear `reviewRunId`.
  - Prevent two decisions on the same claim with optimistic locking (`@Version`) or a row lock.
  - The decision span and its link to the original trace are added in task 11.
- **Superseding:** a customer reply while the claim is `Pending Review` evicts the old scope, and the
  new email runs as a new workflow (task 08 owns this rule). The store must handle evicting a missing key safely.
- **Tests:**
  - **Store:**
    - save/load/delete round trip
    - a scope survives clearing the in-memory registry (simulated restart)
    - the allowlist accepts the intake types
  - **Workflow** (WireMock LLM):
    - a complete claim run comes back suspended
    - on resume, the classifier and extraction agents are **not** called again (WireMock request counts)
    - `READY` and `NEEDS_INFORMATION` give the matching outcomes
    - resume works after the in-memory registry is cleared
    - eviction deletes the database row
    - an incomplete claim never suspends
  - **`ClaimReviewService`:**
    - a decision on a claim that isn't in review is rejected
    - a second decision is rejected
    - an unknown `runId` fails without leaving partial state

## Files/Areas

- `src/main/java/org/parasol/intake/review/`: `ClaimReviewAgent`, `ReviewDecision`, `ClaimReviewService`, `DatabaseAgenticScopeStore`, `AgenticScopeRecord` (new)
- `src/main/java/org/parasol/intake/agent/`: workflow wiring
- `src/main/java/org/parasol/model/claim/Claim.java` (`reviewRunId`, `@Version` if used)
- `src/test/java/org/parasol/intake/review/` (new)

## Key Points

- **Beta API, with two internal touch points.** The whole module is beta (`langchain4j-agentic` `1.20.2-beta30`).
  Suspend/resume and persistence are documented upstream ("AgenticScope and agentic systems recoverability"), and
  most of the API is in public packages. Only two places are internal:
  - `SuspendedResponse` is in `dev.langchain4j.agentic.internal`.
  - The store SPI exposes the `@Internal` type `DefaultAgenticScope`.

  Quarkus has no support (no CDI store, no config). Keep every use behind `DatabaseAgenticScopeStore`,
  `ClaimReviewAgent` and `ClaimReviewService`. Don't use `AgenticScopeRegistry` or `AgenticScopeJsonCodec`: both are `@Internal`.
- **The store is a JVM-wide static.** Tests must restore it after themselves, e.g. reset in `@AfterEach` (the upstream `SuspensionResumeIT` does this).
- **Don't use `async = true`.** It hides `SuspendedResponse` from the suspension check.
- **The HITL method must be `static`** (Quarkus checks this at build time), so it can't use CDI injection. It only returns the suspended response.
- No database transaction may be open while an LLM call runs, including when a workflow resumes.
- **Decision gate:** if task 01 found suspend/resume doesn't work through the Quarkus agent proxies,
  **stop and ask the user**. The fallback is a review driven by claim status only, with the same
  statuses, REST API and UI.

## Done When

- [ ] A complete claim's workflow suspends at `ClaimReviewAgent`, its scope is saved in PostgreSQL, and a decision resumes it to the matching outcome.
- [ ] Resuming works after the in-memory registry is cleared, and doesn't call the earlier agents again.
- [ ] No scope rows remain after any test.
- [ ] All the tests listed above pass under `-Pollama`.