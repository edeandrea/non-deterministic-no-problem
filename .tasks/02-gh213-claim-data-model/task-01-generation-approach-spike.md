# Task 01: Spike the Claim-Number Generation Approach

**Type:** Exploration

## Goal

Decide, with evidence from a throwaway test, how `claimNumber` is generated from a PostgreSQL sequence
on insert, and how that sequence is created in every environment.

## What to Do

- Find the Hibernate ORM version Quarkus (from issue 1's upgraded platform) brings in.
- Prototype **two** approaches in a scratch test, then delete the prototype:
  1. **Database-side default:** `@ColumnDefault("'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0')")`
     plus `@Generated(event = EventType.INSERT)`, so PostgreSQL fills the column and Hibernate reads it back.
  2. **Java-side generator:** a custom `@ValueGenerationType` annotation backed by a
     `BeforeExecutionGenerator` (`ClaimNumberGenerator`). It runs
     `select nextval('claim_number_seq')` through the session and formats `CLM%08d`.
- For each, check:
  - Does schema generation (`drop-and-create`, which is what dev/test Dev Services and `%prod` use)
    create the sequence?
    - If not, check whether the sequence can be declared so Hibernate exports it: a
      `@SequenceGenerator` on the entity, an `orm.xml` `<sequence-generator>`, or `<database-object>`.
    - If none of those work, fall back to creating it in `import.sql`.
  - Does `@NaturalId` (`org.hibernate.annotations.NaturalId`) work with the approach, including
    `session.bySimpleNaturalId(Claim.class).load(...)`?
  - Is the value available on the entity right after `persist()` + flush?
- Check the DDL Hibernate emits for `@Column(length = Length.LONG32)` on PostgreSQL. Expect `text`; confirm it.
- Check that `quarkus.rest.jackson.optimization.enable-reflection-free-serialization: true`
  (`application.yml`) still honours `@JsonValue` / `@JsonCreator` on an enum. If not, record the alternative.
- Record the chosen approach, with the evidence, in `PLAN.md` → Shared Context → Key Decisions.

## Files/Areas

- A throwaway test under `src/test/java` (deleted afterwards)
- `src/main/java/org/parasol/claim/model/Claim.java` (read-only here)

## Key Points

- Sequence: `INCREMENT BY 1009` (first prime above 1000; the user wanted an increment > 1000),
  starting at a value that keeps an 8-digit `CLM` number (e.g. `START WITH 1000000`). Record the
  final start value and the resulting first six numbers; task 03 needs them for the seed data.
- The cluster's PostgreSQL has a persistent volume. Hibernate's `drop-and-create` only drops objects
  it knows about, so an `import.sql`-created sequence must use `DROP SEQUENCE IF EXISTS` / `CREATE SEQUENCE`.
- The numeric `id` stays the primary key (REST paths, UI routes, `ClaimBotQuery.claimId`, the
  `NotificationService` tool and future image foreign keys depend on it).

## Done When

- [ ] `PLAN.md` names the chosen approach, the sequence DDL, and the first six generated claim numbers.
- [ ] `PLAN.md` records the Hibernate version, the observed DDL for `Length.LONG32`, and whether reflection-free serialization honours `@JsonValue`.
- [ ] No prototype code remains in the repository.