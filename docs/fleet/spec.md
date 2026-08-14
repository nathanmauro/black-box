---
project: sba-agentic
tier: production
status: doing
current_round: 3
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
  - round: 2
    branch: "fleet/round-2-milestone-predicate-short-circuit"
    base: "fleet/round-1-timeline-session-index-fast-path"
    pr: "local-only"
    commit: "911aca0"
    status: "review"
    note: "push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base fleet/round-1-timeline-session-index-fast-path --head fleet/round-2-milestone-predicate-short-circuit --title \"fleet r2: fleet/round-2-milestone-predicate-short-circuit\""
---

# sba-agentic — fleet spec

## Intent

Short-circuit storyline classification for the Timeline tab so typed and cheap column matches do
not pay lowered event-type or metadata probes, without changing selected rows, counts, ordering,
pagination, session evidence, saved melds, or alias scopes.

## Stakes

production

## Acceptance bar

- verify: `mvn -q test && (cd frontend && npm test && npm run build)` green
- STORYLINE_PREDICATE is a searched CASE that accepts typed decision/handoff events first, then assistant text and non-null tool_name rows, then event_type tool/error/fail matches, with the guarded decision/handoff metadata branch last and zero result changes
- Temp-file SQLite coverage uses the verbatim old predicate as an oracle across mixed-case kinds and markers, assistant text edge cases, tool rows, mixed-case event_type probes, large non-matching metadata, NULLs, multiple aliases, and saved meld totals
- countTimelineBlocksSql, timelineBlocksSql, and timelineBlocksForSessionSql preserve counts, chronological ordering, LIMIT/OFFSET pagination, session title joins, alias filtering, and idx_agent_events_session_observed plans without a full agent_events scan
- mvn -q test and cd frontend && npm test && npm run build are green; frontend sources and the generated static bundle remain unchanged

## Decided

Keep the existing `SESSION_SCOPED_EVENT_FILTER` and use a searched `CASE` for storyline
classification: accept typed decision/handoff events first; then assistant rows with non-empty text
and rows with non-null tool names; then the lowered event-type tool/error/fail probes. Evaluate the
two lowered metadata markers only in the final `WHEN`, behind one bare, ASCII-case-insensitive
`metadata_json LIKE '%"kind":"%'` guard. The guard is a logical restriction because every old
marker match also matches it; do not use case-sensitive `instr()`. Keeping the guarded branch last
ensures a guard-hit/probe-miss row can still be accepted by every earlier branch. Exercise all three
production timeline SQL shapes through temp-file SQLite tests with the old predicate as the oracle
and session-index query-plan assertions.

## Deferred

- Schema/index changes and trajectory heuristic tuning remain separate slices.
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

### Round 2 — fleet/round-2-milestone-predicate-short-circuit
base: fleet/round-1-timeline-session-index-fast-path
branch: fleet/round-2-milestone-predicate-short-circuit
pr: local-only
commit: 911aca0
status: review
note: push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base fleet/round-1-timeline-session-index-fast-path --head fleet/round-2-milestone-predicate-short-circuit --title "fleet r2: fleet/round-2-milestone-predicate-short-circuit"

### Round 3 — Storyline predicate short-circuit
why: Keep the Timeline tab on the session-index fast path while avoiding lowered event-type and metadata probes for rows already accepted by typed or cheap column checks.
acceptance:
- STORYLINE_PREDICATE is a searched CASE that accepts typed decision/handoff events first, then assistant text and non-null tool_name rows, then event_type tool/error/fail matches, with the guarded decision/handoff metadata branch last and zero result changes
- Temp-file SQLite coverage uses the verbatim old predicate as an oracle across mixed-case kinds and markers, assistant text edge cases, tool rows, mixed-case event_type probes, large non-matching metadata, NULLs, multiple aliases, and saved meld totals
- countTimelineBlocksSql, timelineBlocksSql, and timelineBlocksForSessionSql preserve counts, chronological ordering, LIMIT/OFFSET pagination, session title joins, alias filtering, and idx_agent_events_session_observed plans without a full agent_events scan
- mvn -q test and cd frontend && npm test && npm run build are green; frontend sources and the generated static bundle remain unchanged
key files: src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java, src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTimelineQueryPlanTest.java, docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md
