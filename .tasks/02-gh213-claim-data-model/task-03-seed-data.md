# Task 03: Rewrite the Seed Data

**Type:** Code Modification

## Goal

Make `src/main/resources/import.sql` match the new schema, with consistent sequence-derived claim
numbers and typed incident dates and times.

## What to Do

- If task 01 chose `import.sql` for the sequence, add the `DROP SEQUENCE IF EXISTS` / `CREATE SEQUENCE` statements first.
- For each of the six claims:
  - Set `claim_number` to the first six values the sequence produces, in id order (recorded in task 01).
    Then advance the sequence past them (`ALTER SEQUENCE … RESTART WITH`, or `setval`) so the next generated number doesn't collide.
  - Change `category` to the enum constant names (`MULTIPLE_VEHICLE`, `SINGLE_VEHICLE`, …).
  - Replace `claim_time` with `incident_date` / `incident_time`, derived from each claim's own text:
    - Marty: 1955-01-02, 15:30.
    - Where the email gives only a relative time ("last night"), pick a plausible fixed date and leave `incident_time` null if no clock time is stated.
    - Every `incident_date` must be **after** `inception_date` (all seeds use 1954-09-30).
  - Keep empty `location`, `summary` and `sentiment` values as `NULL` rather than `''`.
- Keep `ALTER SEQUENCE claims_seq RESTART WITH 7` (the id sequence) working.
- Keep all six rows: same ids, names, emails, subjects, bodies and statuses.

## Files/Areas

- `src/main/resources/import.sql`

## Key Points

- Claim numbers appear in the email bodies' subjects, e.g. claim 2's subject mentions `Claim #XYZ789`.
  That's customer-written text; leave it.
- Grep the repository for every old seeded claim number (`CLM195501`, `CLM202402`, `CLM502803`,
  `CLM202415`, `CLM52125`, `CLM605208`):
  - Update references that depend on the database.
  - Leave tests that use a literal only as test data (e.g. `EmailContainsRequiredInformationOutputGuardrailTests`).
- `%prod` / `%openshift` also load `import.sql` (`sql-load-script: import.sql`), so the script must
  work against a fresh cluster database as well as Dev Services.

## Done When

- [ ] `./mvnw quarkus:dev`-equivalent startup (or any `@QuarkusTest`) loads all six claims without SQL errors.
- [ ] A test asserts the six seeded claims have unique, correctly formatted claim numbers and that every seeded `incident_date` is after its `inception_date`.
- [ ] The first claim created after startup gets a number that differs from every seeded one.