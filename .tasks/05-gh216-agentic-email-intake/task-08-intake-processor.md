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

## What to Do

- **Starter** (`ClaimIntakeStarter`, called by the watcher, task 09). Plain code before any run starts; it **doesn't
  wait** for the run (user decision, 2026-10-07):
  1. **Loop protection:** an auto-reply (`Auto-Submitted` other than `no`, or `Precedence: bulk|auto_reply|junk`) or
     mail from the intake address → move to processed, no run, no reply.
  2. **Duplicate:** a `Message-ID` already recorded (unique `Claim.sourceMessageId`, or a `ClaimCorrespondence` row:
     claim, `Message-ID`, direction, sent date, stripped text) → move to processed, no run, no reply.
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
  6. Start the run **inside `ConversationContext.callIn(conversationId, …)`** (task 10b), with a small input record
     (`IntakeRun`: `Message-ID`, matched claim id or none, the sender-check result, the conversation id).
     **That one call is the only observability-related code in the intake.**
  7. **Per-sender order** (user decision, 2026-10-07): if the sender (case-insensitive address) already has a run
     that's still working (not waiting, not ended), queue this email behind it instead of starting a run; start it
     when that run waits or ends. Per sender, not per claim, because `resolveClaim` may only pick the claim inside the
     run; a claim only matches its own address, so this covers per-claim order. A CDI `WorkflowExecutionListener`
     (`onWorkflowStatusChanged` → `WAITING`, `COMPLETED`, `CANCELLED`, `FAULTED`; F2) drives the queue.
     **Never cancel a run mid-step**; only a waiting run is cancelled (the `supersede` step).
