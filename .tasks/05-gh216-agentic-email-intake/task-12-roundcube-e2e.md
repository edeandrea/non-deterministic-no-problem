# Task 12: Roundcube End-to-End Test

**Type:** Verification

## Goal

A Playwright test drives the full demo through the real Roundcube UI and the Parasol review panel, with
the LLM mocked, and runs on every CI build.

## What to Do

- Create a Playwright test class (extending the existing `PlaywrightTests` base) with intake enabled,
  the Compose Roundcube running (using the issue 4 spike's mechanism), and the LLM stubbed with WireMock.
- **Scenario 1, complete claim:**
  1. Log in to Roundcube as `marty.mcfly@email.com` (any password).
  2. Compose an email to `claims@parasol.com` with an incident description and a photo attachment, and send it.
  3. Wait until a new claim appears in the Parasol claims list. Open it, and check it shows `Pending Review`, plus the summary, sentiment and the attached photo.
  4. Back in Roundcube, check the "received — final review" email arrived in Marty's inbox with `[CLM…]` in the subject.
  5. In the Parasol UI, click **Ready for processing**. Check the claim shows `In Process`, and the thank-you (claim in process) email arrives in Roundcube.
- **Scenario 2, missing information:**
  1. Send an email without a location.
  2. Check the claim shows `Pending Information`, and the reply lists the location as missing.
  3. Reply in Roundcube with the location.
  4. Check the claim becomes `Pending Review`, and the "received — final review" email arrives.
  5. Click **Ready for processing**. Check the claim becomes `In Process`, and the thank-you email arrives.
- **Scenario 3, reviewer requests more information:**
  1. Send a complete claim, and wait for `Pending Review`.
  2. Click **Request more information**, tick only the category, and submit.
  3. Check the claim shows `Pending Information`, and the missing-information email lists only the category.
  4. Reply in Roundcube with the category. Check the claim becomes `Pending Review`.
  5. Click **Ready for processing**. Check the claim becomes `In Process`.
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

- [ ] All three scenarios pass locally under `-Pollama`.
- [ ] Every WireMock stub matches on the last message only.
- [ ] The test runs as part of `./mvnw verify` (not excluded or tagged off).
- [ ] The test leaves no claims, images, mail or Flow rows behind.
