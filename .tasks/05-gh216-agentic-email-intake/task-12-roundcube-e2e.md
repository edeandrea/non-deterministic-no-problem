# Task 12: Roundcube End-to-End Test

**Type:** Verification

**Issue:** [#261](https://github.com/edeandrea/non-deterministic-no-problem/issues/261)

## Goal

A Playwright test drives the full demo through the real Roundcube UI and the Parasol review panel, with
the LLM mocked, and runs on every CI build.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b. Nothing here depends on it directly. Check `PLAN.md` → Execution Steps →
2a first.

## What to Do

- Create a Playwright test class (extending the existing `PlaywrightTests` base) with intake enabled,
  the Compose Roundcube running, and the LLM stubbed with WireMock. *(As built, 2026-10-30, user decisions: no copy of the
  Roundcube config and no new test profile. `UiTestProfile` turns on the Compose `webmail` profile with a random host port,
  and the test starts the application's own watcher. See Outcome.)*
- **Scenario 1, complete claim:**
  1. Log in to Roundcube as `marty.mcfly@email.com` (any password). Since #237 the seeded claimants' identities carry their
     names, so the email `claims@parasol.com` receives is `From: Marty McFly <marty.mcfly@email.com>`: assert it, and that a
     reply that resolves no claim greets "Marty McFly" rather than "Customer" (`CustomerName.forReply` falls back to the
     `From` display name).
  2. Compose an email to `claims@parasol.com` with an incident description and a photo attachment, and send it.
  3. Wait until a new claim appears in the Parasol claims list. Open it, and check it shows `Pending Review`, plus the summary, sentiment and the attached photo.
  4. Back in Roundcube, check the "received — final review" email arrived in Marty's inbox with `[CLM…]` in the subject.
  5. In the Parasol UI, click **Submit claim for processing** (the task first called it "Ready for processing"). Check the claim shows `In Process`, and the thank-you (claim in process) email arrives in Roundcube.
- **Scenario 2, missing information:**
  1. Send an email without a location.
  2. Check the claim shows `Pending Information`, and the reply lists the location as missing.
  3. Reply in Roundcube with the location.
  4. Check the claim becomes `Pending Review`, and the "received — final review" email arrives.
  5. Click **Submit claim for processing**. Check the claim becomes `In Process`, and the thank-you email arrives.
- **Scenario 3, reviewer requests more information:**
  1. Send a complete claim, and wait for `Pending Review`.
  2. Check only **Ask customer for category**, then click **Request more information** (the panel's primary button changes to it once an item is asked).
  3. Check the claim shows `Pending Information`, and the missing-information email lists only the category.
  4. Reply in Roundcube with the category. Check the claim becomes `Pending Review`.
  5. Click **Submit claim for processing**. Check the claim becomes `In Process`.
- Clean up the created claims and images, and purge GreenMail, after each scenario. Also assert that
  no Flow workflow instance or task rows remain for the scenario's runs.

## Files/Areas

- `src/test/java/org/parasol/ui/RoundcubeClaimIntakeE2ETests.java` (new)
- Test resources or inline content for the sample photo

## Key Points

- Use stable Roundcube selectors (form field names, ARIA roles), found by inspecting Roundcube 1.7.x. Record them in `PLAN.md`.
- The WireMock stubs must return deterministic agent outputs for the classifier, extraction and summary calls,
  A review decision makes no LLM call (the waiting run's agents already ran), so there are no resume stubs.
  - The review endpoint is asynchronous (202, task 10): after clicking a decision, wait for the status to change
    rather than asserting it right away.
  - **Match each stub on the last message only** (e.g. a JSONPath on `$.messages[-1].content`), never on the
    whole request body. The agents are stateless (spike Q6, Q19), but matching on the last message keeps the stubs
    correct even if a prompt gains a system message or history, and avoids leftover content matching the wrong stub.
  - Distinguish the reply in scenarios 2 and 3 from the first email by content in that last message (the
    combined correspondence marks the newest reply).
- This test must pass in CI under both `-Pollama` and `-Pollama-openai`, with only `OPENAI_API_KEY=change-me`.
- The E2E doesn't assert telemetry (spans, metrics, logs); task 11 covers that.

## Done When

- [x] All three scenarios pass locally under `-Pollama` (and `-Pollama-openai`).
- [x] Every WireMock stub matches on the last message only (`ChatStubs.answerWhenAll`).
- [x] The test runs as part of `./mvnw verify` (not excluded or tagged off).
- [x] The test leaves no claims, images, mail or Flow rows behind (`@AfterEach` asserts no Flow rows, then cleans up).

## Outcome (2026-10-30)

Built on `gh216/12-roundcube-e2e`: `RoundcubeClaimIntakeE2ETests` (three scenarios, about 15 s each), the page helper
`org.parasol.testing.mail.Roundcube`, the test-only `org.parasol.intake.mailbox.InboxWatcherControl` and
`IntakeScenario.awaitNewClaimOf`. Selectors are in `PLAN.md` → task-12 notes.

- **Roundcube comes from compose-devservices.yml itself** (user decision: no duplicated config). A Testcontainers
  `ComposeContainer` was rejected: it runs its own Compose project, and `depends_on` would start a second GreenMail the app
  doesn't read. `UiTestProfile` sets `quarkus.compose.devservices.profiles=webmail`, which **wins over** the surefire/failsafe
  system property `quarkus.compose.devservices.profiles=none` (verified), so `pom.xml` and `DriftTestProfile` are unchanged.
- **Random port:** the Compose port is `'${ROUNDCUBE_PORT}:8000'`. `${ROUNDCUBE_PORT:-8000}` doesn't work: Quarkus parses
  the file itself and its port parser splits on `:` and reads `-` as a range (`Error parsing PortBinding`). The value comes
  from `quarkus.compose.devservices.env-variables.ROUNDCUBE_PORT`: `8000` at root level in `application.yml` (dev mode
  unchanged), `0` in `UiTestProfile`; the `config_map.port.8000` label maps it into `parasol.mail.roundcube-port`.
  `dependencies.yml` (OpenShift) needs nothing: no host port there, and its own probe.
- **The watcher** is started per test by `InboxWatcherControl` (user decision: no new profile), after `IntakeScenario.reset()`,
  and stopped after it. The other UI tests never see it.
- **Cost:** every Playwright boot now starts Roundcube too (a few seconds per boot, five boots until quarkusio/quarkus#56443).
- **Findings:** Roundcube shows the reply's HTML part, so missing items are `li`s; the browser context (and Roundcube's
  session) outlives a test, so the helper logs out first; Marty has seeded claim 1, hence `awaitNewClaimOf`.
