# Email Claim Intake — Roadmap

The end goal is an agentic, email-driven claim intake: a customer emails `claims@parasol.com` from a
webmail client, and the app triages the email with `quarkus-langchain4j-agentic`, creates or updates a
claim, and replies. The work is split into five issues, executed **in order, one at a time**. Each has
its own task directory and `PLAN.md`.

Filed on GitHub as **#212–#216** (bodies in `github-issues/`, numbers in `github-issues/created.md`).

| # | Issue (GitHub title) | Plan | Depends on |
|---|---|---|---|
| 1 | Upgrade quarkus-langchain4j and other dependencies to the latest stable versions | [`01-gh212-dependency-upgrades/PLAN.md`](01-gh212-dependency-upgrades/PLAN.md) | — |
| 2 | Rework the claim data model: sequence-generated claim numbers and typed fields | [`02-gh213-claim-data-model/PLAN.md`](02-gh213-claim-data-model/PLAN.md) | 1 |
| 3 | Serve claim images from the backend instead of bundled frontend assets | [`03-gh214-claim-images/PLAN.md`](03-gh214-claim-images/PLAN.md) | 2 (both edit `ClaimDetail.tsx` and the seed data) |
| 4 | Replace Mailpit with GreenMail and Roundcube webmail | [`04-gh215-greenmail-roundcube/PLAN.md`](04-gh215-greenmail-roundcube/PLAN.md) | 1 |
| 5 | Agentic email claim intake and triage | [`05-gh216-agentic-email-intake/PLAN.md`](05-gh216-agentic-email-intake/PLAN.md) | 1, 2, 3, 4 |

**Related, not part of the five-step order:** #218 Clean up the Grafana AI dashboard
(body in `github-issues/issue-06-grafana-dashboard.md`; independent of steps 2–5, blocked by step 1
because step 1 bumps the `grafana/otel-lgtm` image).

**Related, not part of the five-step order:** #221 Spike: compare a LangChain4j decision model (Jev)
with the LLM email classifier (body in `github-issues/issue-07-decision-model-spike.md`; a follow-up
to step 5, blocked by step 5 and by a quarkus-langchain4j release that ships `quarkus-langchain4j-typesafe`).

**Related, not part of the five-step order:** #222 Repackage `org.parasol` by domain, with layer
sub-packages (body in `github-issues/issue-08-repackage-by-domain.md`). Housekeeping done before steps 2 and 4,
which it blocks, so the new classes in steps 2–5 land in the domain-first layout
(`org.parasol.claim`, `org.parasol.chat`, `org.parasol.notification`, `org.parasol.intake`).

## Draft issue bodies (for filing on GitHub)

Titles and first lines must not contain closing keywords (`close`, `fix`, `resolve` and their forms);
closing references belong at the end of commit/PR bodies only.

### 1. Upgrade quarkus-langchain4j and other dependencies to the latest stable versions
Bump `quarkus-langchain4j` (BOM, currently 1.13.1) and every other Maven dependency, plugin, container
image and runtime pin to its latest **stable** release. This happens first so the agentic module in
issue 5 is introduced at its latest version and the upgrade risk is isolated from feature work.
Includes a regression check of chat, email generation, guardrails and drift detection, plus doc updates.

### 2. Rework the claim data model: sequence-generated claim numbers and typed fields
- `claimNumber` becomes a Hibernate `@NaturalId`, generated on insert from a PostgreSQL sequence with
  `INCREMENT BY 1009` (formatted `CLM` + 8 digits), and protected by a unique constraint. The numeric `id` stays the primary key.
- `subject`, `body` and `location` become unbounded text. `summary` and `sentiment` stay at 5000 characters.
- `category` becomes an enum (`Single vehicle`, `Multiple vehicle`, `Theft`, `Other`).
- `time` (free text) is replaced by `incidentDate` (`LocalDate`) and an optional `incidentTime` (`LocalTime`).
- The seed data in `import.sql` is rewritten to match. REST JSON, UI and tests are updated.

### 3. Serve claim images from the backend instead of bundled frontend assets
Claim images are currently guessed from the claim id in `ClaimDetail.tsx`. They only reach the build
because the legacy `OriginalApp.tsx` page imports them, and any claim with id > 6 shows broken images.
- Store images in a `ClaimImage` table (PostgreSQL `bytea`), seeded from the existing pictures.
- Serve them through a REST endpoint and have the UI read from it.
- Show "No images attached" when a claim has none.
- Remove `OriginalApp.tsx` and the now-unused assets.

### 4. Replace Mailpit with GreenMail and Roundcube webmail
Replace Mailpit with GreenMail (SMTP + IMAP) and the Roundcube webmail, defined once in
`compose-devservices.yml` and used by dev mode **and all tests**. Mirror the same pair in
`src/main/kubernetes/dependencies.yml` (Roundcube exposed through a Route). Remove `quarkus-mailpit`
and `quarkus-mailpit-testing`, and rewrite the mail tests against GreenMail. Customers (e.g. Marty)
can then read the app's status emails in Roundcube.

### 5. Agentic email claim intake and triage
The issue starts with a spike and a design document (requirements, workflow, state diagram, proposed
agentic architecture) that is reviewed externally before implementation starts.
A customer emails `claims@parasol.com`. An IMAP IDLE watcher picks the email up, and an agentic
workflow classifies it (new claim / follow-up / not a claim). For a new claim, it extracts the details
and writes the summary and sentiment. The app then:
- creates the claim (`Pending Information` until the details are complete, then `Pending Review`)
- stores image attachments
- replies to every customer email with Qute-templated emails, confirming what the customer did and what happens next
- moves the email into a processed folder

Once the claim is complete, a claims processor does a final human review (`Pending Review`): the
workflow pauses at a `@HumanInTheLoop` step, suspended and persisted in PostgreSQL, and resumes on the
processor's decision. Ready moves the claim to `In Process` with a "thank you, we're working on your
claim" email; needing more information sends it back to `Pending Information`.

Follow-ups can complete a pending claim. Claims `In Process` or later only get a status answer. Edge cases
covered: policy mismatch, auto-reply loops, idempotency, HTML email and attachments. Every edge case
gets a test, including a Roundcube end-to-end Playwright test that runs in CI.
Observability is preserved: one trace per email, a review trace linked to it, intake metrics, and logs with trace ids.
