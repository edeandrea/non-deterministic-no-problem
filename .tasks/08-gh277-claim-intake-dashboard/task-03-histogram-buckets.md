# Task 03: Give the Intake's Timers and Summaries Explicit Buckets

**Type:** Implementation

## Goal

Every `claim.intake.*` timer and distribution summary exports bucket boundaries that fit its range, so a p50/p95 panel
means something (finding 3).

## What to Do

### 1. `IntakeMetrics` — `serviceLevelObjectives(...)` on each builder

Small fixed sets, chosen for the meter's real range (adjust after looking at task 02's data):

| Meter (builder, line as of `f4eeff7`) | Kind | Suggested boundaries |
|---|---|---|
| `email.latency` (:97) | Timer | 1 s, 5 s, 15 s, 30 s, 1 min, 2 min, 5 min, 10 min, 30 min |
| `email.size` (:105) | Summary, bytes | 4 KiB, 16 KiB, 64 KiB, 256 KiB, 1 MiB, 4 MiB, 16 MiB |
| `extraction.completeness` (:126) | Summary, 0..1 | 0.2, 0.4, 0.6, 0.8, 1.0 |
| `claim.emails` (:136) | Summary | 1, 2, 3, 5, 8 |
| `claim.time.to.complete` (:141) | Timer | 1 min, 5 min, 15 min, 1 h, 4 h, 1 d, 3 d, 7 d |
| `mail.client.duration` (:148) | Timer | 5, 10, 25, 50, 100, 250, 500 ms, 1 s, 5 s |
| `mailbox.lock.wait` (:156) | Timer | 1, 5, 10, 50, 100, 500 ms, 1 s, 5 s |
| `review.wait` (:184) | Timer | 1 min, 5 min, 15 min, 1 h, 4 h, 1 d, 3 d, 7 d |

Keep the boundaries as named constants next to the builders, so the tests and the code share them.

### 2. Tests — `IntakeMetricsTests`

For each meter above, after recording a value: `takeSnapshot().histogramCounts()` lists exactly the configured
boundaries. One parameterized test is enough.

### 3. Confirm the export

In dev (task 01's setup), check the `_bucket` series' `le` values match the boundaries, in the exported unit
(task 02 recorded whether timers export in ms or s).

## Files/Areas

- `src/main/java/org/parasol/intake/observability/IntakeMetrics.java`
- `src/test/java/org/parasol/intake/observability/IntakeMetricsTests.java`

## Key Points

- `serviceLevelObjectives` only, not `publishPercentileHistogram()`: the bridge would then export Micrometer's ~70
  default buckets per series, and these meters carry tags.
- Don't change names, tags or descriptions: the meter schema test and task 02's inventory depend on them.
- The bridge only exports explicit boundaries when the meter has them (`setExplicitBucketsIfConfigured`); verify in dev
  rather than assuming.
- The tests use `SimpleMeterRegistry` (`TestMeterRegistry`), which honors SLOs in its snapshot.

## Done When

- [ ] All eight builders have explicit boundaries.
- [ ] A test asserts each meter's boundaries.
- [ ] Dev's `_bucket` series carry those boundaries.
- [ ] `./mvnw test -Dtest=IntakeMetricsTests` passes.
