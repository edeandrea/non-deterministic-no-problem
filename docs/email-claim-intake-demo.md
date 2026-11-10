# Demo guide: the email claim intake

Five scenarios that walk the whole intake — a customer email in Roundcube, through the IMAP IDLE
watcher and the `ClaimIntakeFlow` workflow, to a claim on the claims list, and (for the complete
scenarios) through the claims processor's review panel. Each scenario lists what to show and where.

See [CLAUDE.md](../CLAUDE.md) for the architecture this guide walks through, and
[docs/design/email-claim-intake.md](design/email-claim-intake.md) for the accepted design.

The sample email text in each scenario below was sent through a real `quarkus:dev` boot (real OpenAI
models, GreenMail SMTP) and produced exactly the outcome described — subjects and bodies are quoted
rather than paraphrased so a demoer can copy them verbatim. A model is still non-deterministic: expect
the same outcome most of the time, not every time.

## Before you start

**Log in to Roundcube as a seeded claimant**, not an arbitrary address:

| Claim id | Claimant | Mailbox |
|---|---|---|
| 1 | Marty McFly | `marty.mcfly@email.com` |
| 2 | John T. Anderson | `JAnderson@mail.com` |
| 3 | Jane Doe | `jane_doe@email.com` |
| 4 | Dominic Toretto | `domt@email.com` |
| 5 | Saul Goodman | `bettercallsaul@email.com` |
| 6 | Tyrion Lannister | `tylannister@email.com` |

