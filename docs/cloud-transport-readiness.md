# Cloud transport readiness

The durable capture outbox can deliver to an explicitly selected HTTPS origin with a destination-bound macOS Keychain bearer. The default remains numeric-loopback HTTP. See [durable capture](durable-capture.md) for configuration, acknowledgement and recovery semantics.

This is the D14 prerequisite from the later personal-cloud consumer trace. Local acceptance happens before credential lookup; the existing sanitized queue, immutable capture identity, origin partition and acknowledgement protocol are unchanged. HTTPS adds normal certificate and hostname verification and fixed credential-failure diagnostics. The hook still has a shared foreground deadline; this change does not establish zero added agent-turn latency.

## Local acceptance

Run `scripts/test-agent-hook.sh`. The transport tests exercise local TLS, credential failure after queue commit, no credential lookup for status, untrusted and mismatched certificates, redirects, endpoint isolation, credential rotation and stable retry bytes. Credential responses and trust roots are test fixtures. No production cloud endpoint, real credential or database is needed.

## Cloud release prerequisites

The September 29 design remains a draft. Its later consumer-trace amendments are on the separately recorded `cloud-spec-consumer-trace` branch; they should be reconciled before planning a migration. Relevant changes include retaining bounded tool input and compatible event response shapes, preserving `agentId` and capture digests, using the existing outbox, capturing Claude assistant turns, and removing the retired coordination board while preserving session lineage.

A working remote service still needs:

1. The selected reviewed source, including the separate lineage/board-removal work, plus a consumer contract against that source.
2. Authenticated HTTPS hosting, owner-provisioned machine credentials and connector OAuth. No infrastructure is provisioned by this transport change.
3. PostgreSQL/S3 projection and payload handling, compatible full-record retrieval, asynchronous derived-work queues, and event-backed transcripts.
4. A resumable migration with count/checksum and retrieval comparisons; tested backup/restore and an explicit cutover plan preserving the local archive.
5. End-to-end phone use with the Mac asleep; Mac outage/replay and each actual consumer. Local TLS tests do not establish those outcomes.

Keep one authoritative server as the intended personal deployment. No history synchronization, multiple replicas, or broad workspace sharing is introduced. Decide on provisioning and cutover using a concrete candidate and measured cost; do not activate a remote endpoint merely because the client can now reach it.
