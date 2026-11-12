# Task 06: Documentation, Diagrams, Verification and the PR

**Type:** Documentation + Verification

## Goal

Every document describes what was built: a single-replica intake that survives restarts, with the single-replica limitation stated plainly. Both CI legs are green. One PR into `main`.

## What to Do

### 1. Documents

- `CLAUDE.md`:
  - **Remove:** the package-table rows for `org.parasol.intake.leadership` and `org.parasol.intake.workflow.signal`, and `IntakeRunSignal` from `org.parasol.intake.model`; the leadership and signal paragraphs under *The email intake's review endpoints* (*Decisions travel as database signals*); the
    `parasol.intake.leadership.*` and `signals.poll-interval` config rows; the metrics `claim.intake.leader`, `signals.pending`, `signal.delivery` and the "NaN on a follower" wording; the `Quarkus.asyncExit` and Lease text; the OpenShift `kubernetes-client`/RBAC mention if any.
  - **Add:** `parasol.intake.review.publish-interval` / `max-publish-attempts` in the config table; how a decision is delivered (published after commit, republished until `decidingRunId` clears, `409` and the review back to open if never taken, `409` when the run isn't hosted and waiting here).
  - **Rewrite** the gotcha *The intake only works correctly with one replica*: still true, now with the four mechanisms listed in PLAN.md and what lifting them would take (a Lease plus a database outbox, or a broker), and that this branch built and then removed that.
  - **Rewrite** the "database survives restarts" gotcha and the Processing-recovery / reclaim gotchas for a single restarting instance (no leader, no `LeadershipAcquired`; a claimed decision for a gone run is not kept).
  - The migration numbers (`V1`–`V4`), *Database migrations*, and *Testing* (three boots; no new profile).
- `README.md`: keep *Single replica* (restore it; add what survives a restart and what doesn't: GreenMail's in-memory mail on OpenShift, a decision in flight), the config table (`review.*`; no `leadership.*`/`signals.*`), the "recreate a database that applied the old migration numbers once" note, and anything that says the schema is recreated.
- `docs/design/email-claim-intake.md`: the "single replica" paragraph; reclaim and recovery in the workflow description; the decision path.
- `docs/email-claim-intake-demo.md`: the single-replica caveat; a restart now keeps claims and waiting reviews (mail on OpenShift still doesn't survive a GreenMail restart); drop any "scale to two instances" or leader-kill scenario.
- `docs/claim-intake-flow.puml` (the `resumesReply` route; the decision straight to the run, not through a table) and `docs/design/claim-intake-*.puml` where they show the decision path. Re-render with `./docs/render-diagrams.sh`; only the changed diagrams' PNGs should differ.
- `.tasks/claim-intake-roadmap.md`: the #284 paragraph (it still describes a Lease and a command table) now says: restart survival, single replica documented.
- `.tasks/08-gh284-intake-restart-survival/spike-results.md`: a one-paragraph banner at the top saying the Lease and second-JVM findings no longer apply.
- `application.yml`: the `%openshift` `replicas` comment and the `auto-restore` comment read correctly.

### 2. Sweep

`grep -rn -i "leader\|lease\|signal\|multi-replica\|more than one replica" --exclude-dir={.git,target,node_modules}` and `"single replica\|one replica\|replicas: 1"`: every remaining hit outside `.tasks/` must be true now.

### 3. Verification

`./mvnw -B clean verify -Pollama` and `-Pollama-openai` (or the PR's CI, if the user prefers, as for #290), plus a `-Pollama quarkus:dev` smoke boot: no `Unrecognized configuration key`, Roundcube → claim → review decision works,
and a restart keeps the claims and gives a waiting review a new run.

### 4. The PR

One branch (`gh284-multi-replica-intake`), one PR into `main`. Suggested title: *Make the email intake survive restarts* (the branch name keeps the issue's history). The body says plainly that single replica remains the supported topology,
lists what was built and what was built and removed (Lease, signal table), and ends `Closes #284` (the issue was retitled and re-scoped on 2026-11-12). Don't commit or open the PR before the user approves.

## Done When

- [x] Every document above is updated, and the sweep finds no stale leader, Lease, signal or multi-replica claim.
- [x] Changed diagrams are re-rendered; unchanged PNGs are byte-identical.
- [ ] Both CI legs are green, and neither log has `Unrecognized configuration key`. (not done: not run in this session / awaiting your approval)
- [ ] The PR is open (after the user's approval), with `Closes #284` at the end of the body. (not done: not run in this session / awaiting your approval)
