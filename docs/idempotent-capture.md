# Idempotent event capture

`POST /api/events/idempotent` records one event with a client-generated capture ID. Retrying the
same capture returns the original event and session IDs without adding another event, incrementing
its session count, changing its session activity timestamp, or repeating downstream publication.
The existing `POST /api/events` endpoint and response remain unchanged and do not deduplicate.

## Request and acknowledgement

Generate a UUID once for a logical capture and retain that ID with the event until acknowledgement.
Send the same recognized event fields on every retry:

```json
{
  "captureId": "5f067ad6-902f-4ae9-bc58-0fd1de998c33",
  "event": {
    "source": "codex",
    "clientSessionId": "example-session",
    "eventType": "Observation",
    "text": "Verification completed.",
    "observedAt": "2026-09-19T12:00:00Z"
  }
}
```

The first successful request returns HTTP 200:

```json
{
  "captureId": "5f067ad6-902f-4ae9-bc58-0fd1de998c33",
  "eventId": "server-event-id",
  "sessionId": "server-session-id",
  "replayed": false
}
```

A retry returns the same IDs with `replayed: true`. The capture UUID must use the full hyphenated
form; hexadecimal letter case is accepted and the acknowledgement uses lowercase. Missing or
invalid IDs, missing event bodies, and invalid required event fields return HTTP 400 before storage.
The acknowledgement confirms canonical relational persistence. It does not assert that optional
search indexing, embeddings, summaries, or other listeners completed; there is no `indexed` field.

## Identity and conflicts

The receipt key is `(normalized source, trimmed clientSessionId, canonical captureId)`. Different
sources or client sessions may independently use the same UUID. These fields are client-declared
identifiers, not authentication or authorization. Existing API access controls still apply.

The server normalizes source and client session ID exactly as it does for the receipt namespace,
so source letter case or surrounding identity whitespace does not create a conflict. It fingerprints
all other recognized, deserialized event fields before redaction, truncation, content normalization,
or server-generated defaults. JSON object keys are sorted recursively; array order
is retained. Unknown event fields are ignored under the existing event request contract. Missing
and explicit null optional fields have the same deserialized representation. In particular,
missing `observedAt` remains null in the fingerprint, even though the stored event receives a server
timestamp. Retrying it later therefore reuses the original event timestamp.

The current `digestV1` field contract is explicitly frozen independently of the request DTO:
`source`, `clientSessionId`, `turnId`, `eventType`, `role`, `text`, `cwd`, `toolName`, `toolInput`,
`toolOutput`, `metadata`, and `observedAt`. All 12 fields are present in the fingerprint JSON,
including null optional values; timestamps use ISO-8601 and hashing uses compact UTF-8 JSON.
Adding an unrelated optional DTO field must not silently change this digest. Future semantic
expansion needs an explicit compatibility design that continues to recognize existing receipts
and queued captures under their original digest contract. There is no digest-version migration
in this change; a fixed expected-hash regression makes accidental field or serialization drift
visible before release.

Reusing a receipt key with a changed fingerprint returns HTTP 409 with error type
`capture_id_conflict` and makes no additional canonical change. Original field changes can conflict
even if normalization or redaction would produce the same stored value. Keep the original request
stable instead of adding a timestamp or rewriting text between retries.
Do not generate a new ID simply to bypass a conflict; inspect which logical event the ID represents.
Only the SHA-256 fingerprint is retained alongside the receipt, never an unredacted request copy.

## Persistence and failure behavior

The selected SQLite or PostgreSQL store reserves the receipt before session/event work using
`INSERT ... ON CONFLICT DO NOTHING`. Reservation, canonical event/session writes, count update,
and receipt binding commit in one transaction. A write failure rolls them all back. Concurrent
requests for one key converge on the same committed event; conflicting requests receive 409.
Receipts have no TTL and retain a foreign key that restricts deletion of their canonical event.
This prevents an acknowledged capture from silently becoming eligible for creation again. There
is no event-deletion API or new retention job in this change. A future purge needs explicit
tombstone or forget semantics for both events and receipts; it must not silently discard receipts.

Only the first successful canonical write attempts `EventRecorded` and, for terminal events,
`SessionStopped` publication. Replays do not repeat these attempts, including after restart or a
lost acknowledgement. Optional publication failures are logged and do not invalidate the canonical
acknowledgement. A process crash after the commit and before publication can leave optional work
undelivered; this endpoint does not add a transactional downstream outbox or promise exactly-once
external effects.

Older servers reject this dedicated route before writing; depending on their route matching, they
can return 404 or 405. A client must not silently retry such a request through legacy `/api/events`
and then assume it has an idempotent acknowledgement.

This server capability is the prerequisite for a durable hook queue. It does not install such a
queue, change hooks or MCP capture, or make existing fire-and-forget delivery reliable.
Distinct events may arrive out of chronological order. Their original `observedAt` values are
preserved, and each newly persisted event keeps `lastSeenAt` at the greater of its existing stored
checkpoint and the incoming observed instant, including nanosecond precision. This prevents new
backward movement; it does not repair historical sessions whose stored checkpoint was already
incorrect. `startedAt` retains its existing meaning: the observed time of the first
persisted event, rather than the minimum timestamp of later backfilled events. Replay changes
neither timestamp. These rules also apply to legacy unkeyed ingestion.

## Verification

`IdempotentCaptureHttpTest` uses a temporary SQLite database and real HTTP, including concurrent
requests, conflicts, malformed requests, rollback, terminal publication, optional listener failure,
legacy compatibility, and a request whose response is never read followed by server restart.
`PostgresBackendContractTest` exercises the shared store against a disposable PostgreSQL schema.
`EventRepositoryMigrationTest` checks that adding the receipt table retains existing history.
