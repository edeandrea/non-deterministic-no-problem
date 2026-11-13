# Task 03: Add `archunit-junit6`, Rule 1, and Rewrite `ConversationLayeringArchTests`

**Type:** Test Infrastructure + Test Addition

## Goal

ArchUnit is on the test classpath and runs in the normal build. `ai.scoring`'s two rule sets are ArchUnit rules:
- rule 1: `ai.scoring` depends on nothing in `org.parasol`;
- the conversation core's layering, rewritten from the constant-pool scan.

## What to Do

1. **`pom.xml`:**
   - Add `<archunit.version>1.5.1</archunit.version>` to the sorted `<properties>` block.
   - Add a test-scoped `com.tngtech.archunit:archunit-junit6` dependency next to `assertj-core`.
   - Run `./mvnw dependency:tree -Dincludes=org.junit.platform` and confirm there's a single `junit-platform-engine`,
     at 6.1.3. If ArchUnit drags in anything else, say so before excluding it.
2. **`src/test/resources/archunit.properties`:** set `archRule.failOnEmptyShould=true` explicitly (ArchUnit's default),
   with a comment saying why: a rule matching no classes must fail, not pass. Leave everything else at its default.
3. **Rule 1:** new `src/test/java/ai/scoring/ScoringBoundaryArchTests.java`.
   - `@AnalyzeClasses(packages = "ai.scoring", importOptions = ImportOption.DoNotIncludeTests.class)`.
   - One `@ArchTest static final ArchRule`: no class in `ai.scoring..` depends on any class in `org.parasol..`.
   - It lives in `ai.scoring`, not `org.parasol`, so it travels with the library if `ai.scoring` is extracted.
4. **Rewrite `src/test/java/ai/scoring/conversation/ConversationLayeringArchTests.java`** with ArchUnit, keeping both rules
   and their meaning:
   - **The core** (`ai.scoring.conversation`, the package itself, not its sub-packages) references:
     - no adapter (`ai.scoring.conversation.*..`);
     - no `ai.scoring` package outside the conversation core;
     - no framework (`io.quarkus..`, `io.quarkiverse..`, `dev.langchain4j..`, `io.serverlessworkflow..`, `io.vertx..`);
     - nothing in `org.parasol..`.
   - **Adapters never reference each other:** `slices().matching("ai.scoring.conversation.(*)..").should().notDependOnEachOther()`.
   - Keep the class Javadoc explaining the rule, and update its last sentence: ArchUnit sees class references in
     signatures, bodies and annotations, but not string constants, which the old scan did.
5. **Check the build runs them.**
   - `./mvnw test -Dtest='ScoringBoundaryArchTests,ConversationLayeringArchTests'` runs both. Confirm in the surefire report
     that ArchUnit's engine executed the `@ArchTest` fields.
   - Then break rule 1 on purpose, for example with an `org.parasol` import in an `ai.scoring` class, and confirm
     `./mvnw test` **fails**. Revert. Record in `PLAN.md` how a failure reads.

## Files/Areas

- `pom.xml` (one property, one dependency)
- `src/test/resources/archunit.properties` (new)
- `src/test/java/ai/scoring/ScoringBoundaryArchTests.java` (new)
- `src/test/java/ai/scoring/conversation/ConversationLayeringArchTests.java` (rewritten)

## Key Points

- **`archunit-junit6`, not `archunit` core and not `archunit-junit5`** (user decision; see Key Decisions). Rules are
  `@ArchTest static final ArchRule` fields; there are no hand-written AssertJ assertions here, and that's the deliberate
  exception.
- **Main code only.** `ImportOption.DoNotIncludeTests` for rules 1–3; the `ai.scoring` tests that drive the chat bot
  (`ClaimBotQuery`, `LangfuseExportTestProfile`) are out of scope by decision.
- **Classes stay package-private, named `*Tests`** (the repo convention, and task 08's naming rule).
- **No Quarkus boot.** These are plain ArchUnit tests and must not carry `@QuarkusTest`.
- Each `ArchRule` gets a `.because("…")` stating the reason in a sentence, so a failure explains itself.

## Done When

- [x] `archunit-junit6` 1.5.1 is a test dependency, and the tree shows one `junit-platform-engine` (6.1.3).
- [x] `ScoringBoundaryArchTests` and the rewritten `ConversationLayeringArchTests` run under `./mvnw test` and are green.
- [x] A deliberate rule-1 violation fails the build (shown once, then reverted).
- [x] `./mvnw -B clean verify -Pollama` is green (688 tests, 0 failures, 2 skipped, 2026-11-13).
- [x] `./mvnw -B clean verify -Pollama-openai` is green: **left to CI on the PR, by user decision (2026-11-13).** Not run
      locally, since nothing in this task is profile-specific.
