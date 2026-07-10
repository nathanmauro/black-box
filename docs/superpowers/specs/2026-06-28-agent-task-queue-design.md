# Black Box Shared Agent Task Queue — Design

- **Date:** 2026-06-28
- **Status:** Implemented on 2026-07-10; integrated release validation remains.
- **Historical design branch:** `feat/agent-task-queue`
- **Implementation record:** [`../plans/2026-07-10-agent-task-queue-implementation.md`](../plans/2026-07-10-agent-task-queue-implementation.md)
- **Repo:** `sba-agentic` (product: Black Box)

## Implementation status (2026-07-10)

This design is now implemented. The shipped substrate uses `specs`, `tasks`, and `task_events` in
SQLite; seven matching REST/MCP operations; a single-statement atomic lane claim; explicit
block/reset/cancel/complete transitions; a normal recallable Handoff on completion; best-effort
`task.*` SSE hints; and the SolidJS `/board` route. The original reasoning below is retained as the
decision record. Where exploratory details changed, the as-built correction is called out in place
and the implementation record is authoritative.

## 1. Problem

Black Box already captures **continuity** (decisions, handoffs, observations) and lets agents
recall each other's reasoning at runtime. What it does not yet own is **coordination**: a shared,
trackable place where heterogeneous agents (Claude, Codex, Grok, Hermes) and humans put *work to be
done*, claim it, and drive it to completion — a programmatic kanban any machine can use, without
changing how each individual agent works internally.

The goal: extend Black Box into the shared **task queue / spec tracker** that turns isolated agent
runs into a self-driving loop — **finish → handoff → enqueue follow-up → next agent claims →
executes → repeat** — while Black Box stays a passive *coordination substrate* and never becomes an
executor.

## 2. Core insight

**Black Box is already a pub/sub bus.** It has the three organs a shared queue needs:

- a **write surface** — the MCP `@Tool`s + the mirrored REST API — where any agent commits
  structured intent;
- a **projection/query surface** — `recall` / `search` — that reads intent back; and
- a **live push channel** — the SSE `GET /api/stream` the web UI already subscribes to.

Decisions/handoffs/observations are *immutable facts*. A **task is the same kind of structured
intent with one added property: a mutable lifecycle** (open → in_progress → done/blocked). That
single difference — mutable state on top of an append-only recorder — is the entire design problem,
and it resolves with the **blackboard pattern**: a shared board agents read and write, coordinating
*through the board, never through each other*. Execution order emerges from the state of the board,
not from a hardwired pipeline.

This is independently the architecture Hermes' `~/.hermes/kanban.db`, `agent-kanban`, and (at
cluster scale) Temporal all converged on. "Substrate, not executor" in practice means **OS-process
workers pull from a shared DB** — exactly what we keep.

## 3. Constraints & non-goals

**Constraints**

- **Simplicity is king.** Prefer reusing existing patterns (event log, SSE, `@Transactional`
  JdbcTemplate, `ON CONFLICT` upserts, `schema.sql` + `ensureSchema()`) over new abstractions.
- **Local-first, single node.** One machine, one SQLite file (`sba-agentic.db`, WAL), `localhost:8766`.
- **Substrate, not executor.** Black Box records and surfaces tasks. Its coordination path **never
  launches worker agents, spawns worker processes, executes task commands, or mutates a checkout or
  an agent's internals.** Agents pull; Black Box does not push work. Configured external session
  summarization is a separate existing path that may invoke a Codex or Claude CLI command through
  `/bin/sh -c` and transmit transcript text.
- **Language-neutral interface only.** Heterogeneous agents interoperate solely over **MCP +
  REST**. Anything an agent needs to act must travel through that interface as plain data — no
  assumption of a shared runtime, git client, or filesystem layout.

**Non-goals (this slice)**

