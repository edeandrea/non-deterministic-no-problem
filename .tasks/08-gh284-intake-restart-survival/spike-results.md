# Spike Results: Multi-Replica Intake (task 01)

> **Superseded in part (2026-11-12):** the plan was re-scoped to a single replica that survives restarts (see PLAN.md). The Lease and second-JVM findings no longer apply; the Flow-restore and Flyway findings still do.

Throwaway spike for #284. All evidence comes from throwaway tests run against the real app (bare `%test` group, `-Pollama`,
WireMock-stubbed models, Dev Services Postgres/GreenMail), a scratch Maven project, and the library sources at the versions
below. Every piece of scratch code was deleted; nothing here is merged or pushed.

Versions: Quarkus 3.40.1.1, quarkus-flow 1.3.0 (`~/workspaces/quarkus/quarkus-flow`, tag `1.3.0`), serverlessworkflow
sdk-java 7.35.3.Final (`~/workspaces/IntelliJ/sdk-java`, tag `7.35.3.Final`), fabric8 kubernetes-client 7.8.0 (the BOM's), Java 25.

## Executive summary

1. **Another JVM can't tell that a run is `WAITING`.** Flow persists `status = null` for a running *or* waiting run, and
   `PersistenceInstanceReader.find` on a run that isn't live in this JVM returns a `WorkflowPersistenceInstance` in
   **`PENDING`** whatever was persisted. `ClaimReviewService.ensureWaiting` would therefore reject *every* decision POSTed to
   a follower with a `409` (Q1). Task 03 needs a database-only check.
2. **`find` leaks a zombie into `activeInstances`.** The `PENDING` instance it builds registers itself in the definition's
   `activeInstances` and is never removed. After one `find`, `activeInstance(runId)` is non-empty on that application instance, so "is the run
   local?" can't be `activeInstance(...).isPresent()` (Q1). Task 03's relay and task 05's reclaim must not use `find`.
3. **Flow 1.3.0's auto-restore never starts anything.** `FlowPersistenceRestore` builds each instance through the same
   self-registering constructor, then skips it because `activeInstance(id)` is now non-empty. It leaves a `PENDING` zombie for
   every persisted run of this app ID at boot (Q1). quarkiverse/quarkus-flow#1116 (on `main`, unreleased, for 1.4.0)
   removes that check and starts every persisted run, which would replay runs on every application instance. Turning it off (task 05) stops
   today's leak and tomorrow's replays, and loses nothing.
4. **There is no API to retire a run that isn't live.** `cancel()` on the zombie returns `false` (`PENDING` isn't active) and
   leaves the rows. Retiring a dead run is a direct `DELETE` (Q1).
5. **A dead run's state is fully readable.** `JpaInstanceOperations.readWorkflowInfo` (an injectable bean, no instance created)
   returns every completed task's output, and `model.as(IntakeState.class)` round-trips it, `applyOutcome`'s included. Each task
   also carries `nextPosition`, so "the run's next task is `waitReview`" is readable from the database (Q1, Q5).
6. **Flow bug: a task that completed twice breaks every read.** `readWorkflowInfo` collects tasks with `Collectors.toMap` keyed on
   `json_pointer`, but each iteration is its own row. After two decision faults (a run waiting a third time), it throws
   `IllegalStateException: Duplicate key do/16/waitReview`, and so do `find` and the restore scan. Today's single-application instance
   `ensureWaiting` fails the same way, as a `500` (Q1).
7. **`quarkus-flow-db-migration` works with `validate`,** under its own named Flyway configuration (`flow-runtime`), as the Flow
   guide describes, with two additions the guide doesn't mention: an explicit default `db-kind`, and `baseline-on-migrate` with
   `baseline-version: 0` on **both** configurations. Here, 65 tests across seven classes passed (Q2).
8. **The fabric8 mock API server is good enough.** In CRUD mode it rejects a stale `resourceVersion` with `409`. Exactly one of
   five electors leads, graceful failover takes about 1 s and a crash about one `leaseDuration`. No k3s is needed (Q3).
9. **Instance B can be the packaged jar,** booted from a `@QuarkusTest` with runtime `-D` overrides. It's ready in about 2.5 s (Q4).

## Q1. Flow's restore, cancel and persisted status

**Auto-restore is a runtime switch.** `quarkus.flow.persistence.auto-restore` (`@WithDefault("true")`, `ConfigPhase.RUN_TIME`)
and `scan-interval` (an empty `Optional`, so periodic scans are off by default):
`persistence/common/runtime/.../FlowPersistenceConfig.java:23,42`. `FlowPersistenceRestore.restoreInstances` returns early when
it's off (`FlowPersistenceRestore.java:47`).

