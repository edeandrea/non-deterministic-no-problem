# Issue 4a: Roundcube Display Names for the Seeded Claimants — Task Execution Plan

## Your Mission

Give the six seeded claimants' Roundcube identities their names, so mail they send from Roundcube goes out as
`Marty McFly <marty.mcfly@email.com>` rather than a bare address. A small custom Roundcube plugin and an address →
name map are mounted into the stock image, in dev mode (`compose-devservices.yml`) and on OpenShift
(`dependencies.yml`). A follow-up to #215 (issue 4); it lands before #216's task 08 (see *Branch and PR*).

**GitHub issue:** [#237](https://github.com/edeandrea/non-deterministic-no-problem/issues/237) (sub-issue of #217,
blocked by #215, blocks #216). Body in `.tasks/github-issues/issue-09-roundcube-identities.md`.
**Plan File:** `.tasks/04a-gh237-roundcube-identities/PLAN.md`
**Tasks Directory:** `.tasks/04a-gh237-roundcube-identities/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/04a-gh237-roundcube-identities/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow `CLAUDE.md` and `CODE_STANDARDS.md`.
- No Java changes are expected. If any slip in, make sure it compiles: `./mvnw -B clean test-compile -Pollama` and
  `./mvnw -B clean test-compile`.
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

- [x] [task-01-plugin-and-compose.md](task-01-plugin-and-compose.md): The `parasol_identities` plugin and the Compose mount (spike + build)
- [x] [task-02-kubernetes-manifests.md](task-02-kubernetes-manifests.md): OpenShift ConfigMap and Roundcube Deployment
- [ ] [task-03-docs-and-verification.md](task-03-docs-and-verification.md): Documentation, verification, PR, and the merge into the #216 feature branch

> **Branch and PR (user decision, 2026-10-11):** all three tasks on one branch, `gh237/roundcube-identities`, off
> **`main`**, and one PR **into `main`** ending `Closes #237` in its body. After it merges, merge `main` into
> `gh216-email-intake` and push (no PR needed), **before #216's task 08 starts**. That merge needs the #216 plan
> updates listed under *Follow-ups in #216*.

---

## Shared Context

### Overview
Roundcube creates a user and their first identity on first login, with an empty name. GreenMail auth is disabled, so
anyone can log in as any address; that's unchanged. This only gives the seeded claimants (`import.sql`) a display name.

### How it works (verified against roundcubemail `master`, 2026-10-11)
- `rcube_user::create` (`program/lib/Roundcube/rcube_user.php`) fires the **`user_create`** hook with `user_email` and
  a mutable `user_name`; the new identity's `name` is `user_name` unless it equals the email.
- `public_html/index.php` fires **`login_after`** on every successful login (the hook controls the post-login redirect,
  so a handler must return its `$args`). Roundcube's bundled `new_user_identity` plugin updates identities from it —
  precedent for doing the same. `rcube_user` has `list_identities()` and `update_identity($iid, $data)`.
- The image's entrypoint (`roundcubemail-docker`, `apache/docker-entrypoint.sh`; `apache-nonroot` is built from it)
  `include`s every `/var/roundcube/config/*.php` into its generated config, and merges `ROUNDCUBEMAIL_PLUGINS`
  (default `archive,zipdownload`) into `$config['plugins']`. Plugins live in `/var/www/html/plugins/<name>/<name>.php`.

### Spike Answers (task 01, 2026-10-11)
1. **Relative bind mounts:** work with `podman compose` (paths resolve against the compose file). Not tested through Quarkus's
   own Compose Dev Services launch: dev mode itself wasn't started; the stack was run with `podman compose -p idtest -f
   compose-devservices.yml --profile webmail up -d`, which is what Quarkus runs.
2. **The plugin can't be mounted at `/var/www/html/plugins/parasol_identities`.** The runtime creates `plugins/` root-owned,
   so the entrypoint (as `www-data`) can't copy Roundcube's own plugins into it, and every plugin fails to load (HTTP 500
   after login). The image has `/entrypoint-tasks/post-setup/` (every executable in it runs at the end of the entrypoint),
   so the plugin is mounted at `/usr/local/share/parasol/plugins/parasol_identities` and a post-setup script symlinks it in.
   This adds a third file (`entrypoint-tasks/post-setup/10-parasol-identities-plugin.sh`) to the plan.
3. **Harmless startup noise:** one `Failed to load plugin file … parasol_identities.php` line, from the entrypoint's DB
   init, which runs before the post-setup link. The built-in plugins load fine and login works.
4. **Verified in a real Roundcube 1.7.4 container (scripted login, SQLite and GreenMail):** Marty, `JAnderson@mail.com`
   (identity stored lower-case, name `John T. Anderson`) and Jane get their names; an unmapped address gets none and logs in
   without error; a cleared name is refilled at the next login; a changed name (`Martin S. McFly`) is kept; and a mail
   sent as Marty arrived in `claims@parasol.com` as `From: Marty McFly <marty.mcfly@email.com>`.
5. **Podman note for agents:** `podman compose` needs the user's normal `TMPDIR` (it finds the machine's API socket there);
   the session's long `TMPDIR` makes it fail with "unix socket path is too long".

### Task 02 decisions (2026-10-11)
- **ConfigMap is generated, not inline (user decision):** `deploy-to-openshift.sh` creates `roundcube-identities` from the
  three files in `src/main/docker/roundcube/` before applying `dependencies.yml`, so there's one copy. `dependencies.yml`
  therefore isn't complete on its own (its comment says so). The script gets mode `0755` through a second volume with `items`.
- **Validated:** kubeconform `-strict` 16/16, and the image run as UID `1000680000:0` with the same mounts links the plugin
  and serves HTTP 200.

### Task Outcomes
- **Task 01:** the `parasol_identities` plugin, its name map and the post-setup link script are in `src/main/docker/roundcube/`, mounted by `compose-devservices.yml`. Checked in a real Roundcube 1.7.4 container and through Quarkus dev mode (`-Pollama`): named identities for mapped addresses (mixed case included), none for an unmapped one, a cleared name refilled at the next login, a changed name kept, and `From: Marty McFly <marty.mcfly@email.com>` on a mail sent from Roundcube.
- **Task 02:** `dependencies.yml` mounts the `roundcube-identities` ConfigMap (script with mode `0755`) and sets `ROUNDCUBEMAIL_PLUGINS`; `deploy-to-openshift.sh` creates the ConfigMap from the same files. kubeconform `-strict` 16/16; arbitrary-UID run OK. The live cluster check is the user's (see Manual Checks).
- **Task 03 (in progress):** README, CLAUDE.md and comments updated. `./mvnw -B clean verify -Pollama` with CI's flags: 169 tests, 0 failures, 1 skipped (the drift test). A fresh `code-review` found no issues. The default-profile dev-mode run wasn't repeated (the mail stack is profile-independent); the PR and the merge into `gh216-email-intake` follow.

### Key Decisions
- **Both hooks, fill only empty names** (user decision, 2026-10-11): `user_create` names new users; `login_after`
  fills an empty name on any mapped identity at every login, so users that existed before the plugin get it too. A
  name set by hand in Settings → Identities is never overwritten.
- **All six seeded claimants** (user decision, 2026-10-11), not only Marty.
- **Its own issue and task folder** (user decision, 2026-10-11): it changes only the #215 mail stack, not the intake.
- **No custom image:** mounts keep the pinned `roundcube/roundcubemail:1.7.4-apache-nonroot` and Dependabot updates.
- **Generic plugin, data in config:** the plugin has no addresses in it; the map is `$config['parasol_identities']`.

### Caveats & Problems
- Roundcube never runs in tests or CI (surefire/failsafe set `quarkus.compose.devservices.profiles=none`), so this
  can't have an automated test here. #216's task 12 (Roundcube E2E) will assert the named `From`.
- The map duplicates the claimant names in `import.sql`; a comment on both sides must say so.

### Follow-ups in #216 (apply on `gh216-email-intake` when merging `main` into it)
`main`'s copy of `.tasks/05-gh216-agentic-email-intake/` is older than the feature branch's, so these edits are made
on the feature branch, in the merge (keep the feature branch's version of any conflicting `.tasks/05-…` file):
- **`PLAN.md`:** note that #237 landed on `main` and was merged in (Caveats or the Mission's status paragraph). Also
  catch up the plan for #236, which nobody has recorded yet: mark task 06b done (squash-merged as `9c2d0b7`), and make
  the Mission's "Next" say task 08.
- **Task 12:** scenario 1 also asserts the email `claims@parasol.com` receives has
  `From: Marty McFly <marty.mcfly@email.com>`; where a reply resolves no claim, it greets "Marty McFly", not "Customer"
  (`CustomerName.forReply` falls back to the `From` display name).
- **Task 13:** the demo guide says to log in as a seeded claimant (not an arbitrary address) to get a named sender.

### Manual Checks (for the user)
- **Cluster:** run `deploy-to-openshift.sh`, open the Roundcube URL it prints, log in as `marty.mcfly@email.com`, check
  Settings → Identities shows "Marty McFly", and send a mail to any address: the `From` carries the name.
