# Task 09: Documentation, Diagrams, Verification and the PR

**Type:** Documentation + Verification

## Goal

Every document describes the multi-replica intake as built. Both CI legs are green. One PR into `main` closes #284.

## What to Do

### 1. Documents

- `CLAUDE.md`:
  - Replace the *intake only works correctly with one replica* gotcha with how it works now: the leader, the command
    table, reclaim, Processing recovery, fencing by exit, and what's still per-pod (the chat bot, the Langfuse
    initializer race).
  - The package table: `org.parasol.intake.leadership`, `…workflow.command`, `IntakeRunCommand`.
  - The `parasol.intake.*` config table: `leadership.*`, `commands.poll-interval`.
  - The metrics list: `claim.intake.leader`, `commands.pending`, `command.delivery`, leader-only gauges, and the reclaim
    metric.
  - *Testing*: four boots, not three.
  - Confirm task 02 removed "No durable state across restarts".
- `README.md`: *Single replica* → *Scaling*; the config table; anything that says the schema is recreated.
- `docs/design/email-claim-intake.md`: the "single replica" paragraph; the command table, reclaim and recovery in the
  workflow description. It's kept in sync with the implementation, not retired (`CLAUDE.md`).
- `docs/email-claim-intake-demo.md`: the single-replica caveat; a restart now keeps claims and waiting reviews (mail on
  OpenShift still doesn't survive a GreenMail restart). Optional: a sixth scenario that scales to two pods and kills the
  leader.
- `docs/claim-intake-flow.puml` (the `resumesReply` route; the decision through the command table) and
  `docs/design/claim-intake-*.puml` where they show the decision path. Re-render with `./docs/render-diagrams.sh`;
  only the changed diagrams' PNGs should differ.
- `.tasks/claim-intake-roadmap.md`: #284 now has a plan here.
- `application.yml`: the `%openshift` `replicas` comment (task 07) and the `auto-restore` comment (task 05) read
  correctly.

### 2. Sweep

`grep -rn -i "single replica\|one replica\|replicas: 1" --exclude-dir={.git,target,node_modules}`: every remaining hit
outside `.tasks/` must be true now.

### 3. Verification

`./mvnw -B clean verify -Pollama` and `-Pollama-openai` (or the PR's CI, if the user prefers, as for #290), plus a
`-Pollama quarkus:dev` smoke boot: no `Unrecognized configuration key`, Roundcube → claim → review decision works, and a
live reload keeps the claims.

### 4. The PR

One branch off `main`, one PR into `main`, title *Make the email intake safe to run with more than one replica*, body
ending `Closes #284`. Say in the body that the OpenShift manifests were checked as generated, not deployed.

## Done When

- [ ] Every document above is updated, and the sweep finds no stale single-replica claim.
- [ ] Changed diagrams are re-rendered; unchanged PNGs are byte-identical.
- [ ] Both CI legs are green, and neither log has `Unrecognized configuration key`.
- [ ] The PR is open with `Closes #284` at the end of the body.
