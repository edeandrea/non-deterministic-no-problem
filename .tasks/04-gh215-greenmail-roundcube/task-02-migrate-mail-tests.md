# Task 02: Migrate Mail Tests to GreenMail

**Type:** Code Modification

## Goal

Every mail-related test reads mail from GreenMail, and `quarkus-mailpit-testing` is removed.

## What to Do

- Write a small test helper (e.g. `GreenMailMailbox`) in `src/test/java`:
  - **read** a user's INBOX over IMAP with `jakarta.mail` / Angus Mail (test scope, version from the
    Quarkus BOM), or through the GreenMail REST API if task 01 found it sufficient
  - **purge** mail between tests (GreenMail API)
  - **wait** with Awaitility until a message for a recipient arrives
  - return messages as a record (from, to, subject, text body)
- Rewrite `NotificationServiceTests` to use the helper. Keep its existing assertions:
  - the recipient
  - the body starts with `EMAIL_STARTING`, ends with `EMAIL_ENDING`, and contains the client name, claim number and status
  - the database status update
  - no email for the "not found" and "invalid status" cases
- Add a test that a message sent to one user doesn't appear in another user's inbox (mailbox isolation).
- Remove `quarkus-mailpit-testing` from `pom.xml`.

## Files/Areas

- `src/test/java/org/parasol/notification/service/NotificationServiceTests.java`
- `src/test/java/org/parasol/testing/GreenMailMailbox.java` (new; choose a fitting package)
- `pom.xml`

## Key Points

- `NotificationServiceTests.emailSendsWhenUserExists` depends on real LLM output (`GenerateEmailService`)
  and fails with stub keys ("Retry or reprompt is not allowed after a rewritten output"). That's
  pre-existing and secret-dependent; don't "fix" it. The no-email cases must pass with stub keys.
- Use AssertJ chains and records, as `AGENTS.md` requires.

## Done When

- [ ] No test imports `io.quarkiverse.mailpit`, and `quarkus-mailpit-testing` is gone from `pom.xml`.
- [ ] `NotificationServiceTests` reads GreenMail. Its no-email tests and the new isolation test pass under `-Pollama`.
- [ ] `./mvnw -B clean test-compile -Pollama` succeeds.