# Issue 2: Claim Data Model Rework — Task Execution Plan

## Your Mission

Rework the `Claim` model:
- generated natural-id claim numbers from a PostgreSQL sequence (increment 1009)
- a category enum, including `Other`
- typed incident date/time
- unbounded subject/body/location

Then update the seed data, UI, tests and docs to match. Second of five issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/02-gh213-claim-data-model/PLAN.md`
**Tasks Directory:** `.tasks/02-gh213-claim-data-model/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/02-gh213-claim-data-model/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `CODE_STANDARDS.md` (coding style, AssertJ, commit rules, documentation policy).
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

- [x] [task-01-generation-approach-spike.md](task-01-generation-approach-spike.md): Spike the claim-number generation approach
- [x] [task-02-claim-entity-and-seed-data.md](task-02-claim-entity-and-seed-data.md): Rework the Claim entity and rewrite the seed data (+ tests)
- [x] [task-03-ui-updates.md](task-03-ui-updates.md): Update the UI for the new claim fields
- [x] [task-04-documentation.md](task-04-documentation.md): Update documentation for the data model
- [x] [task-05-verification.md](task-05-verification.md): Verify the data-model rework

The old tasks 02 (entity) and 03 (seed data) were merged after task 01. Changing the entity breaks seed loading until
`import.sql` matches, so neither could be verified alone. Tasks 04–06 were renumbered 03–05.

---

## Shared Context

### Overview
These are preparatory schema changes for the email intake (issue 5), and they're useful on their own:
consistent claim numbers, real dates instead of prose, and categories that match the UI filter.

### Project Context
- `src/main/java/org/parasol/claim/model/Claim.java` is a Panache entity (`PanacheEntity`, numeric `id`).
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

#### Claim-number generation: DECISION (user, 2026-10-05): the "mix"

The user chose the mix over the Java-side generator after the follow-up spikes below.
- **Generator:** plain `PanacheEntity`, plus a `@ClaimNumber` meta-annotation (`@ValueGenerationType(generatedBy = ClaimNumberGenerator.class)`).
  Hibernate only allows `@ValueGenerationType` on annotation types.
  - `ClaimNumberGenerator` implements `OnExecutionGenerator` + `ExportableProducer`.
  - It's INSERT only, with no column in the SQL and no property value written.
  - `registerExportables` registers `claim_number_seq` (`start with 1000000 increment by 1009 maxvalue 99999999`).
- **Field:** `@NaturalId @ClaimNumber @ColumnDefault("'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')")`,
  `@Column(unique = true, nullable = false, updatable = false)`.
- **What the database does:** it fills the number via `insert … returning claim_number`. Raw SQL inserts (including `import.sql`) are
  numbered by the default, so seeds **omit** the column.
- **Rules:** see "Hybrid edge cases" below. Never set `claim_number`; never assert absolute or contiguous numbers.
- **Kept `CLM`** (user asked about dropping it): the prefix is what #216's code-side `CLM\d+` matching and the
  `EmailContainsRequiredInformationOutputGuardrail` substring check depend on. Dropping it wouldn't remove the second sequence.

#### Claim-number generation: first spike (task 01, Hibernate ORM 7.4.9.Final via Quarkus 3.40.1)

**Initially chosen, later superseded by the mix above: Java-side generator.**
- A custom `@ValueGenerationType` annotation backed by `ClaimNumberGenerator`, which implements
  `BeforeExecutionGenerator` + `ExportableProducer`.
- `registerExportables(Database)` registers `claim_number_seq` in Hibernate's relational model
  (`namespace.createSequence(..., new Sequence(..., 1000000, 1009))`). Schema management therefore owns it:
  - drop-and-create emitted `drop sequence if exists claim_number_seq` and
    `create sequence claim_number_seq start with 1000000 increment by 1009`;
  - **no sequence DDL in `import.sql`**, and the cluster's persistent volume is handled because Hibernate drops what it registered.
