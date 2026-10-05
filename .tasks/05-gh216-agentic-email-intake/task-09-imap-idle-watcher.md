# Task 09: IMAP IDLE Watcher

**Type:** Code Modification

## Goal

New mail in the claims INBOX is processed automatically, without polling. Mail that arrived while the
app was down is processed on reconnect, and a GreenMail outage never stops startup.

## What to Do

- Create `ClaimsInboxWatcher`. At startup it starts one dedicated thread, but only when `IntakeConfig.enabled()`.
  - **Start it after the scope-store registrar** (task 03): from a `@Startup` bean or a default-priority
    `StartupEvent` observer, never below the registrar's priority. A root first invoked before
    `AgenticScopePersister.setStore` never persists, for the life of the JVM (spike Q12, Q18).
  - **No agent calls in low-priority startup observers**, anywhere in the app. The watcher thread itself only
    calls the processor once it's running.

  The thread loops:
  1. Connect (retry with backoff on failure; log WARN, never throw to startup).
  2. Process every message currently in the INBOX, one at a time, through `ClaimEmailProcessor` (catch-up).
  3. Run IMAP `IDLE`, re-issuing it before the server timeout. On `MessageCountEvent`, process new messages one at a time.
  4. On a disconnect, go back to step 1.
- Stop cleanly on `ShutdownEvent`.
- A run that pauses at the human review step (task 07) returns immediately (the processor catches the
  suspension), so the watcher carries on with the next message. It never waits for a claims processor.
- The email is filed when its run finishes, **including when it pauses for review**: the processor moves it
  to the processed folder (or the failed folder on error) before the watcher picks the next message.
- Confirm that the pinned GreenMail version supports IDLE (it's registered in GreenMail's command factory on main). If it doesn't, fall back to a short NOOP-based check and record it in `PLAN.md`.
- **Tests (with a test profile that enables intake; mock `ClaimEmailProcessor`):**
  - a message delivered while watching is processed
  - messages present before start are processed (catch-up)
  - messages are processed one at a time
  - two emails, where the first pauses at review: the second is still processed, and the first is no longer in the INBOX
  - the watcher survives and reconnects after the IMAP connection drops
  - startup succeeds when GreenMail is unreachable
  - the watcher starts after the store registrar (the store is set when the first message is processed)

## Files/Areas

- `src/main/java/org/parasol/intake/ClaimsInboxWatcher.java` (new)
- `src/test/java/org/parasol/intake/` (new tests + profile)

## Key Points

- Intake is disabled in `%test` by default, so only the watcher tests turn it on.
- Use `io.quarkus.logging.Log`. Don't let the thread swallow `InterruptedException` without restoring the interrupt flag.
- The per-email root span is opened by the processor (task 08); watcher metrics and IMAP spans are added in task 11.
- One watcher thread, one replica: the agentic scope is cached in memory until eviction (task 03).

## Done When

- [ ] The watcher starts after the scope-store registrar, and no low-priority startup observer calls an agent.
- [ ] The watcher processes live and backlog mail one message at a time, and recovers from disconnects.
- [ ] A paused run's email is filed and doesn't block the next message.
- [ ] All the watcher tests listed above pass under `-Pollama`.
