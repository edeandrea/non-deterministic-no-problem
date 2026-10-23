# Task 09: IMAP IDLE Watcher

**Type:** Code Modification

## Goal

New mail in the claims INBOX is processed automatically, without polling. Mail that arrived while the
app was down is processed on reconnect, and a GreenMail outage never stops startup.

*Updated after the Flow verdict (task 01b, option b1) and the user's decision of 2026-10-07:* the watcher **doesn't
wait for runs**. It hands each email to `ClaimIntakeDispatcher` (task 08), which starts a run (or queues the email behind
the same sender's running run) and returns at once. There's no agentic scope store, so there's no startup-ordering rule.

**quarkus-flow 1.2.0 (2026-10-09):** adopted in task 06b. Nothing here depends on it directly. Check `PLAN.md` →
Execution Steps → 2a first.

## Planning decisions (2026-10-23, with the user)

Findings that shaped the plan:
- **No lost-message window between a scan and IDLE.** GreenMail 2.1.14's `IdleCommand` sends the session's pending
  unsolicited responses (an `EXISTS` for mail that arrived after the scan) as soon as IDLE starts, so `idle(true)` returns
  at once. RFC 3501 servers behave the same. Rescanning on every IDLE refresh is a safety net on top of that.
- **IDLE needs its own IMAP session.** Angus Mail 2.0.5 turns a socket read timeout during IDLE into a synthetic BYE
  (`IMAPProtocol.readIdleResponse`), which closes the folder. `ClaimsMailbox`'s session has a 10 s read timeout, so IDLE on
  it would drop every 10 s. The IDLE session's read timeout must be longer than the refresh interval (it still detects a
  dead connection).
- **Re-issuing IDLE:** `IMAPFolder.idle()` returns when another thread issues a command on the folder. A timer runs
  `folder.doCommand(protocol -> protocol.noop())` every `idle-refresh`, which ends the IDLE (DONE), and the loop rescans and
  idles again. RFC 2177 asks clients to re-issue IDLE at least every 29 minutes.

Decisions:
- **D1 Packages.** Both new types go in `org.parasol.intake.mailbox`: `InboxIdle` holds the IDLE connection, and
  `ClaimsInboxWatcher` runs the pipeline. Not the `org.parasol.intake` root: that package keeps only what every sub-package
  shares. The watcher doesn't call the starter; it fires an event (D7), so `mailbox` never depends on `dispatch`.
- **D2 Configurable timings.** A new `IntakeConfig.Watcher` group (`parasol.intake.watcher.*`): `idle-refresh` (default
  `1m`, `10s` in dev mode), `reconnect-delay` (first backoff, default `1s`, doubling) and `max-reconnect-delay` (default `1m`). The IDLE
  session's read timeout is derived from `idle-refresh` (twice it), not configured separately.
