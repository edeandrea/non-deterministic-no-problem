# Task 15: Claim Correspondence Transcript

**Type:** Code Modification

**Issue:** [#265](https://github.com/edeandrea/non-deterministic-no-problem/issues/265)

## Goal

The claim detail page's Documents tab shows a single "Original claim content" accordion item — `claim.subject` and
`claim.body` (effectively just the first email), plus attached images. There's no indication a claim's email thread
ever had more than one message: a reply that corrects or adds information leaves no trace in the UI at all, only in
whatever the agents merged into the claim's current field values.

Found while designing task 10's review UI (#258): a claims processor deciding a `Pending Review` claim would
benefit from seeing the actual back-and-forth that led to the current extracted fields, not just the first email.
**For the future — not required for #216 to land**; sequenced after task 14 so it doesn't block the intake's close-out.

## What to Do

- **Decide what "transcript" means here first**, since a true two-sided one needs new persistence this app doesn't
  have today:
  - **Inbound-only transcript** (cheaper): expose `ClaimCorrespondence` (`org.parasol.claim.model`; already records
    every inbound email the intake accepts — unique `Message-ID`, sender, sent date, stripped text, but
    `direction` is `INBOUND` only) via a new read endpoint and render it as a list. Reviewers would see only the
    customer's messages, not the app's replies — arguably *more* confusing than today's single-email view, since it
    looks like a thread but only has half of it.
  - **Real two-sided transcript** (bigger): also persist the app's own outbound replies — a new entity/table, or
    extend `ClaimCorrespondence` with an `OUTBOUND` direction, and start recording what `IntakeReplySender`,
    `GenerateEmailService`'s sender and `NotificationService` actually send. Nothing today stores a copy of an
    outbound email anywhere in the app's own database; they're sent over SMTP and only exist afterward in
    GreenMail/the customer's inbox.
- A new REST read (`GET /api/db/claims/{id}/correspondence` or similar), following the existing DTO/Problem Details
  conventions (a `ClaimDetails`-style record; never serialize entities directly).
- UI: replace or extend the Documents tab's single accordion with a chronological list of messages (sender,
  timestamp, body), keeping the existing image-attachment display for whichever message(s) carried them.
- Tests: REST (GreenMail-backed, mirroring the existing correspondence/reply test patterns) and a Playwright check
  that a multi-message claim's transcript renders in order.

## Files/Areas

- `src/main/java/org/parasol/claim/model/ClaimCorrespondence.java` (existing; may need an `OUTBOUND` direction if
  going for the two-sided transcript)
- `src/main/java/org/parasol/claim/rest/` (new correspondence read endpoint and DTO)
- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx` (the Documents tab)
- If persisting outbound replies: `org.parasol.intake.reply.IntakeReplySender`, `org.parasol.notification.*`
  (`GenerateEmailService`'s sender, `NotificationService`)

## Key Points

- `ClaimDetails` doesn't carry correspondence today, and there's no `/api/db/claims/{id}/correspondence`-shaped
  resource — this is new API surface, not an existing gap in a current endpoint.
- The Documents tab is unchanged since before the intake existed — it has no concept of multiple messages today,
  only the one accordion item.
- A single-email claim should still render sensibly once this lands (no empty "transcript" chrome around one
  message).

## Done When

- [ ] Inbound-only vs. real two-sided transcript is decided (and, if the latter, the outbound persistence is designed)
- [ ] The REST read endpoint and its DTO exist
- [ ] The Documents tab UI shows the transcript
- [ ] REST and Playwright tests pass
- [ ] `CLAUDE.md`'s claim/intake sections (and the design doc, if it describes the Documents tab) are updated
