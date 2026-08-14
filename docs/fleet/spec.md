---
project: sba-agentic
tier: production
status: doing
current_round: 4
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
  - round: 3
    branch: "fleet/round-3-storyline-predicate-short-circuit"
    base: "fleet/round-2-milestone-predicate-short-circuit"
    pr: "local-only"
    commit: "ec69b4a"
    status: "review"
    note: "push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base fleet/round-2-milestone-predicate-short-circuit --head fleet/round-3-storyline-predicate-short-circuit --title \"fleet r3: fleet/round-3-storyline-predicate-short-circuit\""
---

# sba-agentic — fleet spec

## Intent

Make capped project-trajectory feeds honest about history that is older than the 120 visible
captures, without changing uncapped graph output, the feed contract, backend behavior, or layout
constants.

## Stakes

production

## Acceptance bar

- verify: `mvn -q test && (cd frontend && npm test && npm run build)` green
- `buildTrajectory` derives hidden captures from a valid `totalCaptures`, preserves byte-identical
  uncapped output, and always emits an honest deep-past node when hidden captures exist
- capped mode folds the oldest visible historical burst, keeps the head burst, reports the combined
  visible-plus-hidden member count, uses a `before <date>` eyebrow, and discloses the feed cap
- rejected-alternative stubs follow folded visible captures into deep past and trail edges still
  originate there
- Vitest covers hidden-only, folded-oldest, `SPINE_MAX` overflow, exact-total, and invalid-total
  boundaries; ProjectsPage and TrajectoryView remain green
- the generated static frontend bundle is rebuilt and committed

## Decided

Treat `totalCaptures` as usable only when it is a non-negative safe integer, then compute
`hiddenCount = max(0, totalCaptures - captures.length)`. In capped mode, add the oldest otherwise
visible historical burst to the existing collapse, but never fold the head burst. The deep-past
label leads with the combined capture count because the number of hidden bursts is unknowable; its
eyebrow references the first retained spine date, and its full text states both the combined total
and the captures beyond the feed cap. Keep `members[]` limited to visible folded captures so source
links and rejected-alternative attribution remain factual. Leave the original uncapped construction
path unchanged.

## Deferred

- Backend, schema, wire-contract, capture-limit, and query changes remain separate slices.
- Layout-constant and subjective visual tuning remain separate slices.
- The parked `/graph` constellation retirement decision remains untouched.
- Live deployment and live-database queries are not part of this local-only frontend slice.

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

### Round 3 — fleet/round-3-storyline-predicate-short-circuit
base: fleet/round-2-milestone-predicate-short-circuit
branch: fleet/round-3-storyline-predicate-short-circuit
pr: local-only
commit: ec69b4a
status: review
note: push blocked; run gh manually (push_allowed is not true): gh pr create --draft --base fleet/round-2-milestone-predicate-short-circuit --head fleet/round-3-storyline-predicate-short-circuit --title "fleet r3: fleet/round-3-storyline-predicate-short-circuit"

### Round 3 — Storyline predicate short-circuit
why: Keep the Timeline tab on the session-index fast path while avoiding lowered event-type and metadata probes for rows already accepted by typed or cheap column checks.
acceptance:
- STORYLINE_PREDICATE is a searched CASE that accepts typed decision/handoff events first, then assistant text and non-null tool_name rows, then event_type tool/error/fail matches, with the guarded decision/handoff metadata branch last and zero result changes
- Temp-file SQLite coverage uses the verbatim old predicate as an oracle across mixed-case kinds and markers, assistant text edge cases, tool rows, mixed-case event_type probes, large non-matching metadata, NULLs, multiple aliases, and saved meld totals
- countTimelineBlocksSql, timelineBlocksSql, and timelineBlocksForSessionSql preserve counts, chronological ordering, LIMIT/OFFSET pagination, session title joins, alias filtering, and idx_agent_events_session_observed plans without a full agent_events scan
- mvn -q test and cd frontend && npm test && npm run build are green; frontend sources and the generated static bundle remain unchanged
key files: src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java, src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectTimelineQueryPlanTest.java, docs/superpowers/specs/2026-08-05-project-trajectory-graph-design.md

### Round 4 — Honest deep past for capped trajectory feeds
why: Capture-rich projects receive only the newest 120 feed rows, so the graph must disclose older captures without inventing a burst count or start date at the cap boundary.
acceptance:
- valid feed totals produce the exact hidden capture count while missing or invalid totals preserve uncapped behavior
- capped feeds always emit deep past, fold the oldest visible historical burst but never the head, and keep hidden rows out of members
- capped labels, member counts, before-date eyebrows, full text, trail edges, and rejected stubs are deterministic and honest
- hidden-only, folded-oldest, SPINE_MAX overflow, and totalCaptures boundary tests pass with ProjectsPage and TrajectoryView coverage unchanged
- mvn -q test and cd frontend && npm test && npm run build are green; the generated static bundle is rebuilt
key files: frontend/src/lib/trajectory.ts, frontend/src/lib/trajectory.test.ts, src/main/resources/static
