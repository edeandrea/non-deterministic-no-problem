# Task 04: Qute Reply Templates and Sender

**Type:** Code Modification

## Goal

Every fixed reply the intake sends is a Qute mail template (HTML + text), sent by one sender
component and covered by rendering tests.

## What to Do

- Create Qute mail templates (`@CheckedTemplate` / `MailTemplate`), each with `.html` and `.txt` variants:
  - **Received — final review:** confirms we received the claim, with the claim number, status
    `Pending Review`, a short summary of what we recorded, and the next step (a claims processor does a
    final check and we'll be in touch), plus the photos note if no images were received and the
    skipped-attachments fragment when relevant. Used when the first email is already complete and when
    a reply completes a pending claim. A variant (or flag) covers a reply while the claim is
    `Pending Review`: "we've added your latest information; your claim is still in final review".
  - **Claim in process (thank you):** thanks the customer ("we are working on your claim"), with the
    claim number and status `In Process`. Sent **only** after the reviewer clicks Ready.
  - **Missing information:** claim number, status `Pending Information`, a bullet list of exactly the
    missing items (incident description, date, location, category), the photos note if no images were
    received, and an instruction to reply to this email. Also used for the reviewer's "Needs more
    information" decision, listing exactly the items the reviewer ticked.
  - **Still missing:** a follow-up variant listing only what's still missing (including any
    reviewer-ticked items the reply didn't answer).
  - **Processing problem:** we received your email but couldn't process it automatically; a team
    member will follow up, or call 1-800-CAR-SAFE. **No details** about the failure.

  - **Policy inconsistency:** a deliberately vague message saying there's an inconsistency with the
    policy information, asking the customer to call 1-800-CAR-SAFE. **No details** about what didn't match.
  - **Not a claim:** this inbox only handles insurance claims.
  - **No matching claim:** **one** template for both cases where we can't match the email to a claim
    for this sender: a reply from an address other than the claim's (wrong sender), and an email the
    classifier labels a follow-up that matches no claim. It gives **no details** (not whether the claim
    exists, its status or its owner) and asks the customer to write from the address they used for the
    claim, or to include their claim number.
  - **Which claim?** (user decision, 2026-10-07): sent when an email matched no claim, the sender has pending
    claims, and `ClaimResolver` (task 08) couldn't tell which one it's about. Lists the sender's **own** pending
    claim numbers (sent only to the address already on those claims) and asks them to reply on that claim's
    email or include its number. Nothing else about the claims.
  - **Skipped attachments:** a fragment included when attachments were ignored (not an image, too large, too many or
    empty).
- Move the phone number and sign-off into `parasol.claims-department.*` config that both the templates and `GenerateEmailService` use. Keep `EmailEndsAppropriatelyOutputGuardrail` working.
- Create `IntakeReplySender`:
  - Sends from `claims@parasol.com`, with the subject prefixed by `[<claim number>] Re: ` when a claim exists.
  - Sets `In-Reply-To` / `References` to the inbound `Message-ID`, and `Auto-Submitted: auto-replied`.
- **Tests:**
  - render each template and assert the key content (claim number, each missing item, phone number)
  - the received — final review template shows `Pending Review` and the next step, and its
    during-review variant says the latest information was added
  - the policy-inconsistency email contains **no** policy number, name or other customer data
  - the processing-problem email leaks no details (no exception text, claim data or `Message-ID`)
  - the no-matching-claim email contains no claim number, status, name or address from any stored
    claim, and asks for the claim's address or the claim number
  - the which-claim email lists exactly the given claim numbers and nothing else from those claims
  - sent replies carry the subject prefix and headers (read back from GreenMail)

## Files/Areas

- `src/main/resources/templates/` (new Qute templates)
- `src/main/java/org/parasol/intake/reply/` (new)
- `src/main/java/org/parasol/notification/ai/GenerateEmailService.java` (shared sign-off)
- `src/test/java/org/parasol/intake/reply/` (new)

## Key Points

- Qute ships with `quarkus-mailer`, but the app declares `quarkus-qute` explicitly because it uses it directly (user
  decision, 2026-10-08).
- These templates are fixed on purpose. `GenerateEmailService` stays the AI-written example.
- The no-matching-claim reply has no `[CLM…]` subject prefix: no claim is disclosed.

## Outcome

Done 2026-10-08, on branch `gh216/04-reply-templates`; in review as [#234](https://github.com/edeandrea/non-deterministic-no-problem/pull/234) into `gh216-email-intake`.

- **Templates** (`org.parasol.intake.reply.IntakeTemplates`, `@CheckedTemplate(basePath = "IntakeTemplates")`): the
  nine replies, each as `.html` and `.txt` under `src/main/resources/templates/IntakeTemplates/`.
  - The photos note and skipped-attachments list are one shared fragment (`attachmentNotes`), so their wording isn't
    passed in from Java. It takes `hasPhotos` and the `InboundEmail`'s `List<SkippedAttachment>`, and words all four
    skip reasons (`NOT_AN_IMAGE`, `TOO_LARGE`, `TOO_MANY`, `EMPTY`; `skippedAttachmentReason.txt`).
  - Status names come from `IntakeClaimStatus` (`@TemplateEnum`), so a reply names the status stored on the claim.
  - The no-matching-claim reply describes the claim-number format instead of showing an example number: the earlier
    example, `CLM01000000`, is the first seeded claim's real number.
- **Greeting** (user decision, 2026-10-08): every template takes `Optional<String> customerName` and greets
  `Dear <name>,`, or `Dear Customer,` without one. `CustomerName.forReply(email, claim)` picks it:
  1. the claim's `clientName`, when the email resolved to a claim **and** came from that claim's address;
  2. otherwise the `From` display name, unless it's blank or just an email address;
  3. otherwise none.

  A claim's name is never shown to another address. The no-matching-claim reply passes no claim, so it can only use
  the sender's own `From` name.
- **Which claim?** (user decision, 2026-10-08): it lists the sender's own pending claim numbers, and nothing else about
  them. That isn't a leak: the reply goes to the address already on those claims, so a forged `From` never sees it.
- **Sign-off:** `parasol.claims-department.name` / `.phone` in `application.yml` (user decision: app-level config).
  The templates, `GenerateEmailService.EMAIL_ENDING` and `EmailEndsAppropriatelyOutputGuardrail` all read them through
  Qute's `config:` namespace, in the bracket form `{config:['parasol.claims-department.name']}` (the dotted form fails
  with `Property "parasol" not found`). There is no `@ConfigMapping`: no Java code reads the values.
  - The guardrail renders `EMAIL_ENDING` once, at construction, with the same Qute engine that renders the AI
    service's prompt, so the ending the model is told to write and the one it's checked against can't drift apart.
  - "Parasoft" in the old ending is now "Parasol". `NotificationServiceTests.emailSendsWhenUserExists` still passes
    with qwen3:4b under `%ollama`.
- **`IntakeReplySender`:** sends from `parasol.intake.address`. The subject is `[<claim number>] Re: <subject>`, or
  `Re: <subject>` with no claim, and a leading `Re:` isn't doubled. Every reply carries `Auto-Submitted: auto-replied`.
  - `In-Reply-To` is the inbound `Message-ID`. `References` is the inbound email's own `References` plus that
    `Message-ID`, without duplicates, so a customer's answer to our reply still references the original thread.
    Without an inbound `Message-ID`, `In-Reply-To` is left off.
  - The mailer generates our own `Message-ID` (Vert.x `MailEncoder`, read in the source, not tested), so the sender
    doesn't set one.
  - **It blocks**, rather than returning a `Uni`: the callers are Flow steps on worker threads that must know the reply
    went out before they file the email. Text replies use the blocking `Mailer`. Templated replies can only send
    through the `ReactiveMailer`, and `MailTemplateInstance.sendAndAwait()` waits forever, so the sender awaits
    `send()` for `quarkus.mailer.timeout` (forever when 0, as the `Mailer` does).
  - `sendTextReply` is for the AI-written status answer (tasks 06 and 08).
- **Package boundaries** (review, 2026-10-08): `notification` doesn't use `IntakeReplySender`. `NotificationService`
  still sends its status emails from `noreply@parasol.com` through its own `ReactiveMailer`.
- **Enums in `org.parasol.intake`:** `MissingItem` (`label()` for the emails and the review checklist; `find` /
  `fromValue` accept a label or a constant name) and `IntakeClaimStatus` (`Pending Information`, `Pending Review`,
  `In Process`) for tasks 05, 08 and 10.
- **Test support:** `GreenMailMailbox` reads the first `text/plain` part of multipart mail, and `ReceivedEmail` has
  `headers` (keyed in lower case) and `firstHeader(name)`.
- **Tests:**
  - `IntakeTemplatesTests` renders both variants of every template without sending. The HTML is checked through its
    visible text (jsoup), so both variants get the same assertions. It covers the greeting and its fallback, the
    missing-item lists, the photos note, every skip reason, and HTML escaping of the customer's name and file names.
    The no-leak tests check against every seeded claim's policy number, name, address and status, and against any
    `CLM` number or `Message-ID`. The no-matching-claim test was checked to fail with the old example number put back.
  - `IntakeReplySenderTests` reads the sent replies back from GreenMail: sender, subject prefix (with and without a
    claim), `Auto-Submitted` and the threading headers.
  - `CustomerNameTests` is a plain unit test of the name rules.
- **Verified:** a full `./mvnw verify` against the real providers passed (user, 2026-10-08). Locally,
  `./mvnw -B clean verify -P<profile> -Dquarkus.langchain4j.ollama.devservices.enabled=false` under `ollama` and
  `ollama-openai` (219 tests each) passed apart from the 10 Playwright tests in `org.parasol.ui`, which fail the same way
  in that local environment on the base commit `fcfb8e8`.

## Done When

- [x] All the listed templates exist in HTML and text form, and are sent through `IntakeReplySender`.
- [x] There is exactly one "no matching claim" template, used for both the wrong-sender and the unmatched-follow-up cases.
- [x] The rendering and header tests pass, including the "no leaked details" assertions (policy inconsistency, processing problem and no matching claim).
- [x] `GenerateEmailService` and the templates share one phone-number/sign-off source, and the existing email guardrail tests still pass.
