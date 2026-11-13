# Task 07: Coding-Convention Rules

**Type:** Test Addition (+ one logging fix)

## Goal

The conventions in `CLAUDE.md` and the global style rules that a static check can see become ArchUnit rules over both
roots. Each known exception is a named, commented constant in the test, not a silent omission.

## What to Do

New `src/test/java/org/parasol/architecture/CodingConventionArchTests.java`,
`@AnalyzeClasses(packages = {"org.parasol", "ai.scoring"}, importOptions = DoNotIncludeTests)`.

### JAX-RS resources

- Classes annotated `@jakarta.ws.rs.Path` are package-private.
- Their methods annotated with an HTTP-method annotation (meta-annotated `@jakarta.ws.rs.HttpMethod`: `@GET`, `@POST`,
  …) are `public`. Quarkus REST serves a 404 for every non-public endpoint (`CLAUDE.md` → Conventions).
  `@ServerExceptionMapper` methods are exempt by construction, since they carry no HTTP-method annotation.

### Injection and executors

- No field annotated `@jakarta.inject.Inject` in main code. **Exemption:** `NotificationService` (the legacy class
  `CLAUDE.md` names).
- No dependency on `org.eclipse.microprofile.context.ManagedExecutor`. Background work goes on the `@VirtualThreads`
  `ExecutorService`.

### Logging (the user's convention)

- **A CDI bean** uses `io.quarkus.logging.Log` and declares no `org.jboss.logging.Logger` field.
  - "Bean" means annotated with an annotation meta-annotated `@jakarta.inject.Scope` or
    `@jakarta.enterprise.context.NormalScope`, or with `@Dependent`, `@jakarta.interceptor.Interceptor` or
    `@jakarta.ws.rs.Path`. Write it as one named `DescribedPredicate`.
- **A class that isn't a bean** logs through a `private static final org.jboss.logging.Logger` field and never calls
  `io.quarkus.logging.Log`.
- **Exemption:** `ai.scoring.conversation.ConversationEvents`, a bean that keeps a JBoss `Logger` field because the
  conversation core may not reference `io.quarkus` (`ConversationLayeringArchTests`).
- **Fix today's violator first:** `org.parasol.intake.mailbox.InboxIdle` is a `final` class built with `new` that calls
  `Log`. Give it a `private static final Logger LOG = Logger.getLogger(InboxIdle.class)` and switch its calls to that.
- **Settle `ai.scoring.drift.DriftDetectionChatRouteExceptionHandler`.** It has no scope annotation, only a
  `@ChatRouteExceptionHandler` method, and calls `Log`. Check ArC's bean list (Dev UI → ArC → Beans, or a test that
  looks it up). If the chat-routes extension makes it a bean, add `@ChatRouteExceptionHandler`-bearing classes to the
  bean predicate, with a comment. If it isn't one, it's a violator to fix like `InboxIdle`.
- No `System.out`/`System.err` (field access on `java.lang.System`), no `Throwable.printStackTrace`, no
  `java.util.logging..` and no `org.slf4j..` in main code.

### Dates and Lombok

- No dependency on `java.util.Date`, `java.util.Calendar`, `java.text.SimpleDateFormat`, `java.sql.Date` or
  `java.sql.Timestamp`. **Exemption:** `org.parasol.intake.mailbox.MimeMessageParser`, which adapts Jakarta Mail's
  `Date` to `Instant`, as its comment says.
- No dependency on `lombok..`.

## Files/Areas

- `src/test/java/org/parasol/architecture/CodingConventionArchTests.java` (new; task 08 adds to it)
- `src/main/java/org/parasol/intake/mailbox/InboxIdle.java` (logging fix)
- `src/main/java/ai/scoring/drift/DriftDetectionChatRouteExceptionHandler.java` (only if it turns out not to be a bean)
- `CLAUDE.md` → Conventions: state the logging rule as the user put it (bean → `Log`; non-bean → `Logger` field), and
  note that these conventions are enforced by `CodingConventionArchTests`.

## Key Points

- **Design approval before touching `InboxIdle`** (and the chat-route handler, if needed): show the changed file in
  full first.
- **Exemptions are named, commented constants, never inlined.** Each comment says *why*, so the next reader can tell
  whether the exemption is still justified.
- **`ConversationEvents` is the deliberate exception to "a bean uses `Log`",** not an oversight. Make that clear in the
  constant's comment.
- Don't extend the bean predicate to guess at Quarkus's implicit beans (classes with observers or producers but no
  scope). If one shows up as a violation, decide it explicitly, the same way as the chat-route handler.

## Done When

- [ ] `CodingConventionArchTests` covers resources, field injection, `ManagedExecutor`, the bean/non-bean logging rule,
      console/JUL/SLF4J, legacy date types and Lombok, all green.
- [ ] `InboxIdle` logs through a JBoss `Logger` field, and `DriftDetectionChatRouteExceptionHandler`'s bean status is
      settled and recorded in `PLAN.md`.
- [ ] Each rule was shown failing once on a deliberate violation.
- [ ] `CLAUDE.md` → Conventions states the logging rule and names the test.
- [ ] `./mvnw -B clean verify -Pollama` is green.
