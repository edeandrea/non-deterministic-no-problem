# Task 01: Compose Mail Stack and Mail Config (Spike + Build)

**Type:** Code Modification

## Goal

`compose-devservices.yml` defines GreenMail and Roundcube. Quarkus starts them in dev **and** test
mode, and the app's mailer sends through GreenMail.

## What to Do

- Create `compose-devservices.yml` at the project root:
  - **`greenmail`:** `greenmail/standalone:<latest stable>`, with SMTP 3025, IMAP 3143 and the API on 8080
    exposed on random host ports. Its default `GREENMAIL_OPTS` disable auth and auto-create users;
    confirm and keep that. Add a readiness check (healthcheck or `io.quarkus.devservices.compose.wait_for.logs`).
  - **`roundcube`:** `roundcube/roundcubemail:<latest stable>-apache-nonroot`, which listens on **8000**.
    Set `ROUNDCUBEMAIL_DEFAULT_HOST=greenmail`, `ROUNDCUBEMAIL_DEFAULT_PORT=3143`,
    `ROUNDCUBEMAIL_SMTP_SERVER=greenmail`, `ROUNDCUBEMAIL_SMTP_PORT=3025`, and `depends_on: greenmail`.
    Roundcube must never send anywhere except GreenMail.
- Wire the app's config:
  - Map GreenMail's SMTP port with `io.quarkus.devservices.compose.config_map.port.3025: quarkus.mailer.port`,
    plus whatever is needed for the host and the IMAP port (`3143`) and API port (`8080`) used by the test helper.
  - Set `quarkus.mailer.mock=false` explicitly in `%dev` and `%test`. Mailpit used to do this.
  - Set `quarkus.mailer.host`. Verify what host value works, both for local Docker and for Podman.
- **Spike items:** record every answer in `PLAN.md`.
  1. Do Compose Dev Services start in `%test` with the default project-per-test-run behaviour? Is
     Roundcube acceptable to start for every test run (cost/time), or does it need a Compose profile? If
     a profile is needed, find how to enable it for the Roundcube E2E test class only
     (`quarkus.compose.devservices.profiles` from a `@TestProfile`?).
  2. Does Roundcube send through GreenMail when GreenMail has auth disabled? Roundcube sends SMTP AUTH
     with the user's credentials by default. If GreenMail rejects it, find the Roundcube setting that
     disables SMTP auth (e.g. `smtp_user`/`smtp_pass` = '' via a mounted config).
  3. Does the Roundcube nonroot image run under a random UID (as OpenShift's restricted SCC does)?
     Test with `--user` set to an arbitrary UID locally.
  4. GreenMail standalone REST API: confirm the endpoints for listing users/messages and **purging**
     mail between tests (the `/api/...` paths) for the pinned version.
  5. Do the GitHub Actions `ubuntu-latest` runners have Compose V2 available to Quarkus? Look it up in
     the runner image docs; don't assume.
- Remove the `quarkus-mailpit` dependency (keep `quarkus-mailpit-testing` until task 02 has rewritten the tests).

## Files/Areas

- `compose-devservices.yml` (new)
- `src/main/resources/application.yml`
- `pom.xml` (remove `quarkus-mailpit`)

## Key Points

- Use the latest stable image tags (snapshot from planning: `greenmail/standalone:2.1.14`,
  `roundcube/roundcubemail:1.7.4-apache-nonroot`). Re-verify, and pin exact tags (no `latest`).
- In `%dev`, pin Roundcube's host port (e.g. `8000:8000`) so it can be bookmarked. Don't pin ports in `%test`.
- The Compose services must not also be picked up by another extension's Dev Service discovery
  (`io.quarkus.devservices.compose.ignore` exists if needed).

## Done When

- [ ] `./mvnw quarkus:dev` starts GreenMail and Roundcube, Roundcube is reachable in a browser, and you can log in as any user.
- [ ] A status email triggered from the app (`NotificationService`) appears in the recipient's Roundcube inbox (manual check, documented in `PLAN.md`).
- [ ] All five spike answers are recorded in `PLAN.md`, with evidence.
- [ ] `quarkus-mailpit` is gone from `pom.xml`, and `./mvnw -B clean test-compile -Pollama` succeeds.