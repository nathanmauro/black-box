# Subagent lineage: hook-driven child sessions, nested browse

**Date:** 2026-07-23
**Status:** Approved design
**Builds on:** `2026-07-15-full-auto-board-runner.md` (session_links substrate; its line-252 stretch non-goal "Claude-hook subagent lineage capture" is exactly this spec)

## Goal

When a Claude Code session spawns subagents, Black Box records each subagent as a
real child session linked to its parent, and the browse UI shows sessions as a
lineage tree. Display leads; recall attribution falls out of the same model.

Codex runner workers already create `spawned` links (`WorkerSessionIngest` →
`POST /api/session-links`). This spec adds the Claude-hook path and the nested
UI; runner lineage surfaces in the same views for free.

## What already exists (do not rebuild)

- `session_links` table + `LinkType` (`spawned`/`steered`/`continued`),
  `SessionLinkService`/`SessionLinkRepository`, `POST /api/session-links`,
  `GET /api/sessions/{id}/links` — all in the `workflow` module.
- `DagService.forSession` + `GET /api/dag?sessionId=` read model, and the
  frontend `DagView.tsx` SVG (currently rendered only behind `?task=` in
  `SessionsPage.tsx` and on board cards).
- The event bridge `scripts/hooks/sba-agent-hook.sh` → `POST /api/events`,
  with the never-fail contract (no `set -e`, jq guard, `curl --max-time 3 || true`,
  unconditional `exit 0`).

## Hook ground truth (Claude Code v2.1.196+)

`SubagentStart` and `SubagentStop` hooks fire in the parent session. Payloads
carry `session_id` (the PARENT's id), `agent_id` (unique per spawn),
`agent_type` (agent name), and on stop `last_assistant_message`. No transcript
path, no tool_use_id bridge. Registration: `SubagentStart`/`SubagentStop`
entries in the user's `~/.claude/settings.json` with a wildcard matcher
(registration is user-global, not in-repo — same as the existing hooks).

## Identity (the crux)

Child session identity is derived, since hooks report only the parent's
`session_id`:

```
child client_session_id = "<parent client_session_id>:<agent_id>"
source = claude
```

The composite key is self-describing: the parent key is recoverable from the
child key alone, so lineage survives even if a link write is ever lost. Session
identity remains `(source, client_session_id)`; no change to the upsert.

## Data flow (approach A: dumb hook, event-driven links)

1. **SubagentStart** → hook posts one event under the child key:
   `eventType=SubagentStart`, `role=agent`, metadata
   `{agentId, agentType, parentClientSessionId}`, plus a title seed from
   `agent_type` at a low `title_rank` (upgradeable later).
2. **Ingest (recording)** upserts the child session as usual and stamps
   `spawned_by = parentClientSessionId` (new nullable column, set only when the
   metadata carries it).
3. **`EventRecorded`** (existing application event) → new
   **`SubagentLinkListener` in `workflow`**: on `SubagentStart`/`SubagentStop`
   events whose metadata carries `parentClientSessionId`, resolve parent and
   child session UUIDs via recording's public API and create the `spawned` link
   (`task_id` null). Duplicate-link 409/`DUPLICATE_LINK` is swallowed —
   idempotent by design.
4. **SubagentStop** → hook posts under the same child key:
   `role=assistant`, `text=last_assistant_message`, same metadata.
   `subagentstop` joins the final-event set in `EventIngestService`, so
   `SessionStopped` fires and the existing summary pipeline summarizes the
   child session.

The hook makes exactly one POST per event — the host-turn contract is unchanged.

## Module changes

**recording** (`allowedDependencies = {}` — unchanged):
- `agent_sessions.spawned_by TEXT NULL` (schema.sql) — a list-filter hint, not
  the lineage source of truth (links in `workflow` remain authoritative).
- `AgentSession` record gains nullable `spawnedBy`; `EventIngestRequest` is
  unchanged (parent ref rides in `metadata`).
- `/api/sessions` and session listing default to `WHERE spawned_by IS NULL`;
  additive `includeChildren=true` param restores the flat view. The `memory`
  module's `recentSessions`/`searchSessions` inherit the same default.
- Final-event detection adds `subagentstop`.

**workflow** (`allowedDependencies = "recording"` — unchanged):
- `SubagentLinkListener` (application layer) as above.
- Additive `GET /api/session-links/child-counts?ids=a,b,c` →
  `{sessionId: count}` for the browse rail's expanders.

**runner:** untouched. Worker sessions keep today's visibility (no
`spawned_by`); their links already nest in browse.

**Wire contracts:** `wire-fixtures.json`, `rest-contract-matrix.json`,
`rest-mappings.txt`, `SessionLinkApiContractTest` extended in lockstep with the
new field, param, and endpoint. Architecture ratchet must stay green.

## Hook script changes

`scripts/hooks/sba-agent-hook.sh`:
- Recognize `subagentstart`/`subagentstop` in the role map (`agent` /
  `assistant`-when-text respectively).
- When `agent_id` is present, derive the child key and emit the metadata block;
  all other events are untouched.
- Never-fail contract preserved verbatim.
- `scripts/test-agent-hook.sh` gains SubagentStart/SubagentStop fixture
  payloads.
- README hook-registration docs gain the two new entries.

## UI (browse-led)

- **Browse rail (`SessionsPage.tsx`):** parents only by default; sessions with
  children get an expander showing the child count (one batch
  `child-counts` call for the visible rail). Expanding lazy-loads children via
  existing `GET /api/sessions/{id}/links`; child rows show an `agent_type`
  badge and navigate to the child's session view.
- **Session detail:** `DagView` un-gated from `?task=` — any session with links
  renders its lineage graph.
- **Live:** new children ride the existing `session.updated` SSE frame. A
  dedicated `link.created` frame (following the `task.note` pattern) is a
  stretch, only if expand-refresh feels laggy.

## Edge cases

- **Missed SubagentStart** (hook registered mid-session, crash): SubagentStop
  alone mints the child and the listener links from it — both events carry the
  full metadata.
- **Parent session not yet recorded** when the listener fires: skip linking on
  start, retry on stop; worst case the child stands alone with a
  self-describing key (parent recoverable, link repairable).
- **Hook not registered at all:** nothing changes — subagent events simply
  don't arrive, exactly today's behavior.
- **Nested spawns** (a child key becoming someone's parent) compose naturally:
  keys concatenate, links chain, the DAG walker already handles depth.

## Testing

- Ingest unit tests: child session mint, `spawned_by` stamping, final-event
  detection for `subagentstop`.
- Listener tests: link created from start, from stop-only, duplicate swallowed,
  missing-parent skip.
- Repository/contract tests: `spawned_by` filter default + `includeChildren`,
  new counts endpoint, wire fixtures.
- Hook script: fixture-driven test via `scripts/test-agent-hook.sh` additions.
- Frontend: vitest for the nested rail (expander, lazy children, badge);
  existing DagView tests cover the un-gated render.
- Bar: `mvn -q test` green including module ratchets; `npx vitest run` green;
  no packaging required (if packaging happens, `scripts/deploy-local.sh`).

## Non-goals (v1)

- No MCP tool for links/DAG (explicitly optional per the 2026-07-15 spec).
- No retroactive lineage backfill for historical sessions.
- No changes to runner worker visibility or link creation.
- No dedicated lineage page — browse nesting + session DagView only.
- No `link.created` SSE frame unless UX demands it (stretch).
