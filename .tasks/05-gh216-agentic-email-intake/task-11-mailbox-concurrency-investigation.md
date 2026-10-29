# Task 11 — GreenMail mailbox concurrency investigation

## Scope

GreenMail **is the server used by this application**, not a substitute for another production server. This investigation targets
`docker.io/greenmail/standalone:2.1.15`, the image in `compose-devservices.yml`. There is no other-server comparison and no
recommendation to disable exclusion outside tests.

No production locking changes were made by this investigation. During the investigation, the working-tree implementation changed
from a fair exclusive `ReentrantLock` to a fair `ReentrantReadWriteLock`. The retained diagnostic now exercises that current
read/write policy as `CURRENT` and retains the old exclusive behavior as `EXCLUSIVE`. The historical tables below identify
`CURRENT` as the exclusive policy that existed when those measurements ran.

## Diagnostic

`src/test/java/org/parasol/intake/mailbox/ClaimsMailboxConcurrencyTests.java` is a plain JUnit diagnostic, explicitly enabled with
`-Dparasol.mail.stress=true`. It exercises the actual `ClaimsMailbox` method bodies and MIME parser without starting the unrelated
application, database, LLM, or telemetry services.

The fixture sends eight distinct messages over SMTP to a unique mailbox per round and checks delivery and exact Message-ID
preservation **before concurrency starts**. Eight virtual-thread workers rendezvous before the workload. After successful workers
finish, the test checks the exact expected IDs in INBOX and Processed, with no duplicates, missing messages, or leftovers in
Processing or Failed. Each round has a shared 60-second worker deadline and bounded executor shutdown. Full exception causes,
active worker stacks, operation durations, and maximum simultaneous mailbox calls are written to a result file.

The test substitutes only the private lock on a fresh, directly constructed test mailbox using reflection. It does not mutate a
CDI singleton, remove production exclusion, replace mail operations with mocks, or change the connection/close/expunge sequence.
The seam deliberately fails if the production lock field changes. `CURRENT` wraps the real production lock.

| Strategy | Behavior |
|---|---|
| `CURRENT` | Actual production read/write lock; reads overlap, mutations are exclusive |
| `EXCLUSIVE` | Fair exclusive lock; every operation excludes all others |
| `NONE` | No exclusion |
| `READ_WRITE` | Concurrent reads; every mutation excludes reads and other mutations |
| `MOVES_ONLY` | Mutations exclude each other but can overlap reads |
| `LIMIT_2` | Fair two-permit semaphore across all operations |

| Workload | Eight workers do |
|---|---|
| `READS` | Each lists INBOX, then finds its own stable message |
| `MOVES` | Each moves its own distinct message INBOX → Processing → Processed |
| `MIXED` | Four list/find their stable messages while four move different messages |
| `WORKFLOW` | Each finds its message, moves it, finds it in Processing, then files it |
| `OLDEST` | Each takes `moveOldest`, then files the returned message |

Every workload is run both with destination folders precreated and with folders created on demand. `OLDEST` deliberately stresses
concurrent claiming more heavily than the single inbox watcher does; its duplicate-claim failures must not be attributed solely
to a GreenMail defect.

## Measured evidence

Runs used Java 25.0.4, Angus Mail 2.0.5, and the pinned GreenMail container on local Podman. The first complete matrix (before the production policy changed) used four
workloads (`READS`, `MOVES`, `MIXED`, `WORKFLOW`), two folder conditions, and one round per combination:

| Strategy | Passing / completed rounds | Observed failure |
|---|---:|---|
| `CURRENT` | 8 / 8 | None |
| `READ_WRITE` | 8 / 8 | None |
| `NONE` | 2 / 8 | Both read-only rounds passed; all mutation/mixed/workflow rounds failed |
| `MOVES_ONLY` | 6 / 8 | Both mixed rounds failed while reading messages |
| `LIMIT_2` | 7 / 8 | Mixed round failed while reading a message |

The two viable strategies were then repeated across **all five workloads**, both folder conditions, and ten rounds per condition:

| Strategy | Passing / completed rounds | Worker mailbox calls | Maximum overlapping calls |
|---|---:|---:|---:|
| `CURRENT` | 100 / 100 | 1,920 | 1 |
| `READ_WRITE` | 100 / 100 | 1,920 | 8 |

These are rounds, not 200 JUnit methods: the repeated run contained 20 parameterized invocations, each running ten rounds.
Exclusive rounds had a median elapsed time of 405.5 ms and maximum 2,074 ms; read/write rounds had a median of 378 ms and maximum
4,854 ms. Timings include final integrity reads, exclude seeding, and mix different workloads. They are **not** a controlled
throughput benchmark and do not establish a meaningful performance gain. Per-workload medians from the same repeated runs:

