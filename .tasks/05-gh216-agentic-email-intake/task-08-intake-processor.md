# Task 08: The Intake Workflow and Business Rules

**Type:** Code Modification

## Goal

One quarkus-flow workflow, `ClaimIntakeFlow`, carries an inbound email from start to finish: it supersedes a waiting
review, runs the agents, checks the policy number, persists the claim and photos, replies, files the email, and, for
a complete claim, waits for the claims processor's decision and applies it. It's the **only** place with side
effects. Every customer submission gets a reply, and every edge case has an integration test.

*Rewritten after the Flow verdict (task 01b = ADOPT, option b1, user decision).* **Task 07 is merged in**: the human
review is the workflow's last steps. The agentic root (task 06) stays **one step**, so the agent topology is
unchanged. Evidence: `spike-results-flow.md` → *Follow-up spike* (F1–F5); sketch `Spike2IntakeFlow` on
`spike/flow-workflow`.

## The workflow

```
(starter: loop/duplicate checks, claim match, sender check, conversation id, per-sender order; doesn't wait)
  → routeMatch ─ wrong sender ──────────→ replyNoMatchingClaim → file → END
  → resolveClaim (no match + sender has pending claims) ─ unsure → replyWhichClaim → file → END
  → runAgents (ClaimsMailboxAgent.process: one step; Flow generates the sub-workflows)
  → policyCheck ─ different customer ───→ replyPolicyInconsistent → file → END
  → supersede (claim waiting for review: lock + cancel the waiting run)
  → applyOutcome (persist: claim, photos, correspondence)
  → reply → file
  → complete? ─ no ─────────────────────→ END
       └ yes → waitReview (listen: REVIEW_DECIDED for this claim)
               → routeDecision ─ READY ─→ markInProcess + thank-you → END
                               └ NEEDS_INFORMATION → markPendingInformation + missing-information email → END
```

Every terminal `switch` branch needs `.then(FlowDirectiveEnum.END)` (task 01b, A1).

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b before this task. #1058 is fixed there, so a canceled run
now exports its `workflow.execute` span: the old "don't assert it" rule is gone (Key Points). Check `PLAN.md` →
Execution Steps → 2a first (a platform that pins Flow ≥ 1.2.0).

## What to Do

- **Starter** (`ClaimIntakeStarter`, called by the watcher, task 09). Plain code before any run starts; it **doesn't
  wait** for the run (user decision, 2026-10-07):
  1. **Loop protection:** an auto-reply (`Auto-Submitted` other than `no`, or `Precedence: bulk|auto_reply|junk`) or
     mail from the intake address → move to processed, no run, no reply.
  2. **Duplicate:** a `Message-ID` already recorded → move to processed, no run, no reply. **Decided (user, 2026-10-11,
     D4):** one `ClaimCorrespondence` table records **every** inbound email the starter accepts (unique `Message-ID`;
     the claim is nullable and set once the email creates or matches one; direction, sent date, stripped text). There's
     no `Claim.sourceMessageId`. See *Implementation Handoff* below.
  2b. **No `Message-ID`** (user decision, 2026-10-08; `InboundEmail.messageId` is empty, task 03): the failure path, with
     no run: move it to the failed folder, log at WARN, and send the processing-problem template to the `From:` address
     (best-effort, no threading headers). Real mail clients always set one; only hand-written scripts sending raw SMTP
     produce this, and GreenMail stores them as-is. Generating a UUID instead was rejected: steps re-read the email by
     its `Message-ID`, so it would have to be rewritten into the stored message (copy, append, delete). Rule 1 still
     runs first, so this can't start a mail loop.
  3. **Claim resolution (code, not the LLM):** subject/body regex (`[CLM…]` / `CLM\d+`), then
     `In-Reply-To`/`References` against stored `Message-ID`s.
  4. **Sender check:** a resolved claim only matches if its `emailAddress` equals the sender (case-insensitive).
  5. **Conversation id:** a matched claim that passes the sender check reuses its `intakeConversationId`. Otherwise (no
     match, wrong sender, or a seeded claim with none) → mint a UUID.
  6. Start the run **inside `ConversationContext.callIn(conversationId, …)`** (task 10b; **task 08 adds a minimal
     version**, D1), with a small input record
     (`IntakeRun`: `Message-ID`, matched claim id or none, the sender-check result, the conversation id).
     **That one call is the only observability-related code in the intake.**
  7. **Per-sender order** (user decision, 2026-10-07): if the sender (case-insensitive address) already has a run
     that's still working (not waiting, not ended), queue this email behind it instead of starting a run; start it
     when that run waits or ends. Per sender, not per claim, because `resolveClaim` may only pick the claim inside the
     run; a claim only matches its own address, so this covers per-claim order. A CDI `WorkflowExecutionListener`
     (`onWorkflowStatusChanged` → `WAITING`, `COMPLETED`, `CANCELLED`, `FAULTED`; F2) drives the queue.
     **Never cancel a run mid-step**; only a waiting run is canceled (the `supersede` step).
