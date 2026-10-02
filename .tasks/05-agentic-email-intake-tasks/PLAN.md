# Issue 5: Agentic Email Claim Intake — Task Execution Plan

## Your Mission

A customer emails `claims@parasol.com` (from Roundcube). An IMAP IDLE watcher picks the email up, and
a `quarkus-langchain4j-agentic` workflow triages it and extracts the claim details. The app then
creates or updates the claim, stores photos, replies with Qute templates, and files the email. Before a
claim moves to `In Process`, a claims processor does a final review: the workflow pauses at a
`@HumanInTheLoop` step, suspended and persisted in PostgreSQL, and the processor's decision resumes it.
A design document (with state, workflow, agent-topology and review diagrams) is written and reviewed externally before implementation starts.
Last of five issues; see `.tasks/claim-intake-roadmap.md`. **Issues 1–4 must be complete before the
implementation tasks (03 onwards).** The spike (task 01) and the design (task 02) run ahead of them.

**Plan File:** `.tasks/05-agentic-email-intake-tasks/PLAN.md`
**Tasks Directory:** `.tasks/05-agentic-email-intake-tasks/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions, spike results and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/05-agentic-email-intake-tasks/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md` (coding style, AssertJ, records, `Optional`, constructor injection, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

Task 02 is a hard gate: implementation tasks (03+) start only after the user confirms the external design review is complete and the agreed changes are applied.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any? (The spike in task 01 may force changes.)

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [ ] [task-01-agentic-spike.md](task-01-agentic-spike.md): Agentic module spike (runs first, throwaway)
- [ ] [task-02-design-and-review.md](task-02-design-and-review.md): Design document and review gate
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

---

## Shared Context

### Overview
Code in a new package `org.parasol.intake` (with `agent`, `mailbox`, `reply` and `review` sub-packages).
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
- **Triage flow:** classify (`NEW_CLAIM` / `CLAIM_FOLLOW_UP` / `NOT_A_CLAIM`), then route. Agents have
  **no side effects**; `ClaimEmailProcessor` does all persistence, mail and folder moves.
- **Required details:** what happened, the incident date, the location and the category (`OTHER` is a
  valid category). The agents do all the back-and-forth with the customer: if any detail is missing,
  the claim is `Pending Information` and the customer is emailed the exact list of missing items;
  replies are merged in and re-extracted. When the agents judge the claim complete (in the first email
  or after any number of replies), the claim moves to **`Pending Review`**, not `In Process`. A claims
  processor then does one last look-over on the claim detail page:
  - **Ready** → `In Process`, and the customer gets the "thank you, we're working on your claim" email.
  - **Needs more information** → the processor ticks the missing items → `Pending Information`, and
    the customer gets the missing-information email listing exactly the ticked items. The agents then
    continue as before.
- **Design review:** the design lives in the repository as `docs/design/email-claim-intake.md` (plus
  PlantUML diagrams) and is opened as a pull request (task 02). An external reviewer reviews it, and
  implementation (task 03 onwards) starts only after the user confirms sign-off. The spike (task 01) runs
  before the design, because its results shape it: it runs ahead of issue 1, on a throwaway local branch
  in a separate git worktree, with the latest stable versions, and its code is never merged or pushed.
- **Human review** (task 07): a `ClaimReviewAgent` with a **static** `@HumanInTheLoop` method returns a
  `SuspendedResponse`, so the workflow suspends instead of blocking. A `DatabaseAgenticScopeStore`
  (registered JVM-globally through `AgenticScopePersister`) saves the scope in PostgreSQL, so the pause
  survives restarts.
  - Memory id = the inbound email's `Message-ID`. The claim stores it in a `reviewRunId` column.
  - `ClaimReviewService.decide` (behind `POST /api/db/claims/{id}/review-decisions`, task 10) completes
    the pending response, re-invokes the root agent with the same memory id, and hands the outcome to
    `ClaimEmailProcessor.applyReviewOutcome` (task 08).
  - The scope of every run that doesn't suspend is evicted, and so is a resumed one; scopes are never
    deleted automatically.
  - Decision gate: if the spike shows suspend/resume can't work through Quarkus, stop and ask the user.
    The fallback is a status-only review with the same statuses, REST API and UI.
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
- **Policy number:**
  - stated + same customer → reuse
  - stated + a different customer → reject with a vague email (call 1-800-CAR-SAFE), creating nothing
  - stated + unknown → new claim on that number
  - absent → generate a unique one
- **Follow-ups:**
  - Matched by `[CLM…]` regex, then `In-Reply-To`/`References`.
  - Only from the claim's own email address.
  - A `Pending Information` claim gets merged in, re-summarised, and moves to `Pending Review`
    ("received — final review" email) when complete.
  - A reply during `Pending Review` supersedes the waiting review (see above).
  - A claim `In Process` or later gets an AI-written status answer only, and is **never** updated.
- **Photos:** optional. `image/*` attachments are stored as `ORIGINAL`. If none arrive, the reply says photos can be sent.
  Non-image or oversized attachments are skipped and mentioned. New claims get no processed images.
- **Idempotency:** a unique stored `Message-ID`. Replies carry `Auto-Submitted: auto-replied`, and
  inbound auto-replies and self-sent mail are skipped.
- **Inbox handling:** HTML-only email is converted to text. Mail is processed one at a time. Processed
  mail moves to a processed folder, failures to a failed folder.
- **Observability** (task 11):
  - One trace per email: the watcher starts a root span `claim-intake process` (`setNoParent()`, `CONSUMER`).
  - A CDI `AgentListener` creates spans for composite and non-AI agents; leaf `@Agent` spans come from quarkus-langchain4j.
  - `@ParallelAgent` runs on a context-propagating executor, so the parallel sub-agents stay in the root trace.
  - IMAP fetch/move and SMTP send get `CLIENT` spans.
  - The review decision span is **linked** (span link) to the original trace context stored on the claim.
  - `IntakeMetrics` registers the `claim.intake.*` meters, with no high-cardinality tags (no claim numbers, `Message-ID`s or addresses).
  - Intake log lines carry the trace id.
  - Intake LLM calls **are** scored by the tier-1 Langfuse judge (user decision).
  - Langfuse span export is off in `%test` (`quarkus.langfuse.otel.enabled: false`), except in `LangfuseSessionScoringServiceTests`.
  - The root agent extends `MonitoredAgent` for the Dev UI; outside dev it's capped (`setMaxRetainedSessions(0)`).
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
    `AgenticScopePersister.setStore` must run before any agent is built. Keep every use behind
    `DatabaseAgenticScopeStore` and `ClaimReviewService`.
  - The scope store is a **JVM-global static** (`AgenticScopePersister`). Tests that replace it must restore it afterwards.
  - Deserialization needs an allowlist for the intake records and enums
    (`AgenticScopeSerializer.registerForDeserializationPackageOf`).
  - Persistent scopes are never deleted automatically: evict manually. No scope rows may leak, in the app or in tests.
  - Don't use `async = true` with suspension.
  - The `@HumanInTheLoop` method must be `static` (Quarkus build-time check), so it can't use CDI injection.
  - Decision gate: if task 01 finds suspend/resume doesn't work through Quarkus, **stop and ask the
    user**. The fallback is a status-only review (same statuses, REST API and UI).
- `%drift` disables the OTel SDK, so no spans there (expected).

### Design
Design doc: `docs/design/email-claim-intake.md` (PR: _to be filled by task 02_)

### Spike Results
_(Filled in by task 01; full evidence in [`spike-results.md`](spike-results.md).)_
