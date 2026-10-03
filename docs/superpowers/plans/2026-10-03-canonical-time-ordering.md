# Compare canonical timestamps consistently during navigation

## Reproduction and scope

The actual HTTP fixture reproduced a stored row missing from all session transcript pages when
recorded whole-second/fractional timestamps were merged with a newer transcript-file message.
The recorded SQL page compared raw ISO text, while transcript merging compared Instant values.
The same raw-text comparison excluded a later fractional event from an inclusive whole-second
`since:` filter in the feed and legacy search. Exact timestamp ties and a later-arriving older
row already paginated correctly in a control fixture.

Keep canonical observedAt bytes, event/session identities and the existing `<observedAt>|<id>`
cursor wire format. SQL ordering, cursor seeks and time-window predicates must compare the same
nanosecond-preserving key. No offset pagination, durable-cursor redesign, corpus rewrite or new
provider/live-service behavior. Keep query/project/human filters and strict/inclusive date bounds.

## Implementation and performance gate

Use a small shared portable SQL timestamp-key helper for recording and memory queries, with matching
bound-parameter formatting. Retain id as the tie-break. Reuse existing compact-search behavior where
possible and verify SQLite/PostgreSQL parity rather than relying on database datetime functions
that round to milliseconds/microseconds.

Inspect query plans and timings on a moderate disposable corpus. A computed chronological key must
not silently turn every next page into a corpus scan/sort. If additive expression indexes are
needed, record their build cost, resulting plans and startup impact; do not rewrite stored timestamps
or remove existing indexes used by unrelated paths. Coordinator review owns that acceptance.

## Acceptance

- Actual HTTP mixed recorded/transcript pagination returns every canonical and transcript ID once.
- Whole, fractional, nanosecond and tied timestamps preserve descending order across pages.
- Inclusive since/until instants and exclusive date-end bounds agree across feed, session and legacy
  search; project/query filters still exclude other records. Late older arrivals remain navigable.
- Old-format cursors retain their meaning and event payload timestamps remain unchanged.
- Shared real SQLite/PostgreSQL fixtures, meaningful query-plan/performance checks, affected contract
  tests, scoped Palantir formatting, full relevant backend tests and diff checks.

Only isolated checkout sources and disposable fixtures may change. No live database, provider or
recorder is used. Coordinator owns Git integration, publication and merge.

## Settled comparison and migration contract

`SqlInstant` encodes the full Java Instant range as a ten-digit year offset by one billion,
followed by fixed-width UTC month/day/time and nine fractional digits. SQLite uses `printf` and
PostgreSQL uses `lpad`; both compare native SQL keys with the Java-bound key for Instant.MIN/MAX,
negative and extended years, whole seconds, and nanoseconds. Database datetime conversion is avoided.

An initial two-component year/time tuple produced an SQLite index scan for deep cursors. The final
single key plus event ID uses the strict tuple comparison and a redundant inclusive scalar time
bound. This exposes an expression-index range seek without changing tie behavior. Global, session,
and human-turn partial indexes use exactly the same SQL expression as the queries. Existing raw-time
indexes remain for unrelated paths. The new expressions require canonical valid UTC Instant text,
which the recording write path already stores; this is not a repair/import path for malformed rows.

Recording feed/date/cursor queries, the non-paginated session event endpoint, transcript path and
conversation evidence ordering, local search, lexical/semantic recall candidate windows and typed
idea-event pagination now share this normalization. Session-list/project aggregate timestamps are a
separate follow-up. Canonical event text/metadata/timestamp storage and SSE durable cursors are unchanged.

## Verification record and handoff

The initial actual-HTTP regression failed all three new navigation/window cases before the fix;
the additional legacy session-event endpoint check separately failed before its query was changed.
The first affected run passed 91 tests, including PostgreSQL schema-isolated HTTP contracts, SQLite
feed/search/recall tests and native key parity. Full-suite and final performance results are recorded
below after completion. All fixture apps use loopback ephemeral ports and disabled providers; tests
close their contexts and PostgreSQL tests remove only their own random schemas. The disposable
PostgreSQL server is coordinator-owned and is not stopped by this worker.

Codex worker handoff: isolated `codex/canonical-time-ordering` checkout, uncommitted source/test/docs
only. No live database/service changes, Git writes, publication or deployment. Coordinator owns review,
commit as Nathan, integration with the independent session chronology follow-up, and publication.

Final acceptance: `mvn -q test` with the disposable PostgreSQL environment and PostgreSQL 16 clients
completed 668 tests with zero failures/errors and four unrelated skips (two live-model evaluations,
optional Elasticsearch, and a platform-specific Finder absence check). PostgreSQL backend (21),
authenticated consumer (4), restore (2) and stream-position (2) contract classes all ran without skips.
Two pre-existing query-plan assertions initially required the old session index by name; they now
accept either session-leading index while retaining the indexed-search/no-event-scan requirements.

The final 50,000-row fixture contains 1% Decision and 99% hook events. Three index builds took 94 ms
and added 6,299,648 bytes of SQLite pages. Repeated initialization took 9 ms. The first chronological
query took 77 microseconds; deep global/session/human queries took 70–82 microseconds and used indexed
range seeks without a temporary sort. Actual recall of 100 intent rows took 2.528 ms; its plan used
the chronological range index with indexed session and replacement probes. Timing is evidence from a
warmed disposable fixture, not a production guarantee; no hard timing threshold is asserted.

Scoped Palantir formatting and diff checks complete the source verification. No generated frontend
assets, live state or Git metadata were changed by this worker. All fixture application contexts and
random PostgreSQL schemas were closed/removed by tests; the coordinator retains the disposable server.
