# Durable stream recovery

`GET /api/stream` delivers `event.appended` in database append order. The event JSON envelope and
original `observedAt` are unchanged. A delayed hook, migrated Idea, or two captures with the same
timestamp can therefore replay after a disconnect. Business time is not a continuation cursor.

Every canonical append stores a position in the same transaction as its event/session and any
receipt or Decision-replacement relation. Updating a singleton counter holds a PostgreSQL row lock
until commit; SQLite already serializes writers. Receipt retries return the existing identity and
do not allocate or publish another append. Live callbacks wake database drains instead of selecting
the next frame themselves, so callback order cannot move a subscriber past an earlier commit.
The 15-second heartbeat also checks for captures whose optional publication was interrupted.

## Client contract

Treat SSE IDs as opaque. Return the last ID in `Last-Event-ID`; native browser `EventSource` does
this automatically. The versioned cursor binds a database generation, append position and event
identity. A `stream.checkpoint` frame establishes a cursor even when a connection has seen no
captures, and advances through positions whose payloads were deleted or filtered out.

Each drain examines at most 2,000 positions plus one look-ahead. If another page exists,
`replay.more` includes the last examined cursor and closes the connection. Reconnect to continue.
No omitted look-ahead row advances the cursor. Consumers should tolerate repeated event IDs around
network failure and reconcile against canonical HTTP responses when they need a current snapshot.

Legacy `<observedAt>|<id>` cursors, invalid cursors, another database's generation and restored-away
anchors produce `replay.reset` with a fresh checkpoint, then close. Refresh the canonical snapshot;
do not interpret this as a complete historical replay. The browser refreshes Activity Stream, the companion and
open session reader on reset/reconnect, and replayed events wake transcript refreshes. Activity
Stream replaces its loaded pages, pending rows and counts with a fresh snapshot under the current
query, project and human-turn filters; older HTTP responses and scheduled head refreshes cannot
restore discarded rows. This also applies to historical queries and empty feeds. Normal reconnect
uses the same full reload so backdated captures are not excluded by the old head timestamp.
While connected, an empty feed or a backdated/tied notification requests that same canonical reload;
a mixed burst keeps that requirement until the coalesced refresh. Ordinary newer notifications keep
the existing pending-row behavior and continue after the 50-notification buffer fills. An explicit
past `until:` filter continues to pause ordinary live updates; reset/reconnect still reconciles it. The existing
`session.updated` and `judgment.appended` frames remain transient and have no durable event cursor.

`since=<ISO-8601>` is an optional inclusive observed-time filter, applied to both replay and live
drains, including reconnects to the same URL. Matching frames still arrive in append order. A
malformed timestamp returns HTTP 400. An initial time-filtered subscription seeks past the leading
historical prefix that cannot match, without changing append order or skipping concurrent late
captures. The coarse seek retains the full boundary second; the payload filter compares exact
nanoseconds. Old/deleted positions interleaved after that initial candidate still consume bounded
pages. The default browser stream has no time filter.

## Migration, deletion and restore

Startup adds `event_stream_state` and `event_stream_positions`, then atomically assigns missing
historical events stable positions before accepting requests. Original commit order cannot be
reconstructed for preexisting rows. Migration never rewrites event payloads/timestamps, renumbers
existing anchors, or lowers the counter. Backfill is SQL-side; first-start cost grows with existing
history, while subsequent starts check for missing anchors. SQLite and PostgreSQL use the same
transactional migration. A failed backfill rolls back its counter and position writes.

Position rows deliberately retain event IDs without an event foreign key. There is no production
event deletion or position-retention API in this slice. If an operator removes a payload, its compact
anchor remains and stream progress skips the absent body. Do not truncate/rebuild position tables
or reset the counter as cleanup: that changes cursor identity. Partial database imports are outside
the restore contract; normal captures and supported Idea migration allocate fresh positions.

Native full-database/schema backups preserve both tables with canonical events. Restart preserves
valid cursors. Restoring an older snapshot can make a cursor out of range or change its position's
event identity; the server then requires a snapshot reset. This contract is for one API instance;
it does not establish safe multiple writers outside the application's capture transaction.

## Verification

`StreamReplayOrderingTest` covers late/tied timestamps, stable migration and failure rollback,
retained anchors, invalid/restored cursors, reversed callbacks, heartbeat recovery and bounded pages.
`EventStreamTest` exercises real HTTP disconnect/checkpoint/reconnect. `StreamPositionPostgresTest`
uses an explicitly configured disposable PostgreSQL schema and latch-controlled concurrent commits
and rollback; CI requires it to run. `DatabaseRestoreContractTest` compares both new tables during
native SQLite/PostgreSQL recovery. Frontend unit and browser tests exercise transcript catch-up.
