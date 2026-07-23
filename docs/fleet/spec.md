---
project: sba-agentic
tier: production
status: doing
current_round: 2
verify_cmd: "mvn -q test"
push_allowed: true
danger: "Black Box (github.com/nathanmauro/black-box, open source; draft PRs to main welcome). Backlog seed: NEXT.md ranked follow-ups from the 2026-07-16 handoff (adopted-runs crash recovery, pagination past 250 rows + exclude cancelled from runner queries, private tmux socket for Playwright harness, AgenticTools boxed-param NPEs) — but most recent work landed directly via Codex sessions (PR #20 modular monolith, activity event readability), so verify every backlog item against current git log before building; some may already be done. Verify before commit: mvn -q test for Java; cd frontend && npx vitest run for frontend; Playwright e2e only when runner/board flows change. CRITICAL: any mvn package overwrites the jar the live launchd service com.nathan.sba-agentic (:8766) runs from — after any packaging, run scripts/deploy-local.sh so the live service stays healthy. Frontend is SolidJS+Vite in frontend/ compiled into committed static/ — rebuild and commit static/ with any frontend change. Respect the enforced modular-monolith module boundaries. Follow AGENTS.md; keep docs honest; exclude .mcp.json, local DBs, IDE files from commits."
branch_lineage:
  - round: 1
    branch: "fleet/round-1-adopt-alive-runner-sessions"
    base: "main"
    pr: "https://github.com/nathanmauro/black-box/pull/21"
    commit: "d91fed5"
    status: "review"
    note: ""
---

# sba-agentic — fleet spec

## Intent

Make task listings complete and efficient on busy boards by paging in SQLite and teaching runner
scans to consume every page without cancelled-task noise.

## Stakes

production

## Acceptance bar

- verify: `mvn -q test` green
- GET /api/tasks accepts optional offset (int, default 0, clamped >= 0) and excludeStatus (comma-separated or repeatable status values, e.g. excludeStatus=cancelled); omitting both preserves today's exact behavior (backward compatible — no frontend change required)
- Filtering and paging are pushed into the SQLite query in TaskRepository.listTasks (WHERE t.status NOT IN (...), LIMIT ? OFFSET ?), replacing the controller's post-hoc stream().limit() while preserving the 250-per-page server cap, and the ORDER BY gains a deterministic unique tiebreaker (append t.id) so pages never overlap or skip on ties
- TaskQuery (workflow public API record: projectKey, lane, status) extended compatibly to carry excludeStatuses/limit/offset without breaking existing constructor callers (TaskController, WorkflowMcpTools line 141, TaskRepositoryTest, TaskServiceIntegrationTest) or the enforced module boundaries
- Both BlackBoxApiClient.listTasks overloads page through offset until a short page and aggregate results (with a sane safety cap on iterations; note a total divisible by 250 costs one extra empty fetch — acceptable), so the runner sees ALL matching rows past 250; null-status (lane-scan) runner queries pass excludeStatus=cancelled; existing client-side CANCELLED filters in CrashRecovery (lines 332, 353) stay as belt-and-braces
- Tests: TaskRepositoryTest covers exclusion + stable pagination across pages (no dupes/gaps); TaskApiContractTest (or controller test) covers offset/excludeStatus params and backward compatibility; BlackBoxApiClientTest proves multi-page aggregation past 250 and the excludeStatus query param with the existing fake-server pattern
- MCP contract snapshot (src/test/java/dev/nathan/sbaagentic/contracts/McpContractSnapshotTest.java) unchanged unless WorkflowMcpTools is deliberately extended — extending the MCP tool is optional and may be deferred
- mvn -q test green including module-boundary/architecture ratchet tests; no mvn package required (if packaging happens anyway, scripts/deploy-local.sh must be run)
- docs/architecture.md's List-tasks API row (~line 147) updated to document the new params; any other doc describing /api/tasks params updated to stay honest

## Decided

## Deferred

- Extending the MCP `listTasks` tool with `offset` and `excludeStatus`; its existing bounded-list
  contract remains unchanged in this round.

## Rounds

