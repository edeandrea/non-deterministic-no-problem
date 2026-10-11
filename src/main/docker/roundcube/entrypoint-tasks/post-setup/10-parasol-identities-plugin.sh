#!/bin/sh
# Runs at the end of the Roundcube image's entrypoint (the image runs every executable in /entrypoint-tasks/post-setup).
# Links the parasol_identities plugin, mounted at /usr/local/share/parasol/plugins, into Roundcube's plugins directory.
# It can't be mounted there directly: the container runtime would create plugins/ owned by root, and the entrypoint
# (running as www-data) could then no longer copy Roundcube's own plugins into it.
set -e
ln -sfn /usr/local/share/parasol/plugins/parasol_identities /var/www/html/plugins/parasol_identities
