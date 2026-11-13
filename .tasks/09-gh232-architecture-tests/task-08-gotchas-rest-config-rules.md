# Task 08: REST and Persistence, Panache Method References, JSON-Safe Run Data, Config/Transactions/Exceptions, Test Naming

**Type:** Test Addition

## Goal

The second batch of rules the user chose on 2026-11-13. All hold today. Several turn a `CLAUDE.md` gotcha, currently
guarded only by a comment, into a build failure.

## What to Do

### REST and persistence: `org.parasol.architecture.RestAndPersistenceArchTests` (main code)

- `@jakarta.persistence.Entity` classes reside only in a `..model..` package.
- **No JAX-RS endpoint method** (HTTP-method annotation, as in task 07) has an `@Entity` type as its return type, as a
  parameter type, or as a generic type argument of either. "The REST layer never serializes entities" (`CLAUDE.md` →
  REST). Generic arguments need a custom `ArchCondition` over `JavaMethod.getReturnType()`/`getParameterTypes()`
  (`JavaParameterizedType.getActualTypeArguments()`), recursing into nested arguments.
- `@Path` classes are named `*Resource` and reside in a `..rest..` package.

### Panache method references: `org.parasol.architecture.PanacheMethodReferenceArchTests` (main **and** test code)

- **No method reference whose target resolves to a static method declared in
  `io.quarkus.hibernate.orm.panache.PanacheEntityBase` (or `PanacheEntity`).** For example `Claim::listAll` or
  `ClaimImage::flush`: Panache only enhances direct calls to its statics, so these fail at runtime with "did you forget
  to annotate your entity with @Entity?".
- **Still legal:** an entity's own statics (`Claim::findByClaimNumber`, `ClaimCorrespondence::findByMessageId`) and
  instance references (`ClaimImage::delete`).
- **Find out first what javac emits** as the method handle's owner for `Claim::listAll`, `Claim` or
  `PanacheEntityBase`, and write the predicate against the *resolved* member (`JavaMethodReference.getTarget()` →
  `resolveMember()` → owner), not the owner name. Prove it with a scratch reference in a test, then revert.
- Once green, the comments in `IntakeTemplatesTests` ("A lambda, not Claim::listAll…") and `ClaimImageTests` ("A lambda,
  not ClaimImage::flush…") can stay and also name the rule.
- This class imports test classes, so its `@AnalyzeClasses` has no `DoNotIncludeTests`.

### JSON-safe run data, in `org.parasol.intake.IntakeArchTests` (main code)

- "Pass ids through workflow/task data, not payloads" (`CLAUDE.md` → Gotchas, quarkus-flow). Flow persists every step's
  data to PostgreSQL.
- **Scope:** the types Flow persists. After task 05 that's `org.parasol.intake.model..` (`IntakeRun`, `IntakeState`,
  the outcome records, the decision contract `ReviewDecided`/`ReviewDecision`/`ReviewedFields`) **except
  `ClaimIntakeState`, which is a JPA entity, not run data**. Cross-check the set against `IntakeJsonRoundTripTests`,
  `IntakeOutcomeRoundTripTests` and `ClaimExtractionValueRoundTripTests`.
- **No field or record component has type `java.util.Optional`, `byte[]`, `java.io.InputStream` or an `@Entity`.**
  `ClaimExtraction` is documented as "JSON-safe: … no `Optional`".
- **Check the current state before fixing the list.** A legitimate exception becomes a named, commented exemption.

### Config, transactions, exceptions, in `org.parasol.architecture.CodingConventionArchTests` (main code)

- **`@ConfigMapping` prefixes:** `parasol.` in `org.parasol`, `aiscoring` in `ai.scoring` (equal to it or starting
  with `aiscoring.`), and never `quarkus.`. That's the #290 zero-unrecognized-keys invariant (`CLAUDE.md` → *The
  zero-unrecognized-keys invariant*, "Application config lives outside the `quarkus.` namespace").
