# Task 04: Documentation and Verification

**Type:** Verification

## Goal

Every document reflects GreenMail and Roundcube instead of Mailpit, and an independent review confirms
Compose, Kubernetes, tests and docs agree.

## What to Do

- Search all docs for `Mailpit`/`mailpit` and update every hit:
  - `README.md`: prerequisites, quickstart, Dev Services list. Anything about the cluster must agree with `CLAUDE.md`'s
    "OpenShift deployment" section (written in task 03): GreenMail internal only and in-memory, Roundcube behind a Route,
    any-password login.
  - `CLAUDE.md`:
    - Email flow, profiles table, Gotchas (the `NotificationService` mailer note still applies; check it)
    - a new section on the Compose mail stack: Roundcube URL, any-password login, GreenMail mail kept in memory
    - the Testing section's GreenMail helper (`GreenMailMailbox`, task 02) and the "OpenShift deployment" section (task 03):
      verify them against the code, don't rewrite them
  - `docs/application-flow.puml` and `docs/continuous-scoring-architecture.puml` both show Mailpit.
    Update them and re-render with `./docs/render-diagrams.sh`, then compare PNG dimensions with the previous render.
  - `images/arch.png`: check whether it shows Mailpit. It's a source-less raster. If it does, tell the user rather than pixel-editing.
- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`, and the mail suites (`NotificationServiceTests`, `GreenMailMailboxTests`) under `-Pollama`.
- Have a **fresh** reviewer check:
  - Compose vs Kubernetes environment parity, and re-run the kubeconform validation recorded in `PLAN.md`
    (`oc apply --dry-run=client` needs a reachable cluster)
  - no Mailpit leftovers (pom, yml, tests, docs)
  - the tests actually read GreenMail
  - docs match the code

  Re-review after fixes.
- Give the user manual checks:
  - **dev mode:** log in to Roundcube as `marty.mcfly@email.com`, ask the chat to update claim 1's status, and see the email in Marty's inbox
  - **cluster:** run `deploy-to-openshift.sh`, open the printed Roundcube URL, and repeat the check

## Files/Areas

- `README.md`, `CLAUDE.md`, `docs/`, `images/arch.png` (inspect only)

## Key Points

- Verify every doc statement against the code. Leave `AGENTS.md` and `conversation-export.md` untouched.
- `.idea/shelf/` patches mention Mailpit. They're history, so ignore them. The same goes for local agent notes and
  worktrees in `.claude/`, `.junie/` and `.explyt/`.

## Done When

- [x] A repository search for `mailpit` (case-insensitive) finds nothing outside `.idea/`, `.claude/`, `.junie/`, `.explyt/`, `conversation-export.md` and `.tasks/`.
- [x] The diagrams are re-rendered and show GreenMail/Roundcube.
- [x] The fresh review has no unresolved BLOCKER or MAJOR findings, and `PLAN.md` lists the manual checks.