# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**"Non-Deterministic? No Problem!"** is a demo application (Parasol Insurance) that shows how to
test and continuously evaluate non-deterministic AI systems.

It is a **single Quarkus application** (`org.parasol:parasol-app`, Java 25, Quarkus 3.40.1.1) with a React/PatternFly
frontend served via Quinoa. There are **no sub-modules** — one `pom.xml` at the root.

The Java source is split into two top-level packages representing two distinct concerns:

| Package | Concern |
|---|---|
| `org.parasol` | The insurance claims business application (claims REST API, AI chat bot, email notification, guardrails) |
| `ai.scoring` | The reusable AI-quality layer (Langfuse integration, session scoring, drift detection) |

`org.parasol` is organized **by domain first, then by layer** inside each domain:

| Package | Contents |
|---|---|
| `org.parasol.claim.model` | `Claim` (Panache entity, table `claims`), `ClaimImage` (table `claim_images`), `ClaimCorrespondence` (table `claim_correspondence`: every inbound email the intake accepted, by unique `Message-ID`), `ClaimImageKind`, `ClaimImageContentType`, `ClaimCategory`, `IntakeClaimStatus` (the intake's status labels; `isIntakeOpen` is true for `Pending Review` / `Pending Information`), claim-number generation, the domain exceptions (`ClaimNotFoundException`, `ClaimImageNotFoundException`, `UnknownClaimCategoryException`, `UnsupportedClaimImageContentTypeException`) |
| `org.parasol.claim.rest` | `ClaimResource` (`/api/db/claims`), `ClaimImageResource` (`/api/db/claims/{id}/images`), the `ClaimDetails` / `ClaimImageMetadata` DTOs + `ClaimMapper` (MapStruct), `ClaimExceptionMappings` (Problem Details) |
| `org.parasol.claim.seed` | `ClaimImageSeeder` (attaches the sample images to the six seeded claims) |
| `org.parasol.chat.ai` | `ClaimService` (the chat-bot AI service) |
| `org.parasol.chat.model` | `ClaimBotQuery`, `ClaimBotQueryResponse` |
| `org.parasol.notification.service` | `NotificationService` (the `updateClaimStatus` `@Tool`) |
| `org.parasol.notification.ai` | `GenerateEmailService` |
| `org.parasol.notification.model` | `Email`, `ClaimInfo` |
| `org.parasol.notification.guardrail` | The four email output guardrails, their `GenerateEmailOutputGuardrail` base, `PolitenessService`, `StringUtils` |
| `org.parasol.intake` | The email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216), being built). Only what every sub-package shares: `MissingItem` |
| `org.parasol.intake.config` | `IntakeConfig` (`parasol.intake.*`) |
| `org.parasol.intake.mailbox` | `ClaimsMailbox` (the `claims@parasol.com` IMAP mailbox: list, find by `Message-ID`, move between `MailFolder`s, `moveOldest`), `ClaimsInboxWatcher` (the IMAP IDLE watcher, a Mutiny pipeline on a virtual thread (`@VirtualThreads` executor): scans the INBOX, moves each email to `processing`, fires `EmailReceived`; `ClaimIntakeDispatcher` observes it), `InboxIdle` (its own IMAP session for IDLE), `InboundEmail` and its attachment records, `MailLoopGuard` (auto-replies and mail from the claims address), the package-private MIME parser, HTML-to-text and quoted-text helpers |
| `org.parasol.intake.dispatch` | What happens to an inbound email before its run: `ClaimIntakeDispatcher` (`submit(email)`: loop/duplicate/`Message-ID` checks, then a per-sender queue of immutable `SenderState`s; also the Flow status listener that drives the queue), `ClaimMatcher`, `CorrespondenceRecorder` and the package-private `ClaimReference` |
| `org.parasol.intake.failure` | The failure path: `IntakeFailureHandler`, the `IntakeFailureListener` (`onWorkflowFailed`) and `ProcessingProblemReplySender` |
| `org.parasol.intake.reply` | The intake's replies: `IntakeTemplates` (the fixed Qute mail templates, under `templates/IntakeTemplates/`), `IntakeReplySender` (blocking; subject prefix, `ThreadingHeaders` headers and `Auto-Submitted`; overloads with and without a claim number, no `Optional` parameters) and `CustomerName` (who a reply greets) |
| `org.parasol.intake.model` | The intake's records that Flow persists and later steps read: `IncidentDetails`, `ClaimExtraction` (JSON-safe: ISO date/time strings, no `Optional`), the sealed `IntakeOutcome`, `MatchedClaim` (`none()` rather than `null`), `EmailType`, `ClaimResolver`'s `PendingClaim` / `ClaimResolution`, and the `ClaimIntakeState` entity (the intake's bookkeeping about a claim, kept off `Claim`) |
| `org.parasol.intake.workflow` | `ClaimIntakeFlow` (the whole intake as one quarkus-flow workflow, `claim-intake`; the only place with side effects), the JSON-safe `IntakeRun` (input: ids only) and `IntakeState` (threaded through every step), the `EmailRequeued` event, the `AgentStep` and `ClaimMatchingSteps` steps and `CorrespondenceText` |
| `org.parasol.intake.workflow.policy` | `PolicyCheckStep` and its package-private `SameCustomerRule` (same customer) and `PolicyNumbers` (generated `AC-` numbers) |
| `org.parasol.intake.workflow.outcome` | `ApplyOutcomeStep` (persists a new claim or a merged reply) and its helpers `ClaimFields` and the package-private `InceptionDates` |
| `org.parasol.intake.workflow.review` | `SupersedeStep`, `ReviewDecisionSteps` (the review wait's decision steps and its match check), `AfterDecision` (what the run does next: `END` or `WAIT_AGAIN`), and `ReviewRecoverer` (hands a faulted run's review to a new run) |
| `org.parasol.intake.workflow.mail` | `IntakeEmailReader` (re-reads a run's email from the processing folder), `ReplySteps`, `FileEmailStep` |
| `org.parasol.intake.dev` | `DevClaimEmailSeeder`: the bean exists only with the build property `parasol.dev-seed.enabled=true` (`%dev`, and the two Ollama Maven profiles' `quarkus-maven-plugin` `systemProperties`, since they run dev as `prod`, so `@IfBuildProfile("dev")` wouldn't match), and it acts in `LaunchMode.DEVELOPMENT` only, mails two sample claims to the claims address at startup (one complete, one with no date or place) so the real intake produces a `Pending Review` and a `Pending Information` claim to look at. Needs the same model as any intake email; GreenMail is in memory, so it re-sends on every start |
| `org.parasol.intake.review` | The reviewer's side of the wait: `ReviewOutcome`, `ReviewDecision`, the `ReviewDecided` event data, `ReviewedFields` (the values the reviewer confirmed or corrected, carried in the decision and set by the decision step), `ReviewDecisionPublisher` (publishes the CloudEvent the waiting run listens for), `ClaimReviewService` (checks the review is open at the reviewer's version and the reviewed values fit the claim, claims it under the claim's optimistic lock, confirms the run is waiting, then publishes), `ClaimSentimentReassessor` (assesses the claimant's sentiment again for a reviewer) and the package-private `ReviewGuard` (the open-review check both start with), plus its domain exceptions (`ReviewNotOpenException`, `ReviewLockLostException`, `ReviewRunNotFoundException`, `ReviewRunNotWaitingException`, `ReviewedFieldsRejectedException`, `SentimentAssessmentFailedException`) |
| `org.parasol.intake.review.rest` | `ClaimReviewResource` (`GET /api/db/claims/{id}/review`, `POST /api/db/claims/{id}/review-decision`, `POST /api/db/claims/{id}/sentiment`), the `ClaimReviewState` / `ReviewDecisionRequest` (with its nested `Fields`) / `SentimentAssessmentRequest` / `SentimentAssessment` DTOs (the decision's outcome/items/fields pairing enforced by the package-private `@ValidReviewDecision` Bean Validation constraint) and `ReviewExceptionMappings` (Problem Details) |
| `org.parasol.intake.agent` | The root `ClaimsMailboxAgent` (`@SequenceAgent`: classifier → `EmailRouter`), the `@ConditionalAgent` `EmailRouter` and its package-private `EmailRoute` rule (matched claim first, then the label) |
| `org.parasol.intake.agent.extraction` | Everything behind `ClaimExtractionWorkflow` (`@ParallelAgent` over `ClaimSummaryAgent`, `ClaimSentimentAgent` and `IncidentDetailsAgent`): its package-private output guardrails and `IsoValues`, plus `ClaimExtractionRules.findMissingItems` |
| `org.parasol.intake.agent.triage` | `EmailClassifierAgent`, `ClaimFollowUpAgent` and its read-only `ClaimStatusTools`, the non-LLM `UnmatchedEmailAgent`, and `ClaimResolver` (a plain AI service, outside the agent topology) |

Dependencies point one way: `chat` → `notification` → `claim`, and `intake` → `claim`. New features get their own
domain package with the same kind of layer sub-packages. Test packages mirror main, plus the test-only `org.parasol.ui` for Playwright,
`org.parasol.testing` for the shared test profiles, and `org.parasol.testing.mail` for the GreenMail helper.

Supporting docs:
- `README.md` — build/run instructions, Ollama profiles, Langfuse integration notes
- `langfuse-evaluation.md` — **the** design document: the three-tier evaluation strategy, Langfuse
  platform/language gaps, and the workarounds implemented here. Read this before touching anything
  under `ai.scoring.langfuse`.
- `docs/*.puml` — three PlantUML diagrams: `application-flow.puml` (the business flow —
  chat → tool → email), `continuous-scoring-architecture.puml` (single-container component view of
  the evaluation layer) and `continuous-scoring-sequence.puml` (the tier-2 session-scoring
  sequence).
- `docs/design/email-claim-intake.md` — the **proposed** design for the email claim intake ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216)):
  workflow, claim states and agent topology, with three diagrams (`docs/design/claim-intake-*.puml`).
  None of it is implemented yet; don't describe it as existing code.
- `images/arch.png` — the hand-drawn overview of the business flow, framed as "Code I write" vs
  "Is this code?", embedded at the top of README.md's Architecture section with
  `docs/application-flow.png` below it as the detailed complement. It is a **source-less raster**
  (no `.excalidraw`/`.drawio` original) that has already been pixel-edited — a white rectangle
  painted over a now-removed "Input Guardrails" box describing a component that does not exist (all
  seven guardrails here are output-side). Changing it means pixel editing or a full redraw, not
  editing a source file, and this machine has no Pillow, numpy or ImageMagick — the last edit
  needed a hand-rolled Python PNG codec.

## Commands

All Maven commands run from the repository root via the wrapper.

```bash
# Dev mode (OpenAI, default) — requires OPENAI_API_KEY (and COHERE_API_KEY for judge/sentiment)
./mvnw quarkus:dev

# Dev mode against a local Ollama
./mvnw -Pollama quarkus:dev

# Dev mode against Ollama via its OpenAI-compatible endpoint
./mvnw -Pollama-openai quarkus:dev

# Unit tests
./mvnw test

# A single test class
./mvnw test -Dtest=PolitenessOutputGuardrailTests

# Full verify (unit + integration tests)
./mvnw verify

# Exercise the drift-detection tests (otherwise skipped — see Testing below).
# Needs a reachable Langfuse with populated datasets.
./mvnw verify -Dquarkus.test.profile=drift

# Build, skipping tests
./mvnw package -DskipTests

# Run the built app outside dev mode
java -Dquarkus.profile=ollama,prod -jar target/quarkus-app/quarkus-run.jar
```

**Every dev-mode run starts the mail stack** from `compose-devservices.yml` (Compose Dev Services):
GreenMail (SMTP/IMAP/REST API on random host ports) and the Roundcube webmail on
http://localhost:8000 (log in as any address, any password; README "Reading the emails" lists the seeded claimants'
mailboxes and how to trigger a status email). That holds under the default, `-Pollama`
and `-Pollama-openai` profiles alike. Dev Services follow the dev *launch mode*, not the config profile,
and the Ollama Maven profiles run `quarkus:dev` as `<ai>,prod`, so `quarkus.compose.devservices.profiles:
webmail` (which enables Roundcube) and `quarkus.mailer.mock: false` / `host: localhost` sit at root level
in `application.yml` rather than in `%dev`. Tests get GreenMail only: `%test` blanks the Compose profile, and
surefire/failsafe also set `quarkus.compose.devservices.profiles=none`, because a test whose config profile doesn't
include `test` (`DriftTestProfile` returns `drift`, `-Dquarkus.test.profile=drift`) would otherwise start Roundcube on
port 8000.
GreenMail keeps mail in memory, so restarting dev mode (or a test run) empties every mailbox.

