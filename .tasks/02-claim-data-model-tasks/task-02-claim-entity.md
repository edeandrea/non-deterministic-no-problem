# Task 02: Rework the Claim Entity

**Type:** Code Modification

## Goal

Update `Claim` to the new data model: a generated natural-id claim number, typed incident date/time,
a category enum, and unbounded text columns. Cover every behaviour with tests.

## What to Do

- **Claim number:**
  - `claimNumber` becomes `@NaturalId`, generated with the approach chosen in task 01, and unique at
    the database level.
  - It's never set by callers.
  - Add a finder that returns `Optional<Claim>` (e.g. `findByClaimNumber(String)`), using the natural-id API.
- **Category:** a `ClaimCategory` enum with `SINGLE_VEHICLE`, `MULTIPLE_VEHICLE`, `THEFT` and `OTHER`.
  - Display labels: "Single vehicle", "Multiple vehicle", "Theft", "Other".
  - Persist with `@Enumerated(EnumType.STRING)`.
  - JSON uses the display label (`@JsonValue`, plus a matching creator for deserialization), or the alternative task 01 recorded.
- **Incident date/time:** replace `time` (column `claim_time`) with `incidentDate` (`LocalDate`) and
  `incidentTime` (`LocalTime`, nullable). JSON names follow the snake_case strategy: `incident_date`, `incident_time`.
- **Column sizes:**
  - `subject`, `body`, `location` → `@Column(length = Length.LONG32)` (unbounded `text`)
  - `summary`, `sentiment` → stay `length = 5000`
- `status` stays a `String`, because `NotificationService.updateClaimStatus` accepts free text.
- **Tests (`@QuarkusTest`, AssertJ, following existing conventions):**
  - Persisting a claim assigns a `CLM` number. Two consecutive claims differ by exactly the increment.
  - A claim can be loaded by its natural id, and an unknown number returns `Optional.empty()`.
  - Inserting a duplicate claim number fails (unique constraint).
  - A body longer than 5000 characters persists.
  - Category JSON round-trips through the REST layer as the display label, including `Other`.
  - `incident_time` is omitted from JSON when null (the app uses `serialization-inclusion: non-empty`).
- Update `ClaimResourceTests.createClaim()` for the new field types.

## Files/Areas

- `src/main/java/org/parasol/model/claim/Claim.java`
- `src/main/java/org/parasol/model/claim/ClaimCategory.java` (new)
- `src/main/java/org/parasol/model/claim/ClaimNumberGenerator.java` (new, if the Java-side approach was chosen)
- `src/test/java/org/parasol/resources/ClaimResourceTests.java`, plus new entity/generator tests under `src/test/java/org/parasol/model/claim/`

## Key Points

- Tests that persist claims must delete what they create. Generated numbers advance the sequence, so
  never assert absolute claim numbers; assert the format and the increment.
- `ClaimsListPageTests` expects exactly 6 claims, so leftover rows break it.
- Coding rules in `AGENTS.md` apply: `Optional` over null, `var`, AssertJ chains, one statement per line.
  The existing Panache public-field style stays.
- Seed data (`import.sql`) is rewritten in task 03. Until then, the dev/test schema may fail to load
  the seeds. Do task 02 and task 03 back to back, before running the full suite.

## Done When

- [ ] `Claim` has the fields and annotations described above, and `ClaimCategory` exists.
- [ ] New tests cover generation, the increment, natural-id lookup, uniqueness, long text, enum JSON and null-time JSON, and they pass.
- [ ] `./mvnw -B clean test-compile -Pollama` succeeds.