- No message broker (Redis/NATS/Kafka). SQLite *is* the queue.
- No runner/daemon that auto-runs agents (deferred — see §11).
- No multi-node or cloud queue service, and no auth model beyond what already exists. Session
  summary backend/privacy behavior is a separate existing boundary.
- Not a replacement for Todoist (human reminders) or Obsidian (durable narrative). This is the
  **agent execution layer**; the prior ownership split stands (Obsidian = story/decisions,
  Todoist = personal reminders, Black Box = continuity **and now cross-agent execution state**).

## 4. Decisions

Each decision below is settled. Rationale and the rejected alternative are recorded so a later agent
does not re-litigate without new evidence.

### D1 — SQLite is the queue; no broker. (settled)
**Why:** For a handful of agents on localhost, a broker is pure overhead and is *fire-and-forget* —
a weaker durability guarantee than the SQLite file we already trust. The workload is single digits.
**Rejected:** Redis/NATS pub/sub (adds an external dependency,
reintroduces the dual-write desync problem, violates local-first + simplicity).

### D2 — Hybrid state: mutable row + append-only event log. (settled)
**Why:** The `tasks` row is the cheap "what's the board right now" the kanban view and the claim
query need; an append-only lifecycle log keeps Black Box a *recorder* with a full audit trail. Row
update + event append happen in **one `@Transactional` SQLite call**, so they cannot desync (the
dual-write problem that plagues this pattern in distributed systems is solved for free on one file).
**Rejected:** pure event-sourcing (every "next eligible task" query folds the whole log — too slow,
over-engineered for queue semantics); pure mutable row (loses Black Box's recorder value and audit
trail).

### D3 — Lifecycle events get a dedicated `task_events` table, NOT `agent_events`. (settled — corrects an early assumption)
**Why:** `agent_events.session_id` is `NOT NULL` with a FK to `agent_sessions`, and everything
written through `ContextService → EventIngestService.ingest` *requires* a `source` +
`clientSessionId` and creates/attaches a session. **A task is project-scoped, not session-scoped** —
there is no natural session for `task.created`. Shoving task events into `agent_events` would force a
synthetic pseudo-session that pollutes the sessions list, or relaxing an existing NOT-NULL invariant.
A dedicated `task_events` table is cleaner and keeps the sessions UI uncluttered. **Rejected:**
"task events ride on `agent_events` for free" — this was the most load-bearing wrong assumption in
early framing; it is false.

### D4 — Spec body lives in the DB; file pointer is optional provenance only. (settled — answers "pointers vs DB table")
**Why:** A `spec.body` (`TEXT NOT NULL`, frozen at enqueue) is canonical and **language-neutral** —
a Grok/Hermes/Claude agent claiming over MCP receives the full spec text in the claim response with
no git checkout required. A file pointer (`spec_ref` = optional JSON `{repo, path, sha}`) is
**copy-on-enqueue provenance metadata, never a runtime lookup.** **Rejected:** file-as-source-of-
truth with the DB holding only a pointer — breaks the language-neutral constraint (no checkout to
resolve path+sha at claim time) and rots across dirty worktrees, branch switches, moved files, `git
gc`, and force-pushes. Reuses the proven `session_melds.body TEXT NOT NULL` precedent.

### D5 — Subscribe = the existing SSE stream, used as a hint. (settled)
**Why:** Add `task.*` frames to the channel the UI already holds. Agents/runners treat SSE as
"something changed, try to claim," then **pull-claim against the DB** — *notify is a hint, the DB is
the truth.* No new transport. **Rejected:** a real broker for delivery (see D1); push-to-agent
(creates coupling and would make Black Box an executor).

### D6 — Separate `specs` + `tasks` (one spec → many tasks). (owner choice)
**Why:** Honors the original "tracked spec in a DB" intent and matches a real spec tracker: a single
definition (an epic / feature / investigation) can fan out into multiple units of claimable work.
**Rejected (for this project):** body-on-the-task single table (simpler, but collapses the
spec/task distinction the owner explicitly wants).

