# Task board and runner retirement

Black Box's dormant task board and runner were removed from `main` in October 2026 (internal
tracker issue NAT-243), after the v0.2.0 release. The maintainer now tracks selected work in Linear;
you can use any tracker, and Black Box does not require one. Black Box continues to capture and recall agent evidence. The change first extracts session
lineage from `workflow` into the dedicated `lineage` module, then removes the task/spec and runner
implementation.

## Preserved contracts

- `POST /api/session-links`, `GET /api/sessions/{id}/links`, and
  `GET /api/session-links/child-counts?ids=...` retain session relationships and child counts.
- `GET /api/dag?sessionId=...` remains the session-only lineage projection. Browse retains its
  parent/child navigation, session DAG view, link badges, and child counts.
- `event.appended`, `session.updated`, and `judgment.appended` retain their non-task behavior,
  including lineage hints used by the UI and Orbit.
- Projects, capture, recall, search, summaries, and historical Handoff events remain available.

## Removed surfaces

The `/board` UI, task/spec REST endpoints (including task annotations, events, and task DAGs), and
task SSE frames are removed. MCP no longer offers `createSpec`, `enqueueTask`, `claimNextTask`,
`updateTaskStatus`, `completeTask`, `listTasks`, or `getSpec`. All non-workflow MCP tools remain.
Reload or reconnect cached MCP clients after upgrading so their tool inventory is refreshed.

The `runner` CLI mode, runner source/tests, worker scripts, deployment script, launchd template,
example config, coordination demo, and Board screenshot are removed from the repository.
Old `runner` invocations are rejected before server or database initialization.
Earlier specifications and plans are retained only as a clearly labeled
[historical archive](history/retired-board/README.md).

## Data and local state

Fresh databases omit the task tables and `session_links.task_id`. Existing databases continue to
serve lineage without removing legacy data by default. After verifying a database backup, set
`SBA_RETIRE_WORKFLOW=true` (`sba.storage.retire-workflow=true`) for an explicit upgrade to null and
drop `session_links.task_id`, then drop `task_events`, `tasks`, and `specs`. The migration is
idempotent and retains session relationships and recorded agent events, including Handoffs from
old task completions. This schema retirement is not reversed by deploying an older JAR.
An old binary must not be pointed at the migrated database as a rollback strategy.
The [database recovery guide](database-recovery.md) provides an explicit snapshot command and
disposable restore checks that retain legacy tables; running those checks does not authorize
retiring a live database.

Repository cleanup does not stop or restart services, unregister launchd jobs, prune worktrees,
or remove local runner files. Existing local runner configuration, registries, logs, caches, and
worktrees remain untouched. Their inspection, recovery, or removal is a separate operator action.
Do not start an old runner against the updated API.
