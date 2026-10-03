# Session and project chronology

## Contract

Session lists and project first/last activity must compare actual UTC instants, including nanosecond
fractions and extended years, while returning the original stored timestamps. `started_at` remains
the first captured event's time, not the minimum timestamp of delayed input. Preserve scope aliases,
parent/human filters, counts, and the existing missing-summary selection. Equal session times use
session ID descending for deterministic ordering.

No canonical data migration, provider call, or live database change. Use the shared `SqlInstant`
comparison helper from the separate event chronology change. The coordinator owns its integration
and all Git/publication work.

## Steps

1. Reproduce with real POST capture and GET session/project routes on a disposable SQLite database;
   exercise PostgreSQL only in an explicitly opted-in random schema.
2. Compare session ordering and project extrema with shared nanosecond-preserving keys. Include
   saved meld timestamps in the same project-summary aggregation; retain original response values.
3. Verify public HTTP lists, limit selection, deterministic ties, alias project ranking, unchanged
   session endpoint semantics, repository-only selection paths, and stored timestamp bytes.
4. Run focused affected tests, formatting and diff checks. Record measured results below.

## Verification

Before production changes, the actual HTTP fixture failed six of eight cases on SQLite and
PostgreSQL: session order/limit, project session extrema/alias ranking, and saved-meld extrema.
Both first-capture/latest-seen preservation cases already passed. Saved melds were created through
HTTP with a captured source session; only their synthetic fixture times were adjusted directly
because the public save route uses its server clock.

After the fix, all ten cases passed on both engines (zero skips), including whole seconds,
nanoseconds, equal-time ID ties, negative/extended years, aliases, parent/human filters, ordinary
and idempotent capture, missing-summary and selected-session repository paths, and unchanged
stored values. A separate moderate fixture inserts 20,000 synthetic sessions across 200 projects,
rebuilds only its own comparison index, and verifies public project counts and every time bound.
Both query plans use `idx_agent_sessions_last_seen_instant`; SQLite has no temporary ORDER BY sort.
One local run measured index builds of 12.31 ms (SQLite) / 26.18 ms (PostgreSQL), session-list HTTP of
80.73 / 7.72 ms, and project-summary HTTP of 106.76 / 82.77 ms. These are fixture observations,
including HTTP overhead, not production latency or concurrency guarantees. Project extrema still
require aggregate sorting; no speculative scope/partial indexes were added.

Models, judge, editor, and Elasticsearch are disabled in all new fixtures. HTTP uses random loopback ports; SQLite lives under the test temp
directory and opt-in PostgreSQL creates/drops only its own `bb_session_time_*` schema.

Run the focused suite with `mvn -Dtest=SessionProjectChronologyHttpTest test`. PostgreSQL cases
require `SBA_POSTGRES_TEST_URL`, `SBA_POSTGRES_TEST_USERNAME`, and `SBA_POSTGRES_TEST_PASSWORD`
pointing at a disposable test database; otherwise those cases explicitly skip.

The affected recording, project, query-plan, PostgreSQL and architecture selection ran 65 tests:
64 initially passed, and the module graph correctly rejected Project's undeclared new Query
module dependency. Adding only `query` to Project's allowed dependencies resolved that gate;
the architecture rerun passed. The shared Query module remains dependency-free. Scoped Palantir
format checks, Markdown file links and `git diff --check` passed. No frontend behavior/assets,
live service, database or provider was changed. Final integration and exact-head verification
with the event chronology base remain the coordinator's responsibility.

Coordinator integration: combined the additive session initializer with event chronology's PostgreSQL
initializer, preserving both on each backend. The integrated SQLite/PostgreSQL chronology selection
passed 41 tests with zero failures/errors/skips; the two named architecture classes and scoped
RecordingSqlStore formatting gate also passed. Temporary helper source was replaced by the
reviewed event chronology commit.
