# Task 07: Human Review Step (`@HumanInTheLoop` + Database-Backed Scope Store)

**Type:** Code Modification

## Goal

When the agents judge a claim complete, the workflow pauses at a human review step. The pause uses a
static `@HumanInTheLoop` method returning a `SuspendedResponse`, and the workflow state is saved in
PostgreSQL by the `DatabaseAgenticScopeStore` from task 03, so the pause survives restarts. A claims
processor's decision resumes the workflow, which ends the run. The review is a completeness look-over,
not an approval.

## What to Do

- **Scope storage** is built in task 03 (`AgenticScopeRecord` keyed `agentId|memoryId`, `text` JSON,
  `updatedAt`; `DatabaseAgenticScopeStore` in `requiringNew`; the lowest-priority `StartupEvent` registrar
  with the `ShutdownEvent` reset; the startup self-test). This task adds the review types to its allowlist
  and self-test.
- **Review agent** (spike Q9, Q15):
  - `ClaimReviewAgent`, a non-LLM agent with a **static** `@HumanInTheLoop` method (no `async`; with
    `async = true` the next agent runs on the placeholder and runs again on resume), output key `reviewDecision`:
    ```java
    @HumanInTheLoop(outputKey = "reviewDecision")
    static Object review(AgenticScope scope) {
        return new SuspendedResponse<>("review:" + scope.memoryId());
    }
    ```
    It returns `Object` and only returns the suspended response (it's static, so no CDI injection).
  - `ReviewDecision` record: a `ReviewOutcome` enum (`READY` / `NEEDS_INFORMATION`) plus a `Set<MissingItem>`
    of the items the reviewer ticked. **No `java.time` or `Optional`.** It appears in no agent signature, so
    allowlist it with `AgenticScopeSerializer.allowDeserializationType(ReviewDecision.class)` in task 03's
    startup list; otherwise `load` after a restart throws `UnserializableAgenticScopeException`.
  - Every `@Agent` method that consumes the decision types the parameter as **`Object reviewDecision`**, or the build fails.
- **Review decision router** (gap 6): a non-LLM `@ConditionalAgent` with **typed** `@ActivationCondition`s on
  `ReviewDecision` (allowed) that routes to two non-LLM agents producing `ReviewReady` and
  `ReviewNeedsInformation(Set<MissingItem>)` outcomes. No LLM call on resume.
- **Workflow wiring:**
  - After `EmailRouter` (task 06), a review step runs **only** when the route was `ClaimExtractionWorkflow`
    and `missingInformation` (task 05) is empty, for both new claims and pending-claim updates. The missing
    items are written to the scope by a non-LLM step (or the extraction `@Output`) so the condition stays a
    cheap pure function.
  - Incomplete claims, status replies and not-a-claim / no-matching-claim outcomes never reach the review step.
- **Suspension at the root** (gap 4): `ClaimsMailboxAgent.process` keeps a plain `IntakeOutcome` return type,
  and a complete claim's run surfaces as **`AgenticSystemSuspendedException`**. The processor (task 08) catches
  it as a normal outcome. `ResultWithAgenticScope` isn't used: its resume callback only works in the same JVM.
- **Memory id:**
  - Memory id = the inbound email's `Message-ID` (unique, and already stored for idempotency). The scope row
    key is `agentId|memoryId`, with `agentId` = the `ClaimsMailboxAgent` FQCN.
  - The claim stores the memory id of the run waiting for review (new column `reviewRunId`).
  - Scope rows are never deleted automatically; every ending evicts explicitly (task 08 and below).
- **`ClaimReviewService.decide(claimId, expectedVersion, ReviewDecision)`** (used by task 10's REST API):
  1. In a `requiringNew()` transaction, load the claim and **claim the review** with optimistic locking
     (`@Version` on `Claim`): it must be `Pending Review`, have a `reviewRunId`, and still be at the
     version the reviewer saw. Mark the review as being decided (e.g. move `reviewRunId` to a
     `decidingRunId`, or clear it) and commit, bumping the version. A lost lock or a non-pending claim →
     `ClaimReviewConflictException` (409). An unknown claim → `ClaimNotFoundException` (404).
  2. `getAgenticScope(runId)`: `null` → 404 (`ReviewRunNotFoundException`), with the claim left as it was.
     Check `pendingResponseIds()` contains `review:<runId>`, or 409.
  3. `completePendingResponse("review:" + runId, decision)`.
  4. Re-invoke `process(runId, …)` with the **original arguments read back from the scope**
     (`scope.readState("<key>")` for each root argument). Passed arguments overwrite the stored state, and
     `null` throws `MissingArgumentException`. No transaction is open during the call.
  5. Hand the outcome to `ClaimEmailProcessor.applyReviewOutcome` (task 08), then `evictAgenticScope(runId)`.
  - **Needs more information ends that run** (gap 2): the claim goes to `Pending Information`, the scope is
    evicted, and the customer's next email starts a new run. There is no re-suspension.
  - The decision span and its link to the original trace are added in task 11. `decide` reads the claim's
    `intakeConversationId` and makes it current as `gen_ai.conversation.id` baggage before that span starts (task 11).
- **Reply during review (user decision):** a customer reply while the claim is `Pending Review` supersedes the
  review (task 08 owns the rule): in its own transaction it clears `reviewRunId` (bumping the version), then
  evicts the old scope. Whichever of the reply and the decision commits first wins: a losing decision gets a
  409; a losing reply re-reads the claim and is handled under its new status.
- **Tests:**
  - **Workflow** (WireMock LLM, stubs on the last message only):
    - a complete claim run throws `AgenticSystemSuspendedException`, with one scope row keyed `agentId|memoryId`
    - on resume, the classifier and extraction agents are **not** called again (WireMock request counts)
    - `READY` and `NEEDS_INFORMATION` give the matching outcomes, through the non-LLM decision router
    - resume works after the in-memory registry is cleared (`@Internal` `AgenticScopeOwner.registry().clearInMemory()`, tests only)
    - eviction deletes the database row; evicting a missing id returns `false`
    - an incomplete claim never suspends
    - a `ReviewDecision` round-trips through the scope serializer after a simulated restart
  - **`ClaimReviewService`:**
    - a decision on a claim that isn't in review is rejected (409)
    - a second decision is rejected (409)
    - a decision with a stale version is rejected (409)
    - a missing scope gives 404 without partial state
    - Needs more information leaves the claim `Pending Information` with no scope row
  - **Race:** a reply and a decision on the same `Pending Review` claim, run concurrently (latched): exactly
    one wins; a losing decision gets the 409 and sends no email; a losing reply is processed under the new
    status; no scope row is left behind for the superseded or decided run.

## Files/Areas

- `src/main/java/org/parasol/intake/review/`: `ClaimReviewAgent`, `ReviewDecision`, the decision router and its outcome agents, `ClaimReviewService`, exceptions (new)
- `src/main/java/org/parasol/intake/agent/`: workflow wiring
- `src/main/java/org/parasol/claim/model/Claim.java` (`reviewRunId`, `@Version`)
- `src/test/java/org/parasol/intake/review/` (new)

## Key Points

- **Beta API, with two internal touch points.** The whole module is beta (`langchain4j-agentic` `1.20.2-beta30`).
  Suspend/resume and persistence are documented upstream ("AgenticScope and agentic systems recoverability"), and
  most of the API is in public packages. Only two places are internal:
  - `SuspendedResponse` is in `dev.langchain4j.agentic.internal`.
  - The store SPI exposes the `@Internal` type `DefaultAgenticScope`.

  Quarkus has no support (no CDI store, no config). Keep every use behind `DatabaseAgenticScopeStore`,
  `ClaimReviewAgent` and `ClaimReviewService`. Don't use `AgenticScopeRegistry` or `AgenticScopeJsonCodec`: both are `@Internal`.
- **The store is a JVM-wide static**, registered once at startup and reset on shutdown (task 03). Tests don't replace it.
- No database transaction may be open while an agent call runs, including when a workflow resumes.
- The in-memory scope copy is kept until eviction, so the app runs as a **single replica**.
- **Decision gate: passed.** The spike (task 01) showed suspend/resume works through Quarkus, including after a
  genuine restart, so the status-only fallback isn't needed.

## Done When

- [ ] A complete claim's workflow suspends at `ClaimReviewAgent` (static, returns `Object`, no `async`), the root surfaces `AgenticSystemSuspendedException`, and the scope is saved under `agentId|memoryId`.
- [ ] `ReviewDecision` holds no `java.time`/`Optional`, is allowlisted with `allowDeserializationType`, and is consumed as `Object`.
- [ ] A decision resumes the run with the arguments read back from the scope, through the non-LLM decision router, and the scope is evicted; Needs more information ends the run at `Pending Information`.
- [ ] Resuming works after the in-memory registry is cleared, and doesn't call the earlier agents again.
- [ ] Optimistic locking serialises a reply against a decision; the race test passes.
- [ ] No scope rows remain after any test.
- [ ] All the tests listed above pass under `-Pollama`.
