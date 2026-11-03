# Task 02: Documentation, the Handoff to #277, Verification and the PR

**Type:** Verification

## Goal

Every document says what the app's Grafana story now is, #277 carries the scope it inherited, an independent review
confirms nothing was missed, and the PR is merged into `main`.

## What to Do

- **Docs:**
  - `CLAUDE.md` → *Observability* (around lines 775-779): the app ships **no** custom Grafana dashboard; dev mode gets
    the LGTM dev service's four built-in ones (name them), which cover JVM, HTTP and Agroal; AI observability is
    Langfuse; the intake's `claim.intake.*` meters get a dashboard in #277, which also provisions it on the cluster.
    Keep the existing "Prometheus, not Mimir" note. Mention that `META-INF/grafana/grafana-dashboard-*.json` is the
    dev-only mechanism and that nothing provisions dashboards on the cluster today.
  - `README.md` → *Observability* (around lines 255-264): one or two sentences to the same effect. Neither file
    documented the old dashboard, so this is a new statement, not a correction.
  - `.tasks/claim-intake-roadmap.md`: the step-6 row is already added alongside this plan — check it still reads right
    once the work is done, and update the *Related, not part of the five-step order* paragraph for #218 if the outcome
    changed.
  - `.tasks/github-issues/issue-06-grafana-dashboard.md`: add a dated note at the top saying the filed scope was
    superseded on 2026-11-03 by the delete-and-defer decision, and that provisioning and the AI panels moved to #277.
    Correct in place; don't delete the body (same convention as `04a`'s *Caveats & Problems*).
  - Check `docs/*.puml` and `docs/design/*.puml`. `continuous-scoring-architecture.puml:111` names Grafana generically,
    so expect no change.
- **GitHub (outward-facing — get the maintainer's go-ahead before posting):**
  - Comment on [#277](https://github.com/edeandrea/non-deterministic-no-problem/issues/277) recording the scope it
    inherits: cluster provisioning (its body currently says #218 owns it) and the AI row. Point at
    `PLAN.md` → *Findings handed to #277* for the metric inventory, the provisioning recipe and the Grafana version
    skew.
  - Comment on [#218](https://github.com/edeandrea/non-deterministic-no-problem/issues/218) answering the maintainer's
    own question with the decision and the reasoning.
- **Verification:**
  - `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai`, as CI runs them. Record the test
    totals.
  - A **fresh** reviewer checks that the docs match reality, that no dashboard reference was missed, and that nothing
    outside the deletion and the docs changed.
- **PR** into `main` from `gh218/grafana-dashboard`: title without closing keywords (suggested *Delete the stale AI
  Grafana dashboard*), body ending `Closes #218`.

## Key Points

- The claim "the built-in dashboards cover JVM/HTTP/Agroal" is the whole justification for the deletion, so it must be
  stated in the docs as fact, having been checked in task 01 — not asserted.
- Dev and the cluster run different Grafana majors (`0.24.0` vs `0.38.1`; `PLAN.md` → *Findings handed to #277*). Don't
  write anything in the docs that implies a dev check covers the cluster.
- No `src/` change belongs in this task beyond what task 01 already deleted.

## Done When

- [ ] `CLAUDE.md` and `README.md` state the new Grafana story, and `docs/*.puml` was checked.
- [ ] `.tasks/claim-intake-roadmap.md` and `.tasks/github-issues/issue-06-grafana-dashboard.md` are up to date.
- [ ] Comments posted on #277 and #218, with the maintainer's approval.
- [ ] `clean verify` is green under `-Pollama` and `-Pollama-openai`, with the totals recorded in `PLAN.md`.
- [ ] Fresh review approved, with any fixes recorded in `PLAN.md`.
- [ ] PR merged into `main`; #218 closed.
