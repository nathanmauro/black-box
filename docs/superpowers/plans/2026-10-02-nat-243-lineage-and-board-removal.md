# NAT-243: Preserve Lineage, Retire Board And Runner

Owner-approved scope: [NAT-243](https://linear.app/nathanmauro/issue/NAT-243).

## Safety And Completion Contract

- Start from the clean detached checkout at `c523a0c`; preserve unrelated changes.
- Extract session lineage into `lineage` before deleting workflow coordination.
- Preserve session-link routes, session DAGs, subagent discovery, judgment/Orbit children,
  session SSE metadata, the lineage rail and its navigable map, and historical Handoff recall.
- Remove the dormant board, task/spec APIs and MCP tools, runner, scripts, and current docs.
  Keep Projects and all non-workflow tools.
- Upgrade legacy schemas on disposable databases; retain events and lineage rows while removing
  task associations and obsolete board tables behind an explicit retirement flag (default off).
  Never migrate the live database during this task.
- Leave local runner configuration, lock, log, and services untouched. Do not deploy or restart.
- Use isolated writer checkouts, then integrate exact owned paths here. No push or PR is requested.

## Independently Verifiable Steps

1. Extract lineage types, services, repository, HTTP adapters, and tests. Update module boundaries
   and judgment/SSE imports; run targeted lineage and architecture checks before board removal.
2. Remove backend workflow/runner and update both schemas, legacy migration coverage, and generated
   contract snapshots. Confirm the actual non-workflow MCP inventory.
3. Remove frontend board/story/task behavior and runner E2E support. Keep lineage navigation and
   Projects intact. Regenerate bundled assets from frontend source.
4. Remove retired scripts and archive historical board/runner planning; update current product docs.
5. Run backend and frontend suites, format/lint/type checks, isolated browser tests of lineage and
   Orbit-related contracts, and `git diff --check`. Review removal boundaries and record results.

## Results

- Lineage extraction passed its repository, listener, API/DAG, judgment/SSE, Modulith, and ArchUnit
  checkpoint before workflow removal. The final dedicated module depends only on recording.
- Removed board UI/routes/task APIs/SSE/MCP, runner implementation/CLI/scripts, and fresh-schema
  task storage. Regenerated contract snapshots and bundled frontend assets. Historical planning
  documents live in `docs/history/retired-board/`; Projects retains its response contract with an
  empty retired task list.
- Existing data retirement requires `SBA_RETIRE_WORKFLOW=true` (default false). Disposable SQLite
  and PostgreSQL tests verify transaction rollback on unexpected dependencies, repeat safety,
  retained lineage rows and historical task-completion Handoff recall. No live migration ran.
- Independent review found and fixed a retired-command fallback: `runner` now fails before Spring
  initialization. Scripted use of the packaged JAR confirmed nonzero exit without database or file
  creation. A stale Board forwarding test was updated to require 404.
- Full backend suite: 565 tests, zero failures/errors, four existing conditional/platform skips;
  all 13 PostgreSQL tests executed successfully against a disposable PostgreSQL 16 instance.
- Frontend: all 608 unit tests pass; lint (existing warnings only), formatting, type check and
  production build pass. All 28 Playwright tests pass against an isolated packaged server/database,
  including lineage at 1440px and 390px, keyboard navigation, old `?task=` links, Projects, and the
  exact ten-tool MCP inventory. Desktop and phone screenshots were visually inspected.
- Retained hook scripts: 20 normalization/subagent fixtures, 32 outbox tests, and 12 recall-hook
  checks pass. Documentation local links and `git diff --check` pass.
- Protected local runner configuration, lock and log existence/hashes remain unchanged. The
  production listener remained unchanged during browser verification; no deployment or service
  restart occurred. Temporary PostgreSQL was stopped after verification.
- Orbit: seven existing consumer fixtures and nine isolated integration assertions pass against
  the actual unchanged Constellate consumer and Orbit HTML. Explicit-link Codex children (without
  identifier or metadata fallbacks), lineage curves, spawned counts, hover provenance, parent river
  child beats, and live subagent start/stop SSE were exercised. Screenshots were inspected and no
  browser errors occurred. Persistence was disabled for the harness; its disposable backend was
  stopped and Constellate source and production services were left untouched.

## Integration With Project Continuity

The owner subsequently authorized PR creation and merging on 2026-10-02. The completed retirement
commit is being integrated with the already-merged authenticated outbox and project-continuity
changes. This does not authorize or execute the optional live schema retirement.

- Preserve the exact-project recall, resume action, decision-replacement relation and historical
  evidence. Remove the retired Board action while retaining **Resume this project**.
- Regenerate contracts from the retained ten MCP tools, including the new recall/replacement fields.
- Regenerate frontend assets from merged source; preserve the separately verified mobile header fix
  when it reaches main.
- Re-run combined backend/PostgreSQL, frontend and browser verification. Prior branch results above
  are historical evidence, not proof of the combined source. No existing local service or database
  is changed by integration checks.

### Combined Verification Results

- 581 backend tests passed with zero failures/errors and four conditional/platform skips; all
  16 remaining PostgreSQL contracts ran against a disposable PostgreSQL 16 database. Retired
  workflow tests account for the reduced count; project continuity and replacement tests remain.
- All 618 frontend unit tests passed. Lint, formatting, types and the regenerated production
  bundle passed. The mobile header has the retained navigation order after removing Board.
- All 35 packaged-application Playwright journeys passed, including project-scoped recall,
  replacement/history, clipboard export, lineage and keyboard navigation at narrow and desktop
  widths. Final mobile recall and desktop lineage screenshots were inspected.
- The packaged `runner` command exited nonzero before starting Spring and created zero files in
  an empty temporary working directory. No retired command silently starts a server.
- Fresh read-only review found no actionable issue in the conflict resolutions. The existing
  port-8766 listener remained unchanged; the browser harness removed its disposable database.
  Production database identity/counts were unavailable, and physical-phone use was not tested.
- No live schema retirement, deployment, runner configuration change or production restart ran.
