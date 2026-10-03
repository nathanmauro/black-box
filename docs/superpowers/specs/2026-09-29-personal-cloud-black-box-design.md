# Personal cloud Black Box — design

> NAT-243 update (2026-10-02): session lineage now belongs to `dev.nathan.sbaagentic.lineage`; the board, task/spec APIs, and runner are retired. Earlier workflow/runner package names and task tables in this dated design are historical, not current implementation instructions. See [retirement notes](../../board-retirement.md).

Date: 2026-09-29 · Reconciled: 2026-10-02 · Status: draft for owner review.

This proposes a single-user deployment accessible while the primary workstation is asleep. It
supersedes the personal-use scope of the earlier [tenancy/lifecycle proposal](../plans/2026-09-15-cloud-lifecycle-and-tenancy.md),
which remains a reference for a possible multi-tenant product. It does not authorize provisioning,
credential creation, data migration, consumer cutover, or deletion.

## Intent and release boundary

An agent or person should be able to retrieve project evidence, follow its sources, and capture
new structured intent from a browser, phone, desktop client, or remote coding session. The intended
personal deployment has one authoritative server; clients may queue captures while offline.
Selecting a remote database does not synchronize an existing local history.

The existing single-server PostgreSQL profile can already support a deployment behind authenticated
HTTPS. S3, hosted embeddings, connector OAuth, new queues, and event-only transcripts are a larger
proposed architecture, not prerequisites for that simpler deployment. SQLite stays the product's
local default. See [PostgreSQL behavior](../../postgres-backend.md),
[authentication](../../authentication.md), and [transport readiness](../../cloud-transport-readiness.md).

A release candidate must demonstrate:

1. Phone access with the workstation asleep: discover records, retrieve exact-project context,
   inspect full accepted evidence, and capture a decision through an authenticated client.
2. Durable capture during an outage, followed by idempotent replay to the intended server. A lost
   acknowledgement must not create a second event. Idle-client delivery needs an explicit retry
   mechanism; installing the outbox alone does not provide a scheduler.
3. Continued operation of each selected consumer class below, including its authentication,
   field semantics, and streaming behavior. Changing a base URL alone is insufficient.
4. Measured capture and retrieval latency that does not disrupt agent turns. The current hook has
   a bounded foreground deadline; this proposal makes no zero-latency guarantee.
5. A readable event stream with tool identity, available input, status, and bounded output. Large
   input/output must expose truncation and a reliable full-record path.

Non-goals: multi-tenancy, multiple API replicas, customer lifecycle automation, porting the separate
Ask pipeline, or pausing the server to zero. No deployment, phone acceptance, migration, or operating
cost is established by this document.

## Current baseline and evidence

The September consumer review identified duplicated tool payload fields, readers dependent on
specific event fields, and clients requiring changes beyond their endpoint URL. This draft retains
those requirements without reproducing private machine inventories, configuration locations,
service addresses, or workload measurements. Re-measure the chosen deployment and consumer versions
before sizing or cutover; historical observations are not proof of current use.

As of this reconciliation:

- The durable outbox supports explicitly selected HTTPS origins with normal TLS verification and
  a destination-bound macOS Keychain bearer. Local queue acceptance precedes credential lookup.
  Enablement, credentials, endpoint selection, and retry scheduling remain operator work; the
  default remains numeric-loopback HTTP. See [durable capture](../../durable-capture.md).
- REST recall accepts independent `project` and `query` parameters; MCP `recallContext` exposes
  the same separation. Project matching includes registered aliases and constrains both lexical
  and semantic results. An unknown project yields no results, and a blank question retrieves
  recent structured intent. Legacy `scope`/`repoOrTopic` remains supported, but must not be combined
  with `project`/`query`.
- Decision capture accepts `supersedes` for an explicit replacement of one current Decision in the
  same logical project, with a nonblank rationale. Event and relation are persisted atomically;
  original evidence is retained. Normal recall excludes replaced decisions; `includeSuperseded`
  explicitly includes history. See the [recall contract](../../recall-observability.md).
- Session lineage survives the board/runner retirement. Fresh databases omit task tables, while
  existing databases retain legacy data by default. `SBA_RETIRE_WORKFLOW=true` is a separate,
  irreversible opt-in schema retirement after backup verification. The retirement does not stop
  local services or remove operator files. See [upgrade notes](../../board-retirement.md).
