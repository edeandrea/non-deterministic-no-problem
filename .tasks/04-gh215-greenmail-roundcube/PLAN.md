# Issue 4: Replace Mailpit with GreenMail and Roundcube — Task Execution Plan

## Your Mission

Replace Mailpit with GreenMail (SMTP + IMAP) and the Roundcube webmail. The pair is defined once in
`compose-devservices.yml`, used by dev mode and **all** tests, and mirrored in the Kubernetes
manifests. Fourth of five issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/04-gh215-greenmail-roundcube/PLAN.md`
**Tasks Directory:** `.tasks/04-gh215-greenmail-roundcube/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/04-gh215-greenmail-roundcube/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md`.
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [x] [task-01-compose-mail-stack.md](task-01-compose-mail-stack.md): Compose mail stack and mail config (spike + build)
- [x] [task-02-migrate-mail-tests.md](task-02-migrate-mail-tests.md): Migrate mail tests to GreenMail
- [x] [task-03-kubernetes-manifests.md](task-03-kubernetes-manifests.md): Kubernetes manifests and deploy script
- [x] [task-04-docs-and-verification.md](task-04-docs-and-verification.md): Documentation and verification

---

## Shared Context

### Overview
Mailpit has no IMAP, so a browser webmail client can't use it as a mailbox. GreenMail provides SMTP and
IMAP, and Roundcube gives customers a browser inbox. Issue 5 then reads `claims@parasol.com` over IMAP.
Even before that, the app's existing status emails land in customers' Roundcube inboxes.

### Task Outcomes
- **Task 01:** `compose-devservices.yml` runs GreenMail 2.1.14 and Roundcube 1.7.4-apache-nonroot (behind the `webmail` Compose profile), and `quarkus-mailpit` is gone from `pom.xml`. Dev mode under the default, `-Pollama` and `-Pollama-openai` profiles starts both containers, with Roundcube on :8000, and the mailer sends for real to GreenMail. Tests start GreenMail only (`ClaimTests -Pollama`: 14/14, no roundcube container).
- **Task 02:** `quarkus-mailpit-testing` is gone; mail tests read GreenMail through the `org.parasol.testing.mail.GreenMailMailbox` bean (IMAP via test-scoped `angus-mail` for reading, REST API for purge and user listing). Under `-Pollama`, `NotificationServiceTests` is 7 passed / 1 skipped (the LLM-dependent `emailSendsWhenUserExists`, as designed), and the new `GreenMailMailboxTests` (isolation + purge, no LLM) is 2/2.
- **Task 03:** `dependencies.yml` swaps Mailpit (Deployment, Service, Route, PVC) for a GreenMail Deployment + ClusterIP Service (smtp/imap/api, readiness on `/api/service/readiness`) and a Roundcube Deployment + Service + edge-TLS Route, with images and env identical to Compose (checked mechanically). The ConfigMap points the mailer at `greenmail:3025`, `%openshift`'s `connects-to` lists `greenmail`, and `deploy-to-openshift.sh` prints the Roundcube URL. Validated offline with kubeconform `-strict` (16/16), since `oc --dry-run=client` needs a reachable cluster. The live cluster check is the user's.
- **Task 04:** README (Compose prerequisite, Roundcube URL, in-memory mail, port 8000, the no-Compose error), CLAUDE.md and both diagrams (re-rendered: application-flow 3425×1924 → 3483×1924, architecture 4460×2384 → 4392×2399) now describe GreenMail/Roundcube; `images/arch.png` shows no mail component, so it's unchanged. No `mailpit` left outside the excluded folders. Two fresh `pi --no-session` reviews (read-only) approved with no BLOCKER/MAJOR; their fixes are listed under Key Decisions. Mid-task, `main` gained #229 (guardrail chain fix, `qwen3:4b` for `generate-email` under `%ollama`), merged cleanly into the uncommitted work. `emailSendsWhenUserExists` now runs under `-Pollama`, so the mail suites are **10/10 with nothing skipped**: a real LLM email through all four guardrails, sent over SMTP and read back from GreenMail over IMAP.

### Spike Answers (task 01)
1. **Compose in `%test`:** yes. `ClaimTests -Pollama` starts GreenMail through Compose Dev Services and passes 14/14. `%test` blanks `quarkus.compose.devservices.profiles`, so Roundcube stays out; a blank value does unset the list, so the `none` fallback isn't needed. Enabling Roundcube per test class is left to whichever E2E test needs it.
2. **Roundcube SMTP via auth-less GreenMail:** works without overrides. Logged in as `jane@example.com`, sent to `claims@parasol.com`, and the message appeared in that inbox through the GreenMail API. A scripted login (`_token` from the login page, then `POST ?_task=login`) returns 302 to the Inbox.
3. **Random UID (OpenShift restricted SCC):** runs as `1000680000:0`, but only with `ROUNDCUBEMAIL_DB_DIR=/tmp/roundcube-db`, because `/var/roundcube` is owned by www-data. The `tar`/`chown` "Operation not permitted" warnings are harmless. HTTP 200, and login and send both worked.
4. **GreenMail 2.1.14 API:** `GET /api/user`, `GET /api/user/{email}/messages/INBOX` (404 JSON `User '…' not found` until the first mail arrives), `POST /api/mail/purge`, `POST /api/service/reset`, `GET /api/service/readiness`.
5. **CI:** `ubuntu-latest` is Ubuntu 24.04 with Docker 28.0.4 and Compose v2.38.2. Quarkus calls `<runtime> compose` (`ComposeDevServicesProcessor.getComposeExecutable()`).

### Project Context
- `NotificationService` sends status emails with `ReactiveMailer` (moved onto `ForkJoinPool` with a 15s timeout to avoid a deadlock).
- `NotificationServiceTests` is the only mail test today. It uses `@WithMailbox`, `@InjectMailbox` and `Mailbox` from `quarkus-mailpit-testing`.
- `pom.xml` has `quarkus-mailpit` and `quarkus-mailpit-testing`.
- `src/main/kubernetes/dependencies.yml` runs `axllent/mailpit` with a PVC, a Service and a UI Route; the app's ConfigMap points `quarkus.mailer.host` at `mailpit:1025`.
- `application.yml` `%dev` sets `quarkus.mailer.mock: false`. `%openshift` has a `connects-to` annotation listing `mailpit`.

### Key Decisions
- Mailpit is removed entirely, with no UI kept.
- All mail goes through GreenMail. Roundcube sends SMTP and reads IMAP from GreenMail only.
- GreenMail runs with auth disabled (any password; mailboxes auto-created). That's acceptable for the demo.
- One Compose definition for dev **and** tests. GreenMail uses random host ports, mapped into config by `io.quarkus.devservices.compose.config_map.port.*` labels (`quarkus.mailer.port`, `parasol.mail.imap-port`, `parasol.mail.greenmail-api-port`). Only Roundcube pins a host port (8000), and it never runs in tests.
- **Mail stack config is root-level, not `%dev`** (task 01). The Ollama Maven profiles run `quarkus:dev` as `<ai>,prod`, so `%dev` never applies there. `quarkus.compose.devservices.profiles: webmail` and `quarkus.mailer.mock: false` / `host: localhost` therefore sit at root level, and `%test` blanks the Compose profile. Compose Dev Services never run in prod, and `mock: false` / `host: localhost` match the prod defaults, so packaged behaviour is unchanged. Task 03 overrides the host for Kubernetes. `pom.xml` profiles are untouched.
- SMTP and IMAP are not exposed outside the cluster; only Roundcube gets a Route.
- Latest stable image tags, pinned exactly.
- **Test mail is read over IMAP, not the GreenMail REST API** (task 02). The API's `GET /api/user/{email}/messages/INBOX` returns only `uid`, `Message-ID`, `subject`, `contentType` and the raw `mimeMessage`, so decoding a body needs Jakarta Mail anyway, and IMAP is what Roundcube and #216 use. The API is kept for `POST /api/mail/purge` and `GET /api/user` (IMAP sees one mailbox at a time). `angus-mail` is a **test**-scoped dependency (BOM-managed, 2.0.5); #216 will likely need it at compile scope.
- **Tests inject config with `@ConfigProperty`, not `@ConfigMapping`** (user preference, task 02). `GreenMailMailbox` takes `quarkus.mailer.host`, `parasol.mail.imap-port` and `parasol.mail.greenmail-api-port` as constructor parameters, so there's no test-side mapping and no prefix to clash with a future main-code mapping in #216.
- Mail tests purge in `@BeforeEach` (not `@AfterEach`), and "no email sent" means `allMessages()` across every GreenMail user is empty.

### Caveats & Problems
- Without the Mailpit extension, the mailer is mocked in dev/test unless `quarkus.mailer.mock=false` is set explicitly.
- GreenMail keeps mail in memory: a restart loses it.
- The Roundcube nonroot image runs under an arbitrary UID only with `ROUNDCUBEMAIL_DB_DIR` pointed at a writable path (`/tmp/roundcube-db`). Task 03's Deployment must set it too, or mount a writable volume there.
- `quarkus.mailer.host: localhost` is verified on Podman only. Docker isn't available locally, so CI (`ubuntu-latest`, Docker) is the confirming signal.
- *(Resolved in task 04 by #229: `NotificationServiceTests.emailSendsWhenUserExists` now passes under `-Pollama`, end to end through GreenMail.)* The app's own status email couldn't be shown end to end locally. With no real OpenAI key, only Ollama could drive the chat, and `granite4:micro` always hits the existing guardrail-chain problem (`Retry or reprompt is not allowed after a rewritten output`, see `CLAUDE.md` Testing). Under both AI profiles the Dev UI config shows `quarkus.mailer.mock=false` and `quarkus.mailer.port` equal to GreenMail's mapped 3025 port. Task 02's GreenMail-backed `NotificationServiceTests` is the real end-to-end check.
- Under `-Pollama`, a cached `easy-rag-embeddings.json` from the default profile (1536-dim) breaks chat against `snowflake-arctic-embed` (1024-dim). Delete it, or run with `-Dquarkus.langchain4j.easy-rag.reuse-embeddings.enabled=false` (already documented in `CLAUDE.md`).
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.
- With GreenMail auth disabled, an IMAP login as an unknown address succeeds and shows an empty INBOX, and logging in creates the user (it then appears in `GET /api/user`). `POST /api/mail/purge` deletes messages, not users (verified against 2.1.14).
- **`oc apply --dry-run=client` isn't usable offline:** it still does API discovery, and the local kube context points at a cluster that no longer exists. Task 03 validated with `ghcr.io/yannh/kubeconform` (via podman), `-strict`, using the default Kubernetes schemas plus `melmorabity/openshift-json-schemas` `v4.14-standalone-strict` for `Route` (the datree CRDs catalog has no Route schema).
- **Both images run under an arbitrary UID** (checked with `--user 1000680000:0`). GreenMail needs nothing extra; idle it uses about 210 MB, and with no `-Xmx` the JVM sizes its heap from the container limit (512Mi limit, 256Mi request). Roundcube idles at about 50 MB (256Mi limit, 64Mi request). No `JAVA_OPTS` is set on GreenMail, to keep env parity with Compose.
- **Fixed in task 03 (user-approved):** the app's `connects-to` listed `lgtm`, which is only the Service; the Deployment is `grafana-lgtm`, so the topology view drew no arrow for it. It now lists `grafana-lgtm`. `connects-to` names Deployments, not Services.
- **Roundcube is kept out of every test run by surefire/failsafe** (task 04, from the fresh review): `quarkus.compose.devservices.profiles=none` in both plugins' base `systemPropertyVariables` (merged into the Ollama profiles; checked in the effective POM). `%test` alone missed tests whose config profile lacks `test` (`DriftTestProfile` → `drift`); reproduced with `-Dquarkus.test.profile=ollama`, where Roundcube started. Not put in `%drift`, because `%drift` can also drive a dev-mode demo that wants Roundcube. `%test`'s blank value stays for IDE runs and continuous testing.
- **Review fixes (task 04):** `GreenMailMailbox` checks the purge message ("Purged mails"; GreenMail answers 200 even on failure) and HTTP status, throwing `MailboxAccessException` rather than an assertion error, so a broken GreenMail reads as an error, not a test failure. It also sets 5s IMAP connection/read timeouts, sets `basePath("/")` on API calls (RestAssured's globals point at the app, including `quarkus.http.root-path`), and throws `MailboxAccessException` for `null` content. Compose quotes the numeric env values. Not changed: the reviewer's future-proofing of the surefire property (the merge is verified; a Maven property wouldn't prevent an override either), the rejected `zsh -e` claim, and the untracked `CODE_STANDARDS.md` (out of scope).
- **GreenMail persistence was considered and rejected** (user decision). GreenMail 2.1.14 has only an `InMemoryStore` (checked in the jar); `greenmail.preload.dir` only loads `.eml` files at startup and never writes back. Mail is lost on a pod restart, which is acceptable because claim data lives in PostgreSQL. Backup-and-preload schemes were rejected because restored `claims@` mail could be ingested twice by #216.
- Port 8081 (the default test port) was taken locally by another JVM during task 02, so the runs used `-Dquarkus.http.test-port=0`. Not a project problem.

### Manual Checks (for the user)
- **Dev mode:** `./mvnw quarkus:dev` (or `-Pollama`), open http://localhost:8000, and log in as `marty.mcfly@email.com` with any password. In the app's chat, ask to update claim 1's status. The status email should appear in Marty's Roundcube inbox. Port 8000 must be free.
- **Cluster:** run `deploy-to-openshift.sh` and open the Roundcube URL it prints. Repeat the same check. Also confirm the topology view links the app to `grafana-lgtm` and `greenmail`, and Roundcube to `greenmail`.
