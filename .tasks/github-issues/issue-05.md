TITLE: Agentic email claim intake and triage
## Summary

A customer emails `claims@parasol.com` from Roundcube. An IMAP IDLE watcher picks the email up, and a
`quarkus-langchain4j-agentic` workflow:
- classifies it (new claim / follow-up / not a claim)
- extracts the claim details, summary and sentiment

The app then creates or updates the claim, stores photo attachments, replies with Qute-templated emails,
and files the message. Every customer email gets a reply. Once a claim is complete, a claims processor
does a final human review (`Pending Review`) before it moves to `In Process`: the workflow pauses at a
`@HumanInTheLoop` step, suspended and persisted in PostgreSQL, and resumes on the processor's decision.
The existing chat (`ClaimService`, chat scopes, `NotificationService`) is unchanged.

**Depends on** {{ISSUE_1}}, {{ISSUE_2}}, {{ISSUE_3}} and {{ISSUE_4}}.

```
Roundcube ──SMTP──▶ GreenMail ◀──SMTP── app (Qute replies)
                        ▲
                        └──IMAP IDLE── ClaimsInboxWatcher → ClaimEmailProcessor → ClaimsMailboxAgent
                                                                                    ⇣ suspends at ClaimReviewAgent
                                                                                    ⇣ (scope saved in PostgreSQL)
                         claims processor (UI) → ClaimReviewService → resume ⇢ ClaimEmailProcessor outcome
```

## Design and review first

- A **spike** (throwaway code on a local branch, latest stable versions, run ahead of {{ISSUE_1}}) answers the open framework questions listed below.
- `docs/design/email-claim-intake.md` then documents the "what": goal, actors, claim lifecycle, workflow steps, email rules, customer communication matrix, human review and non-functional requirements.
- PlantUML diagrams: claim state, email workflow, agent topology and review sequence.
- It then proposes the agentic architecture, backed by the spike evidence, with the alternatives considered.
- The design is opened as a pull request for an external design review. **Implementation starts only after sign-off.**

## Architecture

- **Package:** new package `org.parasol.intake`, with sub-packages `agent`, `mailbox`, `reply` and `review`.
- **`ClaimsInboxWatcher`:** a dedicated thread that:
  - connects with backoff (never fails startup)
  - processes the INBOX backlog
  - waits with IMAP IDLE
  - handles one message at a time
  - reconnects after a dropped connection
- **`ClaimsMailbox`:**
  - Parses messages into an `InboundEmail` record:
    - `Message-ID`, `In-Reply-To` / `References`
    - sender, subject, sent date
    - text body: HTML-only bodies converted with jsoup, quoted reply text stripped
    - image attachments
    - auto-reply headers
  - Moves messages to the processed or failed folder.
- **Agents:** side-effect free, using model `claim-intake`, with no policy RAG:
  - `EmailClassifierAgent` → `NEW_CLAIM` / `CLAIM_FOLLOW_UP` / `NOT_A_CLAIM`
  - `EmailRouter` (`@ConditionalAgent`)
  - `ClaimExtractionWorkflow` (`@ParallelAgent`) runs three agents in parallel:
    - `ClaimSummaryAgent`
    - `ClaimSentimentAgent`
    - `IncidentDetailsAgent`: description, date, time, location, category, and the policy number if stated. Relative dates are resolved against the sent date.
  - `ClaimFollowUpAgent`:
    - pending claim → extract the newly supplied details
    - non-pending claim → AI-written status answer, using a read-only tool
  - `ClaimReviewAgent`: a static `@HumanInTheLoop` method returning a `SuspendedResponse`. A complete
    claim's workflow suspends here until a claims processor decides (memory id = the inbound `Message-ID`).
  - `ClaimsMailboxAgent` (`@SequenceAgent`) is the entry point and returns a sealed `IntakeOutcome`.
- **`DatabaseAgenticScopeStore`:** an `AgenticScopeStore` that saves suspended scopes in PostgreSQL, so
  a waiting review survives restarts. Scopes of runs that don't suspend are evicted.
- **Beta API:** beta module (`langchain4j-agentic` `-beta30`). Suspend/resume and persistence are documented
  upstream and mostly public; the only internal touch points are `SuspendedResponse`
  (`dev.langchain4j.agentic.internal`) and the `@Internal` `DefaultAgenticScope` exposed by the
  `AgenticScopeStore` SPI. `AgenticScopePersister.setStore` must run before any agent is built.