- The committed [MCP contract](../../../src/test/resources/contracts/mcp-tools.json) lists 10 tools
  after removal of the seven workflow tools. Reconnect cached clients after upgrading.
- Browser authentication and a full-workspace machine bearer exist; connector OAuth and per-client
  permissions do not. The PostgreSQL profile does not implement S3 payload storage, a cloud job
  queue, an importer, or upload of server-local transcript files.

Semantic recall returns structured intent, not the full event corpus or session-summary vectors.
The default external summary backend may send transcript text off the machine; local-model use is
an explicit configuration choice. A cloud design must preserve that disclosure.

## Design decisions and proposals

Identifiers are retained so earlier references can be reconciled. Rows marked **proposed** describe
future behavior, not shipped implementation or a provisioning decision.

| ID | Direction | Status and requirement |
| --- | --- | --- |
| D1 | One authoritative personal server; clients have durable outboxes | Proposed deployment topology. Keep the local default and prevent accidental dual authority during cutover. |
| D2 | AWS us-east-2 is the recorded personal deployment direction | Recorded direction. Compute service, current budget, and provisioning remain open; private account/bootstrap details are omitted. |
| D3 | One API instance with managed PostgreSQL | Existing single-server profile is available. Multi-replica safety is not established by selecting PostgreSQL. |
| D4 | Keep queryable event projections in PostgreSQL and large accepted payloads in private object storage | Proposed; requires a durable upload protocol, full-record retrieval, migration, and compatible consumers. |
| D5 | Remove redundant stored payload copies only after preserving their useful metadata | Proposed. Promote required lineage and recovery fields, including `agentId`, `parentClientSessionId`, and `captureDigest`; preserve provenance and verify every reader before removing `rawHook` dependencies. |
| D6 | Evaluate native PostgreSQL text search for the personal server | Proposed. Preserve query/filter semantics and document ranking differences before replacing fallback or optional index paths. SQLite remains supported. |
| D7 | Run optional embeddings asynchronously using a selected model | Proposed; provider, dimensions, egress, cost, and re-embedding need review. Lexical capture visibility must not wait for a model. Semantic availability may lag. |
| D8 | Run summaries and titles through a workstation worker consuming durable jobs | Proposed. Sleep delays this optional work; expose queue age and avoid claiming it is cost-free. An alternative hosted worker remains a deployment choice. |
| D9 | Preserve judgment APIs and SSE while moving derived work to a durable queue | Proposed. Queue retries and publication must avoid duplicate judgments and silent loss. |
| D10 | Add single-user OAuth for selected connectors; retain machine bearer clients | Proposed connector integration. Verify current client requirements and callback behavior before implementation; existing bearer access has full-workspace privilege. |
| D11 | Offer a bounded connector surface for discovery, full-record fetch, project context, and append capture | Proposed. Preserve idempotency and caller-declared provenance; a connector label is not authenticated client identity. Retire any existing relay only after parity is demonstrated. |
| D12 | Keep enough tool input and output in each row to render normal activity without object-storage reads | Proposed budgets: 20,000 characters of input and a 6,000-character output head. These are bounds, not a promise of full input. Keep the full accepted payload separately and mark truncation explicitly. |
| D13 | Preserve list, exact-event, and streaming consumer contracts | Required migration constraint. Existing field names, types, and semantics remain until a compatible, tested transition exists. Full-record retrieval must work without a workstation filesystem. |
| D14 | Use durable capture for clients selected for remote operation | HTTPS transport is implemented; adoption is pending. Configure both endpoint and allowed HTTPS origin, provision the matching credential, and verify replay and idle retry behavior. |
| D15 | Build transcripts from captured events | Recorded owner direction; implementation and cutover remain pending. Prove final-assistant capture for each client and document completeness and the recorded loss of intermediate assistant prose. New evidence of additional loss requires an explicit follow-up retention decision before retiring local-file hydration. |
| D16 | Retire the board and runner while retaining lineage | Implemented in source. Existing database deletion remains default-off and separately authorized; legacy task data must be accounted for during migration. |

## Proposed architecture

```text
browser / phone / remote agent / desktop client
             | authenticated HTTPS
             v
       one Black Box API instance
         |-- canonical PostgreSQL records and relations
         |-- optional private object payloads (proposed)
         |-- durable derived-work queues (proposed)
         `-- authenticated REST, MCP, and SSE

