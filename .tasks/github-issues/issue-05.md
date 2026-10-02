TITLE: Agentic email claim intake and triage
## Summary

A customer emails `claims@parasol.com` from Roundcube. An IMAP IDLE watcher picks the email up, and a
`quarkus-langchain4j-agentic` workflow:
- classifies it (new claim / follow-up / not a claim)
- extracts the claim details, summary and sentiment

The app then creates or updates the claim, stores photo attachments, replies with Qute-templated emails,
and files the message. Every customer email gets a reply. Once a claim is complete, a claims processor
does a final completeness look-over (`Pending Review`, not an approval) before it moves to `In Process`: the
workflow pauses at a `@HumanInTheLoop` step, suspended and persisted in PostgreSQL, and resumes on the processor's decision.
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

- The **spike** (throwaway code on a local branch, never pushed, latest stable versions) is done: feasible, with workarounds. Its findings are summarised below and in the design pull request.
- `docs/design/email-claim-intake.md` is **short by design**: goal, workflow, claim states and agent architecture. It has three PlantUML diagrams: the email workflow, the claim states, and the agent organisation. The detailed rules stay in this issue and the task plans.
- The design was reviewed and **merged** in https://github.com/edeandrea/non-deterministic-no-problem/pull/220 (approved as-is). Implementation can start once the prerequisites ({{ISSUE_1}}–{{ISSUE_4}}) land. The spike verified quarkus-langchain4j 1.14.1, which arrives with {{ISSUE_1}}.

## Architecture

- **Package:** new package `org.parasol.intake`, with sub-packages `agent`, `mailbox`, `reply` and `review`.
- **`ClaimsInboxWatcher`:** a dedicated thread that:
  - starts after the scope-store registrar (no agent calls in low-priority startup observers)
  - connects with backoff (never fails startup)
  - processes the INBOX backlog
  - waits with IMAP IDLE
  - handles one message at a time; an email is filed when its run finishes, including when it pauses for review
  - reconnects after a dropped connection
- **`ClaimsMailbox`:**
  - Parses messages into an `InboundEmail` record:
    - `Message-ID`, `In-Reply-To` / `References`
    - sender, subject, sent date
    - text body: HTML-only bodies converted with jsoup, quoted reply text stripped
    - image attachments
    - auto-reply headers
  - Moves messages to the processed or failed folder.
- **Agents:** side-effect free and **stateless**, using model `claim-intake`. Every AI agent opts out of chat memory and the policy Easy RAG (`@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`); the processor passes the claim history (correspondence, fields extracted so far, requested items including reviewer-ticked ones) in on every run. Each leaf agent belongs to exactly one root.
  - `ClaimsMailboxAgent` (`@SequenceAgent`) is the root, `process(@MemoryId messageId, …)` (memory id = the inbound `Message-ID`), and returns a sealed `IntakeOutcome`. It receives the claim the processor matched in code (or none).
  - `EmailClassifierAgent` → `NEW_CLAIM` / `CLAIM_FOLLOW_UP` / `NOT_A_CLAIM`
  - `EmailRouter` (`@ConditionalAgent`, no LLM): typed, cheap `@ActivationCondition`s on the **matched claim first**, then the email type:
    - matched pending claim, or unmatched new claim → `ClaimExtractionWorkflow` (`@ParallelAgent`, with a context-propagating `@ParallelExecutor`) runs three agents in parallel:
      - `ClaimSummaryAgent`
      - `ClaimSentimentAgent`
      - `IncidentDetailsAgent`: description, date, location, category, and the policy number if stated. It also picks up the incident time when stated (optional, never required; the design doesn't list it). Dates are ISO strings (scope values can't hold `LocalDate`/`Optional`), resolved against the sent date, and a JSON-extracting output guardrail parses the result.
    - matched claim in any other status → `ClaimFollowUpAgent`: AI-written status answer, using a read-only tool
    - unmatched "not a claim" or "follow-up" → a non-LLM agent producing the not-a-claim / no-matching-claim outcome
  - `ClaimReviewAgent` (no LLM): a static `@HumanInTheLoop` method returning `new SuspendedResponse<>("review:" + memoryId)` as `Object` (no `async`). A complete claim's run suspends here (the root throws `AgenticSystemSuspendedException`) until a claims processor decides; a non-LLM router then turns the decision into the outcome.
