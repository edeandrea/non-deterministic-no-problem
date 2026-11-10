# Task 08: A Two-JVM End-to-End Test

**Type:** Test

## Goal

A test with two real app processes on one database, mailbox and Lease proves what the single-JVM tests can only
simulate: a decision POSTed to the follower is applied by the leader, and when the leader dies the follower takes
over its reviews and its Processing emails.

## What to Do

Build the harness task 01 chose (Option A: the failsafe `@QuarkusTest` is pod A and spawns the packaged jar as pod B;
or Option B). Both pods run `KUBERNETES_LEASE` mode against one fabric8 mock API server (or k3s, if task 01 said so)
started by the test, with a short `lease-duration`. Inject the exit hook so pod A doesn't actually exit the test JVM:
make the test's pod A the one that **stays** alive, and kill pod B (the spawned process) for the failover cases.

Scenarios, in one class (`MultiReplicaIntakeE2ETests`, `org.parasol.intake`, failsafe):
1. **Decision on the follower.** Pod B leads (start it first, or hand it the Lease). An email creates a claim on B; its
   run waits on B. The review decision is POSTed to **pod A**'s REST endpoint, `202`; the claim goes `In Process` and
   the reply arrives in GreenMail.
2. **Leader killed with a review waiting.** Pod B leads with a claim in `Pending Review`, and is killed (`destroyForcibly`,
   so no clean release). Within about `lease-duration`, pod A leads, reclaims the review, and a decision POSTed to A
   completes the claim.
3. **Leader killed with a decision in flight.** As 2, but the decision is POSTed (to A, as a follower) just before B is
   killed, so the command is pending for a dead run. After failover the claim completes with **that** decision, no
   second POST.
4. **Leader killed mid-email.** B is killed after `applyOutcome` and before the reply (a WireMock delay on the model,
   or a GreenMail SMTP delay through Toxiproxy, which the suite already has). A recovers the email from Processing:
   exactly one claim, exactly one reply.
5. **Only the leader watches.** With both pods up, ten emails from two senders: each is processed exactly once, per
   sender in order, and every run is on the leader (from the spans' or the logs' pod identity).

## Files/Areas

- `src/test/java/org/parasol/intake/MultiReplicaIntakeE2ETests.java` (new), a new `@TestProfile` (`MultiReplicaTestProfile`,
  `org.parasol.testing`), the pod-B launcher helper (`org.parasol.testing.cluster`, new)
- `pom.xml`: test-scope `kubernetes-server-mock` (if task 04 didn't already add it); failsafe includes
- `.github/workflows/simple-build-test.yml`: only if the test needs something CI lacks. It must pass on both legs with
  stub keys (`CLAUDE.md` → *CI*).
- `CLAUDE.md` → *Testing*: the 4th boot, how the harness works, how to run it alone.

## Key Points

- **This is the one new `@TestProfile`:** a 4th application boot in `verify`. Record the measured cost in Shared
  Context.
- Pod B needs only `claim-intake` stubbed, on the same WireMock as pod A (`ChatStubs`, marker-based, as
  `RoundcubeClaimIntakeE2ETests` does).
- Kill pod B in `@AfterEach` whatever happens, and clean up as `IntakeScenario.reset()` does, so a failed scenario
  doesn't leak a process or a Lease into the next.
- If pod B can't boot in CI within a reasonable time, present that before weakening any scenario.

## Done When

- [ ] All five scenarios pass locally under `-Pollama` and `-Pollama-openai`.
- [ ] No pod-B process survives the test run (`ps` after `verify`).
- [ ] The extra boot and pod B's startup time are measured and recorded here.