workstation capture outbox -- bounded retry --> API
optional summary worker  <-- durable job claim --> API (proposed)
```

### Payload, projection, and retrieval contract

Full fidelity means the complete accepted, redacted record, not recovery of secrets removed before
acceptance. Preserve stable event/session IDs, original observation timestamps, provenance, and
idempotency receipts regardless of physical storage layout.

For the proposed split, prefer committing the canonical event, receipt, and durable upload work
atomically in PostgreSQL, then uploading the accepted payload asynchronously. Define bounded pending
storage, retry policy, and cleanup before implementation. A crash or object-storage outage must
leave accepted bytes recoverable. If admission cannot retain them durably, fail without recording
a successful acknowledgement so the client can retry. Do not promise nonblocking ingest merely
because failed uploads are retried.

The initial projection budgets in D12 require tool-specific validation. Truncating serialized JSON
and presenting it as valid `toolInputJson` would break readers. Keep a valid compatible representation,
explicit truncation metadata, and a full-input path. Preserve source timestamps and references in
all rendered excerpts. Parsing and projection should be deterministic, with malformed-input tests.

Existing presenters consume `toolInputJson`, `toolOutputJson`, `text`, and `metadata`; retaining field
names alone is not compatibility. Removing a field, changing its meaning, or requiring a new
`projection` object needs a tested client transition or an explicitly versioned contract. Internal
UI consumers count as consumers even when external clients do not use that field.

`GET /api/events/{id}` must continue returning the complete accepted event. During a pending upload,
serve from durable pending storage or return an explicit retriable error; never present the head as
a complete record. A separate payload endpoint is only a proposal. Authorize all payload reads and
keep storage private. Test long Read content, Edit/apply-patch diffs, command failures, and oversized
inputs through the actual UI before removing duplicated fields.

SSE preserves the current non-task event frames, IDs, lineage hints, ordering/reconnect behavior,
and authorization. A direct authenticated stream test does not establish proxy buffering, idle
limits, or reconnection through the selected public endpoint.

### Derived work and transcript boundaries

Model work must remain optional for canonical capture and lexical recall. Derived-work jobs need
stable identities, claim expiry, retry limits, visible failures, and safe result replacement.
Summary refresh should follow actual session growth rather than assuming every stop hook ends the
session. Existing summary/title behavior and model egress need an explicit compatibility check.

Event-backed transcripts are the recorded owner direction. The recorded tradeoff is loss of
intermediate assistant prose between tool calls within a turn, assuming final assistant messages
are captured. Test each selected client's capture completeness against source fixtures and document
that loss before cutover. If new evidence reveals additional loss beyond the recorded acceptance,
obtain an explicit follow-up retention decision or extend capture before retiring local-file
hydration. The direction does not establish implementation readiness.

## Consumer contract

Inventory the actual deployed consumers privately before cutover. The public contract groups them
by behavior; it is not a claim that every consumer is already configured or recently tested.

| Consumer class | Contracts to preserve | Required cutover proof |
| --- | --- | --- |
| Capture hooks and capture CLIs | Durable queue acceptance, `/api/events/idempotent`, stable retry bytes and receipts | Explicit HTTPS origin, destination-bound credential, outage replay, acknowledgement-loss retry, and an idle retry strategy. Legacy direct writers need migration or a documented exception. |
| Structured writers | Decision, handoff, observation, projection, and idea capture | Bearer authentication, validation errors without partial writes, explicit replacement rationale, provenance, and preserved project identity. |
| Recall readers | `/api/recall` and `recallContext`, bounded output and legacy scope compatibility | Same-topic projects do not leak; aliases work; unknown projects fail closed; history excludes replaced choices unless requested. Test lexical and optional semantic modes. |
| MCP clients and connectors | Current tool inventory, request authentication, bounded results | Reconnect and enumerate tools against the candidate; test actual calls. Connector OAuth remains separate from existing bearer support. |
| Event and session readers | Lists, exact-event retrieval, transcript, session links, and saved synthesis | Preserve fields, types, redaction, full accepted payloads, original paths/timestamps, and cross-session source navigation. No caller should need local filesystem access for remote evidence. |
| Streaming consumers | `/api/stream`, event/session/judgment frames and reconnect behavior | Authenticate stream requests; test heartbeats, restart/reconnect, buffering and idle timeouts through the deployed HTTPS proxy. Do not restore retired task frames. |
| Health and telemetry clients | Public minimal health and protected diagnostic surfaces | Replace local process/TCP/log assumptions with reachable health checks and protected log delivery. Verify alerting and redact operational logs. |
| Experimental relays | Their explicitly selected subset of reads/writes/streaming | Choose migration or retirement; verify access and provenance before exposing or retiring a route. |

Use the existing public health routes documented in [authentication](../../authentication.md),
including GET `/actuator/health/liveness` and `/actuator/health/readiness`. This draft does not add
`/healthz` or `/readyz` aliases. Never place credentials in URLs, logs, checked-in configuration,
or captured events.

## Migration and acceptance

A hosted instance using a new empty database is a deployment, not a migration. The importer below
is proposed; it is not an existing `SbaCli` command.

1. Select a reviewed source revision and operator-approved deployment. Verify PostgreSQL profile,
   authentication, secure browser cookies, protected machine APIs, and isolation of the internal
   listener. Cloud-image startup enforcement and authenticated consumer acceptance are separate
   release checks; do not infer them from this design or an image label.
2. Inventory source data and consumers; take and restore-test a backup before any destructive step.
   Determine whether retained legacy task data is archived, left in the source backup, or explicitly
   retired. Board removal alone is not permission to drop it.
3. Build a resumable, idempotent importer using the same accepted-payload transformation as ingest.
   Preserve canonical events and sessions, `human_turn_state`, capture receipts, judgments,
   `project_aliases`, `decision_replacements`, session links, and saved synthesis with its input
   provenance. Ideas remain structured events. Preserve original repo paths rather than rewriting
   them to server paths. Validate foreign-key ordering and replacement relations after import.
4. Decide how embeddings and session summaries migrate. A model/dimension change requires a
   separately measured re-embedding plan; do not imply session-summary vectors are returned by
   recall. Keep optional indexing failures separate from canonical import success.
5. Compare source and destination using per-table counts, relation integrity, deterministic payload
   checksums, and a fixed retrieval set. Explicitly test aliases, same-topic projects, unknown
   projects, replacement history, legacy callers, full-record fetch and source links. Investigate
   every unexplained retrieval difference; ranking overlap alone is insufficient acceptance.
6. Exercise real browser login/CSRF, bearer REST/MCP, invalid credentials, SSE restart/reconnect,
   and persistence on a disposable PostgreSQL server. Then repeat relevant calls through the
   deployed proxy with actual selected consumers; local acceptance is not remote acceptance.
7. Demonstrate phone use with the workstation asleep, capture during an outage, acknowledgement-loss
   retry, and later queue replay. Record latency, delivery gaps, queue limits, and untested paths.
8. For an approved cutover, pause writes to the old authority, import the final delta, verify it,
   then switch selected clients and credentials. Document pending queues bound to the old origin;
   changing a URL does not move those rows. Rollback must preserve new writes and avoid two writable
   authorities. Keep the original database/archive until its retention and disposal are approved.

The importer must not silently discard supersession evidence: a decision replaced outside the
current query/window must stay excluded from normal recall after migration. Preserve original event
bytes and the append-only relation; infer no new replacements from recency.

## Operations and open owner decisions

Use encrypted transport, private storage, least-privilege deployment credentials, durable backups,
and tested restoration. Monitor failures, queue age, rejected/paused captures, pending payloads,
authentication failures, and SSE availability. Retention and payload cleanup must respect canonical
receipts and source references. Any object-storage cost estimate must use the actual GET/PUT/storage
and transfer rates of the selected region and workload; no current monthly cost is asserted here.

One replica remains the limit. JVM-local alias coordination, in-process SSE, scheduled work, and
in-memory browser sessions still need a separate design for multiple instances. Retired runner
locks are not a present deployment constraint.

Before provisioning or accepting the larger architecture, resolve:

1. Compute service within the recorded AWS us-east-2 direction, availability needs, backup
   retention, a current measured monthly budget, and provisioning.
2. Whether the simpler PostgreSQL deployment meets the immediate need, or payload splitting and
   new queues justify their operational and migration cost now.
3. Hosted versus workstation model work, embedding model/dimensions, privacy/egress, and cost.
4. Transcript retention: measure capture completeness against the recorded event-backed direction
   and accepted intermediate-prose loss. Resolve any newly discovered additional loss explicitly
   before removing local-file hydration.
5. The actual connector/authentication requirements and the migration or retirement of experimental
   relays. Decide access changes from the verified deployment inventory, not from this public draft.

These decisions do not block review of this proposal. They do block claiming that the broader cloud
service is provisioned, migration-ready, or verified from a phone with the workstation asleep.
