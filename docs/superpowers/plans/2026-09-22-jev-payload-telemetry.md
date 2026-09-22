# Local Jev payload inspection

The selected slice makes actual sanitized serialized Jev requests and validated
results inspectable in an explicitly approved local log sink. It adds no provider
calls, changes no capture or recall routing, and does not replay historical events.

Safety contract: telemetry is disabled by default. The same request body string
is sent and recorded, with SHA-256, character length, and explicit truncation or
omission flags. Headers and credentials are excluded. Mandatory export redaction
runs on state strings before serialization, even when ingestion redaction is off.
Telemetry failure cannot prevent classification. A completion means the provider
response passed validation; persistence remains a separate check via event IDs.

- [x] Inspect actual transport boundary and existing ingestion redaction.
- [x] Implement opt-in request/completion envelopes and mandatory export redaction.
- [x] Test exact wire equality, failure isolation, errors, truncation, and redaction.
- [x] Verify module boundaries and package the clean checkout.
- [x] Deploy only after local collector routing and retention are verified.
- [x] Verify a new approved call in HyperDX and against persisted judgment IDs.

The paired private collector/view change belongs to Cockpit. Local operations must
verify its actual destinations before enabling content telemetry. Ordinary source
logs may retain content longer than the telemetry database; the database TTL is
not an end-to-end retention guarantee. No push, PR, or branch integration is part
of this slice.

Verified: 678 backend tests, 20 skipped, 0 failures/errors; targeted formatted-code and
module checks passed. The packaged frontend was included. Real local verification
matched the full body hash and all normalized persisted judgment fields. No schema
migration, historical replay, push, PR, or branch integration occurred.