### D7 — Required lane per task; agents claim only their own lane. (owner choice)
**Why:** Every task names a **lane** (`TEXT NOT NULL`) = the capability/agent that should do it
(`'codex'`, `'claude'`, `'grok'`, `'hermes'`, or a logical capability name). An agent's claim is
scoped `WHERE lane = :lane`, so a greedy idle agent can never grab work shaped for another. Routing
intent is decided at enqueue, not contended at claim. **Rejected:** optional lane / `NULL = anyone`
(greedy cross-claims) and soft-preference lanes (more logic than the MVP needs). `lane` is a free
string in MVP; an enumerated capability registry is a phase-2 concern.

### D8 — Trimmed MVP, proven by hand. (owner choice)
The first branch ships the smallest thing that proves the loop end-to-end, walked manually across
two MCP sessions. Lease/heartbeat/reaper, fencing token, `depends_on` DAG, capability discovery, and
runner daemons are deferred **as a matched set** (see §11). Pulling any one forward in isolation is a
trap — e.g. a `depends_on` column the claim query ignores is worse than no column.

## 5. Data model

All tables are created idempotently in `src/main/resources/schema.sql` (`CREATE TABLE IF NOT
EXISTS`), which `EventRepository.ensureSchema()` already runs on every boot. PKs are UUID strings;
timestamps are ISO-8601 strings (`Instant.toString()`), matching existing tables.

```sql
-- The work definition. One spec fans out into many tasks.
CREATE TABLE IF NOT EXISTS specs (
    id           TEXT PRIMARY KEY,
    project_key  TEXT NOT NULL,            -- same project key the Projects/melds surfaces use
    title        TEXT NOT NULL,
    body         TEXT NOT NULL,            -- canonical, language-neutral spec text (frozen at create)
    spec_ref     TEXT,                     -- optional provenance JSON {repo, path, sha}; NEVER resolved at runtime
    status       TEXT NOT NULL,            -- active | done | archived
    created_by   TEXT NOT NULL,            -- source that created it (claude|codex|grok|hermes|manual)
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

-- A claimable unit of work under a spec.
CREATE TABLE IF NOT EXISTS tasks (
    id                TEXT PRIMARY KEY,
    spec_id           TEXT NOT NULL,
    project_key       TEXT NOT NULL,       -- denormalized from spec for cheap board filtering
    title             TEXT NOT NULL,
    lane              TEXT NOT NULL,       -- required routing target (D7): which agent/capability
    status            TEXT NOT NULL,       -- open | in_progress | blocked | done | cancelled
    priority          INTEGER NOT NULL DEFAULT 0,
    created_by        TEXT NOT NULL,
    claimed_by        TEXT,                -- the agent holding it (NULL until claimed)
    blocked_reason    TEXT,                -- set when status=blocked
    result_handoff_id TEXT,               -- the Handoff event id emitted on completion (the loop's hinge)
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (spec_id) REFERENCES specs(id)
);
CREATE INDEX IF NOT EXISTS idx_tasks_claimable ON tasks (lane, status, priority DESC, created_at ASC);

-- Immutable lifecycle log (the recorder half of the hybrid; the SSE source for task.* frames).
CREATE TABLE IF NOT EXISTS task_events (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL,
    type        TEXT NOT NULL,            -- task.created | task.claimed | task.blocked | task.completed | task.reset | task.cancelled
                                          --   (claim emits task.claimed with to_status='in_progress'; no separate in_progress frame in MVP)
    actor       TEXT NOT NULL,           -- the source/agent that caused the transition
    from_status TEXT,
    to_status   TEXT,
    detail_json TEXT,                     -- optional structured detail (reason, handoff id, etc.)
    observed_at TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);
CREATE INDEX IF NOT EXISTS idx_task_events_task ON task_events (task_id, observed_at DESC);
```