- **`DatabaseAgenticScopeStore`:** an `AgenticScopeStore` that saves scopes in PostgreSQL (key `agentId|memoryId`), so a waiting review survives restarts. Registered via `AgenticScopePersister.setStore` from a lowest-priority `StartupEvent` observer, reset on `ShutdownEvent`, and checked by a startup round-trip self-test. The scope is **evicted on every ending** (completed, policy-rejected, superseded, failed, resumed); only a run waiting for review keeps a row. Single replica (scopes are cached in memory until eviction).
- **Beta API:** beta module (`langchain4j-agentic` `-beta30`). Suspend/resume and persistence are documented
  upstream and mostly public; the only internal touch points are `SuspendedResponse`
  (`dev.langchain4j.agentic.internal`) and the `@Internal` `DefaultAgenticScope` exposed by the
  `AgenticScopeStore` SPI.
- **`ClaimReviewService`:** claims the review under optimistic locking, completes the pending response, re-runs the root with the same memory
  id and the original arguments read back from the scope, applies the outcome and evicts the scope. Exposed as `POST /api/db/claims/{id}/review-decisions`
  (`READY` / `NEEDS_INFORMATION` + ticked missing items; RFC 9457 Problem Details: 400, 404 for an unknown claim or missing scope, 409 when no review is pending or the lock is lost).
- **`ClaimEmailProcessor`** matches the claim and checks the sender (code, before the workflow), runs the root, checks the policy number, and does all persistence, mail, folder moves and scope eviction. LLM calls run outside
  transactions (including on resume), persistence uses `QuarkusTransaction.requiringNew()`, and it stays idempotent around the scope saves, which commit separately.
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
   - **Needs more information** → the processor ticks the missing items → `Pending Information`, and the customer is emailed exactly the ticked items. That run ends; the customer's next email starts a new one. The claim stays incomplete until the ticked items are answered, so an empty reply stays `Pending Information`.

   Intake uses `Pending Information`, `Pending Review` and `In Process`, and never leaves a claim in `New`.
2. **Where fields come from:**
   - name and email: the `From:` header
   - claim number: generated ({{ISSUE_2}})
   - summary and sentiment: AI-generated, at most 5000 characters each (output guardrail)
   - inception date: random, before the incident date

   An incident date in the future is treated as missing.