**What a run that isn't live looks like** (spike: a real run driven to `WAITING`, then removed from `activeInstances` by
reflection to simulate another application instance):

| Observation | Result |
|---|---|
| Live instance | `WorkflowMutableInstance`, `WAITING` |
| `workflow_instance_entity.status` of that run | **`null`** |
| `JpaInstanceOperations.readWorkflowInfo(...).status()` | `null` |
| `PersistenceInstanceReader.find(...)` | `WorkflowPersistenceInstance`, **`PENDING`** |
| `activeInstance(runId)` after that `find` | present: the same `WorkflowPersistenceInstance`, `PENDING` |
| `cancel()` on it | `false`; status stays `PENDING`; the instance row is still there |
| `FlowPersistenceRestore.scanAndRestore()` with nothing in memory | registers a `PENDING` instance, never starts it (still `PENDING` 2 s later) |

Why, in the sources (sdk-java 7.35.3.Final):
- `WorkflowMutableInstance`'s constructor sets `PENDING` and calls `definition.addInstance(this)` (`impl/core/.../WorkflowMutableInstance.java:65-70`).
  `WorkflowPersistenceInstance`'s private constructor doesn't set the persisted status (`impl/persistence/api/.../WorkflowPersistenceInstance.java`).
  Only `cleanUp`, at the end of an *executed* run, calls `removeInstance` (`WorkflowMutableInstance.java:129-136`).
- `AbstractPersistenceInstanceReader.find`/`scanAll` map through `WorkflowPersistenceInstance.of`, which returns the active
  instance if there is one, or else builds (and so registers) a new one.
- `FlowPersistenceRestore.scanAndRestore` streams `reader.scanAll(def)` (that builds the instance) and only then checks
  `def.activeInstance(instance.id()).isEmpty()`, which is now false, so it logs "already active" and skips.
- `internalCancel` only acts on `RUNNING`/`WAITING`/`SUSPENDED` (`TaskExecutorHelper.isActive`), so a `PENDING` cancel publishes
  nothing, and `WorkflowPersistenceListener.onWorkflowCancelled` → `removeProcessInstance` never runs.
- `JpaInstanceOperations.writeStatus`/`clearStatus` only ever set `SUSPENDED` or `null`.

**Retiring a dead run's rows:** there's no API that works without a live `WorkflowContextData`
(`PersistenceInstanceWriter`/`Operations` all take one). The `DELETE` that `removeProcessInstance` performs is
`delete from workflow_instance_entity where application_id = ? and instance_id = ?`, which cascades to `task_info_entity`,
`task_metadata_entity` and `workflow_metadata_entity` (`ON DELETE CASCADE` in Flow's PostgreSQL script), plus
`delete from hash_mapping_info_entity where instance = ?` (no foreign key). **Not verified:** whether a dead `listen` leaves
`cloud_event_entity` rows (keyed by `reg_id`, no foreign key). Task 05 checks it with `FlowRows.cloudEventRows`.

**The duplicate-key bug** (spike: two injected decision faults, so the run waits three times): after the first fault there
were 16 task rows and the read worked. After the second there were 21, and the read threw `java.lang.IllegalStateException:
Duplicate key do/16/waitReview`. The cause is `JpaInstanceOperations.from(...)`'s `Collectors.toMap(e -> e.jsonPointer(), ...)`
over `task_info_entity`, whose primary key includes `iteration`.

**The "next task" signal:** each `CompletedTaskInfo` carries `nextPosition`. For a run waiting for its review, the newest
completed task is `do/15/routeAfterFile` with `next=do/16/waitReview` (after a decision fault, it's `afterDecision`, whose
`next` is also `waitReview`).

**The restore is already fixed upstream, unreleased:** quarkiverse/quarkus-flow#1116 ("Fix #1110 - Restore persisted
workflow instances unconditionally", on `main`, for 1.4.0) drops the `activeInstance(...).isEmpty()` check and calls
`start()` on every scanned instance (`start()` on an already-running one is a no-op). quarkiverse/quarkus-flow#1110 was
the report of a restored timed wait that never resumed. `waitReview` has no timeout (`ClaimIntakeFlow.java:119`, a plain
`listen(...).first()`), but once Flow 1.4.0 is in, auto-restore would genuinely restart runs, on every application instance sharing the app
ID.

**What it means for later tasks:**
- **03:** `ensureWaiting` and `isReadyForDecision` can't use `PersistenceInstanceReader`. Use a native read of the run's newest
  task row (`select next_position from task_info_entity where application_id = ? and workflow_instance_id = ? order by instant
  desc limit 1`, expecting the `waitReview` pointer). That avoids the zombie and the duplicate-key bug at once. A run with no
  instance row is "not found". The relay's "is the run hosted here?" must ignore zombies:
  `activeInstance(id).filter(i -> i.status() != PENDING)`, and better, nothing in the app ever calls `find` again.
- **04/05:** turn auto-restore off whatever leadership mode, or a restarted application instance is left holding zombies, and
  `SupersedeStep`'s `activeInstance(oldRunId)` finds one and its `cancel()` silently returns `false`. Retire a dead run with the
  `DELETE`s above. Keep it off after a Flow upgrade too: with #1116 it would restart every persisted run on every application instance.
- **Upstream:** the restore is fixed by quarkiverse/quarkus-flow#1116 (unreleased). The duplicate-key read is still on
  quarkus-flow `main`: filed as quarkiverse/quarkus-flow#1154, fixed by quarkiverse/quarkus-flow#1155. `find` registering a
  `PENDING` instance is open-workflow-specification/sdk-java#1816's territory (PR open-workflow-specification/sdk-java#1820
  lets `cancel()` remove a `PENDING` instance); the read path was added to it as a comment.

