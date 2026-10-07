# Task 07: Human Review Step — MERGED INTO TASK 08

**Type:** — (retired)

**This task is merged into [task 08](task-08-intake-processor.md) (user decision, 2026-10-07).** Under the
quarkus-flow design (task 01b = ADOPT, option b1), the whole intake is one Flow workflow, and the human review is its
last few steps: mark the claim `Pending Review` → wait for the decision event (`listen`) → route the decision
(`switch`) → apply it. Building those steps apart from the workflow they belong to would only add a seam.

What this task used to cover, and where it went:

| Old (agentic `@HumanInTheLoop` design) | Now |
|---|---|
| static `@HumanInTheLoop` `ClaimReviewAgent`, `SuspendedResponse`, `AgenticSystemSuspendedException` | gone: the intake workflow's `listen` step waits (task 08) |
| `DatabaseAgenticScopeStore`, registrar, allowlist, self-test, manual eviction | gone: Flow persists the run in PostgreSQL and deletes its rows when the run ends or is cancelled |
| `ReviewDecision` record | kept, as the decision event's payload (task 08) |
| the non-LLM decision router (`@ConditionalAgent`) | a workflow `switch` on the decision (task 08) |
| `ClaimReviewService.decide` (claim the review, resume with `readState`, evict) | the review endpoint checks the run is waiting (404/409) and publishes the decision event (task 10) |
| optimistic locking between a reply and a decision (`@Version`) | kept: task 08 (supersede) and task 10 (decision) |
| the reply-vs-decision race test | kept: task 08 |
| the decision's linked span | gone: the decision is part of the same run, so its steps are in that run's spans (task 11) |

There's nothing left to do here. `PLAN.md`'s task list marks it as merged.
