# Personal cloud Black Box — design

> NAT-243 update (2026-10-02): session lineage now belongs to `dev.nathan.sbaagentic.lineage`; the board, task/spec APIs, and runner are retired. Earlier workflow/runner package names and task tables in this dated design are historical, not current implementation instructions. See [retirement notes](../../board-retirement.md).

Date: 2026-09-29 · Status: draft for owner review · Supersedes for personal use: the tenancy/lifecycle
scope of `docs/superpowers/plans/2026-09-15-cloud-lifecycle-and-tenancy.md` (that plan stays the
reference if Black Box ever becomes multi-tenant).

## Intent

**Goal (owner's words):** work from any surface. Black Box must be reachable from claude.ai web and
mobile, Claude Desktop, cloud Claude Code sessions, ChatGPT, Codex, and the Mac — with the Mac asleep —
without latency becoming a drag.

**Success criteria**

1. From the phone with the Mac asleep, through the claude.ai connector: search records, get project
   context (backed by `recallContext`), open a record including full tool output on demand, and
   capture a decision.
2. When the Mac wakes, its queued captures land in the cloud with no duplicates.
3. Every existing consumer (see *Consumer contract*) keeps working after pointing at the cloud URL.
4. Claude/Codex turns are not slowed: hook capture stays off the turn's critical path.

**Non-goals (this version):** multi-tenancy, customer lifecycle, multiple replicas / load balancer,
porting Ask, the transcript view's local-JSONL merge arm, pause/resume-to-zero.

## Evidence base

Every claim below was traced through call paths (T) or measured on the live DB (M) on 2026-09-29.
Code paths are in the `idea-kind` worktree, which is what the running jar
(`target/sba-agentic-0.1.0.jar`, built 2026-09-28 16:18) was built from — **not** the main checkout.

**Volume (M).** 394k events; 90% are `PostToolUse`. Last 14 days: 81.5k events, 1.2 GB of payload
columns (tool_output 339 MB, tool_input 61 MB, metadata 487 MB, text 328 MB); peak day 22.4k events.
14.9% of tool outputs exceed 6,000 chars; 0.45% exceed 20,000; the largest is 130 KB.

**Duplication (M).** For tool events, `text` equals `tool_output_json` in 2,870 of 3,000 sampled rows,
and 100% carry the payload again in `metadata_json.rawHook` (`tool_input`, `tool_response`,
`toolInput`, `toolResult`). No code reads `rawHook` except the transcript service's equality check (T).

### Lineage of derived artifacts

| Artifact | Producer · trigger | Dependency | Consumers (T) | Rate (M) |
|---|---|---|---|---|
| Raw tool payloads | `EventIngestService` on ingest (redacted, not truncated) | none | Stream/transcript UI + presenters (`JSON.parse` of full payload), project timeline, `searchSessions`, BeatFolder (parses full output for first line), FTS slices | ~5.8k events/day |
| `event_fts` | SQLite triggers | none | UI stream search and facets only. Indexes `text` + output[0:6000] + metadata[0:4000] + input[0:2000]. MCP tools do not use it; disabled on Postgres (`EventFtsIndex.java:75`) | every event |
| ES `sba-agentic-events` | `ElasticIndexClient`, synchronous per event, best effort | local Elasticsearch | `searchSessions`, `searchContext`, autocomplete. Already drifted: 276k docs vs 394k rows | every event |
| Session summary | `SessionFinalizationService` on session stop, once only (`hasSummary` guard) | Codex via `summarize-with-codex.sh` | Session listings (`recentSessions`, `/api/sessions`, SessionsPage), AI title, melds, Obsidian export. **Not** recall, search, or Ask | ~51/day; 79% coverage |
| AI title (rank 100) | first non-generic line of the summary; no extra call | same as summary | every session list, stream header, judge state | with summary |
| `event_judgments` | `JudgmentListener` beats (12 events / 1,500 chars / 4 s gap), single-thread executor | jev at `api.typesafe.ai`, 12 s timeout | **Constellate Orbit** (`/api/sessions/{id}/judgments`, SSE `judgment.appended`; Cortex phase/salience/novelty/kinship). No Black Box UI reader | ~2–9.5k events/day |
| `memory_embeddings` (event) | `EmbeddingIndexer`, synchronous at ingest, decision/handoff/observation/idea only | **Ollama** `nomic-embed-text` :11434, 5 s timeout | `recallContext` semantic arm only (kNN, floor 0.61) | 14–64/day; 1,679 rows |
| `memory_embeddings` (session_summary) | same | same | **none** (kNN filters to `event:` keys) | 6,259 rows, unread |
| Capture receipts | `persistIdempotentEvent`, same transaction | none | replay branch of `/api/events/idempotent` | 1 row live |

**Read-path dependencies (T).**
- `recallContext`: structured events in window + session cwd (lexical LIKE), plus event embeddings
  (semantic), fused by RRF. It degrades to lexical when embeddings are unavailable.
- `searchContext`: LIKE over text/tool_name/full metadata + ES when unfiltered; returns `text[:601]`.
- Ask: a separate ES index `agent-memory` fed by `ask-my-history`, Ollama query embedding and
  LM Studio synthesis. It does not use Black Box's database.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Cloud is the single source of truth; the Mac is a client with an outbox | the "Mac as authority" model fails whenever the Mac sleeps |
| D2 | AWS us-east-2, reusing job-scout's `bootstrap` (state bucket, KMS, MFA operator) and `cost-guard` (budgets) | existing account and guardrails |
| D3 | One always-on instance + managed Postgres; no load balancer | single user; replicas buy only zero-downtime deploys |
| D4 | Tool events split at capture: structured projection in Postgres, full raw payload in S3 | UI and BeatFolder need parsed fields, not byte prefixes; the DB holds what is retrievable |
| D5 | Drop the `text` copy of tool output and `rawHook`; keep a small metadata projection | ~1.4× output volume is pure duplication |
| D6 | Postgres `tsvector` search replaces SQLite FTS, the LIKE arms, and ES `sba-agentic-events` | one index, no silent drift, no synchronous side-writes |
| D7 | Embeddings run in the cloud on a hosted model, asynchronously | half of `recallContext`; phone captures must be recallable immediately |
| D8 | Summaries and AI titles run on a Mac Codex worker that claims jobs from a cloud queue | used only for listings and titles; no per-token cost; lag is harmless |
| D9 | Judgments migrate unchanged (jev), made asynchronous | Constellate consumes them; the per-call cost is the same from the cloud |
| D10 | Single-user OAuth for connectors; a static bearer for machine clients | claude.ai and ChatGPT require OAuth; hooks and CLIs need a non-interactive credential |
| D11 | The ChatGPT gateway's contract is ported into the app; the tunnel and gateway are retired | one public endpoint serves every client |

## Architecture

```
 phone / claude.ai / Desktop / cloud Claude Code / ChatGPT        Mac
        │  OAuth (MCP connector)                          hooks · CLIs · Codex · Constellate
        ▼                                                         │ bearer, via outbox
 ┌──────────────────────── AWS us-east-2 ─────────────────────────▼──────┐
 │  Black Box app (Spring Boot, 1 container, HTTPS)                      │
 │   ├─ REST /api/**  (existing contract)                                │
 │   ├─ MCP /mcp      (existing tools + 4-tool public connector surface) │
 │   ├─ OAuth AS      (single user; DCR + PKCE + RFC 9728 metadata)      │
 │   ├─ ingest → projection → Postgres ; raw → S3                        │
 │   └─ async workers: embeddings (hosted), judgments (jev)              │
 │  Postgres (managed, encrypted)      S3 (private, SSE-KMS, versioned)  │
 │  job queue table (FOR UPDATE SKIP LOCKED)                             │
 └───────────────────────────────▲───────────────────────────────────────┘
                                 │ claims summarize jobs, posts results
                        Mac worker: Codex summaries + titles
```

### Components

1. **Ingest and projection** (`recording`). On `POST /api/events`, parse the tool payload once and
   write:
   - `agent_events` row: identity, type, role, tool_name, human_text, and a **projection**
     `{exit_code, is_error, first_line, last_line, output_chars, input_chars, input_summary,
     output_head (≤6,000 chars), truncated}`;
   - a metadata projection `{transcript_path, cockpit_capture, agentType, parentClientSessionId,
     title}`;
   - the full payload object `s3://…/events/{yyyy}/{mm}/{event_id}.json.zst` with `payload_ref`.

   For non-tool events, `text` is kept (≤20,000 chars, as today). The S3 write happens before commit.
   If it fails, the event commits with `payload_pending=true` and the payload goes to an in-DB retry
   table, so ingest never blocks on S3.
2. **Payload fetch.** `GET /api/events/{id}/payload` streams the raw object. The feed, transcript, and
   project endpoints return projections. Presenters render from the projection and lazily fetch the
   full payload for Read content, Edit diffs, and expanded long output.
3. **Search.** A `tsvector` generated column over `text || tool_name || output_head || input_summary`,
   with a GIN index. It serves UI search and facets, `searchSessions`, and `searchContext`. ES
   `sba-agentic-events` is removed from the app.
4. **Embeddings.** Enqueue on structured-capture ingest; a worker embeds with a hosted 768-dim
   model. Existing event vectors (~1,700) are re-embedded once at migration. Session-summary vectors
   are not migrated or produced. At this scale, brute-force cosine in the app stays (no pgvector).
5. **Judgments.** `JudgmentListener` is unchanged except that jev calls run from a DB-backed queue
   instead of the in-memory 256-slot drop-oldest executor. The REST routes and SSE
   `judgment.appended` are preserved for Constellate.
6. **Summaries.** Session stop enqueues `summarize(session_id)`. The Mac worker claims the job, pulls
   the session's events (projections), runs Codex, and posts the summary and title back. Fix the
   once-only bug: Codex fires `Stop` every turn, so re-enqueue on `SessionEnd` or after 30 min idle,
   and replace the summary if the event count grew by more than 20%. Also drain the backlog of 1,635
   sessions without a summary.
7. **Auth.** Spring Authorization Server, single user (the owner's login), issuing tokens for the MCP
   connector; protected-resource metadata at `/.well-known/oauth-protected-resource`, callback
   `https://claude.ai/api/mcp/auth_callback` plus ChatGPT's connector callback (exact URL confirmed in
   the plan against OpenAI's connector docs). The existing agent bearer token
   continues for machine clients. Both converge on the same principal.
8. **Public connector surface.** Port the gateway's four tools (`search_records`, `fetch_record`,
   `project_context`, `append_capture`) with bounded inputs and idempotent capture onto the existing
   `event_capture_receipts`. Provenance: `source` per client (`claude-connector`, `chatgpt-work`),
   `integration=blackbox-cloud-mcp-v1`. The full MCP tool set stays available to bearer clients.
9. **Mac client side.**
   - `cockpit-agent-hook` already posts in a detached process. It gains an outbox: on failure it
     appends to `~/.local/state/blackbox/outbox.jsonl`, and a launchd job replays with idempotent
     capture IDs.
   - The summary worker is a launchd job.
   - The runner, LM Studio use, and Ask stay local.

### Consumer contract (must keep working)

Identified by grep across `~/Developer/proj` and `~/.local/bin` (endpoint-level tracing is plan step 1):

- **Cockpit hooks and CLIs:** `cockpit-agent-hook`, `cockpit-agent`, `cockpit-codex-voice-capture`.
- **MCP configs:** `~/.claude.json`, `~/.codex/config.toml`.
- **Constellate** (judgments, SSE).
- **Other projects:** career-flightdeck, employment, switchboard, exec-agent, raycast-scripts, and
  mcp-memory-agent-dna-experiment.
- **The ChatGPT gateway**, which gets retired.

Each consumer moves by changing `SBA_AGENTIC_URL`/base URL plus credential; no route renames.

### Latency budget

- Region us-east-2; expected Mac↔cloud RTT 20–40 ms.
- Hook capture is detached (`start_new_session=True`), so it adds 0 ms to turns.
- Targets: MCP read tools p95 < 400 ms end to end; ingest p95 < 250 ms excluding async workers.
- `searchContext` must not scan payloads (projection-only tsvector).
- Measured in plan verification; a miss is a release blocker.

## Migration

1. **Reconcile source.**
   - Merge the `idea-kind` worktree work that production runs into a branch with an upstream.
   - The cloud build comes only from that branch.
   - Enable the Postgres contract tests by default in CI.
2. **Provision** (Terraform in the reused bootstrap):
   - one container service;
   - managed Postgres, encrypted, automated backups;
   - an S3 bucket (private, versioned, SSE-KMS);
   - secrets;
   - a budget alarm.
3. **Importer** (`SbaCli import-sqlite`): streams SQLite rows and applies the same projection split
   as live ingest. It uploads raw payloads to S3 and drops `rawHook` and text duplicates. It copies
   sessions (summaries, titles, ranks), judgments, receipts, tasks, specs, ideas, links, and aliases.
   It is idempotent by event ID and resumable.
4. **Verify:**
   - row counts per table;
   - checksums for 1% sampled raw payloads (S3 object vs SQLite source);
   - `recallContext`/`searchContext` parity on a fixed query set (≥ 90% overlap in top 10);
   - Constellate Orbit renders a live session.
5. **Cutover:**
   - Freeze the local service.
   - Run a final delta import.
   - Switch the consumers' base URLs.
   - Keep local SQLite read-only as a 30-day fallback, then archive it (not deleted without explicit
     approval).
6. **Retire:** the ChatGPT gateway and tunnel launchd jobs, local ES `sba-agentic-events`, and the
   Ollama dependency for Black Box.

## Operations and security

- **Backups:** Postgres automated backups (7-day) plus a weekly logical dump to S3. S3 is versioned.
  The restore procedure is tested once before cutover.
- **Monitoring:** `/healthz` and `/readyz` probed externally; alert on 5xx rate, queue age > 1 h, and
  `payload_pending` > 0 for 15 min.
- **Exposure:** one public HTTPS endpoint. OAuth or bearer is required on every route except health.
  Redaction at ingest is unchanged. S3 is never public; payloads are served through the app.
- **Cost envelope (to confirm in plan):** container + Postgres ≈ $30–45/mo (the 09-15 Lightsail
  prototype measured $31.20), S3 < $1/mo, hosted embeddings < $1/mo, jev unchanged.

## Replica-readiness (deferred, recorded)

Blocking a second instance (T):
- JVM-local alias lock (`ProjectAliasService`);
- in-process SSE broadcaster;
- `@Scheduled` beats;
- runner file lock and registry;
- in-memory browser sessions.

This design does not worsen any of them. The queue table and projection split are replica-safe.

## Testing

- Unit: projection extraction per tool type (bash, read, edit, write, apply_patch, failure), including
  malformed and oversized payloads.
- Contract: the Postgres suite on by default; importer round-trip on a fixture DB.
- End to end: from the phone with the Mac asleep — search, recall, fetch full payload, capture (plus
  a retry of the same capture ID); from the Mac — outbox replay after a simulated outage; Constellate
  live judgments.

## Open questions

1. Compute: a Lightsail container (proven here on 09-15) vs ECS Fargate — pick in the plan on cost and
   OAuth callback/TLS fit.
2. Hosted embedding model and whether 768 dims are kept — pick in the plan; re-embedding is required
   either way.
3. Whether any consumer in the contract list reads full payloads from list endpoints. Plan step 1
   traces each at endpoint level.