## Q2. `quarkus-flow-db-migration` with this app

Scratch setup: `quarkus-flyway-postgresql` plus `quarkus-flow-db-migration` (1.3.0 is on Central and managed by the Flow BOM).
The app's own DDL was generated with `-Dquarkus.hibernate-orm.scripts.generation=create
-Dquarkus.hibernate-orm.scripts.generation.create-target=<file>`, Flow's six tables were dropped from it, and the rest went into
`db/migration/V1__baseline.sql`. `import.sql` became `V2__seed_claims.sql`, and the root config set
`schema-management.strategy: validate`.

**Flow's migrations get their own named Flyway configuration,** as the Flow guide does: a `flow-runtime` named datasource
(`db-kind: postgresql`, `jdbc.url`/`username`/`password` interpolated from the default datasource), with
`quarkus.flyway.flow-runtime.table: flyway_flow_runtime_history` and `locations: db/flow-migration/runtime/postgresql`. The app's
migrations stay on the default configuration under `db/migration`. Nothing of Flow's schema is written by hand.

Two gaps the guide doesn't mention:
1. **Adding a named datasource un-defines the default one.** The build failed with `Datasource must be defined for persistence
   unit '<default>'` until `quarkus.datasource.db-kind: postgresql` was set explicitly.
2. **Two Flyway configurations in one schema need `baseline-on-migrate`.** Flow's migrated first, then the default one refused:
   `Found non-empty schema(s) "public" but no schema history table`. `baseline-on-migrate: true` with `baseline-version: 0` on
   **both** fixed it (either can run second). The log then showed `flow-runtime` → `1.0.0 - CreateWorkflowSchema`, default
   baselined at `0`, then `1 - baseline` and `2 - seed claims`.

**Result:** `ClaimTests`, `ClaimImageTests`, `ClaimImageSeederTests`, `ClaimResourceTests`, `IntakeExtensionsTests`,
`ClaimRetrievalHasNoIntakeBookkeepingTests` and `ClaimIntakeReviewTests` all passed (65 tests). `validate` accepted Flow's six
entities against Flow's own script. Dev Services still started a single Postgres, the Hibernate statistics test was unaffected,
and no `Unrecognized configuration key` was logged.

**Flow has six tables, not three:** `workflow_instance_entity`, `task_info_entity`, `task_metadata_entity`,
`workflow_metadata_entity`, `cloud_event_entity` and `hash_mapping_info_entity`. `CLAUDE.md` and `FlowRows`' Javadoc say three.

**What `validate` checks:**
- A wrong sequence fails the boot: `Schema validation: sequence [claim_number_seq] defined inconsistent increment-size; found
  [50] but expecting [1009]`.
- A missing column default, or a missing `claims_seq`, was caught only because `V2__seed_claims.sql` failed (`claim_number`
  `NOT NULL`, `relation "claims_seq" does not exist`). Hibernate's validator compares tables, columns, types and sequences, not
  defaults (`AbstractSchemaValidator.validateTable`/`validateSequence`). The seed migration is the backstop for
  `claim_number`'s default, so it's worth a test that inserts a claim without a number.

**What it means for task 02:** use exactly this configuration (the named `flow-runtime` Flyway config, explicit `db-kind`,
`baseline-on-migrate` + `baseline-version: 0` on both). Generate `V1` with the command above and review it by hand: the
generated constraint names (`FKhkpt…`) are Hibernate's hashes, and nothing checks them. Fix "three tables" in `CLAUDE.md` and
`FlowRows`. Not tested here: `clean-at-start` in `%test`, and two boots against the same database.

## Q3. fabric8 `LeaderElector` against the mock API server

Scratch Maven project (Quarkus 3.40.1.1 BOM: `kubernetes-client`/`kubernetes-server-mock` **7.8.0**),
`@EnableKubernetesMockClient(crud = true)`, `LeaseLock("test", "parasol-intake", id)`, lease 4 s, renew deadline 3 s, retry
250 ms.

| Check | Result |
|---|---|
| Stale `resourceVersion` on update | **`409`**; the holder stays the first writer's |
| Five electors at once | exactly one leads, max concurrent leaders 1 |
| Leader canceled, `releaseOnCancel(true)` | new leader after **1,035 ms** |
| Leader stops renewing, no release (crash) | new leader after **4,208 ms** (≈ `leaseDuration`) |

- `LeaderElector` is in `kubernetes-client-api` (`io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector`), which
  `quarkus-kubernetes-client` brings.
- **`onStopLeading` also fires when the elector is canceled** (at shutdown, say), not only when leadership is lost. Task 04's
  "lost the Lease → `Quarkus.asyncExit`" must ignore a stop it caused itself.

**What it means:** task 04's unit tests and task 08's harness can use the mock server. k3s isn't needed.

## Q4. A second JVM in a test

**Option A works.** A throwaway `@QuarkusTest` (instance A) spawned `target/quarkus-app/quarkus-run.jar` (built with `-Pollama`,
about 20 s; under `verify`, failsafe runs after `package`, so the jar is already there). Instance B was ready on `/q/health/ready`
**2.5–2.7 s** after the spawn and served instance A's six seeded claims from `/api/db/claims`.

Instance B's overrides:
- **Copied from instance A's config:** `quarkus.datasource.jdbc.url`/`username`/`password`, `quarkus.mailer.port`,
  `parasol.mail.imap-port`, and `quarkus.langfuse.base-url`/`public-key`/`secret-key`. The prod jar's Langfuse exporter is a
  build-time decision, and without these the boot fails config validation (`SRCFG00014`).
- **Fixed:** `quarkus.profile=ollama,prod`, a free `quarkus.http.port`,
  `quarkus.hibernate-orm.schema-management.strategy=none` (**without it, instance B's `%prod` `drop-and-create` would wipe instance A's
  database**: mechanism 5; task 02 removes the need), `quarkus.langchain4j.easy-rag.ingestion-strategy=OFF`,
  `quarkus.otel.sdk.disabled=true`, `quarkus.flow.persistence.auto-restore=false`, `parasol.intake.enabled=false`.
- Everything else here is runtime config. Compose Dev Services and LGTM aren't in a prod jar, so they didn't get in the way.

**What it means for task 08:** use Option A. It also needs instance A's WireMock base URLs for the model clients
(`quarkus.wiremock.devservices.port`) and the mock API server's URL (`quarkus.kubernetes-client.api-server-url`). Instance B must
keep spans on if task 08 wants to assert them across application instances.

## Q5. A dead run's last persisted state

**Yes.** `JpaInstanceOperations.readWorkflowInfo(definition, runId)`, in a transaction, returns every completed task. Each task's
`CompletedTaskInfo.model()` is a Jackson `ObjectNode`, and `model.as(IntakeState.class)` turned it back into a complete
`IntakeState` for every task. `do/9/applyOutcome`'s output had `claimId`, the `NewClaim` outcome with its extraction,
`policyNumber`, `missingItems`, `needsReview` and `receivedAt`: everything the reply needs, with no reconstruction from
`Claim`/`ClaimIntakeState`. Task keys are JSON pointers (`do/9/applyOutcome`, `do/10/reply`, `do/14/file`, …).

**What it means for task 06:** to resume at the reply, read the dead run's newest completed task output as an `IntakeState`
**before** retiring its rows. The duplicate-key bug can't bite here, since a run handling a new email doesn't loop before it
files. Use the native newest-row read from Q1 anyway, for one code path. `in_reply_to` on outbound rows is still needed to know
whether the reply already went out.
