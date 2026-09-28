# Ambient Companion Design

Status: concept accepted by Nathan on 2026-09-24 (Black Box Decision `5ce7cce9-3dca-4fc1-b4a0-cf0b40529679`).
This document is the design for the first build slice. It is not a build spec for a pet, mascot,
avatar, or notification system; the Codex Agent Pet only sparked the idea.

## 1. Purpose

A small always-available surface that answers two questions at a glance and one on click:

1. Is agent activity happening right now, and is Black Box reachable?
2. What happened that Nathan would want to know: decisions, handoffs, observations, per project?
3. Click: open the exact Black Box context for an item.

Non-goals for this slice: OS notifications, sounds, importance ranking or judgments, writing back
to Black Box, Constellate/Orbit integration, task-board transitions (the coordination board is
being retired in parallel work), Windows/Linux shells.

## 2. Principle: one companion, one feed, one state, several skins

- The **feed** is Black Box's existing stream (`/api/stream`, browser `EventSource` with native
  reconnect and `Last-Event-ID` replay) plus the existing query feed (`/api/events?q=`).
- The **derived model** (pulse, projects, meaningful items, seen-state) is computed client-side in
  the Black Box web app, in one pure module.
- **Skins** are views over that model. This slice ships two: the `/companion` route rendered in any
  browser tab, and a thin macOS shell that hosts the same route in a floating non-activating panel
  and mirrors pulse plus unseen count into a menubar item. The menubar item is the docked state of
  the same companion, not a second product.

## 3. Signals

### 3.1 Activity (drives pulse only, never rows)

- `event.appended` frames (mostly tool calls) and `session.updated` frames.
- Pulse states: `connecting` (stream not yet open), `live` (an event in the last 120 s), `idle`
  (connected, nothing in 120 s), `disconnected` (stream down). Disconnected must never look like
  quiet.
- A session counts as live when its `lastSeenAt` is within 10 minutes.

### 3.2 Meaningful items (rows)

- Event types `Decision`, `Handoff`, `Observation`. Nothing else becomes a row.
- Backfill query on load and on reconnect: `kind:decision,handoff,observation last:24h`, limit 200.
  (Corrected post-ship: the parser treats a facet as a comma IN-list, not a free-text `OR`; the
  `kind:decision OR kind:handoff OR kind:observation` form this section originally specified would
  parse `OR` as a required free-text term and match nothing.) Live additions: on an
  `event.appended` whose `eventType` is one of the three, fetch `GET /api/events/{id}` for the
  full text and metadata and merge it.
- Headline: first line of `metadata.decision` (decision), `metadata.contextSummary` (handoff), or
  `text` (fallback), trimmed to 120 characters.
- Handoff rows show `metadata.nextAction` and the count of `metadata.openLoops` when present.
- A handoff is a baton, not a checkmark. Nothing in this slice claims completion. Session stops are
  liveness changes, not rows.

### 3.3 Project attribution

- Resolve `cwd` through the existing project catalog (`GET /api/projects`, scopes include
  worktrees) with `findProjectByIdentifier`. Items and sessions whose `cwd` resolves to no project
  fall into one `Unassigned` bucket. Never guess.

### 3.4 Seen-state

- A per-browser set of seen item ids, persisted in `localStorage` (memory fallback when storage is
  unavailable), capped at 2000 ids.
- Opened equals seen: opening a project's expanded view marks that project's listed items seen;
  opening the river marks all listed items seen; an item that arrives while its project view or
  the river is open is marked seen on arrival.
- The unseen count therefore means "meaningful items you have not opened".

## 4. Interaction model