3. **Policy number** (checked in code after the agents finish, before any claim is created or changed; a complete claim's run has already paused by then):
   - stated, and belongs to the same customer → reuse it
   - stated, and belongs to a **different** customer → discard the run state, including a paused review, and reject with a deliberately vague email
     ("there's an inconsistency with your policy information; call 1-800-CAR-SAFE"). No details are
     given (anti-phishing / prompt injection), and nothing is created.
   - stated but unknown → create a new claim on that number
   - absent → generate a unique one
4. **Follow-ups:**
   - **Matching (code, not the LLM, before the workflow):** a `[CLM…]` / `CLM\d+` regex first, then `In-Reply-To` / `References`. **The matched claim wins:** a matched email is a follow-up on that claim, whatever the classifier says.
   - **Sender check:** accepted only from the claim's own email address. A wrong sender, or an unmatched email the classifier calls a follow-up, gets the one "no matching claim" reply; nothing is created or changed.
   - **Pending claim:**
     - The reply is appended to the body, and details are merged (never overwritten with null).
     - The extraction workflow re-runs over the whole correspondence (summary and sentiment included).
     - Once complete, the claim becomes `Pending Review` and the "received — final review" email is sent. Otherwise a "still missing" email is sent.
   - **Claim in `Pending Review`:** the reply supersedes the waiting review. The old suspended scope is evicted, the reply is merged, and the workflow re-runs. Still complete → stays `Pending Review` with a "we've added your latest information; your claim is still in final review" email. Now incomplete → `Pending Information` with the missing-information email. A reply and a reviewer's decision are serialised by optimistic locking on the claim: a losing decision gets a 409, a losing reply is handled under the claim's new status.
   - **Claim in any other status** (`New`, `In Process` or later): status answer only. The claim is **never updated**.
5. **Photos:**
   - Optional. `image/*` attachments are stored as `ORIGINAL` ({{ISSUE_3}}).
   - If none arrive, the reply mentions that photos can be sent.
   - Non-image or oversized attachments are skipped, and the reply mentions them.
   - New claims never get processed images.
6. **Idempotency:** each `Message-ID` is stored with a unique constraint, for claims and for follow-up correspondence. Reprocessing a message does nothing beyond sending a reply that was never sent.
7. **Loop protection:**
   - Skip messages marked `Auto-Submitted` or `Precedence: bulk|auto_reply|junk`, and messages sent from the claims address itself.
   - Our replies carry `Auto-Submitted: auto-replied`, a `[CLM…]` subject prefix, and threading headers.
8. **Not a claim:** templated "this inbox handles claims only" reply.
9. **Failures:** the message moves to the failed folder and is logged with its `Message-ID`. No partial claim and no scope row is left behind. The customer gets a best-effort, templated "processing problem" reply (we received your email but couldn't process it automatically; a team member will follow up, or call 1-800-CAR-SAFE), with no details.
10. **Every customer submission gets a reply** confirming what the customer did and what happens next:
    - first email complete → received — final review
    - first email incomplete → missing information
    - reply that completes a pending claim → received — final review
    - reply still incomplete → still missing
    - reply during `Pending Review` → the "still in final review" variant, or missing information
    - claim in any other status → status answer
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
- no matching claim: **one** template for a wrong sender and an unmatched follow-up; no details, asks the customer to write from the claim's address or include the claim number
- processing problem (no details)
- a skipped-attachments fragment

The phone number and sign-off move into one shared source, used by both the templates and `GenerateEmailService`.

## UI

`Pending Information` and `Pending Review` get label colours and status-filter options in `ClaimsList.tsx` / `ClaimDetail.tsx`.
For a `Pending Review` claim, `ClaimDetail.tsx` shows a review panel in place of the disabled Edit button: **Ready for processing**, or **Request more information** with one checkbox per missing item.

## Observability

- **One trace per email:** the processor opens a root span `claim-intake process` (no parent, kind `CONSUMER`, `gen_ai.operation.name=invoke_agent`) for each message, with the classification, claim number, resulting status and reply template as attributes. Errors are recorded; a suspended run is a normal outcome.
- **Agent spans:** a static `@AgentListenerSupplier` on the root (`inheritedBySubagents()=true`), not a CDI `AgentListener` (which only reaches AI leaves), adds `invoke_agent <name>` spans for every agent, composite and non-AI ones included, and ends the spans left open when a run suspends. Leaf AI-service spans come from quarkus-langchain4j. Langfuse types the spans from `gen_ai.operation.name` (AGENT, GENERATION, TOOL).
- **Parallel context propagation:** `@ParallelAgent` declares a `@ParallelExecutor` returning `Context.taskWrapping(…)`, so the parallel sub-agents and their log lines stay in the root trace.
- **Mail spans:** IMAP fetch/move and SMTP send get `CLIENT` spans (no addresses or bodies on spans).
- **Review link:** the review decision runs in a new trace whose span carries a span link to the original trace context, stored on the claim when the run suspends.
- **Conversation grouping:** all traces for a claim (each email, and the review decision) stay separate but share one `gen_ai.conversation.id` (a per-claim UUID stored on the claim in a nullable `intakeConversationId` column, set via OTel baggage), so Langfuse shows the claim as one session. Claims not created by the intake (the seeded claims) have no id; a follow-up on one mints a fresh id.
- **Metrics** (`IntakeMetrics`, no claim numbers, `Message-ID`s or addresses as tags):
  - `claim.intake.emails`, `claim.intake.processing`
  - `claim.intake.claim.status.transitions`, `claim.intake.replies`
  - `claim.intake.review.decisions`, `claim.intake.review.wait`, `claim.intake.reviews.pending`
  - `claim.intake.failures`, `claim.intake.watcher.connected`, `claim.intake.watcher.reconnects`
- **Logs:** intake log lines carry the trace id.
- **Langfuse:** intake LLM calls are scored by the Langfuse judge. Langfuse span export is off in tests, except in `LangfuseSessionScoringServiceTests`.
- **Dev UI:** `MonitoredAgent` (Topology and Executions pages) is **dev-only**; prod has no agent monitor, because suspended runs would stay in its memory forever. The dev-only mechanism is settled during implementation.
- **Grafana:** panels for the intake metrics are tracked in {{ISSUE_6}}.

## Spike (ran before the design)

Verified on quarkus-langchain4j 1.14.1 / langchain4j-agentic 1.20.2-beta30. **Verdict: feasible, with workarounds.**
- `@ModelName`, `@InjectMock` (sub-agent mocks need their own test profile), `@ToolBox` tools and CDI output guardrails (with reprompt) work on agents.
- Nesting Sequence → Conditional → Parallel works; every `@ActivationCondition` is evaluated. Enum and record outputs work; a parse failure aborts the workflow.
- Agents silently get the policy Easy RAG and one chat memory shared by all runs unless they opt out, even under a root `@MemoryId`. Every entry agent must be injected in `src/main`. Shared leaf agents leak listeners between roots.
- **Human review:**
  - a static `@HumanInTheLoop` method returning `SuspendedResponse` passes Quarkus validation; suspension reaches the root as `AgenticSystemSuspendedException`; no `async = true`
  - resume after a genuine restart works and doesn't re-invoke completed agents; the arguments must be passed again (read back from the scope), because they overwrite the stored state
  - the store is JVM-global and must be set before a root's first call; saves run in their own transaction; rows are never deleted without an explicit evict
  - scope values can't hold `LocalDate` or `Optional`; types outside agent signatures need `allowDeserializationType`
- **Observability:**
  - without a caller span, one run is spread over about ten traces
  - a CDI `AgentListener` only sees AI leaves; a root `@AgentListenerSupplier` sees every agent
  - `@ParallelExecutor` with `Context.taskWrapping(…)` keeps parallel sub-agents in the trace
  - `MonitoredAgent`'s retention cap doesn't cover suspended runs, hence dev-only

## Testing: every edge case

- **Unit:**
  - activation conditions (exactly one true per matched-claim status × email type) and outcome mapping
  - the `@Output` combiner
  - the missing-information calculation (`OTHER` vs null; reviewer-ticked items; blank reply)
  - the JSON guardrail, the length guardrail at 5000 and 5001 characters, and the future-date rule
- **Agent tests (WireMock-mocked LLM, stubs matching the last message only):** each route and each extraction case; the matched claim wins over the label; an unmatched follow-up → no matching claim; every request carries one user message (no memory, no RAG).
- **Mailbox tests** against the Compose GreenMail:
  - plain, HTML-only and multipart messages
  - attachments
  - auto-reply detection
  - quote stripping
  - folder moves
- **Template rendering**, including checks that the policy-inconsistency, processing-problem and no-matching-claim emails leak no details.
- **Scope store and human review:**
  - store round trip (`agentId|memoryId`); registrar order and shutdown reset; the startup self-test rejects `LocalDate`/`Optional`
  - a complete claim suspends; resume (also after a simulated restart) doesn't re-invoke earlier agents; `READY` / `NEEDS_INFORMATION` outcomes; eviction deletes the row
  - `ClaimReviewService`: not-in-review, second decision, stale version and missing scope are rejected without partial state
  - a reply racing a decision: exactly one wins (optimistic locking)
- **Review API:** `READY` → `In Process` + one thank-you email; `NEEDS_INFORMATION` → `Pending Information` + exactly the ticked items; 400/404/409 Problem Details.
- **Processor integration tests, one per rule:**
  - complete claim (→ `Pending Review`, one received email, one scope row); each missing item; `OTHER`; no photos; skipped attachments
  - each policy case (a rejection leaves no claim and no scope row, even after the run paused); inception date before incident date
  - pending claim completed (→ `Pending Review` + received email) / completed after two replies / still incomplete; status reply for a claim in any other status
  - matched claim wins over the label; an unmatched follow-up gets the no-matching-claim reply
  - after Needs more information, an empty reply stays `Pending Information`
  - reply during review supersedes (old scope row gone, new row, stays `Pending Review`, variant email) / makes it incomplete (→ `Pending Information`)
  - reviewer outcome applied: Ready → `In Process` + thank-you; Needs more information → `Pending Information` + ticked items
  - wrong sender; header vs subject matching
  - duplicate `Message-ID`; reprocessing after a partial failure sends exactly one reply; auto-reply / self-sent; HTML-only
  - agent failure (failed folder, processing-problem email, no claim)
  - no scope rows remain except for runs waiting for review
- **Watcher tests:** live mail, backlog, one-at-a-time processing, a suspended run doesn't block the next message, reconnect, startup with GreenMail down, start after the store registrar.
- **Observability** (in-memory span exporter):
  - one trace per email, including across `@ParallelAgent`; error and suspended statuses (no span left open); the review span links to the original trace
  - a claim's emails and review decision are separate traces with the same `gen_ai.conversation.id` (also with Langfuse export off); a different claim or a not-a-claim email gets a different id
  - the metrics increment with the expected tags
  - log lines carry the root trace id
  - no agent monitor exists outside dev
  - Langfuse span export is off by default in tests, and `LangfuseSessionScoringServiceTests` still passes
- **Restart:** a review suspended under one test profile resumes under another, against a database that survives the profile switch.
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
- `docs/design/email-claim-intake.md`: status line updated to accepted/implemented.
- `CLAUDE.md`: architecture, the `claim-intake` model in the models table, configuration keys, test layers, gotchas (agent opt-outs, single replica, store lifecycle).
- `README.md`.
- `docs/application-flow.puml`, re-rendered.

## Out of scope

- Non-English email
- More than one incident per email
- Drift detection for agents
- Updating non-pending claims through email
- The reviewer editing the extracted fields

## Tasks

- [x] Agentic module spike (runs first, throwaway)
- [x] Design document and review gate
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
- [ ] Verification