- **Pass ids, not payloads.** Flow persists step data after every step, so the workflow data never holds the raw email
  or attachment bytes. Steps re-read the email with `ClaimsMailbox.find(MailFolder.PROCESSING, messageId)` (task 03) before the `file` step
  moves it. (The agentic scope Flow checkpoints *does* hold the correspondence text the agents receive. That's PII in
  Flow's tables for as long as the run lives; Flow deletes the rows when the run ends or is canceled.)
- **Steps.** Each one is a `function`/`withFilter` calling a package-private bean. No step holds a transaction across
  an LLM call, and every persistence step runs in `QuarkusTransaction.requiringNew()`.
  - **`replyNoMatchingClaim`:** the one "no matching claim" template (task 04). Change nothing.
  - **`supersede`** (after `policyCheck`, just before `applyOutcome`; design review 2026-10-07): if the matched claim is
    `Pending Review`, clear `reviewRunId` in its own transaction under optimistic locking (`@Version` on `Claim`), then
    cancel the waiting run with `definition().activeInstance(oldRunId).map(WorkflowInstance::cancel)` (F3; in-JVM only,
    fine for a single replica). Placing it here means a faulted or policy-rejected reply never leaves a `Pending Review`
    claim with no waiting run. If the lock is lost to a reviewer's decision (or the claim is being decided,
    `decidingRunId` set), end this run without changes and put the email back in the sender's queue: it's handled again
    under the new status (e.g. `In Process` → status reply). **Mechanism decided (user, 2026-10-11, D3):** see
    *Implementation Handoff → D3*.
  - **`resolveClaim`** (user decision, 2026-10-07: "try to route it, ask if we can't"): only when no claim matched and
    the sender has claims in `Pending Information` or `Pending Review`. Calls `ClaimResolver` (task 06) with the email
    and those claims (number, short summary, requested items):
    - `EXISTING` with a number **from that list** → continue exactly as a matched claim (supersede, history, etc.)
    - `NEW_INCIDENT` → continue unmatched (the classifier then decides, as today)
    - `UNSURE`, or a number not in the list → `replyWhichClaim` (task 04's template, the sender's pending claim
      numbers), change nothing, END
    - *From task 06:* `ClaimResolver.resolveClaim(email, List<PendingClaim>)` returns a `ClaimResolution`
      (`org.parasol.intake.model`). Always pass it through `limitToOfferedClaims(offeredNumbers)`, which turns an
      `EXISTING` answer for a claim that wasn't offered into `UNSURE` (unit-tested there; the step's own test still
      covers the routing).
  - **`runAgents`:** `ClaimsMailboxAgent.process(…)` (task 06), with the claim history passed explicitly: the combined
    correspondence, the matched claim (or none), the fields extracted so far, and the **requested items**, including
    any reviewer-ticked items stored on the claim. Output: the `IntakeOutcome`.
    - *From task 06:* the signature is `process(correspondence, matchedClaim, extractedSoFar, requestedItems, sentDate,
      invocationParameters)`. Pass `MatchedClaim.none()` (never `null`) when nothing matched, and always
      `ClaimStatusTools.scopedTo(matchedClaim)` as the last argument: it scopes the status tool to that claim (see the
      task 06 Outcome for why the root takes it). `MatchedClaim.of(claimNumber, status)` takes the claim's stored
      status string.
    - *From task 06:* inject `ClaimsMailboxAgent` into the workflow bean and **delete `ClaimsMailboxAgentEntryPoint`**
      in the same change.
    - *From task 05:* build the combined correspondence in a package-private helper here, and cap it there at
      `intakeConfig.limits().maxBodyCharacters()` (the stored body isn't truncated). The cap was first built in task 05
      and moved here in its review; keep its rules and tests:
      - keep the **newest** text: replies are appended at the end, and older facts reach the agents through the fields
        extracted so far
      - cut forward to the next line break so no line is sent half cut; one very long line is cut mid-line rather than
        dropped
      - put an `[Earlier correspondence omitted]` line in front of what's kept; the marker and its line break count
        toward the limit
      - tests: text that fits is sent unchanged; long text keeps the newest whole lines within the limit; one very long
        line is cut mid-line; a limit with no room beyond the marker is rejected
    - Pass the fields so far as an `IncidentDetails` (all `null` for a new claim, never `null` itself) and the
      requested items as any `Collection<MissingItem>`.
  - **`policyCheck`** (gap 7), in code, before any claim is created or changed:
    - stated, and on another claim with the *same* customer: reuse it. **Same customer (user, 2026-10-11):** the email
      address matches (case-insensitive); the name is compared only when both the `From` display name and the claim's
      `clientName` are present (case- and whitespace-insensitive)
    - stated, with a *different* customer: send the policy-inconsistency template, create and change nothing, END
    - stated but not found: use it for a new claim
    - not stated: generate one (unique `AC-` + 9 digits, the seed format; **a checked random generator**, not a sequence:
      see *Implementation Handoff*), and mark the claim `policyNumberGenerated`
    - **On a `PendingClaimUpdate` (user, 2026-10-11, D5):** a stated number replaces the claim's number **only if we
      generated it**, through the same rule (another customer's → policy-inconsistency reply, nothing changed, END; then
      `supersede` never runs, so a waiting review stays intact). A number the customer gave is never changed.
  - **`applyOutcome`:**
    - **`NewClaim`:**
      - inception date random and strictly before the incident date (or before today if it's missing)
      - name and email from `From:`; subject and full body
      - the run's conversation id in the new `intakeConversationId` column (`intake_conversation_id`; nullable,
        intake-only, never changed once set)
      - the ISO `incidentDate`/`incidentTime` strings (task 05) converted to `LocalDate`/`LocalTime`
      - image attachments as `ORIGINAL` (size/count limits), in the same transaction as the claim. Pass
        `ImageAttachment.data()` straight to `ClaimImage.store` without changing it: the record doesn't copy its array,
        to save memory (task 03)
      - complete → `Pending Review`, with `reviewRunId` = this run's instance id; incomplete → `Pending Information`,
        with the requested items stored
    - **`PendingClaimUpdate`** (matched `Pending Information`, or `Pending Review` superseded above; **the matched claim
      wins**, whatever the classifier said):
      - append the stripped reply to `body` (separated, dated) and record the correspondence
      - merge newly supplied fields (never overwrite with null); store new images
      - **Completeness** (gap 8): complete only when no required detail is missing **and** every reviewer-ticked item
        was answered (`missingInformation`, task 05; built as `ClaimExtractionRules.findMissingItems`). A blank reply answers nothing.
      - complete → `Pending Review` (new `reviewRunId`), clear the requested items; incomplete →
        `Pending Information`, store the still-requested items
    - **`StatusReply`** (matched claim in any other status, including seeded `New`), **`NotAClaim`**,
      **`NoMatchingClaim`:** change nothing.
  - **`reply`:** the template for the outcome (the "Every submission gets a reply" list in `PLAN.md`):
    - received — final review, or the "still in final review" variant if the claim was already `Pending Review`
    - missing information, or still missing information
    - the AI-written status answer
    - not-a-claim; no matching claim

    Add the photos note and skipped-attachments fragment where relevant. A first email gets exactly one reply.
    **No thank-you here.**
  - **`file`:** move the email to the processed folder, **also when the run is about to wait for review**.
  - **`waitReview`:** `listen(toOne(consumed(REVIEW_DECIDED).dataAs(ReviewDecision.class, (data, ctx) -> …)))`,
    correlated on **both the run's own instance id and the claim id** carried in the event data (decided by the user,
    2026-10-11, D6: a new claim's id isn't in the run's input). The event data is `ReviewDecided(claimId, runId, decision)`.
    `ReviewDecision` is a record: a `ReviewOutcome` enum
    (`READY` / `NEEDS_INFORMATION`) plus the `Set<MissingItem>` the reviewer ticked. Task 10 publishes it.
  - **`routeDecision`** (a `switch`), then:
    - **`markInProcess`:** `In Process`, clear `decidingRunId` (task 10 moved `reviewRunId` there when it claimed the
      review), and the thank-you template. **This is the only place the
      thank-you email is sent.**
    - **`markPendingInformation`:** `Pending Information`, store the ticked items as the requested items, clear
      `decidingRunId`, and the missing-information template listing exactly them. **The run ends** (no second wait);
      the customer's next email starts a new run.
- **Failures:** one **failure listener** (a CDI `WorkflowExecutionListener`, `onWorkflowFailed`) handles every faulted
  run, before or after the review wait: move the email to the failed folder (if it's still in `processing`), log at
  ERROR with the `Message-ID`, and send the processing-problem template best-effort (never for auto-replies or self-sent
  mail). No partial claim is left: each persistence step commits only its own transaction.
  - **A fault in the decision steps** (e.g. the thank-you can't be sent) puts the claim back so the reviewer can retry.
    **Decided (user, 2026-10-11, D2): `FlowDSL.tryCatch(...)` inside the run.** If the claim's status hasn't changed
    yet, the catch moves `decidingRunId` back to `reviewRunId`, keeps the claim `Pending Review`, and goes back to
    `waitReview`, so **the same run waits again**. If the status has already changed, it only logs. The failure listener
    alone can't do this: the run would be gone and task 10's retry would get its 404.
- **No crash-recovery rules.** Durable state across restarts isn't a requirement (user decision): a restart wipes the
  database, the claims and GreenMail. So there's no "scope row but no claim" reprocessing, and no eviction anywhere.
- **Status constants:** `Pending Information`, `Pending Review`, `In Process` (intake never sets `New`; seed statuses unchanged).

## Tests

Integration tests against Compose GreenMail, with a WireMock LLM (stubs match on the last message only). Each test
deletes the claims and images it creates. Tests publish decisions with `publishUntilWoken` (re-publish until the run
leaves `WAITING`; F2b). One test per rule:
- a complete new claim ends `Pending Review`, gets exactly one email (received — final review), and its run is
  `WAITING` (`PersistenceInstanceReader`)
- each missing item, and several at once; category `OTHER` is complete
- no photos → the note; non-image and oversized attachments are skipped and mentioned
- each policy case: same customer, different customer (vague template, no claim, no details leaked), unknown, absent
- inception date before incident date; ISO strings persisted as `LocalDate`/`LocalTime`
- a pending claim completed by a reply → `Pending Review` + the received email; completed only after two replies;
  still incomplete after a reply
- **matched claim wins:** a reply on a pending claim labeled `NOT_A_CLAIM` is still merged
- an unmatched email classified as a follow-up → "no matching claim", nothing created
- after Needs more information, an **empty reply** stays `Pending Information` (still-missing email); a reply answering
  the ticked items → `Pending Review`
- **supersede:** a reply during `Pending Review` cancels the waiting run (0 Flow instance and task rows left for it).
  The claim stays `Pending Review` with the new `reviewRunId`, the during-review variant is sent, and a later decision
  reaches **only** the new run. A superseding reply that makes the claim incomplete → `Pending Information`.
- **race (the reply's side; user decision 2026-10-09):** a reply whose `supersede` step finds the `Pending Review` claim
  already being decided (`decidingRunId` set, or the `@Version` lock lost) ends without changes and goes back in the
  queue, and is handled again under the claim's new status (e.g. `In Process` → status reply). The test sets up the
  "being decided" state directly, since the review endpoint is task 10's. **The decision's side, and the two run
  concurrently (latched), are task 10's race test**: a losing decision needs the endpoint to return its 409.
- decision `READY` → `In Process` + exactly one thank-you; `NEEDS_INFORMATION` → `Pending Information` + an email listing
  exactly the ticked items; `reviewRunId` and `decidingRunId` cleared in both; **zero LLM calls** after the decision (WireMock request
  counts unchanged since the wait started)
- a follow-up on an `In Process`/later/seeded `New` claim → status reply, claim unchanged
- a follow-up from a different address → "no matching claim"; matched by header vs by subject number
- a duplicate `Message-ID`, an auto-reply and a self-sent message: no run, no reply
- an email with no `Message-ID` (sent as raw SMTP, since Jakarta Mail adds one): failed folder, processing-problem
  email without threading headers, no run, no claim
- an HTML-only email
- an agent exception → failed folder, processing-problem email, no claim; a fault after `WAITING` is handled as decided
- the starter doesn't wait: it returns before the run's first step finishes
- **per-sender order:** two emails from the same sender, back to back: the second starts only after the first's run
  waits or ends, and both are merged
- **resolveClaim:** a fresh email (no number, no reply headers) from a sender with one pending claim, about it →
  merged into that claim; with two pending claims → merged into the right one; about a new incident → a new claim;
  unclear → the which-claim email listing exactly the sender's pending claims, nothing changed; an LLM answer naming a
  claim not in the list → treated as unsure; a sender with no pending claims → no `ClaimResolver` call
- **supersede after a failure:** a reply on a `Pending Review` claim whose agents fail (or whose policy check rejects
  it) leaves the claim `Pending Review` with its original waiting run, which a decision still reaches
- **no Flow rows remain** for completed and canceled runs (E19; also catches the unreproduced F3 "row still present")
- **conversation id:** a new claim persists a UUID `intakeConversationId`. A follow-up, a superseding reply and a status
  reply reuse it, and it never changes. Not-a-claim, no-matching-claim and policy-rejected emails persist none. A
  wrong-sender email doesn't get the claim's id. (Span-level assertions are task 11's.)

## Files/Areas

- `src/main/java/org/parasol/intake/`: `ClaimIntakeFlow`, `ClaimIntakeStarter` (with the per-sender queue), the run-status and failure listeners, the step beans,
  and `ReviewDecision`/`ReviewOutcome` (in `review/`)
- `src/main/java/org/parasol/claim/model/Claim.java` (requested items, `reviewRunId`, `@Version`, `decidingRunId`,
  `intakeConversationId`, `policyNumberGenerated`), a `ClaimCorrespondence` entity (every inbound `Message-ID`; no
  `Claim.sourceMessageId`, D4)
- `src/test/java/org/parasol/intake/` (integration tests)

## Key Points

- **Side effects only in workflow steps** (and the starter's failure path); agents only decide.
- **No observability code in any step.** The conversation id is entered once, by the starter. Spans come from Flow and
  the adapters (task 11).
- Never hold a transaction open during an agent call.
- Every customer submission gets a reply. The only exceptions are auto-replies, self-sent mail and duplicate `Message-ID`s.
- Never assert absolute claim numbers; `ClaimsListPageTests` expects 6 claims.
- **Sending replies** (task 04): every reply goes through `IntakeReplySender`, with the templates from
  `IntakeTemplates` (the status answer through `sendTextReply`).
  - Both send methods **block** until the mail server accepts the reply (up to `quarkus.mailer.timeout`), so `reply`
    can run before `file` without waiting on a `Uni`.
  - Pass the inbound `messageId` as `inReplyTo` and its `referencedMessageIds` as `references`. Don't set a
    `Message-ID`: the mailer generates one.
  - Pass the claim number only when the reply is about a claim the sender owns. **Never** for no-matching-claim.
  - **Greeting** (user decision, 2026-10-08): `CustomerName.forReply(email, claim)`, with the resolved claim, or empty
    when there isn't one or it mustn't be disclosed (no-matching-claim, which-claim).
  - The photos note and skipped-attachments list are template parameters (`hasPhotos`, the email's
    `skippedAttachments`), not text.
  - Statuses: use `IntakeClaimStatus` labels for `Pending Information`, `Pending Review` and `In Process`.
- A canceled run's `workflow.execute` span is exported since Flow 1.2.0 (quarkus-flow#1058, fixed by #1059): assert it
  where a test cancels a run, rather than working around its absence.
- Editing the `Flow` bean in dev mode can break hot reload (`IncompatibleClassChangeError`); restart dev mode instead.

## Done When

- [x] `ClaimIntakeFlow` implements the steps above, with the agentic root as one step and every terminal branch ending.
- [x] The starter does the checks, enters the conversation id once, keeps each sender's emails in order, and doesn't wait for runs.
- [x] `resolveClaim` routes fresh emails to the sender's pending claim, a new claim, or the which-claim reply, and never to a claim outside the sender's list.
- [x] The policy check runs after the agents; a rejection creates nothing.
- [x] Completeness accounts for reviewer-ticked items; the empty-reply test passes.
- [x] A reply during review cancels the waiting run; a reply that finds the claim being decided goes back in the queue (the reply's side of the race; the decision's side is task 10's); no Flow rows remain for ended runs.
- [x] The thank-you email is sent only from `markInProcess`; a decision makes no LLM call.
- [x] The failure listener handles faults before and after `WAITING`, with tests (run 2026-10-19, see Outcome → Verification).
- [x] Every test listed above passes under `-Pollama` (and `-Pollama-openai`), and both `test-compile` runs succeed (2026-10-19).
- [x] The decision-step `tryCatch` puts the claim back and the same run waits again (D2), with a test.
- [x] Every inbound `Message-ID` is recorded once (D4); a redelivered not-a-claim email gets no second reply.
- [x] The policy rules for updates (D5) and the same-customer rule have tests.
- [x] Docs updated (see *Implementation Handoff → Docs*), the task Outcome written, `PLAN.md` updated.

## Implementation Handoff (planned 2026-10-11; implement in a new session)

Everything below was planned and **decided with the user on 2026-10-11**. Where it differs from the text above, this
section wins. Read first: `CLAUDE.md`, `CODE_STANDARDS.md`, `PLAN.md` (Execution Steps, Key Decisions, Caveats),
this file, `spike-results-flow.md` → *Follow-up spike* (F1–F5) and *Follow-up spike 5* (Q2), and the task 05/06
Outcomes (agent gotchas).

### Starting point
- Branch `gh216/08-intake-workflow` off `gh216-email-intake` (at `fa8c5d9` or later). PR **into the feature branch**,
  opened as a **draft**, body opens "Implements task 08 of #216" linking this file pinned to the PR's commit, ends
  `Part of #216`. One commit per part (below) so it can be reviewed commit by commit; squash-merged at the end.
- Step 2a was checked on 2026-10-11: platform 3.40.1 still pins Flow 1.1.3, Flow 1.2.0 is the latest Flow. Keep the
  override. Re-check at the start.
- Reference code (local, unpushed branches): `spike/flow-workflow` → `src/test/java/org/parasol/spike/flow/Spike2IntakeFlow.java`
  (DSL shape, supersede via `activeInstance`, `listen` correlation predicate reading the instance, task-span accessors),
  `Spike2HandoffListener.java`, `Spike2IntakeFlowTests.java` (`publishDecision` through
  `workflowApplication.eventPublishers()`, `publishUntilWoken`, Flow row counts by native query); `spike/flow-followup5`
  → `Spike5IntakeStarter.java` (per-sender queue driven by `onWorkflowStatusChanged`, run registered as working
  **before** `start()`). Read them with `git show <branch>:<path>`. Don't copy spike code verbatim: it ignores the
  project's style rules (no early returns, `Optional` chains, constructor injection, `Log`).

### What already exists (don't rebuild)
- `ClaimsMailbox` (`unprocessed`, `list`, `find(folder, messageId)`, `move(messageId, from, to)`), `InboundEmail`
  (`messageId` is `Optional`; `referencedMessageIds` = In-Reply-To then References; `strippedBody`; `images`;
  `skippedAttachments`; `isAutoReply`), `MailFolder` (INBOX, PROCESSING, PROCESSED, FAILED).
- `IntakeReplySender.sendTemplateReply(template, recipient, subject, claimNumber, inReplyTo, references)` and
  `sendTextReply(...)`, both blocking; `IntakeTemplates` (`receivedFinalReview`, `claimInProcess`, `missingInformation`,
  `stillMissingInformation`, `processingProblem`, `policyInconsistency`, `notAClaim`, `noMatchingClaim`, `whichClaim`);
  `CustomerName.forReply(email, Optional<Claim>)`.
- `IntakeConfig` (`address`, `folders`, `limits.maxBodyCharacters/maxImageSize/maxImages`), `IntakeClaimStatus`
  (labels), `MissingItem`.
- Agents: `ClaimsMailboxAgent.process(correspondence, matchedClaim, extractedSoFar, requestedItems, sentDate,
  invocationParameters)` → sealed `IntakeOutcome` (`NewClaim`, `PendingClaimUpdate`, `StatusReply`, `NotAClaim`,
  `NoMatchingClaim`); `ClaimResolver.resolveClaim(email, List<PendingClaim>)` → `ClaimResolution` (always
  `.limitToOfferedClaims(numbers)`); `ClaimStatusTools.scopedTo(matchedClaim)`; `MatchedClaim.of/none`;
  `ClaimExtractionRules.findMissingItems(extraction, requestedByReviewer, replyIsBlank)` (returns an `EnumSet` in
  template order); `IsoValues` (package-private in `intake.agent.extraction`, parses the ISO date/time strings: make a
  public accessor or move it if the workflow needs it, rather than duplicating it).
- `ClaimImage.store(claim, ORIGINAL, fileName, contentType, data)`; `Claim.findByClaimNumber`.
- Not built yet: the correspondence cap (it only exists as the spec above; there's no code in history to restore),
  `ConversationContext`, the watcher (task 09), the review endpoint (task 10).

### The decisions (user, 2026-10-11)
- **D1 — `ConversationContext`:** add a minimal `ai.scoring.conversation.ConversationContext` here (the package exists;
  it holds `ConversationalBaggageHandler`). Baggage only, key `gen_ai.conversation.id` (the same key the chat handler
  uses: `GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID.getKey()`, a private constant in the handler today, so take it
  from the same attribute rather than a string literal): `Optional<String> currentId()`, `Scope enter(String)`,
  `<T> T callIn(String, Callable<T>)`, `void runIn(String, Runnable)`. Depends only on the OTel API and CDI. Nothing
  else from task 10b (no span processor, no leak guard, no events, the chat handler isn't moved). Unit-test it. Task 10b
  completes it. Known, accepted until 10b: quarkus#54354 can leave the id on Flow pool threads (span labels only).
- **D2 — decision-step faults:** wrap `markInProcess`/`markPendingInformation` in `FlowDSL.tryCatch(...)`
  (`FuncTryTaskBuilder`: `tryCatch(tasks)`, then `catchError`/`catchType`/`catchWhen(..., catchTasks)`). The catch runs
  a `restoreReview` step: in `requiringNew()`, if the claim is still `Pending Review` with `decidingRunId` = this run,
  move it back to `reviewRunId` and log WARN; otherwise (status already changed) log ERROR. Then go back to
  `waitReview` (`.then("waitReview")`), so the same run waits again and the reviewer can retry. Order inside the decision
  steps: **commit the status change first, then send the email**, so a failed send after the commit only logs (no
  double thank-you on retry). **Verify early** that Flow 1.2.0 supports `then(<earlier task name>)` from inside a
  catch and that `listen` registers a fresh consumer when re-entered; if it doesn't, stop and report to the user.
- **D3 — a reply that finds its claim being decided** (`decidingRunId` set, or the `@Version` update fails with
  `OptimisticLockException`): the `supersede` step changes nothing, leaves the email in `processing` (don't file it,
  don't reply), and hands the Message-ID back to the starter, which puts it at the **front** of the sender's queue.
  The run then ENDs. To make it wait for the decision:
  - **The per-sender lock also covers a woken review run.** The starter keeps `instanceId → sender` for every intake
    run it started, including waiting ones. When a waiting run of a sender wakes and runs again, it becomes that
    sender's working run again, so the requeued email starts only when the decision steps end.
  - **How to see the wake-up:** reading serverlessworkflow-impl-core 7.35.3, `ListenExecutor` sets `WAITING` and
    nothing sets `RUNNING` when the event arrives; the next task's `WorkflowMutableInstance.suspendedCheck` publishes
    the `WAITING → RUNNING` status change before `routeDecision` runs. So `onWorkflowStatusChanged(RUNNING)` for an
    instance whose previous status was `WAITING` should be the signal. **Verify with a test first.** Fallback:
    `onTaskStarted` for the task named `routeDecision`.
  - **The gap:** between task 10 committing `decidingRunId` and the run waking, no run holds the sender. A requeued
    email that starts while no run holds its sender and finds the claim still being decided is retried with a backoff
    (e.g. 250 ms, doubling, about 6 tries); after the last try it takes the failure path (failed folder,
    processing-problem reply). Put the backoff on a scheduled/managed executor, never `Thread.sleep` on a Flow thread.
  - Status listener releases: `WAITING`, `COMPLETED`, `CANCELLED`, `FAULTED` (as in the spike).
- **D4 — one table for every inbound email:** entity `ClaimCorrespondence` (`org.parasol.claim.model`, table
  `claim_correspondence`): `messageId` (unique, not null), `claim` (nullable `@ManyToOne`, FK `on delete cascade`,
  indexed), `direction` (enum, `INBOUND` only for now: outbound Message-IDs aren't known, `IntakeReplySender` lets the
  mailer generate them), `sentDate`, `sender`, `strippedText` (`Length.LONG32`). The starter inserts the row when it
  accepts an email (insert-and-catch-unique-violation is the duplicate check, so two concurrent copies can't both
  pass), in its own `requiringNew()` transaction. `applyOutcome`/the matched path set the claim. Header matching
  (`In-Reply-To`/`References`) looks up rows with a claim. **No `Claim.sourceMessageId`.** Tests delete the rows they
  create (rows with a claim go with `Claim.deleteById`; rows without one need deleting by Message-ID).
- **D5 — policy number on updates:** new boolean `Claim.policyNumberGenerated` (`@ColumnDefault("false")`; seeds are
  customer-given). A `PendingClaimUpdate` whose extraction states a policy number different from the claim's: if the
  claim's number was generated, run the new-claim rule (same customer or unknown → replace and clear the flag; another
  customer's → policy-inconsistency reply, nothing changed, END before `supersede`). If it wasn't generated, ignore it
  (DEBUG log).
- **D6 — review event:** `org.parasol.intake.review`: `ReviewOutcome { READY, NEEDS_INFORMATION }`,
  `ReviewDecision(ReviewOutcome outcome, Set<MissingItem> missingItems)`, event data
  `ReviewDecided(long claimId, String runId, ReviewDecision decision)`, event type constant
  `REVIEW_DECIDED = "org.parasol.intake.review.decided"`, and a `ReviewDecisionPublisher` bean that builds the
  CloudEvent (JSON data, Quarkus `ObjectMapper`) and publishes it through `WorkflowApplication.eventPublishers()`, as the
  spike's `publishDecision` does. The `listen` predicate (`dataAs(ReviewDecided.class, (data, ctx) -> …)`) requires
  `data.runId()` equal to `ctx.instanceData().id()` **and** `data.claimId()` equal to the run's claim id (from the
  workflow context, or from the claim whose `decidingRunId` is this run). Task 10 calls the publisher.
- **Same customer:** the email address matches (case-insensitive); the name is compared only when both are present
  (case- and whitespace-insensitive).

### Two refinements to the starter (from planning; apply them)
- **Match the claim when the run starts, not at submit.** At `submit`: loop protection, record/duplicate check,
  missing Message-ID, then the queue. When the email actually starts (immediately, or at dequeue): re-read it with
  `find(PROCESSING, id)`, match the claim (regex, then headers via `ClaimCorrespondence`), sender check, conversation id,
  then `callIn(conversationId, () -> flow.instance(new IntakeRun(...)).start())`. A queued email then sees the claim its
  predecessor created or changed.
- **Never start a dequeued run on Flow's listener thread.** `onWorkflowStatusChanged` runs inside Flow's lifecycle
  stages (spike 5: on varying pool threads); dispatch the start to a `ManagedExecutor` (IMAP reads and DB lookups
  happen there). Keep the queue's own state under one lock; register the new run as working **before** `start()`.
- Starter API for task 09: `void submit(InboundEmail email)`, called with an email the watcher has **already moved to
  `processing`**; returns at once. The starter, not the watcher, records the Message-ID (update task 09's text).

### Data model details (`org.parasol.claim.model.Claim`)
- `@Version public long version` with `@ColumnDefault("0")` (`import.sql` omits it; check the generated DDL is
  `not null default 0`). Not in `ClaimDetails` (task 10 adds it); `ClaimResourceTests.jsonFieldNames` must stay green.
- `requestedItems`: `@ElementCollection(fetch = EAGER)` `Set<MissingItem>`, `@Enumerated(STRING)`,
  `@CollectionTable(name = "claim_requested_items")` with `@OnDelete(CASCADE)`. Note `MissingItem` has a `@JsonValue`
  label: irrelevant to JPA, but don't let it leak into the column (STRING = constant name).
- `reviewRunId`, `decidingRunId` (Flow instance ids, nullable), `intakeConversationId` (nullable, `updatable` only
  conceptually-once: set on create, never changed), `policyNumberGenerated`.
- Seeded claims keep working: the new columns are null/false/0 for them. `ClaimMapper`'s `unmappedTargetPolicy` only
  covers DTO fields, so new entity fields are fine.
- **Policy number generator** (package-private, e.g. `PolicyNumbers` in the workflow package): `AC-` + 9 random digits,
  redrawn while `Claim.count("policyNumber", n) > 0`. Not a sequence: CLAUDE.md's gotcha (a lone `@SequenceGenerator`
  takes over the Panache id). No unique constraint on `policyNumber` (several claims share one).
- **Inception date:** random, strictly before the incident date (today if unknown), at most ~5 years before.

### Packages and classes
- `org.parasol.intake`: `ClaimIntakeStarter` (+ package-private queue state), the status listener (may be the starter
  itself, as in the spike), `IntakeFailureListener` (`onWorkflowFailed`), helpers `ClaimReference` (regex
  `\[?CLM\d{8}\]?` in subject then body; then headers) and `MailLoopGuard`.
- `org.parasol.intake.workflow` (new): `ClaimIntakeFlow extends Flow` (name constant, e.g. `"claim-intake"`),
  `IntakeRun` (input: `messageId`, `conversationId`, `sender`, matched claim id or `null`, `senderMatches`),
  `IntakeState` (one JSON-safe record threaded through every step: no `Optional`, no bytes, no email text, `with…`
  copy methods; carries the outcome (`IntakeOutcome` is already Jackson-polymorphic), claim id, missing items,
  whether review is needed, the which-claim numbers, etc.), and the package-private step beans (e.g. `ClaimMatching`,
  `AgentStep`, `PolicyCheck`, `Supersede`, `OutcomeApplier`, `ReplySteps`, `ReviewDecisionSteps`, `FileEmailStep`),
  `CorrespondenceText` (combined correspondence + cap) and the merge helper.
- `org.parasol.intake.review`: the D6 types and publisher.
- `org.parasol.claim.model`: `Claim` changes, `ClaimCorrespondence`, its direction enum.
- Delete `org.parasol.intake.agent.ClaimsMailboxAgentEntryPoint` in the commit that injects `ClaimsMailboxAgent` into a
  step bean (the injection in `src/main` keeps the build-time agentic validation passing).

### The workflow as Flow DSL (verified signatures in quarkus-flow 1.2.0's `FlowDSL`)
`function(name, fn, In.class, Out.class)`, `withFilter(name, (in, workflowCtx, taskCtx) -> …, In.class, Out.class)`,
`withContext(...)`, `switchWhenOrElse(name, predicate, thenTask, elseTask|FlowDirectiveEnum, Class)`, multi-way
`switchCase(name, cfg -> …)`, `listen(name, toOne(consumed(type).dataAs(Class, (data, ctx) -> …)))`,
`tryCatch(name, b -> b.tryCatch(tasks…).catchError(...))`; every terminal branch `.then(FlowDirectiveEnum.END)`
(`io.serverlessworkflow.api.types`). Each step takes and returns `IntakeState` (the `listen` output is the event data,
so the decision steps take `ReviewDecided`). Order:
`routeMatch` (switchCase: wrong sender → `replyNoMatchingClaim`; unmatched with pending claims → `resolveClaim`; else
`runAgents`) → `resolveClaim` (unsure → `replyWhichClaim` → `file` → END) → `runAgents` → `policyCheck` (reject →
`replyPolicyInconsistent` → `file` → END) → `supersede` (being decided → requeue, END) → `applyOutcome` → `reply` →
`file` → `needsReview?` (no → END) → `waitReview` → `routeDecision` → tryCatch(`markInProcess` | `markPendingInformation`)
→ END. The `file` step is shared; a reply/terminal step leads into it, and its END/continue decision is the next switch.

### Tests
- Reuse `IntakeTestProfile` (one boot for every intake class; it absorbed `IntakeAgentsTestProfile`) and `ChatStubs` (stubs match the **last** message;
  each agent's user message is distinct, so stub by a phrase from its prompt). Inject `GreenMailMailbox`,
  `ClaimsMailbox`, `WorkflowApplication`, `ClaimIntakeFlow`, `ClaimIntakeStarter`, `EntityManager`, WireMock
  (`@ConnectWireMock`).
- Write a test helper (e.g. `IntakeScenario` in `src/test/java/org/parasol/intake/workflow`): send a real MIME message
  over SMTP to the claims address with Jakarta Mail (as `ClaimsMailboxTests` does; needed for custom headers, raw SMTP
  for the no-Message-ID case), move it INBOX → PROCESSING, `submit`, await the handoff (WAITING/ended) via a test-side
  status listener or polling `PersistenceInstanceReader`/`activeInstance`, `publishUntilWoken`, Flow row counts
  (`workflow_instance_entity`, `task_info_entity`, `cloud_event_entity`), and cleanup (claims via `Claim.deleteById`
  in `QuarkusTransaction.requiringNew()`, orphan `ClaimCorrespondence` rows, mail via GreenMail purge in `@BeforeEach`).
- Every bullet in **Tests** above gets a test, plus, for the 2026-10-11 decisions:
  - D2: the thank-you send fails (e.g. point the mailer at a dead port for one test, or stub `IntakeReplySender` with
    `@InjectSpy`/`@InjectMock` to throw once) → claim back in `Pending Review` with `reviewRunId` restored, the run is
    `WAITING` again, a second decision completes it with exactly one thank-you
  - D3: with a claim set to "being decided" and its run woken, a reply waits until the decision steps end, then gets the
    status reply; the backoff path ends in the failed folder when the claim stays "being decided"
  - D4: a not-a-claim email delivered twice gets one reply; header matching works through `ClaimCorrespondence`
  - D5: a generated policy number is replaced by a reply's own (same customer / unknown); another customer's number on
    an update → policy-inconsistency reply, claim unchanged, a waiting review untouched; a customer-given number is
    never changed
  - D6: a decision with the right claim id but another run id doesn't wake the run
  - same-customer rule: missing display name still matches on the address
  - JSON round-trips of `IntakeRun`, `IntakeState`, `ReviewDecided` through the Quarkus `ObjectMapper`
  - unit tests for `CorrespondenceText` (the four cap tests above), `ClaimReference`, `MailLoopGuard`, the policy rule,
    the merge, the inception date, the policy number format, `ConversationContext`
- Assert the canceled run's `workflow.execute` span is exported (Flow 1.2.0 fixed #1058) where a test supersedes.
- Never assert absolute claim numbers; leave exactly the six seeded claims (`ClaimsListPageTests`).
- Validation: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`; then the intake classes under
  default, `-Pollama` and `-Pollama-openai` (`./mvnw test -Dtest='org.parasol.intake.**'`, plus `ClaimResourceTests`,
  `ClaimTests`, `ClaimImage*Tests`, `NotificationServiceTests` for the `Claim` changes); finally `./mvnw -B clean verify`
  if time allows (10 Playwright tests are known to fail locally and pass in CI: same names as on `1b232cc`).

### Commit series
1. `Claim` columns, `ClaimCorrespondence`, policy number + inception date helpers (+ tests).
2. `ConversationContext` minimal (+ tests).
3. Review payload types and publisher.
4. Pure helpers: correspondence cap, claim reference, loop protection, policy rule, merge (+ unit tests).
5. `ClaimIntakeFlow` and step beans; delete `ClaimsMailboxAgentEntryPoint`.
6. Starter, per-sender queue, status and failure listeners.
7. Integration tests.
8. Docs.

### Docs (update in the same PR)
- `CLAUDE.md`: package table (`org.parasol.intake` no longer "being built"; add `intake.workflow`, `intake.review`; the
  `claim.model` row gets `ClaimCorrespondence`), the `Claim` entity section (new columns, `@Version` default,
  requested items table, policy generator, `ClaimCorrespondence`), Testing (intake workflow tests and helper), Gotchas
  (anything found: e.g. the `listen` wake status change, `tryCatch` loop-back, the D3 backoff).
- This file's **Outcome** section; `PLAN.md` (Task Plan line with the PR, an Overview outcome paragraph, these
  decisions in Key Decisions).
- Follow-on task files: **09** (call `ClaimIntakeStarter.submit` after moving to `processing`; the starter records the
  Message-ID), **10** (publish through `ReviewDecisionPublisher` with `ReviewDecided(claimId, runId, decision)`; `version`
  already exists on `Claim`; a step-2 compensation restoring `reviewRunId` needs no extra hook under D3), **10b**
  (complete the minimal `ConversationContext`), **11** (where `callIn` is), **13** (gotchas, demo).
- `docs/design/email-claim-intake.md`: only if a decision contradicts it (check the duplicate/correlation wording).

## Resume here (updated 2026-10-19, for a fresh session)

**State:** all task 08 work is on branch `gh216/08-intake-workflow` (off `gh216-email-intake` at `fa8c5d9`), **uncommitted**
(`git status` shows it all). Nothing is pushed and no PR exists for this branch. Main and test code compile under the
default profile and `-Pollama`. The Outcome below is current.

**2026-10-19:** the one failing test (`everyEndedOrCanceledRunLeavesNoFlowRows`) exposed a quarkus-flow 1.2.0 leak when an
agent throws; see Outcome → *A quarkus-flow 1.2.0 leak*. The test is scoped to its run, a deprecated workaround is in
`org.parasol.intake.agent`, and the upstream fix is quarkiverse/quarkus-flow#1095 (issue #1096; user's fork, branch
`fix-agentic-run-on-agent-failure`; the user marked the PR ready for review). Both full runs now pass (Verification).

**Next, in order:**
1. `./mvnw -B clean verify` if time allows (10 Playwright tests are known to fail locally and pass in CI).
2. **Show the user the results and wait for their review before any commit.** Then the commit series (Implementation
   Handoff → Commit series, adapted to what was built), each with the trailer
   `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`; a draft PR into `gh216-email-intake` whose body
   opens "Implements task 08 of #216" (linking this file pinned to the PR's commit) and ends `Part of #216`.

**How the user works (keep doing this):**
- **No commits until the user has reviewed.** Build, report, stop.
- When the user says not to run tests, compile-check only (`./mvnw -q -B -Pollama test-compile`).
- **Review comments arrive one at a time: record them only**, and act when told ("go with those comments").
- **US English** everywhere (also in `CLAUDE.md` → Conventions).
- **Don't change what exists on `main`** unless asked (e.g. its test profiles); keep changes to this branch's work.
- Ask before design decisions (package names, visibility trade-offs), with a recommended option.
- Edit this file by unique anchors only: it has two `### Tests` headings (Implementation Handoff and Outcome). An edit keyed
  on the first one deleted half the file on 2026-10-11 (restored).

**Review round 2 (done 2026-10-19, applied when the user said "go with review comments"; old names kept below as the record):**
- `ClaimIntakeState.findForClaim(claimId)` is a named wrapper for `findByIdOptional` (the id is shared through `@MapsId`).
  The user asked why it exists; answered (it says a claim id goes in, pairs with `forClaim`/`createFor`, mirrors
  `ClaimImage.findForClaim`). **Decision: leave it as is** (user, 2026-10-19).
- Rename the `Threading` record to **`ThreadingHeaders`** (user decision): it models the reply's `In-Reply-To` and
  `References` headers, not an activity or a threaded reply. Update its uses (`IntakeReplySender`, `IntakeReplies`,
  `ProcessingProblemReply`, `DecisionSteps`, tests), `CLAUDE.md` and this file.
- Rename `EmailFiling` to **`FileEmailStep`** (user decision, 2026-10-19): it's the body of the workflow's `file` task, like
  `PolicyCheckStep`/`SupersedeStep`/`ApplyOutcomeStep` (not a filter; it moves the email from processing to processed). Update
  `ClaimIntakeFlow` (field `filing`), `CLAUDE.md` (package table) and this file.
- More renames (user decisions, 2026-10-19, after a search for the same naming problem). For each, also rename the matching
  test class, fields and parameters, and update `CLAUDE.md`, this file and any other task file that names it:
  - `workflow.mail.IntakeReplies` → **`ReplySteps`**: the body of the four `reply*` tasks, like `DecisionSteps`.
  - `workflow.IntakeRequeue` → **`EmailRequeued`**: a CDI event, named in the past tense like `ReviewDecided`.
  - `workflow.review.ReviewRecovery` → **`ReviewRecoverer`**: a bean, not an activity.
  - `failure.ProcessingProblemReply` → **`ProcessingProblemReplySender`**: it sends the reply, like `IntakeReplySender`.
  - `failure.IntakeFailures` → **`IntakeFailureHandler`** (`IntakeFailuresTests` → `IntakeFailureHandlerTests`).
  - `mailbox.LoopProtection` → **`MailLoopGuard`** (`LoopProtectionTests` → `MailLoopGuardTests`).
  - `workflow.policy.PolicyRule` → **`SameCustomerRule`** (`PolicyRuleTests` → `SameCustomerRuleTests`): it holds exactly the
    "same customer" rule, and "policy" is ambiguous. The nested `Holder` stays.
- Replace `workflow.review.DecisionResult` (a record with one `boolean restored`) with an **enum `AfterDecision { END,
  WAIT_AGAIN }`** (user decision, 2026-10-19). It only drives the `afterDecision` switch, and its docs described just the fault
  path: a successful decision also returned `false`, which meant "end the run", not "not restored". `markInProcess` and
  `markPendingInformation` return `END`, `restoreReview` returns `WAIT_AGAIN` when it put the claim back in review and `END`
  when the status had already changed; the switch tests `== AfterDecision.WAIT_AGAIN`. Document the enum around "what the run
  does next", update the tests that read `restored` and the JSON round-trip test if it covers the type.
- Rename `workflow.review.DecisionSteps` → **`ReviewDecisionSteps`** (user decision, 2026-10-19): it keeps the `…Step` (one
  task) / `…Steps` (several tasks) convention, and says whose decision it applies. `…Reviewer` was rejected: the reviewer is the
  human claims processor. Update `ClaimIntakeFlow` (field `decisions`, `DecisionSteps.DECISION_FAILED`), the
  `ClaimIntakeReviewTests` spy, `CLAUDE.md` and this file.
  - Left as they are: `IntakeEmailReader` (reads like a repository), the plural static utilities (`PolicyNumbers`,
    `InceptionDates`, `ClaimFields`, `IsoValues`), `ClaimMatchingSteps`, `AgentStep`, `SupersedeStep`.

**Review round 1 (done 2026-10-11):** prefer `@Transactional` to `QuarkusTransaction`; JPA entities in `model` packages;
sub-packages by concern (`intake.dispatch`, `intake.failure`, `workflow.policy|outcome|review|mail`); tests mirror the
packages; one intake test profile; UK → US spelling. Then the known gaps were closed (see Outcome → Not done).

## Outcome

Built 2026-10-11 on `gh216/08-intake-workflow`, **not committed** (user asked to review first). Two things changed from the
plan with the user while building: the intake's bookkeeping moved off `Claim`, and `IntakeReplySender` lost its `Optional`
parameters. Everything else follows the Implementation Handoff.

- **`ClaimIntakeState` instead of columns on `Claim`** (user decision: the plumbing is irrelevant to the claim domain, and
  fetching a claim must not load it). Package `org.parasol.intake.model`, table `claim_intake_state`, a lazy unidirectional
  `@OneToOne @MapsId` to the claim with no reference back, so `Claim` has **no** intake column, not even `version`. It holds
  `reviewRunId`, `decidingRunId`, `intakeConversationId`, `policyNumberGenerated`, `requestedItems` and the `@Version`, which
  is the lock the reply/decision race serializes on (both write this row). `ClaimIntakeState` replaces every "on `Claim`" in
  this file's Implementation Handoff and in tasks 09, 10, 10b and 11 (updated). `ClaimRetrievalHasNoIntakeBookkeepingTests`
  proves retrieval returns none of it (real JSON, real database) and loads none of it (Hibernate statistics).
  `ClaimCorrespondence` (D4) stays in `claim.model`: it is the claim's record of its emails, not run plumbing.
- **Packages** (reorganized in review round 1, user decision): `org.parasol.intake` keeps only what every sub-package
  shares (`IntakeConfig`, `IntakeClaimStatus`, `MissingItem`); `intake.dispatch` (`ClaimIntakeStarter`, `SenderState`,
  `ClaimMatcher`, `ClaimReference`, `CorrespondenceRecorder`); `intake.failure` (`IntakeFailureHandler`, `IntakeFailureListener`,
  `ProcessingProblemReplySender`); `intake.mailbox` gains `MailLoopGuard`; `intake.model` gains the `ClaimIntakeState` entity;
  `intake.workflow` (`ClaimIntakeFlow`, `IntakeRun`, `IntakeState`, `EmailRequeued`, `AgentStep`, `ClaimMatchingSteps`,
  `CorrespondenceText`) with `workflow.policy`, `workflow.outcome`, `workflow.review` (now also `ReviewRecoverer`) and
  `workflow.mail`; `intake.review` (D6 types and `ReviewDecisionPublisher`); `ai.scoring.conversation.ConversationContext`
  (D1). `EmailRequeued` sits in `workflow` (the run fires it, the starter observes it), so `dispatch` depends on `workflow`
  and never the reverse. Step beans the flow calls across packages are public; helpers like `SameCustomerRule` stay
  package-private. `ClaimsMailboxAgentEntryPoint` is deleted; `AgentStep` injects the root.
- **The workflow** is the diagram above. One extra switch per branch (`routeMatch`, `routeResolution`, `routePolicy`,
  `routeSupersede`, `routeAfterFile`, `routeDecision`, `afterDecision`). Each step takes and returns the JSON-safe
  `IntakeState` (ids and small values; round-trip tested); steps re-read the email by `Message-ID`.
- **D2 needed a typed error.** Flow's `TryExecutor` only catches a `WorkflowException`; a plain Java exception from a
  function step skips every catch and faults the run (found by the first D2 test). `ReviewDecisionSteps` wraps a fault before its
  commit in a `WorkflowException` of type `DECISION_FAILED` and the flow uses `catchType(DECISION_FAILED, …)`; the catch
  restores the review and an `afterDecision` switch goes back to `waitReview`. Verified: the same run waits again and a
  second decision completes it with one thank-you.
- **A run that owns a review never dies with it** (gap closed in review round 1). A run that faults outside the decision
  steps' `tryCatch` (or after `applyOutcome` set it as the review run) would leave the claim `Pending Review` with nothing to
  receive the decision, and putting `reviewRunId` back alone wouldn't help: that run is gone. The failure listener calls
  `ReviewRecoverer` (`workflow.review`), which starts a replacement run that goes straight to `waitReview`
  (`IntakeRun.resumesReview`, routed by `routeMatch`) and registers it as the claim's `reviewRunId` before it starts, under
  the intake state's lock. The customer still gets the processing-problem reply.
- **D3, the starter.** `submit` returns at once; the email is matched when its turn comes. Each sender's state is an
  immutable `SenderState` (working run, queue, the email to start) changed only through `ConcurrentHashMap.compute`, so there
  is no `synchronized`; the requeue backoff is a Mutiny delay. A run hands an email back by firing the CDI event
  `EmailRequeued` (front of the queue). `SenderStateTests` pins the transitions.
  - **The wake-up signal** (`WAITING → RUNNING`, with the `routeDecision` task start as a backstop) is what holds a
    reply while a woken review run applies its decision. The first test for it passed either way (the backoff reaches the
    same end state), so `aReplyWaitsWhileAWokenReviewRunAppliesItsDecision` holds the decision step for 6 s and asserts
    that no model call mentions the reply while the run is working.
- **Mail sends run off the caller's thread** (`IntakeReplySender.offCallerThread`, `ForkJoinPool.commonPool()`). Found because
  four test runs stalled for exactly 30 s on a first reply and failed with GreenMail's `421`: the reactive mailer deadlocks when
  it is blocked on a thread with a captured Vert.x context, as `NotificationService`'s existing gotcha says. It affects task 11
  (mail spans lose the OTel context there; see its file).
- **`IntakeReplySender` refactor** (user decision): no `Optional` parameters. `ThreadingHeaders` (`of(email)`, `replyingTo(id)`,
  `none()`) and overloads with and without a `String claimNumber`; the one without is for replies that must not disclose a claim.
- **Transactions** (review round 1: prefer `@Transactional` to `QuarkusTransaction`): no main code uses `QuarkusTransaction`.
  A whole-method transaction is `@Transactional(REQUIRES_NEW)` on that method (`ClaimMatcher.match`, `initialize`,
  `CorrespondenceRecorder`, the decision steps). Where a step must not hold a transaction across an LLM, IMAP or SMTP call,
  the transactional part is its own non-private method called from the step (`AgentStep.snapshot`,
  `ApplyOutcomeStep.persist`, `ClaimMatchingSteps.pendingClaimsOf`, `PolicyCheckStep.holdersOf`/`currentPolicy`/
  `unusedPolicyNumber`, `ReplySteps.claimView`, `SupersedeStep.clearReviewUnlessDecided`,
  `ReviewDecisionSteps.putBackInReview`/`isDecisionFor`, `ReviewRecoverer.reviewOwnedBy`/`takeOver`), relying on Quarkus
  intercepting self-invocation. The review wait's match check moved from a static `QuarkusTransaction` call in the flow to
  `ReviewDecisionSteps.isDecisionFor`. The intake tests use `IntakeScenario`'s `@Transactional` helpers, so no intake test class
  uses `QuarkusTransaction` either.
- **Semantics worth knowing:** a reply must list the requested items it answers in `IncidentDetails.answeredItems`, or they
  stay requested (the prompt asks for it; test stubs must too). Completeness is judged on the extraction of the whole
  correspondence, so a superseding reply whose extraction lacks a field makes the claim `Pending Information` even though
  the claim keeps the value it had (a merge never overwrites with `null`).
- **`IsoValues`** became public (the workflow converts the ISO strings); `IncidentDetails.empty()` was added;
  `ClaimsMailbox.moveWithoutMessageId` files an email that has no id to find it by.
- **A quarkus-flow 1.2.0 leak when an agent throws** (found 2026-10-19 through `everyEndedOrCanceledRunLeavesNoFlowRows`). Its 2
  stray `task_info_entity` rows weren't a `claim-intake` run's: they belonged to a `claim-extraction-workflow` run left behind by
  `ClaimExtractionWorkflowTests.malformedJsonThatIsNeverFixedStillFailsTheRun`. In the full run, 3 agentic runs were still active
  at shutdown (that one, and two `claims-mailbox-agent` runs whose classifier failed: `IntakeFailureHandlerTests`' agent failure and a
  run in flight at the end of `ClaimIntakeReviewTests`).
  - **Cause:** LangChain4j tells a planner when an agent completes (`nextAction`), never when it fails. `FlowPlanner` therefore
    never completes the continuation the generated workflow's task joins on (`AgenticFlow.executeAgent` →
    `executeAgent(i).join()`). The run stays active, its rows (agentic scope, so correspondence text, included) stay, and
    **one worker thread stays blocked forever per failure** (measured with a thread dump). `cancel()` deletes the rows but
    can't release the thread: `join()` ignores interrupts. LangChain4j's `Planner` has no error hook (also on its `main`).
  - **The test** now checks only its own run's task rows (`taskRows(runId)`); the unscoped `taskRows()` was removed.
  - **Workaround (user decisions: in-app, in `org.parasol.intake.agent`, `@Deprecated`; reflection rather than
    cancel-only).** `AgenticWorkflowRuns` (a Flow `WorkflowExecutionListener` bean) tracks every run whose input is an
    `AgenticScope`. `FailedAgenticSystemListener` (`@AgentListenerSupplier` on `ClaimsMailboxAgent` only; not a CDI
    `AgentListener`, which quarkus-langchain4j would attach to every leaf; a leaf's error comes before its error handler
    decides to recover, the root's only once the whole invocation has failed) has `AgenticWorkflowRuns` fail that scope's runs:
    it completes `FlowPlanner`'s pending calls (`currentExchanges`, `agentExchangeQueue`) exceptionally by reflection, so the
    threads are released, the runs fault and Flow deletes their rows. If the internals change it falls back to `cancel()`
    with a WARN. Rejected on the way: cancel only (thread stays blocked), `FlowPlanner.nextAction` (completes normally, so
    the run goes on and reports COMPLETED), recovering every error in the root handler (the next agent fails on a missing
    input), the `AgenticServices.setWorkflowAgentsBuilder` SPI (subclasses of three Flow service classes plus Flow's flow
    lookup), and dropping `quarkus-flow-langchain4j` (the user chose to keep Flow-backed agents).
  - **Not covered:** `ClaimExtractionWorkflow` called directly (only in its own tests): the listener isn't on it, because
    `agent.extraction` would then depend on `agent`.
  - **Upstream:** issue [quarkiverse/quarkus-flow#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096), PR
    [quarkiverse/quarkus-flow#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095) (branch
    `fix-agentic-run-on-agent-failure` on the user's fork): `FlowPlanner` registers itself under its agent's id and
    `FlowAgentContextListener.onAgentInvocationError` aborts it, so every level of a failing system (and a nested one its parent
    recovers from) ends; 4 new tests, red without the change; runtime, deployment and langchain4j ITs pass. Opened as a draft;
    the issue was filed at the user's request. Delete the workaround once a release includes it.

### Tests

New: `ClaimIntakeNewClaimTests` (11, one parameterized over every `MissingItem`), `ClaimIntakePendingClaimTests` (7),
`workflow.review.ClaimIntakeReviewTests` (15), `workflow.policy.ClaimIntakePolicyTests` (7), `ClaimIntakeResolveClaimTests` (5),
`dispatch.ClaimIntakeStarterTests` (4), `failure.IntakeFailureHandlerTests` (2), `dispatch.ClaimMatcherTests` (5),
`dispatch.SenderStateTests` (7), `IntakeJsonRoundTripTests` (5), `ClaimRetrievalHasNoIntakeBookkeepingTests` (3), and unit tests
for `CorrespondenceText` (5: the four cap rules plus the reply separator), `ClaimReference` (4), `MailLoopGuard` (3),
`SameCustomerRule` (5), `PolicyNumbers` (2), `InceptionDates` (2) and `ConversationContext` (5), and `agent.FailedAgenticSystemListenerTests`
(2: a failed classifier, and a failed extraction three levels down, leave no new agentic run rows and no thread blocked in
`AgenticFlow.executeAgent`; red without the workaround). Shared helpers in
`org.parasol.intake` (test): `IntakeScenario`, `IntakeFlowTestBase`, and `WorkflowSpans` (a `SpanExporter` bean that keeps only
`workflow.execute` spans, so a test can check a run's span was exported). Every item of this task's **Tests** list has a test.

- **One test profile for the intake** (user request, 2026-10-11: keep the number of profiles to a minimum). Every `@QuarkusTest`
  boot restarts the Dev Services (Postgres, GreenMail, Langfuse, WireMock), which is most of the suite's run time. The intake
  work had two profiles of its own (`IntakeTestProfile`, `IntakeAgentsTestProfile`) and task 08 had added a third, for
  statistics; they are now one, `IntakeTestProfile` (stub keys, no Easy RAG ingestion, `claim-intake` on WireMock,
  statistics on). **Profiles that exist on `main` were left alone** (the two `KeysTestProfile`s, `DriftHandlerTestProfile`,
  the session-scoring, Quinoa and `drift` ones), as were the default-profile tests.
  - **No class orderer needed:** Quarkus already registers `QuarkusTestProfileAwareClassOrderer` as a global `ClassOrderer`, so
    classes already run grouped by profile and the number of boots is the number of distinct profiles. (An explicit
    `junit-platform.properties` for it was added and removed again: the user pointed to the guide. The first draft of this
    note wrongly said Surefire's order interleaves the profiles.)
  - **Not yet run:** the merged profile was only compiled, at the user's request.

### Not done

- **Default-profile run:** this machine has no real OpenAI/Cohere keys, so the default profile only compiles here.
- *Closed in review round 1:* the canceled run's `workflow.execute` span is now asserted (`WorkflowSpans`), and a fault outside
  the decision catch no longer strands the review (`ReviewRecoverer`).

### Verification

**Latest runs (2026-10-19)**, both with
`./mvnw -B <profile> test -Dtest='org.parasol.intake.**,ai.scoring.conversation.**,ClaimRetrieval*,ClaimResourceTests,ClaimImage*Tests,NotificationServiceTests,ClaimTests' -Dsurefire.failIfNoSpecifiedTests=false`:
- **`-Pollama`: 334 tests, 0 failures, 0 skipped.** 6 agentic runs were ended by the workaround (none needed the `cancel()`
  fallback); one run was still active at shutdown, the documented uncovered case (`ClaimExtractionWorkflowTests` calls the
  extraction workflow directly). Before the workaround: 3.
- **`-Pollama-openai`: 334 tests, 0 failures, 1 skipped** (`NotificationServiceTests.emailSendsWhenUserExists`, disabled under
  that profile by design). It needs CI's stub keys in the environment (`OPENAI_API_KEY`, `COHERE_API_KEY`, `GEMINI_API_KEY` =
  `change-me`): without them every default-profile test class fails to boot (`Could not expand value OPENAI_API_KEY`).
- `test-compile` passes under the default profile and `-Pollama`. `./mvnw -B clean verify` not run.

**2026-10-11 20:31 (`-Pollama`):** 188 tests, 1 failure (`everyEndedOrCanceledRunLeavesNoFlowRows`, fixed above). It covered
every change listed under "Changed after those runs" below, so that list is verified.

**Earlier runs** (all `-Pollama`, one class or group at a time, never the whole suite to completion):
`ClaimIntakeNewClaimTests`, `ClaimIntakePendingClaimTests`, `ClaimIntakeReviewTests`, `ClaimIntakePolicyTests`,
`ClaimIntakeResolveClaimTests`, `ClaimIntakeStarterTests` and `ClaimMatcherTests` passed in their last runs, as did the unit
tests, `IntakeReplySenderTests`, `ClaimResourceTests`, `ClaimImageResourceTests` and `ClaimRetrievalHasNoIntakeBookkeepingTests`.
A full `./mvnw -B -Pollama clean verify` was started and stopped (user request) before it finished.

**Changed after those runs, and not run since** (everything compiles, `-Pollama test-compile`):
- **review round 1:** every class moved to its new package (and the tests with it), `QuarkusTransaction` replaced by
  `@Transactional` methods throughout, the review wait's match check moved into `ReviewDecisionSteps`. **The riskiest unrun change
  is the transaction refactor**: it depends on self-invocation interception, and `ClaimIntakeReviewTests` spies on
  `ReviewDecisionSteps`, whose `applyReady` is now itself `@Transactional` (check the spy still goes through the interceptor);
- the profile consolidation (`IntakeTestProfile` now also covers `IntakeAgentsTestProfile` and the statistics profile);
- `ClaimIntakeFlow` now uses `withContext` for the steps that never read the task context (it was `withFilter`), and a method
  reference in `routeMatch`;
- the lint fixes: `SameCustomerRule` takes the `InboundEmail` instead of `Optional` parameters, `InceptionDates.before(limit, random)`,
  `ClaimMatcher`, `PolicyCheckStep`, `SupersedeStep`, `ApplyOutcomeStep`, and `ClaimIntakeState` (the unused `findByRunId` removed);
- the test split: the duplicate/auto-reply tests moved to `dispatch.ClaimIntakeStarterTests`, the no-`Message-ID` and agent
  failure tests to `failure.IntakeFailureHandlerTests`;
- **the review hand-over** (`ReviewRecoverer`, `IntakeRun.resumesReview`, a new first case in `routeMatch`), and the gap tests:
  `eachMissingItemIsAskedForOnItsOwn`, `aClaimIsCompletedOnlyByTheSecondReply`, `aReplyAnsweringTheTickedItemsGoesBackToReview`,
  `aFollowUpAfterTheDecisionGetsAStatusReplyAndTheConversationNeverChanges`, `aFaultOutsideTheDecisionCatchHandsTheReviewToANewRun`
  (replaces the faulted-run test), the canceled run's span and rows and the unchanged conversation id in the supersede test,
  and the `PersistenceInstanceReader` check in the complete-claim test. `WorkflowSpans` needs
  `quarkus.otel.bsp.schedule.delay=100ms` (in `IntakeTestProfile`); check the bean is picked up as an exporter;
- four new tests: `anImageOverTheSizeLimitIsSkippedAndMentioned`, `aReplyWaitsWhileAWokenReviewRunAppliesItsDecision`,
  `aFaultOutsideTheDecisionCatchFaultsTheRunAndTheCustomerStillGetsAProblemReply`, and the "no conversation id persisted"
  assertions in the not-a-claim, unmatched-follow-up and policy-rejected tests.

**Still to run before commit:** `./mvnw -B -Pollama test -Dtest='org.parasol.intake.**,ClaimRetrieval*,ClaimResourceTests,ClaimImage*Tests,NotificationServiceTests,ClaimTests'`,
then the same under `-Pollama-openai`, then `./mvnw -B clean verify` (10 Playwright tests are known to fail locally and pass in CI).
