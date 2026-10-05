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

- [ ] [task-01-compose-mail-stack.md](task-01-compose-mail-stack.md): Compose mail stack and mail config (spike + build)
- [ ] [task-02-migrate-mail-tests.md](task-02-migrate-mail-tests.md): Migrate mail tests to GreenMail
- [ ] [task-03-kubernetes-manifests.md](task-03-kubernetes-manifests.md): Kubernetes manifests and deploy script
- [ ] [task-04-docs-and-verification.md](task-04-docs-and-verification.md): Documentation and verification

---

## Shared Context

### Overview
Mailpit has no IMAP, so a browser webmail client can't use it as a mailbox. GreenMail provides SMTP and
IMAP, and Roundcube gives customers a browser inbox. Issue 5 then reads `claims@parasol.com` over IMAP.
Even before that, the app's existing status emails land in customers' Roundcube inboxes.

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
- One Compose definition for dev **and** tests. Host ports are pinned only in `%dev`.
- SMTP and IMAP are not exposed outside the cluster; only Roundcube gets a Route.
- Latest stable image tags, pinned exactly.

### Caveats & Problems
- Without the Mailpit extension, the mailer is mocked in dev/test unless `quarkus.mailer.mock=false` is set explicitly.
- GreenMail keeps mail in memory: a restart loses it.
- The Roundcube nonroot image's behaviour under OpenShift's restricted SCC is unverified (task 01 spike).
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.