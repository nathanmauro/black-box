# Durable append-order stream cursors

## Reproduction

The current stream identifies frames by caller-declared observedAt and a random event ID.
A subscriber receiving 12:00|z, disconnecting, then reconnecting after a newly committed
11:59 event or 12:00|a event receives neither. A disposable SQLite reproduction using the
actual StreamReplayRepository returned an empty replay while all three events existed.

## Safety contract

- Preserve event payloads, observed timestamps, receipt identity and the SQLite default.
- Add only event_stream_state and event_stream_positions. Update a singleton counter inside
  the existing canonical event transaction; its PostgreSQL row lock lasts until commit.
  A raw sequence is insufficient. Receipt replay allocates no position.
- Initialize state and backfill existing events atomically before accepting requests, assigning
  stable historical positions once. Historical commit chronology is unavailable. Retain compact
  event-ID anchors even if an operator later removes a payload; positions are never reused.
- Version cursors with generation, position and event identity. Invalid, legacy, foreign or
  restored-away anchors signal replay.reset and a fresh checkpoint, requiring canonical refresh.
- Publication callbacks are wake-ups. Drain persisted positions in order under a subscriber lock.
  Register before draining; checkpoint empty streams; heartbeat also catches up committed writes
  whose optional publication failed. No callback can advance beyond an unseen earlier commit.
- Process at most 2,000 positions plus one look-ahead per drain. Skip absent payloads while
  advancing the checkpoint. Additional rows signal replay.more and close for native reconnect.
- since remains an observed-time filter when supplied, including resumed pages; append order
  governs delivery. Default browser streams have no filter. Session readers refresh on reconnect
  and reset; frame JSON fields remain compatible.
- Native backups preserve both new tables. Startup never renumbers existing positions or resets
  the counter downward. No production database, service, cloud or provider calls in this work.

## Implementation and acceptance

1. Add schema/state migration and transactional position allocation.
2. Replace stream timestamp continuation with validated durable cursors and ordered DB drains.
3. Add frontend reconnect/reset invalidation and document cursor/retention compatibility.
4. Test late/tied timestamps; empty-stream disconnect; legacy/invalid/reset cursors; restart;
   bounded paging; callback reversal; idempotent replay; SQLite and PostgreSQL concurrent commits
   and rollback; native backup/restore table preservation; browser session refresh.
5. Measure one disposable moderate legacy migration if practical, run scoped formatting and
   targeted suites, obtain fresh read-only review, then full relevant verification.

Only isolated fixtures are mutated. Coordinator owns Git, publication, merge and live acceptance.

## Verification record

- Reproduced the original observed-time cursor omission using the actual repository query and
  isolated SQLite rows before implementation. Late and tied captures remained canonical but
  disappeared from replay.
- Full `mvn -B test` with the disposable PostgreSQL environment and native PostgreSQL 16 clients:
  597 tests, zero failures/errors, four unrelated optional/environment skips. PostgreSQL backend
  (16), authenticated consumer (4), native restore (2), and append-order concurrency (2) contracts
  all ran without skips. Restore comparisons now include both stream tables.
- `npm --prefix frontend test`: 622 tests passed. `npm --prefix frontend run check`: typechecking
  and formatting passed, zero lint errors and the existing 70 warnings. Scoped Palantir check
  passed for all 13 changed Java files; `git diff --check` passed.
- Packaged Chromium `stream-reconnect.spec.ts` passed with a real TCP stream disconnect, native
  EventSource reconnect/Last-Event-ID, a backdated HTTP capture while disconnected, and visible
  transcript catch-up. The final run used a clean environment, packaged configuration, disabled
  model providers and temporary SQLite storage. Temporary storage and proxy were removed; the
  protected local-service listener PID was unchanged. No production database was queried or changed.
- A review identified two reset-cache hazards: companion snapshot merging and session-reader
  search/pagination state could retain removed data after restore. Both now clear on replay.reset
  and reject older in-flight reads; regression tests cover late resolution of those reads.
- A recent-time initial subscription over 20,000 old fixture rows originally consumed ten excluded
  pages before reaching current captures. It now seeks conservatively to the first possible
  matching append position, retains exact nanosecond filtering, and caps the seek at its captured
  high-water. The measured SQLite backfill was 17 ms and initial seek 10 ms in one local fixture
  run; these are observations, not performance guarantees. Whole-second, nanosecond, extended-year,
  no-match and uncommitted-append cases pass on the applicable SQLite/PostgreSQL fixtures.

Source-only handoff: Codex changed the additive persistence/stream path, reset/catch-up clients,
regressions and repository documentation in the isolated implementation checkout. No Git,
publication or running-service change was performed. The coordinator owns final integration,
regeneration of the combined frontend bundle, publication and any later deployment. Multiple API
replicas, direct SQL writers, position retention, live restore and managed-proxy behavior remain
outside this contract.

## Coordinator integration verification

Integrated merged PR58/59 with current main and regenerated the static bundle from combined sources.
All 650 frontend tests and check gates passed (70 preexisting lint warnings). The entire packaged
Chromium suite passed: 37 journeys, including native TCP reconnect, exact fixture persistence,
Recall replacements, Browse and editor behavior. Fixture directories and port 8799 were cleaned;
the existing local service PID stayed unchanged. Backend production/test sources are unchanged
from the worker's 597-test run with all PostgreSQL gates enabled. No live deployment was performed.