- **`@ConfigProperty`**, on constructor parameters too, never names a `parasol.*` or `aiscoring.*` key; those belong in a
  `@ConfigMapping`. Today it names only `quarkus.mailer.*` (`MailClientSpanInterceptor`, `IntakeReplySender`). Reading
  parameter annotations needs `JavaParameter.getAnnotations()`.
- **`io.quarkus.narayana.jta.QuarkusTransaction`** is used only by `NotificationService`,
  `IntakeCompleteToolInputGuardrail` and `DevClaimEmailSeeder`, each a commented exemption giving why `@Transactional`
  doesn't fit. Read each class to write the reason; don't guess it.
- **Main code declares no checked exception type:** every class assignable to `Throwable` is assignable to
  `RuntimeException` (or `Error`). Domain exceptions are unchecked by convention.

### Test naming: `org.parasol.architecture.TestNamingArchTests` (test code of both roots)

- **Every class declaring a method annotated `@org.junit.jupiter.api.Test`, `@ParameterizedTest`, `@RepeatedTest` or
  `@TestFactory`, or an `@ArchTest` field or method, has a simple name ending in `Tests`.** A misnamed class (`FooSpec`,
  `FooIT`) falls outside surefire's default includes and silently never runs.
- Nested `@Nested` classes, if any appear, are judged by their enclosing class.
- **A class is named `*ArchTests` if and only if it is an ArchUnit class** (user, 2026-11-13; see `PLAN.md` → Key
  Decisions). An ArchUnit class is one carrying `@AnalyzeClasses` or declaring an `@ArchTest` field or method. Two
  rules, so each failure says which way it broke:
  - every ArchUnit class has a simple name ending in `ArchTests`;
  - no other class has one, so `-Dtest='*ArchTests'` selects exactly the rules.

## Files/Areas

- `src/test/java/org/parasol/architecture/RestAndPersistenceArchTests.java`, `PanacheMethodReferenceArchTests.java`,
  `TestNamingArchTests.java` (new)
- `src/test/java/org/parasol/architecture/CodingConventionArchTests.java` (from task 07)
- `src/test/java/org/parasol/intake/IntakeArchTests.java` (from tasks 05–06)
- `src/test/java/org/parasol/intake/reply/IntakeTemplatesTests.java`,
  `src/test/java/org/parasol/claim/model/ClaimImageTests.java` (comments only)
- `CLAUDE.md`: the Panache gotchas (two places), the quarkus-flow "pass ids" gotcha, the #290 invariant section, REST,
  and Testing's naming note. Each now says the rule is enforced and where.

## Key Points

- **Everything here holds today.** A failing rule on the current tree is a finding to raise, not a rule to loosen.
- **Custom conditions get a clear description**, so the failure message reads as a sentence. For example: "not take
  or return an @Entity, directly or as a type argument".
- **`ClaimIntakeState` is an entity in `intake.model`.** The JSON-safe rule must exclude it, while the `..model..` entity
  rule must include it.

## Done When

- [ ] `RestAndPersistenceArchTests`, `PanacheMethodReferenceArchTests` and `TestNamingArchTests` exist and are green. The
      JSON-safe rule and the config/transaction/exception rules are in their classes and green.
- [ ] Each rule was shown failing once:
  - an endpoint returning `Claim`;
  - a `List<Claim>` return;
  - `Claim::listAll` in a test;
  - an `Optional` field on `IntakeState`;
  - a `@ConfigMapping(prefix = "quarkus.parasol")`;
  - a `@ConfigProperty(name = "parasol.intake.address")`;
  - a checked exception class;
  - a test class named `FooSpec`;
  - an ArchUnit class named `FooTests`, and a plain test class named `FooArchTests`.
- [ ] `CLAUDE.md`'s matching entries name their rules.
- [ ] `./mvnw -B clean verify -Pollama` is green.
