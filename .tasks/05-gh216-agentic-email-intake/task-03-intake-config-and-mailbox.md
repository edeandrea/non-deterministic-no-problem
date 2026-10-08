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
  - **Import the platform's `${quarkus.platform.group-id}:quarkus-flow-bom:${quarkus.platform.version}`** (`type pom`,
    `scope import`) in `<dependencyManagement>`, after the Quarkus platform and quarkus-langchain4j BOMs. No new
    version property. Then add `quarkus-flow`, `quarkus-flow-langchain4j`, `quarkus-flow-jpa` and
    `quarkus-flow-opentelemetry` **without versions**. (The spike versioned each dependency separately instead.)
    - **Why the platform BOM, not `io.quarkiverse.flow:quarkus-flow-bom`** (checked 2026-10-08 by diffing effective
      POMs against today's `pom.xml`): platform 3.40.1's `quarkus-flow-bom` ships Flow **1.1.3**, the version the
      spikes verified. It adds 74 managed entries (Flow, serverless-workflow, cloudevents, jackson-jq) and changes
      none. The Quarkiverse 1.1.3 BOM's parent imports the **Quarkus 3.39.0** BOM and the serverless-workflow BOM,
      so it adds 119 entries, including 32 `io.vertx`, `io.fabric8`, wiremock and assertj. Our 3.40.1 platform BOM
      comes first, so none of today's versions change, but 3.39-era entries for anything the platform doesn't manage
      would come in. The platform BOM also keeps Flow aligned with Quarkus on every platform upgrade.
    - **Flow version:** whatever the platform ships. Re-check Maven Central for 1.2.0 final first (1.2.0.CR3 was the
      latest prerelease on 2026-10-08). A Flow version the platform doesn't ship yet means overriding the platform
      BOM; present that to the user before doing it. Whenever the Flow version changes, re-run the three reproducers
      in [edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) with
      `-Dquarkus.flow.version=<new>` (that's the reproducers' own property) and update the issue status in `PLAN.md`
      (#1056, #1057, #1058).
  - **`org.eclipse.angus:angus-mail` is already in `pom.xml` at `test` scope** (for the GreenMail helper). `ClaimsMailbox`
    is main code, so move it to compile scope; don't add a second entry. `quarkus-mailer` doesn't bring Jakarta Mail.
  - **`org.jsoup:jsoup`** without a version: the Quarkus BOM manages it (1.23.2, the latest release on 2026-10-08).
    It's already on the classpath through Easy RAG's Tika parsers; declare it directly because the mailbox uses it.
  - Confirm with `./mvnw dependency:tree` that quarkus-langchain4j stays on the project's version (Flow 1.1.3 was
    built against 1.13.3), and that the build still succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- **Flow config** (`application.yml`): `quarkus.application.name: parasol-app` is **already set** explicitly. Keep it,
  and add a comment saying it's Flow's `application_id`, part of the primary key of all three Flow tables, so a rename
  orphans every waiting run. **No schema strategy**: durable state across restarts isn't a requirement (user
  decision), and every profile already recreates the schema on boot.
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

- `pom.xml` (the platform `quarkus-flow-bom` import, agentic, the four Flow modules, angus-mail to compile scope, jsoup)
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

- [ ] The platform `quarkus-flow-bom` is imported (a final Flow release), `quarkus-langchain4j-agentic`, the four Flow modules and jsoup are in `pom.xml` without versions, angus-mail is compile scope, `./mvnw dependency:tree` shows quarkus-langchain4j still on 1.14.x, and the build succeeds alongside `quarkus-langchain4j-chat-scopes-websocket`.
- [ ] `quarkus.application.name` stays pinned, with a comment saying it's Flow's `application_id`; there is no agentic scope store, registrar, allowlist or self-test.
- [ ] `IntakeConfig`, `ClaimsMailbox` (including find-by-`Message-ID`) and `InboundEmail` exist, and the `claim-intake` model is configured for every provider/profile, with explicit `temperature` and `top-p` on the OpenAI-client configs.
- [ ] All the mailbox tests listed above pass against Compose GreenMail.
- [ ] Both `test-compile` runs succeed.
