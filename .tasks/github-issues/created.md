# Created GitHub issues

Repo: edeandrea/non-deterministic-no-problem. All issues labelled `enhancement` and cross-linked (placeholders `{{ISSUE_N}}` replaced).

| Roadmap # | GitHub # | Title | URL |
|---|---|---|---|
| 0 (tracking) | #217 | Email claim intake roadmap | https://github.com/edeandrea/non-deterministic-no-problem/issues/217 |
| 1 | #212 | Upgrade quarkus-langchain4j and other dependencies to the latest stable versions | https://github.com/edeandrea/non-deterministic-no-problem/issues/212 |
| 2 | #213 | Rework the claim data model: sequence-generated claim numbers and typed fields | https://github.com/edeandrea/non-deterministic-no-problem/issues/213 |
| 3 | #214 | Serve claim images from the backend instead of bundled frontend assets | https://github.com/edeandrea/non-deterministic-no-problem/issues/214 |
| 4 | #215 | Replace Mailpit with GreenMail and Roundcube webmail | https://github.com/edeandrea/non-deterministic-no-problem/issues/215 |
| 5 | #216 | Agentic email claim intake and triage | https://github.com/edeandrea/non-deterministic-no-problem/issues/216 |
| related | #218 | Clean up the Grafana AI dashboard | https://github.com/edeandrea/non-deterministic-no-problem/issues/218 |
| related | #221 | Spike: compare a LangChain4j decision model (Jev) with the LLM email classifier | https://github.com/edeandrea/non-deterministic-no-problem/issues/221 |

## Sub-issues of #217

Verified via `GET /repos/edeandrea/non-deterministic-no-problem/issues/217/sub_issues`:

1. #212
2. #213
3. #214
4. #215
5. #216
6. #218 (related; not part of the five-step order)
7. #221 (related; follow-up spike to #216, not part of the five-step order)

## Dependencies (blocked by)

Verified via `GET /repos/edeandrea/non-deterministic-no-problem/issues/{n}/dependencies/blocked_by`:

| Issue | Blocked by |
|---|---|
| #212 | — |
| #213 | #212 |
| #214 | #212, #213 |
| #215 | #212 |
| #216 | #212, #213, #214, #215 |
| #218 | #212 (#212 bumps the `grafana/otel-lgtm` image) |
| #221 | #216 (the spike compares against the #216 classifier; it also waits for a quarkus-langchain4j release with `quarkus-langchain4j-typesafe`) |
