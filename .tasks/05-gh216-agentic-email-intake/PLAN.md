# Issue 5: Agentic Email Claim Intake — Task Execution Plan

## Your Mission

A customer emails `claims@parasol.com` (from Roundcube). An IMAP IDLE watcher picks the email up and starts one
**quarkus-flow** workflow run for it. Inside that run, a `quarkus-langchain4j-agentic` composition (one workflow step)
triages the email and extracts the claim details. Then the workflow's own steps create or update the claim, store
photos, reply with Qute templates, and file the email. Before a claim moves to `In Process`, a claims processor does a
final review: the workflow waits (a `listen` step, persisted by Flow in PostgreSQL) for the processor's decision, and
then applies it.
A short design document (workflow, claim-state and agent-topology diagrams) was reviewed and merged in PR #220; the
**revised (quarkus-flow, b1) design passed the review gate again in PR #231** (merged 2026-10-07 as `26d8b83`, no
review feedback).
Last of five issues; see `.tasks/claim-intake-roadmap.md`. **Issues 1–4 must be complete before the
implementation tasks (03 onwards).** The spike (task 01) and the design (task 02) ran ahead of them.

**Prerequisites for task 03+ — all satisfied (updated 2026-10-06).** Issues #212–#215 have landed, in
order: #212 via PR #219 (`2575c10`), #213 via #224, #214 via #225, #215 via #230. `main` is now on
quarkus-langchain4j **1.14.1** / Quarkus **3.40.1**, matching what the task 01 spike verified
(langchain4j-agentic 1.20.2-beta30). **Nothing in the issue queue blocks task 03 any more.**

