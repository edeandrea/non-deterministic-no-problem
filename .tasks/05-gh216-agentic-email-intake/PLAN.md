# Issue 5: Agentic Email Claim Intake — Task Execution Plan

## Your Mission

A customer emails `claims@parasol.com` (from Roundcube). An IMAP IDLE watcher picks the email up, and
a `quarkus-langchain4j-agentic` workflow triages it and extracts the claim details. The app then
creates or updates the claim, stores photos, replies with Qute templates, and files the email. Before a
claim moves to `In Process`, a claims processor does a final review: the workflow pauses at a
`@HumanInTheLoop` step, suspended and persisted in PostgreSQL, and the processor's decision resumes it.
A short design document (workflow, claim-state and agent-topology diagrams) was written and reviewed externally before implementation (PR #220, merged).
Last of five issues; see `.tasks/claim-intake-roadmap.md`. **Issues 1–4 must be complete before the
implementation tasks (03 onwards).** The spike (task 01) and the design (task 02) ran ahead of them.

**Prerequisites for task 03+ — all satisfied (updated 2026-10-06).** Issues #212–#215 have landed, in
order: #212 via PR #219 (`2575c10`), #213 via #224, #214 via #225, #215 via #230. `main` is now on
quarkus-langchain4j **1.14.1** / Quarkus **3.40.1**, matching what the task 01 spike verified
(langchain4j-agentic 1.20.2-beta30). **Nothing in the issue queue blocks task 03 any more.**

**But task 03 is gated on task 01b, not on the issue queue.** The quarkus-flow spike (task 01b) decides
whether the durable human-in-the-loop machinery is built the way the merged design describes. It must
produce a verdict *before* task 03 commits to a scope store and *before* task 07 is written. See
**Flow Spike Results (task 01b)** under Shared Context, then
[`spike-results-flow.md`](spike-results-flow.md) → *Resuming this spike*.

**Plan File:** `.tasks/05-gh216-agentic-email-intake/PLAN.md`
**Tasks Directory:** `.tasks/05-gh216-agentic-email-intake/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions, spike results and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/05-gh216-agentic-email-intake/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md` (coding style, AssertJ, records, `Optional`, constructor injection, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

Task 02 was a hard gate: implementation tasks (03+) start only after the user confirms the external design review is complete and the agreed changes are applied. **Passed:** PR #220 merged with no review feedback, and the gaps and spike changes are applied to the task files (see Design and Caveats).

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any? (The spike in task 01 may force changes.)

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [x] [task-01-agentic-spike.md](task-01-agentic-spike.md): Agentic module spike (runs first, throwaway)
- [x] [task-02-design-and-review.md](task-02-design-and-review.md): Design document and review gate
- [~] [task-01b-flow-spike.md](task-01b-flow-spike.md): quarkus-flow spike (throwaway; decides task 07) — **IN PROGRESS, suspended**
- [ ] [task-03-intake-config-and-mailbox.md](task-03-intake-config-and-mailbox.md): Intake configuration and claims mailbox
- [ ] [task-04-email-templates.md](task-04-email-templates.md): Qute reply templates and sender
- [ ] [task-05-extraction-agents.md](task-05-extraction-agents.md): Claim extraction agents
- [ ] [task-06-triage-agents.md](task-06-triage-agents.md): Triage agents
- [ ] [task-07-human-review-step.md](task-07-human-review-step.md): Human review step (`@HumanInTheLoop` + database-backed scope store)
- [ ] [task-08-intake-processor.md](task-08-intake-processor.md): Intake processor and business rules
- [ ] [task-09-imap-idle-watcher.md](task-09-imap-idle-watcher.md): IMAP IDLE watcher
- [ ] [task-10-review-api-and-ui.md](task-10-review-api-and-ui.md): Review API and UI
- [ ] [task-11-observability.md](task-11-observability.md): Observability for the intake workflow
- [ ] [task-12-roundcube-e2e.md](task-12-roundcube-e2e.md): Roundcube end-to-end test
- [ ] [task-13-demo-and-docs.md](task-13-demo-and-docs.md): Demo guide and documentation
- [ ] [task-14-verification.md](task-14-verification.md): Verification

> **Ordering: task 01b blocks tasks 03 and 07.** It is in progress and suspended. Do **not** start task 03
> (it would commit to the `DatabaseAgenticScopeStore` design) or write task 07 until 01b has recorded a
> verdict here. Everything else in the issue queue is already unblocked. Resume at
> [`spike-results-flow.md`](spike-results-flow.md) → *Resuming this spike*.

---

## Shared Context

### Overview
Code in a new package `org.parasol.intake` (with `agent`, `mailbox`, `reply` and `review` sub-packages).
This follows the domain-first layout of the existing code (`org.parasol.claim`, `org.parasol.chat`, `org.parasol.notification`).
The existing chat (`ClaimService`, chat scopes, `NotificationService`) stays unchanged.

```
Roundcube ──SMTP──▶ GreenMail ◀──SMTP── app (Qute replies, NotificationService)
                        ▲
                        └──IMAP IDLE── ClaimsInboxWatcher → ClaimEmailProcessor → ClaimsMailboxAgent
                                                                                    ⇣ suspends at ClaimReviewAgent
                                                                                    ⇣ (scope saved in PostgreSQL)
                         claims processor (UI) → ClaimReviewService → resume ⇢ ClaimEmailProcessor outcome
```

### Project Context
- Issues 2–4 provide:
  - the natural-id claim number and category enum
  - incident date/time
  - `ClaimImage` storage plus a store method
  - GreenMail + Roundcube in `compose-devservices.yml`, plus a GreenMail test helper
- LLM mocking: a WireMock dev service with a `QuarkusTestProfile` that repoints model base URLs, and
  stubs registered in `@BeforeEach`. CI runs `-Pollama` and `-Pollama-openai` with only `OPENAI_API_KEY=change-me`.
- `GenerateEmailService` is the existing **AI-written** email. The intake's fixed emails are Qute
  templates on purpose, to show both patterns.

### Key Decisions
- **Triage flow:** code matches the claim and checks the sender first; then classify (`NEW_CLAIM` /
  `CLAIM_FOLLOW_UP` / `NOT_A_CLAIM`) and route, **matched claim first**, then the label (gap 1). Agents have
  **no side effects**; `ClaimEmailProcessor` does all persistence, mail, folder moves and scope eviction.
  Unmatched not-a-claim and follow-up emails go to a non-LLM agent (design, Agent architecture).
- **Agent context (user decision):** every intake agent is **stateless**. Chat memory and Easy RAG are
  turned off on each agent interface (`chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class`,
  `retrievalAugmentor = NoRetrievalAugmentorSupplier.class`); by default agents share one `"default"`
  chat memory across all calls (spike Q6). History across the back-and-forth is passed **explicitly as
  input** by the processor:
  - the claim's combined correspondence
  - the fields extracted so far
  - the items we asked the customer for

  Extraction re-runs over the whole thread each time: a reply on a pending claim goes through the same
  `ClaimExtractionWorkflow` as a new claim (design, Workflow step 5). The root `@MemoryId` (= `Message-ID`) keys
  only the workflow's `AgenticScope` (the review pause). Per-email or per-claim chat memory was rejected:
  - per-email memory has no history across replies
  - per-claim memory duplicates the claim record and needs its own persistence and cleanup
  - replaying old turns biases extraction and carries prompt injection forward
- **Required details:** what happened, the incident date, the location and the category (`OTHER` is a
  valid category). The agents do all the back-and-forth with the customer: if any detail is missing,
  the claim is `Pending Information` and the customer is emailed the exact list of missing items;
  replies are merged in and re-extracted. When the agents judge the claim complete (in the first email
  or after any number of replies), the claim moves to **`Pending Review`**, not `In Process`. A claims
  processor then does one last look-over on the claim detail page:
  - **Ready** → `In Process`, and the customer gets the "thank you, we're working on your claim" email.
  - **Needs more information** → the processor ticks the missing items → `Pending Information`, and
    the customer gets the missing-information email listing exactly the ticked items. That run ends
    there (no re-suspension); the customer's next email starts a new run, and the claim stays
    incomplete until the ticked items are answered, so an empty reply stays `Pending Information` (gap 8).
- **Design review:** the design lives in the repository as `docs/design/email-claim-intake.md` (plus
  PlantUML diagrams), opened as PR #220 (task 02) and **merged as-is with no review feedback** (`ec1ca14`).
  The spike (task 01) ran before the design, because its results shape it: it ran ahead of issue 1, on a
  throwaway local branch in a separate git worktree, with the latest stable versions, and its code is never
  merged or pushed.
- **Human review** (task 07): a `ClaimReviewAgent` with a **static** `@HumanInTheLoop` method returns
  `Object` (`new SuspendedResponse<>("review:" + scope.memoryId())`, no `async`), so the workflow suspends
  instead of blocking; the root surfaces `AgenticSystemSuspendedException`, which the processor catches. A
  `DatabaseAgenticScopeStore` (task 03; key `agentId|memoryId`, registered JVM-globally through
  `AgenticScopePersister` by a lowest-priority `StartupEvent` observer, reset on `ShutdownEvent`) saves the
  scope in PostgreSQL, so the pause survives restarts.
  - Memory id = the inbound email's `Message-ID`. The claim stores it in a `reviewRunId` column.
  - `ClaimReviewService.decide` (behind `POST /api/db/claims/{id}/review-decisions`, task 10) claims the
    review under optimistic locking, completes the pending response, re-invokes the root with the original
    arguments read back from the scope (`readState`), hands the outcome to
    `ClaimEmailProcessor.applyReviewOutcome` (task 08) and evicts the scope. A non-LLM router turns the
    decision into the outcome (gap 6).
  - **The scope is evicted on every ending:** completed, policy-rejected, superseded by a reply, failed, and
    after the resume. Only a run waiting for review keeps a row; scopes are never deleted automatically.
  - Decision gate: **passed** (spike verdict: feasible with workarounds). No status-only fallback.
- **Every submission gets a reply** confirming what the customer did and what happens next:
  - first email complete → "received — final review" (claim number, `Pending Review`, what we
    recorded, a claims processor does a final check and we'll be in touch, photos note, skipped-attachments fragment)
  - first email incomplete → missing information
  - reply that completes a pending claim → "received — final review"
  - reply still incomplete → still missing
  - reply while `Pending Review` → the old suspended scope is evicted, the reply merged, and the
    workflow re-run: still complete → stays `Pending Review` (new `reviewRunId`) with the "we've added
    your latest information; your claim is still in final review" variant; now incomplete →
    `Pending Information` + missing information
  - claim `In Process` or later (including seeded statuses) → status reply; the claim is never updated
  - not a claim / policy inconsistency / no matching claim → their templates
  - processing failure → message to the failed folder **and** a templated "processing problem" reply
    (best-effort, no details, call 1-800-CAR-SAFE)
  - reviewer decisions → thank-you (Ready) or missing information (Needs more information)
  - **no reply** only for auto-replies, self-sent mail and duplicate `Message-ID`s (already answered)

  The thank-you email is sent **only** after the reviewer clicks Ready.
- **Statuses used by intake:** `Pending Information`, `Pending Review`, `In Process`. Intake never sets `New`.
- **Summary and sentiment** are AI-generated, at most 5000 characters (guardrail).
- **Field sources:**
  - name and email: the `From:` header
  - claim number: generated (issue 2)
  - inception date: random, before the incident date
- **Policy number** (checked in code by the processor **after the agents finish**, before any claim is
  created or changed; gap 7):
  - stated + same customer → reuse
  - stated + a different customer → evict the run state (including a paused review) and reject with a
    vague email (call 1-800-CAR-SAFE), creating nothing
  - stated + unknown → new claim on that number
  - absent → generate a unique one
- **Follow-ups:**
  - Matched in code, before the workflow, by `[CLM…]` regex, then `In-Reply-To`/`References`. **The matched
    claim wins** over the classifier's label (gap 1).
  - Only from the claim's own email address. A wrong sender, or an unmatched email labelled a follow-up,
    gets the one "no matching claim" reply (no details; asks for the claim's address or the claim number; gap 9).
  - A `Pending Information` claim gets merged in, re-summarised, and moves to `Pending Review`
    ("received — final review" email) when complete.
  - A reply during `Pending Review` supersedes the waiting review (see above). It is serialised against
    the reviewer's decision by optimistic locking on the claim (`@Version`): a losing decision gets a 409,
    a losing reply re-reads the claim (user decision).
  - A claim `In Process` or later gets an AI-written status answer only, and is **never** updated.
- **Photos:** optional. Image attachments are stored as `ORIGINAL` via `ClaimImage.store`. Only the formats in #214's
  `ClaimImageContentType` allow-list (JPEG, PNG, GIF, WebP) count as images: resolve the attachment's media type with
  `ClaimImageContentType.find`. Anything else, including `image/svg+xml`, is a non-image attachment (stored-XSS risk).
  If none arrive, the reply says photos can be sent.
  Non-image or oversized attachments are skipped and mentioned. New claims get no processed images.
- **Idempotency:** a unique stored `Message-ID`. Replies carry `Auto-Submitted: auto-replied`, and
  inbound auto-replies and self-sent mail are skipped.
- **Inbox handling:** HTML-only email is converted to text. Mail is processed one at a time. Processed
  mail moves to a processed folder, failures to a failed folder.
- **Observability** (task 11):
  - One trace per email: the processor opens a root span `claim-intake process` (`setNoParent()`, `CONSUMER`,
    `gen_ai.operation.name=invoke_agent`) and stores its trace context on the claim when the run pauses.
  - A static `@AgentListenerSupplier` on the root, with `inheritedBySubagents()=true`, creates
    `invoke_agent <name>` spans for every agent and ends open composite spans on suspension (gap 3; spike Q16c).
    It is **not** a CDI `AgentListener`, which only reaches AI leaves. Leaf AI-service spans come from quarkus-langchain4j.
  - Each `@ParallelAgent` declares a `@ParallelExecutor` returning
    `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`, so the parallel sub-agents stay in the root trace.
  - Langfuse types observations from `gen_ai.operation.name` (`invoke_agent` → AGENT, `chat` → GENERATION, `execute_tool` → TOOL).
  - IMAP fetch/move and SMTP send get `CLIENT` spans.
  - The review decision span is **linked** (span link) to the original trace context stored on the claim.
  - **Conversation grouping (user decision):** each email and the review decision stay **separate traces**, grouped by
    `gen_ai.conversation.id` (Langfuse maps it to the session id, langfuse/langfuse#7738), so a claim is one Langfuse session.
    - The id is a per-claim UUID (not the claim number, which doesn't exist when the first spans start; no PII),
      minted for unmatched emails and stored on the claim (`intakeConversationId`) when a claim is created; follow-ups and
      the review decision reuse it (tasks 07, 08, 10).
    - It's set via OTel baggage made current before the root / review-decision span (as the chat's
      `ConversationalBaggageHandler` does), and a project-owned `ConversationIdSpanProcessor` copies it onto every
      span, so it works with the Langfuse processor off (`%test`) and for Tempo/LGTM (task 11).
    - Tier-2 session scoring doesn't apply (it's triggered only by `ChatScopeEnded`); the tier-1 judge still scores
      every intake LLM call.
  - `IntakeMetrics` registers the `claim.intake.*` meters, with no high-cardinality tags (no claim numbers, `Message-ID`s or addresses).
  - Intake log lines carry the trace id.
  - Intake LLM calls **are** scored by the tier-1 Langfuse judge (user decision).
  - Langfuse span export is off in `%test` (`quarkus.langfuse.otel.enabled: false`), except in `LangfuseSessionScoringServiceTests`.
  - `MonitoredAgent` is **dev-only** (user decision after spike Q18): prod has **no** monitor.
    `setMaxRetainedSessions(0)` doesn't bound memory: suspended runs stay in `ongoingExecutions` until resumed,
    and an abandoned review leaks forever, including the raw email, so the `@UnlessBuildProfile("dev")` cap is
    dropped. **Open implementation point (task 11):** the mechanism. The interface hierarchy is fixed at compile
    time, and a leaf agent must belong to exactly one root, so a second, dev-only root that shares the leaves
    isn't allowed. The design doc deliberately doesn't cover it (short format).
  - Grafana panels are a separate issue: #218 (Clean up the Grafana AI dashboard).
- **Out of scope:** non-English email, multiple incidents per email, drift detection for the agents,
  the reviewer editing extracted fields.

### Caveats & Problems
- Never hold a transaction open across an LLM call, including when a workflow resumes. Persist in `QuarkusTransaction.requiringNew()`.
- Tests must delete the claims and images they create (`ClaimsListPageTests` expects 6) and must
  never assert absolute claim numbers.
- Every `@QuarkusTest` stubs API keys through its profile, to avoid `SRCFG00011` under `-Pollama-openai`.
- Agent builds don't have real API keys. Only compilation (and LLM-mocked tests) are trustworthy signals.
- **Human review mechanics** (langchain4j-agentic beta; Quarkus adds no HITL or persistence support):
  - Beta module (`langchain4j-agentic` `-beta30`). Suspend/resume and persistence are documented upstream
    and mostly public; the only internal touch points are `SuspendedResponse` (`dev.langchain4j.agentic.internal`)
    and the `@Internal` `DefaultAgenticScope` exposed by the `AgenticScopeStore` SPI.
    `AgenticScopePersister.setStore` must run before any root's first invocation (lowest-priority
    `StartupEvent` observer, task 03). Keep every use behind `DatabaseAgenticScopeStore` and `ClaimReviewService`.
  - The scope store is a **JVM-global static** (`AgenticScopePersister`). It survives a Quarkus restart in the
    same JVM, so the registrar resets it on `ShutdownEvent`. Tests don't replace it.
  - Deserialization needs an allowlist for scope types that appear in no agent signature (e.g. the review
    decision): `AgenticScopeSerializer.allowDeserializationType`, checked by a startup round-trip self-test (task 03).
  - Persistent scopes are never deleted automatically: evict manually. No scope rows may leak, in the app or in tests.
  - Don't use `async = true` with suspension.
  - The `@HumanInTheLoop` method must be `static` (Quarkus build-time check), so it can't use CDI injection.
  - Decision gate: **passed** by task 01 (suspend/resume works through Quarkus); the status-only fallback isn't needed.
- `%drift` disables the OTel SDK, so no spans there (expected).
- **Incident time (deliberate difference from the design):** the merged design lists the extracted details as
  description, date, location, category and stated policy number, and doesn't mention the time. Task 05 also
  extracts the incident time when stated (ISO `HH:mm` string), because #213 adds an optional `incidentTime` to
  `Claim`. It is never required, never asked for and never makes a claim incomplete. Task 13 aligns the design
  doc if the implementation keeps it.
- **Open implementation points** (not in the design doc, by user decision; settle them in the named tasks).
  **Applied to the task files at the design gate** (after PR #220 merged):
  - How to make `MonitoredAgent` dev-only (task 11): see Key Decisions → Observability. **Applied:** task 11 drops
    the `@UnlessBuildProfile("dev")` cap, requires no monitor outside dev, and lists candidate mechanisms to
    investigate and present to the user (still a user decision at implementation time); task 13/14 updated.
  - A customer reply arriving while a reviewer is deciding (tasks 07/08/10). **Applied** to tasks 07
    (`@Version`, `decide(claimId, expectedVersion, …)`, race test), 08 (rule 5 lock and lost-lock test) and 10
    (`version` in the body, 409 on a lost lock). **Decided (user confirmed):**
    the reply supersedes the review and evicts its scope, while the decision tries to resume the same
    scope. Serialise the two with optimistic locking on the claim (`@Version`): whichever loses gets a
    409 (decision) or re-reads the claim (reply). Cover it with a test. Fold this into tasks 07, 08 and
    10 together with the spike-driven changes after the design review.
- **Gaps found while writing the design doc** (found at design commit `876fe9d`; merged unchanged at `29b5073` in
  PR #220, merge commit `ec1ca14`). **All nine are applied to the task files** (at the design gate), as follows:
  1 → tasks 04, 06, 08; 2 → tasks 07, 08, 10 and Key Decisions; 3 → task 11 and Key Decisions; 4 → tasks 03, 07;
  5 → tasks 05, 11; 6 → task 07; 7 → task 08 and Key Decisions; 8 → tasks 05, 08 (empty-reply tests);
  9 → tasks 04, 06, 08. The original text is kept below as history.
  1. **Classification vs. claim resolution, decided (user):** the matched claim wins.
     - If code resolves a claim (`[CLM…]` regex or reply headers), the email is handled as a follow-up on
       that claim, whatever the classifier says. The sender check still applies.
     - If the classifier says follow-up but nothing matches, the customer gets the "no matching claim"
       reply, which asks for their claim number. No claim is created or changed.

     Reflected in the design workflow. Fold it into task-06 (routing), task-08 (processor rules + tests)
     and task-04 (the "no matching claim" template asks for the claim number).
  2. **Needs more information after resume:** Key Decisions end that resumed run at `Pending Information`
     (the customer's next email starts a new run). Spike Results' task-10 list says "handle NEEDS_INFO
     re-suspension". The design follows Key Decisions; drop the re-suspension wording.
  3. **AgentListener contradiction:** Key Decisions → Observability and task-11 say a CDI `AgentListener`
     bean creates the composite-agent spans; spike Q16c shows it only reaches AI leaf agents. Use a root
     `@AgentListenerSupplier` with `inheritedBySubagents()=true` (spike wins).
  4. **task-07 vs. spike:**
     - root return type: catch `AgenticSystemSuspendedException`, per the spike, rather than `ResultWithAgenticScope`
     - scope-row key: `agentId|memoryId`, per the spike, rather than memory id only
     - allowlist: `allowDeserializationType`, per the spike, rather than `registerForDeserializationPackageOf`
  5. **Stale task files already covered by "Changes required in later tasks":**
     - task-05: `LocalDate`/`LocalTime` in scope values → ISO strings
     - task-11: `MonitoredAgent` capped via `@UnlessBuildProfile("dev")` → dev-only per the user decision
     - task-11: CDI listener → see item 3
  6. **Review decision router:** the design shows it as a non-LLM agent (an `@ActivationCondition` route,
     as in the spike). Make task-07 say so explicitly.
  7. **Policy check placement (from the design review):** the stated policy number comes from the
     extraction agent inside the root workflow, so for a complete claim the run has already paused when
     the processor checks it. The processor applies the policy rule in code after the agents finish and
     before any claim is created or changed. On rejection it **evicts the run state (including a paused
     review)**, sends the policy-inconsistency reply, and creates no claim. Fold this into task-08 (rule,
     eviction and a test).
  8. **Reviewer-ticked items (from the design review):** after **Needs more information**, completeness
     also requires answers to the items the reviewer ticked, so a reply without content stays
     `Pending Information`. Fold this into task-05 (extraction receives the requested items),
     task-08 (completeness rule) and their tests, including an empty-reply test.
  9. **"No matching claim" is one template** used for both the wrong-sender case and an unmatched
     follow-up. It gives no details and asks the customer to write from the claim's address or include
     the claim number (task-04).

### Design
- **Design doc:** `docs/design/email-claim-intake.md`, now on `main`.
  - **PR:** [#220](https://github.com/edeandrea/non-deterministic-no-problem/pull/220), **merged** at `ec1ca14`
    (2026-10-02) with no review feedback: the design was approved as-is, so the **gate is passed**.
  - The remote branch `design/email-claim-intake` was deleted on merge. The local branch and the worktree
    `../non-deterministic-no-problem-design-216` (at `29b5073`) still exist and are no longer needed; remove them
    when convenient (`git worktree remove`, `git branch -D`).
  - The doc's status line still says "proposed, for review before implementation"; task 13 flips it to accepted/implemented.
- **Scope (user decision):** short. Four sections (goal, workflow, claim states, agent architecture) and three
  diagrams (email workflow, claim states, agent organisation). No open questions, communication matrix,
  alternatives or review-sequence diagram.
  - This supersedes the task-02 bullet under "Changes required in later tasks" below, which asked for a review
    sequence diagram, span details and the `MonitoredAgent` decision.
- **Checks:** independently reviewed twice before opening; all findings fixed.
- **#216 issue body:** its design section says the design was reviewed and merged in PR #220, and its Architecture,
  Business rules, Spike, Observability and Testing sections match the merged design and the spike.
- **Gate outcome:** the gaps list, the open implementation points and "Changes required in later tasks" are folded
  into tasks 03–14 (see Caveats). Task 03 now also builds the scope store, registrar and self-test (moved from
  task 07, because the store must be registered before any agent runs).
- **Gate: passed.** The review is done (PR #220 merged, no changes requested), and the gaps list above and
  "Changes required in later tasks" are applied to the task files. Tasks 03+ still wait for the prerequisites
  (#212 via PR #219, then #213–#215; see Your Mission).

### Spike Results
Full evidence, the executive summary and the per-question answers (Q1–Q19) are in
[`spike-results.md`](spike-results.md).
- **Setup:** spike branch `spike/agentic-hitl` (local, unpushed), run on quarkus-langchain4j 1.14.1,
  langchain4j-agentic 1.20.2-beta30 and Quarkus 3.40.1. All 32 tests pass.
- **Feasibility verdict: YES, with workarounds.** Static `@HumanInTheLoop` → `SuspendedResponse`, a DB-backed
  `AgenticScopeStore`, `@MemoryId` = Message-ID, and a REST resume after a genuine restart all work. Completed agents
  are not called again on resume. The decision gate in task 07 is passed; no status-only fallback is needed.
- **Agents:** every AI leaf needs
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`.
  - The defaults are policy Easy RAG plus one memory shared across runs (`"default"`), and the root's `@MemoryId`
    does not change that.
  - Every entry agent must be injected in `src/main`, or build validation fails.
- **Store:**
  - Register it with `AgenticScopePersister.setStore` from a lowest-priority `StartupEvent` observer, and reset it on
    `ShutdownEvent`.
  - Saves run in `requiringNew`, so they are not atomic with claim updates.
  - Evict manually on every ending. The scope also stays in memory until it is evicted, so run a single replica.
- **Resume:** read the root arguments back from the scope (`readState`) and pass them again. The arguments
  overwrite the stored state.
- **Scope values:**
  - No `LocalDate`/`Optional` anywhere in agent outputs or the decision (ISO strings instead).
  - Allowlist the decision type with `allowDeserializationType`.
  - Consumers type the review output as `Object`.
  - No `async` on the HITL agent.
- **Observability:**
  - a CONSUMER root span per email with `gen_ai.operation.name=invoke_agent`
  - a root `@AgentListenerSupplier` listener (inherited) emitting `invoke_agent <name>` spans; a CDI
    `AgentListener` bean only sees AI leaves
  - `@ParallelExecutor` returning `Context.taskWrapping(...)` on each `@ParallelAgent`
  - the review resume in a new trace with a span link
  - Langfuse types `invoke_agent` spans as AGENT, `chat` as GENERATION and `execute_tool` as TOOL
- **Needs a user decision** (decided: `MonitoredAgent` dev-only, no monitor in prod; the mechanism is open in task 11):
  - `MonitoredAgent` with `setMaxRetainedSessions(0)` still keeps every suspended run in `ongoingExecutions`, and
    an abandoned review leaks forever.
  - Leaf agents are CDI singletons shared across roots, so give each leaf exactly one root.

### Flow Spike Results (task 01b) — IN PROGRESS
Full evidence in [`spike-results-flow.md`](spike-results-flow.md). **Suspended mid-spike; no verdict yet.**

> **Picking this up?** Start at [`spike-results-flow.md`](spike-results-flow.md) → *Resuming this spike*.
> The worktree `../non-deterministic-no-problem-spike-flow` and branch `spike/flow-hitl` **already exist
> on this machine**, with the pom patch committed as `c3ab343` (local only, never pushed) — nothing needs
> recreating. First action is step 1: finish the gate by adding one `@SequenceAgent` and booting a
> `@QuarkusTest` (~15 min).

- **Why:** [quarkus-flow](https://docs.quarkiverse.io/quarkus-flow/dev/index.html) would replace exactly the
  workaround half of the merged design — `DatabaseAgenticScopeStore` + entity, the
  `AgenticScopePersister.setStore` registrar, the startup self-test, the `ShutdownEvent` reset, the
  `allowDeserializationType` allowlist, the hand-rolled replay, **and both internal-API touch points**
  (`SuspendedResponse`, `DefaultAgenticScope`) — plus the hand-rolled observability of spike items 12–18.
- **Setup:** worktree `…-spike-flow`, branch `spike/flow-hitl` (local, unpushed) from `main` @ `8ed467d`.
  Flow **1.1.3** (latest release; **no 1.2.0 final exists** — latest prerelease is 1.2.0.CR3).
- **Gate: PASSED so far, at 3.40.1 / 1.14.1 with no downgrade.**
  - `test-compile` and a full `package` (augmentation) both `BUILD SUCCESS`.
  - Flow 1.1.3 + serverlessworkflow 7.32.1.Final coexist with quarkus-langchain4j **1.14.1** /
    langchain4j-agentic **1.20.2-beta30**; the BOM import wins, so nothing is dragged back to 1.13.3.
  - **The skew is low-risk:** `quarkus-flow-langchain4j-deployment` scans Jandex itself and couples to
    exactly **one** build item, `DetectedAiAgentBuildItem`, which is **API-identical** between 1.13.3 and
    1.14.1 (`javap` verified), plus one runtime class, `AgenticSystemTopology`.
  - Its `quarkus-langchain4j-ollama-deployment` dependency is **test**-scoped, so it does not leak here.
  - **Still open:** the augmentation ran with no `@SequenceAgent` present, so the agentic-translation path
    did not execute. One `@SequenceAgent` + a booting `@QuarkusTest` finishes the gate.
- **The review contract looks preservable** — the three questions flagged as possible blockers all have
  API-level answers: `WorkflowStatus.WAITING` + `PersistenceInstanceReader.find(def, id)` +
  `PersistenceWorkflowInfo.tasks()` for the "waiting at task Y" check (C8);
  `WorkflowInstance.cancel()` for superseding, so the `toAny` workaround is a fallback not a requirement
  (C9); and `dataAs(Class, predicate)` for business-key correlation, keeping `Claim.reviewRunId`
  meaningful (C10). One check closes all three: whether `PersistenceInstanceReader` is CDI-injectable in
  `quarkus-flow-jpa`.
- **Unproven and load-bearing:** A1 (can `function(...)` be followed by `emitJson`/`listen`/
  `switchWhenOrElse` in one task list), A3 (in-process `publish` wakes it **and** completed tasks are
  skipped), A4 (**pivotal** — is a crash *inside* the agentic subflow resumable, or does the whole task
  re-run all four LLM calls), B6 (survives a genuine restart).
- **Two corrections to the pre-settled research:**
  1. *"Never re-executes completed tasks"* is **not** in `persistence.html` — it only says execution
     resumes "from its last recorded checkpoint". That makes A4 more load-bearing, not less.
  2. Neither the persistence nor the correlation docs document any query/cancel API; the API inventory
     found them anyway (above).
- **Langfuse typing is not a cost to price.** The user owns the `quarkus-langfuse` extension and
  `ai.scoring` is an explicit staging area for logic to be generalized upstream (Flow, langchain4j,
  quarkus-langfuse), with `AiServiceDatasetSpanProcessor` as the in-repo precedent for stamping
  attributes onto spans the app does not own. D12/D13 become "where does the fix live", not "is this a
  blocker". D11 must still confirm the leaf `langchain4j.aiservices.<Agent>.<method>` naming empirically,
  because the dataset-name invariant degrades **silently**.
- **Decision criteria (unchanged):** adopt only if the gate passes with no downgrade, A1–A3 and B6 work,
  and C8 has an answer or the `404`/`409` contract survives another way. Otherwise keep the merged design
  and file an upstream ask for **Flow implementing `AgenticScopeStore` / `AgenticScopePersister`**, which
  would replace only `DatabaseAgenticScopeStore` and leave the merged design's shape intact.
- **Standing note, independent of the verdict:** every current profile wipes durable state on boot
  (`drop-and-create` in `%prod`/`%openshift`, Dev Services defaults elsewhere), so the merged design's
  own scope table does not survive a restart either. Design doc step 8 does not hold today. Raise on #216.

#### Changes required in later tasks
**Applied to tasks 03–14 at the design gate.** Two items were adjusted when applied: the task-03 store
items also absorbed task 07's store work, and the task-10 "handle NEEDS_INFO re-suspension" item was dropped
(gap 2: Needs more information ends the run at `Pending Information`). Task 08 evicts on **every** ending,
not only non-suspended ones (policy rejection and superseding evict a paused review too).
- **task-02 (design):**
  - cite the executive summary
  - the topology diagram shows the root span, the listener spans and the parallel executor
  - the review sequence shows suspend → `Pending Review` → REST `completePendingResponse` → re-invoke with
    arguments from the scope → evict
  - record the `MonitoredAgent` decision
- **task-03 (config/mailbox):**
  - add the `quarkus-langchain4j-agentic` dependency
  - the `claim-intake` named model config
  - the agentic-scope entity/table (`agentId|memoryId` key, `text` JSON, `updatedAt`)
  - the store registrar (`StartupEvent` at the lowest priority, plus a `ShutdownEvent` reset)
  - `allowDeserializationType` for decision/DTO types at startup
  - a startup round-trip self-test of a sample scope
- **task-05 (extraction):**
  - opt-out annotations on every agent
  - DTOs use ISO-string dates, no `Optional`
  - each parallel agent gets the `@ParallelExecutor`
  - add a JSON-extracting output guardrail (parse failures abort the workflow)
  - leaf agents are not reused across roots
- **task-06 (triage):** same opt-outs; route with typed `@ActivationCondition`s; keep conditions cheap, since all
  of them are evaluated.
- **task-07 (human review):**
  - static `@HumanInTheLoop` returning `Object` (`new SuspendedResponse<>("review:" + scope.memoryId())`)
  - no `async`
  - consumers take `Object`
  - the decision record holds no `java.time`/`Optional`
- **task-08 (processor):**
  - root `process(@MemoryId String messageId, …)`, extending `AgenticScopeAccess`, injected into the processor
  - catch `AgenticSystemSuspendedException` → `Pending Review` plus the email
  - side effects happen here, not in agents
  - evict on every non-suspended ending
  - open the CONSUMER root span and store its trace context for the span link
- **task-09 (IMAP watcher):** start after the store registrar (`@Startup`/default priority); no agent calls in
  low-priority startup observers.
- **task-10 (review API/UI):**
  - `POST …/review-decisions` → `getAgenticScope` (404 on `null`)
  - check `review:<messageId>` is pending (409)
  - `completePendingResponse` → re-invoke with `readState` arguments
  - evict on completion; handle NEEDS_INFO re-suspension
  - a SERVER span with a link to the intake trace
- **task-11 (observability):**
  - the root `@AgentListenerSupplier` span listener (end open spans on `onAgenticSystemSuspended`)
  - the `@ParallelExecutor` everywhere
  - Langfuse attribute choices
  - a CDI `AgentListener` only for leaf metrics
  - handle the `MonitoredAgent` `ongoingExecutions` leak per the user's decision
- **task-12 (Roundcube E2E):** stub or mock the LLM agents, with WireMock stubs matching on the last message.
- **task-13 (demo/docs):** document the opt-outs, single replica, store lifecycle and eviction.
- **task-14 (verification):**
  - the restart test needs a DB that survives a Quarkus restart (the test Postgres dev service is recreated)
  - sub-agent `@InjectMock` tests use a dedicated test profile
  - a test proves no scope rows leak
