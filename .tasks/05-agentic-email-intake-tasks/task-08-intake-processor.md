# Task 08: Intake Processor and Business Rules

**Type:** Code Modification

## Goal

`ClaimEmailProcessor` applies every business rule to an inbound email: it matches the claim, runs the
root workflow, checks the policy number, persists claims and images, sends replies, files the message
and evicts the run state. It also applies the claims processor's review decision. It is the **only**
place with side effects. Every customer submission gets a reply, and every edge case is covered by an
integration test.

## What to Do

Implement `ClaimEmailProcessor.process(InboundEmail)` with these rules, in order:

0. **Root span.** Open a span `claim-intake process` (`setNoParent()`, kind `CONSUMER`,
   `gen_ai.operation.name=invoke_agent`) and make it current for the whole run, so every agent, store and
   mail span nests under it (spike Q16b). When the run pauses for review, store the span's trace context on
   the claim (`intakeTraceparent`) in the same transaction as `reviewRunId`, for the review span link.
   Task 11 adds the attributes, listener spans and tests.
   - **Conversation id (task 11, Conversation grouping):** before the root span starts, resolve the claim
     (rule 3) and check the sender (rule 4), read-only. A matched claim that passes the sender check → reuse its
     `intakeConversationId`. Otherwise (no match, wrong sender, or a seeded claim with no `intakeConversationId`) → mint a
     new UUID. Rules 3–4 then reuse this result instead of matching again.
   - Make a `Context` carrying the baggage entry `gen_ai.conversation.id` current (try-with-resources, through
     task 11's intake helper) around the root span, and close it when the run ends.
   - A minted id is persisted only if the run creates a claim (rule 8, `NewClaim`); otherwise it just groups this
     one trace. The `intakeConversationId` is never changed once set.
   - `intakeConversationId` (new column `intake_conversation_id`, via the existing
     `CamelCaseToUnderscoresNamingStrategy`) is **nullable** and intake-only, like `intakeTraceparent`: claims
     not created by the intake (the seeded claims) have none, so a follow-up on one mints a fresh id as above.
1. **Loop protection.** Skip (move to processed, no reply) when:
   - the message is an auto-reply (`Auto-Submitted` other than `no`, or `Precedence: bulk|auto_reply|junk`)
   - or it's from the intake address itself
2. **Idempotency.**
   - Add a unique `sourceMessageId` column to `Claim`.
   - Store `Message-ID`s of follow-ups too (a `ClaimCorrespondence` table: claim, `Message-ID`, direction,
     sent date, stripped text, and whether our reply was sent), so reprocessing a follow-up is a no-op.
   - If the `Message-ID` is already recorded and its reply was sent: change nothing, resend nothing, just move the message.
   - **Around the non-atomic scope saves** (spike Q12: checkpoints commit in their own transaction): a crash can
     leave a scope row with no claim change, or a claim change with no reply sent. On reprocessing:
     - a scope row for this `Message-ID` but no recorded claim change → evict it and run again
     - a recorded claim change whose reply wasn't sent → send the reply, mark it sent, move the message
   - Order every run as: agents → persist (`requiringNew`) → send the reply → mark it sent → move the message.
3. **Claim resolution (code, not the LLM),** before the workflow: subject/body regex (`[CLM…]` / `CLM\d+`),
   then `In-Reply-To`/`References` against stored `Message-ID`s.
4. **Sender check.** A resolved claim only matches if its `emailAddress` equals the sender
   (case-insensitive). Otherwise send the one **"no matching claim"** template (task 04), change nothing and
   don't run the workflow.
5. **Superseding a waiting review.** If the resolved claim is `Pending Review`, in a `requiringNew()`
   transaction clear `reviewRunId` (optimistic lock, `@Version`; task 07), then evict the old suspended scope.
   If the lock is lost to a reviewer's decision, re-read the claim and continue under its new status
   (e.g. `In Process` → status reply). The reply is then merged and the workflow re-run like any pending-claim update.
6. **Run `ClaimsMailboxAgent.process(@MemoryId messageId, …)`** (task 06, injected here; the root interface extends
   `AgenticScopeAccess`), outside any transaction, with memory id = the inbound `Message-ID`. Pass the claim
   history explicitly (the agents are stateless): the combined correspondence, the matched claim (or none),
   the fields extracted so far, and the **requested items**, including the reviewer-ticked items stored on the claim.
   - A complete claim's run throws **`AgenticSystemSuspendedException`** at `ClaimReviewAgent`: catch it as a
     normal outcome, and read the extraction from the scope (`getAgenticScope(messageId).readState(…)`).
7. **Policy check, after the agents finish** (gap 7), in code, before any claim is created or changed. For a
   complete claim the run has already paused for review at this point.
   - stated and exists on another claim with the *same* customer (name + email): reuse it
   - stated and exists with a *different* customer: **evict the run state (including a paused review)**,
     send the policy-inconsistency template, create and change nothing
   - stated but not found: use it for a new claim
   - not stated: generate one (unique, `AC-` + digits)
8. **Apply the outcome:**
   - **`NewClaim`** (unmatched `NEW_CLAIM`):
     - **Inception date:** random, strictly before the incident date (or before today if the date is missing).
     - Name and email from `From:`; subject and full body stored.
     - Store the run's minted conversation id in the new `intakeConversationId` column (rule 0).
     - Convert the ISO-string `incidentDate`/`incidentTime` (task 05) to `LocalDate`/`LocalTime` here.
     - **Status:**
       - suspended at review → `Pending Review`, with `reviewRunId` = the `Message-ID`, and the
         "received — final review" template. **No** thank-you email.
       - completed with missing items → `Pending Information`, store the requested items, and the
         missing-information template.
     - Persist the claim and its image attachments (`ORIGINAL`, size/count limits) in **one new
       transaction** (`QuarkusTransaction.requiringNew()`).
     - Add the photos note and the skipped-attachments fragment where relevant. A first email gets exactly one reply.
   - **`PendingClaimUpdate`** (matched claim `Pending Information`, or `Pending Review` superseded by rule 5;
     the matched claim wins, whatever the classifier said):
     - Append the stripped reply to `body`, separated and dated, and record the correspondence.
     - Merge the newly supplied fields (never overwrite with null). Summary and sentiment come from the
       extraction workflow's re-run over the combined correspondence.
     - Store new image attachments.
     - **Completeness** (gap 8): the claim is complete only when no required detail is missing **and** every
       reviewer-ticked item was answered (`missingInformation`, task 05). A blank reply answers nothing.
       - complete (suspended at review) → `Pending Review` with the new `reviewRunId`, clear the requested
         items, and the "received — final review" template. If the claim was already `Pending Review`, use
         the "we've added your latest information; your claim is still in final review" variant.
       - incomplete → `Pending Information`, store the still-requested items, and the still-missing template
         (or the missing-information template if the claim was `Pending Review`).
   - **`StatusReply`** (matched claim in any other status, including seeded `New`): send the AI-written answer. Change nothing.
   - **`NotAClaim`:** send the not-a-claim template.
   - **`NoMatchingClaim`** (unmatched follow-up): send the one "no matching claim" template. Change nothing.
9. **Evict on every ending** (`evictAgenticScope(messageId)`): completed (any outcome), rejected by the policy
   check, superseded (rule 5), skipped or duplicate after a run started, and failed. The **only** run that keeps a
   scope row is one waiting for review whose claim is `Pending Review` with that `reviewRunId`.
10. **Move the message** to the processed folder when its run finishes, **including when it pauses for review**.
    On any exception:
    - evict the run state, move the message to the failed folder, log at ERROR with the `Message-ID`, and never
      rethrow to the watcher
    - send the processing-problem template, best-effort (a failure to send is logged, not rethrown).
      Never for auto-replies or self-sent mail.

Also implement **`applyReviewOutcome(claim, IntakeOutcome)`**, called by `ClaimReviewService` (task 07) after a resume
with the resumed run's `ReviewReady` or `ReviewNeedsInformation` outcome:
- **Ready** (`ReviewReady`) → `In Process`, and the thank-you template.
- **Needs more information** (`ReviewNeedsInformation`) → `Pending Information`, store the ticked items as the requested items, and the
  missing-information template listing exactly them. The run ends here (no re-suspension); the next reply starts a new run.
- Clear `reviewRunId` in both cases. Persistence runs in its own `requiringNew()` transaction, never across an agent call.
  `ClaimReviewService` evicts the scope afterwards.

- **Integration tests** (Compose GreenMail, `@InjectMock` on the root or a WireMock LLM, cleanup of claims/images and scope rows after each). One test per rule:
  - a complete new claim ends `Pending Review`, gets exactly one email (the received — final review), and leaves exactly one scope row
  - each missing item, and several at once
  - category `OTHER` is complete
  - no photos → the note is included
  - non-image and oversized attachments are skipped and mentioned
  - each policy case: same customer, different customer (vague template, no claim, no details leaked,
    **no scope row left, including when the run had paused for review**), unknown, absent
  - inception date before incident date; ISO date/time strings persisted as `LocalDate`/`LocalTime`
  - a pending claim completed by a reply moves to `Pending Review` and gets the received email
  - a pending claim completed only after two replies
  - a pending claim still incomplete after a reply
  - **matched claim wins:** a reply on a pending claim that the classifier labels `NOT_A_CLAIM` is still merged
  - an unmatched email classified as a follow-up gets the "no matching claim" reply, and nothing is created
  - after Needs more information, an **empty reply** stays `Pending Information` with the still-missing email;
    a reply answering the ticked items moves to `Pending Review`
  - a reply during `Pending Review` supersedes the review: the old scope row is gone, a new one exists,
    the claim stays `Pending Review` with the new `reviewRunId`, and the during-review variant is sent
  - a reply during `Pending Review` that makes the claim incomplete moves it to `Pending Information`, with the missing-information email
  - a reply that loses the optimistic lock to a decision is handled under the claim's new status
  - `applyReviewOutcome`: Ready → `In Process` + exactly one thank-you email; Needs more information →
    `Pending Information` + an email listing exactly the ticked items; `reviewRunId` cleared in both
  - a follow-up on an `In Process` (or later, or seeded `New`) claim gets a status reply and the claim is unchanged
  - a follow-up from a different address gets the "no matching claim" reply
  - a follow-up matched by header vs by subject number
  - a duplicate `Message-ID` is a no-op (no reply)
  - reprocessing after a simulated crash (scope row but no claim; claim but no reply sent) sends exactly one reply
  - an auto-reply and a self-sent message are skipped (no reply)
  - an HTML-only email
  - an agent exception moves the message to the failed folder, sends the processing-problem email, and creates no claim
  - no scope rows remain for runs that didn't end waiting for review
  - **conversation id:** a new claim persists a UUID `intakeConversationId`; a follow-up, a superseding reply and a status
    reply reuse it, and it never changes; not-a-claim, no-matching-claim and policy-rejected emails persist none; a
    wrong-sender email doesn't get the claim's id (span-level assertions are in task 11)

## Files/Areas

- `src/main/java/org/parasol/intake/ClaimEmailProcessor.java` and supporting classes (new)
- `src/main/java/org/parasol/model/claim/Claim.java` (`sourceMessageId`, requested items, `intakeConversationId`), a `ClaimCorrespondence` entity
- `src/test/java/org/parasol/intake/` (new integration tests)

## Key Points

- **Side effects only here:** agents decide; the processor saves, mails, files and evicts.
- Never hold a database transaction open during an agent call, including when a workflow resumes.
- Policy-number generation must not collide with existing policies. Use a sequence or a checked generator, following issue 2's pattern.
- Status string constants: `Pending Information`, `Pending Review`, `In Process` (intake never sets `New`; seed statuses unchanged).
- The thank-you email is sent only from `applyReviewOutcome` (reviewer clicked Ready), never from `process`.
- Every customer submission gets a reply. The only exceptions are auto-replies, self-sent mail and duplicate `Message-ID`s.
- Never assert absolute claim numbers. Delete created claims and images, because `ClaimsListPageTests` expects 6.

## Done When

- [ ] Every rule above is implemented, and each listed edge case has a passing integration test under `-Pollama`.
- [ ] The policy check runs after the agents; a rejection evicts the run state (including a paused review) and creates no claim.
- [ ] Completeness accounts for reviewer-ticked items; the empty-reply test passes.
- [ ] The scope is evicted on every ending; only a run waiting for review keeps a row.
- [ ] Reprocessing after a partial failure is idempotent (exactly one reply, no duplicate claim).
- [ ] Every run has a conversation id in baggage before its root span starts: minted for unmatched emails, persisted as `intakeConversationId` when a claim is created, and reused for matched claims; the conversation-id tests pass.
- [ ] A forced failure leaves the message in the failed folder, sends the processing-problem reply, and leaves no partial claim and no scope row in the database.
- [ ] Both `test-compile` runs succeed.