- **D3 One email at a time.** A new `ClaimsMailbox.moveOldest(MailFolder from, MailFolder to)` returns
  `Optional<InboundEmail>`. It copies the oldest message to the target, deletes and expunges the original, then parses it.
  Memory is bounded to one email even for a large backlog, the INBOX is read in arrival order (which per-sender order
  needs), and emails without a `Message-ID` need no special case. An email that can't be parsed is moved to `FAILED` and
  logged at ERROR, with no reply (there's no parsed sender), so it can't block the scan. A connection failure
  (`FolderClosedException`/`StoreClosedException`) is rethrown as `MailboxException` and the watcher reconnects.
- **D4 Recovery in place (changes the original plan).** Emails left in `processing` by a previous JVM are **submitted where
  they are**, oldest first, **before** the INBOX scan, instead of being moved back to the INBOX. They arrived before anything
  still in the INBOX, so moving them back would put them behind newer mail from the same sender. Recovery runs once per
  JVM, on the first successful connection only: after a reconnect, `processing` holds live runs' emails. If the database
  survived the restart, the starter sees the recorded `Message-ID` and files the email as a duplicate, as it would anyway.
- **D5 Tests share `IntakeTestProfile`** (no new profile, so no extra app boot; intake stays disabled). Each test builds
  its own `ClaimsInboxWatcher` with short timings, passing a mocked `Event<EmailReceived>` and verifying `fire(...)` (an
  `ArgumentCaptor` for order and contents). It can't be `@InjectMock ClaimIntakeDispatcher`: `@InjectMock` disables the observers of the
  mocked bean (quarkusio/quarkus#11122), so it would never see an event. The starter's one-line observer is covered by the intake tests. Network faults come from
  **Toxiproxy** (D6).
- **D6 Toxiproxy for network faults** (revised 2026-10-23: Testcontainers instead of Compose). WireMock can't do this: it
  only speaks HTTP. It can't proxy IMAP's raw TCP stream. Toxiproxy can:
  - `org.testcontainers:testcontainers-toxiproxy` (test scope, version from the Quarkus BOM; it brings `toxiproxy-java`).
    `ToxiproxyTestResource`, a `QuarkusTestResourceLifecycleManager`, starts `ghcr.io/shopify/toxiproxy:2.13.0` and injects a
    `Toxiproxy` helper into test fields annotated `@InjectToxiproxy` (no CDI bean, no config properties). `IntakeTestProfile.testResources()` registers it, so it
    starts with the profile's one boot and needs no extra profile or `@WithTestResource`.
  - GreenMail is a Compose container, so there's no shared network: the proxy's upstream is
    `host.testcontainers.internal:<parasol.mail.imap-port>` (the container gets that name as a `host-gateway` extra host).
    Verified under Podman.
  - `org.parasol.testing.mail.Toxiproxy` (test helper, on `ToxiproxyClient`): create the proxy, disable/enable it (refuses
    new connections and drops open ones) and add a `timeout` toxic (the connection stays open but goes silent).
  - The watcher under test connects its IDLE session through the proxy; `ClaimsMailbox` (scan and moves) still talks to
    GreenMail directly. A `ClaimsMailbox` failure goes through the same reconnect path.
  - Revisit when [quarkus-network-faults#14](https://github.com/quarkiverse/quarkus-network-faults/issues/14) (IMAP support,
    open) ships and the project is on a Quarkus version it supports: it could replace `ToxiproxyTestResource` and the helper.
  - Rejected: `quarkiverse/quarkus-network-faults` (targets Quarkus 4, only knows REST Client/JDBC/Kafka/LangChain4j
    and similar, no IMAP target) and a Compose Toxiproxy service (worked, but needed a test-only Compose profile and
    changes to the surefire/failsafe and `%test` config).

- **D7 The hand-over is a synchronous CDI event** (user decision, 2026-10-23: it keeps the watcher decoupled from the
  starter). It also answers where the watcher lives (D1): in `dispatch`, it would have to call
  `ClaimIntakeDispatcher.submit`, and putting it in `mailbox` with a direct call would make `mailbox` and `dispatch` depend
  on each other.
  - **The event:** `EmailReceived`, a record in `org.parasol.intake.mailbox` holding the `InboundEmail` (past tense, per
    the naming convention). The watcher fires it with `Event.fire` once the email is in `processing`.
  - **The observer:** `ClaimIntakeDispatcher.onEmailReceived(@Observes EmailReceived)` calls `submit(email)`. `submit` stays
    public, because `IntakeScenario` calls it directly.
  - **Why synchronous, not `fireAsync`:**
    - **Order (the deciding reason):** a sync observer runs on the watcher thread, so each sender's queue receives
      emails in arrival order. `fireAsync` hands each event to a separate executor task, so two emails from one sender could
      be queued in reverse order. That breaks the per-sender order task 08 relies on, and fixing it would need sequence
      numbers and re-sorting.
    - **Cost:** `submit` already never waits for a run. On the watcher thread it does the `Message-ID` check (a database
      insert) and queues the email. Only the rare rejected email adds more: an auto-reply or self-sent mail is moved to
      another folder over IMAP, and one without a `Message-ID` is also answered over SMTP. All of these have timeouts.
    - **Pacing:** the next email moves to `processing` only after the previous one is handed over; async would move the
      whole backlog at once.
    - **Errors:** an observer's exception comes back out of `fire()`. The watcher catches it for each email, logs it at
      ERROR with the `Message-ID` and carries on, so one bad email never drops the connection. With `fireAsync` it would
      only surface on a `CompletionStage`.
  - **No observer means nothing happens:** the email would sit in `processing` with no run. The starter is always a bean,
    so this only matters if it's ever made conditional.

## What to Do

- `InboxIdle` (`mailbox`, D1): opens its own IMAP session to the INBOX (D2's timeouts), `awaitChange()` blocks in
  `idle(true)` until the INBOX changes or the scheduled refresh job (Quarkus Scheduler) ends the IDLE, `close()` works from another thread (it ends a
  blocked IDLE). It holds no other state.
- `ClaimsMailbox.moveOldest(from, to)` (D3).
- `EmailReceived` (`mailbox`, D7) and `ClaimIntakeDispatcher.onEmailReceived` (`dispatch`).
- `ClaimsInboxWatcher` (`mailbox`, D1). At startup it subscribes to a Mutiny pipeline on a virtual thread (Quarkus's `@VirtualThreads` executor, from
  `quarkus-virtual-threads`, so a blocked IDLE holds no worker), but
  only when `IntakeConfig.enabled()` (a `StartupEvent` observer). Each session:
  1. Connect `InboxIdle` (backoff per D2 on failure; log WARN, never throw to startup).
  2. First successful connection only: fire `EmailReceived` for every email in `processing`, oldest first (D4).
  3. Catch-up: `moveOldest(INBOX, PROCESSING)` until empty, firing `EmailReceived` for each (D7). **The
     starter records the `Message-ID`** (task 08, D4) and skips duplicates; the watcher doesn't. The starter also handles
     auto-replies, mail from the claims address and mail with no `Message-ID`, so the watcher passes every email on
     without looking at it.
  4. `awaitChange()`, then back to step 3.
  5. On a `MessagingException`/`MailboxException`, close the connection and go back to step 1.
- Stop cleanly on `ShutdownEvent`: set the stop flag, cancel the subscription (that ends a backoff or reconnect pause at once),
  close `InboxIdle` (which ends a blocked IDLE) and wait, with a timeout, for the work in progress. Dev mode's live reload goes through this, and the old watcher must not keep running.
- **One watcher, no pool.** One IMAP IDLE connection notices new mail; several watchers on one INBOX would race for the
  same message. The parallelism comes from Flow (`instance.start()` is asynchronous, on Quarkus's managed executor),
  whose size caps how many runs go at once.
- **Moving to `processing` is what stops a second pickup:** the email leaves the INBOX before its run starts. The run's
  `file` step moves it to processed, or the failure listener to failed (task 08).
- GreenMail 2.1.14 advertises `IDLE` (and `MOVE`, `UIDPLUS`) in its capabilities (task 03), so no NOOP fallback is needed.
- **Tests** (D5, D6; in the shared `IntakeTestProfile`, each with its own watcher and `@InjectMock ClaimIntakeDispatcher`):
  - a message delivered while watching is processed
  - messages present before start are processed (catch-up), in arrival order
  - the watcher doesn't wait: several emails are handed over without waiting for runs
  - an exception from the observer is logged and the next email is still handed over (D7)
  - a picked-up email is in `processing` (and not in the INBOX) when it's handed over, and is never handed over twice, even
    across IDLE refreshes
  - emails left in `processing` at startup are handed over in place, before the INBOX backlog (D4)
  - an unparseable email goes to `FAILED` and the next one is still processed (D3)
  - the watcher reconnects after the connection drops (Toxiproxy disabled, then enabled) and after it goes silent (the
    `timeout` toxic), and mail delivered meanwhile is processed
  - start returns at once when GreenMail is unreachable (Toxiproxy disabled), and the backlog is processed once it's back
  - shutdown stops the watching, including while it's in a backoff pause
  - with `parasol.intake.enabled=false` nothing is started (unit test of the startup observer)

## Files/Areas

- `src/main/java/org/parasol/intake/mailbox/InboxIdle.java` (new)
- `src/main/java/org/parasol/intake/mailbox/ClaimsMailbox.java` (`moveOldest`)
- `src/main/java/org/parasol/intake/mailbox/ClaimsInboxWatcher.java` and `EmailReceived.java` (new)
- `src/main/java/org/parasol/intake/dispatch/ClaimIntakeDispatcher.java` (`onEmailReceived`)
- `src/main/java/org/parasol/intake/config/IntakeConfig.java` (`Watcher` group) and `application.yml`
- `pom.xml` (`testcontainers-toxiproxy`)
- `src/test/java/org/parasol/testing/mail/Toxiproxy.java` and `ToxiproxyTestResource.java` (new), `IntakeTestProfile`, the watcher and mailbox tests
- Docs: CLAUDE.md (package table: `mailbox` gains the watcher and `EmailReceived`, `dispatch`'s starter observes it;
  Commands → mail stack; Testing), README if it describes the mail stack

## Key Points

- Intake is disabled in `%test` by default, and stays disabled: the tests build their own watcher (D5).
- Use `io.quarkus.logging.Log`. Restore the interrupt flag if `stop` is interrupted while it waits.
- No observability code here: the starter enters the conversation id (task 08); IMAP spans and watcher metrics are
  task 11's.
- **Single replica.** Superseding a waiting review uses `activeInstance(id)`, which only finds runs in this JVM, and the
  per-sender queue is in memory.

## Done When

- [x] The watcher hands over live and backlog mail without waiting for runs, never twice, and recovers from disconnects
      and silent connections.
- [x] Emails left in `processing` at startup are handed over in place, before the INBOX backlog.
- [x] All the watcher tests listed above pass under the default profile and `-Pollama`.
