TITLE: Replace Mailpit with GreenMail and Roundcube webmail
## Summary

Mailpit has no IMAP, so it can't be a mailbox for a browser webmail client. Email intake ({{ISSUE_5}})
also needs to read `claims@parasol.com` over IMAP. Replace Mailpit with GreenMail (SMTP + IMAP) and the
Roundcube webmail. The status emails the chat already sends then land in the customer's (e.g. Marty's)
Roundcube inbox right away.

**Depends on** {{ISSUE_1}}.

## Changes

- **`compose-devservices.yml`** (project root) defines GreenMail and Roundcube **once**, for dev mode **and all tests**:
  - **GreenMail** (`greenmail/standalone`): SMTP 3025, IMAP 3143, API 8080. Auth is disabled (any
    password works; mailboxes are created automatically).
  - **Roundcube** (`roundcube/roundcubemail:<version>-apache-nonroot`, listens on **8000**): IMAP and
    SMTP point at GreenMail only.
  - Host ports are pinned in `%dev` only (so Roundcube can be bookmarked), and random in tests.
- **App mailer:**
  - Set `quarkus.mailer.mock=false`, the host and the port explicitly. The Mailpit extension used to do this.
  - Map GreenMail's mapped port into config with Compose `config_map` labels.
- **Dependencies:** remove `quarkus-mailpit` and `quarkus-mailpit-testing`.
- **Tests:**
  - A GreenMail test helper that reads a user's INBOX (IMAP or the GreenMail API), purges mail between
    tests, and waits for a message to arrive.
  - Rewrite `NotificationServiceTests` with the helper, keeping its existing assertions.
  - Add a mailbox-isolation test.
- **Kubernetes** (`src/main/kubernetes/dependencies.yml`):
  - Remove the Mailpit Deployment, Service, Route and PVC.
  - Add GreenMail (ClusterIP only).
  - Add Roundcube (Deployment, Service, edge-TLS Route), with environment identical to the Compose file.
  - ConfigMap: `quarkus.mailer.host: greenmail`, port `3025`.
  - Update the `%openshift` `connects-to` annotation.
  - `deploy-to-openshift.sh` prints the Roundcube URL.

## Decisions

- Mailpit is removed entirely; no UI is kept. All mail goes through GreenMail.
- SMTP and IMAP are never exposed outside the cluster. Only Roundcube gets a Route.
- Image tags are the latest stable, pinned exactly (never `latest`). At planning time:
  `greenmail/standalone:2.1.14`, `roundcube/roundcubemail:1.7.4-apache-nonroot`.

## Spike first

1. **Compose Dev Services in `%test`:** is starting Roundcube on every test run acceptable, or does it need a
   Compose profile enabled only for the Roundcube end-to-end test?
2. **SMTP auth:** does Roundcube's SMTP AUTH work against GreenMail with auth disabled, or must SMTP auth be turned off in Roundcube?
3. **OpenShift:** does the Roundcube nonroot image run under a random UID (OpenShift's restricted SCC)?
4. **Test cleanup:** what are the GreenMail standalone REST API endpoints for listing and purging mail in the pinned version?
5. **CI:** is Compose V2 available to Quarkus on GitHub Actions `ubuntu-latest`?

## Caveats

- GreenMail keeps mail in memory, so restarts lose it. Acceptable for a demo; to be documented.
- `NotificationServiceTests.emailSendsWhenUserExists` depends on real LLM output and fails with stub keys. This is a pre-existing limitation.

## Documentation

- `README.md`: prerequisites, quickstart, the Dev Services list.
- `CLAUDE.md`: the email flow, the profiles table, and a new section on the Compose mail stack (Roundcube URL, any-password login, in-memory mail).
- `docs/application-flow.puml` and `docs/continuous-scoring-architecture.puml` both show Mailpit. Update them and re-render.
- Check whether `images/arch.png` shows Mailpit. It's a raster with no source, so report it rather than pixel-editing.

## Tasks

- [ ] Compose mail stack and mailer config (spike + build)
- [ ] Migrate the mail tests to GreenMail
- [ ] Kubernetes manifests and deploy script (client-side validated)
- [ ] **Documentation and verification:** an independent review, then a dev-mode and cluster check by the maintainer

## Acceptance criteria

- No `mailpit` references remain, except in `.idea/` and the historical `conversation-export.md`.
- In dev mode, a chat-triggered status-update email is visible in Marty's Roundcube inbox.
- `dependencies.yml` passes client-side validation. The live cluster check is done by the maintainer.