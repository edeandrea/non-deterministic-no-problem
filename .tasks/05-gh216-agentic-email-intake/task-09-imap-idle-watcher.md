# Task 09: IMAP IDLE Watcher

**Type:** Code Modification

## Goal

New mail in the claims INBOX is processed automatically, without polling. Mail that arrived while the
app was down is processed on reconnect, and a GreenMail outage never stops startup.

*Updated after the Flow verdict (task 01b, option b1) and the user's decision of 2026-10-07:* the watcher **doesn't
wait for runs**. It hands each email to `ClaimIntakeStarter` (task 08), which starts a run (or queues the email behind
the same sender's running run) and returns at once. There's no agentic scope store, so there's no startup-ordering rule.

**Check first: quarkus-flow 1.2.0** (`PLAN.md` → Execution Steps → 2a; expected ~2026-10-09). If it's out, re-run the
reproducers and re-adjust this task and the earlier ones before building. Nothing here depends on it directly.

## What to Do

- Create `ClaimsInboxWatcher`. At startup it starts one dedicated thread, but only when `IntakeConfig.enabled()`.
  Start it from a `@Startup` bean or a `StartupEvent` observer.

  The thread loops (`ClaimsMailbox` from task 03 has `unprocessed()`, `list(folder)`, `find(folder, messageId)` and
  `move(messageId, from, to)`; `MailFolder.PROCESSING` exists; the IDLE connection itself is this task's):
  1. Connect (retry with backoff on failure; log WARN, never throw to startup).
  2. For every message currently in the INBOX (catch-up): record its `Message-ID` (unique constraint; a duplicate is
     filed without a reply), move it to the `processing` folder, and hand it to `ClaimIntakeStarter`.
  3. Run IMAP `IDLE`, re-issuing it before the server timeout. On `MessageCountEvent`, do the same for new messages.
  4. On a disconnect, go back to step 1.
- Stop cleanly on `ShutdownEvent`.
- **One watcher, no pool.** One IMAP IDLE connection notices new mail; several watchers on one INBOX would race for the
  same message. The parallelism comes from Flow (`instance.start()` is asynchronous, on Quarkus's managed executor),
  whose size caps how many runs go at once.
- **Moving to `processing` is what stops a second pickup:** the email leaves the INBOX before its run starts. The run's
  `file` step moves it to processed, or the failure listener to failed (task 08).
- On startup, emails left in `processing` by a previous JVM belong to runs that no longer exist (a restart wipes the
  database): move them back to the INBOX before the catch-up scan.
- Confirm that the pinned GreenMail version supports IDLE (it's registered in GreenMail's command factory on main). If
  it doesn't, fall back to a short NOOP-based check and record it in `PLAN.md`. **Answered in task 03:** GreenMail
  2.1.14 advertises `IDLE` (and `MOVE`, `UIDPLUS`) in its capabilities.
- **Tests (with a test profile that enables intake; mock `ClaimIntakeStarter`):**
  - a message delivered while watching is processed
  - messages present before start are processed (catch-up)
  - the watcher doesn't wait: several emails are handed over without waiting for runs
  - a picked-up email is in `processing` before its run starts, and is never handed over twice
  - emails left in `processing` at startup are moved back and processed
  - the watcher survives and reconnects after the IMAP connection drops
  - startup succeeds when GreenMail is unreachable

## Files/Areas

- `src/main/java/org/parasol/intake/ClaimsInboxWatcher.java` (new)
- `src/test/java/org/parasol/intake/` (new tests + profile)

## Key Points

- Intake is disabled in `%test` by default, so only the watcher tests turn it on.
- Use `io.quarkus.logging.Log`. Don't let the thread swallow `InterruptedException` without restoring the interrupt flag.
- No observability code here: the starter enters the conversation id (task 08); IMAP spans and watcher metrics are
  task 11's.
- **Single replica.** Superseding a waiting review uses `activeInstance(id)`, which only finds runs in this JVM, and the
  per-sender queue is in memory.

## Done When

- [ ] The watcher hands over live and backlog mail without waiting for runs, never twice, and recovers from disconnects.
- [ ] Emails left in `processing` at startup are recovered.
- [ ] All the watcher tests listed above pass under `-Pollama`.