| Workload (20 rounds per policy) | Exclusive median | Read/write median |
|---|---:|---:|
| Reads only | 1,091.5 ms | 372.5 ms |
| Moves only | 325.5 ms | 323 ms |
| Mixed | 483 ms | 365 ms |
| Workflow | 414 ms | 455 ms |
| Oldest-message claiming | 308.5 ms | 444.5 ms |

Read-heavy cases benefited in these measurements; other cases did not consistently benefit. Policies were run sequentially,
not interleaved, and no representative arrival-rate/latency benchmark was performed.

A subsequent negative-control run used the strengthened exception capture, `MOVES`, `MIXED`, and `OLDEST`, both folder conditions,
and one round per combination:

| Strategy | Passing / completed rounds | Observation |
|---|---:|---|
| `NONE` | 0 / 6 | Worker failures or message-accounting failures |
| `MOVES_ONLY` | 4 / 6 | Both mixed workloads failed |
| `LIMIT_2` | 1 / 6 | Mixed failures; concurrent oldest-message claims left messages unprocessed |

Thus the assertions demonstrably fail when exclusion is removed or narrowed to moves only; they do not merely count successful
method returns.

### Actual failure signatures

Captured client exceptions include:

- `jakarta.mail.FolderClosedException: * BYE Jakarta Mail Exception: java.io.IOException: Connection dropped by server?`
  while copying messages during concurrent distinct-message moves.
- `jakarta.mail.MessageRemovedException` and `jakarta.mail.MessagingException: Unable to load BODYSTRUCTURE` during `list`
  while another worker moves and expunges messages.
- `NO STORE failed. No such message with uid 6 in folder INBOX` during a move.
- A post-move `find` returning empty in the unlocked workflow workload.

GreenMail's own log also records `FolderException: No such message with uid ... in folder INBOX` in `StoreCommand` and
`FetchCommand`, and concurrent `CreateCommand` warnings for a destination that already exists.

The container's **stderr**, retrieved with its full logs during cleanup, additionally exposes server-side handler crashes:

```text
java.lang.IllegalStateException: Can not handle IMAP connection
Caused by: java.lang.IllegalStateException: Need to do this properly...
    at com.icegreen.greenmail.imap.ImapSessionFolder.getExpunged(ImapSessionFolder.java:94)
    at com.icegreen.greenmail.imap.ImapSessionImpl.expungeResponses(ImapSessionImpl.java:100)
    at com.icegreen.greenmail.imap.ImapSessionImpl.unsolicitedResponses(ImapSessionImpl.java:69)
    at com.icegreen.greenmail.imap.ImapSessionImpl.unsolicitedResponses(ImapSessionImpl.java:50)
    at com.icegreen.greenmail.imap.commands.CopyCommand.doProcess(CopyCommand.java:81)
```

These are excerpted frames from actual output, not a reconstructed stack. Another captured handler crash has a
`NullPointerException` in `HierarchicalFolder.copyMessage:413` because `originalMessage` is null. The shared-container log
aggregates several policy runs, so that second crash is **not attributed to a specific workload**. The `getExpunged` crashes
establish a server-side failure handling concurrent copy/expunge notifications, consistent with the observed connection drops;
they do not prove the original long-running stall was this exact failure. When collecting future container logs, redirect both
stdout and stderr; redirecting only stdout omits these uncaught thread exceptions.

Precreating folders did **not** eliminate concurrent-move or mixed-workload failures. Concurrent folder creation is therefore not
the sole explanation. A `list` retrieves live messages and lazily parses them across IMAP round trips; another connection can
expunge those messages before parsing completes. That is a real limitation of the current read/move interaction, not evidence
that every observed exception is a GreenMail implementation bug.

## Conclusions and limits

- **Policy assessment:** both an exclusive lock and a fair read/write lock are supported by these runs. A fair
  read/write lock is reasonable when concurrent reads are worth allowing; it preserves read/mutation and mutation/mutation
  exclusion while admitting read/read overlap. The exclusive lock remains the simpler conservative option. Neither was applied
  by this investigation; the current working tree now uses read/write exclusion.
- A read/write split is an experimentally viable alternative for the tested workloads, not a guess anymore. It permits concurrent
  reads but still requires excluding reads during mutations. The tests do not establish that changing it is worthwhile.
- Moves-only exclusion and a small semaphore are **not sufficient** for the current implementation.
- The original intermittent long-running move stall has **not** been isolated as a server deadlock. This harness reproduced
  concrete transport, expunge/read, and message-accounting failures. Those are evidence for exclusion, but are not interchangeable
  with proof of the original stall's root cause.
