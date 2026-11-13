# Task 06: Rules 4 (App-Wide) and 5, and the Intake AI Service Opt-Outs

**Type:** Test Addition

## Goal

Three invariants that today live only in comments and `CLAUDE.md` become build failures:
- interceptor-bound methods stay interceptable;
- no test mocks an intake agent;
- every intake AI service stays stateless.

## What to Do

### Rule 4, widened app-wide: `org.parasol.architecture.InterceptedMethodTests`

`@AnalyzeClasses(packages = {"org.parasol", "ai.scoring"}, importOptions = DoNotIncludeTests)`.

- **No method meta-annotated with `@jakarta.interceptor.InterceptorBinding` is `private`, `final` or `static`.** That
  covers `@Transactional` (itself an interceptor binding) and the intake's `@MailClientSpan`, and catches the next
  custom binding without a code change. Use `areMetaAnnotatedWith(InterceptorBinding.class)`, and confirm it matches a
  directly annotated `@Transactional` method. That's ArchUnit's documented semantics, but prove it with the mutation.
- **`@WithSpan` methods** (`NotificationService.updateClaimStatus` today): Quarkus turns `@WithSpan` into an interceptor
  binding at build time, so the same constraint applies even though the annotation itself isn't meta-annotated.
- **`@Scheduled` methods** (`InboxDepthMonitor.measure` today): find out what Quarkus already rejects at build time.
  Quarkus allows static `@Scheduled` methods; check whether it fails the build for a private one. Keep only the checks
  Quarkus doesn't already make, and record what was found.
- **Class-level bindings:** none exist today. Decide whether to forbid them in intake. A class-level `@Transactional`
  would make every private method silently non-transactional. Record the decision.
- The `because(…)` names the reason: ArC's interceptor subclass can only intercept an overridable method, and making one
  private or final silently drops the interceptor, with no compile error and no failing test.

Keep the "Non-private and non-final…" comments in the code: they explain *why* at the point where someone is tempted.
Don't add the four missing ones either. The rule is the guard now; record that in `CLAUDE.md`.

### Rule 5 and the opt-outs: `org.parasol.intake.IntakeArchitectureTests`

This class imports test classes too, so use no `DoNotIncludeTests` for these rules. Scope the rule-5 rules to test
classes, for example with `ImportOption.OnlyIncludeTests` on a second `@AnalyzeClasses` class, or by a
`resideIn`/location predicate. Pick whichever reads clearer, and don't mix main-code rules into a tests-only import.

- **Define "intake agent type" once, as a named `DescribedPredicate`.** It is a class in `org.parasol.intake..` that is:
  - annotated `@io.quarkiverse.langchain4j.RegisterAiService`, or
  - declares a method annotated with a langchain4j agentic annotation (`@Agent`, `@SequenceAgent`, `@ParallelAgent`,
    `@ConditionalAgent`, `@LoopAgent`, `@SupervisorAgent`, …).

  Find the annotations' real packages in the jars and match them by package rather than listing names, so a new
  agentic kind is covered automatically. Check that it matches every type in Shared Context → *The intake's agent
  types*; print the matched set once.
- **Rule 5:** no field annotated `@io.quarkus.test.InjectMock` or `@io.quarkus.test.junit.mockito.InjectSpy` has a raw
  type matching the predicate. The `because(…)` names the hazard:
  - quarkus-langchain4j resolves a sub-agent once, when the root agent is built, and unwraps the client proxy, so a mock
    can stay baked into the root for the life of the application;
  - the intake tests share one boot with about 54 other classes.
- **No `QuarkusMock.installMockForType`/`installMockForInstance` call in test code.** ArchUnit can't see which type is
  passed, so this bans the calls outright, which is fine since none exist today. Say in the `because(…)` that the ban
  can be narrowed to a bytecode check of the class-literal argument if a legitimate use ever appears.
- **Opt-outs:** every `@RegisterAiService` in `org.parasol.intake..` has `retrievalAugmentor =
  NoRetrievalAugmentorSupplier.class` and `chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class`. Read the
  annotation's attributes through ArchUnit's `JavaAnnotation.get(...)`. The `because(…)` names the hazard: a seventh
  agent without them silently gets the shared `"default"` chat memory and the policy-PDF retriever.

## Files/Areas

- `src/test/java/org/parasol/architecture/InterceptedMethodTests.java` (new)
- `src/test/java/org/parasol/intake/IntakeArchitectureTests.java` (created in task 05; add here, or split into a
  test-code class if one import can't serve both)
- `CLAUDE.md`:
  - *Gotchas*, "The six intake agents' statelessness opt-outs are load-bearing": say it's now enforced.
  - *Testing*: the "No test in this group may `@InjectMock` an intake agent" paragraph, enforced now.
  - *Gotchas*, "Prefer `@Transactional` to `QuarkusTransaction`": the self-invocation note, enforced now.

## Key Points

- **All three hold today.** If any rule fails on the current tree, that's a finding to raise, not a rule to loosen.
- **Rule 4 checks modifiers, not comments.** Four `@Transactional` methods have no comment at all (Shared Context).
- **`UnmatchedEmailAgent` is a final class with a static `@Agent`.** It matches the predicate harmlessly, since it
  can't be a mock target anyway; make sure its static method doesn't trip rule 4, which it shouldn't, because `@Agent`
  isn't an interceptor binding.
- Static annotated methods that aren't interceptor bindings stay legal: `@Produces static`, and langchain4j's
  `@BeforeCall`, `@Output`, `@ErrorHandler` and `@ActivationCondition`.

## Done When

- [ ] `InterceptedMethodTests` covers interceptor-binding methods app-wide, `@WithSpan`, and whatever `@Scheduled`
      check Quarkus doesn't already make. All green.
- [ ] Rule 5, the `QuarkusMock` ban and the opt-out rule are green, and the agent predicate's matched set equals the
      Shared Context table.
- [ ] Each rule was shown failing once:
  - a private `@Transactional` method;
  - a final `@MailClientSpan` method;
  - an `@InjectMock ClaimSummaryAgent` field;
  - a `QuarkusMock.installMockForType` call;
  - an intake `@RegisterAiService` without `chatMemoryProviderSupplier`.
- [ ] `CLAUDE.md`'s three entries point at their rules.
- [ ] `./mvnw -B clean verify -Pollama` is green.
