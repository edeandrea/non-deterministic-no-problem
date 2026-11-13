# Task 04: Rules 2 and 3, the Baggage Rule and the Boundary Hardening

**Type:** Test Addition (+ one test move)

## Goal

The application's top-level boundaries are enforced:
- `org.parasol` reaches `ai.scoring` only through its published API;
- the five domains depend on each other only in the documented directions;
- no new domain or cycle can appear unnoticed.

## What to Do

New test-only package `org.parasol.architecture`, class `DomainBoundaryArchTests`, with
`@AnalyzeClasses(packages = {"org.parasol", "ai.scoring"}, importOptions = ImportOption.DoNotIncludeTests.class)`.

### Rule 2: `ai.scoring`'s published API

- Classes in `org.parasol..` depend on classes in `ai.scoring..` **only** if the target is on an explicit allow-list,
  and only from the allowed package.
- **Today's list:** `ai.scoring.drift.DriftDetectionOutputGuardrail`, from `org.parasol.chat..`.
- Keep the list a named, commented constant in the test. Its comment says why the edge can't go: guardrails attach
  only by annotation, AI services are synthetic beans, and the guardrail must be able to fail the response.
- After task 02 the intake has no `ai.scoring` edge at all, so a new one fails the build until someone adds it on
  purpose.

### The baggage rule

No class in `org.parasol..` depends on `io.opentelemetry.api.baggage..`. This holds the user's decision that the app
never handles the conversation id itself; `ai.scoring` owns it. Other OTel use, such as the intake's `@MailClientSpan`
spans and `IntakeReplySender`'s `Context` wrap, stays allowed.

### Rule 3: the domain directions

Express it as a `layeredArchitecture().consideringOnlyDependenciesInLayers()` over the five domains:

| Layer | Package | May be accessed by |
|---|---|---|
| Chat | `org.parasol.chat..` | nobody |
| Notification | `org.parasol.notification..` | Chat |
| Claim | `org.parasol.claim..` | Notification, Intake |
| Intake | `org.parasol.intake..` | nobody |
| Problem | `org.parasol.problem..` | Claim, Intake |

Then add the hardening rules:
- **`claim.rest..` and `claim.seed..` are reachable only from `org.parasol.claim..`.** Notification and intake use
  `claim.model` only.
- **`org.parasol.problem..` depends on no other `org.parasol` package.**
- **Top-level slices are free of cycles:** `slices().matching("org.parasol.(*)..")` and
  `slices().matching("ai.scoring.(*)..")`. Intake's own sub-packages come in task 05, after the refactoring.
- **Every class in `org.parasol..` resides in one of the five known domains**, so a sixth domain fails the build until
  it's added to the layers on purpose.

### The test move

Move `src/test/java/org/parasol/claim/rest/ClaimRetrievalHasNoIntakeBookkeepingTests.java` to
`src/test/java/org/parasol/intake/model/`. It's an invariant about `ClaimIntakeState`, an intake type, and it uses
`IntakeScenario`. Change only the package and imports. It stays in the bare `%test` group.

## Files/Areas

- `src/test/java/org/parasol/architecture/DomainBoundaryArchTests.java` (new; new test-only package)
- `src/test/java/org/parasol/claim/rest/ClaimRetrievalHasNoIntakeBookkeepingTests.java` → `…/intake/model/`
- `CLAUDE.md`: the package-structure paragraph that lists the test-only packages (`org.parasol.ui`, `org.parasol.testing`,
  `org.parasol.testing.mail`) gains `org.parasol.architecture`, and the *Testing* entry for
  `ClaimRetrievalHasNoIntakeBookkeepingTests` names its new package.

## Key Points

- **Main code only for rules 1–3** (user decision). Test code reaches across freely: `org.parasol.ui` and
  `org.parasol.testing` by design, and the `ai.scoring` tests that drive the chat bot.
- **`ClaimService`'s `@OutputGuardrails(DriftDetectionOutputGuardrail.class)` is an annotation-parameter dependency.**
  Confirm ArchUnit counts it: temporarily drop the allow-list entry and watch the rule fail. That guards against a rule
  that's too blind to have caught anything.
- **A rule with an exception needs one combined predicate, not `andShould()`** (task 03's finding; see `PLAN.md` → Task
  Outcomes). `noClasses()…should().x().andShould().y()` means "never x, and never y", not "never both together". Rule 2's
  allow-list is that shape: pass one `DescribedPredicate` to `dependOnClassesThat(…)`, as
  `ConversationLayeringArchTests.THE_CORE_REFERENCES_NOTHING_ELSE_IN_SCORING` does.
- **Each rule gets a `.because(…)`** that names the reason: an extractable library, a separate intake app, or a
  deliberate new domain.
- Rule descriptions should read well in a failure message. Prefer ArchUnit's fluent API over custom
  `ArchCondition`s unless the fluent API can't express the rule.

## Done When

- [ ] `DomainBoundaryArchTests` holds rule 2 (with its allow-list), the baggage rule, rule 3 as a layered architecture, the
      `claim.rest`/`claim.seed` rule, the `problem` rule, both cycle rules and the known-domains rule, all green.
- [ ] Removing the allow-list entry makes rule 2 fail (shown once, reverted).
- [ ] `ClaimRetrievalHasNoIntakeBookkeepingTests` lives in `org.parasol.intake.model` and is green.
- [ ] `CLAUDE.md` names the new test-only package and the moved test.
- [ ] `./mvnw -B clean verify -Pollama` is green.
