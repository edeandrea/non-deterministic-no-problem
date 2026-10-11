# Task 03: Documentation, Verification and Merge into the #216 Feature Branch

**Type:** Verification

## Goal

Every document reflects the named identities, an independent review confirms Compose, Kubernetes and docs agree, the
PR is merged into `main`, and `main` is merged into `gh216-email-intake` with the #216 follow-ups applied.

## What to Do

- **Docs:**
  - `README.md` → *Reading the emails*: logging in as a seeded claimant gives their identity their name (the table
    already lists them); any other address gets a bare one; a name cleared in Settings → Identities comes back at the
    next login, a changed one is kept.
  - `CLAUDE.md`: the mail-stack paragraph under Commands and *OpenShift deployment → mail stack* (the plugin, where the
    files live, how they're mounted, and that the map must follow `import.sql`).
  - Check `docs/*.puml` and `docs/design/*.puml` for anything that describes Roundcube's configuration (probably
    nothing to change).
- **Verification:**
  - Re-run task 01's dev-mode checks under the default profile too (not only `-Pollama`).
  - Run `./mvnw -B clean verify -Pollama` once, to confirm the Compose change doesn't affect test boots (tests never start
    Roundcube).
  - A **fresh** reviewer checks Compose vs Kubernetes parity, the PHP (no hook can break login; only empty names
    written; `login_after` returns its args) and that the docs match.
- **PR** into `main` from `gh237/roundcube-identities`: title without closing keywords, body ending `Closes #237`.
- **After it merges:** merge `main` into `gh216-email-intake` locally, apply *Follow-ups in #216* from `PLAN.md` in the
  merge (keeping the feature branch's version of any conflicting `.tasks/05-…` file), run
  `./mvnw -B clean test-compile -Pollama`, and push.

## Done When

- [ ] README, CLAUDE.md and manifest comments updated
- [ ] Dev-mode checks pass under the default and `-Pollama` profiles; `clean verify -Pollama` is green
- [ ] Fresh review approved, with its fixes recorded in `PLAN.md`
- [ ] PR merged into `main`; #237 closed
- [ ] `main` merged into `gh216-email-intake` with the #216 follow-ups applied, and pushed