- `generate()` fetches `nextval` through **raw JDBC** (`session.getJdbcCoordinator()` + the dialect's
  `getSequenceSupport().getSequenceNextValString(name)`) and formats `CLM%08d`.

**Evidence (throwaway `@QuarkusTest`, `-Pollama`, 4/4 green, prototype deleted):**
- Two consecutive inserts got `CLM01000000` → `CLM01001009` → `CLM01002018` (exact 1009 increment).
- A caller-assigned value (`CLM-CALLER-SET`) was overwritten on insert (`currentValue` is passed in and ignored). Callers can't set it.
- `@NaturalId` works. Hibernate 7.3+ deprecates `bySimpleNaturalId`; use
  `session.find(Claim.class, number, KeyType.NATURAL)`. It returns `null` when not found, so wrap it in `Optional.ofNullable`.
- `@Column(unique = true, nullable = false, updatable = false)` produced `varchar(255) not null unique`.
  A raw duplicate insert failed with `duplicate key`.
- The number is assigned **at flush**, not at `persist()`, because the Panache id comes from a pooled sequence and the insert is deferred.
  Code that needs the number in the same transaction must `flush()` / `persistAndFlush()`.

**Traps found:**
- **Never run a session query inside `generate()`.** `session.createNativeQuery(...).getSingleResult()` auto-flushes,
  which re-enters the pending insert, which calls `generate()` again → `StackOverflowError`. Use JDBC via the `JdbcCoordinator`.
- **Rejected in this first spike, then reinstated: database-side default** (`@ColumnDefault("'CLM' || lpad(nextval('…')::text, 8, '0')")`
  + `@Generated(event = INSERT)`).
  It worked mechanically: one `insert … returning claim_number`, no extra round trip.
  But declaring its sequence with a class-level `@SequenceGenerator` **hijacked the `PanacheEntity` id generator**:
  - no `<table>_seq` was created;
  - ids were drawn from the claim-number sequence (pooled, allocation 1009);
  - one claim-number value was burned.
  - **Cause:** the JPA 3.2 default-generator rule (`GeneratorBinder.handleDefaultGenerator` / `determineImpliedGenerator`).
    `PanacheEntity.id` is a bare `@GeneratedValue` (AUTO, no name), and with exactly one local generator in scope Hibernate uses it.
  On `Claim` that would break `ALTER SEQUENCE claims_seq RESTART WITH 7`. An `orm.xml` `<database-object>` isn't a safe
  fallback either: auxiliary objects bound from mapping XML are created **after** tables, and the column default needs
  the sequence first. That wasn't tested further at the time; the follow-up spikes below found the `ExportableProducer` route instead.
  - **This rejection was later reversed:** the final mix keeps exactly this column default. Only the `@SequenceGenerator` declaration
    was the problem, and the mix replaces it with an `ExportableProducer` (see DECISION above).

**First six numbers (task 02 seeds, in id order):** `CLM01000000`, `CLM01001009`, `CLM01002018`, `CLM01003027`,
`CLM01004036`, `CLM01005045`. With the mix, these come from the column default, so no `RESTART` of `claim_number_seq` is needed.
Capacity before overflowing 8 digits: about 98,000 claims.

#### Follow-up spike (user question: `PanacheEntityBase` + two `@SequenceGenerator`s)

- **`PanacheEntityBase` with an explicit `@Id @SequenceGenerator("claims_seq") @GeneratedValue(generator = …)` plus a second,
  unused class-level `@SequenceGenerator` for the claim number: fails.**
  - The id generator no longer gets hijacked.
  - But Hibernate only exports sequences for generators that some `@GeneratedValue` uses, so `create sequence` was never emitted
    for the claim-number sequence.
  - `CREATE TABLE` then failed with `relation "…_number_seq" does not exist`, and the table was never created.
