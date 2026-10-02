# Task 03: Kubernetes Manifests and Deploy Script

**Type:** Code Modification

## Goal

The cluster deployment runs GreenMail and Roundcube instead of Mailpit. Roundcube is exposed through a
Route; GreenMail stays internal.

## What to Do

- In `src/main/kubernetes/dependencies.yml`:
  - **Remove:** the Mailpit Deployment, Service, Route and `mailpit-data-pvc`.
  - **Add GreenMail:** a Deployment (same image and tag as the Compose file) and a ClusterIP Service
    exposing SMTP 3025, IMAP 3143 and the API on 8080. No Route.
  - **Add Roundcube:** a Deployment (same image and tag as Compose; nonroot, port 8000) with the same
    environment as Compose, pointing at the `greenmail` Service. Add a Service and an edge-TLS Route.
  - **Update the ConfigMap** `parasol-app-config`: `quarkus.mailer.host: greenmail`, `quarkus.mailer.port: "3025"`, and keep `quarkus.mailer.mock: "false"`.
- In `src/main/resources/application.yml`, `%openshift`: change the `app.openshift.io/connects-to`
  annotation from `mailpit` to `greenmail` and add `roundcube` if appropriate.
- In `deploy-to-openshift.sh`: after deployment, print the Roundcube Route URL.
- Validate with `oc apply --dry-run=client -f src/main/kubernetes/dependencies.yml` if `oc` is
  available; otherwise use `kubectl` client dry-run or a YAML lint. Record which was used.

## Files/Areas

- `src/main/kubernetes/dependencies.yml`
- `src/main/resources/application.yml` (`%openshift`)
- `deploy-to-openshift.sh`

## Key Points

- Keep the Compose and Kubernetes environment variables identical. A reviewer will diff them.
- GreenMail keeps mail in memory, so a pod restart loses mail. That's acceptable for a demo; note it in the docs.
- If task 01 found Roundcube can't run under a random UID, document the required SCC/securityContext
  rather than granting `anyuid` silently.
- An agent can't deploy to the cluster. Hand the live check to the user.

## Done When

- [ ] `dependencies.yml` has no Mailpit resources, and does have the GreenMail and Roundcube resources described.
- [ ] Client-side validation of `dependencies.yml` passes.
- [ ] The `connects-to` annotation and the deploy script are updated.