# Task 12c: Claim Correspondence Transcript

**Type:** Code Modification

**Issue:** [#265](https://github.com/edeandrea/non-deterministic-no-problem/issues/265)

## Goal

The claim detail page's Documents tab shows a single "Original claim content" accordion item — `claim.subject` and
`claim.body` (effectively just the first email), plus attached images. There's no indication a claim's email thread
ever had more than one message: a reply that corrects or adds information leaves no trace in the UI at all, only in
whatever the agents merged into the claim's current field values.

Found while designing task 10's review UI (#258): a claims processor deciding a `Pending Review` claim would
benefit from seeing the actual back-and-forth that led to the current extracted fields, not just the first email.
**Required before task 13** (user decision, 2026-10-30; it was task 15, "for the future", until then): the demo guide
shows a reviewer deciding a claim, so it should document the transcript, and task 14 verifies it. It runs after task 12b,
so its tests use the final extraction fixtures.

**Correction (2026-10-30, planning):** the Goal above is partly wrong. `Claim.body` already has every inbound reply appended
(`CorrespondenceText.appendReply`, under `----- Reply received <date> -----`). So the tab already shows the customer's side,
as one block. It never shows the app's replies, and it doesn't split the thread into separate messages.

## Decisions (user, 2026-10-30)

1. **Two-sided transcript.** The intake's outbound replies are recorded too, as `OUTBOUND` `ClaimCorrespondence` rows. The
   blocker in `CorrespondenceDirection`'s Javadoc ("the mailer generates the replies' ids") is solvable: Vert.x
   `MailEncoder.createHeaders` (vertx-mail-client 4.5.35) applies user-supplied headers last, so a `Message-ID` the app
   generates replaces the mailer's.
2. **Only the intake's claim replies are recorded.** The four `IntakeReplySender` overloads that take a claim number cover
   the templates and the AI status answer. Replies without a claim never have one, so they stay out. The chat bot's
   `NotificationService` status emails also stay out: a **separate GitHub issue, not part of #216**, will cover them.
3. **Photos appear per message.** `ClaimImage` gets a nullable link to the `ClaimCorrespondence` row of the email that
   carried it.

## Plan (2026-10-30)

### Branch

Fast-forward the local `gh216-email-intake` to `origin` (`995eea1`). Then branch `gh216/12c-correspondence-transcript` from it.

### Backend

#### 1. `ClaimCorrespondence` holds both directions (`org.parasol.claim.model`)
- **`CorrespondenceDirection.OUTBOUND`**, added with Javadoc: a reply the intake sent about a claim. Remove the "the only
  direction recorded today" sentence from `INBOUND`'s Javadoc.
- **New nullable `subject` column** (`Length.LONG32`).
  - `CorrespondenceRecorder.insert` sets it from `email.subject()`.
  - Outbound rows store the subject as sent, including the `[CLM…] Re:` prefix.
- **Outbound values for the existing columns,** documented in the Javadoc:
  - `sentDate` and `receivedAt` are both the moment the mail server accepted the reply.
  - `sender` is the claims address.
  - `strippedText` is the text sent.
- **The existing queries need no change.** `countInboundForClaim` and `findFirstInboundReceivedAt` already filter on
  `INBOUND`, so the claim metrics are unaffected. `findClaimByMessageIds` now also matches a customer reply whose
  `In-Reply-To`/`References` name only our reply. That is an improvement, and a test covers it.
- **New `static ClaimCorrespondence recordReply(Claim claim, String messageId, String sender, String subject, String text)`**
  (Active Record, like `ClaimImage.store`). It persists an `OUTBOUND` row.
- **New `static List<ClaimCorrespondence> listForClaim(long claimId)`.** It throws `ClaimNotFoundException` for an unknown
  claim, like `ClaimImage.listForClaim`, and orders by `receivedAt`, then `id`.

#### 2. `ClaimImage` links to its email
- **New field `@ManyToOne(fetch = LAZY) @JoinColumn(name = "correspondence_id") @OnDelete(SET_NULL) public ClaimCorrespondence correspondence`.**
  - The column is nullable, so seeded images and the seeder are unchanged.
  - It is indexed, like `claim_id`.
  - `SET_NULL` keeps `IntakeScenario.reset()`'s `ClaimCorrespondence.deleteAll()` working.
- **New overload `store(ClaimCorrespondence correspondence, kind, fileName, contentType, data)`.** It takes the claim from
  the row. The existing overload stays for the seeder.
- **`ApplyOutcomeStep`:** merge `storeImages` and `linkCorrespondence` (in both `applyNew` and `applyUpdate`) into one
  helper.
  - It looks the row up once (`email.messageId().flatMap(ClaimCorrespondence::findByMessageId)`) and sets its claim.
  - It stores each image against the row.
  - If an email has no row, it falls back to the claim-only `store`. The dispatcher already filters those emails out, so
    this never happens in practice.

#### 3. The intake records its claim replies (`org.parasol.intake.reply`)
- **Every reply gets a `Message-ID` the app generates:** `<UUID@domain-of-parasol.intake.address>`, added as a header.
  Vert.x `MailEncoder.createHeaders` applies user headers last, so ours replaces the generated one (checked in
  vertx-mail-client 4.5.35).
- **New package-private `@ApplicationScoped ReplyRecorder`** with `@Transactional(REQUIRES_NEW) void record(String claimNumber, SentReply reply)`.
  - It looks the claim up with `Claim.findByClaimNumber` and calls `ClaimCorrespondence.recordReply`.
  - If no claim has that number, it logs a WARN.
  - `SentReply` is a package-private record: `messageId`, `subject`, `text`.
- **`IntakeReplySender` needs a new shape**, which is why the span annotation moves:
  - The two overloads that take a `claimNumber` record the reply **after** the send succeeds. The two without one record
    nothing.
  - **Why `@MailClientSpan` has to move:** if recording stayed inside the annotated public methods, its JDBC work would
    run inside the `smtp send` span and count toward `claim.intake.mail.client.duration{protocol=smtp}`.
  - So the annotation moves to one package-private method, `deliver(Uni<Void> send, String template)`. It awaits the
    send and publishes `ReplySent`. Quarkus intercepts self-invocation of non-private methods, as it already does for
    `ClaimsMailbox.unprocessed()` → `list(...)`.
  - The template is still set as a span attribute, because `ReplySent` is published while the span is current.
- **A failed recording only logs a WARN.** By then the reply has gone out, and faulting the step would answer the customer
  twice. This is the same rule as the post-commit email in `ReviewDecisionSteps`.
- **The text of a template reply is its `text/plain` variant,** rendered with `TemplateInstance.SELECTED_VARIANT`. That is
  what `MailTemplateInstanceImpl.send()` and `IntakeTemplatesTests.render` do. Rendering happens inside the recording
  `try`, so a rendering problem only logs too.

Sketch of the reshaped sender:
```java
@MailClientSpan(protocol = Protocol.SMTP, operation = "send") // moved here from the four public methods
void deliver(Uni<Void> send, String template) {
	awaitSent(send);
	this.activities.publish(new ReplySent(template));
}

public void sendTemplateReply(MailTemplateInstance template, String recipient, String subject, String claimNumber, ThreadingHeaders threadingHeaders) {
	var messageId = newMessageId();
	var fullSubject = subjectPrefix(Optional.of(claimNumber)) + normalizeSubject(subject);

	deliver(templateMail(template, recipient, fullSubject, messageId, threadingHeaders), templateName(template));
	record(claimNumber, () -> new SentReply(messageId, fullSubject, textOf(template)));
}

// The reply has gone out: failing the step now would answer the customer twice
private void record(String claimNumber, Supplier<SentReply> reply) {
	try {
		this.recorder.record(claimNumber, reply.get());
	}
	catch (RuntimeException e) {
		Log.warnf(e, "Sent a reply about claim %s but couldn't record it", claimNumber);
	}
}
```
- **Constructor:** it gains `ReplyRecorder`. Update the hand-built senders in `IntakeReplySenderThreadTests` and
  `IntakeReplySenderFailureTests`, which get a Mockito mock.

#### 4. REST read (`org.parasol.claim.rest`)
- **`GET /api/db/claims/{id}/correspondence`,** served by a new package-private `ClaimCorrespondenceResource` with a
  `public` method. It returns `List<CorrespondenceMessage>`.
  - An unknown claim is a `404` Problem Details (`ClaimNotFoundException`, already mapped).
  - A claim with no rows (the six seeded ones) returns `[]`.
- **`CorrespondenceMessage` record** (`@JsonNaming(SnakeCaseStrategy)`): `id`, `direction`, `sender`, `subject`, `date`
  (`Instant`, taken from `receivedAt`), `text`, `images` (`List<ClaimImageMetadata>`, so the image URLs stay identical).
- **`ClaimMapper`** gets `toMessages(List<ClaimCorrespondence>, List<ClaimImage>, long claimId)`.
  - Group the claim's images by correspondence id, so there's one image query per claim, not one per message.
  - Only images linked to a row appear in a message. Unlinked ones stay available through `/images`.
  - Read the link's id without initializing the lazy row.

### Frontend (`src/main/webui/src/app/`)
- **New `components/ClaimDetail/Correspondence.tsx`.** Like `PendingInformation`, it fetches its own data, with an
  `AbortController` keyed on `claimId`.
  - **Empty list:** it renders today's single "Original claim content" accordion unchanged (subject, `body`, the original
    images). That keeps seeded claims, and `ClaimImagesPageTests`, as they are.
  - **Otherwise:** one `AccordionItem` per message, in order.
    - Toggle text: who sent it (the customer's address, or "Parasol claims") and the date. Subject and text go in the
      body (`display-linebreak`), plus an `ImageCarousel` when the message has photos.
    - The customer's messages start expanded and the replies start collapsed, so the photo is visible when the tab opens.
      `RoundcubeClaimIntakeE2ETests` relies on that.
    - `data-testid="correspondence-message"` and `data-direction` give Playwright stable selectors.
  - **Original images with no message:** they appear in an extra "Other attached images" item, and only when there are any.
- **`ClaimDetail.tsx`:** the Documents tab renders `<Correspondence claimId=… claim=… originalImages=…/>` instead of the
  inline accordion, and the `claim-toggle1` accordion state moves into the new component.
- **New `utils/correspondence.ts` with `correspondence.test.ts`.** It holds the pure helpers: which images have no message,
  and which items start expanded. The test follows `claimImages.test.ts`, and the suite must log nothing.

### Tests (all on existing profiles: no new boots)
- **`ClaimCorrespondenceResourceTests`** (`org.parasol.claim.rest`, default profile like `ClaimImageResourceTests`):
  - Fixtures are committed with `QuarkusTransaction.requiringNew()` and removed with `Claim.deleteById`.
  - The response comes back in order, compared as records: inbound with its image, then outbound.
  - Another claim's rows aren't included.
  - A seeded claim returns `[]`.
  - An unknown claim is a `404` `ProblemDetail`.
  - `jsonFieldNames` pins the exact key set.
- **`ClaimImageTests`:** deleting a correspondence row unlinks its image and keeps the image.
- **`IntakeReplySenderTests`** (GreenMail):
  - A claim reply records an `OUTBOUND` row whose `messageId` equals the delivered message's `message-id` header. Its
    subject and text match what was sent.
  - A reply without a claim records nothing.
  - Rows are cleaned up after each test.
- **`IntakeReplySenderFailureTests`** (Mockito):
  - A failed send never calls the recorder.
  - A recorder that throws doesn't fail the send.
- **Workflow test** in the existing new-claim/reply class of `org.parasol.intake.workflow` (find it during
  implementation):
  - A new claim with a photo yields an `INBOUND` row with its subject and the image linked, then an `OUTBOUND` row for
    the reply.
  - A customer reply whose subject has no claim number and whose headers name only our reply's `Message-ID` matches the
    claim.
- **`IntakeTracingTests`, `IntakeMetricsTests`, `IntakeSpanPropagationTests`:** run them unchanged to confirm the `smtp send`
  span and the `mail.client.duration` tags still hold after the annotation moves.
- **Playwright, in `ClaimReviewPanelPageTests`:** it already drives real emails through `IntakeScenario`, so no new class
  and no new boot.
  - Send a claim with no location, with a photo. Then send the customer's reply with the location.
  - The Documents tab shows four messages in this order: inbound with the photo, the outbound missing-information
    request, the inbound reply, and the outbound final-review notice.
  - The photo appears only in the first message.
- **Unchanged, must pass:** `ClaimImagesPageTests` (the seeded-claim fallback) and `RoundcubeClaimIntakeE2ETests` (photo on
  the Documents tab).

### Docs and tracking
- **`CLAUDE.md`:**
  - Package table: `claim.model` (`ClaimCorrespondence` with both directions), `claim.rest` (the new resource and DTO),
    `intake.reply` (`ReplyRecorder`).
  - REST section: the new endpoint.
  - Rewrite the `ClaimCorrespondence` paragraph, which today says "`INBOUND` only: the mailer generates the replies' ids".
  - Claim images: the correspondence link.
  - Intake reply notes: the generated `Message-ID`, recording after send, and the span on `deliver`.
  - The test sections.
- **`docs/design/email-claim-intake.md`:** one sentence where it describes replies. Replies about a claim carry an
  app-generated `Message-ID` and are recorded, and the claim page shows the two-sided transcript. Check `README.md` for
  any description of the Documents tab.
- **Task file:** tick Done When and add an Outcome.
- **`PLAN.md`:**
  - Mark 12b done (#280, `995eea1`) and 12c built.
  - Add this findings-log line.
  - Add a Key Decisions entry for the three user decisions.
  - Add a note for task 13: document the transcript.
- **New GitHub issue** (user request; unrelated to #216, so no `Part of #216`). Create it with `gh issue create`:
  - Title: "Record the chat bot's status-update emails in a claim's correspondence".
  - Body: #265 records only the intake's replies. `NotificationService.sendEmail` (`GenerateEmailService`) sends with a
    `Message-ID` the mailer generates, from a `commonPool` thread inside the tool's joined transaction, and nothing
    records it. Lay out the options: a generated `Message-ID` plus `ClaimCorrespondence.recordReply`, and where the
    transaction boundary goes.
- **Then stop for review** (plan steps 5–7). Commit, push and open the PR into `gh216-email-intake` (body starts
  "Implements task 12c of #216", `Part of #265`) only after the user approves. Write commit messages per the global rules,
  ending with `Assisted-By: Claude Code <noreply@anthropic.com>`.

### Verification
1. `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
2. `./mvnw test -Dtest='ClaimCorrespondenceResourceTests,ClaimImageResourceTests,ClaimImageTests,IntakeReplySender*Tests,ClaimIntake*Tests,ClaimMatcherTests,IntakeTracingTests,IntakeMetricsTests,IntakeSpanPropagationTests' -Pollama`.
3. Playwright, which also runs Jest through Quinoa:
   `./mvnw verify -Pollama -Dtest=none -Dit.test='ClaimReviewPanelPageTests,ClaimImagesPageTests,RoundcubeClaimIntakeE2ETests'`.
   If the Playwright classes run under surefire, use `-Dtest=` instead.
4. Full `./mvnw -B clean verify -Pollama`. `-Pollama-openai` is left to CI, as in 12b.

## Files/Areas

- `src/main/java/org/parasol/claim/model/ClaimCorrespondence.java` (existing; may need an `OUTBOUND` direction if
  going for the two-sided transcript)
- `src/main/java/org/parasol/claim/rest/` (new correspondence read endpoint and DTO)
- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx` (the Documents tab)
- `org.parasol.intake.reply.IntakeReplySender` and the new `ReplyRecorder` (outbound recording). `org.parasol.notification.*`
  is out of scope (decision 2)

## Key Points

- `ClaimDetails` doesn't carry correspondence today, and there's no `/api/db/claims/{id}/correspondence`-shaped
  resource — this is new API surface, not an existing gap in a current endpoint.
- The Documents tab is unchanged since before the intake existed — it has no concept of multiple messages today,
  only the one accordion item.
- A single-email claim should still render sensibly once this lands (no empty "transcript" chrome around one
  message).

## Done When

- [x] Inbound-only vs. real two-sided transcript is decided (and, if the latter, the outbound persistence is designed): two-sided, intake replies only, photos per message (see Decisions and Plan)
- [x] The REST read endpoint and its DTO exist (`ClaimCorrespondenceResource`, `CorrespondenceMessage`; `ClaimCorrespondenceResourceTests`)
- [x] The Documents tab UI shows the transcript (`Correspondence.tsx`; `ClaimReviewPanelPageTests.theDocumentsTabShowsTheTwoSidedTranscriptWithThePhotoUnderItsMessage`, `correspondence.test.ts`)
- [x] REST and Playwright tests pass: full `clean verify -Pollama` (the CI command), 617 tests, 0 failures, 2 skipped (`DriftDetectionTests`, `ClaimsMailboxConcurrencyTests`), Jest 79 passed. `-Pollama-openai` not run locally (left to CI)
- [x] `CLAUDE.md`'s claim/intake sections and the design doc are updated (no README text describes the Documents tab)

## Outcome

Built as planned, with these deviations and findings:

- **`createdClaim` flag on `ClaimCorrespondence`** (user decision 2026-10-30, option 2): claims don't all come through the intake (an adjustor may enter one). The row of the email that creates a claim has `createdClaim = true`; the endpoint returns it as `created_claim`. The tab shows an "Original claim content" item first unless some message created the claim, so a hand-entered claim that later gets a customer reply keeps its own subject, body and photos in view. Seeded claims (no emails) show only that item, as before.
- **Outbound timestamps are whole seconds.** A reply is recorded with `Instant.now()` truncated to seconds, like the IMAP INTERNALDATE of inbound rows. With sub-second precision, a customer reply in the same second sorted before our reply (found by `ClaimIntakePendingClaimTests.aCustomerReplyThatNamesOnlyOurMessageIdFindsTheClaim`); ties now fall back to the id.
- **`images` is absent from the JSON for an email with no photos** (the global non-empty inclusion). The record's compact constructor reads it as empty, and the UI treats a missing `images` as none (`anEmailWithoutPhotosHasNoImagesKey`).
- **`AccordionItem` renders only a fragment**, so `data-testid`/`data-direction` sit on a wrapper `div`.
- **`ClaimImage.store` overload** made untyped `Claim.findById(...)` calls ambiguous; `ClaimImageResourceTests` now types them.
- The `smtp send` span now wraps `IntakeReplySender.deliver`; `IntakeTracingTests`, `IntakeMetricsTests` and `IntakeSpanPropagationTests` pass unchanged.
- **`ClaimMapperTests`** (added on request; 16 tests, a `@QuarkusTest` on hand-built entities, nothing persisted and no Hibernate): every `ClaimDetails` field, the list, empty and null cases, image metadata and URL, and every `toMessages` case (no emails, all fields, `receivedAt` rather than `sentDate`, outbound, order, images under their own email, none for an email without photos, none for unlinked images or an email outside the list). The first version used the database and Hibernate statistics to measure that the grouping runs no SQL; it was dropped, because the endpoint loads the emails first in the same persistence context, so it guarded a case the endpoint doesn't hit.
- Follow-up issue for the chat bot's emails: [#281](https://github.com/edeandrea/non-deterministic-no-problem/issues/281) (not part of #216).
- Not done: IntelliJ lint of the changed Java files, and the commit, push and PR (waiting for your review).