- **Hybrid: works (3/3, `-Pollama`).** Plain `PanacheEntity`, `@ColumnDefault("'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')")`,
  and a custom `@ValueGenerationType` whose generator implements `OnExecutionGenerator` + `ExportableProducer`.
  - It writes no column value, and `registerExportables` registers the sequence (start 1000000, increment 1009).
  - DDL order: `create sequence claims_seq`, then `create sequence claim_number_seq … increment by 1009`, then `create table` with the default.
  - Inserts use `insert … (body, id) values (?, ?) returning claim_number`. That's one statement, no `nextval` round trip and no JDBC code.
  - Ids came from the entity's own sequence (`id=1`, `2`); numbers were `CLM01000000`, `CLM01001009`.
  - A caller-set `CLM-CALLER-SET` was ignored. Natural-id `find` works.
  - **A raw SQL insert without `claim_number` got `CLM01002018` from the default**, so `import.sql` can omit the column.
  - **Status: adopted (see DECISION above).**

#### Hybrid edge cases (second follow-up spike, 13/13, `-Pollama`, prototype deleted)

**Raw and bulk inserts: all fine.**
- A multi-row `VALUES (…), (…), (…)` gave each row its own number in row order (`CLM01000000`, `CLM01001009`, `CLM01002018`).
- `INSERT … SELECT … generate_series` also gave per-row numbers, increasing.
- `DEFAULT` keyword: numbered. HQL `insert into Claim(body) values (…)`: numbered.

**Gotchas:**
1. **lpad truncates silently on overflow.** Past 99,999,999, `lpad('100000000', 8, '0')` returns `'10000000'`, so the number
   is `CLM10000000`: wrong, and it could collide.
   - **Fix:** the sequence gets `maxvalue 99999999`, passed as `Sequence` options. It renders as
     `… increment by 1009 maxvalue 99999999` and turns overflow into a loud `nextval: reached maximum value` error.
   - Capacity is still about 98,000 claims.
2. **Explicit `NULL` isn't "use the default".** It fails the NOT NULL constraint. Raw SQL must omit the column or write `DEFAULT`.
3. **A hand-picked explicit number isn't checked against the sequence.** Writing the *next* value made the next entity insert fail
   with a duplicate key. The retry succeeded only because the failed `nextval` was burned.
   - Rule: **no SQL, seed or test may ever set `claim_number`.**
4. **Gaps are normal.** A rolled-back insert burned its number: `last_value` 1008072 → 1009081 with no row. Under concurrency,
   numbers needn't follow commit order. Never assert contiguity or absolute values (the existing caveat).
5. **JDBC insert batching is off for `Claim`.** Hibernate disables it for entities with insert-generated properties
   (`InsertCoordinatorStandard`). 5 inserts took 5 statements, against 2 for a plain entity with `statement-batch-size=20`.
   It doesn't matter here: claims arrive one at a time.
6. **Immutable natural id:** changing `claimNumber` on a managed entity throws `HibernateException: An immutable natural identifier
   … was altered`. The **whole flush fails**, so other changes in that transaction (e.g. a status update) are lost too. That's the
   right default; just don't touch the field.
7. **Seed numbers depend on insert order** now that seeds omit the column. Reordering or inserting a seed row renumbers the later
   ones. #214 seeds images by claim number, so it must look numbers up and never hard-code them.
8. **PostgreSQL-specific default** (`::text`, `lpad`, `nextval`). All environments are PostgreSQL, so that's fine. Not verified:
   with a migration tool like Flyway instead of drop-and-create, `ExportableProducer` would do nothing and the sequence DDL would
   have to be hand-written.

Also confirmed: a natural-id `find` in the **same** session right after `persistAndFlush()` returns the same instance.

#### Other spike checks
- `@Column(length = Length.LONG32)` → PostgreSQL `text` (`information_schema` `data_type = text`).
- `@Enumerated(EnumType.STRING)` → `varchar(255)` **plus a CHECK constraint** listing the enum names. Seed rows must use exact
  constant names, and adding a constant needs a schema re-create (fine under drop-and-create).
- `LocalTime` → `time(0)` (seconds precision; fractional seconds are dropped).
- Reflection-free serialization (`enable-reflection-free-serialization: true`) **honours `@JsonValue`**: `"category":"Multiple vehicle"`.
  `@JsonCreator` deserialization works ("Other", "Theft"), and so do `@JsonNaming(SnakeCase)` and
  `serialization-inclusion: non-empty` (`incident_time` omitted when null). `LocalTime` serializes as `"15:30:00"`.
  No alternative is needed.

