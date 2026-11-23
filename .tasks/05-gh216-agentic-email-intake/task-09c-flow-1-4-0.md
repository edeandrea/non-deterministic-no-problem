# Task 09c: Adopt quarkus-flow 1.4.0 and Delete `FlowSpanTypingProcessor`

**Type:** Dependency upgrade

**Issue:** [#286](https://github.com/edeandrea/non-deterministic-no-problem/issues/286) (the email intake follow-ups)

*Added 2026-11-23, when the #286 gate check found quarkus-flow 1.4.0 (released 2026-11-20). It carries two of #286's
upstream gates: [quarkus-flow#1122](https://github.com/quarkiverse/quarkus-flow/issues/1122) (Flow types agentic workflow
spans itself, via #1136 and #1141) and [quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111) (the
generated agentic input schema, via #1115 and #1134).*

## Goal

The app runs on quarkus-flow 1.4.0. The reflective `FlowSpanTypingProcessor` is deleted in favor of Flow's own GenAI span
typing, the agent names on the spans stay what they were, and input validation is re-enabled if 1.4.0 allows it.

## What to Do

- **`pom.xml`:** `quarkus.flow.version` 1.3.0 → 1.4.0. Rewrite the override comment, and **raise its removal floor to
  1.4.0** (user decision, 2026-11-23): a platform on 1.3.x would leave the agentic spans untyped.
- **Delete `ai.scoring.conversation.flow.FlowSpanTypingProcessor`** and its unit test. `TASK_EXECUTE` moves to
  `LangfuseFlowSpanTypingProcessor`, its only main-code user; `FlowConversationAdapterTests` keeps a local copy, so the
  `flow` adapter doesn't reference the Langfuse package.
- **Keep the agent names (user decision, 2026-11-23).** Flow 1.4.0 takes `gen_ai.agent.name` from the composite
  annotation's `name`, falling back to the method name (`process`, `extractClaim`, `routeEmail`). Set
  `name = "claims-mailbox-agent"`, `"claim-extraction-workflow"` and `"email-router"`, so the spans, Langfuse and #310's
  plan see the values they had before.
- **Name the six leaf `@Agent`s the same way** (user decision, 2026-11-23): `email-classifier-agent`, `claim-follow-up-agent`,
  `unmatched-email-agent`, `claim-summary-agent`, `claim-sentiment-agent`, `incident-details-agent`, the kebab-case of the
  class, like the composites. Flow #1141 puts that name on each leaf-agent task span; task names stay the method names (Flow's
  `computeTaskNames`), and dataset names are interface and method, so nothing else moves.
  `IntakeArchTests.EVERY_INTAKE_AGENT_IS_NAMED_AFTER_ITS_CLASS` enforces the rule (shown failing once on an unnamed classifier).
- **Re-enable `quarkus.flow.validation.input.enabled`** if the agentic tests pass with it.
- Check the other 1.4.0 deltas: Flow's `V1.0.1` migration (#1131), the restore change (#1116; `auto-restore` stays
  `false`), the `PersistenceInstanceReader` fix (#1155; still unused here), and the runner endpoints (#1164/#1167; the
  runner isn't on the classpath).

## Done When

- [x] `pom.xml` is on Flow 1.4.0, with the rewritten comment and a 1.4.0 floor; no resolved `io.quarkus` version moved.
- [x] `FlowSpanTypingProcessor` and its test are gone; `IntakeSpanPropagationTests` asserts Flow's own typing, including
  `gen_ai.agent.name` = `flow.workflow.name` on every nested agentic `workflow.execute` span.
- [x] sdk-java#1816: in 7.35.4.Final, so the two "leaked map entry" comments are updated, with a regression test.
- [x] Input validation: **not re-enabled**, for a new reason (see Outcome). The comment in `application.yml` gives it,
  and it's filed upstream with a fix (quarkiverse/quarkus-flow#1180, PR #1181).
- [x] CLAUDE.md, `docs/design/email-claim-intake.md`, `PLAN.md` (2a, 2b, 2c.3, 2c.4, 2c.7, findings log) and #310's
  `PLAN.md` describe 1.4.0.
- [x] No code, main or test, reads Flow's tables: `FlowRows` replaced by `PersistedRuns`, `flowTablesAreCreated` deleted,
  `FlowBoundaryArchTests` enforces it.
- [x] `clean verify` passes: `-Pollama` on the final tree, 732 tests, 0 failures (2 skipped). `-Pollama-openai` passed
  before the `FlowRows` replacement (731, 0 failures, 3 skipped); on the final tree only one leg was run (user decision).
  Default profile: needs real keys, user-run.
- [ ] The reproducers in [edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) pass
  on 1.4.0 (user-run, per Execution Step 2a.4).

## Outcome

**Dependencies.** Resolved runtime deltas (`dependency:list` on 1.3.0 vs 1.4.0): every `io.quarkiverse.flow` module
1.3.0 → 1.4.0, a new `quarkus-flow-persistence-spi`, serverlessworkflow 7.35.3.Final → 7.35.4.Final, cloudevents-core
5.0.0 → 5.1.0, quarkus-jackson-jq 2.6.1 → 2.6.3, json-schema-validator 2.0.7 → 2.0.8. No `io.quarkus` artifact moved. The
effective POM also shows managed-only moves we don't depend on: vert.x test-jars and parent POMs (4.5.32 → 4.5.35), the
platform descriptor/properties entries (3.39.0 → 3.40.1.1) and graalvm polyglot js (23.1.2 → 25.0.4.1).

**Span typing.** Flow 1.4.0 types the three generated workflows' `workflow.execute` spans `invoke_agent`, with the kebab
names from the new `name` attributes. Through #1141 it also types the `task.execute` span of each leaf-agent task in those
workflows (named after the leaf agents, now `email-classifier-agent` etc.), which the old
assertion ("task spans carry no operation name") rejected. Those spans also keep `LangfuseFlowSpanTypingProcessor`'s
`chain`, and `IntakeLangfuseDeliveryTests`, which checks every task observation in the run's trace, shows Langfuse still
types them `CHAIN`: the explicit `langfuse.observation.type` wins. The Langfuse view is therefore unchanged, and other
backends now get the standard agent typing. Making them `AGENT` in Langfuse would mean stamping `chain` only after Flow's
runtime typing, which needs the OTel SDK's `internal` `ExtendedSpanProcessor.onEnding` (SDK 1.62.0 has no public
`onEnding`), so it wasn't done. The assertion now reads: every task is a chain, and the only agent-typed tasks are
generated workflows' leaf-agent tasks, each named after one of the six leaf agents.

