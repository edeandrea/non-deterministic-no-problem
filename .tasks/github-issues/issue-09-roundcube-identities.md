TITLE: Give the seeded claimants' Roundcube identities their names
## Summary

Roundcube ({{ISSUE_4}}) creates each user's identity with an empty name, so mail a seeded claimant sends from
Roundcube goes out as a bare `marty.mcfly@email.com` instead of `Marty McFly <marty.mcfly@email.com>`. The six
claimants in `import.sql` should have their names on their identities, in dev mode and on OpenShift.

It also matters for the email intake ({{ISSUE_5}}): when an email doesn't resolve to a claim (for example, a new
claim), the intake's reply greets the sender by their `From` display name, and today falls back to "Customer".

**Depends on** {{ISSUE_4}} (done).

## Changes

- **A small Roundcube plugin** (`parasol_identities`) and a config file holding the address → name map for the six
  seeded claimants, both **mounted** into the pinned upstream image (no custom image):
  - **`user_create` hook:** sets the name when Roundcube creates a new user, so the first identity has it.
  - **`login_after` hook:** on every login, fills in the name of any of the user's mapped identities whose name is
    **empty**. Users that already existed get it too, and a name someone set in Settings → Identities is kept.
  - Addresses match case-insensitively (`JAnderson@mail.com`); an unmapped address is left alone, and a missing or
    empty map is a no-op.
- **`compose-devservices.yml`:** mount both files into the `roundcube` service and enable the plugin with
  `ROUNDCUBEMAIL_PLUGINS` (keeping the image's default `archive,zipdownload`).
- **`src/main/kubernetes/dependencies.yml`:** the same files in a ConfigMap, mounted on the Roundcube Deployment with
  the same environment, keeping parity with the Compose file.
- **Docs:** README (*Reading the emails*), `CLAUDE.md` (the mail stack and OpenShift deployment sections) and the
  manifest comments.

## Decisions

- Only empty names are filled; a name set by hand is never overwritten.
- All six seeded claimants, not only Marty.
- No custom Roundcube image: mounts keep the pinned tag and Dependabot updates.
- Display names only: GreenMail auth stays disabled, and nothing changes for the app's own mail or for tests
  (Roundcube never runs in tests).

## Spike first

1. Do Quarkus Compose Dev Services honour relative bind mounts (relative to the compose file) under Podman, and can
   the `apache-nonroot` image's user read them? If not, does Compose `configs:` with inline `content:` work instead?
2. Is the OpenShift ConfigMap kept by hand next to the PHP sources, or generated from them by `deploy-to-openshift.sh`?

## Acceptance criteria

- In dev mode, logging in to Roundcube as `marty.mcfly@email.com` and sending a mail delivers it with
  `From: Marty McFly <marty.mcfly@email.com>`; the same works for `JAnderson@mail.com` (mixed case).
- An unmapped address logs in and sends as before, with no errors in Roundcube's log.
- A cleared name comes back at the next login; a changed name is kept.
- `dependencies.yml` passes client-side validation. The live cluster check is done by the maintainer.
