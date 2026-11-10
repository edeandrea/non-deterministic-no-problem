# Task 04: Elect One Intake Leader With a Kubernetes Lease

**Type:** Feature (leader election)

## Goal

Exactly one application instance, the **intake leader**, runs the IMAP watcher, the dispatcher (and so every `ClaimIntakeFlow` run) and
the mailbox measurements. The other application instances serve the UI, REST and chat bot, and relay decisions (task 03). Off-cluster
(dev mode, the test suite) the application instance is always the leader, so nothing about today's behavior changes there. Fixes
**mechanisms 3, 4 and 7**, and **2** by co-location.

## What to Do

### 1. `org.parasol.intake.leadership` (new package)

- `IntakeLeadership`: `isLeader()`, plus CDI events `LeadershipAcquired` and `LeadershipLost` (past tense, per
  `CLAUDE.md` → *Conventions*). Two implementations, chosen by `parasol.intake.leadership.mode`:
  - `SINGLE` (the default): leader from startup. Fires `LeadershipAcquired` on `StartupEvent`, and only if
    `parasol.intake.enabled`.
  - `KUBERNETES_LEASE`: fabric8 `LeaderElector` on a `LeaseLock` (`parasol.intake.leadership.lease-name`, default
    `parasol-intake-leader`; the namespace and identity come from the client and the host name). Runs on its own thread
    (a virtual thread is fine; it blocks). `onStartLeading` fires `LeadershipAcquired`. `onStopLeading` fires
    `LeadershipLost`, then calls `Quarkus.asyncExit(1)` (fencing; PLAN.md → *Decisions made in planning*).
    `releaseOnCancel(true)`, so a clean shutdown hands over at once (about 1 s in task 01; a crash takes about one
    `lease-duration`).
  - **fabric8 calls `onStopLeading` when the elector is canceled too,** not only when the Lease is lost (task 01, Q3).
    The exit must not run for a stop we caused (shutdown): set a flag before canceling, and skip the exit when it's set.
- `IntakeConfig.leadership`: `mode`, `lease-name`, `lease-duration` (`15s`), `renew-deadline` (`10s`), `retry-period`
  (`2s`).

### 2. Who listens

- `ClaimsInboxWatcher`: starts on `LeadershipAcquired` instead of `StartupEvent`, stops on `LeadershipLost` and on
  shutdown. Its `recoverOnce` (the Processing hand-over) now runs once per *leadership*, not once per JVM. Its Javadoc
  stops saying "the intake runs as a single replica".
- `InboxDepthMonitor`: measures only while leading (it already does nothing while the intake is off).
- `IntakeMetrics`: the DB-count gauges (`reviews.pending`, `review.oldest.pending`, `signals.pending`) report only on
  the leader. Decide: `NaN` or not registered on followers. Add `claim.intake.leader` (1/0).
- `ClaimIntakeDispatcher.submit` and `ReviewRecoverer`: assert, cheaply, that they run on the leader (a WARN, and the
  email takes the failure path). That catches a regression, not a normal case.
- `SupersedeStep.cancelWaitingRun`: an empty `activeInstance` logs at WARN, not DEBUG.

### 3. Shutdown order

On `ShutdownEvent` with the Lease, stop the watcher first, then let the runs that are working finish within the
graceful-shutdown window (`quarkus.shutdown.timeout`: decide a value, or leave it), then release the Lease. Runs still
waiting for review are left alone: the next leader reclaims them (task 05).

### 4. Dependency

`io.quarkus:quarkus-kubernetes-client`. In `SINGLE` mode nothing may touch the Kubernetes API: no client call at boot,
no Dev Service. Check `quarkus.kubernetes-client.devservices.enabled` and turn it off if the extension would start a
mock or k3s in dev/test.

### 5. Tests

- Plain JUnit (no Quarkus boot) with `io.fabric8:kubernetes-server-mock` in CRUD mode, or k3s if task 01 says so: two
  `KubernetesLeaseLeadership` instances on one lock. Exactly one leads; a clean stop hands over; a missed renewal hands
  over within `lease-duration`; `LeadershipLost` fires before the exit hook (inject the exit as a `Runnable`, so the
  test JVM survives); a clean shutdown releases the Lease **without** running the exit hook. Task 01 verified the mock
  (fabric8 7.8.0, CRUD mode) rejects a stale `resourceVersion` with `409`, so it's a faithful lock; no k3s.
- In the bare `%test` group (`SINGLE`): the watcher starts only after `LeadershipAcquired`; `InboxDepthMonitor` doesn't
  measure on a follower (drive `isLeader` with a test double).

## Files/Areas

- `pom.xml`
- `src/main/java/org/parasol/intake/leadership/` (new), `IntakeConfig`
- `ClaimsInboxWatcher`, `InboxDepthMonitor`, `IntakeMetrics`, `ClaimIntakeDispatcher`, `ReviewRecoverer`, `SupersedeStep`
- `InboxWatcherControl` (test): it starts the watcher directly. Check it still works, or route it through leadership.
- `CLAUDE.md` (package table, config table, the metrics list), `README.md` config table

## Key Points

- **Don't put `@WithKubernetesTestServer` on any existing test class:** it's a test resource and splits that group's
  reload key (`CLAUDE.md` → *Testing*). Leadership is tested without Quarkus.
- `org.parasol.intake.leadership` depends on nothing in `intake` except `config` and `observability`. The watcher and
  the monitors depend on it, not the other way round.
- `quarkus-kubernetes-client` must add no `Unrecognized configuration key` and no Dev Service to dev mode or tests.

## Done When

- [ ] In `SINGLE` mode, every existing intake test passes unchanged: `./mvnw test -Dtest='Claims*Tests,ClaimIntake*Tests,Intake*Tests'` under `-Pollama`.
- [ ] The leadership unit tests pass, including the missed-renewal handover.
- [ ] No `ClaimsInboxWatcher` or `InboxDepthMonitor` code path runs on a follower.
- [ ] A dev-mode boot makes no Kubernetes API call and starts no extra container.
