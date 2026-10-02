# Task 03: Intake Configuration and Claims Mailbox

**Type:** Code Modification

## Goal

The app can read, parse and move messages in the `claims@parasol.com` IMAP mailbox on GreenMail,
configured through a type-safe mapping. The agentic extension, the `claim-intake` model and the
database-backed agentic scope store are in place, registered before any agent can run, and checked at
startup. Covered by tests against the Compose GreenMail and PostgreSQL.

## What to Do

- Add `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic` (managed by the BOM from issue 1) to `pom.xml`.
  This is where the agentic dependency is added for real; the task 01 spike only added it on a throwaway branch.
  Confirm the build still succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
  - The spike verified quarkus-langchain4j **1.14.1** only. `main` is on 1.13.1 until #212 (PR #219) merges;
    don't start this task before it has.
- Add `org.eclipse.angus:angus-mail` (version managed by the Quarkus BOM; confirm it's the latest stable).
- Add the `claim-intake` model to `application.yml` for openai (default), ollama, `%ollama` and
  `%ollama-openai`, matching how `generate-email` is configured in each.
  - **Project rule:** every new OpenAI-client chat model (the default openai config and `%ollama-openai`) sets
    `temperature` **and** `top-p` explicitly (quarkus-langchain4j ≥1.14 omits them otherwise).
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
- **Agentic scope store** (spike Q12–Q14, Q18), in `org.parasol.intake.review`:
  - `AgenticScopeRecord` entity/table: key = `agentId|memoryId` (`AgenticScopeKey.agentId()` is the root
    interface's FQCN), scope JSON as `text`, `updatedAt` (`Instant`).
  - `DatabaseAgenticScopeStore implements AgenticScopeStore` (`save` / `load` / `delete` / `getAllKeys`).
    Every call runs in its own `QuarkusTransaction.requiringNew()`, so checkpoints commit independently of the
    caller (they can't be atomic with claim updates; task 08 designs around that). Serialize with
    `AgenticScopeSerializer.toJson`/`fromJson`. Deleting a missing key returns `false` without throwing.
  - **Registrar:** a `StartupEvent` observer at the clearly-lowest priority (e.g.
    `@Priority(Interceptor.Priority.PLATFORM_BEFORE - 1000)`) calls `AgenticScopePersister.setStore(cdiStore)`.
    It also observes `ShutdownEvent` and calls `setStore(null)`, because the static survives a Quarkus restart in
    the same JVM (test profiles, dev-mode reload) and otherwise holds the old app's store. A root first invoked
    before the registrar never persists, so **no observer below this priority may call an agent**.
  - **Allowlist:** at startup, call `AgenticScopeSerializer.allowDeserializationType(..)` for every scope type
    that appears in no agent method signature. Task 07 adds the review-decision record to this list.
  - **Startup self-test:** after registration, round-trip a sample scope holding each intake scope value type
    through the serializer, and fail startup with a clear message if it doesn't. This catches a `LocalDate` or
    `Optional` sneaking into a scope value, which otherwise only fails at checkpoint or resume time.
- **Tests (against Compose GreenMail, sending with the app's mailer or Jakarta Mail):**
  - plain-text and HTML-only messages parse to the expected text
  - multipart/alternative prefers the text part
  - image attachments are extracted, and non-image ones are reported as skipped
  - auto-reply headers are detected
  - quoted text is stripped
  - moving a message removes it from the INBOX and creates the folder
- **Store tests:**
  - save/load/delete round trip, keyed by `agentId|memoryId`; deleting a missing key returns `false`
  - the registrar has set the store before any `@Startup` bean runs, and resets it on shutdown
  - the self-test fails for a scope value containing `LocalDate` or `Optional`
  - no scope rows remain after the tests

## Files/Areas

- `pom.xml` (`quarkus-langchain4j-agentic`; angus-mail; jsoup at the latest stable version)
- `src/main/resources/application.yml`
- `src/main/java/org/parasol/intake/IntakeConfig.java`, `src/main/java/org/parasol/intake/mailbox/` (new)
- `src/main/java/org/parasol/intake/review/`: `AgenticScopeRecord`, `DatabaseAgenticScopeStore`, the registrar and self-test (new)
- `src/test/java/org/parasol/intake/mailbox/`, `src/test/java/org/parasol/intake/review/` (new)

## Key Points

- **Every `@QuarkusTest`** must stub API keys through its test profile. Under `-Pollama-openai` none
  are stubbed, and the new `claim-intake` model would otherwise fail with `SRCFG00011`.
- Use the GreenMail test helper from issue 4 to purge mailboxes between tests.
- Records for value types, `Optional` instead of null, and package-private CDI beans, per `AGENTS.md`.
- The store is JVM-global, so every agentic call in the app writes to it (ephemeral roots too: one save and one
  delete per run). Only the intake uses agentic today.
- Scope rows hold the raw email and every prompt (about 3.4–4.7 KB each, PII). Run a **single replica**: each
  scope is also cached in memory until it's evicted.

## Done When

- [ ] `quarkus-langchain4j-agentic` is in `pom.xml` (on quarkus-langchain4j 1.14.x), and the build succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- [ ] `IntakeConfig`, `ClaimsMailbox` and `InboundEmail` exist, and the `claim-intake` model is configured for every provider/profile, with explicit `temperature` and `top-p` on the OpenAI-client configs.
- [ ] `DatabaseAgenticScopeStore` (`agentId|memoryId` key, `text` JSON, `updatedAt`) is registered by a lowest-priority `StartupEvent` observer, reset on `ShutdownEvent`, and checked by the startup round-trip self-test.
- [ ] All the mailbox and store tests listed above pass against Compose GreenMail and PostgreSQL.
- [ ] Both `test-compile` runs succeed.