**Input validation: still off, for a second reason.** With the flag on, `ClaimsMailboxAgentTests` (1 test) and
`ClaimExtractionWorkflowTests` (3) fail with
`Schema validation errors, detail=does not have a value in the enumeration ["MULTIPLE_VEHICLE", "OTHER", "SINGLE_VEHICLE", "THEFT", null]`
(and the same for `MissingItem`). `JandexMethodInputJsonSchema` at 1.4.0 (lines 323–337) lists an enum's constant names
(`field.name()`) and ignores `@JsonValue`, while `ClaimCategory` and `MissingItem` serialize as their labels: the REST
API's contract, not something to change for a schema. It hits a nested record's enum field (`IncidentDetails.category`)
and a root `Collection<MissingItem>` parameter alike. Filed upstream as
[quarkiverse/quarkus-flow#1180](https://github.com/quarkiverse/quarkus-flow/issues/1180), with the fix in
[#1181](https://github.com/quarkiverse/quarkus-flow/pull/1181) (it honors `@JsonProperty` on constants, `@JsonValue`,
`@JsonSerialize` and `@JsonFormat` shapes). #1111's own causes (`InvocationParameters`, nullable fields) are fixed.

**sdk-java#1816 is fixed too.** Flow 1.4.0's serverlessworkflow 7.35.4.Final carries the 7.x backport
([sdk-java#1828](https://github.com/open-workflow-specification/sdk-java/pull/1828)). A first check compared against
#1827's merge commit on `main`, found the branches diverged, and wrongly concluded the fix was missing. Only `start()`
registers an instance now, so the two sites that may create one and never start it (`ReviewRecoverer.handOver`,
`ClaimIntakeDispatcher.startRun`) need no code; their comments are updated, and
`IntakeExtensionsTests.anInstanceThatIsNeverStartedIsNotActive` pins the behavior.

**A test that never tested what it said.** The same SDK change makes `PersistenceInstanceReader.find` always return a new,
unstarted instance (status `PENDING`); before, it returned the live instance when one was active. The full `-Pollama`
verify then failed one test (729 run): `ClaimIntakeNewClaimTests.aCompleteNewClaimWaitsForReviewAndGetsOneReceivedEmail`,
`expected: WAITING but was: PENDING`, deterministically (three reruns). Its "persisted as waiting" assertion had been
reading the in-memory run all along, and Flow never persists `WAITING`: `AbstractPersistenceInstanceWriter` writes a
status only on suspend and clears it on resume, so a waiting run's row has a null status. The assertion now checks what
the review endpoint actually reads, the claim's `reviewRunId` and `reviewWaitingRunId`.

**Flow's tables are off limits to tests too (user decision, 2026-11-23).** `FlowRows` (task 14's R6) read Flow's three tables
with native SQL because Flow's reader couldn't be trusted: `scanAll` threw for a run whose task ran twice (#1155) and
reading left `PENDING` stand-ins (sdk-java#1816). Both are fixed in 1.4.0, so `PersistedRuns` replaces it, over
`PersistenceInstanceReader.scanAll`/`find` and `WorkflowApplication.workflowDefinitions()`. The task-row count is dropped
(a run's tasks go with it) and so is the `cloud_event_entity` count (the intake stores no events). Two things the switch
surfaced: `scanAll` begins its own `QuarkusTransaction`, so `PersistedRuns` holds none and activates a request context
(teardown has none); and a run that ends mid-scan makes Flow's JPA reader throw an NPE from the lazy load of its deleted row,
so a scan retries (up to five times) and cleanup cancels `activeInstances()` from memory rather than scanning.
`IntakeExtensionsTests.flowTablesAreCreated` is deleted. `FlowBoundaryArchTests` (`org.parasol.architecture`, main and
test code) forbids any dependency on Flow's JPA layer and any native SQL or raw JDBC outside `org.parasol.claim..`. Both
rules were shown failing first, on `FlowRows`/`flowTablesAreCreated` and on a scratch class referencing
`WorkflowInstanceEntity`.

**Still gated** (rechecked 2026-11-23): see `PLAN.md`'s findings log.

**Verification (2026-11-23):** CI's flags and stub keys. `-Pollama` `clean verify` on the final tree: 732 tests, 0 failures, 2
skipped. `-Pollama-openai` earlier in the task: 731, 0 failures, 3 skipped. `IntakeSpanPropagationTests` passed in three separate
boots, with no `No ongoing parent invocation found` in any log. A `-Pollama` `quarkus:dev` boot logged no
`Unrecognized configuration key` and applied Flow's `V1.0.1__DropWorflowInstanceIdColumn`. Not run here: the default profile
(real keys) and the Flow reproducers.