- **Pass ids, not payloads.** Flow persists step data after every step, so the workflow data never holds the raw email
  or attachment bytes. Steps re-read the email with `ClaimsMailbox.find(MailFolder.PROCESSING, messageId)` (task 03) before the `file` step
  moves it. (The agentic scope Flow checkpoints *does* hold the correspondence text the agents receive. That's PII in
  Flow's tables for as long as the run lives; Flow deletes the rows when the run ends or is cancelled.)
- **Steps.** Each one is a `function`/`withFilter` calling a package-private bean. No step holds a transaction across
  an LLM call, and every persistence step runs in `QuarkusTransaction.requiringNew()`.
  - **`replyNoMatchingClaim`:** the one "no matching claim" template (task 04). Change nothing.
  - **`supersede`** (after `policyCheck`, just before `applyOutcome`; design review 2026-10-07): if the matched claim is
    `Pending Review`, clear `reviewRunId` in its own transaction under optimistic locking (`@Version` on `Claim`), then
    cancel the waiting run with `definition().activeInstance(oldRunId).map(WorkflowInstance::cancel)` (F3; in-JVM only,
    fine for a single replica). Placing it here means a faulted or policy-rejected reply never leaves a `Pending Review`
    claim with no waiting run. If the lock is lost to a reviewer's decision (or the claim is being decided,
    `decidingRunId` set), end this run without changes and put the email back in the claim's queue: it's handled again
    under the new status (e.g. `In Process` → status reply).
  - **`resolveClaim`** (user decision, 2026-10-07: "try to route it, ask if we can't"): only when no claim matched and
    the sender has claims in `Pending Information` or `Pending Review`. Calls `ClaimResolver` (task 06) with the email
    and those claims (number, short summary, requested items):
    - `EXISTING` with a number **from that list** → continue exactly as a matched claim (supersede, history, etc.)
    - `NEW_INCIDENT` → continue unmatched (the classifier then decides, as today)
    - `UNSURE`, or a number not in the list → `replyWhichClaim` (task 04's template, the sender's pending claim
      numbers), change nothing, END
  - **`runAgents`:** `ClaimsMailboxAgent.process(…)` (task 06), with the claim history passed explicitly: the combined
    correspondence, the matched claim (or none), the fields extracted so far, and the **requested items**, including
    any reviewer-ticked items stored on the claim. Output: the `IntakeOutcome`.
  - **`policyCheck`** (gap 7), in code, before any claim is created or changed:
    - stated, and on another claim with the *same* customer (name + email): reuse it
    - stated, with a *different* customer: send the policy-inconsistency template, create and change nothing, END
    - stated but not found: use it for a new claim
    - not stated: generate one (unique, `AC-` + digits; a sequence or a checked generator, following issue 2's pattern)
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
        was answered (`missingInformation`, task 05). A blank reply answers nothing.
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
    correlated on the claim id read from the run's input (C10). `ReviewDecision` is a record: a `ReviewOutcome` enum
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
  - **A fault in the decision steps** (e.g. the thank-you can't be sent) also puts the claim back so the reviewer can
    retry: `decidingRunId` → cleared and the claim stays `Pending Review`, or — if the status already changed — log it.
    Flow 1.1.3 has `FlowDSL.tryCatch(...)` for doing this inside the run instead; pick whichever is simpler and test it.
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
- **matched claim wins:** a reply on a pending claim labelled `NOT_A_CLAIM` is still merged
- an unmatched email classified as a follow-up → "no matching claim", nothing created
- after Needs more information, an **empty reply** stays `Pending Information` (still-missing email); a reply answering
  the ticked items → `Pending Review`
- **supersede:** a reply during `Pending Review` cancels the waiting run (0 Flow instance and task rows left for it).
  The claim stays `Pending Review` with the new `reviewRunId`, the during-review variant is sent, and a later decision
  reaches **only** the new run. A superseding reply that makes the claim incomplete → `Pending Information`.
- **race:** a reply and a decision on the same `Pending Review` claim, run concurrently (latched): exactly one wins; a
  losing decision gets the 409 and sends no email; a losing reply is handled under the new status
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
- **no Flow rows remain** for completed and cancelled runs (E19; also catches the unreproduced F3 "row still present")
- **conversation id:** a new claim persists a UUID `intakeConversationId`. A follow-up, a superseding reply and a status
  reply reuse it, and it never changes. Not-a-claim, no-matching-claim and policy-rejected emails persist none. A
  wrong-sender email doesn't get the claim's id. (Span-level assertions are task 11's.)

## Files/Areas

- `src/main/java/org/parasol/intake/`: `ClaimIntakeFlow`, `ClaimIntakeStarter` (with the per-sender queue), the run-status and failure listeners, the step beans,
  and `ReviewDecision`/`ReviewOutcome` (in `review/`)
- `src/main/java/org/parasol/claim/model/Claim.java` (`sourceMessageId`, requested items, `reviewRunId`, `@Version`,
  `decidingRunId`, `intakeConversationId`), a `ClaimCorrespondence` entity
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
- Don't assert on a cancelled run's `workflow.execute` span: quarkus-flow#1058 drops it.
- Editing the `Flow` bean in dev mode can break hot reload (`IncompatibleClassChangeError`); restart dev mode instead.

## Done When

- [ ] `ClaimIntakeFlow` implements the steps above, with the agentic root as one step and every terminal branch ending.
- [ ] The starter does the checks, enters the conversation id once, keeps each sender's emails in order, and doesn't wait for runs.
- [ ] `resolveClaim` routes fresh emails to the sender's pending claim, a new claim, or the which-claim reply, and never to a claim outside the sender's list.
- [ ] The policy check runs after the agents; a rejection creates nothing.
- [ ] Completeness accounts for reviewer-ticked items; the empty-reply test passes.
- [ ] A reply during review cancels the waiting run; the race test passes; no Flow rows remain for ended runs.
- [ ] The thank-you email is sent only from `markInProcess`; a decision makes no LLM call.
- [ ] The failure listener handles faults before and after `WAITING`, with tests.
- [ ] Every test listed above passes under `-Pollama`, and both `test-compile` runs succeed.
