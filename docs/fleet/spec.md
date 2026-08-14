---
project: sba-agentic
tier: production
status: doing
current_round: 2
verify_cmd: "mvn -q test && (cd frontend && npm test && npm run build)"
push_allowed: false
danger: "origin nathanmauro/black-box is PUBLIC — never push, strictly local-only, no PRs; base every fleet branch on graph-development (NOT main); FOCUS: continue the trajectory-graph capabilities on graph-development — known open loops: Timeline tab still uses the slow per-row query (~5.7s live; the session-index approach from commit c65b4ea applies directly), graph feed perf (milestone LIKE predicate short-circuit), trajectory heuristics are exported constants in frontend/src/lib/trajectory.ts, and NEW graph capability slices are welcome per the design spec docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md; the /graph constellation retirement decision is PARKED — do not touch it; NEVER touch or point any test at the live DB sba-agentic.db/-shm/-wal in the repo root (test DBs are temp FILES, never cache=shared memory); live service on :8766 via launchd com.nathan.sba-agentic — any mvn package (incl. Playwright webServer) overwrites the live jar, so afterwards run scripts/deploy-local.sh then launchctl kickstart -k gui/501/com.nathan.sba-agentic; never git add -A or git add . — stage explicit paths only, EXCEPT scoped git add -A src/main/resources/static after a frontend bundle rebuild; verify = mvn -q test AND cd frontend && npm test && npm run build (e2e: cd frontend && npm run e2e); Playwright uses domcontentloaded never networkidle (SSE); module ratchet: memory→{project,recording}, memory must never import ask; POST /api/events takes toolInput/toolOutput as objects (the *Json names are read-side only); event table is agent_events"
branch_lineage:
  - round: 1
    branch: "fleet/round-1-timeline-session-index-fast-path"
    base: "graph-development"
    pr: "local-only"
    commit: "38c6910"
    status: "review"
    note: "push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base graph-development --head fleet/round-1-timeline-session-index-fast-path --title \"fleet r1: fleet/round-1-timeline-session-index-fast-path\""
---

# sba-agentic — fleet spec

## Intent

Short-circuit milestone classification for the trajectory feed so large, non-milestone metadata
does not pay four lowered `LIKE` probes, without changing captures, counts, ordering, session
evidence, saved melds, alias scopes, or frontend interpretation.

## Stakes

production

## Acceptance bar

- verify: `mvn -q test && (cd frontend && npm test && npm run build)` green
- MILESTONE_PREDICATE checks typed event kinds first and evaluates the four lowered metadata kind probes only behind one bare case-insensitive metadata_json LIKE guard, with no result changes
- Temp-file SQLite coverage uses the verbatim old predicate as an oracle across mixed-case typed events and metadata markers, large non-matching tool metadata, NULL metadata, aliases, and saved meld totals
- recentEventCaptures preserves chronological ordering, LIMIT, session title joins, and alias scope filtering; EXPLAIN QUERY PLAN uses idx_agent_events_session_observed without a full agent_events scan
- mvn -q test and cd frontend && npm test && npm run build are green; frontend sources and the generated static bundle remain unchanged

## Decided

Keep the existing `SESSION_SCOPED_EVENT_FILTER` and use a searched `CASE` for milestone
classification: accept typed event kinds first, then evaluate the four lowered metadata markers
only when one bare, ASCII-case-insensitive `metadata_json LIKE '%"kind":"%'` guard succeeds. The
guard is a logical restriction because every old marker match also matches the guard; do not use
case-sensitive `instr()`. Exercise the production count and recent-capture SQL through temp-file
SQLite tests with old-predicate equivalence and query-plan assertions.

## Deferred

- `STORYLINE_PREDICATE` tuning, schema/index changes, and trajectory heuristic tuning remain
  separate slices.
- The parked `/graph` constellation retirement decision remains untouched.
- Live deployment, live-database queries, and an after-deploy timing are not part of this
  local-only query slice.

## Rounds

### Round 1 — fleet/round-1-timeline-session-index-fast-path
base: graph-development
branch: fleet/round-1-timeline-session-index-fast-path
pr: local-only
commit: 38c6910
status: review
note: push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base graph-development --head fleet/round-1-timeline-session-index-fast-path --title "fleet r1: fleet/round-1-timeline-session-index-fast-path"

### Round 2 — Milestone predicate short-circuit
why: Keep the trajectory feed on the session-index fast path while avoiding four lower-plus-LIKE scans over metadata blobs that cannot contain a milestone kind marker.
acceptance:
- MILESTONE_PREDICATE checks typed event kinds first and evaluates the four lowered metadata kind probes only behind one bare case-insensitive metadata_json LIKE guard, with no result changes
- Temp-file SQLite coverage uses the verbatim old predicate as an oracle across mixed-case typed events and metadata markers, large non-matching tool metadata, NULL metadata, aliases, and saved meld totals
- recentEventCaptures preserves chronological ordering, LIMIT, session title joins, and alias scope filtering; EXPLAIN QUERY PLAN uses idx_agent_events_session_observed without a full agent_events scan
- mvn -q test and cd frontend && npm test && npm run build are green; frontend sources and the generated static bundle remain unchanged
key files: src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java, src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTrajectoryQueryPlanTest.java, docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md
