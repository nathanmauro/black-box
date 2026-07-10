# Agent Task Queue Implementation Plan

- **Date:** 2026-07-10
- **Status:** Implemented; integrated release validation pending
- **Design:** [`../specs/2026-06-28-agent-task-queue-design.md`](../specs/2026-06-28-agent-task-queue-design.md)

## Goal

Ship the smallest useful cross-agent coordination loop while keeping Black Box a local-first
substrate, never an executor:

```text
create spec → enqueue → atomic lane claim → block/reset or complete → Handoff → recall
```

SQLite remains authoritative. MCP and REST are equivalent adapters. SSE wakes clients but never
owns delivery. The Board observes and manually releases stalled ownership without launching a
worker agent or executing a task command.

## Safety contract

- Store the full frozen spec body in SQLite; treat `specRef` as optional provenance only.
- Select and claim with one `UPDATE … RETURNING` statement scoped to an exact lane.
- Commit a task row mutation and its immutable `task_events` row in one transaction.
- Publish SSE only after durable mutation; a publish failure must not roll back state.
- Complete only when the actor is the current claimant and the task is `in_progress`.
- Capture completion as a normal Handoff with the real source session; roll back if capture fails.
- Never create a synthetic session for ordinary task lifecycle events.
- Never let task coordination or the Board launch a worker agent, execute a task command, mutate a
  checkout, or auto-enqueue follow-up work.
- Keep configured session summarization as a separate explicit boundary: its `external` backend may
  invoke a Codex or Claude CLI command through `/bin/sh -c` and transmit transcript text, but it is
  never a worker or queued-task execution path.

## Implemented work

### 1. Durable coordination core

- [x] Add `specs`, `tasks`, `task_events`, foreign keys, and claim/event indexes in
  `src/main/resources/schema.sql`.
- [x] Add typed task/spec records and enums under `src/main/java/dev/nathan/sbaagentic/task/`.
- [x] Add `TaskRepository` CRUD, filtered ordered list, lifecycle append, and atomic claim.
- [x] Normalize variable-precision ISO-8601 text for chronological FIFO comparison without
  rewriting stored timestamps.
- [x] Enable SQLite foreign keys on every pooled connection and retain WAL/busy timeout behavior.
- [x] Prove concurrent same-lane claims produce exactly one winner and one empty result.

### 2. Lifecycle, Handoff, and notification

- [x] Add `TaskService` validation and the open/in-progress/blocked/done/cancelled transition matrix.
- [x] Add manual reset from `blocked` or `in_progress` to `open`, clearing claimant and blocker.
- [x] Add claimant-owned completion, normal Handoff capture, result id linkage, and rollback on
  Handoff failure.
- [x] Add `task.created`, `task.claimed`, `task.blocked`, `task.completed`, `task.reset`, and
  `task.cancelled` frames to the existing SSE stream.
- [x] Keep SSE best-effort and SQLite authoritative.

### 3. Agent interfaces

- [x] Add the same seven operations to `AgenticController` and `AgenticTools`: `createSpec`,
  `enqueueTask`, `claimNextTask`, `updateTaskStatus`, `completeTask`, `listTasks`, and `getSpec`.
- [x] Return full frozen spec snapshots to claim/list callers.
- [x] Return REST `204` and MCP `null` when no task is eligible.
- [x] Add typed REST and MCP task errors, canonical UUID validation, exact filters, and a 1–250
  bounded list limit.
- [x] Make `resultHandoffId` a direct `recallContext` / `/api/recall` key.

### 4. Live Board

- [x] Add typed task API functions and an idempotent task live store under `frontend/src/lib/`.
- [x] Load authoritative snapshots over REST, ignore duplicate/older frames, and refresh after SSE
  gaps or malformed task frames.
- [x] Add `/board` with Open, In Progress, Blocked, and Done columns plus a cancelled disclosure.
- [x] Add URL-backed `project`, `lane`, and selected `task` state.
- [x] Add frozen spec, ownership, blocker, provenance, and linked Handoff detail.
- [x] Add explicit reset-to-open with server-authoritative refresh and visible failure state.
- [x] Keep executor controls out of the UI.