- Initial read-heavy attempts used four list/find passes per worker and a 20-second deadline. Even exclusive operation exceeded
  that budget under local load; those timeouts were not treated as proof of the reported race. The retained test uses one pass
  by default and a 60-second deadline. Setup bugs in the first probes (unexpanded config expressions and duration conversion)
  were corrected before the reported matrices; those invalid runs are excluded.
- The harness does not reproduce the full Flow application or an active IDLE session, and does not exercise attachments, external
  mailbox clients, multiple application replicas, or `moveWithoutMessageId`. Its lock is process-local, just like production.
- Repeated passes reduce uncertainty; they do not prove the absence of all intermittent failures.

### StampedLock assessment (not benchmarked)

Recommendation: retain the current fair `ReentrantReadWriteLock`; this assessment does not record an additional user policy decision.

- An optimistic stamp does not exclude a mutation. IMAP reads fetch live message data over multiple server round trips; a
  concurrent expunge can fail the fetch before validation. Validation and retry do not turn those operations into a safe
  in-memory snapshot. Optimistic mode is therefore not a drop-in replacement for the read lock.
- Ordinary `StampedLock.readLock()` / `writeLock()` would preserve the read/write exclusion shape, but provide no demonstrated
  performance benefit here. `StampedLock` is non-reentrant and offers no consistent reader/writer preference or fair acquisition
  policy. Those trade-offs add complexity without addressing the observed IMAP failures.
- No `StampedLock` variant was implemented or benchmarked. The measured comparisons above cover only the named harness strategies.

## Reproduce

Normal guarded policy (the diagnostic starts and stops its own isolated GreenMail container):

```sh
./mvnw -B -Pollama test -Dtest=ClaimsMailboxConcurrencyTests \
  -Dquarkus.quinoa.enabled=false -Dparasol.mail.stress=true
```

Compare policies; failures are expected for unsafe variants:

```sh
./mvnw -B -Pollama test -Dtest=ClaimsMailboxConcurrencyTests \
  -Dquarkus.quinoa.enabled=false -Dparasol.mail.stress=true \
  -Dparasol.mail.stress.strategies=CURRENT,EXCLUSIVE,NONE,READ_WRITE,MOVES_ONLY,LIMIT_2 \
  -Dparasol.mail.stress.rounds=10
```

Other controls:

- `parasol.mail.stress.workloads`: comma-separated workload enum values (all five by default).
- `parasol.mail.stress.precreated`: `true,false` by default.
- `parasol.mail.stress.reads`: list/find passes per reader, one by default.
- `parasol.mail.stress.rounds`: five by default.
- `parasol.mail.stress.results`: result directory, `target/imap-concurrency` by default.
- Supply **both** `parasol.mail.stress.imap-port` and `parasol.mail.stress.smtp-port` to use an existing isolated server;
  `parasol.mail.stress.host` defaults to `localhost`. Unique users are left there for diagnosis; do not point this at a shared
  application mailbox or assume it purges another test's fixtures.

This is opt-in and does not yet run as an automatic CI guard. Promoting the current-policy workload into the existing
`ClaimsMailboxTests` fixture is a separate integration choice; retaining an intentionally failing policy matrix in ordinary CI
would be inappropriate.

## Evidence artifacts

Local raw artifacts (the scratch directory is temporary):

