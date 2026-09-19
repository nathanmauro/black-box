# Preserve session activity across delayed capture

Status: implemented, independently reviewed, integrated and deployed locally; pull-request review is separate.

## Problem and scope

Actual legacy HTTP against a disposable SQLite application reproduces the defect: a newer
12:00:00.000000001Z event followed by an older 11:00:00Z event leaves `lastSeenAt` at 11:00,
with both events correctly counted. Queued delivery must not move latest activity backward.
The baseline log is a local verification artifact, not production data.

This slice changes only latest session activity during canonical event persistence. Keep original
event timestamps, insertion-time `started_at` semantics, title/lineage behavior, and idempotent
replay unchanged. No global historical rewrite, schema change, hook activation or live mutation.

## Frozen acceptance

- Newer then older legacy and keyed HTTP captures retain the latest `lastSeenAt` and both events.
- Compare true instants, including whole seconds, fractional precision and adjacent nanoseconds;
  lexical timestamp comparison and millisecond rounding are insufficient.
- Concurrent distinct captures converge on the greatest instant and correct event count.
- Replaying a keyed old capture keeps its original identity and does not change session state.
- Verify actual HTTP/database behavior on SQLite and disposable PostgreSQL, relevant regression,
  fresh independent review, and `git diff --check` before coordinator integration.

## Implementation approach

The existing atomic session upsert obtains the database write/row lock before reading the session.
Preserve its existing latest timestamp on conflict, compare parsed `Instant` values inside that
same transaction, and update count/latest activity together with the event. Do not introduce a
Java process-local lock or assume lexicographic ordering of variable-precision ISO strings.

## Results

The frozen SQLite HTTP regression failed before implementation with expected
`2026-09-19T12:00:00.000000001Z`, actual `2026-09-19T11:00:00Z`. After the narrow lock-preserving
change, the selected SQLite HTTP, PostgreSQL HTTP, migration and ingestion suites passed 41 tests,
zero failures/errors/skips. These include delayed legacy/keyed captures, four precision boundaries,
eight concurrent distinct events per endpoint/engine, event count/original timestamps, and exact
session-state stability under keyed replay. Existing idempotency, rollback and migration checks also
passed. Fresh review verified transaction assumptions and requested an explicit documentation
boundary: existing historically wrong checkpoints are not repaired. That clarification is included.

Local verification logs: `/tmp/blackbox-chronology-red.log` and
`/tmp/blackbox-chronology-green.log` (not committed). No live data or service was changed.
Root owns this isolated checkout and Git integration. Full combined regression follows integration.

Coordinator closure: final combined regression passed 637 tests with zero failures/errors and three intentional skips. The additive server candidate was deployed locally after binary/database compatibility proof; existing historical timestamps were not rewritten.
