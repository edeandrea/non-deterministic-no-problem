# Task 02: Design Document and Review Gate

**Type:** Documentation

## Goal

A reviewable design for the email claim intake exists in the repository and is opened as a pull
request. It first describes **what** the feature must do, without agent or framework terms, then
proposes the agentic architecture, backed by the spike results from task 01. Implementation tasks
(03 onwards) don't start until the user confirms the reviewer has signed off.

## What to Do

- Write `docs/design/email-claim-intake.md` in two parts.
- **Part 1: What we're building** (no agent/framework terms):
  1. **Goal and scope.** The problem, the actors (customer, intake system, claims processor), and what's out of scope.
  2. **Claim lifecycle.** Every status (`Pending Information`, `Pending Review`, `In Process`, plus the existing
     statuses that only get a status reply) and what triggers each transition.
  3. **Workflow steps.** End to end, from the customer's email to the final review and the thank-you email.
  4. **Email handling rules:**
     - classification (new claim / follow-up / not a claim)
     - reply-to-claim matching, and who may update a claim
     - policy-number rules
     - required information (`OTHER` is a valid category)
     - attachments
     - duplicates and auto-replies
     - failures
  5. **Customer communication matrix.** Every situation and the reply it gets. Every submission gets a
     reply; the only exceptions are auto-replies, self-sent mail and duplicates.
  6. **Human review.** What the claims processor sees, the two decisions (Ready / Needs more
     information), and what each one causes.
  7. **Non-functional requirements:**
     - idempotency, and surviving a restart while a claim waits for review
     - observability: one trace per email, a linked review trace, metrics, logs, Langfuse scoring
     - security: email text treated as data (prompt injection), minimal-information rejection emails
     - it runs in dev, in tests and on the cluster
- **Part 2: Proposed agentic architecture**, using the task 01 spike results as evidence:
  1. **Components:** IMAP watcher → processor → agents → reply sender; review service and REST API.
  2. **Agent topology:** every agent, AI or non-AI, with its inputs (scope keys), outputs, model and tools.
  3. **Human review mechanics:**
     - `@HumanInTheLoop` + `SuspendedResponse`
     - the database-backed `AgenticScopeStore`
     - memory id = inbound `Message-ID`
     - `reviewRunId`, eviction, superseding
  4. **Side-effect boundary:** the agents only decide; the processor and services act.
  5. **Observability design:** trace shape, span link, `claim.intake.*` metrics.
  6. **Risks and open questions:**
     - beta module, and the two internal touch points (`SuspendedResponse`, `DefaultAgenticScope`)
     - store registration order
     - the decision gate and its status-only fallback
     - anything the spike left unresolved
     - the proposed GreenMail REST test helper (still awaiting the user's confirmation)
  7. **Alternatives considered**, each with the reason it was rejected:
     - status-only review vs `@HumanInTheLoop` (and blocking vs suspended HITL)
     - polling / Mailpit websocket vs IMAP IDLE
     - Mailpit vs GreenMail + Roundcube
     - in-JVM GreenMail (`greenmail-junit5`) vs Compose
     - AI-written vs templated emails
- **Diagrams** (PlantUML sources under `docs/design/`, rendered to PNG and embedded):
  - **Claim state diagram:** transitions labelled with their trigger and the email sent.
  - **Email workflow:** activity diagram from email received to email filed.
  - **Agent topology:** component diagram.
  - **Review sequence:** customer → GreenMail → watcher → workflow suspends → processor UI → resume → thank-you.
  - Render with `docs/render-diagrams.sh`. If the script only handles a fixed list or `docs/*.puml`,
    extend it to cover `docs/design/`, and include that change in the pull request.
- Link the design doc from `README.md` (Further reading) and `CLAUDE.md`.
- Work on a dedicated branch in a separate git worktree, so the user's working tree is untouched.
  Open a pull request:
  - title without closing keywords
  - body summarising the design and asking for review
  - issue reference (`#216`) at the end of the body only
- Before opening the pull request, have a **fresh** reviewer agent check the document against
  `PLAN.md` Key Decisions, the task files and the spike results: missing rules, contradictions,
  diagram/text mismatches, and statements not backed by evidence. Fix and re-check.
- **Stop.** Report the pull request URL to the user and wait for the user to confirm the review is done.
- After the review: apply the agreed changes to the design doc first, then propagate them into
  `PLAN.md`, the task files and the #216 issue body, before any implementation task starts.

## Files/Areas

- `docs/design/email-claim-intake.md` and `docs/design/*.puml` / `*.png` (new)
- `docs/render-diagrams.sh` (only if it needs extending)
- `README.md`, `CLAUDE.md` (links)

## Key Points

- **Source of truth for the content:** `PLAN.md` Key Decisions, task files 03–14 and the spike results. Don't
  invent new behaviour. If you find a gap, list it under open questions instead of deciding it.
- **Part 1 must be readable by someone who doesn't know LangChain4j.** Part 2 is for an engineer reviewing the architecture.
- **Diagrams must match the text exactly.** The reviewer checks every transition and every reply.
- **Commit rules from `AGENTS.md`:** no closing keywords in the first line; issue references at the end of the body.
  Stage files explicitly (never `git add -A`; `AGENTS.md` is deliberately untracked).

## Done When

- [ ] `docs/design/email-claim-intake.md` has both parts, with all the sections listed above.
- [ ] The four diagrams are rendered and embedded.
- [ ] The fresh review found no unresolved contradictions or gaps.
- [ ] The pull request is open, and its URL is recorded in `PLAN.md`.
- [ ] The user has confirmed the design review is complete.
- [ ] Agreed review changes have been applied to the doc, `PLAN.md`, the task files and the #216 issue body.