# Task 02: Rework the Claim Entity and Rewrite the Seed Data

**Type:** Code Modification

## Goal

Update `Claim` to the new data model:
- a generated natural-id claim number
- typed incident date/time
- a category enum
- unbounded text columns

Rewrite `import.sql` to match, and cover every behaviour with tests.

The entity and seed changes are one task (merged from the old tasks 02 and 03). Changing the entity breaks seed loading until `import.sql` matches, so neither half can be verified on its own.

## What to Do

### Claim number (the "mix" chosen in task 01; see `PLAN.md` → Key Decisions)
- `@ClaimNumber`: a `@ValueGenerationType(generatedBy = ClaimNumberGenerator.class)` meta-annotation. Hibernate only accepts `@ValueGenerationType` on annotation types.
- `ClaimNumberGenerator` implements `OnExecutionGenerator` + `ExportableProducer`:
  - INSERT only; it doesn't reference the column in SQL and doesn't write a property value.
  - `registerExportables` registers `claim_number_seq`: start 1000000, increment 1009, options `maxvalue 99999999`.
- On the field: `@NaturalId @ClaimNumber @ColumnDefault(ClaimNumberGenerator.COLUMN_DEFAULT)` and `@Column(unique = true, nullable = false, updatable = false)`.
  - `COLUMN_DEFAULT` = `'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')`, built from the sequence-name constant.
- `Claim.findByClaimNumber(String)` returns `Optional<Claim>` via `getSession().find(Claim.class, number, KeyType.NATURAL)`. `bySimpleNaturalId` is deprecated since Hibernate 7.3.

### Other fields
- **Category:** a `ClaimCategory` enum with `SINGLE_VEHICLE`, `MULTIPLE_VEHICLE`, `THEFT`, `OTHER`.
  - Labels: "Single vehicle", "Multiple vehicle", "Theft", "Other".
  - Stored with `@Enumerated(EnumType.STRING)`.
  - JSON uses `@JsonValue` on the label and a `@JsonCreator` that accepts the label or the constant name.
- **Incident date/time:** replace `time` (column `claim_time`) with `incidentDate` (`LocalDate`) and `incidentTime` (`LocalTime`, nullable).
- **Column sizes:**
  - `subject`, `body` and `location` → `@Column(length = Length.LONG32)` (`text`).
  - `summary` and `sentiment` stay at 5000.
- `status` stays a `String`.

### Seed data (`import.sql`)
- Omit `claim_number` from every INSERT. The column default numbers the rows in insert (id) order:
  `CLM01000000`, `CLM01001009`, `CLM01002018`, `CLM01003027`, `CLM01004036`, `CLM01005045`.
- Use the enum constant names. The CHECK constraint rejects anything else.
- Replace `claim_time` with `incident_date` / `incident_time`, taken from each email:

  | id | client | incident_date | incident_time | source in the email |
  |---|---|---|---|---|
  | 1 | Marty McFly | 1955-01-02 | 15:30 | "January 2nd, 1955, at approximately 3:30 PM" |
  | 2 | John T. Anderson | 2024-01-15 | 15:45 | "January 15, 2024, at approximately 3:45 PM" |
  | 3 | Jane Doe | 2024-01-15 | 15:30 | "January 15, 2024, at around 3:30 PM" |
  | 4 | Dominic Toretto | plausible fixed date | `NULL` | "last night", no clock time |
  | 5 | Saul Goodman | 2023-03-28 | 16:15 | "March 28, 2023, at around 4:15 PM" |
  | 6 | Tyrion Lannister | 2023-04-15 | 12:00 | "April 15, 2023, at about noon" |

- Every `incident_date` must be after `inception_date` (1954-09-30).
- Use `NULL` instead of `''` for empty `summary`, `location` and `sentiment`.
- Keep `ALTER SEQUENCE claims_seq RESTART WITH 7`.
- Keep the ids, names, emails, subjects, bodies and statuses.

### Tests (`@QuarkusTest`, AssertJ, existing conventions)
- **Generation:** persisting assigns a `CLM\d{8}` number, and two consecutive claims differ by exactly 1009.
- **Callers can't set the number:** a value set before persist is replaced.
- **Natural-id lookup:** `findByClaimNumber` finds a persisted claim, and an unknown number returns `Optional.empty()`.
- **Uniqueness:** a raw insert reusing an existing number fails.
- **Immutability:** changing `claimNumber` on a managed claim fails the flush.
- **Long text:** a body longer than 5000 characters persists.
- **Enum JSON:** the label round-trips through the REST layer, including "Other". Unit-test the `ClaimCategory` mapping too.
- **Null time:** `incident_time` is omitted from JSON when null.
- **Seeds:**
  - all six seeded numbers are unique and well-formed;
  - every seeded `incident_date` is after its `inception_date`;
  - the first claim created after startup has a number different from every seeded one.
- Update `ClaimResourceTests.createClaim()` for the new field types.

## Files/Areas

- `src/main/java/org/parasol/claim/model/`: `Claim.java`, plus the new `ClaimCategory.java`, `ClaimNumber.java` and `ClaimNumberGenerator.java`
- `src/main/resources/import.sql`
- `src/test/java/org/parasol/claim/rest/ClaimResourceTests.java`, plus new tests under `src/test/java/org/parasol/claim/model/`
- Callers of `claim.category` / `claim.time` (`ClaimsListPageTests` compares `claim.category`)

## Key Points

- **Never set `claim_number` anywhere,** whether in SQL, seeds or tests. A hand-picked value can collide with a future `nextval`, and an explicit `NULL` violates NOT NULL.
- **The number is assigned at flush,** so use `persistAndFlush()` when the number is needed in the same transaction.
- **Gaps are normal** (rollbacks burn values), so never assert absolute or contiguous numbers, only the format and the increment between two inserts made by the same test.
- **Tests that persist claims must delete what they create.** `ClaimsListPageTests` expects exactly 6.
- **#214 seeds images by claim number,** so it must look numbers up and never hard-code them. Reordering seed rows renumbers them.
  *(Superseded in #214: the maintainer chose to match the seeded claims by their explicit ids instead; see
  `.tasks/03-gh214-claim-images/PLAN.md`.)*
- **Coding rules (`CODE_STANDARDS.md`):** `Optional` over null, `var`, AssertJ chains, one statement per line. The Panache public-field style stays.

## Done When

- [x] `Claim`, `ClaimCategory`, `ClaimNumber` and `ClaimNumberGenerator` exist as described.
- [x] `import.sql` seeds the six claims without SQL errors (verified by any `@QuarkusTest` boot).
- [x] The new tests cover generation, the increment, caller override, natural-id lookup, uniqueness, immutability, long text, enum JSON, null-time JSON and the seed invariants, and they pass under `-Pollama`.
- [x] `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` succeed.
