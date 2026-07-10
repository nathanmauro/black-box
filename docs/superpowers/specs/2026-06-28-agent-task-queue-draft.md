# Task Queue Design for Black Box

> **Superseded.** This is the original exploratory draft and does not describe the shipped API or
> schema. The approved design is
> [`2026-06-28-agent-task-queue-design.md`](2026-06-28-agent-task-queue-design.md); the as-built file
> map and validation plan are in
> [`../plans/2026-07-10-agent-task-queue-implementation.md`](../plans/2026-07-10-agent-task-queue-implementation.md).
> This draft is retained below as design history. Its executor-boundary wording has been clarified
> to distinguish task coordination from the existing configured session-summary subprocess.

Black Box remains a coordination *substrate* and append-only recorder. Its task path never launches
worker agents, executes task commands, or owns the work. All coordination happens over the existing
language-neutral MCP + REST surface and the SQLite event log + SSE. No broker, no new task
executors. Configured external session summarization is separate and may invoke a Codex or Claude
CLI command with transcript text.

## 1. Task table schema + lifecycle states

**Hybrid (mutable row + immutable events).**

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    repo TEXT,
    title TEXT NOT NULL,
    spec TEXT NOT NULL,
    spec_path TEXT,
    spec_sha TEXT,
    priority INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'open',
    claimed_by TEXT,
    claimed_at TEXT,
    lease_until TEXT,
    blocked_reason TEXT,
    depends_on_json TEXT,  -- JSON array of task ids
    created_by TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    metadata_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_status_prio ON tasks (status, priority DESC, created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_repo_status ON tasks (repo, status);
```

States (string, stored in row + mirrored in events): `open`, `claimed`, `in_progress`, `blocked`, `done`, `cancelled`.

**Justification**: A pure mutable row is queryable for kanban lists and claims but loses auditability. Pure event-sourced requires replay for every `list open tasks` — unacceptable for the local surface. The hybrid matches the existing sessions/events pattern: the row is the live aggregate for reads/claims; every transition also produces a structured `agent_event` (event_type="TaskEnqueued", "TaskClaimed", etc.) with the delta in `metadata_json`. This gives recall, history, and SSE for free.

## 2. Concurrency / claim mechanism (single SQLite, no broker)

One `@Transactional` method (pattern from `EventRepository.persistEvent`):

```java
@Transactional
Optional<Task> claimNext(String agentId, String scope) {
    String now = Instant.now().toString();
    // Reclaim any expired leases first (same tx)
    jdbc.update("UPDATE tasks SET status='open', claimed_by=NULL, claimed_at=NULL, lease_until=NULL " +
                "WHERE status='claimed' AND lease_until < ?", now);

    int updated = jdbc.update("""
        UPDATE tasks
           SET status='claimed', claimed_by=?, claimed_at=?, lease_until=?, updated_at=?
         WHERE id = (SELECT id FROM tasks
                      WHERE status='open'
                        AND (repo IS NULL OR repo LIKE ?)
                        AND (depends_on_json IS NULL OR json_array_length(depends_on_json)=0)
                      ORDER BY priority DESC, created_at ASC LIMIT 1)
           AND status='open'
        """, agentId, now, computeLease(now), now, likeScope(scope));

    if (updated != 1) return Optional.empty();
    // fetch the row and also INSERT a structured "TaskClaimed" agent_event in same tx
    ...
}
```

The sub-select + recheck in WHERE + single statement makes the claim atomic. WAL + `busy_timeout=5000` (already set) handles the rare writer contention. No `FOR UPDATE`; SQLite write lock + conditional UPDATE is sufficient. Second agent always sees 0 rows updated.

## 3. Subscribe semantics

**Existing SSE is enough.** Verdict: do not add a broker.

Agents (and the UI) `GET /api/stream`. On `event.appended` (or a lightweight `task.updated` frame) they call the idempotent `claimNext` or `listTasks(status=open)`. 

Rationale: local single node, low latency, low contention. The push merely wakes claimants; the *claim* itself is the authoritative pull. Adding Redis/NATS would violate simplicity, introduce a new durable surface outside SQLite, and contradict "local-first single-node". SSE + atomic claim already gives the observable "enqueue → notified → claimed" loop today.

## 4. Verdict on (A) files/pointers vs (B) DB body vs (C) queue pub/sub

**B primary + optional A pointers. Reject C.**

Store the spec body directly in `spec` (self-describing, searchable via existing recall/search paths, survives cwd moves or git checkouts). Support `spec_path` + `spec_sha` for the case where the authoritative definition is a large on-disk artifact under version control. On ingest we can optionally snapshot a small prefix or just store the pointer.

- Pure A fails: recall and heterogeneous agents without the exact FS tree lose the definition.
- C (real pub/sub queue) is unnecessary and harmful: it would make Black Box own delivery/ack semantics instead of remaining a recorder. The coordination language stays the task row + events over MCP/REST.
- Hybrid B+A is the smallest surface that works for both tiny inline tasks and "implement this 40 kB spec.md at sha X".

## 5. Smallest MVP that proves the loop

- Add the `tasks` table (and indexes) to `schema.sql`; add idempotent ensure in a repo.
- New `TaskRepository` + thin `TaskService` (one transactional path: mutate row + call existing `EventIngestService` with a `Task*` event so SSE and recall light up).
- MCP surface additions (in `AgenticTools`): `enqueueTask`, `claimNextTask`, `updateTaskStatus`, `listTasks`, `releaseTask`.
- Mirror REST: `POST /api/tasks`, `GET /api/tasks?status=open`, `POST /api/tasks/{id}/claim`, etc.
- Emit the events via the existing broadcaster path.
- No UI, no deps enforcement, no priority aging in slice 1.
- Prove with two different "agents" (plain HTTP or a test double): A enqueues, B receives via SSE, claims, updates to done, and a `recallContext` later surfaces the handoff-style event.

This slice touches only new narrow paths and reuses ingest/SSE verbatim.

## 6. Top 3 failure modes / risks and mitigations

1. **Stale claims (dead agent)**: agent claims then crashes.  
   Mitigation: short leases (`lease_until`); any claim or list operation first resets expired leases to `open` inside the same transaction. Add explicit `releaseTask`. Agents are encouraged to heartbeat via a cheap `updateTask` or observation.

2. **Dependency ordering / blocked work**: tasks wait on unfinished deps forever, or cycles appear.  
   Mitigation: `depends_on_json` array + claim predicate that only returns tasks with all deps in `done`. On enqueue, do a cheap cycle check (or punt to later). Keep the check in the claim query so blocked tasks simply stay invisible to claimants.

3. **Starvation + unfairness**: one noisy agent drains the queue.  
   Mitigation: deterministic ORDER BY `priority DESC, created_at ASC` (FIFO under priority). Document that priority is advisory. If abuse appears, later add per-agent claim rate or round-robin token in the selection query. Never implement executor-side scheduling here.

Black Box records and coordinates tasks. It does not run the queued work; configured session
summarization remains a separate subprocess boundary.

(Word count of advice body: 648)