**Status enum reserved value:** `claimed` is intentionally *not* used in the MVP (claim transitions
`open → in_progress` directly, since the claiming agent is the working agent and there are no leases
yet). `claimed` is reserved in the enum for phase-2 lease semantics (a short pre-work hold) so the
phase-2 migration is additive, not a rename.

## 6. Lifecycle & transitions (MVP)

```
            enqueueTask                 claimNextTask(lane)            completeTask
   (none) ───────────────▶  open  ──────────────────────────▶ in_progress ─────────────▶ done
                             ▲                                     │
                             │ updateTaskStatus(reset)             │ updateTaskStatus(blocked, reason)
                             └─────────────────────────────────────┘
                                                                   ▼
                                                                blocked ──(reset)──▶ open
   any non-terminal ──updateTaskStatus(cancel)──▶ cancelled
```

- **`open → in_progress`**: the atomic claim (§7). Sets `claimed_by`.
- **`in_progress → done`**: `completeTask` emits a Handoff and records `result_handoff_id`.
- **`in_progress → blocked`**: `updateTaskStatus` with a `blocked_reason`. Blocking is a *first-class
  visible state* (invisible stalls are the most common queue failure mode), broadcast immediately.
- **`blocked|in_progress → open`**: manual "reset to open" (the MVP stand-in for an auto-reaper).
- **`* → cancelled`**: terminal, owner/agent abandons the task.

Every transition writes one `task_events` row in the same transaction as the `tasks` row update.
After that durable mutation returns, the service publishes one best-effort SSE frame. A disconnected
subscriber or publish failure never rolls back committed state; clients recover from REST.

## 7. The atomic claim (the one piece of real queue machinery)

A single compound statement. SQLite's single-writer (WAL) serializes writers, so candidate selection
and the update are atomic with zero time-of-check/time-of-use gap — **two agents cannot claim the
same row**, and no `BEGIN IMMEDIATE`, `SKIP LOCKED`, or distributed lock is needed.

```sql
UPDATE tasks
SET status = 'in_progress',
    claimed_by = :agent,
    updated_at = :now
WHERE id = (
    SELECT id FROM tasks
    WHERE status = 'open' AND lane = :lane
    ORDER BY priority DESC, created_at ASC
    LIMIT 1
)
RETURNING *;
```

The follow-up `task_events` insert (`task.claimed` / `task.in_progress`) runs in the same
`@Transactional` method. A second agent racing for the same lane simply gets **0 rows back** and
retries the next eligible task.

> **Day-0 de-risk (do this before anything else):** xerial `sqlite-jdbc` surfaces `RETURNING` rows
> via `executeQuery`/`ResultSet`, not `executeUpdate`. Issue the claim through
> `jdbcTemplate.query(sql, rowMapper, args)` (or `queryForObject`), **never** `jdbcTemplate.update()`
> — the latter can swallow the `RETURNING` payload, and behavior varies by driver version. A
> ~30-minute spike that confirms a row comes back protects the entire queue. If the bundled driver
> misbehaves, fall back to `BEGIN IMMEDIATE; SELECT…LIMIT 1; UPDATE…WHERE id=? AND status='open';`
> inside one transaction (still race-free, just two statements).

## 8. Components & boundaries

Mirrors the existing split where `AgenticTools` (MCP) and `AgenticController` (REST) both delegate to
one service (`ContextService`). **One service layer is the single source of truth; MCP, REST, and the
Board UI all observe identical state.**