### Diagrams

```bash
# Re-render every docs/*.puml and docs/design/*.puml to a sibling PNG (pinned PlantUML, needs graphviz `dot`)
./docs/render-diagrams.sh
```

### Frontend (from `src/main/webui/`)

```bash
npm test        # Jest
npm run build   # production build into dist/
```

Quinoa builds the frontend as part of the Maven build — you rarely need to run npm directly.

The Jest suite also runs inside the Maven build. The Playwright test classes use
`@TestProfile(UiTestProfile.class)`, which carries Quinoa's `EnableAndRunTests` overrides (`quarkus.quinoa.run-tests=true`), so Quinoa runs
`npm test` before building the UI, and a failing Jest test fails the `@QuarkusTest` boot. Jest needs the
`src/main/webui/__mocks__/` stubs for CSS and asset imports, and maps deep
`@patternfly/react-icons/dist/esm/...` imports to the CommonJS `dist/js/...` build. `jest.config.js` doesn't
transform `node_modules`, so Jest can't load the ES-module build. `app.test.tsx` renders the app under jsdom with
no backend, so it stubs `axios.get` with a promise that never settles: no request, and no state update after the render
(outside `act`). The committed snapshot therefore always shows an empty claims table; claim rendering is covered by the
Playwright tests, not Jest. **A test that logs fails:** `jest.setup.ts` (`setupFilesAfterEnv`) records every
`console.error`/`console.warn` and fails the test in `afterEach`, so a green run prints nothing (#269: React `act(...)`
warnings and jsdom XHR errors used to fill the Quinoa output). A log that arrives after its test has finished is charged
to whichever test is running, or missed after the last one. Fix the cause (wrap in `act`, stub the request), don't mute
the console.

### CI

`.github/workflows/simple-build-test.yml` runs `./mvnw -B clean verify` on Java 25 across the
`ollama` and `ollama-openai` profiles. CI has no real OpenAI/Cohere/Gemini credentials, so **any new
test must pass under the Ollama profiles**. It runs on pushes to `main` and on pull requests into `main` or into
`gh216-email-intake`, the feature branch the #216 task PRs merge into. Remove that branch from the trigger once it
merges.

**CI supplies Ollama as a GitHub service container,** `ollama/ollama` on `localhost:11434`, with
`granite4:micro` and `snowflake-arctic-embed` pulled in a step before the build. Both profiles reach
it: `%ollama-openai` points there explicitly, and `%ollama` falls back to the Ollama extension's
default `base-url` because the workflow passes
`-Dquarkus.langchain4j.ollama.devservices.enabled=false`. That flag is the **only** CI-specific
config — everything else, including the model id, is in `application.yml`, so local runs and CI
exercise the same thing.

Don't re-enable the Dev Service in CI. It recreates its container once per augmentation (once per
test profile in a full `verify`) and shuts the previous one down, so an app whose build-time config captured an
earlier container's ephemeral port boots against a dead address. That was a `Connection refused` at
startup for every `@QuarkusTest` without a Dev-Service-free profile.

## Architecture

### Business application — `org.parasol`

**AI services** (Quarkus LangChain4j `@RegisterAiService`):

- `ClaimService` — the chat bot. `@ChatScoped` (session-scoped conversation), exposed as a chat
  route (`@ChatRoute("chat")` / `@DefaultChatRoute`) over the websocket chat-routes endpoint
  `/_chat/routes` provided by `quarkus-langchain4j-chat-scopes-websocket`. Uses RAG over
  `src/main/resources/policies/policy-info.pdf` (Easy RAG, embeddings reused via
  `easy-rag-embeddings.json`) and a `@ToolBox(NotificationService.class)`. Annotated with
  `@OutputGuardrails(DriftDetectionOutputGuardrail.class)`, which is a no-op unless
  `interaction-mode` is `DRIFT_DETECTION`.
- `GenerateEmailService` — generates a `{subject, body}` `Email` record; four output guardrails.
- `PolitenessService` — AI-backed politeness check used by `PolitenessOutputGuardrail`.

**Output guardrails** on `GenerateEmailService` (all extend `GenerateEmailOutputGuardrail`, itself a
`dev.langchain4j.guardrails.JsonExtractorOutputGuardrail<Email>`):
`EmailContainsRequiredInformationOutputGuardrail`, `EmailStartsAppropriatelyOutputGuardrail`,
`EmailEndsAppropriatelyOutputGuardrail`, `PolitenessOutputGuardrail`.
`GenerateEmailService.EMAIL_ENDING` is a Qute template: the department name and phone come from
`parasol.claims-department.*` through `{config:['…']}` (bracket form; the dotted form fails). The ending guardrail
renders it once, at construction, with the same engine that renders the prompt, and checks against that.

