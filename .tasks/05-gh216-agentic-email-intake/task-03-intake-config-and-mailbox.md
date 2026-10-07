# Task 03: Intake Configuration, Flow Dependencies and Claims Mailbox

**Type:** Code Modification

## Goal

The app can read, parse and move messages in the `claims@parasol.com` IMAP mailbox on GreenMail,
configured through a type-safe mapping. The agentic extension, quarkus-flow (with its LangChain4j, JPA and
OpenTelemetry modules) and the `claim-intake` model are in place. Covered by tests against the Compose
GreenMail and PostgreSQL.

*Rewritten after the Flow verdict (task 01b = ADOPT, option b1).* The `DatabaseAgenticScopeStore`, its
entity, the `AgenticScopePersister.setStore` registrar, the `ShutdownEvent` reset, the
`allowDeserializationType` allowlist and the startup round-trip self-test are **gone**: Flow persists the run.

## What to Do

- **Dependencies** (`pom.xml`):
  - `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic` (managed by the quarkus-langchain4j BOM).
  - **Import `io.quarkiverse.flow:quarkus-flow-bom`** (`type pom`, `scope import`) in `<dependencyManagement>`, after
    the Quarkus platform and quarkus-langchain4j BOMs, with its version in a `quarkus.flow.version` property. Then
    add `quarkus-flow`, `quarkus-flow-langchain4j`, `quarkus-flow-jpa` and `quarkus-flow-opentelemetry` **without
    versions**. (The spike versioned each dependency separately instead; the BOM is cleaner.) The 1.1.3 BOM manages
    only `io.quarkiverse.flow` artifacts, so it can't override the Quarkus or quarkus-langchain4j versions. Check
    that this still holds for the version you pick.
  - Use the latest **final** release: re-check Maven Central for 1.2.0 final first (1.1.3 is what the spikes
    verified; 1.2.0.CR3 was the latest prerelease on 2026-10-07). If you move off 1.1.3, re-run the three
    reproducers in [edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) with
    `-Dquarkus.flow.version=<new>` and update the issue status in `PLAN.md` (#1056, #1057, #1058).
  - `org.eclipse.angus:angus-mail` (managed by the Quarkus BOM) and `org.jsoup:jsoup` (latest stable).
  - Confirm with `./mvnw dependency:tree` that quarkus-langchain4j stays on the project's version (Flow 1.1.3 was
    built against 1.13.3), and that the build still succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- **Flow config** (`application.yml`): set `quarkus.application.name` explicitly. It becomes Flow's
  `application_id`, part of the primary key of all three Flow tables. **No schema strategy**: durable state across
  restarts isn't a requirement (user decision), and every profile already recreates the schema on boot.
- **Model:** add the `claim-intake` model for openai (default), ollama, `%ollama` and `%ollama-openai`, matching
  `generate-email` in each.
  - **Project rule:** every new OpenAI-client chat model (the default openai config and `%ollama-openai`) sets
    `temperature` **and** `top-p` explicitly (quarkus-langchain4j ≥1.14 omits them otherwise).
  - **Pin the unnamed default provider in every mocking profile** (`quarkus.langchain4j.chat-model.provider=openai`):
    under `-Pollama`, an agent with no `@ModelName` makes augmentation fail with *"multiple available providers"*
    (task 01b).
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
  - **find a message by `Message-ID`** (workflow steps re-read the email from the mailbox instead of carrying it
    in the workflow data; see task 08)
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
  - a message is found by its `Message-ID`, and a missing one gives an empty `Optional`
  - moving a message removes it from the INBOX and creates the folder
  - the app boots with the four Flow modules under both Ollama profiles

## Files/Areas

- `pom.xml` (the `quarkus-flow-bom` import, agentic, the four Flow modules, angus-mail, jsoup)
- `src/main/resources/application.yml`
- `src/main/java/org/parasol/intake/IntakeConfig.java`, `src/main/java/org/parasol/intake/mailbox/` (new)
- `src/test/java/org/parasol/intake/mailbox/` (new)

## Key Points

- **Every `@QuarkusTest`** must stub API keys through its test profile. Under `-Pollama-openai` none
  are stubbed, and the new `claim-intake` model would otherwise fail with `SRCFG00011`.
- Use the GreenMail test helper from issue 4 to purge mailboxes between tests.
- Records for value types, `Optional` instead of null, and package-private CDI beans, per `AGENTS.md`.
- `quarkus-flow-langchain4j` and `quarkus-flow-opentelemetry` are **Preview** extensions (accepted cost, task 01b).
- Editing a `Flow` bean in dev mode can throw `IncompatibleClassChangeError … _ClientProxy overrides final method
  Flow.definition()`; restart dev mode instead (task 13 documents it).

## Done When

- [ ] `quarkus-flow-bom` is imported (a final release), `quarkus-langchain4j-agentic` and the four Flow modules are in `pom.xml` without versions, quarkus-langchain4j stays on 1.14.x, and the build succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- [ ] `quarkus.application.name` is set; there is no agentic scope store, registrar, allowlist or self-test.
- [ ] `IntakeConfig`, `ClaimsMailbox` (including find-by-`Message-ID`) and `InboundEmail` exist, and the `claim-intake` model is configured for every provider/profile, with explicit `temperature` and `top-p` on the OpenAI-client configs.
- [ ] All the mailbox tests listed above pass against Compose GreenMail.
- [ ] Both `test-compile` runs succeed.
