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
  - **Skipped attachments:** a fragment included when attachments were ignored (non-image or too large).
- Move the phone number and sign-off (now in `GenerateEmailService.EMAIL_ENDING`) into a shared
  constant/config that both the templates and `GenerateEmailService` use. Keep `EmailEndsAppropriatelyOutputGuardrail` working.
- Create `IntakeReplySender`:
  - Sends from `claims@parasol.com`, with the subject prefixed by `[<claim number>]` when a claim exists.
  - Sets `In-Reply-To` / `References` to the inbound `Message-ID`, and `Auto-Submitted: auto-replied`.
- **Tests:**
  - render each template and assert the key content (claim number, each missing item, phone number)
  - the received — final review template shows `Pending Review` and the next step, and its
    during-review variant says the latest information was added
  - the policy-inconsistency email contains **no** policy number, name or other customer data
  - the processing-problem email leaks no details (no exception text, claim data or `Message-ID`)
  - the no-matching-claim email contains no claim number, status, name or address from any stored
    claim, and asks for the claim's address or the claim number
  - sent replies carry the subject prefix and headers (read back from GreenMail)

## Files/Areas

- `src/main/resources/templates/` (new Qute templates)
- `src/main/java/org/parasol/intake/reply/` (new)
- `src/main/java/org/parasol/notification/ai/GenerateEmailService.java` (shared sign-off)
- `src/test/java/org/parasol/intake/reply/` (new)

## Key Points

- Qute comes with `quarkus-mailer` (`quarkus-qute` is a dependency of quarkus-mailer). Confirm before adding anything to `pom.xml`.
- These templates are fixed on purpose. `GenerateEmailService` stays the AI-written example.
- The no-matching-claim reply has no `[CLM…]` subject prefix: no claim is disclosed.

## Done When

- [ ] All the listed templates exist in HTML and text form, and are sent through `IntakeReplySender`.
- [ ] There is exactly one "no matching claim" template, used for both the wrong-sender and the unmatched-follow-up cases.
- [ ] The rendering and header tests pass, including the "no leaked details" assertions (policy inconsistency, processing problem and no matching claim).
- [ ] `GenerateEmailService` and the templates share one phone-number/sign-off source, and the existing email guardrail tests still pass.