| Level | Shows | Behavior |
|---|---|---|
| Docked (shell only) | Menubar glyph: pulse state plus unseen count. | Panel hidden. Menu: Show/Hide Companion, Open Black Box, Quit. |
| Mini | One chip: pulse dot plus unseen count. | Click expands to Compact. In the shell the panel is 132 x 36 and draggable. |
| Compact | Active projects, one row each: name, live dot, live session count, unseen badge, latest headline, age. Footer shows pulse text. | Click a project for Expanded. "River" button for the cross-project river. Collapse returns to Mini. Panel 340 x 420. |
| Expanded | One project: activity strip (live sessions, last activity, last capture) then its items newest first. Or the river: all items newest first with a project prefix. Each row is a link into Black Box. | Back returns to Compact. Toggle switches project/river. Collapse returns to Mini. Panel 400 x 560, user-resizable in the shell. |

- Escape steps down one level. Mode and last expanded view persist in `localStorage`.
- Active-project membership is the union of "has a live session" and "has a meaningful item in the
  window". Sort: unseen count descending, then last activity descending.
- With one active project, grouped and river views are identical; the toggle still exists but
  only matters with two or more projects.
- Motion: none on tool calls. The chip changes only when pulse state or unseen count changes.
- Deep link per item: `/?view=browse&session=<sessionId>&event=<eventId>[&project=<projectKey>]`,
  opened with `target="_blank"` so the shell hands it to the default browser and a tab opens a new
  tab.
- Empty state text: "Quiet. No active projects in the last 24h."

## 5. Architecture

### 5.1 Black Box web app (frontend/)

- New route `/companion` registered in `frontend/src/index.tsx`, rendered without the utility bar
  or command palette (`App.tsx` gains a bare mode keyed on `location.pathname === "/companion"`).
- New pure modules under `frontend/src/lib/companion/`: `model.ts` (types and `deriveModel`),
  `seen.ts`, `links.ts`, `bridge.ts`, `store.ts` (Solid signals wiring the live store, API calls,
  mode, and seen-state).
- New components under `frontend/src/components/companion/`: `MiniChip`, `CompactList`,
  `ExpandedView`; page `frontend/src/pages/CompanionPage.tsx`; styles in
  `frontend/src/companion.css`.
- `frontend/src/lib/sse.ts` gains an additive `onEventAppended` subscription (mirrors
  `onSessionUpdated`). `frontend/src/lib/api.ts` gains `getEvent(id)`.
- Shell bridge: when `window.webkit.messageHandlers.companion` exists the page posts
  `{type:"state", pulse, unseen}` on model change and `{type:"mode", mode, width, height}` on mode
  change. Absent handler: no-op.

### 5.2 Backend

- `SpaForwardingController` adds `/companion` to its explicit forward list so a direct load or
  hard refresh resolves. No new endpoints. No CORS: the shell loads the page same-origin.

### 5.3 macOS shell (companion/macos/, Swift Package)

- Executable `BlackBoxCompanion`: `NSStatusItem` plus a borderless, non-activating, floating,
  resizable `NSPanel` hosting a `WKWebView` that loads
  `http://127.0.0.1:8766/companion?embedded=1` (override with `--url` or
  `BLACKBOX_COMPANION_URL`).
- Bridge messages resize the panel anchored at its top-right corner and set the menubar title.
- `--self-test <png>` loads the page, waits for `.companion`, writes a snapshot, exits 0. This is
  the scripted use check for the shell.
- Pure, unit-tested pieces: option parsing, status title rendering, bridge message parsing, panel
  geometry.

## 6. Verification

- Vitest for `deriveModel`, seen store, links, bridge, store wiring, and components.
- Playwright e2e against the isolated jar: mini to compact to expanded, link href shape, no
  utility bar, and a live handoff arriving over SSE with its next action rendered.
- `swift test` for the shell's pure pieces and `swift run BlackBoxCompanion --self-test` against
  the e2e server for the real load path.
- After any `mvn package` (including the e2e run) restart the live service:
  `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`.

## 7. Open loops carried into the plan

- Ingest judgments are not available on the running service; importance stays kind-based.
- Result and blocker observation sub-kinds are unused in the live data; all observations are rows.
- Heartbeat watchdog (server hangs without closing the socket) is out of scope; pulse relies on
  `EventSource` open/error.