**None of them may report success by rewriting the output** — that was [#228](https://github.com/edeandrea/non-deterministic-no-problem/issues/228).
`JsonExtractorOutputGuardrail.validate` returns `successWith(json, value)` unconditionally, which is a
rewrite, and `OutputGuardrailExecutor.handleFatalResult` refuses any retry or reprompt once something
earlier in the chain has rewritten (`"Retry or reprompt is not allowed after a rewritten output"`). With
four of them chained, only the *first* could ever reprompt. So the base deliberately does **not** use the
inherited `validate`: it exposes `extractEmail(AiMessage)` (parse, no rewrite) and `invalidJson(AiMessage)`,
and each guardrail returns `success()` or `reprompt(...)`. `super.validate(...)` is still inherited and
still rewrites — don't call it. `EmailOutputGuardrailChainTests` is the guard; it drives the real
`OutputGuardrailExecutor`, and every `guardrailSuccess` unit test asserts plain `Result.SUCCESS` rather
than `SUCCESS_WITH_RESULT`.

Nothing here needs the rewrite: Quarkus and LangChain4j **already** extract JSON from surrounding prose on
the AI service return path (`PojoOutputParser` → `JsonParsingUtils.extractAndParseJson`, plus
`QuarkusJsonCodecFactory`'s own first-`{`-to-last-`}` regex). What `JsonExtractorOutputGuardrail` still
buys here is its deliberately strict plain `ObjectMapper` and a reprompt — rather than an escaping
`OutputParsingException` — when the output can't be parsed.

The project has **seven guardrail classes in total and they are all output guardrails** — the four
email ones above plus `DriftDetectionOutputGuardrail`, `SessionSentimentGuardrail` and
`EvaluatorResultOutputGuardrail`. There are **zero (LLM) input guardrails**; the one *tool* input guardrail is below.

**The chat bot can't act on a claim still in intake,** enforced twice. `IntakeCompleteToolInputGuardrail`
(`@ToolInputGuardrails` on `updateClaimStatus`, quarkus-langchain4j's tool input guardrail) reads `claimId` from the tool call's
JSON arguments and refuses before the tool runs, returning the reason to the model. It fails closed: a call whose `claimId` is missing or unreadable is refused too, while an
unknown claim goes through (the tool answers "no claim found"). It is a separate read, so `updateClaimStatus` repeats the check as the authoritative one,
`IntakeClaimStatus.isIntakeOpen` inside the same transaction that would change the status: for a `Pending Review` or `Pending Information` claim it changes nothing,
sends no email and tells the model why (`NotificationService.INTAKE_NOT_COMPLETE`). `IntakeClaimStatus` lives in
`org.parasol.claim.model` rather than `intake` so `notification` doesn't depend on `intake`. The UI's `StatusLabel` shows the
same reason as a tooltip on those two statuses (list and detail page).

**Email flow:** the chat bot calls the `NotificationService.updateClaimStatus` tool → updates the
`Claim` Panache entity → `GenerateEmailService` produces the email → sent via Quarkus Mailer
(GreenMail from `compose-devservices.yml` in dev/test; readable in Roundcube in dev mode). On OpenShift both run
from `src/main/kubernetes/dependencies.yml` (see OpenShift deployment).

**REST:** the REST layer never serializes entities. Resources hold no queries; they call the Active Record methods
and map to DTO records with `ClaimMapper`.
- `ClaimResource`: `GET /api/db/claims`, `GET /api/db/claims/{id}` return `ClaimDetails`. A missing claim is a
  Problem Details `404` (the resource throws `ClaimNotFoundException` when `findByIdOptional` is empty); before #214 it
  was an empty `204`.
  The UI's claim detail page shows "Claim not found" for it (`ClaimImagesPageTests.unknownClaimSaysTheClaimDoesNotExist`).
- `ClaimImageResource`: `GET /api/db/claims/{id}/images` (`ClaimImageMetadata` list, oldest first) and
  `GET /api/db/claims/{id}/images/{imageId}` (the bytes, with the stored content type and
  `X-Content-Type-Options: nosniff`).
- **The DTOs are the API contract.** `ClaimDetails` lists every field the UI reads, in snake_case
  (`@JsonNaming` on the record; `category` is the label via `ClaimCategory`'s `@JsonValue`; nulls are omitted by the
  global `serialization-inclusion: non-empty`). A new `Claim` column stays out of the API until it's added to
  the DTO; `ClaimResourceTests.jsonFieldNames` pins the exact set of JSON keys. **The intake's bookkeeping isn't on
  `Claim` at all** (see *The intake's state* below), so fetching a claim neither returns nor loads it;
  `ClaimRetrievalHasNoIntakeBookkeepingTests` measures that with Hibernate statistics.
- **Errors:** domain exceptions are mapped once, by `ClaimExceptionMappings` (Quarkus `@ServerExceptionMapper`), to RFC
  9457 Problem Details (`application/problem+json`, `type: about:blank`, `instance` = request path), through the shared
  `org.parasol.problem.ProblemDetail` record (`MEDIA_TYPE` constant and `of(status, detail, uriInfo)` factory). RFC 9457
  is a named standard in its own right, not generic REST plumbing, so it gets its own package rather than a domain's or a
  top-level technical-layer bucket (which would violate the domain-first package structure).
  `ClaimNotFoundException` and `ClaimImageNotFoundException` are `404`. An image that exists but belongs to another
  claim is the same `404` as an unknown image.
- **The email intake's review endpoints** (`org.parasol.intake.review.rest.ClaimReviewResource`, task 10): `GET
  /api/db/claims/{id}/review` returns a `ClaimReviewState` (the claim's status, the `ClaimIntakeState` version, whether a
  review is open, its requested items and the full `MissingItem` list for the UI's checklist) — its own read rather than
  widening `ClaimDetails`, since the intake's bookkeeping stays off the claim DTO. `POST
  /api/db/claims/{id}/review-decision` takes a `ReviewDecisionRequest` (`outcome`, `missing_items`, `fields`, `version`),
  claims the review under the claim's optimistic lock (`ClaimReviewService`), confirms the run is `WAITING` via `@Inject
  PersistenceInstanceReader`, publishes the decision and returns `202 Accepted` with no body — the UI re-fetches the claim
  itself, since the call returns once the decision is published, not once the workflow has applied it. Errors: `404` for
  an unknown claim or a vanished run, `409` for a claim not open for review, already being decided, a run that isn't
  waiting, or a lost optimistic lock (a stale `version`, or a reply that superseded the review first); each maps through
  `ReviewExceptionMappings`, reusing the shared `org.parasol.problem.ProblemDetail`. A Bean Validation failure is a `400`
  whose `detail` names each violation as the request does (snake_case, like the DTOs' `@JsonNaming`, e.g.
  `fields.incident_date must be a date in the past or in the present`): the resource method and its parameter are left
  off the path, and a class-level violation (`@ValidReviewDecision`) is just its message.
  - **The reviewer edits the claim, so nothing is published before every check passes.** `fields` (`description`, which
    is the claim's `summary`, `incident_date`, `incident_time`, `location`, `category`) is the whole form, not a patch:
    `@ValidReviewDecision` requires every `MissingItem` to have a value **or** be in `missing_items`, never both and never
    neither (so `READY` carries every value), `NEEDS_INFORMATION` to ask for at least one item and `READY` for none, and an
    `incident_time` only with an `incident_date`. Field constraints: `incident_date` `@PastOrPresent`, `description` ≤ 5000
    characters (the `summary` column), `location` ≤ 1000, blank text counts as no value, and `version` is a required `Long`
    (a primitive would turn an omitted version into `0`, which a fresh `ClaimIntakeState` can match). All of these are
    `400`, as is a body Jackson can't read (malformed JSON, a bad date, an unknown category): Quarkus REST wraps most of
    those in a bare `WebApplicationException(400)`, so `ClaimReviewResource` carries its own `@ServerExceptionMapper`s
    for them, scoped to its endpoints rather than app-wide. Then, in the claim transaction, the order is: the claim exists
    (`404`), it's `Pending Review` with its review open (`409`), it's at the reviewer's `version` (`409`), and the reviewed
    values fit the claim (`422`: an incident date before the policy's `inceptionDate`). Jakarta REST 4.0's `Status` has no
    422, so `ProblemDetail.of` takes a `StatusType`.
  - **The reviewed values ride in the decision** (`ReviewDecision.fields`, a `ReviewedFields`, JSON-safe through Flow's
    persistence) and `ReviewDecisionSteps` sets them in the same transaction as the status change, after re-checking the
    claim is still `Pending Review` and being decided by that run. A decision that faults before its commit leaves none of
    them set. Asked fields keep their current value until the customer answers.
  - **`POST /api/db/claims/{id}/sentiment`** (`{ "version": … }` → `200 { "sentiment": … }`) runs
    `ClaimSentimentAgent` again over the claim's stored body (the whole correspondence, capped at
    `parasol.intake.limits.max-body-characters`) and stores the result. Only the sentiment changes, the version isn't
    bumped, and the run isn't touched. It checks the open review and the version (`404`/`409`) before the model call, which
    runs outside any transaction, and again under a `PESSIMISTIC_WRITE` lock on the `ClaimIntakeState` row before it
    stores, so a reply or decision that lands during the call wins (`409`, result discarded). A failed or blank answer is
    `502`; the claim keeps its sentiment. This resource depends only on
  `org.parasol.claim.model` (the established `intake → claim` direction) — never on `org.parasol.claim.rest`'s DTOs, so a
  `ClaimDetails` field addition can't silently go stale here the way a hand-rolled copy would. `ProblemDetail` itself
  moved out of `org.parasol.claim.rest` (where it first lived, nested in `ClaimExceptionMappings`) into the neutral
  `org.parasol.problem` once this resource needed it too — it carries no claim-specific fields, so leaving it under one
  domain's package would have been the same kind of accidental coupling, just one step removed.
  - **A documented, deliberately-not-worked-around race (F2b):** `ReviewDecisionPublisher`'s Javadoc and
    `serverlessworkflow-impl-core`'s `ListenExecutor.internalExecute` show a run's `WAITING` status can become visible a
    couple of statements before its `listen` task actually registers a consumer. `ClaimReviewService` doesn't retry or
    block for this: `InMemoryEvents.publish` always hops to an executor before it reads the registered consumer, and that
    hop structurally outlasts two sequential method calls on the thread that's already running, so any real caller doing
    actual work in between (a DB transaction, JSON (de)serialization, an HTTP round trip) can't observe the gap in
    practice. Only a synthetic caller that calls `publish` back-to-back with nothing else could — which is why
    `ReviewDecisionPublisher`'s republish-until-woken loop stays a caller concern (`IntakeScenario.publishUntilWoken`),
    not something this service retries for.

**The `Claim` entity** (`org.parasol.claim.model`, table `claims`; serialized only through `ClaimDetails`):
- **`id`** is the Panache numeric primary key (`claims_seq`). REST paths, UI routes, `ClaimBotQuery.claimId`
  and the `NotificationService` tool all use it.
- **`claimNumber`** is a separate `@NaturalId` (`CLM` + 8 digits, unique, not updatable). PostgreSQL
  generates it on insert:
  - **The column default** is `@ColumnDefault(ClaimNumberGenerator.COLUMN_DEFAULT)`, which is
    `'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')`.
  - **`@ClaimNumber`** is a `@ValueGenerationType` meta-annotation for `ClaimNumberGenerator`, which is an
    `OnExecutionGenerator` + `ExportableProducer`. It leaves the column out of the `INSERT`, reads the value
    back with `insert … returning claim_number`, and registers `claim_number_seq` (`start with 1000000
    increment by 1009 maxvalue 99999999`) with Hibernate's schema management.
  - **The first numbers** are `CLM01000000`, `CLM01001009`, `CLM01002018`, …; capacity is about 98,000 claims.
  - **Lookup:** `Claim.findByClaimNumber(String)` returns `Optional<Claim>` via `session.find(..., KeyType.NATURAL)`.
- **`category`** is a `ClaimCategory` enum (`SINGLE_VEHICLE`, `MULTIPLE_VEHICLE`, `THEFT`, `OTHER`).
  - **Database:** stored as the constant name (`@Enumerated(STRING)`, so Hibernate adds a CHECK constraint).
  - **JSON:** written as the display label ("Single vehicle", …, "Other") via `@JsonValue`.
  - **Input:** `ClaimCategory.fromValue` (`@JsonCreator`) accepts a label or a constant name, ignoring case,
    and throws `UnknownClaimCategoryException` otherwise.
- **`incidentDate`** (`LocalDate`) and **`incidentTime`** (`LocalTime`, nullable) replaced the old free-text
  `time` / `claim_time`. `ZonedDateTime` was rejected because claim emails rarely state a time zone.
  The UI formats them with `src/main/webui/src/app/utils/formatIncident.ts`, whose `toLocalIsoDate` also gives the
  review form's latest selectable date in the user's time zone (`toISOString()` is UTC, already tomorrow in the evening
  west of it, which the backend's `@PastOrPresent` would reject).
- **Sizes:** `subject`, `body` and `location` are unbounded `text` (`Length.LONG32`; not `@Lob`, which
  maps to `oid` on PostgreSQL). `summary` and `sentiment` stay `length = 5000`. `status` is free text.

**Seed data:** `src/main/resources/import.sql` seeds six claims (ids 1–6) and ends with
`ALTER SEQUENCE claims_seq RESTART WITH 7`.
- **It omits `claim_number`,** so the column default numbers the rows in insert order (`CLM01000000` … `CLM01005045`).
- **It uses the enum constant names** for `category`.
- Dev/test load it by default; `%prod` / `%openshift` load it via `sql-load-script` under drop-and-create.
- `ClaimImageSeeder` attaches the 12 JPEGs in `src/main/resources/seed/claim-images/` to the six sample claims,
  matched by their explicit ids (1–6), not their generated claim numbers. See Gotchas.

**The intake's state** (`ClaimIntakeState`, package `org.parasol.intake.model`, table `claim_intake_state`): the plumbing the email
intake keeps about a claim it created or updated. It is **not part of the claim domain**: `Claim` has no reference to it
and no intake columns (not even a version), so no claim fetch can load it, and `ClaimMapper`/`ClaimDetails` never see it.
- **Shape:** a unidirectional `@OneToOne(fetch = LAZY) @MapsId` from the state to the claim, so the row shares the claim's
  id and goes with it (`@OnDelete(CASCADE)`). A seeded claim has no row until the intake first touches it. Use
  `ClaimIntakeState.findForClaim` (empty if none), `forClaim` (find or create) and `createFor(claim, conversationId)`
  (the conversation id is `updatable = false`, so it must be set before the insert).
- **Contents:** `reviewRunId` (the run waiting for the review), `decidingRunId` (task 10 moves it there when it claims the
  review), `intakeConversationId`, `policyNumberGenerated`, `requestedItems` (what the customer was asked for; table
  `claim_requested_items`, stored as constant names) and the `@Version`.
- **The lock is on this row.** A reply that supersedes a review and a reviewer's decision both write it, so the race is
  serialized there. Someone editing a claim's own details is a different concern and would need its own lock on `Claim`.
- **`ClaimCorrespondence`** (`org.parasol.claim.model`) records every inbound email the intake accepts: unique `Message-ID`
  (the duplicate check is insert-and-catch), a nullable claim set once the email creates or matches one, direction
  (`INBOUND` only: the mailer generates the replies' ids), sent date, sender and the stripped text. Reply headers are
  matched against it (`findClaimByMessageIds`).

**Claim images** (`ClaimImage`, table `claim_images`):
- **Persistence is Active Record, like `Claim`:** `listForClaim(claimId)` (throws `ClaimNotFoundException`),
  `findForClaim(claimId, imageId)` (returns `Optional`; always matches claim **and** image id, so another claim's
  image is empty; `ClaimImageResource` throws `ClaimImageNotFoundException` for the 404),
  `hasImage(claim, kind, fileName)` and `store(claim, kind, fileName, contentType, data)`. #216's intake stores
  attachments through `store`. Image ids are table-wide `PanacheEntity` ids.
- **`kind`** is `ORIGINAL` (customer photo) or `PROCESSED` (annotated damage image). New claims never get processed
  images.
- **`contentType` is an allow-list,** the `ClaimImageContentType` enum (JPEG, PNG, GIF, WebP; stored as the constant name,
  JSON and `Content-Type` use the media type). Resolve an outside media type with `ClaimImageContentType.find` /
  `fromMediaType` (accepts aliases like `image/jpg` and ignores parameters). Never widen it to `image/*`: the endpoint
  serves the bytes back on the app's own origin, so `image/svg+xml` or `text/html` would be stored XSS.
- **`data`** is `byte[]` with `@Column(length = Length.LONG32)`, which is `bytea` on PostgreSQL (`@Lob` would be an
  `oid` large object; `ClaimImageTests` checks the column type). It's `@Basic(fetch = LAZY)` (Hibernate bytecode
  enhancement), so listing images never reads the bytes.
- **Validation:** `@NotNull` / `@NotBlank` / `@NotEmpty` fail the flush with a `ConstraintViolationException` (empty
  data, blank file name).
- **The `claim_id` foreign key is `on delete cascade`** (`@OnDelete`) and indexed, so deleting a claim deletes its
  images; tests only need `Claim.deleteById`.
- **Image URLs are root-relative** (`/api/db/claims/{id}/images/{imageId}`), built by `ClaimMapper` from the
  resource's `@Path`s. `%openshift` terminates TLS at the route and doesn't enable proxy forwarding, so a URL built
  from the request would come out as `http://`. The UI resolves it against `backend_api_url`'s origin.
- **`ClaimMapper`** is MapStruct (`mapstruct-processor` on the compiler's `annotationProcessorPaths`) with the
  `JAKARTA_CDI` component model, one mapper for the claim aggregate (`Claim` → `ClaimDetails`, `ClaimImage` →
  `ClaimImageMetadata`). `unmappedTargetPolicy = ERROR` fails the build if a DTO field has no source; unmapped entity
  fields are ignored on purpose. The image mapping only reads metadata fields, so it never triggers the lazy `data`
  load.

**Frontend:** React + TypeScript + PatternFly in `src/main/webui/src/app/`, SPA routing enabled.

### AI quality layer — `ai.scoring`

Configuration is driven by `ScoringConfig` (`quarkus.aiscoring.*`) and `LangfuseConfig`
(`quarkus.aiscoring.langfuse.*`), both `@ConfigMapping` interfaces.

`InteractionMode` (default `NORMAL`) selects whether tier 3 is armed:

- `NORMAL` — `DriftDetectionOutputGuardrail.validate` returns `success()` immediately.
- `DRIFT_DETECTION` — the guardrail actually evaluates and can fail the response.

Tiers 1 and 2 are independent of this switch; they are controlled by the
`quarkus.aiscoring.langfuse.evaluation.*` flags instead.

The three evaluation tiers (see `langfuse-evaluation.md` for the full rationale):

1. **Per-trace** — native Langfuse LLM-as-a-Judge. `LangfuseEvaluationInitializer` programmatically
   creates the Langfuse model, LLM connection, score config, evaluator, and evaluation rule on a
   `StartupEvent`, gated on `initialize-on-startup`. It uses the `LangfuseOperations` layer
   (`createIfAbsent` / `upsert` / `findByName`) from quarkus-langfuse, dropping to the raw
   `langfuse.api()` client only for the evaluator update call, which the operations layer does not
   cover. `createEvaluationRule` installs two `NONE_OF` filters on the rule: `environment` not in
   (`langfuse-llm-as-a-judge`, `llm-as-judge`) — which is what stops the judge from scoring its own
   output — and `type` not in (`SPAN`, `EVENT`), so only generations get scored.
2. **Session-level** — Langfuse has no session evaluation target, so this is custom code.
   `ConversationalBaggageHandler` (the chat adapter, `ai.scoring.conversation.chat`) observes
   `ChatScopeStarted/Activated/Deactivated/Ended`, makes the scope id the current conversation through
   `ConversationContext` (OTel baggage, `gen_ai.conversation.id`), and on session end fires a
   `ConversationEndedEvent` through `ConversationEvents.ended` (`fireAsync`; a failed observer is logged there).
   `ConversationEndedScorer` (`ai.scoring.evaluation`) is the one `@ObservesAsync` observer: it calls
   `SessionScoringService.scoreSession` on the async-observer worker thread, so the handler doesn't know scoring
   exists. Only the chat fires the event today.
   `LangfuseSessionScoringService` polls Langfuse until the session's observations are ingested
   (OTel flush + async ClickHouse ingest), reconstructs `ConversationExchange`s, asks
   `SessionSentimentService` for a `SessionSentiment` (that AI service carries
   `@OutputGuardrails(SessionSentimentGuardrail.class)` — an `@ApplicationScoped`
   `JsonExtractorOutputGuardrail<SessionSentiment>` in `ai.scoring.langfuse.session` that exists
   purely as deserialization insurance for models that wrap their JSON in prose), and posts the
   score back with
   `langfuse.scores().create(...)` — deliberately the synchronous tree, because the score must land
   before `scoreSession` ends the enclosing `ComputeSessionScore` span. The observation query is a
   typed `ObservationFilter` (`sessionId` + `fields("core,basic,io,metadata")`) handed to
   `async().observations().matching(filter).findAll()`; `findAll()` pages through *all* matching
   observations, so a session is no longer capped at one page. That call is wrapped in
   `Uni.createFrom().deferred(...)`, which is load-bearing rather than stylistic: it is the retried
   step of the polling pipeline and must re-query on every re-subscription, otherwise each retry
   replays the first (empty) response and the session silently goes unscored. It also records the
   exchanges as a Langfuse dataset, gated on `create-dataset-on-session-close`: dataset names are
   deduplicated and created sequentially through `async().datasets().createIfAbsent(...)` (not
   atomic, so concurrent creation of the same name would race it against itself), then the dataset
   items are created in parallel through `async().datasetItems().create(...)`.
3. **Drift detection** — `DriftDetectionOutputGuardrail` runs the quarkus-langchain4j evaluation
   framework (`Evaluation.withSamples(<datasetName>)`) and returns a `fatal` result carrying a
   `DriftDetectionException` when the score falls below `quarkus.aiscoring.threshold`.
   `DriftDetectionChatRouteExceptionHandler` turns that into a `DRIFT DETECTED!!!` chat-route error
   instead of a stack trace.

   Two pieces plug into that framework:
   - `LangfuseDatasetSampleLoader` (`SampleLoader<String>`) supplies the samples. It is **not**
     called directly — it is discovered via `ServiceLoader` through
     `src/main/resources/META-INF/services/io.quarkiverse.langchain4j.testing.evaluation.SampleLoader`,
     grabs `LangfuseOperations` via `CDI.current()`, checks the dataset exists with
     `datasets().findByName(...)` in `supports`, and reads the items with
     `datasetItems().matching(DatasetItemFilter...).streamAll()` — no hand-written page arithmetic.
     Two non-obvious constraints: `DatasetItemFilter` has no status criterion, so the
     `DatasetStatus.ACTIVE` filter necessarily stays client-side; and `streamAll()` is lazy, so the
     terminal `.toList()` has to run *inside* the `try` or the `LangfuseNotFoundException`-to-empty-list
     behavior silently stops working.
   - `Evaluator` (`EvaluationStrategy<String>`) scores them by delegating to the `EvaluatorAgent`
     AI service (the `judge` model), whose `isResponseCorrect` method carries
     `@OutputGuardrails(EvaluatorResultOutputGuardrail.class)` — an `@ApplicationScoped`
     `JsonExtractorOutputGuardrail<EvaluatorResult>` in `ai.scoring.langfuse.evaluation`, again just
     deserialization insurance rather than business validation. `Evaluator` is the only
     `EvaluationStrategy` bean in the project —
     despite `quarkus-langchain4j-testing-evaluation-semantic-similarity` being on the classpath,
     nothing currently wires up a semantic-similarity strategy.

**Dataset naming — the critical invariant.** The dataset name is the LangChain4j AI service span
name verbatim: `langchain4j.aiservices.<AiServiceClassName>.<methodName>`.

- **Write side:** `AiServiceDatasetSpanProcessor` (an OTel `SpanProcessor`) stamps
  `langfuse.dataset.name`, `ai.service.class`, and `ai.service.method` onto the AI service root span
  and cascades them to every descendant (tool executions, model generations).
- **Read side (tier 2):** `ConversationExchange` resolves the name with a five-step fallback —
  attributes on the observation → attributes on an ancestor → name of the nearest
  `langchain4j.aiservices.*` ancestor → trace name → the observation's own name. It also probes
  three different metadata shapes (`langfuse.dataset.name`, `attributes.langfuse.dataset.name`, and
  a nested `attributes` map) because Langfuse's OTLP ingestion has changed shape across releases.
- **Read side (tier 3):** `DriftDetectionOutputGuardrail` rebuilds the name from the LangChain4j
  `InvocationContext` (simple interface name + method name).

All sides share `AiServiceAttributes.AI_SERVICES_PREFIX` — **if you change the naming on one side,
change it on the others or drift detection silently finds no samples** (it returns `success` on
`SampleLoadException`, so a broken name looks like a pass, not a failure).

### Conversation core — `ai.scoring.conversation`

Which work belongs to which conversation, and what happens when one ends. Generic and framework-neutral, ready to
be extracted: the core depends only on the OTel API/SDK, CDI and MicroProfile Context Propagation (no Quarkus,
LangChain4j, chat-scope or Flow types). Frameworks feed it through adapters in sub-packages; consumers observe its
events and never call an adapter.

- **Core:** `ConversationContext` (`currentId`, `enter`, `callIn`, `runIn`; the id is OTel baggage under
  `CONVERSATION_ID_KEY` = `gen_ai.conversation.id`), `ConversationIdSpanProcessor` (copies the baggage entry onto every
  span at `onStart`, read from the parent context; the same value quarkus-langfuse's processor writes, so Tempo and the
  tests see it with Langfuse export off), `ConversationEndedEvent` and `ConversationEvents` (`fireAsync`, logging any
  observer failure).
- **Adapters:** `chat` (`ConversationalBaggageHandler`, chat scopes). Task 11 of #216 adds the Flow/agentic ones.
- **`quarkus.OtelContextLeakGuard`** — a **temporary** `ThreadContextProvider` (registered in
  `META-INF/services/org.eclipse.microprofile.context.spi.ThreadContextProvider`) working around quarkusio/quarkus#54354:
  Quarkus's OTel context-propagation provider leaves a task's context, and its conversation id, on a pool thread.
  **Delete it, and its services entry, once a Quarkus we're on ships quarkusio/quarkus#56805** (4.0, or the 3.40
  backport quarkusio/quarkus#57879); `OtelContextLeakGuardTests` passing without it is the signal. It's the one class
  touching a Quarkus type, which is why it has its own package.
- **Layering is enforced** by `ConversationLayeringTests` (reads the compiled classes' constant pools with
  `java.lang.classfile`): the core references no adapter or framework, adapters never reference each other.

### Models

Model names are configured in `src/main/resources/application.yml` and resolve per profile:

| Model name | Used by | Default (OpenAI profile) |
|---|---|---|
| `parasol-chat` | `ClaimService` | `gpt-5-mini` |
| `generate-email` | `GenerateEmailService` | `gpt-5-mini` |
| `politeness` | `PolitenessService` | `gpt-5-mini` |
| `session-sentiment` | `SessionSentimentService` | Cohere `command-r7b-12-2024` via its OpenAI-compatible endpoint |
| `judge` | `EvaluatorAgent` (drift detection) | Cohere `command-r7b-12-2024` via its OpenAI-compatible endpoint |
| `claim-intake` | The email intake agents (#216, being built): `ClaimSummaryAgent`, `ClaimSentimentAgent`, `IncidentDetailsAgent` | `gpt-5-mini` |

Note the Cohere models are reached through the **OpenAI** extension pointed at
`https://api.cohere.ai/compatibility/v1` with `COHERE_API_KEY` — there is no Cohere-specific
extension in the build.

Every base (non-profile) `quarkus.langchain4j.openai.<name>.chat-model` block sets `temperature` **and** `top-p`
explicitly. That is deliberate. Since quarkus-langchain4j 1.14 the OpenAI extension leaves both
out of the request unless they are configured, so the provider's own default applies. The
explicit values keep the pre-1.14 wire behavior (temperature `1.0` unless overridden, top-p `1.0`).
Don't delete the `top-p: 1` lines because they look redundant. The `%ollama-openai` overrides
inherit these values, because they only repoint `base-url` and `model-name`.

There are **two separate judges**, which is easy to confuse:
- The in-app `judge` model above, driving `EvaluatorAgent` → `Evaluator` for tier-3 drift detection.
- The Langfuse-side LLM-as-a-Judge evaluator for tier 1, which runs inside Langfuse using **Google
  Gemini** (`gemini-2.5-flash`, `LlmAdapter.GOOGLE_AI_STUDIO`) configured by
  `LangfuseEvaluationInitializer` from `LangfuseConfig.Evaluation.Gemini`.

Embeddings use the OpenAI embedding model by default (`quarkus.langchain4j.embedding-model`).
Because `reuse-embeddings` is enabled, the ingested vectors are cached in `easy-rag-embeddings.json`
(project root, gitignored) and reloaded on every restart. OpenAI (1536-dimension) and
`snowflake-arctic-embed` (1024-dimension) vectors are incompatible, so delete that file when
switching between the default and the Ollama profiles.

The two Ollama profiles are **not** equivalent:
- `%ollama` switches `parasol-chat`, `generate-email`, `politeness`, `claim-intake` and the embedding model to
  `provider: ollama` (`granite4:micro`, embeddings `snowflake-arctic-embed`) — **except
  `generate-email` (and `claim-intake`, which matches it), which use `qwen3:4b` with `model-options.think: false`**,
  because it is the
  only model measured to reproduce `EMAIL_ENDING` verbatim for
  `EmailEndsAppropriatelyOutputGuardrail`, and qwen3 reasons by default (~6k tokens per email
  versus ~140 with thinking off). Note `%ollama-openai` does **not** inherit this: all four of its chat
  `model-name`s interpolate from `quarkus.langchain4j.ollama.parasol-chat.chat-model.model-id`, not
  from their own, so changing `generate-email`'s id moves only the Ollama-native leg. That is
  deliberate — `model-options.think` has no equivalent on the OpenAI-compatible endpoint. It also
  stubs
  `quarkus.langchain4j.openai.api-key` plus the `session-sentiment` and `judge` API keys to
  `changeme`, and disables Langfuse session scoring + startup initialization. Note
  `session-sentiment` and `judge` keep `provider: openai` pointed at the Cohere-compatible
  endpoint — they are *not* moved to Ollama, they are just given a dummy key.
- `%ollama-openai` only repoints the OpenAI client's `base-url` at `http://localhost:11434/v1` for
  `parasol-chat`, `generate-email`, `politeness`, `claim-intake` and the embedding model. It stubs **no** API keys
  and does **not** disable session scoring or startup initialization.

### Configuration profiles

`src/main/resources/application.yml` is the single config source. Notable profiles:

| Profile | Purpose |
|---|---|
| `dev` | Only `datasource.dev-ui.allow-sql`. The LGTM, PostgreSQL and Langfuse dev services do run in dev mode, but they come from the extensions' own Dev Services defaults, not from this profile block. The GreenMail/Roundcube mail stack and `mailer.mock: false` are root-level config (see Commands) |
| `test` | Observability + Langfuse init/session scoring disabled; `compose.devservices.profiles` blanked so Roundcube doesn't start |
| `ollama` | Local Ollama via the Ollama extension; OpenAI/Cohere keys stubbed to `changeme`; Langfuse session scoring + startup init disabled |
| `ollama-openai` | Local Ollama via the OpenAI client (`base-url: http://localhost:11434/v1`); no key stubs, no `quarkus.aiscoring` overrides |
| `drift` | Parent `langfuse-ocp`; sets `quarkus.aiscoring.interaction-mode: drift-detection` |
| `langfuse-ocp` | Points at a deployed Langfuse instance; disables OTLP export and LGTM |
| `instana` | Exports OTLP to Instana instead of LGTM |
| `prod`, `openshift` | Schema drop-and-create + `import.sql`; OpenShift deployment config |

### OpenShift deployment

`deploy-to-openshift.sh` installs Langfuse (Helm), applies `src/main/kubernetes/dependencies.yml`, then builds and
deploys the app with `-Dquarkus.profile=openshift`. It ends by printing the Roundcube Route URL.

- **`dependencies.yml`** holds PostgreSQL, LGTM and the mail stack, plus the `parasol-app-config` ConfigMap the app
  reads (`quarkus.mailer.host: greenmail`, port `3025`).
- **LGTM's Grafana needs `GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false` or it shows no datasources.** Grafana 13
  (`grafana/otel-lgtm` ≥ `0.32.0`) ships the Prometheus, Loki, Tempo and Pyroscope datasources as bundled plugins
  inside the image, at `/otel-lgtm/grafana/data/plugins-bundled`. Grafana loads them fine; what breaks is that its
  background installer then tries to **auto-update** each one from grafana.com, and an update begins by deleting
  the existing directory. Those files belong to the image's uid 1000 while OpenShift runs the container as an
  arbitrary UID, so the delete fails (`unlinkat …/prometheus/1008.js: permission denied`) and the failed install
  drops the plugin that had already registered. The `grafana-lgtm` Deployment therefore pins the bundled versions
  rather than updating them. This does **not** stop the Drilldown apps installing — they aren't in the image at
  all, so they're still downloaded into `GF_PATHS_PLUGINS` on the `/data` PVC.
  - **The symptom is non-obvious:** every picker (Explore, Drilldown) offers only `-- Mixed --` / `-- Grafana --`,
    while the datasources themselves *are* provisioned and `/api/datasources` happily lists all four. They vanish
    in `/api/plugins` and `/api/frontend/settings` (what the UI actually builds the picker from), so diagnose
    there.
  - **To read Grafana's own log you need `ENABLE_LOGS_GRAFANA=true`** — the image swallows Grafana's stdout by
    default, which hides both `Failed to install plugin … permission denied` and the resulting `Could not find
    plugin definition for data source`.
  - **This is UID-specific, not universal.** A plain `docker`/`podman run` of the same image is fine: as root the
    delete succeeds and the plugin is replaced by a fresh download. Don't expect to reproduce it locally, and
    don't expect an image bump to remove the need for the env var.
  - Local dev mode is unaffected for a different reason: the Quarkus LGTM Dev Service still runs `0.24.0`
    (Grafana 12), where these were ordinary core plugins with no bundled-plugin update path.
- **The two PVC-backed Deployments (`grafana-lgtm`, `non-deterministic-db`) use `strategy: Recreate`.** Their PVCs
  are `ReadWriteOnce`, so a rolling update deadlocks: the new pod can't attach the volume until the old one
  releases it, and the old one isn't terminated until the new one is ready (`Multi-Attach error for volume …`,
  then `oc rollout status` times out). Don't drop back to the default `RollingUpdate`.
- **`langfuse-web` must not be killed while it migrates.** It runs the Prisma and ClickHouse migrations *before* it
  binds `:3000`, which on a fresh install takes longer than the chart's stock liveness probe allows (20s delay, 10s
  period, 3 failures → SIGKILL ~50s in). The kill lands mid-statement, golang-migrate leaves ClickHouse's
  `default.schema_migrations` marked dirty, and **every subsequent boot then refuses outright** with
  `error: Dirty database version N. Fix and force version.` — a crash loop that no restart, redeploy or chart/operator
  upgrade clears.
  - `langfuse-helm.values.yml` therefore overrides `langfuse.web.livenessProbe.failureThreshold` (and the readiness
    one) to `60`, giving ~10 minutes of boot grace. Chart 2.1.6 exposes **no `startupProbe`** for the web pod, so
    `failureThreshold` is the only lever. The cost is that a genuinely hung *running* web pod also takes ~10 minutes
    to be restarted.
  - **Recovering a dirty marker is manual.** golang-migrate's ClickHouse driver reads
    `SELECT version, dirty FROM schema_migrations ORDER BY sequence DESC LIMIT 1` and records versions by *appending*
    a row, so `migrate force N-1` is just an insert:
    `INSERT INTO default.schema_migrations (version, dirty, sequence) VALUES (N-1, 0, toUnixTimestamp64Nano(now64(9)))`
    via `clickhouse-client` in `langfuse-clickhouse-0-0-0`, then `oc rollout restart deployment/langfuse-web`. Replaying
    a migration is safe — Langfuse's ClickHouse migrations are `ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS` /
    `MODIFY QUERY`. Apply the probe fix first, or the replay gets killed the same way.
  - **Diagnose with ClickHouse, not the pod log.** The web log only ever shows the *refusal* from a later boot; the
    original failure is in `system.query_log` (`WHERE exception_code != 0`). A `Code: 210 … Broken pipe, while writing
    to socket` whose inner `/* ddl_entry=… */` statement shows `QueryFinish` means the DDL succeeded and the *client*
    died — i.e. the kubelet killed it, not ClickHouse. Cross-check with
    `oc get events | grep langfuse-web` for `failed liveness probe`.
  - The script gates on `oc rollout status deployment/langfuse-web` (and the worker) after the Helm install, so a
    broken Langfuse aborts the run instead of letting `parasol-app` deploy against it and printing success.
  - The ClickHouse operator chart is pinned to `0.0.8` — the latest release, and the version Langfuse's own
    `examples/minimal-installation` prescribes. Dirty-migration crash loops are **not** an operator version problem;
    don't go bumping it.
- **The mail stack mirrors `compose-devservices.yml`:** same images and tags, same environment variables. Change
  both together.
  - **GreenMail** is internal only (ClusterIP Service for SMTP 3025, IMAP 3143 and the API on 8080; no Route). It keeps
    mail in memory, so a pod restart loses every mailbox. That's deliberate: GreenMail has no persistent store
    (`greenmail.preload.dir` only loads `.eml` files at startup), and claim data lives in PostgreSQL anyway.
  - **Roundcube's `parasol_identities` plugin** (`src/main/docker/roundcube/`) gives the six seeded claimants' identities
    their names, so mail they send from Roundcube goes out as `Marty McFly <marty.mcfly@email.com>`. `user_create` names a
    new user, and `login_after` fills the name of any mapped identity whose name is empty at every login (a name set in
    Settings → Identities is never overwritten). The address → name map, `config/parasol-identities.php`, must follow the
    claimants in `import.sql`. Compose mounts the three files read-only; on OpenShift `deploy-to-openshift.sh` creates the
    `roundcube-identities` ConfigMap from the same files, so there is one copy. **The plugin can't be mounted into
    `/var/www/html/plugins`:** the runtime would create that directory root-owned and the image's entrypoint (as
    `www-data`) couldn't copy Roundcube's own plugins into it. It is mounted under `/usr/local/share/parasol/plugins`, and
    `entrypoint-tasks/post-setup/10-parasol-identities-plugin.sh` (run by the image's entrypoint) symlinks it in. The
    one `Failed to load plugin file … parasol_identities.php` line in the startup log comes from the entrypoint's DB
    initialization, which runs before that link exists; it is harmless.
  - **Roundcube** is exposed through an edge-TLS Route; log in as any address with any password. It runs under the
    restricted SCC (arbitrary UID) only because `ROUNDCUBEMAIL_DB_DIR=/tmp/roundcube-db` moves its SQLite DB off the
    `www-data`-owned `/var/roundcube`. There is no volume, so sessions and settings are lost on restart.
- The app's `app.openshift.io/connects-to` annotation (`%openshift` in `application.yml`) names **Deployments**, not
  Services: `non-deterministic-db,grafana-lgtm,greenmail,langfuse-web` (the LGTM Service is `lgtm`, its Deployment
  `grafana-lgtm`). Roundcube's Deployment carries its own `connects-to: greenmail`.

### Environment variables

| Variable | When it is needed |
|---|---|
| `OPENAI_API_KEY` | Default profile — `parasol-chat`, `generate-email`, `politeness`, `claim-intake`, embeddings |
| `COHERE_API_KEY` | Default profile — `session-sentiment` and `judge` |
| `GEMINI_API_KEY` | Only when `LangfuseEvaluationInitializer` runs (`initialize-on-startup`); it throws `IllegalStateException` if absent |

Under `-Pollama` none of these are required — the OpenAI and Cohere keys are stubbed to `changeme`
and `LangfuseEvaluationInitializer` is switched off.

Under `-Pollama-openai` nothing is stubbed, but which keys you actually need depends on the goal,
because the Maven profile sets **two different Quarkus profiles**:
- `quarkus:dev` and the packaged app run under `quarkus.profile=ollama-openai,prod`, so
  `COHERE_API_KEY` is needed for `session-sentiment`/`judge` and `GEMINI_API_KEY` is needed because
  startup initialization stays enabled.
- `./mvnw test` / `./mvnw verify` run under `quarkus.test.profile=ollama-openai,test` (forced on
  surefire and failsafe), and `%test` sets `initialize-on-startup: false` and `score-session: false`
  — so the initializer and session scorer are inert and **neither `COHERE_API_KEY` nor
  `GEMINI_API_KEY` is required for the build**. That is why CI passes with only a stubbed
  `OPENAI_API_KEY`.

### Observability

OpenTelemetry + Micrometer. Dev mode uses the LGTM dev service (Grafana/Loki/Tempo/Prometheus).
The `grafana/otel-lgtm` image ships Prometheus as its metrics store, not Mimir, despite the "M" in LGTM.
Traces are also exported to Langfuse via the `quarkus-langfuse` extension.

## Testing

Test layout mirrors main: `src/test/java/org/parasol/...` and `src/test/java/ai/scoring/...`, plus the test-only
`org.parasol.testing` (the shared test profiles), `org.parasol.testing.mail` (GreenMail helper) and `org.parasol.ui` (Playwright).

- `@QuarkusTest` + `@InjectMock`/`@InjectSpy` + `dev.langchain4j.test.guardrail.GuardrailAssertions`
  for guardrail tests.
- REST Assured for REST; the websocket chat-routes client (`WebsocketChatRoutes.newClient(...)`)
  for chat tests (`ClaimWebsocketChatBotTests`).
- Playwright E2E tests in `org.parasol.ui`, extending the `@WithPlaywright` `PlaywrightTests` base
  class (records video to `target/playwright`). They all share `UiTestProfile` (one boot): Quinoa's `EnableAndRunTests`
  overrides, which also run the frontend Jest suite (see Frontend above), plus only the `claim-intake` model on WireMock
  (`StubbedModelsTestProfile.claimIntakeOnWireMock()`). Every other model stays real, with Easy RAG ingestion at boot, because
  `ClaimsDetailPageTests.pageLoads` talks to the real chat bot.
  - **`ClaimReviewPanelPageTests`** is why `claim-intake` is on WireMock there (and `UiTestProfile` carries `@ConnectWireMock`): the review
    panel only renders its buttons for a claim whose review is genuinely open (`ClaimReviewState.open`), which only a real
    waiting `ClaimIntakeFlow` run produces. It drives a real email through `IntakeScenario` to get one, then uses the
    panel: the controls (every "Ask customer" checkbox, the edit fields, Submit/Request more information and Regenerate)
    are absent for an `In Process` and a `Pending Information` claim and present for `Pending Review`; submitting an
    edited claim persists the edit and removes the controls; checking "Ask customer" disables that field and moves the
    claim to `Pending Information` with the read-only list of what was asked; Regenerate shows the new sentiment; a `409`
    (forced with a Playwright route) reloads the claim and remounts the panel, which re-reads the review so the next submit
    uses the current version. All
    through the real endpoints, not stubs. The status labels/filters are covered against two directly-persisted fixture
    claims, no Flow run needed for that one.
- `ai.scoring.it.DriftDetectionTests` is annotated with the custom meta-annotation
  `ai.scoring.it.DriftDetectionTest`, which combines `@QuarkusTest` with **four** independent
  enablement conditions, each carrying its own `disabledReason`:
  `@EnabledIfConfig(named = "quarkus.aiscoring.interaction-mode", matches = "drift-detection")`
  (`io.quarkus.test.junit.condition.EnabledIfConfig`, which ships with Quarkus itself — not
  `@EnabledIfApplicationProperty`, and there is no local copy of the condition classes),
  `@EnabledIfSystemProperty(named = "quarkus.profile", matches = "drift")`,
  `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")` and
  `@EnabledIfEnvironmentVariable(named = "COHERE_API_KEY", matches = ".+")`. The annotation also
  nests `DriftTestProfile implements QuarkusTestProfile`, which returns the `drift` config profile.
  Practical consequence: these tests need the `drift` profile **and** real OpenAI *and* Cohere keys,
  so they can never run in CI, which only supplies a stubbed `OPENAI_API_KEY=change-me`.
- `NotificationServiceTests.emailSendsWhenUserExists` is `@DisabledIfSystemProperty(named =
  "quarkus.test.profile", matches = ".*ollama-openai.*")` — it runs everywhere **except** the
  `%ollama-openai` leg. It is the only test that drives the real `GenerateEmailService` through all
  four output guardrails end to end. **The gate is model capability, not the #228 guardrail bug,
  which is fixed** (`EmailOutputGuardrailChainTests` covers that).
  `EmailEndsAppropriatelyOutputGuardrail` requires the body to end with
  `GenerateEmailService.EMAIL_ENDING` **verbatim**, and most small models reflow it — `granite4:micro`
  collapses the three disclaimer lines onto one and adds a space after `"Sincerely,"`, so the
  guardrail reprompts until max-retries. Measured identical for `llama3.2:latest`, `ministral-3:3b`
  and `qwen2.5:3b`. `qwen3:4b` is the one that reproduces it (5/5 runs), which is why
  `generate-email` uses it under `%ollama` — see the Models section. `%ollama-openai` can't follow:
  it reaches Ollama over the OpenAI-compatible endpoint, where `model-options.think` doesn't exist,
  and a thinking qwen3 spends ~6k tokens per email (minutes on a 4 vCPU runner). `qwen3:4b-instruct`
  was tried and rejected: its 256K context blows the test's 5-minute transaction budget even at
  `num-ctx=8192`. **Don't re-enable the last leg by relaxing the guardrail** — the system prompt and
  the reprompt both say "EXACTLY as it appears below", and enforcing that is the point of the demo.
- **Mocking LLM calls:** the Quarkus WireMock dev service (`@ConnectWireMock` + an injected
  `WireMock`). A `QuarkusTestProfile` repoints every relevant `quarkus.langchain4j.openai.*.base-url`
  (including `parasol-chat`, `session-sentiment`, `judge`) at
  `http://localhost:${quarkus.wiremock.devservices.port}/v1`, then stubs the
  OpenAI-compatible `/chat/completions` endpoint. Stubs are registered programmatically in
  `@BeforeEach`; there are no WireMock mapping files. (`src/test/resources` only holds
  `seed/claim-images/empty-for-tests.jpg`, an empty file for `ClaimImageSeederTests`; WireMock reads only its
  `mappings/` and `__files/` subdirectories, so it's unaffected.)
- **Every distinct `@TestProfile` class is a full application boot.** Quarkus restarts the application, and with it the Dev
  Services (Postgres, GreenMail, Langfuse, WireMock), whenever the profile class changes, which is most of this suite's
  run time. Quarkus already runs the classes grouped by profile (`QuarkusTestProfileAwareClassOrderer` is registered as a
  global `ClassOrderer` by default; `quarkus.test.class-orderer` sets its secondary order), so the number of boots is the
  number of distinct profiles. **Don't add a profile for one test;** a test whose config is additive belongs in an existing one.
  **A change in a class's test resources is a boot too.** Quarkus compares each class's set of `@QuarkusTestResource`s
  (`TestResourceManager.testResourcesRequireReload`), and `@ConnectWireMock` and `@WithPlaywright` are both test resources.
  So `@ConnectWireMock` sits on the profile classes, never on a test class: Quarkus reads test-resource annotations on the profile
  too, so every class on a profile gets the same set, and `WireMock` fields are still injected. A test class on one of these
  profiles adds no test-resource annotation of its own. **The Playwright classes still boot once each:** `@WithPlaywright` has
  array attributes, which Quarkus 3.40 compares by identity, so two identical annotations never match. quarkusio/quarkus#56443
  (4.0.0.Beta1) fixes it; once a Quarkus we're on has it, the UI classes share one boot.
  There are four profiles (five with the gated-off drift tests), so seven boots in a full `verify`:
  - **none** (the `%test` defaults): the tests that need real models, a real key and boot-time Easy RAG ingestion.
  - **`org.parasol.testing.StubbedModelsTestProfile`**: every test whose models are stubbed or never called. That's the intake
    tests, the tests of what the intake stores, `DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests` and
    `DriftDetectionChatRouteExceptionHandlerTests`. It sets stub keys and Easy RAG ingestion off, and points `claim-intake`,
    `parasol-chat`, the default OpenAI model and embeddings at WireMock with their providers pinned. It also turns Hibernate
    statistics on, exports spans every 100 ms and starts Toxiproxy.
  - **`org.parasol.ui.UiTestProfile`**: every Playwright test (see above).
  - **`org.parasol.testing.LangfuseExportTestProfile`** (extends the stubbed one): session scoring, dataset creation and
    startup initialization, with spans exported to Langfuse, for `LangfuseSessionScoringServiceTests`.
- **A WireMock-backed profile must also pin the provider.** Redirecting `...openai.*.base-url` is
  not enough: `%ollama` sets `quarkus.langchain4j.parasol-chat.chat-model.provider: ollama` (and the
  same for `embedding-model`), so under `-Pollama` the request never goes near the OpenAI client and
  the stub is silently bypassed — the test then runs against a real model and inherits its
  non-determinism. Every mocking profile (`StubbedModelsTestProfile`) therefore also sets
  `quarkus.langchain4j.parasol-chat.chat-model.provider=openai` and
  `quarkus.langchain4j.embedding-model.provider=openai`. They look redundant under the default
  profile; they are not. This is what made `LangfuseSessionScoringServiceTests` flaky — llama3.2
  decided to call `updateClaimStatus`, which produced a second `GENERATION`.
- **Easy RAG in WireMock-backed profiles:** `StubbedModelsTestProfile` repoints the default
  `quarkus.langchain4j.openai.base-url` at WireMock (for `DriftDetectionChatRouteExceptionHandlerTests` and
  `LangfuseSessionScoringServiceTests`), so it also sets `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF`.
  Otherwise Easy RAG ingests the policy documents at boot, calling `/v1/embeddings` before the
  `@BeforeEach` stubs exist. With `OFF` the in-memory store starts empty and `easy-rag-embeddings.json`
  is neither read nor written; queries are still embedded at chat time, so those tests keep a
  1536-dimension `/v1/embeddings` stub.
- **Easy RAG with stub keys:** `OFF` matters for the tests that only need the stub keys
  (`DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests`) too. Under the default profile, boot-time
  ingestion would call the real OpenAI embeddings API with the `changeme` stub key and fail startup with a
  401 (`AuthenticationException`); under `-Pollama-openai` it would call an Ollama that may not be running.
  Under the default profile it only ever passed when a cached `easy-rag-embeddings.json` let `reuse-embeddings`
  skip ingestion.
  Neither test uses RAG.
- **Langfuse is not mocked.** `DriftDetectionOutputGuardrailTests`, `LangfuseDatasetSampleLoaderTests`
  and `LangfuseSessionScoringServiceTests` inject the real `LangfuseOperations` against the Langfuse
  dev service, creating and tearing down real datasets/items around each test. Their fixtures and
  assertions still go through `api()` (`api().datasetItems()`, `api().observations()`,
  `api().scoresV3()`) — that is test-fixture code that predates the operations layer covering those
  domains, not a gap in the layer.
- AssertJ + Awaitility; Mockito is attached as a `-javaagent` in the surefire/failsafe config.
- **Mail tests read the real GreenMail** from `compose-devservices.yml` (every test run starts it) through the
  injectable `org.parasol.testing.mail.GreenMailMailbox` bean. There is no mail mock.
  - **Reading is IMAP** (Angus Mail, version from the Quarkus BOM; compile scope since `ClaimsMailbox` uses it too):
    `messages(address)` reads one INBOX, `awaitMessage(address, atMost)` polls with Awaitility and returns the first message, and `allMessages()` reads
    every known user's INBOX. Messages come back as the `ReceivedEmail` record (`from`, `to`, `subject`, decoded
    `body` with `\r\n` line endings, and `headers` keyed in lower case, read with `firstHeader(name)`). The body is
    the message's `text/plain` content, or the first `text/plain` part of a multipart one that isn't an attachment (the
    intake's templated replies are multipart/alternative). A message without one throws `MailboxAccessException`.
  - **Purging, listing and deleting users is the GreenMail REST API** (`POST /api/mail/purge`, `GET /api/user`,
    `DELETE /api/user/{address}`), because IMAP only sees one mailbox at a time. The API returns messages only as raw MIME, which is why reading doesn't use it.
  - **Auth is disabled,** so any password logs in and an unknown address is just an empty INBOX (no error). IMAP
    logins and deliveries create users; purging removes mail, not users or folders. `deleteUser(address)` removes the
    user with its folders too, which is how `ClaimsMailboxTests` starts every test with only an empty claims INBOX.
  - Host and ports are `@ConfigProperty` constructor parameters: `quarkus.mailer.host`, plus `parasol.mail.imap-port`
    and `parasol.mail.greenmail-api-port`, which the Compose labels map in. Test code injects config properties
    directly; the `@ConfigMapping` convention is for main code.
  - **Purge in `@BeforeEach`,** not `@AfterEach`, so mail from other test classes can't leak into "no email sent"
    assertions (`NotificationServiceTests.assertNoEmailSent` checks `allMessages()` is empty).
  - `GreenMailMailboxTests` covers the helper with the app's own `Mailer`, no LLM: mailbox isolation and purge.
- **Claims mailbox tests** (`org.parasol.intake.mailbox`): `ClaimsMailboxTests` sends real MIME messages over SMTP with
  Jakarta Mail (the app's `Mailer` can't build multipart/alternative or set arbitrary headers) and reads them back
  through `ClaimsMailbox`: plain text, HTML-only, alternative, image and skipped attachments, auto-reply headers, quoted
  text, find by `Message-ID` and moving between folders. `QuotedTextTests` and `HtmlTextTests` are plain unit tests.
  `IntakeExtensionsTests` checks the app boots with the agentic and quarkus-flow extensions and Flow's three tables
  exist. They share `org.parasol.testing.StubbedModelsTestProfile` (stub keys, Easy RAG ingestion off).
- **Inbox watcher tests** (`ClaimsInboxWatcherTests`, `org.parasol.intake.mailbox`): each test builds its own `ClaimsInboxWatcher` with short timings and a mocked `Event<EmailReceived>`. It can't be `@InjectMock ClaimIntakeDispatcher`: `@InjectMock` disables the observers declared on the mocked bean (quarkusio/quarkus#11122; `@InjectSpy` keeps them, since #51790), so the mock would never be notified. Network faults (connection dropped, silent, unreachable) come from a **Toxiproxy** Testcontainers container, because WireMock only speaks HTTP: `ToxiproxyTestResource` (a `QuarkusTestResourceLifecycleManager`, registered by `StubbedModelsTestProfile.testResources()`, so no extra boot) starts it, and `org.parasol.testing.mail.Toxiproxy` drives it. Its upstream is GreenMail's mapped IMAP port through `host.testcontainers.internal`, since GreenMail is a Compose container. Only the watcher's IDLE session goes through the proxy. Track [quarkus-network-faults#14](https://github.com/quarkiverse/quarkus-network-faults/issues/14) (IMAP support): it may replace this.
- **Intake workflow tests** (`ClaimIntake*Tests` in `org.parasol.intake.workflow` and its sub-packages, `dispatch` and `failure`; tests sit in the package of what they test) run the real `ClaimIntakeFlow`
  against the Compose GreenMail and a WireMock model, one test per rule: new claims, pending claims and replies, the
  review (supersede, the decision race, both decisions, a fault in a decision step), the policy rules, `resolveClaim`, and the
  starter (it doesn't wait; per-sender order). They share `StubbedModelsTestProfile` (one boot; it also pins `claim-intake` to WireMock and turns Hibernate statistics on, so every test of what the intake stores or loads uses it too, and **a new intake test uses it rather than adding a profile**: each profile is another full application boot) and:
  - `IntakeScenario` (`org.parasol.intake`, an `@ApplicationScoped` test helper) sends a real MIME message to the claims address, moves it to
    the processing folder and calls `ClaimIntakeDispatcher.submit`, as the watcher will; it also reads replies, finds a
    claim's waiting run, simulates claiming the review (`claimReview`, the same `reviewRunId` → `decidingRunId` move the
    real `ClaimReviewService` does — these workflow-level tests exercise the decision steps directly, not through the
    REST layer, so the simulation stays), publishes decisions with `publishUntilWoken` (a decision published the moment a
    run says `WAITING` can be lost, so it republishes until the run leaves `WAITING`; see **Review tests** below for why
    the real endpoint doesn't need to), and cleans up (every Flow run canceled, claims beyond the six seeded ones,
    recorded emails and intake state). It also owns the tests' database reads and fixtures, as `@Transactional(REQUIRES_NEW)`
    methods, so the test classes need no `QuarkusTransaction`.
  - `IntakeFlowTestBase` (`org.parasol.intake`, public with `protected` members so every intake test package can extend it: `createClaim`, `stubNewClaim`, `stubFollowUp`) and `ChatStubs.answerWhenAll`: a stub matches the
    last message on **two** texts, a phrase from the agent's prompt and a per-test marker in the email, so each test
    stubs only its own email. A reply's correspondence carries the first email's marker too; the most recently
    registered matching stub wins.
  - **A reply's stubbed `answeredItems` matter:** the agent says which requested items a reply answers
    (`IncidentDetails.answeredItems`), and an item that isn't listed stays requested.
  - `SenderStateTests` (the per-sender queue as pure functions), `ClaimMatcherTests`, `IntakeJsonRoundTripTests`
    (`IntakeRun`, `IntakeState`, `ReviewDecided` through the Quarkus `ObjectMapper`) and the helper unit tests
    (`CorrespondenceText`, `SameCustomerRule`, `PolicyNumbers`, `InceptionDates`, `ClaimReference`, `MailLoopGuard`,
    `ConversationContext`) need no LLM.
  - `WorkflowSpans` (`org.parasol.intake`, test) is a `SpanExporter` CDI bean (Quarkus registers it on its own) that keeps
    only `workflow.execute` spans; `StubbedModelsTestProfile` exports every 100 ms so a run's span shows up soon after it ends.
- **Intake reply tests** (`org.parasol.intake.reply`): `IntakeTemplatesTests` renders both variants of every template
  without sending (the HTML through jsoup's visible text, so both get the same assertions): greetings, missing items,
  attachment notes, HTML escaping, and the no-leak checks against every seeded claim. `IntakeReplySenderTests` sends
  through GreenMail and checks the subject and headers. `CustomerNameTests` is a plain unit test.
- **Intake agent tests** (`org.parasol.intake.agent` and its sub-packages): plain unit tests for `findMissingItems`, the
  guardrails, the `@Output` method, the router's conditions (`EmailRouterTests`: exactly one is true for every matched
  status × email type), `UnmatchedEmailAgent`, `ClaimStatusTools` and `ClaimResolution.limitToOfferedClaims`.
  `ClaimExtractionValueRoundTripTests` and `IntakeOutcomeRoundTripTests` (`org.parasol.intake.model`) round-trip the
  records through the Quarkus `ObjectMapper` (what Flow persists with); `IntakeOutcomeTests` checks `@JsonSubTypes`
  lists every permitted `IntakeOutcome` variant. `ClaimExtractionWorkflowTests`,
  `ClaimsMailboxAgentTests`, `FailedAgenticSystemTests` and `ClaimResolverTests` run the real agents against WireMock:
  - they share `StubbedModelsTestProfile` (one app boot) and the `ChatStubs` helper;
  - stubs match on the **last** message (`matchingJsonPath("$.messages[-1:].content", ...)`), so a reprompt or a tool
    result gets its own stub;
  - the profile pins `claim-intake`'s provider to `openai` and its `model-name` to `claim-intake-model`, so the WireMock
    stubs are hit under every CI profile, and an agent on the wrong model shows up;
  - parallelism is checked from WireMock's own timings (every request arrives before the first is answered), not from
    wall-clock time.
  - the incident-details prompt's category list comes from `ClaimCategory` through `ExtractionPromptExtensions`
    (`{extraction:claimCategories}`), a test checks the rendered list. Domain enums carry no Qute annotation; bind them
    in the template's own package with a `@TemplateExtension(namespace = …)`.
  - an agent that **returns** an enum (or a record holding one) needs no list in its prompt: Quarkus appends
    LangChain4j's format instructions built from the return type. `EmailType`'s constants and `ClaimResolution`'s `kind`
    field carry LangChain4j's `@Description` for the meanings, and tests assert the generated text in the request.
- **Agentic gotchas (quarkus-langchain4j 1.14.2):**
  - `@ModelName` must sit on the `@Agent` **method**; on the interface it's silently ignored and the agent uses the
    default model. Also set `@RegisterAiService(modelName = ...)` to the same name: the core extension reads only that
    attribute when it registers the AI service, so without it the build asks for the default chat model, which is
    ambiguous (and fails) under `-Pollama`, where both `ollama` and `openai` are on the classpath.
  - A workflow's own parameters only pass the build's "No agent provides an output key" check if the workflow interface
    is `@Inject`-ed somewhere **in `src/main`**. `AgentStep` injects `ClaimsMailboxAgent`, which does it.
  - **A `null` agent input counts as missing** (`MissingArgumentException`), so "no matched claim" is
    `MatchedClaim.none()`, and `extractedSoFar` is an all-`null` `IncidentDetails`.
  - **A nested workflow's `@ErrorHandler` doesn't apply under a root:** sub-agents run in the root's agentic scope,
    whose error handler is the root's. `ClaimsMailboxAgent` delegates to `ClaimExtractionWorkflow`'s handler.
  - **An `@Output` method on a router reads the scope** (`AgenticScope` parameter): one parameter per branch output
    would throw for the branches that didn't run.
  - **The status tool is scoped through `InvocationParameters`**, not a tool argument, so the model can't pick another
    claim. 1.14.2's build check doesn't skip an agent's `InvocationParameters` parameter, so the root takes one too
    (callers pass `ClaimStatusTools.scopedTo(matchedClaim)`); it's an execution context, never a prompt variable.
    Upstream: quarkiverse/quarkus-langchain4j#2950 (fix #2951); once released, a root `@BeforeCall` can fill it instead.
  - **An agent that throws used to leave its Flow-generated agentic run unended (quarkus-flow 1.2.0), fixed upstream in
    1.3.0.** LangChain4j never told `FlowPlanner` that an agent failed, so the continuation the generated workflow's
    task joined on (`AgenticFlow.executeAgent`) never completed: the run stayed active, **a worker thread stayed
    blocked forever**, and its rows (agentic scope included, so the correspondence text) stayed in Flow's tables.
    `cancel()` deleted the rows but couldn't release the thread (`join()` ignores interrupts). quarkus-flow
    [#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095) (the user's own PR, fixing
    [#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096)) fixes it from `FlowAgentContextListener`, which
    sees every agent's failure, not just the root's — so task 09b (2026-10-26) deleted the app-level workaround this
    repo carried (`AgenticWorkflowRuns`, `FailedAgenticSystemListener`, `ClaimsMailboxAgent.endFlowRunsOnFailure()`).
    `FailedAgenticSystemTests` (renamed from `FailedAgenticSystemListenerTests`) is kept unchanged as the regression
    guard (no new agentic run rows, no thread blocked in `AgenticFlow.executeAgent`), and passes against 1.3.0.
    `ClaimExtractionWorkflowTests.malformedJsonThatIsNeverFixedStillFailsTheRun`'s leak (it calls the extraction
    workflow directly, without the root's listener) is fixed too, since the upstream fix isn't root-only — verified
    2026-10-26.
  - **`quarkus.flow.validation.input.enabled` (on by default since quarkus-flow 1.3.0, quarkiverse/quarkus-flow#1077)
    is turned off app-wide** (`application.yml`). The generated schema for `ClaimsMailboxAgent` /
    `ClaimExtractionWorkflow` marks every field of every record in the agent topology `required`, with no
    nullable/`Optional` support, including fields that are the agentic system's own not-yet-computed outputs and
    `InvocationParameters` (which `langchain4j-agentic` deliberately keeps out of `AgenticScope.state()`, so it's
    never actually present to validate against). There is no per-workflow scoping for the flag, so turning it off is
    app-wide; `ClaimIntakeFlow`'s own `IntakeRun` input is unaffected either way. Filed upstream as
    [quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111); re-enable once it's
    addressed there.
- **Claim image tests:** `ClaimImageTests` (entity, `@TestTransaction`), `ClaimImageResourceTests` (REST and Problem
  Details), `ClaimImageContentTypeTests` (allow-list, no Quarkus), `ClaimImageSeederTests` and the Playwright
  `ClaimImagesPageTests`.
  - Fixtures that the endpoints must see are committed with `QuarkusTransaction.requiringNew()` and removed with
    `Claim.deleteById` (cascades to images). `@TestTransaction` doesn't work for these: REST Assured calls the endpoint
    over HTTP, on another thread with its own transaction, so it can't see the test's uncommitted rows (verified: a
    claim persisted and flushed in a `@TestTransaction` is a `404` to the endpoint). `@TestTransaction` is fine for
    tests that only call the model directly (`ClaimImageTests`, `ClaimTests`).
  - Seeder tests that insert or delete images do it on a claim of their own, never on the seeded claims that other
    classes (e.g. `ClaimImagesPageTests`) rely on.
  - **REST tests compare responses as the DTO / `ProblemDetail` records** (`.as(...)` / `getObject(...)` then
    `isEqualTo`), not as `Map`s. A record round-trip can't see the wire format, since the same `@JsonNaming` serializes
    and deserializes, so each class keeps small JSON-level tests for that: the exact key set (`jsonFieldNames`,
    `metadataJsonFieldNames`), the category label and an omitted null `incident_time`.
  - Call Panache statics through a lambda in `assertThatThrownBy(() -> ClaimImage.flush())`: a method reference
    (`ClaimImage::flush`) bypasses Panache's enhancement and throws "did you forget to annotate your entity with
    @Entity?".

## Conventions

Beyond the global Java/Quarkus style rules in `CODE_STANDARDS.md`, this repo specifically uses:

- **Tabs for indentation**, tab width 2, max line length 180 (see `.editorconfig`). Note
  `insert_final_newline = false`.
- **US English** everywhere: code, comments, docs, task files and commit messages ("canceled", "behavior",
  "labeled", "serialize", "organize", "color"). Identifiers from libraries keep their own spelling (e.g. Flow's
  `WorkflowStatus.CANCELLED`).
- `io.quarkus.logging.Log` static methods (`Log.debugf`, `Log.warnf`) — no `Logger` fields.
- `@ConfigMapping` interfaces with `@WithDefault` — not `@ConfigProperty` fields.
- Constructor injection in `ai.scoring`; `org.parasol.notification.service.NotificationService` still uses `@Inject`
  fields (legacy — prefer constructor injection for new code).
- Hand-written fluent builders (e.g. `DriftDetectionException.builder()`), records for value types.
- **An exception that only exists to be mapped to a response computes no stack trace** (`super(message, null, false,
  false)`; an `IllegalArgumentException` subclass has no such constructor, so it overrides `fillInStackTrace()` to return
  `this`). RESTEasy Reactive neither logs nor shows a mapped exception's trace, so building one is waste. That covers the
  claim not-found, unknown category and unsupported content type exceptions and the review exceptions. One that wraps a
  failure someone needs to see (`SentimentAssessmentFailedException` keeps its cause; `ClaimSentimentReassessor` logs it)
  or faults a Flow run (`MailboxException`, `IntakeEmailMissingException`) keeps a trace.
- **Type names are nouns for what the type is, not activities** (no `Threading`, `Filing`, `Recovery`): a bean is named for
  its role (`…Sender`, `…Handler`, `…Recoverer`, `…Guard`), a value for what it holds (`ThreadingHeaders`), a CDI event in the
  past tense (`ReviewDecided`, `EmailRequeued`). In the intake workflow, a bean that is the body of one Flow task is a
  `…Step` (`PolicyCheckStep`, `FileEmailStep`), of several tasks `…Steps` (`ReplySteps`, `ReviewDecisionSteps`). A value that
  only picks the next branch is an enum, not a `boolean` (`AfterDecision`).
- **Moving a class to another package is safe; renaming an AI service interface or method is not.**
  Langfuse dataset names are the AI service span name, `langchain4j.aiservices.<SimpleClassName>.<method>`.
  Quarkus LangChain4j uses the simple interface name, `AiServiceDatasetSpanProcessor` and `ConversationExchange`
  copy the span name, and `DriftDetectionOutputGuardrail` rebuilds it from the simple name. A rename
  orphans the recorded datasets, and drift detection then passes silently.
- **JAX-RS resource classes are package-private, their endpoint methods stay `public`.** Quarkus REST serves a 404 for every endpoint
  whose method isn't public (tried on `ClaimReviewResource`: all 17 `ClaimReviewResourceTests` failed), but the class itself needn't be.
  Exception-mapper methods can be package-private.
- `Optional` chains over null checks and guard-clause early returns.

## Gotchas

- **Older revisions of this file described a two-module layout** (`parasol-app/` + `ai-scorer/`)
  communicating over a REST contract in `openapi/ai-interactions.yml`, with an
  `InteractionPublisher`/`InteractionScorer` pipeline and a `RESCORE` interaction mode. **None of
  that exists any more** — it collapsed into this single app scoring against Langfuse in-process,
  and `InteractionMode` is now `NORMAL`/`DRIFT_DETECTION`. Do not reintroduce references to it.
- **Tiers 2 and 3 are mutually exclusive in practice.** `%drift` is the only profile that sets
  `quarkus.aiscoring.interaction-mode: drift-detection`, and it declares `langfuse-ocp` as its
  parent profile — which sets `score-session: false` and disables the OTLP exporter. So whenever
  tier 3 is armed, tier-2 session scoring is off; only tiers 1 and 3 are live under `%drift`.
- **The quarkus-langfuse operations layer (`LangfuseOperations`) covers broadly, but not
  everything.** Prefer it (`createIfAbsent`, `upsert`, `findByName`/`findByProvider`, `matching`,
  `streamAll`) wherever it exists — it replaced the hand-rolled lookup-then-create helpers and
  pagination loops this app used to carry. The residual gaps that still force `langfuse.api()`:
  - **No `update` on any domain except annotation queue items.** That is precisely why
    `LangfuseEvaluationInitializer.updateEvaluatorModel` stays on
    `api().evaluators().evaluatorsUpdate(...)` — it is not an oversight waiting to be tidied up.
  - **Traces and sessions are not covered, deliberately.** They remain reachable only via `api()`.
    Per the Langfuse upstream documentation these endpoints are deprecated, with a Langfuse Cloud
    removal date of 16 November 2026 — that date is not asserted anywhere in the extension sources,
    so treat it as an upstream claim to re-check rather than a verified project fact.
  - **`/api/public/unstable/` endpoints (dashboards, dashboard widgets) are excluded** as a
    standing project rule.
- **Log Langfuse failures with `LangfuseApiException.getStatusCode()`/`getServerMessage()`, not
  `getMessage()`** — `getMessage()` carries the entire raw JSON response body behind a
  `Langfuse API error (404): ` prefix. The broad trailing `catch (Exception)` blocks in
  `LangfuseEvaluationInitializer` are intentional: they run inside `onStartup(@Observes StartupEvent)`,
  where an escaping exception aborts application boot.
- **A Langfuse `412` on evaluator creation is a failed provider call, not a bad payload.** Langfuse
  test-calls the evaluator's LLM connection before it will save it, and answers
  `412 EvaluatorConfigurationError` ("The LLM provider could not complete the model request during
  evaluator validation") when that call fails. **It never passes the provider's own error through** —
  not in the response body, and not in the `langfuse-web` container log either, which carries only the
  `EvaluatorConfigurationError` stack. So the cause has to be found by calling the provider directly;
  `LangfuseEvaluationInitializer.logEvaluatorValidationHint` logs the suspects and the `generateContent`
  curl that settles it. Seen in the wild as a Gemini project over its **monthly spend cap**
  (`429 RESOURCE_EXHAUSTED`, https://ai.studio/spend) — note that `upsert`ing the `google-ai-studio`
  LLM connection *succeeds* regardless, because Langfuse does not validate the key at that point, so a
  present, correct-looking connection proves nothing. Don't read a 412 as a reason to change the
  configured model.
- **The observation `fields` parameter is unvalidated free-form text** — an unknown field group is
  silently ignored, not rejected with a 400 — which is how the earlier `meta` typo survived so long
  (the group is spelled `metadata`). `LangfuseSessionScoringService.fetchSessionObservations`
  requests `"core,basic,io,metadata"`, which is exactly what the code consumes: `core`/`basic` carry
  `startTime` and `parentObservationId` (`isCompleteExchange`, `ConversationExchange.hierarchyOf`),
  `io` carries input/output (`isCompleteExchange`, `ConversationExchange.from`), and `metadata`
  feeds steps 1-2 of `ConversationExchange.resolveDatasetName`, which read
  `observation.getMetadata()` and fall back to the span-name-based step 3 when metadata is absent.
  Note `io` does **not** include metadata — that is the separate `metadata` group.
- Langfuse online evaluators historically had to be created by hand in the UI; this repo creates
  them via the Langfuse API in `LangfuseEvaluationInitializer`. Langfuse's own `LANGFUSE_INIT_*`
  env vars do **not** cover evaluators.
- Session scoring is inherently racy against Langfuse's async ingest. The wait/poll knobs live
  under `quarkus.aiscoring.langfuse.evaluation.session` (`otel-flush-wait-time`,
  `observation-poll-interval`, `observation-max-wait-time`, `dataset-creation-max-wait-time`).
  If session scores go missing, tune
  these before suspecting the scorer.
- **Never set `claim_number` on a claim that gets persisted: not in Java, SQL or seeds.** The only exceptions
  are tests that deliberately exercise the constraint and clean up after themselves (see `ClaimTests`);
  unpersisted fixtures such as the `PanacheMock` claim in `ClaimResourceTests` are fine.
  - A value set on a new `Claim` is ignored.
  - Changing it on a managed claim fails the whole flush (`HibernateException: An immutable natural
    identifier … was altered`), so other changes in that transaction are lost too.
  - In raw SQL, an explicit `NULL` violates NOT NULL (it isn't "use the default"), and a hand-picked value
    can collide with a future `nextval`. Omit the column or write `DEFAULT`.
- **Claim numbers have gaps and depend on insert order.** A rolled-back insert still uses up its `nextval`,
  and tests advance the sequence too.
  - Tests must assert only the format, uniqueness, or the 1009 step between two claims they created
    themselves, never an absolute number.
  - Tests must delete or roll back the claims they create. `ClaimsListPageTests` expects exactly six.
  - Reordering or inserting seed rows renumbers the later seeds, so look claim numbers up instead of
    hard-coding them.
- **The number exists only after the insert is flushed.** `persist()` defers the insert, because Panache ids
  come from a pooled sequence. Use `persistAndFlush()` when the number is needed in the same transaction.
- **`ClaimImageSeeder` runs on every start and re-inserts any missing configured image.**
  - Every profile currently recreates the schema on each boot (`%prod` / `%openshift` set `drop-and-create`; dev/test
    get it from Dev Services), so in practice each boot inserts all 12 ("Processed 12 seed claim images: 12 inserted,
    0 already present, 0 claim not found, 0 file missing/unreadable/empty"). The re-insert-missing path matters for a
    persistent schema, and `ClaimImageSeederTests` covers it by calling `seed` directly; a second run inserts nothing.
  - **It matches the sample claims by their explicit ids in `import.sql`** (claim numbers are generated, so they can't
    be listed). Keep `SEED_IMAGES` aligned if the seed claims change.
  - **Seed data never fails startup.** An image whose claim doesn't exist, or whose resource file is missing,
    unreadable or empty, is logged (WARN, with the reason) and skipped, and the rest are still seeded. A file is read
    only when it's about to be inserted.
  - `seed` does the work and returns the count of each outcome as a `SeedResult`; the `StartupEvent` observer logs it
    as one INFO summary. Tests assert the same `SeedResult`, which is how they check "already present" and "claim not
    found" (those leave nothing in the database).
  - Empty files are skipped before `store`: `ClaimImage.data` is `@NotEmpty`, and a violation at flush would roll
    back the whole run.
  - It runs in one transaction from a `StartupEvent` observer, so an unexpected database error still aborts boot.
- **The claim-number sequence can't be a `@SequenceGenerator`** (spike-verified on Hibernate 7.4.9).
  - **A lone class-level one takes over the `PanacheEntity` id.** This follows the JPA 3.2 default-generator rule:
    there's no `claims_seq`, and ids come from the claim-number sequence.
  - **With `PanacheEntityBase` and two generators,** the unused one is never created, so `CREATE TABLE` fails
    on the column default.
  - That's why `ClaimNumberGenerator` registers the sequence itself via `ExportableProducer`.
  - **`@ColumnDefault` can't move onto `@ClaimNumber`:** it's `@Target({FIELD, METHOD})`, and Hibernate reads
    it only directly from the field.
- **`lpad` would silently truncate claim numbers past 8 digits.** The sequence's `maxvalue 99999999` turns
  that into a `nextval: reached maximum value` error instead of wrong, colliding numbers. Don't drop it.
- **`Claim` inserts aren't JDBC-batched.** Hibernate disables batching for entities with insert-generated
  values. Claims arrive one at a time, so this doesn't matter.
- **`ClaimTests` logs two expected WARNs on success.** `ARJUNA012125` with a stack trace comes from
  `changingClaimNumberFailsTheFlush`, and `HHH000247` / `23505 duplicate key` from
  `duplicateClaimNumberIsRejected`. Neither is a failure.
- `NotificationService.sendEmail` deliberately moves the mailer onto `ForkJoinPool.commonPool()`
  with a 15s timeout — the reactive mailer blocks the calling (tool-execution) thread and deadlocks
  otherwise. Likewise `updateStatusIfFound` uses `QuarkusTransaction.joiningExisting()` because the
  tool runs inside the AI service's invocation context.
- The `langfuse-ocp` profile in `application.yml` contains a **committed Langfuse public and secret
  key** pointing at a demo OpenShift cluster. There is a `.gitleaks.toml` at the root. Do not copy
  this pattern for new credentials.
- **Two different score scales meet in `DriftDetectionOutputGuardrail`.**
  `quarkus.aiscoring.threshold` defaults to `0.75` (a 0–1 fraction), but `EvaluationReport.score()`
  is `100.0 * passed / total` — a **pass-rate percentage**, not an average of the judge's
  confidence. The guardrail divides by `100.0` to reconcile them. Consequently the per-sample
  `EvaluatorResult.score()` returned by `EvaluatorAgent` never reaches the threshold comparison
  directly; only its boolean `verdict` (via `EvaluationResult.passed()`) moves the needle.
- **The email intake workflow** (`ClaimIntakeFlow`, task 08 of #216):
  - **Flow's `tryCatch` only catches a `WorkflowException`**, never a plain Java exception from a function step, which just
    faults the run. `ReviewDecisionSteps` wraps a fault *before* its commit in a `WorkflowException` of type `DECISION_FAILED`, and
    the flow catches it with `catchType(DECISION_FAILED, …)`: the catch puts the claim back in review (`restoreReview`) and
    an `afterDecision` switch sends the same run back to `waitReview` (the `listen` registers a fresh consumer).
    A fault *after* the commit (the email can't be sent) only logs: the claim has moved on, and a retry would send twice.
  - **Block on the mailer only off the caller's thread.** The reactive mailer stalls until the SMTP server's own timeout (a
    `421` after 30 s from GreenMail) when it's blocked on a thread that carries a captured Vert.x context, which depends on
    where a run was started (a `ManagedExecutor` or Flow task thread). `IntakeReplySender` runs every send on
    a new virtual thread (`CompletableFuture.runAsync(send, Thread::startVirtualThread)`) for that reason; it has no
    captured Vert.x context. Text and templated replies both send through the `ReactiveMailer` and share one await,
    `quarkus.mailer.timeout` (forever when 0). It's not `ForkJoinPool.commonPool()` (as `NotificationService` still uses for a plain mail):
    a virtual thread inherits the caller's context class loader, a common pool thread has the JVM's system loader, which
    in dev mode isn't the Quarkus application loader, and a template's `{config:…}` lookup then fails with
    `ServiceConfigurationError: … QuarkusConfigFactory not a subtype of SmallRyeConfigFactory`. Tests don't reproduce it
    (their class loading is flat), so `IntakeReplySenderThreadTests` (a plain Mockito test) checks the cause instead: the
    sending thread is a virtual one carrying the caller's context class loader. It fails with `commonPool()`.
  - **Panache statics through a method reference throw** ("did you forget to annotate your entity"): use a lambda
    (`id -> Claim.<Claim>findById(id)`), never `Claim::<Claim>findById`.
  - **The per-sender lock covers a woken review run.** `ClaimIntakeDispatcher` (also Flow's status listener) holds the sender
    while a run works. A waiting review run that wakes for its decision holds it again until it ends, so an email that a
    run handed back (`EmailRequeued`: its claim was being decided) waits for the decision steps. The wake-up is seen as a
    `WAITING → RUNNING` status change, with the `routeDecision` task start as a backstop; a requeued email that still finds
    its claim being decided is retried with a delay (250 ms, doubling, six times) and then takes the failure path.
    `ClaimIntakeFlow.isForThisRun` also requires the event's claim and run id to match, so a decision for another run is ignored.
  - **A claim's review always has a live run.** A run that faults while it owns a review (as `reviewRunId` or `decidingRunId`)
    is replaced by `ReviewRecoverer`: a new run with `IntakeRun.resumesReview` goes straight to `waitReview`, and is set as the
    review run before it starts. Restoring `reviewRunId` alone would point at a dead run.
  - **Never set `Claim.version` or any intake column on `Claim`:** they don't exist. Use `ClaimIntakeState`.
  - **Prefer `@Transactional` to `QuarkusTransaction`** (review decision). Where a step must not hold a transaction across an
    LLM, IMAP or SMTP call, the transactional part is its own non-private method annotated
    `@Transactional(TxType.REQUIRES_NEW)` and called from the step: Quarkus intercepts self-invocation of non-private methods
    (so the annotation applies), and a private method or a private record in its signature would break the generated
    subclass. `CorrespondenceRecorder`, `SupersedeStep` and `ReviewDecisionSteps` catch the exception from such a call.
  - **Don't pass `Optional` to `IntakeReplySender`:** use `ThreadingHeaders` (`of(email)`, `replyingTo(id)`, `none()`) and the overload
    with or without a `String claimNumber`. The overload without one is for replies that mustn't disclose a claim.