- **`ClaimReviewService`:** completes the pending response, resumes the workflow with the same memory
  id, and applies the outcome. Exposed as `POST /api/db/claims/{id}/review-decisions`
  (`READY` / `NEEDS_INFORMATION` + ticked missing items; RFC 9457 Problem Details for 400/404/409).
- **`ClaimEmailProcessor`** does all persistence, mail and folder moves. LLM calls run outside
  transactions (including on resume), and persistence uses `QuarkusTransaction.requiringNew()`.
- **`IntakeConfig`** (`@ConfigMapping`, `parasol.intake.*`):
  - enabled (off in `%test`)
  - inbox address, IMAP settings, folder names
  - body cap for the LLM
  - image size and count limits

## Business rules

1. **Required details:** what happened, the incident date, the location, and the category (`OTHER` is valid).
   If any is missing, the claim is created as **`Pending Information`** and the customer is emailed the exact list of missing items.
   When every required detail is present — in the first email or after any number of replies — the claim moves to **`Pending Review`** and the customer is sent a "received — final review" email. A claims processor then does a final review on the claim detail page:
   - **Ready** → **`In Process`**, and the customer is sent a "thank you, we're working on your claim" email.
   - **Needs more information** → the processor ticks the missing items → `Pending Information`, and the customer is emailed exactly the ticked items. The agents then continue as before.

   Intake uses `Pending Information`, `Pending Review` and `In Process`, and never leaves a claim in `New`.
2. **Where fields come from:**
   - name and email: the `From:` header
   - claim number: generated ({{ISSUE_2}})
   - summary and sentiment: AI-generated, at most 5000 characters each (output guardrail)
   - inception date: random, before the incident date

   An incident date in the future is treated as missing.
3. **Policy number:**
   - stated, and belongs to the same customer → reuse it
   - stated, and belongs to a **different** customer → reject with a deliberately vague email
     ("there's an inconsistency with your policy information; call 1-800-CAR-SAFE"). No details are
     given (anti-phishing / prompt injection), and nothing is created.
   - stated but unknown → create a new claim on that number
   - absent → generate a unique one
4. **Follow-ups:**
   - **Matching (code, not the LLM):** a `[CLM…]` / `CLM\d+` regex first, then `In-Reply-To` / `References`.
   - **Sender check:** accepted only from the claim's own email address. Otherwise the reply is a vague "no matching claim".
   - **Pending claim:**
     - The reply is appended to the body, and details are merged (never overwritten with null).
     - Summary and sentiment are re-run.
     - Once complete, the claim becomes `Pending Review` and the "received — final review" email is sent. Otherwise a "still missing" email is sent.
   - **Claim in `Pending Review`:** the reply supersedes the waiting review. The old suspended scope is evicted, the reply is merged, and the workflow re-runs. Still complete → stays `Pending Review` with a "we've added your latest information; your claim is still in final review" email. Now incomplete → `Pending Information` with the missing-information email.
   - **Claim `In Process` or later:** status answer only. The claim is **never updated**.
5. **Photos:**
   - Optional. `image/*` attachments are stored as `ORIGINAL` ({{ISSUE_3}}).
   - If none arrive, the reply mentions that photos can be sent.
   - Non-image or oversized attachments are skipped, and the reply mentions them.
   - New claims never get processed images.
6. **Idempotency:** each `Message-ID` is stored with a unique constraint, for claims and for follow-up correspondence. Reprocessing a message does nothing.
7. **Loop protection:**
   - Skip messages marked `Auto-Submitted` or `Precedence: bulk|auto_reply|junk`, and messages sent from the claims address itself.
   - Our replies carry `Auto-Submitted: auto-replied`, a `[CLM…]` subject prefix, and threading headers.
