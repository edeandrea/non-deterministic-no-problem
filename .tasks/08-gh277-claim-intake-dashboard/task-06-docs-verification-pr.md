# Task 06: Documentation, Verification and the PR

**Type:** Documentation and Verification

## Goal

Every document that said "no custom dashboard" is correct, `CLAUDE.md` records how metrics reach Grafana and why,
both CI legs are green, and the PR is open.

## What to Do

### 1. `CLAUDE.md`

- **Observability** (around lines 830–843): replace "The app ships no custom Grafana dashboard" with the two
  dashboards (files, uids, what each covers, the cross-links), and record:
  - metrics reach Prometheus by OTLP push only; the panels use the OTLP-translated names;
  - the dev scrape is off (`force-scraping: false`) and why (finding 2), `/q/metrics` still answers;
  - the dev image is pinned to the cluster's (change both together);
  - the intake's timers and summaries have explicit buckets, and `GrafanaDashboardTests` fails on an intake meter with
    no panel;
  - Flow's bundled dashboard is empty in both places, on purpose.
- **OpenShift deployment** (from line ~729): the `grafana-dashboards` ConfigMap, the provider ConfigMap, the two mounts.
- The note around lines 750–751 that dev runs otel-lgtm 0.24.0: now the same version as the cluster.
- **Testing:** `GrafanaDashboardTests` and the shared `IntakeMeterSchema`.

### 2. Other documents

- `README.md` (lines 265–268): replace the "no custom Grafana dashboard … #277" sentence with where to find the two
  dashboards, in dev and on the cluster.
- `docs/email-claim-intake-demo.md` (near the Langfuse section, ~line 225): a short "in Grafana" pointer for the demo.
- `.tasks/claim-intake-roadmap.md`: the #277 paragraph links this plan (done when the plan was written; check it's
  still right).

### 3. Verify

- Dev: every panel on both dashboards has data after the traffic in task 02.
- `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai` (CI's two legs), with no
  `Unrecognized configuration key`.

### 4. The PR

From `gh277/claim-intake-dashboard` into `main`. Title without closing keywords, e.g. *Add Grafana dashboards for the
email intake and the AI services*. The body summarizes the decisions, includes task 04's screenshots, lists the manual
cluster check, and ends `Closes #277`.

## Files/Areas

- `CLAUDE.md`, `README.md`, `docs/email-claim-intake-demo.md`, `.tasks/claim-intake-roadmap.md`

## Key Points

- Re-grep line numbers: earlier tasks shifted them.
- Commits carry the `Co-authored-by: Copilot` trailer.

## Done When

- [ ] `CLAUDE.md`, `README.md` and the demo guide describe the dashboards; nothing still says there are none.
- [ ] Both CI legs pass locally.
- [ ] The PR is open, body ending `Closes #277`.
