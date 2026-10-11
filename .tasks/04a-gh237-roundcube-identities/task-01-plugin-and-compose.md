# Task 01: The `parasol_identities` Plugin and the Compose Mount

**Type:** Spike + build

## Goal

In dev mode, logging in to Roundcube as a seeded claimant gives their identity their name, and mail they send goes
out as `From: Marty McFly <marty.mcfly@email.com>`.

## What to Do

- **Spike first** (record the answers in `PLAN.md`):
  1. Do Quarkus Compose Dev Services honour **relative** bind mounts (relative to `compose-devservices.yml`) under
     Podman? Check what Quarkus passes to `podman compose` and that the files appear in the container.
  2. Can the `apache-nonroot` image's user read them (`podman exec … ls -l`, and Roundcube's log)? Note SELinux `:z` for
     Linux hosts, even though macOS Podman doesn't need it.
  3. If relative bind mounts don't work: does Compose `configs:` with inline `content:` work through Quarkus instead?
     Record what was tried.
- **Files**, in one directory (proposed `src/main/docker/roundcube/`; confirm the location fits the repo before
  committing to it), each with a header comment saying what it is:
  - `plugins/parasol_identities/parasol_identities.php`: a `rcube_plugin` whose `init()` registers two hooks. Both look
    addresses up case-insensitively in `$config['parasol_identities']` and do nothing for an unmapped address:
    - **`user_create`:** sets `$args['user_name']`, so a new user's first identity has the name.
    - **`login_after`:** for each of the user's identities (`rcmail::get_instance()->user->list_identities()`) whose
      email is mapped and whose `name` is **empty**, `update_identity($id, ['name' => …])`. Returns `$args` unchanged.
  - `config/parasol-identities.php`: `$config['parasol_identities'] = [ … ];` for the six claimants in `import.sql`
    (`marty.mcfly@email.com` → Marty McFly, `JAnderson@mail.com` → John T. Anderson, `jane_doe@email.com` → Jane Doe,
    `domt@email.com` → Dominic Toretto, `bettercallsaul@email.com` → Saul Goodman, `tylannister@email.com` → Tyrion
    Lannister). Say it must follow `import.sql`, and add the reverse note at the top of `import.sql`.
- **`compose-devservices.yml`:** on `roundcube`, mount the plugin directory read-only to
  `/var/www/html/plugins/parasol_identities` and the config file to `/var/roundcube/config/parasol-identities.php`, and
  set `ROUNDCUBEMAIL_PLUGINS: archive,zipdownload,parasol_identities`. Update the header comment.

## Key Points

- **Never break login:** a missing or empty map is a no-op, and no hook throws.
- **Only empty names are filled;** a name set by hand stays.
- **No custom image.** GreenMail auth, the app's own mail and the tests are untouched.
- `php -l` both files, e.g. `podman run --rm -v "$PWD/src/main/docker/roundcube:/x:ro" docker.io/roundcube/roundcubemail:1.7.4-apache-nonroot php -l /x/…`.

## Done When

- [ ] Spike answers recorded in `PLAN.md`
- [ ] Both PHP files committed and lint clean; the Compose file mounts them and enables the plugin
- [ ] In dev mode (`-Pollama` is enough), log in as `marty.mcfly@email.com`: Settings → Identities shows "Marty McFly";
  a mail sent to `claims@parasol.com` arrives with `From: Marty McFly <marty.mcfly@email.com>` (check in Roundcube as
  `claims@parasol.com`, or through the GreenMail API)
- [ ] `JAnderson@mail.com` (mixed case) gets "John T. Anderson"; an unmapped address logs in and sends with a bare
  `From`
- [ ] `login_after`: clear Marty's name, log out and in, and it's back; change it, log out and in, and it's kept
- [ ] No PHP errors in Roundcube's container log
