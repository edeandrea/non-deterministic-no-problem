# Task 01: Spike the Five Unknowns

**Type:** Spike (throwaway code; findings only)

## Goal

Answer, with evidence, the five questions the later tasks rest on, and write the answers to `spike-results.md` in this
directory. No production code lands. Any throwaway code lives on a scratch branch or in `/tmp` and is deleted.

## What to Do

### 1. Flow's restore and how to turn it off

- Confirm `quarkus.flow.persistence.auto-restore=false` stops `FlowPersistenceRestore` from starting persisted
  instances at boot (1.3.0 `FlowPersistenceConfig`), and that `scan-interval` stays off by default.
- Find out whether Flow 1.3.0 (or serverlessworkflow sdk-java 7.35.3) can **cancel or delete a persisted instance that
  isn't active in this JVM** (`PersistenceInstanceWriter`/`…Operations`). Task 05 needs to retire a dead leader's
  `WAITING` rows. If there's no API, say which table rows would need a direct `DELETE`/`UPDATE`, and whether that's safe.
- Find out what `PersistenceInstanceReader.find(...)` reports for a dead pod's run (`WAITING` forever, presumably).
  `ClaimReviewService.ensureWaiting` reads it.

### 2. `quarkus-flow-db-migration` with this app

On a scratch branch, add `quarkus-flyway` and `quarkus-flow-db-migration` with the documented `flow-runtime` named
datasource. Generate the app's own DDL (`quarkus.hibernate-orm.scripts.generation=create` into a file, or read it from
a `drop-and-create` boot's log) and boot with `schema-management.strategy=validate`.
- Does `validate` pass for Flow's six entities against Flow's own script? (`CLAUDE.md` says "Flow's three tables"; the
  migration says six. Find out which is true here.)
- Does a named datasource change anything else (Agroal pool, Dev Services, health checks, the Hibernate statistics
  `ClaimRetrievalHasNoIntakeBookkeepingTests` reads)?
- Does `validate` check the `claim_number_seq` sequence and the column default, or only tables/columns?

### 3. fabric8 `LeaderElector` against the mock API server

In a plain JUnit test (no Quarkus), with `io.fabric8:kubernetes-server-mock` in CRUD mode, run two `LeaderElector`s on
one `LeaseLock`:
- Exactly one leads.
- Failover after `stop` with `releaseOnCancel(true)` is quick.
- Failover after the leader just stops renewing (simulated crash) takes about one `leaseDuration`.
- Does the mock's CRUD mode reject a stale `resourceVersion` on update? If it doesn't, two electors can both "win", and
  task 08 needs k3s (Testcontainers) instead of the mock.
- Record the fabric8 version the Quarkus 3.40.1.1 BOM pins, and whether `quarkus-kubernetes-client` brings
  `LeaderElector` (package `io.fabric8.kubernetes.client.extended.leaderelection`).

### 4. A second JVM in a test

Task 08 needs pod B: a second app process sharing pod A's Postgres, GreenMail, WireMock and mock API server.
Prototype the cheapest harness that works:
- **Option A:** a failsafe `@QuarkusTest` (pod A) spawns `target/quarkus-app/quarkus-run.jar` (pod B, a `prod` build)
  with `-D` overrides read from pod A's config: datasource URL/user/password, `quarkus.mailer.*`,
  `parasol.intake.imap.*`, the WireMock base URLs, the Kubernetes API URL and a different `quarkus.http.port`. Which
  build-time settings of the prod jar get in the way (the Langfuse exporter, LGTM, Compose Dev Services, Easy RAG
  ingestion, `quarkus.flow.validation`)? Can each be turned off at runtime?
- **Option B,** if A is impractical: both pods as spawned jars, the test plain JUnit, and Postgres/GreenMail/WireMock
  started by Testcontainers directly.
- Measure the extra wall-clock cost.

### 5. A dead run's last persisted state

Task 06's *resume at reply* needs the `IntakeState` the dead run had after `applyOutcome` (the outcome decides the
reply template). Can a dead run's persisted task output be read back (through `PersistenceInstanceReader`, or the
`task_info_entity` rows), and is it JSON the Quarkus `ObjectMapper` turns back into an `IntakeState`? If not, list what
the reply needs (template, missing items, claim number, threading) and whether the database alone can rebuild it
(`Claim.status`, `ClaimIntakeState.requestedItems`, the correspondence row).

## Files/Areas

- `.tasks/08-gh284-multi-replica-intake/spike-results.md` (new): one section per question, with the evidence (file and
  line in the 1.3.0 sources, test output, timings)
- Scratch code only, deleted at the end. The Flow 1.3.0 sources are cloned at `~/qf`.

## Key Points

- The precedent is `.tasks/05-gh216-agentic-email-intake/spike-results.md` and `spike-results-flow.md`: verified facts
  with sources, and the decision each one forces.
- If question 3 says the mock can't serialize updates, stop and present that. It changes task 08's harness (k3s), and
  maybe task 04's unit tests.
- If question 5 says the database alone can't rebuild the reply, present the options before task 06 starts.

## Done When

- [ ] `spike-results.md` answers all five questions with evidence, and states what each answer means for tasks 02–08.
- [ ] Any change to later task files that the answers force is proposed (step 6 of the Execution Steps).
- [ ] No scratch code is left in the repository.
