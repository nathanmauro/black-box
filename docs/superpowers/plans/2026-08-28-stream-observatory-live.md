# Plan: Stream Observatory Live (Slice 4)

**Date:** 2026-08-28  
**Status:** Active  
**Spec:** `docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md` §4.6, §13 row 4

## Context

Phase 1 slice 4 of the stream-first consolidation. Slices 1–3 already shipped (presenters, recall
links, file links). Slice 4 adds **live process monitoring** so users can distinguish "thinking"
from "hung" without waiting for the next event.

## Scope

Three independent features, all using the existing SSE `/api/stream` channel:

1. **Follow mode**: pin-to-newest toggle; the existing `pendingItems` "N new" pill becomes the
   paused state
2. **Process monitor**: server-side poll of `ps -eo pid,ppid,rss,pcpu,etime,comm` every 1s, matched
   against known agent binaries, broadcast only when the set changes
3. **Per-session heartbeat**: "last event 4s ago" label plus a running/idle/stale dot derived from
   `agent_sessions.last_seen_at`

Out of scope: narrative/turn grouping, Memory merge, route promotion, cost/tokens, runner, workers,
slice 5, slice 6.

## Design

### 1. Follow mode

**Current state**: `StreamPage.tsx` accumulates new SSE events in `pendingItems` and shows an "N
new" pill. Clicking scrolls to top and merges them into `items`.

**Change**: add a pin-to-newest toggle beside the pill. When pinned:
- Merge `pendingItems → items` automatically on arrival
- Scroll to top automatically
- Hide the "N new" pill

When unpinned (default):
- Keep current behavior (pill + manual merge)

**UI**: a pin icon toggle beside the pill. Persisted in localStorage (`streamFollowMode: boolean`).

**Dependencies**: none.

### 2. Process monitor

**Backend**:
- New `ProcessMonitor` service in `platform.internal.application`
- Poll `ps -eo pid,ppid,rss,pcpu,etime,comm` every 1s via scheduled task
- Parse output, match against known agent binary patterns:
  - `claude` (Claude Desktop)
  - `codex` (Codex CLI/desktop)
  - `cursor` (Cursor IDE)
  - `raycast` (Raycast agent)
  - Any process with parent that matches these
- Compare to previous snapshot; broadcast via `EventBroadcaster` only on change
- New SSE event type `processes` carrying a list of `AgentProcess` records
- Fail closed: parse failures degrade to "process info unavailable"; the stream keeps working

**API**:
- New `GET /api/processes` endpoint returning current process snapshot
- Response: `AgentProcess[]`

**Models**:
```java
record AgentProcess(
  long pid,
  String agent,      // "claude", "codex", "cursor", etc.
  double cpuPercent,
  long rssKb,
  String elapsed     // e.g. "02:34:15"
) {}
```

**Frontend**:
- Listen to `processes` SSE event, store in live store
- New `ProcessPanel` component on StreamPage showing:
  - Each running agent process
  - CPU % bar
  - Memory (RSS)
  - Elapsed time
  - Agent color dot (reusing existing agent colors)
- Panel collapses to a summary count when no detail needed

**Testing**:
- Unit tests for `ps` output parsing (various OS formats, malformed output)
- Mock scheduled executor in tests
- E2E: verify SSE `processes` event arrives

**Dependencies**: none (uses existing SSE infrastructure).

### 3. Session heartbeat

**Data**: `agent_sessions.last_seen_at` already exists and is updated on event ingest.

**Display**: on each session header/fold in the stream:
- "last event 4s ago" (relative time)
- Status dot:
  - 🟢 **running**: last_seen_at within 10s
  - 🟡 **idle**: last_seen_at 10s–5min ago
  - ⚫ **stale**: last_seen_at >5min ago

**Update**: recompute on every SSE `session.updated` event (already broadcasts `lastSeenAt`).

**UI**: small inline text + dot in `StreamFold` session header beside the session title.

**Testing**:
- Unit tests for relative time formatting
- Unit tests for status dot thresholds
- E2E: verify heartbeat updates on new events

**Dependencies**: none (data already present).

## Implementation order

1. **Backend process monitor** (independent, testable)
   - `ProcessMonitor` service
   - `GET /api/processes` endpoint
   - SSE `processes` event type
   - Unit tests for parsing
2. **Frontend process monitor**
   - Extend `sse.ts` to handle `processes` events
   - `ProcessPanel` component
   - Wire into StreamPage
3. **Follow mode** (independent, UI-only)
   - Toggle component
   - localStorage persistence
   - Auto-merge + auto-scroll logic
4. **Session heartbeat** (independent, UI-only)
   - Relative time utility
   - Status dot logic
   - Wire into `StreamFold`

## Testing

- Backend: `mvn test` (process parsing, endpoint, SSE broadcast)
- Frontend: `npm test` (components, relative time, status logic)
- E2E: Playwright test for follow mode, process panel, heartbeat updates
- Manual: verify against live launchd service (read-only, no deploy)

## Verification

Before considering done:
- [ ] `mvn test` green
- [ ] `cd frontend && npm test` green
- [ ] `cd frontend && npm run build` green
- [ ] `cd frontend && npm run e2e` green
- [ ] `git diff --check` clean
- [ ] Manual smoke test: process panel shows live processes, follow mode works, heartbeat updates
- [ ] NEXT.md updated with slice 4 shipped, next action = slice 5

## Risks

- **Jar swap**: `mvn package` kills live `:8766` — do NOT run it expecting to test against the live
  service. Verify via tests and read-only checks.
- **Process poll cost**: 1s poll interval is acceptable for local-only use. If it becomes noisy,
  lengthen to 2–5s.
- **Platform differences**: `ps` output format varies (macOS BSD vs. Linux). Parse defensively;
  degrade on failure.

## Open loops

- Process monitor currently assumes macOS/BSD `ps`. Linux support can be added later if needed.
- Process color mapping reuses existing agent colors; if new agents appear, extend the palette.