These six addresses carry the claimant's name as their Roundcube identity
([#237](https://github.com/edeandrea/non-deterministic-no-problem/issues/237)), so mail they send goes
out as `Marty McFly <marty.mcfly@email.com>` and the intake's replies greet them by name ("Marty
McFly"). Any other address sends as a bare address and every reply greets "Customer".

**Why a seeded claimant, and whether it's required, is different per scenario:**

- **Scenarios 1, 2 and 5 (new claims / a non-claim email) don't technically need one.** Any address
  creates a genuinely new claim the same way. They use seeded claimants anyway, purely so the demo shows
  the named greeting instead of "Customer", and so every claimant on screen is one from the README's
  table rather than a one-off made-up address.
- **Scenario 3 (the follow-up) actually requires it.** `ClaimMatcher` only matches a follow-up to an
  existing claim when the sender's address is that claim's own recorded `email_address` — a deliberate
  anti-spoofing check (anyone else gets the generic "no matching claim" reply, with no details). Only the
  six seeded claimants have an existing claim in `V2__seed_claims.sql` to follow up on at all, so the sender has to
  be one of them, and specifically the one whose claim you're asking about.
- **Scenario 4 (the policy conflict) needs an email from someone who is *not* the real policy holder**,
  stating a policy number that *is* already on file for someone else. It doesn't have to be one of the six
  seeded identities — any address that isn't Marty's own would trigger the same "different customer" check
  — but using one (Jane Doe, rather than an arbitrary address) keeps the demo consistent with the rest and
  makes the contrast ("Jane's email, Marty's policy number") obvious to watch.

- **Dev mode:** Roundcube is at http://localhost:8000. Log in with any password — GreenMail has
  authentication disabled.
- **Cluster (OpenShift):** `deploy-to-openshift.sh` prints the Roundcube Route URL at the end of the
  deploy (`https://$(oc get route roundcube -o jsonpath='{.spec.host}')`); the app itself is on the
  `parasol-app` Route (`oc get route parasol-app`, or `oc get routes` to find it). Login and every
  scenario below work the same way; the only difference is where you point your browser.

### Sample photos to attach

`src/main/resources/seed/claim-images/` already has a car-damage JPEG for each of the six seeded claims — the
`ORIGINAL`-kind photo `ClaimImageSeeder` attaches to each claim by id (`SEED_IMAGES`), alongside the six `PROCESSED`
ones it also seeds (twelve files in all). Click one below to
view or save it, then attach it in Roundcube's compose window (`#uploadformInput`) when sending a new
claim email:

| Seeded for claim | Claimant | Image |
|---|---|---|
| 1 | Marty McFly | [`original_car1.jpg`](../src/main/resources/seed/claim-images/original_car1.jpg) |
| 2 | John T. Anderson | [`original_car2.jpg`](../src/main/resources/seed/claim-images/original_car2.jpg) |
| 3 | Jane Doe | [`original_car3.jpg`](../src/main/resources/seed/claim-images/original_car3.jpg) |
| 4 | Dominic Toretto | [`original_car4.jpg`](../src/main/resources/seed/claim-images/original_car4.jpg) |
| 5 | Saul Goodman | [`original_car5.jpg`](../src/main/resources/seed/claim-images/original_car5.jpg) |
| 6 | Tyrion Lannister | [`original_car6.jpg`](../src/main/resources/seed/claim-images/original_car6.jpg) |

That mapping only matters if you want the photo to match the claimant sending it (for example
`original_car1.jpg` when logged in as Marty for Scenario 1) — the intake itself doesn't check that a
photo matches its claim's own damage description, so any of the six works for any scenario. A plain JPEG
under the `limits.max-image-size` default (`10M`, see README → *Configuration*) is all that's required;
these files are 15–150 KB.

**Caveats that apply to every scenario:**

- **GreenMail keeps mail in memory**, so restarting it empties every mailbox. Dev mode's database is Dev Services'
  and goes with a dev mode restart (a live reload keeps both); on OpenShift the claims survive a redeploy, the mail doesn't.
  Plan a demo around one boot.
- **Dev mode already seeds two claims by email at startup** (`DevClaimEmailSeeder`, on when
  `parasol.dev-seed.enabled=true`, which `%dev` and the two Ollama `quarkus:dev` Maven profiles set):
  "Car hit a lamp post" from `dev.pending-review@example.com` (a complete claim) and "Someone damaged my
  car" from `dev.pending-information@example.com` (no date or place given). So before you send anything,
  the claims list usually already has one `Pending Review` and one `Pending Information` claim beyond
  the six seeded ones. **Hedge this** when demoing — it's what the model usually decides, not a code
  guarantee, and an occasional run may classify either differently.
- **Editing a `Flow` bean in dev mode can throw `IncompatibleClassChangeError`** on the next live-reload
  request. If that happens mid-demo, restart dev mode (which also resets the demo per the point above).
- **The agentic Dev UI does not show the intake.** `ClaimsMailboxAgent`'s `extends MonitoredAgent` is
  commented out (upstream `AgentMonitor` bugs,
  [langchain4j#6885](https://github.com/langchain4j/langchain4j/issues/6885) and
  [#6913](https://github.com/langchain4j/langchain4j/issues/6913)), so don't go looking for the intake's
  agents there — show the **Flow Dev UI** instead (see *Observability*, below).

---

## Scenario 1 — a complete new claim, both review branches

**Show:** Roundcube → the `claims` folders → the claims list → the review panel.

1. In Roundcube, log in as `marty.mcfly@email.com` and compose a new message to `claims@parasol.com`.
   Give it every detail the extraction needs — what happened, when, where, and the category — for
   example:

   > Subject: Fender bender on Oak Street
   >
   > On November 1, 2026 at about 2:00 PM I was rear-ended while stopped at a light on Oak Street. My
   > rear bumper is dented. Nobody was hurt.

   Attach [`original_car1.jpg`](../src/main/resources/seed/claim-images/original_car1.jpg) (Marty's own
   seeded photo) if you want to show photos on the Documents tab — see *Sample photos to attach*, above.
2. Watch the email move: it starts in the `INBOX`, the IMAP IDLE watcher moves it to `Processing`
   while its run works, and once the run ends it lands in `Processed` (or `Failed` if something goes
   wrong — see the processing-problem reply if that happens).
3. Marty gets a reply saying the claim is received and in final review (`receivedFinalReview`
   template). The claims list now shows a new claim in **Pending Review**.
4. Open the claim. The Documents tab's correspondence transcript shows the one inbound email (and any
   photo it carried). The Summary tab shows the extracted fields and the **review panel**: the claims
   processor can correct anything that's wrong, or check **Ask customer** next to any field that's
   still missing.
5. **Branch A — ready:** with every field correct and nothing checked, click **Submit claim for
   processing**. The claim moves to **In Process**, the review panel disappears, and Marty gets a
   thank-you email (`claimInProcess` template).
6. **Branch B — needs more information:** instead, check **Ask customer** on one field (for example
   location) and click **Request more information**. The claim moves to **Pending Information**, Marty
   is emailed asking for exactly that item (`missingInformation`/`stillMissingInformation` template, the
   read-only list of what was asked shown on the claim), and the review panel's controls disappear. Reply
   from Roundcube supplying the missing detail — the claim goes back to **Pending Review** and a review
   panel reappears for you to finish with Branch A.

## Scenario 2 — a claim missing its location

1. Log in as a different seeded claimant, `jane_doe@email.com`, and send a new claim that states what
   happened, when and the category, but **leaves out the location**:

   > Subject: Vandalism to my car
   >
   > On November 1, 2026 at around 9:00 AM, someone keyed the driver's side door of my car while it was
   > parked. The paint is scratched down to bare metal. No one was hurt.

   Attach [`original_car3.jpg`](../src/main/resources/seed/claim-images/original_car3.jpg) (Jane's own
   seeded photo) if you want to show a photo on the Documents tab.
2. The reply asks only for the location (`missingInformation`, listing just `LOCATION`); the claim is
   **Pending Information**. `GET .../review` for the claim shows `requested_items: ["location"]`.
3. Reply from Roundcube with the location:

   > Subject: Re: Vandalism to my car
   >
   > It happened in the parking lot at 450 Riverside Drive.

   The claim becomes **Pending Review** (`stillMissingInformation` is not used here since the reply
   completes it — the claim gets `receivedFinalReview` instead, and the recorded summary notes the
   location "added in reply").
4. Open the review panel and click **Submit claim for processing** → **In Process**, same as Scenario 1.

## Scenario 3 — a follow-up on Marty's claim

1. Still logged in as `marty.mcfly@email.com`, send an email **asking about claim 1's status** rather
   than describing a new incident:

   > Subject: Status of my claim
   >
   > What's the status of my claim CLM01000000?

   Use whichever claim number claim 1 actually has on your database — it's generated, not fixed; look it
   up on the claims list first (`CLM01000000` is what a fresh seed produces).

   Don't bother attaching a photo here: a follow-up never runs `ApplyOutcomeStep`, so nothing would store
   it (the email itself is still recorded on the Documents tab — just without a photo).
2. Claim 1 is seeded `In Process`, so the intake classifies this as a follow-up, matches it to claim 1 by
   the claim number in the body, and `ClaimFollowUpAgent` writes an AI status answer using its read-only
   status tool — for example "I looked up your claim — CLM01000000 — and its current status is: In
   Process." **The claim itself is never updated** — a follow-up only ever answers; it doesn't change the
   claim.
3. Show the reply in Roundcube, and the claim's Documents tab transcript, which now shows the follow-up
   email and the AI-written reply alongside the original claim correspondence.

## Scenario 4 — a policy-number conflict

1. Log in as `jane_doe@email.com` — not the holder of the policy number you're about to use — and send a
   **new** claim (a fresh incident, not a follow-up) that states Marty McFly's seeded policy number,
   `AC-987654321` (claim 1, `src/main/resources/db/migration/V2__seed_claims.sql`):

   > Subject: New claim - hail damage
   >
   > On November 1, 2026 my car was damaged by hail while parked outside my house at 42 Main Street. My
   > policy number is AC-987654321.

   A photo wouldn't demonstrate anything extra here: the policy check runs before `ApplyOutcomeStep`, so
   no claim or image is ever stored, attached or not.
2. The extraction runs as normal, but before any claim is created the workflow's policy-number rule
   (code, not the LLM) finds that `AC-987654321` belongs to a different customer (a different email
   address) and sends the vague **`policyInconsistency`** reply — for example "There is an inconsistency
   with the policy information provided. Please call us at 1-800-CAR-SAFE to resolve this issue." It
   never says whose policy number it is, or why. **No claim is created.**
3. Show the claims list is unchanged, and Jane's Roundcube Sent/Inbox shows only the one vague reply —
   nothing claim-specific leaks.

## Scenario 5 — a not-a-claim email

1. Log in as any seeded claimant (or any address) and send something that obviously isn't a claim:

   > Subject: Your monthly Parasol Insurance newsletter
   >
   > Your monthly Parasol Insurance newsletter: 5 tips for winter driving.

   Skip the photo here too: `UnmatchedEmailAgent`'s outcome never reaches `ApplyOutcomeStep` either, so
   nothing would be stored.

2. `EmailClassifierAgent` labels it `NOT_A_CLAIM`; since nothing matched an existing claim either, it
   routes to the non-LLM `UnmatchedEmailAgent`, which sends the **`notAClaim`** reply — for example
   "Thank you for reaching out. This inbox is dedicated solely to handling insurance claims. If your
   inquiry is unrelated to an insurance claim, please contact customer support at 1-800-CAR-SAFE." No
   claim is created or changed.

---

## Observability: one claim, one Langfuse session

1. Open Langfuse (dev mode's LGTM/Langfuse Dev Service prints its URL in the Quarkus dev console, or use
   the Dev UI's Langfuse card). Find the **session** for the claim you just created — search by its
   claim number, or browse Sessions by recency.
2. **A claim's session has several traces, not one** — one per workflow/sub-workflow run, plus the roughly 58 root
   traces Flow's own persistence emits per run (all kept and unsampled).
   `gen_ai.conversation.id` is the shared id that ties them into one session; it's
   entered once when the run starts and carried through the chat, Flow and the agents by the app's own
   `ai.scoring.conversation` package (see CLAUDE.md → *Conversation core*).
3. Open one of the traces and show the `workflow.execute` / `task.execute` spans Flow generates, typed
   `invoke_agent` / `chain` for Langfuse, and the `claim-intake` model calls underneath.
4. Note what's *not* there: no session-level sentiment score. The intake never ends a "conversation" in
   the chat-scope sense, so `ConversationEndedScorer` never fires for it — intake LLM calls get tier-1
   per-trace scores only (see `langfuse-evaluation.md`).

## Observability: the Flow Dev UI

1. Open the Quarkus Dev UI (http://localhost:8080/q/dev-ui in dev mode) → the **Workflows** card (from
   the `quarkus-flow` extension).
2. Find `claim-intake` and open it: Flow draws the whole intake as one typed, branching diagram — the
   supersede check, the agent step, the policy branch, persistence, the reply, filing, and the review
   `listen`/`switch` wait (task 01b, E17 in the spike results).
3. For a claim currently in **Pending Review**, show the run sitting in the `waitReview` state — it's
   genuinely suspended, persisted in PostgreSQL, holding no thread, until the claims processor decides.

---

## On the cluster

The same five scenarios run unchanged against an OpenShift deployment
(`./deploy-to-openshift.sh`); the only differences are the URLs:

- Roundcube: the Route URL the deploy script prints at the end.
- The claims app: the `parasol-app` Route.
- Langfuse and the Flow Dev UI: through the deployed app's own Dev UI is not available outside dev mode
  (the Dev UI is a dev-mode-only feature), so on the cluster, show the Langfuse session only; the Flow
  workflow diagram is a dev-mode-only demonstration.
- The intake runs as a **single replica** on the cluster (`quarkus.openshift.replicas: 1`, pinned and
  commented in `application.yml`) — see README → *Single replica* for why, and
  [#284](https://github.com/edeandrea/non-deterministic-no-problem/issues/284) for the follow-up to lift
  that constraint.
