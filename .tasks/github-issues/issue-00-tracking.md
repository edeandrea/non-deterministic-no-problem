TITLE: Email claim intake roadmap
## Summary

This issue tracks the email-driven claim intake work. A customer emails `claims@parasol.com` from a
webmail client. The app triages the email with `quarkus-langchain4j-agentic`, creates or updates a
claim, and replies.

The work is split into five issues, implemented **one at a time** in the order below. Each one is a
sub-issue of this issue; the ordering constraints are recorded as GitHub issue dependencies
("blocked by").

## Order

| Step | Issue | Blocked by |
|---|---|---|
| 1 | #212 Upgrade quarkus-langchain4j and other dependencies to the latest stable versions | — |
| 2 | #213 Rework the claim data model: sequence-generated claim numbers and typed fields | #212 |
| 3 | #214 Serve claim images from the backend instead of bundled frontend assets | #212, #213 |
| 4 | #215 Replace Mailpit with GreenMail and Roundcube webmail | #212 |
| 5 | #216 Agentic email claim intake and triage | #212, #213, #214, #215 |

Every step except #216 is useful on its own.

## Rules for every step

- Update every affected document in the same change: `README.md`, `CLAUDE.md`, `docs/`, Javadoc.
- New dependencies and container images use the latest **stable** versions.
- Every edge case gets a test.
- Commit and PR titles never contain closing keywords. Issue references go at the end of the body.

## Related

- #218 Clean up the Grafana AI dashboard (independent of steps 2–5; blocked by #212)
- {{ISSUE_7}} Spike: compare a LangChain4j decision model (Jev) with the LLM email classifier (follow-up to #216; blocked until it lands and a quarkus-langchain4j release ships the typesafe extension)
