# Task 03: Remove the Leader Election

**Type:** Removal / Refactoring

## Goal

No Kubernetes Lease, no leader, no `quarkus-kubernetes-client`. The watcher, the review reclaimer and the metrics start and behave as they did for a single instance, with the
restart-recovery behavior of the committed tasks kept. Single replica is the supported topology.

This reverts commits `a6ac331` (leadership) and `e149dd7` (OpenShift wiring) **by hand, not with `git revert`**: later commits (reclaim, recovery) build on the same classes.

## What to Do

### 1. Delete

- `src/main/java/org/parasol/intake/leadership/` (`IntakeLeadership`, `LeadershipAcquired`, `LeadershipLost`, `ConfiguredIntakeLeadership`, `KubernetesLeaseLeadership`).
- `src/test/java/org/parasol/intake/leadership/KubernetesLeaseLeadershipTests.java`.
- `pom.xml`: `quarkus-kubernetes-client` and the test-scoped `io.fabric8:kubernetes-server-mock`.
- `application.yml`: `%openshift` `rbac.*`, `quarkus.kubernetes-client.generate-rbac`, `parasol.intake.leadership.mode`; `IntakeConfig.Leadership`, `IntakeConfig.Mode` and `leadership()`.
  Restore the `%openshift` `replicas: 1` comment to say it's single-replica on purpose (see README → *Single replica*).

### 2. Put the consumers back on `StartupEvent`

- `ClaimsInboxWatcher`: `onStart(@Observes StartupEvent)` replaces `onLeadershipAcquired`; delete `onLeadershipLost`; Javadoc back to "one watcher, single replica"; "the first connection of this JVM",
  "what a previous JVM left in the processing folder".
- `ReviewReclaimer`: `onStart(@Observes StartupEvent)` replaces `onLeadershipAcquired`; the Javadoc says "when the application restarts".
- `InboxDepthMonitor`: drop `IntakeLeadership`; it does nothing only while the intake is off.
- `ClaimIntakeDispatcher`: drop the `IntakeLeadership` field, constructor parameter and the follower WARN in `submit`; "a previous JVM left" in the `recover` Javadoc. Same wording in `ProcessingRecovery` and `EmailReceived`.
- `SupersedeStep`: restore the pre-`a6ac331` `cancelWaitingRun` (a miss logs at DEBUG again).
- `IntakeMetrics`: drop the `IntakeLeadership` constructor parameter, the `claim.intake.leader` gauge, and the leader checks in the three DB-count gauges (they report always).
  `ReviewReclaimed`'s description says "the restarted application", not "a new leader".

### 3. Dashboard and meter schema

- `grafana-dashboard-claim-intake.json`: remove the `claim.intake.leader` panel; rename the row "Leadership and failover" to "Restart recovery" (it keeps `reviews.reclaimed`; the signal panels go in task 04).
- `IntakeMeterSchema`: remove `claim.intake.leader`.

### 4. Tests

- `ClaimsInboxWatcherTests`: back to `watcher.onStart(new StartupEvent())`; drop `watchesWhileLeadingAndStopsWhenLeadershipIsLost` and the `RETURNS_DEEP_STUBS` import it needed.
- `ClaimIntakeDispatcherStartFailureTests`: drop the `() -> true` constructor argument.
- `InboxDepthMonitorTests`, `IntakeMetricsTests`: drop the follower cases and `IntakeLeadership` arguments.

### 5. Docs in this task

Only what would otherwise be false right now: the Javadoc of what you touched. The rest is task 06.

## Done When

- [x] `grep -rn -i "leadership\|Lease\|isLeader\|kubernetes-client" src pom.xml` finds nothing (comments in `.tasks/` aside).
- [x] `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile` pass.
- [x] `ClaimsInboxWatcherTests`, `InboxDepthMonitorTests`, `IntakeMetricsTests`, `ClaimIntakeDispatcherStartFailureTests` and `GrafanaDashboardTests` pass under `-Pollama`.
- [x] No `Unrecognized configuration key` on a boot.
