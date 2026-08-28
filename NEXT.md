# Handoff — 2026-08-28 (slice 4 shipped: live process monitor + follow mode + heartbeat)

**Shipped**: Phase 1 **slice 4** of the stream-first consolidation
(`docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md` §13) on branch
`cursor/stream-observatory-live-d4f2`. The branch is three commits above `main` at `af4ba42`: 
initial implementation (191bccb), NEXT.md update (5f64f49), and heartbeat fix (bd4ab72). 
Pushed to origin but not yet merged. Local `main` remains at `af4ba42` (Merge PR #26 
stream-legibility-queriability).

## What landed

### Backend
- **ProcessMonitor service**: polls `ps -eo pid,ppid,rss,pcpu,etime,comm` every 1s, matches against 
  known agent binaries (claude, codex, cursor, raycast), broadcasts over SSE only when the set 
  changes
- **GET /api/processes**: returns current agent processes snapshot (pid, agent, cpuPercent, rssKb, 
  elapsed)
- **SSE processes event**: new event type carrying AgentProcess[] and availability flag
- **lastSeenAt in EventFeedItem**: session's last_seen_at now included in feed items for heartbeat 
  display
- **@EnableScheduling**: enabled in platform config for ProcessMonitor's scheduled poll

### Frontend
- **ProcessPanel component**: displays running agent processes with CPU bar, memory, and uptime; 
  color-coded by agent; collapses when empty or unavailable
- **Follow mode**: pin-to-newest toggle beside the "N new" pill; when enabled, auto-merges pending 
  items and scrolls to top; persisted in localStorage; distinct from existing `livePaused` (which 
  is about `until:` query bounds)
- **Session heartbeat**: RunHeader now shows "last event Xs ago" with status dot (🟢 running <10s, 
  🟡 idle 10s–5m, ⚫ stale >5m); updates reactively from SSE `session.updated.lastSeenAt` events, 
  not from feed query snapshot (corrected in commit bd4ab72)
- **SSE processes handling**: live store extended to receive and store processes updates
- **Utilities**: formatRelativeTime and getSessionStatus with thresholds per spec

### Tests
- Backend unit tests: ProcessMonitorTest (initial state), ProcessControllerTest (empty/present)
- Frontend unit tests: heartbeat.test.ts (time formatting, status thresholds), 
  followMode.test.ts (localStorage persistence)

## Verification

- `git diff --check` clean
- All new files added and committed
- Branch pushed to origin
- Backend tests written and committed (cannot run mvn test in this environment)
- Frontend tests written and committed (cannot run npm test in this environment)
- Plan documented at `docs/superpowers/plans/2026-08-28-stream-observatory-live.md`

**Deferred verification** (requires local Maven/Node.js or deployment):
- `mvn test` to verify backend process parsing and controller
- `cd frontend && npm test` to verify heartbeat and follow mode utilities
- `cd frontend && npm run build` to compile frontend and commit bundle
- `cd frontend && npm run e2e` to verify follow mode, process panel, and heartbeat in Playwright

## Out of scope

Per spec §13 row 4 and the plan, slice 4 contains ONLY live monitoring (process panel, follow mode, 
heartbeat). Out of scope:
- Narrative/turn grouping (slice 5)
- Route promotion / Memory merge (slice 6)
- Cost/tokens display
- File-action authority expansion
- Runner or worker launching
- Any interaction with `com.nathan.blackbox-runner`

## Open loops (ranked)

1. **Frontend build and E2E**: the frontend code is written and tests exist, but the Vite bundle 
   has not been rebuilt. Next agent should run `cd frontend && npm run build` and commit the 
   `src/main/resources/static` bundle, then run `npm run e2e` to verify. The backend cannot be 
   tested live without deploying via `scripts/deploy-local.sh` on Nathan's Mac.
2. **Platform differences**: ProcessMonitor assumes macOS/BSD `ps` output format. If Linux support 
   is needed, add platform detection and adjust the regex pattern.
3. **Slice 5 — narrative** (spec §4.7, §13 row 5): turn grouping, update_plan presenter, derived 
   session titles, remaining tool presenters (grep, webFetch, webSearch, task).
4. **Slice 6 — consolidation** (spec §4.10, §13 row 6): route promotion (`/stream`, `/browse`), 
   Memory merge (Ask + Recall → `/memory`), delete `/stats` and `/overview`, park `/graph`.
5. **#21 redesign** (adopt-alive-runner-sessions) as lane-scoped adoption; also cures the retry 
   wedge from #23 (crash-after-commit → branch-exists collision → task blocked).
6. Runner safety follow-ups: gate `cleanupWorktreeAndBranch`'s exception path 
   (`RunExecutor.java:378→391`) on reachability; add `--` to the rev-list probe; no collector 
   exists for preserved orphan worktrees.
7. `ask` module: nomic prefix defect (bare prompt, no `search_query:`) + its kNN query targets the 
   foreign Elasticsearch index. Pointing it at the SQLite vector store fixes both. The two embedding 
   HTTP clients (`ask`, `memory`) still want consolidation behind one public port in `memory`.
8. Recall: separate `query` from `scope`; Tier 2 full-corpus semantic search; re-embedding is not 
   automatic on model change. The 0.61 floor is a ceiling, not a starting point.

## Gotchas (carried forward)

- Any `mvn package` (including the Playwright webServer) overwrites the live jar. Run 
  `scripts/deploy-local.sh` afterward, then `launchctl kickstart -k` if 500s persist.
- Never `git add -A` except scoped `git add -A src/main/resources/static` after a bundle rebuild.
- File authorization is the current `/api/projects/code-scopes` projection, not `ProjectKey.decode` 
  and not the broad `/api/projects` catalog. Preserve the exact matched worktree/scope key.
- The default direct Cursor CLI works under launchd's minimal PATH; the 
  `/Users/nathan/.local/bin/cursor` shim does not.
- Module ratchet: `memory → {project, recording}`; `memory` must never import `ask`. Every 
  `@Repository` lives in `<module>.internal.adapter.out.sqlite..`.
- Test DBs are temp **files**, never `cache=shared` memory. Never point a second app at the live 
  DB; snapshot with `sqlite3 sba-agentic.db ".backup <path>"`. The event table is `agent_events`.
- Playwright against the live app uses `domcontentloaded`, never `networkidle` (SSE).
- `POST /api/events` ingest takes `toolInput` / `toolOutput` as **objects**. The `*Json` names are 
  read-side only.
- Surefire counts: clear stale reports before trusting aggregates.

## Next action

**Slice 4 verification and merge** (or delegate to a follow-on agent):
1. Build frontend: `cd frontend && npm run build && git add -A src/main/resources/static && git commit -m "Build frontend bundle for slice 4"`
2. Run tests: `mvn test && cd frontend && npm test && npm run e2e`
3. Verify all green, then open PR or merge to main
4. Deploy locally via `scripts/deploy-local.sh` for manual smoke test (optional, Nathan's machine)

OR **skip to slice 5** if slice 4 verification is deferred to Nathan or another context.
