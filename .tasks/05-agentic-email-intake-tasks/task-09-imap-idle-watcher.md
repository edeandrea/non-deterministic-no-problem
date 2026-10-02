# Task 09: IMAP IDLE Watcher

**Type:** Code Modification

## Goal

New mail in the claims INBOX is processed automatically, without polling. Mail that arrived while the
app was down is processed on reconnect, and a GreenMail outage never stops startup.

## What to Do

- Create `ClaimsInboxWatcher`. At startup it starts one dedicated thread, but only when `IntakeConfig.enabled()`.
  The thread loops:
  1. Connect (retry with backoff on failure; log WARN, never throw to startup).
  2. Process every message currently in the INBOX, one at a time, through `ClaimEmailProcessor` (catch-up).
  3. Run IMAP `IDLE`, re-issuing it before the server timeout. On `MessageCountEvent`, process new messages one at a time.
  4. On a disconnect, go back to step 1.
- Stop cleanly on `ShutdownEvent`.
- A run that suspends at the human review step (task 07) returns immediately, so the watcher carries
  on with the next message. It never waits for a claims processor.
- Confirm that the pinned GreenMail version supports IDLE (it's registered in GreenMail's command factory on main). If it doesn't, fall back to a short NOOP-based check and record it in `PLAN.md`.
- **Tests (with a test profile that enables intake; mock `ClaimEmailProcessor`):**
  - a message delivered while watching is processed
  - messages present before start are processed (catch-up)
  - messages are processed one at a time
  - two emails, where the first suspends at review: the second is still processed
  - the watcher survives and reconnects after the IMAP connection drops
  - startup succeeds when GreenMail is unreachable

## Files/Areas

- `src/main/java/org/parasol/intake/ClaimsInboxWatcher.java` (new)
- `src/test/java/org/parasol/intake/` (new tests + profile)

## Key Points

- Intake is disabled in `%test` by default, so only the watcher tests turn it on.
- Use `io.quarkus.logging.Log`. Don't let the thread swallow `InterruptedException` without restoring the interrupt flag.
- Per-email root span and watcher metrics are added in task 11.

## Done When

- [ ] The watcher processes live and backlog mail one message at a time, and recovers from disconnects.
- [ ] All the watcher tests listed above pass under `-Pollama`.