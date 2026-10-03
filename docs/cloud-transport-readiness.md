# Cloud transport readiness

The durable capture outbox can deliver to an explicitly selected HTTPS origin with a destination-bound macOS Keychain bearer. The default remains numeric-loopback HTTP. See [durable capture](durable-capture.md) for configuration, acknowledgement and recovery semantics.

This is the D14 prerequisite from the later personal-cloud consumer trace. Local acceptance happens before credential lookup; the existing sanitized queue, immutable capture identity, origin partition and acknowledgement protocol are unchanged. HTTPS adds normal certificate and hostname verification and fixed credential-failure diagnostics. The hook still has a shared foreground deadline; this change does not establish zero added agent-turn latency.

## Local acceptance

Run `scripts/test-agent-hook.sh`. The transport tests exercise local TLS, credential failure after queue commit, no credential lookup for status, untrusted and mismatched certificates, redirects, endpoint isolation, credential rotation and stable retry bytes. Credential responses and trust roots are test fixtures. No production cloud endpoint, real credential or database is needed.

## Authenticated PostgreSQL runtime acceptance

The existing cloud image now refuses startup without explicit PostgreSQL configuration and enabled
browser/bearer authentication; secure cookies remain required. Local SQLite startup is unchanged.
See the [cloud image startup contract](postgres-backend.md#cloud-image-startup-contract) for its
supported environment settings.

CI supplies a disposable PostgreSQL 16 service and requires both database contract classes to run.
The authenticated consumer contract exercises real HTTP and streamable MCP calls, full event
retrieval, idempotent replay, project isolation, Decision replacement/history, bearer SSE event and
heartbeat delivery, and restart persistence in a randomly named test schema. Its MCP tool checks
cover capabilities it uses, so retiring the coordination board does not freeze an obsolete tool
inventory. No model provider, live database or cloud endpoint is used.

This establishes the existing single-server application contract locally. It does not establish
managed-proxy SSE behavior, production TLS, browser/phone acceptance, connector OAuth, backup/restore,
or a completed migration. The [runtime plan](superpowers/plans/2026-10-02-authenticated-cloud-runtime.md)
records the startup reproduction, verification and limits.

## Cloud release prerequisites

The September 29 design remains a draft. Its later consumer-trace amendments are on the separately recorded `cloud-spec-consumer-trace` branch; they should be reconciled before planning a migration. Relevant changes include retaining bounded tool input and compatible event response shapes, preserving `agentId` and capture digests, using the existing outbox, capturing Claude assistant turns, and removing the retired coordination board while preserving session lineage.

The existing single-server PostgreSQL profile can already be hosted behind authenticated HTTPS;
see [PostgreSQL backend](postgres-backend.md). That deployment does not require S3, connector OAuth
or board removal. The broader proposed personal-cloud architecture still needs:

1. The selected reviewed release revision, with the authenticated consumer contract rerun against that source and deployed consumers verified. Board/runner removal and retained lineage are implemented; existing-table retirement remains a separate explicit opt-in.
2. Authenticated HTTPS hosting, owner-provisioned machine credentials and connector OAuth. No infrastructure is provisioned by this transport change.
3. PostgreSQL/S3 projection and payload handling, compatible full-record retrieval, asynchronous derived-work queues, and event-backed transcripts.
4. A resumable migration with count/checksum and retrieval comparisons; tested backup/restore and an explicit cutover plan preserving the local archive.
5. End-to-end phone use with the Mac asleep; Mac outage/replay and each actual consumer. Local TLS tests do not establish those outcomes.

Keep one authoritative server as the intended personal deployment. No history synchronization, multiple replicas, or broad workspace sharing is introduced. Decide on provisioning and cutover using a concrete candidate and measured cost; do not activate a remote endpoint merely because the client can now reach it.
