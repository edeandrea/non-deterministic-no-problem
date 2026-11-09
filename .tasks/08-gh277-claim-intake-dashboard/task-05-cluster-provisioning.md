# Task 05: Provision Both Dashboards on OpenShift

**Type:** Deployment

## Goal

`deploy-to-openshift.sh` leaves the cluster's Grafana showing both dashboards, from the same JSON files dev uses, and
prints the Grafana URL.

## What to Do

### 1. `deploy-to-openshift.sh`

Next to the `roundcube-identities` ConfigMap (lines 84–88), before `oc apply -f src/main/kubernetes/dependencies.yml`
(line 89):

```bash
# The app's Grafana dashboards: the same files dev mode provisions from META-INF/grafana
oc create configmap grafana-dashboards \
  --from-file=claim-intake.json=src/main/resources/META-INF/grafana/grafana-dashboard-claim-intake.json \
  --from-file=ai.json=src/main/resources/META-INF/grafana/grafana-dashboard-ai.json \
  --dry-run=client -o yaml | oc apply -f -
```

At the end, after the Roundcube line, print the Grafana Route (`grafana`, `dependencies.yml:202`):

```bash
echo "Grafana dashboards (Dashboards → Parasol Claim Intake / Parasol AI): https://$(oc get route grafana -o jsonpath='{.spec.host}')"
```

### 2. `src/main/kubernetes/dependencies.yml`

- An inline ConfigMap `grafana-dashboard-provisioning` with one key, `custom.yaml`:

  ```yaml
  apiVersion: 1
  providers:
    - name: parasol
      type: file
      disableDeletion: true
      options:
        path: /otel-lgtm/grafana/conf/provisioning/dashboards/custom
        foldersFromFilesStructure: false
  ```

- On the `grafana-lgtm` Deployment (volumeMounts ~185, volumes ~194):
  - the provider: `subPath: custom.yaml` at `/otel-lgtm/grafana/conf/provisioning/dashboards/custom.yaml`;
  - the dashboards: the `grafana-dashboards` ConfigMap as a **directory** at
    `/otel-lgtm/grafana/conf/provisioning/dashboards/custom` (no `subPath`, so a re-applied ConfigMap reaches the pod).
- A comment, like Roundcube's (`dependencies.yml:280`), that this Deployment needs the `grafana-dashboards` ConfigMap
  `deploy-to-openshift.sh` creates, and won't start without it.

### 3. Local check of the mount layout

Without a cluster, prove the layout with podman (or docker), mounting the files as the pod would:

```bash
podman run --rm -p 3000:3000 \
  -v ./provider/custom.yaml:/otel-lgtm/grafana/conf/provisioning/dashboards/custom.yaml:ro \
  -v ./dashboards:/otel-lgtm/grafana/conf/provisioning/dashboards/custom:ro \
  docker.io/grafana/otel-lgtm:0.38.1
```

Both dashboards appear (`/api/search`), with their uids, and the image's own provisioning still works.
Also run once with `--user 12345:0` to mimic OpenShift's arbitrary UID: ConfigMap mounts are read-only, so nothing
should need writing there.

## Files/Areas

- `deploy-to-openshift.sh`
- `src/main/kubernetes/dependencies.yml`

## Key Points

- The ConfigMap comes from the JSON files themselves: never copy the JSON into `dependencies.yml`.
- Don't touch `GF_PLUGINS_PREINSTALL_AUTO_UPDATE` or the `/var/tempo` mount; they fix other OpenShift problems.
- A ConfigMap is capped at 1 MiB; two dashboards are far below that, but say so in a comment if it ever gets close.
- `strategy: Recreate` stays (the PVC is `ReadWriteOnce`).

## Done When

- [ ] The script creates `grafana-dashboards` before `oc apply`, and prints the Grafana URL.
- [ ] `dependencies.yml` has the provider ConfigMap, both mounts and the comment.
- [ ] The local podman run shows both dashboards, also as an arbitrary UID.
- [ ] `oc apply --dry-run=client -f src/main/kubernetes/dependencies.yml` (or a YAML lint) accepts the file.
