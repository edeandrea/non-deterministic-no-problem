# Task 02: Design Document and Review Gate

**Type:** Documentation

## Goal

A short, reviewable design for the email claim intake exists in the repository and is opened as a
pull request. It covers only the workflow, the claim states, and how the agents are organised. The
detailed rules, edge cases and implementation notes stay in `PLAN.md` and the task files. Implementation
tasks (03 onwards) don't start until the user confirms the reviewer has signed off.

## What to Do

- Write `docs/design/email-claim-intake.md`, short enough to read in a few minutes (roughly 500–800 words of prose; the diagrams carry most of the content):
  1. **Goal.** One or two paragraphs: the problem, the actors (customer, intake system, claims
     processor), and what's out of scope. Link the prerequisite issues (#212–#215) and the tracking issue #217.
  2. **Workflow.** The end-to-end flow as a numbered list plus an activity diagram: email received →
     classified (new claim / follow-up / not a claim) → details extracted → missing details requested
     from the customer, with back-and-forth → claim judged complete → the claims processor's final
     look-over → Ready or Needs more information. Show where the customer gets a reply, without listing
     every template.
  3. **Claim states.** A state diagram for `Pending Information` → `Pending Review` → `In Process`
     (and back to `Pending Information`), with each transition's trigger. Mention that claims already
     `In Process` or later only get a status answer.
  4. **Agent architecture.** A component diagram, plus a short description, of how the agents are organised:
     - the root workflow and its sub-agents
     - which agents call the LLM and which don't
     - what each agent receives and produces
     - where the workflow pauses for the human review and how it resumes
     - the boundary: agents only decide; the processor saves claims, sends email and files messages

     Keep this at the level of "which agents exist and how they fit together". The framework workarounds
     from the spike are covered by a one-line pointer to `spike-results.md` findings, not explained.

  No open-questions section (user decision). Unresolved implementation points stay in `PLAN.md` → Caveats.
- **Diagrams** (PlantUML sources under `docs/design/`, rendered to PNG and embedded): `claim-intake-workflow.puml`,
  `claim-intake-states.puml` and `claim-intake-agents.puml`. Render with `docs/render-diagrams.sh`, extending it to
  cover `docs/design/` if needed. Check each PNG for silent cropping (exactly 4096 px) and warning banners.
- Link the design doc from `README.md` (Further reading) and `CLAUDE.md`.
- Work on a dedicated branch (`design/email-claim-intake`) in a separate git worktree, so the user's working
  tree is untouched. Open a pull request:
  - title without closing keywords
  - body summarising the design and asking for review
  - issue reference (`Part of #216`) at the end of the body only
- Before opening the pull request, have a **fresh** reviewer agent check the document against `PLAN.md`
  Key Decisions and the spike results: contradictions, diagram/text mismatches, and anything the short
  format leaves out that a reviewer would need in order to judge the design. Fix and re-check.
- **Stop.** Report the pull request URL to the user, and wait for the user to confirm the review is done.
- After the review: apply the agreed changes to the design doc first, then propagate them into `PLAN.md`,
  the task files and the #216 issue body, before any implementation task starts. Apply the
  "Changes required in later tasks" list from `PLAN.md` → Spike Results at the same time.

## Files/Areas

- `docs/design/email-claim-intake.md` and `docs/design/*.puml` / `*.png` (new)
- `docs/render-diagrams.sh` (only if it needs extending)
- `README.md`, `CLAUDE.md` (links)

## Key Points

- **User decision:** keep it short: workflow, states and agent organisation only. The communication
  matrix, non-functional requirements, alternatives considered and framework details stay in `PLAN.md`,
  the task files and `spike-results.md`.
- **Source of truth for the content:** `PLAN.md` Key Decisions and the spike results. Don't invent new
  behaviour. If you find a gap, report it to the user and record it in `PLAN.md` → Caveats; don't put it in the doc.
- **The diagrams must match the text exactly.**
- **Commit rules from `AGENTS.md`:** no closing keywords in the first line; issue references at the end of
  the body. Stage files explicitly (never `git add -A`; `AGENTS.md` is deliberately untracked). Never
  commit `.tasks/` on the design branch.

## Done When

- [ ] `docs/design/email-claim-intake.md` has the four sections above, with three embedded diagrams, and reads in a few minutes.
- [ ] The fresh review found no unresolved contradictions.
- [ ] The pull request is open, and its URL is recorded in `PLAN.md`.
- [ ] The user has confirmed the design review is complete.
- [ ] Agreed review changes, and the spike-driven task changes, are applied to the doc, `PLAN.md`, the task files and the #216 issue body.