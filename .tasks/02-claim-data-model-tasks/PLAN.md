# Issue 2: Claim Data Model Rework — Task Execution Plan

## Your Mission

Rework the `Claim` model:
- generated natural-id claim numbers from a PostgreSQL sequence (increment 1009)
- a category enum, including `Other`
- typed incident date/time
- unbounded subject/body/location

Then update the seed data, UI, tests and docs to match. Second of five issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/02-claim-data-model-tasks/PLAN.md`
**Tasks Directory:** `.tasks/02-claim-data-model-tasks/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/02-claim-data-model-tasks/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md` (coding style, AssertJ, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
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

- [ ] [task-01-generation-approach-spike.md](task-01-generation-approach-spike.md): Spike the claim-number generation approach
- [ ] [task-02-claim-entity.md](task-02-claim-entity.md): Rework the Claim entity (+ tests)
- [ ] [task-03-seed-data.md](task-03-seed-data.md): Rewrite the seed data
- [ ] [task-04-ui-updates.md](task-04-ui-updates.md): Update the UI for the new claim fields
- [ ] [task-05-documentation.md](task-05-documentation.md): Update documentation for the data model
- [ ] [task-06-verification.md](task-06-verification.md): Verify the data-model rework

---

## Shared Context

### Overview
These are preparatory schema changes for the email intake (issue 5), and they're useful on their own:
consistent claim numbers, real dates instead of prose, and categories that match the UI filter.

### Project Context
- `src/main/java/org/parasol/model/claim/Claim.java` is a Panache entity (`PanacheEntity`, numeric `id`).
  JSON uses snake_case via `@JsonNaming(SnakeCaseStrategy.class)`.
- `src/main/resources/import.sql` seeds six claims (ids 1–6) and restarts `claims_seq` at 7. It's
  loaded in dev/test by default, and in `%prod`/`%openshift` via `sql-load-script`.
- `src/main/resources/application.yml`:
  - `hibernate-orm.physical-naming-strategy: CamelCaseToUnderscoresNamingStrategy`
  - `jackson.serialization-inclusion: non-empty`
  - `rest.jackson.optimization.enable-reflection-free-serialization: true`
- UI: `ClaimsList.tsx` (category/status filters, label colours) and `ClaimDetail.tsx` ("Date and time" section).
- Tests: `ClaimResourceTests` (PanacheMock + recursive comparison); Playwright `ClaimsListPageTests`
  (expects exactly 6 claims) and `ClaimsDetailPageTests`.

### Key Decisions
- The numeric `id` stays the primary key. `claimNumber` is a `@NaturalId` with a unique constraint.
- Claim numbers come from a PostgreSQL sequence with `INCREMENT BY 1009`, formatted `CLM` + 8 digits.
  Not random-looking. Callers never set them.
- Category is an enum with `OTHER`. The API exposes display labels ("Single vehicle", …, "Other").
- `time` is replaced by `incidentDate` (`LocalDate`, required for intake) and `incidentTime` (`LocalTime`, optional).
  `ZonedDateTime` was rejected: emails rarely state a time zone.
- `subject`, `body`, `location` are unbounded `text`. `summary` and `sentiment` stay 5000 characters
  (issue 5 adds an output guardrail).
- `status` stays a free-text `String`.
- Seeded claim numbers may be rewritten. Seed rows otherwise keep their ids and content.

### Caveats & Problems
- Tests must never assert absolute claim numbers, and must delete any claim they create.
- The cluster database has a persistent volume. Sequence DDL in `import.sql` (if used) must drop and recreate.
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.