8. **Not a claim:** templated "this inbox handles claims only" reply.
9. **Failures:** the message moves to the failed folder and is logged with its `Message-ID`. No partial claim is left behind. The customer gets a best-effort, templated "processing problem" reply (we received your email but couldn't process it automatically; a team member will follow up, or call 1-800-CAR-SAFE), with no details.
10. **Every customer submission gets a reply** confirming what the customer did and what happens next:
    - first email complete → received — final review
    - first email incomplete → missing information
    - reply that completes a pending claim → received — final review
    - reply still incomplete → still missing
    - reply during `Pending Review` → the "still in final review" variant, or missing information
    - claim `In Process` or later → status answer
    - not a claim / policy inconsistency / no matching claim → their templates
    - processing failure → processing problem
    - reviewer decisions → thank you (Ready) or missing information (Needs more information)

    No reply only for auto-replies, self-sent mail and duplicate `Message-ID`s (already answered).

## Replies

Fixed **Qute** mail templates (HTML + text), deliberately contrasting with the AI-written `GenerateEmailService`:
- received — final review (sent whenever a claim becomes complete: in the first email, or after back-and-forth), plus a "we've added your latest information" variant for a reply during review
- thank you / claim in process (sent **only** after the reviewer clicks Ready)
- missing information (also used for the reviewer's "Needs more information", listing the ticked items)
- still missing
- policy inconsistency
- not a claim
- no matching claim
- processing problem (no details)
- a skipped-attachments fragment

The phone number and sign-off move into one shared source, used by both the templates and `GenerateEmailService`.

## UI

`Pending Information` and `Pending Review` get label colours and status-filter options in `ClaimsList.tsx` / `ClaimDetail.tsx`.
For a `Pending Review` claim, `ClaimDetail.tsx` shows a review panel in place of the disabled Edit button: **Ready for processing**, or **Request more information** with one checkbox per missing item.

## Observability

- **One trace per email:** the watcher starts a root span `claim-intake process` (no parent, kind `CONSUMER`) for each message, with the classification, claim number, resulting status and reply template as attributes. Errors are recorded; a suspended run is a normal outcome.
- **Agent spans:** a CDI `AgentListener` adds spans for composite agents (`@SequenceAgent`, `@ConditionalAgent`, `@ParallelAgent`) and non-AI agents (including `ClaimReviewAgent`). Leaf `@Agent` spans come from quarkus-langchain4j.
- **Parallel context propagation:** `@ParallelAgent` runs on a context-propagating executor, so the parallel sub-agents stay in the root trace.
- **Mail spans:** IMAP fetch/move and SMTP send get `CLIENT` spans (no addresses or bodies on spans).
- **Review link:** the review decision span carries a span link to the original trace context, stored on the claim when the run suspends.
- **Metrics** (`IntakeMetrics`, no claim numbers, `Message-ID`s or addresses as tags):
  - `claim.intake.emails`, `claim.intake.processing`
  - `claim.intake.claim.status.transitions`, `claim.intake.replies`
  - `claim.intake.review.decisions`, `claim.intake.review.wait`, `claim.intake.reviews.pending`
  - `claim.intake.failures`, `claim.intake.watcher.connected`, `claim.intake.watcher.reconnects`
- **Logs:** intake log lines carry the trace id.
- **Langfuse:** intake LLM calls are scored by the Langfuse judge. Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`.
- **Dev UI:** the root agent extends `MonitoredAgent` (Topology and Executions pages); outside dev the monitor retains no sessions.
- **Grafana:** panels for the intake metrics are tracked in {{ISSUE_6}}.

## Spike (runs before the design)

- `@ModelName` works on agents.
- `@InjectMock` works on agents.
- Nesting works: Sequence → Conditional → Parallel, with values shared through the agentic scope.
- Agents can return enums and records.
- Output guardrails run on agents, and whether guardrail instances are CDI beans or reflection-created.
- Whether Easy RAG is attached to agents automatically, and how to opt out.
- The span names agents produce vs `langchain4j.aiservices.*`. Drift detection for agents is out of scope.
- `@ToolBox` tools work on agents.
- **Human review:**
  - the exact API for a static `@HumanInTheLoop` method returning a `SuspendedResponse`, accepted by Quarkus' build-time validation
  - suspension propagates from nested agents to the root (suspended result vs `AgenticSystemSuspendedException`)
  - resume after a simulated restart doesn't re-invoke completed agents, and which arguments it needs
  - the `AgenticScopeStore` signatures and registration, and whether it can use Hibernate in `requiringNew()`
  - serialization of records/enums/`LocalDate`/`Optional`, and the deserialization allowlist
  - eviction deletes from the store (a missing key is safe); `async = true` isn't combined with suspension
  - a feasibility verdict; if suspend/resume can't work through Quarkus, the fallback is a status-only review
- **Observability:**
  - the span tree for one email, whether a CDI `AgentListener` fires for composite and non-AI agents (including HITL), and how Langfuse types these observations
  - whether a `@ParallelExecutor` returning `Context.taskWrapping(executor)` keeps the parallel sub-agents in the root trace
  - the earliest startup hook that runs before agents are built (`AgenticScopePersister.setStore`) and before the first `MonitoredAgent` use

## Testing: every edge case

- **Unit:**
  - activation conditions and outcome mapping
  - the `@Output` combiner
  - the missing-information calculation (`OTHER` vs null)
  - the length guardrail at 5000 and 5001 characters
  - the future-date rule
- **Agent tests (WireMock-mocked LLM):** each route and each extraction case.
- **Mailbox tests** against the Compose GreenMail:
  - plain, HTML-only and multipart messages
  - attachments
  - auto-reply detection
  - quote stripping
  - folder moves
- **Template rendering**, including checks that the policy-inconsistency and processing-problem emails leak no details.
- **Scope store and human review:**
  - store round trip; a scope survives a simulated restart; the allowlist accepts the intake types
  - a complete claim suspends; resume (also after a simulated restart) doesn't re-invoke earlier agents; `READY` / `NEEDS_INFORMATION` outcomes; eviction deletes the row
  - `ClaimReviewService`: not-in-review, second decision and unknown run id are rejected without partial state
- **Review API:** `READY` → `In Process` + one thank-you email; `NEEDS_INFORMATION` → `Pending Information` + exactly the ticked items; 400/404/409 Problem Details.
- **Processor integration tests, one per rule:**
  - complete claim (→ `Pending Review`, one received email, one scope row); each missing item; `OTHER`; no photos; skipped attachments
  - each policy case; inception date before incident date
  - pending claim completed (→ `Pending Review` + received email) / completed after two replies / still incomplete; status reply for a claim `In Process` or later
  - reply during review supersedes (old scope row gone, new row, stays `Pending Review`, variant email) / makes it incomplete (→ `Pending Information`)
  - reviewer outcome applied: Ready → `In Process` + thank-you; Needs more information → `Pending Information` + ticked items
  - wrong sender; header vs subject matching
  - duplicate `Message-ID`; auto-reply / self-sent; HTML-only
  - agent failure (failed folder, processing-problem email, no claim)
  - no scope rows remain for runs that didn't suspend
- **Watcher tests:** live mail, backlog, one-at-a-time processing, a suspended run doesn't block the next message, reconnect, startup with GreenMail down.
- **Observability** (in-memory span exporter):
  - one trace per email, including across `@ParallelAgent`; error and suspended statuses; the review span links to the original trace
  - the metrics increment with the expected tags
  - log lines carry the root trace id
  - `MonitoredAgent` keeps no sessions outside dev
  - Langfuse span export is off by default in tests, and `LangfuseSessionScoringServiceTests` still passes
- **Playwright UI:** labels and filters for both new statuses; the review panel shows only for `Pending Review` and drives both decisions.
- **Roundcube end-to-end (Playwright):** runs on **every CI build** under both Ollama profiles, with the LLM mocked:
  - Complete claim with a photo → `Pending Review` and the received email reaches Marty's inbox → **Ready for processing** → `In Process`, with the thank-you email.
  - Missing location → `Pending Information` → the customer replies → `Pending Review`, with the received email → Ready → `In Process`, with the thank-you email.
  - The reviewer requests more information (category only) → the email lists only the category → the customer replies → `Pending Review` → Ready → `In Process`.
- **Rules for every test:**
  - delete the claims and images it creates (`ClaimsListPageTests` expects 6), and leave no agentic scope rows behind
  - never assert absolute claim numbers
  - stub API keys in every test profile (to avoid `SRCFG00011` under `-Pollama-openai`)

## Documentation

- A demo guide (`docs/email-claim-intake-demo.md`) with five scenarios, for dev mode and the cluster.
- `CLAUDE.md`: architecture, the `claim-intake` model in the models table, configuration keys, test layers, gotchas.
- `README.md`.
- `docs/application-flow.puml`, re-rendered.

## Out of scope

- Non-English email
- More than one incident per email
- Drift detection for agents
- Updating non-pending claims through email
- The reviewer editing the extracted fields

## Tasks

- [ ] Agentic module spike (runs first, throwaway)
- [ ] Design document and review gate
- [ ] Intake configuration and claims mailbox
- [ ] Qute reply templates and sender
- [ ] Claim extraction agents
- [ ] Triage agents
- [ ] Human review step (`@HumanInTheLoop` + database-backed scope store)
- [ ] Intake processor and business rules
- [ ] IMAP IDLE watcher
- [ ] Review API and UI
- [ ] Observability for the intake workflow
- [ ] Roundcube end-to-end test
- [ ] Demo guide and documentation
- [ ] **Verification:** an independent review, then a real-key `verify`, the live demo and a cluster check by the maintainer