# Design: email claim intake

Status: proposed, for review before implementation ([#216](https://github.com/edeandrea/non-deterministic-no-problem/issues/216)).
Revised after the quarkus-flow spike: the intake is now one [quarkus-flow](https://docs.quarkiverse.io/quarkus-flow/dev/)
workflow, and the agents are one of its steps. This replaces the agentic human-in-the-loop design merged in
[#220](https://github.com/edeandrea/non-deterministic-no-problem/pull/220).

## Goal

Customers open a claim by emailing `claims@parasol.com`. The **intake system** classifies each email,
extracts the details, asks the **customer** for anything missing and updates the claim as replies
arrive. A **claims processor** then does one last completeness look-over (not an approval).
The existing chat (`ClaimService`), `NotificationService` and `GenerateEmailService` behave as today.
They share the email sign-off with the new templates, and the chat's conversation handling moves onto the
new conversation package described under Observability, with no behaviour change. Out of scope: non-English email, several
incidents in one email, the reviewer editing extracted fields, and drift detection for the new agents.

The prerequisites are [#212](https://github.com/edeandrea/non-deterministic-no-problem/issues/212) (dependency upgrades), [#213](https://github.com/edeandrea/non-deterministic-no-problem/issues/213) (claim data model), [#214](https://github.com/edeandrea/non-deterministic-no-problem/issues/214) (claim images) and [#215](https://github.com/edeandrea/non-deterministic-no-problem/issues/215)
(GreenMail and Roundcube), all merged. Progress is tracked in [#217](https://github.com/edeandrea/non-deterministic-no-problem/issues/217).

## Workflow

![Email claim intake workflow](claim-intake-workflow.png)

A claim is **pending** while it is `Pending Information` or `Pending Review`. Each email gets **one
workflow run**, and the steps below are that run's steps.

1. A watcher (IMAP IDLE) receives the email, records its `Message-ID`, moves it to a `processing` folder
   and starts its run **without waiting for it**. Runs for different customers go in parallel. One
   customer's emails go in order: while their previous run is still working, the next email waits behind
   it.
2. Auto-replies, self-sent mail and duplicates (same `Message-ID`) are filed without a reply, and no run
   starts.
3. Code (not the LLM) matches the email to a claim: a claim number in the subject or body, then the
   reply headers. Only the claim's own email address may match it; anyone else gets the "no matching
   claim" reply, which gives no details and asks them to write from the claim's address or include
   the claim number. If nothing matched but the sender has pending claims, the run first asks an LLM
   whether the email is about one of them (checked in code against those claims only). If it is, the email
   is handled as a follow-up on that claim; if it's a new incident, as a new claim; if it can't tell, the
   customer gets a "which claim?" reply listing their pending claim numbers, and nothing changes. A reply
   to a `Pending Review` claim will **cancel** that claim's waiting run (step 6).
4. The agents classify the email as a new claim, a follow-up or not a claim. **The matched claim wins:**
   a matched email is a follow-up on that claim, whatever the label. Without a match the label decides;
   an unmatched "follow-up" gets the "no matching claim" reply, and nothing is created or changed.
5. A matched claim in any other status (e.g. `New`, `In Process`, `Processed`, `Denied`) gets a status
   answer. For a matched pending claim, or an unmatched new claim, the details are extracted over the
   whole correspondence: what happened, incident date, location and category (`Other` counts).
6. When the agents finish, the workflow applies the policy-number rule in code, before any claim is
   created or changed. If a new claim states a number that belongs to another customer, it sends a vague
   "policy inconsistency" reply and ends; no claim is created. Otherwise, if the claim was waiting for
   review, its waiting run is cancelled now, just before the claim changes.
7. If anything is missing, the claim is `Pending Information` and the customer is asked for exactly
   those items. Each reply starts again at step 1.
8. When nothing is missing, the claim is `Pending Review`, the customer is told it's received and in
   final review, and the run **waits** for the claims processor without holding a thread. Flow keeps
   the waiting run in PostgreSQL while the app runs. (A restart resets the demo: the claims, the mail
   and any waiting review.)
9. The run files the email before it waits or ends. If any step fails, before or after the wait, a
   failure listener moves the email to a failed folder and the customer gets a "processing problem" reply.
10. Later, and separately, the claims processor decides on the claim detail page, which wakes the
    waiting run. **Ready** → `In Process` and a thank-you email. **Needs more information** (ticking
    items) → `Pending Information` and an email listing exactly those items; the claim stays incomplete
    until the customer answers them, so an empty reply doesn't go straight back to review. Applying the
    decision makes no LLM call.

Every submission gets a reply (fixed templates, except the AI-written status answer), apart from
auto-replies, self-sent mail and duplicates. A reply and a decision arriving together are serialised by
optimistic locking on the claim: whichever commits first wins. A losing decision gets a 409; a losing
reply's email goes back in line behind the claim's run and is handled again under the claim's new status.

## Claim states

![Claim states set by the email intake](claim-intake-states.png)

| From | Trigger | To |
|---|---|---|
| (new) | New claim email with details missing | `Pending Information` |
| (new) | New claim email with every detail present | `Pending Review` |
| `Pending Information` | Customer reply, details still missing | `Pending Information` |
| `Pending Information` | Customer reply completes the details | `Pending Review` |
| `Pending Review` | Customer reply, details still complete: the waiting run is cancelled and a new one waits | `Pending Review` |
| `Pending Review` | Customer reply leaves details missing: the waiting run is cancelled | `Pending Information` |
| `Pending Review` | Claims processor: Needs more information | `Pending Information` |
| `Pending Review` | Claims processor: Ready | `In Process` |

Claims in any other status (e.g. `New`, `In Process`, `Processed`, `Denied`) only get a status answer
and are never updated. Intake never sets `New`.

## Architecture

![Email claim intake: workflow and agents](claim-intake-agents.png)

**The workflow.** `ClaimIntakeFlow` is one quarkus-flow workflow, and its steps do the side effects
(apart from filing skipped mail and handling a failed run, which a failure listener does): cancel a superseded run, check the policy number, save the claim and photos, send the reply
(`IntakeReplySender`, Qute templates), and file the email. For a complete claim, a `listen` step then
waits for a `REVIEW_DECIDED` event for that claim, and a `switch` applies the decision. Flow's Dev UI
draws the workflow as a diagram. `ClaimIntakeStarter` does the checks before a run (loop and duplicate checks,
claim match, sender check), keeps each sender's emails in order, and starts the run; nothing waits for
it. Ordering by sender covers ordering by claim, because a claim only ever matches its own address.
The app runs as a **single replica**: cancelling a run and delivering the decision event both happen
in-process.

**Resolving the claim.** Before the agents, a `resolveClaim` step handles an email that code couldn't
match but whose sender has pending claims. `ClaimResolver` (an LLM service, not part of the agent
topology) gets the email and those claims (number, short summary, what we asked for) and answers one of
them, a new claim, or unsure; code accepts only a claim from that list. It only ever offers the sender
their own claims, so the "which claim?" reply leaks nothing even if `From:` is spoofed.

**The agents** are one workflow step. `ClaimsMailboxAgent` is the root (a sequence), called with the
email, the matched claim (or none) and the claim's history:

- `EmailClassifierAgent` (LLM) returns the email type.
- `EmailRouter` (no LLM) routes on the matched claim first, then on the type:
  - matched pending claims, and unmatched new claims, go to `ClaimExtractionWorkflow`: three LLM agents
    in parallel, `ClaimSummaryAgent`, `ClaimSentimentAgent` and `IncidentDetailsAgent` (description,
    date, optional time, location, category, stated policy number).
  - matched claims in any other status go to `ClaimFollowUpAgent` (LLM), which writes the status
    answer using a read-only status tool.
  - unmatched "not a claim" and "follow-up" emails go to `UnmatchedEmailAgent` (no LLM) for those two outcomes.

Flow runs each composite (sequence, router, parallel) as its own sub-workflow, and the parallel one really
runs in parallel. The agents only decide: they return an `IntakeOutcome`, have no side effects, and never
pause. All LLM agents use the `claim-intake` model. They're stateless (no chat memory, no retrieval over
the policy PDF): the workflow passes the claim's history (correspondence, fields extracted so far, items
requested) in on every run.

**The review decision** (`POST /api/db/claims/{id}/review-decisions`) claims the review under the claim's
lock, checks that the claim's run is still waiting (404 if it's gone, 409 if it isn't waiting), and
publishes the decision event. It returns once the event is published (202), and the waiting run applies it.

**Observability.** The goal is that every span of a claim's runs carries the same
`gen_ai.conversation.id`, so Langfuse groups each claim into one session. One run is still several
traces ([quarkus-flow#1056](https://github.com/quarkiverse/quarkus-flow/issues/1056)); the shared id is what
ties them together. The id is OTel baggage, entered once when a run starts. The code that carries it across
the chat, Flow and the agents is our own framework-neutral package, `ai.scoring.conversation`, kept
separate so it can later become its own library. It has a small core (the conversation context, a span
processor that stamps the id, and a "conversation ended" event), one adapter per framework, and no intake
code. Two parts of it are temporary workarounds:
- a guard for a Quarkus context-propagation bug ([quarkus#54354](https://github.com/quarkusio/quarkus/issues/54354)),
  which otherwise leaks one run's id onto another run's spans;
- an agent adapter that carries the id into Flow's generated agent sub-workflows until #1056 is fixed. It's
  proven before implementation starts.

The chat moves onto the same package, and session scoring becomes a listener for a "conversation ended"
event. Nothing in the intake ends a conversation yet, so intake LLM calls get tier-1 scores but claims get
no session score.

quarkus-flow is 1.1.3; its LangChain4j and OpenTelemetry integrations are Preview extensions. The spike
evidence behind this design is in
[`spike-results-flow.md`](../../.tasks/05-gh216-agentic-email-intake/spike-results-flow.md).