| Unit | What it does | Interface | Depends on |
|---|---|---|---|
| `schema.sql` (+`ensureSchema`) | Creates `specs`/`tasks`/`task_events` on boot | DDL run at startup | existing boot path |
| `TaskRepository` | All SQLite reads/writes incl. the atomic claim | `@Transactional` methods over `JdbcTemplate` | `JdbcTemplate`, `ObjectMapper` |
| `TaskService` | Business logic: validate, transition, emit lifecycle event + SSE, call `captureHandoff` on complete | plain methods returning typed records | `TaskRepository`, `ContextService`, `EventBroadcaster` |
| `AgenticTools` (extend) | MCP verbs: `createSpec`, `enqueueTask`, `claimNextTask`, `updateTaskStatus`, `completeTask`, `listTasks`, `getSpec` | `@Tool`-annotated methods | `TaskService` |
| `AgenticController` (extend) | REST mirror: `POST /api/specs`, `POST /api/tasks`, `POST /api/tasks/claim`, `PATCH /api/tasks/{id}`, `POST /api/tasks/{id}/complete`, `GET /api/tasks`, `GET /api/specs/{id}` | HTTP | `TaskService` |
| `StreamEvents` / `EventBroadcaster` (extend) | New `TaskChanged` record + `publishTaskChanged(...)` | SSE frames `task.created`/`task.claimed`/`task.blocked`/`task.completed`/`task.reset` | existing emitter list |
| Board UI (Solid) | Renders a REST snapshot of tasks by status column (lanes as a facet); applies `task.*` frames as a live diff; "reset to open" button | `EventSource('/api/stream')` + REST | existing SSE client |

**SSE note:** `EventBroadcaster.index(AgentSession, AgentEvent)` is the *ingest* sink and is
session/event-shaped — it does **not** fit task events. Add a separate public
`publishTaskChanged(StreamEvents.TaskChanged)` that the `TaskService` calls directly after the
transactional write. Best-effort, never breaks the write — same contract as today.

## 9. The autonomy loop contract (made explicit)

The whole point of the feature. `completeTask` is the hinge that turns finished work into the next
agent's input:

1. Agent B (claimed the task) finishes the work.
2. `completeTask(taskId, agent, summary, openLoops, nextAction)` →
   - emits a **Handoff** through the existing `ContextService.captureHandoff` using **B's own
     `clientSessionId`** (this works with the current session-bound event model — the handoff is a
     normal `agent_events` row);
   - sets `tasks.result_handoff_id` to that handoff event's id;
   - transitions the task to `done` (+ `task_events` row + SSE).
3. A follow-up `enqueueTask` (by B, or by a human, or by an orchestrator) creates the next task and
   may **embed the upstream `result_handoff_id` in its spec body**. Black Box does not enqueue the
   follow-up automatically.
4. Agent C calls `claimNextTask(lane)`, receives the body, and **resolves the upstream result via
   `recallContext`** (the handoff is already recallable) before executing.

This walks **finish → handoff → enqueue → claim → recall → execute** with no new recall machinery —
the queue rides on top of the loop Black Box already owns.

## 10. Implemented MVP scope

1. `specs`, `tasks`, `task_events` tables via `schema.sql`.
2. `TaskRepository` + `TaskService` (one service layer).
3. MCP verbs + REST mirror: `createSpec`, `enqueueTask`, `claimNextTask`, `updateTaskStatus`,
   `completeTask` (emits Handoff, sets `result_handoff_id`), `listTasks`, `getSpec`.
4. `task.*` SSE frames + `publishTaskChanged`.
5. Solid **Board** view: status columns, lane facet, live diff, manual "reset to open".
6. **Verify-through-use by hand** (no runner daemon): two MCP sessions walk the §9 loop end-to-end.

## 11. Deferred to phase 2 — as a matched set

These only make sense **together**; do not pull one forward in isolation.

- **Lease + heartbeat + reaper** — `claim_expires_at`, the reserved `claimed` state, a sweep that
  returns dead-agent tasks to `open`.
- **Fencing token** — a monotonic `received`/`fence` counter incremented on claim and required on
  every post-claim write, so a resurrected zombie consumer's writes silently affect 0 rows.
  *Meaningless without the reaper — they ship together.*
