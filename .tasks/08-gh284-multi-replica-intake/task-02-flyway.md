# Task 02: Replace `drop-and-create` With Flyway

**Type:** Schema / Configuration Modification

## Goal

No pod wipes the database when it boots. The schema is versioned Flyway migrations (the app's own, plus Flow's from
`quarkus-flow-db-migration`), Hibernate only `validate`s, and the seed data is a migration that runs once per database.
Dev mode and the test suite still start from the six seeded claims every time. Fixes **mechanism 5**.

## What to Do

### 1. Dependencies and Flow's migrations

Add `io.quarkus:quarkus-flyway`, `org.flywaydb:flyway-database-postgresql` (if Quarkus 3.40 still needs it for
PostgreSQL) and `io.quarkiverse.flow:quarkus-flow-db-migration` (managed by the Flow BOM). Configure the `flow-runtime`
named datasource exactly as task 01 verified: same URL and credentials as the default, its own history table and
location.

### 2. The app's baseline: `src/main/resources/db/migration/V1__baseline.sql`

Generate it from the current entities, then review it by hand:
- `claims` with the `claim_number` column default `'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')`, its
  unique constraint, and `claim_number_seq` (`start with 1000000 increment by 1009 maxvalue 99999999`, see `CLAUDE.md`
  → *Gotchas*). Also `claims_seq` and the other `PanacheEntity` sequences, with their allocation sizes.
- `claim_images` (`bytea`, `on delete cascade` foreign key and index, the enum CHECK constraints),
  `claim_correspondence` (unique `message_id`, `on delete set null` from images), `claim_intake_state` (`@MapsId`, `on
  delete cascade`, the version) and `claim_requested_items`.
- A **unique constraint on `claim_images (claim_id, kind, file_name)`**, so `ClaimImageSeeder` is safe when two pods
  boot at once (see 5).

### 3. Seed data: `V2__seed_claims.sql`

Move `import.sql` into it unchanged (it omits `claim_number` and uses enum constant names; it ends with `ALTER SEQUENCE
claims_seq RESTART WITH 7`). Delete `import.sql` and every `sql-load-script`.

### 4. Configuration

- Root: `quarkus.hibernate-orm.schema-management.strategy: validate`, `quarkus.flyway.migrate-at-start: true`.
- Delete `drop-and-create` and `sql-load-script` from `%prod` and `%openshift` (`application.yml:524-539`).
- **The test suite starts clean on each boot:** `%test` sets `quarkus.flyway.clean-at-start: true` (and
  `clean-disabled: false`, if Quarkus still disables clean by default), for the default **and** the `flow-runtime`
  configuration. Per C1, `%test` wins over `%ollama`/`%ollama-openai`, so this holds on both CI legs.
- **Dev mode:** decide, and record the decision. The Dev Services Postgres outlives a live reload, so without a clean
  the claims survive it, and so does GreenMail's mail, which is *more* consistent than today. The trap is that
  `-Pollama` dev mode runs as `<ai>,prod`, so a `%dev`-only clean doesn't apply there, and `clean-at-start` must never
  be on under `%prod`. Recommended: no clean in dev; state survives a live reload.
- `quarkus.flyway.*` keys must not log `Unrecognized configuration key` (the #290 invariant): check with a real boot.

### 5. The two seeders

- `ClaimImageSeeder`: insert-if-absent against the new unique constraint (catch the violation per image, or `insert …
  on conflict do nothing` through the entity's own query), so two pods booting together insert each image once. Keep
  the `SeedResult` counts honest ("already present" includes losing that race).
- `DevClaimEmailSeeder`: with a database that outlives a live reload, re-sending the two samples on every start piles
  up claims. Recommended: send only when no claim beyond the six seeded ones exists. Decide and record.

### 6. `ClaimNumberGenerator`

Its `ExportableProducer` only feeds Hibernate's schema export. With `validate`, decide whether it stays (harmless,
documents the sequence) or goes. Its insert-and-read-back (`OnExecutionGenerator`) stays either way.

## Files/Areas

- `pom.xml`
- `src/main/resources/db/migration/` (new), `src/main/resources/import.sql` (deleted)
- `src/main/resources/application.yml`
- `src/main/java/org/parasol/claim/seed/ClaimImageSeeder.java`, `src/main/java/org/parasol/intake/dev/DevClaimEmailSeeder.java`,
  maybe `src/main/java/org/parasol/claim/model/ClaimNumberGenerator.java`
- Tests: `ClaimImageSeederTests` (the race), `ClaimTests`/`ClaimImageTests` (column types still hold), anything that
  relied on `import.sql` being re-run
- `CLAUDE.md`: *Seed data*, the `Claim` entity section (DDL now in Flyway), *Configuration profiles* (`%prod`,
  `%openshift`), and **delete the "No durable state across restarts (user decision)" gotcha** in favor of what's true
  now. `README.md` wherever it says the schema is recreated on boot.

## Key Points

- **Hibernate `validate` is the guard** against an entity and the migrations drifting apart. Every later schema change
  in this issue (tasks 03, 06) is a new `V<n>__…sql`, never an edit to `V1`/`V2`.
- Tests assert claim-number **format and the 1009 step only** (`CLAUDE.md` → *Gotchas*). A test that suddenly needs an
  absolute number means the sequence DDL is wrong, not the test.
- The `flow-runtime` datasource is for Flyway only. Flow's entities stay in the default persistence unit.
- On OpenShift the database now persists across app restarts (the PVC already exists, `strategy: Recreate`). The first
  deploy after this change finds the old `drop-and-create` schema with no Flyway history. Decide between
  `baseline-on-migrate` and documenting a one-time `oc delete` of the database PVC. The demo has no data worth keeping:
  documenting is fine.

## Done When

- [ ] `grep -rn "drop-and-create\|sql-load-script" src/main/resources` returns nothing; `import.sql` is gone.
- [ ] Two boots in a row against the same database (`java -jar` twice, or dev mode plus a live reload) keep the claims,
      and the second boot neither fails Flyway nor duplicates seed images.
- [ ] `./mvnw test -Dtest='ClaimTests,ClaimImageTests,ClaimImageSeederTests,ClaimResourceTests,IntakeExtensionsTests,ClaimRetrievalHasNoIntakeBookkeepingTests'`
      passes under `-Pollama`, and one intake workflow test class passes too.
- [ ] A boot logs no `Unrecognized configuration key`.
- [ ] `CLAUDE.md` and `README.md` no longer say the schema is recreated on boot or that state never survives a restart.
