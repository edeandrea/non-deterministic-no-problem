# Issue #232: Bring in ArchUnit — Task Execution Plan

## Your Mission

Turn the application's architecture into rules the build enforces. Today several invariants live only in comments or in
`CLAUDE.md`, and each breaks silently: a `private` `@Transactional` helper quietly loses its transaction, and an
`@InjectMock` on an intake agent quietly weakens every intake test in the shared boot.

The motivation is modularity. This is a demo, so everything sits in one application, but:
- `ai.scoring` should be extractable as a library somewhere else;
- the intake (#216) could be a completely separate application.

So nothing may leak between them. The issue's five rules, plus the ones agreed while planning, become ArchUnit tests.
Where a rule doesn't hold today, the code changes rather than the rule. That means two pieces of real refactoring:
- moving the conversation id out of the intake (tasks 01–02);
- breaking the intake's package cycles (task 05).

**GitHub issue:** [#232](https://github.com/edeandrea/non-deterministic-no-problem/issues/232)
**Plan File:** `.tasks/09-gh232-architecture-tests/PLAN.md`
**Tasks Directory:** `.tasks/09-gh232-architecture-tests/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/09-gh232-architecture-tests/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- Make sure it compiles under both CI profiles: `./mvnw -B clean test-compile -Pollama` and
  `./mvnw -B clean test-compile -Pollama-openai`.
- **Before touching a production class, present the design and every affected file in full, and wait for approval.**
  This is the user's standing preference: don't go straight from diagnosis to implementation.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [x] [task-01-conversation-id-spike.md](task-01-conversation-id-spike.md): Spike: can an `ai.scoring` Flow adapter attach the conversation id with no intake code?
- [ ] [task-02-conversation-id-adapter.md](task-02-conversation-id-adapter.md): Build the Flow adapter and take `ConversationContext` out of the intake
- [ ] [task-03-archunit-and-scoring-rules.md](task-03-archunit-and-scoring-rules.md): Add `archunit-junit6`, rule 1, and rewrite `ConversationLayeringTests`
- [ ] [task-04-domain-boundaries.md](task-04-domain-boundaries.md): Rules 2 and 3, the baggage rule and the boundary hardening
- [ ] [task-05-intake-package-cycles.md](task-05-intake-package-cycles.md): Break the intake's package tangle by moving classes, then enforce it
- [ ] [task-06-intake-and-interceptor-rules.md](task-06-intake-and-interceptor-rules.md): Rules 4 (app-wide) and 5, and the intake AI service opt-outs
- [ ] [task-07-coding-conventions.md](task-07-coding-conventions.md): Coding-convention rules (resources, injection, executors, logging, dates)
- [ ] [task-08-gotchas-rest-config-rules.md](task-08-gotchas-rest-config-rules.md): REST and persistence, Panache method references, JSON-safe run data, config/transactions/exceptions, test naming
- [ ] [task-09-docs-and-verification.md](task-09-docs-and-verification.md): Documentation, mutation check, verification and the PR

> **Branch and PR: confirm before branching.** One branch off `main` (suggested `gh232-architecture-tests`) and one PR into
> `main`. Suggested title: *Enforce the application's module boundaries with ArchUnit*. No closing keyword in the title;
> the body ends `Closes #232`.

---

## Shared Context

Everything below was established on 2026-11-13 against `main` at `1e33c96`, mostly with `jdeps` over `target/classes`
plus a grep of the sources. Line numbers drift; re-read before relying on one.

### The issue's rules against today's code

| Rule | Holds today? | Notes |
|---|---|---|
| 1. `ai.scoring` depends on nothing outside it | **Yes** (main) | The only links are a Javadoc mention of `ClaimsMailboxAgent` in `FlowSpanTypingProcessor`, and that class's runtime `Class.forName` on whatever agentic interface Flow names. That is generic: a convention to document, not an edge to fix. |
| 2. Nothing outside `ai.scoring` depends on it | **No**: 3 edges | See below |
| 3. `org.parasol` domains don't cross boundaries | **Yes** (main) | chat → notification → `claim.model`; intake → `claim.model`; claim and intake → problem. Intake's two mentions of `claim.rest.ClaimExceptionMappings` are Javadoc only, so not in bytecode. In tests, `claim/rest/ClaimRetrievalHasNoIntakeBookkeepingTests` reaches into intake (`IntakeScenario`, `ClaimIntakeState`). |
| 4. No intake `@Transactional`/`@MailClientSpan` method is private, final or static | **Yes** | 30 `@Transactional` methods in 14 intake classes (25 package-private, 5 public), plus `ClaimImageSeeder.seed` outside intake; `@MailClientSpan` on `ClaimsMailbox`'s five operations and `IntakeReplySender.deliver`. The "Non-private and non-final…" comment is on 24 of them and **missing** from `ClaimMatcher.match`, `ReplyRecorder.record`, `ClaimMatchingSteps.initialize` and `ReviewRecoverer.reclaimingRun`, so the rule must check modifiers, never comments. |
| 5. No test mocks or spies an intake agent | **Yes** | None of the 24 `@InjectMock`/`@InjectSpy` fields targets one; no `QuarkusMock.installMockFor*` anywhere. Real agents are `@Inject`ed in `ClaimsMailboxAgentTests`, `FailedAgenticSystemTests`, `ClaimExtractionWorkflowTests` and `ClaimResolverTests`. |

### Rule 2's three edges, and what was decided about each

- **`org.parasol.chat.ai.ClaimService` → `ai.scoring.drift.DriftDetectionOutputGuardrail`** (through
  `@OutputGuardrails`) is **allowed API**. It can't be removed:
  - quarkus-langchain4j attaches guardrails only through that build-time annotation; neither the core nor the deployment
    jar has a config key for it;
  - AI services are synthetic beans, so a CDI interceptor can't bind to them;
  - the guardrail must be able to fail the response (`fatal`, then `DRIFT DETECTED!!!`), which rules out a
    `ChatModelListener`.

  This is the library's opt-in point: an extracted `ai.scoring` would be depended on exactly this way.
- **`ClaimIntakeDispatcher.startRun` (`runIn`) and `ReviewRecoverer.handOver` (`callIn`) →
  `ai.scoring.conversation.ConversationContext`** are **removed**. They start a Flow run with the claim's conversation id
  in OTel baggage, so every span of the run carries it. The user decided the intake may touch **neither `ai.scoring` nor
  OTel baggage**, because `ai.scoring` may become a separate library. Instead, an `ai.scoring.conversation.flow` adapter
  attaches the conversation id by observing Flow, the way `ConversationalBaggageHandler` observes chat scopes. The only
  contract left with the app is a documented convention: the run's instance metadata carries the id under
  `gen_ai.conversation.id` (task 01 replaced the planned input-field convention; see Key Decisions).
- **The wire contract underneath** is the GenAI semconv baggage key `gen_ai.conversation.id`. There are two readers:
  `ai.scoring`'s `ConversationIdSpanProcessor`, and quarkus-langfuse 0.9.1's own `LangfuseSpanProcessor.onStart`, which
  reads `Baggage.fromContext(...).getEntryValue(...)` itself (checked in its bytecode). The intake gets nothing else
  from `ai.scoring`: its runs are never session-scored, since the intake never ends a chat scope.

### The intake's package tangle

Nine intake packages form **one** strongly connected group: `mailbox`, `observability`, `reply`, `review`, `workflow`,
`workflow.mail`, `workflow.outcome`, `workflow.policy` and `workflow.review`. There are three causes, all fixable by
moving classes:

1. **`workflow`'s root holds both the orchestrator and the shared state.**
   - `ClaimIntakeFlow` calls the step sub-packages.
   - The steps reach back up for `IntakeState` (`FileEmailStep`, `ReplySteps`, `ApplyOutcomeStep`, `PolicyCheckStep`,
     `SupersedeStep`), `IntakeRun` (`ReviewDecisionSteps`, `ReviewRecoverer`), `CorrespondenceText`
     (`ApplyOutcomeStep`) and `EmailRequeued` (`SupersedeStep`).
   - `ReviewReclaimer`, `ReviewRecoverer` and `SupersedeStep` need `ClaimIntakeFlow` itself to start or cancel runs.
2. **`review` ↔ `workflow`.**
   - `ClaimReviewService` → `ClaimIntakeFlow` (is the run waiting?), and `ClaimSentimentReassessor` →
     `CorrespondenceText`.
   - The other way: `ClaimIntakeFlow` → `ReviewDecided` and `ReviewDecisionPublisher.REVIEW_DECIDED` (the constant is the
     only thing it uses from the publisher), and `ReviewDecisionSteps` → `ReviewDecision`, `ReviewOutcome` and
     `ReviewedFields`.
3. **Observability payloads point back at their producers.** `IntakeActivity.EmailAccepted` carries
   `mailbox.SkippedAttachment.Reason`, and `IntakeActivity.ReviewDecisionApplied` and `IntakeMetrics` carry
   `review.ReviewOutcome`.

`IntakeOutcome` uses `@JsonTypeInfo(use = NAME)`, so its persisted JSON doesn't name packages, and existing data doesn't
matter anyway (see Key Decisions).

### The intake's agent types (rule 5 and the opt-outs)

| Type | Kind |
|---|---|
| `org.parasol.intake.agent.ClaimsMailboxAgent` | `@SequenceAgent` (root) |
| `org.parasol.intake.agent.EmailRouter` | `@ConditionalAgent` |
| `org.parasol.intake.agent.extraction.ClaimExtractionWorkflow` | `@ParallelAgent` |
| `…agent.triage.EmailClassifierAgent`, `…agent.triage.ClaimFollowUpAgent`, `…agent.extraction.ClaimSummaryAgent`, `…ClaimSentimentAgent`, `…IncidentDetailsAgent` | `@RegisterAiService` + `@Agent` |
| `org.parasol.intake.agent.triage.ClaimResolver` | plain `@RegisterAiService` (`@ApplicationScoped`) |
| `org.parasol.intake.agent.triage.UnmatchedEmailAgent` | `final` class with a static `@Agent`; can't be mocked anyway |

All six intake `@RegisterAiService`s set both `retrievalAugmentor = NoRetrievalAugmentorSupplier.class` and
`chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class`.

### Conventions, as they stand today

- **JAX-RS resources:** four `@Path` classes (`ClaimResource`, `ClaimCorrespondenceResource`, `ClaimImageResource`,
  `ClaimReviewResource`), all package-private with public endpoint methods. `ClaimReviewResource` also has two
  package-private `@ServerExceptionMapper` methods.
- **Field injection:** only `NotificationService`'s two `@Inject` fields (legacy, documented).
- **Absent entirely:** `ManagedExecutor`, `System.out`/`System.err`/`printStackTrace`, `java.util.logging`, SLF4J and
  Lombok.
- **`java.util.Date`:** only `MimeMessageParser`, adapting Jakarta Mail's `Date` to `Instant` (justified in a comment).
- **Logging:**
  - Every `Log` caller is a CDI bean except `org.parasol.intake.mailbox.InboxIdle` (a `final` class built with `new`) and
    possibly `ai.scoring.drift.DriftDetectionChatRouteExceptionHandler`. The latter has no scope annotation, only a
    `@ChatRouteExceptionHandler` method.
  - `ai.scoring.conversation.ConversationEvents` is a bean with a JBoss `Logger` field, because the conversation core
    may not reference `io.quarkus`.
- **Config:**
  - `@ConfigMapping` prefixes: `parasol.intake`, `aiscoring` and `aiscoring.langfuse`.
  - `@ConfigProperty` appears only on constructor parameters naming `quarkus.mailer.*` keys (`MailClientSpanInterceptor`,
    `IntakeReplySender`).
- **`QuarkusTransaction` in main:** `NotificationService`, `IntakeCompleteToolInputGuardrail` and
  `DevClaimEmailSeeder`.
- **Exceptions:** no checked exception type in main.
- **Entities:** `Claim`, `ClaimCorrespondence`, `ClaimImage` (`claim.model`) and `ClaimIntakeState` (`intake.model`).
- **Panache method references:**
  - Main code uses `Claim::findByClaimNumber` and `ClaimCorrespondence::findByMessageId`, which are the entities' own
    statics and fine.
  - The hazard is a reference to a static **inherited** from `PanacheEntityBase` (`Claim::listAll`,
    `ClaimImage::flush`), which `IntakeTemplatesTests` and `ClaimImageTests` currently guard only with comments.

### Key Decisions (user, 2026-11-13)

- **Library: `com.tngtech.archunit:archunit-junit6` 1.5.1.**
  - Released 2026-09-25 per Maven Central; Java 25 class files are supported since 1.4.1, and the `archunit-junit6`
    module since 1.5.0.
  - Its `archunit-junit6-engine-api` depends on `junit-platform-engine` **6.1.3**, the version the Quarkus BOM manages,
    so there's no platform conflict. (The older `archunit-junit5` engine depends on Platform 1.14.4; the BOM would
    override that, but the engine was built against JUnit 5.)
  - Rules are `@ArchTest static final ArchRule` fields in package-private `*Tests` classes carrying `@AnalyzeClasses`.
  - **The AssertJ convention doesn't apply to them:** they are declarative rules that fail with ArchUnit's own
    `AssertionError`, not hand-written assertions. Record that exception in `CLAUDE.md` → Testing.
- **Rule 2 means "the app uses only `ai.scoring`'s published API".** That is an explicit allow-list; today it holds
  just `DriftDetectionOutputGuardrail`, usable only from `org.parasol.chat..`.
- **The intake touches neither `ai.scoring` nor OTel baggage.** An `ai.scoring` Flow adapter owns the conversation id.
  Spike it first (task 01). If only an internal-API route works, or none does, bring it back to the user rather than
  quietly allow-listing `ConversationContext`.
- **Rules 1–3 cover main code only.** `ClaimRetrievalHasNoIntakeBookkeepingTests` moves into an intake test package.
- **`ConversationLayeringTests` is rewritten with ArchUnit**, so the repo has one mechanism.
- **Extra rules are all in.**
  - First batch:
    - boundary hardening;
    - the intake AI service opt-outs;
    - rule 4 widened app-wide;
    - coding conventions.
  - Second batch:
    - REST and persistence;
    - gotchas turned into rules (Panache method references, JSON-safe run data);
    - config, transactions and exceptions;
    - test naming.
- **Logging follows the user's convention.** A CDI bean uses `io.quarkus.logging.Log` and declares no logger field. A
  class that isn't a bean logs through a `private static final org.jboss.logging.Logger` field and never calls `Log`.
- **Break the intake's package cycles in #232, by moving classes only.** No interfaces may be added just to break a
  cycle, because readability matters as much as maintainability. A strict cycle-free rule then holds the result.
- **Existing data doesn't matter.** The demo always starts and seeds its database from scratch, so moving a type that
  Flow persists needs no migration.

- **Task 02 builds mechanism A from the spike (user, 2026-11-13).** See [spike-results.md](spike-results.md). Public SPIs
  only, so there is no `internal` API and no upstream issue:
  - a `CallableTaskProxyBuilder` (`ServiceLoader`, priority below `OTelTaskSpanProxy`'s 100) puts the run's id in OTel
    baggage around each call task's body;
  - a `WorkflowExecutionListener` bean records instance id → conversation id in `onWorkflowStarted`, ahead of Flow's
    OTel listener;
  - a span processor stamps Flow's own spans (`workflow.create`, `workflow.execute`, `task.execute`) by
    `flow.workflow.instance.id`.

  **The contract is instance metadata, not the run's input (user, 2026-11-13): no reflection.** The intake calls
  `instance.addMetadataIfAbsent("gen_ai.conversation.id", …)` between `flow.instance(run)` and `start()` (a public
  `WorkflowInstance` method, no cast), and the adapter reads `findMetadata("gen_ai.conversation.id", String.class)`.
  Verified on a second scratch branch with the same test classes. Stamping by trace with no baggage (mechanism B) also
  passed and was turned down: it's only right while a run owns its trace.

### Task Outcomes

- **Task 01 (spike, 2026-11-13):** an `ai.scoring.conversation.flow` adapter can attach the conversation id with no
  intake code. With `ConversationContext` removed from `ClaimIntakeDispatcher` and `ReviewRecoverer`,
  `IntakeSpanPropagationTests`, `IntakeTracingTests` and `IntakeLangfuseDeliveryTests` passed unchanged, and failed with
  either half of the mechanism switched off. Task 02 still has to put the intake's metadata call in one place and check the `ServiceLoader` registration in dev mode. The scratch branch is deleted.

### Caveats & Problems

- **`archRule.failOnEmptyShould` must stay `true`** (ArchUnit's default). A rule whose `that()` matches nothing, after a
  package rename say, should fail rather than pass vacuously.
- **ArchUnit doesn't see string constants.** The current `ConversationLayeringTests` scans the whole constant pool, so it
  also catches a string such as `"org/parasol/…"`; the ArchUnit rewrite won't. It also won't see Javadoc, which is
  correct.
- **Runtime coupling that no static rule sees:**
  - `FlowSpanTypingProcessor` reflects on Flow's naming to find an agentic interface.
  - The new Flow adapter reads the conversation id from a run's instance metadata, which the intake sets by key.
  - Both are conventions; document them where they live.
- **Every new rule must be shown to fail at least once** (task 09's mutation check). A rule that has never failed may be
  matching nothing.
- **Don't add a `@TestProfile`.** ArchUnit tests are plain (non-`@QuarkusTest`) classes, so they don't boot the
  application, and the boot count (3) doesn't change.