### Task Outcomes
- **Task 01:** the **mix** was adopted (user, 2026-10-05; see DECISION above).
  - It took three spike rounds.
  - **Round 1** first picked a Java-side `BeforeExecutionGenerator`, because the plain DB-side default needed a `@SequenceGenerator`, and
    that took over the entity's id generation.
  - **Round 2** showed `PanacheEntityBase` + two `@SequenceGenerator`s fails (unused generators aren't exported), and that the mix works.
  - **Round 3** tested the mix's edge cases and added `maxvalue 99999999`.
  - The mix keeps the DB default's advantage (raw SQL and seeds numbered automatically, one statement per insert) without JDBC code
    in a generator.

- **Task 02:** `Claim` was reworked with `ClaimCategory` (+ `UnknownClaimCategoryException`), `@ClaimNumber`, `ClaimNumberGenerator` and
  `Claim.findByClaimNumber`. `import.sql` now omits `claim_number` and uses enum names, typed incident date/time and `NULL`s.
  - **Dominic Toretto (id 4):** "last night" became the plausible fixed date `2024-02-03`, with no time.
  - **Results under `-Pollama` with stub keys:**
    - `ClaimTests` 14/14, `ClaimCategoryTests` 16/16, `ClaimResourceTests` 10/10, `ClaimsListPageTests` 2/2.
    - `NotificationServiceTests` 7/8. Only `emailSendsWhenUserExists` failed, with the known secret-dependent guardrail
      "rewritten output" error.
  - **Both `test-compile` profiles pass.**
  - **For task 03:** `ClaimsListPageTests` already asserts the category *label* (`claim.category.label()`). The UI renders the JSON label
    unchanged, so only the "Other" filter option and the detail page's date/time remain.

- **Task 03:** the UI now shows the new fields.
  - `ClaimDetail.tsx` renders the incident via a new `utils/formatIncident.ts` ("January 2, 1955 at 3:30 PM", or just the date).
    - It parses the ISO strings by hand: `new Date("1955-01-02")` is UTC midnight and would show the previous day west of UTC.
    - It formats the time by hand because `Intl` output differs between browsers.
  - `ClaimsList.tsx` gains the "Other" filter. Both filters' `aria-label`s were the identical `FormSelect Input`; they're now
    "Filter by category" / "Filter by status", so Playwright can target them.
  - **Tests:**
    - Jest `formatIncident.test.ts` 25/25.
    - Playwright `ClaimsListPageTests` 5/5: label filters for the two seeded categories, plus "Other" with a committed claim
      that is deleted in `finally`.
    - `ClaimsDetailPageTests#showsIncidentDateAndTime` 3/3 (claims 1, 4 and 6).
    - `package -DskipTests -Pollama` builds the frontend.
  - **Pre-existing Jest breakage: fixed here (user's request).** `app.test.tsx` failed on `main` too. The cause was two layers:
    1. `jest.config.js` mapped CSS and assets to `__mocks__/styleMock.js` / `fileMock.js`, which never existed in git history.
       They were added.
    2. Once past that, Jest choked on `ClaimsList.tsx`'s deep `@patternfly/react-icons/dist/esm/...` import. A
       `moduleNameMapper` entry now points Jest at the CommonJS `dist/js/...` build; webpack is unaffected.
    - **Result:** 30 tests (29 pass, 1 was already `it.skip`). The first run wrote `src/app/__snapshots__/app.test.tsx.snap`;
      it's stable (deterministic OUIA ids, no paths or ports) and passes with `--ci`.
    - **Wired into Maven:** the Playwright classes switched to `QuinoaTestProfiles.EnableAndRunTests`
      (`quarkus.quinoa.run-tests=true`). A deliberately failing Jest test was confirmed to fail the Maven run.
    - **Docs:** `CLAUDE.md` (Frontend + Testing) and `src/main/webui/README.md` (new Tests section).

- **Side fix (user-reported, 2026-10-05):** `LangfuseDatasetSampleLoaderTests` failed to boot under the default profile with
  `AuthenticationException` (OpenAI 401, `invalid_api_key: changeme`).
  - **Cause:** its `KeysTestProfile` stubbed the key but left boot-time Easy RAG ingestion on, so startup called the real
    embeddings API. It only passed when a cached `easy-rag-embeddings.json` skipped ingestion.
  - **Fix:** both `KeysTestProfile`s (that one and `DriftDetectionOutputGuardrailTests`) now set
    `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF`. This was a #212 follow-up the user had shelved; now done.
  - **Verified:** the default profile with a stub key and no cache passed 8/8 (3 + 5). `CLAUDE.md` Testing was updated.

- **Task 04:** documentation updated.
  - **`CLAUDE.md`:**
    - The package table lists the new model classes.
    - A new "The `Claim` entity" + "Seed data" block sits under Architecture.
    - Gotchas cover: never setting `claim_number`, gaps and insert-order numbering, the number appearing
      only after flush, why a `@SequenceGenerator` can't be used, `@ColumnDefault` staying on the field,
      `maxvalue`, no JDBC batching, and the expected `ClaimTests` WARNs.
    - Earlier in this issue: the Jest wiring and the `KeysTestProfile` ingestion fix.
  - **`README.md`:** a short claim-model paragraph next to the REST endpoints.
  - **`src/main/webui/README.md`:** claim JSON shape (category label, incident date/time) and why `formatIncident`
    parses by hand.
  - **No diagram changes:** `docs/*.puml` and `docs/design/*.puml` don't show claim fields (only the `claims` table
    name), and the design doc's references to "claim number" / "category (`Other` counts)" are still accurate.
  - **#213 issue body:** the "Spike first (open question)" section was replaced by "Spike outcome (decided)", the
    seed bullet now says the INSERTs omit `claim_number`, and the documentation task is ticked. The local copy
    `issue-02.md` was synced.
- **Expected test-log noise (user decision, 2026-10-05: leave as is):** `ClaimTests` logs two WARNs on success, and neither is a failure.
  - `ARJUNA012125` + stack trace from `changingClaimNumberFailsTheFlush`. The test commits so it can also assert claim 1 keeps its
    number; Narayana logs the refused commit. Switching to a `@TestTransaction` + `Claim.flush()` was offered and declined.
  - `HHH000247` / `23505 duplicate key` from `duplicateClaimNumberIsRejected`.
  - Reviewers in task 05 shouldn't flag these.

- **Task 05 (in progress):**
  - **Full `-Pollama` verify** (embeddings cache moved aside): 110 tests, 2 errors, 1 skipped. Both errors are known,
    depend on the environment, and match the #212 baseline:
    - `NotificationServiceTests.emailSendsWhenUserExists`: guardrail "rewritten output"; needs real model output.
    - `LangfuseSessionScoringServiceTests.canFetchObservationsBySessionId:182`: timeout after llama3.2 unexpectedly called
      `updateClaimStatus`, which then hit `WebSocketServerException`.
    - The skip is `DriftDetectionTests` (drift profile only).
    - Everything else passed, including all claim, REST, Playwright and Jest tests. `ClaimsListPageTests` still saw exactly
      6 claims in a full run.
  - **Fresh review, round 1:** 0 BLOCKER, 0 MAJOR, 2 MINOR, 4 NIT.
    - MINOR, fixed in this file: this plan's "Rejected: database-side default" bullet contradicted the final design.
    - MINOR, fixed: `ClaimTests.duplicateClaimNumberIsRejected` and `changingClaimNumberFailsTheFlush` run in their own
      `requiringNew()` transactions and now undo any leaked change in `finally` (delete row `-1`; restore claim 1's number via
      native UPDATE). The original assertions still run, so a regression still fails the test. The class comment was corrected.
    - NIT, fixed: `formatIncident.ts` now range-checks month (1–12), day (1–31), hour (0–23) and minute (0–59), with 7 new
      Jest cases (`formatIncident.test.ts` 32/32; Jest overall 36 passed, 1 skipped).
    - NIT, fixed: `import.sql` has a `--` comment above claim 4 explaining the chosen date and NULL time.
    - Fixes verified: `-Pollama` `ClaimTests` + `ClaimsListPageTests` 19/19 (6 seeds still load); `tsc` OK.
  - **Fresh re-review of the fixes (round 2):** approved. 0 BLOCKER, 0 MAJOR. Its 1 MINOR (these statuses still said "to fix")
    and 2 NITs (`ClaimTests` comment wrongly said both tests fail at commit; this plan's first-spike paragraph mentioned the
    `@SequenceGenerator` problem twice) were applied afterwards. It also noted that seconds aren't range-checked; accepted, since
    they aren't displayed.
  - **Fresh review, round 3** (restarted by the user after a mode switch; two parallel reviewers, one on docs and one on code):
    0 BLOCKER, 0 MAJOR.
    - **Fixed:**
      - `ClaimDetail.tsx`: the invalid `aria-label` on a `<p>` was replaced with `data-testid='incident-date-time'`; the test uses
        `getByTestId`.
      - `ClaimsListPageTests`: a retrying `hasCount` wait now follows each `selectOption`.
      - `ClaimTests.duplicateClaimNumberIsRejected`: it asserts SQLState `23505` plus `claim_number`, not PostgreSQL's constraint name.
      - `EmailContainsRequiredInformationOutputGuardrailTests`: the literal is `CLM01000000`.
      - `CLAUDE.md`: the stub-key ingestion sentence is scoped to the default profile.
      - `CLAUDE.md` + webui README: Jest under Maven has expected axios errors and an empty-table snapshot.
      - Task files: the H1 numbering and the `EnableAndRunTests` mention.
    - **Re-review of those fixes (round 4):** approved, with 1 MINOR and 2 NIT wording points, all applied:
      - the "never set `claim_number`" rule is scoped to persisted claims, since `PanacheMock` fixtures are fine;
      - the empty snapshot is because `asFragment()` runs synchronously, not only because there's no backend;
      - this summary.
    - **Verification after the fixes:** Jest 36 passed, 1 skipped; `tsc` clean; `-Pollama` `ClaimTests` 14, `EmailContainsRequired…` 10,
      `ClaimsDetailPageTests#showsIncidentDateAndTime` 3 and `ClaimsListPageTests` 5. All 32 pass.
    - **Maintainer decisions (2026-10-05):**
      - The unstaged `.gitignore` `+/.tasks/` line was reverted; `.tasks/` stays versioned. It kept coming back because an Explyt
        plugin setting appended it; the user changed that setting.
      - "The global rules in `AGENTS.md`" now reads `CODE_STANDARDS.md` in `CLAUDE.md` and this issue's plan files. The other issues'
        `.tasks` plans keep the old wording until their own PRs. Root `AGENTS.md` (now a short pointer to `CLAUDE.md`,
        `CODE_STANDARDS.md` and `.tasks`) is committed with this issue.
      - New and rewritten files follow `.editorconfig`: Java uses tabs, and `*.ts` uses 4 spaces.
      - Noted, not changed: Quinoa's `npm test` runs once per test profile that enables it, and its output lands in another test
        class's surefire report.
  - **Compile:** `test-compile` passes under both `-Pollama` and the default profile after all fixes.
  - **Maintainer verification (2026-10-05): passed.** The real-key `./mvnw -B clean verify` and the manual UI check (claims list,
    "Other" filter, detail-page incident date/time) are both good. Task 05 is done.

### Caveats & Problems
- Tests must never assert absolute claim numbers, and must delete any claim they create.
- The cluster database has a persistent volume. That's handled: Hibernate owns `claim_number_seq` (via `ExportableProducer`) and
  drops/recreates it under drop-and-create. `import.sql` contains no sequence DDL for it.
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.