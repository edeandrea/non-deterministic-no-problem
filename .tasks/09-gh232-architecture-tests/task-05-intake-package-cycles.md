# Task 05: Break the Intake's Package Tangle by Moving Classes, Then Enforce It

**Type:** Refactoring (moves only) + Test Addition

## Goal

The intake's sub-packages form a directed acyclic graph, and a rule keeps it that way. Today nine of them are one
strongly connected group; see Shared Context → *The intake's package tangle*. The fix moves classes; it adds no
interfaces, because the user weighs readability as much as maintainability.

## The Three Rules the Layout Follows

1. **`intake.model` and `intake.observability` are leaves.** Everyone depends on them. They depend on no other intake
   package except `config`, the shared `org.parasol.intake` root (`MissingItem`), and, for `observability`, `model`.
2. **A step sub-package (`workflow.mail`, `.outcome`, `.policy`, `.review`) never depends on `workflow`'s root.**
3. **Whatever needs `ClaimIntakeFlow` itself lives in `workflow`'s root**, next to it.

## What to Do

### 1. Design, then approval (MANDATORY)

Before moving anything:
- re-run the analysis (`jdeps -verbose:class` over `target/classes`, or an ArchUnit cycle rule run once to print the
  cycles);
- present the target layout: each package's contents after the moves, and what it may depend on;
- show every file that moves or changes, in full;
- show every member whose visibility must widen because it is now used across packages;
- show every test that moves.

Wait for approval.

### 2. The planned moves (confirm in the design)

| Cycle | Move |
|---|---|
| Steps → root's shared state | `IntakeRun`, `IntakeState`, `EmailRequeued` → `intake.model`. That package's documented purpose is already "the intake's records that Flow persists and later steps read". |
| `workflow` ↔ `review` | `ReviewDecided`, `ReviewDecision`, `ReviewOutcome`, `ReviewedFields` → `intake.model`, as the decision contract between reviewer and run. `ReviewDecisionPublisher.REVIEW_DECIDED` (the only thing `ClaimIntakeFlow` uses from the publisher) becomes a constant on `ReviewDecided`. The publisher stays in `review`, so `review` depends on `workflow` and never the reverse. |
| `workflow.review` → `ClaimIntakeFlow` | `SupersedeStep`, `ReviewReclaimer`, `ReviewRecoverer` → `workflow` root. They start or cancel runs, which makes them peers of the flow; the root already holds steps (`AgentStep`, `ClaimMatchingSteps`). `workflow.review` keeps the review wait's own steps: `ReviewDecisionSteps` and `AfterDecision`. |
| `observability` → `mailbox` | `IntakeActivity.EmailAccepted` carries a new observability tag enum for the skip reason instead of `SkippedAttachment.Reason`, the same pattern as `EmailOutcome`, `MatcherResult` and `ReviewAgreement`. The publisher maps the reason. |
| `observability` → `review` | Disappears once `ReviewOutcome` is in `model`: `ReviewDecisionApplied` and `IntakeMetrics` use it from there. |
| `CorrespondenceText` | Decide in the design: `intake.model` if a sub-package or `review` still needs it after the moves (today `ApplyOutcomeStep` and `ClaimSentimentReassessor` do), otherwise the `workflow` root. |

After the moves, re-run the analysis and confirm no strongly connected group is left in `org.parasol.intake..`.

### 3. The rules

Add them to `src/test/java/org/parasol/intake/IntakeArchTests.java`, created here. Tasks 06 and 08 add to it.
- **No cycles:** `slices().matching("org.parasol.intake.(**)").should().beFreeOfCycles()`. Check that `(**)` slices
  nested packages separately (`workflow` vs `workflow.review`); if it doesn't, use the form that does.
- **Leaves:** `intake.model..` depends on no other `org.parasol.intake` package except the root package itself.
  `intake.observability..` depends only on `intake.model..`, `intake.config..` and the root.
- **The step rule:** classes in `org.parasol.intake.workflow.(*)..` don't depend on classes residing directly in
  `org.parasol.intake.workflow`.

### 4. Tests and docs move with the classes

- Test packages mirror main, so the tests of each moved class move too, for example `IntakeJsonRoundTripTests` if its
  subjects are now all in `model`.
- `CLAUDE.md`'s package table: the `intake.model`, `intake.review`, `intake.workflow`, `intake.workflow.review` and
  `intake.observability` rows. Also every prose mention of a moved class's package; grep for each class name.
- `docs/design/email-claim-intake.md`, `docs/claim-intake-flow.puml` and `docs/design/claim-intake-*.puml`, wherever
  they name a package. Re-render with `./docs/render-diagrams.sh`.

## Files/Areas

- `src/main/java/org/parasol/intake/workflow/` (`IntakeRun`, `IntakeState`, `EmailRequeued`, `CorrespondenceText`)
- `src/main/java/org/parasol/intake/workflow/review/` (`SupersedeStep`, `ReviewReclaimer`, `ReviewRecoverer`)
- `src/main/java/org/parasol/intake/review/` (`ReviewDecided`, `ReviewDecision`, `ReviewOutcome`, `ReviewedFields`,
  `ReviewDecisionPublisher`)
- `src/main/java/org/parasol/intake/observability/IntakeActivity.java`, `IntakeMetrics.java` (+ the new tag enum)
- `src/main/java/org/parasol/intake/mailbox/SkippedAttachment.java` (unchanged, unless the design says otherwise)
- Every importer of the above, and their tests
- `src/test/java/org/parasol/intake/IntakeArchTests.java` (new)
- `CLAUDE.md`, `docs/design/email-claim-intake.md`, the intake `.puml` diagrams and PNGs

## Key Points

- **Moves only.** No new interface or abstraction exists just to break a cycle. If a cycle can't be broken by a move,
  stop and bring it to the user.
- **Moving a class is safe; renaming an AI service is not** (`CLAUDE.md` → Conventions). None of the moved classes is an
  AI service.
- **Existing data doesn't matter** (user): the demo database starts fresh. Still run `IntakeJsonRoundTripTests` and the
  other round-trip tests, since they're what Flow persists with.
- **Keep visibility minimal.** A member that must widen to `public` is listed in the design. `ReviewDecisionSteps`'
  public `@Transactional` methods and the package-private ones elsewhere must stay non-private (rule 4, task 06).
- **No behavior change.** The whole intake suite (bare `%test` group), the UI tests and `IntakeMetricsTests`' exact meter
  deltas stay green without assertion edits. A metric's tag values must not change: the new skip-reason tag enum has the
  same values on the wire as today.
- **`GrafanaDashboardTests` and `IntakeMeterSchema`** read meter tags; confirm they're untouched by the tag enum change.

## Done When

- [ ] The design (layout, every affected file, visibility changes, test moves) was presented and approved.
- [ ] No strongly connected group remains in `org.parasol.intake..` (analysis output recorded in `PLAN.md`).
- [ ] `IntakeArchTests` holds the no-cycles, leaf and step rules, all green, and each was shown failing once on a
      deliberate back-edge.
- [ ] No production behavior changed: intake, UI, metrics, dashboard and round-trip tests are green with untouched
      assertions.
- [ ] `CLAUDE.md`'s package table, the design doc and the diagrams match the new layout, and the PNGs are re-rendered.
- [ ] `./mvnw -B clean verify -Pollama` and `-Pollama-openai` are green.
