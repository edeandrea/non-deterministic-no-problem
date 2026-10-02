# Task 03: Intake Configuration and Claims Mailbox

**Type:** Code Modification

## Goal

The app can read, parse and move messages in the `claims@parasol.com` IMAP mailbox on GreenMail,
configured through a type-safe mapping. Covered by tests against the Compose GreenMail.

## What to Do

- Add `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic` (managed by the BOM from issue 1) to `pom.xml`.
  This is where the agentic dependency is added for real; the task 01 spike only added it on a throwaway branch.
  Confirm the build still succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- Add `org.eclipse.angus:angus-mail` (version managed by the Quarkus BOM; confirm it's the latest stable).
- Add the `claim-intake` model to `application.yml` for openai (default), ollama, `%ollama` and
  `%ollama-openai`, matching how `generate-email` is configured in each.
- Create an `IntakeConfig` `@ConfigMapping` (prefix e.g. `parasol.intake`):
  - `enabled` (default `true`; `false` in `%test`)
  - inbox `address`
  - IMAP host/port/user/password
  - the processed and failed folder names
  - max body characters sent to the LLM
  - max image size and count
- Create `ClaimsMailbox`, a CDI bean over Jakarta Mail:
  - open the INBOX
  - list unprocessed messages
  - parse a message into an `InboundEmail` record:
    - `Message-ID`, `In-Reply-To` / `References`
    - from name and address, subject, sent date
    - plain-text body; for HTML-only bodies, convert to text with jsoup
    - image attachments (bytes, content type, file name)
    - auto-reply indicators (`Auto-Submitted`, `Precedence`)
  - move a message to a named folder, creating the folder if it's missing
- Strip quoted reply text from bodies (lines starting with `>` and "On … wrote:" blocks) and expose both the full and the stripped text.
- **Tests (against Compose GreenMail, sending with the app's mailer or Jakarta Mail):**
  - plain-text and HTML-only messages parse to the expected text
  - multipart/alternative prefers the text part
  - image attachments are extracted, and non-image ones are reported as skipped
  - auto-reply headers are detected
  - quoted text is stripped
  - moving a message removes it from the INBOX and creates the folder

## Files/Areas

- `pom.xml` (`quarkus-langchain4j-agentic`; angus-mail; jsoup at the latest stable version)
- `src/main/resources/application.yml`
- `src/main/java/org/parasol/intake/IntakeConfig.java`, `src/main/java/org/parasol/intake/mailbox/` (new)
- `src/test/java/org/parasol/intake/mailbox/` (new)

## Key Points

- **Every `@QuarkusTest`** must stub API keys through its test profile. Under `-Pollama-openai` none
  are stubbed, and the new `claim-intake` model would otherwise fail with `SRCFG00011`.
- Use the GreenMail test helper from issue 4 to purge mailboxes between tests.
- Records for value types, `Optional` instead of null, and package-private CDI beans, per `AGENTS.md`.

## Done When

- [ ] `quarkus-langchain4j-agentic` is in `pom.xml`, and the build succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- [ ] `IntakeConfig`, `ClaimsMailbox` and `InboundEmail` exist, and the `claim-intake` model is configured for every provider/profile.
- [ ] All the mailbox tests listed above pass against Compose GreenMail.
- [ ] Both `test-compile` runs succeed.