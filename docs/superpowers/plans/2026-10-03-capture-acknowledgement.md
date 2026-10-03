# Preserve acknowledgements after canonical capture

## Reproduction

On current main, an optional EventRecorded listener throwing after persistence caused REST
POST /api/decisions to return 500 with one committed event. Retrying returned 200 with two events.
MCP captureDecision showed the same sequence: tool error with one event, successful retry with two.
The existing receipt-protected endpoint passed all 13 contract tests and already contains a guarded
publication helper. The reproduction used actual HTTP/MCP against isolated SQLite with providers off.

## Safety contract

- Canonical persistence and validation errors remain errors; do not catch them or acknowledge
  rolled-back writes. Keep response shapes and existing receipt behavior unchanged.
- Reuse publishOptional for ordinary EventRecorded and independently SessionStopped publication,
  after the canonical store transaction returns. A failed optional notification cannot turn a
  committed event into an API/tool failure or suppress the attempt to notify a terminal stop.
- This does not add idempotency to legacy/structured endpoints or MCP tools. A genuinely lost
  network response remains ambiguous there; repeating a request still creates another event.
- No derived-work retry queue, schema/transport redesign, provider call or live database/service
  change. Only temporary SQLite/loopback fixtures; coordinator owns Git/publication.

## Acceptance

Actual REST and MCP capture acknowledgements after controlled optional-listener errors;
independent terminal notification attempts when either/both publications throw; validation and
persistence rollback failures still reject with no event or publication; legacy retries remain
append-only. Re-run existing receipt/lost-response tests, relevant full backend tests, scoped
Palantir formatting and diff checks. Record opt-in PostgreSQL skips honestly; its fixture is stopped.

## Verified result

- Before the source change, the new actual HTTP/MCP fixture ran eight cases: six failed at the
  acknowledgement assertions; validation and database-rollback cases passed.
- The production change reuses the existing guard for two independent publications in `ingest`.
  Canonical persistence, request validation, response fields and receipt behavior are unchanged.
- `mvn -q -Dtest=CaptureAcknowledgementHttpMcpTest,IdempotentCaptureHttpTest test`: 21 passed.
- `mvn -q test`: 611 tests, zero failures/errors, 25 skips. These include 21 opt-in PostgreSQL
  cases because its fixture was stopped, plus four pre-existing optional/unrelated checks.
- Review corrected the fixture flag to `sba.judge.enabled=false` and added an assertion on the
  bound configuration. `SBA_JUDGE_ENABLED=true mvn -q -Dtest=CaptureAcknowledgementHttpMcpTest test`
  then passed all eight cases. Only the fixture changed after the full suite.
- Scoped Palantir formatting and `git diff --check` passed.

Codex worker handoff: branch `codex/capture-acknowledgement`, four owned source/test/doc paths
left uncommitted for the coordinator's Git review and publication. Disposable application contexts
and HTTP client closed; temporary test databases cleaned up. No live database, service or provider
was used or changed. Legacy captures remain append-only after a genuinely lost network response;
there is no downstream retry queue or guarantee that every optional listener completed.

Coordinator integration: merged main through PR60 and ran the actual acknowledgement HTTP/MCP
fixture plus EventStreamTest and StreamReplayOrderingTest together: 22 tests passed with no skips.
Fresh review caught and corrected the fixture's judge-disable property; the corrected fixture
passed with an ambient enabled value, as recorded above. No live deployment.
