# Idempotent event capture: server prerequisite

Status: server prerequisite independently reviewed, integrated and deployed locally. The separately
reviewed [opt-in hook outbox](2026-09-19-durable-hook-outbox.md) passed actual Java delivery proof;
global client configuration remains unchanged. Pull-request review is separate.

## Safety and scope

Add only a dedicated `POST /api/events/idempotent` endpoint with a wrapper containing `captureId`
and the existing event request. Keep the legacy endpoint and event DTO constructors unchanged.
All proof uses temporary SQLite files or a disposable PostgreSQL container with randomly named test
schemas. Do not change hooks, MCP structured capture, live databases, deployed services, runner
policies, or public/external state. The coordinator owns review, Git finish, and integration.

## Frozen acceptance

- A canonical UUID capture ID is namespaced by normalized source and trimmed client session ID.
- A deterministic SHA-256 digest covers recursively sorted JSON of recognized original request
  fields before redaction, content normalization, truncation, or server timestamp defaults. The
  architecture review refined this contract: normalize source and client session ID in the digest
  exactly as in the namespace, so identity aliases replay. Store the
  digest only; do not persist an unredacted request copy. Missing `observedAt` stays missing in
  the digest.
- Reserve the receipt first with `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as
  session/event persistence and receipt binding. Duplicate keys must not rely on catching a
  constraint exception in a PostgreSQL transaction.
- Same key and digest returns the original event/session IDs with `replayed: true`. Changed
  payload returns HTTP 409 `capture_id_conflict` without another canonical write.
- Repeated and concurrent duplicates do not update session counters or timestamps and do not
  republish `EventRecorded` or `SessionStopped`. The acknowledgement promises canonical storage,
  not optional index or model completion.
- Missing/malformed IDs and event bodies fail before persistence. A failure after receipt
  reservation rolls the whole write back. Receipts have no TTL. The architecture review selected a
  restrictive event foreign key: future deletion requires explicit tombstone/forget semantics,
  preventing a receipt cascade from silently making acknowledged IDs eligible for creation again.
- Exercise real HTTP and real database state: duplicate/concurrent requests, independent source
  and session namespaces, payload conflict, malformed input, rollback, retry after restart/lost
  response, terminal fanout once, optional fanout failure, and unchanged legacy behavior.
- Verify an existing pre-change database keeps its history when the receipt schema is added.
- Verify both SQLite and disposable PostgreSQL, run relevant regression tests, and get fresh
  independent review before integration.

## Sequence

1. Prove the new route is absent over real local HTTP. A 404/405 is evidence of absent capability,
   not a claim that an existing endpoint was incorrectly implemented.
2. Implement the receipt transaction, deterministic identity, dedicated API response/error mapping.
3. Verify failure and concurrency cases using isolated databases and update documentation.
4. Hand exact evidence and remaining limits to the coordinator.

## Separate work identified at initial scope freeze

Durable hook outbox/retry behavior remains a later slice. Existing ingestion can move a session's
`last_seen_at` backwards when older queued events arrive; report this for the next slice rather
than expanding this prerequisite.

## Results

### Capability absence

Before implementation, `mvn -q -Dtest=IdempotentCaptureHttpTest test` sent the proposed valid request
through real HTTP to a temporary SQLite-backed application and failed its expected-200 assertion
with HTTP 405 `method_not_allowed`. The existing GET `/api/events/{id}` mapping caused 405 rather
than 404. No canonical event write happened through this missing POST route. This establishes
absent capability; it is not presented as an existing idempotent endpoint defect.

### Implementation and verified outcomes

The new endpoint, wrapper/acknowledgement DTOs, conflict exception mapping, canonical request
fingerprint, shared SQL receipt transaction, and both schemas are implemented. Existing event
request constructors, legacy endpoint response, hooks, and MCP structured capture remain unchanged.

Fresh review requested one narrow compatibility refinement: freeze the fingerprint's 12 fields
in a private `DigestV1` record instead of serializing the extensible event DTO. This is implemented
with a fixed SHA-256 fixture independently computed from compact, recursively sorted UTF-8 JSON,
including null fields and an ISO-8601 timestamp. Future request expansion must preserve old receipt
and queued-capture compatibility rather than silently changing the digest contract.

The focused command was:

```bash
SBA_POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:<disposable-port>/blackbox_test \
SBA_POSTGRES_TEST_USERNAME=blackbox_test \
SBA_POSTGRES_TEST_PASSWORD=blackbox_disposable_test \
mvn -q -Dtest=CaptureIdentityTest,IdempotentCaptureHttpTest,EventIngestServiceTest,EventRepositoryMigrationTest,RecordingLifecyclePublicationTest,PostgresBackendContractTest,ApiExceptionHandlerTest test
```

All 41 tests across the seven selected classes passed with zero failures, errors, or skips, including
13 PostgreSQL contract tests and 11 SQLite HTTP tests. `git diff --check` passed.

- Eight simultaneous HTTP requests converge on one receipt, event, session count, and original
  identity on both database engines. Concurrent different payloads yield one HTTP 200 and one
  typed HTTP 409 without leaving a poisoned transaction.
- Replays retain session state/timestamps and do not repeat terminal publication. An optional
  publication exception still returns canonical acknowledgement, and its replay does not retry
  publication. Legacy requests still create distinct events and retain the `indexed` wire field.
- Recursively reordered objects replay. Source case/whitespace and session whitespace aliases
  replay. Other source/session namespaces remain independent. Changed original secret-bearing
  text conflicts even when redaction would make persisted text identical.
- Missing/malformed identities or event bodies fail before receipts/events. Triggered event-insert
  failures after reservation roll back receipt/session/event on SQLite and PostgreSQL; retries
  then succeed.
- SQLite real HTTP sends a request over a socket, never reads its response, confirms canonical
  commit, restarts the application, and retries to the original event ID. PostgreSQL also verifies
  receipt/identity persistence across application restart.
- SQLite and PostgreSQL pre-change schemas retain exact existing session/event rows after adding
  the receipt schema. Foreign-key restriction prevents event deletion while its receipt exists,
  and replay still returns the original identity.

### Operational boundary and handoff

Verification used temporary SQLite files and a new disposable local `postgres:18-alpine` container
with no host bind mount. The image creates an anonymous Docker data volume; the container was
started with `--rm` for disposal together with that volume. PostgreSQL tests created and dropped
only randomly named fixture schemas.
During that implementation slice, existing databases and containers were not changed. Coordinator
review, full-suite testing and integration subsequently passed. The server candidate was deployed
locally through the verified deployment procedure with unchanged configuration/database identity;
the disposable PostgreSQL container and its anonymous volume were removed after final verification.

The new endpoint provides canonical persistence with at-most-once publication attempts; it does
not guarantee completion of optional downstream work across a crash. Receipts have no TTL, and
future purge must explicitly define tombstone/forget semantics. The separate opt-in durable-hook
and monotonic-activity slices are now reviewed and integrated. Remote/authenticated client capture
and global hook activation remain outside this implementation.