**Task 01b is done and its verdict is ADOPT quarkus-flow**, with option **b1** (the whole intake is one workflow) and
four follow-up spikes. See **Flow Spike Results (task 01b)** under Shared Context and
[`spike-results-flow.md`](spike-results-flow.md). **The task files are rewritten for it (2026-10-07):** tasks 03, 05,
06, 08–11 and 12–14 changed, task 07 is merged into task 08, and the new task 10b builds the generic conversation core.
**The design gate re-run passed** (PR #231 merged; the #216 issue body is updated to match and links it). **Tasks 03 and
04 are done** (task 03 squash-merged as [#233](https://github.com/edeandrea/non-deterministic-no-problem/pull/233);
task 04 squash-merged as [#234](https://github.com/edeandrea/non-deterministic-no-problem/pull/234) (`9c719cb`), both into
the `gh216-email-intake` feature branch, see Task Plan → Branches and pull requests).
**Tasks 05 and 06 are done**, squash-merged into the feature branch as one commit,
[#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235) (`1b232cc`, 2026-10-09).
**quarkus-flow 1.2.0 was released on 2026-10-09** with all three of the user's fixes, and the user decided to adopt it
ahead of the platform (2026-10-09): **task 06b** overrides Flow to 1.2.0, in its own PR into the feature branch
([#236](https://github.com/edeandrea/non-deterministic-no-problem/pull/236), squash-merged as `9c2d0b7`, 2026-10-11).
See Caveats → *quarkus-flow 1.2.0*.
**Issue #237 (step 4a, a #215 follow-up) landed on `main` first** (user request, 2026-10-11; PR
[#238](https://github.com/edeandrea/non-deterministic-no-problem/pull/238), squash-merged as `9939213`, then `main` was
merged into this branch): the six seeded claimants' Roundcube identities carry their names, so mail they send from
Roundcube is `From: Marty McFly <marty.mcfly@email.com>`. Plan: `.tasks/04a-gh237-roundcube-identities/PLAN.md`; it
changes tasks 12 and 13 (below).
**Task 08 is built** (2026-10-11, branch `gh216/08-intake-workflow`, uncommitted): **resume from the task file's *Resume here* section**. It was planned
with the user the same day (six decisions, D1–D6) and then changed twice with the user while building it: the intake's
bookkeeping lives in its own `ClaimIntakeState` entity, never on `Claim` (so claim retrieval neither returns nor loads
it), and `IntakeReplySender` takes no `Optional` parameters. **Next: task 09.**

**Plan File:** `.tasks/05-gh216-agentic-email-intake/PLAN.md`
**Tasks Directory:** `.tasks/05-gh216-agentic-email-intake/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions, spike results and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/05-gh216-agentic-email-intake/task-XX-*.md`: Goal, Key Points, Done When.

### 2a. Check for a platform that pins quarkus-flow 1.3.0 or later (every task, while the override stays)
*History: until 2026-10-09 this step waited for quarkus-flow 1.2.0 itself. It was released that day, the reproducers
passed against it, and task 06b overrides the platform's Flow 1.1.3 with it (user decision; see Caveats →
quarkus-flow 1.2.0). 2026-10-26: task 09b moves the override to 1.3.0, so this step now watches for a platform that
pins Flow ≥ 1.3.0 (see Caveats → quarkus-flow 1.3.0).*

Before starting any task:
1. Check which Flow version the newest Quarkus platform's `quarkus-flow-bom` pins:
   `curl -s https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-flow-bom/maven-metadata.xml | grep -E '<latest>|<release>'`,
   then grep that version's `quarkus-flow-bom-<v>.pom` for `<artifactId>quarkus-flow</artifactId>`. The platform side is
   tracked in quarkusio/quarkus-platform (`gh search prs --repo quarkusio/quarkus-platform "Quarkus Flow"`).
2. **If a platform we can move to pins Flow 1.3.0 or later**, say so in the findings: dropping the override (the
   `io.quarkiverse.flow:quarkus-flow-bom` import and `quarkus.flow.version` in `pom.xml`) is a decision for the user,
   usually taken together with that platform upgrade.
3. Otherwise say so in one line in the findings and carry on.
4. **Whenever the Flow version changes**, re-run the reproducers in
   [edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers) by **editing
   `quarkus.flow.version` in its `pom.xml`**. A `-Dquarkus.flow.version=` on the command line doesn't reach Quarkus's
   test bootstrap, which then runs the old Flow (found on 2026-10-09). Check what ran with
   `strings -n 8 target/quarkus/bootstrap/test-app-model.dat | grep -oE 'io.quarkiverse.flow:quarkus-flow[a-z-]*::jar:[0-9.A-Z]+' | sort | uniq -c`.

### 2b. Check the status of quarkus-flow#1111 (every task, while `quarkus.flow.validation.input.enabled` stays off)
*Added by task 09b (2026-10-26): see Caveats → quarkus-flow 1.3.0 for the full writeup.*

1. Check [quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111): `gh issue view 1111
   --repo quarkiverse/quarkus-flow`.
2. **If it's fixed in a released version** (not just merged — check the milestone/release, the same way 2a checks for
   a release rather than a merged PR), re-enable `quarkus.flow.validation.input.enabled` in `application.yml` (delete
   the override and its comment) as part of that task, and re-run the full `org.parasol.intake.**` suite to confirm
   the generated agentic workflows (`ClaimsMailboxAgent`, `ClaimExtractionWorkflow`) still pass with validation back
   on.
3. Otherwise say so in one line in the findings and carry on — the override in `application.yml` stays.

### 2c. Re-check the remaining upstream items (every task, while each workaround stays)
*Added 2026-10-26. 2a and 2b each watch one item; these three were tracked only in prose (Caveats and the task files),
so nobody re-checked them unless they happened to open that file. Same shape as 2a/2b: check, act if it shipped,
otherwise one line in the findings.*

Before starting any task, check each of these. **"Fixed" means released**, not merged — the same bar 2a and 2b use.

1. **[quarkusio/quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354) / PR
   [#56805](https://github.com/quarkusio/quarkus/pull/56805)** — the OTel context-propagation provider doesn't restore
   a pool thread's context, which is why task 10b carries the `ThreadContextProvider` guard (Caveats → the two
   observability workarounds; evidence in follow-up spikes 3 and 4). The fix is in 4.0.0.Beta1 and labeled
   `triage/backport-3.40`. `gh pr view 56805 --repo quarkusio/quarkus`, then check whether the backport is in a 3.40.x
   we can move to.
   **If it ships in a 3.40.x (or we move to 4.0):** delete the guard class and its `META-INF/services` entry as part of
   that task, and rely on spike 3's `managedExecutorPropagationIsClean` as the regression test.
2. **[quarkiverse/quarkus-langchain4j#2950](https://github.com/quarkiverse/quarkus-langchain4j/issues/2950) / PR
   [#2951](https://github.com/quarkiverse/quarkus-langchain4j/pull/2951)** — the agentic build check doesn't skip an
   agent's `InvocationParameters` parameter, so the root `ClaimsMailboxAgent` carries one it never uses
   (`task-06-triage-agents.md`, and CLAUDE.md's agentic gotchas). `gh issue view 2950 --repo
   quarkiverse/quarkus-langchain4j`.
   **If it's in a released quarkus-langchain4j we can move to:** drop the `InvocationParameters` parameter from the
   root agent's signature and fill it from a `@BeforeCall` (`agenticScope.writeExecutionContext(...)`), and update
   CLAUDE.md's gotcha.
3. **[quarkiverse/quarkus-network-faults#14](https://github.com/quarkiverse/quarkus-network-faults/issues/14)** (IMAP
   support) — it could replace the Toxiproxy test harness `ClaimsInboxWatcherTests` uses
   (`task-09-imap-idle-watcher.md`). `gh issue view 14 --repo quarkiverse/quarkus-network-faults`.
   **If it's released with IMAP support and the extension targets a Quarkus version we're on:** say so in the findings
   — replacing `ToxiproxyTestResource` and `org.parasol.testing.mail.Toxiproxy` is a decision for the user, not an
   automatic swap.

**Findings log (most recent check first; drop an item once it's resolved):**
- **2026-10-26, before task 10:** 2a — latest platform `quarkus-flow-bom` is still 3.40.1 (Flow 1.1.3); override stays.
  2b — [#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111) still open; `quarkus.flow.validation.input.enabled=false`
  stays. 2c.1 — [#56805](https://github.com/quarkusio/quarkus/pull/56805) merged for 4.0.0.Beta1 only, no 3.40.x backport
  yet; task 10b's `ThreadContextProvider` guard stays. 2c.2 — issue #2950 is closed and its fix, PR
  [#2951](https://github.com/quarkiverse/quarkus-langchain4j/pull/2951), merged to `main` 2026-10-10, but 1.14.2
  (2026-10-15, the latest release) is a 1.14-branch patch that doesn't carry it; `ClaimsMailboxAgent`'s
  `InvocationParameters` workaround stays. **2c.3 — changed: issue #14 is closed and quarkus-network-faults
  [0.3.0](https://github.com/quarkiverse/quarkus-network-faults/releases/tag/0.3.0) (2026-10-25) ships "Add mailer
  support" (PR [#15](https://github.com/quarkiverse/quarkus-network-faults/pull/15)).** This is a real candidate to
  replace the Toxiproxy harness. User decision (2026-10-26): record it here and keep Toxiproxy for now; the swap is
  its own task, not folded into task 10.

Otherwise say so in one line each in the findings and carry on. Drop an item from this step once its workaround is
gone.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md` (coding style, AssertJ, records, `Optional`, constructor injection, commit rules, documentation policy).
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

Task 02 was a hard gate: implementation tasks (03+) start only after the user confirms the external design review is complete and the agreed changes are applied. **Passed:** PR #220 merged with no review feedback, and the gaps and spike changes are applied to the task files (see Design and Caveats).

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any? (The spike in task 01 may force changes.)

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

**Each task is tracked as a GitHub sub-issue of #216 (created retroactively, 2026-10-26); the Task Plan and each task
file's header link to it.**

- [x] [task-01-agentic-spike.md](task-01-agentic-spike.md): Agentic module spike (runs first, throwaway) — [#248](https://github.com/edeandrea/non-deterministic-no-problem/issues/248)
- [x] [task-02-design-and-review.md](task-02-design-and-review.md): Design document and review gate — [#250](https://github.com/edeandrea/non-deterministic-no-problem/issues/250)
- [x] [task-01b-flow-spike.md](task-01b-flow-spike.md): quarkus-flow spike (throwaway; decided task 07) — **verdict: ADOPT**; follow-up (b1, whole intake as one workflow) **feasible** — [#249](https://github.com/edeandrea/non-deterministic-no-problem/issues/249)
- [x] **Design gate re-run (blocking):** update `docs/design/email-claim-intake.md` and its diagrams for b1, open a PR
  ending `Part of #216`, wait for the review; then update the #216 issue body to match — **passed:** PR #231 merged
  (`26d8b83`, no review feedback); the issue body matches it — tracked under [#250](https://github.com/edeandrea/non-deterministic-no-problem/issues/250)
- [x] **Follow-up spike 5 (blocking, user decisions 2026-10-07)**: **verdict: all four answered** (see
  `spike-results-flow.md` → Follow-up spike 5; branch `spike/flow-followup5`):
  1. the agentic adapter carries the baggage id onto every AI call inside the generated sub-workflows (8 concurrent
     runs plus no-id runs: 0 mixing, 0 foreign, 0 stale). Their own Flow spans stay unstamped. Option 2 (a Flow
     listener) is **invalidated**, because it leaks across threads, so the gap is accepted until #1056
  2. per-sender order works with a CDI status listener driving the queue
  3. `MonitoredAgent` works under Flow and holds nothing for waiting runs; **keep both Dev UIs** (user decision)
  4. the three-way router (parallel LLM, tool-calling LLM, plain Java) works under Flow's translation
  — tracked under [#249](https://github.com/edeandrea/non-deterministic-no-problem/issues/249)
- [x] [task-03-intake-config-and-mailbox.md](task-03-intake-config-and-mailbox.md): Intake configuration, Flow dependencies and claims mailbox — **done 2026-10-08**, squash-merged into `gh216-email-intake` as `fcfb8e8` ([#233](https://github.com/edeandrea/non-deterministic-no-problem/pull/233)) — issue [#251](https://github.com/edeandrea/non-deterministic-no-problem/issues/251)
- [x] [task-04-email-templates.md](task-04-email-templates.md): Qute reply templates and sender — **done 2026-10-08**, squash-merged into `gh216-email-intake` as `9c719cb` ([#234](https://github.com/edeandrea/non-deterministic-no-problem/pull/234)) — issue [#252](https://github.com/edeandrea/non-deterministic-no-problem/issues/252)
- [x] [task-05-extraction-agents.md](task-05-extraction-agents.md): Claim extraction agents — **done 2026-10-09**, squash-merged into `gh216-email-intake` with task 06 as `1b232cc` ([#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235)) — issue [#253](https://github.com/edeandrea/non-deterministic-no-problem/issues/253) (shared with task 06)
- [x] [task-06-triage-agents.md](task-06-triage-agents.md): Triage agents — **done 2026-10-09**, in the same squash commit `1b232cc` ([#235](https://github.com/edeandrea/non-deterministic-no-problem/pull/235)) — issue [#253](https://github.com/edeandrea/non-deterministic-no-problem/issues/253) (shared with task 05)
- [x] [task-06b-flow-1-2-0.md](task-06b-flow-1-2-0.md): Adopt quarkus-flow 1.2.0 ahead of the platform (user decision, 2026-10-09) — **done**, squash-merged into `gh216-email-intake` as `9c2d0b7` ([#236](https://github.com/edeandrea/non-deterministic-no-problem/pull/236)) — issue [#254](https://github.com/edeandrea/non-deterministic-no-problem/issues/254)
- ~~[task-07-human-review-step.md](task-07-human-review-step.md): Human review step~~ — **merged into task 08** (user decision, 2026-10-07); tracked under task 08's issue [#255](https://github.com/edeandrea/non-deterministic-no-problem/issues/255)
- [x] [task-08-intake-processor.md](task-08-intake-processor.md): The intake workflow and business rules (includes the human review) — **done**, squash-merged into `gh216-email-intake` as `ae2885c` ([#240](https://github.com/edeandrea/non-deterministic-no-problem/pull/240)) — issue [#255](https://github.com/edeandrea/non-deterministic-no-problem/issues/255) (includes task 07)
- [x] [task-09-imap-idle-watcher.md](task-09-imap-idle-watcher.md): IMAP IDLE watcher — **done**, squash-merged into `gh216-email-intake` as `e41c44c` ([#245](https://github.com/edeandrea/non-deterministic-no-problem/pull/245)) — issue [#256](https://github.com/edeandrea/non-deterministic-no-problem/issues/256)
- [x] [task-09b-flow-1-3-0.md](task-09b-flow-1-3-0.md): Adopt quarkus-flow 1.3.0, delete the agentic-run workaround and bump the container images (user decision, 2026-10-26) — task 08 found a quarkus-flow 1.2.0 leak when an agent throws on 2026-10-19 (workaround in `org.parasol.intake.agent`, upstream issue [quarkiverse/quarkus-flow#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096), PR [#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095)); 1.3.0 ships the fix — **done**, squash-merged into `gh216-email-intake` as `6d610b1` ([#264](https://github.com/edeandrea/non-deterministic-no-problem/pull/264)) — issue [#257](https://github.com/edeandrea/non-deterministic-no-problem/issues/257); found and filed a new upstream regression, [quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111) (#1077's input validation breaks every generated agentic workflow), worked around in `application.yml`; otel-lgtm held back (no OpenShift cluster available here) — see Outcome
- [x] [task-10-review-api-and-ui.md](task-10-review-api-and-ui.md): Review API and UI — done on `gh216/10-review-api-and-ui` (branch not yet pushed/PR'd) — issue [#258](https://github.com/edeandrea/non-deterministic-no-problem/issues/258); see Outcome
- [ ] [task-10b-conversation-core.md](task-10b-conversation-core.md): Generic conversation context core (`ai.scoring.conversation`; changes the chat's scoring trigger) — issue [#259](https://github.com/edeandrea/non-deterministic-no-problem/issues/259)
- [ ] [task-11-observability.md](task-11-observability.md): Observability for the intake workflow (Flow and agentic adapters) — issue [#260](https://github.com/edeandrea/non-deterministic-no-problem/issues/260)
- [ ] [task-12-roundcube-e2e.md](task-12-roundcube-e2e.md): Roundcube end-to-end test — issue [#261](https://github.com/edeandrea/non-deterministic-no-problem/issues/261)
- [ ] [task-13-demo-and-docs.md](task-13-demo-and-docs.md): Demo guide and documentation — issue [#262](https://github.com/edeandrea/non-deterministic-no-problem/issues/262)
- [ ] [task-14-verification.md](task-14-verification.md): Verification — issue [#263](https://github.com/edeandrea/non-deterministic-no-problem/issues/263)
- [ ] [task-15-claim-correspondence-transcript.md](task-15-claim-correspondence-transcript.md): Show a claim's full email correspondence as a transcript on the Documents tab (user decision, 2026-10-26: for the future, doesn't block #216's close-out, sequenced after task 14) — found while designing task 10's review UI: the Documents tab shows only the first email, and a reviewer deciding a `Pending Review` claim would benefit from the actual back-and-forth — issue [#265](https://github.com/edeandrea/non-deterministic-no-problem/issues/265)
- [ ] [task-16-consolidate-description-into-summary.md](task-16-consolidate-description-into-summary.md): Consolidate incident description into the claim summary (user decision, 2026-10-26: for the future, doesn't block #216's close-out, sequenced after task 14) — found while designing task 10's review UI: `IncidentDetailsAgent`'s `description` is extracted only to be thrown away, while a separate `ClaimSummaryAgent` output is what's actually persisted — issue [#266](https://github.com/edeandrea/non-deterministic-no-problem/issues/266)

> **Ordering:** the task files describe the b1 design (rewritten 2026-10-07). The design gate re-run has passed
> (PR #231), so implementation starts at task 03. Task 10b may run any time before task 11 (it only touches `ai.scoring` and the chat);
> task 08 uses its `ConversationContext`, so if 08 runs first, give it a minimal `ConversationContext` stub and finish
> it in 10b.

> **Branches and pull requests (user decision, 2026-10-08).** `main` only ever gets the finished intake:
> - **Feature branch `gh216-email-intake`** (from `main` @ `00d95cf`; first commit `b1a450f` adds it to CI's
>   `pull_request` trigger). Each task gets a branch off it (`gh216/<NN>-<topic>`, e.g. `gh216/03-mailbox`) and a PR
>   **into the feature branch**, ending `Part of #216`. The user reviews it, then it's squash-merged. Tasks 05 and 06
>   share one PR (one agent topology; user decision, 2026-10-09): opened as a **draft** with task 05 as its first
>   commit, task 06 added as a second commit on the same branch (`gh216/05-extraction-agents`) so it can be reviewed
>   on its own, then marked ready and **squash-merged like the rest**, so the two tasks are one commit in the history.
> - **Link task and PR both ways:** the PR body opens with "Implements task NN of #216" linking the task file (pinned to
>   the PR's commit), and the task's line in the Task Plan plus the task file's Outcome name the PR.
> - **Each task also has a GitHub sub-issue of #216** (created retroactively 2026-10-26; see the Task Plan list above
>   for the numbers). A task's PR body should also reference its sub-issue (e.g. "Part of #257"), and the sub-issue
>   gets closed with a link to the merging PR, the same way #220–#245 were closed for tasks 01–09.
> - **Task 10b is the exception:** it's complete on its own (the generic conversation core, the chat moved onto it
>   with no behavior change, the quarkus#54354 guard), so its PR goes **straight to `main`**. Merge `main` into the
>   feature branch afterwards, before task 11.
> - **Keeping up with `main`:** merge `main` into the feature branch locally and push (no PR needed: the `main`
>   ruleset doesn't cover it). Its required checks are strict, so the final PR must be up to date anyway.
> - **Final PR** after task 14: `gh216-email-intake` → `main`, linking every task PR, merged with a **merge commit**
>   (user decision) so each reviewed task stays its own commit on `main`. The `main` ruleset allows only squash, so
>   allow merge commits on the ruleset (and in the repo settings) for that one PR, then switch back. Then remove the
>   branch from the CI trigger and delete it.

---

## Shared Context

### Overview
**Task 03 outcome (2026-10-08):** Flow 1.1.3 comes in through the platform `quarkus-flow-bom`, alongside the agentic
extension, with quarkus-langchain4j still on 1.14.1. `IntakeConfig` (`parasol.intake.*`) and `ClaimsMailbox` (list, find
by `Message-ID` in a given folder, move between `MailFolder`s) parse mail into `InboundEmail`, with the stripped body,
images on the allow-list, skipped attachments with reasons, and auto-reply detection. They're tested over real SMTP/IMAP
against the Compose GreenMail. GreenMail 2.1.14 supports IDLE (task 09). An email can arrive with no `Message-ID`, so
`messageId` is an `Optional`. **Decided (user, 2026-10-08):** an email without one takes the failure path (failed
folder, processing-problem reply, no run; task 08 starter rule 2b), rather than getting a generated UUID.

**Task 04 outcome (2026-10-08):** the nine fixed replies are Qute mail templates (`org.parasol.intake.reply.IntakeTemplates`,
HTML and text), sent by `IntakeReplySender`, which **blocks** (no `Uni`) for up to `quarkus.mailer.timeout`. It sets the
`[CLM…] Re:` subject prefix (`Re:` alone with no claim), `Auto-Submitted: auto-replied`, `In-Reply-To` and a `References`
that carries the inbound email's own `References` plus its `Message-ID`. The sign-off is `parasol.claims-department.*`,
read through Qute's `config:` namespace by the templates, `GenerateEmailService` and its ending guardrail alike.
**Decided (user, 2026-10-08):** replies greet the claim's `clientName` when the email resolved to a claim from that claim's
address, otherwise the `From` display name, otherwise "Customer" (`CustomerName.forReply`); "Which claim?" lists the
sender's own pending claim numbers; `notification` doesn't use the intake sender. `MissingItem` and `IntakeClaimStatus`
live in `org.parasol.intake`. Details in the task file's Outcome.

**Task 05 outcome (2026-10-08, merged in #235):** `ClaimExtractionWorkflow` is a declarative `@ParallelAgent` over the
summary, sentiment and incident-details agents (`extractClaim(correspondence, extractedSoFar, requestedItems,
sentDate)`), with guardrails for JSON, length and dates. A date or time the model never corrects is treated as
missing, through the workflow's `@ErrorHandler`; malformed JSON still fails the run. `findMissingItems` is a pure
helper for task 08; the correspondence cap moved to task 08. Packages: `intake.model` for the records,
`intake.agent.extraction` for the rest. Two quarkus-langchain4j findings for tasks 06+: `@ModelName` only works on
the `@Agent` method (and `@RegisterAiService(modelName)` is needed too, or `-Pollama` fails the build), and an entry
agent's own parameters only validate when it's injected in `src/main` (`ClaimExtractionWorkflowEntryPoint`,
`@Unremovable`; task 06 deletes it). Details in the task file's Outcome.

**Task 06 outcome (2026-10-09, merged in #235):** `ClaimsMailboxAgent` (classifier → `EmailRouter`, a three-way
`@ConditionalAgent` deciding on the matched claim first) returns the sealed `IntakeOutcome`; `ClaimResolver` is a plain
AI service whose answer task 08 limits to the offered claims. "No match" is `MatchedClaim.none()` (a `null` input counts
as missing), the extraction's date recovery moved to the root's `@ErrorHandler` (a nested workflow's own handler doesn't
apply under a root), and the status tool is scoped to the matched claim through `InvocationParameters`, which
quarkus-langchain4j 1.14.1's build check forces onto the root's signature too (filed as quarkiverse/quarkus-langchain4j#2950,
fix in #2951). Details in the task file's Outcome.

Code in a new package `org.parasol.intake` (with `agent`, `mailbox`, `reply` and `review` sub-packages), plus the
generic conversation core and its adapters in `ai.scoring.conversation` (task 10b, task 11).
This follows the domain-first layout of the existing code (`org.parasol.claim`, `org.parasol.chat`, `org.parasol.notification`).
The existing chat (`ClaimService`, chat scopes, `NotificationService`) behaves as today; its conversation handling moves
onto the generic conversation package `ai.scoring.conversation` (task 10b), with no behavior change.

```
Roundcube ──SMTP──▶ GreenMail ◀──SMTP── app (Qute replies, NotificationService)
                        ▲
                        └──IMAP IDLE── ClaimsInboxWatcher → ClaimIntakeStarter ─(handoff)─▶ next email
                                                               │ starts one run
                                                               ▼
                         ClaimIntakeFlow: supersede → runAgents (ClaimsMailboxAgent) → policy → persist
                                          → reply → file → [complete] waitReview (listen, kept in PostgreSQL)
                         claims processor (UI) → POST review-decision → publish REVIEW_DECIDED ⇢ apply decision
```

### Project Context
- Issues 2–4 provide:
  - the natural-id claim number and category enum
  - incident date/time
  - `ClaimImage` storage plus a store method
  - GreenMail + Roundcube in `compose-devservices.yml`, plus a GreenMail test helper
- LLM mocking: a WireMock dev service with a `QuarkusTestProfile` that repoints model base URLs, and
  stubs registered in `@BeforeEach`. CI runs `-Pollama` and `-Pollama-openai` with only `OPENAI_API_KEY=change-me`.
- `GenerateEmailService` is the existing **AI-written** email. The intake's fixed emails are Qute
  templates on purpose, to show both patterns.

### Key Decisions
- **Task 08 planning decisions (user, 2026-10-11)**, detailed in task 08's *Implementation Handoff*:
  - D1: task 08 adds a minimal `ConversationContext`; 10b completes it.
  - D2: a `tryCatch` around the decision steps restores the review and loops back to `waitReview`.
  - D3: a reply that finds its claim being decided goes back to the front of the sender's queue; the per-sender lock
    also covers a woken review run, with a short backoff for the gap.
  - D4: one `ClaimCorrespondence` table records every inbound `Message-ID` (no `Claim.sourceMessageId`).
  - D5: a reply replaces only a generated policy number (`policyNumberGenerated`), through the same rule.
  - D6: `ReviewDecided(claimId, runId, decision)`; the review wait matches both ids.
  - Same customer: the address matches, and the name is compared only when both are present.
- **Task 08 decisions made while building (user, 2026-10-11):**
  - **The intake's bookkeeping is not on `Claim`.** `ClaimIntakeState` (`org.parasol.intake.model`, 1:1 `@MapsId`, lazy, no reference
    from `Claim`) holds `reviewRunId`, `decidingRunId`, `intakeConversationId`, `policyNumberGenerated`, requested items and
    the `@Version`. The lock the reply/decision race needs is on that row; `Claim` has no `version` column. Claim retrieval
    neither returns nor loads any of it (`ClaimRetrievalHasNoIntakeBookkeepingTests`). Tasks 09, 10, 10b and 11 are updated.
  - **`IntakeReplySender` takes no `Optional` parameters:** a `ThreadingHeaders` record, and overloads with and without a claim number.
  - The per-sender queue is an immutable `SenderState` changed only through `ConcurrentHashMap.compute` (no `synchronized`),
    with a Mutiny delay for the requeue backoff.
- **Task 08 review, round 1 (user, 2026-10-11):**
  - **`@Transactional`, not `QuarkusTransaction`,** as much as possible: a transaction that mustn't span an LLM or mail call
    is its own non-private `@Transactional(REQUIRES_NEW)` method (Quarkus intercepts self-invocation). Applies to later
    tasks too (task 10's claim-the-review step).
  - **JPA entities live in a `model` sub-package** (`ClaimIntakeState` → `intake.model`).
  - **Sub-packages by concern:** `intake` keeps only what every sub-package shares; `intake.dispatch` (the starter side),
    `intake.failure`, and `intake.workflow` with `policy`, `outcome`, `review` and `mail` sub-packages. Tests sit in the
    package of what they test.
- **Triage flow:** code matches the claim and checks the sender first; then classify (`NEW_CLAIM` /
  `CLAIM_FOLLOW_UP` / `NOT_A_CLAIM`) and route, **matched claim first**, then the label (gap 1). Agents have
  **no side effects**; the intake workflow's steps (and the starter's skip and failure paths) do all persistence, mail
  and folder moves.
  Unmatched not-a-claim and follow-up emails go to a non-LLM agent (design, Agent architecture).
  *(Under b1, "the processor" is the intake workflow's steps plus the starter; task 08.)*
- **Agent context (user decision):** every intake agent is **stateless**. Chat memory and Easy RAG are
  turned off on each agent interface (`chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class`,
  `retrievalAugmentor = NoRetrievalAugmentorSupplier.class`); by default agents share one `"default"`
  chat memory across all calls (spike Q6). History across the back-and-forth is passed **explicitly as
  input** by the processor:
  - the claim's combined correspondence
  - the fields extracted so far
  - the items we asked the customer for

  Extraction re-runs over the whole thread each time: a reply on a pending claim goes through the same
  `ClaimExtractionWorkflow` as a new claim (design, Workflow step 5). *(Superseded: the root no longer has a
  `@MemoryId` for a review pause; the review is the outer Flow workflow's, task 06/08.)* Per-email or per-claim chat
  memory was rejected:
  - per-email memory has no history across replies
  - per-claim memory duplicates the claim record and needs its own persistence and cleanup
  - replaying old turns biases extraction and carries prompt injection forward
- **Required details:** what happened, the incident date, the location and the category (`OTHER` is a
  valid category). The agents do all the back-and-forth with the customer: if any detail is missing,
  the claim is `Pending Information` and the customer is emailed the exact list of missing items;
  replies are merged in and re-extracted. When the agents judge the claim complete (in the first email
  or after any number of replies), the claim moves to **`Pending Review`**, not `In Process`. A claims
  processor then does one last look-over on the claim detail page:
  - **Ready** → `In Process`, and the customer gets the "thank you, we're working on your claim" email.
  - **Needs more information** → the processor checks the missing items → `Pending Information`, and
    the customer gets the missing-information email listing exactly the checked items. That run ends
    there (no re-suspension); the customer's next email starts a new run, and the claim stays
    incomplete until the checked items are answered, so an empty reply stays `Pending Information` (gap 8).
- **Design review:** the design lives in the repository as `docs/design/email-claim-intake.md` (plus
  PlantUML diagrams), opened as PR #220 (task 02) and **merged as-is with no review feedback** (`ec1ca14`).
  The spike (task 01) ran before the design, because its results shape it: it ran ahead of issue 1, on a
  throwaway local branch in a separate git worktree, with the latest stable versions, and its code is never
  merged or pushed.
- **Human review — superseded by the Flow verdict (b1); kept as history.** Now: the review is the intake workflow's
  last steps (`waitReview` `listen` → `routeDecision` `switch` → apply; task 08), and the review endpoint publishes a
  `REVIEW_DECIDED` event after a `PersistenceInstanceReader` 404/409 check (task 10). A reply during review cancels
  the waiting run (`activeInstance(id).cancel()`); optimistic locking on the claim (`@Version`) still serializes a reply
  against a decision. No scope store, no eviction. The old text:
  - A `ClaimReviewAgent` with a **static** `@HumanInTheLoop` method returns
  `Object` (`new SuspendedResponse<>("review:" + scope.memoryId())`, no `async`), so the workflow suspends
  instead of blocking; the root surfaces `AgenticSystemSuspendedException`, which the processor catches. A
  `DatabaseAgenticScopeStore` (task 03; key `agentId|memoryId`, registered JVM-globally through
  `AgenticScopePersister` by a lowest-priority `StartupEvent` observer, reset on `ShutdownEvent`) saves the
  scope in PostgreSQL, so the pause survives restarts.
  - Memory id = the inbound email's `Message-ID`. The claim stores it in a `reviewRunId` column.
  - `ClaimReviewService.decide` (behind `POST /api/db/claims/{id}/review-decision`, task 10) claims the
    review under optimistic locking, completes the pending response, re-invokes the root with the original
    arguments read back from the scope (`readState`), hands the outcome to
    `ClaimEmailProcessor.applyReviewOutcome` (task 08) and evicts the scope. A non-LLM router turns the
    decision into the outcome (gap 6).
  - **The scope is evicted on every ending:** completed, policy-rejected, superseded by a reply, failed, and
    after the resume. Only a run waiting for review keeps a row; scopes are never deleted automatically.
  - Decision gate: **passed** (spike verdict: feasible with workarounds). No status-only fallback.
- **Every submission gets a reply** confirming what the customer did and what happens next:
  - first email complete → "received — final review" (claim number, `Pending Review`, what we
    recorded, a claims processor does a final check and we'll be in touch, photos note, skipped-attachments fragment)
  - first email incomplete → missing information
  - reply that completes a pending claim → "received — final review"
  - reply still incomplete → still missing
  - reply while `Pending Review` → the reply's own run cancels the waiting run and merges the reply: still complete → stays `Pending Review` (new `reviewRunId`) with the "we've added
    your latest information; your claim is still in final review" variant; now incomplete →
    `Pending Information` + missing information
  - claim `In Process` or later (including seeded statuses) → status reply; the claim is never updated
  - not a claim / policy inconsistency / no matching claim → their templates
  - no match, sender has pending claims, `ClaimResolver` unsure → "which claim?" (lists their pending claim numbers)
  - processing failure → message to the failed folder **and** a templated "processing problem" reply
    (best-effort, no details, call 1-800-CAR-SAFE)
  - reviewer decisions → thank-you (Ready) or missing information (Needs more information)
  - **no reply** only for auto-replies, self-sent mail and duplicate `Message-ID`s (already answered)

  The thank-you email is sent **only** after the reviewer clicks Ready.
- **Statuses used by intake:** `Pending Information`, `Pending Review`, `In Process`. Intake never sets `New`.
- **Summary and sentiment** are AI-generated, at most 5000 characters (guardrail).
- **Field sources:**
  - name and email: the `From:` header
  - claim number: generated (issue 2)
  - inception date: random, before the incident date
- **Supersede placement (design review, 2026-10-07):** the reply's run cancels the waiting run **after** the agents and
  the policy check, just before the claim changes, so a failed or policy-rejected reply never leaves a `Pending Review`
  claim with no waiting run. A decision that lands while the reply's agents run wins the lock (the reply then goes back
  in line under the new status).
- **Policy number** (checked in code by the processor **after the agents finish**, before any claim is
  created or changed; gap 7; the "different customer" rejection applies to **new** claims):
  - stated + same customer → reuse
  - stated + a different customer → reject with a vague email (call 1-800-CAR-SAFE), creating nothing (the run ends
    there; under b1 the check runs before any review wait, so there's nothing to evict)
  - stated + unknown → new claim on that number
  - absent → generate a unique one
- **Follow-ups:**
  - Matched in code, before the workflow, by `[CLM…]` regex, then `In-Reply-To`/`References`. **The matched
    claim wins** over the classifier's label (gap 1).
  - **No match, but the sender has pending claims (user decision, 2026-10-07: "try to route it, ask if we
    can't"):** the run's `resolveClaim` step asks `ClaimResolver` (an LLM service outside the agent topology)
    whether the email is about one of the sender's pending claims. One of them (validated in code against
    that list) → handled as matched; a new incident → new claim; unsure → the "which claim?" reply listing the
    sender's pending claim numbers, nothing changed. It closes the duplicate-claim gap for fresh emails without
    a claim number, and leaks nothing: it only offers the sender their own claims.
  - **Ordering is per sender address** (not per claim), because the claim isn't known until `resolveClaim`;
    a claim only ever matches its own address, so this covers per-claim order.
  - Only from the claim's own email address. A wrong sender, or an unmatched email labeled a follow-up,
    gets the one "no matching claim" reply (no details; asks for the claim's address or the claim number; gap 9).
  - A `Pending Information` claim gets merged in, re-summarized, and moves to `Pending Review`
    ("received — final review" email) when complete.
  - A reply during `Pending Review` supersedes the waiting review (see above). It is serialized against
    the reviewer's decision by optimistic locking on the claim (`@Version`): a losing decision gets a 409,
    a losing reply re-reads the claim (user decision).
  - A claim `In Process` or later gets an AI-written status answer only, and is **never** updated.
- **Photos:** optional. Image attachments are stored as `ORIGINAL` via `ClaimImage.store`. Only the formats in #214's
  `ClaimImageContentType` allow-list (JPEG, PNG, GIF, WebP) count as images: resolve the attachment's media type with
  `ClaimImageContentType.find`. Anything else, including `image/svg+xml`, is a non-image attachment (stored-XSS risk).
  If none arrive, the reply says photos can be sent.
  Non-image or oversized attachments are skipped and mentioned. New claims get no processed images.
- **Idempotency:** a unique stored `Message-ID`. Replies carry `Auto-Submitted: auto-replied`, and
  inbound auto-replies and self-sent mail are skipped.
- **Inbox handling:** HTML-only email is converted to text. **The watcher doesn't wait for runs (user decision,
  2026-10-07):** it records the `Message-ID`, moves the email to a `processing` folder and starts its run. Runs for
  different senders go in parallel; a sender's emails go in order (a per-sender queue: the next waits while that
  sender's previous run is still working; a run waiting for review is canceled instead). Only waiting runs are ever canceled, never one
  mid-step. One watcher, no pool: Flow's executor gives the parallelism and caps it. A failure listener handles every
  failed run (failed folder + processing-problem reply). The run moves processed mail to the processed folder.
- **Observability** (task 11), rewritten for b1:
  - **Superseded:** the synthetic `claim-intake process` CONSUMER root span, the `@AgentListenerSupplier` span tree,
    `@ParallelExecutor` (quarkus-flow#1057) and the linked review-decision span. Flow's own `workflow.execute` /
    `task.execute` spans replace the root and agent spans (typed AGENT by a Flow-adapter `SpanProcessor`); the decision
    steps run inside the claim's own run.
  - **One run is still several traces** (quarkus-flow#1056); the claim is one **conversation**, not one trace.
  - Langfuse types observations from `gen_ai.operation.name` (`invoke_agent` → AGENT, `chat` → GENERATION, `execute_tool` → TOOL).
  - IMAP fetch/move and SMTP send get `CLIENT` spans.
  - **Conversation grouping (user decision):** every span of a claim's runs carries `gen_ai.conversation.id` (Langfuse
    maps it to the session id, langfuse/langfuse#7738), so a claim is one Langfuse session.
    - The id is a per-claim UUID (not the claim number, which doesn't exist when the first spans start; no PII),
      minted for unmatched emails and stored on the claim (`intakeConversationId`) when a claim is created; follow-ups and
      the review decision reuse it (task 08).
    - **Carrier: baggage** (as the chat's `ConversationalBaggageHandler` does; user decision, 2026-10-07). The
      carry-as-data approach from follow-up spike 2 is **dropped**. Follow-up spike 3 traced the leak to
      quarkus#54354 (fixed by quarkus#56805, not yet in 3.40.x).
    - **Workaround until the fix ships (user decision, 2026-10-07):** one clearly named class linking quarkus#54354,
      deleted when the fix lands. **It belongs in the generic core, not the Flow adapter:** the bug is in Quarkus's
      context propagation, so every `ManagedExecutor` leaks. The leaked ids in spike 3 were all on Flow's
      persistence spans, which run on quarkus-flow-jpa's own `ManagedExecutor`, not on Flow's
      `ExecutorServiceFactory`. (Flow also injects its executor factory by concrete class, so it can't be swapped
      cleanly.) **Proven (follow-up spike 4):** an extra MicroProfile `ThreadContextProvider` of its own context type,
      registered through `META-INF/services`, which resets a pool thread to `Context.root()` when a task ends with
      the propagated context still current. No Quarkus class is overridden (task 10b). A project-owned
      `ConversationIdSpanProcessor` stamps the id onto every span, so it works with the Langfuse processor off (`%test`)
      and for Tempo/LGTM.
    - Tier-2 session scoring doesn't run for intake conversations: nothing in the intake fires `ConversationEndedEvent`
      (task 10b), and the session scorer reconstructs exchanges assuming the chat's shape. The tier-1 judge still scores
      every intake LLM call. *(A later decision could fire it when a claim leaves the intake states.)*
  - **Generic conversation context (user decision, 2026-10-07).** The conversation concept is application-agnostic and
    will move to a separate library, so it's designed as one now, staged in `ai.scoring.conversation`:
    - **Core** (OTel API only): a `ConversationContext` API to start, read and run code within a conversation, a
      `ConversationIdSpanProcessor` stamping `gen_ai.conversation.id` on every span, and the conversation lifecycle
      **CDI events**, starting with `ConversationEndedEvent` (the conversation id).
    - **Adapters**, each only translating one framework's lifecycle into the core: chat scopes (today's
      `ConversationalBaggageHandler`, which fires `ConversationEndedEvent` on `ChatScopeEnded`), quarkus-flow (restores
      the context at Flow's thread hops, owns the "make the task span current" helper) and LangChain4j agentic (an
      `AgentListener`).
    - **Consumers react to the events and never call an adapter:** session scoring becomes a single
      `@ObservesAsync ConversationEndedEvent` listener, instead of `ConversationalBaggageHandler` calling
      `SessionScoringService` directly. Any number of places can fire the event; there's one listener.
    - **No mixing:** the intake workflow only says which conversation a run belongs to, with no span code in any step.
      The core never depends on an adapter, and adapters never depend on each other.
    - **The Flow adapter's job** (task 11): a `CallableTaskProxyBuilder` makes each task's context current, so steps
      need no helper. The generated agent sub-workflows (`FlowPlanner`'s `supplyAsync`, quarkus-flow#1056) get no
      context: an agentic `AgentListener` adapter covers their AI calls (to be proven in task 11), and the sub-workflows'
      own Flow spans are a user decision in task 11.
    - **Built in:** task 10b (core, chat adapter, scoring listener, leak guard) and task 11 (Flow and agentic adapters).
  - `IntakeMetrics` registers the `claim.intake.*` meters, with no high-cardinality tags (no claim numbers, `Message-ID`s or addresses).
  - Intake log lines carry the trace id.
  - Intake LLM calls **are** scored by the tier-1 Langfuse judge (user decision).
  - Langfuse span export is off in `%test` (`quarkus.langfuse.otel.enabled: false`), except in `LangfuseSessionScoringServiceTests`.
  - `MonitoredAgent`: **the root extends it, in every profile; both Dev UIs are kept** (user decision 2026-10-07, after
    spike 5's screenshots). The agentic Dev UI shows the per-run agent detail (branch taken, tool calls, tokens), and
    Flow's shows the business workflow and the review wait. The old dev-only decision was driven by suspended
    `@HumanInTheLoop` runs leaking in `ongoingExecutions`. Under Flow the agent call ends before the run waits, and
    spike 5 measured 0 ongoing with 3 runs waiting. **Finished sessions are kept only in dev mode** (user decision
    2026-10-07): at startup, outside `LaunchMode.DEVELOPMENT`, `setMaxRetainedSessions(0)`. That's checked by launch
    mode, not by profile, because dev runs activate `prod,ollama`, not `dev`. The monitor itself can't be made
    dev-only: it isn't a CDI bean, and `extends MonitoredAgent` is fixed at compile time (details in task 11).
  - Grafana panels are a separate issue: #218 (Clean up the Grafana AI dashboard).
- **Out of scope:** non-English email, multiple incidents per email, drift detection for the agents.
  ~~the reviewer editing extracted fields~~ — **reversed, 2026-10-26, in task 10**: reviewing the built review panel
  surfaced that checking an abstract "category" checkbox with no indication of the claim's current value gave the
  reviewer nothing to actually judge. The user decided a reviewer should see and correct the claim's own extracted
  fields directly (a human's correction becomes the claim's truth with no customer round-trip), with a per-field
  toggle for "I don't know this, ask the customer" instead. See task 10's *Resume Here* for the full design.

### Caveats & Problems
- Never hold a transaction open across an LLM call. Persist in a `@Transactional(REQUIRES_NEW)` method of its own (task 08 review: prefer `@Transactional` to `QuarkusTransaction`).
- **Two observability workarounds with exit conditions:** the quarkus#54354 `ThreadContextProvider` guard (task 10b;
  delete when quarkusio/quarkus#56805 ships) and the agentic conversation adapter (task 11; delete when
  quarkus-flow#1056 is fixed). Re-check both, and #1057/#1058, whenever Quarkus or Flow is upgraded. The #54354 guard
  is also a standing per-task check (Execution Steps → 2c), together with the other open upstream items.
  - **2026-10-09: #1056 is fixed in Flow 1.2.0, which task 06b adopts.** Task 11 now expects to drop both its Flow task
    proxy and the agentic adapter, after a concurrent test proves the baggage id reaches every span (see task 11's
    "Check first"). The quarkus#54354 guard stays: #56805 is in no 3.40 release yet (3.40.1 is the latest).
  - **#1056 has a proposed fix (2026-10-07):** [quarkus-flow#1065](https://github.com/quarkiverse/quarkus-flow/pull/1065)
    (open, against `main` = 2.0.0-SNAPSHOT; not in any release, no 1.1.x backport yet). It makes the task span current
    in every task body (`OTelTaskSpanProxy`, on by default, `quarkus.flow.otel.task-span-current`), carries the
    caller's context into the generated agent workflows (`FlowPlanner.firstAction`), and activates the requesting
    task's context on the agent's thread (`FlowAgentContextListener`). It propagates the whole OTel `Context`, so
    baggage rides along. If it ships in a version we can use, it replaces **both** our Flow task proxy and the agentic
    adapter (task 11), and the sub-workflows' own spans get the id too. Its only failing check on 2026-10-07 is the
    website build's generated-docs validation, on `quarkus-flow-persistence*.adoc`, which the PR doesn't touch.
  - **Re-checked 2026-10-08:** #1065 is still open with no reviews; #1056, #1057 and #1058 are open; Flow 1.2.0 final
    isn't out (1.2.0.CR3), and 1.1.3 is the latest 1.1.x release. quarkus#56805 is merged for 4.0.0.Beta1 and labeled
    `triage/backport-3.40`, but 3.40.1 is still the latest 3.40 release. Both workarounds stay.
- **quarkus-flow 1.2.0: released 2026-10-09 and adopted ahead of the platform (task 06b, user decision 2026-10-09).**
  - **State on 2026-10-09** (checked with `gh` and Maven Central): 1.2.0 was released at 19:21Z. Its release notes include
    #1065 (#1056), #1068 (#1057), #1059 (#1058) and #1066 (#1040); the milestone's #1013 (log/trace correlation, PR #939)
    is still open and not in it. quarkus-platform 3.40.1 (the latest) still pins Flow 1.1.3, and no platform PR for 1.2.0
    exists yet. 1.2.0's parent is on Quarkus 3.39.0 and quarkus-langchain4j 1.14.1.
  - **Reproducers** ([edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers), version
    set in its `pom.xml`, the loaded jars checked in Quarkus's bootstrap model): all five tests fail on 1.1.3 and pass on
    1.2.0. On 1.2.0 the task span is current inside the task body, one `TracingFlow` run is 24 spans in **1 trace** (6
    on 1.1.3), a `@ParallelExecutor` runs, and 10 canceled waiting runs each export their `workflow.execute` span.
  - **Our intake suite on 1.2.0**: 146 tests (all 22 intake classes), 0 failures under default, `-Pollama` and
    `-Pollama-openai`, in task 06b's full `clean verify`. The rest of the suite is unchanged from the base commit (10
    Playwright failures locally, the same names on `1b232cc`, green in CI).
  - **What the override costs:** an `io.quarkiverse.flow:quarkus-flow-bom:${quarkus.flow.version}` import *after*
    quarkus-bom (its Quarkus 3.39.0 parent then changes no `io.quarkus` version). It moves serverlessworkflow 7.32.1 →
    7.35.3, cloudevents 3.0.0 → 5.0.0, h2-mvstore and classgraph (patch), and brings `quarkus-scheduler` +
    `cron-utils` in through `quarkus-flow-persistence-common`, for 1.2.0's periodic re-scan of persisted runs (#1064).
    The re-scan is off unless `quarkus.flow.persistence.scan-interval` is set (it's `Optional`, no default), and it skips
    instances already active in this JVM.
  - **Dropping the override** (Execution Steps → 2a): superseded on 2026-10-26 by the 1.3.0 bullet below — the override
    now stays until a platform pins Flow ≥ 1.3.0 (see the 1.3.0 bullet below).

  *History (2026-10-08): the maintainer's plan and the state before the release, kept as written.*
- **quarkus-flow 1.2.0 (expected EOD 2026-10-09; recorded 2026-10-08).** The maintainer, on
  [#1065](https://github.com/quarkiverse/quarkus-flow/pull/1065): *"my goal is to have all the telemetry PRs merged by
  tomorrow and release 1.2.0 my EOD. I'll set a milestone for it."* Every task re-checks it first (Execution Steps → 2a).
  - **Where things stand on 2026-10-08** (checked with `gh` and Maven Central):
    - Flow's latest releases are 1.1.3 (the one we use, via platform 3.40.1's `quarkus-flow-bom`) and 1.2.0.CR3
      (2026-10-06). `main` is 17 commits past 1.2.0.CR3 and still `2.0.0-SNAPSHOT`, with `current-version: 1.2.0.CR3`
      in `.github/project.yml`, so 1.2.0 is cut from `main`. Platform 3.40.1 is the latest platform and pins Flow 1.1.3.
    - Flow's [1.2.0 milestone](https://github.com/quarkiverse/quarkus-flow/milestone/12) (due 2026-10-09) holds all three of the user's issues: #1056 and #1058 open, #1057 closed. It
      also holds two others' tracing issues, [#1040](https://github.com/quarkiverse/quarkus-flow/issues/1040) and
      [#1013](https://github.com/quarkiverse/quarkus-flow/issues/1013), both open.

    | Upstream | What it is | State on 2026-10-08 |
    |---|---|---|
    | [#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056) / PR [#1065](https://github.com/quarkiverse/quarkus-flow/pull/1065) | Task spans not current in task bodies; agentic sub-workflows start new traces | issue open; PR open, checks green, not yet reviewed |
    | [#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057) / PR [#1068](https://github.com/quarkiverse/quarkus-flow/pull/1068) | `@ParallelExecutor` on a `@ParallelAgent` throws at runtime | **fixed**: merged into `main` 2026-10-08 (`043833e`), after 1.2.0.CR3 |
    | [#1058](https://github.com/quarkiverse/quarkus-flow/issues/1058) / PR [#1059](https://github.com/quarkiverse/quarkus-flow/pull/1059) | `cancel()` on a waiting run loses its `workflow.execute` span | both open |
    | [#1040](https://github.com/quarkiverse/quarkus-flow/issues/1040) / PR [#1066](https://github.com/quarkiverse/quarkus-flow/pull/1066) | Trace continuity after a JVM restart (not ours; in the milestone) | both open |
    | [#1013](https://github.com/quarkiverse/quarkus-flow/issues/1013) / PR [#939](https://github.com/quarkiverse/quarkus-flow/pull/939) | Flow lifecycle logs carry the active trace (not ours; in the milestone) | both open |
  - **What 1.2.0 would change**, if the reproducers confirm each fix (the starting list for step 2a.3):

    | Task | Today | With 1.2.0 |
    |---|---|---|
    | 03 (done) | Flow 1.1.3 from platform 3.40.1's `quarkus-flow-bom` | Override to 1.2.0 until a platform pins it (user decision); re-run `IntakeExtensionsTests` |
    | 05 | No `@ParallelExecutor` (#1057) | It works again; decide whether `ClaimExtractionWorkflow` wants one at all (Flow already forks on the `ManagedExecutor`) |
    | 08 | Don't assert a canceled run's `workflow.execute` span (#1058) | Drop the rule, assert the span |
    | 11 | Our Flow task proxy plus the agentic conversation adapter; several traces per run (#1056) | #1065 replaces both (it propagates the whole OTel `Context`, so the baggage id rides along); one trace per run; the sub-workflows' own spans get the id. Keep the `invoke_agent` typing and the concurrent no-mixing test. Recheck the log trace-id step against #1013 |
    | 13 | Documents the workarounds and their exit conditions | Document what 1.2.0 fixed instead |
    | 14 | Re-check #1056/#1057/#1058 for the pinned Flow version | Same, against 1.2.0 |
- **quarkus-flow 1.3.0: released 2026-10-23, adopted in task 09b (user decision, 2026-10-26) — done.** It supersedes
  the 1.2.0 override above; that bullet and its history stay as written.
  - **Why:** 1.3.0's release notes include [#1095](https://github.com/quarkiverse/quarkus-flow/pull/1095) ("End
    generated agentic workflows when one of their agents throws", the user's own PR), the upstream fix for
    [#1096](https://github.com/quarkiverse/quarkus-flow/issues/1096) — the leak task 08 hit on 2026-10-19 and worked
    around in `org.parasol.intake.agent` by reflecting into `FlowPlanner`'s private internals. Task 09b deleted all
    three workaround pieces (`AgenticWorkflowRuns`, `FailedAgenticSystemListener` and
    `ClaimsMailboxAgent.endFlowRunsOnFailure()`) and kept `FailedAgenticSystemListenerTests`, renamed to
    `FailedAgenticSystemTests`, as the regression guard against upstream — it passes unchanged against 1.3.0 with the
    workaround gone. The known remaining leak
    (`ClaimExtractionWorkflowTests.malformedJsonThatIsNeverFixedStillFailsTheRun`, which calls the extraction workflow
    directly, without the root's listener) is fixed too: `FlowAgentContextListener` sees every agent's failure, not
    just the root's.
  - **What to watch in the 1.2.0 → 1.3.0 delta:** [#1077](https://github.com/quarkiverse/quarkus-flow/pull/1077) turns
    workflow **input schema validation on by default** (`quarkus.flow.validation.input.enabled`) — confirmed
    `ClaimIntakeFlow`'s `IntakeRun` passes, but it broke every generated agentic workflow (`ClaimsMailboxAgent`,
    `ClaimExtractionWorkflow`). Investigated by decompiling `quarkus-flow-langchain4j-deployment`'s
    `JandexMethodInputJsonSchema`: the generated schema marks every field of every record in the agent topology
    `required`, unconditionally, with no nullable/`Optional` support — including fields that are the agentic system's
    own not-yet-computed outputs, and `InvocationParameters` (which `langchain4j-agentic` deliberately keeps out of
    `AgenticScope.state()`, so a parameter every caller correctly supplies is still reported missing). There is no
    input shape that satisfies this schema, and no per-workflow scoping for the flag, so "fix the input instead of
    disabling the flag" isn't achievable here — turned off app-wide in `application.yml` instead. Filed upstream as
    [quarkiverse/quarkus-flow#1111](https://github.com/quarkiverse/quarkus-flow/issues/1111); re-enable once it's
    addressed there — tracked as Execution Steps → 2b, a standing per-task check like 2a's. [#1086](https://github.com/quarkiverse/quarkus-flow/pull/1086) consolidates the `quarkus.flow`
    config roots, which is low-risk here because no other `quarkus.flow.*` property is set anywhere in `src/` (re-grep
    rather than assume). #1106/#1105 (JQ expression-factory priority, native reflection registration), #1079 (a
    synchronous runner interface) and #1108 (member lease renewal conditional on ownership) are not obviously ours.
  - **Version alignment:** `quarkus.langchain4j.version` is already 1.14.2, which is what 1.3.0's parent aligns to, so
    no reconciliation is needed. Expect cloudevents 5.0.0 → 5.1.0 and new managed `quarkus-flow-db-migration` entries
    we don't depend on.
  - **Dropping the override** (Execution Steps → 2a): once a platform we move to pins Flow ≥ 1.3.0.
- **Flow comes in through the platform BOM** (`io.quarkus.platform:quarkus-flow-bom`, task 03), not the Quarkiverse
  `io.quarkiverse.flow:quarkus-flow-bom`. The Quarkiverse BOM's parent imports an older Quarkus BOM (3.39.0 for Flow
  1.1.3), so it brings stale entries. Platform 3.40.1 ships Flow 1.1.3, the version the spikes verified.
- Tests must delete the claims and images they create (`ClaimsListPageTests` expects 6) and must
  never assert absolute claim numbers.
- Every `@QuarkusTest` stubs API keys through its profile, to avoid `SRCFG00011` under `-Pollama-openai`.
- Agent builds don't have real API keys. Only compilation (and LLM-mocked tests) are trustworthy signals.
- **Human review mechanics — superseded by the Flow verdict (b1); kept as history.** None of the scope-store,
  allowlist, eviction or `@HumanInTheLoop` rules below apply any more; the quarkus-flow gotchas are in task 13's
  Gotchas list and `spike-results-flow.md`. The old text:
  (langchain4j-agentic beta; Quarkus adds no HITL or persistence support):
  - Beta module (`langchain4j-agentic` `-beta30`). Suspend/resume and persistence are documented upstream
    and mostly public; the only internal touch points are `SuspendedResponse` (`dev.langchain4j.agentic.internal`)
    and the `@Internal` `DefaultAgenticScope` exposed by the `AgenticScopeStore` SPI.
    `AgenticScopePersister.setStore` must run before any root's first invocation (lowest-priority
    `StartupEvent` observer, task 03). Keep every use behind `DatabaseAgenticScopeStore` and `ClaimReviewService`.
  - The scope store is a **JVM-global static** (`AgenticScopePersister`). It survives a Quarkus restart in the
    same JVM, so the registrar resets it on `ShutdownEvent`. Tests don't replace it.
  - Deserialization needs an allowlist for scope types that appear in no agent signature (e.g. the review
    decision): `AgenticScopeSerializer.allowDeserializationType`, checked by a startup round-trip self-test (task 03).
  - Persistent scopes are never deleted automatically: evict manually. No scope rows may leak, in the app or in tests.
  - Don't use `async = true` with suspension.
  - The `@HumanInTheLoop` method must be `static` (Quarkus build-time check), so it can't use CDI injection.
  - Decision gate: **passed** by task 01 (suspend/resume works through Quarkus); the status-only fallback isn't needed.
- `%drift` disables the OTel SDK, so no spans there (expected).
- **Incident time (deliberate difference from the design):** the merged design lists the extracted details as
  description, date, location, category and stated policy number, and doesn't mention the time. Task 05 also
  extracts the incident time when stated (ISO `HH:mm` string), because #213 adds an optional `incidentTime` to
  `Claim`. It is never required, never asked for and never makes a claim incomplete. Task 13 aligns the design
  doc if the implementation keeps it.
- **Open implementation points** (not in the design doc, by user decision; settle them in the named tasks).
  **Applied to the task files at the design gate** (after PR #220 merged):
  - *(Superseded 2026-10-07 by spike 5: `MonitoredAgent` is kept in every profile, see Key Decisions → Observability.)*
    How to make `MonitoredAgent` dev-only (task 11): see Key Decisions → Observability. **Applied:** task 11 drops
    the `@UnlessBuildProfile("dev")` cap, requires no monitor outside dev, and lists candidate mechanisms to
    investigate and present to the user (still a user decision at implementation time); task 13/14 updated.
  - A customer reply arriving while a reviewer is deciding (tasks 07/08/10). **Applied** to tasks 07
    (`@Version`, `decide(claimId, expectedVersion, …)`, race test), 08 (rule 5 lock and lost-lock test) and 10
    (`version` in the body, 409 on a lost lock). **Decided (user confirmed):**
    the reply supersedes the review and evicts its scope, while the decision tries to resume the same
    scope. Serialize the two with optimistic locking on the claim (`@Version`): whichever loses gets a
    409 (decision) or re-reads the claim (reply). Cover it with a test. Fold this into tasks 07, 08 and
    10 together with the spike-driven changes after the design review.
- **Gaps found while writing the design doc** (found at design commit `876fe9d`; merged unchanged at `29b5073` in
  PR #220, merge commit `ec1ca14`). **All nine are applied to the task files** (at the design gate), as follows:
  1 → tasks 04, 06, 08; 2 → tasks 07, 08, 10 and Key Decisions; 3 → task 11 and Key Decisions; 4 → tasks 03, 07;
  5 → tasks 05, 11; 6 → task 07; 7 → task 08 and Key Decisions; 8 → tasks 05, 08 (empty-reply tests);
  9 → tasks 04, 06, 08. The original text is kept below as history.
  1. **Classification vs. claim resolution, decided (user):** the matched claim wins.
     - If code resolves a claim (`[CLM…]` regex or reply headers), the email is handled as a follow-up on
       that claim, whatever the classifier says. The sender check still applies.
     - If the classifier says follow-up but nothing matches, the customer gets the "no matching claim"
       reply, which asks for their claim number. No claim is created or changed.

     Reflected in the design workflow. Fold it into task-06 (routing), task-08 (processor rules + tests)
     and task-04 (the "no matching claim" template asks for the claim number).
  2. **Needs more information after resume:** Key Decisions end that resumed run at `Pending Information`
     (the customer's next email starts a new run). Spike Results' task-10 list says "handle NEEDS_INFO
     re-suspension". The design follows Key Decisions; drop the re-suspension wording.
  3. **AgentListener contradiction:** Key Decisions → Observability and task-11 say a CDI `AgentListener`
     bean creates the composite-agent spans; spike Q16c shows it only reaches AI leaf agents. Use a root
     `@AgentListenerSupplier` with `inheritedBySubagents()=true` (spike wins).
  4. **task-07 vs. spike:**
     - root return type: catch `AgenticSystemSuspendedException`, per the spike, rather than `ResultWithAgenticScope`
     - scope-row key: `agentId|memoryId`, per the spike, rather than memory id only
     - allowlist: `allowDeserializationType`, per the spike, rather than `registerForDeserializationPackageOf`
  5. **Stale task files already covered by "Changes required in later tasks":**
     - task-05: `LocalDate`/`LocalTime` in scope values → ISO strings
     - task-11: `MonitoredAgent` capped via `@UnlessBuildProfile("dev")` → dev-only per the user decision
     - task-11: CDI listener → see item 3
  6. **Review decision router:** the design shows it as a non-LLM agent (an `@ActivationCondition` route,
     as in the spike). Make task-07 say so explicitly.
  7. **Policy check placement (from the design review):** the stated policy number comes from the
     extraction agent inside the root workflow, so for a complete claim the run has already paused when
     the processor checks it. The processor applies the policy rule in code after the agents finish and
     before any claim is created or changed. On rejection it **evicts the run state (including a paused
     review)**, sends the policy-inconsistency reply, and creates no claim. Fold this into task-08 (rule,
     eviction and a test).
  8. **Reviewer-checked items (from the design review):** after **Needs more information**, completeness
     also requires answers to the items the reviewer checked, so a reply without content stays
     `Pending Information`. Fold this into task-05 (extraction receives the requested items),
     task-08 (completeness rule) and their tests, including an empty-reply test.
  9. **"No matching claim" is one template** used for both the wrong-sender case and an unmatched
     follow-up. It gives no details and asks the customer to write from the claim's address or include
     the claim number (task-04).

### Design
- **Design doc:** `docs/design/email-claim-intake.md`, on `main`, is the **quarkus-flow b1 version merged in
  [#231](https://github.com/edeandrea/non-deterministic-no-problem/pull/231)** (`26d8b83`, 2026-10-07, no review
  feedback: **gate re-run passed**). Its worktree is removed; the local branch `design/email-claim-intake-flow` is
  kept (remote deleted on merge). The notes below are about the #220 round.
  - Observability's last bullet still says the agentic adapter is "proven before implementation starts"; spike 5 has
    since proven it. Fix that wording (doc and issue body) in task 13, with the status line.
  - **PR:** [#220](https://github.com/edeandrea/non-deterministic-no-problem/pull/220), **merged** at `ec1ca14`
    (2026-10-02) with no review feedback: the design was approved as-is, so the **gate is passed**.
  - The remote branch `design/email-claim-intake` was deleted on merge; the local branch and worktree are gone too.
  - The doc's status line still says "proposed, for review before implementation"; task 13 flips it to accepted/implemented.
- **Scope (user decision):** short. Four sections (goal, workflow, claim states, agent architecture) and three
  diagrams (email workflow, claim states, agent organization). No open questions, communication matrix,
  alternatives or review-sequence diagram.
  - This supersedes the task-02 bullet under "Changes required in later tasks" below, which asked for a review
    sequence diagram, span details and the `MonitoredAgent` decision.
- **Checks:** independently reviewed twice before opening; all findings fixed.
- **#216 issue body:** its design section says the design was reviewed and merged in PR #220, and its Architecture,
  Business rules, Spike, Observability and Testing sections match the merged design and the spike.
- **Gate outcome:** the gaps list, the open implementation points and "Changes required in later tasks" are folded
  into tasks 03–14 (see Caveats). Task 03 now also builds the scope store, registrar and self-test (moved from
  task 07, because the store must be registered before any agent runs).
- **Gate: passed.** The review is done (PR #220 merged, no changes requested), and the gaps list above and
  "Changes required in later tasks" are applied to the task files. Tasks 03+ still wait for the prerequisites
  (#212 via PR #219, then #213–#215; see Your Mission).

### Spike Results
Full evidence, the executive summary and the per-question answers (Q1–Q19) are in
[`spike-results.md`](spike-results.md).
- **Setup:** spike branch `spike/agentic-hitl` (local, unpushed), run on quarkus-langchain4j 1.14.1,
  langchain4j-agentic 1.20.2-beta30 and Quarkus 3.40.1. All 32 tests pass.
- **Feasibility verdict: YES, with workarounds.** Static `@HumanInTheLoop` → `SuspendedResponse`, a DB-backed
  `AgenticScopeStore`, `@MemoryId` = Message-ID, and a REST resume after a genuine restart all work. Completed agents
  are not called again on resume. The decision gate in task 07 is passed; no status-only fallback is needed.
- **Agents:** every AI leaf needs
  `@RegisterAiService(retrievalAugmentor = NoRetrievalAugmentorSupplier.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)`.
  - The defaults are policy Easy RAG plus one memory shared across runs (`"default"`), and the root's `@MemoryId`
    does not change that.
  - Every entry agent must be injected in `src/main`, or build validation fails.
- **Store:**
  - Register it with `AgenticScopePersister.setStore` from a lowest-priority `StartupEvent` observer, and reset it on
    `ShutdownEvent`.
  - Saves run in `requiringNew`, so they are not atomic with claim updates.
  - Evict manually on every ending. The scope also stays in memory until it is evicted, so run a single replica.
- **Resume:** read the root arguments back from the scope (`readState`) and pass them again. The arguments
  overwrite the stored state.
- **Scope values:**
  - No `LocalDate`/`Optional` anywhere in agent outputs or the decision (ISO strings instead).
  - Allowlist the decision type with `allowDeserializationType`.
  - Consumers type the review output as `Object`.
  - No `async` on the HITL agent.
- **Observability:**
  - a CONSUMER root span per email with `gen_ai.operation.name=invoke_agent`
  - a root `@AgentListenerSupplier` listener (inherited) emitting `invoke_agent <name>` spans; a CDI
    `AgentListener` bean only sees AI leaves
  - `@ParallelExecutor` returning `Context.taskWrapping(...)` on each `@ParallelAgent`
  - the review resume in a new trace with a span link
  - Langfuse types `invoke_agent` spans as AGENT, `chat` as GENERATION and `execute_tool` as TOOL
- **Needs a user decision** (decided: `MonitoredAgent` dev-only, no monitor in prod; the mechanism is open in task 11).
  *Superseded 2026-10-07: under Flow nothing suspends, so the monitor is kept in every profile (Key Decisions → Observability):*
  - `MonitoredAgent` with `setMaxRetainedSessions(0)` still keeps every suspended run in `ongoingExecutions`, and
    an abandoned review leaks forever.
  - Leaf agents are CDI singletons shared across roots, so give each leaf exactly one root.

### Flow Spike Results (task 01b) — COMPLETE
Full evidence in [`spike-results-flow.md`](spike-results-flow.md). **14 spike tests pass.**

> ## Verdict: **ADOPT quarkus-flow for the #216 review gate.**
>
> Every decision criterion is met **empirically**: the gate passes at Quarkus 3.40.1 /
> quarkus-langchain4j 1.14.1 with **no downgrade**, A1–A3 and B6 all work, and C8 has a real answer
> (`PersistenceInstanceReader` is CDI-injectable). The 12 CI-shaped tests pass under **both** Ollama
> profiles.

- **Setup:** worktree `…-spike-flow`, branch `spike/flow-hitl` (local, unpushed) from `main` @ `8ed467d`;
  commits `c3ab343`, `7306867`, `d953680`. Flow **1.1.3** (**no 1.2.0 final exists**; latest prerelease
  `1.2.0.CR3`). Worktree removed; branch kept. `main` and `src/main` untouched.
- **Gate: PASSED, complete.** A `@SequenceAgent` is now actually present, so
  `FlowLangChain4jProcessor` really ran its agentic-translation path and generated
  `GeneratedSpikeIntakeAgentFlow`. The skew is harmless: the BOM import wins, and the coupling is one
  API-identical build item (`DetectedAiAgentBuildItem`) plus `AgenticSystemTopology`.
- **Answers to the load-bearing questions:**
  - **A1 YES** — `function(agent::method, …)` → `emitJson` → `listen(toOne(consumed(…).dataAs(…)))` →
    `switchWhenOrElse` compose in one `.tasks(...)` list. Both switch branches need
    `.then(FlowDirectiveEnum.END)` (the enum lives in `io.serverlessworkflow.api.types`).
  - **A2 YES** — the default fallback broker serves `listen`; no messaging module needed.
  - **A3 YES** — in-process `publish(...)` wakes it, and the completed intake task is **not** re-run
    (invocation counter and WireMock request count both unchanged).
  - **A4 — better than hoped.** The agentic subflow is **its own persisted workflow instance,
    checkpointed per sub-agent** (`do/0/triage-0` on disk with `next_position=do/1/summarize-1` while the
    second leaf is still in flight). Both halves of the contradictory docs are true. Measured by stalling
    the second leaf agent, because sampling after the subflow completes proves nothing — Flow does not
    retain completed instances.
  - **B6 YES** — a review suspended in one JVM is auto-restored, woken and completed in a **separate
    JVM**, with **zero** LLM calls in the new JVM.
  - **C8/C9/C10 YES** — `@Inject PersistenceInstanceReader` (`JpaInstanceReader` is `@ApplicationScoped`)
    → `find(def, id).status() == WAITING`; `WorkflowInstance.cancel()` works, so the `toAny` workaround is
    unnecessary; `dataAs(Class, predicate)` correlates on the claim id, asserted negatively too.
  - **E19 YES** — completed and canceled instances leave **0** rows in all three tables.
- **Three corrections recorded during the spike:**
  1. **OTel is a separate extension**, `io.quarkiverse.flow:quarkus-flow-opentelemetry`. An initial
     measurement without it wrongly concluded Flow emits no spans. `quarkus-flow`'s own `tracing.html` is
     a *different*, log/MDC-based feature — easy to conflate.
  2. *"Never re-executes completed tasks"* is still not in the docs, but is now **measured** (A3).
  3. The docs document no query/cancel API, yet both exist and are CDI-reachable.
- **Observability — good tree, two real gaps.** With `quarkus-flow-opentelemetry`, one trace holds
  `workflow.execute spike-review` with every `task.execute <name>` parented under it, typed by
  `flow.task.type` (`call_function`/`emit`/`listen`/`switch`). That replaces task 01's synthetic CONSUMER
  span. **But** (a) the agentic subflow starts its own trace, and (b) the
  `langchain4j.aiservices.*` spans remain orphan roots — three disconnected trace islands per run — and
  (c) Flow spans carry `flow.*`, never `gen_ai.*`, so Langfuse renders them as untyped SPAN ancestors.
  **D11 is safe**: the `langchain4j.aiservices.<SimpleClassName>.<method>` naming and the
  `langfuse.dataset.name` stamping both survive (pinned by assertion, since this degrades silently).
  **D15: the in-JVM resume is *not* a linked span** — same trace, zero links.
  **The islands are an open issue to be addressed, with options on record** —
  [`spike-results-flow.md`](spike-results-flow.md) → *Open issue: the three trace islands*. They are a
  **legibility** problem, not a correctness one: tiers 1–3 and the dataset-name invariant are all
  unaffected, so price it as demo polish rather than a blocker.
- **D16 free metrics:** `quarkus.flow.workflow.started.total` / `.completed.total` / `.duration`, tagged
  `workflow` + `workflowVersion`, for parent and subflow. No per-task meter and **no waiting gauge**, so
  "how many reviews are waiting" still needs our own metric.
- **E17 Dev UI is a genuine demo asset** (screenshots in the worktree's `spike-evidence/`): a Workflows
  card that renders the HITL pipeline automatically as a typed, branching diagram —
  `runIntake (CALL)` → `emitReviewRequired (EMIT)` → `waitHumanReview (LISTEN)` →
  `routeDecision (SWITCH)` → `approveClaim` / `rejectClaim`. Caveat: editing a `Flow` bean in dev mode
  throws `IncompatibleClassChangeError … _ClientProxy overrides final method Flow.definition()`; a clean
  restart fixes it, but it is a sharp edge for live demos.
- **Costs accepted:** Preview status on `quarkus-flow-langchain4j` and `quarkus-flow-opentelemetry`;
  `gen_ai.*` stamping stays ours (an `ai.scoring` `SpanProcessor`, with `AiServiceDatasetSpanProcessor`
  as precedent) and **the trace joining is a separate, undecided problem** — see
  [`spike-results-flow.md`](spike-results-flow.md) → *Open issue: the three trace islands*; `application_id` is
  `quarkus.application.name` and is part of the PK of all three tables, so it must be pinned explicitly or
  a rename orphans every suspended instance.
- **A gotcha for every future mocking profile:** an agent with no `@ModelName` uses the *unnamed default*
  model, and under `-Pollama` that provider is ambiguous — augmentation fails with *"multiple available
  providers … (ollama,openai)"*. Add `quarkus.langchain4j.chat-model.provider=openai`. This extends the
  existing "a mocking profile must pin the provider" rule to the unnamed model.
- **Open, not blocking:** (1) does auto-restore reattach a half-finished **subflow** to its parent's
  pending task, or re-run it? (worst case = the merged design's behavior); (2) is restore a linked span?;
  (3) `fork` OTel/MDC propagation if a parallel branch appears; (4) concurrency — every test ran one
  instance at a time, and Flow explicitly leaves singleton behavior to the application; (5) re-check for
  1.2.0 final.
- **Durable state across restarts is not a requirement (user decision, 2026-10-07).** Every profile wipes
  the schema on boot, and that's intended: a restart also reseeds the claims and empties GreenMail, so a
  surviving review would point at data that no longer exists. No Flyway, no schema strategy, no separate
  issue. `quarkus-flow-jpa` stays (it backs the `404`/`409` check). Design doc step 8 changes to "kept in
  PostgreSQL while the app runs", and task 14's restart test is dropped.

#### Follow-up spike: the whole intake as one workflow (option b1) — COMPLETE
Full evidence in [`spike-results-flow.md`](spike-results-flow.md) → *Follow-up spike*. Three upstream bugs are filed
(quarkus-flow #1056, #1057, #1058), each with a runnable reproducer in
[edeandrea/quarkus-flow-reproducers](https://github.com/edeandrea/quarkus-flow-reproducers). Branch
`spike/flow-workflow` (local, unpushed), commit `a7a2a62`. 18 tests pass under both Ollama profiles.

- **User decision: option (b1).** The **whole intake** is one Flow workflow (match → supersede → agents →
  route → persist → reply → file → wait for review → decision). The agentic root stays **one task** inside
  it, so the agent topology in the design is unchanged. Rejected: (a) Flow only around the review, and
  (b2) the agents redrawn as Flow tasks (which drops the agentic module's composition).
- **F1 YES:** the design's sequence → conditional → parallel nesting runs as one Flow task. Each composite
  becomes its own generated workflow; the parallel one is a real `fork`. Routing is right on both branches,
  and there are zero LLM calls on resume.
- **F5 — design change: no `@ParallelExecutor`.** Under Flow it fails **every** run with
  `UnsupportedOperationException: Changing the default WorkflowApplication executor is not supported`.
  Filed upstream as [quarkiverse/quarkus-flow#1057](https://github.com/quarkiverse/quarkus-flow/issues/1057).
  Flow runs fork branches on its own executor.
- **F2 YES:** a CDI `WorkflowExecutionListener` (`onWorkflowStatusChanged` → `WAITING`/`COMPLETED`/
  `CANCELLED`/`FAULTED`) tells the one-at-a-time watcher when to move on. **F2b:** `WAITING` is set just
  before the `listen` registers its consumer, so a decision published at that exact moment can be lost.
  That's irrelevant for a human reviewer, but tests re-publish until the run leaves `WAITING`.
- **F3 YES:** the reply's run cancels the waiting run from its first task
  (`definition().activeInstance(id).map(WorkflowInstance::cancel)`), leaving no rows; the decision then
  reaches only the live run. `activeInstance` is in-JVM only, which is fine given the restart decision.
  A bug in `cancel()` drops the canceled run's `workflow.execute` span (cosmetic; filed as [quarkiverse/quarkus-flow#1058](https://github.com/quarkiverse/quarkus-flow/issues/1058)). One first-run
  "row still present" failure was not reproduced; task 08's test covers it.
- **F4 — trace islands diagnosed.** Flow never makes its task span current while the task body runs
  (cause a), **and** each generated agent subflow starts its own trace via `supplyAsync` (cause b).
  Re-entering Flow's span (public accessors in `quarkus-flow-opentelemetry`) joins only the first agent
  call. One claim run is 7 traces, or 6 re-entered. **Grouping by `gen_ai.conversation.id` (option 2) is
  the only local fix that covers every island**; how to stamp it across Flow's thread hops is the main
  open question for task 11. Option 5 (upstream) is filed as
  [quarkiverse/quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056) (both causes, reproducer, suggested fixes).

#### Follow-up spike 2: the conversation id on every span — COMPLETE
Full evidence in [`spike-results-flow.md`](spike-results-flow.md) → *Follow-up spike 2*. Branch
`spike/flow-conversation-id` (local, unpushed), commit `66db1e7`; 20 tests pass under both Ollama profiles.
- **Feasible, with no baggage:** every span of a run (3 workflows, 7 tasks, 4 AI calls and their children) carries
  the run's `gen_ai.conversation.id`, and 8 concurrent runs don't leak into each other.
- **Baggage leaked across concurrent runs in the first version, but the cause isn't established.** Stale baggage
  put other runs' ids on spans (and quarkus-langfuse stamps from baggage). That version's own `AgentListener` also
  made baggage current per agent call, so the leak may be self-inflicted rather than Flow's. Context propagation
  (capture on submit, restore and clear after) should make baggage correct; whether Flow's hops allow it is open.
- **The mechanism (task 11):** the id travels as data: in the workflow input and as an argument of the agentic
  root. A Flow listener maps instance id → conversation id (sub-workflows read it from the agentic scope), a CDI
  `AgentListener` covers each agent call, the task-08 step helper covers our own steps, and a `SpanProcessor`
  stamps from parent → instance map → agent call.
- **Still open for task 11:** traces stay split (#1056), and Flow's ~58 persistence spans per run are separate
  trace roots with no id (decide whether to suppress them). Also untested: the session mapping against a live
  Langfuse, and the chat's baggage leaking onto Flow threads.

#### Follow-up spike 3: why baggage leaked — COMPLETE
Full evidence in [`spike-results-flow.md`](spike-results-flow.md) → *Follow-up spike 3*. Branch `spike/flow-baggage`
(local, unpushed), commit `c28e09d`.
- **The leak is a Quarkus bug, not Flow's or the spike's:** [quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354).
  In 3.40.1, the OTel context-propagation provider doesn't restore a pool thread's context after a task, so baggage
  stays on the thread. Reproduced with the `ManagedExecutor` alone, no Flow.
- **Fixed upstream** by [quarkus#56805](https://github.com/quarkusio/quarkus/pull/56805) (4.0.0.Beta1, labeled for a
  3.40 backport, not in 3.40.1). With that one class shadowed: **0** leaked ids, against 81/118 without.
- **Carrier decision input: baggage**, correct once the fix ships. What baggage alone misses is Flow's generated agent
  sub-workflows (the `supplyAsync` hop, quarkus-flow#1056); a `CallableTaskProxyBuilder` covers our own steps with no
  step code.
- **Decided (user, 2026-10-07): baggage is the carrier; spike 2's data approach is dropped; work around quarkus#54354
  until the fix ships**, in the generic core (see Key Decisions → Observability).

#### Follow-up spike 4: the quarkus#54354 workaround — COMPLETE
Evidence in [`spike-results-flow.md`](spike-results-flow.md) → *Follow-up spike 4*. Branch `spike/flow-baggage-guard`
(local, unpushed).
- **One class plus a `META-INF/services` entry:** a second `ThreadContextProvider` (its own context type, so it
  doesn't clash with Quarkus's) resets the pool thread to `Context.root()` when a task ends and the propagated context
  is still current. It works whichever order SmallRye ends the providers in, and leaves alone a thread that runs
  the task inline and has its own context.
- **Measured on 3.40.1, both Ollama profiles, no Quarkus class overridden:** pool tasks submitted with no baggage see
  none (against earlier tasks' ids without it); a second batch of 8 runs carries **0** first-batch ids (against 118);
  8 concurrent runs, 0 traces mixing ids. A thread's own span and baggage survive running a contextual task inline.
- **Delete it when** quarkus#56805 ships (4.0, or the 3.40 backport). Spike 3's `managedExecutorPropagationIsClean`
  is the regression test that shows it's no longer needed.
- **The full suite is unchanged with the guard** (198 run, same 4 environment errors with or without it). But the 96
  tests that need a real OpenAI key were skipped both times, so the chat's baggage paths haven't run with the guard
  yet. The core task runs them with a real key.

#### Rework required by the Flow verdict (task 01b = ADOPT) — APPLIED to the task files (2026-10-07)

**Applied** to tasks 03, 05, 06, 08–11 and 12–14, with task 07 merged into 08 and the new task 10b. The task files
are now the source of truth; where this list differs, the task file wins (it also absorbed the follow-up spikes 2–4,
which this list predates). Still open from it: the **design gate re-run**. History below.

The list below **supersedes** the "Changes required in later tasks" that follows it wherever the two
conflict. That list was written against the merged `@HumanInTheLoop` + `DatabaseAgenticScopeStore` design.

- **Design gate re-run (blocking).** `docs/design/email-claim-intake.md` and
  `docs/design/claim-intake-agents.puml` describe the LangChain4j-native approach and are now wrong about
  the review gate. Update both and put them **back through the review gate** before any implementation
  task starts. The agent topology itself is unaffected — only the suspend/resume/persistence mechanism and
  the observability section change.
- **task-03 (config/mailbox):** **drop the scope-store deliverable entirely** — no
  `DatabaseAgenticScopeStore`, no entity, no `AgenticScopePersister.setStore` registrar, no startup
  round-trip self-test, no `ShutdownEvent` reset, no `allowDeserializationType` allowlist. Replace with the
  four Flow dependencies (`quarkus-flow`, `-langchain4j`, `-jpa`, `-opentelemetry`) plus
  `quarkus-langchain4j-agentic`. Add an explicit `quarkus.application.name` (it becomes Flow's
  `application_id`; nice-to-have). **No schema strategy** (durable state isn't a requirement).
- **task-07 (human review step): rewrite.** No static `@HumanInTheLoop` agent, no `SuspendedResponse`, no
  `DefaultAgenticScope`, no hand-rolled replay. It becomes a `Flow` bean whose `descriptor()` composes
  `function(intakeAgent::process, …)` → `emitJson` → `listen(toOne(consumed(REVIEW_DONE).dataAs(…)))` →
  `switchWhenOrElse`, with `.then(FlowDirectiveEnum.END)` on each terminal branch. **Both internal-API
  touch points disappear**, which was the main argument for this spike.
- **task-08 (intake processor): rewrite as the intake workflow (b1).** `ClaimEmailProcessor`'s ordered
  rules become the steps and branches of one `Flow` (sketch in the follow-up spike results). The
  watcher starts a run and moves on at the handoff (F2). The "evict on every ending" rules go away (Flow
  deletes the rows on completion *and* on `cancel()`). Supersede-a-waiting-review is a first-step
  `cancel()` of the old run (F3). The policy check becomes a branch after the agents task, before any
  persistence. Keep step data small: pass ids, not photo bytes or the raw email. **Task 07 is merged into
  this rewrite (user decision, 2026-10-07):** the review is the last few steps of the same workflow.
- **task-10 (review API and UI):** the `404`/`409` contract is preserved via `@Inject
  PersistenceInstanceReader` → `find(definition, instanceId).status() == WAITING`. **Do not implement the
  409 check as a raw `select status`** — that column is `NULL` for a waiting instance. The decision is
  delivered by publishing a CloudEvent, so the endpoint is now **asynchronous**: it returns once the event
  is published, not once the workflow has finished. Correlate on the claim id (`Claim.reviewRunId` keeps
  the Flow instance id for tracing).
- **task-11 (observability):** much is now free — `workflow.execute` / `task.execute` spans typed by
  `flow.task.type`, and `quarkus.flow.workflow.started.total` / `.completed.total` / `.duration`. Drop the
  synthetic CONSUMER span and the `@AgentListenerSupplier` tree. **Three things remain ours:** (1) an
  `ai.scoring` `SpanProcessor` stamping `gen_ai.operation.name=invoke_agent` onto `workflow.execute` /
  `task.execute` so Langfuse types them AGENT rather than untyped SPAN; (2) a **waiting-reviews gauge**,
  which Flow does not provide; (3) a regression test pinning the
  `langchain4j.aiservices.<SimpleClassName>.<method>` naming, because the dataset-name invariant degrades
  silently; (4) **a decision on the three trace islands** — one intake run emits three disconnected traces
  (parent workflow / agentic subflow / each `langchain4j.aiservices.*` leaf). **This is legibility, not
  correctness: no scoring tier and not the dataset-name invariant is affected.** Note a `SpanProcessor`
  **cannot** do the joining — parent and trace id are immutable after span creation. Ranked options, a
  one-line diagnostic to pick between them, and the severity analysis are in
  [`spike-results-flow.md`](spike-results-flow.md) → *Open issue: the three trace islands*.
- **Tasks 04, 05, 06, 09, 12, 13, 14:** mostly unaffected. Task 05 drops `@ParallelExecutor` (F5). Task 09's
  watcher starts a workflow run per email and waits only for the handoff (F2). Task 13's demo guide gains the
  Flow Dev UI diagram. Task 14 drops the restart test. Tasks 12 and 14 run under both Ollama profiles.

#### Changes required in later tasks
**History: superseded by the Flow rework above (the task files were rewritten on 2026-10-07).** Originally
**applied to tasks 03–14 at the design gate.** Two items were adjusted when applied: the task-03 store
items also absorbed task 07's store work, and the task-10 "handle NEEDS_INFO re-suspension" item was dropped
(gap 2: Needs more information ends the run at `Pending Information`). Task 08 evicts on **every** ending,
not only non-suspended ones (policy rejection and superseding evict a paused review too).
- **task-02 (design):**
  - cite the executive summary
  - the topology diagram shows the root span, the listener spans and the parallel executor
  - the review sequence shows suspend → `Pending Review` → REST `completePendingResponse` → re-invoke with
    arguments from the scope → evict
  - record the `MonitoredAgent` decision
- **task-03 (config/mailbox):**
  - add the `quarkus-langchain4j-agentic` dependency
  - the `claim-intake` named model config
  - the agentic-scope entity/table (`agentId|memoryId` key, `text` JSON, `updatedAt`)
  - the store registrar (`StartupEvent` at the lowest priority, plus a `ShutdownEvent` reset)
  - `allowDeserializationType` for decision/DTO types at startup
  - a startup round-trip self-test of a sample scope
- **task-05 (extraction):**
  - opt-out annotations on every agent
  - DTOs use ISO-string dates, no `Optional`
  - each parallel agent gets the `@ParallelExecutor`
  - add a JSON-extracting output guardrail (parse failures abort the workflow)
  - leaf agents are not reused across roots
- **task-06 (triage):** same opt-outs; route with typed `@ActivationCondition`s; keep conditions cheap, since all
  of them are evaluated.
- **task-07 (human review):**
  - static `@HumanInTheLoop` returning `Object` (`new SuspendedResponse<>("review:" + scope.memoryId())`)
  - no `async`
  - consumers take `Object`
  - the decision record holds no `java.time`/`Optional`
- **task-08 (processor):**
  - root `process(@MemoryId String messageId, …)`, extending `AgenticScopeAccess`, injected into the processor
  - catch `AgenticSystemSuspendedException` → `Pending Review` plus the email
  - side effects happen here, not in agents
  - evict on every non-suspended ending
  - open the CONSUMER root span and store its trace context for the span link
- **task-09 (IMAP watcher):** start after the store registrar (`@Startup`/default priority); no agent calls in
  low-priority startup observers.
- **task-10 (review API/UI):**
  - `POST …/review-decision` → `getAgenticScope` (404 on `null`)
  - check `review:<messageId>` is pending (409)
  - `completePendingResponse` → re-invoke with `readState` arguments
  - evict on completion; handle NEEDS_INFO re-suspension
  - a SERVER span with a link to the intake trace
- **task-11 (observability):**
  - the root `@AgentListenerSupplier` span listener (end open spans on `onAgenticSystemSuspended`)
  - the `@ParallelExecutor` everywhere
  - Langfuse attribute choices
  - a CDI `AgentListener` only for leaf metrics
  - handle the `MonitoredAgent` `ongoingExecutions` leak per the user's decision
- **task-12 (Roundcube E2E):** stub or mock the LLM agents, with WireMock stubs matching on the last message.
- **task-13 (demo/docs):** document the opt-outs, single replica, store lifecycle and eviction.
- **task-14 (verification):**
  - the restart test needs a DB that survives a Quarkus restart (the test Postgres dev service is recreated)
  - sub-agent `@InjectMock` tests use a dedicated test profile
  - a test proves no scope rows leak