### Round 1 — fleet/round-1-adopt-alive-runner-sessions
base: main
branch: fleet/round-1-adopt-alive-runner-sessions
pr: https://github.com/nathanmauro/black-box/pull/21
commit: d91fed5
status: review
note:

### Round 2 — Paginate task listings past 250 rows and exclude cancelled from runner queries
why: NEXT.md (2026-07-16 handoff) ranked follow-up #3: 'Pagination past 250 rows (server clamp) for task listings; busy lanes will exceed it. Also consider excluding cancelled from runner-facing queries.' Verified still open on the current round-1 branch: TaskController.safeTaskLimit clamps to 250 with no offset param and applies the limit post-query via stream().limit() (line 75) so every call already fetches all rows; TaskRepository.listTasks (line 173) builds SQL with no LIMIT/OFFSET and orders by priority DESC, created_at ASC with no unique tiebreaker (unsafe for paging); BlackBoxApiClient.listTasks (both overloads, lines 123-147) hard-codes limit=250 with a code comment admitting the cap already hid 11 tasks in a live cleanup. NEXT.md records ~110 cancelled junk tasks sitting on the board eating the cap. Item #2 shipped in round 1 (d91fed5, PR #21); item #1 targets Nathan's global ~/.codex/config.toml (out of repo); #4/#5 are smaller and lower-ranked. docs/fleet/spec.md has no Decided/Deferred entries blocking this.
acceptance:
- GET /api/tasks accepts optional offset (int, default 0, clamped >= 0) and excludeStatus (comma-separated or repeatable status values, e.g. excludeStatus=cancelled); omitting both preserves today's exact behavior (backward compatible — no frontend change required)
- Filtering and paging are pushed into the SQLite query in TaskRepository.listTasks (WHERE t.status NOT IN (...), LIMIT ? OFFSET ?), replacing the controller's post-hoc stream().limit() while preserving the 250-per-page server cap, and the ORDER BY gains a deterministic unique tiebreaker (append t.id) so pages never overlap or skip on ties
- TaskQuery (workflow public API record: projectKey, lane, status) extended compatibly to carry excludeStatuses/limit/offset without breaking existing constructor callers (TaskController, WorkflowMcpTools line 141, TaskRepositoryTest, TaskServiceIntegrationTest) or the enforced module boundaries
- Both BlackBoxApiClient.listTasks overloads page through offset until a short page and aggregate results (with a sane safety cap on iterations; note a total divisible by 250 costs one extra empty fetch — acceptable), so the runner sees ALL matching rows past 250; null-status (lane-scan) runner queries pass excludeStatus=cancelled; existing client-side CANCELLED filters in CrashRecovery (lines 332, 353) stay as belt-and-braces
- Tests: TaskRepositoryTest covers exclusion + stable pagination across pages (no dupes/gaps); TaskApiContractTest (or controller test) covers offset/excludeStatus params and backward compatibility; BlackBoxApiClientTest proves multi-page aggregation past 250 and the excludeStatus query param with the existing fake-server pattern
- MCP contract snapshot (src/test/java/dev/nathan/sbaagentic/contracts/McpContractSnapshotTest.java) unchanged unless WorkflowMcpTools is deliberately extended — extending the MCP tool is optional and may be deferred
- mvn -q test green including module-boundary/architecture ratchet tests; no mvn package required (if packaging happens anyway, scripts/deploy-local.sh must be run)
- docs/architecture.md's List-tasks API row (~line 147) updated to document the new params; any other doc describing /api/tasks params updated to stay honest
key files: src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/in/web/TaskController.java, src/main/java/dev/nathan/sbaagentic/workflow/TaskQuery.java, src/main/java/dev/nathan/sbaagentic/workflow/internal/application/TaskService.java, src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/TaskRepository.java, src/main/java/dev/nathan/sbaagentic/runner/internal/client/blackbox/BlackBoxApiClient.java, src/main/java/dev/nathan/sbaagentic/runner/CrashRecovery.java, src/test/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/TaskRepositoryTest.java, src/test/java/dev/nathan/sbaagentic/web/TaskApiContractTest.java, src/test/java/dev/nathan/sbaagentic/runner/internal/client/blackbox/BlackBoxApiClientTest.java
