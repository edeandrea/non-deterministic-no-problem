# Task 09: Documentation, Mutation Check, Verification and the PR

**Type:** Documentation + Verification

## Goal

Every document matches the code, every rule has been seen to fail, both CI profiles are green, and one PR into `main`
closes #232.

## What to Do

### 1. Documentation sweep

Earlier tasks updated what they touched. This task checks the whole set for consistency and catches what they missed.
- **`CLAUDE.md`:**
  - *Package structure*: the test-only `org.parasol.architecture`, and the intake layout after task 05.
  - *Conversation core*: the Flow adapter and the `conversationId` contract. "Adapters: `chat`" and "Task 11 needed no
    adapter" are no longer true.
  - *Testing*: a new entry on the architecture tests:
    - which class holds which rule;
    - `archunit-junit6` and why;
    - the AssertJ exception for declarative rules;
    - main code vs test code scope;
    - `failOnEmptyShould`;
    - that they're plain tests with no boot.
  - *Conventions*: "Dependencies point one way" now names `DomainBoundaryTests`.
  - *Gotchas*: every entry a rule now enforces points at it, without deleting the explanation.
- **`docs/`:** `docs/design/email-claim-intake.md`, `docs/claim-intake-flow.puml`, `docs/design/claim-intake-*.puml`,
  `docs/continuous-scoring-architecture.puml` and `docs/continuous-scoring-sequence.puml`. Find every mention of
  `ConversationContext`, a moved class's package, or the intake entering a conversation. Re-render with
  `./docs/render-diagrams.sh`.
- **`README.md`, `langfuse-evaluation.md`, `CODE_STANDARDS.md`:** grep for `ConversationContext`, `conversation id`,
  `ArchUnit` and the moved classes' old packages.
- **`.tasks/claim-intake-roadmap.md`:** mark #232 done in its status line.

### 2. Mutation check (not committed)

Break every rule once, run it, see the failure message, and revert. Earlier tasks did this one at a time; this task
does it on the finished branch, so a rule an earlier task later weakened is caught. Keep a table in `PLAN.md`:

| Rule | Mutation | Failed? |
|---|---|---|

Cover every rule in tasks 03–08. A rule that can't be made to fail is broken.

### 3. Verification

- `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai`, the two CI legs.
- Count the application boots in the log and confirm it's still **three**. ArchUnit tests must not add one.
- Note the wall-clock cost of the ArchUnit tests from the surefire report. Expect a few seconds; flag it if it's more.
- `quarkus:dev` still starts, the dev seeder produces its two claims, and a claim's traces in Langfuse still form one
  session. That's the conversation id, end to end, outside the tests.

### 4. Branch and PR

- Confirm with the user before branching. Suggested branch `gh232-architecture-tests`, off `main`.
- **Suggested PR title:** *Enforce the application's module boundaries with ArchUnit*. No closing keyword in the
  title.
- **Body:**
  - the rules and where each lives;
  - the two refactorings (conversation id adapter, intake package cycles);
  - the exemptions;
  - the mutation-check table;
  - ending with `Closes #232`.
- Commits end with `Assisted-By: Claude Code <noreply@anthropic.com>`. No claude.ai session links.

## Key Points

- **Don't delete a gotcha's explanation because a rule enforces it.** The rule says *that*; the text says *why*.
- **US English** everywhere, including the PR.

## Done When

- [ ] Every document named above is consistent with the code, and the diagrams are re-rendered.
- [ ] The mutation-check table in `PLAN.md` covers every rule, and every rule failed.
- [ ] Both CI profiles are green locally, still with three boots.
- [ ] Dev mode end-to-end still groups a claim's traces into one Langfuse session.
- [ ] The PR is open, with the body above, and CI is green on it.
