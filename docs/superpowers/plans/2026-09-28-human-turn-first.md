# Human turn first

**Status:** in progress, prototype mode, branch `claude/human-turn-first`.

**Origin:** hunting for a forgotten one-line aside across every transcript showed that Black Box
leads with the wrong thing. Session titles such as `<system-reminder>`, `claude RawText`, and
`## Memory Writing Agent: Phase 2` are junk. AI summaries ("Nathan asked whether…") replace the
human's words. The human's own turns are buried among tool calls and harness boilerplate. The one
thing a person most wants back, something they said once, is the hardest thing to find.

**Accepted scope (2026-09-28):** first-turn lead and the human-turn stream together, in one
vertical slice. The toggle ships on REST, MCP, and the frontend at the same time. Sessions always
lead with the human's turn. The human-only filter is off by default and remembers its last state.

## Out of scope

- Semantic or vector indexing of human turns. Recall stays limited to structured intent events.
- Elasticsearch projection of the new field. `humanOnly` search uses the local index only.
- Stripping the stored event `text`. It stays byte-identical, and idempotent capture hashes are
  unaffected.
- ChatGPT Work captures. They arrive as structured `Decision`/`Handoff`/`Observation` events
  written by the assistant, not as human turns.

## What counts as a human turn

`dev.nathan.sbaagentic.recording.HumanTurns` is the single rule set.

- It considers only prompt-shaped events: `UserPromptSubmit`, `beforeSubmitPrompt`, `QuickNote`,
  and `QuickCapture`, normalized through `EventTypes`.
- The stored `role` does not separate human input from machine input, so the rules ignore it.
- It removes harness blocks: `system-reminder`, `task-notification`, relayed `agent-message` and
  `cross-session-message`, `heartbeat`, `scheduled-task`, IDE context, command and shell output,
  and Codex browser/instruction context. It also removes a block that was truncated before its
  closing tag.
- It unwraps what the human typed: `<command-name>`/`<command-args>` become `/cmd args`,
  `<bash-input>` becomes `! cmd`, and pasted content becomes `[pasted]`.
- For Codex attachments, it keeps only the text after `## My request for Codex:`.
- Realtime voice `<realtime_delegation>` yields the `<input>` utterance. A
  `transcript_tail_flush` instead yields the `user:` lines of its transcript delta.
- It rejects automation prompts: Memory Writing Agent, ambient suggestions, title generators,
  `Automation:`, story front matter, MIMIC briefs, and similar. It also rejects long
  second-person briefs and long prompts that open with a markdown heading.

The classification is **heuristic**. Calibrated against the local corpus on 2026-09-28, it kept
5,640 human turns and rejected 3,462 prompts. Short agent-written prompts sent to headless
sessions ("Review Task 6 for spec compliance…") still classify as human. `HumanTurns.VERSION`
versions the rules: bumping it reclassifies stored rows on the next start.

## Contract

### Storage

- `agent_events.human_text TEXT NULL` holds the cleaned human text of a human turn and is null
  for every other event. Ingest computes it from the stored, already redacted and truncated text.
- `agent_sessions.first_human_turn TEXT NULL` holds the `human_text` of the session's earliest
  human turn, by `observed_at` and then `id`.
- A partial index `idx_agent_events_human` on `(observed_at DESC, id DESC) WHERE human_text IS
  NOT NULL` serves the global human stream.
- Startup reclassification runs when the stored classifier version is below
  `HumanTurns.VERSION`. It re-derives `human_text` for every prompt-shaped event, recomputes
  `first_human_turn`, and applies human titles. It is idempotent.
- PostgreSQL gets the same columns and index, using `ADD COLUMN IF NOT EXISTS` for existing
  databases.

### Titles

- `TitleRank.HUMAN = 5`. A human turn titles its session with its first line at `HUMAN`. It
  replaces fallback, tool, text-derived, and client-explicit titles. The AI summary title (`AI =
  100`) still outranks it, so the lead is shown separately, not by overwriting AI titles.
- Non-human text titles pass through `HumanTurns.stripBoilerplate`. Blank results fall through to
  the tool or fallback rule.

### Wire

- `AgentSession.firstHumanTurn` (nullable) is added.
- `AgentEvent.humanText` and `EventFeedItem.humanText` (nullable) are added.
- `humanOnly` (boolean, default `false`) is accepted by:
  - `GET /api/events` and `GET /api/events/facets`, meaning human turns only;
  - `GET /api/sessions/{id}/transcript` and `GET /api/sessions/{id}/events`;
  - `GET /api/search`, which is local only when set;
  - `GET /api/sessions`, meaning only sessions that have a human turn.
- MCP `recentSessions(limit, humanOnly)` returns `firstHumanTurn` on every session.
  `searchSessions(query, limit, humanOnly)` restricts matches to human turns.

### Frontend

- A module-level signal persisted at `localStorage["bb.humanOnly"]` holds the toggle. Reads and
  writes are wrapped in try/catch, and it defaults to off.
- A header toggle button carries `aria-pressed`. The `h` key toggles it when focus is not in an
  editable field. A command palette entry toggles it too.
- The stream, session transcript, session list, and search pass `humanOnly`. In human mode, rows
  render `humanText` verbatim.
- The session header shows the first human turn ahead of the AI summary. Session list rows lead
  with the first human turn and show the stored title as secondary text.

## Verification

- `HumanTurnsTest` covers the rule set. Store and ingest tests cover persistence, the first-turn
  and title ranks, the `humanOnly` feed with keyset paging, and idempotent reclassification. Web
  and MCP tests cover the parameters. Contract snapshots are updated.
- Frontend: vitest for the store, the toggle, the shortcut, and page wiring; `npm run check`;
  Playwright for the stream toggle against the packaged jar.
- Verify through use: run the packaged jar on a spare port against a consistent copy of the local
  database, never the live service or the live database. Hit REST and MCP with `humanOnly`, then
  open the UI. Acceptance: a real one-line aside from a noisy recent session appears within
  seconds of flipping the toggle.
