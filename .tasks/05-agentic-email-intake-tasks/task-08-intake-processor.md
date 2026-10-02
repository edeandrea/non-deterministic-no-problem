# Task 08: Intake Processor and Business Rules

**Type:** Code Modification

## Goal

`ClaimEmailProcessor` applies every business rule to an inbound email: it persists claims and images,
sends replies and moves the message. It also applies the claims processor's review decision. Every
customer submission gets a reply, and every edge case is covered by an integration test.

## What to Do

Implement `ClaimEmailProcessor.process(InboundEmail)` with these rules, in order:

1. **Loop protection.** Skip (move to processed, no reply) when:
   - the message is an auto-reply (`Auto-Submitted` other than `no`, or `Precedence: bulk|auto_reply|junk`)
   - or it's from the intake address itself
2. **Idempotency.**
   - Add a unique `sourceMessageId` column to `Claim`.
   - If a claim already exists for this `Message-ID`, don't create another. Resend nothing that was already sent, and just move the message.
   - Store `Message-ID`s of follow-ups too (e.g. a `ClaimCorrespondence` table) so reprocessing a follow-up is a no-op.
3. **Claim resolution:** subject/body regex, then reply headers (see task 06).
4. **Follow-up authorisation.** A follow-up only matches a claim whose `emailAddress` equals the sender
   (case-insensitive). Otherwise send the "no matching claim" template and change nothing.
5. **Superseding a waiting review.** If the resolved claim is `Pending Review`, evict its suspended
   scope (`reviewRunId`) and clear `reviewRunId` before the agent runs. The reply is then merged and
   the workflow re-run like any pending-claim update.
6. **Run `ClaimsMailboxAgent`** outside any transaction, with `@MemoryId` = the inbound `Message-ID` (task 07).
   A complete claim comes back **suspended** at `ClaimReviewAgent`.
7. **Apply the outcome:**
   - **`NewClaim`:**
     - **Policy number:**
       - stated and exists on another claim with the *same* customer (name + email): reuse it
       - stated and exists with a *different* customer: send the policy-inconsistency template, create nothing
       - stated but not found: use it for a new claim
       - not stated: generate one (unique, `AC-` + digits)
     - **Inception date:** random, strictly before the incident date (or before today if the date is missing).
     - Name and email from `From:`; subject and full body stored.
     - **Status:**
       - nothing missing (the run suspended at review) → `Pending Review`, with `reviewRunId` = the
         `Message-ID`, and the "received — final review" template. **No** thank-you email.
       - something missing → `Pending Information`, and the missing-information template.
     - Persist the claim and its image attachments (`ORIGINAL`, size/count limits) in **one new
       transaction** (`QuarkusTransaction.requiringNew()`).
     - Add the photos note and the skipped-attachments fragment where relevant. A first email gets exactly one reply.
   - **`PendingClaimUpdate`** (claim `Pending Information`, or `Pending Review` superseded by rule 5):
     - Append the stripped reply to `body`, separated and dated.
     - Merge the newly supplied fields (never overwrite with null).
     - Re-run summary and sentiment over the combined body.
     - Store new image attachments.
     - Recompute what's missing:
       - complete (suspended at review) → `Pending Review` with the new `reviewRunId`, and the
         "received — final review" template. If the claim was already `Pending Review`, use the
         "we've added your latest information; your claim is still in final review" variant.
       - incomplete → `Pending Information`, and the still-missing template (or the missing-information
         template if the claim was `Pending Review`).
   - **`StatusReply`** (claim `In Process` or later, including seeded statuses): send the AI-written answer. Change nothing.
   - **`NotAClaim`:** send the not-a-claim template.
8. **Evict the scope of every run that didn't suspend** (`evictAgenticScope`), whatever its outcome,
   including failures. Only a run waiting for review keeps a scope row.
9. **Move the message** to the processed folder after success. On any exception:
   - move it to the failed folder, log at ERROR with the `Message-ID`, and never rethrow to the watcher
   - send the processing-problem template, best-effort (a failure to send is logged, not rethrown).
     Never for auto-replies or self-sent mail.

Also implement **`applyReviewOutcome(claim, ReviewDecision)`**, called by `ClaimReviewService` (task 07) after a resume:
- **Ready** → `In Process`, and the thank-you template.
- **Needs more information** → `Pending Information`, and the missing-information template listing exactly the ticked items.
- Clear `reviewRunId` in both cases. Persistence runs in its own `requiringNew()` transaction, never across an LLM call.

- **Integration tests** (Compose GreenMail, `@InjectMock` on the agents, cleanup of claims/images and scope rows after each). One test per rule:
  - a complete new claim ends `Pending Review`, gets exactly one email (the received — final review), and leaves exactly one scope row
  - each missing item, and several at once
  - category `OTHER` is complete
  - no photos → the note is included
  - non-image and oversized attachments are skipped and mentioned
  - each policy case: same customer, different customer (vague template, no claim, no details leaked), unknown, absent
  - inception date before incident date
  - a pending claim completed by a reply moves to `Pending Review` and gets the received email
  - a pending claim completed only after two replies
  - a pending claim still incomplete after a reply
  - a reply during `Pending Review` supersedes the review: the old scope row is gone, a new one exists,
    the claim stays `Pending Review` with the new `reviewRunId`, and the during-review variant is sent
  - a reply during `Pending Review` that makes the claim incomplete moves it to `Pending Information`, with the missing-information email
  - `applyReviewOutcome`: Ready → `In Process` + exactly one thank-you email; Needs more information →
    `Pending Information` + an email listing exactly the ticked items; `reviewRunId` cleared in both
  - a follow-up on an `In Process` (or later) claim gets a status reply and the claim is unchanged
  - a follow-up from a different address is rejected
  - a follow-up matched by header vs by subject number
  - a duplicate `Message-ID` is a no-op (no reply)
  - an auto-reply and a self-sent message are skipped (no reply)
  - an HTML-only email
  - an agent exception moves the message to the failed folder, sends the processing-problem email, and creates no claim
  - no scope rows remain for runs that didn't suspend

## Files/Areas

- `src/main/java/org/parasol/intake/ClaimEmailProcessor.java` and supporting classes (new)
- `src/main/java/org/parasol/model/claim/Claim.java` (`sourceMessageId`), a correspondence entity if used
- `src/test/java/org/parasol/intake/` (new integration tests)

## Key Points

- Never hold a database transaction open during an LLM call, including when a workflow resumes.
- Policy-number generation must not collide with existing policies. Use a sequence or a checked generator, following issue 2's pattern.
- Status string constants: `Pending Information`, `Pending Review`, `In Process` (intake never sets `New`; seed statuses unchanged).
- The thank-you email is sent only from `applyReviewOutcome` (reviewer clicked Ready), never from `process`.
- Every customer submission gets a reply. The only exceptions are auto-replies, self-sent mail and duplicate `Message-ID`s.
- Never assert absolute claim numbers. Delete created claims and images, because `ClaimsListPageTests` expects 6.

## Done When

- [ ] Every rule above is implemented, and each listed edge case has a passing integration test under `-Pollama`.
- [ ] A forced failure leaves the message in the failed folder, sends the processing-problem reply, and leaves no partial claim and no scope row in the database.
- [ ] Both `test-compile` runs succeed.
