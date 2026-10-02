TITLE: Rework the claim data model: sequence-generated claim numbers and typed fields
## Summary

Rework the `Claim` model so that claim numbers are generated consistently and fields are typed. This
prepares for email intake ({{ISSUE_5}}), and is useful on its own:
- consistent claim numbers (today's seeded numbers vary in length, e.g. `CLM52125` has 5 digits)
- real dates instead of prose
- categories that match the UI filter

**Depends on** {{ISSUE_1}}. **Blocks** {{ISSUE_3}} (both change `ClaimDetail.tsx` and the seed data) and {{ISSUE_5}}.

## Changes

- **Claim number:**
  - `claimNumber` becomes a Hibernate `@NaturalId`, generated on insert from a PostgreSQL sequence
    with `INCREMENT BY 1009` (the first prime above 1000), formatted `CLM` + 8 digits, with a unique constraint.
  - Callers never set it.
  - Add a natural-id finder that returns `Optional<Claim>`.
  - **The numeric `id` stays the primary key.** REST paths, UI routes, `ClaimBotQuery.claimId`, the
    `NotificationService` tool and the future image foreign key all depend on it.
- **Category:**
  - New `ClaimCategory` enum: `SINGLE_VEHICLE`, `MULTIPLE_VEHICLE`, `THEFT`, `OTHER`, persisted as strings.
  - JSON exposes the display labels ("Single vehicle", "Multiple vehicle", "Theft", "Other").
  - The UI category filter gains "Other".
- **Incident date/time:**
  - Free-text `time` (column `claim_time`) is replaced by `incidentDate` (`LocalDate`) and an optional `incidentTime` (`LocalTime`).
  - `ZonedDateTime` was rejected because claim emails rarely state a time zone.
- **Column sizes:**
  - `subject`, `body`, `location` become unbounded `text` (`@Column(length = Length.LONG32)`). Avoid `@Lob`, which maps to `oid` on PostgreSQL.
  - `summary` and `sentiment` stay at 5000 characters. {{ISSUE_5}} adds an output guardrail for them.
- **Status:** `status` stays free text. The chat's status-update tool accepts arbitrary statuses.
- **Seed data:** rewrite `import.sql`:
  - the six claims get the first six sequence values
  - category enum names
  - typed incident date/time derived from each claim's email (e.g. Marty: `1955-01-02 15:30`)
  - `NULL` instead of `''`
  - every incident date after its inception date

  Ids, names, bodies and statuses stay unchanged.

## Spike first (open question)

Decide, with evidence from a throwaway test:
- **How the number is generated:**
  - database side: `@ColumnDefault("'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')")` + `@Generated(event = INSERT)`
  - or Java side: a `@ValueGenerationType` backed by a `BeforeExecutionGenerator`
- **How the sequence is created:** by Hibernate schema export under drop-and-create, or by `import.sql`
  with `DROP SEQUENCE IF EXISTS` / `CREATE SEQUENCE`. The cluster database has a persistent volume, and
  drop-and-create only drops objects Hibernate knows about.
- **Two smaller checks:**
  - confirm the DDL `Length.LONG32` produces on PostgreSQL
  - confirm reflection-free Jackson serialization (enabled in `application.yml`) honours `@JsonValue` on enums

## Tests

- Generated numbers have the right format, and consecutive claims differ by exactly 1009.
- Natural-id lookup works, and an unknown number returns empty.
- A duplicate claim number is rejected.
- A body longer than 5000 characters persists.
- Category JSON round-trips as the label, including "Other".
- A null `incident_time` is omitted from JSON.
- The six seeded claims have unique, well-formed numbers, and each incident date is after its inception date.
- The first newly created claim's number differs from every seeded one.
- **Playwright:** the detail page shows the incident date/time; the list shows category labels, and the "Other" filter works.

## Caveats

- Tests must never assert absolute claim numbers, and must delete any claims they create (`ClaimsListPageTests` expects exactly 6).
- Grep for the old seeded numbers. Update database-dependent references, and leave literal test data
  alone (e.g. `EmailContainsRequiredInformationOutputGuardrailTests`).

## Documentation

- `CLAUDE.md`:
  - **Architecture:** the entity, the natural id, the sequence, the enum, the typed date/time.
  - **Gotchas:** how the sequence is created, and why tests must not assert absolute numbers.
- `README.md`, and the diagrams under `docs/` if they show claim fields (re-render with `docs/render-diagrams.sh`).

## Tasks

- [ ] Spike the claim-number generation approach
- [ ] Rework the `Claim` entity, with tests
- [ ] Rewrite the seed data
- [ ] Update the UI (detail date/time, "Other" filter), with Playwright tests
- [ ] Update the documentation
- [ ] **Verify:** an independent review, then a real-key `./mvnw verify` by the maintainer