- `/Users/edeandre/.hermes/cache/scratch/imap-stress-matrix-final.log`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-matrix-final/`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-repeated.log`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-repeated/`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-negative-controls.log`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-negative-controls/`
- `/Users/edeandre/.hermes/cache/scratch/imap-stress-server-recent.log`

## Final verification

The final source was exercised through the Maven wrapper (not only the standalone JUnit launcher):

| Run | JUnit invocations | Completed rounds | Result |
|---|---:|---:|---|
| Default profile, `CURRENT,READ_WRITE` | 20 | 20 | Passed |
| `-Pollama`, `CURRENT,READ_WRITE` | 20 | 20 | Passed |
| `-Pollama-openai`, `CURRENT,READ_WRITE` | 20 | 20 | Passed |
| Self-managed GreenMail startup/log capture/shutdown, `CURRENT/MOVES` | 1 | 1 | Passed |

Each profile run used all five workloads and both folder conditions, one round each. The self-managed test started the pinned
image with Testcontainers, captured its log, and removed its container on completion. The earlier two-round Maven verification
also passed 20 invocations / 40 rounds. No full application suite or production-lock modification was performed.

Final log paths follow `/Users/edeandre/.hermes/cache/scratch/imap-stress-{default-final,ollama-final,ollama-openai-final,self-managed}.log`.

`git diff --check HEAD` passed. IntelliJ reported no errors and four warnings:

| Warning | Disposition |
|---|---|
| Container not in try-with-resources | Lifecycle spans JUnit invocations; `@AfterAll` stops it in `finally`, even if log capture fails |
| Executor not in try-with-resources | Intentional: `close()` waits indefinitely; explicit shutdown is bounded and asserted |
| Two inherited `@NotNull` hints on unused `Lock` methods | IDE hints; those methods intentionally throw and are never used by the mailbox |

## Independent review follow-up

The independent review identified gaps in the earlier diagnostic. The retained source now:

- The **initial revision** substituted only `Lock`; after the production field changed to `ReadWriteLock`, the test seam was adapted
  to that interface. Final checks below cover the actual working-tree read/write policy, not just a hand-written alternative.
- Checks every returned message has a known ID and the expected distinct sender, subject, and body, including `find` and
  `moveOldest` results. List assertions require all permanently resident messages and allow in-flight moved messages to disappear;
  final folder checks require exact IDs/multiplicities. ID-less messages cannot disappear through Optional flattening.
- Preserves the original failure if diagnostic writing or shutdown fails; after nontermination, later parameterized scenarios
  are aborted rather than running against a possibly contaminated server.
- Requires observed overlap for policies/workloads intended to permit it, and checks exclusive/two-permit concurrency limits.
- Uses fixture IMAP/SMTP socket timeouts and stops the owned container in `finally` even when log capture fails. These two
  corrections were already in place before the review result arrived.

The five-minute JUnit invocation timeout covers setup and final verification as well as workers, but interruption is **not a
hard process-kill guarantee**. Production lock acquisition remains uninterruptible. For a genuinely hard deadline, run the
Maven invocation under an external process supervisor and discard/restart the diagnostic JVM/server after a timeout. No original
stall or server-deadlock guarantee is claimed. Failure files retain full causes so expunge/removal, transport, timeout, and
integrity failures can be distinguished; their summary remains PASS/FAIL rather than assigning speculative root causes.

The tables above describe the earlier tested revision. Post-review verification of the exclusive-era source passed 20 cases
under `-Pollama`, including the stronger payload/identity oracle. Log: `/Users/edeandre/.hermes/cache/scratch/imap-stress-reviewed.log`.

After the working-tree production policy changed to `ReadWriteLock`, the diagnostic seam was updated to implement that interface
and delegate `CURRENT` to the actual production read/write locks. `EXCLUSIVE` now names the historical exclusive policy explicitly.
The first verification after this adaptation completed 29 of 30 scenarios successfully; one mixed scenario had correct results
but no observed read overlap, so the strict overlap assertion failed. The diagnostic now writes `INCONCLUSIVE` and aborts such
an invocation instead of calling it a mailbox failure or a concurrency pass. If this happens, repeat the named scenario.

The diagnostic's reflection seam intentionally caught a subsequent production-field rename from `lock` to `folderLock`
(`NoSuchFieldException`, before any workload ran). The test was updated to the new name without touching production code; those
setup errors are not counted as mailbox concurrency failures.

A scratch mutation probe also verified the strengthened oracle itself: a valid message was accepted, while a corrupted body,
an extra ID-less message, and a `find` result with the wrong identity were all rejected (3 of 3 mutations). The probe is at
`/Users/edeandre/.hermes/cache/scratch/ImapOracleProbe.java`.

Post-review IntelliJ inspections report no errors and eight warnings: the two documented resource-lifecycle warnings, four
inherited nullability hints, an Optional parameter in the assertion helper, and throwing a cleanup failure from `finally`.
The cleanup throw is conditional on there being no primary failure; otherwise it is suppressed on the original exception.
The Optional parameter intentionally asserts the mailbox's return contract. These warnings do not indicate a mailbox failure.

### Final adaptation verification

The retained source uses the current `folderLock` field and compares actual production `CURRENT`, explicit `EXCLUSIVE`, and
independent `READ_WRITE` variants, with all five workloads and both folder conditions:

| Profile | Passed | Inconclusive/skipped | Failures/errors |
|---|---:|---:|---:|
| Default | 30 | 0 | 0 |
| `ollama` | 29 | 1 | 0 |
| `ollama-openai` | 30 | 0 | 0 |

The inconclusive case was a correct mixed workload whose scheduling produced no read/read overlap. It is not included as a
concurrency pass. All three profile builds succeeded: 89 passes and one inconclusive invocation across 90 selected cases.

The final mixed-workload negative controls (`NONE`, `MOVES_ONLY`, `LIMIT_2`, each with and without precreated folders) failed all
six cases as expected, with real worker exceptions during concurrent message parsing. The negative-control Maven build therefore
exited 1; this is the intentionally failing diagnostic matrix, not a failure of the retained production policy.

Logs: `/Users/edeandre/.hermes/cache/scratch/imap-stress-final-folderlock-<profile>.log`; negative controls use `negative` as the suffix.
No full application suite was run. The retained diagnostic remains opt-in.
