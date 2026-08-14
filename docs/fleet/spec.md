---
project: sba-agentic
tier: production
status: doing
current_round: 1
verify_cmd: "mvn -q test && (cd frontend && npm test)"
push_allowed: false
danger: "origin nathanmauro/black-box is PUBLIC — never push, strictly local-only, no PRs; base every fleet branch on graph-development (NOT main); FOCUS: continue the trajectory-graph capabilities on graph-development — known open loops: Timeline tab still uses the slow per-row query (~5.7s live; the session-index approach from commit c65b4ea applies directly), graph feed perf (milestone LIKE predicate short-circuit), trajectory heuristics are exported constants in frontend/src/lib/trajectory.ts, and NEW graph capability slices are welcome per the design spec docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md; the /graph constellation retirement decision is PARKED — do not touch it; NEVER touch or point any test at the live DB sba-agentic.db/-shm/-wal in the repo root (test DBs are temp FILES, never cache=shared memory); live service on :8766 via launchd com.nathan.sba-agentic — any mvn package (incl. Playwright webServer) overwrites the live jar, so afterwards run scripts/deploy-local.sh then launchctl kickstart -k gui/501/com.nathan.sba-agentic; never git add -A or git add . — stage explicit paths only, EXCEPT scoped git add -A src/main/resources/static after a frontend bundle rebuild; verify = mvn -q test AND cd frontend && npm test && npm run build (e2e: cd frontend && npm run e2e); Playwright uses domcontentloaded never networkidle (SSE); module ratchet: memory→{project,recording}, memory must never import ask; POST /api/events takes toolInput/toolOutput as objects (the *Json names are read-side only); event table is agent_events"
branch_lineage:
  []
---

# sba-agentic — fleet spec

## Intent

Move the project Timeline queries onto the same session-index fast path as the trajectory feed,
without changing storyline rows, ordering, paging, saved melds, session evidence, or alias scopes.

## Stakes

production

## Acceptance bar

- verify: `mvn -q test && (cd frontend && npm test)` green
- All three Timeline queries use SESSION_SCOPED_EVENT_FILTER while preserving storyline filtering, chronological ordering, paging, saved melds, session output, and alias scopes
- Temp-file SQLite coverage proves project isolation, exact counts, gap-free LIMIT/OFFSET pagination, saved-meld inclusion, and per-session scoping
- EXPLAIN QUERY PLAN for count and list shows SEARCH e USING INDEX idx_agent_events_session_observed and no full agent_events scan
- mvn -q test and cd frontend && npm test are green; frontend sources and bundle remain unchanged

## Decided

Use the existing `SESSION_SCOPED_EVENT_FILTER` shape for all three Timeline event queries. Keep the
session join only where Timeline output needs session title and cwd, and exercise the production SQL
through temp-file SQLite tests with query-plan assertions.

## Deferred

- Milestone predicate tuning, trajectory heuristic tuning, and the parked `/graph` constellation
  retirement decision remain separate slices.
- Live deployment and an after-deploy timing are not part of this local-only server query slice.

## Rounds

### Round 1 — Timeline queries onto the session-index fast path
why: Extend c65b4ea session-index filtering to the three Timeline queries so SQLite scopes sessions first instead of evaluating the canonical-key CASE across every event row.
acceptance:
- All three Timeline queries use SESSION_SCOPED_EVENT_FILTER while preserving storyline filtering, chronological ordering, paging, saved melds, session output, and alias scopes
- Temp-file SQLite coverage proves project isolation, exact counts, gap-free LIMIT/OFFSET pagination, saved-meld inclusion, and per-session scoping
- EXPLAIN QUERY PLAN for count and list shows SEARCH e USING INDEX idx_agent_events_session_observed and no full agent_events scan
- mvn -q test and cd frontend && npm test are green; frontend sources and bundle remain unchanged
key files: src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java, src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTimelineQueryPlanTest.java, docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md
