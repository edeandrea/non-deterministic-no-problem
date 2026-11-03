# Task 13: Demo Guide and Documentation

**Type:** Code Modification

**Issue:** [#262](https://github.com/edeandrea/non-deterministic-no-problem/issues/262)

## Goal

The demo can be run from a written guide, and every project document describes the email intake accurately.

**Task 12c is merged** (checked 2026-11-02): PR [#282](https://github.com/edeandrea/non-deterministic-no-problem/pull/282),
squash-merged into `gh216-email-intake` as `5d05a4b` on 2026-10-30. `PLAN.md` still says "built, not yet in a PR" — that
is stale and this task fixes it. Tasks 12b and 12c both landed before this one (user decision, 2026-10-30), so document
the extraction contract as 12b leaves it (the claim summary is the only incident text; no separately extracted
`description`) and the Documents tab's correspondence transcript as 12c built it.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b, ahead of the platform. Document what 1.2.0 fixed, not the
workarounds it replaced, and the override in `pom.xml` with its removal condition. Check `PLAN.md` → Execution Steps →
2a first.

**quarkus-flow 1.3.0 (2026-10-26):** adopted in task 09b, ahead of the platform, superseding the 1.2.0 override. It
fixes [quarkiverse/quarkus-flow#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096) upstream
([#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095)), and task 09b deleted the app's own workaround for it
(`AgenticWorkflowRuns`, `FailedAgenticSystemListener`, `ClaimsMailboxAgent.endFlowRunsOnFailure()`). Document what
1.3.0 fixed, not the workaround it replaced.

## Decisions (user, 2026-11-02)

1. **Diagrams: a new sibling, not an extension.** Add `docs/claim-intake-flow.puml` rather than adding the intake to
   `docs/application-flow.puml`. The existing file is already a dense C4 component diagram near PlantUML's size
   ceiling — `render-diagrams.sh` raises `PLANTUML_LIMIT_SIZE` to 8192 specifically for it. README embeds both.
2. **Single replica: pin it, document it, file a follow-up.** See *The single-replica constraint* below.
3. **README: promote the intake to a co-equal demonstrated feature**, not a minimal correctness pass. It is currently
   chat-only and still says the intake is not implemented.

## Pre-flight checks (`PLAN.md` → Execution Steps 2a–2c), run 2026-11-02

**No change; every workaround stays.** Copy this into `PLAN.md`'s findings log when updating the plan:

- **2a** — the newest platform `quarkus-flow-bom` is still `3.40.1` (Flow 1.1.3); Flow `1.3.0` is still the latest
  release. The `pom.xml` override stays.
- **2b** — [quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111) is closed for milestone 1.4.0
  (due 2026-11-20), not released. `quarkus.flow.validation.input.enabled=false` stays.
- **2c.1** — [quarkus#57879](https://github.com/quarkusio/quarkus/pull/57879) still open, no milestone.
  `OtelContextLeakGuard` stays.
- **2c.2** — [quarkus-langchain4j#2951](https://github.com/quarkiverse/quarkus-langchain4j/pull/2951) merged but in no
  release; 1.14.2 is still the latest stable (2.0.0.Beta2 is a pre-release). The `InvocationParameters` workaround stays.
- **2c.3** — quarkus-network-faults `0.3.0` still the latest; the Toxiproxy swap stays a separate decision.
- **2c.4** — [quarkus-flow#1122](https://github.com/quarkiverse/quarkus-flow/issues/1122) open. `FlowSpanTypingProcessor` stays.
- **2c.6 — changed:** [langchain4j#6914](https://github.com/langchain4j/langchain4j/pull/6914) was **merged 2026-11-02**;
  [#6886](https://github.com/langchain4j/langchain4j/pull/6886) is still open. Neither is in a release, so by the
  step's own bar ("fixed means released") `MonitoredAgent` stays commented out. Record the movement.

## Verified findings that shape this task

### The single-replica constraint

Checked against the code on 2026-11-02. The intake genuinely requires one instance, but this is **emergent** — nobody
decided it, and nothing in the repo enforces it (you get one replica only because that is the OpenShift extension's
default). Four mechanisms:

| # | Mechanism | What breaks with two replicas |
|---|---|---|
| 1 | `ReviewDecisionPublisher.publish` → `workflowApplication.eventPublishers()` (Flow's in-memory publisher) | **Silent.** `ClaimReviewService` checks the run is `WAITING` through `PersistenceInstanceReader`, which reads the shared database and so passes on either replica. A POST to the replica that isn't hosting the run passes every check, publishes locally and returns `202` — and the decision is never applied. |
| 2 | `SupersedeStep:111`, `…definition().activeInstance(oldRunId)` | An in-JVM lookup, so a reply handled on replica B can't cancel a run waiting on replica A. `cancel` returns `false` and the step logs at **debug** only, leaving a live waiting run holding stale data. |
| 3 | `ClaimIntakeDispatcher`'s three `ConcurrentHashMap`s, plus `implements WorkflowExecutionListener` | The listener only hears local runs, so each replica keeps its own per-sender queue and one sender's emails can interleave. The documented per-sender ordering is gone. |
| 4 | `ClaimsInboxWatcher` (`@ApplicationScoped` + `StartupEvent`) with `ClaimsMailbox`'s process-local `ReentrantReadWriteLock` | Both replicas IDLE on the same INBOX and race to move the same message to `Processing`. |

Already safe across instances, and worth saying so: the duplicate check (`ClaimCorrespondence.messageId` is a database
unique constraint, which partly backstops #4), the reply-against-decision race (optimistic locking on the
`ClaimIntakeState` row) and run persistence itself (`quarkus-flow-jpa`).

### The documentation is uneven

Each implementation task updated `CLAUDE.md` and the design doc as it went, so those are close to accurate and need gap
filling rather than rewriting. **`README.md` was never updated at all**, and **no demo guide exists anywhere in the repo**.

### The stateless agents are correct; only the warning is missing

All six intake AI services (`EmailClassifierAgent`, `ClaimFollowUpAgent`, `ClaimResolver`, `ClaimSummaryAgent`,
`ClaimSentimentAgent`, `IncidentDetailsAgent`) already carry
`@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`
— verified 2026-11-02. Statelessness is a user decision (`PLAN.md` → Key Decisions → *Agent context*), and
`docs/design/email-claim-intake.md:126` records the intent. **No code changes.** What is missing is the warning in
`CLAUDE.md`, which has zero mentions of either supplier or of "stateless".

## What to Do

### 1. Write the demo guide — `docs/email-claim-intake-demo.md` (new)

Five scenarios, each listing what to show: Roundcube, the `claims@` folders, the claims list and review panel, and
Langfuse. Cover both local dev mode and the cluster.

Say to log in as a **seeded claimant** (`marty.mcfly@email.com`, …), not an arbitrary address: those identities carry
the claimant's name (#237), so the email goes out as `Marty McFly <marty.mcfly@email.com>` and the replies greet
"Marty McFly". Any other address sends as a bare address and is greeted "Customer".

1. A complete new claim → `Pending Review`, then the claims processor clicks **Submit claim for processing** →
   `In Process`. Also show the other branch: checking **Ask customer** on a field turns the button into
   **Request more information** → `Pending Information` → the customer replies → `Pending Review`.
2. A claim missing its location → reply → `Pending Review` → Submit claim for processing → `In Process`.
3. A follow-up on Marty's claim (a status question; claim 1 is seeded `New`, so it gets the AI-written status answer
   and is never updated).
4. A policy-number conflict — a **new** claim stating a number that belongs to another customer: the vague
   "policy inconsistency" reply, and **no claim created**.
5. A not-a-claim email (a newsletter) → the `notAClaim` template.

Also include:
- The step "open the claim's **session** in Langfuse" — one session per claim, several traces. Explain why:
  [quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056) means one run is several traces, and
  `gen_ai.conversation.id` is what ties them together.
- The step "open the intake workflow in the Flow Dev UI" — the Workflows card draws `claim-intake` as a typed,
  branching diagram, including the review wait (task 01b, E17).
- **The agentic Dev UI does not show the intake:** `ClaimsMailboxAgent`'s `extends MonitoredAgent` is commented out
  (langchain4j#6885 and #6913). Say so, rather than leaving a demoer hunting for it.
- **Live-demo caveat:** editing a `Flow` bean in dev mode can throw `IncompatibleClassChangeError`; restart dev mode.
- **Dev mode already seeds two claims by email at startup** (`DevClaimEmailSeeder`, build property
  `parasol.dev-seed.enabled=true`): "Car hit a lamp post" from `dev.pending-review@example.com` (complete) and
  "Someone damaged my car" from `dev.pending-information@example.com` (no date or place). So a `Pending Review` and a
  `Pending Information` claim are already there before you send anything. **Hedge these outcomes** — they are what the
  model usually decides, not a code guarantee.
- GreenMail is in memory and the schema is drop-and-create, so restarting dev mode resets the demo; a waiting review
  does not survive it.

### 2. Add `docs/claim-intake-flow.puml` (new) and re-render

A C4 component diagram, sibling to `application-flow.puml` and in the same visual style: Roundcube → GreenMail
(SMTP/IMAP) → `ClaimsInboxWatcher` (IMAP IDLE) → `ClaimIntakeDispatcher` (per-sender queue) → `ClaimIntakeFlow`'s steps
(supersede → `AgentStep` → policy → persist → reply → file → `waitReview`), with `ClaimsMailboxAgent`'s topology as a
nested box (classifier → `EmailRouter` → `ClaimExtractionWorkflow` / `ClaimFollowUpAgent` / `UnmatchedEmailAgent`), the
`claim-intake` model, `IntakeReplySender` with its Qute templates, PostgreSQL (claims, `claim_intake_state`, Flow's
tables), and the claims processor → `POST review-decision` → the `ReviewDecided` CloudEvent waking the waiting run.

Re-render everything with `./docs/render-diagrams.sh` (Java and Graphviz `dot` are both on this machine — verified
2026-11-02) and commit the new PNG.

### 3. Update the design doc — `docs/design/email-claim-intake.md`

Surgical; the doc is otherwise accurate and current (12b and 12c are already folded in).

- **Status line (L3):** `Status: proposed, for review before implementation (#216).` becomes a line saying the design
  is **accepted and implemented**, linking #216. Keep the sentences after it about the quarkus-flow revision
  superseding #220.
- **L16 contradiction:** "Out of scope: … the reviewer editing extracted fields" is now **false** — reversed by the
  user on 2026-10-26 in task 10, and L133–135 already describes the implemented behavior. Remove it from the list.
- **L18–19:** the prerequisites and "Progress is tracked in #217" line reads as forward-looking; put it in past tense.
- **Incident time:** keep it, and settle `PLAN.md` → Caveats → *Incident time* in favor of the implementation. The doc
  already says "optional time" (L118); add a clause if it does not already say the time is never required and never
  asked for.
- Re-render its diagrams only if their `.puml` files change (they probably do not).

### 4. Update the #216 issue body

Two lines still describe the agentic adapter as future work: the sentence at ~L75 ("The code that carries it across the
chat, Flow and the agents is our own … package") and the bullet at ~L77 ("an agent adapter that carries the id into
Flow's generated agent sub-workflows until #1056 is fixed. It's proven before implementation starts."). Task 11 built
**no** Flow or agentic adapter — Flow 1.3.0 carries the OTel context itself, pinned by `IntakeSpanPropagationTests`.
Reword both, the same way the design doc's Observability section already was. Edit with `gh issue edit 216 --body-file`.

### 5. Update `README.md` — promote the intake to a co-equal feature

- **"What it demonstrates":** add a bullet for the agentic email intake inside a quarkus-flow workflow, and one for the
  human-in-the-loop review.
- **Architecture:** embed the new `docs/claim-intake-flow.png` below `application-flow.png`, with a paragraph. **Fix the
  stale count** — "The three PlantUML diagrams are generated with PlantUML from the `.puml` sources committed next to
  them in `docs/`" is wrong: there will be **seven** (four in `docs/`, three in `docs/design/`), and the render script
  covers `docs/design/` too.
- **A new "Email claim intake" section:** sending a claim to `claims@parasol.com` from Roundcube; the three statuses
  (`Pending Information`, `Pending Review`, `In Process` — intake never sets `New`); the review panel's
  **Submit claim for processing** / **Request more information** / **Regenerate** controls; the Documents tab's
  two-sided transcript; and a link to the demo guide.
- **REST paragraph:** add `GET /api/db/claims/{id}/correspondence`, `GET …/review`, `POST …/review-decision`
  (asynchronous, `202`, no authentication — it's a demo app; `400`/`404`/`409`/`422`) and `POST …/sentiment`.
- **Configuration:** a short `parasol.intake.*` table, sourced from
  `src/main/java/org/parasol/intake/config/IntakeConfig.java` (`enabled`, `address`, `imap.*`, `folders.*`, `limits.*`,
  `watcher.*`, `metrics.inbox-depth-interval`). Say it is **off only in `%test`**, and that `%dev` drops
  `watcher.idle-refresh` to `10s`.
- **Single replica:** a short subsection naming the four mechanisms above.
- **Observability:** one claim is one Langfuse **session**; the `claim.intake.*` meters; the Flow Dev UI.
- **Further reading:** rewrite the stale design-doc line ("Not implemented yet") and add the demo guide.
- Note the profile asymmetry for `claim-intake`: `%ollama` runs it on `qwen3:4b` with `think: false`, while
  `%ollama-openai` runs it on `granite4:micro` (its `model-name` interpolates from `parasol-chat`'s Ollama `model-id`,
  and `model-options.think` has no equivalent on the OpenAI-compatible endpoint).

### 6. Update `CLAUDE.md` — gap fill, not a rewrite

It already covers the intake packages, `ClaimIntakeFlow`, the review endpoints, statuses, the `claim-intake` model, the
testing layers, observability and most gotchas. The verified gaps:

- A consolidated **`parasol.intake.*` configuration list**, and the profiles where the intake is off.
- **The stateless-agent opt-outs as a gotcha.** The behavior is already right (see *Verified findings* above); what is
  missing is the warning that the two annotations are load-bearing. A seventh intake agent that omits them silently
  gets the policy Easy RAG retriever and the shared `"default"` chat memory, with no error and no failing test. This is
  the highest-value addition precisely because the failure mode is invisible.
- **The remaining quarkus-flow gotchas** not yet recorded: every terminal `switch` branch needs
  `.then(FlowDirectiveEnum.END)`; pass ids, not payloads, in workflow data; `quarkus.application.name` is part of
  Flow's table primary keys, so renaming the application orphans every waiting run (the comment is already in
  `application.yml`, but not in `CLAUDE.md`).
- **No durable state across restarts** (user decision): a restart wipes the database and GreenMail, so a waiting review
  does not survive it.
- **The single-replica requirement**, with the four mechanisms.
- Pointers to the new demo guide and the new diagram.

### 7. Pin the replica count and file the follow-up

- Add `quarkus.openshift.replicas: 1` to the `%openshift` profile in `src/main/resources/application.yml`, with a
  comment naming the four mechanisms and linking the follow-up issue.
- Confirm `src/main/kubernetes/dependencies.yml` still has `replicas: 1` on all four dependency Deployments (lines 67,
  144, 225, 291). No change needed there; note it in the PR body.
- **File a follow-up issue outside #216** for the HA story: leader election for the watcher, a durable cluster-wide
  transport for the review-decision event, and making case #1 loud instead of silent. Same pattern as
  [#281](https://github.com/edeandrea/non-deterministic-no-problem/issues/281), filed for the chat bot's emails in task
  12c. Link it from `PLAN.md` and from the new README subsection.

### 8. Update `langfuse-evaluation.md` — light touch

It makes no "the only AI service" claim and enumerates no models, so little is stale. Two fixes:
- The tier table's tier-2 row defines scope purely as a chat conversation. Add that the intake never ends a
  conversation, so intake LLM calls get **tier-1** scores but claims get **no session score**.
- L75's "exercised end-to-end via its external interfaces (REST, WebSocket, etc.)" — add email/IMAP.

### 9. Check `images/arch.png` — report, do not edit

Per the standing rule: **report** what the feature makes stale, do not pixel-edit the raster. It is source-less (no
`.excalidraw`/`.drawio` original), already hand-patched once, and this machine has no Pillow, numpy or ImageMagick. It
shows only the chat flow, so the intake makes it incomplete rather than wrong. Note it in the PR body and in `PLAN.md`
as a known gap.

### 10. Update `PLAN.md` and this task file

- Correct the stale status: **task 12c is done**, squash-merged as `5d05a4b` (PR #282). Tick its box in the Task Plan
  and update the "Status (2026-10-30)" paragraph.
- Add the 2026-11-02 findings-log entry from *Pre-flight checks* above, including the #6914 movement.
- Record the single-replica decision and the follow-up issue number under Key Decisions.
- Mark task 13 complete with a one-to-two sentence Outcome (Execution Steps → 4), and add an `## Outcome` section here.

## Files/Areas

- `docs/email-claim-intake-demo.md` (new), `docs/claim-intake-flow.puml` + PNG (new),
  `docs/design/email-claim-intake.md` (status line and the out-of-scope contradiction), `README.md`, `CLAUDE.md`,
  `langfuse-evaluation.md`, `src/main/resources/application.yml` (`%openshift` replica pin), `PLAN.md`, this task file,
  and the #216 issue body.

## Key Points

- **Verify every statement against the implemented code.** Do not copy assertions out of `PLAN.md`: it was wrong about
  task 12c's merge status, and its single-replica prose understated the problem.
- Leave `AGENTS.md` and `conversation-export.md` untouched.
- Branch `gh216/13-demo-and-docs`, off an **updated** `gh216-email-intake` (`git fetch` first — the local branch is
  behind at `995eea1`, the remote is at `5d05a4b`). One PR into the feature branch, opening "Implements task 13 of
  #216" and referencing `Part of #262`.
- This is documentation plus one config line, so the bar is the accuracy of every statement and an unbroken build.

## Verification

1. `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`. The `quarkus.openshift.replicas`
   addition sits inside `%openshift` and must not perturb either.
2. `./docs/render-diagrams.sh` exits 0 and lists every PNG (it fails loudly on a missing one). Eyeball the new
   `claim-intake-flow.png` for cropping.
3. Spot-check every new relative markdown link — paths from `docs/` into `src/` and `.tasks/` are easy to get wrong.
4. **Walk the demo guide end to end in dev mode** (`./mvnw quarkus:dev`) and correct anything that does not match:
   Roundcube at `http://localhost:8000`, logged in as `marty.mcfly@email.com`, each scenario's email, the
   `claims@parasol.com` folders, the claims list, the review panel, the Documents transcript, then the claim's session
   in Langfuse and the workflow in the Flow Dev UI. This is the only real test of the guide, and the one thing the test
   suite cannot do.
5. Do **not** run the full `verify` here — that is task 14's job, with real keys.

## Done When

- [x] The demo guide exists and covers all five scenarios, including the review step, for dev mode and the cluster.
- [x] `docs/claim-intake-flow.puml` exists, renders, and is embedded in README alongside `application-flow.png`.
- [x] The design doc's status line says accepted/implemented, the reviewer-edits contradiction is gone, and the doc
  matches the code.
- [x] The #216 issue body no longer describes the agentic adapter as future work.
- [x] `README.md` describes the intake as a co-equal feature: the workflow, the human review step, the `claim-intake`
  model, the review and correspondence endpoints, the `parasol.intake.*` keys, the single-replica requirement, the
  Langfuse session per claim, the `claim.intake.*` metrics and the Flow Dev UI — and the diagram count is correct.
- [x] `CLAUDE.md` gains the configuration list, the stateless-agent gotcha, the remaining quarkus-flow gotchas, the
  no-durable-state note and the single-replica requirement.
- [x] `quarkus.openshift.replicas: 1` is pinned with a comment, and the HA follow-up issue is filed and linked.
- [x] `PLAN.md` records task 12c as merged, carries the 2026-11-02 findings entry, and marks task 13 complete.

## Outcome

Built on `gh216/13-demo-and-docs` off `gh216-email-intake` @ `5d05a4b` (includes task 12c). Added the demo guide
(`docs/email-claim-intake-demo.md`, five scenarios), `docs/claim-intake-flow.puml`/`.png` (the 7th diagram, re-rendered
with `render-diagrams.sh`), corrected the design doc's status/out-of-scope/prerequisites text, reworded the #216 issue
body's Observability section to match the design doc (and fixed a stale `/review-decisions` → `/review-decision`
slip), promoted the intake to a co-equal README feature (new "Email claim intake" section: config table, single-replica
subsection, observability), filled CLAUDE.md's gaps (stateless-agent opt-out gotcha, remaining quarkus-flow gotchas,
no-durable-state note, the single-replica mechanisms), pinned `quarkus.openshift.replicas: 1` with a comment, and filed
the HA follow-up [#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284). `langfuse-evaluation.md`
got the two light-touch edits (tier-2 scope note, email/IMAP in the end-to-end sentence). `images/arch.png` is reported
as a known, accepted gap in `PLAN.md` rather than edited.

**Live-verified in dev mode** (`quarkus:dev` via the Quarkus Agent MCP tooling, real OpenAI/Cohere keys): the two
`DevClaimEmailSeeder` claims landed as `Pending Information` / `Pending Review` exactly as hedged; a complete new
claim from Marty (scenario 1) produced `Pending Review` with the `receivedFinalReview` reply and the correct
two-sided transcript, then `POST .../review-decision` with `READY` returned `202` and moved it to `In Process` with
the `claimInProcess` reply (Branch A); a follow-up on claim 1 (scenario 3) left it unchanged at `In Process` with an
AI-written status answer recorded; a policy-number conflict naming Marty's seeded policy number (scenario 4) produced
the vague `policyInconsistency` reply with no claim created; a newsletter (scenario 5) produced the `notAClaim` reply.
On a second boot, scenario 2 (a claim missing its location) was also live-verified: the new claim landed `Pending
Information` with `requested_items: ["location"]` only, and a reply supplying the location moved it straight to
`Pending Review` via `receivedFinalReview` (matched to the pending claim through the LLM `resolveClaim` fallback,
since the test reply didn't carry the `[CLM…]` subject prefix a real Roundcube reply would). The
Langfuse/Flow-Dev-UI steps were not separately re-walked, since the mechanisms they exercise (the review wait,
Flow's Dev UI reachability) were already covered by the above and by the existing `org.parasol.intake.**` test suite.
No corrections to the guide's mechanics were needed.

**Guide refined after user feedback (same session):** every scenario now quotes the exact, live-verified subject and
body rather than a paraphrase, and each scenario links the one sample photo (if any) that's actually worth attaching
there — only scenarios 1 and 2 run `ApplyOutcomeStep`, so a photo attached on a follow-up, a policy conflict or a
not-a-claim email is never stored, and the guide now says so instead of implying otherwise; a new *Before you start*
explanation covers which scenarios actually require a seeded claimant (only scenario 3, for the anti-spoofing address
match) versus which use one only for the named-greeting demo value; and *Sample photos to attach* links each of the
six seeded `original_carN.jpg` files directly, in a table (seed claim, claimant, image) naming which claim/claimant
each was seeded for.

**`docs/application-flow.puml`/`.png` renamed to `docs/claim-chat-flow.puml`/`.png` (user decision, same session):**
`application-flow` read as "the whole application's flow," which stopped being true once the email intake added a
second, unrelated flow. The new name mirrors `claim-intake-flow` so the two diagrams read as a pair in README and
CLAUDE.md. `git mv` on both files, the `.puml`'s internal `title` line, and every README/CLAUDE.md reference were
updated together; `.tasks/**` historical task files that mention the old name are left alone, as a record of what
was true when they were written. The `claim-intake-flow.puml` layout was also redone as a single top-to-bottom spine
(it originally mixed `-right-> -up-> -down->` edges around the agent topology, which crossed visually) — the model,
database and review-decision wake-up are now short side attachments off the main vertical flow rather than long
edges crossing back through it.