- **`depends_on` DAG** — a `task_dependencies` join table + the inline `NOT EXISTS (… dep.status !=
  'done')` predicate added to the claim `WHERE` clause **in the same change** + cycle rejection on
  insert.
- **Capability discovery / enumerated lane registry** — agents advertise the lanes they serve.
- **Per-agent runner daemons** — the optional, **strictly external** processes that watch SSE +
  poll `GET /api/tasks?status=open&lane=<x>` every 15–30s (SSE is only the wake hint) and invoke
  their agent. This is where "autonomously driving the system" becomes hands-off.

## 12. Testing & verification

- **Unit (`mvn test`)**: schema creates cleanly; enqueue→claim round-trip; **two concurrent claims
  on one lane → exactly one wins, the other gets 0 rows** (the core safety property); `completeTask`
  emits a recallable Handoff and sets `result_handoff_id`; `blocked` carries a reason; lifecycle
  rows + SSE frames emitted per transition.
- **Day-0 spike**: the `RETURNING` claim returns a row through the bundled xerial driver (§7).
- **Verify-through-use (required, per house rule)**: drive the real `localhost:8766` surface across
  two MCP/CLI sessions through the §9 loop; confirm the Board reflects each transition live. This is
  the use-level proof; unit tests are necessary but not sufficient.
- `git diff --check` on touched files.

## 13. Risks & mitigations (MVP-scoped)

| Risk | Sev | Mitigation |
|---|---|---|
| `RETURNING` swallowed by xerial via `update()` | med | Day-0 spike with `jdbcTemplate.query(...)`; documented 2-statement fallback (§7). |
| Dead agent leaves a task stuck `in_progress` | med | MVP has no lease by design — manual "reset to open" on the Board. Auto-reaper is phase 2. |
| Lifecycle events wrongly attached to `agent_events` | high | **Resolved by D3** — dedicated `task_events` table. |
| `spec_ref` resolved at runtime, breaking heterogeneity | high | **Resolved by D4** — provenance only; `body` is canonical and never nullable. |
| Runner-by-proximity erodes "substrate not executor" | med | No runner in MVP. When built (phase 2), it is strictly external with its own entrypoint; Black-Box-the-server never spawns it. |
| Oversized spec bodies bloat claim payloads | low | No server-side size cap shipped in the MVP; callers should keep the frozen body bounded. A validated limit remains follow-up work. |
| Lane starvation / priority abuse | low | Deterministic `priority DESC, created_at ASC`; explicit lane filters keep one noisy lane from hiding others. Aging deferred. |

## 14. Open phase-2 decisions (recorded, not blocking the MVP)

- **Runner placement** — strictly external (recommended) vs in-repo isolated module.
- **Lease model** — none + manual reset (MVP) vs minimal lease+reaper (phase 2 entry point).
- **Lane taxonomy** — free string (MVP) vs enumerated capability registry.
- **Concurrency policy** — may one agent hold multiple simultaneous claims? Aging for starvation?

## 15. Prior art (validates the direction)

- **Blackboard architecture** (1970s–present; recent LLM-agent work, arXiv 2507.01701): shared
  workspace, indirect coordination, emergent execution order.
- **Hermes Agent kanban** (`~/.hermes/kanban.db`): SQLite, lease+heartbeat, MCP/CLI/REST surfaces,
  OS-process workers — the closest prior art and a direct "substrate not executor" precedent.
- **agent-kanban** (saltbo): SQLite + REST + SSE, `depends_on` DAG, supports Claude/Codex/Gemini —
  matches this design nearly one-for-one (including deferring the DAG).
- **Temporal**: capability-routed task queues, pull model, lease-based — teaches separating
  *capability-to-route* (`lane`) from *current holder* (`claimed_by`). Its weakness (server cluster)
  is exactly what local-first SQLite avoids.
- **SQLite-as-queue** (single compound `UPDATE…RETURNING`, WAL single-writer): the validated
  race-free claim with no broker.