### 5. Documentation and release gates

- [x] Update README/API/MCP examples and architecture to match code.
- [x] Mark the exploratory draft superseded and this design implemented without deleting history.
- [x] Use synthetic identifiers and machine-neutral paths in examples.
- [ ] Run the integrated Maven, Vitest, frontend build, packaged-jar, and isolated Playwright gates.
- [ ] Align the runtime `SBA_SUMMARY_BACKEND` default with the documented project contract
  (`external`) and protect it with a regression test before release.
- [ ] Exercise the same flow on the supported live deployment path and merge only after CI passes.

## Code-to-contract map

| Contract | Implementation | Focused proof |
| --- | --- | --- |
| SQLite schema and FK integrity | `schema.sql`, datasource configuration | `TaskSchemaTest`, `TaskRepositoryTest` |
| Priority/FIFO atomic claim | `TaskRepository.claimNextTask` | `TaskRepositoryTest` |
| Lifecycle and ownership | `TaskService` | `TaskServiceIntegrationTest` |
| REST/MCP parity and typed errors | `AgenticController`, `AgenticTools`, `McpToolConfiguration` | `TaskApiContractTest`, `ApiExceptionHandlerTest` |
| SSE wake hint | `EventBroadcaster`, `StreamEvents`, `StreamController` | `TaskStreamTest` |
| Frontend API/live recovery | `frontend/src/lib/api.ts`, `tasks.ts`, `sse.ts` | corresponding Vitest suites |
| Board route/detail/reset | `frontend/src/pages/BoardPage.tsx`, `frontend/src/index.tsx` | `BoardPage.test.tsx`, packaged SPA tests |
| Completion recall | `TaskService.completeTask`, `ContextService`, `EventRepository` | task service and API contract tests |

## Verification commands

Run from the repository root unless a command changes directory:

```bash
mvn -q -Dtest=TaskSchemaTest,TaskRepositoryTest,TaskServiceIntegrationTest,TaskStreamTest,TaskApiContractTest,ApiExceptionHandlerTest test
(cd frontend && npm run test -- src/lib/api.test.ts src/lib/tasks.test.ts src/lib/sse.test.ts src/pages/__tests__/BoardPage.test.tsx)
(cd frontend && npm run build)
mvn -q -Pfrontend -DskipTests package
git diff --check
```

The integrated release task also runs the full Maven, Vitest, and isolated Playwright suites. It
must use a temporary SQLite path and a non-production port; no deterministic seed may touch the
normal service database.

### Documentation validation recorded 2026-07-10

- Focused Maven task/schema/stream/API/error suites: 56 tests passed, 0 failed.
- Focused frontend API/live-store/SSE/Board suites: 37 tests passed, 0 failed.
- TypeScript check plus Vite production build: passed.
- Frontend-profile packaged jar build: passed.
- README REST loop against an isolated app and temporary SQLite file: observed
  `in_progress → blocked → open → in_progress → done`, and recalled the exact linked Handoff id.
- No-eligible claim: REST `204`; ownership failure: REST `409` with `claimant_mismatch`.
- `GET /api/specs/{id}`, filtered `GET /api/tasks`, and packaged `/board`: HTTP `200`.
- Relative Markdown links across the six touched docs: all targets exist.
- `git diff --check` for the documentation-owned paths: passed.

The isolated smoke set `SBA_SUMMARY_BACKEND=local` and disabled local AI, ASK embeddings, and
Elasticsearch, so it did not invoke the default external summary path or touch a live service.

## Deferred, explicitly not hidden

Leases, heartbeats, automatic reaping, dependency DAGs, a capability registry, priority aging,
authentication, multi-node brokers, and any external runner protocol remain future work. Manual
reset is the MVP recovery mechanism. Adding an executor inside the Black Box server is not a follow-
up; it violates the product boundary.
